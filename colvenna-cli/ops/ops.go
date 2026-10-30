// Package ops are Colvenna Lake's operations as requests to the Kubernetes API: what `colvenna` submits,
// and what the console submits for the same operation. Both build the same objects here, so an
// operation has one meaning whichever way it is done; admission and every decision stay in the
// API server and the operator.
package ops

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	authv1 "k8s.io/api/authentication/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/types"
	utilyaml "k8s.io/apimachinery/pkg/util/yaml"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"sigs.k8s.io/yaml"
)

// Clients are what operations talk to, in one namespace; tests substitute fakes.
type Clients struct {
	Dynamic   dynamic.Interface
	Core      kubernetes.Interface
	Namespace string
}

// ApprovedBy is the annotation naming who created a waiver; the API server checks it.
const ApprovedBy = "colvenna-lake.io/approved-by"

// PersonKey is the user extra naming the person behind a shared identity: the console's, which
// acts as colvenna-console:operator or colvenna-console:admin on behalf of a signed-in person.
const PersonKey = "colvenna-lake.io/person"

// SubjectKey is the Secret key an erasure's subject values are under (LakeErasureSpec.DEFAULT_KEY).
const SubjectKey = "values"

// The acceptance contract's erasure deadlines (section 4), the defaults and the longest an
// erasure may ask for.
const (
	LogicalDeadline  = "24h"
	PhysicalDeadline = "9d"
)

// ReadManifests are the YAML or JSON documents of a manifest; empty ones are skipped.
func ReadManifests(data []byte) ([]*unstructured.Unstructured, error) {
	decoder := utilyaml.NewYAMLOrJSONDecoder(bytes.NewReader(data), 4096)
	var out []*unstructured.Unstructured
	for {
		var doc map[string]any
		if err := decoder.Decode(&doc); err != nil {
			if errors.Is(err, io.EOF) {
				return out, nil
			}
			return nil, err
		}
		if len(doc) > 0 {
			out = append(out, &unstructured.Unstructured{Object: doc})
		}
	}
}

// Apply server-side applies Colvenna Lake objects, as manager, and refuses any other kind: neither
// the CLI nor the console is a general kubectl. Each applied object is reported as kind/name.
func Apply(ctx context.Context, c *Clients, manager string, objects []*unstructured.Unstructured) ([]string, error) {
	var applied []string
	for _, obj := range objects {
		kind, err := LookupKind(obj.GetKind())
		if err != nil {
			return applied, err
		}
		if obj.GetAPIVersion() != Group+"/"+Version {
			return applied, fmt.Errorf("%s/%s: apiVersion must be %s/%s", obj.GetKind(), obj.GetName(), Group, Version)
		}
		if obj.GetNamespace() == "" {
			obj.SetNamespace(c.Namespace)
		}
		body, err := yaml.Marshal(obj.Object)
		if err != nil {
			return applied, err
		}
		force := true
		if _, err := c.Dynamic.Resource(kind.GVR()).Namespace(obj.GetNamespace()).Patch(ctx, obj.GetName(), types.ApplyPatchType,
			body, metav1.PatchOptions{FieldManager: manager, Force: &force}); err != nil {
			return applied, fmt.Errorf("%s/%s: %w", kind.Aliases[0], obj.GetName(), err)
		}
		applied = append(applied, kind.Aliases[0]+"/"+obj.GetName())
	}
	return applied, nil
}

// Suspend stops a declaration's jobs keeping their position, or (suspend false) starts them again.
func Suspend(ctx context.Context, c *Clients, manager string, kind Kind, name string, suspend bool) error {
	patch := fmt.Sprintf(`{"spec":{"suspend":%t}}`, suspend)
	_, err := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Patch(ctx, name, types.MergePatchType,
		[]byte(patch), metav1.PatchOptions{FieldManager: manager})
	return err
}

// Delete deletes a declaration; its spec.deletionPolicy decides what happens to its tables.
func Delete(ctx context.Context, c *Clients, kind Kind, name string) error {
	return c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace).Delete(ctx, name, metav1.DeleteOptions{})
}

// ErrChanged is a declaration that is no longer the one a deletion was confirmed for.
var ErrChanged = errors.New("the declaration changed since it was shown: show it again")

