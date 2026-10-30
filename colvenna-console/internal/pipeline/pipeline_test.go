package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

func object(t *testing.T, text string) unstructured.Unstructured {
	t.Helper()
	var o map[string]any
	if err := json.Unmarshal([]byte(text), &o); err != nil {
		t.Fatal(err)
	}
	return unstructured.Unstructured{Object: o}
}

// A source writing ODS, DWD reading it, DWS reading DWD, a dashboard reading DWS: what the
// monitoring acceptance runs.
func objects(t *testing.T) Objects {
	return Objects{
		Namespaces: []string{"ns"},
		Sources: []unstructured.Unstructured{object(t, `{"metadata":{"name":"shop","namespace":"ns"},
			"spec":{"target":{"database":"ods"}},
			"status":{"phase":"Running","tables":[{"name":"orders","target":"orders"},{"name":"customers","target":"customers"},
				{"name":"refunds","state":"Pending"}],
			"jobs":[{"deployment":"shop-cdc","jobId":"j1"}],
			"identity":{"principal":"p","tokenId":"t-secret"}}}`)},
		Tables: []unstructured.Unstructured{
			object(t, `{"metadata":{"name":"dwd","namespace":"ns"},"spec":{"target":{"database":"dwd","table":"orders"},
				"upstream":["ods.orders","ods.customers","ods.refunds"]},"status":{"phase":"Running","job":{"deployment":"dwd-table"},
				"applied":{"target":"dwd.orders","quarantine":"orders_quarantine","streams":["ods.orders"],"lookups":["ods.customers"]},
				"upstream":[{"table":"ods.orders","lag":3,"lagSeconds":40}],
				"conditions":[{"type":"Ready","status":"True","reason":"Running"}]}}`),
			object(t, `{"metadata":{"name":"dws","namespace":"ns"},"spec":{"target":{"database":"dws","table":"regions"},
				"upstream":["dwd.orders"]},"status":{"phase":"Running"}}`),
		},
		Dashboards: []unstructured.Unstructured{object(t, `{"metadata":{"name":"board","namespace":"ns"},
			"spec":{"tables":["dws.regions"]},"status":{"phase":"Ready","users":{"ns.board.a":"sealed(user/hash)"},
			"queries":[{"name":"totals","serve":"materialized","refreshAgeSeconds":30}]}}`)},
		Tasks: []unstructured.Unstructured{object(t, `{"metadata":{"name":"compact-1","namespace":"ns"},
			"spec":{"type":"compact"},"status":{"table":"ods.orders","phase":"Succeeded"}}`)},
		Deployments: []unstructured.Unstructured{object(t, `{"metadata":{"name":"dwd-table","namespace":"ns"},
			"status":{"lifecycleState":"STABLE","jobStatus":{"state":"RUNNING","jobId":"f1"}}}`)},
	}
}

func TestTheFlowIsWhatTheDeclarationsSay(t *testing.T) {
	p := Build(objects(t), time.Unix(1_800_000_000, 0))
	want := []string{
		"job:ns/dwd>table:dwd.orders", "job:ns/dwd>table:dwd.orders_quarantine", "job:ns/dws>table:dws.regions",
		"source:ns/shop>table:ods.customers", "source:ns/shop>table:ods.orders",
		"table:dwd.orders>job:ns/dws", "table:dws.regions>dashboard:ns/board",
		"table:ods.customers>job:ns/dwd", "table:ods.orders>job:ns/dwd",
	}
	var got []string
	for _, e := range p.Edges {
		got = append(got, e.From+">"+e.To)
	}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Fatalf("%v", got)
	}
	dwd := p.Node("job:ns/dwd")
	if len(dwd.Jobs) != 1 || dwd.Jobs[0].State != "RUNNING" || dwd.Jobs[0].JobID != "f1" || dwd.Depth != 2 ||
		len(dwd.Upstream) != 1 || *dwd.Upstream[0].LagSeconds != 40 {
		t.Fatalf("%+v", dwd)
	}
	// The planner's reads and the quarantine, not what was declared before it ran; nothing for a
	// source table not yet carried.
	if p.Node("table:ods.refunds") != nil || p.Node("table:dwd.orders_quarantine") == nil {
		t.Fatalf("%v", got)
	}
	if n := p.Node("dashboard:ns/board"); n.Depth != 6 || len(n.Queries) != 1 {
		t.Fatalf("%+v", n)
	}
	if n := p.Node("table:ods.orders"); len(n.Tasks) != 1 || n.Tasks[0].Phase != "Succeeded" {
		t.Fatalf("%+v", n)
	}
	// What the console shows holds no identity, token or password seal.
	text, _ := json.Marshal(p)
	for _, secret := range []string{"t-secret", "sealed(", "principal"} {
		if strings.Contains(string(text), secret) {
			t.Fatalf("shows %q: %s", secret, text)
		}
	}
}

