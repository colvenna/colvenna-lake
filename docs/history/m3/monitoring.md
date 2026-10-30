---
title: "M3 监控、告警与数据质量"
date: 2026-10-16
status: complete
issue: 29
---

# M3 监控、告警与数据质量

本页记录 Issue #29 的验收：按源、表、作业追踪延迟、积压、提交冲突、维护积压和查询新鲜度；错误数据有可追踪的去向；关键的正确性与延迟违约产生告警；面板与告警的指标与 [M0 验收契约 2.1 节](../../design/acceptance-contract.md#21-测量协议) 的口径一致。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "监控与数据质量"，依据 HLD 11.2、12.6、14。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。

**证据的来源版本**：结果来自提交 `b40bb98`（之后 rebase 到合并了 #113、#28 的 `main`：o2 的 MySQL 作业用的是 Flink CDC 3.5.0 的 connector，合并后的 `main` 用 3.7.0-1.20），私有证据目录 `m3-29/run-o2-b40bb98/`。镜像：Operator `cove-operator@sha256:67f38c20…`，作业 `cove-jobs@sha256:3671b032…`（mysql）与 `cove-jobs@sha256:a03564d8…`（sql），cove-catalog `cove-catalog@sha256:5ffe897b…`（运行前由 `upgrade-catalog.sh` 升到同一提交）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 可按源、表、作业追踪延迟、积压、提交冲突、维护积压和查询新鲜度 | 通过；提交冲突与任务阶段只由测试覆盖 | Prometheus 抓到这次运行每条路径的指标，按源、表、作业的标签区分：源库 → ODS 的心跳延迟与新鲜度，DWD、DWS 的逐层滞后、逐层延迟与查询新鲜度，按结果计的提交，三个作业的 checkpoint 与 TaskManager 堆，五张表的维护积压，隔离表的行数。这次运行没有发生提交冲突，只有 `committed`；`conflict` 的计数由 cove-catalog 的 `CommitProtocolTest` 验证，`CoveCommitConflicts` 由告警规则测试验证。提交指标只带表名，属于哪个源或分层表靠与维护积压指标关联。运行里没有维护任务，`cove_task_phase` 没有出现。查询新鲜度是经 cove-catalog 读到的，Doris 的读路径见 #32 |
| 错误数据有可追踪去向，关键正确性或延迟违约能产生告警 | 通过 | 4 行状态不合法的订单进了 DWD 的隔离表，带着未通过的检查 `allowed(state)`，不在 DWD 里；`CoveRowsQuarantined` 31 秒后触发。DWD 挂起超过 5 分钟，`CoveLayerLagP99` 触发，恢复后解除；源的作业挂起超过 5 分钟，`CoveSourceStale` 触发，恢复后解除。源恢复后，积压的心跳到达时延迟超过 5 分钟，`CoveSourceLatencyP99` 随之触发；ODS 的 level-0 文件超过 50 个时 `CoveCompactionBacklog` 进入 pending。其余告警由 `check-rules.sh` 的规则测试按 M0 阈值逐条验证 |
| 面板与告警所用指标与 M0 的验收口径一致 | 通过 | 6 条带源端提交时间的标记订单，从 ODS 的 changelog 独立测得的延迟中位数 19 秒，同一时段心跳延迟的中位数 23 秒，在容差内；ODS → DWD 的标记中位数 35 秒，Operator 测的逐层延迟中位数 31 秒，也在容差内。面板与告警用同一组 M0 目标（chart 的 `values.yaml`）。边界：只对照了 6 条标记的中位数，容差取 15 秒与中位数四分之一的较大者；告警用的 p95、p99 没有独立对照 |

## 运行