// DeleteUnchanged deletes a declaration only if it is still the one shown: the same object (uid)
// and the same spec (generation); the API server checks the uid again as it deletes.
func DeleteUnchanged(ctx context.Context, c *Clients, kind Kind, name, uid string, generation int64) error {
	resource := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace)
	obj, err := resource.Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return err
	}
	if string(obj.GetUID()) != uid || obj.GetGeneration() != generation {
		return ErrChanged
	}
	id := obj.GetUID()
	return resource.Delete(ctx, name, metav1.DeleteOptions{Preconditions: &metav1.Preconditions{UID: &id}})
}

// TaskOptions are what every task takes: its retries and the timeout of one attempt.
type TaskOptions struct {
	// Retries are the attempts after the first, 0 to 5 ("" for the task's default, 2).
	Retries string
	// Timeout is how long one attempt may run, e.g. 30m ("" for the default, 1h).
	Timeout string
}

// Task is a LakeTask of a type on a table (<database>.<table>), with the type's parameters.
func Task(name, taskType, table string, params map[string]any, o TaskOptions) (*unstructured.Unstructured, error) {
	database, tableName, ok := strings.Cut(table, ".")
	if !ok || database == "" || tableName == "" {
		return nil, fmt.Errorf("%q: give the table as <database>.<table>", table)
	}
	spec := map[string]any{"type": taskType, "table": map[string]any{"database": database, "table": tableName}}
	for k, v := range params {
		spec[k] = v
	}
	if o.Retries != "" {
		n, err := strconv.Atoi(o.Retries)
		if err != nil {
			return nil, fmt.Errorf("retries %q: %w", o.Retries, err)
		}
		spec["retries"] = int64(n)
	}
	if o.Timeout != "" {
		spec["timeout"] = o.Timeout
	}
	return object("LakeTask", name, nil, spec), nil
}

// Backfill recomputes a LakeTable's target from its upstream (HLD 9.2).
func Backfill(name, table string, o TaskOptions) (*unstructured.Unstructured, error) {
	return Task(name, "backfill", table, nil, o)
}

// Compact compacts a table, or some of its partitions (column=value[,column=value] each).
func Compact(name, table string, full bool, partitions []string, o TaskOptions) (*unstructured.Unstructured, error) {
	p := map[string]any{"full": full}
	if len(partitions) > 0 {
		p["partitions"] = anyList(partitions)
	}
	return Task(name, "compact", table, map[string]any{"compact": p}, o)
}

// Expire expires a table's snapshots: those a reader's consumer still needs and tagged ones stay.
func Expire(name, table string, retainMin int, olderThan string, o TaskOptions) (*unstructured.Unstructured, error) {
	p := map[string]any{}
	if retainMin > 0 {
		p["retainMin"] = int64(retainMin)
	}
	if olderThan != "" {
		p["olderThan"] = olderThan
	}
	return Task(name, "expireSnapshots", table, map[string]any{"expireSnapshots": p}, o)
}

// Orphans removes files no snapshot, tag or branch refers to.
func Orphans(name, table, olderThan string, o TaskOptions) (*unstructured.Unstructured, error) {
	p := map[string]any{}
	if olderThan != "" {
		p["olderThan"] = olderThan
	}
	return Task(name, "removeOrphanFiles", table, map[string]any{"removeOrphanFiles": p}, o)
}

// Tag tags a table's latest snapshot, for audit and replay.
func Tag(name, table, tag, retained string, o TaskOptions) (*unstructured.Unstructured, error) {
	if tag == "" {
		return nil, errors.New("give the tag's name")
	}
	p := map[string]any{"name": tag}
	if retained != "" {
		p["timeRetained"] = retained
	}
	return Task(name, "createTag", table, map[string]any{"createTag": p}, o)
}

// Check checks a table against its LakeQuality rules: some partitions, a snapshot (0 for the
// latest when it starts) and the time freshness is measured at (RFC 3339, "" for when it starts).
func Check(name, table string, partitions []string, snapshot int64, asOf string, o TaskOptions) (*unstructured.Unstructured, error) {
	params := map[string]any{}
	if len(partitions) > 0 {
		params["partitions"] = anyList(partitions)
	}
	if snapshot > 0 {
		params["snapshot"] = snapshot
	}
	if asOf != "" {
		t, err := time.Parse(time.RFC3339Nano, asOf)
		if err != nil {
			return nil, fmt.Errorf("as of %q: %w", asOf, err)
		}
		// The task takes UTC.
		params["asOf"] = t.UTC().Format("2006-01-02T15:04:05.999999999Z")
	}
	return Task(name, "check", table, map[string]any{"check": params}, o)
}

