package ops

import (
	"fmt"
	"sort"
	"strings"

	"k8s.io/apimachinery/pkg/runtime/schema"
)

// Group and Version of Colvenna Lake's resources.
const (
	Group   = "colvenna-lake.io"
	Version = "v1alpha1"
)

// Kind is one declarable resource and the names the CLI accepts for it.
type Kind struct {
	Kind     string
	Resource string
	Aliases  []string
}

// Kinds the CLI knows; the operator defines what each means.
var Kinds = []Kind{
	{Kind: "LakeSource", Resource: "lakesources", Aliases: []string{"source", "sources", "ls", "lakesource", "lakesources"}},
	{Kind: "LakeTable", Resource: "laketables", Aliases: []string{"table", "tables", "lt", "laketable", "laketables"}},
	{Kind: "LakeTask", Resource: "laketasks", Aliases: []string{"task", "tasks", "ltk", "laketask", "laketasks"}},
	{Kind: "LakeErasure", Resource: "lakeerasures", Aliases: []string{"erasure", "erasures", "ler", "lakeerasure", "lakeerasures"}},
	{Kind: "LakeDashboard", Resource: "lakedashboards", Aliases: []string{"dashboard", "dashboards", "ldb", "lakedashboard", "lakedashboards"}},
	{Kind: "LakeQuality", Resource: "lakequalities", Aliases: []string{"quality", "qualities", "lq", "lakequality", "lakequalities"}},
	{Kind: "LakeWaiver", Resource: "lakewaivers", Aliases: []string{"waiver", "waivers", "lw", "lakewaiver", "lakewaivers"}},
	{Kind: "LakeIcebergPublication", Resource: "lakeicebergpublications", Aliases: []string{"iceberg", "icebergs", "lip", "lakeicebergpublication", "lakeicebergpublications"}},
}

// GVR is the resource's group, version and plural name.
func (k Kind) GVR() schema.GroupVersionResource {
	return schema.GroupVersionResource{Group: Group, Version: Version, Resource: k.Resource}
}

// LookupKind finds a kind by any of its names, or by its Kubernetes kind for manifests.
func LookupKind(name string) (Kind, error) {
	for _, k := range Kinds {
		if strings.EqualFold(name, k.Kind) {
			return k, nil
		}
		for _, a := range k.Aliases {
			if strings.EqualFold(name, a) {
				return k, nil
			}
		}
	}
	var names []string
	for _, k := range Kinds {
		names = append(names, k.Aliases[0])
	}
	sort.Strings(names)
	return Kind{}, fmt.Errorf("unknown kind %q; known: %s", name, strings.Join(names, ", "))
}
