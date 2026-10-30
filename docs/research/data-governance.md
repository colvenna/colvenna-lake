---
title: "Colvenna Lake 数据治理调研"
date: 2026-09-28
status: draft
sources:
  - https://paimon.apache.org/docs/master/concepts/rest/management-api/
  - https://github.com/apache/paimon (master 分支 docs/docs/*.md|mdx、docs/generated/*.html、paimon-api CoreOptions.java)
  - https://github.com/apache/paimon-trino
  - https://github.com/apache/doris-website (docs/admin-manual/auth/**、audit-plugin.md、lakehouse/catalogs/paimon-catalog.mdx、data-governance/data-lineage.md、key-features/*.mdx)
  - https://trino.io/docs/current/security/built-in-system-access-control.html
  - https://trino.io/docs/current/security/file-system-access-control.html
  - https://trino.io/docs/current/security/opa-access-control.html
  - https://trino.io/docs/current/security/ranger-access-control.html
  - https://trino.io/docs/current/admin/event-listeners-http.html
  - https://trino.io/docs/current/admin/event-listeners-openlineage.html
  - https://github.com/apache/ranger
  - https://github.com/apache/gravitino (docs/lakehouse-paimon-catalog.md、security/*.md、iceberg-rest-service.md、lineage/lineage.md、flink-connector/*.md、spark-connector/*.md)
  - https://polaris.apache.org/ ; https://polaris.apache.org/in-dev/unreleased/generic-table/
  - https://docs.unitycatalog.io/ ; https://docs.unitycatalog.io/usage/tables/formats/
  - https://docs.lakekeeper.io/
  - https://docs.datahub.com/integrations
  - https://docs.open-metadata.org/latest/connectors
  - https://nightlies.apache.org/flink/flink-docs-stable/docs/internals/data_lineage/
  - https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/job_status_listener/
  - https://cwiki.apache.org/confluence/spaces/FLINK/pages/255070913/FLIP-314+Support+Customized+Job+Lineage+Listener
  - https://openlineage.io/docs/integrations/flink/flink2 ; https://openlineage.io/docs/integrations/spark/
  - https://docs.greatexpectations.io/docs/core/connect_to_data/sql_data/ ; https://docs.greatexpectations.io/docs/core/connect_to_data/dataframes/
  - https://docs.soda.io/sitemap.md
  - https://github.com/awslabs/deequ
  - https://docs.aws.amazon.com/AmazonS3/latest/userguide/ (serv-side-encryption、object-lock、object-lifecycle-mgmt、lifecycle-transition-general-considerations、storage-class-intro、logging-with-S3、cloudtrail-logging、ServerLogs、lifecycle-and-other-bucket-config、example-bucket-policies)
  - https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html
---

# Colvenna Lake 数据治理调研

