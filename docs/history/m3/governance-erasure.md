---
title: "M3 保留策略与跨层个人数据删除"
date: 2026-10-16
status: complete
issue: 28
---

# M3 保留策略与跨层个人数据删除

本页记录 Issue #28 的验收：每张表声明分类与保留边界，只有受治理的表才发布给读端；用户发起一次受控删除，得到跨层的逻辑与物理完成状态和证据。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "治理与个人数据删除" 一节，依据 HLD 12.4、12.5、12.7 与 [M0 验收契约第 4 节](../../design/acceptance-contract.md#4-合规删除的副本范围与完成证据)。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。

**证据的来源版本**：结果来自提交 `a240681`（之后 rebase 到合并了 #113 的 `main`，只带进 #113 的改动），私有证据目录 `m3-28/run-g7-a240681/`。镜像：Operator `cove-operator@sha256:95633092…`，作业 `cove-jobs@sha256:65d105ad…`（mysql）与 `cove-jobs@sha256:ed3368c8…`（sql）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 缺少必要保留或分类声明的表不能以"已受治理"状态发布 | 通过 | 运行中依次去掉 DWD 的 `retention`、再去掉整个 `governance`：`Governed` 分别为 False（`RetentionMissing`、`Unclassified`），读端两次都得到 403，重新声明后 200。低于上游的表 `BelowUpstream`，不发布（403）；DWD 降到 L2 时 grant 立即撤销（403），恢复后重新发布（200）。其余原因（`LevelTooLow`、`SubjectKeyMissing`、`RetentionTooLong`、`UpstreamUnclassified`）由单元测试 `GovernancePolicyTest` 覆盖。个人数据表的保留选项都在 24 小时以内，按个数保留的下限为 1，DWD 设了 `compaction.optimization-interval=1 h` |
| 删除流程覆盖所有受管副本，逻辑不可查与物理删除分别有可核对状态 | 通过 | 受理时列出 ODS、DWD 的行、历史、文件，源库，两个作业及不再声明的作业的 Flink 状态，读端，对象存储；受理后 1458 秒 `LogicallyErased`。独立的取证身份读三张表都没有这个客户的行，每张表最早保留的快照都不早于各自的"干净"快照。DWD 的流没有带来删除（lookup join 丢了它）：它读的两张表干净、流也读过了干净快照之后，删除流程发起回刷，删掉的 27 行正是这个客户在 DWD 里的 27 行，发了事件 `ErasureBackfill`，DWD 的 `Rows` 证据写明了这次回刷 |
| 未能满足约定时限时明确报告未完成及残留位置，不生成虚假的完成证明 | 通过 | 物理层面在运行时间内做不完（孤儿文件清理只删一天以前的文件）。过了 75 分钟的物理期限，Operator 在 29 秒后把删除判为 `SlaBreach`：`status.breaches` 记下一条，列出三张表的 `Files` 还没完成和原因（各自的孤儿文件清理要到第二天才会覆盖压缩前的文件），条件 `WithinDeadlines` 为 False，发 Warning `ErasureOverdue`。条件 `PhysicallyErased` 保持 False，没有物理完成的时间 |

## 运行

