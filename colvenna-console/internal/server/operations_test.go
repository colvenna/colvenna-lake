package server

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	kubefake "k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/operations"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/pipeline"
)

// fakeKube is the API server as the operations see it: what they ask for, and as whom.
type fakeKube struct {
	dyn *dynamicfake.FakeDynamicClient
	as  []operations.Identity
}

func (f *fakeKube) Clients(id operations.Identity, namespace string) (*ops.Clients, error) {
	f.as = append(f.as, id)
	return &ops.Clients{Dynamic: f.dyn, Core: kubefake.NewClientset(), Namespace: namespace}, nil
}

// changes are the requests that change something.
func (f *fakeKube) changes() []string {
	var out []string
	for _, a := range f.dyn.Actions() {
		if a.GetVerb() != "get" && a.GetVerb() != "list" {
			out = append(out, a.GetVerb()+" "+a.GetResource().Resource)
		}
	}
	return out
}

func operating(t *testing.T) (*Server, *recorder, *fakeKube) {
	t.Helper()
	s, rec, _ := newServer(t)
	table := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "colvenna-lake.io/v1alpha1", "kind": "LakeTable",
		"metadata": map[string]any{"name": "dwd", "namespace": "ns"},
		"spec":     map[string]any{"target": map[string]any{"database": "dwd", "table": "orders"}, "deletionPolicy": "Delete"},
	}}
	kinds := map[schema.GroupVersionResource]string{}
	for _, k := range ops.Kinds {
		kinds[k.GVR()] = k.Kind + "List"
	}
	kube := &fakeKube{dyn: dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(), kinds, table)}
	kube.dyn.PrependReactor("patch", "laketasks", func(a k8stesting.Action) (bool, runtime.Object, error) {
		return true, &unstructured.Unstructured{Object: map[string]any{"apiVersion": "colvenna-lake.io/v1alpha1", "kind": "LakeTask",
			"metadata": map[string]any{"name": a.(k8stesting.PatchAction).GetName(), "namespace": "ns"}}}, nil
	})
	s.ops = Operations{Runner: kube, Namespaces: []string{"ns"},
		Confirm: &operations.Confirmations{Key: []byte("0123456789abcdef0123456789abcdef"), TTL: 5 * time.Minute, Now: s.now}}
	s.pipeline = fakePipeline{objects: pipeline.Objects{Namespaces: []string{"ns"}, Tables: []unstructured.Unstructured{*table}}}
	return s, rec, kube
}

func post(s *Server, path, user string, form url.Values, origin string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", path, strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.Header.Set("X-Test-User", user)
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	return w
}

func TestAnOperationRunsAsThePersonAndIsAudited(t *testing.T) {
	s, rec, kube := operating(t)
	w := post(s, "/operations/tag", "operator", url.Values{"namespace": {"ns"}, "table": {"dwd.orders"},
		"name": {"tag-1"}, "tag": {"t1"}}, publicURL)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "task/tag-1") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	if len(kube.as) != 1 || kube.as[0] != (operations.Identity{As: operations.OperatorUser, Username: "olive", Sub: "s-1"}) {
		t.Fatalf("%+v", kube.as)
	}
	patch := kube.dyn.Actions()[0].(k8stesting.PatchAction)
	if !strings.Contains(string(patch.GetPatch()), "createTag") {
		t.Fatalf("%s", patch.GetPatch())
	}
	var ops []string
	for _, e := range rec.events {
		if e.Kind == "operation" {
			ops = append(ops, e.Action+" "+e.Resource+" "+e.Reason)
		}
	}
	if strings.Join(ops, "|") != "tag ns/LakeTask/tag-1 requested|tag ns/LakeTask/tag-1 task/tag-1" {
		t.Fatalf("%v", ops)
	}
}

