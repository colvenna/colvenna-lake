"""The tasks run's stand-in for a user's Airflow: a DAG that runs one LakeTask and ends as it ends.

Acceptance only, not a template the product maintains (HLD 9.2): it shows an orchestrator a user
already has driving Colvenna Lake's public interface, the Kubernetes API, under its own identity
with RBAC on laketasks in one namespace. The DAG creates the LakeTask its run's conf describes
and waits for a final phase: Succeeded passes, Failed fails the Airflow task with the operator's
message. A retried or repeated run creates nothing new: a LakeTask runs once, so the run finds
the task it created before and waits for it; a task of that name with another spec fails it.

conf: {"name": "<laketask>", "spec": {<LakeTask spec>}, "namespace": "<optional>",
       "timeoutMinutes": <optional; the task's attempts and convergence by default>}
In a pod the service account's credentials and namespace are used, elsewhere the kubeconfig's.
"""

from __future__ import annotations

import os
import time
from datetime import datetime, timedelta

from airflow.decorators import dag, task
from airflow.exceptions import AirflowFailException

GROUP, VERSION, PLURAL = "colvenna-lake.io", "v1alpha1", "laketasks"
NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
FINAL = ("Succeeded", "Failed")


def _api():
    from kubernetes import client, config

    if os.path.exists(NAMESPACE_FILE):
        config.load_incluster_config()
    else:
        config.load_kube_config()
    return client.CustomObjectsApi()


def _namespace(conf) -> str:
    if conf.get("namespace"):
        return conf["namespace"]
    with open(NAMESPACE_FILE, encoding="utf-8") as f:
        return f.read().strip()


def _minutes(duration: str) -> int:
    """30m or 2h, the LakeTask's timeout syntax, in minutes."""
    return int(duration[:-1]) * (60 if duration.endswith("h") else 1)


def _backoff_minutes(retries: int) -> int:
    """The operator's waits before the retries: 1 min after the first failure, doubling, at most
    10 min each."""
    return sum(min(2 ** i, 10) for i in range(retries))


def _timeout_minutes(conf) -> int:
    """How long the task may take with the LakeTask defaults: each attempt's timeout and the
    waits between them, plus the operator's limits on suspending a backfill's table (20 min) and
    its convergence (30 min). Time queued behind other tasks of the table is not counted: give
    timeoutMinutes for that."""
    if conf.get("timeoutMinutes"):
        return int(conf["timeoutMinutes"])
    spec = conf["spec"]
    retries = int(spec.get("retries", 2))
    return (1 + retries) * _minutes(spec.get("timeout", "1h")) + _backoff_minutes(retries) + 20 + 30


def _differs(asked, held) -> bool:
    """Whether the held spec is not the one asked for; the API server fills in defaults."""
    if isinstance(asked, dict):
        return not isinstance(held, dict) or any(_differs(v, held.get(k)) for k, v in asked.items())
    return asked != held


@dag(
    dag_id="colvenna_lake_task",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["colvenna-lake"],
)
def colvenna_lake_task():
    @task(retries=2, retry_delay=timedelta(seconds=10))
    def submit(**context) -> str:
        from kubernetes.client.exceptions import ApiException

        conf = context["dag_run"].conf
        name, spec = conf["name"], conf["spec"]
        body = {
            "apiVersion": f"{GROUP}/{VERSION}",
            "kind": "LakeTask",
            "metadata": {"name": name, "labels": {"colvenna-lake.io/requested-by": "airflow"}},
            "spec": spec,
        }
        api, ns = _api(), _namespace(conf)
        try:
            api.create_namespaced_custom_object(GROUP, VERSION, ns, PLURAL, body)
            print(f"COLVENNA_TASK created {ns}/{name}")
        except ApiException as e:
            if e.status != 409:
                raise
            existing = api.get_namespaced_custom_object(GROUP, VERSION, ns, PLURAL, name)
            if _differs(spec, existing["spec"]):
                raise AirflowFailException(
                    f"LakeTask {name} exists with another spec: {existing['spec']}"
                ) from e
            print(f"COLVENNA_TASK exists {ns}/{name}; waiting for it")
        return name

    @task
    def wait(name: str, **context) -> dict:
        conf = context["dag_run"].conf
        deadline = time.monotonic() + 60 * _timeout_minutes(conf)
        api, ns = _api(), _namespace(conf)
        phase = None
        while time.monotonic() < deadline:
            status = api.get_namespaced_custom_object(GROUP, VERSION, ns, PLURAL, name).get(
                "status", {}
            )
            if status.get("phase") != phase:
                phase = status.get("phase")
                print(f"COLVENNA_TASK {name} {phase}: {status.get('message')}")
            if phase in FINAL:
                if phase == "Failed":
                    raise AirflowFailException(f"LakeTask {name} failed: {status.get('message')}")
                return {
                    "phase": phase,
                    "attempts": status.get("attempts"),
                    "result": status.get("result"),
                    "message": status.get("message"),
                }
            time.sleep(10)
        raise AirflowFailException(f"LakeTask {name} not final in time (phase {phase})")

    wait(submit())


colvenna_lake_task()
