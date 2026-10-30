package ops

import (
	"context"
	"testing"

	authv1 "k8s.io/api/authentication/v1"
	"k8s.io/apimachinery/pkg/runtime"
	kubefake "k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
)

func TestTheConsolesPersonIsPartOfWhoApproves(t *testing.T) {
	for name, user := range map[string]authv1.UserInfo{
		"arn:aws:sts::1:assumed-role/owner/alice": {Username: "arn:aws:sts::1:assumed-role/owner/alice"},
		"colvenna-console:admin/alice": {Username: "colvenna-console:admin",
			Extra: map[string]authv1.ExtraValue{PersonKey: {"alice"}}},
	} {
		core := kubefake.NewClientset()
		core.PrependReactor("create", "selfsubjectreviews", func(k8stesting.Action) (bool, runtime.Object, error) {
			return true, &authv1.SelfSubjectReview{Status: authv1.SelfSubjectReviewStatus{UserInfo: user}}, nil
		})
		got, err := Whoami(context.Background(), &Clients{Core: core})
		if err != nil || got != name {
			t.Fatalf("%s: %s %v", name, got, err)
		}
	}
}

func TestTasksAreTheCLIsObjects(t *testing.T) {
	task, err := Compact("c1", "dwd.orders", true, []string{"dt=2026-10-19"}, TaskOptions{Retries: "1", Timeout: "30m"})
	if err != nil {
		t.Fatal(err)
	}
	spec := task.Object["spec"].(map[string]any)
	if spec["type"] != "compact" || spec["retries"] != int64(1) || spec["timeout"] != "30m" ||
		spec["table"].(map[string]any)["database"] != "dwd" ||
		spec["compact"].(map[string]any)["partitions"].([]any)[0] != "dt=2026-10-19" {
		t.Fatalf("%v", task.Object)
	}
	if _, err := Backfill("b1", "orders", TaskOptions{}); err == nil {
		t.Fatal("a table is <database>.<table>")
	}
	if _, err := Check("q1", "ods.clicks", nil, 0, "yesterday", TaskOptions{}); err == nil {
		t.Fatal("as of is RFC 3339")
	}
	if _, err := Waiver("w1", "q1", nil, "why", "alice"); err == nil {
		t.Fatal("a waiver names its rules")
	}
	if _, err := Tag("t1", "ods.clicks", "", "", TaskOptions{}); err == nil {
		t.Fatal("a tag has a name")
	}
}
