---
title: "M3 Doris 看板服务与查询负载隔离"
date: 2026-10-16
status: complete
issue: 32
---

# M3 Doris 看板服务与查询负载隔离

本页记录 Issue #32 的验收：BI 与看板通过平台的 Doris 查询湖表，固定聚合与少量热数据在声明的副本边界内满足响应与新鲜度目标，明细权威仍在 Paimon。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "看板服务（LakeDashboard）"，依据 HLD 10.1、12.2、14；目标取自 [M0 验收契约 2.1 节](../../design/acceptance-contract.md#21-测量协议)。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。Doris 装在现有的两个 query 节点（r6i.xlarge）上，没有增加容量。

**证据的来源版本**：结果来自提交 `7765092`，私有证据目录 `m3-32/run-d3-7765092/`。镜像：Operator `cove-operator@sha256:e74794a3…`，作业 `cove-jobs@sha256:5b54e673…`（mysql）与 `cove-jobs@sha256:89e15187…`（sql），Doris FE `m1-doris@sha256:ae19e6d6…`、BE `m1-doris@sha256:ecaf6f89…`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 按查询清单区分外表、物化结果和内表，结果与权威数据一致 | 通过 | 七个查询按清单分别以外表视图、物化视图、内表服务；停止写入、副本刷新两轮后，`region_totals` 与 DWS（4 行）、`customers_hot` 与 ODS 客户（200 行）、`order_lookup` 与 DWD（49,118 行）逐行一致 |
| 看板响应分位数、刷新滞后和副本恢复时间满足 M0 确定的目标 | 通过 | 固定看板查询 24,000 个样本 p95 0.25 秒、p99 0.54 秒（目标 3 / 10 秒），ad-hoc 突发期间 p95 0.32 秒；内表点查 12,000 个样本 p95 0.26 秒。每条路径 24,018 个新鲜度样本：源库 → 外表 p95 36 秒（目标 5 分钟），源库 → DWS 看板 p95 135 秒（15 分钟），Paimon → 外表 p95 7 秒（3 分钟），→ 物化视图 p95 49 秒、→ 内表 p95 69 秒（5 分钟）；积压没有增长。ad-hoc 看板与 `normal` 的突发不影响固定看板：两组按 FE 的计数各最多 2 个在执行，有查询排队（规划不在排队范围内，见"限制"）。BE、FE 各丢失一次，所有路径分别在 143 秒、126 秒内恢复（RTO 30 分钟） |
| 用户级权限与查询审计在各读路径有效，管理账号边界明确 | 通过 | 每个用户只读到所给的对象，catalog、`SHOW CREATE CATALOG`、查询日志、别的看板、`information_schema` 与 `mysql` 都被拒；root、admin 的空口令和错误口令被拒；没有声明的命名空间连不上 FE。查询日志里每个用户对所得的每条路径都有记录，也有被拒的记录；日志留 7 天。删除看板后用户不能登录，库、catalog、workload group 消失，token 被拒（401）。root 与 admin 只由 Operator、安装脚本使用，审计日志与 catalog 定义只在这个边界内可见 |

## 运行