func TestOperationsRefusedWhoeverThePageHidThemFrom(t *testing.T) {
	s, _, kube := operating(t)
	form := url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}}
	for name, c := range map[string]struct {
		path, user, origin string
		code               int
	}{
		"another site":         {"/operations/delete", "admin", "https://evil.example", 403},
		"no origin":            {"/operations/delete", "admin", "", 403},
		"an operator deletes":  {"/operations/delete", "operator", publicURL, 403},
		"a BI user":            {"/operations/tag", "bi", publicURL, 403},
		"no role":              {"/operations/tag", "norole", publicURL, 403},
		"no such operation":    {"/operations/rm", "admin", publicURL, 404},
		"another namespace":    {"/operations/suspend", "admin", publicURL, 403},
		"not signed in at all": {"/operations/suspend", "", publicURL, 401},
	} {
		f := form
		if name == "another namespace" {
			f = url.Values{"namespace": {"kube-system"}, "kind": {"LakeTable"}, "name": {"dwd"}}
		}
		if w := post(s, c.path, c.user, f, c.origin); w.Code != c.code {
			t.Fatalf("%s: %d", name, w.Code)
		}
	}
	if ch := kube.changes(); len(ch) != 0 {
		t.Fatalf("changed: %v", ch)
	}
	// The page offers an operator no administrator's operation.
	w := do(s, "GET", "/operations", "operator", nil)
	if w.Code != 200 || strings.Contains(w.Body.String(), "/operations/delete") || !strings.Contains(w.Body.String(), "/operations/tag") {
		t.Fatalf("%d", w.Code)
	}
	// The declaration field's help names the product.
	if !strings.Contains(w.Body.String(), "Colvenna Lake 的资源，与 colvenna apply -f 相同") {
		t.Fatal(w.Body.String())
	}
}

func TestAnIrreversibleOperationNeedsItsOwnConfirmation(t *testing.T) {
	s, rec, kube := operating(t)
	form := url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}}
	w := post(s, "/operations/delete", "admin", form, publicURL)
	body := w.Body.String()
	if w.Code != 200 || !strings.Contains(body, "deletionPolicy Delete") || !strings.Contains(body, "下游受影响") {
		t.Fatalf("%d %s", w.Code, body)
	}
	if ch := kube.changes(); len(ch) != 0 {
		t.Fatalf("a preview changed %v", ch)
	}
	token := between(body, `name="confirmation" value="`, `"`)
	// Another name, another person, a forged or an expired confirmation: nothing happens.
	for name, c := range map[string]struct {
		user string
		form url.Values
	}{
		"another name":   {"admin", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dws"}, "confirmation": {token}}},
		"forged":         {"admin", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}, "confirmation": {"9999999999.x"}}},
		"another person": {"operator", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}, "confirmation": {token}}},
	} {
		if w := post(s, "/operations/delete", c.user, c.form, publicURL); w.Code != http.StatusForbidden {
			t.Fatalf("%s: %d", name, w.Code)
		}
	}
	later := s.now
	s.ops.Confirm.Now = func() time.Time { return later().Add(6 * time.Minute) }
	if w := post(s, "/operations/delete", "admin", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}, "confirmation": {token}}, publicURL); w.Code != http.StatusForbidden {
		t.Fatalf("expired: %d", w.Code)
	}
	if ch := kube.changes(); len(ch) != 0 {
		t.Fatalf("changed: %v", ch)
	}
	s.ops.Confirm.Now = later
	form.Set("confirmation", token)
	// The form the confirmation page sends: with what the preview bound (the object as shown).
	form.Set("uid", between(body, `name="uid" value="`, `"`))
	form.Set("generation", between(body, `name="generation" value="`, `"`))
	if w := post(s, "/operations/delete", "admin", form, publicURL); w.Code != 200 {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	if ch := kube.changes(); strings.Join(ch, ",") != "delete laketables" {
		t.Fatalf("%v", ch)
	}
	if kube.as[len(kube.as)-1].As != operations.AdminUser {
		t.Fatalf("%+v", kube.as)
	}
	var reasons []string
	for _, e := range rec.events {
		if e.Kind == "operation" {
			reasons = append(reasons, e.Reason)
		}
	}
	if reasons[0] != "previewed" || reasons[len(reasons)-1] != "table/dwd deleted" {
		t.Fatalf("%v", reasons)
	}
}

