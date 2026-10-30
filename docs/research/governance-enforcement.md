---
title: "列标签、脱敏与行过滤：引擎能执行什么"
date: 2026-10-17
status: decided
issue: 35
---

# 列标签、脱敏与行过滤：引擎能执行什么

本文记录 #35 开工前的调研结论，决定见 [colvenna-operator LLD](../design/colvenna-operator-lld.md) 的"列标签、脱敏与行过滤"一节。Doris 以 4.1.4 为准（release commit `ad35a140c7fd`），Paimon 以 2.0.0 发布源码为准，Trino 以 484 的文档为准，Ranger 以 2.8.0 为准。结论都来自源码和文档，调研时没有在集群上验证；标为"需实测"的结论由 #35 在 EKS 上的验收确认或否定，本文不作为它们的证据。

源码路径的写法：`FE/` 指 Doris 的 `fe/fe-core/src/main/java/org/apache/doris/`，`BE/` 指 `be/src/`，`RT/` 指 `regression-test/`，`G4` 指语法文件 `fe/fe-core/src/main/antlr4/org/apache/doris/nereids/DorisParser.g4`；Paimon 的路径相对于它的仓库根；Doris 文档的路径相对于 `apache/doris-website` 的 `versioned_docs/version-4.x/`（读取时 HEAD 为 `aaeed3f933f7`），在线版在 <https://doris.apache.org/docs/4.x/>；Trino 的源码行号取自 `trinodb/trino` master `951e58383ef7`。部署中的 FE 仍是官方 `fe-core` 的字节，只换了 Paimon SDK 的 JAR、重编了 BE 的 JNI connector（[Paimon 2.0 读取器](../history/m1/paimon2-reader-candidate.md)的"固定输入与替换方式"），所以 FE 侧关于策略和权限的结论直接适用。

## 结论

1. **原生 Row Policy 能建在 Paimon 外表上，按会话用户生效；没有策略就全量可见。**
   - 表名是多段标识符，写作 `ON <Doris 外部 catalog>.<库>.<表>`（`G4:228-232`；`FE/nereids/parser/LogicalPlanBuilder.java:2548-2556`；`FE/info/TableNameInfo.java:60-74`）。catalog 指 Operator 为看板在 Doris 里建的外部 catalog，不是 Paimon 的 warehouse；同一张表挂在两个看板 catalog 下，就要各建一条。官方文档只有两段名的示例（<https://doris.apache.org/docs/4.x/sql-manual/sql-statements/data-governance/CREATE-ROW-POLICY>），外表上需实测。
   - 分析阶段由 `CheckPolicy` 在表扫描上方插入 `LogicalFilter`（`FE/nereids/jobs/executor/Analyzer.java:130-134`、`FE/nereids/rules/analysis/CheckPolicy.java:53-105`）。Paimon 表绑定成 `LogicalFileScan`，它是 `CatalogRelation`（`FE/nereids/rules/analysis/BindRelation.java:554-559`、`FE/nereids/trees/plans/logical/LogicalFileScan.java:65`），所以外表也覆盖。
   - 同样按调用者套用的入口：
     - 视图里引用的表，以及直接建在视图上的策略（`BindRelation.java:696-750`、`CheckPolicy.java:74,111-121`；回归用例 `RT/suites/account_p0/test_view_row_policy.groovy:74-87`）；
     - time travel 与 `@incr/@branch/@tag/@options`，它们按同一表名匹配（`G4:1492-1498,1972-1975`）；
     - CTAS 与 INSERT SELECT 的源查询；EXPORT（`FE/load/ExportJob.java:321-338`，任务以提交者身份执行）；`SELECT … INTO OUTFILE`。
   - 不覆盖 root 与 admin：`FE/nereids/trees/plans/logical/LogicalCheckPolicy.java:152-155` 与 `'root'@'%'`、`'admin'@'%'` 精确比较（`FE/analysis/UserIdentity.java:289-295`）。其他持有 ADMIN_PRIV 的用户不豁免，但能删策略。
   - 不覆盖物化视图刷新：刷新以 `UserIdentity.ADMIN` 运行（`FE/mtmv/MTMVPlanUtil.java:131-135`），MV 里是未经过滤的数据。MV 本身是 OLAP 关系，用户查 MV 时只受 MV 上的策略约束。
   - 不覆盖 Paimon 系统表（`tbl$audit_log`、`$ro`、`$binlog`、`$files` 等）和 `paimon_meta()` 表函数，见"必须关闭的入口"。
   - 组合：RESTRICTIVE 之间取 AND，PERMISSIVE 之间取 OR，两类并存时为 `AND(restrictive…) AND OR(permissive…)`（`LogicalCheckPolicy.java:222-252`）。user 与 role 的策略收进同一个列表合并，不分层（`FE/policy/PolicyMgr.java:358-388`），所以角色与用户的 PERMISSIVE 取并集，可见范围会扩大。
   - 没有匹配的策略就不加过滤（`CheckPolicy.java:98-100`）；策略按 catalog、库、表名的字符串匹配（`FE/policy/RowPolicy.java:190-198`），表名只在 `lower_case_table_names` 下转小写（`TableNameInfo.java:65-67`），改名或大小写不一致即失配。两者都是 fail-open；反过来，表删除后同名重建，策略仍然生效。
   - 策略本身失效时是 fail-closed：回放时谓词解析失败只记日志（`RowPolicy.java:159-180`），之后取过滤表达式时抛错，查询失败（`RowPolicy.java:224-230`、`LogicalCheckPolicy.java:226-231`）。
   - 给每个受众角色加一条 `AS PERMISSIVE TO ROLE r USING (1 = 0)` 基线，没有别的 PERMISSIVE 时就是 0 行，"身份未映射"因此是 fail-closed。建策略时只核对列名（`FE/nereids/trees/plans/commands/CreatePolicyCommand.java:159-167`），按源码应接受常量谓词，需实测。
