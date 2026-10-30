---
title: "版本与跨组件能力基线"
date: 2026-09-28
status: design-evidence
sources:
  - https://github.com/apache/paimon (release-2.0.0 = 604e6d5；1.4.2 = ac223f4；master ce0633c)
  - https://github.com/apache/doris (4.1.4 = ad35a14)
  - https://github.com/apache/doris-website (bf98921：versioned_docs/version-4.x 与 docs/lakehouse)
  - https://github.com/apache/flink (1.20.4 = f6265b2) ; https://flink.apache.org/downloads/
  - https://github.com/apache/flink-cdc (release-3.5.0、release-3.6.0；bf16033 docs)
  - https://spark.apache.org/releases/ (3.5.8、3.5.9) ; https://github.com/apache/spark (3.5.8 = 5a48a37)
  - https://github.com/StarRocks/starrocks (4.1.3 = 8a8e186)
---

# 版本与跨组件能力基线

范围：Issue #1、[HLD §4.3/6.4/10.1/16/17](../design/hld-realtime-lakehouse.md) 与同一提交的 [README](../../README.md)。截至 2026-09-28，仅核对官方发布页、发布标签源码与版本化文档；下表的「已发布」只证明代码或文档进入正式版本，**不等于跨组件、S3 或 EKS 联调通过**。

## 候选版本

| 组件 | M1/M2 候选正式版（发布标签所指提交） | 选择依据与边界 |
| --- | --- | --- |
| Paimon | **2.0.0**，`604e6d5e131c74a8d127333a2a3ad6d0319732bf` | [正式 Release][p-release]；发布树含 Flink 1.20、Spark 3.5 connector 与 REST 客户端。保留 [1.4.2][p-142]（`ac223f47d916f4c8e9c07fd1745db448e4e7d0d8`）作**重新评估方案**，不是满足 HLD「Doris 读 2.x DV」退出条件的替代通过。[构建配置][p-build] |
| Flink | **1.20.4**，`f6265b2a32fd1571cd5dcea694c923b057239ac4` | [正式发布列表][f-download]、[标签][f-tag]；与 Paimon 2.0.0 CDC 模块的 `flink.version=1.20.4` 对齐。Paimon 1.20 connector 模块自身写的是 `1.20.1`；1.20.4 与全部相关 jar 的运行组合仍须联调。[CDC POM][p-cdc-pom]、[connector POM][p-flink-pom] |
| Flink CDC | **3.5.0**，`d15c5de8a74d244aea5cd59640543cf5438ee877` | [正式 Release][cdc-release]；Paimon 2.0.0 CDC 模块依赖 3.5.0，MySQL action 文档给出 3.5.0 jar。[CDC POM][p-cdc-pom]、[action 文档][p-mysql]。3.6.0 虽也[正式发布][cdc-360]，不据此替换 action 的依赖。**M3 复评（#113）：** MySQL 的 SQL connector 换成 3.7.0-1.20（修复新增源表 failover 后丢数，FLINK-40741），PostgreSQL、MongoDB 仍为 3.5.0；YAML Pipeline 不采用。见 [Flink CDC 3.7 评估](../history/m3/flink-cdc-3.7.md)。 |
| Doris | **4.1.4**，`ad35a140c7fd0b842f18c23300bac581f7d04326` | [正式 Release][d-release]；[4.x 版本文档][d-doc]标 JDBC catalog 自 4.1.0 起为实验性，4.1.4 [源码 POM][d-pom]已依赖 Paimon **1.4.2**。读 Paimon 2.0.0 表的正确性未验证。 |
| Spark（可选） | **3.5.8**，`5a48a37b2dbd7b51e3640cd1d947438459556cc6` | [官方 3.5.8 发布页][s-release]、[标签][s-tag]；与 Paimon 2.0.0 Spark 3.x 构建属性及 [3.5 connector 文档][p-spark]对齐。M1 无批量回刷需求时不装；[3.5.9 已发布][s-359]，但不作为此轮联调基线。[构建配置][p-build] |
| StarRocks（评估备用） | **4.1.3**，`8a8e18642450fba09149c8a3f149abefa043c2b7` | [正式 Release][sr-release]；[该标签的 Paimon catalog 文档][sr-doc]仅列 filesystem/Hive 元数据源，未给 JDBC/REST、DV 保证，不能直接替代以下两条 Doris 查询路径。 |

