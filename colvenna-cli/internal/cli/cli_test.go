package cli

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	authv1 "k8s.io/api/authentication/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	kubefake "k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
	"sigs.k8s.io/yaml"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func source(name, phase string, tables ...map[string]any) *unstructured.Unstructured {
	var ts []any
	for _, t := range tables {
		ts = append(ts, t)
	}
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": ops.Group + "/" + ops.Version,
		"kind":       "LakeSource",
		"metadata":   map[string]any{"name": name, "namespace": "colvenna-data", "creationTimestamp": "2026-10-15T00:00:00Z"},
		"spec":       map[string]any{"type": "mysql", "target": map[string]any{"database": "ods_shop"}},
		"status": map[string]any{
			"phase": phase, "summary": "1/2", "message": "1 table(s) snapshotting",
			"conditions": []any{map[string]any{"type": "Ready", "status": map[bool]string{true: "True", false: "False"}[phase == "Running"]}},
			"tables":     ts,
		},
	}}
}

func layered(name, phase string) *unstructured.Unstructured {
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": ops.Group + "/" + ops.Version,
		"kind":       "LakeTable",
		"metadata":   map[string]any{"name": name, "namespace": "colvenna-data", "creationTimestamp": "2026-10-15T00:00:00Z"},
		"spec":       map[string]any{"target": map[string]any{"database": "dwd_shop", "table": "orders"}},
		"status": map[string]any{
			"phase": phase, "message": "streaming [ods_shop.orders]",
			"validation": map[string]any{"streams": []any{"ods_shop.orders"}, "lookups": []any{"ods_shop.customers"}},
			"job":        map[string]any{"deployment": name + "-table", "state": "RUNNING", "restoredFrom": "s3://b/savepoint-1"},
			"upstream":   []any{map[string]any{"table": "ods_shop.orders", "nextSnapshot": int64(8), "latestSnapshot": int64(9), "lag": int64(2)}},
		},
	}}
}

func task(name, phase string) *unstructured.Unstructured {
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": ops.Group + "/" + ops.Version,
		"kind":       "LakeTask",
		"metadata":   map[string]any{"name": name, "namespace": "colvenna-data", "creationTimestamp": "2026-10-15T00:00:00Z"},
		"spec":       map[string]any{"type": "backfill", "table": map[string]any{"database": "dwd_shop", "table": "orders"}},
		"status": map[string]any{
			"phase": phase, "message": "waiting for [orders-daily (reads 11, backfill committed 12)]",
			"attempts": int64(2), "run": name + "-run-2",
			"fence":      map[string]any{"lakeTable": "orders-wide", "fencedAt": "2026-10-15T00:00:01Z", "releasedAt": "2026-10-15T00:03:00Z"},
			"result":     map[string]any{"snapshotBefore": int64(9), "snapshotAfter": int64(12), "detail": "upserted 40 rows, deleted 2 no longer derived"},
			"waitingFor": []any{"orders-daily (reads 11, backfill committed 12)"},
		},
	}}
}

func fakes(objects ...runtime.Object) (*ops.Clients, *dynamicfake.FakeDynamicClient) {
	dyn := dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(),
		map[schema.GroupVersionResource]string{
			{Group: ops.Group, Version: ops.Version, Resource: "lakesources"}:    "LakeSourceList",
			{Group: ops.Group, Version: ops.Version, Resource: "laketables"}:     "LakeTableList",
			{Group: ops.Group, Version: ops.Version, Resource: "laketasks"}:      "LakeTaskList",
			{Group: ops.Group, Version: ops.Version, Resource: "lakeerasures"}:   "LakeErasureList",
			{Group: ops.Group, Version: ops.Version, Resource: "lakedashboards"}: "LakeDashboardList",
			{Group: ops.Group, Version: ops.Version, Resource: "lakequalities"}:  "LakeQualityList",
			{Group: ops.Group, Version: ops.Version, Resource: "lakewaivers"}:    "LakeWaiverList",
		}, objects...)
	return &ops.Clients{Dynamic: dyn, Core: kubefake.NewClientset(), Namespace: "colvenna-data"}, dyn
}

func runWith(c *ops.Clients, args ...string) (int, string, string) {
	var out, errOut bytes.Buffer
	code := run(args, &out, &errOut, func(*options) (*ops.Clients, error) { return c, nil })
	return code, out.String(), errOut.String()
}

