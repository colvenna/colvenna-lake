// Package server is the console's HTTP surface. Every path but /healthz needs a verified user with
// a role, and every request of a user is audited before it is served.
package server

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"html/template"
	"io/fs"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/colvenna/colvenna-lake/colvenna-console/internal/audit"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/identity"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/pipeline"
)

//go:embed templates/*.html static/*
var assets embed.FS

// Config is what the console knows of the entry it sits behind.
type Config struct {
	// PublicURL is the console's address at the entry, e.g. https://console.colvenna.example.
	PublicURL string
	// SignInURL is the user pool's sign-in domain, e.g. https://<prefix>.auth.<region>.amazoncognito.com.
	SignInURL string
	ClientID  string
	// SessionCookie is the load balancer's session cookie name for the console; the load
	// balancer shards it into <name>-0 to <name>-3.
	SessionCookie string
	Pod           string
}

// Verifier tells a request's user.
type Verifier interface {
	Verify(*http.Request) (identity.User, error)
}

// SignOut ends a user's sessions at the user pool: their refresh tokens stop working.
type SignOut interface {
	SignOut(ctx context.Context, username string) error
}

// Pipeline reads the pipeline's picture afresh.
type Pipeline interface {
	Read(ctx context.Context) (*pipeline.Pipeline, error)
}

// Server serves the console.
type Server struct {
	cfg      Config
	verifier Verifier
	audit    audit.Recorder
	signOut  SignOut
	pipeline Pipeline
	ops      Operations
	now      func() time.Time
	pages    *template.Template

	// signIns are the sign-ins (user and sign-in time) this pod has audited.
	mu      sync.Mutex
	signIns map[string]time.Time
}

// New is a console server.
func New(cfg Config, v Verifier, a audit.Recorder, s SignOut, p Pipeline, o Operations) (*Server, error) {
	pages, err := template.New("").Funcs(template.FuncMap{
		"kindName": pipeline.KindName,
		"label":    pipeline.Label,
		"count":    pipeline.Count,
		"seconds":  pipeline.Seconds,
		"measure":  pipeline.Show,
	}).ParseFS(assets, "templates/*.html")
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, verifier: v, audit: a, signOut: s, pipeline: p, ops: o, now: time.Now,
		pages: pages, signIns: map[string]time.Time{}}, nil
}

type userKey struct{}

func userOf(r *http.Request) identity.User {
	u, _ := r.Context().Value(userKey{}).(identity.User)
	return u
}

// Handler is the console's routes.
func (s *Server) Handler() http.Handler {
	static, err := fs.Sub(assets, "static")
	if err != nil {
		panic(err)
	}
	signedIn := http.NewServeMux()
	signedIn.HandleFunc("GET /{$}", s.home)
	signedIn.HandleFunc("GET /api/me", s.me)
	signedIn.HandleFunc("POST /logout", s.logout)
	signedIn.HandleFunc("GET /signed-out", s.signedOut)
	signedIn.HandleFunc("GET /pipeline", s.operators(s.pipelinePage))
	signedIn.HandleFunc("GET /pipeline/node", s.operators(s.nodePage))
	signedIn.HandleFunc("GET /api/pipeline", s.operators(s.pipelineJSON))
	signedIn.HandleFunc("GET /operations", s.operators(s.operationsPage))
	signedIn.HandleFunc("POST /operations/{op}", s.operators(s.operate))
	signedIn.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(static)))

	mux := http.NewServeMux()
	// For the load balancer's health checks and the kubelet: says nothing about anyone.
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.Handle("/", s.authenticate(signedIn))
	return s.headers(mux)
}

// headers are on every response: nothing is cached, framed, sniffed or sent on as a referrer.
func (s *Server) headers(next http.Handler) http.Handler {
	csp := "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'; " +
		"form-action 'self' " + s.cfg.SignInURL
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("Cache-Control", "no-store")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Strict-Transport-Security", "max-age=31536000")
		next.ServeHTTP(w, r)
	})
}

