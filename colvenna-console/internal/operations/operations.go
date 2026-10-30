// Package operations are the console's controlled operations: what `colvenna` does, done by a
// signed-in operator or administrator. Each builds its objects with the CLI's own package
// (colvenna-cli/ops) and sends them to the Kubernetes API as the person: the console impersonates
// colvenna-console:operator or colvenna-console:admin with the person as a user extra, so the API server's
// RBAC, its admission and the operator judge it as they judge the CLI's, and the cluster's audit
// names the person. Operations that cannot be undone or that change what is downstream show what
// they affect first, and run only with a signed confirmation of exactly that request.
package operations

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

// Manager is the field manager of what the console applies.
const Manager = "colvenna-console"

// The console's two Kubernetes users: what it acts as for an operator and for an administrator.
const (
	OperatorUser = "colvenna-console:operator"
	AdminUser    = "colvenna-console:admin"
)

// SubKey is the user extra with the person's sub, next to ops.PersonKey's user name.
const SubKey = "colvenna-lake.io/sub"

// Field is one input of an operation's form.
type Field struct {
	Name  string
	Label string
	// Type is text, number, lines (one value a line), manifest, choice or check.
	Type     string
	Choices  []string
	Required bool
	// Private values (an erasure's subject) are personal data: never audited or shown back but in
	// the confirmation form the person sends again.
	Private bool
	Help    string
}

// Form is a filled-in form: each field's value as sent.
type Form map[string]string

