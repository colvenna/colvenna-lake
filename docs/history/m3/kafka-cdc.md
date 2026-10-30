---
title: "M3 Kafka 承载的 CDC"
date: 2026-10-15
status: complete
issue: 31
---

# M3 Kafka 承载的 CDC

本页记录 Issue #31 的验收：MySQL 源的变更改从用户的 Kafka 读入 Paimon 主键表。选定的格式是 Debezium JSON，带 schema。范围包括：

- 重复、乱序、回放和格式不兼容的事件，处理结果明确，可以观测；
- 从直连切换到 Kafka 时，目标表与下游 consumer 都不出现未定义的重放或缺口。

设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "Kafka 承载的 CDC"，依据 HLD 7.2、7.6、9.1。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。采集器用 Debezium Server 3.6.3.Final，代替用户的 connector；Kafka 用开发 profile 的单节点 Strimzi Kafka（`deploy/helm/cove-dev-kafka`），代替用户的集群。每个角色各用自己的 SCRAM 用户经 TLS 连接，并校验集群 CA，包括采集器、入湖作业和模拟的错误生产者。

**证据的来源版本**：结果来自提交 `d2648e2`，私有证据目录 `m3-31/run-c5-d2648e2/`（运行 c5，2026-10-15）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 选定格式的 insert、update、delete 与源数据最终状态一致，前后镜像满足下游需求 | 通过 | ODS orders 7956 行、segments 200 行与源表逐行一致，DWD 7956 行与源端 join 一致；三张表的完整 changelog 回放无误（ODS orders 跨越切换：+I 9008、-U/+U 2233、-D 1052，每个前镜像都是表里当时的行） |
| 重复、乱序、回放和格式不兼容事件的处理结果明确且可观测 | 通过 | 重发 192 条（其中 8 条删除被吸收），`stale` 增加 184，表不变；3 条格式不兼容的记录计入 `unparsable` 并发 Warning；采集器丢失位点后重新快照，Operator 发现 17 行未送达的删除并补写（`DeletesLost` → `DeletesRepaired` → `TableReconciled`） |
| 从直连切换到 Kafka 路径时，目标表与下游消费者不出现未定义的重放或缺口 | 通过 | 事件顺序为 `JobDeleted` → `JobCreated` → `ChangelogSwitched`，两张目标表先压缩再切换，起点在目标表最后一次写入之前；被删的 Kafka 作业重建时起点同样取自目标表；DWD 的 changelog 没有重放和缺口 |

## 运行

