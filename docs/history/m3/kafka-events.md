---
title: "M3 声明式 Kafka 事件源"
date: 2026-10-15
status: complete
issue: 25
---

# M3 声明式 Kafka 事件源

本页记录 Issue #25 的验收：用户声明一个 Kafka 事件源，以及事件的字段、事件时间和校验规则；平台维持入湖作业，事件进入正确的目标表，无效与迟到的事件进入拒绝表，断线、凭证失效、升级和重建都有明确的状态。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的 "Kafka 事件源"，依据 HLD 8、9.1、11。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），只用合成数据。Kafka 用开发 profile 的单节点 Strimzi Kafka（`deploy/helm/cove-dev-kafka`），代替用户的集群；源以自己的 SCRAM 用户经 TLS 连接，并校验集群 CA。

**证据的来源版本**：结果来自提交 `3077584`，私有证据目录 `m3-25/run-k3-3077584/`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 新增或修改声明后可检查连接与消费状态，事件持续进入正确的目标表 | **通过** | 声明后 63 s 进入 `Running`，状态列出每个 topic 的分区、末端位点和积压；声明第二个 topic 后作业从表中的位点重建（`JobDeleted`）；加可选字段 `campaign` 后加列（`FieldAdded`）；最终 1000 条有效事件逐字段进入事件表 |
| 断线、凭证失效、无效事件及迟到事件有明确状态与可观测结果 | **通过** | 口令失效 `AuthenticationFailed`、集群不可达 `Unreachable`、用户不能读 `NotAuthorized`、topic 不存在 `TopicMissing`，各有 Warning，修复后恢复；每种拒绝原因（含 `INVALID_SIZE` 与 `LATE`）都进入拒绝表，只有原因与位置；状态里的计数与两张表一致 |
| 重建或升级作业后满足已确认的消费与去重语义 | **通过** | 升级以 last-state 从 checkpoint 恢复；改 topic 与在 Operator 之外删除作业后，新作业都从两张表的位点继续，group 位点被重置到开头也不受影响；三个分区从 0 到末端的 1081 个位点，每个恰好出现在两张表之一 |

## 运行