2. **列权限对外部 catalog 走同一条检查路径，但只有内表的回归证据。**
   - 只有 SELECT_PRIV 能带列（`FE/catalog/AccessPrivilege.java:87-89`），且必须指定具体的表（`FE/nereids/trees/plans/commands/GrantTablePrivilegeCommand.java:162-165`）；权限的键里带 catalog 名（`FE/mysql/privilege/Role.java:95-96`）。列名不对照 schema、不转小写（`FE/catalog/AccessPrivilegeWithCols.java:68-86`），写错时拒绝。
   - 重写阶段的 `CheckPrivileges` 对每个 `LogicalCatalogRelation` 求实际用到的列，谓词、连接、排序里的列都算（`FE/nereids/rules/rewrite/CheckPrivileges.java:94-101,116-136`）；`SELECT *` 只要有一列没授权就报 `Permission denied`（`FE/mysql/privilege/Auth.java:421-430`；`RT/suites/nereids_p0/authorization/column_authorization.groovy:69-70`）。
   - 这条路径与 catalog 类型无关，但源码里没有外表专门的分支，回归用例只用内表（`RT/suites/auth_p0/test_select_column_auth.groovy`），外表需实测。系统表不按列检查，改查源表的表级 SELECT（`FE/nereids/rules/analysis/UserAuthentication.java:58-64`）。
