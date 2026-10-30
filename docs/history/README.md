# 开发记录

> 本文记录的是当时的情况：产品当时名为 cove-lake，技术标识以 cove 开头；2026-10-24/25 改为 Colvenna Lake 与 colvenna（#258、#271）。

cove-lake 按里程碑 M0–M5 开发。本目录保留各里程碑的验收报告与基线报告，作为开发过程的记录：报告按当时的写法保留，描述的是当时的代码、环境与命名。

- 报告引用的代码（`infra/m1`–`infra/m5`、M1 的实验探针、M2 的 JDBC → REST 切换演练等）在 git tag `history/m1-m5`（在早期私有仓库的 tag `history/m1-m5` 下） 下；报告里的代码链接都指向该 tag。主干上的对应代码已按产品组件重组到 `deploy/` 与 `tests/`，资源也改为按组件命名。
- 报告里的环境是开发用的 PoC 环境（栈 `CoveLakeM1Poc`、`CoveLakeM2Catalog`、`CoveLakeM3`–`CoveLakeM5`，命名空间 `cove-m3` 等），由 tag 下的脚本管理，主干的安装脚本不再管理它。
- 报告里维护者的部署值已换成占位符（#248）：工作账号写作 `<ACCOUNT_ID>`，托管区的域名写作 `<COVE_HOSTED_ZONE_NAME>`，执行机的实例角色写作 `<执行身份>`。各报告里同一个占位符指同一个值；具体的值只记在维护者未提交的本地设置里。
- 当前的设计以 [HLD](../design/hld-realtime-lakehouse.md)、各 LLD 与[验收契约](../design/acceptance-contract.md)为准。

## 里程碑

| 里程碑 | 交付目标 | 退出标准 |
| --- | --- | --- |
| **M0** 设计基线 | HLD、架构图、PR / FAQ、带版本依据的能力矩阵及授权、SLO、回刷、删除契约 | [M0 退出核对](m0/exit-review.md)：Issue #1–#5 文档口径与阻断关口逐项对应；不含 M1/M2 运行通过 |
| **M1** PoC | Paimon 2.0.0 / Flink 1.20.4 / Flink CDC 3.5.0 / Doris 4.1.4 在真实 EKS + S3 上验证 CDC 主键表、事件流 append 表及独立维护；JDBC catalog 作脚手架 | 共享 JDBC 锁下并发提交零快照丢失；Doris 4.1.4 正确读取 Paimon 2.0.0 DV 表；采集延迟、小文件与 S3 限流基线 |
| **M2** 控制平面 | Java 21 + Maven 实现对齐 Paimon 2.0.0 REST 契约的 cove-catalog MVP（config / database / table / commit / token、bearer、自有管理面的服务身份库表授权）替换 JDBC；以 Helm chart 部署 | Flink/可选 Spark 经 REST 写、Doris 4.1.4 经 REST 读同一表；原子提交、通用 bearer、显式开启的按表凭证与跨表拒绝均实测；全新集群 30 分钟内可写可查 |
| **M3** 声明式接入 | Java 21 + Java Operator SDK 实现 `LakeSource` / `LakeTable` CRD 与 cove-lake Operator；Go 实现薄 `cove` CLI，经 Kubernetes API 提交与查看声明、触发批任务 | 新增一个源库或一个 topic 只需一个 YAML；治理准入由 Operator 核对 SQL 实际上游表与声明 |
| **M4** 多云与开放 | 第二朵云的 S3 兼容存储回归与凭证下发适配；Iceberg 兼容发布；上游 Management API 正式发布且引擎验证后再评估行列级策略与 entity label | 同一套配置跨云通过集成测试 |
| **M5** 管理控制台与 BI 查询 | 经认证的公网 Web 入口与统一登录（Cognito、公网 ALB）；基于 Apache Superset 的 BI 查询界面；管理控制台的流水线全景与受控操作 | 浏览器经同一次登录使用 BI 与控制台，未认证的请求一律被拒；BI 用户以自己的 Doris 身份查询；控制台读到的状态与 kubectl、Prometheus、Marquez 一致，操作与 CLI 一致并留下审计 |

## 验收与基线报告

