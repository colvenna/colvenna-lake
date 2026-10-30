package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-console/internal/audit"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/identity"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/pipeline"
)

// fakeVerifier knows users by the X-Test-User header: "operator", "norole", or nothing.
type fakeVerifier struct{}

func (fakeVerifier) Verify(r *http.Request) (identity.User, error) {
	switch r.Header.Get("X-Test-User") {
	case "operator":
		return identity.User{Sub: "s-1", Username: "olive", Groups: []string{"colvenna-operator"},
			Roles: []identity.Role{identity.Operator}, SignedIn: time.Unix(1_799_990_000, 0)}, nil
	case "admin":
		return identity.User{Sub: "s-3", Username: "ada", Groups: []string{"colvenna-admin"},
			Roles: []identity.Role{identity.Admin}, SignedIn: time.Unix(1_799_990_200, 0)}, nil
	case "bi":
		return identity.User{Sub: "s-4", Username: "bea", Groups: []string{"colvenna-bi"},
			Roles: []identity.Role{identity.BI}, SignedIn: time.Unix(1_799_990_300, 0)}, nil
	case "norole":
		return identity.User{Sub: "s-2", Username: "nora", Groups: []string{}, Roles: []identity.Role{},
			SignedIn: time.Unix(1_799_990_100, 0)}, nil
	case "":
		return identity.User{}, identity.ErrNoIdentity
	}
	return identity.User{}, errors.New("bad signature")
}

type recorder struct {
	mu     sync.Mutex
	events []audit.Event
	fail   bool
}

func (r *recorder) Record(e audit.Event) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.fail {
		return audit.ErrUnavailable
	}
	r.events = append(r.events, e)
	return nil
}

type signOut struct {
	users []string
	err   error
}

func (s *signOut) SignOut(_ context.Context, username string) error {
	s.users = append(s.users, username)
	return s.err
}

const publicURL = "https://console.colvenna.example"

func newServer(t *testing.T) (*Server, *recorder, *signOut) {
	t.Helper()
	rec, out := &recorder{}, &signOut{}
	s, err := New(Config{
		PublicURL: publicURL, SignInURL: "https://pool.auth.us-west-2.amazoncognito.com",
		ClientID: "client123", SessionCookie: "colvenna-console-auth", Pod: "pod-1",
	}, fakeVerifier{}, rec, out, nil, Operations{})
	if err != nil {
		t.Fatal(err)
	}
	s.now = func() time.Time { return time.Unix(1_800_000_000, 0) }
	return s, rec, out
}

func do(s *Server, method, path, user string, header map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, nil)
	if user != "" {
		r.Header.Set("X-Test-User", user)
	}
	r.Header.Set("X-Forwarded-For", "198.51.100.7, 203.0.113.9")
	for k, v := range header {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	return w
}

func TestHealthNeedsNoIdentityAndIsNotAudited(t *testing.T) {
	s, rec, _ := newServer(t)
	if w := do(s, "GET", "/healthz", "", nil); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if len(rec.events) != 0 {
		t.Fatalf("%+v", rec.events)
	}
}

