"""Writes deploy/helm/colvenna-monitoring/files/dashboard.json, Colvenna Lake's Grafana panels (LLD "监控与数据质量").

The latency panels draw the acceptance contract's paths (2.1) against its targets: source to ODS (the heartbeat), one
layer to the next (the reader's consumer), and the freshness a query of each table sees. Run it
again after changing a panel; the JSON is checked in.
"""

import json
import pathlib
import sys

# The latency targets (values.yaml latency), filled in by the chart when it renders the dashboard: the panels
# draw the same lines the alert rules use, whatever the release sets.
TARGETS = {"p95": "__TARGET_LAYER_P95__", "p99": "__TARGET_LAYER_P99__",
      "whole_p95": "__TARGET_WHOLE_P95__", "whole_p99": "__TARGET_WHOLE_P99__"}


def panel(pid, title, exprs, unit="s", x=0, y=0, w=12, h=8, thresholds=None, description=""):
    steps = [{"color": "green", "value": None}]
    for value, color in thresholds or []:
        steps.append({"color": color, "value": value})
    return {
        "id": pid,
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": {"type": "prometheus", "uid": "colvenna-prometheus"},
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "thresholds": {"mode": "absolute", "steps": steps},
                "custom": {"thresholdsStyle": {"mode": "line" if thresholds else "off"}},
            },
            "overrides": [],
        },
        "options": {"legend": {"displayMode": "table", "placement": "bottom"}},
        "targets": [
            {"refId": chr(65 + i), "expr": e, "legendFormat": legend}
            for i, (e, legend) in enumerate(exprs)
        ],
    }