3. **Doris 没有不依赖 Ranger 的脱敏；Ranger 能执行，但代价大。**
   - 内置控制器的 `evalDataMaskPolicy` 直接返回空（`FE/mysql/privilege/InternalAccessController.java:83-87`），语法里的 POLICY 只有 ROW、STORAGE、WORKLOAD 三种（`G4:228-243`）；文档写明 Ranger "is currently the only supported configuration path"（`admin-manual/auth/authorization/data.md:102`）。
   - 视图里可用的内置函数：`digital_masking`、`mask`、`mask_first_n`、`mask_last_n`、`md5`、`sha2`、`sm3`（`FE/catalog/BuiltinScalarFunctions.java:764,918-921,1038,1044`）。
   - Ranger（`fe.conf` 设 `access_controller_type=ranger-doris`）的行过滤与脱敏覆盖外部 catalog：没设 `access_controller.class` 的 catalog 映射到默认控制器（`FE/mysql/privilege/AccessControllerManager.java:119-141`）。回归用例覆盖了 Hive catalog（`RT/suites/ranger_p2/test_ranger_masking.groovy`、`test_ranger_row_level_filter.groovy`），没有 Paimon 的。
   - 行过滤取 Ranger 返回的一条 `filterExpr`，类型固定为 RESTRICTIVE（`FE/catalog/authorizer/ranger/RangerAccessController.java:91-121`、`FE/mysql/privilege/RowFilterPolicy.java:25-26`）；脱敏按 servicedef 里的 transformer 生成表达式，例如 `MASK_HASH` 为 `hex(sha2({col},256))`，由 `CheckPolicy` 作为投影套在扫描上方（`RangerAccessController.java:123-166`）。
   - 开启后全局禁止 GRANT（`FE/nereids/trees/plans/commands/GrantTablePrivilegeCommand.java:88-91`："Grant is prohibited when Ranger is enabled."），Operator 现有的"GRANT 视图给用户"要改为调 Ranger REST。原生 Row Policy 也不再被读取，同一个 catalog 上两者只能二选一。
   - 发给 Ranger 的请求只带用户名和 IP，不带 Doris 的角色（`FE/catalog/authorizer/ranger/doris/RangerDorisAccessController.java:76-86`），用户要在 Ranger 里手工建同名账号（`ranger.md:323`）；审计的代码被注释掉（同文件 49-61 行），Ranger 收不到 Doris 的审计。
   - 策略靠轮询拉取，撤销有延迟；系统表照样绕过；脱敏按列名绑定，列改名后新名字不再脱敏（fail-open）。
   - 部署要 Ranger Admin 加关系库（`apache/ranger` 的 `release-ranger-2.8.0:security-admin/scripts/install.properties:26-27`）。审计存储默认是 Solr（`install.properties:91-93`），只服务于 Ranger 的审计检索，对执行不是必需的。Doris 4.1.4 的 FE 打包的是 `ranger-plugins-common` 2.8.0（`fe/pom.xml:376`），所以 Ranger Admin 2.8.x 是最自然的搭配。
   - Doris 的 servicedef 与插件不在上游：`apache/ranger` 的 `service-defs/` 下有 presto、trino，没有 doris（2026-10-17 核对）。文档让用户从 SelectDB 下载 `3.0.0-SNAPSHOT` 插件、从个人 fork `morningman/ranger` 的 `doris-plugin` 分支（HEAD `74986d8f57`）取 servicedef（`admin-manual/auth/authorization/ranger.md:60,76`），与正式版 Admin 的兼容性没有声明，需实测。
4. **视图：查询时只检查视图的权限，视图里的策略按调用者评估。**
   - `CheckPrivileges.visitLogicalView` 检查视图上用到的列后就返回，不下钻底表（`CheckPrivileges.java:79-85`），视图之后才被内联（`FE/nereids/jobs/executor/Rewriter.java:497-506`）。M3 已实测用户读得到视图、读不到底表（[M3 看板](../history/m3/dashboards.md)）。
   - 建视图时按定义者完整规划一次（`FE/nereids/trees/plans/commands/info/BaseViewInfo.java:194-197`），之后不复查；`*` 在建视图时展开（`FE/nereids/trees/plans/commands/info/CreateViewInfo.java:74-75`），上游新增的列不会自动出现在视图里。
   - 视图体用同一个 ConnectContext 重新分析（`BindRelation.java:696-750`），中间不切换到定义者：`CURRENT_USER()` 在 FE 折叠为查询用户的 `'alice'@'%'`（`FE/nereids/rules/expression/rules/FoldConstantRuleOnFE.java:367-371`），`USER()` 与 `SESSION_USER()` 带客户端 IP（同文件 379-389 行）。所以可以写按用户的过滤，例如与授权映射表做内连接，映射缺失时自然是 0 行。
   - MV 刷新以 admin 运行（`MTMVPlanUtil.java:135`），MV 定义里的 `CURRENT_USER()` 得到的是 admin，所以按用户的过滤只能写在 MV 之上的视图里。SQL cache 的键里有用户身份（`FE/common/cache/NereidsSqlCacheManager.java:318-325`），外表也不走 SQL cache（`BindRelation.java:628-650`），不会跨用户命中缓存。
   - 用户拿不到底表的行（源码确认），但能看到元数据：
     - 对视图有 SELECT 就能在 `information_schema.views.VIEW_DEFINITION` 读到完整 SQL（`FE/service/FrontendServiceImpl.java:712-716,777-779`、`BE/information_schema/schema_views_scanner.cpp:102-105`）。M3 已撤销 `information_schema`，但用户仍能看到自己视图的定义（[M3 看板](../history/m3/dashboards.md)）。
     - `SHOW CREATE VIEW` 需要 SHOW_VIEW_PRIV，只有 SELECT 会被拒（`FE/nereids/trees/plans/commands/ShowCreateViewCommand.java:74-87`）；`DESCRIBE` 只返回视图的列（`FE/nereids/trees/plans/commands/DescribeCommand.java:261-270`）。
     - EXPLAIN 与查询做同样的权限检查，给出内联后的计划，含底表的 catalog、库、表名，策略谓词和脱敏表达式；VERBOSE 下还有 Paimon split 的行数和数据文件的 S3 路径（`FE/datasource/FileScanNode.java:173-226`、`FE/datasource/paimon/source/PaimonScanNode.java:1077-1110`）。
   - 谓词合并与侧信道见下文。