func TestRequestsWithoutAUserAreRefusedAndAudited(t *testing.T) {
	s, rec, _ := newServer(t)
	for _, path := range []string{"/", "/api/me", "/static/console.css", "/anything"} {
		if w := do(s, "GET", path, "", nil); w.Code != http.StatusUnauthorized {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	if w := do(s, "GET", "/", "forged", nil); w.Code != http.StatusUnauthorized {
		t.Fatal(w.Code)
	}
	if len(rec.events) != 5 {
		t.Fatalf("%d events", len(rec.events))
	}
	last := rec.events[4]
	if last.Kind != "denied" || last.Status != 401 || last.Reason != "bad signature" || last.SourceIP != "203.0.113.9" {
		t.Fatalf("%+v", last)
	}
}

func TestAUserWithoutARoleIsRefused(t *testing.T) {
	s, rec, _ := newServer(t)
	w := do(s, "GET", "/", "norole", nil)
	if w.Code != http.StatusForbidden || !strings.Contains(w.Body.String(), "没有分配角色") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	if len(rec.events) != 2 || rec.events[0].Kind != "signin" || rec.events[0].Username != "nora" {
		t.Fatalf("%+v", rec.events)
	}
	if e := rec.events[1]; e.Kind != "denied" || e.Username != "nora" || e.Reason != "no role" {
		t.Fatalf("%+v", e)
	}
}

func TestAUserWithARoleIsServedAfterTheAudit(t *testing.T) {
	s, rec, _ := newServer(t)
	w := do(s, "GET", "/", "operator", nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "olive") || !strings.Contains(w.Body.String(), "运维") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	// The product's name, in the page's title and header.
	if body := w.Body.String(); !strings.Contains(body, "<title>Colvenna Lake 控制台</title>") ||
		!strings.Contains(body, `<span class="brand">Colvenna Lake 控制台</span>`) || strings.Contains(body, "colvenna-lake 控制台") {
		t.Fatal(body)
	}
	if e := rec.events[0]; e.Kind != "signin" || e.Username != "olive" || e.SignedIn != "2027-01-15T05:13:20Z" {
		t.Fatalf("%+v", e)
	}
	if e := rec.events[1]; e.Kind != "request" || e.Username != "olive" || e.Path != "/" ||
		strings.Join(e.Roles, ",") != "operator" || e.Pod != "pod-1" || e.SignedIn != "2027-01-15T05:13:20Z" {
		t.Fatalf("%+v", e)
	}
	w = do(s, "GET", "/api/me", "operator", nil)
	// The same sign-in again: one more request, no second sign-in.
	if len(rec.events) != 3 || rec.events[2].Kind != "request" {
		t.Fatalf("%+v", rec.events)
	}
	var me identity.User
	if err := json.Unmarshal(w.Body.Bytes(), &me); err != nil || me.Username != "olive" || !me.Has(identity.Operator) {
		t.Fatalf("%v %s", err, w.Body.String())
	}
	if w := do(s, "GET", "/nowhere", "operator", nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
}

func TestNothingIsServedWhenTheAuditIsDown(t *testing.T) {
	s, rec, _ := newServer(t)
	rec.fail = true
	if w := do(s, "GET", "/api/me", "operator", nil); w.Code != http.StatusServiceUnavailable || strings.Contains(w.Body.String(), "olive") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
}

func TestEveryResponseForbidsCachingAndFraming(t *testing.T) {
	s, _, _ := newServer(t)
	for _, user := range []string{"", "operator"} {
		h := do(s, "GET", "/", user, nil).Header()
		if h.Get("Cache-Control") != "no-store" || h.Get("X-Frame-Options") != "DENY" ||
			!strings.Contains(h.Get("Content-Security-Policy"), "frame-ancestors 'none'") {
			t.Fatalf("%v", h)
		}
	}
}

func TestLogoutEndsTheSessionsThenTheSignOutPageDropsTheCookie(t *testing.T) {
	s, rec, out := newServer(t)
	w := do(s, "POST", "/logout", "operator", map[string]string{"Origin": publicURL})
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/signed-out" {
		t.Fatal(w.Code, w.Header().Get("Location"))
	}
	// Behind the authenticate action the load balancer may set the cookie again: not here.
	if len(w.Result().Cookies()) != 0 {
		t.Fatalf("%v", w.Result().Cookies())
	}
	if strings.Join(out.users, ",") != "olive" {
		t.Fatalf("%v", out.users)
	}
	if e := rec.events[len(rec.events)-1]; e.Kind != "logout" || e.Username != "olive" || e.Reason != "signed out at the user pool" {
		t.Fatalf("%+v", e)
	}
	// The sign-out page, signed in like every other, drops the cookie and goes to the user
	// pool's sign-out; without a user it is refused.
	if w := do(s, "GET", "/signed-out", "", nil); w.Code != http.StatusUnauthorized {
		t.Fatal(w.Code)
	}
	w = do(s, "GET", "/signed-out", "operator", nil)
	if w.Code != http.StatusSeeOther {
		t.Fatal(w.Code)
	}
	target, err := url.Parse(w.Header().Get("Location"))
	if err != nil || target.Host != "pool.auth.us-west-2.amazoncognito.com" || target.Path != "/logout" ||
		target.Query().Get("client_id") != "client123" || target.Query().Get("logout_uri") != publicURL+"/" {
		t.Fatalf("%v %v", target, err)
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 4 {
		t.Fatalf("%v", cookies)
	}
	for i, c := range cookies {
		if c.Name != "colvenna-console-auth-"+string(rune('0'+i)) || c.MaxAge >= 0 || !c.Secure || c.Path != "/" {
			t.Fatalf("%+v", c)
		}
	}

}

func TestLogoutGoesOnWhenTheUserPoolFails(t *testing.T) {
	s, rec, out := newServer(t)
	out.err = errors.New("throttled")
	if w := do(s, "POST", "/logout", "operator", map[string]string{"Origin": publicURL}); w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/signed-out" {
		t.Fatal(w.Code)
	}
	if e := rec.events[len(rec.events)-1]; !strings.Contains(e.Reason, "throttled") {
		t.Fatalf("%+v", e)
	}
}

func TestLogoutFromAnotherOriginOrByGetIsRefused(t *testing.T) {
	s, _, out := newServer(t)
	if w := do(s, "POST", "/logout", "operator", map[string]string{"Origin": "https://evil.example"}); w.Code != http.StatusForbidden {
		t.Fatal(w.Code)
	}
	if w := do(s, "POST", "/logout", "operator", nil); w.Code != http.StatusForbidden {
		t.Fatal(w.Code)
	}
	if w := do(s, "GET", "/logout", "operator", nil); w.Code != http.StatusMethodNotAllowed {
		t.Fatal(w.Code)
	}
	if len(out.users) != 0 {
		t.Fatalf("signed out %v", out.users)
	}
}

func TestConcurrentFirstRequestsRecordOneSignIn(t *testing.T) {
	s, rec, _ := newServer(t)
	var wg sync.WaitGroup
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			do(s, "GET", "/api/me", "operator", nil)
		}()
	}
	wg.Wait()
	signIns := 0
	for _, e := range rec.events {
		if e.Kind == "signin" {
			signIns++
		}
	}
	if signIns != 1 {
		t.Fatalf("%d sign-ins recorded", signIns)
	}
}

