package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/spf13/cobra"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"sigs.k8s.io/yaml"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func getCommand(o *options) *cobra.Command {
	var output string
	cmd := &cobra.Command{
		Use:   "get <kind> [name]",
		Short: "List declarations, or show one, with their status",
		Args:  cobra.RangeArgs(1, 2),
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
			var items []unstructured.Unstructured
			if len(args) == 2 {
				obj, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Get(ctx, args[1], metav1.GetOptions{})
				if err != nil {
					return err
				}
				items = []unstructured.Unstructured{*obj}
			} else {
				list, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).List(ctx, metav1.ListOptions{})
				if err != nil {
					return err
				}
				items = list.Items
			}
			return printObjects(cmd.OutOrStdout(), items, output, time.Now())
		},
	}
	cmd.Flags().StringVarP(&output, "output", "o", "", "json or yaml for the full objects")
	return cmd
}

func printObjects(out io.Writer, items []unstructured.Unstructured, output string, now time.Time) error {
	switch output {
	case "json":
		objects := make([]map[string]any, 0, len(items))
		for _, i := range items {
			objects = append(objects, i.Object)
		}
		data, err := json.MarshalIndent(objects, "", "  ")
		if err != nil {
			return err
		}
		_, err = fmt.Fprintln(out, string(data))
		return err
	case "yaml":
		for _, i := range items {
			data, err := yaml.Marshal(i.Object)
			if err != nil {
				return err
			}
			if _, err := fmt.Fprintf(out, "---\n%s", data); err != nil {
				return err
			}
		}
		return nil
	case "":
	default:
		return fmt.Errorf("unknown output %q", output)
	}
	sort.Slice(items, func(a, b int) bool { return items[a].GetName() < items[b].GetName() })
	t := newTable(out)
	if len(items) > 0 && items[0].GetKind() == "LakeTable" {
		t.row("NAME\tTARGET\tPHASE\tAGE\tMESSAGE")
		for _, i := range items {
			t.row("%s\t%s\t%s\t%s\t%s", i.GetName(), tableTarget(i.Object),
				str(i.Object, "status", "phase"), age(i.GetCreationTimestamp().Time, now),
				str(i.Object, "status", "message"))
		}
		return t.flush()
	}
	if len(items) > 0 && items[0].GetKind() == "LakeErasure" {
		t.row("NAME\tPHASE\tLOGICAL\tPHYSICAL\tAGE\tMESSAGE")
		for _, i := range items {
			t.row("%s\t%s\t%s\t%s\t%s\t%s", i.GetName(), str(i.Object, "status", "phase"),
				str(i.Object, "status", "logicallyErasedAt"), str(i.Object, "status", "physicallyErasedAt"),
				age(i.GetCreationTimestamp().Time, now), str(i.Object, "status", "message"))
		}
		return t.flush()
	}
	if len(items) > 0 && items[0].GetKind() == "LakeDashboard" {
		t.row("NAME\tDATABASE\tPHASE\tAGE\tMESSAGE")
		for _, i := range items {
			t.row("%s\t%s\t%s\t%s\t%s", i.GetName(), str(i.Object, "status", "database"),
				str(i.Object, "status", "phase"), age(i.GetCreationTimestamp().Time, now),
				str(i.Object, "status", "message"))
		}
		return t.flush()
	}
	if len(items) > 0 && items[0].GetKind() == "LakeTask" {
		t.row("NAME\tTYPE\tTABLE\tPHASE\tATTEMPTS\tAGE\tMESSAGE")
		for _, i := range items {
			t.row("%s\t%s\t%s\t%s\t%s\t%s\t%s", i.GetName(), str(i.Object, "spec", "type"),
				taskTable(i.Object), str(i.Object, "status", "phase"), str(i.Object, "status", "attempts"),
				age(i.GetCreationTimestamp().Time, now), str(i.Object, "status", "message"))
		}
		return t.flush()
	}
	t.row("NAME\tTYPE\tTARGET\tPHASE\tREADY\tAGE\tMESSAGE")
	for _, i := range items {
		t.row("%s\t%s\t%s\t%s\t%s\t%s\t%s", i.GetName(),
			str(i.Object, "spec", "type"), str(i.Object, "spec", "target", "database"),
			str(i.Object, "status", "phase"), str(i.Object, "status", "summary"),
			age(i.GetCreationTimestamp().Time, now), str(i.Object, "status", "message"))
	}
	return t.flush()
}

// taskTable is the database.table a LakeTask runs on.
func taskTable(obj map[string]any) string {
	return str(obj, "spec", "table", "database") + "." + str(obj, "spec", "table", "table")
}

// tableTarget is a LakeTable's database.table.
func tableTarget(obj map[string]any) string {
	return str(obj, "spec", "target", "database") + "." + str(obj, "spec", "target", "table")
}

func str(obj map[string]any, path ...string) string {
	value, found, err := unstructured.NestedFieldNoCopy(obj, path...)
	if err != nil || !found || value == nil {
		return "-"
	}
	return strings.TrimSpace(fmt.Sprint(value))
}

func age(created, now time.Time) string {
	if created.IsZero() {
		return "-"
	}
	d := now.Sub(created).Round(time.Second)
	switch {
	case d >= 48*time.Hour:
		return fmt.Sprintf("%dd", int(d.Hours()/24))
	case d >= time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	case d >= time.Minute:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	default:
		return fmt.Sprintf("%ds", int(d.Seconds()))
	}
}
