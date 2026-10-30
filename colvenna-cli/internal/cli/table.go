package cli

import (
	"fmt"
	"io"
	"text/tabwriter"
)

// table writes aligned columns and keeps the first write error for flush to return.
type table struct {
	w   *tabwriter.Writer
	err error
}

func newTable(out io.Writer) *table {
	return &table{w: tabwriter.NewWriter(out, 0, 4, 2, ' ', 0)}
}

func (t *table) row(format string, args ...any) {
	if t.err == nil {
		_, t.err = fmt.Fprintf(t.w, format+"\n", args...)
	}
}

func (t *table) flush() error {
	if err := t.w.Flush(); t.err == nil {
		t.err = err
	}
	return t.err
}
