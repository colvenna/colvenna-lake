---
title: "M1 PostgreSQL CDC 单表私有验证"
date: 2026-09-28
status: private-poc-validated
issue: 9
---

# M1 PostgreSQL CDC 单表私有验证

本工作对应 Issue #9。固定使用 Paimon 2.0.0、Flink 1.20.4、Flink CDC 3.5.0；目标是把私有 PostgreSQL 分区主键表的初始行及 insert/update/delete 同步到 S3 上的 Paimon ODS，并检查完整前后镜像、schema 变更、断线恢复及 WAL 保留边界。Paimon 2.0.0 的 [`PostgresSyncTableAction`](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-cdc/src/main/java/org/apache/paimon/flink/action/cdc/postgres/PostgresSyncTableAction.java) 只支持目标单表；本 Issue 不承诺 PostgreSQL 整库自动发现。

## 安全与资源边界

TypeScript CDK 栈 `CoveLakeM1PostgresSource` 单独创建 RDS PostgreSQL 16.13、参数组、Secrets Manager 凭据、私有 ECR 仓库与 CloudWatch 复制槽告警。源库复用 #6 catalog 已有、经实际子网校验的 DB subnet group；账号的 60 个 DB subnet group 配额已满，首次创建新组失败并回滚。源库位于现有 VPC 的两条隔离子网，存储加密，`PubliclyAccessible=false`，安全组仅接受 EKS 安全组的 TCP 5432；`rds.force_ssl=1`，客户端要求 TLS。管理员与受限 CDC 用户分开。所有 Pod、FlinkDeployment 在创建前通过私有清单检查，REST 仅为 ClusterIP，没有 Service 外部 IP、Ingress 或 hostPort；密码通过 Secret 环境变量传入，Flink `job.args` 不含密码。任何后来需要公开的页面或资源必须先设计、验证身份认证和授权，否则保持关闭。

2026-09-28 的隔离 schema 测试发现 Flink CDC 在 INFO 日志中输出了包含 CDC 明文密码的连接器配置，属于安全门禁失败。立即停止了两个 PostgreSQL FlinkDeployment 并删除其 Pod，轮换 RDS 数据库角色密码、Secrets Manager 和 Kubernetes Secret，使用新凭据通过私有 TLS 登录。原密码已在数据库侧失效。历史日志不得公开或复制到提交文件；忽略目录 `infra/m1/evidence/issue9-secret-log-incident-20260928212818/` 记录停机和轮换操作。后续部署对 Log4j 根日志与 CDC/Debezium 连接器设为 WARN，并在启动后检查真实 Pod 的配置和日志；这项修复必须用新镜像和运行时重新验证，不能把轮换当成日志修复通过。

当前写入与查询节点组共用 EKS 安全组，因此 RDS 网络规则无法区分两组节点。源库仍需 TLS 和数据库身份验证，但**尚不满足 HLD §12.1 的“仅写入节点”生产网络边界**。基础镜像的漏洞也必须在生产前解除。此工作只用于私有合成数据 PoC。

CDK 参数要求 `rds.logical_replication=1`、`wal_level=logical`、10 个复制槽、25 个 WAL sender、复制槽 WAL 上限 128 MiB。首次创建时参数写为 10 个 WAL sender，RDS 事件明确把它调整为 25；实际配置已按该下限修正。CloudWatch 对 `OldestReplicationSlotLag` 设 64 MiB 阈值；本地 doctor 同时检查实际槽状态与剩余 `safe_wal_size`。指标缺失不能当成安全：告警把缺失数据视为非违例，doctor 必须独立拒绝缺失槽。CloudWatch 告警状态是当前 PoC 的告警信号，尚未配置外部通知订阅。[AWS 逻辑复制前提](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.FeatureSupport.LogicalReplication.html)、[RDS 指标定义](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)。

## 可重放路径