## 能力与证据矩阵

| 能力 | 发布版证据 | 对本项目的判断 / 关口 |
| --- | --- | --- |
| Paimon JDBC catalog + PostgreSQL 锁 | 2.0.0 [catalog 文档][p-cat]列 JDBC/PostgreSQL；[并发文档][p-concurrency]明确 S3 非原子 rename 须用 Hive/JDBC catalog 与 `lock.enabled`；[锁方言源码][p-pg-lock]含 PostgreSQL。 | **已发布，M1 必配**。所有写入与维护进程使用同一 JDBC 库、warehouse、`catalog-key`、`lock.enabled=true`、`lock.type=jdbc`；不能以单进程锁代替分布式锁。[JDBC 说明][p-views]。并发提交结果待测。 |
| Paimon REST catalog / 服务端 | 2.0.0 [REST 文档][p-rest]描述客户端与需实现 API 的服务端；[OpenAPI 3.1.1，API version 1.0][p-openapi]含 `/v1/config`、table `commit`、`token`。Flink 与 Spark 发布树各有基于**测试服务端**的 REST 测试。[Flink 测试][p-flink-rest]、[Spark 测试][p-spark-rest] | **客户端与契约已发布；可部署的 colvenna-catalog 服务端未实现/未验证**。OpenAPI 出现 `commit` 不证明服务端具有原子提交；M2 必须实现并测试。不要把 Paimon REST catalog 与可选的 Iceberg REST 发布接口混同。 |
| REST Management API（grant、label、policy） | [Paimon 2.0.0 发布树的 REST 文档目录][p-rest-tree]没有 `management-api.md`，发布版 [Catalog OpenAPI][p-openapi]也没有 permissions/labels/policies 路由；[发布树][p-release-tree]无 `PermissionManagement`/`LabelManagement`/`PolicyManagement` 接口。[固定 master 提交][p-master-mgmt]才提供实验性 Management API 及独立的 [management OpenAPI][p-master-mgmt-api]。 | **仅开发分支，不能当作 Paimon 2.0.0 正式客户端能力**。M2 服务身份库表授权由 colvenna-catalog 自有、单独版本化的管理面登记，并在已发布的 catalog load/commit/token 路径执行；列 grant、行过滤、列脱敏与 entity label 的上游接口适配推迟到有正式发布版、升级并逐引擎验证后。 |
| Deletion Vector（DV）与完整变更 | Paimon 2.0.0 [主键表模式][p-dv]给出 `deletion-vectors.enabled=true`，同时说明 L0 行默认要在 compaction 后可见；[changelog 文档][p-changelog]确认 `input` 写**额外 changelog 文件**。Doris 4.1.4 [版本文档][d-doc]声明读 DV；[扫描源码][d-scan]有 DV 文件传递与 native/JNI 分流。 | **两侧能力各已发布；Paimon 2.0.0 → Doris 内置 1.4.2 reader 的 DV 正确性、native 占比和新鲜度均未验证**。`input` 并非零额外写入成本。DV 也不保证每个 split 都走 native。 |
| CDC 路径 | Paimon 2.0.0 [MySQL action][p-mysql]支持 `mysql_sync_database`、`--catalog_conf`、`--mode combined`；其 CDC 模块固定 Flink CDC 3.5.0。[CDC POM][p-cdc-pom]。Flink CDC 3.6.0 自带 [Paimon Pipeline sink 文档][cdc-sink]写明仅列 filesystem/Hive catalog，且为 at-least-once + 主键幂等，**不支持 exactly-once**。 | **M1 基线是 Paimon 自家 action + CDC 3.5.0**；`--catalog_conf metastore=jdbc` 或 M2 `rest` 仍需真实作业冒烟验证。Flink CDC YAML Pipeline sink 不承担 M1/M2 所需的 catalog/提交语义。 |
| Doris JDBC 查询 | 4.1.4 [版本文档][d-doc]有 PostgreSQL + S3 的 `paimon.catalog.type=jdbc` 示例，标注 4.1.0 起实验性；[发布源码][d-jdbc]包含 URI、JDBC 驱动装载和 Paimon catalog 初始化。 | **查询代码已发布，M1 组合未验证**。需 JDBC 驱动可由 FE/BE 使用、能连同一 PostgreSQL catalog 并访问同一 S3 warehouse；以 CDC 增删改行做 Doris/Flink 结果对账。 |
| Doris REST 查询 / bearer | 4.1.4 [发布源码][d-rest]允许 `paimon.catalog.type=rest`，把 `paimon.rest.*` 转成 Paimon catalog 选项；Paimon 2.0.0 [bearer 文档][p-bear]定义 `token.provider=bear` 与 `token`。Doris [4.x 文档][d-doc]给出的 REST 示例是阿里云 DLF。 | **REST 通路在发布源码中；通用 bearer + 自建 colvenna-catalog 的端到端读取仍未验证**。M2 用 `paimon.rest.token.provider=bear` / `paimon.rest.token` 做冒烟，不把 DLF 示例当作通用 bearer 验证。 |
| REST 按表数据凭证 | Paimon 2.0.0 [`RESTTokenFileIO` 源码][p-token]的 `data-token.enabled` **默认 false**；[RESTCatalog][p-rest-code]仅在开启时装配该 FileIO。 | **已发布但默认未开启**。M2 要在 Flink/Spark 客户端显式开启并核实 Doris 选项转发及实际 `/token` 调用、短期凭证刷新和 S3 拒绝越权。接口存在不等于权限边界已生效。 |
| StarRocks 备用查询 | 4.1.3 [发布标签文档][sr-doc]确认 Paimon 外部表只读，元数据源只列 filesystem/Hive。 | **不是 JDBC/REST 的现成回退**；DV、Paimon 2.0.0 表正确性与目标延迟均未获该文档证明。 |
| 仅 master 叙述 | Paimon 2026-09-28 的 [master POM][p-master]为 `2.2-SNAPSHOT`；Doris [master 能力矩阵][d-master-matrix]列 Paimon 写入，而 [4.x 版本文档][d-doc]仍写只读；4.1.4 发布源码却已含 Paimon [写入执行器][d-write]与[写入回归测试][d-write-test]，故不能反推“仅 master 才有写入”。 | **不纳入 M1/M2 基线**：不按 master 文档锁版本或承诺 Doris 写 Paimon。该写入能力的发布版范围有文档冲突，归为未验证；本 issue 只要求 Doris 查询。 |

