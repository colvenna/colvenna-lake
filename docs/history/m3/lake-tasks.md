---
title: "M3 批任务与维护任务"
date: 2026-10-15
status: complete
issue: 27
---

# M3 批任务与维护任务

本页记录 Issue #27 的验收：用户按表发起历史回刷、常规维护和日终 tag（`LakeTask`），用自己已有的编排器触发并判断最终结果。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "LakeTask" 一节，依据 HLD 9.2、9.3、11。执行路径沿用 M1 #17 的决定：Pod 内的 Flink batch，以有终态的 Kubernetes Job 提交（[批处理边界](../m1/batch-engine-boundary.md)）。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源库是 M1 的 RDS MySQL 8.0，每次运行使用自己的数据库。

**证据的来源版本**：结果来自提交 `4ecab1c`（`#30` 的 `eb8eea8` 之上；`#27` 的代码在合并前只做了 rebase），私有证据目录 `m3-27/run-t4-4ecab1c/`。镜像：Operator `cove-operator@sha256:9e4e39ce…`，作业 `cove-jobs@sha256:5f61121c…`（mysql）与 `cove-jobs@sha256:750ea176…`（sql），Airflow `apache/airflow@sha256:6499a680…`（2.10.5），cove-catalog `cove-catalog@sha256:63159657…`（这次运行前由 `infra/m3/scripts/upgrade-catalog.sh` 升级，见下文 t1）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 任务可查询开始、进度、完成、失败与重试结果，重复请求不会产生未定义效果 | 通过 | `status` 记下开始、结束、每次运行的 `history`、结果与原因：压缩提交了新快照，`retries: 0` 的任务失败为 "attempt 1 of 1" 并有 Warning，回刷第一次运行被删后第二次接着完成（"1 Failed, 2 Succeeded"）；重复 apply 同一份声明不再建 Job，改 spec 被 API server 拒绝，再打已有的 tag 以 "exists" 完成，同表的任务按创建顺序一个接一个跑 |
| 历史回刷仅在下游收敛后标记完成；维护不提前清理活跃读端所需数据 | 通过 | 回刷在 DWD 的栅栏后运行，DWS 挂起期间停在 `Converging` 并列出 DWS，恢复后才 `Succeeded`，此刻 DWS 已与源端聚合逐行一致；快照过期保留了 DWD consumer 所在的快照，DWD 从 savepoint 继续；有状态的 DWS 拒绝回刷（`StatefulQuery`） |
| 至少一种用户已有编排器可按公开接口触发与判断任务最终结果 | 通过 | Airflow 2.10.5 的 DAG 只用 `laketasks` 的 `create`、`get`：成功、重复与失败三种 DAG run 都按任务的终态判定；不带凭证创建任务返回 401 |

## 运行

