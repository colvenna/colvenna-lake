---
title: "M3 Flink CDC 3.7 兼容性评估"
date: 2026-10-16
status: complete
issue: 113
---

# M3 Flink CDC 3.7 兼容性评估

本页记录 Issue #113 的评估：Flink CDC 3.7.0 对 Cove Lake 现有 CDC 接入链路（#24 的声明式 MySQL / PostgreSQL 源、#30 的 MongoDB 源）的兼容性与收益，以及它的 YAML Pipeline 能否复用于声明式接入。两条路径分别评估：

- **CDC action 依赖升级**：仍用 Paimon 2.0.0 的 CDC action（cove-jobs 的 `MySqlCdcJob` 等），只把 Flink CDC 的 SQL connector 换成 3.7；
- **YAML Pipeline 接入**：用 Flink CDC 3.7 的 MySQL pipeline source 加 Paimon pipeline sink，代替 Paimon 的 action。

对照基线是 Paimon 2.0.0、Flink 1.20.4、Flink CDC 3.5.0 与已验证的 Paimon CDC action + cove-catalog REST 路径。M1、M2 的证据仍归属它们各自的来源版本；这里没运行的验证标为 NOT_RUN，不据此宣称 M1/M2 新版本验收通过。

所有 AWS 资源都在 profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。本机测试的源库是本机 Docker 里的 MySQL 8.0 与 PostgreSQL 16（只监听回环地址，每次运行新起一个）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 3.5.0 与 3.7.0 的能力与兼容性对照，CDC action 与 YAML Pipeline 分开 | 完成 | 发布标签、提交与 jar 的来源见"版本与来源"；两条路径的检查各有一张表，判定为 PASS、FAIL 或 NOT_RUN，只能观测的一项写明"观测"和边界 |
| 真实 S3 上的快照、增删改、Schema 演进与不兼容变更拒绝；最终行集与完整 changelog 都对照源端 | CDC action：PASS；pipeline：部分 NOT_RUN | CDC action：EKS 上的整套验收 r37a（#24 的 harness）50 项全部通过，MySQL 作业用 3.7.0-1.20。快照、持续增删改、新表接入、支持的结构变化（加列、放宽列长度）与不支持变更的隔离、升级、故障与暂停之后，ODS 表与源库逐行一致，完整 changelog 回放无误。pipeline：快照、增删改与加列只核对了最终行集（y10），完整 changelog 没有与源端比对，不兼容变更的拒绝也没有运行，都是 NOT_RUN（按调研，`lenient` 会放过类型变更） |
| checkpoint 恢复、新增源表恢复、增量快照 backfill、升级恢复；Snapshot Split 元数据释放 | 恢复：PASS（升级恢复只在本机）；长期观测：NOT_RUN | EKS（r37a）：TaskManager、JobManager 丢失与暂停之后从 checkpoint 恢复；新表 `invoices` 在写入中加入，last-state 恢复时增量快照回填，行与 changelog 都对。本机：新增源表 failover 后 3.5.0 丢数 4/10，3.7.0-1.20 为 0/10（FLINK-40741）；PostgreSQL 叶子分区 0/12；3.5.0 的 savepoint 由 3.7.0-1.20 恢复后行与 changelog 一致，EKS 上从 3.5.0 的 checkpoint 恢复 NOT_RUN。元数据释放的开关与新增源表互斥，用不上；JM 堆只连续观测了约 5 分钟，长期运行 NOT_RUN |
| 二进制列与 VARBINARY 主键分别复测 | 完成：维持拒绝 | 主键比较：VARBINARY 主键用 ASCII 字节时两版都对。写入端解码：含高位字节时两版都失败，原因是 Paimon 写入端的 UTF-8 解码。按代码，这个解码对每个二进制列都一样，不作主键的二进制列也会坏，但没有单独运行（NOT_RUN）。#24 的拒绝（`UnsupportedBinaryColumn`）在 r37a 的 `mysql-admission` 里再次通过（`blobs`、`tokens` 两张表）；拒绝由 Operator 在准入时判定，与 connector 版本无关 |
| 拟采用路径的提交权威、REST bearer、按表数据凭证与续期、跨表拒绝 | 提交与 bearer：PASS；续期与跨表拒绝：NOT_RUN（3.7 的 CDC action） | 采用的是 Paimon CDC action。r37a 里它经同一 cove-catalog 提交、以 bearer 认证，`LakeSource` 删除后它的 token 被拒（401）。续期由 Paimon 的 `RESTTokenFileIO` 完成，M2 的 #20 验证的是普通 Flink 流式写入，不是 CDC action，也不在 3.7 上；跨表拒绝在 cove-catalog 服务端判定（M2 的 #21），没有在 3.7 的 CDC action 上单独运行。pipeline 测了 bearer、数据凭证获取与跨库拒绝，续期 NOT_RUN |
| YAML Pipeline 的至少一次语义、主键表与 append 表、自动建表与加列不绕过准入 | 治理准入：FAIL，不采用；其余 NOT_RUN | 自动建表与加列只受 cove-catalog 授权约束，绕过 Operator 的准入、分类与读端发布（y10 实测）。至少一次：按 sink 的文档，failover 后会重放，changelog 会有重复，没有实测。append 表：只看了文档（不支持），没有运行。Schema Expansion（3.7 新增的 `existing-table.schema-expansion.mode`，默认 `DISABLED`）：没有运行；它给已有的表加列、放宽类型，同样只受授权约束 |
| 结论记入能力矩阵并关联 #24：选择、路径、剩余阻断项、回退条件 | 完成 | 见"选择"；能力矩阵、HLD 与 #24 的文档已更新 |

