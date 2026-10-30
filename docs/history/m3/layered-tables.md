---
title: "M3 声明式分层表与常驻流作业"
date: 2026-10-15
status: complete
issue: 26
---

# M3 声明式分层表与常驻流作业

本页记录 Issue #26 的验收：用户声明 DWD/DWS 表、它的查询和上游表，平台维持这张表及其常驻 Flink SQL 作业，并在升级、失败回退、暂停和故障之后保持正确。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "LakeTable" 一节，依据 HLD 9.1、11.1。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源库是 M1 的 RDS MySQL 8.0，每次运行使用自己的数据库。

**证据的来源版本**：结果来自提交 `a4d9958`，以该 SHA 构建镜像、部署 cove-operator。原始日志在私有证据目录 `m3-26/run-t3-a4d9958/`：`verdicts.tsv`（每项检查的结论）、`timeline.tsv`、`status/`（各检查点的资源状态）、`tables/`（声明）、`dumps/`（两端的行集、changelog 与比对结论）、`events.json`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 声明变更后，表和作业收敛到可观测状态；无效依赖或 SQL 明确拒绝 | **通过** | 两张表同时声明时 DWS 先等待上游，随后都进入 `Running`；9 种无效声明各以对应原因 `Failed`，既没有建表也没有作业；输出 schema 的修改被拒绝，作业照常运行，撤回修改后恢复 `Ready`；暂停、恢复与删除都收敛，状态、条件和事件都可以看到 |
| 有状态升级与失败回退不造成静默漏数或重复业务效果 | **通过** | 兼容升级都从升级时的 savepoint 恢复；不兼容升级（聚合前加过滤）无法映射状态，10 分钟后自动回退到原 spec，发出 Warning `UpgradeRolledBack`；之后 DWS 的行与 changelog 都与源端聚合一致，没有重复计数 |
| 上下游结果满足 M1 的正确性与恢复验收 | **通过** | 持续写入 3813 个事务，期间经历维表变化、升级、回退、暂停、TaskManager 丢失。停止写入后，DWD 与源端 join、DWS 与源端聚合逐行一致，完整 changelog 回放无误；consumer 丢失后作业以 `REBUILD_REQUIRED` 拒绝启动，源端又写入 391 个事务，DWD 的行保持不变（M1 #13） |

## 运行

