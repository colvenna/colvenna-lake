---
title: "M5 管理控制台：受控操作"
date: 2026-10-19
status: complete
issue: 138
---

# M5 管理控制台：受控操作

本页记录 Issue #138 的验收：有权限的运维在管理控制台里完成 cove CLI 已有的变更，每个操作都经过授权、必要时先确认，并留下审计。控制台在 [#135](entry.md) 的入口后面（`https://console.cove.<COVE_HOSTED_ZONE_NAME>/operations`），与[流水线全景](pipeline.md)在同一个应用里。设计见 [Web 入口与控制台 LLD](../../design/colvenna-console-lld.md) 的"管理控制台：受控操作"，依据 HLD 9.1、9.2、11.1、12.3、12.4。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），与 M1–M4 同一个 EKS 集群（`v1.35.8-eks`，控制面审计日志开着），只用合成数据。

**证据的来源版本**：结果来自提交 `fb149b7`，私有证据目录 `m5-138/run-o7-fb149b79/`。镜像：控制台 `m5-console@sha256:8dce2da0…`，Operator `cove-operator@sha256:f260309d…`（#136 的构建，Operator 与作业的代码此后没有改动；chart 以这次提交的版本 `helm upgrade --reuse-values`，带上豁免准入与豁免角色的修改），`cove` 是这次提交的构建。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 每个操作在控制台与 CLI 上的结果一致：同样的资源、状态与 Operator 判定 | 通过 | 每个操作各做一次 `cove`（本机的 IAM 身份）、一次控制台（登录的运维或管理员），输入相同。结果：spec、阶段、条件（类型、状态、原因）、检查的结论与未通过的规则都相同（名字之外）。覆盖的操作：应用质量规则（`Ready`），以及一份被 API server 拒绝的声明（同样的报错）；质量检查（`Succeeded`，`Failed`，未通过 `volume`）与它的豁免（`Accepted`）；打 tag、压缩、过期快照、清理孤儿文件（都是 `Succeeded`）；挂起与恢复，两种方式交替各做一次；回刷（`Succeeded`）；删除声明（质量规则；分层表与源，各带走自己的作业）；合规删除（`LogicallyErased`，Secret 由删除请求拥有、内容相同） |
| 没有对应角色的用户的操作被拒；前端隐藏按钮不算授权，直接调用后端接口同样被拒 | 通过 | 下面这些请求直接调用后端接口，都被拒绝，什么也没有建、没有删：BI 用户、没有角色的用户 403；运维直接提交删除、合规删除与豁免 403（页面上本就没有这三项）；别的站点或没有 `Origin` 的请求、不受管的命名空间 403；没有会话 302 到登录。Kubernetes 自己也拒绝：`cove-console:operator` 不能删除，不能建删除请求、豁免与 Secret，`cove-console:admin` 不能读 Secret；控制台只能扮演它的两个用户。控制台的"应用声明"也拒绝要确认的任务（回刷，以及用别名写的过期快照）与别的命名空间的对象（400） |
| 每个操作都留下审计：谁、何时、对哪个资源、做了什么、结果如何 | 通过 | 控制台的审计日志组里，每个操作都按人与对象记下 `requested` 与结果（产生的对象，或 API server 的报错与状态码），要确认的另有 `previewed`；被拒的操作以 403 与原因记下。集群的审计日志里，控制台建豁免的那次请求的 `user` 是控制台的 ServiceAccount，`impersonatedUser` 是 `cove-console:admin`，附加信息是本人的用户名与 sub。主体的键值在两处审计与这次运行留下的证据里都找不到 |
| 不可逆的操作需要确认，取消后不产生任何变更 | 通过 | 回刷、删除、合规删除、过期快照与清理孤儿文件都先列出影响范围，要带着确认再提交一次才执行；删除的确认只对预览时看到的那个对象有效（单元测试覆盖它变了之后回 409）。三次取消（回刷、删除质量规则、合规删除）之后：没有任务，规则的 generation 没变、不在删除中，没有删除请求与 Secret；回刷与合规删除的取消在审计里只有 `previewed` |