5. **Paimon 2.0.0 没有列标签；query-auth 存在，但 Doris 侧未验证。**
   - 发布版没有 entity label 或列标签：`DataField` 的 JSON 只有 `id/name/type/description/defaultValue`（`paimon-api/.../types/DataField.java:134-147`），`Schema` 只有 `fields/partitionKeys/primaryKeys/options/comment`（`paimon-api/.../schema/Schema.java:58-62`），REST 里的 "tag" 指快照 tag。
   - 可用的载体是列 `description`、表 `comment` 和表 `options`，Doris 都读得到（`FE/datasource/paimon/PaimonExternalTable.java:505-516,545-557`），但不据此执行任何控制。它们只是目录里保存的分类，由 Operator 编译成视图投影、脱敏表达式或策略之后才算执行。
   - `query-auth.enabled`（默认 false，`paimon-api/.../CoreOptions.java:2304-2310`）让扫描规划时调用 REST `POST /v1/{prefix}/databases/{db}/tables/{table}/auth`（`paimon-core/.../rest/RESTCatalog.java:681-699`），服务端返回 `filter` 与 `columnMasking`，由 Paimon reader 执行（`paimon-core/.../table/source/AbstractDataTableRead.java:106-160`）。
   - Doris 源码里没有提到 QueryAuth。按源码推断，这类 split 不是 `DataSplit`，会交给 JNI 读取器、由 SDK 执行，这张表因此失去 native reader（`PaimonScanNode.java:496-515`、`fe/be-java-extensions/paimon-connector/.../PaimonJniScanner.java:239`），未验证。`ignore_split_type=IGNORE_JNI` 会跳过这些 split，静默返回 0 行；用户不能用 `@options` 关掉 query-auth，它不在白名单里（`FE/datasource/paimon/PaimonScanParams.java:50-63,101-106`）。
   - 身份只到 catalog token，即看板一级，传不到终端用户。colvenna-catalog 的 `/auth` 目前只校验列清单，返回空的 filter 和 mask（`colvenna/colvenna-catalog` 的 `v1.0.0-rc.1:src/main/java/com/colvenna/catalog/http/CatalogRoutes.java:531-549`、`v1.0.0-rc.1:src/main/java/com/colvenna/catalog/catalog/CatalogService.java:1329-1360`；服务端源码在 #295 移出了本仓库，所引的内容与移出前相同），所以这条路径目前属于"只在目录保存的策略"，必须保持关闭。