`infra/m3/flows/tables/run-tables.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **源。** 建 MySQL 库：`orders`（5000 行）和维表 `segments`（200 行，三种 segment），都满足源端契约。启动写入器：每秒约两个事务，包含插入、版本递增的更新和偶尔的删除，一直写到第 9 步。声明 `LakeSource`，只接入这两张表。
2. **分层表。** 同时声明两张表，DWS 先于它的上游：
   - DWD：`orders` 以 lookup join 关联 `segments`，主键 `order_id`，与 M1 #13 的写法相同；
   - DWS：从 DWD 的 changelog 按 `segment` 统计订单数和金额，主键 `segment`。

   DWS 先停在 `Pending`（`UpstreamMissing`），DWD 建好后自动跟上。两张表都要求进入 `Running`，校验结论中的流读表和 lookup 表与预期一致。
3. **维表变化。** 把 3 个客户的 segment 改为 `platinum`，等 90 秒（CDC 提交一次、lookup 缓存刷新一次），再更新这些客户的全部订单。第 9 步的比对要求这些订单在 DWS 里离开原来的 segment、计入新的。

   M1 #13 只验证了维表变化之后到达的新订单。已有订单在维表变化后更新时，lookup join 发出的撤回带的是新值，只有从表中旧行生成的 changelog（`changelog-producer=lookup`）才能让 DWS 正确扣减。
4. **拒绝。** 下面每个声明都必须以对应原因进入 `Failed`，并且没有建表、没有作业：

   | 声明 | 原因 |
   | --- | --- |
   | 不能解析的 SQL | `InvalidQuery` |
   | 读了没声明的表 | `UpstreamNotDeclared` |
   | 声明了却不读的表 | `UpstreamMismatch` |
   | join 键不在目标主键里的普通 join | `UnorderedChangelog` |
   | 上游是本命名空间以外的表（M2 遗留表） | `UpstreamNotAllowed` |
   | 两张表互读 | 两张都是 `DependencyCycle` |
   | 读自己 | `InvalidSpec` |
   | 写别人的目标表 | `TargetConflict` |

5. **schema 变化。** 给 DWS 加一个输出列。要求判为 `SchemaChanged`，旧作业照常运行；然后改回。
6. **兼容升级。**
   - DWD 的 checkpoint 间隔从 30 s 改为 20 s；
   - DWS 的 TaskManager 内存改为 4096m。这一项对应 M1 #13 的"DWS 独立 savepoint 重启"，要求新作业恢复自的路径，正是 Flink Operator 记录的这次升级的 savepoint。
7. **不兼容升级。** 在 DWS 的聚合前加一个过滤条件。输出 schema 不变，但计划变了，savepoint 里的聚合状态对应不到新的算子。要求 Operator 用升级时的 savepoint 回退到原 spec，给出 `RolledBack` 条件与 Warning `UpgradeRolledBack`；然后改回原查询，条件恢复。
8. **暂停与故障。**
   - 暂停 DWD 90 秒，期间源端继续写。要求 DWD 没有新快照，因而 DWS 也不动；恢复后，要求 DWD 从新的 savepoint 继续。
   - 删除 DWS 的 TaskManager，要求从 checkpoint 恢复。
9. **正确性。** 停止写入，等两张表读到上游的最新快照（consumer 落后 0），然后比对：
   - DWD 与源端 `orders JOIN segments` 逐行一致，完整 changelog 回放无误；
   - DWS 与源端 `GROUP BY segment` 的结果逐行一致，changelog 回放时每条撤回都等于当时的聚合值。
10. **丢失上游位置。** 重新启动写入器，暂停 DWD，删掉它在 `orders` 上的 consumer（模拟 consumer 过期），再恢复。要求作业以 `REBUILD_REQUIRED` 拒绝启动（`RebuildRequired`），并且源端继续变化时 DWD 的行不变。
11. **删除。** 两张表都是 `Delete` 策略。
    - 先删 DWD：它仍被 DWS 读取，所以保留目标表，并发 Warning `TargetRetained`；
    - 再删 DWS：作业消失，它在 DWD 上的 consumer 被删，目标表删除；
    - 两张表的 token 都被拒（401）；
    - 最后删除源。

**比对方法**与 [#24](declarative-cdc.md#运行) 相同：取证用只读身份 `m3-verifier`，`LakeDump` 读最新快照的行和完整 changelog，`compare.py` 按主键逐列比较并逐条回放。DWS 没有版本列，回放时撤回按整行比较：撤回的值不等于表中当时的值，就说明聚合重复计数或漏计了。两张表都声明 `snapshot.time-retained: 1 d`，好让完整 changelog 在运行结束时仍可回放。

M1 #13 的"consumer 落后于最早保留的快照"一项需要快照过期，而接入表是 write-only、M3 还没有维护作业，所以由 `SqlJobTest` 在本地 Paimon 表上验证。EKS 上验证的是 consumer 丢失的一支。

## 结果

2026-10-15 的 `run-t3-a4d9958` 有 37 项检查，全部通过。

**声明与拒绝**：

| 检查 | 用时 |
| --- | --- |
| 源两张表 `Ready`（写入贯穿初始快照） | 454 s |
| DWS 先于上游声明，停在 `Pending`（`UpstreamMissing`） | 3 s |
| DWD `Running`，流读 `orders`、lookup `segments` | 75 s |
| DWS `Running`，流读 DWD | 64 s |
| 9 种无效声明各以对应原因 `Failed` | 各 2～26 s |
| 输出 schema 修改被拒，作业照常运行 | 15 s |

被拒的声明与原因见"运行"第 4 步的表，结果与之一致。这些声明都没有留下目标表或作业。

**升级、回退与故障**：

- 改 checkpoint 间隔与 DWS 的 TaskManager 内存，作业都从升级时的 savepoint 恢复，状态中记录了恢复自哪个 savepoint；
- 在聚合前加过滤，Flink 无法映射状态（`Cannot map checkpoint/savepoint state`）。10 分钟后作业自动回退到原 spec：从升级时的 savepoint 重新部署，用时 699 s，并发 Warning `UpgradeRolledBack`。撤回声明后 14 s 恢复；
- 暂停 DWD 期间源端继续写入，DWD 停在快照 102；恢复后从暂停时的 savepoint 继续；
- 删掉 DWS 的 TaskManager，4 s 后恢复运行。

**正确性**：停止写入后：

| 表 | 源端行数 = 目标行数 | changelog |
| --- | --- | --- |
| DWD `orders` | 8439 | +I 8713，-U 2816，+U 2816，-D 274 |
| DWS `segments` | 4（含 `platinum`） | +I 4，-U 69，+U 69 |

回放检查了以下几点，都通过：

- 每条撤回都等于表中当时的行，DWS 按整行比较；
- 插入没有落在已有主键上；
- DWD 的版本只增不减；
- 回放结果等于最新快照。

维表变化之后更新的订单，在 DWS 里离开了原来的 segment，计入 `platinum`。

**丢失上游位置**：删掉 DWD 在 `orders` 上的 consumer 后恢复，作业以 `REBUILD_REQUIRED` 拒绝启动，用时 38 s，并发 Warning `RebuildRequired`。源端随后又写入 391 个事务，60 s 后 DWD 的行仍逐字节不变。

**删除**：先删 DWD，它仍被 DWS 读取，目标表保留，并发 Warning `TargetRetained`。再删 DWS：作业消失，它在 DWD 上的 consumer 被删，目标表删除。两张表的 token 都被拒（401）。

## 过程中的发现

- **回退判断不能依赖作业的启动时间。** 恢复失败时，Flink Operator 不更新 `jobStatus.startTime`，它停在旧值。第一次运行因此认不出失败的升级，拒绝回退。现在改用 checkpoint 编号判断：Paimon 快照的 `commitIdentifier` 就是 checkpoint 编号，并且在 savepoint 之后连续。新作业没有完成过 checkpoint，或者目标表最新的 `commitIdentifier` 不超过升级前的 checkpoint 加一，就能证明新作业没有写过目标表，可以从升级时的 savepoint 回退。
- **验收脚本自己的三处问题。** 第二次运行中发现，修复后重跑：
  - 删除 consumer 后表上一个 consumer 也没有，`lake.sh` 把空输出当成失败；
  - 第二个写入器的主键和第一个一样从 1000000 开始，每个事务都因主键冲突失败，"拒绝启动期间源端仍在变化"实际没有写入。现在写入器从已有的最大主键之后接着写，判定也要求这一段的事务数大于零；
  - `LakeDump` 的表头字段顺序每次运行不同，前后两次 dump 无法逐字节比较。

## 复现

```bash
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/flows/sources.sh users
bash infra/m3/flows/tables/run-tables.sh <run-id> <evidence-dir>
```

声明与查看用 `cove`：

```bash
cove apply -f orders-dwd.yaml
cove get tables -n cove-m3
cove describe table orders-dwd -n cove-m3     # 校验结论、作业、恢复来源、各上游的 consumer 进度
cove wait table orders-dwd -n cove-m3 --for ready
```

> 注：本文中的 issue 与 PR 编号（如 #26）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
