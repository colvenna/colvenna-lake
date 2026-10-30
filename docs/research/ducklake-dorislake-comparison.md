---
title: "对照 DuckLake / DorisLake：Colvenna Lake 的目录与开放度取舍"
date: 2026-10-03
status: draft
sources:
  - https://mp.weixin.qq.com/s/UTmMiBQveU0n6WX7C2kUqA （二手来源：公众号「品睿.数往知来」文章，作为对照框架）
  - https://ducklake.select/docs/stable/ （含 specification、choosing_a_catalog_database、data_inlining、data_change_feed、conflict_resolution）
  - https://ducklake.select/manifesto/
  - https://ducklake.select/release_calendar ; https://ducklake.select/roadmap ; https://ducklake.select/2026/04/13/ducklake-10/
  - https://github.com/duckdb/ducklake-web (115b41c) ; https://github.com/duckdb/ducklake
  - https://flink.apache.org/downloads/ ; https://github.com/apache/doris-website/tree/master/docs/lakehouse/catalogs ; https://docs.starrocks.io/docs/data_source/catalog/catalog_overview/
  - https://github.com/apache/doris/issues/56920 ; https://github.com/StarRocks/starrocks/issues/59898
---

# 对照 DuckLake / DorisLake：Colvenna Lake 的目录与开放度取舍

> 范围：Issue #99。以公众号文章《湖仓一体：DuckLake是个好主意吗？DorisLake有意义吗？》的分层视角和"开放度"尺子为框架，逐项对照 Colvenna Lake 的现有设计与 M1 实测。Colvenna Lake 一侧的陈述都指向仓库内文档章节；文章中的外部事实凡被引用，或在第 2 节用一手出处核实，或标「文章观点」。以「推断：」开头的内容是本文判断。本文只做调研记录，不改变 HLD 的任何决策。查看日期 2026-10-03，仓库基线为 `origin/main` `6f527b4`。

## 1. 文章的论点与评估框架

以下均为文章观点，本文只转述结构，不复述原文：

- **DuckLake**：把湖格式的元数据交给一个事务型 SQL 数据库，数据面保持不可变 Parquet。文章认为机制上是好主意，但第一方引擎只有 DuckDB，Flink、Doris、StarRocks 等没有适配，适合嵌入式与中小规模场景，不适合企业多引擎底座。
- **DorisLake**：文章自己提出的构想（不是 Apache Doris 发布的产品或路线），即 Doris FE 托管元数据与事务、数据存成标准 Parquet 放在 HDFS/S3。文章认为它的意义是"内表 2.0"，不是新湖格式：数据文件开放了，表语义（快照、Schema 演进、删除感知）仍封闭在 FE 私有协议里。
- **两把尺子**：
  - 开放度 = 数据文件 × 元数据规范 × 引擎适配，三项相乘；"能不能适配"由规范决定，"谁来适配、适配得多好"由生态投入决定。
  - 分布式引擎接入"目录放在数据库里"的湖格式，应由主节点独占元数据事务，从节点只读写数据文件并上报文件级状态，避免连接数打爆目录库、保证事务单入口。
- **场景建议**：文章把"流式高频更新湖"推荐为 Paimon + Flink + Doris/StarRocks，这与 Colvenna Lake 的选型一致（[HLD 3.3](../design/hld-realtime-lakehouse.md)）。

## 2. DuckLake 关键事实（一手来源核实）

本节只用 DuckLake 官方文档、规范、博客与各项目官方仓库，查看日期 2026-10-03；`ducklake.select` 文档源码对应 `duckdb/ducklake-web` 提交 `115b41c`。

