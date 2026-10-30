package cli

import (
	"io"
	"sort"

	"github.com/spf13/cobra"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func describeCommand(o *options) *cobra.Command {
	return &cobra.Command{
		Use:   "describe <kind> <name>",
		Short: "Show a declaration's conditions, jobs, tables and recent events",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			kind, err := ops.LookupKind(args[0])
			if err != nil {
				return err
			}
			c, err := o.clients(o)
			if err != nil {
				return err
			}
			ctx, cancel := background()
			defer cancel()
			obj, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Get(ctx, args[1], metav1.GetOptions{})
			if err != nil {
				return err
			}
			events, err := c.Core.CoreV1().Events(c.Namespace).List(ctx, metav1.ListOptions{
				FieldSelector: "involvedObject.name=" + args[1] + ",involvedObject.kind=" + kind.Kind,
			})
			if err != nil {
				return err
			}
			return describe(cmd.OutOrStdout(), obj, events.Items)
		},
	}
}

func describe(out io.Writer, obj *unstructured.Unstructured, events []corev1.Event) error {
	o := obj.Object
	t := newTable(out)
	if obj.GetKind() == "LakeTable" {
		describeTable(t, obj)
		return describeEvents(t, events)
	}
	if obj.GetKind() == "LakeTask" {
		describeTask(t, obj)
		return describeEvents(t, events)
	}
	if obj.GetKind() == "LakeErasure" {
		describeErasure(t, obj)
		return describeEvents(t, events)
	}
	if obj.GetKind() == "LakeQuality" {
		describeQuality(t, obj)
		return describeEvents(t, events)
	}
	t.row("Name:\t%s/%s", obj.GetNamespace(), obj.GetName())
	t.row("Type:\t%s", str(o, "spec", "type"))
	t.row("Target:\t%s", str(o, "spec", "target", "database"))
	t.row("Phase:\t%s (%s ready)", str(o, "status", "phase"), str(o, "status", "summary"))
	t.row("Message:\t%s", str(o, "status", "message"))
	t.row("Principal:\t%s", str(o, "status", "identity", "principal"))
	t.row("\nCONDITION\tSTATUS\tREASON\tMESSAGE")
	for _, c := range list(o, "status", "conditions") {
		t.row("%s\t%s\t%s\t%s", str(c, "type"), str(c, "status"), str(c, "reason"), str(c, "message"))
	}
	t.row("\nJOB\tSTATE\tLIFECYCLE\tTABLES\tLAST ERROR")
	for _, j := range list(o, "status", "jobs") {
		t.row("%s\t%s\t%s\t%s\t%s", str(j, "deployment"), str(j, "state"), str(j, "lifecycle"), str(j, "tables"), str(j, "lastError"))
	}
	t.row("\nTABLE\tSTATE\tREASON\tROWS (SOURCE/TARGET)\tMESSAGE")
	for _, tb := range list(o, "status", "tables") {
		t.row("%s\t%s\t%s\t%s/%s\t%s", str(tb, "name"), str(tb, "state"), str(tb, "reason"), str(tb, "sourceRows"), str(tb, "targetRows"), str(tb, "message"))
	}
	return describeEvents(t, events)
}

// describeTable shows a LakeTable: its query's validation, the job and how far it has read.
func describeTable(t *table, obj *unstructured.Unstructured) {
	o := obj.Object
	t.row("Name:\t%s/%s", obj.GetNamespace(), obj.GetName())
	t.row("Target:\t%s", tableTarget(o))
	t.row("Phase:\t%s", str(o, "status", "phase"))
	t.row("Message:\t%s", str(o, "status", "message"))
	t.row("Principal:\t%s", str(o, "status", "identity", "principal"))
	t.row("Validation:\t%s %s", str(o, "status", "validation", "reason"), str(o, "status", "validation", "message"))
	t.row("Streams:\t%s", str(o, "status", "validation", "streams"))
	t.row("Lookups:\t%s", str(o, "status", "validation", "lookups"))
	t.row("\nCONDITION\tSTATUS\tREASON\tMESSAGE")
	for _, c := range list(o, "status", "conditions") {
		t.row("%s\t%s\t%s\t%s", str(c, "type"), str(c, "status"), str(c, "reason"), str(c, "message"))
	}
	t.row("\nJOB\tSTATE\tLIFECYCLE\tRESTORED FROM\tLAST ERROR")
	t.row("%s\t%s\t%s\t%s\t%s", str(o, "status", "job", "deployment"), str(o, "status", "job", "state"),
		str(o, "status", "job", "lifecycle"), str(o, "status", "job", "restoredFrom"), str(o, "status", "job", "lastError"))
	t.row("\nUPSTREAM\tNEXT SNAPSHOT\tLATEST\tLAG")
	for _, u := range list(o, "status", "upstream") {
		t.row("%s\t%s\t%s\t%s", str(u, "table"), str(u, "nextSnapshot"), str(u, "latestSnapshot"), str(u, "lag"))
	}
}