`infra/m3/flows/kafka/run-kafka-cdc.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. 安装开发 Kafka，建两张表的 topic，以及采集器、入湖作业、错误生产者各一个 SCRAM 用户。入湖用户只能读这两个 topic（以及读它们的配置，用来确认没有压缩），只能用 `cove.` 开头的 group。
2. 建 MySQL 夹具：orders 5000 行、segments 200 行，带源端版本契约；启动持续写入（插入、版本上升的更新、删除）。
3. 以 binlog 直连声明源，等两张表 `Ready`；再声明 DWD 分层表（orders 按 segments lookup join），作为下游 consumer。
4. 启动 Debezium Server，先快照，再读 binlog。它的位点和 schema 历史保存在 pod 里，口令经 Secret 以环境变量传入，不写进配置。
5. 写入不停，给源加上 `spec.transport.kafka`。要求：
   - 事件顺序为先删除直连作业（`JobDeleted`），再创建 Kafka 作业（`JobCreated`），最后切换 changelog（`ChangelogSwitched`）；
   - 作业日志显示两张目标表都先压缩、再切换，起点取自目标表最后一次写入；
   - `TransportReady` 为 True。
6. 重放：把作业已经读过的最近 200 条记录原样再发一遍，tombstone 除外。要求非删除的记录全部计入 `stale`，表不变。
7. 格式不兼容：发 3 条记录，分别是非 JSON、不带 schema 的 change、`op=t`。要求 `unparsable` 恰好加 3，并发 Warning `UnparsableEvents`。
8. 采集器丢失位点：先停掉采集器，在源库删掉 5 行，再让它不带 offset 重新启动。它把每一行重新快照一遍，这 5 行的删除（以及写入器同期的删除）不在任何记录里。要求：
   - 快照记录被全部吸收，Operator 发 `SnapshotAgain`；
   - Operator 与源库对账，发现至少 5 行没送到的删除（Warning `DeletesLost`），挂起作业写入删除（`DeletesRepaired`），作业恢复后对账通过（`TableReconciled`）；
   - 最后的逐行与 changelog 对账（第 10 步）中，ODS 与 DWD 都没有这些行。
9. 在 Operator 之外删掉 Kafka 作业：要求重建（`JobRecreated`），起点同样取自目标表，而不是 consumer group 的位点。
10. 停止写入，等积压归零。

最后比对：

- ODS orders、segments 逐行等于源表，DWD 逐行等于源端 join；
- 三张表各自的完整 changelog（ODS 跨越切换）回放无误：每个 delete/update-before 都带表里当时的行，每行的版本只升不降，回放结果等于最新快照；
- 读过两遍的变更没有一条进入表或下游。

最后按 `Delete` 删除 DWD 和源：作业消失，表删除（404），token 被拒（401）。

## 结果

运行 c5 的 24 项检查全部 PASS：

| 检查 | 结果 |
| --- | --- |
| `direct-ready`、`dwd-running`、`debezium-streaming` | 直连源两张表 `Ready`，DWD 运行，采集器写入 topic |
| `kafka-running`、`switch-ordered`、`switch-starts-before-last-write`、`kafka-caught-up` | 切换按顺序完成，作业日志里两张表都在最后一次写入之前起步，积压追平 |
| `replay-dropped-as-stale` | 重发 192 条，`stale` +184，8 条删除被吸收 |
| `unparsable-counted`、`unparsable-alerted` | `unparsable` +3，Warning `UnparsableEvents` |
| `resnapshot-absorbed`、`snapshot-again-noticed` | 重新快照的记录被吸收，Operator 发 `SnapshotAgain` |
| `deletes-lost-repaired`、`resnapshot-reconciled` | 17 行未送达的删除被发现并补写，作业恢复后对账通过 |
| `kafka-recreated`、`kafka-recreated-from-tables` | 被删的作业重建，起点取自目标表 |
| `kafka-drained` | 停止写入后 15 秒积压归零 |
| `rows-ods-orders`、`rows-ods-segments`、`rows-dwd` | 逐行一致，缺失、多出、不同均为 0 |
| `changelog-ods-orders`、`changelog-ods-segments`、`changelog-dwd` | changelog 回放无误，`problems` 与 `replayMismatches` 均为 0 |
| `kafka-cdc-deleted` | 作业消失，表 404，token 401 |

之前的运行发现并修正了这些问题：

- c1：Kafka 作业镜像缺少 Avro 依赖；Debezium 的 DECIMAL 默认以 base64 表示；catalog 客户端的表缓存推迟了 `ChangelogSwitched`；
- c2：重放时把客户端的告警行当成了记录；
- c3：采集器重新快照时，两次快照之间的删除不在任何记录里，于是加了对账补删；
- c4：重新对账的标记没有随状态保存，跨分区读取让快照判定误报，改为按分区判断。

## 边界

- 只支持 MySQL 源和带 schema 的 Debezium JSON。PostgreSQL 经 Kafka、不能直连的源库（Oracle、SQL Server、Db2）以及 Canal、云厂商格式，都不在 M3 范围内。
- 源库仍需可直连：准入和对账读数据库。
- 起点依赖两个条件：overlap 长于上一个写入者停止时的延迟，也长于采集器的延迟；快照保留时间长于两倍 overlap。无法满足时作业以 `POSITION_UNKNOWN` 失败；保留期删掉了起点之后的记录时，计入 `unverified` 并告警。
- 删除行的识别只覆盖整数和字符串主键，而且只覆盖 overlap 内、以及本次运行里最近 20 万行中被删除的行。回放更早的历史时，这之外被删除的行会短暂重新出现，直到它的删除也被回放。
- topic 必须是 `cleanup.policy=delete`，事件必须带 schema 和源端列类型（`column.propagate.source.type`）。

## 复现

```bash
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/flows/kafka/run-kafka-cdc.sh <run-id> <evidence-dir>
```

> 注：本文中的 issue 与 PR 编号（如 #31）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