func TestHelpNamesTheProduct(t *testing.T) {
	c, _ := fakes()
	code, out, errOut := runWith(c, "--help")
	if code != 0 || !strings.Contains(out, "Declare and inspect Colvenna Lake sources") || strings.Contains(out, "colvenna-lake") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
}

func TestKindsHaveShortNames(t *testing.T) {
	for _, name := range []string{"source", "ls", "LakeSource", "lakesources"} {
		if k, err := ops.LookupKind(name); err != nil || k.Resource != "lakesources" {
			t.Fatalf("%s: %v %v", name, k, err)
		}
	}
	if _, err := ops.LookupKind("ConfigMap"); err == nil {
		t.Fatal("ConfigMap is no colvenna kind")
	}
}

func TestGetPrintsPhaseAndReadiness(t *testing.T) {
	c, _ := fakes(source("shop", "Provisioning"))
	code, out, errOut := runWith(c, "get", "sources")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"NAME", "shop", "mysql", "ods_shop", "Provisioning", "1/2"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}

func TestLakeTablesShowTheirTargetJobAndUpstreamProgress(t *testing.T) {
	if k, err := ops.LookupKind("lt"); err != nil || k.Resource != "laketables" {
		t.Fatalf("lt: %v %v", k, err)
	}
	c, _ := fakes(layered("orders-wide", "Running"))
	code, out, errOut := runWith(c, "get", "tables")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"TARGET", "orders-wide", "dwd_shop.orders", "Running"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	code, out, errOut = runWith(c, "describe", "table", "orders-wide")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"ods_shop.customers", "savepoint-1", "UPSTREAM", "ods_shop.orders  8"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}

func TestLakeTasksShowTheirRunFenceAndReaders(t *testing.T) {
	if k, err := ops.LookupKind("task"); err != nil || k.Resource != "laketasks" {
		t.Fatalf("task: %v %v", k, err)
	}
	c, _ := fakes(task("orders-backfill", "Converging"))
	code, out, errOut := runWith(c, "get", "tasks")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"ATTEMPTS", "orders-backfill", "backfill", "dwd_shop.orders", "Converging"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	code, out, errOut = runWith(c, "describe", "task", "orders-backfill")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"orders-backfill-run-2", "orders-wide", "9 -> 12", "deleted 2", "orders-daily (reads 11"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}

func TestBatchCommandsApplyALakeTaskAndWaitForItsEnd(t *testing.T) {
	done := task("eod-1", "Succeeded")
	failed := task("bf-1", "Failed")
	failed.Object["status"].(map[string]any)["message"] = "the LakeTable's declaration is not applied"
	c, dyn := fakes(done, failed)
	var patched []string
	dyn.PrependReactor("patch", "laketasks", func(a k8stesting.Action) (bool, runtime.Object, error) {
		p := a.(k8stesting.PatchAction)
		patched = append(patched, string(p.GetPatch()))
		if p.GetName() == "bf-1" {
			return true, failed, nil
		}
		return true, done, nil
	})
	code, out, errOut := runWith(c, "maintain", "tag", "ods_shop.orders", "--tag", "eod-20261014", "--name", "eod-1", "--wait")
	if code != 0 || !strings.Contains(out, "task/eod-1 applied") || !strings.Contains(out, "phase=Succeeded") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	for _, want := range []string{"kind: LakeTask", "type: createTag", "database: ods_shop", "name: eod-20261014"} {
		if !strings.Contains(patched[0], want) {
			t.Fatalf("missing %q in\n%s", want, patched[0])
		}
	}
	code, _, errOut = runWith(c, "backfill", "dwd_shop.orders", "--name", "bf-1", "--wait")
	if code != 1 || !strings.Contains(errOut, "not applied") {
		t.Fatalf("a failed task fails the command: %d %s", code, errOut)
	}
	if code, _, errOut := runWith(c, "maintain", "compact", "orders", "--name", "x"); code != 1 || !strings.Contains(errOut, "<database>.<table>") {
		t.Fatalf("%d %s", code, errOut)
	}
	if code, _, _ := runWith(c, "maintain", "expire", "ods_shop.orders"); code != 1 {
		t.Fatal("--name is required")
	}
}

