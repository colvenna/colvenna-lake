# M1 批处理引擎与托管边界

本页对应 Issue #17。
目标是在固定的 Paimon 2.0.0、Flink 1.20.4、JDBC catalog 和真实 S3
上，确定首版历史更正、append 行级修正及定时维护的执行路径。
下表区分发布文档支持与本项目实测；单表通过不代表上下游收敛或生产容量通过。

| 任务 | 首版执行路径 | 发布文档依据 | 当前实测 |
| --- | --- | --- | --- |
| 主键表历史更新、删除 | Flink 批模式 `UPDATE` / `DELETE`，限制历史分区和写入窗口 | Paimon 2.0.0 Flink SQL 写入文档限定主键表、deduplicate 或适用的 partial-update | **PASS，单表**：更新 `100→125`、删除另一主键后，原生 S3 批读只见最终 1 行；重复更新结果不变 |
| append 表行级更正 | 按稳定 event ID 将受影响分区读入隔离暂存表，再以 `INSERT OVERWRITE` 整分区替换 | Flink SQL 写入文档支持分区 `INSERT OVERWRITE`，不提供 append 表直接 `UPDATE` / `DELETE` | **PASS，单分区**：删除事件 11、修正事件 12 的金额，覆盖后原生行集正确；重复覆盖不变 |
| 历史分区整体回刷 | Flink 批模式 `INSERT OVERWRITE`，围栏活跃流写并显式重算下游 | Flink SQL 写入文档支持静态及动态分区覆盖 | **受限**：上述单分区覆盖已通过；活跃流并行、跨层完成信号由 #15 验证 |
| 小文件合并 | Flink 批模式 `CALL sys.compact`，指定目标表和分区，并启用 `table.dml-sync=true` | Paimon procedure 在同步模式等待作业终态；append 默认至少 5 个输入文件才生成任务 | **PASS，单表**：快照 7→8，当前数据文件 5→1，6 行不变 |
| 快照过期和孤儿清理 | 独立 Flink 维护作业，先核对 reader/tag/consumer 引用与 dry-run | Flink procedure 文档列出 `expire_snapshots` 和 `remove_orphan_files` | **受限**：#12 已在独立私有表完成保留与清理实测；本次 #17 未重复执行 |
| 日终打 tag | Flink 批模式 `CALL sys.create_tag`，由独立 Kubernetes Job 提交；含有时限删除的 PII 表禁止长期 tag | Paimon procedure 文档列出 `create_tag`；tag 会保留所指快照 | **NOT_RUN**：#17 只固定执行路径，tag 与保留引用须在 M3 的任务入口验收 |
| 批量质量、报表和通知 | 外部编排器提交独立 Job；批量扫描首选 Flink 批模式，报表与通知沿用调用方工具 | 这是托管边界的设计选择，不构成具体框架兼容性验证 | **NOT_RUN**：批量质量由 M4 #38 验收；此处不宣称已有数据质量框架 |

文档依据固定在 Paimon 2.0.0 发布提交：
[Flink SQL 写入](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/flink/sql-write.mdx)
和 [Flink procedures](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/flink/procedures.md)。
[Spark SQL 写入](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/spark/sql-write.md)
另提供 `MERGE INTO` 以及更直接的行级更新、删除，但引入第二套运行时、
JDBC 锁配置和作业托管边界；它是候选增量能力，尚不能替代 Flink 路径的实测。

## 私有 AWS 实测

最终正式运行使用已提交源码 `486980363bc6db8965bf0d19ca5d5864c3482094`、
AWS `work` / `us-west-2`、私有 EKS Job 与真实 S3。证据保存在忽略目录
`infra/m1/evidence/issue17-batch-20260929102032-2241686/`；角色由
`CoveLakeM1Issue17Batch` TypeScript CDK 栈创建，栈绑定同一源码
`486980363bc6db8965bf0d19ca5d5864c3482094`。每个 Job 的
manifest、终态和原生
Paimon 快照/数据文件核对均留证。

更正 Job 对主键表完成 `INSERT`、历史 `UPDATE`、`DELETE` 和重复
`UPDATE`，最终快照 4 仅有 `id=1, amount=125`。append 表先有
事件 10、11、12，再经隔离暂存表覆盖受影响分区，最终删除 11、
将 12 的金额修正为 130；重复同一覆盖不改变行集。随后分四次加入
事件 13–16，形成默认 compaction 阈值所需的 5 个当前文件。
维护 Job 以同步 `CALL sys.compact` 把文件降至 1 个，前后 6 行完全相同。
更正快照的 `commitUser` 带 `issue17_correct_20260929102032_2241686`，
维护快照带独立的 `issue17_maintain_20260929102032_2241686` 前缀。
每个受检快照都对照实际 S3 manifest 中的数据文件路径与大小；仅有
Kubernetes Job `succeeded=1` 不作为正确性证据。

