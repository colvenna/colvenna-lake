package cli

import (
	"time"

	"github.com/spf13/cobra"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

// taskFlags are what every batch command takes: the task's name, its retries and timeout, and
// whether to wait for its end.
type taskFlags struct {
	name        string
	retries     string
	timeout     string
	wait        bool
	waitTimeout time.Duration
}

func (f *taskFlags) add(cmd *cobra.Command) {
	cmd.Flags().StringVar(&f.name, "name", "", "the LakeTask's name; the same command again finds it (required)")
	cmd.Flags().StringVar(&f.retries, "retries", "", "attempts after the first, 0 to 5 (the task's default: 2)")
	cmd.Flags().StringVar(&f.timeout, "timeout", "", "how long one attempt may run, e.g. 30m or 2h (default 1h)")
	cmd.Flags().BoolVar(&f.wait, "wait", false, "wait for the task to end: exit 0 if it Succeeded, 1 if it Failed, 2 on timeout")
	cmd.Flags().DurationVar(&f.waitTimeout, "wait-timeout", 4*time.Hour, "how long --wait waits")
	_ = cmd.MarkFlagRequired("name")
}

// submit applies the LakeTask (server-side apply: the same task again changes nothing, a changed
// one is refused by the API server) and waits for its end when asked.
func (f *taskFlags) submit(o *options, cmd *cobra.Command, task *unstructured.Unstructured, err error) error {
	if err != nil {
		return err
	}
	c, err := o.clients(o)
	if err != nil {
		return err
	}
	if err := apply(c, []*unstructured.Unstructured{task}, cmd.OutOrStdout()); err != nil {
		return err
	}
	if !f.wait {
		return nil
	}
	kind, _ := ops.LookupKind("LakeTask")
	return waitFor(c, kind, f.name, condition{kind: "phase", value: "Succeeded"}, f.waitTimeout,
		10*time.Second, cmd.OutOrStdout())
}

func (f *taskFlags) options() ops.TaskOptions {
	return ops.TaskOptions{Retries: f.retries, Timeout: f.timeout}
}

// backfillCommand is HLD 9.2's `colvenna backfill`: recompute a LakeTable's target from its upstream.
func backfillCommand(o *options) *cobra.Command {
	var f taskFlags
	cmd := &cobra.Command{
		Use:   "backfill <database.table> --name <task>",
		Short: "Recompute a LakeTable's target from its upstream; done once every table downstream read it",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Backfill(f.name, args[0], f.options())
			return f.submit(o, cmd, task, err)
		},
	}
	f.add(cmd)
	return cmd
}

// maintainCommand is HLD 9.2's `colvenna maintain`: one maintenance run on a table.
func maintainCommand(o *options) *cobra.Command {
	root := &cobra.Command{
		Use:   "maintain compact|expire|orphans|tag <database.table> --name <task>",
		Short: "Run one maintenance task on a table: compaction, snapshot expiry, orphan files, a tag",
	}
	var compact taskFlags
	var full bool
	var partitions []string
	compactCmd := &cobra.Command{
		Use:   "compact <database.table> --name <task>",
		Short: "Compact the table, or some of its partitions",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Compact(compact.name, args[0], full, partitions, compact.options())
			return compact.submit(o, cmd, task, err)
		},
	}
	compactCmd.Flags().BoolVar(&full, "full", false, "merge every level")
	compactCmd.Flags().StringArrayVar(&partitions, "partition", nil, "only this partition, as column=value[,column=value]; repeatable")
	compact.add(compactCmd)

	var expire taskFlags
	var retainMin int
	var olderThan string
	expireCmd := &cobra.Command{
		Use:   "expire <database.table> --name <task>",
		Short: "Expire snapshots; those a reader's consumer still needs and tagged ones stay",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Expire(expire.name, args[0], retainMin, olderThan, expire.options())
			return expire.submit(o, cmd, task, err)
		},
	}
	expireCmd.Flags().IntVar(&retainMin, "retain-min", 0, "snapshots kept whatever their age (default 10)")
	expireCmd.Flags().StringVar(&olderThan, "older-than", "", "only snapshots older than this, e.g. 1h or 2d (default 1h)")
	expire.add(expireCmd)

	var orphans taskFlags
	var orphansOlderThan string
	orphansCmd := &cobra.Command{
		Use:   "orphans <database.table> --name <task>",
		Short: "Remove files no snapshot, tag or branch refers to",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Orphans(orphans.name, args[0], orphansOlderThan, orphans.options())
			return orphans.submit(o, cmd, task, err)
		},
	}
	orphansCmd.Flags().StringVar(&orphansOlderThan, "older-than", "", "only files older than this, at least 1d (default 1d)")
	orphans.add(orphansCmd)

	var tag taskFlags
	var tagName, retained string
	tagCmd := &cobra.Command{
		Use:   "tag <database.table> --tag <tag> --name <task>",
		Short: "Tag the table's latest snapshot, for audit and replay",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			task, err := ops.Tag(tag.name, args[0], tagName, retained, tag.options())
			return tag.submit(o, cmd, task, err)
		},
	}
	tagCmd.Flags().StringVar(&tagName, "tag", "", "the tag's name (required)")
	tagCmd.Flags().StringVar(&retained, "time-retained", "", "how long the tag keeps its snapshot, e.g. 7d (default 7d)")
	_ = tagCmd.MarkFlagRequired("tag")
	tag.add(tagCmd)

	root.AddCommand(compactCmd, expireCmd, orphansCmd, tagCmd)
	return root
}