## 运行

`infra/m5/scripts/build-console.sh`、`install-console.sh` 构建并安装控制台（安装脚本生成确认的密钥），Operator 的 chart 以 `helm upgrade --reuse-values` 更新。`infra/m5/flows/operations/run-operations.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL；比较由 `judge.py` 做。浏览器由 #135 的 `entry.py` 模拟，表单以 `entry.py post` 从控制台的页面提交（带本站的 `Origin`）。

1. **用户与流水线。** 4 个合成用户：admin（`cove-admin`）、op（`cove-operator`）、bi（`cove-bi`）、none（没有组）。MySQL 源接入 `orders`、`customers`，一张 DWD 表读它们。
2. **没有角色的。** 依次请求：没有会话、bi、none 打 tag；op 直接提交删除、合规删除、豁免；admin 从别的站点、不带 `Origin`、在 `kube-system` 打 tag。然后核对：
   - 两个角色的页面上各有哪些操作；
   - 什么也没有建，DWD 还在；
   - `kubectl auth can-i` 看控制台能扮演谁，以及它的两个用户在作业命名空间能做什么。
3. **应用声明。** 一份 `LakeQuality` 先用 `cove apply` 应用、记下它，再删掉；然后在控制台以 op 应用同一份，比较两者。一份 spec 不合 schema 的声明加一个 ConfigMap，两种方式各提交一次，比较报错。控制台再提交一个回刷任务、一个以别名 `task` 写的过期快照任务、一个属于别的命名空间的压缩任务。
4. **检查与豁免。** 依次做：先用 `cove check --wait` 检查（行数下限故意不满足），并 `cove waive`；之后在控制台以 op 检查、以 admin 豁免。后一次检查会取代前一次的豁免，所以每一边都等它稳定之后记下，再做另一边。
5. **维护。** 打 tag 与压缩做在 `customers` 上，过期与清理孤儿做在 `orders` 上，两种方式各一个任务（控制台的过期与清理先确认）。等任务结束，逐对比较。
6. **挂起与恢复。** 在控制台挂起 DWD，用 `cove` 恢复，再用 `cove` 挂起，在控制台恢复。
7. **回刷。** 控制台以 op 预览一次后取消；再预览、确认；然后用 `cove backfill --wait` 回刷一次。比较两次回刷。
8. **删除。** 记下质量规则的 generation，在控制台以 admin 预览删除后取消（之后它的 spec 不变、不在删除中），再确认删除；比较删除所用的时间与 `cove` 删除它的时间。确认时提交的是确认页上的全部隐藏字段，与浏览器一样，包括预览绑定的 uid 与 generation。
9. **合规删除。** 主体是一个随机的、数据里没有的值。在控制台预览后取消；然后用 `cove forget`，等它稳定；再在控制台确认，等它稳定。比较两个删除请求与它们的 Secret。合规删除的页面只留在临时目录里（页面带着键值），不进证据。
10. **审计。** 控制台的审计日志组里逐个核对上面的操作与拒绝；集群审计日志里找控制台建豁免的那次请求；等集群审计里出现控制台建的那个主体 Secret 的事件之后，再找主体的值（原样与 base64）。
11. **结束。** 在控制台确认删除 DWD，用 `cove` 删除源：两者都没了，它们的 Flink 作业也没了。

## 结果

运行 o7（`fb149b7`）的 59 项检查全部通过。

| 检查 | 结果 |
| --- | --- |
| `source-ready`、`dwd-running` | 212、100 秒 |
| `refused-without-the-role`、`the-page-offers-each-role-its-own`、`nothing-made-by-the-refused`、`nothing-deleted-by-the-refused` | 没有会话 302；bi、none 403；op 的删除、合规删除、豁免 403；别的站点、没有 `Origin`、`kube-system` 403。op 的页面上是 apply、suspend、resume、backfill、compact、expire、orphans、tag、check；什么也没建，DWD 仍 `Running` |
| `the-console-impersonates-only-its-users`、`kubernetes-holds-the-roles` | 能扮演 `cove-console:admin`、`cove-console:operator`；不能扮演 `system:admin`、`kubernetes-admin`、`system:masters` 组、ServiceAccount。operator：删除、建删除请求、建豁免、建 Secret 都不行，改分层表可以；admin：删除、建 Secret 可以，读 Secret、建 Pod 不行 |
| `apply-is-the-same`、`apply-refused-the-same`、`apply-leaves-confirmed-and-other-namespaces-out`、`nothing-applied-around-the-rules` | 两边都是 `Ready`，没有差异；被拒的声明两边是同一句报错（`.spec.rules: expected list`，API server 回 500）；回刷任务、别名写的过期快照任务、别的命名空间的任务都是 400，都没有建出来 |
| `check-is-the-same`、`check-failed-the-rule`、`waive-is-the-same`、`the-waivers-approver-is-the-person` | 两次检查都 `Succeeded`、结论 `Failed`（`cove` 退出码 3）；两份豁免都 `Accepted`；控制台那份的审批人是 `cove-console:admin/m5o-o7-admin`，`cove` 那份是本机的 IAM 角色会话 |
| `maintenance-submitted`、`tag-`、`compact-`、`expire-`、`orphans-is-the-same`、`expire-and-orphans-showed-their-effect` | 四个控制台任务都 200；八个任务都 `Succeeded`，每对没有差异；过期的预览写明"time travel 回不去"，清理的写明"删掉的文件不能恢复" |
| `suspended-in-the-console`、`resumed-with-cove`、`suspended-with-cove`、`resumed-in-the-console`、`suspend-and-resume-answered` | 12、91、13、91 秒 |
| `backfill-shows-its-effect-and-a-cancel-changes-nothing`、`backfill-is-the-same` | 预览："从上游重算 `dwd_m5o_o7.orders`（由分层作业 `cove-m3/owd-o7` 写）"；取消之后没有任务；两次回刷都 `Succeeded` |
| `delete-shows-its-effect-and-a-cancel-changes-nothing`、`delete-is-the-same` | 预览："删除表 `ods_m5o_o7.orders` 的质量规则；它的门禁随之撤下"；取消之后规则的 spec 不变、不在删除中；`cove` 8 秒、控制台 7 秒删掉 |
| `forget-shows-its-effect-and-a-cancel-changes-nothing`、`forget-is-the-same`、`the-erasure-owns-its-subject`、`the-subject-is-the-same` | 预览：1 个键值、期限 24h 与 9d、这个命名空间写的 3 张湖表；取消之后什么也没有；两个删除请求都到了 `LogicallyErased`；控制台的 Secret 由它的删除请求拥有，内容与 `cove` 的相同 |
| `audit-*`（21 项） | 每个操作的 `requested` 与结果都在（例如 `waive cove-m3/LakeWaiver/owc-o7`：requested，`waiver/owc-o7` 200）；要确认的还有 `previewed`；回刷与合规删除的取消只有 `previewed`（删除的取消与随后的确认是同一个对象，那里是两条 `previewed`，再 `requested` 与结果）；op 的三次越权各一条 403 "not an administrator"；被拒的四次 apply 各有它的状态码与报错 |
| `cluster-audit-names-the-person`、`the-subject-is-in-no-audit` | `patch lakewaivers/owc-o7`：`user` 是 `system:serviceaccount:cove-console:cove-console`，扮演 `cove-console:admin`，附加信息 `cove-lake.io/person: [m5o-o7-admin]` 与 sub，201；控制台审计 97 条里没有主体的值；集群审计里有这个删除请求的 18 条事件，没有一条带着主体的值（原样或 base64）；证据目录里也没有 |
| `deleting-a-pipeline-declaration-each-way` | 控制台删除 DWD（预览写明 deletionPolicy Delete、下游 `dwd_m5o_o7.orders`）7 秒后没了，`cove` 删除源 14 秒后没了，两者的 Flink 作业都没了 |

之前的运行发现并修正了这些问题：

- **o1：** 确认的密钥是只有 root 能读的 Secret 文件，控制台以 65532 运行读不到，起不来。现在 Pod 带 `fsGroup`，文件对组可读。
- **o2：** `cove check --wait` 在规则未通过时按设计退出 3，harness 在 `set -e` 下因此中止。现在把预期中的非零退出写明，某一步失败只记一条 FAIL，不中止运行。
- **o4：** 控制台的豁免被拒（403）。豁免是 server-side apply，要 `patch`，而 `cove-console:admin` 只有 `create`。Operator chart 给数据负责人的豁免角色也缺这一项，`cove waive` 对非集群管理员同样会被拒。
- **o5（`733f71b`，57 项全部通过）之后的复核：**
  - 删除的确认原先只签表单：预览之后、确认之前把 `deletionPolicy` 改成 Delete，确认的删除就会删表。现在绑定对象的 uid 与 generation，删除请求也带 uid 的前提。
  - "应用声明"原先只按种类的正式名字拦截，用别名能绕过；过期快照与清理孤儿文件的任务也能经它不确认地提交。
  - 看板没有 `deletionPolicy`，预览原先却写着 Retain；不是分层作业写的表，回刷原先也给出确认。
  - harness：
    - 集群审计里找主体的值原先不等日志投递；
    - 取消之后原先比较 `resourceVersion`，状态更新也会改它。
- **o6（`d24defc`，58 项通过、1 项失败）：** 控制台的"应用声明"先逐个核对清单，遇到不认识的种类就拒绝；CLI 则按顺序应用、在第一个被拒的对象处停下，于是两边的报错不同。现在不认识的种类留给应用那一步按顺序拒绝。
- **评审：**
  - "应用声明"原先能提交回刷任务，绕过确认；也能把对象写进清单里的另一个命名空间。
  - 过期快照与清理孤儿文件不可逆，原先不用确认。
  - 删除质量规则、豁免、删除请求等原先不说影响；读不到影响时原先仍然给出确认。
  - 越权的拒绝原先不带操作名与对象。
  - 合规删除原先可能把一个同名的、别人的 Secret 当成主体，现在先以 dry run 试建。
  - harness：
    - 原先任务还在运行就比较；
    - "取消"原先不核对对象是否正被删除；
    - "删除一致"原先什么也没比较；
    - 合规删除的页面原先进了证据；
    - 集群审计原先没有查主体的值。

## 限制

- **控制台能做它两个用户能做的事。** 控制台被攻破时，攻击者能以 `cove-console:admin` 在所管命名空间改、删声明，建 Secret（不能读），不能扮演别人，也碰不到别的命名空间。
- **本人是控制台说的。** 附加信息里的本人来自控制台验证过的 token；API server 不再核对它。
- **没有逐一验收的。** Iceberg 发布的删除，以及 Kafka 源、MongoDB 源上的操作，没有在这次运行里做；它们走同一条路径（`cove-cli/ops`、同样的 RBAC）。
- **删除只发出请求。** 控制台不等 Operator 清理完；`cove delete` 的 `--wait` 与各命令的 `--wait` 在控制台里没有对应，结果看流水线的节点页。
- **确认的密钥。** 拿到 Secret `cove-console-confirm` 的人可以为任何请求伪造确认，但仍要以有那个角色的人登录。
- **主体的键值经过浏览器。** 合规删除的确认页把键值作为隐藏字段交回给本人，再随第二步提交（TLS，页面不缓存）。

> 注：本文中的 issue 与 PR 编号（如 #138）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