// authenticate lets through a verified user with a role, after auditing the request.
func (s *Server) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, err := s.verifier.Verify(r)
		if err != nil {
			s.deny(w, r, identity.User{}, http.StatusUnauthorized, err.Error(), "没有通过登录。")
			return
		}
		if err := s.auditSignIn(user, r); err != nil {
			http.Error(w, "审计暂不可用，请稍后再试。", http.StatusServiceUnavailable)
			return
		}
		if len(user.Roles) == 0 {
			s.deny(w, r, user, http.StatusForbidden, "no role", "你的账号还没有分配角色，请联系平台管理员。")
			return
		}
		if err := s.audit.Record(s.event("request", user, r, 0, "")); err != nil {
			http.Error(w, "审计暂不可用，请稍后再试。", http.StatusServiceUnavailable)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey{}, user)))
	})
}

// auditSignIn records a sign-in the first time this pod sees it: the user pool logs the password
// step, and only a completed sign-in yields the access token whose sign-in time this is. Another
// pod, or this one after a restart, may record the same sign-in once more.
func (s *Server) auditSignIn(user identity.User, r *http.Request) error {
	key := user.Sub + "|" + user.SignedIn.UTC().Format(time.RFC3339)
	// Held while recording (Record only queues), so concurrent first requests record it once.
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, seen := s.signIns[key]; seen {
		return nil
	}
	if err := s.audit.Record(s.event("signin", user, r, 0, "")); err != nil {
		return err
	}
	if len(s.signIns) >= 10000 {
		// Sessions last at most a day; older sign-ins will not come back.
		for k, at := range s.signIns {
			if s.now().Sub(at) > 24*time.Hour {
				delete(s.signIns, k)
			}
		}
	}
	s.signIns[key] = user.SignedIn
	return nil
}

// deny refuses the request and audits the refusal; the refusal stands even if the audit fails.
func (s *Server) deny(w http.ResponseWriter, r *http.Request, user identity.User, status int, reason, message string) {
	_ = s.audit.Record(s.event("denied", user, r, status, reason))
	s.page(w, status, "message", map[string]any{"Message": message, "User": user})
}

func (s *Server) event(kind string, user identity.User, r *http.Request, status int, reason string) audit.Event {
	roles := make([]string, 0, len(user.Roles))
	for _, role := range user.Roles {
		roles = append(roles, string(role))
	}
	signedIn := ""
	if !user.SignedIn.IsZero() {
		signedIn = user.SignedIn.UTC().Format(time.RFC3339)
	}
	return audit.Event{
		Time: s.now().UTC(), Kind: kind, Sub: user.Sub, Username: user.Username, Roles: roles,
		SignedIn: signedIn, Method: r.Method, Path: r.URL.Path, Status: status, Reason: reason,
		SourceIP: clientIP(r), Agent: r.UserAgent(), TraceID: r.Header.Get("X-Amzn-Trace-Id"),
		Pod: s.cfg.Pod,
	}
}

// clientIP is the address the load balancer saw: it appends it to X-Forwarded-For, after whatever
// the client sent.
func clientIP(r *http.Request) string {
	hops := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	return strings.TrimSpace(hops[len(hops)-1])
}

func (s *Server) home(w http.ResponseWriter, r *http.Request) {
	user := userOf(r)
	s.page(w, http.StatusOK, "home", map[string]any{"User": user, "Operates": operates(user)})
}

// operates: platform administrators and operators, who see the pipeline.
func operates(user identity.User) bool {
	return user.Has(identity.Admin) || user.Has(identity.Operator)
}

// operators lets through platform administrators and operators.
func (s *Server) operators(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := userOf(r)
		if !operates(user) {
			s.deny(w, r, user, http.StatusForbidden, "not an administrator or operator", "流水线与操作只对平台管理员与运维开放。")
			return
		}
		next(w, r)
	}
}