type fakeKube struct{ o Objects }

func (f fakeKube) Read(context.Context) (Objects, error) { return f.o, nil }

func prometheus(t *testing.T, down bool) *Prometheus {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if down {
			http.Error(w, "no", http.StatusServiceUnavailable)
			return
		}
		switch r.URL.Path {
		case "/api/v1/query":
			q := r.URL.Query().Get("query")
			var result string
			switch q {
			case "colvenna_table_upstream_lag_seconds":
				result = `{"metric":{"namespace":"ns","laketable":"dwd","upstream":"ods.orders"},"value":[1,"400"]},
					{"metric":{"namespace":"ns","laketable":"dwd","upstream":"ods.customers"},"value":[1,"12"]}`
			case "colvenna_source_latency_seconds":
				result = `{"metric":{"namespace":"ns","source":"shop","table":"colvenna_heartbeat"},"value":[1,"21.5"]}`
			case "colvenna_table_freshness_seconds":
				result = `{"metric":{"namespace":"ns","laketable":"dws","table":"dws.regions"},"value":[1,"420"]}`
			}
			_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[` + result + `]}}`))
		case "/api/v1/alerts":
			_, _ = w.Write([]byte(`{"status":"success","data":{"alerts":[
				{"labels":{"alertname":"ColvennaLayerLagP99","namespace":"ns","laketable":"dwd","severity":"critical"},"state":"firing","activeAt":"2027-01-15T08:00:00Z"},
				{"labels":{"alertname":"ColvennaCompactionBacklog","table":"ods.orders"},"state":"pending","activeAt":"2027-01-15T08:00:00Z"},
				{"labels":{"alertname":"ColvennaFreshnessP99","namespace":"ns","laketable":"dws","table":"dws.regions"},"state":"firing","activeAt":"2027-01-15T08:05:00Z"},
				{"labels":{"alertname":"ColvennaLayerLagP99","namespace":"other","laketable":"dwd"},"state":"firing","activeAt":"2027-01-15T08:00:00Z"},
				{"labels":{"alertname":"ColvennaMetricsDown","job":"colvenna-catalog"},"state":"firing","activeAt":"2027-01-15T08:00:00Z"}]}}`))
		}
	}))
	t.Cleanup(server.Close)
	return &Prometheus{URL: server.URL, Client: server.Client()}
}

func TestMeasuresAndAlertsGoToTheirNodesAndProblemsAreLocated(t *testing.T) {
	r := &Reader{Kubernetes: fakeKube{objects(t)}, Prometheus: prometheus(t, false), Now: time.Now}
	p, err := r.Read(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	dwd := p.Node("job:ns/dwd")
	if dwd.Measures["upstreamLagSeconds"] != 400 || !dwd.Problem || dwd.Why[0] != "alert ColvennaLayerLagP99" {
		t.Fatalf("%+v", dwd)
	}
	if p.Node("source:ns/shop").Measures["sourceLatencySeconds"] != 21.5 {
		t.Fatal("source latency")
	}
	if p.Node("table:dws.regions").Measures["freshnessSeconds"] != 420 {
		t.Fatal("freshness")
	}
	// Downstream of the problem is affected by it; upstream is not.
	if n := p.Node("dashboard:ns/board"); len(n.AffectedBy) != 1 || n.AffectedBy[0] != "job:ns/dwd" {
		t.Fatalf("%+v", n)
	}
	for id, affected := range map[string]bool{"table:dwd.orders": true, "table:dwd.orders_quarantine": true, "job:ns/dws": true, "dashboard:ns/board": true,
		"table:ods.orders": false, "source:ns/shop": false} {
		if n := p.Node(id); n.Affected != affected || n.Problem {
			t.Fatalf("%s: %+v", id, n)
		}
	}
	// What a query of DWS sees is late because DWD is: shown on DWS, which is affected.
	if n := p.Node("job:ns/dws"); len(n.Alerts) != 1 || n.Problem || !n.Affected ||
		n.Why[0] != "alert ColvennaFreshnessP99 (from upstream)" {
		t.Fatalf("%+v", n)
	}
	// A pending alert is shown, not a problem; one with no node is the pipeline's; another
	// namespace's is not shown.
	if n := p.Node("table:ods.orders"); len(n.Alerts) != 1 || n.Problem {
		t.Fatalf("%+v", n)
	}
	if len(p.Alerts) != 1 || p.Alerts[0].Name != "ColvennaMetricsDown" {
		t.Fatalf("%+v", p.Alerts)
	}
	svg := string(SVG(p))
	if !strings.Contains(svg, `stroke="#d64545"`) || !strings.Contains(svg, "/pipeline/node?id=job%3Ans%2Fdwd") {
		t.Fatal(svg[:300])
	}
}

func TestWithoutPrometheusTheDeclarationsStillShowAndSaySo(t *testing.T) {
	r := &Reader{Kubernetes: fakeKube{objects(t)}, Prometheus: prometheus(t, true), Now: time.Now}
	p, err := r.Read(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Nodes) != 9 || len(p.Alerts) != 2 || p.Alerts[0].Name != "ConsoleMeasuresMissing" {
		t.Fatalf("%d %+v", len(p.Nodes), p.Alerts)
	}
}

type failingKube struct{}

func (failingKube) Read(context.Context) (Objects, error) { return Objects{}, errors.New("forbidden") }

func TestWithoutKubernetesThereIsNoPicture(t *testing.T) {
	r := &Reader{Kubernetes: failingKube{}, Prometheus: prometheus(t, false), Now: time.Now}
	if _, err := r.Read(context.Background()); err == nil {
		t.Fatal("a picture without declarations")
	}
}

func TestAPhaseDegradedIsAProblemAndADrawingEscapesNames(t *testing.T) {
	o := objects(t)
	o.Sources[0].Object["status"].(map[string]any)["phase"] = "Degraded"
	o.Sources[0].Object["metadata"].(map[string]any)["name"] = "<b>shop</b>"
	p := Build(o, time.Now())
	p.locate()
	n := p.Node("source:ns/<b>shop</b>")
	if !n.Problem || n.Why[0] != "phase Degraded" {
		t.Fatalf("%+v", n)
	}
	if strings.Contains(string(SVG(p)), "<b>") {
		t.Fatal("an unescaped name")
	}
}

func TestAFreshnessAlertWithNothingWrongUpstreamIsTheProblem(t *testing.T) {
	p := Build(objects(t), time.Now())
	p.attach([]Alert{{Name: "ColvennaFreshnessP99", State: "firing",
		Labels: map[string]string{"namespace": "ns", "laketable": "dws", "table": "dws.regions"}}})
	p.locate()
	if n := p.Node("job:ns/dws"); !n.Problem || n.Why[0] != "alert ColvennaFreshnessP99" {
		t.Fatalf("%+v", n)
	}
	if n := p.Node("dashboard:ns/board"); !n.Affected || n.Problem {
		t.Fatalf("%+v", n)
	}
	if n := p.Node("job:ns/dwd"); n.Affected || n.Problem {
		t.Fatalf("%+v", n)
	}
}

func TestAMeasureShowsAsACountOrADuration(t *testing.T) {
	for name, want := range map[string]string{"level0Files": "51", "upstreamLagSeconds": "6m40s", "copyAgeSeconds": "-"} {
		v := map[string]float64{"level0Files": 51, "upstreamLagSeconds": 400, "copyAgeSeconds": -1}[name]
		if got := Show(name, v); got != want {
			t.Fatalf("%s: %s, not %s", name, got, want)
		}
	}
}

func TestNothingDeclaredIsEmptyLists(t *testing.T) {
	text, _ := json.Marshal(Build(Objects{Namespaces: []string{"ns"}}, time.Unix(0, 0)))
	if !strings.Contains(string(text), `"nodes":[],"edges":[]`) {
		t.Fatal(string(text))
	}
}

func TestAFreshnessAlertAfterAProblemThatHoldsNothingBackIsItsOwn(t *testing.T) {
	// Rows quarantined in DWD hold nothing back: DWS's late data is DWS's problem.
	p := Build(objects(t), time.Now())
	p.attach([]Alert{
		{Name: "ColvennaRowsQuarantined", State: "firing", Labels: map[string]string{"namespace": "ns", "laketable": "dwd"}},
		{Name: "ColvennaFreshnessP99", State: "firing", Labels: map[string]string{"namespace": "ns", "laketable": "dws"}},
	})
	p.locate()
	if n := p.Node("job:ns/dws"); !n.Problem || n.Why[0] != "alert ColvennaFreshnessP99" || n.AffectedBy[0] != "job:ns/dwd" {
		t.Fatalf("%+v", n)
	}
	if n := p.Node("dashboard:ns/board"); !n.Affected || len(n.AffectedBy) != 2 {
		t.Fatalf("%+v", n)
	}
}

func TestEverySourceJobAndATaskBeforeItStartsAndAnEventSource(t *testing.T) {
	o := objects(t)
	o.Sources[0].Object["status"].(map[string]any)["jobs"] = []any{
		map[string]any{"deployment": "shop-cdc"}, map[string]any{"deployment": "shop-cdc-2", "jobId": "j2"}}
	o.Sources = append(o.Sources, object(t, `{"metadata":{"name":"clicks","namespace":"ns"},
		"spec":{"kafka":{"topics":["clicks"]},"target":{"database":"ods","table":"clicks"}},"status":{"phase":"Running"}}`))
	o.Tasks = append(o.Tasks, object(t, `{"metadata":{"name":"compact-2","namespace":"ns"},
		"spec":{"type":"compact","table":{"database":"dwd","table":"orders"}}}`))
	p := Build(o, time.Now())
	if n := p.Node("source:ns/shop"); len(n.Jobs) != 2 || n.Jobs[1].JobID != "j2" {
		t.Fatalf("%+v", n.Jobs)
	}
	if n := p.Node("table:dwd.orders"); len(n.Tasks) != 1 || n.Tasks[0].Name != "ns/compact-2" {
		t.Fatalf("%+v", n)
	}
	var clicks []string
	for _, e := range p.Edges {
		if e.From == "source:ns/clicks" {
			clicks = append(clicks, e.To)
		}
	}
	if strings.Join(clicks, " ") != "table:ods.clicks table:ods.clicks_rejections" {
		t.Fatalf("%v", clicks)
	}
}

func TestANumberOfTheStatusAsKubernetesDecodesIt(t *testing.T) {
	o := map[string]any{"i": int64(3), "f": 2.5, "s": "x"}
	if *number(o, "i") != 3 || *number(o, "f") != 2.5 || number(o, "s") != nil || number(o, "none") != nil {
		t.Fatal("numbers")
	}
	if Count(nil) != "-" || Seconds(nil) != "-" {
		t.Fatal("no number")
	}
}

func TestLateDataAtANodeThatWasAProblemAlreadyExplainsWhatFollows(t *testing.T) {
	// DWD quarantined rows (holds nothing back) and its own data is late: DWS's late data is
	// DWD's, not a problem of its own.
	p := Build(objects(t), time.Now())
	p.attach([]Alert{
		{Name: "ColvennaRowsQuarantined", State: "firing", Labels: map[string]string{"namespace": "ns", "laketable": "dwd"}},
		{Name: "ColvennaFreshnessP99", State: "firing", Labels: map[string]string{"namespace": "ns", "laketable": "dwd"}},
		{Name: "ColvennaFreshnessP99", State: "firing", Labels: map[string]string{"namespace": "ns", "laketable": "dws"}},
	})
	p.locate()
	if n := p.Node("job:ns/dwd"); !n.Problem || len(n.Why) != 2 {
		t.Fatalf("%+v", n)
	}
	if n := p.Node("job:ns/dws"); n.Problem || !n.Affected || n.Why[0] != "alert ColvennaFreshnessP99 (from upstream)" {
		t.Fatalf("%+v", n)
	}
}