**证据的来源版本**：

- EKS 运行 r37a 来自提交 `6933b91`，私有证据 `m3-113/run-r37a-6933b91/`。镜像：Operator `cove-operator@sha256:a56a8970…`，作业 `cove-jobs@sha256:93e1e1a1…`（mysql，Flink CDC 3.7.0-1.20）与 `cove-jobs@sha256:7f8016b5…`（postgres，3.5.0）。JobManager 堆的采样用的是 `f5a5e14` 里修过的 `sample-jm.sh`：原版在运行中退出，修好后在工作区里重新启动。
- pipeline 运行 y10 来自 `c50f5da`，私有证据 `m3-113/pipeline-y10/`。
- 本机复现的证据在 `m3-113/repro-*/`。

## 版本与来源

| 项 | 3.5.0（基线） | 3.7.0（评估） |
| --- | --- | --- |
| 发布标签 | `release-3.5.0`，`d15c5de8` | `release-3.7.0`，`045e8da4`（[发布说明](https://flink.apache.org/2026/10/10/apache-flink-cdc-3.7.0-release-announcement/)） |
| SQL connector | `flink-sql-connector-{mysql,postgres,mongodb}-cdc:3.5.0` | `…:3.7.0-1.20`（按 Flink 版本发布，另有 `-2.2`）；MySQL jar 的 SHA-256 `d1c91f29…`，与 Maven Central 发布的 SHA-1 核对过 |
| pipeline | — | `flink-cdc-composer`、`flink-cdc-cli`、`flink-cdc-pipeline-connector-{mysql,paimon}:3.7.0-1.20`；Paimon pipeline connector 自带 Paimon 2.0.0 |
| 运行组合 | Paimon 2.0.0、Flink 1.20.4、Java 11 字节码 | 同左（3.7.0-1.20 的 jar 按 Flink 1.20.3 构建） |
| Debezium | 1.9.8.Final | 1.9.8.Final |

调研笔记（每条带一手来源：标签、提交、JIRA、Maven Central 的 POM 与 jar）在私有证据 `m3-113/research.md`。

## 新增源表的故障恢复（FLINK-40741）

Flink CDC 的 newly-added-table 流程有一个缺陷：作业把新表加入之后、流程最后一步刚完成时如果发生 failover，新表此后的变更会被静默丢弃（[FLINK-40741](https://issues.apache.org/jira/browse/FLINK-40741)）。#30 在 EKS 上遇到过这个缺陷（MongoDB），#24 的 MySQL 新表接入也走这条路：声明加表后，Operator 以 last-state 重启作业，Flink CDC 在恢复时快照新表。

**复现。** `infra/m3/cdc37/repro` 是一个独立的 Maven 工程（需要 Docker，不在 CI 里跑），由 `infra/m3/cdc37/run-repro.sh` 运行：

1. 起一个全新的 MySQL 8.0（GTID、row binlog）；
2. 用 Paimon 2.0.0 的 `MySqlSyncDatabaseAction`（cove-jobs 的 `MySqlCdcJob` 继承的那个 action）只同步 `orders`，写入、savepoint、停下；
3. 新建 `products` 并写入 10 行，作业带着 `orders|products` 从 savepoint 恢复，Flink CDC 快照 `products`；
4. `products` 的第一条 binlog 变更到达时，在解析算子里抛一次异常，作业 failover；
5. 继续写入，要求 `products` 的 30 行、`orders` 的 30 行都到达 Paimon。

丢不丢数取决于 failover 落在流程的哪一步，一次运行说明不了问题，所以每个版本、每种 checkpoint 间隔各跑 5 次。每次都核对两件事：`products` 的行逐值等于源库；它的完整 changelog 回放无误，即每个前镜像都等于表里当时的行，没有重复插入，终态等于源库。

| Flink CDC | checkpoint 间隔 | 丢数的次数 | 丢了什么 |
| --- | --- | --- | --- |
| 3.5.0 | 1 秒 | 3 / 5 | `products` 在 failover 之后的 20 条变更全部丢失，changelog 回放的终态也与源库不符；`orders` 不受影响 |
| 3.5.0 | 10 秒 | 1 / 5 | 同上 |
| 3.7.0-1.20 | 1 秒 | 0 / 5 | — |
| 3.7.0-1.20 | 10 秒 | 0 / 5 | — |

3.5.0 在 10 次里丢了 4 次，丢的时候是新表此后的全部变更，没有任何报错。3.7.0-1.20 的 10 次都没丢。之前一轮只核对行、不核对 changelog，结果相同：3.5.0 是 4/10，3.7.0-1.20 是 0/10。

修复是 [`2ae8f874`](https://github.com/apache/flink-cdc/commit/2ae8f874)，只改了 MySQL connector 的 `MySqlSourceEnumerator` 与 `MySqlSourceReader`，`flink-cdc-base` 没动，所以 PostgreSQL 与 MongoDB 仍是旧逻辑（见下文）。私有证据：`m3-113/repro-7/failover/`，之前一轮在 `m3-113/repro-3/`。

**PostgreSQL。** #24 的 PostgreSQL 源每张表一个作业，新表就是新作业，从快照开始，不走运行中作业的 newly-added 流程。唯一的例外是分区表后来多出的叶子分区：作业的表清单变了，Operator 以 last-state 重启。`infra/m3/cdc37/repro-pg` 复现这个流程：

1. 作业读 `orders_p1`；
2. 作业还在跑时，建叶子分区 `orders_p2`、加进 publication、写入 10 行；
3. savepoint，作业带着两个叶子恢复；
4. `orders_p2` 的第一条变更到达时注入一次 failover；
5. 继续写入，要求每一行都到达 Paimon。

两种配置都测：

- cove-jobs 现在的配置，`scan.newly-added-table.enabled` 未设、默认关闭，新叶子只靠复制流读到；
- 打开它，恢复时快照新叶子。

| Flink CDC | newly-added-table | 丢数的次数 |
| --- | --- | --- |
| 3.5.0 | 关闭（cove-jobs 现状） | 0 / 3 |
| 3.7.0-1.20 | 关闭 | 0 / 3 |
| 3.5.0 | 打开 | 0 / 3 |
| 3.7.0-1.20 | 打开 | 0 / 3 |

12 次都没有丢数。PostgreSQL 共用的 `flink-cdc-base` 在 3.7 没有改过，MongoDB 在同一个框架上复现过同类丢数（#30），所以这只能说明在这个流程上没有观测到，不能证明框架没有缺陷。PostgreSQL 保持 3.5.0，分区表新增叶子的风险记为剩余项。

复现时还发现，`paimon-flink-1.20-2.0.0.jar` 自己也带一份 `flink-cdc-base` 的 `JdbcConnectionPools`，签名与 PostgreSQL connector 里的不同，classpath 上谁在前就用谁的。作业镜像的 `/opt/flink/lib` 按文件名加载，connector 排在前面，所以作业没受影响；测试工程把 connector 放在第一个依赖。私有证据：`m3-113/repro-6/`。

## CDC action 依赖升级

cove-jobs 继续用 Paimon 2.0.0 的 CDC action，把 MySQL 的 SQL connector 换成 `flink-sql-connector-mysql-cdc:3.7.0-1.20`（3.7 按 Flink 版本发布，没有不带后缀的 `3.7.0`）；PostgreSQL 与 MongoDB 留在 3.5.0。

| 检查 | 结果 | 依据 |
| --- | --- | --- |
| 二进制兼容 | PASS | Paimon 2.0.0 按 3.5.0 编译。对照 3.7.0 的 jar 检查了 `paimon-flink-1.20` 的 445 个字节码成员引用，只有一处断裂：Paimon 自己的 pipeline 类 `PaimonMetadataApplier` 调用的 `SchemaChangeEventVisitor.visit` 签名在 3.7 被删了，而 CDC action 不用这个类（调研笔记）。cove-jobs 只直接用了 MongoDB 的 `MongoDBSource` 和 `StartupOptions` |
| 编译与单元测试 | PASS | 两种组合都编译通过、48 个单元测试都通过：三个 connector 都换成 3.7.0-1.20（评估时），以及最终的组合，即只有 MySQL 用 3.7.0-1.20、另两个仍为 3.5.0（本 PR，CI 的 `java-verify`） |
| 新增源表的故障恢复 | PASS（3.5.0：FAIL） | 见上文，3.5.0 丢数 4/10，3.7.0-1.20 为 0/10 |
| 升级恢复 | PASS（本机） | `UpgradeTest`：3.5.0 的作业写入后以 savepoint 停下；停着的时候源库又 update 5 行、delete 3 行、insert 10 行；3.7.0-1.20 的作业从这个 savepoint 恢复，再 insert 10 行、update 1 行。最终 47 行与源库逐值一致；完整 changelog 回放无误：每个前镜像都等于表里当时的行，没有重复插入，回放的终态等于源库。私有证据：`m3-113/repro-7/upgrade/` |
| EKS 上的整套验收 | PASS | 运行 r37a（`6933b91`，#24 的 harness `run-cdc.sh`）50 项全部通过。MySQL 源 31 项：准入（含二进制列的两张表以 `UnsupportedBinaryColumn` 拒绝）；新表 `invoices` 加入后 last-state 恢复并回填；支持的结构变化（`orders` 加列、放宽列长度）；`ledger` 的不支持变更被隔离并告警；取消选择后再选回被拒；改声明后升级恢复；并行度；TaskManager 与 JobManager 丢失；暂停与恢复；作业被删后不重建；删除。`orders`、`customers`、`invoices`、`typed` 四张表与源库逐行一致（`orders` 7458 行），完整 changelog 回放无误（`orders` 的 +I 10244、-D 2786，0 个问题）。MySQL 的 `typed` 只留下了判定行：证据里 `dumps/typed.*` 被 PostgreSQL 的同名表覆盖了。PostgreSQL 源 19 项（connector 仍为 3.5.0）也都通过。运行中的 MySQL 作业 Pod 里是 `flink-sql-connector-mysql-cdc-3.7.0-1.20.jar`，SHA-256 与上文一致。私有证据：`m3-113/run-r37a-6933b91/` |
| 快照分片元数据释放 | 观测（有边界） | 3.7 新增 `scan.incremental.snapshot.metadata.release.enabled`（默认关闭）。它与 `scan.newly-added-table.enabled` 同时打开时作业启动失败，而 #24 的新表接入依赖后者；Paimon 2.0 的 action 也不透传它，所以 cove-jobs 用不上。EKS 运行期间，每分钟记一次 MySQL 源作业 JobManager 的堆用量（`sample-jm.sh`）：05:54 到 06:07 共 10 次（其中 2 次 JobManager 刚起来，还没有指标），堆用量在 114–310 MiB 之间随 GC 起伏，上限 928 MiB。采样脚本第一次启动时作业还没起来，它就退出了，修好后从 05:54 重新采样；其间 JobManager 因故障注入、暂停与恢复换过两次 Pod，最长的一段连续观测约 5 分钟。表只有几千行、MySQL 作业一共运行约 32 分钟，看不出大规模、长时间运行时分片元数据的增长，这一项的结论只到此为止 |
| Flink 1.20.4 | PASS（本地） | 3.7.0-1.20 的 jar 按 Flink 1.20.3 构建；上面的本地运行都用 1.20.4 |

## YAML Pipeline 接入

`infra/m3/cdc37/pipeline` 用 Flink CDC 3.7.0-1.20 的 MySQL pipeline source 与 Paimon pipeline sink，经 port-forward 连 EKS 上的 cove-catalog，由 `infra/m3/cdc37/run-pipeline.sh` 运行：

- **身份。** pipeline 用一个自己的 principal，只有一个库（`pipe_<run>`）的 `MANAGE`，另一个库（`pipe_other_<run>`）没有任何授权。两个库都由管理员预先建好。
- **凭证。** token 只经环境变量交给测试，日志在落盘前抹掉 token。
- **数据凭证。** 测试 JVM 没有自己的 AWS 凭证：没有 profile、密钥和配置文件，也关掉了实例元数据。所以它写 S3 只能用 cove-catalog 按表签发的数据凭证。

运行 y10（`c50f5da`）的结果：

| 检查 | 结果 |
| --- | --- |
| 初始快照 | PASS：20 行全部写入，表由 pipeline 自动创建（`createdBy`、`owner` 都是它的 principal） |
| 持续变更与加列 | PASS：insert、update、delete 之后 28 行与源库逐行一致；源库加列后，表多了列 `c`，新行的值正确 |
| REST bearer 与数据凭证 | PASS（获取）：各算子共取得数据凭证 9 次，S3 的读写都经由它。凭证 3 小时有效，测试只运行了 37 秒，续期没有观测到：NOT_RUN |
| 跨库访问拒绝 | PASS：路由到没有授权的库时，sink 应用建表事件就被拒（`DatabaseNoPermissionException`，cove-catalog 返回 Forbidden），作业失败。管理员列出那个库的表：一张也没有（`"tables":[]`），所以什么也没写进去 |
| 自动建表、加列与治理准入 | 不经过准入（判定为 FAIL）：pipeline 只要有库的 `MANAGE`，就能在库里建任何表、改任何表的结构。Operator 的准入、分类、隔离区都不参与，能挡住它的只有 cove-catalog 的授权。这样建出的表没有声明，Operator 不会给读端发表级 `READ`；但读端若持有库级或 catalog 级的读授权，就能读到这些表，而且没有分类。这正是 issue 要求避免的扩大读权限，所以不采用 |

途中发现的兼容性问题（都只影响 pipeline，Paimon CDC action 不受影响）：

- **Hadoop 依赖。** Paimon 2.0 的 `RESTCatalog` 要从 `CatalogContext` 取 Hadoop 配置。classpath 上没有 Hadoop 时，sink 的 committer 打不开 catalog（`Hadoop configuration is not available for this CatalogContext`），所以要另外带上 Hadoop（这里用 `paimon-hadoop-uber`）。cove-jobs 的镜像本来就有 Hadoop。
- **类加载顺序。** composer 会把 connector 的 jar 加进作业。按默认的 child-first 加载时，JobMaster 里的 Paimon 类与构建作业时用的不是同一份（`ClassCastException`），要设 `classloader.resolve-order: parent-first`。
- **S3 依赖。** S3 的 FileIO 不在 Paimon pipeline connector 的 jar 里，要另带 `paimon-s3`。

**语义边界。** Paimon pipeline sink 的文档写明是至少一次，靠主键表去重保证最终结果。按文档，failover 后重放的变更会再写一遍：最终行集不变，但 changelog（`changelog-producer=input`）里会多出重复的记录。读 changelog 做累加的下游会多算，按变更计数的对账也会对不上。cove-lake 的 ODS 需要完整、不重复的 changelog（DWD、DWS 的回刷与 LakeTask 都以它为前提），这正是 Paimon CDC action 加 exactly-once 提交所保证的。这里没有在 pipeline 上注入 failover 去实测重复，记为 NOT_RUN。

另外，按调研：pipeline 的 `schema.change.behavior` 默认是 `lenient`，而代码会把类型变更原样放过去，与文档的描述不一致；append 表在文档里写的是不支持；3.7 新增的 `existing-table.schema-expansion.mode`（默认 `DISABLED`）可以给已有的表加列、放宽类型，同样只受 cove-catalog 的授权约束，这里没有运行。私有证据：`m3-113/pipeline-y10/`（之前的运行 y1–y9 是排查上面这些问题的过程）、调研笔记 `m3-113/research.md`。证据里没有 token，也没有 AWS 密钥。

## 二进制列

`BinaryKeyTest` 建一张 `VARBINARY(16)` 主键、`VARBINARY(32)` 值列的表，快照按 8 行一片切分（`scan.incremental.snapshot.chunk.size`），再做 insert、update、delete，最后按字节比较源库与 Paimon。两个问题分开测：

- **键与值都是 ASCII 字节**（有效的 UTF-8）：只测 Flink CDC 按二进制主键切分快照、按主键关联变更；
- **含 0x80 以上的字节**：再加上 Paimon 写入端对二进制值的解码。

| Flink CDC | ASCII 字节 | 含高位字节 |
| --- | --- | --- |
| 3.5.0 | PASS：55 行逐字节一致，没有缺失、多出或不同 | FAIL：作业失败，`Length of type VARBINARY(16) NOT NULL is 16, but casting result has a length of 46` |
| 3.7.0-1.20 | PASS：同上 | FAIL：同上 |

- **主键比较。** 3.7 修了 MySQL 二进制分片键按对象标识比较的问题（`b8edfcfd`、`93a40138`），但这组数据在 3.5.0 上也没出错，所以这里只能说"两版都对"，看不出修复的效果。
- **写入端解码。** 两版的失败相同：Paimon 2.0.0 的 CDC 写入端用 `String.getBytes(UTF_8)` 把 CDC 值转成字节（`TypeUtils.castFromCdcValueString`），高位字节先被替换成 3 字节的替换符，16 字节变成 46 字节。这是 Paimon 的问题，换 Flink CDC 的版本解决不了。

结论：#24 的准入规则不变，有二进制列（MySQL 的 `binary`、`varbinary`、blob、`bit`；PostgreSQL 的 `bytea`）的表仍以 `UnsupportedBinaryColumn` 拒绝并发 Warning。等 Paimon 的 CDC 写入端能原样写二进制值之后再放开。私有证据：`m3-113/repro-5/binary/`。

## 边界

- **changelog 的核对方式。** EKS 上的 changelog 检查（`compare.py changelog`）回放湖自己的完整 changelog：
  - 每个 delete、update-before 都要等于表里当时的行；
  - insert 不能落在已有的主键上；
  - 源端版本必须递增；
  - 回放的终态必须等于源库。

  它不逐条比对源库的 binlog，所以源端两次更新之间丢掉一次中间版本时查不出来；本机的复现同样如此。
- **新增源表的 failover 只在本机注入。** EKS 上的整套验收里有新表接入，但没有在 newly-added 流程结束的那一刻注入 failover：时间点太窄，在集群上打不准。FLINK-40741 的结论来自本机的 20 次复现。
- **升级恢复只在本机验证。** 3.5.0 的 savepoint 由 3.7.0-1.20 恢复是本机、文件系统 warehouse 上的结果。EKS 上的验收从空状态开始，不经过跨版本恢复。#24 的源按 last-state 升级时，作业要从 3.5.0 的 checkpoint 恢复，这一步与本机的升级测试同理，没有在 EKS 上单独演练（开发阶段不要求跨版本兼容）。
- **数据凭证的续期。**
  - CDC action 路径的续期由 Paimon 的 `RESTTokenFileIO` 完成，不在 Flink CDC connector 里。M2 的 #20 验证过普通的 Flink 流式写入越过首个凭证的有效期之后仍持续提交（[M2 验收](../m2/catalog-on-eks.md#续期)），但那不是 CDC action，也不在 3.7 上；r37a 不到 1 小时，越不过 3 小时的有效期。这里记为 NOT_RUN。
  - pipeline 路径的续期没有观测到（NOT_RUN）。
- **跨表访问拒绝。** CDC action 的身份只得到它目标库的授权，由 Operator 按声明发放；没有授权的身份被拒、WRITE 不能做 DDL 等，都在 M2（#21）的[授权验证](../m2/authorization-on-eks.md)里覆盖；授权在 cove-catalog 服务端判定，与 connector 版本无关，但没有在 3.7 的 CDC action 上单独运行（NOT_RUN）。pipeline 路径在这里测了跨库拒绝。

## 选择

- **CDC action 的依赖：MySQL 升级，PostgreSQL 与 MongoDB 暂缓。** cove-jobs 的 MySQL SQL connector 换成 3.7.0-1.20（本 PR），因为它修复了新增源表 failover 后丢数这个已复现的正确性问题，Paimon 2.0.0 的 action 与它兼容，3.5.0 的 savepoint 也能直接恢复。PostgreSQL 与 MongoDB 用的 `flink-cdc-base` 在 3.7 没有变化，升级得不到修复，保持 3.5.0。
- **YAML Pipeline：不采用。** 声明式接入继续用 Paimon CDC action。理由：
  - pipeline 只保证至少一次，cove-lake 的分层需要完整、不重复的 changelog；
  - 自动建表、加列只受 cove-catalog 授权约束，不经过准入、分类与读端发布；
  - REST 路径要额外带 Hadoop 与 S3 依赖，还要改类加载顺序。

  pipeline 留作非敏感、只要最终行集的场景的候选，不进入 1.0。
- **二进制列：维持拒绝。** 这是 Paimon 写入端的限制，换 Flink CDC 版本解决不了。
- **剩余项：**
  - PostgreSQL 分区表新增叶子分区后的 failover（本机 12 次未复现，base 框架未修）；
  - MongoDB 作业启动后出现的集合继续以 `AddedAfterStart` 拒绝（#30）；
  - 快照分片元数据释放无法在现有路径上启用；
  - 3.7 的 CDC action 上没有单独运行的验证：数据凭证越过有效期的续期、跨表访问拒绝、从 3.5.0 的 checkpoint 在 EKS 上恢复、长期运行时分片元数据的增长。
- **回退条件：** 3.7.0-1.20 的 MySQL 作业在验收或运行中出现 3.5.0 没有的错误时，把 `flink-cdc-mysql.version` 和镜像里的 jar 改回 3.5.0；同时让 MySQL 源后加的表也走 `AddedAfterStart`，因为退回 3.5.0 会重新暴露 FLINK-40741。

## 复现

```bash
# 本机：FLINK-40741（MySQL）、PostgreSQL 叶子分区、二进制列、升级恢复
CHECKPOINT_MS=1000 bash infra/m3/cdc37/run-repro.sh <evidence-dir> failover 3.5.0 3.7.0-1.20
NEWLY=false bash infra/m3/cdc37/run-repro.sh <evidence-dir> pg-leaf
NEWLY=true bash infra/m3/cdc37/run-repro.sh <evidence-dir> pg-leaf
bash infra/m3/cdc37/run-repro.sh <evidence-dir> binary
bash infra/m3/cdc37/run-repro.sh <evidence-dir> upgrade 3.5.0 3.7.0-1.20
# 本机经 port-forward 连 EKS 上的 cove-catalog：YAML Pipeline
bash infra/m3/cdc37/run-pipeline.sh <run-id> <evidence-dir>
# EKS：CDC action 路径的整套验收（#24 的 harness），在 MySQL connector 为 3.7.0-1.20 的镜像上
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/cdc37/sample-jm.sh cove-m3 cdc-<run-id>-cdc- <evidence-dir>/jm-heap.txt 150 &
bash infra/m3/flows/cdc/run-cdc.sh <run-id> <evidence-dir>
```

> 注：本文中的 issue 与 PR 编号（如 #113）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