6. **Trino 的 file-based access control 在引擎层执行，与连接器无关。**
   - 表规则的 `filter`、`columns` 里的 `mask` 与 `allow: false` 由 `StatementAnalyzer.analyzeFiltersAndMasks` 套在表引用、视图和物化视图上（`core/trino-main/src/main/java/io/trino/sql/analyzer/StatementAnalyzer.java:2539-2574`）；同一列有多个脱敏规则时报 `INVALID_COLUMN_MASK`（`core/trino-main/src/main/java/io/trino/security/AccessControlManager.java:1485-1528`）。
   - 规则从上到下第一条匹配的生效，有规则但都不匹配时拒绝，完全没有规则时放行，且不适用于 `information_schema`（<https://trino.io/docs/current/security/file-system-access-control.html>）。
   - 规则可以配在系统级（`etc/access-control.properties` 的 `access-control.name=file`），也可以配在 catalog 级（`iceberg.security=FILE`），catalog 级规则支持 `{user}` 占位符。`iceberg.security` 默认 `ALLOW_ALL`（`plugin/trino-iceberg/.../IcebergSecurityConfig.java:21-29`），系统级规则照常生效。
   - Iceberg 的元数据表是独立的表名，必须单独拒绝：`$files` 带列的上下界，`$partitions` 带每列的 min/max，`$manifests` 带分区摘要，`orders.*` 这类正则会匹配到 `orders$files`；隐藏列 `$path`、`$file_modified_time` 暴露文件路径（<https://trino.io/docs/current/connector/iceberg.html>）。
   - Trino 要自己做终端用户认证；它连 Iceberg REST 用的是 catalog 级凭证，和 Doris 一样传不到终端用户。
7. **推荐：Operator 生成的对象是唯一的执行点。**
   - 视图、物化视图和内表做列投影、内置函数脱敏和固定谓词；行过滤要构成保密边界时，按受众物理分表。
   - 同一对象要按用户或角色分行时，在内部视图或 MV 上叠加原生 Row Policy，并加 PERMISSIVE `FALSE`（`1 = 0`）基线，身份缺失时返回 0 行。
   - 不启用 Ranger，不授予任何 Paimon catalog 权限；关闭系统表、`paimon_meta`、文件类表函数的环境凭证、视图定义的可见性和写权限；Trino、query-auth、Flink 与 Spark 的读路径保持关闭。

## 机制取舍

| 机制 | 能覆盖 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| Operator 生成的视图、物化视图、内表 | 行（固定谓词或按受众物理分表）、列（投影）、脱敏（内置函数） | 沿用 M3 的授权模型；上游新增列默认不暴露；脱敏不会被谓词绕过；物化之后没有侧信道 | 动态按用户分行要靠 `CURRENT_USER()` 或 Row Policy；视图定义对用户部分可见 | **主机制** |
| 原生 Row Policy | 行；可以挂在内部视图或 MV 上 | 按用户或角色；挂在视图上有回归证据 | 没有策略就全量可见；按名字绑定；不覆盖系统表、MV 刷新、root 与 admin；侧信道与视图相同 | **辅助**：只挂内部对象，加 `FALSE`（`1 = 0`）基线 |
| 列权限 `SELECT_PRIV(col…)` | 列 | 引擎执行 | 视图投影已能做到；外表未验证 | 不用于外表；共享视图上可选 |
| Ranger（`ranger-doris`） | 行、列、脱敏 | 集中管理 | 部署重；插件来自 fork；全局禁 GRANT；取代原生 Row Policy；没有审计；不覆盖系统表；列改名即失效 | #35 内**不采用** |
| Paimon query-auth | 行、脱敏 | 策略由目录下发，引擎侧的 SDK 执行 | 未验证；身份只到看板；失去 native reader；colvenna-catalog 未实现 | **关闭**，留作后续 |

- 分类：用 colvenna-catalog 的 table option 约定（例如 `colvenna.classification.<col>=pii|internal|public`）或列 `description`。Operator 读出后编译进视图定义，并复制到视图列的 COMMENT；没有分类或分类未知的列不发布（fail-closed）。
- 列：视图的 SELECT 列表只放已放行的列，要脱敏的列写成 `mask(c)`、`mask_last_n(c,4)`、`sha2(c,256)` 或 `NULL AS c`。
- 行：优先按受众物化，每个受众一个 MV 或内表，用固定谓词；共享对象用内部视图或 MV 上的 Row Policy 加基线，或用 `CURRENT_USER()` 内连接授权映射表。不在 Paimon 外表上建策略：用户本来就没有外表权限，而 MV 刷新以 admin 运行，会绕过这些策略。

LLD 最终的做法与这里的建议有一处不同：Operator 按用户而不是按角色建策略，每个用户在每个对象上一条 RESTRICTIVE 策略，没有声明条件的用户为 `1 = 0`。不用角色，PERMISSIVE 跨角色取并集、扩大可见范围的问题就不会出现；没有策略的用户也拿不到授权（先建策略再授权）。

