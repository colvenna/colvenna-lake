---
title: "M2 从 JDBC catalog 切换到 cove-catalog"
date: 2026-10-13
status: complete
issue: 22
---

# M2 从 JDBC catalog 切换到 cove-catalog

本页记录 Issue #22 的切换方案和在真实 AWS 环境中的结果。M1 创建的表从 M1 的共享 JDBC catalog 切到 cove-catalog，M1 的 MySQL CDC 作业随表一起切换；Flink、Spark 和 Doris 经 REST 和按表凭证使用这些表；最后按原路回退。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群上，只使用合成数据。服务端设计见 [cove-catalog LLD](https://github.com/colvenna/colvenna-catalog/blob/main/docs/design/colvenna-catalog-lld.md)，部署见 [EKS 部署与引擎验收](catalog-on-eks.md)。

**证据的来源版本**：EKS 上的结果来自提交 `230e8db1cc93200817043b2d804edb1fe681fa7a`，镜像以该 SHA 构建并安装。之后的提交只改了本页。原始日志在私有证据目录 `m2-22/run-230e8db/`。同目录下还有两次早先的运行：

- `run-92bc828-failed-cdc-start/`：一次做到一半失败的切换，见 [失败的切换与回退](#失败的切换与回退)；
- `run-e9274f7-interrupted/`：回退途中因主机重启中断。它之前的各阶段都通过了，评审之后的修改见 [过程中的发现](#过程中的发现)。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 切换前后表、快照、tag 与查询结果一致，无已确认数据丢失 | **通过** | 挂起 M1 后，5 张 CDC 表的 JDBC 视图与 MySQL 一致；7 张表切换前的 JDBC 视图与切换后的 REST 视图逐表一致：快照、schema、tag、分支、行数、校验和、数值列之和；回退时 REST 视图与恢复后的 JDBC 视图同样一致。JDBC 下打的 tag 经 REST 被 Flink、Spark、Doris 读到。两个 CDC 作业交接时重放已写过的 binlog，表仍与 MySQL 一致。一次中途失败的切换回退后，失败期间写入 MySQL、REST 作业没能写入的一行由 M1 作业补上 |
| 所有写入方切换后使用同一提交权威，混用目录被拒绝或明确阻断 | **通过** | 每张切换的表只有 cove-catalog 一个提交权威：5 张 CDC 表唯一的写入方 M1 CDC 作业挂起，由切换后的作业从 M1 已提交过的 binlog 位点经 REST 续写；JDBC 读取被拒（`Table ... does not exist`），7 张表"缺表就建"的写入都被拒（`Schema in filesystem exists`）；绕过 catalog 的直接提交使表被围栏，REST 写入以 `fenced` 被拒，reconcile 后恢复。其他写入方的表留在 JDBC catalog，见 [切换哪些表](#切换哪些表) |
| 目标版本下的 REST 认证和临时凭证路径全部通过；回退条件与数据边界明确 | **通过** | Flink 与 Spark 各 11 步、Doris 31 项检查全部通过，都以 bearer token 认证、以按表数据凭证读写文件；Doris 对 append 夹具的 6 个 split 全部走原生读取。回退条件与数据边界见 [回退](#回退) |

## 切换哪些表

M1 的 CDC 作业 `cove-mysql-cdc` 用 Paimon 的 MySQL 整库同步 action，把 MySQL `source_db` 的每张表写到 `ods`。切换必须带上它写的全部表，否则：

- 切换后的作业在 cove-catalog 里找不到某张表，会在 cove-catalog 里新建一张；
- 新表的路径不会和原表冲突（cove-catalog 发现目录已有文件时改用带后缀的路径），但数据会分成两份。

因此切换的表是 `source_db` 现有的 5 张表：`issue8_accounts`、`issue8_orders`、`issue8_probe_records`、`issue8_products`、`issue8_promotions`。运行开始时会向 MySQL 查询 `source_db` 的表清单，与这组表不一致就停止。

另有两张本次运行自建的夹具表，都在 JDBC catalog 里建立，各有三次提交，并在第二次提交上打了 tag `before_switch`：

- `m2_switch_<run>`：append 分区表；
- `m2_switch_<run>_pk`：主键表。

夹具用于：

- 检查 tag 能否跨切换使用；
- 让 Flink 和 Spark 写入 M1 建立的 append 表和主键表（追加、upsert、行级 update 与 delete），而不改动 M1 的证据表。

**切换范围**：`ods` 中其他 M1 表不在本次切换范围内：

- `issue7_*` 和 #14 数据表是 M1 证据；
- `issue9_orders` 由 PostgreSQL CDC 作业写入；
- `kafka_events*` 由 Kafka 接入作业写入（已暂停）。

每张表在任一时刻只有一个提交权威：已切换的表只经 cove-catalog 提交，其余表的写入方仍全部留在 JDBC catalog。

其他写入方的切换不在本页验证范围内。表这一侧的步骤可以沿用：停写，导出并登记，阻断 JDBC。但每个写入方都要有经 REST 提交的版本，以及自己的续读方式；例如 PostgreSQL CDC 作业是 M1 自己的作业，要单独改造和验证。

## 切换过程

`infra/m2/scripts/switch-catalog.sh to-rest` 依次执行：

1. **停写**：把 `cove-mysql-cdc` 置为 `suspended`。它的 upgradeMode 是 `last-state`，checkpoint 和 binlog 位点都保留，回退时从这里继续。挂起之前先记下 MySQL 当前的 binlog 位点，这个位点就是切换后作业续读的起点。然后等 M1 作业完成两次 checkpoint：
   - 第一次在记下位点至少一分钟后才开始。M1 作业通常几秒内就能读到 binlog 的新变更，所以位点之前的变更都在这次 checkpoint 里（下面对照 MySQL 的核对兜底）；
   - Paimon 在收到 checkpoint 完成的通知后才提交，这个提交可能晚于 checkpoint 完成。再等下一次 checkpoint 完成，前一次的提交就已经完成，挂起不会截断它。
2. **JDBC 视图**：以 M1 自己的 Flink 身份和 catalog 凭证，导出每张表的状态（`CatalogStateDump`）：
   - 最新与最早快照、快照数、最新提交类型、schema id、tag、分支；
   - 全表扫描得到的行数、数值列之和，以及与扫描顺序无关的行校验和（每行 64 位 FNV-1a 之和）。

   每张 CDC 表的行数与 id 之和必须等于 MySQL 中的值。否则 M1 没有提交到位点，切换后的作业会漏掉缺少的变更。这时还什么都没改，脚本恢复 M1 作业后退出。
3. **登记**：经管理员接口把每张表按原路径登记到 cove-catalog。cove-catalog 读取文件中的最新快照，作为已发布指针。每张表的一致性报告必须干净。
4. **阻断 JDBC**：先保存、再删除这些表在 M1 JDBC catalog（`paimon_tables`）中的行。表文件不动。
5. **REST 视图**：以只读身份 `m2-migration` 经 REST 和按表凭证再导出一次，必须与第 2 步逐表完全一致。
6. **确认阻断**：JDBC catalog 必须拒绝这些表，见 [混用阻断](#混用阻断)。

M1 catalog 数据库的主账号凭证只经一个短期 Secret 交给 psql Pod，退出时删除；不出现在命令行参数或清单中。

## 写入方随表切换

切换后的 CDC 作业 `RestMySqlCdcJob` 由 `infra/m2/flows/run-rest-cdc.sh` 部署。它沿用 M1 作业的这几样：

- 同一个上游 action：`MySqlSyncDatabaseAction`，combined 模式；
- 同一份表配置：`bucket=1`、`changelog-producer=input`、`write-only=true`、`sequence.field=version_no`；
- 同一个版本守卫 `MonotonicVersionGuard`；
- 同一个 MySQL CDC 用户。

不同之处：

| 方面 | M1 作业 | 切换后的作业 |
| --- | --- | --- |
| 提交方式 | 经 JDBC catalog 和 JDBC 锁提交 | 经 cove-catalog 提交，表文件只用按表凭证访问 |
| 身份与权限 | 不经 catalog 授权 | catalog 身份 `m2-cdc`，在 `ods` 上有 MANAGE（同步 action 遇到源端新表时要建表、遇到 schema 变化时要改表） |
| 运行位置 | 命名空间 `cove-lake-ods` | 引擎命名空间 `cove-m2`（Pod Security restricted），Pod 自身的 AWS 身份只能读写 checkpoint 前缀 |
| MySQL server-id | `5401-5410` | `5501-5510` |
| 状态 | `last-state`，checkpoint 在 M1 前缀 | 同样是 `last-state`，开启 Kubernetes HA，checkpoint 与 HA 数据在 `flink-state/m2/m2-flink/rest-cdc/` |
| 起点 | — | 不做全量快照，从 M1 已提交过的 binlog 位点续读（`scan.startup.mode=specific-offset`） |

**续读而不是重做快照**：

- **为什么不重做快照**：全量快照只插入 MySQL 现有的行。如果两个作业都不在运行时 MySQL 删了行，快照不会删除 Paimon 中对应的行。
- **为什么用已提交的位点**：从 M1 已提交过的位点续读，可以覆盖 M1 在它之后读到、但挂起前没来得及提交的变更。
- **为什么不用时间点**：Flink CDC 3.5 按时间点启动时，查找 binlog 的那条连接不走 TLS，而源库要求 TLS（`REQUIRE SSL`），实测启动即被拒（`Access denied`）。
- **重放为什么安全**：主键加 `sequence.field=version_no` 使重放幂等，同一版本再写一次，结果不变。

**验证**：作业启动后，每 30 秒经 REST 导出一次，直到每张 CDC 表的行数与 id 之和等于 MySQL 中的值。这时的查询结果还必须与切换前的 JDBC 视图一致，包括行数、校验和、数值列之和、schema 与 tag。

**凭证**：

- `m2-cdc` 的 bearer token 有效期 7 天，作业启动时读取一次。轮换就是带新 token 重启作业；`last-state` 保证续读位点不丢。
- 表的数据凭证由客户端按需续期，见 #20。
- 作业停止（`run-rest-cdc.sh stop`）时：
  - 确认 FlinkDeployment 与 Pod 都已消失；
  - 删除 `m2-cdc` 的授权，撤销它的全部 token；
  - 再查一次，确认它没有授权，也没有未撤销的 token。

## 混用阻断

切换后，一张表只有 cove-catalog 一个提交权威。其他写法的结果：

| 写法 | 结果 | 机制 |
| --- | --- | --- |
| JDBC 读取或写入 | 拒绝：`Table ... does not exist` | 表的行已从 JDBC catalog 删除 |
| JDBC 下"缺表就建"的写入方（M1 的 CDC action 就是） | 拒绝：`Schema in filesystem exists, creation is not allowed` | Paimon 不在已有 schema 的目录上建新表；对每张切换的表逐一实测 |
| 绕过 catalog、直接向表目录提交（M1 的身份仍可写 `ods.db`） | 已发布快照不受影响，表被围栏 | 见下文 |
| M1 的 CDC 作业 | 保持 `suspended`，回退时才恢复 | — |

直接向表目录提交时：

- cove-catalog 只发布自己提交的快照，REST 读取方看不到这次外来提交；
- 下一次经 REST 的提交发现快照文件已被占用，于是围栏该表，后续提交一律拒绝；
- 一致性报告列出这次外来提交；
- 管理员执行 reconcile 后，外来快照被发布，围栏解除。

实测在 append 夹具上逐步核对：

1. 以 M1 的身份直接提交一行；
2. 一致性报告列出"超出已发布指针的快照文件"；
3. 经 REST 的写入失败，且失败原因必须是 fenced；
4. reconcile 报告之前的状态为 `FENCED`；
5. 之后报告干净，REST 写入恢复。

每项拒绝都核对日志中的具体原因，因权限、调度或超时而失败的不算通过。

**残余风险**：存储层并未阻止这种写入。M1 的 IRSA 角色仍可写 `warehouse/ods.db/*`，因为未切换的 M1 表和回退都需要这项权限。要在存储层也阻止，须把这些表移出 M1 角色的写前缀，这超出本 issue 的范围。

## 回退

`switch-catalog.sh to-jdbc` 的前提是这些表的 REST 写入方都已停止（`run-rest-cdc.sh stop` 会确认作业与 Pod 都已消失）。切换做到一半失败时，回退同样可以执行：它只处理仍登记在 cove-catalog 中的表，JDBC 行按表名重建，已存在的行保留不动。步骤：

1. 仍登记的表的一致性报告必须干净，再经 REST 导出状态；
2. 从 cove-catalog 注销这些表，不删文件；
3. 恢复 JDBC 行；
4. 经 JDBC 导出状态，必须与第 1 步一致；
5. 恢复 M1 的 CDC 作业。它会重放挂起以来的 binlog。之后每 30 秒经 JDBC 导出一次，直到每张 CDC 表的行数与 id 之和等于 MySQL 中的值；其余的表必须不变。

**回退条件**：

- 所有 REST 写入方已停止；
- MySQL binlog 仍保留着 M1 作业挂起后的全部变更（PoC 设置为 24 小时）。否则 M1 作业无法从原位点续读，要先清空状态，从全量快照重来。

**数据边界**：

- **REST 期间的快照**：都写在表目录里，回退后 JDBC 读取方照样能看到，查询结果不会退回切换前。
- **重放**：两个 CDC 作业交接时都会重放一段已写过的 binlog。
  - 切换后的作业从 M1 已提交过的位点续读；
  - 回退后 M1 作业从它挂起时的 checkpoint 续读，覆盖整个 REST 期间。

  主键与 `version_no` 使这些重放不改变表的查询结果，但 `changelog-producer=input` 会把重放的变更再写进 changelog。流式读取 changelog 的下游会再收到这一段变更，所以必须按主键幂等处理（upsert），或者在切换后从快照重新开始消费。
- **只在 cove-catalog 中的信息**：授权、审计、token 和 catalog 侧注释不会带回 JDBC catalog。
- **tag 与分支**：存放在表目录里，两边都能看到。

## 结果

运行于 2026-10-14 05:00–05:34（UTC）。

| 时间 | 阶段 |
| --- | --- |
| 05:00:49 | MySQL `source_db` 的表清单与切换范围一致，共 5 张 |
| 05:01:07 | 在 JDBC catalog 建夹具 `m2_switch_s0500` 与 `m2_switch_s0500_pk`：各 30 行、3 个快照，tag `before_switch` 在快照 2 |
| 05:04:06 | binlog 位点 `mysql-bin-changelog.004525:157`：M1 作业 05:03:29 开始的 checkpoint 34246 覆盖它，之后 checkpoint 34247 也已完成 |
| 05:04:20 | M1 的 CDC 作业挂起 |
| 05:05:34 | JDBC 视图中 5 张 CDC 表的行数与 id 之和等于 MySQL 中的值 |
| 05:05:38–05:06:10 | 7 张表按原路径登记，一致性报告全部干净 |
| 05:06:26–05:08:32 | 删除 JDBC 行（`DELETE 7`），核对两个视图，确认 JDBC 阻断 |
| 05:08:33–05:14:00 | 切换后的 CDC 作业启动并核对 |
| 05:14:00–05:16:21 | 围栏演示 |
| 05:16:21–05:24:56 | Flink、Spark、Doris 依次经 REST 读写 |
| 05:24:56–05:32:00 | 回退，M1 作业恢复并追平 |
| 05:32:00–05:33:46 | 回退后经 JDBC 写入一行 |

### 切换

| 检查 | 结果 |
| --- | --- |
| 挂起后的 JDBC 视图与 MySQL | 5 张 CDC 表一致，如 `issue8_accounts` 为 5 行、id 之和 3583868404 |
| JDBC 视图与 REST 视图 | `SAME 7 tables` |
| JDBC 读取 | 拒绝：`Table ods.issue8_accounts does not exist` |
| JDBC 下缺表就建 | 7 张表都拒绝：`Schema in filesystem exists, creation is not allowed.` |

### 切换后的 CDC 作业

| 检查 | 结果 |
| --- | --- |
| 起点 | `mysql-bin-changelog.004525:157`，不做快照 |
| 追平 | 第一次轮询就与 MySQL 一致；查询结果与切换前的 JDBC 视图相比，7 张表都不变 |
| MySQL 向 `issue8_accounts` 插入一行（id 1791954722） | 第一次轮询就经 REST 可见：6 行，与 MySQL 一致 |
| 停止 | FlinkDeployment 与 Pod 都已消失；作业日志中没有异常；`m2-cdc` 没有授权，token 都已撤销 |

### 围栏

在 append 夹具 `m2_switch_s0500` 上：

| 步骤 | 结果 |
| --- | --- |
| 以 M1 的身份直接提交 | 写出快照 4 |
| 一致性报告 | `main: snapshot file 4 exists beyond the published pointer 3` |
| 经 REST 写入 | 拒绝：`fenced` |
| reconcile | 之前的状态为 `FENCED`，`main` 的指针 3 → 4 |
| 之后 | 一致性报告干净，经 REST 写入成功 |

### 引擎

每个引擎开始前先经 REST 导出一次状态，引擎查到的行数与 id 之和必须与之相同。

- **Flink**（`SwitchedTablesFlow`，11 步全部通过）：
  - 读 7 张表；
  - 读两张夹具在 JDBC 下打的 tag；
  - 向 append 夹具提交一次；
  - 在主键夹具中更新一行切换前写入的行，并新增一行。
- **Spark**（`SparkSwitchedFlow`，11 步全部通过）：
  - 与 Flink 相同的 7 张表和两个 tag；
  - 向 append 夹具提交一次；
  - 在主键夹具中按行更新、删除各一行切换前写入的行。
- **Doris**（31 项检查全部通过）：
  - 7 张表的行数与 id 之和，原生读取与 JNI 读取各查一次，共 28 项；
  - 两张夹具按 tag `before_switch` 都读到 20 行；
  - 对 append 夹具的 `EXPLAIN` 显示 6 个 split 全部走 Paimon 原生读取（`paimonNativeReadSplits=6/6`）。主键表在没有 compaction 时要合并多层文件，Doris 可能改用 JNI 读取，这里没有核对。

### 回退的结果

| 检查 | 结果 |
| --- | --- |
| 回退前 | 7 张表的一致性报告干净 |
| REST 视图与恢复后的 JDBC 视图 | `SAME 7 tables` |
| M1 作业恢复后 | 第一次轮询就追平：5 张 CDC 表与 MySQL 一致，`issue8_accounts` 仍为 6 行；两张夹具不变 |
| 回退后 MySQL 再插入一行（id 1791955924） | 第一次轮询就经 JDBC 可见：7 行，与 MySQL 一致 |

M1 作业从挂起时的 checkpoint 续读，所以 REST 期间插入的那一行它也重放了一次；能读到之后插入的行，说明重放已经过了那一行。表仍是 7 行，与 MySQL 一致，重放没有产生重复。

### 失败的切换与回退

更早的一次运行（`92bc828`）让切换后的 CDC 作业从挂起前五分钟的时间点启动。作业进入 RUNNING，但读 binlog 时被源库拒绝（`Access denied`，原因见 [写入方随表切换](#写入方随表切换)）。MySQL 新插入的一行迟迟没有出现在 REST 中，运行被中止，切换后的作业被停下，7 张表留在 cove-catalog。

之后以 `e9274f7` 的 `switch-catalog.sh to-jdbc` 回退：

- 7 张表的一致性报告干净，注销后恢复 JDBC 行（`INSERT 0 7`），JDBC 视图与 REST 视图一致；
- M1 作业恢复后重放了挂起以来的 binlog：5 张 CDC 表的行数与 id 之和都等于 MySQL 中的值，包括失败期间插入的那一行；两张夹具不变。

## 过程中的发现

- **时间点启动不可用**：Flink CDC 3.5 按时间点启动时，查找 binlog 的连接不走 TLS，源库拒绝（`Access denied`）。切换后的作业因此改为从 M1 已提交过的 binlog 位点续读。
- **checkpoint 完成不等于已提交**：Paimon 收到 checkpoint 完成的通知后才提交，挂起作业可能截断这次提交。挂起前因此再多等一次 checkpoint，并让挂起后的 JDBC 视图对照 MySQL。`e9274f7` 的运行还只等一次 checkpoint，结果虽然一致，但没有这项核对。
- **固定等待改为轮询**：两个 CDC 作业追平后才核对，改为每 30 秒对照 MySQL 一次，直到一致，不再固定等两分钟。

## 复现

```bash
# 前提：cove-catalog 已按 catalog-on-eks.md 安装；当前树已提交。
bash infra/m2/scripts/run-switch.sh <run-id> <evidence-dir>
```

各阶段也可单独运行：`switch-catalog.sh`、`run-rest-cdc.sh`、`mysql-source.sh`、`run-catalog-tool.sh`。

> 注：本文中的 issue 与 PR 编号（如 #22）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
