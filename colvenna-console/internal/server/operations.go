package server

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"strings"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/identity"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/operations"
	"github.com/colvenna/colvenna-lake/colvenna-console/internal/pipeline"
)

// Operations are what the controlled operations need: the Kubernetes clients of an
// identity, the confirmations' signer, and the namespaces they may act in.
type Operations struct {
	Runner interface {
		Clients(id operations.Identity, namespace string) (*ops.Clients, error)
	}
	Confirm    *operations.Confirmations
	Namespaces []string
}

// maxForm is the largest form an operation takes (a manifest).
const maxForm = 1 << 20

// identityOf is who the console acts as for the user: an administrator as colvenna-console:admin, an
// operator as colvenna-console:operator.
func identityOf(user identity.User) operations.Identity {
	as := operations.OperatorUser
	if user.Has(identity.Admin) {
		as = operations.AdminUser
	}
	return operations.Identity{As: as, Username: user.Username, Sub: user.Sub}
}

func (s *Server) operationsPage(w http.ResponseWriter, r *http.Request) {
	user := userOf(r)
	var allowed []*operations.Op
	for i := range operations.All {
		if op := &operations.All[i]; !op.Admin || user.Has(identity.Admin) {
			allowed = append(allowed, op)
		}
	}
	s.page(w, http.StatusOK, "operations", map[string]any{"User": user, "Ops": allowed, "Namespaces": s.ops.Namespaces})
}

// operate is one operation: from the console's own pages only; for the roles it needs, whatever
// the page offered; with a confirmation first when it needs one; audited before it changes
// anything (no audit, no change) and with its result after.
func (s *Server) operate(w http.ResponseWriter, r *http.Request) {
	user := userOf(r)
	if r.Header.Get("Origin") != s.cfg.PublicURL {
		s.deny(w, r, user, http.StatusForbidden, "operation from another origin", "请从控制台页面发起操作。")
		return
	}
	op := operations.Find(r.PathValue("op"))
	if op == nil {
		s.page(w, http.StatusNotFound, "message", map[string]any{"Message": "没有这个操作。", "User": user})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxForm)
	if err := r.ParseForm(); err != nil {
		s.page(w, http.StatusBadRequest, "message", map[string]any{"Message": "表单读不出来：" + err.Error(), "User": user})
		return
	}
	namespace := r.PostForm.Get("namespace")
	form := operations.Form{}
	for _, f := range op.Fields {
		form[f.Name] = r.PostForm.Get(f.Name)
	}
	resource := namespace
	kind, name := op.Target(form)
	for _, part := range []string{kind, name} {
		if part != "" {
			resource += "/" + part
		}
	}
	// refuse audits a refusal of this operation on this resource.
	refuse := func(status int, reason, message string) {
		_ = s.recordOperation(r, op, resource, status, reason)
		s.page(w, status, "message", map[string]any{"Message": message, "User": user})
	}
	if op.Admin && !user.Has(identity.Admin) {
		refuse(http.StatusForbidden, "not an administrator", "这个操作只对平台管理员开放。")
		return
	}
	if !slices.Contains(s.ops.Namespaces, namespace) {
		refuse(http.StatusForbidden, "namespace not managed", "控制台不在这个命名空间里操作。")
		return
	}
	if op.Confirm {
		token := r.PostForm.Get("confirmation")
		if token == "" {
			s.preview(w, r, op, namespace, resource, form)
			return
		}
		// What the preview bound into the confirmation, sent back with it.
		for _, b := range op.Binds {
			form[b] = r.PostForm.Get(b)
		}
		if err := s.ops.Confirm.Check(token, user.Sub, op.Name, namespace, form); err != nil {
			refuse(http.StatusForbidden, err.Error(), "没有确认，或者确认已过期、与这次的请求不符，什么也没有改。")
			return
		}
	}
	if err := s.recordOperation(r, op, resource, 0, "requested"); err != nil {
		http.Error(w, "审计暂不可用，请稍后再试；什么也没有改。", http.StatusServiceUnavailable)
		return
	}
	done, err := s.run(r.Context(), op, identityOf(user), namespace, form)
	status, result := http.StatusOK, strings.Join(done, "; ")
	if err != nil {
		status, result = statusOf(err), err.Error()
	}
	_ = s.recordOperation(r, op, resource, status, result)
	s.page(w, status, "result", map[string]any{"User": user, "Op": op, "Namespace": namespace, "Resource": resource,
		"Done": done, "Error": err})
}