`infra/m3/scripts/install-doris.sh` 装好平台的 Doris 后，`infra/m3/flows/dashboards/run-dashboards.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **湖。** MySQL 源带心跳表，接入 `orders`、`customers`、`cove_heartbeat`；DWD（订单关联客户的地区）、DWS（按地区汇总，另计其中的 marker 订单）是 LakeTable。写入不停。ODS 的写入端不压缩，所以每 10 分钟为 ODS 的 `orders`、`customers` 各请求一次全量压缩（`LakeTask`，像 #27 的编排器那样）：Doris 外表读一个还有 level-0 文件的桶时，要同时打开桶里的全部文件（见"限制"）。
2. **看板。** `LakeDashboard` `board-<run>` 按查询清单服务七个查询：

   | 查询 | `serve` | 读什么 |
   | --- | --- | --- |
   | `region_totals` | 物化视图 | DWS（固定看板查询） |
   | `marker_mv` | 物化视图 | ODS 里的 marker |
   | `marker_live` | 外表视图 | 同上，查询时读湖 |
   | `marker_dws` | 物化视图 | DWS 计入的 marker |
   | `order_lookup` | 外表视图 | DWD 订单（交互点查） |
   | `customers_hot` | 内表 | ODS 客户（热维表） |
   | `marker_hot` | 内表 | ODS 里的 marker |

   三个用户：`analyst` 得到外表与物化视图，`viewer` 只得到 `region_totals`，`ops` 得到两张内表。另一个 `LakeDashboard` `adhoc-<run>` 服务一个重查询 `explore`（DWD 订单按客户自关联），`workload.maxConcurrency: 2`，四个用户。要求两者都 `Ready`，副本都刷新过。
3. **访问。** 从声明为 Doris 客户端的命名空间：
   - 每个用户读得到所给的，读不到其余的：catalog 里的表、`SHOW CREATE CATALOG`（其中有 token）、查询日志、另一个看板的对象，以及 Doris 默认开放而 Operator 撤销的 `information_schema` 与 `mysql`；
   - root 和 admin 的空口令、错误口令都被拒；
   - 从没有声明的命名空间，FE 的 HTTP 与 MySQL 端口都超时；对照：从声明的命名空间，MySQL 端口可达。

   Doris 仍向任何用户开放的（`tasks()`、`SHOW BACKENDS`、自己视图的定义）记录下来。
4. **marker。** 一个 Pod 每 0.1 秒向源库提交一个 marker 订单（id 从 10,000,000 起），记下 MySQL 提交后的时钟。同一个 Pod 每秒轮询每条读路径：`marker_live`、`marker_mv`、`marker_dws`、`marker_hot`。
   - **样本：** 每个 marker 在每条路径上第一次被读到（且它之前的 marker 都已读到）的时刻就是一个样本。
   - **起点：** 源库的提交时刻，以及 Paimon 提交 ODS 的时刻（LakeDump changelog 的 `committedAt`）。
   - **对照（M0 2.1）：**

     | 路径 | p95 / p99 |
     | --- | --- |
     | 源库 → Doris 外表 | 5 / 10 分钟 |
     | 源库 → DWS 看板 | 15 / 30 分钟 |
     | Paimon → Doris 外表 | 3 / 6 分钟 |
     | Paimon → 物化视图、内表 | 5 / 10 分钟 |

   - **时钟：** 记录 MySQL、marker Pod 与 Flink Pod 的时钟偏差，超过 1 秒的样本无效。
   - **稳态：** 窗口最后 5 分钟的延迟中位数比最初 5 分钟高出不到 1 分钟，即积压不在增长。
5. **负载。** 40 分钟开环负载（M0 的稳态至少 30 分钟），每秒：
   - 10 个固定看板查询（`region_totals`）；
   - 5 个内表点查（`customers_hot`）；
   - 1 个外表交互点查。

   每个查询从应发出的时刻起计时。前两类对照 p95 ≤3 秒、p99 ≤10 秒，并且要有 M0 要求的 10,000 个以上样本。中间 20 分钟，ad-hoc 看板的四个用户各开三个连接反复跑重查询，其中两个先 `SET workload_group = 'normal'`：
   - 固定看板查询在这 20 分钟里仍满足目标；
   - 按 FE 自己的计数（管理客户端每 0.5 秒 `SHOW WORKLOAD GROUPS`），ad-hoc 看板的 workload group 和 `normal` 同时运行的查询都不超过 2 个，并且都有排队或被拒的查询（限制确实起作用了）。审计日志的耗时包括 FE 的规划，外表查询的规划要读 catalog，在排队之前，所以日志里重叠的查询数只作观测；
   - 每个用户的第三个连接被拒。
6. **恢复。** 删掉 BE 的 Pod，再删 FE 的 Pod。从注入起计时，直到每条路径都恢复（M0 单 Pod 重启 RTO ≤30 分钟）：
   - 恢复后连续三次都有回答；
   - 湖路径读到注入时已提交的 marker，并且滞后回到稳态 p95 以内；
   - 内表在注入后重写过一次。
7. **一致。** 停止写入，等 DWS 追上、副本刷新两轮后：`region_totals` 与 DWS、`customers_hot` 与 ODS 客户、`order_lookup` 与 DWD 逐行一致。
8. **审计。** 查询日志里，每个用户对它所得的每条路径都有查询记录，每个用户都有被拒的记录。保留设置也要符合：
   - 审计表 7 个按天的分区；
   - 审计文件 5 天后删除；
   - 回收站 1 天；
   - BE 不留 trash。
9. **删除。** 删除两个 `LakeDashboard`：用户登录被拒，数据库、catalog 与 workload group 消失，它们的 cove-catalog token 被拒（401）。

## 结果

运行 d3（`7765092`）的 26 项检查全部通过：

| 检查 | 结果 |
| --- | --- |
| `source-ready`、`dwd-running`、`dws-running`、`dashboard-ready` | 源 249 秒后 `Ready`，DWD、DWS 运行；两个看板 `Ready`，七个查询都服务着，副本都刷新过 |
| `users-read-what-they-are-given`、`system-schemas-withdrawn` | `analyst` 读到 5 个对象，其余（内表、catalog 里的表、`SHOW CREATE CATALOG`、查询日志、另一个看板的视图）都被拒，看不到 catalog；`viewer`、`ops`、`explorer` 同样只读到所给的；`information_schema` 与 `mysql` 被拒。Doris 仍向用户开放的 `tasks()`、`SHOW BACKENDS` 与自己视图的定义记在时间线里 |
| `passwords-required` | root 与 admin 的空口令、`analyst` 的错误口令都被拒 |
| `doris-isolated` | 从作业命名空间，FE 的 HTTP 与 MySQL 端口都连不上（连接时间 0）；从 BI 命名空间，MySQL 端口连得上，HTTP 端口连不上 |
| `load-within-m0`、`dashboard-under-burst` | 40 分钟开环负载：固定看板查询 24,000 个样本 p95 0.247 秒、p99 0.537 秒；内表点查 12,000 个样本 p95 0.257 秒、p99 0.54 秒；ad-hoc 突发的 20 分钟里固定看板查询 12,114 个样本 p95 0.316 秒、p99 0.802 秒；没有错误。外表交互点查 2,400 个样本 p95 2.2 秒（M0 不承诺，只观测）。审计日志里服务端耗时 p95 33 毫秒 |
| `steady-state`、`fresh-*` | 每条路径 24,018 个 marker，时钟偏差都在 1 秒内（源 3 毫秒，Flink 26 毫秒），没有空洞。源库 → 外表 p50 23 秒、p95 36 秒、p99 39 秒；源库 → DWS 看板 p95 135 秒、p99 139 秒；Paimon → 外表 p95 7 秒、p99 9 秒；Paimon → 物化视图 p95 49 秒、p99 50 秒；Paimon → 内表 p95 69 秒、p99 74 秒。最后 5 分钟的中位数比最初 5 分钟高 1.5 秒 |
| `adhoc-bounded`、`connections-bounded` | ad-hoc 看板的组与 `normal` 都各跑了 2,300 多次重查询。FE 的计数在突发期间每组采样 2,237 次，覆盖整个突发：执行中的最多 2 个（满 2 个的时间约 36%），排队的最多 2 个（有排队的时间约 4%）；审计日志里有 315 / 387 次排过队。日志里重叠的查询有 4 个，多出的在规划。每个用户的第三个连接都被拒（各约 1,160 次） |
| `recovered-be`、`recovered-fe` | 删 BE 后 143 秒、删 FE 后 126 秒，每条路径都连续回答并读到注入时已提交的 marker，滞后回到稳态 p95 以内，内表重写过 |
| `consistent-*` | `region_totals` 与 DWS、`customers_hot` 与 ODS 客户、`order_lookup` 与 DWD 逐行一致 |
| `queries-audited`、`doris-retention` | 每个用户对所得的每条路径都有查询记录，也都有被拒的记录；审计表 7 个按天的分区、审计文件 5 天、回收站 1 天、BE 不留 trash |
| `dashboard-deleted` | 删除后用户登录被拒，库、catalog 与 workload group 消失，两个看板的 cove-catalog token 被拒（401） |

运行期间，每 10 分钟为 ODS 的两张表各请求一次全量压缩，8 轮 16 次都成功（按 Operator 的日志，私有证据 `compactions.txt`）。d3 的 harness 当时没有记下这些任务的结果；之后的版本在删除前记下 `status/compactions.json`，并多了一项 `ods-compacted`。`adhoc-bounded` 在运行之后又按更严的规则重新判定过一次（FE 的计数要覆盖整个突发、执行数要达到上限并且有排队），结果相同（`doris/burst-rejudged-08aa144plus.json`）。

之前的运行发现并修正了这些问题：

- **d1：** 看板向 cove-catalog 申请表级授权时没有带 `approval`，cove-catalog 返回 400，看板一直 `Degraded`。改为以看板的 uid 作为 approval；测试用的 catalog 也像真实的一样要求它。看板始终不 `Ready` 时，harness 现在直接收尾。
- **d2：** 23 项通过，3 项失败：
  - `doris-isolated`：探测 MySQL 端口用的是 curl 的 telnet，连上之后服务端等客户端认证，curl 照样超时（退出码 28），分不出连没连上。改为看连接时间。
  - `adhoc-bounded`：按审计日志，两个组都有 4 个查询同时在跑。但日志里的耗时包括 FE 的规划，而外表查询的规划要读 catalog，在排队之前。在 Doris 上对照测过（私有证据 `m3-32/queue-control-test.md`）：上限为 2 时同时发 4 个外表查询，4 个一起规划，FE 计数的执行数始终不超过 2，另外 2 个等执行槽位。改为按 FE 自己的计数判定。
  - `recovered-be`：BE 重启后，读 ODS 的外表、物化视图、内表一直不恢复。原因见"限制"里的"ODS 要定期压缩"：没有压缩，level-0 文件涨到 366 个（`run-d2-…/level0-files.json`），JNI 读取器超时被取消后泄漏 S3 连接（线程转储 `d2-be-threads-1243.txt`）。改为每 10 分钟压缩一次 ODS。诊断时手动压缩了 ODS、重启了 BE，之后 FE 恢复、一致、审计、删除各项都通过，但这些只作诊断，不算验收。

## 限制

- **部署：** 平台的 Doris 只有一个 FE、一个 BE（M3 的容量），没有高可用；恢复时间是单 Pod 重启的。它是存算一体，派生副本在 gp3 卷上，不是 HLD 10.1 设想的 Storage Vault 存算分离。
- **数据量：** 千万行、7 天窗口的查询档由 M1 实测（[24 小时基线](../m1/doris-paimon.md#结项判定)）；这里测同样的负载形态，数据量小。
- **传输与口令：** 客户端与 FE、FE 与 BE 之间是集群内的明文 MySQL 协议；Doris 用户口令以 MySQL 口令哈希保存；用户是 Doris 本地用户，不是 LDAP。
- **外表授权：** 用户不能直接查外表，外表路径也经视图授予，因为 catalog 的属性里有它的 token。
- **刷新方式：** 物化视图按固定周期整表刷新（Paimon 基表不能 ON COMMIT 刷新）；内表由 Operator 按周期整表重写，适合少量热数据。
- **元数据可见：** Doris 仍向任何用户开放 `tasks()`、`jobs()`、`SHOW BACKENDS` 和自己视图的定义，这些是元数据。
- **排队的范围：** workload group 的排队只管执行。FE 的规划在排队之前，外表查询要在规划时读 catalog 的元数据，这部分不受 `maxConcurrency` 限制。d3 的突发里，同时在途的 4 个查询多数时间有 2 个在规划：执行中满 2 个的时间只占约 36%。规划的并发只受用户数与每个用户的连接数（`maxConcurrency`）限制，它压在 FE 和 cove-catalog 上，而不在 BE 上。
- **ODS 要定期压缩：** ODS 的写入端不压缩。Doris 外表读一个还有 level-0 文件的桶时，走 Paimon 的 JNI 读取器，要同时打开桶里的全部文件。运行 d2 没有压缩 ODS，`orders` 的 level-0 文件 90 分钟里从 10 个涨到 366 个，读 ODS 的查询越来越慢、超时被取消。被取消的 JNI 读取器没有释放 S3 连接，BE 的连接池随之耗尽：75 个线程在等连接，没有一个在读。此后这张表的外表读、物化视图刷新和内表重写都失败，压缩之后也没有恢复，直到 BE 重启。所以 Doris 读的 ODS 表要由编排器定期压缩（#27 的 `LakeTask`），运维要求如下：
  - **间隔：** 压缩的间隔要让 level-0 文件远低于 d2 停顿时的约 300 个。按 d2 每分钟约 4 个的增长，至少每小时一次；验收用的是 10 分钟。
  - **告警：** level-0 文件超过 50 个持续 30 分钟时 `CoveCompactionBacklog` 告警（#29），按这个增长约在 170 个时触发，离停顿还有约 30 分钟。
  - **恢复：** 连接一旦泄漏，压缩救不回来，要重启 BE。

  泄漏的原因是根据一次线程转储推断的：等连接的线程很多，没有线程在读，所以连接被没有关闭的流占着。泄漏本身是 Doris 的 JNI 读取器的问题，平台没有修复。压缩只降低暴露：两次压缩之间，以及 DWD、DWS 还有 level-0 文件时，被取消的 JNI 读取仍可能泄漏连接。
- **Doris 镜像的口令参数：** Doris 镜像的 FE、BE 启动脚本以 `-p<口令>` 把 root 口令交给 mysql 客户端，用来注册节点（Doris Operator 的 `authSecret`）。这个参数只在 Doris 自己的容器里可见，mysql 客户端启动后会把它抹掉；平台的脚本与 Operator 不在参数里放口令。
- **删除证据：** 删除对看板副本以刷新时序为证据，不做负向查询。
- **指标：** 外表查询的 native reader 分片占比、内外表响应差只在验收中测得，没有常驻指标（HLD 11.2）。

> 注：本文中的 issue 与 PR 编号（如 #32）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