`infra/m3/flows/kafka/run-kafka.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. 安装开发 Kafka，建两个 topic（2 个分区和 1 个分区），以及以下 SCRAM 用户：
   - 生产者；
   - 源用户：只能读这两个 topic，只能用 `cove.` 开头的 group；
   - 只能 Describe topic、不能读的用户。
2. 声明源，要求 `Running`、`SourceReady`、`SourceVerified`、`TargetReady`。
3. 两个暂停的声明，要求探测拒绝并告警：
   - 用户能 Describe topic 但不能读：`NotAuthorized`；
   - topic 不存在：`TopicMissing`。
4. 第一批 300 条有效事件，其中一部分带未声明的字段；声明可能遇到的每种拒绝原因各一条，包括空记录（`INVALID_SIZE`）。`UNKNOWN_FIELD` 要求声明 `unknownFields: reject`，由单元测试覆盖。一分钟后再发一条落后 10 分钟的事件，要求判为 `LATE`。
5. 改掉源用户的口令：要求 `AuthenticationFailed` 并告警；把新口令写进源的 Secret 后恢复。
6. 去掉命名空间的 Kafka 客户端标签，使集群不可达：要求 `Unreachable` 并告警；加回后恢复。
7. 改 TaskManager 内存：要求以 last-state 升级，从 checkpoint 恢复。
8. 声明第二个 topic：要求作业被删除后重建（`JobDeleted`），从表中的位点继续，不从恢复出来的位点继续。
9. 在 Operator 之外删掉作业，同时把 group 的位点重置到开头：要求作业被重建（`JobRecreated`），从表中记录的位点之后继续，而不是 group 的位点。
10. 声明一个新的可选字段 `campaign`：要求加列（`FieldAdded`）。
11. 暂停，暂停期间发一批（积压上升），再恢复。
12. 每次修改后都再发一批，并等待积压归零。

最后比对：

- 事件表恰好是发出的有效事件，逐字段一致（加列之前的 `campaign` 在 `extra_json` 里）；
- 拒绝表的原因与预期一致，且只有原因与位置；
- 两个 topic 每个分区从 0 到末端位点的每个位点，恰好出现在两张表之一：没有漏读，也没有重读；
- 状态里的计数与此一致。

最后按 `Delete` 删除源：两张表都删除（404），token 被拒（401）。

## 结果

2026-10-15 的 `run-k3-3077584` 有 32 项检查，全部通过。

**连接与拒绝**：

| 检查 | 用时 |
| --- | --- |
| 声明到 `Running`、`SourceReady`、`SourceVerified`、`TargetReady` | 63 s |
| 只能 Describe 不能读的用户：`NotAuthorized` 并告警 | 14 s |
| 不存在的 topic：`TopicMissing` 并告警 | 14 s |
| 改掉口令：`AuthenticationFailed` 并告警；换上新口令后恢复 | 27 s，75 s |
| 去掉命名空间标签：`Unreachable` 并告警；加回后恢复 | 39 s，15 s |

**作业**：

- 改 TaskManager 内存，作业以 last-state 从 checkpoint 恢复；
- 声明第二个 topic：作业被删除后从表中的位点重建，51 s 后两个 topic 都在读；
- 在 Operator 之外删除作业，同时把 group 位点重置到开头：作业被重建（`JobRecreated`），从表中的位点继续；
- 加字段 `campaign`；暂停期间积压上升，恢复后 125 s 读完。

**每条记录恰好一次**：三个分区（`m3-ev-k3` 的 0、1，`m3-ev-k3-b` 的 0）共 1081 个位点：

- 1000 条有效事件，逐字段与发出的一致；
- 81 条拒绝：10 种原因各 8 条（每批一条），外加 1 条 `LATE`；
- 没有缺口，没有重复；状态里的计数与此一致。

**删除**：作业消失，两张表都删除（404），token 被拒（401）。

## 过程中的发现

- **group 位点不能当起点，k3 里真的遇到了。**
  - 作业被删时，第二个 topic 的 110 条记录已经提交了 group 位点（积压显示为 0），但两张表还没提交那个 checkpoint；
  - 重建的作业从表推出的起点（日志 `KAFKA_EVENTS_START`）里没有这个分区，于是从头读，这 110 条只写进表一次；
  - 如果按 group 位点继续，这 110 条就丢了。
- **watermark 必须按分区算。** 评审发现，原实现在解析之后才分配 watermark，所有分区共用一个。积压或追赶时，读得快的分区会让其他分区的有效事件被判 `LATE`。现在在 source 中解析，watermark 按分区推进。
- **两张表的提交不是原子的。** 重建时取两张表都已提交的那一次为起点，并丢弃领先那张表在此之后已写的记录。
- **前两轮的失败。**
  - `k1`：CRD 中 `{1,1024}` 超过 Kubernetes 正则（RE2）的重复上限 1000，安装被拒；修好后，Operator 镜像里有两个 jar 的权限是 0600，非 root 用户读不了，启动失败，进程却仍显示 Running；
  - `k2`：正确性全部通过，另有 4 项失败：
    - Kafka 对没有 ACL 覆盖的 topic 报 NotAuthorized，看不出它不存在；
    - topic 身份注解写在了 Pod 模板上；
    - `status.py` 缺 `sum`；
    - 一开始就声明为暂停的源显示 Suspending。

  均已修复。

## 复现

```bash
bash infra/m3/scripts/build-images.sh
bash infra/m3/scripts/install-operator.sh
bash infra/m3/flows/kafka/run-kafka.sh <run-id> <evidence-dir>
```

> 注：本文中的 issue 与 PR 编号（如 #25）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