`infra/m3/flows/governance/run-governance.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **声明。** 在 cove-catalog 建一个读端 principal。`LakeSource` 接入 `orders` 和 `customers`，声明为个人数据（L3），保留 24 小时，主体键分别是 `customer_id` 和 `id`，发布给这个读端。两张 `LakeTable`：
   - DWD：`orders` 以 lookup join 关联 `customers`，声明为个人数据（L3），保留 24 小时，主体键 `customer_id`，也发布给读端；
   - 一张 L2、保留 7 天、没有个人数据类别的表读 `orders`：低于上游，要求 `Governed` 为 False（`BelowUpstream`），不发布。
2. **发布。** 用读端的 token 读表：要求受治理的三张表可读（200），低于上游的那张不可读（403）。把 DWD 降到 L2，要求它的 grant 被撤销（403），恢复后重新发布（200）。再去掉 DWD 的 `retention`（`RetentionMissing`）、再去掉整个 `governance`（`Unclassified`），两次都要求不可读（403），重新声明后可读（200）。
3. **有界保留。** 要求两张个人数据表的选项是 `snapshot.time-retained=24 h`、`consumer.expiration-time=24 h`、`snapshot.num-retained.min=1`；DWD 的写入端自己压缩，还要有 `compaction.optimization-interval=1 h`。对个人数据表建保留两天的 tag，要求 `Failed`（`RetentionTooLong`）。ODS 的写入端不压缩、不过期快照：要求 Operator 为两张 ODS 表各建一个每小时的过期任务，并且成功。
4. **删除。** 停止写入，删掉低于上游的那张表（它会让删除停在 `Blocked`）。以源库所有者的身份删除客户 42 的行，建 Secret（主体键的值只在这里）和 `LakeErasure`，把期限缩短为逻辑 60 分钟、物理 75 分钟。受理之后重启 Operator，它要接着做已经开始的事。要求：
   - 60 分钟内 `LogicallyErased`：源库核对过；ODS 与 DWD 的最新快照里没有这个客户的行（源库先删订单、再删客户，同一次 checkpoint 提交，DWD 的 lookup join 会丢掉订单的删除，要由删除流程发起的回刷去掉这些行）；文件里的记录和 manifest 统计里的值压缩掉了（DWD 的写入端自己压缩，它的压缩在围栏后运行）；"干净"快照之前的快照，以及变更里还带着这个客户删除记录的快照，都已过期；没有 tag 和读端停在更早的快照上；
   - 独立读一遍（取证身份 `m3-verifier`）：三张表里都没有这个客户的行，每张表最早保留的快照都不早于它的"干净"快照；
   - DWD 的压缩任务在围栏后成功，围栏已解除；
   - DWD 的流没有带来删除时，删除流程发起的回刷要有交代：回刷任务成功，有 Normal 事件 `ErasureBackfill`，DWD 的 `Rows` 证据写出这次回刷；流带来了删除时，不应有回刷；
   - 两个作业的 Flink 状态里没有早于删除的对象（检查作业以作业的身份列出 checkpoint 与 savepoint，删掉恢复用不到的旧对象）；
   - 物理层面在运行时间内做不完：孤儿文件清理只删一天以前的文件。所以过了 75 分钟的物理期限，要求 `SlaBreach`，消息列出还没完成的副本和原因，`status.breaches` 记下一条，并发 Warning `ErasureOverdue`；条件 `WithinDeadlines` 为 False，`PhysicallyErased` 仍为 False，没有物理完成的时间；
   - 对象存储一项有安装脚本留下的证据（仓库桶没有开版本、没有复制）。
5. **清理。** 删除 `LakeErasure`、表和源；要求读端不再持有任何 grant。

## 结果

运行 g7（`a240681`）的 28 项检查全部通过：

| 检查 | 结果 |
| --- | --- |
| `source-ready`、`source-governed`、`source-published` | 源对账后 `Ready`（279 秒），受治理，两张 ODS 表发布给读端 |
| `dwd-running`、`dwd-published` | DWD 运行，发布给读端 |
| `low-not-governed`、`low-not-published` | L2 的表读 L3 的上游：`BelowUpstream`，不发布 |
| `published-only-governed` | 读端读 ODS 两张表和 DWD 得到 200，读低于上游的表得到 403 |
| `dwd-lowered-unpublished`、`dwd-republished`、`grant-follows-governance` | DWD 降到 L2 后读端得到 403，恢复后 200 |
| `dwd-retention-missing`、`dwd-unclassified`、`dwd-declared-republished`、`undeclared-unpublished` | 去掉 `retention`：`RetentionMissing`，403；去掉 `governance`：`Unclassified`，403；重新声明后发布，200 |
| `bounded-retention` | 两张个人数据表：`snapshot.time-retained=24 h`、`consumer.expiration-time=24 h`、`snapshot.num-retained.min=1`；DWD 还有 `compaction.optimization-interval=1 h` |
| `long-tag-refused`、`retention-enforced` | 保留两天的 tag 被拒（`RetentionTooLong`）；两张 ODS 表每小时的过期任务成功 |
| `dwd-caught-up`、`erasure-counted-before` | 删除之前，DWD 里有这个客户的 27 行 |
| `erasure-logical` | 06:51:35 受理，07:15:53 `LogicallyErased`（1458 秒）。期间 ODS 两张表各压缩一次、过期两次旧快照；DWD 先回刷（见下）、再在围栏后全量压缩、过期旧快照 |
| `erasure-independent-read` | 取证身份读三张表：这个客户 0 行；每张表最早保留的快照都不早于"干净"快照（ODS 的 customers 19、orders 18，DWD 18） |
| `dwd-compacted-behind-fence` | DWD 的压缩任务成功，围栏在 07:03:01 加上、07:03:49 解除 |
| `dwd-backfill-accounted` | 回刷 `erase-g7-backfill-dwd-m3g-g7-orders-rows-1` 在受理后约 7 分钟发起（06:58:56–07:01:44），"upserted 5722 rows, deleted 27 no longer derived"；事件 `ErasureBackfill` 写明 DWD 有这个客户的 27 行、上游从 06:53:48 起干净且流已读过；DWD 的 `Rows` 证据："no row of the subject in snapshot 18, after backfill …（…; the table held 27 rows of the subject at most）" |
| `flink-state-checked` | 两个作业的 checkpoint、savepoint 里没有早于删除的对象（DWD 处理掉一个旧 savepoint）；不再声明的作业留下的状态清掉了 9 个目录（之前运行的作业，以及这次删掉的低于上游的表的作业） |
| `erasure-overdue-reported` | 物理期限 08:06:35，08:07:04 判为 `SlaBreach`；记下一条超期，列出 ODS 两张表与 DWD 的 `Files`（`Pending`：文件已干净，孤儿文件清理在 10-17 之后才能覆盖压缩前的文件）；`PhysicallyErased` 为 False、`WithinDeadlines` 为 False、没有物理完成时间；有 Warning `ErasureOverdue` |
| `storage-evidence` | 对象存储一项 `Done`：仓库桶从未开版本、没有复制（安装脚本在 06:40:14 以部署身份核对；删除时不重查） |
| `unpublished-on-delete` | 删掉删除请求、表和源之后，读端不再持有任何 grant |

之前的运行发现并修正了这些问题：

- **g1：** harness 里读条件的 Python 片段在 f-string 里写了 `\"`，任何 Python 都解析不了，所以 9 项条件检查都误判为失败。改正后，又把所有 harness 里内嵌的 Python 逐一编译检查了一遍。
- **g2：** 含个人数据的分层表建不出来。Paimon 2.0 不允许 `full-compaction.delta-commits` 与 `changelog-producer=lookup` 同时出现，而写入端自己压缩的表都是 lookup changelog，所以这个选项实际上从未生效过。改为 `compaction.optimization-interval=1 h`：Paimon 的 universal compaction 用它触发全量压缩（`EarlyFullCompaction`）。在 Paimon 2.0 的字节码上核对过，lookup 表的写入端用的正是这个触发器。
- **g3：** DWD 里的 26 行一直删不掉，删除停在 `Accepted`。源库先删订单、再删客户，两张 ODS 表在同一次 checkpoint 提交；DWD 处理订单的删除时，lookup join 已找不到客户，就把删除连同它关联的变更一起丢了。改为：分层表读的每张表都已 `clean` 满 5 分钟、它却仍有主体的行时，删除流程建一个回刷的 `LakeTask`，按上游重算整表，删掉不再产生的主键。
- **g4：** 两处问题。
  - 对象存储一项是 `Done`，但核查记录放在了 `message`，没有 `evidence` 和 `doneAt`。改为与其他副本一样经 `done()` 记下证据。
  - harness 写死了"两份 Flink 状态"。删除流程还会清理不再声明的作业的状态，这次从 80 个旧作业目录里清掉了遗留状态，算作第三份。改为要求每一份都 `Done`。
