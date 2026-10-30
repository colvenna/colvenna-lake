package cli

import (
	"fmt"

	"github.com/spf13/cobra"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

// qualityFailed is a check that ran and failed rules: exit code 3, so an orchestrator holds back
// what follows it.
type qualityFailed struct{ message string }

func (q *qualityFailed) Error() string { return q.message }

// checkCommand is `colvenna check`: one batch quality check of a table's LakeQuality rules.
func checkCommand(o *options) *cobra.Command {
	var f taskFlags
	var partitions []string
	var snapshot int64
	var asOf string
	cmd := &cobra.Command{
		Use:   "check <database.table> --name <task>",
		Short: "Check a table against its LakeQuality rules; --wait exits 3 if rules failed",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Check(f.name, args[0], partitions, snapshot, asOf, f.options())
			if err := f.submit(o, cmd, task, err); err != nil || !f.wait {
				return err
			}
			return verdict(o, f.name, cmd)
		},
	}
	cmd.Flags().StringArrayVar(&partitions, "partition", nil, "only this partition, as column=value[,column=value]; repeatable")
	cmd.Flags().Int64Var(&snapshot, "snapshot", 0, "the snapshot to check (default: the latest when it starts)")
	cmd.Flags().StringVar(&asOf, "as-of", "", "the time freshness is measured at, RFC 3339 UTC (default: when it starts)")
	f.add(cmd)
	cmd.Flags().Lookup("wait").Usage = "wait for the check's end: exit 0 if every rule passed, 3 if some failed, 1 if it could not run, 2 on timeout"
	return cmd
}

// verdict reads a finished check's verdict: a *qualityFailed when rules failed.
func verdict(o *options, name string, cmd *cobra.Command) error {
	c, err := o.clients(o)
	if err != nil {
		return err
	}
	ctx, cancel := background()
	defer cancel()
	kind, _ := ops.LookupKind("LakeTask")
	obj, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return err
	}
	switch v := str(obj.Object, "status", "check", "verdict"); v {
	case "Passed":
		_, _ = fmt.Fprintf(cmd.OutOrStdout(), "task/%s passed every rule at snapshot %s\n", name,
			str(obj.Object, "status", "check", "snapshot"))
		return nil
	case "Failed":
		return &qualityFailed{fmt.Sprintf("task/%s failed rules %s at snapshot %s", name,
			str(obj.Object, "status", "check", "failed"), str(obj.Object, "status", "check", "snapshot"))}
	default:
		return fmt.Errorf("task/%s succeeded without a verdict", name)
	}
}

// waiveCommand is `colvenna waive`: accept rules a check failed. The waiver names its creator, whom
// the API server checks against the request.
func waiveCommand(o *options) *cobra.Command {
	var name, reason string
	var rules []string
	cmd := &cobra.Command{
		Use:   "waive <check-task> --name <waiver> --rule <rule>... --reason <why>",
		Short: "Accept failed rules of one check, so its table's quality gate opens; you are its approver",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := o.clients(o)
			if err != nil {
				return err
			}
			ctx, cancel := background()
			defer cancel()
			user, err := ops.Whoami(ctx, c)
			if err != nil {
				return err
			}
			waiver, err := ops.Waiver(name, args[0], rules, reason, user)
			if err != nil {
				return err
			}
			return apply(c, []*unstructured.Unstructured{waiver}, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "the LakeWaiver's name (required)")
	cmd.Flags().StringArrayVar(&rules, "rule", nil, "a failed rule to accept; repeatable")
	cmd.Flags().StringVar(&reason, "reason", "", "why, kept in the gate's decisions; no personal data")
	_ = cmd.MarkFlagRequired("name")
	return cmd
}

// describeQuality shows a LakeQuality: its gate, the checks it stands on and its latest decisions.
func describeQuality(t *table, obj *unstructured.Unstructured) {
	o := obj.Object
	t.row("Name:\t%s/%s", obj.GetNamespace(), obj.GetName())
	t.row("Table:\t%s", str(o, "status", "table"))
	t.row("Phase:\t%s", str(o, "status", "phase"))
	t.row("Rules:\tversion %s, publish %s", str(o, "status", "ruleVersion"), str(o, "spec", "gate", "publish"))
	t.row("Gate:\t%s since %s", str(o, "status", "gate", "cause"), str(o, "status", "gate", "since"))
	t.row("Message:\t%s", str(o, "status", "message"))
	t.row("Store:\t%s", str(o, "status", "store"))
	t.row("\nCONDITION\tSTATUS\tREASON\tMESSAGE")
	for _, c := range list(o, "status", "conditions") {
		t.row("%s\t%s\t%s\t%s", str(c, "type"), str(c, "status"), str(c, "reason"), str(c, "message"))
	}
	t.row("\nCHECK\tSNAPSHOT\tAS OF\tPARTITIONS\tPASSED\tFAILED\tTAG")
	for _, c := range list(o, "status", "checks") {
		t.row("%s\t%s\t%s\t%s\t%s\t%s\t%s", str(c, "task"), str(c, "snapshot"), str(c, "asOf"), str(c, "partitions"),
			str(c, "passed"), str(c, "failed"), str(c, "tag")+str(c, "note"))
	}
	t.row("\nDECIDED AT\tOPEN\tCAUSE\tCHECK\tWAIVERS")
	for _, d := range list(o, "status", "decisions") {
		t.row("%s\t%s\t%s\t%s\t%s", str(d, "at"), str(d, "open"), str(d, "cause"), str(d, "check"), str(d, "waivers"))
	}
}

// describeCheck adds a check task's fixed inputs and verdict.
func describeCheck(t *table, o map[string]any) {
	if str(o, "spec", "type") != "check" {
		return
	}
	t.row("Check:\tsnapshot %s as of %s, partitions %s, rules %s (%s)", str(o, "status", "check", "snapshot"),
		str(o, "status", "check", "asOf"), str(o, "status", "check", "partitions"),
		str(o, "status", "check", "ruleVersion"), str(o, "status", "check", "quality"))
	t.row("Verdict:\t%s %s (%s rows)", str(o, "status", "check", "verdict"), str(o, "status", "check", "failed"),
		str(o, "status", "check", "rows"))
}
