package cli

import (
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/spf13/cobra"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"github.com/colvenna/colvenna-lake/colvenna-cli/ops"
)

func applyCommand(o *options) *cobra.Command {
	var files []string
	cmd := &cobra.Command{
		Use:   "apply -f <file>...",
		Short: "Create or update declarations from YAML (server-side apply)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			if len(files) == 0 {
				return errors.New("-f is required")
			}
			var objects []*unstructured.Unstructured
			for _, f := range files {
				docs, err := readManifests(f)
				if err != nil {
					return err
				}
				objects = append(objects, docs...)
			}
			c, err := o.clients(o)
			if err != nil {
				return err
			}
			return apply(c, objects, cmd.OutOrStdout())
		},
	}
	cmd.Flags().StringArrayVarP(&files, "filename", "f", nil, "manifest file, or - for stdin")
	return cmd
}

func readManifests(path string) ([]*unstructured.Unstructured, error) {
	var data []byte
	var err error
	if path == "-" {
		data, err = io.ReadAll(os.Stdin)
	} else {
		data, err = os.ReadFile(path)
	}
	if err != nil {
		return nil, err
	}
	objects, err := ops.ReadManifests(data)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return objects, nil
}

// manager is the field manager of what the CLI applies.
const manager = "colvenna-cli"

// apply server-side applies Colvenna Lake objects (ops.Apply) and says what it applied.
func apply(c *ops.Clients, objects []*unstructured.Unstructured, out io.Writer) error {
	ctx, cancel := background()
	defer cancel()
	applied, err := ops.Apply(ctx, c, manager, objects)
	for _, a := range applied {
		_, _ = fmt.Fprintf(out, "%s applied\n", a)
	}
	return err
}
