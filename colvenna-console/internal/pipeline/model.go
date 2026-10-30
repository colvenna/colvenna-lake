// Package pipeline is the console's picture of the whole pipeline: each source, lake table, table
// job and dashboard, how data flows between them, their state, measures and alerts, and where a
// problem is. It is read afresh from the authoritative places each time (the declarations' status
// in Kubernetes, Prometheus's metrics and alerts) and kept nowhere, so it cannot drift from them.
// The flow is the declarations' own: what each source writes, what each table job reads and
// writes, what each dashboard reads; it is what the operator reports as lineage.
package pipeline

import (
	"context"
	"fmt"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

// The kinds of node.
const (
	Source    = "source"
	Table     = "table"
	Job       = "job"
	Dashboard = "dashboard"
)

// Condition is a declaration's status condition.
type Condition struct {
	Type    string `json:"type"`
	Status  string `json:"status"`
	Reason  string `json:"reason,omitempty"`
	Message string `json:"message,omitempty"`
	Since   string `json:"since,omitempty"`
}

// FlinkJob is a Flink deployment a declaration runs, as the Flink operator reports it.
type FlinkJob struct {
	Deployment string `json:"deployment"`
	Lifecycle  string `json:"lifecycle,omitempty"`
	State      string `json:"state,omitempty"`
	JobID      string `json:"jobId,omitempty"`
}

// Upstream is how far a table job is behind one table it reads.
type Upstream struct {
	Table        string   `json:"table"`
	LagSnapshots *float64 `json:"lagSnapshots,omitempty"`
	LagSeconds   *float64 `json:"lagSeconds,omitempty"`
}

// Query is one of a dashboard's queries as served.
type Query struct {
	Name              string   `json:"name"`
	Serve             string   `json:"serve,omitempty"`
	RefreshedAt       string   `json:"refreshedAt,omitempty"`
	RefreshAgeSeconds *float64 `json:"refreshAgeSeconds,omitempty"`
	Message           string   `json:"message,omitempty"`
}

// Task is a LakeTask on a table.
type Task struct {
	Name      string `json:"name"`
	Type      string `json:"type"`
	Phase     string `json:"phase,omitempty"`
	StartedAt string `json:"startedAt,omitempty"`
}

// Node is one part of the pipeline.
type Node struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	Namespace string `json:"namespace,omitempty"`
	Name      string `json:"name"`
	Phase     string `json:"phase,omitempty"`
	Message   string `json:"message,omitempty"`
	// DeletionPolicy is what deleting the declaration does to its tables (spec.deletionPolicy).
	DeletionPolicy string `json:"deletionPolicy,omitempty"`
	// Conditions, Jobs, Upstream, Queries and Tasks are what the status says, the fields the
	// console shows and no others: no identities, tokens, password seals or policies.
	Conditions []Condition        `json:"conditions,omitempty"`
	Jobs       []FlinkJob         `json:"jobs,omitempty"`
	Upstream   []Upstream         `json:"upstream,omitempty"`
	Queries    []Query            `json:"queries,omitempty"`
	Tasks      []Task             `json:"tasks,omitempty"`
	Measures   map[string]float64 `json:"measures,omitempty"`
	Alerts     []Alert            `json:"alerts,omitempty"`
	Problem    bool               `json:"problem"`
	Affected   bool               `json:"affected"`
	// AffectedBy are the problems upstream of it, by id.
	AffectedBy []string `json:"affectedBy,omitempty"`
	Why        []string `json:"why,omitempty"`
	Depth      int      `json:"depth"`
}

// Edge is data flowing from one node to another.
type Edge struct {
	From string `json:"from"`
	To   string `json:"to"`
}

// Pipeline is the picture at one moment.
type Pipeline struct {
	At     time.Time `json:"at"`
	Nodes  []*Node   `json:"nodes"`
	Edges  []Edge    `json:"edges"`
	Alerts []Alert   `json:"alerts,omitempty"`
	byID   map[string]*Node
	// namespaces are the ones read.
	namespaces map[string]bool
}

// Node is the node of that id, or nil.
func (p *Pipeline) Node(id string) *Node { return p.byID[id] }

// Downstream are the nodes data flows to from that one, near to far.
func (p *Pipeline) Downstream(id string) []*Node {
	out := map[string][]string{}
	for _, e := range p.Edges {
		out[e.From] = append(out[e.From], e.To)
	}
	var found []*Node
	seen := map[string]bool{id: true}
	queue := append([]string(nil), out[id]...)
	for len(queue) > 0 {
		next := queue[0]
		queue = queue[1:]
		if seen[next] {
			continue
		}
		seen[next] = true
		if n := p.byID[next]; n != nil {
			found = append(found, n)
		}
		queue = append(queue, out[next]...)
	}
	return found
}