已存在对象的匿名 S3 GET 返回 403，临时 Service/Ingress 为 0。
IAM 模拟对其他表数据文件的 `GetObject` 和 `PutObject` 均为 `implicitDeny`；
它不能代替 M2 按表凭证的真实跨表拒绝测试。
清理后 `pk`、`append`、`stage` 三个表的 S3 前缀对象数均为 0，
本次 Job/Pod、ConfigMap、服务账号均无剩余。
该私有 ECR 基础镜像扫描虽为 `COMPLETE`，仍有 27 个 Critical、
83 个 High；本结论仅适用于内部合成数据 PoC。

前三次失败运行保留为 **RED**：
`issue17-batch-20260929091553-984088/` 在 `UPDATE/DELETE` 后把主键
批读的 `UPDATE_AFTER` 可见行误判为非 INSERT；
`issue17-batch-20260929092210-1385696/` 已通过更正和覆盖，
但 `CALL sys.compact` 默认异步返回 JobID，立刻读到旧快照，
当时仅有 4 个当前文件，低于发布版默认的 5 文件任务阈值。
`issue17-batch-20260929101648-2078172/` 收窄 `ListBucket`
到单表前缀后，在 Flink catalog 对 warehouse 根路径的
`getFileStatus` 收到 S3 403；它没有执行更正或维护。
三次失败均不得计作维护通过；对应隔离表及临时资源事后核对已清零。

## 首版执行与托管决定

首版使用 **Flink 批模式 + 有终态的 Kubernetes Job** 提交上述有限批任务。
流式 CDC/DWD/DWS 仍由 Flink Kubernetes Operator 的
`FlinkDeployment` 托管。外部编排器只触发批任务并读取 Job 终态；
平台的完成信号还必须等待快照提交、原生行集核对和受影响下游收敛。
Airflow 的 `FlinkKubernetesSensor` 只看 CR 状态，不能把
`FlinkDeployment READY` 用作批任务完成条件。

首版不安装 Spark，也不选 Spark Operator。若 #15/#16 证明
Flink 的分区覆盖、并发围栏或容量不足，才单独验证 Spark
与相同 JDBC 锁及 S3 权限边界，再选择 Apache 或 Kubeflow Spark
Operator。当前测试没有覆盖活跃流写入时的同分区冲突、完整历史回刷、
跨 ODS/DWD/DWS 与 Doris 的结果收敛；这些仍分别由 #15/#16 验收。

## 验证方法

在私有 AWS `work` 账号 `<ACCOUNT_ID>`、`us-west-2` 中，创建一张
本次运行独有的合成主键表与一张分区 append 表。用 Flink 批模式完成
一次历史金额更正和一次 append 事件删除或修正，记录输入快照、
输出快照及目标行集；重复同一更正后最终行集须不变。
对 append 表连续提交小文件，再执行一次批模式 compaction，
核对当前数据文件数下降而业务行集不变。批作业的完成判定须同时有
作业终态、快照提交和原生 S3 逐行对账；单独的 Job 成功不构成通过。

测试身份的 IAM 角色由 TypeScript CDK 创建，对象写入仅允许
`warehouse/ods.db/issue17_*` 隔离表前缀。对象读取还包括
`warehouse/`、`warehouse/ods.db/` 等精确目录标记对象和
`user.sys`、`privilege.sys` 的精确标记对象；这些不授予其他表的数据文件读取。
Paimon 2.0.0 在 Flink catalog 初始化时会检查 warehouse 根路径，
因此 IAM 的 `ListBucket` 还允许 `warehouse`、`warehouse/` 等目录前缀；
这会暴露其他表的对象名称，即使内容仍不可读。本测试只用于隔离合成数据，
不能当作 M2 的严格按表凭证验收。
作业使用私有 EKS 节点、独立服务账号，不创建公开页面、Ingress、
LoadBalancer 或 NodePort。若生成服务，必须为 `ClusterIP`；
对已确认存在的合成 S3 对象做匿名 GET 并要求 403。运行结束须清理
隔离表和临时 Kubernetes 资源，并确认对应 S3 前缀清零。
2026-09-29 核对的私有 ECR 基础镜像扫描为 `COMPLETE`，仍有 27 个
Critical、83 个 High 漏洞；只准用于内部合成数据 PoC，
生产发布须先解决镜像风险。

跨 ODS/DWD/DWS 与 Doris 派生结果的更正完成信号由 #15 验证，
本 issue 的单表批处理实验不能代替该接收条件。

> 注：本文中的 issue 与 PR 编号（如 #17）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