func (s *Server) run(ctx context.Context, op *operations.Op, id operations.Identity, namespace string, form operations.Form) ([]string, error) {
	c, err := s.ops.Runner.Clients(id, namespace)
	if err != nil {
		return nil, err
	}
	return op.Run(ctx, c, form)
}

// statusOf is the API server's answer; 409 for a declaration changed since it was shown; or 400
// for a request the console found wrong itself.
func statusOf(err error) int {
	var status apierrors.APIStatus
	if errors.As(err, &status) && status.Status().Code != 0 {
		return int(status.Status().Code)
	}
	if errors.Is(err, ops.ErrChanged) {
		return http.StatusConflict
	}
	return http.StatusBadRequest
}

// recordOperation audits an operation: who (the event's user), what, on which resource, and the
// result. The fields marked private (an erasure's subject) are never in it.
func (s *Server) recordOperation(r *http.Request, op *operations.Op, resource string, status int, result string) error {
	e := s.event("operation", userOf(r), r, status, result)
	e.Action, e.Resource = op.Name, resource
	return s.audit.Record(e)
}

// preview shows what a confirmed operation affects, and the form again with its confirmation.
// Nothing changes: an operation not confirmed is an operation not done. When what it affects
// cannot be told, there is no confirmation.
func (s *Server) preview(w http.ResponseWriter, r *http.Request, op *operations.Op, namespace, resource string, form operations.Form) {
	user := userOf(r)
	impact, err := s.impact(r.Context(), op, identityOf(user), namespace, form)
	if err != nil {
		status := http.StatusServiceUnavailable
		if apierrors.IsNotFound(err) || apierrors.IsForbidden(err) {
			status = statusOf(err)
		}
		_ = s.recordOperation(r, op, resource, status, "no preview: "+err.Error())
		s.page(w, status, "message", map[string]any{"User": user,
			"Message": "看不出影响范围，没有给出确认，什么也没有改：" + err.Error()})
		return
	}
	if err := s.recordOperation(r, op, resource, 0, "previewed"); err != nil {
		http.Error(w, "审计暂不可用，请稍后再试。", http.StatusServiceUnavailable)
		return
	}
	var fields []map[string]any
	for _, f := range op.Fields {
		fields = append(fields, map[string]any{"Name": f.Name, "Value": form[f.Name]})
	}
	for _, b := range op.Binds {
		fields = append(fields, map[string]any{"Name": b, "Value": form[b]})
	}
	s.page(w, http.StatusOK, "confirm", map[string]any{"User": user, "Op": op, "Namespace": namespace,
		"Resource": resource, "Impact": impact, "Fields": fields,
		"Token": s.ops.Confirm.Token(user.Sub, op.Name, namespace, form)})
}