`infra/m3/flows/monitoring/run-monitoring.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **访问。** `install-monitoring.sh` 装好的 Prometheus 与 Grafana 只有 ClusterIP。经认证的 `kubectl port-forward`，Grafana 对未登录的请求返回 401，登录后看到面板。从没有监控标记的命名空间里的 Pod，Prometheus、Operator 与 cove-catalog 的指标端口都连接超时（curl 退出码 28：名字解析得到，连接被丢弃）；对照：从监控命名空间里的 Pod，两个指标端口返回 200，Prometheus（只接受 Grafana）仍然超时。
2. **流。** MySQL 源带心跳表，接入 orders、customers；DWD（orders 按客户 lookup join 地区）声明检查：`customer_id` 非空、`state` 只能是 created/paid/shipped、`amount` 不小于 0；DWS 按地区聚合 DWD。写入不停。
3. **各项测量。** 要求 Prometheus 抓到这次运行的每条路径的指标（按源、表、作业的标签筛选）：源库 → ODS 的心跳延迟与新鲜度、DWD 与 DWS 的逐层滞后和查询新鲜度、DWD 的逐层延迟、ODS 的按结果的提交、源与 DWD 作业的 checkpoint 和 TaskManager 堆、ODS 与 DWD 的维护积压（含 manifest 大小与最近一次提交的文件）、DWD 隔离表按检查的行数。
4. **与 M0 的口径对照。** 写入 6 条标记订单，每条在 `note` 里带源端提交时间（UTC）。从 ODS 的 changelog 独立找到每条首次出现的快照及其提交时间，得到它的延迟（M0 2.1：源端提交 → ODS 快照可读）；要求它们的中位数与同一时段这个源的心跳延迟的中位数相差不超过 15 秒或中位数的四分之一（取大者）。再从 DWD 的 changelog 找到每条首次出现的快照，与它在 ODS 的快照相减得到这一层的延迟，与 Operator 测的逐层延迟的中位数按同样的容差比较。
5. **错误数据。** 插入 3 条状态为 lost 的订单，再把一条已有订单改成 lost。要求 DWD 的 `status.quality` 记下 4 行 `allowed(state)`，告警 `CoveRowsQuarantined` 触发；独立读一遍：这 4 行在隔离表里、带着未通过的检查，不在 DWD 里。
6. **延迟违约。** 挂起 DWD 超过 M0 的 p99（5 分钟）：要求 `CoveLayerLagP99` 触发，DWD 与 DWS 的查询新鲜度都超过 5 分钟；恢复后告警解除。再挂起源的作业：要求 `CoveSourceStale`（源库 → ODS 超过 p99）触发，恢复后解除。
7. **清理。** 停止写入，记下提交、维护积压与 checkpoint 的指标，删除表和源。

## 结果

运行 o2（`b40bb98`）的 16 项检查全部通过：

| 检查 | 结果 |
| --- | --- |
| `grafana-signin-required` | 未登录的请求得到 401，登录后看到面板 "Cove Lake 运行" |
| `metrics-isolated` | 从作业命名空间的 Pod：Prometheus、Operator 与 cove-catalog 的指标端口都连接超时（curl 退出码 28）；从监控命名空间的 Pod：两个指标端口返回 200，Prometheus 仍然超时 |
| `source-ready`、`dwd-running`、`dws-running` | 源对账后 `Ready`（298 秒），DWD、DWS 运行 |
| `paths-measured` | 每条路径的指标都抓到了（`prometheus/series.json`，42 个序列） |
| `m0-source-latency-agrees` | 标记订单源库 → ODS：13、15、17、20、22、25 秒，中位数 19 秒；心跳中位数 23 秒 |
| `m0-layer-latency-agrees` | 标记订单 ODS → DWD：5、34、34、35、35、35 秒，中位数 35 秒；Operator 测的逐层延迟中位数 31 秒 |
| `quarantined`、`rows-quarantined-alerted`、`bad-rows-traceable` | `status.quality` 64 秒后记下 4 行 `allowed(state)`；`CoveRowsQuarantined` 31 秒后触发；独立读：订单 10、9100001–9100003 在隔离表里，不在 DWD 里 |
| `layer-lag-alerted`、`freshness-grows-down-the-path`、`layer-lag-resolved` | DWD 挂起 365 秒后 `CoveLayerLagP99` 触发；此时 DWD 与 DWS 的查询新鲜度都是 352 秒（超过 5 分钟）；恢复后 153 秒解除 |
| `source-stale-alerted`、`source-stale-resolved` | 源的作业挂起 319 秒后 `CoveSourceStale` 触发，恢复后 182 秒解除 |

记下的指标（`prometheus/`）：

- 09:43 时 ODS 三张表各有 23 次 `committed` 提交（`series.json`）；清理前合计 264 次 `committed`（`commits.json`，这次按结果汇总，没有按表分）。
- 清理前三个作业最近一次 checkpoint 距今 46–63 秒。
- ODS 表的 level-0 文件 44–96 个（写入端不压缩，由维护任务处理），DWD、DWS 为 0。`backlog.json` 的查询用了 `or`，同一张表只留下一个指标，小文件数与快照数没有记下；harness 已改为按指标名查询。

告警的快照里还有一项检查没有覆盖的：DWD 恢复之后有一次 checkpoint 失败，warning 级的 `CoveCheckpointsFailing` 从 09:55 起触发，到运行结束仍在它 15 分钟的窗口内。作业的 Pod 已随表删除，失败的原因没有留下。

运行之后的修改只有单元测试，没有在 EKS 上运行：

- 隔离表上的压缩在写它的作业的围栏后运行，与同一张表的回刷排队；
- 删除流程要去掉隔离表里主体的行时，回刷目标表。

之前的运行发现并修正了这些问题：

- **m1：** 链路没有升级 cove-catalog。集群上的 cove-catalog 还是没有指标端口的旧版本，`metrics-isolated` 的对照里，监控命名空间连 cove-catalog 的 9100 也超时。链路改为运行前用 `upgrade-catalog.sh` 升到同一提交。
- **m2：** 名字和 MongoDB 的 harness 撞了。两边的 ODS 库都叫 `ods_m3m_<run>`，写入进程都叫 `mw-<run>`，#30 的 m2 留下了 `ods_m3m_m2`，新源判为 `TargetConflict`。监控的 harness 改用 `m3o` 和 `ow-<run>`。
- **o1：** 声明了检查的表，作业身份只有目标表的 `WRITE`，没有隔离表的，DWD 一启动就被 cove-catalog 拒绝（403）。改为表的身份对隔离表也有 `WRITE`，这张表的回刷任务也是。

## 限制

- 心跳只有 MySQL 与 PostgreSQL 源；Kafka 事件源、Kafka 承载的 CDC 与 MongoDB 源只报积压（记录数、快照数），不报时间延迟。心跳按源测：一个源的表共用一次提交，单独落后的表只在它的状态和积压里。
- 查询新鲜度是经 cove-catalog 读到的数据的新鲜度；Doris 外表与物化视图的刷新滞后、外表查询的指标由 #32 补充。
- S3 503 Slow Down 次数与每次 commit 的 list 次数（HLD 11.2 存储指标）没有导出。
- 数据质量只做声明的流内检查（非空、枚举、范围）；批量校验与血缘属后续阶段。范围只能是固定的值，不能相对当前时间。
- 告警只在 Prometheus 里触发，接收方由部署方接入。

> 注：本文中的 issue 与 PR 编号（如 #29）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
