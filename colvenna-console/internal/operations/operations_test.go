package operations

import (
	"context"
	"strings"
	"testing"
	"time"

	"k8s.io/client-go/rest"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func TestAConfirmationIsForOnePersonOneRequestAndAFewMinutes(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	c := &Confirmations{Key: []byte("0123456789abcdef0123456789abcdef"), TTL: 5 * time.Minute, Now: func() time.Time { return now }}
	form := Form{"kind": "LakeTable", "name": "dwd"}
	token := c.Token("s-1", "delete", "ns", form)
	if err := c.Check(token, "s-1", "delete", "ns", Form{"name": "dwd", "kind": "LakeTable"}); err != nil {
		t.Fatal(err)
	}
	for name, check := range map[string]func() error{
		"another person":    func() error { return c.Check(token, "s-2", "delete", "ns", form) },
		"another operation": func() error { return c.Check(token, "s-1", "backfill", "ns", form) },
		"another namespace": func() error { return c.Check(token, "s-1", "delete", "ns2", form) },
		"another field":     func() error { return c.Check(token, "s-1", "delete", "ns", Form{"kind": "LakeTable", "name": "dws"}) },
		// Moving a value from one field to the next does not keep the same signature.
		"shifted":  func() error { return c.Check(token, "s-1", "delete", "ns", Form{"kind": "LakeTabl", "name": "edwd"}) },
		"no token": func() error { return c.Check("", "s-1", "delete", "ns", form) },
	} {
		if check() == nil {
			t.Fatalf("%s confirmed", name)
		}
	}
	now = now.Add(6 * time.Minute)
	if c.Check(token, "s-1", "delete", "ns", form) == nil {
		t.Fatal("expired, confirmed")
	}
}

func TestTheConsoleActsOnlyAsItsOwnUsers(t *testing.T) {
	r := &Runner{Config: &rest.Config{Host: "https://kubernetes.default.svc"}}
	if _, err := r.Clients(Identity{As: "system:admin", Username: "x"}, "ns"); err == nil {
		t.Fatal("impersonated someone else")
	}
	if _, err := r.Clients(Identity{As: OperatorUser, Username: "olive", Sub: "s-1"}, "ns"); err != nil {
		t.Fatal(err)
	}
	if strings.Join((Form{"v": " a \n\n b"}).Lines("v"), ",") != "a,b" {
		t.Fatal("lines")
	}
	for _, op := range All {
		if Find(op.Name) == nil || op.Target == nil || op.run == nil {
			t.Fatalf("%s", op.Name)
		}
	}
}

func TestApplyLeavesToTheirOwnOperationsWhatNeedsThem(t *testing.T) {
	op := Find("apply")
	for manifest, refused := range map[string]bool{
		"kind: LakeTask\nmetadata: {name: t}\nspec: {type: backfill}":    true,
		"kind: LakeErasure\nmetadata: {name: e}":                         true,
		"kind: LakeWaiver\nmetadata: {name: w}":                          true,
		"kind: LakeQuality\nmetadata: {name: q, namespace: other}":       true,
		"kind: lakeerasure\nmetadata: {name: e}":                         true,
		"kind: task\nmetadata: {name: t}\nspec: {type: expireSnapshots}": true,
		"kind: ConfigMap\nmetadata: {name: c}":                           false,
		"kind: LakeTask\nmetadata: {name: t}\nspec: {type: createTag}":   false,
	} {
		_, err := op.Run(context.Background(), &ops.Clients{Namespace: "ns"}, Form{"manifest": "apiVersion: x/y\n" + manifest})
		// Refused before any request (no clients); the others reach Apply, which refuses x/y.
		if got := err != nil && strings.Contains(err.Error(), "use the") || err != nil && strings.Contains(err.Error(), "namespace other"); got != refused {
			t.Fatalf("%q: %v", manifest, err)
		}
	}
}