// Waiver accepts rules a check failed, so its table's quality gate opens; approver is who creates
// it (Whoami), which the API server checks.
func Waiver(name, check string, rules []string, reason, approver string) (*unstructured.Unstructured, error) {
	if len(rules) == 0 || reason == "" {
		return nil, errors.New("give at least one rule and a reason")
	}
	return object("LakeWaiver", name, map[string]any{ApprovedBy: approver},
		map[string]any{"check": check, "rules": anyList(rules), "reason": reason}), nil
}

// Whoami is who the API server knows these clients as: its user name, and for the console's
// shared identities the person acting through them (<user>/<person>).
func Whoami(ctx context.Context, c *Clients) (string, error) {
	review, err := c.Core.AuthenticationV1().SelfSubjectReviews().Create(ctx, &authv1.SelfSubjectReview{}, metav1.CreateOptions{})
	if err != nil {
		return "", fmt.Errorf("who am I (SelfSubjectReview): %w", err)
	}
	user := review.Status.UserInfo
	if user.Username == "" {
		return "", errors.New("the API server did not name this user")
	}
	if person := user.Extra[PersonKey]; len(person) == 1 && person[0] != "" {
		return user.Username + "/" + person[0], nil
	}
	return user.Username, nil
}

// Forget erases one data subject across the namespace's managed copies (HLD 12.7). The subject's
// key values are personal data: they go into a Secret of the erasure's name, which the erasure
// owns (it goes with it), and into no argument, output or declaration. A name whose Secret exists
// is refused first (a dry run, so another Secret is never taken for the subject); then the
// erasure is created (it waits for its Secret), and the Secret already owned by it: an erasure is
// accepted once, and a refused one leaves nothing behind.
func Forget(ctx context.Context, c *Clients, manager, name string, values []byte, logical, physical string) error {
	if strings.TrimSpace(string(values)) == "" {
		return errors.New("no subject values")
	}
	deadlines := map[string]any{}
	if logical != "" {
		deadlines["logical"] = logical
	}
	if physical != "" {
		deadlines["physical"] = physical
	}
	spec := map[string]any{"subjectSecret": map[string]any{"name": name, "key": SubjectKey}}
	if len(deadlines) > 0 {
		spec["deadlines"] = deadlines
	}
	secrets := c.Core.CoreV1().Secrets(c.Namespace)
	probe := &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: c.Namespace}}
	if _, err := secrets.Create(ctx, probe, metav1.CreateOptions{DryRun: []string{metav1.DryRunAll}}); apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("secret %s exists: an erasure is accepted once; choose another name", name)
	} else if err != nil {
		return fmt.Errorf("secret/%s: %w", name, err)
	}
	kind, _ := LookupKind("LakeErasure")
	erasures := c.Dynamic.Resource(kind.GVR()).Namespace(c.Namespace)
	created, err := erasures.Create(ctx, object("LakeErasure", name, nil, spec), metav1.CreateOptions{FieldManager: manager})
	if apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("erasure %s exists: an erasure is accepted once; choose another name", name)
	} else if err != nil {
		return fmt.Errorf("erasure/%s: %w", name, err)
	}
	secret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: c.Namespace, OwnerReferences: []metav1.OwnerReference{{
			APIVersion: Group + "/" + Version, Kind: "LakeErasure", Name: name, UID: created.GetUID(),
		}}},
		Data: map[string][]byte{SubjectKey: values},
	}
	if _, err := secrets.Create(ctx, secret, metav1.CreateOptions{FieldManager: manager}); err != nil {
		// No subject, so no erasure.
		_ = erasures.Delete(ctx, name, metav1.DeleteOptions{})
		if apierrors.IsAlreadyExists(err) {
			return fmt.Errorf("secret %s exists: an erasure is accepted once; choose another name", name)
		}
		return fmt.Errorf("secret/%s: %w", name, err)
	}
	return nil
}

func object(kind, name string, annotations map[string]any, spec map[string]any) *unstructured.Unstructured {
	metadata := map[string]any{"name": name}
	if annotations != nil {
		metadata["annotations"] = annotations
	}
	return &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": Group + "/" + Version, "kind": kind, "metadata": metadata, "spec": spec,
	}}
}

func anyList(values []string) []any {
	out := make([]any, len(values))
	for i, v := range values {
		out[i] = v
	}
	return out
}
