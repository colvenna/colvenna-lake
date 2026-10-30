package cli

import (
	"context"
	"fmt"
	"time"

	"github.com/spf13/cobra"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func suspendCommand(o *options, suspend bool) *cobra.Command {
	use, done, short := "suspend", "suspended", "Stop a source's jobs, keeping their position"
	if !suspend {
		use, done, short = "resume", "resumed", "Start a suspended source's jobs from where they stopped"
	}
	return &cobra.Command{
		Use:   use + " <kind> <name>",
		Short: short,
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
			if err := ops.Suspend(ctx, c, manager, kind, args[1], suspend); err != nil {
				return err
			}
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "%s/%s %s\n", kind.Aliases[0], args[1], done)
			return nil
		},
	}
}

func deleteCommand(o *options) *cobra.Command {
	var wait bool
	var timeout time.Duration
	cmd := &cobra.Command{
		Use:   "delete <kind> <name>",
		Short: "Delete a declaration; its spec.deletionPolicy decides what happens to its tables",
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
			resource := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace)
			ctx, cancel := background()
			err = ops.Delete(ctx, c, kind, args[1])
			cancel()
			if err != nil {
				return err
			}
			if wait {
				deadline, stop := context.WithTimeout(context.Background(), timeout)
				defer stop()
				for {
					_, err := resource.Get(deadline, args[1], metav1.GetOptions{})
					if apierrors.IsNotFound(err) {
						break
					}
					select {
					case <-deadline.Done():
						return &waitTimeout{fmt.Sprintf("%s/%s still exists after %s", kind.Aliases[0], args[1], timeout)}
					case <-time.After(2 * time.Second):
					}
				}
			}
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "%s/%s deleted\n", kind.Aliases[0], args[1])
			return nil
		},
	}
	cmd.Flags().BoolVar(&wait, "wait", true, "wait until the operator has cleaned up")
	cmd.Flags().DurationVar(&timeout, "timeout", 10*time.Minute, "how long to wait")
	return cmd
}