`infra/m3/flows/tasks/run-tasks.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **源与分层表。** MySQL 库里有 `orders`（5000 行）和维表 `segments`（200 行）。写入器每秒约两个事务，一直写到第 5 步。`LakeSource` 接入这两张表；`LakeTable` 维持两张表：
   - DWD：`orders` 以 lookup join 关联 `segments`；
   - DWS：从 DWD 的 changelog 按 segment 统计。
2. **Airflow。** 一个 Pod 里跑 Airflow 2.10 的调度器（SequentialExecutor、SQLite）和 DAG `tasks/cove_lake_task.py`，Pod 用自己的服务账号，只有 `laketasks` 的 `create` 和 `get`。依次触发三次 DAG run：
   - 给 `ods.orders` 打日终 tag：要求 DAG run 成功、任务 `Succeeded`，`LakeDump` 读到这个 tag；
   - 同样的 conf 再跑一次：要求 DAG run 成功，找到已有的任务，不再建 Job；
   - 对 ODS 表回刷（不是分层表）：要求任务 `Failed`（`NotALakeTable`），DAG run 以 Operator 给出的原因失败。

   另外，不带凭证向 API server 创建 `LakeTask`，要求被拒（401 或 403）。
3. **维护。**
   - 对 `ods.orders` 全量压缩，要求提交新快照；再 apply 同一份声明，要求 `unchanged`、不再建 Job；改它的 spec，要求被 API server 拒绝；
   - 另建一个任务打已有的 tag，要求以 "tag … exists" 完成；
   - 对 `ods.segments` 同时建孤儿文件清理和压缩：要求后者 `Pending` 等前者，前者结束之后才开始；
   - 对 `ods.segments` 建 `retries: 0` 的压缩，Pod 一开始运行就删掉，要求任务 `Failed`，消息为 "attempt 1 of 1"，并发 Warning `TaskRunFailed`。
4. **快照过期与读端。** 挂起 DWD，等 150 秒（写入继续，`ods.orders` 积累快照），再以 `retainMin: 1`、`olderThan: 1m` 过期 `ods.orders`。要求最早保留的快照不晚于 DWD 的 consumer 位置；恢复 DWD，要求从 savepoint 继续、不触发 `RebuildRequired`。
5. **维表变化。** 停止写入，等两张表追上。把 segment 1–3 改名为 `platinum`，删掉 segment 4。lookup join 只在订单变化时重新查维表，已有订单不会跟上，所以要求此时 DWD 与源端 join 不一致。
6. **回刷。**
   - 对 DWS 回刷：它是聚合，作业有状态，要求 `Failed`（`StatefulQuery`）；
   - 挂起 DWS，对 DWD 建 `retries: 2` 的回刷。要求 DWD 被栅栏挂起（`Suspended`，消息写明是哪个任务），回刷运行期间 DWD 的作业一直不是 `RUNNING`；
   - 第一次运行在日志报告 upsert 已提交（`TASK_PROGRESS upserted`）之后被删掉，要求第二次运行接着完成，`history` 为 "1 Failed, 2 Succeeded"，结果里删掉了不再产生的行（segment 4 的订单）；
   - DWS 挂起期间，要求任务停在 `Converging`，`waitingFor` 列出 DWS；恢复 DWS 后要求 `Succeeded`，并且此刻（不重试）DWS 已与源端聚合逐行一致。写入已停，没有别的变化要追，所以过早的 `Succeeded` 会在这里暴露。
7. **正确性。** DWD 与源端 join、DWS 与源端聚合逐行一致，完整 changelog 回放无误。回刷改写行时源端版本不变，所以 DWD 的前像按整行比较，不按版本。
8. **拒绝与清理。**
   - 对不属于本命名空间声明的表建任务，要求 `Failed`，没有 Job；
   - 结束的任务的 token 都被拒（401）；
   - 删除所有任务，要求不留 Job，也不留栅栏；最后删除分层表和源。

## 结果

运行 t4（`4ecab1c`）的 42 项检查全部 PASS：

| 检查 | 结果 |
| --- | --- |
| `source-ready`、`dwd-running`、`dws-running` | 源两张表 `Ready`，DWD、DWS 运行 |
| `airflow-task-succeeded`、`airflow-repeat-reused`、`airflow-refused-failed` | 日终 tag 的 DAG run 成功（`eod-20261015-t4`）；同一 conf 再跑找到已有任务、不再建 Job；对 ODS 回刷的 DAG run 以 Operator 的原因失败 |
| `anonymous-refused` | 不带凭证创建 `LakeTask` 返回 401 |
| `tag-exists`、`compact-succeeded`、`compact-committed` | tag 可读；全量压缩把快照从 15 推进到 16 |
| `reapply-unchanged`、`changed-spec-refused` | 再 apply 不变、只有 1 个 Job；改 spec 被拒 |
| `tag-again`、`tag-again-idempotent` | 已有的 tag 以 "exists" 完成 |
| `orphans-succeeded`、`second-succeeded`、`tasks-serialized` | 同一张表的压缩等孤儿清理结束后才开始 |
| `retries-exhausted`、`retries-exhausted-reported` | "attempt 1 of 1: the run failed (BackoffLimitExceeded)"，Warning `TaskRunFailed` |
| `dwd-suspended`、`expire-succeeded`、`expire-kept-reader-position`、`dwd-resumed-after-expire`、`dwd-caught-up`、`dws-caught-up` | 过期 20 个快照，最早保留 21，DWD 的 consumer 在 21；DWD 从 savepoint 继续并追上 |
| `backfill-has-work` | 维表改动后 DWD 多出 40 行、93 行不同 |
| `refused-stateful`、`refused-stateful-backfill` | DWS 回刷 `StatefulQuery` |
| `dws-suspended`、`backfill-fenced`、`backfill-waits-for-dws`、`backfill-succeeded`、`dws-converged-at-success`、`backfill-retried` | 回刷在栅栏后运行（DWD 作业一直不在 RUNNING），DWS 挂起时 `Converging`；成功时 upsert 6684 行、删掉 40 行，DWS 已一致；两次运行 |
| `rows-dwd`、`changelog-dwd`、`rows-dws`、`changelog-dws` | 与源端 join、聚合逐行一致，changelog 回放无误 |
| `refused-outside`、`refused-outside-ran-nothing` | 命名空间外的表被拒，没有 Job |
| `finished-tokens-refused`、`tasks-deleted` | 结束任务的 token 都返回 401；删除后不留 Job 和栅栏 |

## 过程中的发现

- t1：孤儿文件清理调用 cove-catalog 失败：表没有 branch 时，列 branch 的接口返回 `null`，Paimon 2.0 的客户端把它变成不可修改的空列表，孤儿清理往里加 `main` 时出错；cove-catalog 改为返回 `[]`。另外 Airflow 内存不足、harness 清理有遗漏，均已修正；
- t1、t2：Airflow 2.10 遍历 DAG 目录时把 ConfigMap 卷的 `..data` 符号链接当成循环，DAG 处理进程退出，DAG 一直没有加载。改为启动时把 DAG 复制到普通目录；
- t3：重试耗尽的检查原先在 Pod 运行 5 秒后才删掉它，没有事做的压缩在这之前已经成功完成（`Succeeded`），检查落空：现在一开始运行就删掉 Pod；
- 还没有 phase 的 Pod 按"仍在运行"处理。

## 限制

- 回刷按整表重算，不做分区回刷；有状态查询（聚合）的回刷只修正目标表、不修正作业状态。见 LLD "已知限制"。
- 快照过期只保护有 consumer 的读端；按快照的批读要靠 `olderThan` 或 tag。
- 孤儿文件清理只删一天以前的文件（`olderThan` 至少 1 天），运行时表不到一天，验收里它删了 0 个文件：真的删除没有在 EKS 上演练，"不提前清理活跃读端所需数据"的依据是快照过期的检查。
- 收敛只看分层表；Doris 物化视图等派生副本由 #32 补充，PII 表的 tag 限制由 #28 补充。

> 注：本文中的 issue 与 PR 编号（如 #27）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