1. 在 AWS profile `work`、`us-west-2`、账号 `<ACCOUNT_ID>` 的干净提交上运行 `bash infra/m1/issue9/deploy-source.sh`。脚本检查现有 #6 VPC/EKS、两条隔离子网和路由，CDK 部署后再核对真实 RDS、5432 安全组、参数组和 CloudWatch 告警。不同账号、非私有资源或源码 SHA 不一致时停止。
2. 运行 `source-client.sh bootstrap`，先创建带主键的分区父子表、父子表的 `REPLICA IDENTITY FULL`、行版本触发器及 `publish_via_partition_root=false` 的预建 publication，再授予 `cove_pgcdc` 复制角色和两张叶表的 SELECT。Paimon 2.0.0 只从 JDBC `TABLE` 类型枚举源表，PG JDBC 42.7.8 将分区父表报告为 `PARTITIONED TABLE`；所以固定匹配两张叶表并合并到一张 ODS，publication 也用叶表事件身份，不能声称自动发现任意新分区。`query-prereqs` 实际核对逻辑复制、已建立 TLS、禁用 TLS 时的明确拒绝、发布范围和源端版本约束；`seed` 后生成跨两个分区的初始合成行。CDC 源连接器固定 `pgoutput`、预建 publication、单独复制槽，详见 [Paimon 发布源码](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-cdc/src/main/java/org/apache/paimon/flink/action/cdc/postgres/PostgresActionUtils.java)、[PG JDBC 发布源码](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.8/pgjdbc/src/main/java/org/postgresql/jdbc/PgDatabaseMetaData.java) 和 [Flink CDC 3.5.0 源码](https://github.com/apache/flink-cdc/blob/d15c5de8a74d244aea5cd59640543cf5438ee877/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-postgres-cdc/src/main/java/org/apache/flink/cdc/connectors/postgres/source/config/PostgresSourceConfigFactory.java)。
3. 运行 `build-image.sh` 和 `deploy-cdc-job.sh`。镜像以基础 Flink 的不可变 digest 构建，加入固定 SHA-256 的 PostgreSQL CDC/Avro/Jackson 依赖，推送私有 ECR 且必须有完成的漏洞扫描。Flink 作业只写 `ods.issue9_orders`，要求私有 S3 checkpoint、私有 REST、名称限定的 RBAC；部署前后还要确认现有 MySQL CDC 作业健康。PoC 资源使用 50m CPU request、500m limit 的 JobManager/TaskManager；此配置不是容量结论。
4. 使用 `source-client.sh query`、`inspect-ods.sh pg_orders` 与 `verify-state.py` 核对初始快照的真实 S3 数据文件。记下目标快照号，运行 `mutate`，再次对账，再用 `inspect-ods.sh --changelog pg_orders <起点> <终点>`、`verify-changelog.py` 检查 insert、update 的 DELETE/INSERT 前后镜像和 delete 四条精确记录。`reject-regress`、`reject-repeat` 必须被源库触发器以 SQLSTATE `23514` 拒绝，不能靠 CDC 静默跳过已提交非法事件。
5. 运行 `schema-add`，核对新增列及其值。兼容和不兼容变更的隔离测试在 `schema-probe.sh start` 创建的独立私有表、publication、复制槽及作业上进行；探针按已核对的写入节点余量使用 25m+25m CPU request、1 GiB+1 GiB 内存 request，一次只启用一个临时客户端。先核对初始行与 checkpoint，再测试 `VARCHAR(64→128)` 后 ODS 的文本容量至少 128、源端实际写入 96 字符及真实 changelog、重复版本拒收，最后把隔离表主键改为 `VARCHAR(16)` 并提交一条变更事件。仅当 `schema-probe.sh observe` 捕获准确的 schema 错误、同因 Kubernetes Warning 且主作业仍 RUNNING 时通过；测试后运行 `schema-probe.sh cleanup` 删除隔离作业、授权、表、槽和 ODS 对象。当前没有外部通知订阅。
6. 在主源表已完成新增列后运行 `reboot-source.sh`，记录私有 RDS 重启、故障后 Flink S3 checkpoint、新插入事件与源/ODS 行集追平。`wal-probe.sh` 使用独立合成槽和表把保留余量降至告警阈值，要求实际槽状态、CloudWatch 指标数据点与 ALARM 一致，然后清理测试槽和表；不得用源库 `available` 或 Pod Ready 代替业务对账。
7. 运行 #6 的 `infra/m1/scripts/security.sh` 验证匿名 EKS/S3 访问拒绝、无公开 Service/Ingress/主机端口/云资源及 IRSA 越权拒绝；另核对 #9 源库仍为私有、加密且仅有受限 5432 规则。

所有密码、原始行和完整审计载荷只留在忽略的 `infra/m1/evidence/` 或 Secret 中。源端 `seed`、`mutate`、`schema-add`、`post-reconnect`、隔离测试与 WAL 测试是一次性合成场景，不应在同一个源库上盲目重放。

## 验证状态

TypeScript CDK 类型检查与静态基础设施测试、Java 11 编译、Spotless、Error Prone、PostgreSQL CDC guard 单元测试已在本地通过。提交 `f6287b3` 已部署私有 RDS、ECR 镜像和 CDC 作业，源/ODS 初始及变更后行集、四条真实 S3 changelog、新增列、非单调版本源端拒收已经核对。隔离 schema 探针的旧 `VARCHAR(64→128)` 精确列宽断言为 RED；直接读取真实 S3 元数据证实目标从 `VARCHAR(64)` 演进为 `STRING`，可容纳 128 字符，但旧断言只接受恰好 `VARCHAR(128)`。原 RED 见 `infra/m1/evidence/issue9-probe-widen-20260928212339/`，两版 S3 元数据摘要存于其中。`f2550a5` 修正后完成新探针的私有 checkpoint、源/ODS 基线、文本容量至少 128、96 字符变更写入、源/ODS 最终行集和四条真实 S3 changelog 核对。不兼容 `INT→VARCHAR(16)` 后隔离作业 FAILED，Operator 发出同因 Warning，但前置校验误报为 `POSTGRES_REPLICA_IDENTITY_INCOMPLETE`，因此原自动验收保持 RED；记录于 `infra/m1/evidence/issue9-schema-probe-observe-20260928220008-2120308/`，需以准确的类型错误重跑。`67e18e1` 在密码轮换后更新了私有 CDK 栈、镜像和 CDC 作业，真实 Pod 日志配置与凭据扫描通过，主作业的私有 S3 checkpoint、3 行源/ODS 与新增列值对账通过。#6 安全检查 `infra/m1/evidence/security-0928213939/` 再次确认 EKS/S3 匿名访问拒绝及无公网 Service、Ingress、节点端口和负载均衡。镜像扫描完成但有 27 Critical、83 High，生产发布阻断。GitHub CI 和合并后 CI 尚未完成；GitHub Actions 当前因账户账单限制在运行 job 前失败。每项必须绑定实际源码 SHA、镜像 digest 和私有入口证据，不能由本地构建推断通过。

`9909afb` 的隔离测试已用相同源码 SHA、镜像和私有作业重跑：`issue9-schema-probe-start-20260928220921-2134121/` 有 checkpoint 与日志安全检查，初始源/ODS 各 1 行、快照 3；`issue9-ods-inspect-20260928221521-2152176/` 验证目标文本容量至少 128，`issue9-ods-inspect-20260928221711-2162882/` 从真实 S3 读取快照 3→6 的 insert、96 字符 update 前后像及 delete 共 4 条，最终源/ODS 仍各 1 行。不兼容主键类型事件让隔离作业 FAILED，`issue9-schema-probe-observe-20260928221851-2168692/` 验证准确的 `POSTGRES_INCOMPATIBLE_ID_TYPE`、同因 Operator Warning、保存日志无密码和主作业继续 RUNNING；`issue9-schema-probe-cleanup-20260928221911-2176798/` 清理了隔离资源。

`issue9-rds-reboot-20260928222208/` 记录私有 RDS 重启、加密与非公网状态、故障后 checkpoint、新事件和源/ODS 4 行追平；`issue9-ods-inspect-20260928222508-2212961/` 进一步验证快照 12→15 只有 1 条重连后 INSERT、无重复。第一次 WAL 探针在创建测试槽前因本地检查器把 PostgreSQL `active::text` 的 `true` 误读成 `t` 而 RED，记录于 `issue9-wal-probe-20260928222537/`；真实主槽为 active/reserved。WAL 告警测试必须在修正后重新运行。

`98a10b9` 的第二次 WAL 探针在 `issue9-wal-probe-20260928223522/` 留下另一项 RED：受限四批写入使 CloudWatch `OldestReplicationSlotLag` 超过 64 MiB，并在 22:39 UTC 转入 ALARM；但 PostgreSQL `safe_wal_size` 是剩余安全余量（当时仍约 2 GiB），不能用它判断 64 MiB 的“滞后”阈值。脚本在四批边界内停止并清理测试槽/表；主槽仍 active/reserved，主 Flink 作业 RUNNING。修正后的只读七字段槽查询与四个回归测试通过，`issue9-source-client-20260928224232-2306639/` 标记为 `PROBE_ONLY`，不能代替新提交 SHA 的告警验收。

`be3ff73` 的受限 WAL 探针在 `issue9-wal-probe-20260928224932/` 验证：隔离槽第二批后滞后 100,108,760 字节、CloudWatch 同期最大指标同为 100,108,760 字节，告警于 22:54 UTC 转入 ALARM；主槽当时 active/reserved、剩余安全余量约 2.18 GB，主 Flink 作业 RUNNING。测试表/槽清理后，主槽滞后回到 56 字节，告警于 22:57 UTC 自然回到 OK。`issue9-source-client-20260928225716-2350340/` 与 `issue9-ods-inspect-20260928225734-2351351/` 的源/ODS 4 行对账通过。#6 的 `infra/m1/evidence/security-0928225822/` 再次验证匿名 EKS/S3 拒绝、无公开 Kubernetes/负载均衡入口及 IRSA 越权拒绝。镜像漏洞、写入节点专属网络隔离和外部告警通知仍阻断生产；GitHub CI 的实际交付状态见下文。

## 私有 PoC 交付结论

Issue #9 已在真实私有 S3 上核对 PostgreSQL 分区叶表的初始与增量
行集、删除、四条变更前后镜像、兼容 schema 演进、
不兼容主键变更的失败及 Operator Warning、
RDS 重启后的新 checkpoint 与业务追平、
独立复制槽的 CloudWatch 指标及 ALARM。
源端逻辑复制、TLS、publication、复制槽和 WAL 上限均有可复核检查。
私有运行中发现的 CDC 密码日志泄漏已停止作业、轮换凭据，
并用新镜像检查运行配置及日志；原始事件仍须保留为历史安全失败。
PR #55
已合并，审核的源提交为 `c18a70e8b5614e2c4b81f1dc7319b97dd45bb9cd`，
合并提交为 `bf6d7278b7552fd55df1ade1d232cff54d75ea75`。
GitHub Actions 因账号账单限制在作业开始前失败；
请求方豁免了本轮 GitHub CI 合并门禁，其状态为
`WAIVED_BY_REQUESTER_NOT_PASS`，不能记作测试通过。

早期 schema 断言和 WAL 探针的 RED 仍保留，以后续准确的
私有实测作为本 PoC 结论。此版本固定匹配已知分区叶表，
不承诺任意新分区自动发现。镜像扫描仍有 27 项 Critical、
83 项 High，写入与查询节点共用安全组，
外部告警通知也未建立；此结论仅适用于短期、私有、仅合成数据的
CDC PoC。生产安全准入和 M1 端到端性能仍未完成。

> 注：本文中的 issue 与 PR 编号（如 #9）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
