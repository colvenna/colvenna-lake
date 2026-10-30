// Package cli implements the colvenna commands.
package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/spf13/cobra"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

type options struct {
	kubeconfig string
	context    string
	namespace  string
	clients    func(o *options) (*ops.Clients, error)
}

// Main runs the CLI and returns the process exit code: 0 on success, 1 when a command fails, 2
// when a wait ends without its condition (so an orchestrator can tell a timeout from an error),
// and 3 when a waited-for quality check failed rules.
func Main(args []string, stdout, stderr io.Writer) int {
	return run(args, stdout, stderr, defaultClients)
}

func run(args []string, stdout, stderr io.Writer, clients func(o *options) (*ops.Clients, error)) int {
	o := &options{clients: clients}
	root := &cobra.Command{
		Use:           "colvenna",
		Short:         "Declare and inspect Colvenna Lake sources",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.PersistentFlags().StringVar(&o.kubeconfig, "kubeconfig", "", "kubeconfig file (default: $KUBECONFIG or ~/.kube/config)")
	root.PersistentFlags().StringVar(&o.context, "context", "", "kubeconfig context")
	root.PersistentFlags().StringVarP(&o.namespace, "namespace", "n", "", "namespace (default: the context's)")
	root.SetOut(stdout)
	root.SetErr(stderr)
	root.SetArgs(args)
	root.AddCommand(applyCommand(o), getCommand(o), describeCommand(o), suspendCommand(o, true),
		suspendCommand(o, false), deleteCommand(o), waitCommand(o), backfillCommand(o), maintainCommand(o),
		forgetCommand(o), checkCommand(o), waiveCommand(o), versionCommand())
	if err := root.Execute(); err != nil {
		_, _ = fmt.Fprintln(stderr, "error:", err)
		var timeout *waitTimeout
		if errors.As(err, &timeout) {
			return 2
		}
		var failed *qualityFailed
		if errors.As(err, &failed) {
			return 3
		}
		return 1
	}
	return 0
}

func defaultClients(o *options) (*ops.Clients, error) {
	rules := clientcmd.NewDefaultClientConfigLoadingRules()
	if o.kubeconfig != "" {
		rules.ExplicitPath = o.kubeconfig
	}
	config := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(rules, &clientcmd.ConfigOverrides{CurrentContext: o.context})
	rest, err := config.ClientConfig()
	if err != nil {
		return nil, err
	}
	rest.Timeout = 30 * time.Second
	namespace, _, err := config.Namespace()
	if err != nil {
		return nil, err
	}
	if o.namespace != "" {
		namespace = o.namespace
	}
	dyn, err := dynamic.NewForConfig(rest)
	if err != nil {
		return nil, err
	}
	core, err := kubernetes.NewForConfig(rest)
	if err != nil {
		return nil, err
	}
	return &ops.Clients{Dynamic: dyn, Core: core, Namespace: namespace}, nil
}

func versionCommand() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the CLI version",
		RunE: func(cmd *cobra.Command, _ []string) error {
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "colvenna %s (%s/%s)\n", version, ops.Group, ops.Version)
			return nil
		},
	}
}

// version is set at build time with -ldflags "-X .../cli.version=<sha>".
var version = "dev"

func background() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), 60*time.Second)
}