// Lines are a lines field's values, blank ones dropped.
func (f Form) Lines(name string) []string {
	var out []string
	for _, l := range strings.Split(f[name], "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}

// Op is one operation.
type Op struct {
	Name  string
	Title string
	// Admin: only administrators; the others operators and administrators.
	Admin bool
	// Confirm: shows what it affects and runs only once confirmed.
	Confirm bool
	Fields  []Field
	// Binds are what its preview adds to the form, from the object as shown (its uid and
	// generation): the confirmation covers them, so it runs only on what was shown.
	Binds []string
	// Target is what it acts on: a kind and a name, for the audit and its effect.
	Target func(Form) (kind, name string)
	run    func(ctx context.Context, c *ops.Clients, f Form) ([]string, error)
}

// Run validates the form and runs the operation; it returns what it applied or changed.
func (o *Op) Run(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
	for _, field := range o.Fields {
		if field.Required && strings.TrimSpace(f[field.Name]) == "" {
			return nil, fmt.Errorf("%s is required", field.Label)
		}
	}
	return o.run(ctx, c, f)
}

// Find is the operation of that name, or nil.
func Find(name string) *Op {
	for i := range All {
		if All[i].Name == name {
			return &All[i]
		}
	}
	return nil
}

// applicable refuses, in an applied manifest, what has an operation of its own or would leave the
// namespace the request is for: an erasure (its subject goes in a Secret), a waiver (it names its
// approver), a backfill (it is confirmed first), an object of another namespace.
func applicable(o *unstructured.Unstructured, namespace string) error {
	if ns := o.GetNamespace(); ns != "" && ns != namespace {
		return fmt.Errorf("%s/%s is in namespace %s, not %s", o.GetKind(), o.GetName(), ns, namespace)
	}
	// By the kind as the CLI's apply takes it (any of its names); a kind it does not know, it
	// refuses itself, in the manifest's order, as the CLI does.
	kind, err := ops.LookupKind(o.GetKind())
	if err != nil {
		return nil
	}
	own := map[string]string{"LakeErasure": "forget", "LakeWaiver": "waive"}[kind.Kind]
	if t, _, _ := unstructured.NestedString(o.Object, "spec", "type"); kind.Kind == "LakeTask" {
		// The tasks that are confirmed first.
		own = map[string]string{"backfill": "backfill", "expireSnapshots": "expire", "removeOrphanFiles": "orphans"}[t]
	}
	if own != "" {
		return fmt.Errorf("%s/%s: use the %s operation", o.GetKind(), o.GetName(), own)
	}
	return nil
}

func applied(ctx context.Context, c *ops.Clients, object *unstructured.Unstructured, err error) ([]string, error) {
	if err != nil {
		return nil, err
	}
	return ops.Apply(ctx, c, Manager, []*unstructured.Unstructured{object})
}

func taskOptions(f Form) ops.TaskOptions {
	return ops.TaskOptions{Retries: f["retries"], Timeout: f["timeout"]}
}

func taskTarget(f Form) (string, string) { return "LakeTask", f["name"] }

var (
	name     = Field{Name: "name", Label: "名字", Type: "text", Required: true}
	taskName = Field{Name: "name", Label: "任务名", Type: "text", Required: true, Help: "同一个名字再提交一次不会重复执行"}
	table    = Field{Name: "table", Label: "表（库.表）", Type: "text", Required: true}
	retries  = Field{Name: "retries", Label: "重试次数（0–5）", Type: "number"}
	timeout  = Field{Name: "timeout", Label: "单次超时（如 30m）", Type: "text"}
	declared = []string{"LakeSource", "LakeTable", "LakeDashboard", "LakeQuality", "LakeIcebergPublication", "LakeTask", "LakeWaiver", "LakeErasure"}
)

func kindOf(f Form) (ops.Kind, error) { return ops.LookupKind(f["kind"]) }

// All are the operations, those of colvenna's commands that change something.
var All = []Op{
	{Name: "apply", Title: "应用声明（apply）", Fields: []Field{
		{Name: "manifest", Label: "声明（YAML）", Type: "manifest", Required: true,
			Help: "Colvenna Lake 的资源，与 colvenna apply -f 相同；LakeErasure 与 LakeWaiver 走下面的合规删除与豁免"},
	}, Target: func(f Form) (string, string) {
		objects, err := ops.ReadManifests([]byte(f["manifest"]))
		if err != nil || len(objects) == 0 {
			return "", ""
		}
		names := make([]string, 0, len(objects))
		for _, o := range objects {
			names = append(names, o.GetKind()+"/"+o.GetName())
		}
		return "", strings.Join(names, ",")
	}, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
		objects, err := ops.ReadManifests([]byte(f["manifest"]))
		if err != nil {
			return nil, err
		}
		for _, o := range objects {
			if err := applicable(o, c.Namespace); err != nil {
				return nil, err
			}
		}
		return ops.Apply(ctx, c, Manager, objects)
	}},
	suspendOp("suspend", "挂起（suspend）", true),
	suspendOp("resume", "恢复（resume）", false),
	{Name: "delete", Title: "删除声明（delete）", Admin: true, Confirm: true, Fields: []Field{
		{Name: "kind", Label: "种类", Type: "choice", Choices: declared, Required: true}, name,
	}, Binds: []string{"uid", "generation"}, Target: func(f Form) (string, string) { return f["kind"], f["name"] },
		run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			kind, err := kindOf(f)
			if err != nil {
				return nil, err
			}
			generation, err := strconv.ParseInt(f["generation"], 10, 64)
			if err != nil {
				return nil, fmt.Errorf("generation %q: %w", f["generation"], err)
			}
			if err := ops.DeleteUnchanged(ctx, c, kind, f["name"], f["uid"], generation); err != nil {
				return nil, err
			}
			return []string{kind.Aliases[0] + "/" + f["name"] + " deleted"}, nil
		}},
	{Name: "backfill", Title: "回刷（backfill）", Confirm: true, Fields: []Field{table, taskName, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			task, err := ops.Backfill(f["name"], f["table"], taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "compact", Title: "压缩（maintain compact）", Fields: []Field{table, taskName,
		{Name: "full", Label: "合并所有层级", Type: "check"},
		{Name: "partitions", Label: "只压缩这些分区（每行一个，列=值[,列=值]）", Type: "lines"}, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			task, err := ops.Compact(f["name"], f["table"], f["full"] == "on", f.Lines("partitions"), taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "expire", Title: "过期快照（maintain expire）", Confirm: true, Fields: []Field{table, taskName,
		{Name: "retainMin", Label: "至少保留的快照数（默认 10）", Type: "number"},
		{Name: "olderThan", Label: "只过期早于（如 2d，默认 1h）", Type: "text"}, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			retain := 0
			if f["retainMin"] != "" {
				n, err := strconv.Atoi(f["retainMin"])
				if err != nil {
					return nil, fmt.Errorf("retainMin %q: %w", f["retainMin"], err)
				}
				retain = n
			}
			task, err := ops.Expire(f["name"], f["table"], retain, f["olderThan"], taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "orphans", Title: "清理孤儿文件（maintain orphans）", Confirm: true, Fields: []Field{table, taskName,
		{Name: "olderThan", Label: "只清理早于（至少 1d，默认 1d）", Type: "text"}, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			task, err := ops.Orphans(f["name"], f["table"], f["olderThan"], taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "tag", Title: "打 tag（maintain tag）", Fields: []Field{table, taskName,
		{Name: "tag", Label: "tag 名", Type: "text", Required: true},
		{Name: "timeRetained", Label: "保留多久（默认 7d）", Type: "text"}, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			task, err := ops.Tag(f["name"], f["table"], f["tag"], f["timeRetained"], taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "check", Title: "质量检查（check）", Fields: []Field{table, taskName,
		{Name: "partitions", Label: "只检查这些分区（每行一个）", Type: "lines"},
		{Name: "snapshot", Label: "快照（默认开始时最新的）", Type: "number"},
		{Name: "asOf", Label: "新鲜度的计算时刻（RFC 3339）", Type: "text"}, retries, timeout},
		Target: taskTarget, run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			var snapshot int64
			if f["snapshot"] != "" {
				n, err := strconv.ParseInt(f["snapshot"], 10, 64)
				if err != nil {
					return nil, fmt.Errorf("snapshot %q: %w", f["snapshot"], err)
				}
				snapshot = n
			}
			task, err := ops.Check(f["name"], f["table"], f.Lines("partitions"), snapshot, f["asOf"], taskOptions(f))
			return applied(ctx, c, task, err)
		}},
	{Name: "waive", Title: "豁免（waive）", Admin: true, Fields: []Field{
		{Name: "check", Label: "检查任务", Type: "text", Required: true}, name,
		{Name: "rules", Label: "接受的规则（每行一个）", Type: "lines", Required: true},
		{Name: "reason", Label: "原因（不要写个人数据）", Type: "text", Required: true},
	}, Target: func(f Form) (string, string) { return "LakeWaiver", f["name"] },
		run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			// The approver is who the API server knows the request as: the console's identity and
			// the person (ops.Whoami), which its admission checks.
			approver, err := ops.Whoami(ctx, c)
			if err != nil {
				return nil, err
			}
			waiver, err := ops.Waiver(f["name"], f["check"], f.Lines("rules"), f["reason"], approver)
			return applied(ctx, c, waiver, err)
		}},
	{Name: "forget", Title: "合规删除（forget）", Admin: true, Confirm: true, Fields: []Field{name,
		{Name: "values", Label: "主体的键值（每行一个；多列的键用 Tab 分隔）", Type: "lines", Required: true, Private: true},
		{Name: "logical", Label: "逻辑删除期限（至多 " + ops.LogicalDeadline + "）", Type: "text"},
		{Name: "physical", Label: "物理删除期限（至多 " + ops.PhysicalDeadline + "）", Type: "text"},
	}, Target: func(f Form) (string, string) { return "LakeErasure", f["name"] },
		run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			values := strings.Join(f.Lines("values"), "\n") + "\n"
			if err := ops.Forget(ctx, c, Manager, f["name"], []byte(values), f["logical"], f["physical"]); err != nil {
				return nil, err
			}
			return []string{"erasure/" + f["name"] + " created"}, nil
		}},
}

func suspendOp(op, title string, suspend bool) Op {
	return Op{Name: op, Title: title, Fields: []Field{
		{Name: "kind", Label: "种类", Type: "choice", Choices: []string{"LakeSource", "LakeTable"}, Required: true}, name,
	}, Target: func(f Form) (string, string) { return f["kind"], f["name"] },
		run: func(ctx context.Context, c *ops.Clients, f Form) ([]string, error) {
			kind, err := kindOf(f)
			if err != nil {
				return nil, err
			}
			if err := ops.Suspend(ctx, c, Manager, kind, f["name"], suspend); err != nil {
				return nil, err
			}
			done := " resumed"
			if suspend {
				done = " suspended"
			}
			return []string{kind.Aliases[0] + "/" + f["name"] + done}, nil
		}}
}

// Identity is who the console acts as: one of its two Kubernetes users, for a person.
type Identity struct {
	// As is the Kubernetes user, AdminUser or OperatorUser.
	As string
	// Username and Sub are the person's.
	Username string
	Sub      string
}

// Runner makes the Kubernetes clients of an identity: the console's own credentials,
// impersonating the identity's user with the person as user extras. The console's RBAC lets it
// impersonate those two users and those extras, nothing else.
type Runner struct {
	Config *rest.Config
}

// Clients are the identity's clients in a namespace.
func (r *Runner) Clients(id Identity, namespace string) (*ops.Clients, error) {
	if id.As != AdminUser && id.As != OperatorUser {
		return nil, fmt.Errorf("the console acts only as its own users, not %q", id.As)
	}
	cfg := rest.CopyConfig(r.Config)
	cfg.Impersonate = rest.ImpersonationConfig{UserName: id.As,
		Extra: map[string][]string{ops.PersonKey: {id.Username}, SubKey: {id.Sub}}}
	cfg.UserAgent = Manager + "/" + id.Username
	dyn, err := dynamic.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	core, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return nil, err
	}
	return &ops.Clients{Dynamic: dyn, Core: core, Namespace: namespace}, nil
}

// Confirmations sign the second step of a confirmed operation: a token for this person, this
// operation and exactly these fields, valid for a few minutes. The key is shared by the console's
// pods (a Secret), so either may take the second step.
type Confirmations struct {
	Key []byte
	TTL time.Duration
	Now func() time.Time
}

// ErrConfirmation is a confirmation that is missing, expired, or for another request.
var ErrConfirmation = errors.New("not confirmed: the confirmation is missing, expired, or for another request")

// Token is the confirmation of a request.
func (c *Confirmations) Token(sub, op, namespace string, f Form) string {
	expires := strconv.FormatInt(c.Now().Add(c.TTL).Unix(), 10)
	return expires + "." + c.sign(expires, sub, op, namespace, f)
}

// Check says whether a token confirms this request.
func (c *Confirmations) Check(token, sub, op, namespace string, f Form) error {
	expires, mac, ok := strings.Cut(token, ".")
	if !ok {
		return ErrConfirmation
	}
	at, err := strconv.ParseInt(expires, 10, 64)
	if err != nil || c.Now().Unix() > at {
		return ErrConfirmation
	}
	if !hmac.Equal([]byte(mac), []byte(c.sign(expires, sub, op, namespace, f))) {
		return ErrConfirmation
	}
	return nil
}

func (c *Confirmations) sign(expires, sub, op, namespace string, f Form) string {
	m := hmac.New(sha256.New, c.Key)
	keys := make([]string, 0, len(f))
	for k := range f {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	// Length-prefixed, so no two requests write the same bytes.
	for _, part := range append([]string{expires, sub, op, namespace}, keys...) {
		_, _ = fmt.Fprintf(m, "%d:%s;", len(part), part)
	}
	for _, k := range keys {
		_, _ = fmt.Fprintf(m, "%d:%s;", len(f[k]), f[k])
	}
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}