func TestNoAuditNoChangeAndTheSubjectStaysOutOfIt(t *testing.T) {
	s, rec, kube := operating(t)
	rec.fail = true
	if w := post(s, "/operations/tag", "admin", url.Values{"namespace": {"ns"}, "table": {"dwd.orders"}, "name": {"tag-1"}, "tag": {"t1"}}, publicURL); w.Code != http.StatusServiceUnavailable {
		t.Fatal(w.Code)
	}
	if ch := kube.changes(); len(ch) != 0 {
		t.Fatalf("changed without an audit: %v", ch)
	}
	rec.fail = false
	form := url.Values{"namespace": {"ns"}, "name": {"erase-1"}, "values": {"customer-4711"}}
	w := post(s, "/operations/forget", "admin", form, publicURL)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "1 个键值") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	for _, e := range rec.events {
		if strings.Contains(e.Reason+e.Resource+e.Path, "4711") {
			t.Fatalf("the subject is in the audit: %+v", e)
		}
	}
}

func between(s, from, to string) string {
	_, rest, _ := strings.Cut(s, from)
	v, _, _ := strings.Cut(rest, to)
	return v
}

func TestRefusalsAreAuditedWithWhatAndWhichAndAPreviewNeedsItsImpact(t *testing.T) {
	s, rec, kube := operating(t)
	post(s, "/operations/delete", "operator", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}}, publicURL)
	last := rec.events[len(rec.events)-1]
	if last.Kind != "operation" || last.Action != "delete" || last.Resource != "ns/LakeTable/dwd" || last.Status != 403 {
		t.Fatalf("%+v", last)
	}
	// Nothing to delete: no preview, no confirmation.
	w := post(s, "/operations/delete", "admin", url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"nothing"}}, publicURL)
	if w.Code != http.StatusNotFound || strings.Contains(w.Body.String(), "confirmation") {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	// Expiring snapshots cannot be undone: it is confirmed first.
	w = post(s, "/operations/expire", "operator", url.Values{"namespace": {"ns"}, "table": {"dwd.orders"}, "name": {"e1"}}, publicURL)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "time travel") || !strings.Contains(w.Body.String(), `name="confirmation"`) {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	if ch := kube.changes(); len(ch) != 0 {
		t.Fatalf("changed: %v", ch)
	}
}

func TestADeletionConfirmedForWhatWasShownOnly(t *testing.T) {
	s, _, kube := operating(t)
	form := url.Values{"namespace": {"ns"}, "kind": {"LakeTable"}, "name": {"dwd"}}
	body := post(s, "/operations/delete", "admin", form, publicURL).Body.String()
	form.Set("confirmation", between(body, `name="confirmation" value="`, `"`))
	form.Set("uid", between(body, `name="uid" value="`, `"`))
	form.Set("generation", between(body, `name="generation" value="`, `"`))
	// The declaration changes after it was shown: its spec (generation) is not the one confirmed.
	gvr := schema.GroupVersionResource{Group: "colvenna-lake.io", Version: "v1alpha1", Resource: "laketables"}
	obj, _ := kube.dyn.Resource(gvr).Namespace("ns").Get(t.Context(), "dwd", metav1.GetOptions{})
	obj.SetGeneration(obj.GetGeneration() + 1)
	_, _ = kube.dyn.Resource(gvr).Namespace("ns").Update(t.Context(), obj, metav1.UpdateOptions{})
	if w := post(s, "/operations/delete", "admin", form, publicURL); w.Code != http.StatusConflict {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	// A confirmation with the bound fields changed is no confirmation.
	form.Set("generation", "99")
	if w := post(s, "/operations/delete", "admin", form, publicURL); w.Code != http.StatusForbidden {
		t.Fatal(w.Code)
	}
	for _, ch := range kube.changes() {
		if strings.HasPrefix(ch, "delete") {
			t.Fatalf("deleted: %v", kube.changes())
		}
	}
}
