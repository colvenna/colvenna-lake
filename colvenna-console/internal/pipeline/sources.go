package pipeline

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
)

// The kinds the pipeline is made of, as the operator's CRDs and the Flink operator's define them.
var (
	sources    = schema.GroupVersionResource{Group: "colvenna-lake.io", Version: "v1alpha1", Resource: "lakesources"}
	tables     = schema.GroupVersionResource{Group: "colvenna-lake.io", Version: "v1alpha1", Resource: "laketables"}
	dashboards = schema.GroupVersionResource{Group: "colvenna-lake.io", Version: "v1alpha1", Resource: "lakedashboards"}
	tasks      = schema.GroupVersionResource{Group: "colvenna-lake.io", Version: "v1alpha1", Resource: "laketasks"}
	flinks     = schema.GroupVersionResource{Group: "flink.apache.org", Version: "v1beta1", Resource: "flinkdeployments"}
)

// Objects are the declarations of some namespaces, read from the Kubernetes API.
type Objects struct {
	Namespaces                                      []string
	Sources, Tables, Dashboards, Tasks, Deployments []unstructured.Unstructured
}

// Kubernetes lists the declarations; the console's identity may get and list them, nothing more.
type Kubernetes struct {
	Client     dynamic.Interface
	Namespaces []string
}

// Read lists every kind in every namespace.
func (k *Kubernetes) Read(ctx context.Context) (Objects, error) {
	o := Objects{Namespaces: k.Namespaces}
	for _, ns := range k.Namespaces {
		for _, l := range []struct {
			gvr  schema.GroupVersionResource
			into *[]unstructured.Unstructured
		}{{sources, &o.Sources}, {tables, &o.Tables}, {dashboards, &o.Dashboards}, {tasks, &o.Tasks}, {flinks, &o.Deployments}} {
			list, err := k.Client.Resource(l.gvr).Namespace(ns).List(ctx, metav1.ListOptions{})
			if err != nil {
				return Objects{}, fmt.Errorf("%s in %s: %w", l.gvr.Resource, ns, err)
			}
			*l.into = append(*l.into, list.Items...)
		}
	}
	return o, nil
}

// Sample is one series of a Prometheus vector.
type Sample struct {
	Labels map[string]string
	Value  float64
}

// Alert is one alert Prometheus holds, pending or firing.
type Alert struct {
	Name     string            `json:"name"`
	State    string            `json:"state"`
	Severity string            `json:"severity,omitempty"`
	Summary  string            `json:"summary,omitempty"`
	Labels   map[string]string `json:"labels"`
	Since    time.Time         `json:"since"`
}

// Prometheus reads the metrics and alerts of the monitoring stack (deploy/helm/colvenna-monitoring).
type Prometheus struct {
	URL    string
	Client *http.Client
}

// Query is an instant query's vector.
func (p *Prometheus) Query(ctx context.Context, expr string) ([]Sample, error) {
	var body struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
				Value  [2]any            `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := p.get(ctx, "/api/v1/query?query="+url.QueryEscape(expr), &body); err != nil {
		return nil, err
	}
	if body.Status != "success" {
		return nil, fmt.Errorf("prometheus query %q: status %q", expr, body.Status)
	}
	samples := make([]Sample, 0, len(body.Data.Result))
	for _, r := range body.Data.Result {
		text, _ := r.Value[1].(string)
		v, err := strconv.ParseFloat(text, 64)
		if err != nil {
			continue
		}
		samples = append(samples, Sample{Labels: r.Metric, Value: v})
	}
	return samples, nil
}

// Alerts are the alerts that are pending or firing.
func (p *Prometheus) Alerts(ctx context.Context) ([]Alert, error) {
	var body struct {
		Status string `json:"status"`
		Data   struct {
			Alerts []struct {
				Labels      map[string]string `json:"labels"`
				Annotations map[string]string `json:"annotations"`
				State       string            `json:"state"`
				ActiveAt    time.Time         `json:"activeAt"`
			} `json:"alerts"`
		} `json:"data"`
	}
	if err := p.get(ctx, "/api/v1/alerts", &body); err != nil {
		return nil, err
	}
	if body.Status != "success" {
		return nil, fmt.Errorf("prometheus alerts: status %q", body.Status)
	}
	alerts := make([]Alert, 0, len(body.Data.Alerts))
	for _, a := range body.Data.Alerts {
		alerts = append(alerts, Alert{
			Name: a.Labels["alertname"], State: a.State, Severity: a.Labels["severity"],
			Summary: a.Annotations["summary"], Labels: a.Labels, Since: a.ActiveAt,
		})
	}
	return alerts, nil
}

func (p *Prometheus) get(ctx context.Context, path string, into any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.URL+path, nil)
	if err != nil {
		return err
	}
	resp, err := p.Client.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("prometheus %s: %s", path, resp.Status)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 32<<20)).Decode(into)
}