// unavailable is the page saying why the pipeline is not to be had.
func (s *Server) unavailable(w http.ResponseWriter, r *http.Request, err error) {
	s.page(w, http.StatusServiceUnavailable, "message", map[string]any{"Message": "读不到流水线：" + err.Error(), "User": userOf(r)})
}

func (s *Server) pipelinePage(w http.ResponseWriter, r *http.Request) {
	p, err := s.pipeline.Read(r.Context())
	if err != nil {
		s.unavailable(w, r, err)
		return
	}
	var problems []*pipeline.Node
	for _, n := range p.Nodes {
		if n.Problem {
			problems = append(problems, n)
		}
	}
	s.page(w, http.StatusOK, "pipeline", map[string]any{"User": userOf(r), "Pipeline": p,
		"Drawing": pipeline.SVG(p), "Problems": problems})
}

func (s *Server) nodePage(w http.ResponseWriter, r *http.Request) {
	p, err := s.pipeline.Read(r.Context())
	if err != nil {
		s.unavailable(w, r, err)
		return
	}
	n := p.Node(r.URL.Query().Get("id"))
	if n == nil {
		s.page(w, http.StatusNotFound, "message", map[string]any{"Message": "流水线里没有这一项。", "User": userOf(r)})
		return
	}
	var from, to []*pipeline.Node
	for _, e := range p.Edges {
		if e.To == n.ID {
			from = append(from, p.Node(e.From))
		}
		if e.From == n.ID {
			to = append(to, p.Node(e.To))
		}
	}
	var by []*pipeline.Node
	for _, id := range n.AffectedBy {
		by = append(by, p.Node(id))
	}
	s.page(w, http.StatusOK, "node", map[string]any{"User": userOf(r), "Node": n, "From": from, "To": to,
		"AffectedBy": by, "At": p.At})
}

func (s *Server) pipelineJSON(w http.ResponseWriter, r *http.Request) {
	p, err := s.pipeline.Read(r.Context())
	if err != nil {
		http.Error(w, "读不到流水线："+err.Error(), http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(p)
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(userOf(r))
}

// logout ends the user's sessions everywhere, then sends the browser to the sign-out page. When
// the access token has expired, the load balancer refreshes the session on this request and sets
// the cookie again in this very response, after the console's headers; on the next request the
// token is fresh, nothing is refreshed, and the cookie the console drops stays dropped.
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	user := userOf(r)
	if r.Header.Get("Origin") != s.cfg.PublicURL {
		s.deny(w, r, user, http.StatusForbidden, "logout from another origin", "请从控制台页面退出登录。")
		return
	}
	reason := "signed out at the user pool"
	if err := s.signOut.SignOut(r.Context(), user.Username); err != nil {
		reason = "user pool sign-out failed: " + err.Error()
	}
	_ = s.audit.Record(s.event("logout", user, r, http.StatusSeeOther, reason))
	http.Redirect(w, r, "/signed-out", http.StatusSeeOther)
}

// signedOut drops the load balancer's session cookie and sends the browser to the user pool's
// sign-out, which returns to the console and so to sign-in. Like every path but /healthz, it is
// signed in first.
func (s *Server) signedOut(w http.ResponseWriter, r *http.Request) {
	for i := range 4 {
		http.SetCookie(w, &http.Cookie{
			Name: fmt.Sprintf("%s-%d", s.cfg.SessionCookie, i), Value: "", Path: "/", MaxAge: -1,
			Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode,
		})
	}
	target := s.cfg.SignInURL + "/logout?" + url.Values{
		"client_id": {s.cfg.ClientID}, "logout_uri": {s.cfg.PublicURL + "/"},
	}.Encode()
	http.Redirect(w, r, target, http.StatusSeeOther)
}

func (s *Server) page(w http.ResponseWriter, status int, name string, data map[string]any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_ = s.pages.ExecuteTemplate(w, name, data)
}