// Writer is the node that writes a lake table, or nil.
func (p *Pipeline) Writer(table string) *Node {
	for _, e := range p.Edges {
		if e.To == ID(Table, "", table) {
			if n := p.byID[e.From]; n != nil && n.Kind != Table {
				return n
			}
		}
	}
	return nil
}

// ID is a node's id: <kind>:<namespace>/<name>, or table:<database>.<table> for a lake table.
func ID(kind, namespace, name string) string {
	if kind == Table {
		return Table + ":" + name
	}
	return kind + ":" + namespace + "/" + name
}

// Reader reads what the picture is made of.
type Reader struct {
	Kubernetes interface {
		Read(context.Context) (Objects, error)
	}
	Prometheus interface {
		Query(context.Context, string) ([]Sample, error)
		Alerts(context.Context) ([]Alert, error)
	}
	Now func() time.Time
}

// Read is the pipeline now. Without Prometheus it is still the declarations' picture, and says
// that measures are missing.
func (r *Reader) Read(ctx context.Context) (*Pipeline, error) {
	objects, err := r.Kubernetes.Read(ctx)
	if err != nil {
		return nil, err
	}
	p := Build(objects, r.Now())
	for _, m := range measures {
		samples, err := r.Prometheus.Query(ctx, m.expr)
		if err != nil {
			p.Alerts = append(p.Alerts, Alert{Name: "ConsoleMeasuresMissing", State: "firing",
				Summary: fmt.Sprintf("Prometheus did not answer %s: %v", m.name, err)})
			break
		}
		p.apply(m, samples)
	}
	alerts, err := r.Prometheus.Alerts(ctx)
	if err != nil {
		p.Alerts = append(p.Alerts, Alert{Name: "ConsoleAlertsMissing", State: "firing",
			Summary: fmt.Sprintf("Prometheus did not answer for alerts: %v", err)})
	}
	p.attach(alerts)
	p.locate()
	return p, nil
}

// Build is the declarations' picture: nodes, the flow between them, their status.
func Build(o Objects, now time.Time) *Pipeline {
	// Empty lists, not null, when nothing is declared.
	p := &Pipeline{At: now.UTC(), Nodes: []*Node{}, Edges: []Edge{}, byID: map[string]*Node{}, namespaces: map[string]bool{}}
	for _, ns := range o.Namespaces {
		p.namespaces[ns] = true
	}
	deployments := map[string]FlinkJob{}
	for _, d := range o.Deployments {
		deployments[d.GetNamespace()+"/"+d.GetName()] = FlinkJob{
			Deployment: d.GetName(),
			Lifecycle:  str(d.Object, "status", "lifecycleState"),
			State:      str(d.Object, "status", "jobStatus", "state"),
			JobID:      str(d.Object, "status", "jobStatus", "jobId"),
		}
	}
	for _, s := range o.Sources {
		n := p.declared(Source, s)
		for _, target := range writtenBy(s) {
			p.edge(n.ID, p.table(target).ID)
		}
		// A source runs one job or several (the tables split between them).
		for _, j := range slice(s.Object, "status", "jobs") {
			n.Jobs = append(n.Jobs, jobOf(j, deployments, s.GetNamespace()))
		}
	}
	for _, t := range o.Tables {
		n := p.declared(Job, t)
		reads, writes := readsAndWrites(t)
		for _, up := range reads {
			p.edge(p.table(up).ID, n.ID)
		}
		for _, target := range writes {
			p.edge(n.ID, p.table(target).ID)
		}
		if j, ok := field(t.Object, "status", "job").(map[string]any); ok {
			n.Jobs = append(n.Jobs, jobOf(j, deployments, t.GetNamespace()))
		}
		for _, u := range slice(t.Object, "status", "upstream") {
			n.Upstream = append(n.Upstream, Upstream{Table: str(u, "table"), LagSnapshots: number(u, "lag"),
				LagSeconds: number(u, "lagSeconds")})
		}
	}
	for _, d := range o.Dashboards {
		n := p.declared(Dashboard, d)
		for _, t := range nonBlank(d.Object, "spec", "tables") {
			p.edge(p.table(t).ID, n.ID)
		}
		for _, q := range slice(d.Object, "status", "queries") {
			n.Queries = append(n.Queries, Query{Name: str(q, "name"), Serve: str(q, "serve"),
				RefreshedAt: str(q, "refreshedAt"), RefreshAgeSeconds: number(q, "refreshAgeSeconds"),
				Message: str(q, "message")})
		}
	}
	for _, task := range o.Tasks {
		// The table it works on: its status says once it started, its spec ({database, table}) before.
		table := str(task.Object, "status", "table")
		if table == "" {
			table = str(task.Object, "spec", "table", "database") + "." + str(task.Object, "spec", "table", "table")
		}
		n := p.byID[ID(Table, "", table)]
		if n == nil {
			continue
		}
		n.Tasks = append(n.Tasks, Task{Name: task.GetNamespace() + "/" + task.GetName(), Type: str(task.Object, "spec", "type"),
			Phase: str(task.Object, "status", "phase"), StartedAt: str(task.Object, "status", "startedAt")})
	}
	p.depths()
	sort.Slice(p.Nodes, func(i, j int) bool {
		if p.Nodes[i].Depth != p.Nodes[j].Depth {
			return p.Nodes[i].Depth < p.Nodes[j].Depth
		}
		return p.Nodes[i].ID < p.Nodes[j].ID
	})
	sort.Slice(p.Edges, func(i, j int) bool {
		return p.Edges[i].From+p.Edges[i].To < p.Edges[j].From+p.Edges[j].To
	})
	return p
}

