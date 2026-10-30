---
title: "Apache Paimon vs Apache Iceberg vs Apache Hudi：基于一手资料的深度对比"
date: 2026-09-28
status: draft
sources:
  - https://iceberg.apache.org/spec/
  - https://iceberg.apache.org/docs/latest/
  - https://iceberg.apache.org/rest-catalog-spec/
  - https://iceberg.apache.org/multi-engine-support/
  - https://iceberg.apache.org/vendors/
  - https://hudi.apache.org/docs/overview
  - https://hudi.apache.org/learn/tech-specs
  - https://hudi.apache.org/docs/concurrency_control
  - https://hudi.apache.org/docs/sql_queries
  - https://hudi.apache.org/releases/release-1.0
  - https://paimon.apache.org/docs/master/
  - https://paimon.apache.org/docs/master/concepts/spec/
  - https://paimon.apache.org/docs/master/primary-key-table/
  - https://paimon.apache.org/docs/master/concepts/concurrency-control
  - https://paimon.apache.org/docs/master/iceberg/
  - https://xtable.apache.org/docs/features-and-limitations
  - https://incubator.apache.org/projects/paimon.html （及 iceberg.html、hudi.html）
  - https://cwiki.apache.org/confluence/display/INCUBATOR/PaimonProposal （及 IcebergProposal、HudiProposal）
  - https://api.github.com/repos/apache/paimon （及 apache/iceberg、apache/hudi）
---

# Apache Paimon vs Apache Iceberg vs Apache Hudi：三方深度对比

> 本文只做三方对比，Paimon 单项目的完整介绍见同目录 `apache-paimon.md`。所有结论均标注一手来源；官方文档未明确、仅为本文推断的内容以「推断：」标出。文档版本：Iceberg 1.11.0 / Hudi 1.2.1 / Paimon master（2.2-SNAPSHOT，稳定版 2.0）。

## 1. 项目背景速览

（查看日期 2026-09-28。GitHub 数据取自 `api.github.com`；贡献者数为 contributors 端点含匿名提交者的分页计数，仅供量级参考。）

| 维度 | Apache Iceberg | Apache Hudi | Apache Paimon |
| --- | --- | --- | --- |
| 起源 | Netflix 内部开发后捐赠 ASF | Uber 内部项目 "Hoodie"，2016 年生产、2017 年开源 | 2022 年以 "Flink Table Store" 诞生于 Flink 社区，主要开发者来自阿里巴巴 |
| 进入孵化器 | 2018-11-16 | 2019-01-17 | 2023-03-12 |
| 毕业为顶级项目 | 2020-05-20 | 2020-05-20 | 2024-03-21 |
| 最新稳定版（发布日） | 1.11.0（2026-05-19） | 1.2.1（2026-09-24） | 2.0.0（2026-08-07） |
| 格式版本 | 表格式 v1/v2/v3 已采纳，v4 开发中 | table version 8（1.0.0）、9（1.1.0+） | snapshot JSON `version` = 3 |
| Stars / Forks | 9,280 / 3,562 | 6,274 / 2,524 | 3,408 / 1,403 |
| 贡献者（含匿名） | ≈899 | ≈597 | ≈398 |
| 最近 push | 2026-09-28 | 2026-09-28 | 2026-09-28 |

