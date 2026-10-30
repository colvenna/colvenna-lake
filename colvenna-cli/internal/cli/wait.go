package cli

import (
	"context"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/spf13/cobra"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

// waitTimeout is a wait that ended without its condition: exit code 2.
type waitTimeout struct{ message string }

func (w *waitTimeout) Error() string { return w.message }

// condition is what `colvenna wait --for` accepts:
//
//	ready                 the Ready condition is True
//	phase=<Phase>         status.phase equals Phase
//	table/<name>=<State>  the table's state equals State
type condition struct {
	kind  string
	key   string
	value string
}

// String is the condition as --for takes it.
func (c condition) String() string {
	switch c.kind {
	case "ready":
		return "ready"
	case "phase":
		return "phase=" + c.value
	}
	return "table/" + c.key + "=" + c.value
}

func parseCondition(text string) (condition, error) {
	if text == "ready" {
		return condition{kind: "ready"}, nil
	}
	key, value, ok := strings.Cut(text, "=")
	if !ok || value == "" {
		return condition{}, fmt.Errorf("--for %q: use ready, phase=<Phase> or table/<name>=<State>", text)
	}
	if key == "phase" {
		return condition{kind: "phase", value: value}, nil
	}
	if table, ok := strings.CutPrefix(key, "table/"); ok && table != "" {
		return condition{kind: "table", key: table, value: value}, nil
	}
	return condition{}, fmt.Errorf("--for %q: use ready, phase=<Phase> or table/<name>=<State>", text)
}

// holds reports whether the condition holds, and an error when it never can (a failed source or
// table).
func (c condition) holds(obj map[string]any) (bool, error) {
	phase := str(obj, "status", "phase")
	switch c.kind {
	case "ready":
		for _, cond := range list(obj, "status", "conditions") {
			if str(cond, "type") == "Ready" && str(cond, "status") == "True" {
				return true, nil
			}
		}
	case "phase":
		if phase == c.value {
			return true, nil
		}
	case "table":
		for _, t := range list(obj, "status", "tables") {
			if str(t, "name") == c.key {
				state := str(t, "state")
				if state == c.value {
					return true, nil
				}
				// Failed is final; a Rejected table is admitted once its source is fixed.
				if c.value != "Failed" && state == "Failed" {
					return false, fmt.Errorf("table %s is %s: %s", c.key, state, str(t, "message"))
				}
			}
		}
	}
	if phase == "Failed" && (c.kind != "phase" || c.value != "Failed") {
		return false, fmt.Errorf("failed: %s", str(obj, "status", "message"))
	}
	return false, nil
}

func waitCommand(o *options) *cobra.Command {
	var forText string
	var timeout, interval time.Duration
	cmd := &cobra.Command{
		Use:   "wait <kind> <name> --for <condition>",
		Short: "Wait for a condition: exit 0 when it holds, 1 if it cannot, 2 on timeout",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			kind, err := ops.LookupKind(args[0])
			if err != nil {
				return err
			}
			cond, err := parseCondition(forText)
			if err != nil {
				return err
			}
			c, err := o.clients(o)
			if err != nil {
				return err
			}
			return waitFor(c, kind, args[1], cond, timeout, interval, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringVar(&forText, "for", "ready", "ready, phase=<Phase> or table/<name>=<State>")
	cmd.Flags().DurationVar(&timeout, "timeout", 10*time.Minute, "how long to wait")
	cmd.Flags().DurationVar(&interval, "interval", 5*time.Second, "how often to look")
	return cmd
}

// waitFor polls the object until the condition holds (nil), cannot hold (an error) or the timeout
// passes (a *waitTimeout).
func waitFor(c *ops.Clients, kind ops.Kind, name string, cond condition, timeout, interval time.Duration, out io.Writer) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	for {
		obj, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Get(ctx, name, metav1.GetOptions{})
		if err == nil {
			ok, err := cond.holds(obj.Object)
			if err != nil {
				return fmt.Errorf("%s/%s %w", kind.Aliases[0], name, err)
			}
			if ok {
				_, _ = fmt.Fprintf(out, "%s/%s %s\n", kind.Aliases[0], name, cond)
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return &waitTimeout{fmt.Sprintf("%s/%s: %s not reached within %s (phase %s)",
				kind.Aliases[0], name, cond, timeout, phaseOf(obj))}
		case <-time.After(interval):
		}
	}
}

func phaseOf(obj *unstructured.Unstructured) string {
	if obj == nil {
		return "unknown"
	}
	return str(obj.Object, "status", "phase")
}