// writtenBy are the lake tables a source writes, as the operator reports them to the lineage
// catalog: an event source's table and its rejections; a CDC source's tables that have one.
func writtenBy(s unstructured.Unstructured) []string {
	db := str(s.Object, "spec", "target", "database")
	if field(s.Object, "spec", "kafka") != nil {
		table := str(s.Object, "spec", "target", "table")
		return []string{db + "." + table, db + "." + table + "_rejections"}
	}
	var out []string
	for _, t := range slice(s.Object, "status", "tables") {
		if target := str(t, "target"); target != "" {
			out = append(out, db+"."+target)
		}
	}
	return out
}

// readsAndWrites are what a table job reads and writes: as the planner found them for the query it runs
// (what the operator reports to the lineage catalog), its target and quarantine; before anything
// runs, as declared.
func readsAndWrites(t unstructured.Unstructured) (reads, writes []string) {
	db := str(t.Object, "spec", "target", "database")
	applied, _ := field(t.Object, "status", "applied").(map[string]any)
	if applied == nil {
		return nonBlank(t.Object, "spec", "upstream"), []string{db + "." + str(t.Object, "spec", "target", "table")}
	}
	for _, up := range append(nonBlank(applied, "streams"), nonBlank(applied, "lookups")...) {
		if !slices.Contains(reads, up) {
			reads = append(reads, up)
		}
	}
	writes = []string{str(applied, "target")}
	if q := str(applied, "quarantine"); q != "" {
		writes = append(writes, db+"."+q)
	}
	return reads, writes
}

// jobOf is a status's job with its deployment's state, when the deployment is there.
func jobOf(j map[string]any, deployments map[string]FlinkJob, namespace string) FlinkJob {
	if d, ok := deployments[namespace+"/"+str(j, "deployment")]; ok {
		if d.JobID == "" {
			d.JobID = str(j, "jobId")
		}
		return d
	}
	return FlinkJob{Deployment: str(j, "deployment"), JobID: str(j, "jobId")}
}

// declared is the node of a declaration, with its status.
func (p *Pipeline) declared(kind string, o unstructured.Unstructured) *Node {
	n := p.node(kind, o.GetNamespace(), o.GetName())
	n.Phase = str(o.Object, "status", "phase")
	n.Message = str(o.Object, "status", "message")
	n.DeletionPolicy = str(o.Object, "spec", "deletionPolicy")
	for _, c := range slice(o.Object, "status", "conditions") {
		n.Conditions = append(n.Conditions, Condition{
			Type: str(c, "type"), Status: str(c, "status"), Reason: str(c, "reason"),
			Message: str(c, "message"), Since: str(c, "lastTransitionTime"),
		})
	}
	return n
}

// table is a lake table's node: it has no declaration of its own.
func (p *Pipeline) table(name string) *Node { return p.node(Table, "", name) }

func (p *Pipeline) node(kind, namespace, name string) *Node {
	id := ID(kind, namespace, name)
	n := p.byID[id]
	if n == nil {
		n = &Node{ID: id, Kind: kind, Namespace: namespace, Name: name, Measures: map[string]float64{}}
		p.byID[id] = n
		p.Nodes = append(p.Nodes, n)
	}
	return n
}

func (p *Pipeline) edge(from, to string) {
	for _, e := range p.Edges {
		if e.From == from && e.To == to {
			return
		}
	}
	p.Edges = append(p.Edges, Edge{From: from, To: to})
}

