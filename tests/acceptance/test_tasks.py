"""Unit tests of the Airflow DAG of the tasks run (tasks/colvenna_lake_task.py), without Airflow."""
import importlib.util
import os
import sys
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class Fail(Exception):
    pass


class ApiException(Exception):
    def __init__(self, status):
        super().__init__(status)
        self.status = status


class Api:
    """CustomObjectsApi over a dict of LakeTasks by name; status phases play back in order."""

    def __init__(self, existing=None, phases=()):
        self.tasks = dict(existing or {})
        self.phases = list(phases)
        self.created = []

    def create_namespaced_custom_object(self, group, version, ns, plural, body):
        if body["metadata"]["name"] in self.tasks:
            raise ApiException(409)
        self.tasks[body["metadata"]["name"]] = body
        self.created.append(body)

    def get_namespaced_custom_object(self, group, version, ns, plural, name):
        task = self.tasks[name]
        if self.phases:
            task["status"] = self.phases.pop(0)
        return task


def load(api):
    """The DAG module with Airflow stubbed and the API faked: its submit, its wait, the module."""
    steps = {}

    def task(*args, **kwargs):
        def register(f):
            steps[f.__name__] = f
            return lambda *a, **kw: None
        return register(args[0]) if args else register

    decorators = types.ModuleType("airflow.decorators")
    decorators.dag = lambda **kwargs: (lambda f: f)
    decorators.task = task
    exceptions = types.ModuleType("airflow.exceptions")
    exceptions.AirflowFailException = Fail
    k8s_exceptions = types.ModuleType("kubernetes.client.exceptions")
    k8s_exceptions.ApiException = ApiException
    sys.modules.update({"airflow": types.ModuleType("airflow"), "airflow.decorators": decorators,
                        "airflow.exceptions": exceptions,
                        "kubernetes": types.ModuleType("kubernetes"),
                        "kubernetes.client": types.ModuleType("kubernetes.client"),
                        "kubernetes.client.exceptions": k8s_exceptions})
    spec = importlib.util.spec_from_file_location(
        "colvenna_lake_task", os.path.join(HERE, "tasks", "colvenna_lake_task.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module._api = lambda: api
    module._namespace = lambda conf: "colvenna-data"
    module.time = types.SimpleNamespace(monotonic=lambda: 0, sleep=lambda s: None)
    return steps["submit"], steps["wait"], module


SPEC = {"type": "createTag", "table": {"database": "ods", "table": "orders"},
        "createTag": {"name": "eod"}}


def context(**conf):
    return {"dag_run": types.SimpleNamespace(conf=dict({"name": "eod-1", "spec": SPEC}, **conf))}


class DagTest(unittest.TestCase):
    def test_a_run_creates_the_task_and_passes_once_it_succeeds(self):
        api = Api(phases=[{"phase": "Running"}, {"phase": "Succeeded", "attempts": 1,
                                                  "result": {"detail": "tagged eod"}}])
        submit, wait, _ = load(api)
        self.assertEqual("eod-1", submit(**context()))
        self.assertEqual(1, len(api.created))
        self.assertEqual("Succeeded", wait("eod-1", **context())["phase"])

    def test_a_repeated_run_waits_for_the_task_it_made(self):
        held = {"spec": dict(SPEC, createTag={"name": "eod", "timeRetained": "7d"}, retries=2)}
        api = Api(existing={"eod-1": held})
        submit, _, _ = load(api)
        self.assertEqual("eod-1", submit(**context()))
        self.assertEqual([], api.created)

    def test_a_task_of_that_name_with_another_spec_fails_the_run(self):
        held = {"spec": dict(SPEC, createTag={"name": "other"})}
        submit, _, _ = load(Api(existing={"eod-1": held}))
        with self.assertRaises(Fail):
            submit(**context())

    def test_a_failed_task_fails_the_run_with_its_reason(self):
        api = Api(existing={"eod-1": {"spec": SPEC}},
                  phases=[{"phase": "Failed", "message": "is not a LakeTable"}])
        _, wait, _ = load(api)
        with self.assertRaisesRegex(Fail, "is not a LakeTable"):
            wait("eod-1", **context())


class TimeoutTest(unittest.TestCase):
    def test_a_run_waits_as_long_as_the_task_may_take(self):
        _, _, module = load(Api())
        # Each attempt's timeout, the waits before the retries (1 and 2 min), fence and convergence.
        self.assertEqual(3 * 60 + 3 + 50, module._timeout_minutes({"spec": SPEC}))
        self.assertEqual(2 * 30 + 1 + 50, module._timeout_minutes(
            {"spec": dict(SPEC, retries=1, timeout="30m")}))
        self.assertEqual(6 * 60 + 1 + 2 + 4 + 8 + 10 + 50, module._timeout_minutes(
            {"spec": dict(SPEC, retries=5)}))
        self.assertEqual(5, module._timeout_minutes({"spec": SPEC, "timeoutMinutes": 5}))


if __name__ == "__main__":
    unittest.main()
