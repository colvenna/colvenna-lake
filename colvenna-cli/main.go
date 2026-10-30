// Command colvenna is Colvenna Lake's command line: a thin client of the Kubernetes API that applies
// declarations and reads their status. Admission, authorization and every other decision stay in
// the operator and colvenna-catalog, so the CLI never widens what the platform allows.
package main

import (
	"os"

	"github.com/colvenna/colvenna-lake/colvenna-cli/internal/cli"
)

func main() {
	os.Exit(cli.Main(os.Args[1:], os.Stdout, os.Stderr))
}