// depths are each node's longest distance from a node nothing flows into: its column.
func (p *Pipeline) depths() {
	into := map[string][]string{}
	for _, e := range p.Edges {
		into[e.To] = append(into[e.To], e.From)
	}
	memo := map[string]int{}
	var depth func(id string, seen map[string]bool) int
	depth = func(id string, seen map[string]bool) int {
		if d, ok := memo[id]; ok {
			return d
		}
		if seen[id] {
			return 0 // a cycle in declarations: shown, not followed
		}
		seen[id] = true
		d := 0
		for _, from := range into[id] {
			d = max(d, depth(from, seen)+1)
		}
		delete(seen, id)
		memo[id] = d
		return d
	}
	for _, n := range p.Nodes {
		n.Depth = depth(n.ID, map[string]bool{})
	}
}

// owner is the node a series or an alert belongs to by its labels: the table job (laketable),
// the source, the dashboard (lakedashboard) or the lake table, in that order; "" for none.
func owner(l map[string]string) string {
	switch {
	case l["laketable"] != "":
		return ID(Job, l["namespace"], l["laketable"])
	case l["source"] != "":
		return ID(Source, l["namespace"], l["source"])
	case l["lakedashboard"] != "":
		return ID(Dashboard, l["namespace"], l["lakedashboard"])
	case l["table"] != "":
		return ID(Table, "", l["table"])
	}
	return ""
}

// tableOf is the node of a series that measures a lake table, whatever declaration exported it.
func tableOf(l map[string]string) string {
	if l["table"] == "" {
		return ""
	}
	return ID(Table, "", l["table"])
}

// measure is a measure the console shows: a PromQL query and the node each series belongs to.
type measure struct {
	name string
	expr string
	// node is the id of the series' node, "" for none.
	node func(labels map[string]string) string
	// count is a number of things; the others are seconds.
	count bool
}

// The measures the acceptance contract defines for each path (docs/history/m3/monitoring.md), as
// the operator exports them.
// Several series of one node keep the worst, the largest: lags, ages and counts all are.
var measures = []measure{
	{"sourceLatencySeconds", "colvenna_source_latency_seconds", owner, false},
	{"sourceFreshnessSeconds", "colvenna_source_freshness_seconds", owner, false},
	{"kafkaLag", "sum by (namespace, source) (colvenna_source_kafka_lag)", owner, true},
	{"upstreamLagSeconds", "colvenna_table_upstream_lag_seconds", owner, false},
	{"layerLatencySeconds", "colvenna_table_layer_latency_seconds", owner, false},
	{"quarantinedRows", "sum by (namespace, laketable) (colvenna_table_quarantined_rows)", owner, true},
	{"secondsSinceCheckpoint", "time() - colvenna_job_last_checkpoint_timestamp_seconds", owner, false},
	{"freshnessSeconds", "colvenna_table_freshness_seconds", tableOf, false},
	{"level0Files", "colvenna_table_level0_files", tableOf, true},
	{"smallFiles", "colvenna_table_small_files", tableOf, true},
	{"snapshots", "colvenna_table_snapshots", tableOf, true},
	{"copyAgeSeconds", "time() - colvenna_dashboard_refreshed_timestamp_seconds", owner, false},
}

// Show is a measure's value as people read it: a count, or a duration.
func Show(name string, v float64) string {
	for _, m := range measures {
		if m.name == name && m.count {
			return Count(&v)
		}
	}
	return Seconds(&v)
}

// Count is a status's number of things, "-" when it has none.
func Count(v *float64) string {
	if v == nil {
		return "-"
	}
	return strconv.FormatFloat(*v, 'f', 0, 64)
}

// Seconds is a status's number of seconds as a duration, "-" when it has none.
func Seconds(v *float64) string {
	if v == nil || *v < 0 {
		return "-"
	}
	return time.Duration(*v * float64(time.Second)).Round(time.Second).String()
}

func (p *Pipeline) apply(m measure, samples []Sample) {
	for _, s := range samples {
		n := p.byID[m.node(s.Labels)]
		if n == nil {
			continue
		}
		if v, ok := n.Measures[m.name]; ok {
			n.Measures[m.name] = max(v, s.Value)
		} else {
			n.Measures[m.name] = s.Value
		}
	}
}

// attach gives each alert to the node its labels name; the rest stay the pipeline's.
func (p *Pipeline) attach(alerts []Alert) {
	for _, a := range alerts {
		if ns := a.Labels["namespace"]; ns != "" && !p.namespaces[ns] {
			continue // another namespace's, which the console does not show
		}
		if n := p.byID[owner(a.Labels)]; n != nil {
			n.Alerts = append(n.Alerts, a)
		} else {
			p.Alerts = append(p.Alerts, a)
		}
	}
}

