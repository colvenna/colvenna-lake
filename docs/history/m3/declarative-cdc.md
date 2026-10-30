---
title: "M3 声明式 CDC 数据源接入"
date: 2026-10-15
status: complete
issue: 24
---

# M3 声明式 CDC 数据源接入

本页记录 Issue #24 的验收：用户声明一个 MySQL 或 PostgreSQL 数据源，平台托管它的接入作业和 ODS 表，并在增删改、暂停和故障之后保持收敛。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md)。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源库是 M1 的 RDS MySQL 8.0 与 RDS PostgreSQL 16，每次运行使用自己的数据库。

**证据的来源版本**：结果来自提交 `2f1529604c320511a823d578012cf83cb6033d4e`，以该 SHA 构建镜像、部署 `CoveLakeM3` 栈与 cove-operator。原始日志在私有证据目录 `m3-24/run-r7-2f15296/`，其中：

- `verdicts.tsv`：每项检查的结论；
- `timeline.tsv`：各步骤的时间；
- `status/`：各检查点的资源状态；
- `dumps/`：两端的行集、changelog 与比对结论；
- `events.json`：事件。

早先的运行也保留在证据目录中，每一轮暴露的问题见 [过程中的发现](#过程中的发现)：

- `run-r1-cf6b18d/`：暴露了并行度问题；
- `run-r2-37d83c4/`：27 项全部通过，但写入是在初始快照之后才开始的。之后的运行改为写入贯穿初始快照与新表纳管；
- `run-r3-9b1650f/`：持续写入下对账一直通不过；
- `run-r4-28e6bc1/`：MySQL 部分有 3 项恢复记录检查失败，PostgreSQL 部分因测试数据问题没有开始；
- `run-r5-debcfca/`：PostgreSQL 的新表因测试数据没有授权被拒；
- `run-r6-46c0d1d/`：运行中 M1 #16 的临时容量节点组被删除（M1 收尾清理），作业无处调度，中止。最终运行改在 PoC 剩余的 4 个节点上，作业的 JobManager 与 TaskManager 各请求 0.5 CPU，最多用到 1 CPU。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 新增、修改、暂停和删除声明后，实际作业与表状态收敛并可观察 | **通过** | 声明后各表进入 `Ready`，不合格的表按原因 `Rejected`；修改资源后作业以 last-state 升级；修改并行度时作业保持原值，并给出条件与告警；暂停后作业挂起，恢复后从原位置继续；删除时 `Retain` 保留表、`Delete` 删表并删除 replication slot，两者都吊销 catalog 身份。状态、条件、事件、指标和 `cove` CLI 都能看到这些结果 |
| 可支持的源表与 schema 变化自动反映到目标；不支持的变化明确失败并告警 | **通过** | 运行中新建的表自动纳管，对账后进入 `Ready`；MySQL 加列、加长与 PostgreSQL 加列都反映到目标，并逐列比对一致；`DECIMAL→VARCHAR` 只让那一张表判为 `Failed`，同时发 Warning 事件，同源的其他表继续运行。二进制列与 PostgreSQL 字符主键在准入时拒绝 |
| 作业升级和故障恢复维持 M1 验证过的数据正确性 | **通过** | 持续写入期间经历了新表升级、表剔除升级、资源升级、TaskManager 与 JobManager 丢失、暂停与恢复。最终源端与目标逐行逐列一致，完整 changelog 回放没有重复插入、前镜像错误或版本回退 |

## 运行

`infra/m3/flows/cdc/run-cdc.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

**MySQL**：

1. 建库建表：
   - 3 张合规表：`orders`、`customers`，以及 `typed`（准入接受的每种类型各一列，含 NULL）；
   - 4 张不合规表：`no_key` 缺主键，`no_guard` 缺版本守卫，复合主键的 `tokens` 有 `BINARY(16)` 列，`blobs` 有 `VARBINARY` 列。
2. 启动写入器：每秒约两个事务，每个事务包含新主键插入、版本递增的更新、偶尔的删除，一直写到第 11 步之前。写入在声明之前就开始，贯穿初始快照。
3. 声明源（校验 RDS 证书），等待合规表 `Ready`、其余按原因拒绝，并要求 `SourceVerified`、`TargetIsolated` 为 True。
4. 运行中新建 `invoices` 并持续写入，等待它纳管完成，并确认作业从 checkpoint 恢复。
5. 给 `orders` 加列并加长一列。
6. 再新建 `ledger`，纳管后把它的一列从 `DECIMAL` 改成 `VARCHAR`。等待 `ledger` 判为 `Failed` 并告警，其他表不受影响。然后取消选择 `ledger` 再选回，它必须判为 `TargetNotEmpty`，不再快照进已有数据的目标表。
7. 把 TaskManager 内存改为 4096m，等待作业以新 spec 运行，并从 checkpoint 恢复。
8. 把并行度改为 2，确认作业保持 1 并发出告警，然后改回 1。
9. 依次删除 TaskManager 和 JobManager 的 Pod，各等待作业从 checkpoint 恢复。然后暂停 60 秒再恢复。
10. 停止写入，对 `orders`、`customers`、`invoices`、`typed` 做逐行比对和完整 changelog 回放。
11. 在 Operator 之外删除作业：表判为 `Failed`（`JobStateLost`），不会重建作业。
12. 按 `Retain` 删除源：作业消失，它的 token 被拒（401），目标表保留。

**PostgreSQL**：

1. 建库建表：
   - 合规表：普通表 `accounts`；以 `text` 列分区、有两个叶子分区的 `payments`；`typed`（准入接受的每种类型）；`ledger`；
   - 不合规表：`partial_identity` 不是 `REPLICA IDENTITY FULL`，`unpublished` 不在 publication 中。
2. 启动写入器，声明源（`verify-full` 校验证书），等待合规表 `Ready`、其余按原因拒绝。
3. 运行中新建 `refunds` 并持续写入，等待纳管完成。
4. 给 `accounts` 加列。
5. 把 `ledger` 的一列改成 `text`：它的作业被删，slot 也被删，其他表不受影响。
6. 把 TaskManager 内存改为 4096m，然后删除 `accounts` 的 JobManager，每次都要求从 checkpoint 恢复。然后暂停并恢复。
7. 停止写入，对 `accounts`、`payments`、`refunds`、`typed` 逐行比对并回放 changelog。
8. 按 `Delete` 删除源：作业、replication slot 和目标表都没有了。

**比对方法**：

- **取证身份**：取证读取用一个只读的 catalog 身份 `m3-verifier`，与作业身份分开。
- **行集**：`LakeDump rows` 读取目标表最新快照的全部行，`sources.sh` 读取源表全部行。`compare.py rows` 按主键逐列比较，数值按值比较；源端已没有的列（Paimon 保留删掉的列）不参与比较。
- **changelog**：`LakeDump changelog` 从最早的快照开始，按提交顺序读出每张表的全部变更。`compare.py changelog` 逐条回放：
  - 删除或 update-before 必须恰好是表中当前行；
  - 插入不能落在已存在的主键上；
  - 版本必须递增；
  - 回放的最终状态必须等于最新快照。

  因为写入贯穿初始快照，所以这项检查也覆盖了快照与 binlog 续读的重叠段：重叠处有重复变更，就会表现为重复插入或前镜像错误。

## 结果

2026-10-15 的 `run-r7-2f15296` 有 50 项检查，全部通过：MySQL 31 项，PostgreSQL 19 项。

**准入与纳管**：

| 检查 | 用时 |
| --- | --- |
| MySQL 声明到全部合规表 `Ready`（写入贯穿初始快照） | 245 s |
| MySQL 运行中新建的 `invoices` 纳管并对账 | 223 s |
| `ledger` 的 `DECIMAL→VARCHAR` 变更后判为 `Failed`，其他表照常 | 125 s |
| PostgreSQL 声明到全部合规表 `Ready` | 359 s |
| PostgreSQL 运行中新建的 `refunds` 纳管并对账 | 385 s |
| PostgreSQL `ledger` 改类型后移出作业，slot 删除 | 27 s |

不合规的表都以各自的原因被拒：`NoPrimaryKey`、`VersionGuardMissing`、`UnsupportedBinaryColumn`（`tokens` 与 `blobs`）、`ReplicaIdentityNotFull`、`NotInPublication`。`ledger` 取消选择后再选回，判为 `TargetNotEmpty`，没有再次快照。

**升级与故障**：作业每次重启都记下了恢复自哪个 checkpoint（`chk-N`），包括新表纳管、资源修改、TaskManager 与 JobManager 丢失，以及暂停后的恢复。并行度改为 2 时，作业保持 1，并给出 `ParallelismPinned` 条件与告警。在 Operator 之外删掉 MySQL 作业后，表判为 `JobStateLost`，没有重建作业。

**正确性**：写入器在 MySQL 上运行了 2545 个事务，在 PostgreSQL 上运行了 2259 个。停止写入后，每张表都与源端逐行逐列一致，完整 changelog 回放没有问题：

| 表 | 源端行数 = 目标行数 | changelog |
| --- | --- | --- |
| MySQL `orders` | 7285 | +I 9835，-D 2550 |
| MySQL `customers` | 200 | +I 2571，-D 2371 |
| MySQL `invoices` | 4780 | +I 6833，-D 2053 |
| MySQL `typed` | 50 | +I 50 |
| PostgreSQL `accounts` | 4081 | +I 6119，-D 2038 |
| PostgreSQL `payments`（两个叶子分区） | 3761 | +I 5731，-D 1970 |
| PostgreSQL `refunds` | 2495 | +I 3856，-D 1361 |
| PostgreSQL `typed` | 50 | +I 50 |

Paimon CDC 把更新写成 `-D` 加 `+I`，所以 changelog 里没有 `-U`/`+U`。回放检查了每条删除都带着表中当时的那一行，插入不会落在已有主键上，版本递增，回放结果等于最新快照。

**删除**：MySQL 源按 `Retain` 删除，作业消失、token 被拒（401）、目标表保留；PostgreSQL 源按 `Delete` 删除，作业、replication slot 与目标表都没有了。

## 过程中的发现

- **二进制列无法写入**：Paimon 2.0.0 把 MySQL 的 `BINARY(n)` 也映射为 `VARBINARY(n)`。base64 模式下存进去的是 base64 文本，长度超出列宽，作业失败：`Length of type VARBINARY(16) NOT NULL is 16, but casting result has a length of 24`。默认模式下，非 UTF-8 字节在解码成字符串时就已损坏（`TypeUtils.castFromCdcValueString`）。所以 #8 结项时记下的 base64 方向修不好这个问题，准入改为拒绝所有二进制列，并给出原因。#113 会在 Flink CDC 3.7 上复测。
- **PostgreSQL 的字符主键会变类型**：Debezium 报出的 `varchar` 不带长度，Paimon 把这类列演进成 `STRING`。主键列不能改类型，cove-catalog 回 501（`Cannot update primary key`），作业从此失败。Paimon 的 PostgreSQL 类型映射只有"全部转字符串"一种选项，所以主键含 `varchar`/`char` 列的表在准入时拒绝。
- **并行度不能原地改**：run-r1 中，并行度从 1 改为 2 之后，作业从 checkpoint 恢复失败：`There is no operator for the state ...`。原因是 Paimon CDC action 的算子没有固定 uid，并行度一变，算子链和算子 id 都变了。新作业在初始化时失败，Flink 随即把它判为终态并清掉 HA 数据，Flink Operator 只能一直等待。
  - 恢复：按 LLD "运维" 的步骤，从 `chk-7` 重新部署。之后又持续写入 2353 个事务，`orders` 7106 行与源端逐列一致，changelog 中 9675 条插入与 2569 条删除回放无误，`customers` 也一致。
  - 处理：作业部署后 Operator 锁定并行度，并对卡住的升级给出 `UpgradeStuck` 条件。
- **持续写入时对账永远追不上**（r3）：原先先读源端、再读目标表，而目标表总比源端晚一两个 checkpoint，快照期间持续写入时永远对不上。改为两次读目标表，第二次等目标表提交了足够晚的快照，见 LLD "对账"。
- **恢复记录漏记**（r4）：Flink Operator 的 last-state 升级沿用原来的 job id，TaskManager 故障转移连提交时间都不变。Operator 原先每个 job id 只读一次恢复来源，所以升级与故障转移后的恢复都没有记下。改为作业运行时每轮都读。
- **测试数据的两个错误**：r4 中，PostgreSQL 的 `typed` 测试数据有一条不抬高版本的 UPDATE，被源端版本守卫拒绝；r5 中，新表脚本没有给 CDC 用户授 SELECT。后一种情况下，PostgreSQL 的 `information_schema.columns` 只列出用户有权限的列，准入看到一张没有列的表，报的是 `VersionColumnMissing`。现在这种表判为 `NotReadable`，说明缺的是什么。
- **取证工具读旧快照时用了旧 schema**：`FileStoreTable.copy()` 带 `scan.snapshot-id` 时，会时间旅行到那个快照的 schema。这是取证工具自身的问题，Paimon 没有问题，改用 `copyWithoutTimeTravel` 即可。下游作业的流读不受影响：本地复现确认，加列前写的文件会按新 schema 补 null。

## 之后的修正

- **MySQL 新表在 failover 后丢数**（#113）：本页的 r7 用的是 Flink CDC 3.5.0。新表加入作业要靠 last-state 重启后的 newly-added-table 流程，而 3.5.0 在这个流程刚结束时如果发生 failover，会静默丢掉新表此后的全部变更（FLINK-40741）。#113 在本机用 Paimon 的 MySQL action 复现：3.5.0 下 10 次丢了 4 次。r7 的新表检查没有在这个时间点注入 failover，所以没有遇到。#113 起，MySQL 的 connector 换成 3.7.0-1.20（同样的复现 10 次 0 次丢失）。PostgreSQL 的结论见 [Flink CDC 3.7 评估](flink-cdc-3.7.md)。

## 复现

```bash
bash infra/m3/scripts/deploy-stack.sh
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/flows/sources.sh users
bash infra/m3/flows/cdc/run-cdc.sh <run-id> <evidence-dir>
```

声明与查看用 `cove`：

```bash
cove apply -f source.yaml
cove get sources -n cove-m3
cove describe source shop -n cove-m3
cove wait source shop -n cove-m3 --for table/orders=Ready   # 0：达到；1：不可能达到；2：超时
```

> 注：本文中的 issue 与 PR 编号（如 #24）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