// impact is what an operation affects: in the console's picture of the pipeline, and for a
// declaration the pipeline does not draw, what the declaration is (read as the person).
func (s *Server) impact(ctx context.Context, op *operations.Op, id operations.Identity, namespace string, form operations.Form) ([]string, error) {
	p, err := s.pipeline.Read(ctx)
	if err != nil {
		return nil, fmt.Errorf("the pipeline: %w", err)
	}
	downstream := func(nodeID string) string {
		var out []string
		for _, n := range p.Downstream(nodeID) {
			out = append(out, pipeline.KindName(n.Kind)+" "+pipeline.Label(n))
		}
		if len(out) == 0 {
			return "下游：无"
		}
		return "下游受影响：" + strings.Join(out, "、")
	}
	table := form["table"]
	switch op.Name {
	case "delete":
		return s.deletion(ctx, p, id, namespace, form, downstream)
	case "backfill":
		writer := p.Writer(table)
		if writer == nil || writer.Kind != pipeline.Job || writer.Namespace != namespace {
			return nil, fmt.Errorf("%s is no table a table job of %s writes", table, namespace)
		}
		return []string{"从上游重算 " + table + "（由 " + pipeline.KindName(writer.Kind) + " " + pipeline.Label(writer) + " 写），读它的下游随后重读",
			downstream(pipeline.ID(pipeline.Table, "", table))}, nil
	case "expire":
		return []string{fmt.Sprintf("过期 %s 早于 %s 的快照，至少留 %s 个；tag 与读端还需要的快照留下", table,
			or(form["olderThan"], "1h"), or(form["retainMin"], "10")), "过期的快照不能再读到，time travel 回不去"}, nil
	case "orphans":
		return []string{fmt.Sprintf("删除 %s 里早于 %s、没有快照、tag 或分支引用的文件", table, or(form["olderThan"], "1d")),
			"删掉的文件不能恢复"}, nil
	case "forget":
		var tables []string
		for _, n := range p.Nodes {
			if w := p.Writer(n.Name); n.Kind == pipeline.Table && w != nil && w.Namespace == namespace {
				tables = append(tables, n.Name)
			}
		}
		return []string{
			fmt.Sprintf("在 %s 的所有受管副本里删除这个主体（%d 个键值）；逻辑删除期限 %s，物理删除期限 %s", namespace,
				len(form.Lines("values")), or(form["logical"], ops.LogicalDeadline), or(form["physical"], ops.PhysicalDeadline)),
			fmt.Sprintf("这个命名空间写的湖表：%d 张（%s）；删除不能撤销", len(tables), strings.Join(tables, "、")),
		}, nil
	}
	return nil, fmt.Errorf("no preview for %s", op.Name)
}

// deletion is what deleting a declaration does: for one the pipeline draws, its tables (by its
// deletionPolicy) and what is downstream; for the others, what goes with it.
func (s *Server) deletion(ctx context.Context, p *pipeline.Pipeline, id operations.Identity, namespace string,
	form operations.Form, downstream func(string) string) ([]string, error) {
	kind, err := ops.LookupKind(form["kind"])
	if err != nil {
		return nil, err
	}
	c, err := s.ops.Runner.Clients(id, namespace)
	if err != nil {
		return nil, err
	}
	obj, err := c.Dynamic.Resource(kind.GVR()).Namespace(namespace).Get(ctx, form["name"], metav1.GetOptions{})
	if err != nil {
		return nil, err
	}
	// The confirmation is for this object as it is now.
	form["uid"], form["generation"] = string(obj.GetUID()), strconv.FormatInt(obj.GetGeneration(), 10)
	spec := func(path ...string) string {
		v, _, _ := unstructured.NestedString(obj.Object, append([]string{"spec"}, path...)...)
		return v
	}
	table := spec("table", "database") + "." + spec("table", "table")
	switch kind.Kind {
	case "LakeSource", "LakeTable":
		nodeKind := map[string]string{"LakeSource": pipeline.Source, "LakeTable": pipeline.Job}[kind.Kind]
		return []string{fmt.Sprintf("删除 %s %s/%s；它的表按 deletionPolicy %s 处理", pipeline.KindName(nodeKind), namespace, form["name"],
			or(spec("deletionPolicy"), "Retain")), downstream(pipeline.ID(nodeKind, namespace, form["name"]))}, nil
	case "LakeDashboard":
		return []string{fmt.Sprintf("删除看板 %s/%s；Operator 撤下它在 Doris 里的库、对象与用户，读它的人不再能查询", namespace, form["name"])}, nil
	case "LakeQuality":
		return []string{"删除表 " + table + " 的质量规则；它的门禁随之撤下，发布不再受这些规则约束"}, nil
	case "LakeWaiver":
		return []string{"撤回检查 " + spec("check") + " 的豁免；门禁回到这次检查本身的结论"}, nil
	case "LakeErasure":
		return []string{"删除这个删除请求与它拥有的主体 Secret；还没做完的删除不再推进，已经删掉的不会恢复"}, nil
	case "LakeTask":
		return []string{fmt.Sprintf("删除任务（%s，表 %s）；它已经做完的改动不会撤回", spec("type"), table)}, nil
	}
	return []string{fmt.Sprintf("删除 %s %s/%s（表 %s）；Operator 按它的删除规则处理它在外部的登记", kind.Kind, namespace, form["name"], table)}, nil
}

func or(v, otherwise string) string {
	if v == "" {
		return otherwise
	}
	return v
}