## 必须关闭的入口

| 入口 | 风险 | 关闭方式 |
| --- | --- | --- |
| Paimon catalog 的任何库、表、列权限 | 系统表、`paimon_meta`、time travel、`@incr/@branch/@tag/@options`、EXPORT 绕过行策略。系统表名是 `tbl$xxx`，策略按 `tbl` 存，所以不匹配；权限却按源表 `tbl` 做表级 SELECT（`BindRelation.java:411-458`、`FE/datasource/paimon/PaimonSysExternalTable.java:89-97`、`UserAuthentication.java:56-64`）。有表级 SELECT 就能读 `$audit_log`、`$ro`、`$binlog` 的全行数据和 `$files` 的 `min_value_stats/max_value_stats`（`paimon-core/.../table/system/FilesTable.java:101-126`）。`paimon_meta()` 只校验表级 SELECT（`FE/tablefunction/PaimonTableValuedFunction.java:154-180`）。源码里没有关闭系统表的配置项 | 只有 Operator 持有这些权限，用户只拿内部对象的 SELECT（M3 已如此） |
| `SHOW CREATE CATALOG` | 里面有 token，拿到就能自建读取 | M3 已拒绝 |
| 写与 DDL 权限（CREATE、ALTER、LOAD、DROP、GRANT） | 改对象、改策略、导出 | 不授予 |
| 视图定义的可见性 | 暴露策略谓词和底表名（只是元数据） | 不授 SHOW_VIEW_PRIV，撤销 `information_schema`（M3 已做）；EXPLAIN 记为已知的元数据暴露，或按用户加 SQL_BLOCK_RULE |
| 文件与 URL 类表函数：`s3()`、`hdfs()`、`local()`、`http()`、`file()` | FE 侧没有权限检查，只在用 resource 时检查 USAGE（`FE/tablefunction/ExternalFileTableValuedFunction.java:662-671`、`FE/nereids/rules/analysis/BindExpression.java:1770-1792`、`CheckPrivileges.java:87-92`）。没有 AK/SK 时 FE 和 BE 都走默认凭证链（`FE/datasource/property/storage/S3Properties.java:181-188,342-366`、`BE/util/s3_util.cpp:372-405`），用户还能指定 `s3.credentials_provider_type=INSTANCE_PROFILE` 或 `s3.role_arn`。`http()` 可被当作 SSRF 跳板（`FE/tablefunction/HttpTableValuedFunction.java:63-65`）。文档 `sql-manual/sql-functions/table-valued-functions/local.md:54-57` 写着需要 ADMIN_PRIV，FE 源码里没有这项检查 | Doris Pod 的 IRSA 角色读不到表数据（`deploy/scripts/doris/install-doris.sh:6-7`），也不给它 `sts:AssumeRole`；IMDSv2 hop-limit 设为 1，或让节点角色也没有湖权限；NetworkPolicy 限制出站；全局 SQL_BLOCK_RULE 作纵深防御，它只匹配正则，可能被绕过（`FE/blockrule/SqlBlockRuleMgr.java:221-247`） |
| `SELECT … INTO OUTFILE` 写到 S3 或 HDFS | 行策略仍然生效，但写远端不需要额外权限，只有本地文件受开关限制（`FE/analysis/OutFileClause.java:485-487`），是数据外发通道 | SQL_BLOCK_RULE 加出站限制 |
| root 与 admin | 不受策略约束 | 只给 Operator 和安装脚本使用（M3 已如此） |
| Arrow Flight SQL（默认端口 8070，`fe/fe-common/src/main/java/org/apache/doris/common/Config.java:570`）、FE HTTP 的 `/api/query/{ns}/{db}`（`FE/httpv2/rest/StmtExecutionAction.java:85`）与 `/_query_plan`（`FE/httpv2/rest/TableQueryPlanAction.java:106-140`） | 走同一个规划器，但都是额外的接口 | 用 NetworkPolicy 关闭，或纳入同一套测试 |
| Ranger、Paimon query-auth、Trino、Flink 与 Spark 的读路径 | 未经 #35 验证 | 支持矩阵里标为关闭；终端用户不持有 data token |

