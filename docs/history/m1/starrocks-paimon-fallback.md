---
title: "M1 Issue #14：StarRocks 4.1.3 读取 Paimon 2.0.0 的回退评估"
date: 2026-09-30
status: source-and-image-audited-live-not-run
versions:
  starrocks: "4.1.3 @ 8a8e18642450fba09149c8a3f149abefa043c2b7"
  paimon-writer: "2.0.0 @ 604e6d5e131c74a8d127333a2a3ad6d0319732bf"
  starrocks-bundled-paimon: "1.3.1 @ 28dfdfed24877c5f4c36b7c2409794fc8ef79607"
project-base: "8e86d25bfbfd5199a3e5d39844e115b07630076e"
---

# M1 Issue #14：StarRocks 4.1.3 / Paimon 2.0.0 回退评估

## 结论与口径

[HLD §17](../../design/hld-realtime-lakehouse.md#17-未决问题)要求 StarRocks **分别证明**能读取同一权威 catalog、正确读取 DV，且满足查询目标，才能接替 Doris。Issue #14还要求原生行集对账、快照与 schema 新鲜度、查询耗时及 time travel / 增量读 / 系统表逐项记录。本页核对的是 StarRocks `4.1.3` 和 Paimon `2.0.0` 的发布标签；两个版本号已由各自的正式 tag 指向上述提交。[StarRocks tag][s-tag]、[Paimon tag][p-tag]。

**当前判定：回退 `NOT_RUN`，不可报 PASS。** StarRocks 的正式文档只列 filesystem/Hive Paimon catalog；4.1.3 源码留下一个未经发布文档支持的 JDBC 配置入口；但该版 FE 和 BE/JNI 固定 Paimon **1.3.1**。1.3.1 的普通 DV batch scan 默认排除 L0，而 2.0.0 的 `deletion-vectors.merge-on-read=true` 才允许本项目所需的未压实 L0 可见性。[StarRocks catalog 文档][s-doc]、[connector 源码][s-connector]、[FE POM][s-fe-pom]、[JNI POM][s-jni-pom]、[1.3.1 选项][p13-options]、[1.3.1 batch scan][p13-batch]、[2.0.0 选项][p20-options]。发布源码和下述镜像字节码指出默认读路径的**具体不匹配**；尚无 StarRocks 连接、查询或行集回执，不能把构件结论冒充现场 RED 或 PASS。

### 正式镜像的只读核验（2026-09-30）

从 Docker Hub 的 `4.1.3` 官方标签解析并按 `linux/amd64`
manifest digest 拉取 FE
`sha256:cde5fb43f44a108f01f8781cb56f4600079e31d52bea9a69856cce59f18596a7`
与 BE
`sha256:d33a7af3f92a97236febc7719a4ce67778e4495bb8b8552e989e245ca2025b41`。
容器只创建、复制文件，没有启动服务或连接 AWS。FE 的
`paimon-bundle-1.3.1.jar` SHA-256 为
`ec0a91c0cf6c1da46c7175b7778e3207395eca10272d891fa49770ec7a780162`；
BE 的 `paimon-reader-lib` 中是**相同字节**的 bundle，
JNI `starrocks-paimon-reader.jar` SHA-256 为
`7f14a1573947e85ddc82418b4ab2d40e2b56de81d945d9e2ad618b12cbf6afb7`。
FE 另含 PostgreSQL JDBC `42.7.11`；JAR 存在不证明能以
只读角色接通本项目的权威 JDBC catalog。

对 FE 实际 bundle 的 `DataTableBatchScan` 与 `CoreOptions`
运行 `javap -c -p`：主键表的默认 batch scan 在
`batchScanSkipLevel0()` 为真且 `BATCH_SCAN_MODE=NONE` 时
调用 `SnapshotReader.withLevelFilter`；过滤函数仅接受
`level > 0`。该 bundle 的 `batchScanSkipLevel0()` 对
启用 deletion vectors 的表返回真，且没有 2.0.0 的
`deletion-vectors.merge-on-read` 分支。这与发布源码一致，
也把 **stock 4.1.3 FE 默认 DV/L0 扫描不能覆盖本项目
未压实数据**从版本推测收敛为构件级阻断。
因此不将该原装镜像直接部署为 M1 主查询回退；
换用自构建 FE/BE/JNI 或只读已压实快照均属另一独立配置。
本次没有启动 StarRocks、查询 S3 或测量 JDBC、行集和性能；
这些现场门禁仍为 **NOT_RUN**，并非 StarRocks 实测 RED。
只读命令、镜像元数据、JAR 校验和与字节码当时保存在开发环境未入库的证据目录
`issue14-starrocks-image-preflight-20260930/`。

本仓库的 [Doris 实测记录](doris-paimon.md)保留了
**Doris 4.1.4 / Paimon 2.0.0 DV L0 RED**：首次漏行，
更新/删除后返回旧值；维护后的 DV 与非 DV、append
合成样本有有限通过记录。四会话受限视图发布有独立的小规模
PASS，但目标负载的分位延迟、长稳态与主查询选型仍未完成。
本次 StarRocks 核验只有本机镜像/JAR/字节码读取，
没有启动 StarRocks、执行 AWS/Kubernetes 操作或查询行集；
Doris 的 RED 也不构成 StarRocks 通过的证据。

## 逐项兼容性判断

| 问题 | 发布文档 / 发布源码实际证明 | 尚缺什么；当前状态 |
| --- | --- | --- |
| **同一 PostgreSQL JDBC 权威 catalog？** | Paimon 2.0.0 文档明确支持 JDBC metastore，目录参数含 `uri`、`jdbc.user`、`jdbc.password`、`catalog-key`、`warehouse`。[Paimon catalog][p20-catalog] StarRocks 4.1.3 文档将 `paimon.catalog.type` 限定为 `filesystem` 或 `hive`。[StarRocks catalog][s-doc] StarRocks 源码把任意非空 `paimon.catalog.type` 传给 Paimon `METASTORE`，并透传 `paimon.option.*` 后调用 Paimon factory；其内置 Paimon 1.3.1 已含 `jdbc` factory、注册项和 `JdbcCatalog`。[connector][s-connector]、[factory 调用][s-create]、[factory][p13-factory]、[factory 注册][p13-service] | **source-observed 候选，未获 StarRocks 文档支持，live `NOT_RUN`。** 必须用与写端完全相同的 JDBC 数据库、`catalog-key` 和 S3 warehouse，经只读 PostgreSQL 身份实际列出 `ods.issue14_*` 并读取；仅对同一 S3 路径另建 filesystem catalog 不满足“同一权威 catalog”。Paimon 1.3.1 `JdbcCatalog` 初始化会在元数据表不存在时尝试建表，故只读身份若因此失败须记录失败，不可为绕过而扩大权限。[1.3.1 JDBC 初始化][p13-jdbc-init] |
| **DV 与未压实 L0 正确吗？** | StarRocks FE 可把 Paimon `DataSplit` 的 raw file 和 deletion file 交给 native reader，不适合 raw 的 split 则交 JNI reader；BE 有 deletion bitmap 读取代码。[scan 分流][s-scan]、[BE DV][s-be-dv]、[JNI reader][s-jni] 但 FE/JNI 构建都锁 Paimon 1.3.1。[FE POM][s-fe-pom]、[JNI POM][s-jni-pom] Paimon **1.3.1** 的 `batchScanSkipLevel0()` 对 DV 返回 true，普通 batch scan 随即过滤 `level > 0`；`batch-scan-mode` 默认 `NONE`，其他值是内部 compaction 用途。[1.3.1 选项][p13-options]、[1.3.1 batch scan][p13-batch]、[内部选项][p13-mode] Paimon **2.0.0** 的发布源码有 `deletion-vectors.merge-on-read`，设 true 时不再跳过 DV L0，且文档限定该选项只改变 batch scan 的 L0 可见性。[2.0.0 选项][p20-options]、[2.0.0 batch scan][p20-batch]、[2.0.0 文档][p20-config] | **stock 4.1.3 的 FE/BE 镜像都含 1.3.1 bundle，FE 字节码确认默认 DV batch scan 过滤 L0；构件级阻断，live `NOT_RUN`。** #14 合成 DV 表同时设置 `write-only=true`、`deletion-vectors.enabled=true` 和 `deletion-vectors.merge-on-read=true`；对其 snapshot 直接按旧 reader 规划，不能假设旧 reader 识别新开关。本仓库表定义（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/src/main/java/com/covelake/m1/DorisSeedProbe.java`） 压实后 DV 文件的 native/JNI 结果、2.0.0 表元数据/文件格式能否由 1.3.1 解读，也没有跨版本保证或现场证据。 |
| **能查目标表及功能吗？** | 4.1.3 文档给出 Paimon 外表 `SELECT`、数据库发现和 S3 配置；文档没有对本项目的 2.0.0 主键 DV、非 DV 主键、append 表给出组合保证。[StarRocks 查询][s-query]、[S3 设置][s-doc] 4.1.3 源码有按 snapshot/branch/tag 选版本、`incremental-between` 和 Paimon system table 分流。[版本选择][s-version]、[增量][s-incremental]、[系统表][s-system] | **通用 SELECT documented；目标表与高级功能 live `NOT_RUN`。** 需逐项核对主键 insert/update/delete、append 与新增列、历史快照、增量行集和所需系统表；源码入口不证明 Paimon 2.0.0 上的结果正确。新 snapshot、schema 缓存刷新与目标负载耗时同样未知。 |
| **需要哪些发布构件？** | StarRocks 4.1.3 FE 依赖 `paimon-bundle`、`paimon-s3` **1.3.1** 和 PostgreSQL JDBC driver **42.7.11**；BE/JNI `paimon-reader` 也依赖 `paimon-bundle`、`paimon-s3` **1.3.1**，并产出 `starrocks-paimon-reader`。[FE 版本][s-fe-pom]、[FE 依赖][s-fe-deps]、[driver 版本][s-driver]、[driver 依赖][s-fe-driver]、[JNI POM][s-jni-pom]、[JNI 依赖][s-jni-deps] StarRocks 文档要求集群能访问对象存储和 metastore，并为 S3 配置凭证。[StarRocks 概述][s-overview]、[S3 设置][s-s3] | **FE/BE 镜像的 JAR 版本与 SHA-256 已实物核对；EKS IRSA 与查询仍 `NOT_RUN`。** PoC 要记录 FE 与 BE/CN 的真实版本、jar 清单/校验和、driver 可加载性、PostgreSQL/TLS 可达性和只读 S3 访问。若试验 Paimon 2.0.0 reader，须构建并记录独立的 StarRocks FE、JNI 与相关依赖组合及兼容性结果；直接替换单个 jar 不再是官方 4.1.3 发布构件的证据。[FE 规划到 JNI 传递][s-scan]、[JNI reader][s-jni] |

### JDBC 源码入口的有界假设

下面是待验证的**选项映射**，不是可用配置或支持声明。StarRocks 自身使用 `"type"="paimon"`；`paimon.catalog.type=jdbc` 对应 Paimon `metastore=jdbc`；`paimon.catalog.warehouse` 对应**同一** S3 warehouse；`paimon.option.uri`、`paimon.option.jdbc.user`、`paimon.option.jdbc.password`、`paimon.option.catalog-key` 通过 `paimon.option.*` 转发给 Paimon 1.3.1 factory。[StarRocks connector][s-connector]、[Paimon 1.3.1 JDBC 参数][p13-catalog]、[1.3.1 factory][p13-factory]。本仓库 #14 写端的 `catalog-key` 为 `cove-lake-m1-shared`，并使用 JDBC 锁；这两个事实仅定义比较对象，不授权 StarRocks 写目录。本仓库 catalog 定义（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/src/main/java/com/covelake/m1/DorisCatalog.java`） Paimon 2.0.0 对 S3 多写者要求 Hive/JDBC catalog 加锁；查询 PoC 不应改写或重建权威目录。[Paimon 并发说明][p20-lock]。

## 后续有界 PoC（本次未执行）

原装 4.1.3 已有默认 DV/L0 构件级阻断，不作为本项目主查询的直接回退。若为其他表模式单独评估，或以成套重构的 FE/BE/JNI 评估 DV，须先明确配置身份，再由 #14 负责人在私有合成数据环境单独排期。第一轮以不超过 **2 小时**、同一隔离 run ID 为界；每一关保存版本、命令、snapshot ID、预期与实际结果、查询计划/reader 分流及错误回执，失败即停止该候选，不以超时或空行集算成功。

1. **目录与构件（≤30 分钟）。** 核对官方 4.1.3 FE、BE/CN 与 Paimon/driver jar，使用仅具合成表访问权限的 StarRocks 服务身份和只读 PostgreSQL 身份；检验实际 JDBC catalog 是否能读取与 Paimon 2.0.0 写端相同的 `ods` 表清单与 schema。若只能通过 filesystem/Hive 或需要目录 DDL 权限，记 catalog 关口失败。记录 S3 实际使用的身份与跨前缀拒绝；StarRocks 文档的 `aws.s3.use_instance_profile` 示例不足以证明本 EKS 环境的 IRSA 行为。[StarRocks S3 设置][s-s3]。
2. **逐快照正确性（≤60 分钟）。** 在独立隔离表上复用 #14 的非 DV 主键、DV 主键、append 三类合成 schema；每次由 Paimon **2.0.0 原生 reader**固定 snapshot 生成逐行基线，核查真实 L0 文件与维护后真实 deletion file。分别查初始 L0、update/delete 后 L0、压实后 DV、非 DV 更新删除、append 新列；比较完整行集与删除键负向查询，记录 native/JNI 和 DV scan 指标。对两个结果不同的 snapshot 检查固定版本是否真生效。任一 L0 漏行、旧值、文件格式错误或 snapshot 不一致，DV 关口记 RED，不以维护后的正确结果覆盖。所需场景来自 [#14 对账契约](doris-paimon.md)；StarRocks reader 分流依据 [发布源码][s-scan]。
3. **查询面与时延样本（≤30 分钟）。** 对 `SELECT` 点查、全表聚合、append 过滤与 schema 增列分别记录计划、行集、snapshot 提交到新查询首次正确可见时间和单次耗时；time travel、增量读、系统表逐项标 `PASS`、`LIMITED`、`UNSUPPORTED` 或 `NOT_RUN`，缺原生行集对账时不可标 PASS。首轮样本只定位正确性与配置障碍。完整选型仍按 [M0 测量协议](../../design/acceptance-contract.md#21-测量协议)另测：千万行、10 QPS 固定看板与 1 QPS 交互、连续 24 小时、足够样本的分位延迟；外表新鲜度目标 p95/p99 ≤3/6 分钟，固定看板查询 p95/p99 ≤3/10 秒。这些是**目标**，不是现有 StarRocks 实测值。

**退出判定。** 只要“同一 JDBC 权威 catalog”“Paimon 2.0.0 DV/L0 与更新删除逐行正确”“查询功能、负载与安全准入”任一条件无证据或失败，StarRocks 就仍是评估候选。改用 Paimon 1.3.1 写表、只发布已压实旧快照或用 filesystem 绕开目录，均须另立边界，不能算现行 HLD §17 的回退通过。Doris 路径继续保留其原有 RED/`NOT_COMPLETE` 状态；本研究不会把两条路径的证据合并。

<!-- 所有上游源码链接均固定到正式发布标签所指 commit；#L 行号对应该 commit。 -->
[s-tag]: https://github.com/StarRocks/starrocks/releases/tag/4.1.3
[s-overview]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/docs/en/data_source/catalog/paimon_catalog.md#L13-L26
[s-doc]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/docs/en/data_source/catalog/paimon_catalog.md#L122-L132
[s-s3]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/docs/en/data_source/catalog/paimon_catalog.md#L138-L180
[s-query]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/docs/en/data_source/catalog/paimon_catalog.md#L688-L741
[s-connector]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/connector/paimon/PaimonConnector.java#L58-L110
[s-create]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/connector/paimon/PaimonConnector.java#L148-L153
[s-fe-pom]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/pom.xml#L76-L82
[s-fe-deps]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/pom.xml#L670-L680
[s-driver]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/pom.xml#L1180-L1185
[s-fe-driver]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/pom.xml#L731-L736
[s-jni-pom]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/java-extensions/paimon-reader/pom.xml#L12-L16
[s-jni-deps]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/java-extensions/paimon-reader/pom.xml#L81-L115
[s-scan]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/planner/PaimonScanNode.java#L152-L185
[s-be-dv]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/be/src/exec/paimon/paimon_delete_file_builder.cpp#L27-L65
[s-jni]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/java-extensions/paimon-reader/src/main/java/com/starrocks/paimon/reader/PaimonSplitScanner.java#L97-L116
[s-version]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/connector/paimon/PaimonMetadata.java#L440-L489
[s-incremental]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/connector/paimon/PaimonMetadata.java#L576-L594
[s-system]: https://github.com/StarRocks/starrocks/blob/8a8e18642450fba09149c8a3f149abefa043c2b7/fe/fe-core/src/main/java/com/starrocks/connector/paimon/PaimonMetadata.java#L598-L621
[p-tag]: https://github.com/apache/paimon/releases/tag/release-2.0.0
[p20-catalog]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/catalog.md#L78-L94
[p20-lock]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/concurrency-control.md#L38-L46
[p20-config]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/generated/core_configuration.html#L606-L615
[p20-options]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java#L2137-L2146
[p20-batch]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-core/src/main/java/org/apache/paimon/table/source/AbstractBatchTableScan.java#L74-L80
[p13-catalog]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/docs/content/concepts/catalog.md#L80-L95
[p13-factory]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-core/src/main/java/org/apache/paimon/jdbc/JdbcCatalogFactory.java#L28-L43
[p13-service]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-core/src/main/resources/META-INF/services/org.apache.paimon.factories.Factory#L16-L18
[p13-jdbc-init]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-core/src/main/java/org/apache/paimon/jdbc/JdbcCatalog.java#L88-L146
[p13-options]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java#L2919-L2929
[p13-batch]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-core/src/main/java/org/apache/paimon/table/source/DataTableBatchScan.java#L54-L66
[p13-mode]: https://github.com/apache/paimon/blob/28dfdfed24877c5f4c36b7c2409794fc8ef79607/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java#L1225-L1232

> 注：本文中的 issue 与 PR 编号（如 #14）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
