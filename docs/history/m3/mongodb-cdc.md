---
title: "M3 MongoDB CDC"
date: 2026-10-15
status: complete
issue: 30
---

# M3 MongoDB CDC

本页记录 Issue #30 的验收：用户声明 MongoDB 数据源后，平台持续托管单集合与数据库范围的变更接入。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "MongoDB 源"，依据 HLD 7.2、7.5、9.1。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。MongoDB 是验收脚本建在集群里的 MongoDB 7.0（`infra/m3/flows/mongo/dev-mongo.sh`），代替用户的库：

- 单成员副本集，数据在 EBS 卷上；
- 必须 TLS，CA 由脚本生成；
- 开启访问控制：key file 加用户，源用户只有 `find`、`changeStream` 等读权限，外加读 `local`，用来查看 oplog 窗口。

**证据的来源版本**：结果来自提交 `80f7989`，私有证据目录 `m3-30/run-m3-80f7989/`。镜像：Operator `cove-operator@sha256:25415843…`，作业 `cove-jobs@sha256:3645d612…`，MongoDB `mongo@sha256:9650758f…`（7.0）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 初始与增量数据在目标表中可核对，新增、更新、删除结果正确 | 通过 | 写入不停的情况下，`orders` 6115 个文档、`products` 283 个与目标表逐文档一致（缺失、多出、不同均为 0）；两张表的完整 changelog 回放无误（`orders`：+I 6367、-U/+U 1245、-D 252，每个前镜像都是表里当时的行） |
| 新增集合或字段按声明范围处理；不支持的变化明确失败或隔离 | 通过 | 新字段 `channel` 成为 `orders` 的新列；MongoDB 源的范围在作业启动时取定：之后出现的 `products` 判为 `AddedAfterStart` 并有 Warning（明确隔离，不自动加入），单集合的源读它，逐文档一致；整数 `_id` 的 `ledger` 判为 `UnsupportedKeyType`；被改名覆盖的 `catalog` 判为 `SourceReplaced`；被 drop 的 `customers` 离开作业并有 `TableGone`，其余表照常 |
| 重启和断线恢复后无静默漏数，源端前提和保留窗口可检查 | 通过 | MongoDB 成员被删后新的 Pod 带着原卷回来、重新成为 primary，TaskManager 丢失，TaskManager 内存升级（从 checkpoint 恢复），作业每次都恢复运行，最终比对无漏数；oplog 窗口记在状态里，短于 24 小时有 `RetentionWindow` 条件与 Warning |

## 运行