type fakePipeline struct {
	err     error
	objects pipeline.Objects
}

func (f fakePipeline) Read(context.Context) (*pipeline.Pipeline, error) {
	if f.err != nil {
		return nil, f.err
	}
	p := pipeline.Build(f.objects, time.Unix(1_800_000_000, 0))
	return p, nil
}

type objectsKube struct{ o pipeline.Objects }

func (k objectsKube) Read(context.Context) (pipeline.Objects, error) { return k.o, nil }

type noPrometheus struct{}

func (noPrometheus) Query(context.Context, string) ([]pipeline.Sample, error) { return nil, nil }
func (noPrometheus) Alerts(context.Context) ([]pipeline.Alert, error)         { return nil, nil }

func TestANodesPageShowsItsStatus(t *testing.T) {
	s, _, _ := newServer(t)
	table := unstructured.Unstructured{Object: map[string]any{
		"metadata": map[string]any{"name": "dwd", "namespace": "ns"},
		"spec":     map[string]any{"target": map[string]any{"database": "dwd", "table": "orders"}, "upstream": []any{"ods.orders"}},
		"status": map[string]any{"phase": "Degraded", "job": map[string]any{"deployment": "dwd-table", "jobId": "f1"},
			"upstream": []any{map[string]any{"table": "ods.orders", "lag": int64(3), "lagSeconds": int64(400)}}},
	}}
	board := unstructured.Unstructured{Object: map[string]any{
		"metadata": map[string]any{"name": "board", "namespace": "ns"},
		"spec":     map[string]any{"tables": []any{"dwd.orders"}},
		"status":   map[string]any{"phase": "Ready", "queries": []any{map[string]any{"name": "totals", "serve": "materialized", "refreshAgeSeconds": int64(30)}}},
	}}
	// Read as the console reads, so where the problem is is located.
	s.pipeline = &pipeline.Reader{Kubernetes: objectsKube{pipeline.Objects{Namespaces: []string{"ns"},
		Tables: []unstructured.Unstructured{table}, Dashboards: []unstructured.Unstructured{board}}},
		Prometheus: noPrometheus{}, Now: time.Now}
	for path, want := range map[string]string{
		"/pipeline/node?id=job:ns/dwd":            "6m40s",
		"/pipeline/node?id=job:ns/dwd&jobs":       "dwd-table · Flink job f1",
		"/pipeline/node?id=dashboard:ns/board":    "30s",
		"/pipeline/node?id=dashboard:ns/board&by": "受谁影响</dt><dd><a href=\"/pipeline/node?id=job%3ans%2fdwd\">分层作业 ns/dwd</a>",
		"/pipeline/node?id=table:dwd.orders":      ">ns/board</a>",
	} {
		w := do(s, "GET", path, "operator", nil)
		if w.Code != 200 || !strings.Contains(w.Body.String(), want) {
			t.Fatalf("%s: %d %s", path, w.Code, w.Body.String())
		}
	}
	if w := do(s, "GET", "/pipeline/node?id=nothing", "operator", nil); w.Code != http.StatusNotFound {
		t.Fatal(w.Code)
	}
}

func TestThePipelineIsForAdministratorsAndOperators(t *testing.T) {
	s, _, _ := newServer(t)
	s.pipeline = fakePipeline{}
	for _, path := range []string{"/pipeline", "/api/pipeline"} {
		if w := do(s, "GET", path, "operator", nil); w.Code != 200 {
			t.Fatalf("%s: %d", path, w.Code)
		}
		if w := do(s, "GET", path, "norole", nil); w.Code != http.StatusForbidden {
			t.Fatalf("%s: %d", path, w.Code)
		}
		if w := do(s, "GET", path, "", nil); w.Code != http.StatusUnauthorized {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	// Read-only: nothing but GET.
	for _, method := range []string{"POST", "PUT", "DELETE", "PATCH"} {
		if w := do(s, method, "/api/pipeline", "operator", nil); w.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: %d", method, w.Code)
		}
	}
	s.pipeline = fakePipeline{err: errors.New("forbidden")}
	if w := do(s, "GET", "/pipeline", "operator", nil); w.Code != http.StatusServiceUnavailable {
		t.Fatal(w.Code)
	}
	if w := do(s, "GET", "/pipeline/node?id=nothing", "operator", nil); w.Code != http.StatusServiceUnavailable {
		t.Fatal(w.Code)
	}
}
