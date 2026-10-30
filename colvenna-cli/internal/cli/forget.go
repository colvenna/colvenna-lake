package cli

import (
	"fmt"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

// forgetCommand is HLD 12.7's `colvenna forget`: erase one data subject across the namespace's managed
// copies. The subject's key values are personal data: they are read from a file (one per line,
// tab-separated for a key of several columns) into a Secret of the erasure's name, which the
// erasure then owns (it goes with it), and appear in no argument, output or declaration.
func forgetCommand(o *options) *cobra.Command {
	var subjectFile, logical, physical string
	var wait bool
	var waitTimeout time.Duration
	cmd := &cobra.Command{
		Use: "forget <erasure> --subject-file <file>",
		Short: "Erase a data subject across the managed copies; logically by " + ops.LogicalDeadline +
			", physically by " + ops.PhysicalDeadline,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			values, err := os.ReadFile(subjectFile)
			if err != nil {
				return fmt.Errorf("--subject-file: %w", err)
			}
			c, err := o.clients(o)
			if err != nil {
				return err
			}
			ctx, cancel := background()
			defer cancel()
			if err := ops.Forget(ctx, c, manager, name, values, logical, physical); err != nil {
				return err
			}
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "erasure/%s created\n", name)
			kind, _ := ops.LookupKind("LakeErasure")
			if !wait {
				return nil
			}
			return waitFor(c, kind, name, condition{kind: "phase", value: "PhysicallyErased"}, waitTimeout,
				time.Minute, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringVar(&subjectFile, "subject-file", "", "file with the subject's key values, one per line (required)")
	cmd.Flags().StringVar(&logical, "logical", "", "logical deadline, at most "+ops.LogicalDeadline+" (the default)")
	cmd.Flags().StringVar(&physical, "physical", "", "physical deadline, at most "+ops.PhysicalDeadline+" (the default)")
	cmd.Flags().BoolVar(&wait, "wait", false, "wait until physically erased: exit 0, 1 if it failed, 2 on timeout")
	cmd.Flags().DurationVar(&waitTimeout, "wait-timeout", 9*24*time.Hour, "how long --wait waits")
	_ = cmd.MarkFlagRequired("subject-file")
	return cmd
}