def row(pid, title, y):
    return {"id": pid, "type": "row", "title": title, "collapsed": False,
            "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []}


def dashboard():
    p95, p99 = TARGETS["p95"], TARGETS["p99"]
    lines = [(p95, "orange"), (p99, "red")]
    panels = [
        row(1, "延迟（验收契约 2.1 的路径与目标）", 0),
        panel(2, "源库 → ODS：心跳延迟 p95 / p99（30 分钟窗）", [
            ("quantile_over_time(0.95, colvenna_source_latency_seconds[30m])", "p95 {{source}}"),
            ("quantile_over_time(0.99, colvenna_source_latency_seconds[30m])", "p99 {{source}}"),
        ], y=1, thresholds=lines,
            description="源端提交 → ODS 快照可读，合成心跳行测得；阈值线是验收契约每段的 p95、p99 目标"),
        panel(3, "上一层 → 本层：未带走的上游变更已等待多久", [
            ("colvenna_table_upstream_lag_seconds", "{{table}} ← {{upstream}}"),
        ], x=12, y=1, thresholds=lines,
            description="上游快照可读 → 本层的提交带上它（读端 consumer 推进）；超过 p99 即已违约"),
        panel(4, "上一层 → 本层：每个带走的快照用时 p95 / p99（30 分钟窗）", [
            ("quantile_over_time(0.95, colvenna_table_layer_latency_seconds[30m])", "p95 {{table}} ← {{upstream}}"),
            ("quantile_over_time(0.99, colvenna_table_layer_latency_seconds[30m])", "p99 {{table}} ← {{upstream}}"),
        ], y=9, thresholds=lines,
            description="上游快照提交 → 本层带上它的提交；阈值线是验收契约每段的 p95、p99 目标"),
        panel(5, "查询新鲜度：查询分层表看到的最新源端变更有多旧", [
            ("colvenna_table_freshness_seconds", "{{table}}"),
        ], x=12, y=9, thresholds=[(TARGETS["whole_p95"], "orange"), (TARGETS["whole_p99"], "red")],
            description="上游新鲜度 + 本层落后的时间；阈值线是验收契约整条路径的 p95、p99 目标"),
        panel(26, "ODS 新鲜度：湖里最新的源端变更有多旧（心跳）", [
            ("colvenna_source_freshness_seconds", "{{source}}"),
        ], y=17, thresholds=lines,
            description="源库 → ODS 一段：超过 p99 加一轮心跳即 ColvennaSourceStale"),
        panel(27, "测量的时效：Operator 上次测量至今", [
            ("time() - colvenna_source_measured_timestamp_seconds", "源 {{source}}"),
            ("time() - colvenna_table_measured_timestamp_seconds", "表 {{laketable}}"),
        ], x=12, y=17, thresholds=[(300, "red")],
            description="超过 5 分钟，这些对象的延迟、滞后与新鲜度都是旧的：见 ColvennaMeasuresStale"),
        row(6, "积压", 25),
        panel(7, "上游未读快照数", [("colvenna_table_upstream_lag_snapshots", "{{table}} ← {{upstream}}")],
              unit="short", y=26),
        panel(8, "Kafka 未消费记录数", [("colvenna_source_kafka_lag", "{{source}} {{topic}}")],
              unit="short", x=12, y=26),
        row(9, "写入、提交与目录", 34),
        panel(10, "提交：成功 / 冲突 / 被围栏（每秒），冲突按源与分层表", [
            ("sum by (outcome) (rate(colvenna_catalog_commits_total[5m]))", "{{outcome}}"),
            # A table's owner from its backlog gauges, which carry the source or layered table.
            ("sum by (namespace, source, laketable) (rate(colvenna_catalog_commits_total{outcome=\"conflict\"}[5m])"
             " * on (table) group_left (namespace, source, laketable)"
             " max by (table, namespace, source, laketable) (colvenna_table_snapshots * 0 + 1))",
             "冲突 {{namespace}}/{{source}}{{laketable}}"),
        ], unit="ops", y=35),
        panel(11, "colvenna-catalog 请求延迟 p99（按动作）", [
            ("histogram_quantile(0.99, sum by (le, action) (rate(colvenna_catalog_request_seconds_bucket[5m])))", "{{action}}"),
        ], x=12, y=35),
        panel(12, "作业最近一次 checkpoint（即最近一次提交）距今", [
            ("time() - colvenna_job_last_checkpoint_timestamp_seconds", "{{deployment}}"),
        ], y=43, thresholds=[(600, "red")]),
        panel(13, "失败的 checkpoint（本次运行累计）与最近一次的时长", [
            ("colvenna_job_checkpoints_failed", "失败 {{deployment}}"),
            ("colvenna_job_last_checkpoint_duration_seconds", "时长（秒） {{deployment}}"),
        ], unit="short", x=12, y=43),
        panel(22, "最近一次数据提交新增的文件：个数与平均大小", [
            ("colvenna_table_commit_files", "个数 {{table}}"),
            ("colvenna_table_commit_file_bytes / 1048576", "平均 MiB {{table}}"),
        ], unit="short", y=51),
        panel(23, "TaskManager 堆内存（作业里最忙的一个：写入与 committer）", [
            ("colvenna_job_taskmanager_heap_used_bytes", "已用 {{deployment}}"),
            ("colvenna_job_taskmanager_heap_max_bytes", "上限 {{deployment}}"),
        ], unit="bytes", x=12, y=51),
        row(14, "维护积压", 59),
        panel(15, "未合并的 level-0 文件", [("colvenna_table_level0_files", "{{table}}")], unit="short", y=60,
              thresholds=[(50, "orange")]),
        panel(16, "小文件与保留的快照", [
            ("colvenna_table_small_files", "小文件 {{table}}"),
            ("colvenna_table_snapshots", "快照 {{table}}"),
        ], unit="short", x=12, y=60),
        panel(24, "manifest 总大小与数据文件总大小", [
            ("colvenna_table_manifest_bytes", "manifest {{table}}"),
            ("colvenna_table_data_bytes", "数据 {{table}}"),
        ], unit="bytes", y=68),
        panel(17, "维护任务的阶段", [("sum by (phase, type) (colvenna_task_phase)", "{{type}} {{phase}}")],
              unit="short", x=12, y=68),
        row(18, "数据质量与治理", 76),
        panel(19, "隔离表中的行（按未通过的检查）", [("colvenna_table_quarantined_rows", "{{table}} {{rule}}")],
              unit="short", y=77),
        panel(20, "被拒绝的事件（按原因）", [("colvenna_source_events_rejected", "{{source}} {{reason}}")],
              unit="short", x=12, y=77),
        panel(21, "删除请求的阶段与超期", [
            ("sum by (phase) (colvenna_erasure_phase)", "{{phase}}"),
            ("sum(colvenna_erasure_breaches)", "超期"),
        ], unit="short", y=85),
        panel(28, "看板副本持有的湖有多旧（物化视图与内表，上次成功刷新至今）", [
            ("time() - colvenna_dashboard_refreshed_timestamp_seconds", "{{lakedashboard}} {{query}}"),
        ], y=93, thresholds=[("__TARGET_DORIS_P95__", "orange"), ("__TARGET_DORIS_P99__", "red")],
            description="Paimon → Doris 副本一段：阈值线是验收契约的 p95、p99 目标；超过 p99 即 ColvennaDashboardStale"),
        panel(25, "指标采集：哪些目标答不上（0）", [("up{job=~\"colvenna-.*\"}", "{{job}} {{pod}}")],
              unit="short", x=12, y=85,
              description="答不上的目标没有指标，它的延迟告警也就不会触发：见 ColvennaMetricsDown"),
    ]
    return {
        "uid": "colvenna-lake",
        "title": "Colvenna Lake 运行",
        "tags": ["colvenna-lake"],
        "timezone": "utc",
        "schemaVersion": 39,
        "refresh": "1m",
        "time": {"from": "now-6h", "to": "now"},
        "panels": panels,
    }


if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "deploy/helm/colvenna-monitoring/files/dashboard.json")
    out.write_text(json.dumps(dashboard(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
