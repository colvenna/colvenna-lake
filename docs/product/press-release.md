---
title: "Colvenna Lake Press Release & FAQ"
type: working-backwards
status: draft
date: 2026-10-19
---

# Press Release

> 以下为 working-backwards 写法的预期发布稿。文中数字均为产品目标，每处目标都以「目标」标注；已有实测的，在旁注明出处。

## Colvenna Lake 发布：在你自己的 Kubernetes 和 S3 上搭起实时湖仓

**新的开源平台把 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/) 与 [Apache Doris](https://doris.apache.org/) 打包成开箱即用的实时湖仓，数据库变更与事件流分钟级入湖、直接可查。**

**2027 年 3 月 —** 今天，Colvenna Lake 项目发布 1.0 版本。Colvenna Lake 是一个面向 Kubernetes 与 S3 兼容对象存储的开源实时湖仓平台。数据平台团队用 Helm chart 与安装脚本就能在 Amazon EKS 上部署完整的实时湖仓；用一个 YAML 声明一个 MySQL 库或一个 [Apache Kafka](https://kafka.apache.org/) topic，数据就会以分钟级延迟进入湖内，并立刻可以用 SQL 查询；compaction、快照过期等小文件治理以声明式的维护任务执行。

### 问题

越来越多的团队希望把"实时管道"和"分析数仓"合二为一：业务数据库的每一次更新、应用的每一条埋点，都应该在几分钟内出现在同一份可查询的数据里，而不是先进 Kafka、再进数仓、再同步一份到湖里。开放表格式的出现让这件事在技术上成为可能，但落地成本极高。一个团队通常要自己拼装表格式、流计算、消息队列、元数据服务、查询引擎和运维作业，并在生产中逐一踩坑：对象存储上快照提交不是原子的，多写者不加锁会静默丢数据；写入作业内嵌 compaction 会拖垮写入，小文件失控；主键表的合并语义、乱序处理、changelog 生成方式互相制约，选错一个下游就读到错误结果；查询引擎与表格式的版本依赖需要反复联调。换一朵云，以上全部重来。结果是大多数团队要么放弃实时，要么维持两套系统。

### 解决方案

Colvenna Lake 把这些决策固化为默认值，把运维做成声明式的任务，把部署做成 Helm chart 与安装脚本。

- **两类接入，一个声明。** `LakeSource` 资源描述一个 CDC 源（MySQL、PostgreSQL、MongoDB；MySQL 也可改从 Kafka 上的 Debezium 事件接入）或一个事件流 topic。平台自动创建 Paimon 表、生成并托管 Flink 作业，并在源库加列时自动演进表结构。CDC 进主键表，事件流进 append 表，各自带上经验证的默认配置。
- **一份数据，流批同源。** 湖内每一层都是 Paimon 表，常规 CDC 变更可由下游流作业消费。同一张表也由 Doris 直接查询；历史覆盖和 append 行级更正会触发受影响下游与看板结果的显式重算。
- **内置的控制平面。** Colvenna Lake 自带 Paimon REST catalog 服务 colvenna-catalog，即同一系列的独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog)，每套环境都装它的发布版：服务端原子提交快照，按表签发临时对象存储凭证，并审计作业身份。终端用户的列/行权限由经验证的查询引擎策略执行；未经验证的敏感数据读入口默认关闭。直读 binlog / WAL 的 CDC 与事件流写入作业只负责写；compaction、快照过期、孤儿文件清理与打 tag 是声明式的 `LakeTask`，由你的编排器或 `colvenna` CLI 触发，按表排队执行。
- **开箱可查。** 平台的 Doris 直读湖内的 Paimon 文件，按 `LakeDashboard` 声明的查询以外表视图、物化视图或内表服务看板；分析师在 [Apache Superset](https://superset.apache.org/) 查询界面里以自己的 Doris 身份查询，脱敏与行过滤照常生效。需要时声明一个 `LakeIcebergPublication`，就把表以 [Apache Iceberg](https://iceberg.apache.org/) 元数据只读发布，让 [Apache Spark](https://spark.apache.org/)、Trino 等外部引擎读同一批文件。
- **开放的组件栈。** 计算全部运行在 Kubernetes 上，存储通过 S3 接口访问；表格式、流计算与查询引擎都是开源组件。

### 目标（1.0）

| 指标 | 目标 |
| --- | --- |
| 部署 | 全新 EKS 集群 30 分钟内完成部署并可写可查 |
| 接入 | 新增一个源库或 topic 只需一个 YAML，无需编写 Flink 作业 |
| 延迟 | 稳态基线负载下，源库事务提交到 Doris ODS 外表可查 p95 ≤5 分钟、p99 ≤10 分钟；DWS 看板 p95 ≤15 分钟；M1 的 24 小时基线实测分别为 p95 1.70 分钟、p99 2.29 分钟与 p95 2.59 分钟（[M1 基线](../history/m1/capacity-baseline.md)） |
| 正确性 | 多写者并发提交零快照丢失，由集成测试持续验证 |
| 运维 | 小文件数、compaction 积压均有默认看板与告警 |

### 引言

"实时湖仓的每一个组件都已经开源了，缺的是把它们正确地拼在一起的那一层。Colvenna Lake 把我们在调研和验证中得到的每一个'必须这样配'固化成默认值，让团队第一天就能站在正确的起点上。" —— Colvenna Lake 项目负责人

"我们之前维护着 Kafka 到数仓、数仓到数据湖两条链路，同一份订单数据存了三份。用 Colvenna Lake 之后，CDC 直接进湖，下游流作业从湖里继续消费，看板查的也是同一张表。" —— 早期用户，某电商数据平台负责人（预期引言）

### 开始使用

访问 <https://github.com/colvenna/colvenna-lake> 获取安装说明、HLD 与示例。用 `deploy/install.sh` 部署平台（其中 Colvenna Lake 自有的组件是 `deploy/helm/` 下的 Helm chart），用 `colvenna apply -f my-source.yaml` 声明第一个数据源。

---

<!-- markdownlint-disable-next-line MD025 -- PR/FAQ 由 Press Release 与 FAQ 两个一级部分组成 -->
# FAQ

## 面向用户

**Q：Colvenna Lake 和直接用 Paimon 有什么区别？**
Paimon 是表格式与存储层。Colvenna Lake 在它之上补了三样 Paimon 自己没有的东西：一个可部署的 REST catalog 服务（Paimon 官方只有客户端和契约，没有服务端），把服务身份授权、临时凭证、原子提交和审计放进控制平面（终端用户的列/行权限仍由经验证的查询引擎执行）；一个 Kubernetes Operator，把 Flink 作业、表配置和维护作业变成声明式资源；以及 Doris 看板服务与治理基线。你仍然直接使用 Paimon 表，只是不用自己搭和自己配。

**Q：为什么要自己实现 catalog 服务？**
因为服务身份授权、临时凭证下发、服务端原子提交和审计字段需要一个可部署的 REST catalog 服务端。JDBC catalog 可以用共享锁保证多写者提交，也可以配合 S3 IAM 限制对象访问，但没有 Paimon 服务端权限模型或按表凭证。控制平面是这个产品的差异化；终端用户的列/行权限仍须由各查询引擎验证。

**Q：为什么选 Paimon 而不是 Iceberg 或 Apache Hudi？**
因为实时是核心场景。Iceberg 官方文档写明 Flink 流读不支持 CDC，Spark 增量读只包含 append 快照；Hudi 的 Flink changelog 是 best-effort。只有 Paimon 提供带前镜像的完整变更流和内建的 merge engine。如果你的核心读端是 Snowflake、BigQuery 等云数仓、且只需要分钟级 append，Iceberg 是更合适的选择，Colvenna Lake 不打算覆盖这个场景。

**Q：必须用 AWS 吗？**
目前只在 Amazon EKS 加 S3 上验证过。组件都以 Kubernetes 原生方式部署，存储走 S3 接口，设计上刻意避开了 Glue 作为元数据目录、Athena 作为主查询引擎、EMR 与 Managed Flink 等云专有组件。但按表凭证由 AWS STS 签发，作业身份用 IRSA，Web 入口用 ALB 与 Cognito；第二朵云的存储与身份适配（#33）尚未开展，跨云迁移未验证。

**Q：Colvenna Lake 会替我部署 Kafka 吗？**
生产环境不会。Kafka 只在事件流链路中充当采集缓冲，CDC 链路默认直连源库，层间队列由 Paimon 表承担。你把已有的 Amazon MSK、Confluent 或自建集群的地址与认证信息写进 `LakeSource`，平台负责消费。开发与 PoC 可以用 `tests/fixtures/kafka/colvenna-dev-kafka` 在已有的 Strimzi operator 上装一个单节点 Kafka，它不用于生产。

**Q：CDC 需要先经过 Kafka 吗？**
默认不需要。CDC 作业直连源库，Paimon 表本身承担层间队列。当同一份 binlog 有多个异构消费方，或需要独立于源库 binlog 保留期的回放缓冲时，再在源库与平台之间加 Kafka：MySQL 源改从你的 Kafka 读 Debezium JSON 事件，平台侧只在声明里加上 `transport.kafka`。其他数据库目前不能经 Kafka 接入，Oracle、SQL Server、Db2 不在支持范围内。

**Q：端到端延迟能到多少？**
在 M0 定义的基线负载下，设计目标是源库变更到 ODS 已提交快照 p95 ≤2 分钟，到 Doris ODS 外表可查 p95 ≤5 分钟、p99 ≤10 分钟；经过 DWD/DWS 和物化视图的看板 p95 ≤15 分钟。checkpoint、维护、查询缓存与刷新都会计入。固定看板查询本身的 p95 目标是 3 秒；任意 ad-hoc 查询不承诺秒级。M1 的 24 小时基线实测：源库到 Doris ODS 外表 p95/p99 1.70/2.29 分钟，到 DWS 看板 p95 2.59 分钟，固定看板查询 p95 2.5 毫秒；结论限于[报告](../history/m1/capacity-baseline.md)中的版本、配置、负载与查询模板。

**Q：schema 变更怎么处理？**
源库加列、字符串加长、数值类型加宽会自动同步。新表先落进只有本源可读的接入库；声明了 Owner、级别与保留期的源，新表就绪后按表级权限发布给声明的读端，未分类的表不发布。新列在 Doris 看板上按表的级别默认脱敏，在 Iceberg 开放层要复核后才发布。删列时保留原列，改名视为新增列，主键与分区键不会自动迁移。不兼容变更（如 `DECIMAL→VARCHAR`）让这张表失败并移出作业、发出告警，同一个源的其他表继续运行，不会静默忽略。

**Q：需要 Apache Airflow 之类的调度器吗？**
分层加工是常驻的 Flink 流作业，由平台以 Kubernetes 自定义资源托管，层与层之间靠数据依赖自动流动，不需要调度器。需要调度的是批处理：历史回刷、每日快照过期、打 tag、数据质量校验。平台不内置调度器，而是提供 `colvenna backfill`、`colvenna maintain`、`colvenna check` 等命令，也可以直接经 Kubernetes API 建 `LakeTask`，供你在已有的 Airflow 或 [Apache DolphinScheduler](https://dolphinscheduler.apache.org/) 中调用；M3 以 Airflow 2.10 验收，DolphinScheduler 未验证。事件流表的分区完成信号（`_SUCCESS` 文件，由 Airflow 以 S3 sensor 等待）可以直接触发下游批任务。

**Q：需要 Spark 吗？**
不需要，平台也不部署 Spark。回刷、compaction、快照过期由 Flink 批模式的 `LakeTask` 完成，GDPR 删除由 `LakeErasure` 编排：源库的行由用户删除，Operator 以 Flink 批任务压缩、过期并逐副本核对。Spark 3.5.8 只在 M2 作为经 REST 读写的验证客户端、在 M4 作为 Iceberg 开放层的外部读端验收过。你自己的 Spark 要写湖表时，必须与 Flink 经同一个 colvenna-catalog 提交，不能绕过它直写对象存储。

**Q：权限和 GDPR 删除怎么做？**
colvenna-catalog 认证作业和查询引擎服务身份，按表授权操作和临时对象凭证；表前缀凭证不能隔离同一文件内的列或行。每个源和派生表在声明里写 Owner、L1–L4 级别、个人数据类别、保留期与主体键，列标签写在 `governance.columns`；未分类的表按临时 L4 处理、不发布。下游不得低于上游的最高级别，目前不提供降级路径。Doris 看板这条读路径上，Operator 按列标签生成脱敏视图与行策略，加列默认脱敏，湖表、系统表、文件类表函数与直接存储访问都被拒绝（[M4 验收](../history/m4/governance.md)）；其他读入口不做列级控制，含个人数据的替代读入口默认关闭。合规删除（`LakeErasure`）登记并核对源库（只核对，不替你删）、Paimon 各层的当前行、历史与文件、平台 Doris 的看板副本、Iceberg 元数据、Flink 状态和对象存储的版本设置，分别报告“受管入口逻辑不可查”和“受管存储物理不可恢复”；事件流 append 表与 MongoDB 源的表目前不在删除范围内，列为待负责人复核。期限默认逻辑 24 小时、物理 9 天：M3 验收中逻辑删除在受理后 1,458 秒完成，9 天的物理期限尚未端到端跑完，只验证了逾期时如实报告残留，所以物理期限还不作为已实现的 SLA；读端另存的副本与你的 Kafka topic 由其所有者删除并出具回执。

**Q：能用现有的 Doris 或 Trino 吗？**
Doris 由平台部署：看板服务需要配套的 Paimon 2.0.0 读取器（Doris 4.1.4 自带的 1.4.2 读取器读 DV 表不可用），把平台的 catalog 接到你已有的 Doris 集群没有验证。Trino 不在默认栈：Trino 独有的容错执行并不支持 Paimon 连接器。如果你已有 Trino 或 BI 层只接 Trino 协议，你的 Trino 以 Iceberg 连接器读平台以 Iceberg 兼容模式发布的表：只读，授权到 Trino 的目录身份为止，不按 Trino 用户继承；Paimon 的原生 Trino 连接器不在支持范围内（原因见 [Trino 调研](../research/trino.md)）。这和接入已有 Kafka 是同一类边界。

## 面向内部

**Q：为什么快照提交必须经过 colvenna-catalog？**
Paimon 的 S3 实现沿用"写临时文件再 rename"提交快照，S3 的 rename 不是原子操作；Paimon 源码注释明确对象存储上需要额外的保护。只要一张表有两个写入方，包括官方推荐的"写入作业 + 维护作业"组合，不加保护就会有静默覆盖的风险。Paimon 给了两条路：REST catalog 的服务端提交，或 Apache Hive / JDBC catalog 的共享锁。我们选服务端提交，因为同一个服务还能承担服务身份授权和按表临时凭证；JDBC catalog 加锁只在 M1 PoC 中用作脚手架，平台不提供退回 JDBC 锁的轻量模式。这是数据正确性问题，不能交给用户选择。

**Q：为什么不用 Glue 或现成的 Iceberg catalog？**
Glue 通过 Hive 兼容客户端接入时，锁调用直接抛不支持异常，也不是多云的。[Apache Polaris](https://polaris.apache.org/)、Lakekeeper、Unity Catalog、[Apache Gravitino](https://gravitino.apache.org/) 的 REST 服务端都只实现 Iceberg 契约，不实现 Paimon 契约，只能作为 Iceberg 兼容发布的目标。colvenna-catalog 的后端选 PostgreSQL，因为每朵云都有托管服务，也能在集群内运行。

**Q：最大的技术风险是什么？**
两个。一是 colvenna-catalog 的工程量与契约漂移：M2 对齐 Paimon 2.0.0 正式 REST Catalog 契约，服务身份授权由 colvenna-catalog 自有管理面登记；上游 REST Management API 只见于 master，不能作为正式版列 grant。二是跨组件的已发布能力不完全重合：Doris 4.1.4 内置 Paimon 1.4.2 reader，M1 实测它读 Paimon 2.0.0 DV 表为 RED、不可用，改用在官方镜像上自建的配套 Paimon 2.0.0 读取器后结项（[Doris 结项判定](../history/m1/doris-paimon.md#结项判定)）；M2 验收了通用 bearer 与默认关闭的按表凭证路径。版本与阻断关口见 [M0 能力矩阵](../research/version-capability.md)；未通过的路径不能用在敏感表。M1 结项不启动 StarRocks 4.1.3 对照：它的发布文档没有 JDBC/REST Paimon 路径，不能自动代替。

**Q：为什么控制台到 M5 才做？**
Helm chart 与安装脚本覆盖安装，CLI 与 CRD 覆盖接入和运维的操作，控制台是可观测与易用性的增量。所以先把正确性做实，控制台放在 M5：它与 Superset BI 查询界面共用一个经 Cognito 认证的公网入口和一次登录；流水线全景只读，受控操作与 `colvenna` CLI 做出同样的声明，以受限的 Kubernetes 用户提交并带上发起人本人，控制台与集群各留一份审计。

**Q：什么情况下这个产品不应该存在？**
如果 Paimon 官方或某家云厂商推出同等能力的托管服务且支持多云，或 Iceberg 在流式 CDC 读上补齐能力并被主流引擎支持，Colvenna Lake 的差异化会大幅缩小。项目应每半年重新评估这两个前提。

## 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Spark, Spark, Apache Superset, Superset, Apache Hudi, Hudi, Apache Hive, Hive, Apache Airflow, Airflow, Apache DolphinScheduler, DolphinScheduler, Apache Polaris, Polaris, Apache Gravitino and Gravitino are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；平台的查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见[商标与命名](../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #33）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