func TestErasuresShowEachCopyWithItsStateAndEvidence(t *testing.T) {
	e := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": ops.Group + "/" + ops.Version,
		"kind":       "LakeErasure",
		"metadata":   map[string]any{"name": "erase-1", "namespace": "colvenna-data", "creationTimestamp": "2026-10-15T00:00:00Z"},
		"status": map[string]any{
			"phase": "SlaBreach", "message": "physical deadline passed; not done: Files ods.orders",
			"logicallyErasedAt": "2026-10-15T00:05:00Z", "logicalDeadline": "2026-10-15T00:30:00Z",
			"copies": []any{
				map[string]any{"kind": "Rows", "name": "ods.orders", "layer": "Logical", "state": "Done", "evidence": "no row of the subject in snapshot 10"},
				map[string]any{"kind": "Files", "name": "ods.orders", "layer": "Physical", "state": "Pending", "message": "orphan-file cleaning at 2026-10-16"},
			},
		},
	}}
	c, _ := fakes(e)
	code, out, errOut := runWith(c, "get", "erasures")
	if code != 0 || !strings.Contains(out, "SlaBreach") || !strings.Contains(out, "2026-10-15T00:05:00Z") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	code, out, errOut = runWith(c, "describe", "erasure", "erase-1")
	if code != 0 {
		t.Fatal(errOut)
	}
	for _, want := range []string{"snapshot 10", "orphan-file cleaning", "Physical", "Files"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}