## 侧信道与限制

- 视图在权限检查之后被内联（`Rewriter.java:497-506`），之后照常做谓词下推。`MergeFilters` 把用户 WHERE 的合取项放在视图或策略谓词之前（`FE/nereids/rules/rewrite/MergeFilters.java:45-50`）。
- BE 的 Parquet 读取器在 `enable_adjust_conjunct_order_by_cost` 打开时（默认 true，用户可以 SET）按代价重排合取项（`BE/format/parquet/vparquet_group_reader.cpp:269-273`、`FE/qe/SessionVariable.java:3518-3519`）。所以策略谓词不保证先执行。
- 没有 security barrier：源码里没有 security barrier 或 leakproof 的概念。`NoneMovableFunction`（如 `assert_true`）只阻止过滤条件穿过含有它的投影，不阻止含有它的过滤条件下推（`FE/nereids/rules/rewrite/PushDownFilterThroughProject.java:57`）。
- 预言机：用户可以调用 `assert_true(cond, 'literal')`（`BuiltinScalarFunctions.java:666`），第二个参数必须是常量，报错信息带不出值，但"报不报错"本身就是 1 bit 的预言机；`sleep()`（同文件 1043 行）可以构成计时预言机。可利用性需实测。
- 列脱敏是稳的：脱敏写在投影里，用户对该列的谓词下推时被替换成脱敏表达式，碰不到原值。
- 行过滤不是保密边界：视图 WHERE 和 Row Policy 都挡不住有意探测被过滤行在已暴露列上的取值。行过滤要构成保密边界（其他租户、其他区域）时，按受众建 MV 或内表、只装允许的行，用户扫描的对象里就没有隐藏行；动态的 `CURRENT_USER()` 视图或 Row Policy 只用在剩余风险可以接受的场景，并写进支持矩阵的限制。

## 需实测

按风险排序：

1. 有表级 SELECT 的用户读 `t$audit_log`、`$ro`、`$binlog`、`$files` 能绕过行策略，这验证关闭 Paimon catalog 权限的必要性。
2. `s3()` 在默认凭证链、`INSTANCE_PROFILE`、`s3.role_arn` 三种方式下读 warehouse，都应失败；非 ADMIN 用户调用 `local()` 的行为（文档与源码不一致）。
3. 视图 WHERE 和 Row Policy 下，`assert_true` 与 `sleep` 能否探测到被过滤的行；如果能，就把"动态行过滤不是保密边界"写进限制。
4. Row Policy 建在 Paimon 外表上和内部视图或 MV 上的允许与拒绝；`PERMISSIVE 1 = 0` 基线；role 与 user 的组合；catalog 或表改名后的 fail-open。
5. 外部 catalog 的列权限：`SELECT *`、谓词里的列、`count(*)`。
6. schema 变化：新增列不暴露；删除或改名被引用的列时报错，Operator 停止发布。
7. `information_schema.views`、EXPLAIN、query profile 实际暴露的内容。
8. （后续）开启 query-auth 后，Doris 是否经 JNI 执行 colvenna-catalog 返回的 filter 和 mask，以及对性能和系统表的影响。

Issue #35 的 EKS 验收回答了其中的第 1、2、3、6、7 项（[验收](../history/m4/governance.md)）：系统表被拒（用户没有 catalog 的权限）；`s3()` 三种方式都被 IAM 拒绝；`local()` 果然不检查权限，默认能读 BE 目录下的表数据与日志，平台把 BE 的 `user_files_secure_path` 指向空目录后读不到任何文件；`assert_true` 在验收的三种写法里都没有探到隐藏的行；`EXPLAIN` 只暴露计划。验收还发现 `paimon_meta()` 在 Doris 4.1.4 的 SQL 里没有注册（`Can not found function 'paimon_meta'`），上文按源码列出的这条入口实际不存在。第 4 项只在 Operator 建的查询对象上验证（按用户的 RESTRICTIVE 策略，没有条件的用户为 `1 = 0`），Operator 不在外表上建策略、不用角色；第 5 项不再需要（不用列权限）；第 8 项留作后续。

> 注：本文中的 issue 与 PR 编号（如 #35）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