// describeTask shows a LakeTask: its attempts, a backfill's fence, the run's result and the
// readers it waits for.
func describeTask(t *table, obj *unstructured.Unstructured) {
	o := obj.Object
	t.row("Name:\t%s/%s", obj.GetNamespace(), obj.GetName())
	t.row("Type:\t%s", str(o, "spec", "type"))
	t.row("Table:\t%s", taskTable(o))
	t.row("Phase:\t%s", str(o, "status", "phase"))
	t.row("Message:\t%s", str(o, "status", "message"))
	t.row("Attempts:\t%s (run %s)", str(o, "status", "attempts"), str(o, "status", "run"))
	t.row("Started:\t%s", str(o, "status", "startedAt"))
	t.row("Finished:\t%s", str(o, "status", "finishedAt"))
	t.row("Fence:\t%s %s..%s", str(o, "status", "fence", "lakeTable"), str(o, "status", "fence", "fencedAt"),
		str(o, "status", "fence", "releasedAt"))
	t.row("Snapshots:\t%s -> %s", str(o, "status", "result", "snapshotBefore"), str(o, "status", "result", "snapshotAfter"))
	t.row("Result:\t%s", str(o, "status", "result", "detail"))
	t.row("Waiting for:\t%s", str(o, "status", "waitingFor"))
	describeCheck(t, o)
	t.row("\nCONDITION\tSTATUS\tREASON\tMESSAGE")
	for _, c := range list(o, "status", "conditions") {
		t.row("%s\t%s\t%s\t%s", str(c, "type"), str(c, "status"), str(c, "reason"), str(c, "message"))
	}
}

// describeErasure shows a LakeErasure: its deadlines and each copy with its state and evidence.
func describeErasure(t *table, obj *unstructured.Unstructured) {
	o := obj.Object
	t.row("Name:\t%s/%s", obj.GetNamespace(), obj.GetName())
	t.row("Phase:\t%s", str(o, "status", "phase"))
	t.row("Message:\t%s", str(o, "status", "message"))
	t.row("Accepted:\t%s", str(o, "status", "acceptedAt"))
	t.row("Logical:\t%s (deadline %s)", str(o, "status", "logicallyErasedAt"), str(o, "status", "logicalDeadline"))
	t.row("Physical:\t%s (deadline %s)", str(o, "status", "physicallyErasedAt"), str(o, "status", "physicalDeadline"))
	t.row("\nCOPY\tNAME\tLAYER\tSTATE\tEVIDENCE OR MESSAGE")
	for _, c := range list(o, "status", "copies") {
		detail := str(c, "evidence")
		if str(c, "state") != "Done" {
			detail = str(c, "message")
		}
		t.row("%s\t%s\t%s\t%s\t%s", str(c, "kind"), str(c, "name"), str(c, "layer"), str(c, "state"), detail)
	}
}

func describeEvents(t *table, events []corev1.Event) error {
	sort.Slice(events, func(a, b int) bool { return events[a].LastTimestamp.Before(&events[b].LastTimestamp) })
	if len(events) > 20 {
		events = events[len(events)-20:]
	}
	t.row("\nEVENT TIME\tTYPE\tREASON\tMESSAGE")
	for _, e := range events {
		t.row("%s\t%s\t%s\t%s", e.LastTimestamp.UTC().Format("2006-01-02T15:04:05Z"), e.Type, e.Reason, e.Message)
	}
	return t.flush()
}

func list(obj map[string]any, path ...string) []map[string]any {
	values, found, err := unstructured.NestedSlice(obj, path...)
	if err != nil || !found {
		return nil
	}
	var out []map[string]any
	for _, v := range values {
		if m, ok := v.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}