> 调研时的比较基线为 Paimon + JDBC catalog/PostgreSQL + S3 + Flink + Doris/Trino；第 10 节是当时按 JDBC 路径给出的建议，**不是当前产品承诺**。项目已决定在 M1 用 JDBC 验证数据平面、M2 自建 REST colvenna-catalog，当前权限与删除边界以 [HLD 第 12 节](../design/hld-realtime-lakehouse.md#12-安全与数据治理)及 [M0 验收契约](../design/acceptance-contract.md)为准。本文保留一手机制出处与未核实项；`paimon.apache.org/docs/master/...` 对应 2026-09-28 的上游 master 页面。

## 1. 访问控制与权限

### 1.1 Paimon 自身

- **JDBC catalog 没有权限模型**。`catalog_configuration.html` 与 `jdbc_catalog_configuration.html` 中只有 cache、lock、`catalog-key`、`lock-key-max-length` 等选项，无任何 principal/grant 相关项。[来源: https://github.com/apache/paimon/blob/master/docs/generated/catalog_configuration.html; https://github.com/apache/paimon/blob/master/docs/generated/jdbc_catalog_configuration.html] 官方 Security 页明确："the primary trust and authorization boundaries are enforced by the surrounding catalog, engine, service, operator configuration, and storage-level authorization rather than by Paimon alone"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/project/security.md]
- **REST Management API（实验性，契约 1.0，OpenAPI 3.1）**提供实体 label、对象权限、row filter、column mask。文档写明 "`RESTCatalog` exposes `labelManagement()`, `permissionManagement()`, and `policyManagement()` ... Other catalog implementations do not expose this management contract"。权限模型：资源类型 `CATALOG`/`CATALOG_ALL`/`DATABASE`/`DATABASE_ALL`/`TABLE`/`COLUMN`/`FUNCTION`/`VIEW`；访问项 `ALL`/`CREATEDATABASE`/`DESCRIBE`/`ALTER`/`DROP`/`CREATETABLE`/`CREATEFUNCTION`/`CREATEVIEW`/`LIST`/`SELECT`/`UPDATE`/`GRANT`；列权限支持 allowlist/denylist；row filter 是序列化的 Paimon `Predicate`，column mask 是序列化的 `Transform`（非 SQL 文本，客户端/服务端 Paimon 版本须兼容）；策略只能挂在表上，要求表 `query-auth.enabled=true`，冲突或不兼容时 fail closed；principal 是服务端定义的不透明字符串；认证、成员解析、持久化、审计日志都是 "REST server concerns"。[来源: https://paimon.apache.org/docs/master/concepts/rest/management-api/]
- Spark 的 `grant_permission`/`revoke_permission`/`list_permissions`/`create_policy`/`drop_policy`/`list_policies` 在 procedures 页归入 "REST catalog access management"，全部指向 Management API。[来源: https://github.com/apache/paimon/blob/master/docs/docs/spark/procedures.md] `query-auth.enabled` 的定义是 "Enable query auth to give Catalog the opportunity to perform column level and row level permission validation on queries"，开启后 `files`、`file_key_ranges`、`binlog`、`statistics` 系统表不可用。[来源: https://github.com/apache/paimon/blob/master/docs/generated/core_configuration.html; https://paimon.apache.org/docs/master/concepts/system-tables/]
- 推断：JDBC catalog 下这些 procedure 没有服务端可调用，等价于不可用；官方也未列出任何开源 Paimon REST 服务端（见已有报告 13.2）。

### 1.2 Doris

- RBAC 仿 MySQL，权限层级 Global `*.*.*` / Catalog `ctl.*.*` / Database / Table / Column（`GRANT Select_priv(col1,col2) ON ctl.db.tbl`，列权限仅 `Select_priv`）；Row Policy 自 1.2 起 `CREATE ROW POLICY ... AS RESTRICTIVE|PERMISSIVE TO user|ROLE role USING (...)`，`RESTRICTIVE` 之间 AND、`PERMISSIVE` 之间 OR；Data Masking 自 2.1.2 起 "currently only configurable via Apache Ranger"；三者对 `root`/`admin` 均不生效。权限项为 `Node_priv`/`Admin_priv`/`Grant_priv`/`Select_priv`/`Load_priv`/`Alter_priv`/`Create_priv`/`Drop_priv`/`Usage_priv`/`Show_view_priv`，其中 `Select_priv` 可到列级，`Load_priv` 覆盖 Insert/Delete；2.1.2 起授权者除 `Grant_priv` 外还须自身持有被授予的权限。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/auth/authentication-and-authorization.md; https://github.com/apache/doris-website/blob/master/docs/admin-manual/auth/authorization/data.md; https://github.com/apache/doris-website/blob/master/docs/sql-manual/sql-statements/data-governance/CREATE-ROW-POLICY.md]
- **对外部 catalog**：GRANT 路径中的 `catalog_name` 可为任意 catalog；Ranger-Doris 文档的示例全部使用外部 Hive catalog（`grant select_priv on hive.tpch.user`、列权限 `select_priv(name,age) on hive.tpch.user`），并说明每个外部 catalog 可用 `access_controller.class` 选择独立的授权器（如 `ranger-hive`）。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/auth/authorization/ranger.md; https://github.com/apache/doris-website/blob/master/docs/key-features/pluggable-auth.mdx] Row Policy 能否建在 Paimon 外部表上，文档未明示（见末节）。
- **Ranger 集成**：FE 配置 `access_controller_type=ranger-doris`；行过滤与脱敏需 Doris 2.1.3+；插件 jar 从 SelectDB 对象存储下载、servicedef 取自 `morningman/ranger` 的 `doris-plugin` 分支，**不在 apache/ranger 主仓**。[来源: 同上 ranger.md] LDAP 认证支持 group→role 映射与默认角色，LDAPS 需 4.0.5+；`authentication_type`/`authentication_chain` 可插拔（含 OIDC/JWT 插件），官方示例用 `authentication_type=ldap` + `authentication_chain=default` 保留本地应急账号。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/auth/authentication/ldap.md; pluggable-auth.mdx]
- Doris Paimon catalog：`paimon.catalog.type=jdbc` 自 4.1.0 支持；读 Deletion Vector；Time Travel 与系统表自 3.1.0（含 `$audit_log`、`$snapshots`、`$schemas` 等，不支持全局 `sys.*` 表）。[来源: https://github.com/apache/doris-website/blob/master/docs/lakehouse/catalogs/paimon-catalog.mdx]

### 1.3 Trino

- 系统级 access control "enforces authorization at a global level, before any connector level authorization"，内置 `default`/`allow-all`/`read-only`/`file`/`opa`/`ranger`，可用 `access-control.config-files` 叠加多套（任一拒绝即拒绝）。[来源: https://trino.io/docs/current/security/built-in-system-access-control.html]
- `file`：表级 `privileges`、列级 `allow`/`mask`、`filter` 行过滤，`security.refresh-period` 热加载；限制："These rules do not apply to system-defined tables in the `information_schema` schema"，且 "does not support privileges for table procedures and therefore all are effectively allowed"——即 Paimon 的 `sys.*` procedure 无法用 file 规则限制。[来源: https://trino.io/docs/current/security/file-system-access-control.html] `opa`：`opa.policy.uri` 必填，`row-filters-uri`/`column-masking-uri` 可选，支持 batch；每列仅一个 mask；grant/revoke 类操作 "no request is sent to OPA"，只由 `opa.allow-permission-management-operations` 一刀切；非 batch 模式下宽表逐列请求 "can result in a performance degradation"。[来源: https://trino.io/docs/current/security/opa-access-control.html] `ranger`："Apache Ranger 2.5.0 and greater include the required Trino service definition"，支持 column-masking、row-filtering、audit logging；用户须在 Ranger 中获得 Query ID `*` 的 `execute` 与对自身的 `impersonate` 授权，否则 "users are not able to execute any query"。[来源: https://trino.io/docs/current/security/ranger-access-control.html]
- Paimon connector 不在 Trino 官方文档中（`/connector/paimon.html` 返回 404），由 `apache/paimon-trino` 单独发布，main 分支 `trino.version=476`、`paimon.version=1.3.1`；源码将 catalog properties 直接封装为 Paimon `Options` 并经 `CatalogFactory` 建 catalog。[来源: https://github.com/apache/paimon-trino/blob/main/pom.xml; .../TrinoModule.java; .../catalog/TrinoCatalog.java] 推断：`metastore=jdbc` 可透传；系统级 access control 与 connector 无关，file/OPA/Ranger 对 Paimon 表同样生效。

### 1.4 Apache Ranger 插件现状

`apache/ranger` master 顶层含 `plugin-trino`、`plugin-presto` 等模块，**无 Doris、Paimon 插件**。[来源: https://github.com/apache/ranger] Doris 插件由 Doris 社区在外部分支维护（见 1.2）；Paimon 无 Ranger 插件，Gravitino 的 Ranger `HadoopSQL` pushdown 覆盖 "Hive, Iceberg, and Paimon catalogs"，但 "Spark reaches these catalogs through the Kyuubi authorization plugin. That plugin cannot push updates or deletes for a Paimon catalog"。[来源: https://github.com/apache/gravitino/blob/main/docs/security/authorization-pushdown.md]

### 1.5 结论

JDBC catalog 下，Paimon 层零权限；可执行的落点只有：(a) 查询引擎层——Doris RBAC + Row Policy（脱敏需 Ranger），Trino file/OPA/Ranger；(b) 存储层——S3 IAM/bucket policy 可按 prefix 授权（"you can control access to groups of objects that begin with a common prefix"），Flink/Spark 作业通过 EKS IRSA 获取最小权限凭证。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-bucket-policies.html; https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html] 推断：任何直接持有 S3 读权限与 JDBC 连接串的进程可绕过引擎层策略读到全部数据，因此 1.0 必须按库/域拆 S3 prefix 与 IAM 角色，把引擎层策略视为"便利层"而非唯一防线。

## 2. 统一元数据与目录

- **Gravitino**：Paimon catalog "Builds with Apache Paimon `1.2`"，作为 `FilesystemCatalog`/`JdbcCatalog`/`HiveCatalog` 的代理（`catalog-backend=jdbc` 需 `jdbc-user/password/driver`），`rest` 后端仅作为阿里云 DLF 的客户端；支持 schema/table/view DDL、列 comment、nullability、properties，不支持 dropTable（只 purgeTable）。[来源: https://github.com/apache/gravitino/blob/main/docs/lakehouse-paimon-catalog.md] 仓库模块只有 `iceberg`（Iceberg REST server，"Apache Iceberg 1.11 REST API"、S3 credential vending、OAuth2）与 `lance` REST 服务，**没有 Paimon REST 服务端**；Iceberg REST server "Managed table type: Iceberg table only"。[来源: https://github.com/apache/gravitino; https://github.com/apache/gravitino/blob/main/docs/iceberg-rest-service.md] 引擎侧：Flink connector 仅 1.18–1.20（不支持 `CALL`/`UPDATE`/`DELETE`），Spark connector 仅 3.5 且 "Only supports Paimon FilesystemCatalog on HDFS now"。[来源: https://github.com/apache/gravitino/blob/main/docs/flink-connector/flink-catalog-paimon.md; .../spark-connector/spark-catalog-paimon.md] 自带 tag（示例 `pii`）、policy（示例 `retention_30d`）、metalake 级 RBAC 与 `DENY`。[来源: https://github.com/apache/gravitino/blob/main/docs/manage-tags-in-gravitino.md; .../manage-policies-in-gravitino.md; .../security/access-control.md] 推断：Gravitino 可作 Paimon JDBC catalog 之上的"元数据/标签/策略登记簿"，但因 Flink 2.x 连接器缺失且 Spark 连接器不支持 JDBC 后端，1.0 写链路无法经其强制执行。
- **Polaris**："an open-source, fully-featured catalog for Apache Iceberg"；Generic Table 为 beta，字段 `format` 示例仅 delta/csv，"There is no commit coordination provided by Polaris"、"do not support credential vending"。[来源: https://polaris.apache.org/; https://polaris.apache.org/in-dev/unreleased/generic-table/] **Unity Catalog OSS**：`--format` 取值 DELTA/PARQUET/ORC/JSON/CSV/AVRO/TEXT，Iceberg 经 UniForm/REST，无 Paimon。[来源: https://docs.unitycatalog.io/usage/tables/formats/] **Lakekeeper**：Iceberg REST catalog + generic tables（Lance/Delta/Parquet），OpenFGA 授权与 Trino OPA bridge，无 Paimon。[来源: https://docs.lakekeeper.io/] 三者只能承接 Paimon 的 Iceberg 兼容发布。
- **DataHub**：无 Paimon source；Doris（Alpha）、Trino（GA）、Iceberg（Beta）、Flink（Beta）、OpenLineage（GA）。[来源: https://docs.datahub.com/integrations] **OpenMetadata**：无 Paimon、无 Iceberg；Doris（PROD）、Trino、Flink、OpenLineage（Pipeline）。[来源: https://docs.open-metadata.org/latest/connectors] 推断：两者只能通过 Doris/Trino 连接器"看见"Paimon 表（作为外部 catalog 的表），或经 OpenLineage 接收血缘。
- **Paimon 自身元数据**：`$snapshots`、`$schemas`（`fields`/`partition_keys`/`primary_keys`/`options`/`comment`/`update_time`）、`$options`、`$audit_log`、`$binlog`、`$partitions`、`$files`、`$tags`、`$branches`、`$consumers`、`$manifests`、`$statistics`、`$table_indexes` 及全局 `sys.tables`/`sys.partitions`；`$partitions` 的 `created_by`/`updated_by` 与 `sys.tables` 的 `owner`/`created_by` "are populated from REST catalog audit information and are `NULL` for non-REST catalogs"。[来源: https://paimon.apache.org/docs/master/concepts/system-tables/] 治理上最有用的组合：`$snapshots JOIN $schemas ON schema_id` 追溯任一版本的字段；`$consumers.next_snapshot_id` 与 `$tags.time_retained` 解释"为何某些文件迟迟不被清理"；`$partitions.done` 与 `deleted_record_count` 看分区完成状态与 DV 删除量。全局 `sys.tables`/`sys.partitions` 只能在 Flink/Spark 查询，Doris 不支持。表/列 comment 可通过 `ALTER TABLE ... SET ('comment'=...)` 与 `MODIFY col TYPE COMMENT '...'` 维护。[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/sql-alter.md] 推断：table options 是自由 key/value 并随 schema 版本持久化，可镜像表级分类摘要，但任何引擎都不会据此执行策略；开发分支 REST entity label 也不属于当前发布版强制策略来源。

## 3. 数据血缘

- Paimon 实现 FLIP-314，通过 Flink `LineageVertexProvider` 暴露 source/sink 数据集，附带 namespace 与含核心表选项的 config facet；"available only for Flink 2.0 and above"；Table API 完整支持依赖上游 Flink PR #27727，缺失时 "can lead to potentially missing Paimon input or output datasets"；开启方式为 `execution.job-status-changed-listeners = io.openlineage.flink.listener.OpenLineageJobStatusChangedListenerFactory`。血缘不落盘在 Paimon，由 listener 送往外部系统。[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/lineage.md]
- Flink 侧：`JobCreatedEvent` 携带 `LineageGraph`，"If this configuration is empty, NO enrichers will be started"；FLIP-314 状态 accepted、目标 1.19，"column lineages ... not supported in Flink at present"。[来源: https://nightlies.apache.org/flink/flink-docs-master/docs/deployment/advanced/job_status_listener/; https://cwiki.apache.org/confluence/spaces/FLINK/pages/255070913/FLIP-314+Support+Customized+Job+Lineage+Listener]
- OpenLineage Flink 2 集成读取原生 `LineageGraph`，但文档称 "Currently, only the Kafka connector supports this functionality"，且 "lineage interfaces in Flink 2.x do not provide column level lineage information"；源码中命名 visitor 仅 Kafka/JDBC/Kinesis。[来源: https://openlineage.io/docs/integrations/flink/flink2; https://github.com/OpenLineage/OpenLineage/tree/main/integration/flink/flink2] 推断：Paimon 数据集能否被正确命名需实测。OpenLineage Spark 覆盖 S3/JDBC/仓库源，源码有 Delta/Iceberg handler、无 Paimon。[来源: https://openlineage.io/docs/integrations/spark/; https://github.com/OpenLineage/OpenLineage/tree/main/integration/spark]
- Trino 的 OpenLineage event listener 默认只覆盖 `DELETE,INSERT,MERGE,UPDATE,ALTER_TABLE_EXECUTE`。[来源: https://trino.io/docs/current/admin/event-listeners-openlineage.html] Doris 4.0.6 起有 `LineagePlugin` SPI（仅 `INSERT ... SELECT`/`INSERT OVERWRITE`/CTAS，"asynchronous and best effort"），通过 FE 配置 `activate_lineage_plugin` 启用、队列 `lineage_event_queue_size` 默认 50000、不为 `SELECT`/`UPDATE`/`DELETE`/load 产生事件；并称 `audit_log` 的 `queried_tables_and_views` 列可用于查询级表血缘。Gravitino 的血缘同样基于 OpenLineage，"Supports column lineages"、"Supports Spark"，可跨 Paimon/Iceberg/Hive catalog。[来源: https://github.com/apache/gravitino/blob/main/docs/lineage/lineage.md] 推断：1.0 的血缘只能达到"作业级 + 表级"，且 Flink 侧依赖 Flink 2.x 与上游补丁，应先用 `commit.user-prefix`（见 8）建立"作业到表"的写入映射，作为血缘的兜底数据源。[来源: https://github.com/apache/doris-website/blob/master/docs/data-governance/data-lineage.md; .../key-features/data-lineage.mdx]

## 4. 数据质量

- Paimon 约束：主键 `PRIMARY KEY (...) NOT ENFORCED`；schema 中记录 `BIGINT NOT NULL` 等可空性；nullable→NOT NULL 默认禁用（`alter-column-null-to-not-null.disabled=false` 才放开，且 "changes the schema without rewriting existing rows"）；列默认值经 `sys.alter_column_default_value` 设置，"An explicit `NULL` is also replaced by the configured default"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/concepts/spec/schema.md; .../flink/sql-alter.md; .../flink/default-value.md] 推断：文档中无 CHECK/UNIQUE 类约束，主键唯一性由 merge engine 合并语义提供而非校验。
- 工具官方支持：Great Expectations Core 的 SQL 源列 BigQuery/Databricks/Fabric/SQL Server/PostgreSQL/Redshift/Snowflake/SQLite，Trino 经 SQLAlchemy 且 "support Table Data Assets only"；另支持 Spark DataFrame。[来源: https://docs.greatexpectations.io/docs/core/connect_to_data/sql_data/; .../dataframes/] Soda Core 数据源参考含 Trino、Spark Dataframe、PostgreSQL 等，无 Doris。[来源: https://docs.soda.io/sitemap.md] Deequ 为 Spark 库，2.x 提供 3.1–3.5 构件，Apache 2.0。[来源: https://github.com/awslabs/deequ]
- 推断：流内校验放在 Flink SQL（过滤/分流坏数据到隔离表），结构性校验（唯一性、非空率、新鲜度）放 Spark 批（Deequ/GE 读 Paimon），消费侧断言放 Trino（GE/Soda）。Doris 无官方 GE/Soda 支持，不作质量执行层。1.0 建议的最小检查集（推断）：主键表主键列非空率与 `sequence.field` 单调性、append 表业务键重复率、`$snapshots.commit_time` 推导的新鲜度、`$partitions.done` 与 `record_count` 的分区完整性，以及 CDC 作业因不兼容 schema 变更失败的告警——后者是 Paimon 提供的唯一"内建质量闸门"。Deequ 自带 completeness/uniqueness/anomaly detection 与指标仓库，适合作为 Spark 侧的默认选择。

## 5. Schema 治理

- 演进边界见已有报告 14.2。`$schemas` 可回溯每个 schema 版本并与 `$snapshots.schema_id` 关联。[来源: system-tables] Spark 写入端 `write.merge-schema` → `type-widening` → `explicit-cast` 三级开关，且 "catalog caching needs to be disabled"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/spark/schema-evolution.md]
- Doris 可见性：Paimon 最新 snapshot 与表 schema 缓存由 `meta.cache.paimon.table.ttl-second` 控制，默认 86400 s；要每次查最新需设为 0；4.1 之前用 `external_cache_refresh_time_minutes`（默认 10 min）异步刷新。[来源: paimon-catalog.mdx; https://github.com/apache/doris-website/blob/master/docs/lakehouse/meta-cache.md] 推断：默认配置下 Doris 可能长时间看不到加列，1.0 须显式调低 TTL 或在发布流程中 `REFRESH`。
- 事件流 JSON 漂移：Kafka CDC "Missing field types are mapped to `STRING`"，可用 `--type_mapping to-string` 全部兜底为 STRING；不兼容变更让作业失败而非静默丢弃。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/kafka-cdc.mdx; 已有报告 14.2]

## 6. 数据生命周期与保留

- snapshot/partition/tag/consumer 过期见已有报告 9。补充：`record-level.expire-time` + `record-level.time-field` 只对主键表、"expiration happens in compaction, there is no strong guarantee to expire records in time"。[来源: core_configuration.html; https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/compaction.md] 分区过期是逻辑删除，"the files in the file system are not immediately physically deleted, it depends on when the corresponding snapshot expires"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/manage-partitions.md]
- 相关选项：`consumer.expiration-time` 防止废弃 consumer 无限阻止过期；`snapshot.clean-empty-directories` 默认 false，因对象存储上删除目录标记 "may cause performance issue"；`compaction.skip-expired-partitions` 可避免对已过期分区做无意义 compaction；`snapshot.expire.execution-mode=async` 可避免过期阻塞流作业，但批作业不推荐。[来源: core_configuration.html; manage-snapshots.mdx]
- `remove_orphan_files`："only deletes orphan files older than 1 day by default"，支持 `dry_run`、`parallelism`、`mode=local|distributed`，`database.*` 批处理；`purge_files` 文档仅一句 "Clear table with purge files"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/spark/procedures/maintenance.md]
- S3 侧：Lifecycle 支持按 prefix/tag/大小过滤的转储与过期，默认 "prevents objects smaller than 128 KB from being transitioned"；Glacier Flexible/Deep Archive "not available in real time"，Standard-IA/Glacier IR 等保持毫秒访问；Lifecycle 动作不进 CloudTrail 对象级日志。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html; .../lifecycle-transition-general-considerations.html; .../storage-class-intro.html; .../lifecycle-and-other-bucket-config.html] 推断：Paimon 不感知存储类别，冷分区可转 Standard-IA/Glacier IR 而不影响读；转 Glacier Flexible/Deep Archive 会让 Doris/Trino 读旧分区或 time travel 直接失败。S3 过期规则不能替代 Paimon 过期——直接删对象会留下悬空 manifest。对访问模式未知的历史分区，Intelligent-Tiering "no retrieval fees"、不需应用改动，可作默认；但 "less than 128 KB ... not monitored"，小文件多的表收益有限，应先做 compaction。[来源: storage-class-intro.html]
- Object Lock："works only in buckets that have S3 Versioning enabled"，compliance 模式下 "can't be overwritten or deleted by any user"。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html] 推断：Paimon 的 snapshot 过期、orphan 清理与 S3 rename 提交（见已有报告 13.1）均依赖 DeleteObject，与 Object Lock 不兼容；开启版本控制也会让被删文件以 noncurrent version 长期占用空间，需配 `NoncurrentVersionExpiration`。合规留存应通过 tag + 备份桶实现，而非锁住主桶。

## 7. 隐私与删除（GDPR）

- 主键表：`DELETE FROM ... WHERE` 需批模式，支持 `deduplicate` 与开启 `partial-update.remove-record-on-delete` 的 partial-update。[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/sql-write.mdx] MOW 模式（`deletion-vectors.enabled`）下删除只在 DV 中标记位置，"The old data file is retained"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/table-mode.md]
- append 表：Spark `DELETE`/`UPDATE`/`MERGE INTO`；COW 重写受影响文件，DV 模式只写删除向量；"Replaced files can remain available to older snapshots until snapshot expiration makes them eligible for cleanup"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/append-table/row-level-operations.md] 推断：被遗忘权场景下 append 表应关闭 DV 或删除后强制 compaction，否则原始记录仍留在数据文件里。
- 物理删除时间线：数据文件 "are only deleted when the snapshot expires"；tag "will maintain the manifests and data files of the snapshot"，consumer 亦阻止过期。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/manage-snapshots.mdx; .../manage-tags.mdx] 推断：从逻辑删除到 S3 消失 ≈ max(`snapshot.time-retained`, 覆盖该 snapshot 的 tag 保留期, consumer 位点) + compaction 重写旧文件的时间；在此之前 time travel/tag 读仍能看到已删数据，GDPR SLA 必须据此设定 tag 保留上限。推断的删除操作序列：① 批模式 `DELETE`（主键表按 key，append 表按谓词）；② 对 append 表或 DV 表执行 full compaction 让旧文件被重写；③ 删除或不再创建覆盖该数据的 tag，并确认无 consumer 卡在旧 snapshot（`$consumers`）；④ 等待 `snapshot.time-retained` 后用 `$files` 确认文件已不在任何 snapshot 中；⑤ 运行 `remove_orphan_files`（默认只删 1 天前）清理残留；⑥ 若走 Iceberg 兼容发布，Iceberg 侧元数据由 Paimon 提交时同步，无需单独处理，但 Doris/Trino 的元数据缓存需刷新。
- 加密：S3 默认 SSE-S3，"all new object uploads to Amazon S3 are automatically encrypted"；可改桶默认为 SSE-KMS/DSSE-KMS。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/serv-side-encryption.html] Paimon `core_configuration.html` 无任何 encryption 选项，文档中唯一 SSE 章节是 OSS 的 `fs.oss.server-side-encryption*`。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/filesystems.mdx] 推断：无表级/列级加密；S3 桶默认 SSE-KMS 对 Paimon 透明，列级保护需要经验证的引擎策略或写入前去标识化。手机号等低熵标识符不能用无密钥哈希替代明文；Colvenna Lake 的 keyed HMAC/token 设计约束见 [HLD 12.1](../design/hld-realtime-lakehouse.md#121-边界与威胁模型)。

## 8. 审计

- `$audit_log` 给出 `rowkind`（`+I/-U/+U/-D`），主键表可加 `table-read.sequence-number.enabled` 输出 `_SEQUENCE_NUMBER`；Doris 3.1+ 可直接读 `tbl$audit_log@options(...)`。[来源: system-tables; paimon-catalog.mdx] `commit.user-prefix` 只是前缀，源码 `createCommitUser` 生成 `prefix + "_" + UUID`，写入 snapshot 的 `commitUser`（"Writer identity used for commit recovery and deduplication"）。[来源: https://github.com/apache/paimon/blob/master/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java; https://github.com/apache/paimon/blob/master/docs/docs/concepts/spec/snapshot.md] 推断：为每个作业设不同前缀即可从 `$snapshots.commit_user` 反查写入方，是 JDBC catalog 下唯一的"谁写了什么"记录。
- Doris：`set global enable_audit_plugin = true` 后写入 `__internal_schema.audit_log`（动态分区，默认保留 30 天），字段含 `user`、`client_ip`、`catalog`、`stmt`、`scan_bytes` 等；`audit_plugin_max_sql_length` 默认 4096 会截断长 SQL，`skip_audit_user_list` 可排除服务账号；FE 本地 `fe.audit.log`。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/audit-plugin.md; .../system-tables/internal_schema/audit_log.md] Trino：HTTP event listener POST `QueryCreatedEvent`/`QueryCompletedEvent` JSON，多 listener 经 `event-listener.config-files`，`connect-retry-count` 默认 0（推断：需自行保证接收端高可用或改用 Kafka listener）。[来源: https://trino.io/docs/current/admin/event-listeners-http.html]
- S3：AWS 推荐 CloudTrail（数据事件 5 分钟内送达、可校验完整性、按 prefix 选择，收费）；server access logs 免费但 "delivered on a best-effort basis"、"might not be delivered at all"；只有 server access logs 会记录 Lifecycle 触发的 `S3.EXPIRE.OBJECT`/`S3.TRANSITION*`，CloudTrail 不记录。推断：合规审计以 CloudTrail 数据事件为准，server access logs 仅用于成本与 Lifecycle 观测。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/logging-with-S3.html; .../ServerLogs.html]

## 9. 数据分类与敏感数据发现

**上游事实。** Paimon 非 REST catalog 没有正式 label；`$schemas` 记录字段与 schema 版本，可用于发现演进（见 2）。开发分支 REST label 的语义是 "a label does not itself grant access or enforce a policy"，无继承、无标签驱动匹配。[来源: https://paimon.apache.org/docs/master/concepts/rest/management-api/; https://paimon.apache.org/docs/master/concepts/system-tables/] 该 Management API 不属于 Paimon 2.0.0 已发布能力，见 [发布版核对](version-capability.md)。Doris 官方文档给出 `GRANT Select_priv(col1,col2) ON ctl.db.tbl` 的列级 RBAC 语法。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/auth/authorization/data.md] 但尚无 Doris 4.1.4 对本项目 Paimon 外部表的逐入口拒绝证据，验收口径见 [M0 验收契约](../design/acceptance-contract.md#1-身份授权和绕过路径)。

**Colvenna Lake 1.0 设计决定。** 由自有版本化登记簿保存机器可读的表级和列级 L1–L4、敏感类别、Owner、schema 版本与复核状态；table option 只镜像表级摘要，column comment 只供展示。级别映射访问、去标识化和审计的默认控制；个人数据类别独立触发删除准入。字段名/正则仅产出候选标签，Owner 复核后才可发布。`combined` 新表先进入隔离区，`$schemas` 新版本用于发现新列而不是事前授权围栏；任意自动演进的可读表须确保新列默认不可见。Doris 列白名单只有在目标外表和所有旁路通过拒绝测试后才能承担该围栏，否则阻断自动暴露或改用独立物理表。`LakeTable.upstream` 支持表级继承，降级须声明去标识化并审批；设备 ID、时间、经纬度等组合可人工上调。Doris MV/内表和报表缓存与删除共用跨系统副本登记簿。完整准入表和阶段划分见 [HLD 12.5–12.7](../design/hld-realtime-lakehouse.md#125-schema-与元数据一致性)。

**阶段 2 设计候选。** 把可确认的 Doris 查询表及查询时刻与历史分级登记簿离线关联，形成 L3/L4 表级访问报表与扫描量、非工作时间候选告警；`audit_plugin_max_sql_length` 默认 4096 会截断 SQL，不能由 audit SQL 断言实际命中的列或脱敏结果。[来源: https://github.com/apache/doris-website/blob/master/docs/admin-manual/audit-plugin.md] Doris 终端用户授权有效期可由声明式控制器回收，但原生到期能力和撤销生效窗口仍待核实。

## 10. 调研时的 JDBC 路径建议（历史记录，不作为当前 1.0 方案）

本节保留早期 JDBC 方案的研究轨迹，不作为当前 1.0 准入口径；其中以 column comment 分类、直接依赖 Row Policy/Ranger 或仅按保留期承诺 GDPR 删除的建议，已由 [HLD 第 12 节](../design/hld-realtime-lakehouse.md#12-安全与数据治理)及 [M0 验收契约](../design/acceptance-contract.md)替代。

| 治理能力 | 当时建议落点 | 当时建议纳入 1.0 | 依据 |
| --- | --- | --- | --- |
| 库/表/列权限 | Doris RBAC；Trino file/OPA；S3 prefix IAM | 是 | 1.2/1.3/1.5 |
| 行过滤 | Doris Row Policy；Trino `filter`/OPA | 是 | 1.2/1.3 |
| 列脱敏 | Trino `mask`/OPA；Doris 需 Ranger | 部分 | 1.2/1.3 |
| Paimon 原生权限/label/policy | REST catalog（未采用） | 否 | 1.1 |
| 元数据发现 | Paimon 系统表 + Doris/Trino 连接器 → DataHub/OpenMetadata | 部分 | 2 |
| 血缘 | Flink 2.x + OpenLineage listener（需实测 Paimon 命名）；Trino OL listener；Doris LineagePlugin | 否 | 3 |
| 质量校验 | Flink SQL 流内；Spark（Deequ/GE）；Trino（GE/Soda） | 部分 | 4 |
| schema 治理 | Paimon `$schemas` + CDC 演进规则；Doris 缓存 TTL 调优 | 是 | 5 |
| 生命周期 | Paimon snapshot/partition/tag/record-level expire + orphan 清理；S3 Lifecycle 转储 | 是 | 6 |
| 删除与 GDPR | Paimon DELETE + 过期 + compaction；tag 保留上限 | 是 | 7 |
| 加密 | S3 桶默认 SSE-KMS | 是 | 7 |
| 审计 | `$audit_log` + `commit.user-prefix`；Doris audit_log；Trino HTTP listener；CloudTrail 数据事件 | 是 | 8 |
| 分类 | table option/comment 约定；Gravitino tag（可选） | 部分 | 9 |

**当时建议的 1.0 必做（非当前准入口径）**：① 按数据域拆 S3 prefix 与 IAM 角色，Flink/Spark 用 IRSA，禁止共享桶级凭证；② Doris 开启 audit plugin、按 catalog/db 建 role，敏感表加 Row Policy；Trino 用 file-based 规则起步（含 `mask`）；③ 每个写作业设唯一 `commit.user-prefix`，主键表按需开 `table-read.sequence-number.enabled`；④ 明确保留策略三元组（`snapshot.time-retained`、tag 保留、consumer 过期）并据此承诺 GDPR 物理删除 SLA，含 PII 的 append 表禁用 DV；⑤ Doris `meta.cache.paimon.table.ttl-second` 与 schema 变更发布流程绑定；⑥ 桶默认 SSE-KMS，CloudTrail 开启 Paimon prefix 的数据事件，加 `AbortIncompleteMultipartUpload`；⑦ 分类以 table option + comment 约定落地。

**当时建议的阶段 2（非当前准入口径）**：引入 OPA 作为 Trino 策略引擎（Rego 可复用到其他支持 OPA 的组件，Lakekeeper 亦有 OPA bridge），或 Ranger 统一 Doris/Trino（需接受 Doris 插件在外部分支、Trino 需 Ranger 2.5+）；Spark 批质量作业（Deequ/GE）纳入调度；Doris LineagePlugin + Trino OpenLineage listener 汇聚到 DataHub/OpenMetadata。

**当时建议的阶段 3（非当前准入口径）**：Gravitino 作为标签/策略登记簿并评估其 Ranger pushdown；Flink 2.x 就位后接入 OpenLineage 并实测 Paimon 数据集命名；评估自建 Paimon REST catalog（见下）。

**当时假设的 Management API 发布并被引擎执行后可能获得什么**：与引擎无关的库/表/列权限、行过滤、列脱敏（策略随表存储、schema 变更时服务端校验、fail closed）；entity label 作为正式分类载体；`sys.tables`/`$partitions` 的 owner/created_by 审计字段；服务端临时凭证下发（不再向作业分发长期 S3 凭证）；服务端统一审计与 principal 解析。[来源: management-api; system-tables; https://github.com/apache/paimon/blob/master/docs/docs/concepts/rest/index.md] 这段依赖未发布的 Management API；行过滤/脱敏还依赖引擎实现 `AuthTableQuery` 授权流程，不能作为当前 1.0 能力。

## 未能核实的问题

- Doris Row Policy 能否建在 Paimon 外部 catalog 的表上：`CREATE ROW POLICY` 与 data.md 未提及外部表；Ranger 示例覆盖外部 Hive catalog，但内置 Row Policy 未见示例。
- Doris `audit_log` 的 `queried_tables_and_views`/`chosen_m_views` 列：key-features 页声称存在，但 `internal_schema/audit_log.md` 字段表未列出，可能是版本差异。
- Doris/Trino 是否有独立的表级 tag/label 机制：本次只核实了权限与脱敏文档，未穷举。
- Paimon REST catalog 的 `AuthTableQuery` 行过滤/脱敏在 Flink、Doris、Trino 读路径中是否被执行：文档仅描述 Spark procedure 与服务端契约。
- OpenLineage Flink 2 集成能否为 Paimon 数据集生成正确 namespace/name：OpenLineage 文档称仅 Kafka 支持，Paimon 文档称已实现接口，需实测。
- `purge_files` 的确切语义（是否绕过 snapshot 过期直接删文件）：文档仅一句描述，未读源码。
- paimon-trino 对 `metastore=jdbc`/PostgreSQL 锁的实际支持：仅从源码透传逻辑推断，未见文档或测试。
- Trino `/connector/paimon.html` 404 是否意味着 Trino 官方从未收录 Paimon connector，还是页面迁移：未查 Trino release notes。
- S3 Object Lock 与 Paimon rename/删除路径的冲突为推断，未在 AWS 上实测。
- DataHub/OpenMetadata 的 classification 功能细节未抓取，仅核实了连接器列表。
- Ranger 官方站首页未列插件清单，Trino/Presto 插件存在性依据 GitHub 仓库目录结构。