// followsUpstream are the alerts on what a query sees, the whole path's: they are the effect of a
// problem upstream that holds data back, not another problem.
var followsUpstream = map[string]bool{"ColvennaFreshnessP99": true, "ColvennaFreshnessUnmeasured": true}

// delayAlerts are the alerts that say data is held back: the acceptance contract's latency
// alerts, a source whose changes stop arriving, a job that commits nothing. A quarantine or a
// quality gate does not make what follows late. (A freshness alert of its own, unexplained, holds
// back too: locate.)
var delayAlerts = map[string]bool{
	"ColvennaSourceLatencyP95": true, "ColvennaSourceLatencyP99": true, "ColvennaSourceStale": true,
	"ColvennaLayerLatencyP95": true, "ColvennaLayerLagP99": true,
	"ColvennaNoCheckpoint": true, "ColvennaCheckpointsFailing": true, "ColvennaDegraded": true,
}

// locate marks where the problems are: a node whose declaration is degraded or failed, or that an
// alert fires for; and what is downstream of one, which is affected by it. A freshness alert is a
// problem of its own only where no problem upstream holds data back.
func (p *Pipeline) locate() {
	// delays are the problems that hold back what is downstream, by node id.
	delays := map[string]bool{}
	out := map[string][]string{}
	for _, e := range p.Edges {
		out[e.From] = append(out[e.From], e.To)
	}
	downstream := func(problem *Node) {
		queue := append([]string(nil), out[problem.ID]...)
		seen := map[string]bool{}
		for len(queue) > 0 {
			id := queue[0]
			queue = queue[1:]
			if seen[id] {
				continue
			}
			seen[id] = true
			if d := p.byID[id]; d != nil {
				if !slices.Contains(d.AffectedBy, problem.ID) {
					d.AffectedBy = append(d.AffectedBy, problem.ID)
				}
				d.Affected = !d.Problem
			}
			queue = append(queue, out[id]...)
		}
	}
	for _, n := range p.Nodes {
		if n.Phase == "Degraded" || n.Phase == "Failed" {
			n.Problem, delays[n.ID] = true, true
			n.Why = append(n.Why, "phase "+n.Phase)
		}
		for _, a := range n.Alerts {
			if a.State == "firing" && !followsUpstream[a.Name] {
				n.Problem = true
				delays[n.ID] = delays[n.ID] || delayAlerts[a.Name]
				n.Why = append(n.Why, "alert "+a.Name)
			}
		}
	}
	for _, n := range p.Nodes {
		if n.Problem {
			downstream(n)
		}
	}
	// Upstream first (the nodes are in depth order): a freshness problem found here explains the
	// freshness alerts further down.
	for _, n := range p.Nodes {
		for _, a := range n.Alerts {
			if a.State != "firing" || !followsUpstream[a.Name] {
				continue
			}
			if slices.ContainsFunc(n.AffectedBy, func(id string) bool { return delays[id] }) {
				n.Why = append(n.Why, "alert "+a.Name+" (from upstream)")
				continue
			}
			// Late data of its own: a problem here, and it holds back what follows, even when
			// this node was a problem already for another reason.
			n.Why = append(n.Why, "alert "+a.Name)
			delays[n.ID] = true
			if !n.Problem {
				n.Problem, n.Affected = true, false
				downstream(n)
			}
		}
	}
}

func field(o map[string]any, path ...string) any {
	var v any = o
	for _, k := range path {
		m, ok := v.(map[string]any)
		if !ok {
			return nil
		}
		v = m[k]
	}
	return v
}

func str(o map[string]any, path ...string) string {
	s, _ := field(o, path...).(string)
	return s
}

// number is a status's number (decoded as int64 or float64), nil when absent.
func number(o map[string]any, path ...string) *float64 {
	switch v := field(o, path...).(type) {
	case int64:
		f := float64(v)
		return &f
	case float64:
		return &v
	}
	return nil
}

func slice(o map[string]any, path ...string) []map[string]any {
	items, _ := field(o, path...).([]any)
	var out []map[string]any
	for _, i := range items {
		if m, ok := i.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

// nonBlank is a list of non-blank strings.
func nonBlank(o map[string]any, path ...string) []string {
	items, _ := field(o, path...).([]any)
	var out []string
	for _, i := range items {
		if s, ok := i.(string); ok && strings.TrimSpace(s) != "" {
			out = append(out, s)
		}
	}
	return out
}