`infra/m3/flows/mongo/run-mongo.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. 建库，包括以下集合：
   - `orders` 5000 个文档：ObjectId 键，带嵌套文档和数组；
   - `customers` 200 个：字符串键；
   - `ledger` 20 个：整数键；
   - `catalog` 100 个；
   - `events` 500 个。
2. 启动写入器：每秒约两轮。每轮插入一个订单、更新一个抽样订单；每 5 轮删一个订单，每 7 轮更新一个客户；300 轮以后的更新带上新字段 `channel`。
3. 声明源，范围是名单 `orders`、`customers`、`ledger`、`catalog` 加模式 `prod.*`。要求：
   - `orders`、`customers`、`catalog` 对账后 `Ready`；
   - `ledger` 判为 `UnsupportedKeyType`；
   - 状态里有 oplog 窗口，短于默认 24 小时，所以有 `RetentionWindow` 条件和告警。
4. 新建 `products`（由模式匹配）并先写入 300 个文档：运行中的作业不收它，要求 `products` 判为 `Rejected`（`AddedAfterStart`）并有 Warning。原因见 LLD：Flink CDC 往运行中的作业加集合，failover 后会静默丢掉它的变更（FLINK-40741）。此前的一次运行（`9a653e7`）正是这样丢了 `products` 的全部变更。
5. 单集合的源：另一个 `LakeSource` 只读 `products`。要求 `Ready`；插入、更新、删除后与集合逐文档一致，changelog 回放无误；然后删除这个源，要求它的库删除、token 被拒。
6. 删掉 MongoDB 成员的 pod，要求新的 pod 带着原来的卷回来、重新成为 primary；再删一个 TaskManager；再改 TaskManager 内存。每次都要求作业恢复运行，升级时从 checkpoint 恢复。
7. 用另一个集合 rename 覆盖 `catalog`：要求 `catalog` 判为 `SourceReplaced`。再 drop `customers`：要求它离开作业，并有 `TableGone` 告警。两种情况下 `orders` 都照常 `Ready`。
8. 停止写入后比对：
   - `orders` 与集合逐文档一致：`_id` 集合相同，每个顶层字段的值相同，嵌套值按结构比较；
   - 完整 changelog 回放无误：每个 delete/update-before 都等于表中当时的行，回放结果等于最新快照；
   - `orders` 有新列 `channel`。
9. 按 `Delete` 删除源：作业消失，表删除（404），token 被拒（401）。

## 结果

运行 m3（`80f7989`）的 21 项检查全部 PASS：

| 检查 | 结果 |
| --- | --- |
| `source-ready` | `orders`、`customers`、`catalog` 对账后 `Ready`（186 秒） |
| `ledger-refused` | `ledger` 判为 `UnsupportedKeyType` |
| `oplog-window-reported`、`oplog-window-alerted` | oplog 窗口短于 24 小时：`RetentionWindow` 为 False，Warning `RetentionWindowShort` |
| `products-refused`、`products-refused-alerted` | 作业启动后出现的 `products` 判为 `AddedAfterStart`，Warning `TableRejected` |
| `one-collection-ready`、`rows-products`、`changelog-products`、`one-collection-deleted` | 单集合的源读 `products`：`Ready`，插入、更新、删除后 283 个文档逐文档一致，changelog 回放无误；删除后库 404、token 401 |
| `member-back-primary`、`recovered-from-member-loss` | 成员的新 Pod 重新成为 primary，作业恢复运行 |
| `recovered-from-taskmanager-loss`、`upgraded` | TaskManager 丢失后恢复；内存升级从 checkpoint 恢复 |
| `catalog-replaced` | 改名覆盖的 `catalog` 判为 `SourceReplaced` |
| `customers-isolated`、`customers-isolated-alerted` | drop 的 `customers` 离开作业，Warning `TableGone`（23:04:29），`orders` 照常。这次运行的检查明细列显示的是第一条提到 customers 的 Warning（`products` 的拒绝），判定依据是 `TableGone` 存在；明细之后改为只取 `TableGone` |
| `rows-orders`、`changelog-orders`、`new-field-column` | 停止写入后 `orders` 6115 个文档逐文档一致，changelog 回放无误，有新列 `channel` |
| `mongo-deleted` | 作业消失，表和库删除（404），token 被拒（401） |

之前的运行发现并修正了这些问题：

- m1：开发用 MongoDB 的用户没有建出来（第一次之后 localhost 例外失效），mongosh 不带 `.js` 扩展名的脚本要用 `--file`；
- m2：`ledger` 按设计被拒，源始终是 Degraded，恢复改为看作业重新运行和表 `Ready`；
- m3 第一、二次：开发用 MongoDB 的成员回来时自己的名字还解析不到，留在 REMOVED；同一运行 id 重跑时，验证身份上次留下的库级读授权让目标库不再隔离；
- m3 第三次：作业带着后加的 `products` 重启后，Paimon 的 committer 在恢复后有意让作业失败一次，Flink CDC 的 newly-added-table 流程恰在最后一步之后被打断，`products` 之后的变更全部被静默丢弃（FLINK-40741，上游只修了 MySQL）。本地稳定复现后，改为不往运行中的作业加集合（`AddedAfterStart`）；
- m3 第四次：删除源时，已离开源的 `customers` 的目标表没被删除，库留了下来，同名源再建时被拒；改为把离开源的表的目标记进 `status.retired` 一并删除；
- m3 第五次（`eb8eea8`）21 项全部通过；评审之后，离开选择范围的表、失败的表的目标也一并删除，单集合的源单独验证并检查它的删除，成员的恢复以新 Pod 重新成为 primary 为准，于是又跑了第六次。

## 边界

- 只接受 ObjectId 或字符串 `_id`；字段都是 STRING，不做按字段的类型映射。
- 作业启动后出现的集合不加入它（`AddedAfterStart`），要另声明一个源：Flink CDC 3.5 的 newly-added-table 流程在 failover 后会静默丢掉新集合的变更（FLINK-40741，上游只修了 MySQL）。
- 续读点不在 oplog 中时只能重新声明再全量读；这一情形在单元测试中覆盖，验收里没有真的让 oplog 滚动。
- 只支持副本集；分片集群在这一版不接受。
- 文档在回查前已被删除的更新（`coveUpdateGone`）由单元测试覆盖，写入器只是偶尔触发。

## 复现

```bash
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/flows/mongo/run-mongo.sh <run-id> <evidence-dir>
```

> 注：本文中的 issue 与 PR 编号（如 #30）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