- **g5：** 23 项通过，`erasure-overdue-reported` 失败：harness 在逻辑完成之后固定等 40 分钟，而这次逻辑删除用了 23 分钟，等到的时候还没到物理期限。这是 harness 的等待窗口，不是 Operator 的问题。改为读删除请求的 `physicalDeadline`，一直等到期限之后 10 分钟。
- **g6：** 23 项全部通过。之后的评审指出：
  - 回刷的触发条件太宽：上游干净 5 分钟就回刷，不看 DWD 的流读到了哪，流只是落后时也会整表重算；上游一张都没登记时立即回刷。改为还要求流已读过每张上游的干净快照，上游都没登记时不回刷；回刷发事件，`Rows` 的证据写出它改了什么。
  - 接收条件 1 在运行里只测了"低于上游"，没测缺声明。harness 加了去掉 `retention`、去掉 `governance` 两步。
  - 超期一项没有核对"不声明物理完成"。harness 加了 `PhysicallyErased`、`WithinDeadlines` 与物理完成时间的检查，也加了回刷一项。
  - LLD 把 `compaction.optimization-interval` 写成"至少每小时全量压缩一次"，其实只在有写入时触发；对象存储一项只在安装时核对。LLD 已改正。

## 限制

- Kafka 事件源与 MongoDB 源的表，删除时列为 `Blocked`：M3 的删除只覆盖 MySQL 与 PostgreSQL 源的 ODS 表和分层表。
- 物理完成最早在压缩一天之后：孤儿文件清理只删一天以前的文件。
- 平台不删源库和用户 Kafka 里的数据：源库只核对，topic 列为外部副本。
- 没有降级（去标识化）路径：读个人数据的表只能声明为个人数据，级别不低于上游。
- Doris 的派生副本（物化视图、内表、审计日志）由 #32 纳入登记；在那之前，每个读端列为外部副本。
- 从删除之前的状态恢复的演练不在这次运行里：Flink 状态以"没有早于删除的对象"为证据。

> 注：本文中的 issue 与 PR 编号（如 #28）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
