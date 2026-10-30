# M1 历史回刷与下游收敛验证

本页对应 Issue #15 和
[M0 历史更正契约](../../design/acceptance-contract.md#3-历史更正与分层回刷)。
按 Issue #15 的三条接收条件，当前状态为 **COMPLETE**，判定依据见
[结项判定](#结项判定)；M1 的四个 issue（#8、#14、#15、#16）均已按接收条件结项。
下文按时间保留各轮私有 S3 实测：前两轮发现 DWS 重复累加，第三轮在最终
consumer 门禁被拒；第四轮 Paimon 分层收敛通过，第五轮 Doris 诊断在业务
变更前被节点及主机重启中断；第六、七轮隔离的 Paimon 与 Doris 外表切片通过；
第十轮增加物化结果；第十一轮真实覆盖写入与物化刷新失败恢复切片通过。
各轮小节里的 NOT_COMPLETE 是当时的判定。

## 验收数据与边界

沿用 #13 的订单 ODS、客户地区维表、DWD 明细和 DWS 地区聚合语义，
但为 #15 创建独立的合成表、运行身份、检查点和 Doris 读端。
首版仍以 Flink 1.20.4、Paimon 2.0.0、共享 JDBC catalog、
AWS `work` / `us-west-2` 私有 S3 为准。
全部表及状态前缀按本次运行 ID 隔离。
已验证的 #13 处理时间 lookup join 只用**新到达**的订单查当前维表；
更正客户地区不会自动改写已有订单，须显式重算。

| 阶段 | ODS/维表 | DWD 预期 | DWS 预期 |
| --- | --- | --- | --- |
| 初始 | 订单 1：客户 1、金额 10；订单 2：客户 2、金额 20；订单 3：客户 1、金额 30。客户 1 为 north，客户 2 为 south | 1、3 属于 north；2 属于 south | north：2 单、40；south：1 单、20 |
| 活跃流更正 | 订单 1 金额改为 15，删除订单 2，插入客户 2 的订单 4、金额 40 | 1、3 属于 north；4 属于 south | north：2 单、45；south：1 单、40 |
| 历史维表更正并显式回刷 | 客户 1 改为 west；旧订单 1、3 的地区需追溯更正 | 1、3 属于 west；4 属于 south；2 不存在 | west：2 单、45；south：1 单、40；north 不存在 |
| 围栏期间新事件排队，恢复后消费 | 客户 1 的订单 5、金额 5 在 DWD/DWS 暂停期间进入 ODS | 恢复前 DWD 不得变化；恢复后 1、3、5 属于 west；4 属于 south | 恢复前 west：2 单、45；恢复后 west：3 单、50；south：1 单、40 |
| 恢复后再新增事件 | 客户 1 的订单 6、金额 7 | 1、3、5、6 属于 west；4 属于 south | west：4 单、57；south：1 单、40 |

完成门必须同时核对受影响键、ODS/DWD/DWS 的固定快照、
消费者位点、Doris 查询行集和删除键的负向查询。
各层记录数及金额汇总要与上表一致，
并记录源提交、批作业终态、各层快照、Doris 可见时刻。
作业 `FINISHED` 或快照增长本身不能放行完成信号。
Issue #14 记录 Doris 4.1.4 / Paimon 2.0.0 自带读端的 DV L0 读为 **RED**，
非 DV 主键表与 append 表读取正确。Issue #15 必须用已核对正确的读路径验证
Doris 结果；各轮使用非 DV 表和自带读端，满足这一准入。

## 故障、围栏与重复执行

1. 在更正范围和输入快照确定后，先把所有可能写入受影响分区的
   常驻流任务停在可验证的 checkpoint/savepoint 边界，
   记录源位点与各层 consumer 的下一快照。
   未证实写入已暂停或隔离时，回刷不能开始。
2. 以独立批作业重算受影响的 DWD 键和 DWS 聚合。
   DWS 隔离 staging 结果是供核对的候选值；活跃 DWS 只有在
   DWD 更正 changelog 被恢复的流聚合完整消费并核对后才算收敛。
   注入一次在 DWD 已提交、DWS 或 Doris 尚未收敛时的失败；
   完成门必须报告 `NEEDS_RECONCILIATION`，不得报告 `COMPLETE`。
3. 使用相同更正批次和固定输入边界重试，确认每层及 Doris
   收敛后才报告完成。再重复执行同一批次，
   最终业务键集合、删除键负向查询和聚合结果必须相同。
4. 在围栏期间先向 ODS 追加订单 5，核对 DWD/DWS 快照均未前进；
   从已记录边界恢复常驻流，再注入订单 6，
   确认既不丢失新事件，也不把旧地区或已删除订单重新写回。
   如果原地覆盖无法保证安全的暂停与重放，应改用隔离版本并在
   全部读端核对后切换；不能把 Paimon strict mode 的冲突拒绝
   当成应用级围栏。

## 当前门禁

| 项目 | 当前状态 |
| --- | --- |
| 活跃流的历史订单金额更正与删除 | PASS：第六轮私有 S3 小样本 ODS/DWD/DWS 与 Doris 外表行集一致；本轮使用非 DV 表，DV 读路径见 #14 |
| 维表历史更正、显式 DWD/DWS 回刷与 Doris 收敛 | PASS：前两轮 **RED**、第三轮最终 consumer 门被拒后修复；第六轮隔离外表切片通过 |
| 并发写入围栏与边界后事件恢复 | PASS：第六轮订单 5 在围栏期只进入 ODS；savepoint 恢复后订单 5、6 均在 DWD/DWS 与 Doris 外表得到正确结果 |
| DWD 后故障、完成信号拒绝及修复重试 | PASS：第六轮注入 DWS Job 失败，完成门返回 `NEEDS_RECONCILIATION`；修复后切片通过 |
| 同一批次重复执行后的最终集合 | PASS：第六轮围栏期 DWD 重跑为 0 条更改，DWS staging 重跑行集一致；第七轮恢复后再次运行同批次，DWD 为 0 条更改，DWS staging 与原生/Doris 外表行集一致；仅限隔离合成切片 |
| 历史 `INSERT OVERWRITE` 后的显式重算 | PASS：第十一轮实际源快照 2→3 为 OVERWRITE，固定输入重算与两次重复执行、流恢复、外表和物化结果一致；仅限隔离合成切片 |
| 实际物化刷新失败与恢复门禁 | PASS：第十一轮实际 FAILED 任务被拒绝；恢复 catalog 后新 SUCCESS 任务及正确物化行通过；完整故障矩阵属于 #16 |
| 受限身份、私有入口、真实 S3 行集及定点清理 | PASS：各轮使用本轮专用身份；匿名 GET 403 记录于第六轮；第五轮清理陷阱未执行、由人工完成，其余各轮的定点清理已核对 |

运行证据须绑定干净源码 SHA、Flink/Paimon/Doris 镜像 digest、
作业与输入/输出快照 ID。临时角色仅能访问 `issue15_*` 合成表
及本次 checkpoint 前缀，所有服务保持私有且要求认证；
先确认已存在的 S3 对象匿名 GET 为 403。
失败时保留 `RED`、`UNKNOWN` 或 `NOT_RUN` 原始记录，
先核实远端作业终态再清理隔离表和 Kubernetes 资源。

## 结项判定

2026-10-11 按 Issue #15 原文的三条接收条件结项，下文各轮记录保持原样。
性能目标和完整故障矩阵属于 #16，不在本 issue 的接收条件内。

| 接收条件 | 证据 | 判定 |
| --- | --- | --- |
| 对历史更新与删除构造端到端用例，所有应更新的下游结果可核对 | 第六、七轮：活跃流金额更正与删除、维表历史更正后显式重算 DWD/DWS，ODS/DWD/DWS 原生行集与 Doris 外表一致；第十轮加入物化结果；第十一轮真实 `INSERT OVERWRITE` 2→3 后的固定输入重算、外表和物化结果一致 | PASS |
| 回刷完成状态不早于下游结果收敛；失败时无静默半成品 | 第六轮注入 DWS 失败、第十一轮实际 MV `FAILED` 任务，完成门均返回 `NEEDS_RECONCILIATION`；修复并核对全部读端后才返回通过 | PASS |
| 重复执行得到相同最终结果 | 第六轮围栏期与第七轮恢复后重跑同一批次，DWD 为 0 条更改，DWS staging、原生和 Doris 外表行集不变；第十一轮覆盖后两次重复执行结果一致 | PASS |

Issue 边界里的"活跃流任务与历史作业并存"由第六轮围栏和 savepoint
恢复覆盖：围栏期间订单 5 只进入 ODS，恢复后订单 5、6 在各层与 Doris
外表均正确。这两笔都是新键，受影响键上的并发写入见下表的归属。

第四轮至第十一轮使用非 DV 表和 Doris 4.1.4 自带读端。DV 表经
`cove-readonly-jdbc` 受限读端的读取不作为本 issue 的证据，由 #14 判定；
补充观察：#16 的 24 小时基线 `20261011062753_1289437` 用同一工厂初始化
Doris catalog 并持续读取 DV ODS、DWS 外表和定时刷新 MV，结果记在 #16 的报告中。

失败期间各表独立提交，读者直接查询时可能看到新 DWD 与旧 DWS 并存。
M0 契约不承诺跨表瞬时原子可见，只要求完成信号前不把新旧批次当作已完成
结果发布；完成门在这种情况下返回 `NEEDS_RECONCILIATION` 而非 PASS。

M0 第 3 节中超出本 issue 接收条件的项目另有归属：

| 项目 | 归属 |
| --- | --- |
| 同一输入位点重放后最终集合不变、完整故障矩阵 | #16（checkpoint 失败后源重放等故障注入） |
| 定时刷新的 MV 与代表性查询负载 | #16（24 小时基线中的定时刷新 MV 与 10+1 QPS 查询压测） |
| Doris 内表的刷新与重建 | #32 |
| 回刷期间常驻流对**受影响键**的并发写入（各轮围栏只用新键订单 5、6） | #26 |
| 更正批次登记、负责人、对外显示旧批次或"结果待更正"的发布契约 | #27 |

各轮记录的源码 SHA 是 2026-10-11 rebase 到 main 之前的提交，rebase 只
叠加了 main 的 README 与 `docs/research` 文档改动。第十一轮（`0f0347a`）
之后的提交新增了 `paimon2` / `paimon2-scoped` 读端选项、受限工厂及其测试，
默认读端生成的 catalog 属性不变；复审修复又改动了各轮共用的读端角色创建、
`doris_stop` 和完成门（见下段），完成门的判定结果不变。
本次复审后的修复：`BackfillReader` 改为只向 PostgreSQL 发送客户端计算的
SCRAM-SHA-256 verifier，不再在 `CREATE ROLE` 语句中携带明文。2026-10-11 在
私有 catalog RDS 上以临时运行 `20261011141500_1` 实测：verifier 建角色成功，
读端用原口令登录并通过无写权限检查，随后删除角色。Doris 4.1.4 会把
`CREATE CATALOG` 属性中的 JDBC 读端口令明文记入 FE 审计日志；该日志只在集群
内 FE Pod 中，读端为每次运行专用的只读角色。正常清理会删除角色使口令失效；
`doris_stop` 失败时脚本在 `reader-drop` 之前退出，角色与 FE 卷都会保留，
须在对账后人工删除该角色。GitHub Actions 因账号账单限制未启动作业；PR #74 按需求方
豁免记为 `WAIVED_BY_REQUESTER_NOT_PASS`，在同一 SHA 上本地执行了全部工作流作业。

## 首轮实测 RED

干净源码 SHA `4e8c92b1e636ab3c4134e8b40fd76e258d37862f`，
私有运行 `20260929160751-1562023` 使用专用 IRSA、
Flink 1.20.4 / Paimon 2.0.0 和真实 S3。
初始订单、活跃流金额更正与删除均在三层得到预期行集。
DWD、DWS 分别完成 checkpoint 后保存 savepoint 并暂停；
客户 1 更正为 west 后，DWD 与 DWS 批作业分别提交。
在 DWD 成功、DWS 故意失败时，完成门返回
`NEEDS_RECONCILIATION`。订单 5 在两条流暂停期间进入 ODS，
DWD/DWS 快照不前进。重复执行相同批次仍得到暂停期相同行集。

从原 savepoint 恢复两条流后，DWS 期望 west **3 单 / 50**，
实际为 **5 单 / 95**。其 consumer 已越过 DWD 最新快照，
且又完成多次 checkpoint；这不是可见性等待。
批 DWS 已把 west **2 单 / 45** 写入活跃表，
而保存的流聚合状态随后又回放 west **3 单 / 50**，
两者被错误叠加。该路径保持 **RED**，未继续订单 6 与 Doris 检查。

本机私有证据归档在
`<证据目录>/issue15-live-20260929160751-red/`，
241 个文件已逐字节核对。该轮 4 张隔离表、检查点/savepoint 前缀
和 #15 Kubernetes 资源均清零；为调度临时增加的 query/write
节点组也已删除，共享 CNI 为 false/false，4 个原有长驻作业 RUNNING。
第二轮已将 DWS 批结果写入隔离 staging 表；下文记录其单独实测。

## 第二轮实测 RED

干净源码 SHA `c17ccfd8a56950d5fdf03025b2a581d8b82cd6f9`，
私有运行 `20260929171213-1720212` 将批 DWS 结果写入隔离 staging 表。
staging 得到 south **1 单 / 40**、west **2 单 / 45**；
活跃 DWS 在流恢复前仍保留 north **2 单 / 45**。DWD/DWS
从各自已核实的 savepoint 恢复，DWD 得到正确的地区更正与
排队订单 5；活跃 DWS 稳定为 north **2 单 / 45**、
south **1 单 / 40**、west **5 单 / 95**，预期则没有 north，
west 应为 **3 单 / 50**。DWS consumer 位点已越过 DWD
最新快照，多次 checkpoint 完成，因而判定 **RED**。

仅用于诊断的 Paimon 增量读取显示：SQL `UPDATE` 更正的 DWD
快照只产生 west 的 `UPDATE_AFTER`，未产生 north 的 `DELETE`
或 `UPDATE_BEFORE`；批次重复执行使 west 被再加一次。
在两张单独的临时表中，原生 Paimon 写入显式 north `DELETE`
和 west `INSERT` 均产生预期的撤回与新增。临时表已删除，
这项 **PROBE_ONLY** 诊断不算第三轮验收通过。

本机私有证据归档在
`<证据目录>/issue15-live-20260929171213-red/`，
369 个文件已逐字节核对。该轮五张隔离表、Job、checkpoint/
savepoint 前缀及 #15 身份和准入资源已清理。
下一轮将从注册的 DWD 输入快照精确读取旧键，
写入显式撤回与更正，逐条检查 DWD 增量 changelog
中的旧地区删除与新地区插入，并核对重试幂等性；
此修复尚未实测。若批 Job 终态无法观察，保留隔离资源及
`UNKNOWN` 证据，待人工核对终态后再清理。

## 第三轮业务行集收敛，最终门禁未通过

干净源码 SHA `07e0fda10465bc4889d8e09619ee855199911286`，
私有运行 `20260929181308-1986495`。DWD 批 Job 终态
`SUCCEEDED`，注册 DWD 快照 6→7 的增量 changelog 精确包含
订单 1、3 的 north `DELETE` 与 west `INSERT`，旧地区撤回
差异为空。故障注入的 DWS Job 终态 `FAILED`，完成门返回
`NEEDS_RECONCILIATION`。同一 DWD 批次重跑报告 0 条更改；
DWS staging 重跑行集不变。两条流从各自的原 savepoint
恢复后，排队订单 5 对应的 DWS 为 south **1 单 / 40**、
west **3 单 / 50**，没有 north；DWS consumer 位点 11
已越过 DWD 快照 10。追加订单 6 后业务行集变为
south **1 单 / 40**、west **4 单 / 57**，预期差异为空。

最后一次读数时 DWD 又提交了快照 13，DWS consumer 位点
仍为 13；完成门正确返回
`NEEDS_RECONCILIATION dws-consumer-progress,doris-not-verified`。
这不能据最终行集正确推断消费位点已经覆盖最新快照。
原运行按失败路径退出并清理了五张隔离表、Job、checkpoint/
savepoint 前缀及身份、准入对象。本机私有证据位于
`<证据目录>/issue15-live-20260929181308-gate-rejected/`，
235 个文件已逐字节核对。本轮标记为 **GATE_REJECTED**，
整体 **NOT_COMPLETE**；Doris 仍 **NOT_RUN**。

新脚本在最终业务行集一致的前提下，逐次保存原生快照与
consumer 位点，直到同一份读数只剩 `doris-not-verified`，
或在有界等待后明确失败。第四轮已实测此门禁。

## 第四轮 Paimon 收敛通过，Doris NOT_RUN

干净源码 SHA `b1aaf273373b959cf7c610bd50e2b7359723f666`，
私有运行 `20260929185832-2136849` 使用与提交同 SHA 的
专用容量栈。初始、活跃流更新/删除后的 ODS/DWD/DWS 行集
逐项匹配。DWD/DWS 完成 checkpoint 并从各自 savepoint 暂停；
客户地区更正后，DWD 批 Job 终态 `SUCCEEDED`，注册 DWD
快照 6→7 的原生增量 changelog 含订单 1、3 精确的 north
`DELETE` 和 west `INSERT`。故障注入的 DWS Job 终态
`FAILED`，完成门返回 `NEEDS_RECONCILIATION`。
围栏期间订单 5 只进入 ODS；同一 DWD 批次重跑为 0 条更改，
隔离 DWS staging 重跑行集不变。两条流从记录的 savepoint
恢复后，订单 5 的 DWS 为 south **1 单 / 40**、
west **3 单 / 50**，north 不存在。恢复后订单 6 使 west
成为 **4 单 / 57**，最终 ODS/DWD/DWS 行集差异为 0。

最后一份原生读数中，ODS 快照为 4、DWD 为 13、DWS 为 13；
DWD consumer 为 5，越过 ODS 快照；DWS consumer 为 14，
越过 DWD 快照。最终门仅返回
`NEEDS_RECONCILIATION doris-not-verified`，本次分类为
**PAIMON_CONVERGED_DORIS_NOT_RUN**。这只证明本合成数据的
Paimon 分层更正、故障拒绝、重试与流恢复；
尚未证明 Doris 查询收敛、千万行目标负载或第二朵云。
Issue #15 与 M1 继续 **NOT_COMPLETE**。

本轮 DWD 更正使用独立 Kubernetes Job 中的原生 Paimon
`DELETE`/`INSERT` 写入，因为 Flink SQL `UPDATE` 在第二轮
没有产生供 DWS 撤回旧地区的 changelog；DWS staging
仍使用 Flink 批模式。这个实测方案与 HLD 的“历史回刷统一用
Flink 批模式”目标有差异，尚未作为正式执行引擎选型通过。
`convergence_gate.py` 是本次 PoC 的 Python 验证器，
不作为违反项目 Java/Go 产品语言边界的线上完成服务交付。
恢复后再次运行同一更正批次尚未验证；
当前精确键集合检查会拒绝注册边界之后新增的订单 5、6，
这项能力与历史 `INSERT OVERWRITE` 后显式重算同为 **NOT_RUN**。

本机私有证据归档于
`<证据目录>/issue15-live-20260929185832-paimon-converged/`，
248 个文件已逐字节核对。五张隔离表、Job、checkpoint/
savepoint 前缀及本次身份、准入对象均已定点清理。

## 第五轮 Doris 查询诊断被重启中断

`issue15/run-doris-convergence.sh` 将以独立的 Doris 只读 IRSA、
只读 PostgreSQL catalog 账号和私有 FE/BE 集群，
在同一次 #15 回刷的初始、更新删除、DWD 成功/DWS 失败、
围栏排队、流恢复及最终阶段查询 ODS、维表、DWD 和 DWS。
每阶段核对默认查询与指定原生快照的完整行集、
查询计划中的 Paimon native/JNI 分流，以及订单 2 与 north 地区
在相应阶段的存在或消失。只有原生行集、作业终态、
consumer 位点全部通过后，`convergence_gate.py`
才调用带认证的 Doris 客户端并产生隔离外表查询切片判定；
调用方提交的 `doris_status` 字符串仍不能放行。

干净源码 SHA `d66254a0cc6ebb6141f8649ae6d86bd37868fcf5`
的私有运行 `20260929235424-3582625` 已通过两种 Pod
准入策略、隔离 FE 入口拒绝与放行、Doris FE/BE Ready、
只读 JDBC 角色核对和 Doris catalog 创建。
但在初始订单提交前，临时 query 节点于
2026-09-30 00:02 UTC 关机并重启，Probe Pod 失败；
主机随后重启，运行脚本的清理 trap 未执行。
恢复后的 DWD 作业虽为 RUNNING，但原生检查证实只有维表
快照 1，ODS 订单、DWD、DWS 均无快照，两个 consumer 位点为空，
且没有隔离批 Job。因此本轮为
**INTERRUPTED_NOT_PASS**，Doris 业务行集仍 **NOT_RUN**。
FE Pod 在节点重启后重建，已创建的临时 catalog 在认证查询中
变为缺失，**FE catalog 恢复为 RED**；不能把这一状态当作正常删除。

确认没有批 Job 后，恢复流程先停止 DWD 写入，再移除 Doris
Cluster、Service 与身份；以原 manifest 重建 Probe Pod、
核对相同 JAR SHA，删除只读 JDBC 角色与五张隔离表，
并验证各表 S3 目录和 checkpoint/savepoint 前缀为空。
最后恢复共享 CNI 到 false/false、PolicyEndpoint 为 0，
销毁两个临时节点组；四个原有常驻 Flink 作业均 RUNNING，
Kafka broker Ready。279 个私有证据文件已逐字节核对并归档于
`<证据目录>/issue15-live-20260929235424-interrupted/`。

本切片的非 DV 主键表允许已在 #14 核对过行集的 JNI 路径，
须将 `native-splits=0/1` 如实记录为 JNI；DV 路径仍按 #14
的 **RED** 隔离。JDBC 角色对共享 `public` catalog 元数据
有只读可见性，S3 物理数据权限仅限 `issue15_*`；
本次只使用合成数据，不能据此宣称元数据按表隔离。
后续脚本在 Doris catalog 创建请求前及成功后立即记录回执；
已发起创建但清理时缺失的情形会阻止自动卸载，留待核对。
此保护已通过本地故障用例，真实 FE 故障恢复仍未验收。
即使外表查询切片通过，历史 `INSERT OVERWRITE`、
流恢复后重复更正和 Doris MV/内表派生结果仍为 **NOT_RUN**；
Issue #15 与 M1 继续 **NOT_COMPLETE**。

## 第六轮隔离外表查询切片通过

干净源码 SHA `aab73c241bf522424f9b8377e15c3d818ab0e956`，
私有运行 `20260930010356-178377` 复用了经 ECR digest 与扫描核对的
Doris FE/BE、Flink 1.20.4 和 Paimon 2.0.0，使用本轮
TypeScript CDK 身份与专用节点。Doris catalog 在私有 FE 认证通过后创建，
只读 JDBC 角色、Pod 准入策略及匿名 S3 GET 403 均经核对。
首次写入、活跃流更新和删除、DWD 成功而 DWS 故意失败、
围栏内排队订单、修复后流恢复的各阶段，
Doris 对 ODS、维表、DWD、DWS 的默认读和固定快照读
均与同轮原生 Paimon 行集一致；被删订单 2 和旧 north 聚合
也按阶段做了负向查询。初始快照的查询计划为
`NATIVE native-splits=1/1`；更新、删除后的主键表和最终四表
转为 `JNI native-splits=0/1`。这些均是不带 DV 的表，
不能用初始 native 读推断更新后的实际读路径。

DWD 批 Job 在注册快照 6→7 写出旧 north 的两条 `DELETE` 与
新 west 的两条 `INSERT`；相同输入边界下的围栏期重跑为 0 条更改。
故意失败的 DWS Job 终态为 `FAILED`，完成门返回
`NEEDS_RECONCILIATION`。隔离 DWS staging 修复后重复运行结果一致；
两条 Flink 流均从各自 savepoint 恢复，排队订单 5 和新增订单 6
最终在 ODS/DWD/DWS 与 Doris 外表得到 south **1 单 / 40**、
west **4 单 / 57**，north 不存在。最终原生快照为
ODS 4、维表 2、DWD 13、DWS 13；DWD consumer 位点 5
越过 ODS 4，DWS consumer 位点 14 越过 DWD 13。
首次最终门因 DWS 位点尚未越过 DWD 快照而拒绝，
第二次同轮 Doris 认证查询及完成门返回
`SLICE_PASS isolated-Paimon-and-Doris-external-tables-converged`。
运行分类为 `EXTERNAL_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`，
仅覆盖隔离外表查询切片。

探针与外层脚本均以退出码 0 结束。随后独立复核 #15 的
Kubernetes 对象为 0，两台原有节点 Ready，
共享 CNI 为 false/false、PolicyEndpoint 为 0；
五张隔离表和 checkpoint/savepoint 七处 S3 前缀的对象数均为 0。
本机私有证据归档于
`<证据目录>/issue15-live-20260930010356-doris-converged/`，
342 个文件逐字节核对相同。
主查询引擎的 DV/L0 读仍是 #14 的 **RED**；
历史 `INSERT OVERWRITE` 后显式重算、流恢复后重复更正、
Doris MV/内表派生结果及负载档仍 **NOT_RUN**。

## 第七轮恢复后重复回刷切片通过

固定干净源码 `200bf287580e32f14a4755e8e9e5e724f9df60c7`，
隔离运行 `20260930030938-912515` 在第六轮基础上增加
**从原 savepoint 恢复之后再次执行相同 DWD/DWS 批次**，
并重新核对原生 Paimon、认证 Doris 外表、消费者位点与完成门。
这轮运行期间 `origin/main` 因 #71/#72 合并前进；现场源码未改动，
下面结果只绑定上述运行 SHA。

故意失败的 DWS Job 终态为 `FAILED`，完成门按预期拒绝半成品；
其余 DWD/DWS 批 Job 均为 `SUCCEEDED`。
首次 DWD 更正写出 4 条变化，围栏期重跑和流恢复后重跑均为
**0 条变化**。恢复后 DWS staging、下游层和 Doris 外表
与预期集合一致，最终原生差异为空；
第一轮最终完成门即返回
`SLICE_PASS isolated-Paimon-and-Doris-external-tables-converged`。
探针将其分类为
`EXTERNAL_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`。

探针和外层会话均以退出码 0 完成清理。独立现场复核确认
Issue #15 的查询、ODS、准入对象为 0，五张隔离表与 checkpoint/savepoint
共七处 S3 前缀为空；共享 CNI 为 `false/false`、
PolicyEndpoint 为 0，四条常驻 Flink 作业 RUNNING、
两台原有节点 Ready 且 Kafka broker Ready；
临时容量栈和节点组不存在。
355 个子探针及外层会话证据文件逐字节归档于
`<证据目录>/issue15-live-20260930030938-post-resume-repeat/`。

这轮只证明隔离合成数据上的恢复后幂等重跑与 Doris **外表**
查询切片。历史 `INSERT OVERWRITE` 后的显式跨层重算、
Doris MV/内表派生结果、主查询路径的 DV/L0 **RED**、
目标负载与故障矩阵均未被本轮消除；#15 与 M1 仍为
**NOT_COMPLETE**。

## 第七轮后的代码复审与待复验范围

代码复审指出：旧版可独立调用的 `convergence_gate.py` 在收据缺少
失败注入、围栏期间排队写入或恢复后重复批次时，仍可能返回切片通过。
现已将这三类证据与恢复后 ODS 边界列为通过的必要条件，并添加缺项
拒绝测试。独立门禁还要读取同轮七个批 Job 的身份、终态和日志，
核对故意失败的 Job UID、DWS 未提交快照、被拒绝门禁回执及其摘要；
收据中的通过字段本身不能充当这些证据。旧轮原始 Job 和拒绝回执
只做了结构回放，新源码仍未现场运行。
IRSA 部署后以及直接运行探针前的校验也改为将现场角色的完整信任策略、
内联授权与附加授权逐项比对本次合成的 CDK 模板；当前旧部署
`200bf287580e32f14a4755e8e9e5e724f9df60c7` 已完成只读比对，
新源码部署仍 **NOT_RUN**。

复审还发现旧版 Doris FE 缺少持久化元数据卷。新源码为每次隔离运行
创建加密 gp3 claim，核对 PVC/PV/EBS 身份、容器实际读写挂载，并在
catalog 建立后重启 FE，要求同一卷及 catalog 恢复后才能继续。
清理按这次运行的卷身份确认删除；重启结果不确定时保留资源供核对。
外层会话另核对本轮 PVC、StorageClass、PV 引用及 NetworkPolicy
端点，任何残留均阻止网络和专用容量的自动回收。
截至第七轮收尾，这些更改仅通过本地静态与单元校验，
**现场复验 NOT_RUN**；下节记录后续精确源码复验。
第七轮结果继续严格绑定当时的 `200bf287…`，不能作为新源码的
运行通过证据。`INSERT OVERWRITE` 跨层重算、Doris MV/内表派生
及刷新失败门禁、完整批次发布契约、受影响分区并发写入与同输入
offset 重放仍 **NOT_RUN**；#15 和 M1 仍 **NOT_COMPLETE**。

## 第八轮精确源码、FE 持久卷与收敛门禁复验

固定干净源码 `5dc7af9694b7a74cce0cd7776bea7828bef8936d`，
外层会话 `20260930053142-807998` 部署了本轮 CDK 身份与
专用容量；隔离探针 `20260930053833-960071` 对部署的
IRSA 策略和本轮源码进行核对。Doris FE 重建前后保持同一
PVC UID、PV 和加密 gp3 EBS 卷，容器实际挂载可读写；
新 FE 使用原 catalog 经认证恢复，在首次尝试中通过原生
Paimon 与 Doris 外表行集核对。单有卷对象存在不构成这项通过。

活跃 DWD/DWS 流先进入 RUNNING，再围栏并执行维表历史更正。
首次 DWD 更正产生四条变更；故意失败的 DWS Job 到达
`FAILED`，完成门返回 `NEEDS_RECONCILIATION`。
修复、围栏内重复批次和两个 savepoint 恢复后，
排队订单与恢复后新订单均进入下游；恢复后再次执行 DWD/DWS
批次，最终原生行集差异为空，south 为 1 单 / 40，
west 为 4 单 / 57，north 不存在。
同轮消费者位点、批 Job 回执和经认证的 Doris 外表读回执
进入最终完成门，首次最终尝试返回
`SLICE_PASS isolated-Paimon-and-Doris-external-tables-converged`，
分类为 `EXTERNAL_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`。

子探针与外层会话均退出 0。外层先恢复共享 CNI、删除专用容量；
随后独立现场复核确认 #15 的 Kubernetes 资源与 FE EBS 卷均已删除，
五张隔离表及 checkpoint/savepoint 七处 S3 前缀为空，
CNI 两处开关为 `false/false`、PolicyEndpoint 为 0，
两台原有节点 Ready、四条常驻 Flink 作业 RUNNING、
Kafka broker Ready，专用容量栈不存在。
子探针、外层和独立复核共 412 个证据文件已逐字节核对并归档于
`<证据目录>/issue15-live-20260930053833-exact-sha/`。

这一轮只通过隔离合成数据的 Paimon 与 Doris **外表**收敛切片。
历史 `INSERT OVERWRITE` 后的跨层重算、Doris MV/内表派生结果和
刷新失败门禁、完整批次发布契约、受影响分区并发写入、
同输入 offset 重放及目标负载仍 **NOT_RUN**；
Issue #14 的直接 DV/L0 读取仍 **RED**。#15 与 M1 继续
**NOT_COMPLETE**。

## 第八轮后的门禁复审

第八轮运行结束后的复审指出：若共享 CNI 启用失败且内部回滚也失败，
外层会话原先可能只核对 PolicyEndpoint，而未再次核对 agent 与
controller 两处开关。现改为退出前无条件读取两处现场配置并要求
恢复到 `false/false`；否则保留专用容量供排查。独立完成门也增加
同轮排队订单原生读回执、恢复前拒绝门、批次位点和消费者位点校验，
缺少这些回执不能仅凭最终收据中的快照数字宣称围栏期间成功排队。
新增门禁在第八轮保存的私有回执上完成结构回放；
这些代码变更仍须下一次精确源码现场复验，不能转移第八轮
`5dc7af9` 的运行结论。

本 PoC 的写入 IRSA 限制在 `issue15_*` 表和
`flink-state/*/backfill/issue15/*`，**未细分到单次运行 ID**；
若上一轮保留了对象，下一轮相同身份在 IAM 层仍可能访问它们。
因此当前只能声明单会话隔离合成数据验证，不声明按运行 ID
强制隔离的生产授权。FE 卷在绑定前结果不确定时，清理继续
失败关闭并保留 StorageClass、CNI 和容量供人工核对；
不能把未生成 PV/EBS 的猜测当作删除完成。

## 第九轮精确源码复验

在干净源码 `c8a69e996ff3118d1db6c459f028e1acf7465cbd` 上，
外层会话 `20260930071828-1383648` 和隔离探针
`20260930072509-1776615` 均退出 0。本轮实际运行了第八轮后增加的
共享 CNI 双开关检查，以及排队订单原生行、下游快照与消费者位点、
恢复前拒绝门和最终行集的完成门校验。

Doris FE 重启沿用原 PVC UID、PV 和加密 gp3 EBS 卷；
实际挂载可读写，原 catalog 在首次认证尝试中恢复。
DWD/DWS 流启动并在维表历史更正前围栏；
故意失败的 DWS Job 达到 `FAILED`，完成门返回
`NEEDS_RECONCILIATION`。修复、围栏内重复批次及两个 savepoint
恢复后，排队订单与恢复后新订单进入下游，随后再次执行
DWD/DWS 批次。最终原生行集差异为空，经认证的 Doris 外表
读回执一致；首次最终完成门返回
`SLICE_PASS isolated-Paimon-and-Doris-external-tables-converged`，
分类为 `EXTERNAL_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`。

外层恢复共享 CNI 并删除专用容量后，独立现场复核确认 #15
Kubernetes 资源为零，CNI 两处开关为 `false/false`，
PolicyEndpoint 为零，两台原有节点 Ready、四条常驻 Flink
作业 RUNNING、Kafka broker Ready，五张隔离表和两处状态
S3 前缀为空，FE EBS 卷与专用容量栈不存在。探针、外层及
独立复核共 471 个文件已逐字节核对并私有归档于
`<证据目录>/issue15-live-20260930072509-exact-sha/`。

这一结果只证明 `c8a69e9` 的隔离合成数据 Doris **外表**
收敛切片。历史 `INSERT OVERWRITE` 后的显式跨层重算、
Doris MV/内表派生结果和刷新失败门禁、完整批次发布契约、
受影响分区并发写入、同输入 offset 重放和目标负载仍
**NOT_RUN**；Issue #14 的直接 DV/L0 读取仍 **RED**。
Issue #15 与 M1 仍 **NOT_COMPLETE**。

## 物化结果刷新与完成门

后续隔离验收沿用 `infra/m1/issue15/run-doris-convergence.sh`。
在运行独占的 Doris 内部库中创建基于 DWD 外表的异步物化视图，
按 region 聚合订单数与金额。使用 `BUILD DEFERRED REFRESH COMPLETE ON MANUAL`，
在每个更正边界显式提交 `REFRESH MATERIALIZED VIEW ... COMPLETE`；
此切片尚未验证定时刷新、查询透明改写或看板目标负载。

刷新前保存运行 ID、源码 SHA、对象 ID、查询摘要、输入业务行摘要及
既有任务 ID。只接受本次新增且属于同一物化对象的任务，
要求实际终态 `SUCCESS`、完整手动刷新标记与无错误，
并直接查询物化视图，核对原生 Paimon DWS 行集。
围栏暂停期间，历史 DWS 批任务只写独立 stage；
必须等保存点恢复后 DWS 消费 DWD 撤回记录并收敛，再核对该次物化刷新。
重复刷新必须产生新的成功任务 ID，业务行仍相同。
完成门的物化验收分支还会核对最新任务与当前 DWD/DWS 行摘要；
旧任务成功、提交成功或调用方的状态字段都不能替代当前结果验证。

验收脚本会在更新删除、历史 region 更正及恢复后故意跳过刷新，
要求旧物化行被拒绝。最后一次拒绝通过完整完成门进行：
其他原生和外表边界先通过，物化结果仍旧时必须返回
`NEEDS_RECONCILIATION materialized-view-stale`，刷新并核对后才允许
`MATERIALIZED_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`。
原有不带物化参数的完成门继续只报告外表切片，不能充当物化验收。

物化对象先于外部 catalog 清理。未知创建结果、活动刷新无法终止、
对象身份改变或运行库中出现额外对象时保留 FE 和 catalog 供核查，
不报告清理通过。刷新任务错误仅保存长度及摘要，避免复制潜在凭证。

下文第十轮和第十一轮已对这些逻辑进行固定源码现场验收。
定时看板、千万行/10 QPS/24 小时与完整故障矩阵仍未由此切片证明，
Issue #15 和 M1 继续 **NOT_COMPLETE**。

语法与任务字段依据：
[Doris 异步物化视图文档](https://doris.apache.org/docs/4.x/query-acceleration/materialized-view/async-materialized-view/functions-and-demands/)、
[Doris 4.1.4 刷新任务源码](https://github.com/apache/doris/blob/4.1.4/fe/fe-core/src/main/java/org/apache/doris/job/extensions/mtmv/MTMVTask.java)。
目标私有 Doris 4.1.4 与 Paimon 2.0.0 上的实际 SQL 和任务字段仍以实跑为准。

## 历史覆盖验收分支

`run-doris-convergence.sh --overwrite` 增加真实源表 `INSERT OVERWRITE`，
并沿用保存点围栏、独立批 Job、DWS stage、恢复后消费撤回记录及物化完成门。
源订单 1 从金额 15/v2 更正为 17/v3，历史订单 3 被删除；
源和旧 DWD/DWS 边界登记后，实际快照必须属于 `OVERWRITE`。
全量重算以登记源快照和维表快照为输入，对原 DWD 键集合产生旧行撤回和新行，
重复回刷核对当前行是否已为目标结果，边界之后的额外键保留。
已变成其他版本的受影响键会明确拒绝。

完成门额外核对覆盖 Job 的 UID、终态、输入参数、实际 SQL 与快照回执，
以及三轮 DWD 重算读取同一覆盖输入的记录。最终恢复后的固定期望为
`south: 1/40`、`west: 3/29`；实际 Doris 外表还须对已删除订单 2/3
作负向查询，物化行集和新刷新任务同样必须通过。
成功分类为 `HISTORICAL_OVERWRITE_SLICE_PASS_ISSUE15_NOT_COMPLETE`。

下文第十一轮已通过隔离合成数据的覆盖分支及实际 MV 失败恢复。
同一来源位点重放、受影响键的并发写者拒绝场景及完整负载和故障矩阵仍需后续验收；
此分支不会据 SQL Job 成功提前给出完整 Issue #15 或 M1 完成结论。

隔离验收的每个 JobManager 申请 500m CPU。先前 100m 配置在实际
Pod 上也成为 CPU 上限，保存点恢复时 SQL 规划持续数分钟；
第十一轮归档的 FlinkDeployment JobManager CPU 配置为 500m，
未保留本轮实际 Pod 上限回执，也未进行同负载改善幅度比较，
也不作为稳态吞吐或容量通过证据。

## 实际物化刷新失败分支

`run-doris-convergence.sh --overwrite --mv-failure` 在最后两次成功刷新后，
临时重命名本运行独占的外部 catalog，保存原 catalog ID 并核对新旧名字，
提交一次新的 COMPLETE/MANUAL 刷新。只有实际任务终态为 `FAILED`、
有错误内容且身份与登记任务一致，才保存失败回执。
随后恢复原 catalog 名字和同一 ID，以完整完成门确认即使物化行仍正确，
失败刷新仍返回 `NEEDS_RECONCILIATION materialized-refresh-not-verified`。
恢复后必须另有新的成功刷新、正确物化行以及真实失败任务的现场复核。

重命名和恢复均先持久化操作意图。未知 catalog 修改结果会保留 FE、
catalog 和运行资源供核查；不以一次名字查询推断未知操作已经完成。
本分支只制造隔离 catalog 暂时不可解析导致的实际刷新失败，
不代表已覆盖 S3 限流、网络断线或全部故障场景。
下文第十一轮已通过本故障切片；同一来源位点重放、并发受影响键写入和完整负载仍需验收。

## 第十轮物化查询切片通过

固定干净源码 `f58fee943d6e42a1dbf08a722caf3554c34bba5b`，
外层会话 `20260930135943-2219717`、隔离探针
`20260930140616-2235256` 在 `work/us-west-2` 使用 Doris 4.1.4
与 Paimon 2.0.0 写入端。本轮五个实际 COMPLETE 成功任务分别为：

| 刷新阶段 | 任务 ID |
| --- | --- |
| initial | 1627592724061 |
| update-delete | 1741078252806 |
| queued-replayed | 3072595038620 |
| final | 3380148419308 |
| final-repeat | 3393070113797 |

各次直接物化行集均与同轮原生 DWS 一致，最终为 south **1/40**、
west **4/57**。最终完整完成门先拒绝旧物化结果
`NEEDS_RECONCILIATION materialized-view-stale`，新刷新及重复刷新
产生不同成功任务 ID 后通过，分类为
`MATERIALIZED_QUERY_SLICE_PASS_ISSUE15_NOT_COMPLETE`。
故意失败的 DWS 批 Job、恢复前拒绝门及保存点恢复回执一并保留。
本轮没有执行真实源表 `INSERT OVERWRITE`，没有注入实际 MV 失败任务。

外层在 `2026-09-30 15:09:15 UTC` 退出 0；随后独立核验确认本轮
Kubernetes、五张表和两个状态 S3 前缀、FE EBS 及专用容量均清除，
CNI `false/false`、PolicyEndpoint 0，原节点、Flink 与 Kafka 身份未变。
633 份回执逐字节归档并复核 SHA-256：
`<证据目录>/issue15-mv-20260930140616-exact-sha/`。

## 第十一轮真实覆盖与物化失败恢复切片通过

固定干净源码 `0f0347afd42312620bb1f4acbfba704b8ab110c2`，
外层会话 `20260930151157-2364238`、隔离探针
`20260930151816-2371758` 执行 `--overwrite --mv-failure`。
这轮从 `2026-09-30 15:11:55` 到 `16:02:46 UTC`；
子探针与外层均退出 0。
使用非 DV 表、Doris 4.1.4 的 stock Paimon 1.4.2 reader 与
Paimon 2.0.0 写入/原生对照端，不能转移为 #14 的 DV/L0 通过证据。

原生源快照 **2→3** 的实际提交类型为 **OVERWRITE**，SQL 将源集替换为
订单 1 的金额 **17/v3** 与订单 4 的 **40/v1**。
DWD 重算固定读取源快照 3、维表 2、原 DWD 6：
快照 6→7 写出三条撤回/新增，更正订单 1 并删除历史订单 3；
围栏期和流恢复后的同批重跑均为 **0 条更改**。
三次 DWD 日志均保留相同 `3/OVERWRITE/2/3` 输入回执。
故意失败的 DWS Job 及恢复前门禁仍拒绝半成品；
两条流从原 savepoint 恢复后，订单 5、6 均保留。
最终源快照 5、DWD 13、DWS 12，consumer 位点分别为 6、14；
原生、认证外表及直接物化行集均为 south **1/40**、west **3/29**，
订单 2、3 和 north 不存在。

| 实际任务 | 任务 ID | 终态 |
| --- | --- | --- |
| initial | 1085739332071 | SUCCESS / COMPLETE |
| update-delete | 1197596844612 | SUCCESS / COMPLETE |
| queued-replayed | 2062827925976 | SUCCESS / COMPLETE |
| final | 2337371647516 | SUCCESS / COMPLETE |
| final-repeat | 2349324572566 | SUCCESS / COMPLETE |
| injected-failure | 2370488125678 | FAILED，54 字节错误内容 |
| failure-recovered | 2420169266187 | SUCCESS / COMPLETE |

故障通过临时重命名本轮独占的外部 catalog 触发；
恢复后 catalog ID 仍为 **1790781683106**。
完整完成门在实际 MV 行仍为正确的两行时核对最新 FAILED 任务，
返回 `NEEDS_RECONCILIATION materialized-refresh-not-verified`。
随后新成功任务与失败历史、对象身份、当前原生/物化行集一并核对，
完成门才返回
`SLICE_PASS isolated-historical-overwrite-Paimon-Doris-external-and-materialized-results-converged`。
分类为 `HISTORICAL_OVERWRITE_SLICE_PASS_ISSUE15_NOT_COMPLETE`。

自动清理后独立核验通过：本轮 Kubernetes 对象、五张表与两个状态 S3
前缀、FE EBS、专用容量栈均不存在；CNI `false/false`、PolicyEndpoint 0，
原节点、四条 RUNNING Flink 作业及 Kafka 身份不变。
657 份回执逐字节归档并复核 SHA-256：
`<证据目录>/issue15-overwrite-mv-failure-20260930151816-exact-sha/`。
原始 driver 的 `independent_cleanup=NOT_RUN` 字段保持原样；
后续独立复核的 `RESULT.json` 是清理 PASS 的依据。

两轮源码的精确 SHA GitHub Actions 均因账单或消费上限在作业启动前被拒绝，
CI 为 **NOT_STARTED_BILLING / NOT_PASS**，当时 #74 尚未取得 CI 豁免（合并前按需求方豁免处理，见结项判定）。
同来源位点重放、并发受影响键、完整批次发布契约、定时刷新、
代表性查询负载和完整故障矩阵仍未完成。
**Issue #15 与 M1 继续 NOT_COMPLETE**。

## 按表授权的 Paimon 2 JDBC 读端初始化

混合验收的实际 FE 只读角色可读取本轮维表文件，但缺失的 `warehouse`
目录标记 HEAD 返回 403。临时 IAM 实验确认，允许缺少 `max-keys` 的
ListBucket 请求虽使 HEAD 返回 404，也允许未传 max-keys 的全桶列表；
该方案被拒绝，原角色策略已恢复。实际 FE 中的 Paimon 2.0.0
`FileIO.listStatus(warehouse)` 在原策略下成功返回 ODS、DWD、DWS
三个数据库目录，`exists(warehouse)` 则仍报 AccessDenied。

`ScopedReadOnlyJdbcCatalogFactory` 注册为 `cove-readonly-jdbc`。它要求
本轮专用 `cove_issue15_reader_RUN`、固定 JDBC catalog key、加密 RDS
连接和 M1 warehouse 身份，先实际列出并核对上述三个目录，再交给
Paimon 的 JDBC catalog。它不调用 warehouse `exists` 或 `mkdirs`。
完整 Paimon factory 链仍执行权限系统检查。限定的只读 FileIO 对
本 warehouse 直属 `user.sys`、`privilege.sys` 用每次实际父目录列表
判断存在性；列表失败时拒绝初始化，目录存在时仍执行原权限表读取
及授权检查，不把 HEAD 403 当成权限系统未启用，也不缓存缺失结果。
其余路径的存在性和读取交给原 FileIO，写入、删除、建目录及重命名
在适配层被拒绝，IAM 和 JDBC 只读授权仍须独立验证。
实际 S3 数据范围和 SQL 读权限仍须核对 IAM、数据库授权与独立读回；
运行名称校验或目录列表不能替代这些门禁。

`COVE_PAIMON_READER_VARIANT=paimon2-scoped` 的 `catalog-plan` 保持
`paimon.catalog.type=jdbc`，通过 `paimon.metastore=cove-readonly-jdbc`
选择 SPI，并传入 `paimon.cove.reader-run`。部署前须把经审查的工厂
jar 纳入成对 FE/BE reader 镜像，固定源码与镜像 digest，不能只更新
catalog 属性就声称工厂已经加载。

公开 Java CLI 接受 stdin 的非秘密 JSON 运行选项并输出结构化判定：

```bash
java -cp "$REVIEWED_READER_CLASSPATH" \
  com.covelake.m1.ScopedReadOnlyJdbcCatalogFactory --validate < runtime-plan.json
```

清单必须包含 `metastore`、`warehouse`、`catalog-key`、`uri`、
`cove.reader-run`、`jdbc.user` 六个字符串字段。CLI 拒绝额外凭据字段、
写角色、错运行、错仓库及未加密连接，校验期间不访问 S3 或 JDBC。
工厂实际初始化、Doris 四表读回、动态 MV、持续负载和故障门禁
尚须单独验证（本节写于结项前，当时 Issue #15 为 NOT_COMPLETE）。
该工厂不在本 issue 的接收证据内，实际初始化见 #16 的基线报告。

> 注：本文中的 issue 与 PR 编号（如 #15）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
