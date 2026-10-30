---
title: "M4 批量数据质量校验与发布门禁"
date: 2026-10-17
status: complete
issue: 38
---

# M4 批量数据质量校验与发布门禁

本页记录 Issue #38 的验收：用户对历史分区和当前数据执行批量质量校验（`LakeQuality` 规则、`check` 类型的 `LakeTask`），依据结果决定是否放行下游发布（门禁），未通过的规则可以由有权限的人豁免（`LakeWaiver`）。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的"批量质量校验"一节，依据 HLD 9.2、12.6、12.7。执行路径沿用 M1 #17 的决定：Pod 内的 Flink batch，以有终态的 Kubernetes Job 提交（[批处理边界](../m1/batch-engine-boundary.md)），不依赖第三方质量框架。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。事件来自开发 profile 的单节点 Strimzi Kafka（代替用户的集群），看板在平台的 Doris 上（#32）。

**证据的来源版本**：结果来自提交 `957be53`，私有证据目录 `m4-38/run-q5-957be53/`。镜像：Operator `cove-operator@sha256:9eb2224f…`，作业 `cove-jobs@sha256:e55b6e8c…`（kafka）与 `cove-jobs@sha256:7d3da730…`（sql），CLI `cove 957be53`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 每次校验可关联表、快照或分区、规则版本和结果 | 通过 | 结果表每次校验每条规则一行，写着表、快照、`asOf`、分区、规则版本与定义、量值、阈值、通过与否、失败行数；昨天和今天分区的两次校验各写 4 行，任务的 `status.check` 固定了同样的快照、`asOf` 和规则版本 |
| 失败数据与失败规则可定位，发布阻断或豁免行为可审计 | 通过 | 失败的快照打了 tag，两条失败规则的定位语句按 tag 读出的正是发送的 5 条缺 `user_id` 事件和 3 个重复的 `event_id`；门禁关闭时读端 403、看板被拒，豁免以调用者身份批准后才放行；`decisions` 依次记下 `NoCheck`、`Passed`、`Passed`、`Failed`、`Failed`、`Waived`、`NoCheck`、`Passed`、`Expired`、`Removed`，豁免那一行写明豁免名、批准人和理由 |
| 重跑得到稳定结果，已发布数据不因未完成校验被误标为通过 | 通过 | 同样的快照、`asOf` 与分区再跑一次，4 条规则的结果逐条相同；JVM 被杀的校验 `Failed`（`RunFailed`），结果表里没有它的行，门禁不变；还没有校验、规则改了之后、校验过期之后，门禁都是关的 |

## 运行