- 起源与孵化时间 [来源: https://cwiki.apache.org/confluence/display/INCUBATOR/IcebergProposal ; https://cwiki.apache.org/confluence/display/INCUBATOR/HudiProposal ; https://cwiki.apache.org/confluence/display/INCUBATOR/PaimonProposal ; https://incubator.apache.org/projects/iceberg.html ; https://incubator.apache.org/projects/hudi.html ; https://incubator.apache.org/projects/paimon.html]
- 版本与日期 [来源: https://iceberg.apache.org/releases/ ; https://api.github.com/repos/apache/hudi/releases ; https://api.github.com/repos/apache/paimon/releases]；格式版本 [来源: https://iceberg.apache.org/spec/ ; https://hudi.apache.org/learn/tech-specs ; https://paimon.apache.org/docs/master/concepts/spec/snapshot]

## 2. 设计哲学与定位

- **Iceberg：开放表格式、引擎中立。** 官方定义："Apache Iceberg is an open table format for huge analytic datasets." 强调 "designed and developed to be an open community standard with a specification to ensure compatibility across languages and implementations" [来源: https://iceberg.apache.org/docs/latest/]。规范目标包括 Serializable isolation、O(1) 远程调用的 scan planning、"Job planning will be handled primarily by clients and not bottleneck on a central metadata store" [来源: https://iceberg.apache.org/spec/]。
- **Hudi：事务型数据湖平台、增量处理。** README："Apache Hudi is an open data lakehouse platform, built on a high-performance open table format to ingest, index, store, serve, transform and manage your data" [来源: https://github.com/apache/hudi]。文档称其 "pioneered the concept of 'transactional data lakes'"，表格式 "purpose-built for high performance writes on incremental data pipelines" [来源: https://hudi.apache.org/docs/overview]；并明确自我定位为超越表格式的平台："the Hudi stack expands out of being just a 'table format' to a comprehensive and robust data lakehouse platform" [来源: https://hudi.apache.org/docs/hudi_stack]。
- **Paimon：实时湖仓、流批一体、LSM。** README："Apache Paimon is a lake format that enables building a Realtime Lakehouse Architecture with Flink and Spark for both streaming and batch operations. Paimon innovatively combines lake format and LSM structure, bringing realtime streaming updates into the lake architecture" [来源: https://github.com/apache/paimon]。master 文档首页的新口号为 "Data Lake Platform — unified batch, streaming, and multimodal AI in a single lake format"，三大支柱为 Large-Scale Analytics、Realtime Streaming、Multimodal AI [来源: https://paimon.apache.org/docs/master/]。README 还坦承 "The architecture refers to some design concepts of Iceberg"。

孵化期的一句话定义更能看出原始出发点：Iceberg 是 "a table format for large, slow-moving tabular data"；Hudi 提供 "atomic upserts and incremental data streams on big data"；Paimon 是 "a unified lake storage to build dynamic tables for both stream and batch processing" [来源: https://incubator.apache.org/projects/iceberg.html ; https://incubator.apache.org/projects/hudi.html ; https://incubator.apache.org/projects/paimon.html]。

推断：三者已从"表格式"话语向"平台"话语靠拢（Hudi、Paimon 尤为明显），Iceberg 仍最坚持"规范优先、引擎中立"。

## 3. 元数据与文件布局

**Iceberg**：表状态存于 `metadata.json`（schema、partition spec、properties、snapshots 列表）；"All changes to table state create a new metadata file and replace the old metadata with an atomic swap"。snapshot 引用一个 manifest list，manifest list 记录各 manifest 的分区统计与文件计数，manifest 逐行记录 data/delete 文件及其分区值与列统计，且 "Manifest files are reused across snapshots"。snapshot 字段包括 `snapshot-id`、`parent-snapshot-id`、`sequence-number`、`manifest-list`、`summary.operation`（`append`/`replace`/`overwrite`/`delete`）。delete 文件由独立的 delete manifest 跟踪，数据文件与删除文件的新旧关系靠提交序号判定："The relative age of data and delete files relies on a sequence number that is assigned to every successful commit"。v4 草案新增相对路径 [来源: https://iceberg.apache.org/spec/]。

**Hudi**：基路径下的 `.hoodie/` 保存 `hoodie.properties`、`timeline/`（活动时间线，每个 instant 一个 Avro 文件）、`timeline/history/`（LSM 归档：`_version_`、`manifest_N`、`<min>_<max>_<level>.parquet`）、`metadata/`（本身是一张 MoR 表）、`.index_defs/`。"Hudi stores all actions performed on a table into a Log Structured Merge (LSM) tree structure called the Timeline"。instant 有 requested/inflight/completed 三态，完成态文件名为 `[begin]_[completion].[action]`；action 类型包括 commit、deltacommit、replacecommit、clean、compaction、logcompaction、indexing、rollback、savepoint、restore。数据按 file group（file id）组织，"Each record in the table is identified by an unique key and mapped to a single file group at any given time"，file group 再切成 file slice（一个可选 base file + 若干 log file，log 内以 block 编码 data/delete/CDC 等） [来源: https://hudi.apache.org/learn/tech-specs ; https://hudi.apache.org/docs/timeline]。1.0 起 "The active and archived timeline dichotomy has been done away with a more scalable LSM tree based timeline" [来源: https://hudi.apache.org/releases/release-1.0]。

**Paimon**："A snapshot is the entry point to a committed table state"。`snapshot/snapshot-N` JSON 记录 `schemaId`、`baseManifestList`（继承状态）、`deltaManifestList`（本次变更）、可选 `changelogManifestList` 与 `indexManifest`、`commitKind`（APPEND/COMPACT/OVERWRITE/ANALYZE）、`commitUser`、`watermark` 等；`EARLIEST`/`LATEST` 只是可能过期的提示文件。manifest 记录 ADD/DELETE 文件项与统计；数据文件位于 `partition/bucket-N/`，`index/` 存放动态桶索引与 deletion vector [来源: https://paimon.apache.org/docs/master/concepts/spec/ ; https://paimon.apache.org/docs/master/concepts/spec/snapshot]。主键表中 "Each bucket has its own LSM tree of data files"；"A sorted run contains one or more data files"，"Each Level-0 file is a separate sorted run; each higher level forms a run of non-overlapping files" [来源: https://paimon.apache.org/docs/master/primary-key-table/]。

### 元数据层级对比表

| 层级 | Iceberg | Hudi | Paimon |
| --- | --- | --- | --- |
| 表根元数据 | `vN.metadata.json`（catalog 指针指向） | `.hoodie/hoodie.properties` + timeline | `schema/schema-N` + `snapshot/snapshot-N` |
| 快照表示 | metadata.json 内嵌 snapshot 列表，每个指向 manifest list | 无独立快照文件；状态 = 时间线上某 completed instant 时各 file group 的最新 file slice | 每个 snapshot 一个 JSON，base+delta manifest list |
| 文件清单 | manifest list → manifest（data / delete 分开） | metadata table `files` 分区（`hoodie.metadata.enable` 默认 true，避免文件 listing） | manifest list → manifest（ADD/DELETE 条目） |
| 数据组织 | 不可变 data file + delete file/DV，按分区跟踪 | 分区 → file group → file slice（base + log） | 分区 → bucket → LSM sorted run / level |
| 变更日志 | 无独立 changelog 文件（可由 snapshot 差分计算） | log file 中的 CDC block / changelog 记录 | 独立 changelog 文件 + changelog manifest |
| 行级删除载体 | position delete file（v2）/ deletion vector（v3，Puffin） | log 中 delete block；COW 直接重写 | deletion vector（`index/`）；LSM 中的 `-D` 记录 |

## 4. 写入模型与更新语义

**Iceberg**：写入模式由 `write.delete.mode` / `write.update.mode` / `write.merge.mode` 决定，默认 `copy-on-write`，`merge-on-read` 需 v2+ [来源: https://iceberg.apache.org/docs/latest/configuration/]。行级删除有两类："Position deletes -- Mark a row deleted by data file path and the row position"（v2 用 position delete file，v3 改用 deletion vector）；"Equality deletes -- Mark a row deleted by one or more column values, like id = 5"。v3 中 "Position delete files must not be added to v3 tables"，且 "there can be at most one deletion vector for a given data file in a snapshot" [来源: https://iceberg.apache.org/spec/]。Flink upsert 需 v2 + 主键："Iceberg supports UPSERT based on the primary key when writing data into v2 table format"，分区表要求分区源列包含在 equality fields 中 [来源: https://iceberg.apache.org/docs/latest/flink-writes/]。Iceberg 没有 record key 索引，也没有 merge engine 概念；MERGE INTO 支持列级 SET，但 推断：底层仍以整行重写（COW）或整行 delete+insert（MOR）实现，不存在"只写变更列"的存储级 partial update。

**Hudi**：表类型 COW（"optimized for read-heavy workloads"，更新重写 base file，读放大 0、写放大最高）与 MOR（更新写入 log file、查询时合并或由 compaction 合并，写放大 O(records_changed)） [来源: https://hudi.apache.org/docs/table_types]。写操作含 upsert（默认，"The target table will never show duplicates"）、insert、bulk_insert、delete、insert_overwrite、delete_partition；删除分软删除（非 key 字段置空后 upsert）与硬删除（`delete` 操作或 `_hoodie_is_deleted` 列） [来源: https://hudi.apache.org/docs/write_operations]。每条记录由 record key（可选分区路径）通过索引映射到 file group；索引类型有 SIMPLE（Spark 默认）、BLOOM、GLOBAL_SIMPLE/GLOBAL_BLOOM、BUCKET（simple / partition-level / consistent hashing）、RECORD_LEVEL_INDEX / GLOBAL_RECORD_LEVEL_INDEX、HBASE、FLINK_STATE（Flink 默认） [来源: https://hudi.apache.org/docs/indexes]。乱序由 `hoodie.table.ordering.fields`（替代旧 `precombine.field`）与 merge mode 决定：COMMIT_TIME_ORDERING、EVENT_TIME_ORDERING（有 ordering field 时默认）、CUSTOM（`HoodieRecordMerger`，含 `partialMerge()`）；旧 payload 体系自 1.1.0 起弃用 [来源: https://hudi.apache.org/docs/record_merger]。partial update："1.0.0 extends support for partial updates to Merge-on-Read tables, which allows users to update only a subset of columns in a record" [来源: https://hudi.apache.org/releases/release-1.0]，Spark SQL 中 MERGE INTO 只 SET 部分列即触发，"Partial updates only write updated columns instead of full change records"，但不支持 global index、schema-on-read 等 [来源: https://hudi.apache.org/docs/sql_dml]。

**Paimon**：主键表 "maintains one logical row per key"，写入先缓冲排序再刷成 L0 文件，读取或 compaction 时按 merge engine 合并 [来源: https://paimon.apache.org/docs/master/primary-key-table/]。merge engine 四种：`deduplicate`（默认，"Keep the latest row; a latest retract removes it"）、`partial-update`（"Update non-null fields; sequence groups can also explicitly set nulls"）、`aggregation`、`first-row` [来源: https://paimon.apache.org/docs/master/primary-key-table/merge-engine/]。partial-update 支持按列组的 `fields.<col>.sequence-group` 版本控制、聚合函数、`partial-update.remove-record-on-delete` 等删除策略，默认无 sequence group 时 DELETE 记录被拒绝 [来源: https://paimon.apache.org/docs/master/primary-key-table/merge-engine/partial-update]。存储模式三选一：MOR（默认，读时合并 overlapping runs）、COW（`full-compaction.delta-commits = 1`）、MOW（`deletion-vectors.enabled = true`，lookup compaction 时把旧版本位置写入 deletion vector，"Readers skip those positions"） [来源: https://paimon.apache.org/docs/master/primary-key-table/table-mode]。乱序用 `sequence.field`（支持多列） [来源: https://paimon.apache.org/docs/master/primary-key-table/sequence-rowkind]。append 表的 DELETE/UPDATE/MERGE 仅在 Spark SQL 文档中给出，可选重写文件或 deletion vector [来源: https://paimon.apache.org/docs/master/append-table/row-level-operations]。

### 更新语义对比表

| 问题 | Iceberg | Hudi | Paimon |
| --- | --- | --- | --- |
| upsert 定位方式 | 无索引；MERGE 靠 join；Flink upsert 产生 equality delete（sink 提供 `convertEqualityDeletes()` 转为 DV） | record key → file group 的索引映射（多种索引） | 主键哈希到 bucket，bucket 内 LSM 按 key 合并 |
| 主键/唯一性 | 可选 identifier fields，不强制 | record key（可选 ordering fields） | 主键表强制一行一 key；append 表无 |
| partial update | 无存储级支持（推断） | 1.0 起 MOR 支持列级 partial update | `partial-update` merge engine + sequence group |
| 乱序处理 | 无（按提交序） | ordering fields + EVENT_TIME_ORDERING | `sequence.field` |
| 行级删除代价 | COW 重写文件；MOR 写 position/equality delete 或 DV，读时应用 | COW 重写 base file；MOR 追加 delete block，compaction 时消化 | MOR 写 `-D` 记录读时合并；MOW 用 DV 避免读时合并，写时多一次 lookup |
| 聚合/首行语义 | 无 | CUSTOM merger 可实现 | `aggregation` / `first-row` 内建 |

## 5. 流式能力

**Iceberg**：Flink 可流式读："Iceberg supports processing incremental data in Flink streaming jobs which starts from a historical snapshot-id"，通过 `streaming=true`、`monitor-interval` 轮询；FLIP-27 source 说明 "polls Iceberg table to discover new append-only snapshots. CDC read is not supported yet" [来源: https://iceberg.apache.org/docs/latest/flink-queries/]。Spark 增量读的官方限制："Currently gets only the data from `append` operation. Cannot support `replace`, `overwrite`, `delete` operations" [来源: https://iceberg.apache.org/docs/latest/spark-queries/]。批式 CDC 可用 `create_changelog_view` 过程生成 INSERT/DELETE/UPDATE_BEFORE/UPDATE_AFTER 视图（需 identifier columns 才能计算更新前后镜像） [来源: https://iceberg.apache.org/docs/latest/spark-procedures/]。Flink 文档未出现 lookup join / 维表能力。

**Hudi**：查询类型分 Snapshot、Time Travel、Read Optimized（仅 MOR）、Incremental（latest_state，"one record per key"）与 Incremental（CDC，"with both before and after images for each change record"）；1.0 起 "we switch the incremental and CDC queries to used completion time" [来源: https://hudi.apache.org/docs/table_types ; https://hudi.apache.org/docs/sql_queries]。Flink 流读：`read.streaming.enabled=true` + `read.start-commit`，`read.streaming.check-interval` 默认 60 s [来源: https://hudi.apache.org/docs/reading_tables_streaming_reads]；`changelog.enabled` 可保留 I/-U/U/D 中间变更，但 "the retention of changelog records is best-effort only"，异步 compaction 会把 changelog 合并为每 key 一条 [来源: https://hudi.apache.org/docs/ingestion_flink]。Spark Structured Streaming 读 "based on Hudi's Incremental Query feature"，因此可读范围受 cleaner 限制——保留的 commit 数 "directly translates into how much data retention the table supports for incremental queries" [来源: https://hudi.apache.org/docs/cleaning]。Hudi Streamer 提供 Kafka 等来源的 "Exactly once ingestion" 与 `--continuous` 模式 [来源: https://hudi.apache.org/docs/hoodie_streaming_ingestion]。Flink lookup join 受支持，1.2.0 新增 `lookup.join.cache.type = rocksdb` 以缓解堆压力 [来源: https://hudi.apache.org/docs/ingestion_flink]。

**Paimon**：流写 "Streaming writers commit data as checkpoints complete" [来源: https://paimon.apache.org/docs/master/flink/]。流读默认 "loads the latest full snapshot and then follows changes"，`scan.mode = 'latest'` 只读新变更，另有按 snapshot/时间戳/tag 定位；批式增量读 `(start, end]`；流读进度受 snapshot 过期影响，官方建议 "use a Consumer ID when a reader needs to protect its progress from expiration" [来源: https://paimon.apache.org/docs/master/flink/sql-query]。主键表能给下游什么变更由 `changelog-producer` 决定：`none`（默认，"the writer creates no separate full changelog"，Flink 可能插入有状态 normalize 算子）、`input`（透传源端完整 changelog）、`lookup`（compaction 时查旧值生成 `-U/+U`）、`full-compaction`（两次全量 compaction 差分，"intermediate updates may be collapsed"，适合"tens of minutes"延迟预算） [来源: https://paimon.apache.org/docs/master/primary-key-table/changelog-producer]。append 表流读 "Unaware-bucket tables do not guarantee row order"，bucket 内可保序 [来源: https://paimon.apache.org/docs/master/append-table/streaming]。lookup join："Paimon supports lookup joins on tables with primary keys and append tables in Flink"，默认 "each Flink subtask would store a whole copy of the lookup table"，支持 `lookup.async`、`max_pt()` 动态分区、Flink 2.0+ 的 shuffle 模式与 Query Service [来源: https://paimon.apache.org/docs/master/flink/sql-lookup]。

### 流式能力对比表

| 能力 | Iceberg | Hudi | Paimon |
| --- | --- | --- | --- |
| 流式写入 | Flink/Spark Streaming append 与 upsert（v2） | Flink、Spark Structured Streaming、Hudi Streamer | Flink（checkpoint 即提交）、Spark Structured Streaming |
| 流式读取 | Flink 轮询 append-only 快照 | Flink `read.streaming.enabled`；Spark 基于增量查询 | Flink 流读全量+增量；PyPaimon 亦有流读 |
| 增量读限制 | 仅 `append` 快照，不含 replace/overwrite/delete | 受 cleaner 保留期限制 | 受 snapshot 保留期限制（Consumer ID 可保护） |
| CDC（带前镜像） | 批式 `create_changelog_view`；流式 "not supported yet" | CDC 查询（`cdc.enabled`/supplemental logging）；Flink `changelog.enabled` best-effort | `input`/`lookup`/`full-compaction` changelog producer 产完整 `-U/+U/-D` |
| lookup join | 文档未提及 | Flink 支持，heap/rocksdb 缓存 | Flink 支持，含异步、动态分区、shuffle、Query Service |
| 端到端延迟量级（官方措辞） | 取决于 monitor-interval / 快照频率 | 分钟级（"minute-level analytics"） | 取决于 checkpoint 间隔；`full-compaction` 为数十分钟 |

## 6. 并发控制与事务

- **Iceberg**：乐观并发，"An atomic swap of one table metadata file for another provides the basis for serializable isolation"；冲突时 "the writer must retry the update based on the new current version"，append 总可重放，replace/delete 需校验被替换文件仍在表中。原子交换由 catalog 的 check-and-put 提供；基于文件系统 rename 的方案 "is **deprecated** and will be removed in version 4 of this spec. The scheme is **unsafe** in object stores" [来源: https://iceberg.apache.org/spec/ ; https://iceberg.apache.org/docs/latest/reliability/]。
- **Hudi**：读者快照隔离；写者之间 OCC（"file level optimistic concurrency control"，需外部 lock provider：ZooKeeper、Hive Metastore、DynamoDB、基于对象存储条件写的 storage lock、InProcess；FileSystem 锁 "not for production use"），写者与表服务之间 MVCC；1.0 引入 NBCC——"A lock is only needed for writing the commit metadata to the Hudi timeline"，但 "currently works only for MOR tables using simple bucket index and partition-level bucket index" [来源: https://hudi.apache.org/docs/concurrency_control]。0.13 起可用 marker 做早期冲突检测（默认关闭、标记为实验特性）。所有 instant 时间遵循 TrueTime 语义、全局单调递增 [来源: https://hudi.apache.org/docs/timeline]。
- **Paimon**："Paimon uses optimistic concurrency control"，提交前 "validate the proposed changes against its file set"，snapshot 冲突自动重试，文件冲突（如两个 compaction 替换同一文件）则拒绝并触发恢复，官方提醒 "Repeated conflicts can therefore cause repeated restarts"。原子发布可委托 catalog（REST 的 snapshot commit API）或依赖文件系统原子 rename/条件创建；对象存储需 Hive/JDBC catalog 的 `lock.enabled` [来源: https://paimon.apache.org/docs/master/concepts/concurrency-control]。多写者同分区时建议 `write-only = true` + 专用 compaction 作业；动态桶模式 "Only one job may write to a given partition" [来源: https://paimon.apache.org/docs/master/maintenance/dedicated-compaction ; https://paimon.apache.org/docs/master/primary-key-table/data-distribution]。

推断：三者都是 OCC，差别在"谁做原子提交"——Iceberg 完全依赖 catalog；Hudi 依赖外部锁（或 NBCC 缩小锁范围）；Paimon 在 HDFS/REST 上无需锁，在 S3 类存储上需要 catalog 锁。

## 7. Schema 演进与分区演进

- **Iceberg**：schema 支持 add/drop/rename/update（widen）/reorder，含嵌套结构，"Iceberg schema updates are **metadata changes**"，靠字段 ID 保证 "Added columns never read existing values from another column"；类型提升 int→long、float→double、decimal 精度放宽，v3 新增 date→timestamp、`unknown`→任意类型及列默认值 [来源: https://iceberg.apache.org/docs/latest/evolution/ ; https://iceberg.apache.org/spec/]。隐藏分区通过 transform（identity/bucket/truncate/year/month/day/hour/void）生成分区值，"queries no longer depend on a table's physical layout"；分区演进 "is a metadata operation and does not eagerly rewrite files"，新旧 spec 共存、分别规划 [来源: https://iceberg.apache.org/docs/latest/partitioning/ ; https://iceberg.apache.org/docs/latest/evolution/]。
- **Hudi**："Hudi supports schema evolution on write out-of-the-box, and experimental support for schema evolution on read"。schema-on-write 允许追加可空列、类型提升（int→long/float/double/string 等），不允许追加非空列、不允许"加列并重排"；schema-on-read（`hoodie.schema.on.read.enable`）支持 add/drop/rename/move 含嵌套，但 "If schema on read is enabled, it cannot be disabled again"，且要求 Spark > 3.1.x [来源: https://hudi.apache.org/docs/schema_evolution]。分区演进：官方文档未见说明（见「未能核实」）。
- **Paimon**：ALTER 支持 add（含 FIRST/AFTER）、rename、drop、modify 类型（含 row/array/map 嵌套）、修改位置与可空性（nullable→NOT NULL 默认禁用）、drop partition；Hive catalog 下 drop/modify 需 HMS 端放开 `disallow.incompatible.col.type.changes` [来源: https://paimon.apache.org/docs/master/flink/sql-alter]。CDC 入湖动作可在作业运行中自动同步 "Column additions and compatible type changes"，但 "Schema evolution is not full DDL replication" [来源: https://paimon.apache.org/docs/master/cdc-ingestion/]。分区演进：文档未提供修改分区键的操作。推断：Hudi 与 Paimon 的分区键在建表后不可演进，只能新建表。

## 8. 索引与查询加速

- **Iceberg**：manifest 中每个文件携带 value/null/NaN 计数与列上下界，manifest list 携带分区摘要，用于跳过 manifest 和文件 [来源: https://iceberg.apache.org/spec/]。Puffin 文件承载表级统计（`apache-datasketches-theta-v1` NDV 草图）与 `deletion-vector-v1`，"Statistics are informational. A reader can choose to ignore statistics information" [来源: https://iceberg.apache.org/puffin-spec/ ; https://iceberg.apache.org/spec/]。规范中没有二级索引或 bloom filter 索引定义（推断：数据跳过主要靠分区与列统计）。
- **Hudi**："Hudi employs a special metadata table"——一张 HFile 为 base 的内部 MoR 表，分区包括 `files`、`column_stats`、`partition_stats`、`bloom_filters`、`record_index`、`secondary_index_<name>`、`expr_index_<name>` [来源: https://hudi.apache.org/docs/metadata ; https://hudi.apache.org/learn/tech-specs]。1.0 新增 secondary index（非 key 列）、partition stats index、expression index（"an index on a function of a column"，类型含 COLUMN_STATS/BITMAP/LUCENE） [来源: https://hudi.apache.org/releases/release-1.0 ; https://hudi.apache.org/docs/indexes]。索引同时服务写路径（"bound the amount of change records any given base file needs to be merged against"）与读路径 [来源: https://hudi.apache.org/docs/indexes]。
- **Paimon**：文件级索引通过 `file-index.<type>.columns` 配置，类型有 bloom filter、bitmap、range-bitmap（BSI 已弃用），"Small indexes can be embedded in the manifest; larger indexes are stored alongside the data file"，结果 "pushed down to the DataFile for filtering rowgroups and pages" [来源: https://paimon.apache.org/docs/master/concepts/spec/fileindex]。表级索引含动态桶哈希索引与 deletion vector（默认 32 位 RoaringBitmap；"only 64-bit bitmap implementation is compatible with Iceberg"） [来源: https://paimon.apache.org/docs/master/concepts/spec/tableindex]。2.x 另有面向多模态的全局索引（bitmap/btree/full-text/vector） [来源: https://paimon.apache.org/docs/master/multimodal-table/global-index]。注意 MOR 主键表 "Filters on mutable non-key columns generally cannot be applied before merging" [来源: https://paimon.apache.org/docs/master/primary-key-table/table-mode]。

## 9. 引擎与生态支持矩阵

说明：只列各项目官方文档能确认的内容；标「厂商观点」者来自 Iceberg 官网 vendors 页的厂商自述；「经 Iceberg」指 Paimon 通过 Iceberg 兼容元数据被读取。

| 引擎/平台 | Iceberg | Hudi | Paimon |
| --- | --- | --- | --- |
| Flink | 读写；官方连接器，1.11.0 维护 1.20、2.1（main 分支已列 2.2/2.3） | 读写；bundle 覆盖 1.17–1.20、2.0、2.1 | 读写（流+批、lookup join、CDC action）；1.16–1.20、2.0–2.2 |
| Spark | 读写；维护 3.5、4.0、4.1 | 读写；1.2.0 增加 4.1 bundle | 读写（含 MERGE/UPDATE/DELETE、Structured Streaming）；3.2–3.5、4.0、4.1 |
| Trino | 支持；连接器由 Trino 社区维护 | COW snapshot、MOR read-optimized；`hudi-trino` 模块（Trino 483） | Paimon 社区维护 `paimon-trino`（示例 Trino 440）：读、INSERT、DDL、time travel；亦可经 Iceberg |
| Presto | 支持；连接器由 Presto 社区维护 | Hive 连接器 + 原生连接器（≥0.275），snapshot/RO，不支持增量 | 文档目录提及，master 无专页（未核实） |
| Hive | Hive 4 读写含 DELETE/UPDATE/MERGE；Hive 2/3 连接器已弃用 | 读（snapshot、受限增量），需 `hudi-hadoop-mr-bundle` | 读 + `INSERT INTO`（仅 MR）、DDL；Hive 2.1–3.1 |
| StarRocks | 厂商观点（CelerData） | snapshot（COW）、snapshot+RO（MOR） | 只读，StarRocks ≥ 3.1 |
| Doris | 厂商观点（VeloDB） | 官方注明仅在 0.10.0–0.13.1 测试 | 只读；DLF REST 需 Doris ≥ 3.1 |
| DuckDB | 厂商观点（duckdb-iceberg 扩展可读写） | 未核实 | 经 Iceberg 只读 |
| Snowflake | 厂商观点（Horizon Catalog、Snowflake-managed Iceberg Tables） | 未核实 | 未核实 |
| BigQuery | 厂商观点（BigLake / BigLake metastore） | `BigQuerySyncTool` 外部表：COW 与 MOR RO，需 hive-style 分区 | 未核实 |
| Athena | 厂商观点（原生 Iceberg 支持） | snapshot（COW）、snapshot+RO（MOR） | 经 Iceberg 读（仅 v2，PK 表需 full-compaction 模式） |
| Databricks | 厂商观点（Unity Catalog 管理 Delta/Iceberg/Hudi） | 官网列为 Data Processing 集成，无细节 | 未核实 |
| ClickHouse | 厂商观点（只读） | 只读 COW | 未核实 |
| Python | PyIceberg 0.12.0（2026-09-01）：读、append/overwrite，代码库含 upsert/delete 模块 | hudi-rs 0.5.0（2026-09-10）：只读（snapshot/time travel/incremental），MOR 仅限 Parquet log block | PyPaimon 2.0.0（2026-08-07）：读写、提交、流读、REST/JDBC/filesystem catalog，"The core Python API does not require a JDK" |

来源：Iceberg [https://iceberg.apache.org/multi-engine-support/ ; https://iceberg.apache.org/docs/latest/hive/ ; https://iceberg.apache.org/vendors/ ; https://py.iceberg.apache.org/]；Hudi [https://hudi.apache.org/docs/sql_queries ; https://hudi.apache.org/docs/flink-quick-start-guide ; https://hudi.apache.org/docs/gcp_bigquery ; https://hudi.apache.org/docs/python-rust-quick-start-guide ; https://hudi.apache.org/releases/release-1.2]；Paimon [https://paimon.apache.org/docs/master/flink/installation ; https://paimon.apache.org/docs/master/spark/installation ; https://paimon.apache.org/docs/master/ecosystem/trino ; https://paimon.apache.org/docs/master/ecosystem/hive ; https://paimon.apache.org/docs/master/ecosystem/starrocks ; https://paimon.apache.org/docs/master/ecosystem/doris ; https://paimon.apache.org/docs/master/iceberg/ecosystem ; https://paimon.apache.org/docs/master/pypaimon/]；版本号来自 PyPI 与 GitHub Releases。

## 10. Catalog 与互操作

- **Iceberg REST Catalog**：官方以 OpenAPI 定义 "a REST-based Catalog API for managing table metadata and performing catalog operations"，动机是 "Catalogs needed to be implemented in multiple languages and it proved difficult for commercial offerings to support many different catalogs and clients"；收益包括 change-based commits 的服务端去冲突、lazy snapshot loading、multi-table commits、credential vending [来源: https://iceberg.apache.org/rest-catalog-spec/]。行业采用：Iceberg vendors 页中 MinIO AIStor 自述 "implements the Apache Iceberg REST Catalog specification inside the storage layer"，SeaweedFS 自述内嵌 Iceberg REST catalog，Google Cloud 提到 BigLake metastore 的 "Iceberg REST Catalog"，dltHub 列出 Lakekeeper、Polaris、Unity Catalog、Snowflake Open Catalog 等 REST 实现（均为厂商观点） [来源: https://iceberg.apache.org/vendors/]。
- **Hudi**：以"同步"为主——Hive sync（HMS/JDBC/HIVEQL 三种模式，MOR 表默认生成 `_ro` 后缀表）、AWS Glue sync（每次 commit 都同步会导致 "too many versions kept in the catalog"，建议 `meta_sync.condition.sync=true`）、BigQuery sync、DataHub、Apache Polaris（自 Polaris 1.3.0 / Hudi 1.1.1，"by delegating table creation operations to the Polaris Spark client"）、XTable sync [来源: https://hudi.apache.org/docs/syncing_metastore ; https://hudi.apache.org/docs/syncing_aws_glue_data_catalog ; https://hudi.apache.org/docs/catalog_polaris ; https://hudi.apache.org/docs/syncing_xtable]。1.1+ 提出 "pluggable table format framework" 通过 XTable 适配器扩展到 Iceberg/Delta [来源: https://hudi.apache.org/docs/hudi_stack]。
- **Paimon**：catalog 类型 filesystem（默认）、hive、jdbc、rest [来源: https://paimon.apache.org/docs/master/concepts/catalog]。REST Catalog 是 "a Paimon catalog client that talks to a remote service over HTTP"，有独立的 OpenAPI 契约与 Management API（权限、行过滤、列脱敏），认证支持 bearer token 与阿里云 DLF；页面未提及与 Iceberg REST 协议的关系 [来源: https://paimon.apache.org/docs/master/concepts/rest/]。**Iceberg 兼容模式**："Paimon can publish Iceberg metadata that points to its existing data files"，`metadata.iceberg.storage` 取值 `disabled`/`table-location`/`hadoop-catalog`/`hive-catalog`/`rest-catalog`；append 表按快照增量发布；主键表两种模式——full-compaction 模式只发布最高 level 文件，"A completed Paimon write alone does not guarantee that Iceberg can see the change"；deletion-vector 模式需 `metadata.iceberg.format-version=3` + `deletion-vectors.enabled` + `deletion-vectors.bitmap64`，发布 L0 以上文件与 DV [来源: https://paimon.apache.org/docs/master/iceberg/ ; https://paimon.apache.org/docs/master/iceberg/catalogs ; https://paimon.apache.org/docs/master/iceberg/primary-key-table]。要求所有写入与维护经 Paimon 完成，"independent Iceberg mutations or cleanup can invalidate Paimon's view of the table"。
- **Apache XTable**：在 Hudi/Delta/Iceberg 之间全向翻译元数据，"Only Copy-on-Write or Read-Optimized views of tables are currently supported"，Hudi 需 ≥ 0.14.0；catalog 同步目前仅 HMS 与 Glue；官方文档未提及 Paimon [来源: https://xtable.apache.org/docs/features-and-limitations ; https://xtable.apache.org/docs/how-to]。

## 11. 运维

| 事项 | Iceberg | Hudi | Paimon |
| --- | --- | --- | --- |
| 小文件合并 | `rewrite_data_files`（bin-pack/sort/zorder/hilbert）等 Spark 过程或 Flink `TableMaintenance`，需单独调度 | 写入时自动 file sizing（小于 100 MB 视为小文件，目标 120 MB）；MOR compaction 默认异步；clustering 可 inline/async/独立作业，但 "can only be scheduled for tables / partitions not receiving any concurrent updates" | 主键表 compaction 默认在 writer 后台线程执行（`num-sorted-run.compaction-trigger` 默认 5）；append 表由 sink 内 compact coordinator 处理；可 `write-only` + 专用作业（注意 `write-only` 同时跳过快照过期） |
| 快照过期 | `expire_snapshots`，"Regularly expiring snapshots is recommended" | cleaner 自动在 commit 后运行，默认 KEEP_LATEST_COMMITS 保留 10 个；archival 进入 LSM 历史 | writer 提交时自动过期（`snapshot.time-retained` 1 h、`num-retained.min` 10） |
| 孤儿文件 | `remove_orphan_files`，默认保留 3 天，过短 "might corrupt the table" | 通过 rollback/marker 与 pre-write cleaner 处理失败写入 | `remove_orphan_files` 需单独提交，默认只删 1 天前文件 |
| 是否必需独立维护作业 | 是（Spark/Flink 维护任务） | 否（inline/async 内置），跨进程需锁 | 基本否；多写者或资源隔离时需专用 compaction 作业 |
| 指标接口 | `MetricsReporter`（ScanReport / CommitReport），默认 Logging | JMX、Graphite、Datadog、Prometheus PushGateway、CloudWatch、自定义 | 桥接到 Flink metrics（commit/scan/write/compaction/lookup），scan 指标需 Flink ≥ 1.18 |

来源：[https://iceberg.apache.org/docs/latest/maintenance/ ; https://iceberg.apache.org/docs/latest/metrics-reporting/ ; https://hudi.apache.org/docs/file_sizing ; https://hudi.apache.org/docs/compaction ; https://hudi.apache.org/docs/cleaning ; https://hudi.apache.org/docs/metrics ; https://paimon.apache.org/docs/master/primary-key-table/compaction ; https://paimon.apache.org/docs/master/append-table/streaming ; https://paimon.apache.org/docs/master/maintenance/manage-snapshots ; https://paimon.apache.org/docs/master/maintenance/metrics]

## 12. 选型建议

先给三句话结论：Iceberg 的核心资产是规范与生态广度，代价是流式/CDC 能力最弱且维护需独立作业；Hudi 的核心资产是面向 upsert 的索引与内置表服务，代价是概念与配置面最大、多写者依赖锁；Paimon 的核心资产是 LSM 带来的流式更新与完整 changelog，代价是引擎生态以 Flink/Spark 为中心、外部引擎多依赖 Iceberg 兼容层。三者都在 2026 年把 AI/多模态列为重点（Iceberg v3 的 variant/geo 类型、Hudi 1.2 的 VECTOR/BLOB/Lance、Paimon 2.x 的 multimodal table），这一维度尚无一手对比材料，本文不展开。

### 选型决策表

| 主要场景 | 更适合 | 理由（基于上文一手材料） |
| --- | --- | --- |
| 批处理分析、多引擎共享只读数仓 | Iceberg | 规范化程度最高，Trino/Presto/Hive/Spark/Flink 及主流云仓均声明支持；隐藏分区与分区演进只有 Iceberg 完整提供 |
| 流式 CDC 入湖（需下游消费完整 changelog） | Paimon | changelog producer 可产出带前镜像的 `-U/+U/-D`；Iceberg 流读 "CDC read is not supported yet"，Hudi Flink changelog 为 best-effort |
| 近实时 upsert / 宽表 partial update / 流式聚合 | Paimon 或 Hudi | Paimon 有 partial-update + sequence group、aggregation、first-row 内建；Hudi 1.0 MOR partial update + 多种索引；Iceberg 需 MOR delete 文件且无 partial update |
| 大规模随机 upsert、需按 key 精确定位（Spark 生态） | Hudi | record index / bucket index / bloom index 等为 upsert 专门设计，写入时自动 file sizing |
| 多引擎互操作、与云厂商托管服务（Snowflake、BigQuery、Athena、S3 Tables 等）集成 | Iceberg（Paimon 可借 Iceberg 兼容模式） | 云厂商声明以 Iceberg 为主（厂商观点）；Paimon 主键表经 Iceberg 读取有可见性延迟；Hudi 依赖 sync 工具 |
| Flink 为主、需要维表 lookup join 与流批一体 | Paimon | 官方文档明确 lookup join、Query Service、CDC action 等 Flink 深度集成；Hudi 亦支持 lookup join 但功能较少 |
| Python/AI 数据管线直接读写 | Paimon 或 Iceberg | PyPaimon 与 PyIceberg 均可读写；hudi-rs 目前只读 |

### 针对 Colvenna Lake 必须先回答的问题

1. **主负载是流还是批？** 若以 Flink CDC 实时入湖并需要下游继续流式消费变更，Paimon 的 changelog producer 是决定性优势；若以 Spark/Trino 批分析为主，Iceberg 的引擎覆盖与规范稳定性更重要。
2. **更新模式是什么？** 是否存在主键、乱序到达、多流拼宽表（partial update）或流式聚合？这直接决定是否需要 merge engine / record merger，还是 COW 重写即可接受。
3. **谁来读？** 是否需要 Snowflake、BigQuery、Athena、DuckDB 等外部引擎直接读取？若是，Iceberg 或 Paimon 的 Iceberg 兼容模式（接受主键表可见性延迟）是前提。
4. **底层存储与 catalog 是什么？** S3 类对象存储上 Iceberg 依赖 catalog 原子提交、Paimon 需要 catalog 锁、Hudi 多写者需要 lock provider；是否已有 HMS/Glue/REST catalog 决定并发写入方案。
5. **谁来运维？** 能否长期运行独立的 compaction/expire/orphan 作业（Iceberg 模式），还是希望维护内嵌在写入作业中（Hudi/Paimon 模式）？团队是 Flink 栈还是 Spark 栈？

## 未能核实的问题

- **分区演进**：Hudi 与 Paimon 官方文档均未找到修改分区键的操作或声明，本文"不可演进"为推断。
- **Paimon 官网首页口号**：首页为 JS 渲染，仅能从 `og:description` 元标签获取文案（与 README 一致）；未能核实首页是否仍使用 "Streaming Lakehouse" 表述。
- **Paimon 1.x 各版本发布日期**：`project/releases` 页只描述发布流程；日期仅能从 GitHub tag 时间推得（如 release-1.0.0 为 2025-01-03），且不存在 `release-1.4.0` tag（只有 1.4.1/1.4.2）。
- **Hudi 1.0.0 官方发布页**：`hudi.apache.org/releases/release-1.0.0` 抓取为空，改用 asf-site 分支源文件 `website/releases/release-1.0.md`；`tech-specs-1_0` 路径已 404，现行地址为 `/learn/tech-specs`（描述 table version 9）。
- **Iceberg REST Catalog 的采用规模**：官方 spec 页未列出实现者名单，只能引用 vendors 页厂商自述。
- **Iceberg Flink 流式写入的提交时机**：本文未从 Iceberg 文档中抓取到"checkpoint 触发提交"的原文，故未在对比表中断言。
- **Snowflake / Databricks / DuckDB 对 Hudi、Paimon 的支持**，以及 Presto 对 Paimon 的支持：三方官方文档均无对应页面，标为未核实。
- **Apache XTable 对 Paimon 的支持**：XTable 官方文档未提及 Paimon。
- **贡献者数**：GitHub contributors 端点计数含匿名邮箱且有统计上限，仅为量级参考。
- **Hudi Flink `cdc.enabled`**：仅在 Flink 快速入门页出现示例，`ingestion_flink` 页未详细说明其与 `changelog.enabled` 的区别。