**版本。** 最新正式版是 DuckLake v1.0（规范 1.0 + `ducklake` 扩展 1.0），2026-04-13 随 DuckDB v1.5.2 发布，官方称其为向后兼容的生产版；下一版（规范 1.1 / 扩展 2.0）暂定 2026-10-21。[来源: https://ducklake.select/release_calendar; https://ducklake.select/2026/04/13/ducklake-10/]

**目录库。** 规范只要求目录库支持事务与 SQL-92 主键约束。[来源: https://ducklake.select/docs/stable/specification/introduction] 官方选型页列出 DuckDB（单客户端）、SQLite（多个本地客户端）、PostgreSQL 12+（多用户、远程客户端）和 MySQL 8+ 四种，并写明因已知问题"do not recommend to use MySQL as a catalog"。[来源: https://ducklake.select/docs/stable/duckdb/usage/choosing_a_catalog_database] MotherDuck 是官方单列的托管 DuckLake 服务（public preview），不是第五种可自选的目录库。[来源: https://ducklake.select/docs/stable/]

**元数据与提交。**

- 元数据全部是目录库里的 SQL 表，v1.0 规范共 28 张，例如 `ducklake_snapshot`、`ducklake_snapshot_changes`、`ducklake_data_file`、`ducklake_delete_file`、`ducklake_file_column_stats`。[来源: https://ducklake.select/docs/stable/specification/tables/overview]
- 对象存储上没有 Avro / JSON 元数据文件，也没有 manifest 或 manifest list。[来源: https://ducklake.select/manifesto/]
- 一次表变更分两步：先把数据文件写到存储，再在目录库里执行一次 SQL 事务；一个已提交事务就是一个快照。[来源: https://ducklake.select/manifesto/; https://ducklake.select/docs/stable/duckdb/advanced_features/transactions]
- 并发冲突靠 `ducklake_snapshot.snapshot_id` 的主键约束检测；没有逻辑冲突时，扩展在目录库内自动重试，无需重写数据文件。[来源: https://ducklake.select/docs/stable/duckdb/advanced_features/conflict_resolution]

**数据文件。** 数据文件只允许 Parquet，写入后永不修改。[来源: https://ducklake.select/docs/stable/specification/tables/ducklake_data_file; https://ducklake.select/docs/stable/duckdb/usage/choosing_storage] 两处要注意：

- 数据内联默认开启，小于 10 行的插入和删除直接写进目录库，flush 前不落 Parquet，所以"数据永远不经过目录库"并不严格成立。[来源: https://ducklake.select/docs/stable/duckdb/advanced_features/data_inlining]
- 删除文件可以是 Parquet 位置删除文件，也可以是实验性、默认关闭的 Puffin 删除向量。[来源: https://ducklake.select/docs/stable/specification/tables/ducklake_delete_file]

**客户端。** 官方文档首页的 "List of DuckLake Clients" 以 DuckDB `ducklake` 扩展为参考实现，DuckDB 扩展列表标注其由 DuckDB 团队维护、支持等级为 "Secondary"（best-effort）。[来源: https://ducklake.select/docs/stable/; https://duckdb.org/docs/current/core_extensions/overview] 其余客户端官方只说"at various levels of maturity"，下表的能力与成熟度均为各仓库 README 自述：

| 客户端 | 维护方 | 读写 | 自述成熟度 |
| --- | --- | --- | --- |
| DataFusion（`datafusion-contrib/datafusion-ducklake`） | Hotdata 发起 | 读写，写需开启 feature flag | alpha |
| Spark（`motherduckdb/ducklake-spark`） | MotherDuck | 只写、仅 append；不支持 Spark 4 | v0.3.0 |
| Trino（`awitten1/trino-ducklake`） | 个人 | DDL、读、INSERT/CTAS、update/delete | Work in progress，无 release |
| Trino（`brikk/trino-ducklake`；官方文档旧链接已 404） | brikk | 读写、MERGE、维护过程、`table_changes` | release v483-0.2.0 |
| PostgreSQL（`relytcloud/pg_ducklake`） | Relyt | 读写、MERGE、`table_changes` | v1.0.2，未标注成熟度 |

[来源: https://github.com/datafusion-contrib/datafusion-ducklake; https://github.com/motherduckdb/ducklake-spark; https://github.com/awitten1/trino-ducklake; https://github.com/brikk/trino-ducklake; https://github.com/relytcloud/pg_ducklake] Pandas/Polars/PySpark 集成不在文档清单里，只出现在官方博客，其 README 自称概念验证、不用于生产。[来源: https://ducklake.select/2026/05/04/ducklake-dataframe/; https://github.com/pdet/ducklake-dataframe]

**Flink / Doris / StarRocks。** 截至查看日期均无 DuckLake 连接器：

- DuckLake 官方文档与扩展源码检索不到这三个引擎。[来源: https://github.com/duckdb/ducklake-web; https://github.com/duckdb/ducklake]
- Flink 的外部 connector 列表与 Table & SQL Connectors 总览均未列出 DuckLake，Apache JIRA 检索为 0 条。[来源: https://flink.apache.org/downloads/; https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/table/overview/; https://issues.apache.org/jira/issues/?jql=project%20%3D%20FLINK%20AND%20text%20~%20%22ducklake%22]
- Doris 的 catalog 文档不含 DuckLake；功能请求 [apache/doris#56920](https://github.com/apache/doris/issues/56920) 仍为 open，贡献者 2026-06-29 回复待 catalog SPI 重构后支持，未见对应 PR 或文档。[来源: https://github.com/apache/doris-website/tree/master/docs/lakehouse/catalogs]
- StarRocks 的 catalog 总览不含 DuckLake；[StarRocks/starrocks#59898](https://github.com/StarRocks/starrocks/issues/59898) 仍为 open。[来源: https://docs.starrocks.io/docs/data_source/catalog/catalog_overview/]

**变更数据。** DuckDB 扩展提供 `table_changes`（以及 `table_insertions` / `table_deletions`），查询任意两个快照（或时间戳）之间的变更，`change_type` 含 `insert`、`update_preimage`、`update_postimage`、`delete`；快照过期会限制可读范围。该函数写在扩展文档而非规范里，其他客户端须各自实现。[来源: https://ducklake.select/docs/stable/duckdb/advanced_features/data_change_feed; https://ducklake.select/docs/stable/specification/queries] 官方提到 streaming 的地方都在写入侧（数据内联解决流式写入的小文件），没有描述持续订阅、流式读取或消费位点；物化视图与增量维护列在路线图"Looking for Funding"下。[来源: https://ducklake.select/2026/04/02/data-inlining-in-ducklake/; https://ducklake.select/roadmap]

**与文章的差异。** 文章的生态判断与上述核实基本一致。需修正或补充的有三处：

- 目录后端里 MySQL 官方不推荐，MotherDuck 是托管服务而不是后端类型。
- 数据内联让小批量数据暂存在目录库里。
- DataFusion 客户端已移到 `datafusion-contrib`，并自标 alpha。

## 3. 按文章的分层视角逐层对照

| 层 | 文章的关注点（文章观点） | Colvenna Lake 的选择 | 仓库依据 |
| --- | --- | --- | --- |
| 存储 | S3 协议已是事实标准，HDFS 退居存量 | Amazon S3 及 S3 兼容存储，计算全部无状态；换云只改端点、凭证与 path-style 开关 | HLD 5.1、5.3 |
| 表格式 | 开放表格式是"数据的 API" | Paimon 2.0.0；决定性理由是带前镜像的完整 changelog 流读与内建 merge engine | HLD 4.1；[三方对比](paimon-vs-iceberg-vs-hudi.md) |
| 目录 | 目录层是战略高地：DuckLake 收进 SQL 库，DorisLake 收进 FE | M2 目标：自建 Paimon REST 服务端 colvenna-catalog，PostgreSQL 后端（尚未交付）；M1 以 Paimon JDBC catalog + `lock.type=jdbc` 作脚手架 | HLD 6.1–6.3 |
| 计算 | 没有单一赢家 | 首版流批均用 Flink（写入、分层流读与批任务）；Doris 查询；Spark 仅在 #15/#16 证实 Flink 不足后单独验证；Trino 按需接入用户已有集群 | HLD 3.3、4.2、9.3、10.2 |
| 开放出口 | 建议双轨制，或兼容 REST Catalog | Paimon Iceberg 兼容发布到自建 Iceberg REST catalog；Glue 仅在需要 Athena 时作为第二目标 | HLD 10.3 |

## 4. 与 DuckLake：相同的动机，不同的元数据落点

**相同点：提交点交给数据库。** 文章把 DuckLake 的核心洞察概括为"元数据问题交还给数据库"。Colvenna Lake 走到 catalog 服务端的原因更具体：Paimon 的 `S3FileIO` 没有覆写原子写方法，快照提交沿用"写临时文件再 rename"，而 S3 的 rename 不能作为提交算法的依据；因此同一张表只要有多个写入方，快照发布就必须由 catalog 保证原子性（[HLD 5.2](../design/hld-realtime-lakehouse.md)，[Paimon 调研 13.1](apache-paimon.md)）。M2 规划的 colvenna-catalog 以 PostgreSQL 为后端，M1 的 JDBC 锁也落在 PostgreSQL 上，本质上同样是把"哪次提交生效"交给数据库裁决。

**不同点：数据库里放多少元数据。** DuckLake 把快照、Schema、数据文件清单与统计信息都放进目录库的普通表，小批量写入还会内联在目录库里（见第 2 节）。colvenna-catalog 的 1.0 能力范围是 catalog API、快照原子提交、bearer 认证、按表临时凭证、服务身份授权与审计（HLD 6.2），不包含把文件清单搬进数据库。M1 的 JDBC 模式下，schema 文件、snapshot、manifest list 与 manifest（Avro）以及数据文件都按 Paimon 规范写在 S3 上（[Paimon 调研 3.1](apache-paimon.md)）。推断：M2 REST 模式下 manifest 与数据文件仍是 S3 上的文件，catalog 接管快照发布；快照与 schema 的具体存放以 M2 实现为准。

| 维度 | DuckLake | Colvenna Lake（Paimon + colvenna-catalog） |
| --- | --- | --- |
| 文件清单与统计 | 目录库内的表，读规划是一次 SQL 查询 | S3 上的 manifest 文件，读规划要读 manifest，并需要 manifest 合并维护（[Paimon 调研 14.5](apache-paimon.md)） |
| 读端依赖 | 必须能连目录库（见第 2 节） | 引擎按 Paimon 规范读 S3 上的文件，不直连 PostgreSQL；但表发现须经 catalog，M2 的按表临时凭证也由 catalog 签发，读端不得另持宽 S3 凭证（HLD 6.2） |
| 提交 | 目录库内一次事务（见第 2 节） | M1：JDBC 锁 + 文件提交；M2 目标：REST snapshot commit 由服务端原子发布 |
| 并发冲突 | `snapshot_id` 主键冲突检测；无逻辑冲突时扩展在目录库内自动重试，不重写数据文件（见第 2 节） | M1 诊断运行中出现明确的文件删除冲突，须先查证快照再决定是否用 `filterAndCommit` 重试（见下文） |

**代价在 M1 诊断中已经出现。** [M1 #7](../history/m1/jdbc-multiwriter.md) 在 JDBC 锁与真实 S3 上的诊断运行（目录带 `PROBE_ONLY.txt`，不算正式验收）发现：提交后丢失回执、再用原始 `commit` 重试同一提交 ID，会额外生成 APPEND 与 COMPACT 快照，重试并不幂等；报错的写者也可能其实已经发布。恢复逻辑因此改为先按提交身份查证快照，已提交则跳过，确实未提交才用 `filterAndCommit`。推断：DuckLake 把冲突检测与重试收进目录库事务，参考实现已内置；但"事务已提交、回执丢失"在数据库事务里同样存在，并不会因此消失。差别在于 Colvenna Lake 的查证与重试逻辑要由平台自己实现并逐故障验证，这份复杂度是为了让多引擎按开放规范读文件而付出的。M2 的 REST snapshot commit（#19）是收窄这一窗口的计划路径，其幂等语义仍待实测。

**为什么不能选 DuckLake。** Colvenna Lake 的 CDC 入湖、分层流读全部依赖 Flink，查询依赖 Doris（HLD 3.3、9）。第 2 节核实：Flink、Doris 均无 DuckLake 连接器；DuckLake 的 `table_changes` 能按两个快照查询带前后镜像的变更，但官方没有持续订阅、流式读取或消费位点的描述。HLD 4.1 把"下游能继续流式消费带前镜像的变更"列为 R1/R4 的决定性要求，HLD 9 以 `consumer-id` 流读上一层并在停机后从位点恢复；DuckLake 目前在机制和引擎两方面都不满足。

## 5. 与 DorisLake：Doris 只做查询层

**定位不同。** 文章的 DorisLake 是"Doris 存算分离 + 数据文件 Parquet 化"。Colvenna Lake 的 Doris 同样以存算分离部署，但只是查询层（[HLD 10.1](../design/hld-realtime-lakehouse.md)）：

- 明细的唯一权威副本永远在 Paimon，Doris 通过 Paimon 外部 catalog 直读 S3。
- Doris 内表只承载两类派生内容：以 Paimon 外表为基表的异步物化视图（聚合结果），以及少量热维表（Unique Key）。
- 1.0 不使用 Doris 写 Paimon，首版写入均走 Flink。

**双轨制，主次相反。** 文章建议 DorisLake 作实时主轨、Iceberg/Paimon 作开放出口。Colvenna Lake 是 Paimon 主轨、Doris 内表作加速轨。推断：这正是文章所说"一旦出现 Spark/Flink ETL，封闭元数据会更早撞墙"的情形——Colvenna Lake 的写入与分层加工全在 Flink，Doris 若持有权威元数据，Flink 就进不了同一个事务边界。

**性能税也要交。** 文章观点：标准 Parquet 只有 min/max、Page Index 与 BloomFilter，弱于 Doris 私有格式的前缀索引、ZoneMap、BitMap 与倒排索引，换成 Parquet 会让谓词下推降级（本文未核实这一比较）。Colvenna Lake 的应对与未核实项：

| 手段 | 状态 | 依据 |
| --- | --- | --- |
| CDC 主键表开 deletion vector，让 Doris 走 native reader | 主键表默认 MOW（HLD 7.4）；HLD 10.1 将其列为待验证的性能候选，M1 实测 L0 读取为 RED（见第 7 节） | HLD 7.4、10.1；[M1 #14](../history/m1/doris-paimon.md) |
| Paimon file index（bloom filter / bitmap / range bitmap） | Paimon 文档支持：append 表在写入前配置（14.4）；主键表上 file index 仅对全量 compaction 文件或 DV 表有效（14.6） | [Paimon 调研 14.4、14.6](apache-paimon.md)；README 接入链路 B |
| Doris 读 Paimon 时是否利用 file index | **未核实**，后续建议见第 10 节 | 仓库内无对应证据 |
| 秒级看板走异步物化视图，热维表进内表 | 设计；Paimon 基表只能定时刷新，不能 ON COMMIT | HLD 10.1 |

推断：文章为 DorisLake 建议的"索引侧车化"（把索引放在 Parquet 旁并由元数据登记），在 Paimon 中已有规范内的 file index 作对应能力；两者实现细节未逐项对比。

## 6. 用"开放度"三项给 Colvenna Lake 定位

| 项 | Colvenna Lake | 说明 |
| --- | --- | --- |
| 数据文件 | 开放 | Parquet / ORC，按 Paimon 规范组织 |
| 元数据规范 | 开放 | Paimon 规范公开；colvenna-catalog 的 M2 目标是实现 Paimon 2.0.0 已发布的 REST 契约（OpenAPI 3.1.1），不是私有协议（HLD 4.3、6.2），服务端尚未交付。这是它与 DorisLake 的 FE 最根本的区别 |
| 引擎适配 | 部分开放 | Flink、Spark、Hive connector 在 Paimon 主仓库维护（HLD 4.2）；Doris 官方只读支持；Trino 为独立仓库（见第 9 节）；其余引擎经 Iceberg 兼容层只读（[三方对比 9](paimon-vs-iceberg-vs-hudi.md)） |

**弱点在"自建服务端"。** 引擎声明支持 Paimon REST，不等于能对接 Colvenna Lake 自己实现的服务端。Doris 对 REST 通用 bearer token 与按表凭证的完整支持尚未核实；不支持则 M2 REST 查询阻断，JDBC / filesystem 只能留在隔离的非敏感验证环境（[HLD 17 第 8 条](../design/hld-realtime-lakehouse.md)）。用文章的话说，Colvenna Lake 押注的是规范，但"适配得多好"——尤其是对第三方服务端的兼容性——要由 #20、#22 自己证明。

**"数据开放 ≠ 表开放"同样适用于 Colvenna Lake 的开放出口。** 文章警告：裸读 Parquet 的引擎没有快照隔离、不感知删除与 compaction。Colvenna Lake 的对应约束已写入设计：

- Iceberg 兼容发布不保证 Paimon 写入后立即可见；主键表在无 Iceberg DV 时只发布已 full compaction 的文件；所有写入与维护必须经 Paimon，Iceberg 侧修改会使 Paimon 视图失效（HLD 10.3，[Paimon 调研 8](apache-paimon.md)）。
- 含 PII 或受限列/行的表默认不发布；开放读端不会自动继承 colvenna-catalog 或 Doris 的用户策略（HLD 10.3、12.5）。
- 按表签发前缀级临时凭证，并须验证客户端没有其他宽 S3 凭证，防止绕过表语义直读文件（HLD 6.2）。

## 7. 规范开放之后，"适配得多好"的实测

文章用 DuckLake 说明"规范公开只解决能不能适配"。Colvenna Lake 在 Paimon 这一侧有同类证据：即使是规范公开、引擎官方支持的组合，跨版本读取仍可能错。

[M1 #14](../history/m1/doris-paimon.md) 在真实 S3 与共享 JDBC catalog 上，用 Paimon 原生 reader 的逐行结果作基线核对 Doris 4.1.4（内置 Paimon 1.4.2 reader）读 Paimon 2.0.0：

- 非 DV 主键表与 append 表的行集：PASS（合成数据单次样本）。
- DV 主键表的 L0 行集：**RED**，首次漏行，更新后读到旧行。
- 维护后（DV 已生成、无 L0 残留）的默认读与历史快照：PASS。
- 主查询路径选型：NOT_COMPLETE。

推断：这与文章判断二的逻辑一致——开放度三项里的"引擎适配"不是一次性的勾选项，而是要按固定版本组合、按读路径逐项实测的持续成本。

## 8. 主从元数据分工：读写链路已是这个形态，目录收敛待 M2

文章认为分布式引擎接入目录型湖格式的标准姿势是"主节点独占元数据事务，从节点只碰数据"。Colvenna Lake 的现状：

- **写入**：Paimon 的 Flink sink 由并行 writer 写文件，在 checkpoint 完成时把 committable 交给 committer 发布快照；committer 是写入链路的单点，官方提供单独给它加内存与 CPU 的选项（[Paimon 调研 6、14.5](apache-paimon.md)）。
- **读取**：Doris BE 的向量化 reader 直读 S3 上的 Parquet/ORC，按 DV 跳过失效行（HLD 10.1）。推断：读规划在 Doris FE 侧完成，仓库内未单独核实。
- **连接收敛**：M1 的 JDBC 模式下，每个写作业与 Doris 都直接持有 PostgreSQL 的 catalog 与锁配置。M2 改为经 HTTP 访问无状态、多副本的 colvenna-catalog，由服务端统一访问 PostgreSQL；HLD 6.3 要求写同一张表的所有作业必须经同一组 colvenna-catalog 实例提交。推断：这同时解决了文章担心的"连接数打爆目录库"与"事务单入口"两个问题，实际连接规模待 #23 的安装与恢复验收。

## 9. 文章与本仓库调研不一致之处

文章的 connector 矩阵把 Paimon 在 Trino 上的适配与 Spark、Flink、Hive 并列为第一方。本仓库核实的情况更弱（[Paimon 调研 7、7.1](apache-paimon.md)）：

- `apache/paimon-trino` 是与 Paimon 主仓库版本解耦的独立仓库，没有 GitHub Release，README 未列版本矩阵。
- 官方文档只示例 filesystem catalog，对 REST 与 JDBC catalog 的支持未核实；写入只限 fixed bucket 主键表与 `bucket=-1` 的非主键表。

这也是 HLD 10.2 不把 Trino 放进默认栈、只按需接入用户已有集群的原因之一（另见 #36）。推断：文章对 Paimon 引擎覆盖的估计偏乐观；以"开放度三项"衡量时，Paimon 的"引擎适配"一项应按逐引擎、逐 catalog 类型的实测打分。

## 10. 对 Colvenna Lake 的启示与后续建议

**与现有设计一致，无需改 HLD：**

- 文章的场景建议与 HLD 3.3、4.1 的选型一致；其对 DorisLake 的批评，正是 HLD 10.1 让 Doris 只做查询层、不持有权威元数据的理由。
- 文章的"主从分工"与"数据开放 ≠ 表开放"两条，在 HLD 6.3、10.3、12.5 中已有对应约束。

**建议在已有 issue 内补充验证（不扩大本 issue 范围）：**

1. 第 5 节列出的 file index 未核实项：可在 #14 或 #32 的查询性能验证中用 `EXPLAIN VERBOSE` 与过滤行数补测。
2. REST snapshot commit 在"提交后丢回执"场景下的幂等语义：建议作为 #19 的显式用例，对照 M1 #7 的 JDBC 诊断结论。
3. 轻量读端：若出现 DuckDB 用户需求，不引入 DuckLake，优先评估 Paimon Iceberg 兼容发布（Paimon 经 Iceberg 被 DuckDB 只读，[三方对比 9](paimon-vs-iceberg-vs-hudi.md)）或 PyPaimon 批读输出 DuckDB（[Paimon 调研 10](apache-paimon.md)），归入 #34 的开放读端评估；两条路径都未在 Colvenna Lake 实测。

## 未能核实的问题

- 文章中 DorisLake 的定义、"开放度三项乘积"与"主从分工"是文章的分析框架，不是任何项目的官方规范；本文只作对照工具使用。
- 文章关于 Doris 3.x 存算分离"FE/MetaService 管元数据、BE 无状态化、数据文件为私有 Segment"的描述，本文未逐项核实；Colvenna Lake 的 Doris 部署形态以 HLD 10.1 为准。
- Doris 读 Paimon 时是否利用 Paimon file index，仓库内无证据。
- Doris 读 Paimon 时的读规划由 FE 完成，本文为推断，未单独核实。
- M2 REST snapshot commit 的幂等语义与连接规模，待 #19、#23 实测。
- DuckLake 社区客户端的读写能力与成熟度只来自各仓库 README 自述，未实测；文章所称"万级并发写者无重试风暴"的官方基准，本文未核实。
- Doris 能否经其 Trino Connector 兼容框架加载社区 trino-ducklake，Doris 与 DuckLake 双方文档均未提及。
- DuckDB 1.5.3–1.5.6 所带 ducklake 扩展没有对外版本号，官方兼容矩阵只列到 DuckDB 1.5.2。

> 注：本文中的 issue 与 PR 编号（如 #99）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