## 可执行版本前提与验证顺序

1. **M1 / JDBC 查询。** 固定 Paimon 2.0.0 + Flink 1.20.4 + Flink CDC 3.5.0 + Doris 4.1.4，PostgreSQL catalog 与真实 S3；装 Paimon Flink 1.20/action/S3 jar、CDC 3.5.0 源连接器及 Doris PostgreSQL JDBC 驱动。Flink SQL、CDC action、独立 compaction 作业对齐 `metastore=jdbc`、`uri=jdbc:postgresql:...`、`warehouse=s3://...`、`catalog-key` 与 JDBC 锁；Doris 用 `paimon.catalog.type=jdbc`、`paimon.jdbc.uri/user/password/driver_class/driver_url` 读同一库。[Paimon catalog][p-cat]、[Doris 示例][d-doc]。先建表与 CDC 冒烟，再让写入和维护按 [M0 验收契约 2.1](../design/acceptance-contract.md#21-测量协议) 的基线档并发连续 24 小时，核对 snapshot 单调/无丢失、Flink 与 Doris 在同一快照上的 insert/update/delete 结果；用 `EXPLAIN VERBOSE` 核对 `paimonNativeReadSplits`、记录 DV 与 compaction 可见延迟。[扫描源码][d-scan]。**任何查询错误、快照丢失或 2.x DV 读错均阻断 M1 退出。**
2. **M2 / REST 查询。** 仅在 M1 数据与查询关口通过后，把**同一版本组合**迁到按 Paimon 2.0.0 [OpenAPI][p-openapi]实现的 colvenna-catalog：先验证 `/v1/config`、库表加载、bearer、table commit 的并发原子性与 `/token`；服务身份 grants 使用 colvenna-catalog 自有管理面，并在发布版 catalog 操作上做拒绝测试，不依赖 master-only Management API。再以 Flink/CDC action、可选 Spark 用 `metastore=rest` 写，同一 Doris 4.1.4 用 `paimon.catalog.type=rest` 和 `paimon.rest.token.provider=bear` 读。将 `data-token.enabled=true` 及 Doris 对应 `paimon.rest.data-token.enabled=true` 作为**待验证配置**，抓取实际 token 请求/凭证使用/过期刷新证据。[REST 源码][p-rest-code]、[Doris REST 源码][d-rest]。**Doris 只能经 JDBC/filesystem 读、通用 bearer 不通、凭证未生效或并发 commit 丢失时，M2 REST 查询/治理条件均不得报通过。**
3. **Doris 默认与 StarRocks 回退触发。** 默认保持 Doris 4.1.4，最终选型待 M1 以相同数据和查询清单验证。若 Doris 对 2.0.0 DV 读错/不可接受的新鲜度或查询性能、JDBC 路径无法建立，启动 StarRocks 4.1.3 对照验证；**只有**找到可证明的同一权威 catalog 接入办法、DV 正确性及查询 SLA 后才可改选。其已发布文档只列 filesystem/Hive，故不能假设它自动修复 JDBC 或 M2 REST 问题。[StarRocks 标签文档][sr-doc]。Paimon 1.4.2 与 Doris 4.1.4 reader 版本相同可作为另一个调查分支，但改为 1.4.2 会违反现有 HLD 的「2.x DV」M1 条件，须另行修改里程碑后才能算通过。

## 验证状态

本矩阵只证明发布版源码、文档和候选选择；**没有运行跨组件兼容联调、EKS/S3 作业或 Doris/StarRocks 查询**。HLD 与 README 的版本和里程碑口径以本矩阵为依据；M1 JDBC 与 M2 REST 关口仍须在对应里程碑执行，不计为 M0 已实测结果。

## 未能核实的问题

上一节的运行关口不在此列；以下是本矩阵没有从发布版文档或源码中核实的事实。

- Doris 4.1.4 的 Paimon 写入是否默认可用，或受会话变量/实验开关限制：发布源码有写入执行器与回归测试，4.x 文档仍写只读，本矩阵没有读启用条件。
- Doris BE 的 native reader 读 Paimon 文件时，用的是 catalog 上配置的存储凭证，还是 colvenna-catalog 经 `/token` 下发的表凭证：本矩阵只引用了 FE 端 `paimon.rest.*` 选项转换的源码，没有追到 BE 读路径。
- Doris 内置的 Paimon 1.4.2 reader 与 Paimon 2.0.0 写出的表（manifest、DV 文件格式）是否兼容：没有找到上游兼容性声明，只能靠 M1 对账。
- StarRocks 4.1.3 源码是否有文档未列出的 JDBC/REST 元数据源：本矩阵只核对了该标签的文档。

<!-- 一手材料：功能链接均指向对应发布标签提交或文档仓库固定提交。 -->
[p-release]: https://github.com/apache/paimon/releases/tag/release-2.0.0
[p-142]: https://github.com/apache/paimon/tree/ac223f47d916f4c8e9c07fd1745db448e4e7d0d8
[p-build]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/pom.xml
[p-cdc-pom]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-cdc/pom.xml
[p-flink-pom]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-1.20/pom.xml
[p-mysql]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/cdc-ingestion/mysql-cdc.md
[p-cat]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/catalog.md
[p-concurrency]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/concurrency-control.md
[p-pg-lock]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-core/src/main/java/org/apache/paimon/jdbc/DistributedLockDialectFactory.java
[p-views]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/views.md
[p-rest]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/rest/index.md
[p-rest-tree]: https://github.com/apache/paimon/tree/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/rest
[p-release-tree]: https://github.com/apache/paimon/tree/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-api/src/main/java/org/apache/paimon
[p-master-mgmt]: https://github.com/apache/paimon/blob/ce0633cb09ec3b0d951a01cda54ab963ea565ea9/docs/docs/concepts/rest/management-api.md
[p-master-mgmt-api]: https://github.com/apache/paimon/blob/ce0633cb09ec3b0d951a01cda54ab963ea565ea9/docs/static/rest-management-open-api.yaml
[p-openapi]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/static/rest-catalog-open-api.yaml
[p-flink-rest]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-common/src/test/java/org/apache/paimon/flink/FlinkRestCatalogITCase.java
[p-spark-rest]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-spark/paimon-spark-ut/src/test/scala/org/apache/paimon/spark/PaimonSparkTestWithRestCatalogBase.scala
[p-dv]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/primary-key-table/table-mode.md
[p-changelog]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/primary-key-table/changelog-producer.md
[p-bear]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/rest/bear.md
[p-token]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-common/src/main/java/org/apache/paimon/rest/RESTTokenFileIO.java
[p-rest-code]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-core/src/main/java/org/apache/paimon/rest/RESTCatalog.java
[p-spark]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/spark/quick-start.mdx
[p-master]: https://github.com/apache/paimon/blob/ce0633cb09ec3b0d951a01cda54ab963ea565ea9/pom.xml
[f-download]: https://flink.apache.org/downloads/
[f-tag]: https://github.com/apache/flink/tree/f6265b2a32fd1571cd5dcea694c923b057239ac4
[cdc-release]: https://github.com/apache/flink-cdc/releases/tag/release-3.5.0
[cdc-360]: https://github.com/apache/flink-cdc/releases/tag/release-3.6.0
[cdc-sink]: https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/docs/content/docs/connectors/pipeline-connectors/paimon.md
[d-release]: https://github.com/apache/doris/releases/tag/4.1.4
[d-pom]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/pom.xml
[d-doc]: https://github.com/apache/doris-website/blob/bf9892167d06541fac3cfcb28b4e0ff571653ebe/versioned_docs/version-4.x/lakehouse/catalogs/paimon-catalog.mdx
[d-jdbc]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/datasource/property/metastore/PaimonJdbcMetaStoreProperties.java
[d-rest]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/datasource/property/metastore/PaimonRestMetaStoreProperties.java
[d-scan]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/datasource/paimon/source/PaimonScanNode.java
[d-write]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/nereids/trees/plans/commands/insert/PaimonInsertExecutor.java
[d-write-test]: https://github.com/apache/doris/tree/ad35a140c7fd0b842f18c23300bac581f7d04326/regression-test/suites/external_table_p0/paimon/write
[d-master-matrix]: https://github.com/apache/doris-website/blob/bf9892167d06541fac3cfcb28b4e0ff571653ebe/docs/lakehouse/best-practices/doris-paimon-capability-matrix.mdx
[s-release]: https://spark.apache.org/releases/spark-release-3-5-8.html
[s-359]: https://spark.apache.org/releases/spark-release-3-5-9.html
[s-tag]: https://github.com/apache/spark/tree/5a48a37b2dbd7b51e3640cd1d947438459556cc6
[sr-release]: https://github.com/StarRocks/starrocks/releases/tag/4.1.3
[sr-doc]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/docs/en/data_source/catalog/paimon_catalog.md

> 注：本文中的 issue 与 PR 编号（如 #1）和 tag（如 `f517f2ab`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