| 报告 | 内容 |
| --- | --- |
| [M1 PoC 环境手册](m1/poc-environment.md) | 开发用 PoC 环境（`CoveLakeM1Poc` 等栈）的配置、健康检查与公网拒绝测试；这套环境由 tag `history/m1-m5` 下的脚本管理 |
| [M0 退出核对](m0/exit-review.md) | Issue #1–#5 的设计证据与后续运行关口，明确未运行项 |
| [M1 容量、延迟与故障基线](m1/capacity-baseline.md) | 24 小时 CDC / Kafka 吞吐、各链路分位延迟、查询耗时、写入扩容、故障恢复、S3 请求与未通过项 |
| [M2 cove-catalog 在 EKS 上的部署与引擎验收](m2/catalog-on-eks.md) | Flink、Spark、Doris 经 REST 的读写流程；token 的有效、过期、撤销与越权；按表数据凭证的跨表拒绝与续期 |
| [M2 原子快照提交与故障恢复](m2/commit-recovery.md) | 多个写入方与维护任务经 cove-catalog 提交同一张表，在服务实例切换、元数据后端中断与全部副本失效下的提交结果与文件引用核对 |
| [M2 目录授权与审计](m2/authorization-on-eks.md) | 服务身份在目录查询、数据读取、写入与管理操作上的允许与拒绝，以及这些请求的审计 |
| [M2 从 JDBC catalog 切换到 cove-catalog](m2/jdbc-to-rest-switch.md) | M1 的表与 CDC 作业切到 REST 的顺序、切换前后的一致性核对，以及回退 |
| [M2 控制平面的安装、检查与恢复](m2/control-plane-operations.md) | 全新起点的首次安装计时、健康检查、单实例故障与元数据后端恢复、升级与回退、开发环境的可选 Kafka |
| [M3 声明式 CDC 接入](m3/declarative-cdc.md) | `LakeSource` 在 EKS 上的验收：准入与拒绝、新表与 schema 变化、升级与故障下的逐行与 changelog 对账 |
| [M3 声明式 Kafka 事件源](m3/kafka-events.md) | Kafka `LakeSource` 在 EKS 上的验收：连接与消费状态、无效与迟到事件、口令失效与断线、升级与重建后每条记录恰好一次 |
| [M3 MongoDB CDC](m3/mongodb-cdc.md) | MongoDB `LakeSource` 在 EKS 上的验收：准入与拒绝、oplog 窗口、作业启动后的新集合（`AddedAfterStart`）与新字段、成员与 TaskManager 丢失、升级、删除与替换集合，以及与源端的逐文档与 changelog 对账 |
| [M3 Kafka 承载的 CDC](m3/kafka-cdc.md) | MySQL 源从 binlog 切到用户 Kafka（Debezium JSON）的验收：切换顺序与起点、重放与格式错误的记录、采集器重新快照与作业重建后，ODS、DWD 与源端的逐行与 changelog 对账 |
| [M3 声明式分层表](m3/layered-tables.md) | `LakeTable` 在 EKS 上的验收：无效 SQL 与依赖的拒绝、savepoint 升级与失败回退、暂停与故障、DWD/DWS 与源端的逐行与 changelog 对账 |
| [M3 批任务与维护任务](m3/lake-tasks.md) | `LakeTask` 在 EKS 上的验收：Airflow 调度器经 Kubernetes API 触发并判断结果、重复请求、压缩与 tag、按表排队、快照过期与挂起的读端、带栅栏的回刷及其重试与下游收敛 |
| [M3 Flink CDC 3.7 兼容性评估](m3/flink-cdc-3.7.md) | CDC action 依赖升级与 YAML Pipeline 分别评估：新增源表 failover 丢数（FLINK-40741）的复现、PostgreSQL 叶子分区、二进制列、跨版本恢复、EKS 上的整套验收，以及 pipeline 经 cove-catalog REST 的运行与治理边界 |
| [M3 保留策略与跨层个人数据删除](m3/governance-erasure.md) | 分类与保留声明、只发布受治理的表、有界保留，以及跨源库、ODS、DWD、历史、文件与作业状态的主体删除：逻辑与物理状态、证据与逾期报告 |
| [M3 监控、告警与数据质量](m3/monitoring.md) | 按源、表、作业的延迟、积压、提交、维护积压与查询新鲜度，口径与 M0 一致（心跳与独立标记行对照）；错误数据进隔离表；延迟违约与坏数据的告警；只经认证访问 |
| [M3 Doris 看板服务](m3/dashboards.md) | `LakeDashboard` 在 EKS 上的验收：平台的 Doris 按查询清单以外表视图、物化视图、内表服务看板；10 QPS 看板负载的响应与新鲜度对照 M0；BE、FE 丢失后的恢复；用户级授权、拒绝与查询审计；与 Paimon 的逐行一致 |
| [M4 批量数据质量校验与发布门禁](m4/batch-quality.md) | `LakeQuality` 与 `check` 任务在 EKS 上的验收：按快照与分区的可复现校验、失败行的定位、门禁对读端与看板发布的拦截、受准入策略约束的豁免，以及门禁每次变化的审计记录 |
| [M4 血缘与目录发现](m4/lineage.md) | Operator 发的 OpenLineage 血缘在 EKS 上的验收：从 MySQL 到 ODS、DWD、DWS 与 Doris 视图的表级路径，作业版本对应 FlinkDeployment 的 spec 哈希与 Flink job，Marquez 的 schema 与 cove-catalog 一致，覆盖矩阵与没有匿名入口 |
| [M4 开放层：Iceberg 兼容发布](m4/iceberg.md) | `LakeIcebergPublication` 在 EKS 上的验收：写入端在表目录里写 Iceberg 元数据、不访问目录，Operator 核对后登记到 Polaris 的 EXTERNAL 目录；append 表的 Iceberg 视图与 Paimon 一致，主键表的视图在全量压缩后一致（之前等于 `$ro`）；加列、tag、删行、L3 撤下与 Polaris 宕机后的发布行为；读端写不了、越不过表级授权，没有匿名入口。补验：写 Iceberg 元数据的表的个人数据删除，以及持续写入时不需要压缩任务、由写入端自己的压缩保持新鲜的分层表 |
| [M4 已有 Trino 的接入边界](m4/trino.md) | 以 Trino 484 替身代表用户已有的 Trino，在 EKS 上验收它经 Iceberg 连接器读开放层的 append 表与主键表：与 Paimon 原生读取一致，认证、对象存储与授权各自的拒绝测试；原生 Paimon 连接器不支持，Trino 不进默认安装 |
| [M4 列标签、脱敏与行过滤](m4/governance.md) | 列标签、Operator 生成的脱敏视图与 Doris 行策略在 EKS 上的验收（只在 Doris 看板这条读路径执行）：用户看到脱敏后的列与按策略过滤的行（行过滤不当作保密边界）；湖表、系统表、文件类表函数与直接存储都被拒绝；加列默认脱敏，标签冲突时停止发布；支持矩阵 |
| [M5 Web 入口与统一登录](m5/entry.md) | 公网入口在 AWS 上的验收：未认证的请求一律被拒、口令加 TOTP 的登录与组到角色的映射、伪造的身份与绕过 ALB 都不行、登出与停用与会话到期后访问结束、按用户追溯的审计、暴露面与 HLD 一致 |
| [M5 BI 查询界面](m5/bi.md) | Superset 在入口后面的验收：每个人以自己的 Doris 用户查询，SQL Lab、图表与导出读到的与直连 Doris 一致（脱敏、行过滤）；越权被 Doris 拒绝；没有 `information_schema` 也能浏览；没有跨人的结果；Doris 审计按人；去掉登录名、登出与删除看板后访问结束 |
| [M5 管理控制台：流水线全景](m5/pipeline.md) | 控制台在入口后面的验收：从源到 Doris 看板每个环节的状态、指标、告警与流向，与 kubectl、Prometheus、Marquez 读到的一致；注入坏数据、挂起作业与数据源时，告警触发的同一时段标出出问题的环节；不露 Secret 与表数据、只有 GET、身份只读；按角色可见 |
| [M5 管理控制台：受控操作](m5/operations.md) | 控制台的操作在 AWS 上的验收：每个操作与 cove CLI 做出同样的声明、得到 Operator 同样的判定；没有角色的、运维直接调用管理员的操作都被拒，Kubernetes 再拒一次；删除、回刷、过期快照、清理孤儿文件与合规删除先列出影响、取消不改任何东西；控制台与集群的审计都记下本人，主体的键值不进审计 |
| [M1 Doris 正式版 Paimon 2.x reader 升级核验](m1/doris-paimon-reader-upgrade.md) | Doris 官方镜像能否换用 Paimon 2.x reader 的源码与镜像核验 |
| [M1 StarRocks 读取 Paimon 2.0.0 的回退评估](m1/starrocks-paimon-fallback.md) | 未启动的 StarRocks 4.1.3 对照方案及其门禁 |

> 注：本文中的 issue 与 PR 编号（如 #258）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