`infra/m4/flows/quality/run-quality.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **源、读端与看板。** 一个 Kafka 事件源把点击事件按天分区写进 ODS 表（L2，不含个人数据，发布给一个读端 principal），一个看板经外表视图读它；`LakeQuality` 声明四条规则：`user_id` 非空率至少 0.99、`event_id` 唯一、行数至少 50、`event_time_ms` 不旧于 2 小时，`gate.publish: true`。还没有校验时，门禁关着：读端访问这张表得到 403，看板被拒、没有用户。
2. **干净的数据。** 昨天和今天的分区各 60 条事件；分别对两个分区执行 `cove check --partition ... --wait`，退出码 0，结果表里各有 4 行（每条规则一行）。门禁打开：读端得到 200，看板恢复、用户的查询有结果。第一次校验只覆盖昨天的分区时门禁就打开了，状态写明其他分区与之后的数据未校验（见"限制"）。
3. **坏数据。** 今天的分区再来 5 条没有 `user_id` 的事件、3 条重复的 `event_id`。校验它，退出码 3，未通过 `user-present` 与 `event-unique`；门禁关闭，读端 403，看板被拒、用户登录被拒，有 Warning `GateClosed`。
4. **定位。** 校验的快照被打上 tag `cove-check-<uid>`；从结果表取出两条未通过规则的 `locate` 语句，换成按 tag 读，执行后列出的行正是发送的坏事件。
5. **重跑。** 新建一个任务，给出同样的快照、`asOf` 与分区：结果表里两次的量值、通过与否、失败行数、定位语句逐条相同。
6. **删掉任务。** 删掉这两个失败校验的 `LakeTask`：门禁仍然关着，依据仍是那次校验。
7. **豁免。** 注解里冒用别人名字的豁免被 API server 拒绝（ValidatingAdmissionPolicy）；没有 RBAC 权限的服务账号建不了豁免；豁免了一条通过的规则的被 `Rejected`；`cove waive` 以调用者的身份豁免两条失败规则，`Accepted`，门禁以 `Waived` 打开，读端与看板恢复。
8. **中断的校验。** 校验的 Pod 一开始运行就杀掉它的 JVM（退出码 143），在它写结果之前：任务 `Failed`（`RunFailed`），结果表里没有它的行，门禁不变。结果在一次提交里写入，提交本身的原子性由 Paimon 保证，这里没有在提交中途打断。
9. **改规则与过期。** 去掉唯一性规则、非空率降到 0.9、`gate.maxAge` 设为 6 分钟：门禁因 `NoCheck` 关闭，豁免变为 `Superseded`，读端 403；整表校验通过后门禁打开，6 分钟后因 `Expired` 关闭。
10. **审计记录。** 删除 `LakeQuality`，读结果库的 `decisions`：门禁的每次变化依次在案，豁免那一行写明豁免名、批准人和理由，最后一行是 `Removed`；它打的 tag 已删除。

## 结果

56 项检查全部通过（`verdicts.tsv`）。主要数字：

| 步骤 | 结果 |
| --- | --- |
| 校验前 | 门禁 `NoCheck`，读端 403，看板 `Failed`（quality gate） |
| 两个干净分区（各 60 条） | `cove check` 退出码 0；`user-present` 1.0、`event-unique` 0 个重复、`volume` 60、`fresh` 57 s 与 119 s；门禁 `Passed`（c1 之后的消息："other partitions, and data after it, not checked yet"），读端 200，看板的查询得到 `SUM(n)` 120 |
| 坏数据（5 条缺 `user_id`、3 条重复） | 退出码 3，未通过 `event-unique`（3 个重复键，6 行）与 `user-present`（0.926，5 行）；门禁 `Failed`，读端 403，看板 `Failed`、条件 `Ready` 为 False，用户登录被拒，Warning `GateClosed` |
| 定位 | tag `cove-check-8dd2ce94` 在快照 12；两条定位语句读出的行与发送的坏事件一致 |
| 重跑 | 4 条规则的量值、通过与否、失败行数、定位语句与第一次相同 |
| 删掉两个失败任务 | 门禁仍关 |
| 豁免 | 冒用批准人被 ValidatingAdmissionPolicy 拒绝；无权限的服务账号 Forbidden；豁免通过的规则 `Rejected`；`cove waive` 后 `Accepted`，门禁 `Waived`，读端 200，看板约 17 s 后恢复（门禁打开到用户重新授权） |
| 中断的校验 | 写结果之前被杀，`Failed`（`RunFailed`），结果表没有它的行，门禁不变 |
| 改规则与过期 | 门禁 `NoCheck`，豁免 `Superseded`，读端 403；整表校验通过（快照 25）后读端 200；6 分钟后 `Expired`，读端 403 |
| 删除 `LakeQuality` | `decisions` 的 10 行如上，最后一行 `Removed`；tag 已删除 |

## 过程中的发现

- q1：harness 运行期间修改了它调用的 `lake.sh`，bash 边读边执行，运行中断（这次运行作废，不是产品问题）。
- q2：被门禁拒绝的看板撤下后不再重新判定，门禁打开也回不来（M3 的看板只在声明变化时重判）；改为拒绝时同样每 30 秒复查。
- q2：harness 重发旧事件时沿用了原来的事件时间，水位已经过去，被源判为 `LATE` 进了拒绝表，唯一性规则因此没有失败；改为按当前时间发送。
- q3：看板从 `Ready` 转为被门禁拒绝时，用户被撤销了，状态却一直是 `Ready`。Operator 日志里是状态补丁的 422（`status.users: Invalid value: "null"`），重试耗尽后不再协调；对这个看板用 server-side apply 的 dry-run 写入 `users: {}` 复现了同样的 422，省略这个字段则通过（`run-q3-4850b05/root-cause.txt`）。也就是说 apply 把清空的 map 变成了 null，CRD schema 拒绝整个补丁。M3 起就有这个缺陷（任何先 Ready 后失败的看板都会遇到），这次被门禁暴露出来。改为所有状态里的 map 清空时省略，由 apply 删除这个字段；单元测试检查每个状态 map 都这样标注。q4 在修复之后全部通过（56 项）。
- 评审：被拒的看板 `phase` 是 `Failed`，条件 `Ready` 却仍为 True，改为 False；声明无效的看板不再每 30 秒重判（只有新的声明能改好它），被治理拒绝的照旧重判；门禁靠部分分区的校验打开时，消息写明其他分区未校验。q5 在这些改动之后重跑，56 项全部通过，即上文的结果。
- 评审：门禁因失败关闭时，`decisions` 原先记的是最新的一次校验（可能是通过的），改为记下导致失败的那一次；校验与 `LakeQuality` 同时 apply 时，任务原先可能在规则判定前就以 `NoRules` 失败，改为等待；`asOf` 不能晚于现在，否则门禁永不过期；结果表按被校验的表分区，Operator 只读这张表的分区，结果表没有新快照时沿用缓存。

## 限制

- 指标只有非空率、唯一性、行数和新鲜度；没有自定义 SQL 规则、跨表规则（如外键）。
- 门禁按仍有效的校验判定，不要求覆盖整张表：只校验了某些分区时，门禁打开发布的是整张表，其他分区只在状态里写明未校验。要拦住未校验的分区，就校验整表，或由编排器按分区决定。
- `freshness` 是整表的性质，校验历史分区时同样按整表计算。
- 门禁只拦平台自己的发布（`readers` 与平台 Doris 的看板）；编排器的下游步骤按 `cove check` 的退出码自行决定。
- 豁免的批准人由准入策略保证；集群里没有这条策略时，注解可以伪造（Operator 不检查策略是否存在）。
- 结果库只有 Operator 与平台管理员能读；用户经由状态看到结论。
- 开发阶段不保证升级兼容：结果库的布局变化要删掉旧表重建。

> 注：本文中的 issue 与 PR 编号（如 #38）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