func TestApplyServerSideAppliesColvennaKindsOnly(t *testing.T) {
	c, dyn := fakes()
	var patched []k8stesting.PatchAction
	dyn.PrependReactor("patch", "lakesources", func(a k8stesting.Action) (bool, runtime.Object, error) {
		patched = append(patched, a.(k8stesting.PatchAction))
		return true, source("shop", "Pending"), nil
	})
	dir := t.TempDir()
	good := filepath.Join(dir, "shop.yaml")
	if err := os.WriteFile(good, []byte("apiVersion: colvenna-lake.io/v1alpha1\nkind: LakeSource\nmetadata: {name: shop}\nspec: {type: mysql}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	code, out, errOut := runWith(c, "apply", "-f", good)
	if code != 0 || !strings.Contains(out, "source/shop applied") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	if len(patched) != 1 || string(patched[0].GetPatchType()) != "application/apply-patch+yaml" ||
		patched[0].GetNamespace() != "colvenna-data" || !strings.Contains(string(patched[0].GetPatch()), "namespace: colvenna-data") {
		t.Fatalf("unexpected patch %+v", patched)
	}
	bad := filepath.Join(dir, "cm.yaml")
	if err := os.WriteFile(bad, []byte("apiVersion: v1\nkind: ConfigMap\nmetadata: {name: x}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if code, _, errOut := runWith(c, "apply", "-f", bad); code != 1 || !strings.Contains(errOut, "unknown kind") {
		t.Fatalf("%d %s", code, errOut)
	}
}

func TestSuspendPatchesTheDeclaration(t *testing.T) {
	c, dyn := fakes(source("shop", "Running"))
	if code, out, errOut := runWith(c, "suspend", "source", "shop"); code != 0 || !strings.Contains(out, "source/shop suspended") {
		t.Fatal(out, errOut)
	}
	obj, err := dyn.Resource(ops.Kinds[0].GVR()).Namespace("colvenna-data").Get(t.Context(), "shop", metav1.GetOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if suspend, _, _ := unstructured.NestedBool(obj.Object, "spec", "suspend"); !suspend {
		t.Fatal("not suspended")
	}
}

func TestWaitExitCodes(t *testing.T) {
	ready := map[string]any{"name": "orders", "state": "Ready"}
	failed := map[string]any{"name": "invoices", "state": "Failed", "message": "Cannot convert field"}
	c, _ := fakes(source("shop", "Running", ready, failed))
	if code, out, errOut := runWith(c, "wait", "source", "shop", "--for", "table/orders=Ready"); code != 0 {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	if code, _, _ := runWith(c, "wait", "source", "shop", "--for", "ready"); code != 0 {
		t.Fatal("ready")
	}
	if code, _, errOut := runWith(c, "wait", "source", "shop", "--for", "table/invoices=Ready"); code != 1 || !strings.Contains(errOut, "Cannot convert field") {
		t.Fatalf("a failed table cannot become ready: %d %s", code, errOut)
	}
	rejected := map[string]any{"name": "no_key", "state": "Rejected", "message": "no primary key"}
	c2, _ := fakes(source("shop", "Degraded", rejected))
	if code, _, _ := runWith(c2, "wait", "source", "shop", "--for", "table/no_key=Ready", "--timeout", "200ms", "--interval", "50ms"); code != 2 {
		t.Fatalf("a rejected table may still be admitted: wait until the timeout, not %d", code)
	}
	if code, _, _ := runWith(c, "wait", "source", "shop", "--for", "phase=Suspended", "--timeout", "300ms", "--interval", "50ms"); code != 2 {
		t.Fatalf("timeout exits 2, not %d", code)
	}
	if _, err := parseCondition("phase"); err == nil {
		t.Fatal("phase needs a value")
	}
}

func TestAge(t *testing.T) {
	created := time.Date(2026, 10, 15, 0, 0, 0, 0, time.UTC)
	for d, want := range map[time.Duration]string{30 * time.Second: "30s", 5 * time.Minute: "5m", 3 * time.Hour: "3h", 72 * time.Hour: "3d"} {
		if got := age(created, created.Add(d)); got != want {
			t.Fatalf("%s: %s", d, got)
		}
	}
}

func TestForgetKeepsTheSubjectInASecretOnly(t *testing.T) {
	c, dyn := fakes()
	// The fake keeps what a dry run creates; the API server does not.
	made := map[string]bool{}
	c.Core.(*kubefake.Clientset).PrependReactor("create", "secrets", func(a k8stesting.Action) (bool, runtime.Object, error) {
		create := a.(k8stesting.CreateActionImpl)
		name := create.GetObject().(*corev1.Secret).Name
		if made[name] {
			return true, nil, apierrors.NewAlreadyExists(corev1.Resource("secrets"), name)
		}
		if len(create.CreateOptions.DryRun) > 0 {
			return true, create.GetObject(), nil
		}
		made[name] = true
		return false, nil, nil
	})
	var created []string
	dyn.PrependReactor("create", "lakeerasures", func(a k8stesting.Action) (bool, runtime.Object, error) {
		obj := a.(k8stesting.CreateAction).GetObject().(*unstructured.Unstructured).DeepCopy()
		body, _ := yaml.Marshal(obj.Object)
		created = append(created, string(body))
		obj.SetUID("u-1")
		return true, obj, nil
	})
	file := filepath.Join(t.TempDir(), "subject")
	if err := os.WriteFile(file, []byte("customer-4711\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	code, out, errOut := runWith(c, "forget", "erase-1", "--subject-file", file, "--logical", "12h")
	if code != 0 {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	secret, err := c.Core.CoreV1().Secrets("colvenna-data").Get(context.Background(), "erase-1", metav1.GetOptions{})
	if err != nil || string(secret.Data["values"]) != "customer-4711\n" {
		t.Fatalf("the values go to the Secret: %v %v", secret, err)
	}
	// Created already owned by the erasure, which existed first.
	if len(secret.OwnerReferences) != 1 || secret.OwnerReferences[0].UID != "u-1" {
		t.Fatalf("the erasure owns the Secret: %v", secret.OwnerReferences)
	}
	for _, want := range []string{"kind: LakeErasure", "name: erase-1", "key: values", "logical: 12h"} {
		if !strings.Contains(created[0], want) {
			t.Fatalf("missing %q in\n%s", want, created[0])
		}
	}
	if strings.Contains(created[0]+out+errOut, "4711") {
		t.Fatal("the subject's values appear outside the Secret")
	}
	// The same name again: an erasure is accepted once.
	var deleted []string
	dyn.PrependReactor("delete", "lakeerasures", func(a k8stesting.Action) (bool, runtime.Object, error) {
		deleted = append(deleted, a.(k8stesting.DeleteAction).GetName())
		return true, nil, nil
	})
	if code, _, errOut := runWith(c, "forget", "erase-1", "--subject-file", file); code != 1 || !strings.Contains(errOut, "exists") {
		t.Fatalf("%d %s", code, errOut)
	}
	// Refused before an erasure is made.
	if len(deleted) != 0 || len(created) != 1 {
		t.Fatalf("made %d erasures, deleted %v", len(created), deleted)
	}
	// The Secret cannot be made after the erasure was: the erasure goes again.
	c.Core.(*kubefake.Clientset).PrependReactor("create", "secrets", func(a k8stesting.Action) (bool, runtime.Object, error) {
		if len(a.(k8stesting.CreateActionImpl).CreateOptions.DryRun) > 0 {
			return true, a.(k8stesting.CreateActionImpl).GetObject(), nil
		}
		return true, nil, apierrors.NewForbidden(corev1.Resource("secrets"), "erase-2", nil)
	})
	if code, _, _ := runWith(c, "forget", "erase-2", "--subject-file", file); code != 1 || len(created) != 2 || len(deleted) != 1 || deleted[0] != "erase-2" {
		t.Fatalf("%d: made %d, deleted %v", code, len(created), deleted)
	}
}

func TestCheckExitsThreeWhenRulesFailAndWaiveNamesItsApprover(t *testing.T) {
	failed := task("check-1", "Succeeded")
	failed.Object["spec"] = map[string]any{"type": "check", "table": map[string]any{"database": "ods_web", "table": "clicks"}}
	failed.Object["status"].(map[string]any)["check"] = map[string]any{"verdict": "Failed", "failed": []any{"fresh"}, "snapshot": int64(9)}
	passed := task("check-2", "Succeeded")
	passed.Object["status"].(map[string]any)["check"] = map[string]any{"verdict": "Passed", "snapshot": int64(10)}
	c, dyn := fakes(failed, passed)
	var patched []string
	dyn.PrependReactor("patch", "*", func(a k8stesting.Action) (bool, runtime.Object, error) {
		p := a.(k8stesting.PatchAction)
		patched = append(patched, string(p.GetPatch()))
		switch p.GetName() {
		case "check-1":
			return true, failed, nil
		case "check-2":
			return true, passed, nil
		}
		return true, &unstructured.Unstructured{Object: map[string]any{"apiVersion": ops.Group + "/" + ops.Version, "kind": "LakeWaiver",
			"metadata": map[string]any{"name": p.GetName(), "namespace": "colvenna-data"}}}, nil
	})
	code, _, errOut := runWith(c, "check", "ods_web.clicks", "--name", "check-1", "--partition", "dt=2026-10-16", "--wait")
	if code != 3 || !strings.Contains(errOut, "fresh") {
		t.Fatalf("failed rules exit 3: %d %s", code, errOut)
	}
	for _, want := range []string{"type: check", "dt=2026-10-16"} {
		if !strings.Contains(patched[0], want) {
			t.Fatalf("missing %q in\n%s", want, patched[0])
		}
	}
	if code, out, errOut := runWith(c, "check", "ods_web.clicks", "--name", "check-2", "--snapshot", "10", "--as-of", "2026-10-16T16:00:00+08:00", "--wait"); code != 0 || !strings.Contains(out, "passed every rule") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	if !strings.Contains(patched[1], "snapshot: 10") || !strings.Contains(patched[1], "asOf: \"2026-10-16T08:00:00Z\"") {
		t.Fatalf("snapshot and asOf: %s", patched[1])
	}
	if code, _, _ := runWith(c, "check", "ods_web.clicks", "--name", "x", "--as-of", "yesterday"); code != 1 {
		t.Fatal("--as-of is RFC 3339")
	}

	core := c.Core.(*kubefake.Clientset)
	core.PrependReactor("create", "selfsubjectreviews", func(k8stesting.Action) (bool, runtime.Object, error) {
		return true, &authv1.SelfSubjectReview{Status: authv1.SelfSubjectReviewStatus{UserInfo: authv1.UserInfo{Username: "arn:aws:sts::1:assumed-role/owner/alice"}}}, nil
	})
	code, out, errOut := runWith(c, "waive", "check-1", "--name", "w1", "--rule", "fresh", "--reason", "upstream outage")
	if code != 0 || !strings.Contains(out, "waiver/w1 applied") {
		t.Fatalf("%d %s %s", code, out, errOut)
	}
	last := patched[len(patched)-1]
	for _, want := range []string{"kind: LakeWaiver", "colvenna-lake.io/approved-by: arn:aws:sts::1:assumed-role/owner/alice", "check: check-1", "- fresh", "reason: upstream outage"} {
		if !strings.Contains(last, want) {
			t.Fatalf("missing %q in\n%s", want, last)
		}
	}
	if code, _, _ := runWith(c, "waive", "check-1", "--name", "w2", "--rule", "fresh"); code != 1 {
		t.Fatal("a waiver gives its reason")
	}
}
