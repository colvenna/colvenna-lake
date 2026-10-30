---
title: "Apache Paimon 技术调研"
date: 2026-09-28
status: draft
sources:
  - https://paimon.apache.org/docs/master/
  - https://github.com/apache/paimon (master 分支 docs/docs/*.md|mdx、README.md、LICENSE、pom.xml)
  - https://github.com/apache/paimon/releases/tag/release-2.0.0
  - https://api.github.com/repos/apache/paimon/releases
  - https://news.apache.org/foundation/entry/apache-software-foundation-announces-new-top-level-project-apache-paimon
  - https://incubator.apache.org/projects/paimon.html
  - https://paimon.apache.org/docs/rust/
  - https://github.com/apache/paimon-rust
  - https://github.com/apache/paimon-cpp
  - https://github.com/apache/paimon-python
  - https://cwiki.apache.org/confluence/display/PAIMON/Paimon+Improvement+Proposals
  - https://docs.starrocks.io/docs/data_source/catalog/paimon_catalog/
  - https://github.com/apache/doris-website (docs/lakehouse/catalogs/paimon-catalog.mdx, best-practices/doris-paimon-capability-matrix.mdx)
---

# Apache Paimon 技术调研

> 说明：本文 `paimon.apache.org/docs/master/...` 链接对应调研时的上游 master（2.2-SNAPSHOT），不能直接作为 M1/M2 发布版能力证明。项目候选版本、Doris 4.1.4 的 Paimon 1.4.2 依赖和可执行关口以 [M0 发布版能力矩阵](version-capability.md)为准；下方第 7.1 节保留原调研快照。

## 1. 项目概况

- **是什么**：官方 README 的定义是 "a lake format that enables building a Realtime Lakehouse Architecture with Flink and Spark for both streaming and batch operations"，核心创新是"将 lake format 与 LSM 结构结合，把实时流式更新带入湖架构"。[来源: https://github.com/apache/paimon/blob/master/README.md]
- **起源**：前身是 Flink 社区开发的 `Flink Table Store`；README 同时明确"架构参考了 Iceberg 的部分设计理念"。[来源: https://github.com/apache/paimon/blob/master/README.md] ASF 公告亦写明 "Formerly known as Flink Table Store, Paimon was first developed by the Flink community"。[来源: https://news.apache.org/foundation/entry/apache-software-foundation-announces-new-top-level-project-apache-paimon]
- **孵化与毕业**：2023-03-12 进入 Apache 孵化器；2023-06-07 发布首个孵化版本 0.4.0；2024-03-21 毕业。[来源: https://incubator.apache.org/projects/paimon.html] ASF 于 2024-04-16 正式发布 TLP 公告。[来源: https://news.apache.org/foundation/entry/apache-software-foundation-announces-new-top-level-project-apache-paimon]
- **当前版本**：最新正式版 **2.0.0**，GitHub Release 发布时间 2026-08-07（UTC），与 PyPaimon 2.0.0 同版本号联合发布；上一版为 1.4.2。[来源: https://github.com/apache/paimon/releases/tag/release-2.0.0] master 分支 `pom.xml` 为 `2.2-SNAPSHOT`；tag 列表中已有 `release-2.1.0-rc1~rc3`，但尚无 2.1.0 正式 release。[来源: https://api.github.com/repos/apache/paimon/tags]
- **许可证**：Apache License 2.0。[来源: https://github.com/apache/paimon/blob/master/LICENSE]

## 2. 核心定位与设计目标

官方措辞并非字面上的 "streaming lakehouse"，而是：

- README："Realtime Lakehouse Architecture"。[来源: https://github.com/apache/paimon/blob/master/README.md]
- ASF 公告："a streaming data lake platform"，"combines lake format and log-structured merge-tree (LSM) to bring real-time streaming updates into the data lake"。[来源: https://news.apache.org/foundation/entry/apache-software-foundation-announces-new-top-level-project-apache-paimon]
- 2.x 文档首页把定位扩展为 "Data Lake Platform — unified batch, streaming, and multimodal AI in a single lake format"，三大支柱：大规模分析、实时流（LSM + merge engine + changelog producer）、多模态 AI（向量/全文检索、BLOB 表、Python SDK）。[来源: https://paimon.apache.org/docs/master/]

与 Iceberg / Hudi / Delta 的差异，官方文档**没有**给出直接对比表，只有两类一手表述：

| 维度 | Paimon 官方表述 | 与 Iceberg 的关系 |
| --- | --- | --- |
| 更新机制 | 每个 bucket 一棵 LSM tree，写入排序刷盘、compaction 合并 | README 承认借鉴 Iceberg 设计概念 |
| 流式能力 | 内建 merge engine、changelog producer、Flink 流读/lookup join | 提供 Iceberg Compatibility：提交后发布 Iceberg 元数据，Iceberg 读取器直接读同一批数据文件 |
| 元数据 | snapshot / manifest list / manifest（Avro）+ schema 文件 | Iceberg 兼容模式下 Paimon 负责写、compaction、过期，Iceberg 侧只读 |

[来源: https://github.com/apache/paimon/blob/master/README.md; https://paimon.apache.org/docs/master/iceberg/]。Hudi / Delta 在官方文档中未被提及。

## 3. 存储架构

### 3.1 文件布局

单 bucket 主键表首个提交后的目录（官方示例）：

```text
warehouse/default.db/my_table/
├── bucket-0/data-<uuid>-0.parquet
├── manifest/manifest-<uuid>-0, manifest-list-<uuid>-0, manifest-list-<uuid>-1
├── schema/schema-0
└── snapshot/EARLIEST, LATEST, snapshot-1
```

分区表在 bucket 目录之上增加 `dt=.../` 分区目录；dynamic bucket 索引和 deletion vector 放在 `index/` 并由 `manifest/` 下的 index manifest 引用；`EARLIEST`/`LATEST` 只是查找提示，读者不能把目录里所有文件当作有效数据。[来源: https://paimon.apache.org/docs/master/concepts/spec/]

- **snapshot**：JSON，格式 version 3，字段含 `baseManifestList`、`deltaManifestList`、可选 `changelogManifestList`、`indexManifest`、`commitKind`（`APPEND`/`COMPACT`/`OVERWRITE`/`ANALYZE`）、`watermark` 等；base+delta 合并即得该快照全部有效文件，无需回放历史。[来源: https://paimon.apache.org/docs/master/concepts/spec/snapshot/]
- **manifest / manifest list / index manifest**：均为 Avro；manifest 记录数据文件的 `ADD`/`DELETE` 项及 `_LEVEL`、`_MIN_KEY/_MAX_KEY`、统计信息；manifest list 记录 manifest 元数据与分区统计用于跳过。DELETE 只是逻辑删除，物理删除交给 snapshot 过期。[来源: https://paimon.apache.org/docs/master/concepts/spec/manifest/]
- **data file / changelog file**：数据文件命名 `data-${uuid}-${id}.${format}`；主键表文件列为 `_KEY_*`、`_SEQUENCE_NUMBER`、`_VALUE_KIND`、value 列（`data-file.thin-mode=true` 时省略 `_KEY_*`）；changelog 文件复用同样的 key/value 结构，由 snapshot 的 changelog manifest 单独引用。[来源: https://paimon.apache.org/docs/master/concepts/spec/datafile/]

### 3.2 LSM tree、sorted run、level、compaction

- 每个 bucket 是独立的 LSM tree；writer 缓冲、排序、刷出新文件，compaction 合并文件降低读放大。
- **sorted run** = 一个或多个 key 范围互不重叠的文件；不同 run 之间可重叠并含同一 key 的多个版本。每个 Level-0 文件单独算一个 run，L1 以上每层整体算一个 run。[来源: https://paimon.apache.org/docs/master/primary-key-table/]
- compaction 默认采用 universal compaction 策略；`num-sorted-run.compaction-trigger` 默认 5，`num-sorted-run.stop-trigger` 默认为 trigger+3（写停顿阈值）。lookup compaction 用于 `lookup` changelog producer、`first-row`、deletion vector 表。[来源: https://paimon.apache.org/docs/master/primary-key-table/compaction/]
- 三种 table mode（无独立选项）：MOR（默认，读时合并）、COW（`full-compaction.delta-commits=1`）、MOW（`deletion-vectors.enabled=true`，写时查旧行并记录 DV）。[来源: https://paimon.apache.org/docs/master/primary-key-table/table-mode/]

### 3.3 Primary Key 表 vs Append 表

| | Primary-key table | Append table |
| --- | --- | --- |
| 语义 | 同 key 记录按 merge engine 合并为一逻辑行 | 每行独立存储，不按 key 去重 |
| 典型用途 | CDC、upsert、实体更新 | 事件日志、批数据集 |
| bucket 默认 | `-1` dynamic bucket | `-1` unaware-bucket，写入 `bucket-0` 目录 |
| 行级修改 | merge engine + 删除记录 | Spark `DELETE/UPDATE/MERGE INTO`（COW 或 DV） |

[来源: https://paimon.apache.org/docs/master/concepts/basic-concepts/; https://paimon.apache.org/docs/master/append-table/]

Append 表两种布局：**unaware-bucket**（默认，无流式顺序保证，支持 row tracking / data evolution）与 **bucketed append**（正整数 `bucket` + `bucket-key`，支持 bucket 裁剪、Spark bucketed join、`bucket-append-ordered=true` 时分区+bucket 内保序）。[来源: https://paimon.apache.org/docs/master/append-table/bucketed/]

### 3.4 Bucket 模式（主键表）

| 模式 | `bucket` | 分配方式 | 关键约束 |
| --- | --- | --- | --- |
| Fixed | 正整数 | `Math.abs(key_hashcode % numBuckets)`；`bucket-key` 默认为主键去掉分区列 | 改数需离线 rescale；MOR 单 bucket 200 MB–1 GB 为起步参考 |
| Dynamic（默认） | `-1` | 维护 key→bucket 哈希索引，可自动增长 | **同一分区只能有一个写作业**；1 亿 key 约 1 GB 索引内存 |
| Postpone | `-2` | 先写暂存（`bucket-postpone` 或 Spark 暂存文件），再按行数/大小推算每分区 bucket 数 | 数据需经 compaction 或二次提交才可读；各分区 bucket 数可不同 |

[来源: https://paimon.apache.org/docs/master/primary-key-table/data-distribution/]

**bucket 与并发**：bucket 决定写入分布并影响读并行度，但"不是普遍的单读者上限"——批扫描可把一个 bucket 再切成独立 key 区间；Flink 侧建议 sink 并行度 ≤ bucket 数（最好相等），固定 bucket 流读的推断并行度等于 bucket 数。[来源: https://paimon.apache.org/docs/master/primary-key-table/; https://paimon.apache.org/docs/master/maintenance/write-performance/; https://paimon.apache.org/docs/master/flink/sql-query/]

### 3.5 文件格式

`file.format` 默认 **Parquet**；另支持 Avro、ORC、CSV/Text/JSON（标注 experimental，不建议生产）、Lance（ML/向量场景，不支持 MAP/MULTISET/TIMESTAMP_LTZ/VARIANT）、Vortex、Mosaic（宽表列分桶格式）、Row（`.row` 行存块，O(1) 行定位）、BLOB（`.blob`/`.video`）。schema version 1/2 的老表若缺 `file.format` 则按 ORC 解释。[来源: https://paimon.apache.org/docs/master/concepts/spec/fileformat/; https://paimon.apache.org/docs/master/concepts/spec/schema/]

## 4. Merge Engine

| Engine | 同 key 结果 | 典型输入 | 流读要求 / 限制 |
| --- | --- | --- | --- |
| `deduplicate`（默认） | 保留最新行；最新为 `-D/-U` 则删除 | 完整替换行（CDC） | null 会覆盖非 null |
| `partial-update` | 非 null 字段更新、null 保持；sequence group 可显式置 null | 多流更新同一实体的不同列 | 完整行流读需 `lookup`/`full-compaction`；默认拒绝 DELETE |
| `aggregation` | 每个 value 字段按 `fields.<f>.aggregate-function` 聚合（sum/max/min/listagg/collect/merge_map/nested_update/rbm32/rbm64/hll_sketch/theta_sketch 等） | 指标贡献值 | 仅 sum/product/collect/merge_map/nested_update/last_value/last_non_null_value 支持回撤 |
| `first-row` | 保留首行，忽略后续 | 事件/日志去重 | 仅 `none`/`lookup` producer；不支持 `sequence.field`；默认拒绝 DELETE |

[来源: https://paimon.apache.org/docs/master/primary-key-table/merge-engine/; .../merge-engine/partial-update/; .../merge-engine/aggregation/; .../merge-engine/first-row/]

- **sequence field**：`sequence.field` 用源侧版本/时间戳决定"谁更新"，支持多字段与 `sequence.field.sort-order`；与 `first-row`、跨分区更新、`sequence.snapshot-ordering` 互斥。`rowkind.field` 可从数据列解码 `+I/-U/+U/-D`。[来源: https://paimon.apache.org/docs/master/primary-key-table/sequence-rowkind/]
- **sequence group**：`fields.<ordering-field>.sequence-group = colA,colB` 让不同流各自持有版本；组内 ordering 为 null 则跳过该组，≥ 存量版本则整组替换（含 null），并可与 `aggregate-function` 结合。[来源: https://paimon.apache.org/docs/master/primary-key-table/merge-engine/partial-update/]
- Flink SQL 需设 `table.exec.sink.upsert-materialize = NONE`，避免 sink materializer 重排记录。[来源: https://paimon.apache.org/docs/master/primary-key-table/merge-engine/]

## 5. Changelog Producer

| Producer | 旧值来源 | 变更可见时机 | 代价 | 适用 |
| --- | --- | --- | --- | --- |
| `none`（默认） | 消费者自行维护状态 | 增量快照读 | 无额外文件；Flink 可能插入有状态 normalize 算子 | 批读或能接受 upsert 的下游 |
| `input` | 上游已提供完整 before/after | 输入 changelog 文件提交后 | 额外写一份 changelog 文件 | 数据库 CDC 或 Flink 有状态计算的完整变更 |
| `lookup` | compaction 时查找现有行 | lookup compaction 提交后 | writer 默认等待 compaction，提交延迟增加；占用内存/本地盘缓存 | 输入无 before image 但下游需完整变更；低延迟 |
| `full-compaction` | 比较前后两次 full compaction | full compaction 提交后 | 写放大高；中间更新会被折叠 | 可接受分钟级延迟的周期性变更 |

[来源: https://paimon.apache.org/docs/master/primary-key-table/changelog-producer/]

补充约束：deletion vector 表仅支持 `none/input/lookup`；`lookup` 与 `full-compaction.delta-commits` 互斥；`full-compaction.delta-commits` 在 Flink 流写未配置时默认每个 checkpoint 一次；`lookup`/`full-compaction` 可用 `changelog-producer.row-deduplicate`、`ignore-update-before`、`ignore-delete` 过滤；小 changelog 文件多时用 `precommit-compact=true` 合并。[来源: 同上]

## 6. 一致性与事务

- **快照隔离**：writer 先写文件再发布 snapshot，读者只看到已提交文件集合；一次写事务可产生 append 与 compaction 两个 snapshot，空提交可跳过。[来源: https://paimon.apache.org/docs/master/concepts/basic-concepts/]
- **乐观并发**：提交流程为"写文件 → 读最新 snapshot 校验 → 原子发布 → 冲突则重试/拒绝"。**snapshot 冲突**（两 writer 抢同一 ID）由落败方重试解决；**file 冲突**（两 compactor 替换同一文件）直接拒绝，流作业会触发恢复重启。[来源: https://paimon.apache.org/docs/master/concepts/concurrency-control/]
- **原子发布机制**：catalog 托管快照时委托给 catalog（REST Catalog 的 snapshot commit API）；文件系统托管时依赖原子 rename（HDFS）或条件创建（OSS 实现）；不满足时需 Hive/JDBC catalog 并开 `lock.enabled=true`，"不要假设对象存储 rename 具有 HDFS 语义"。[来源: 同上]
- **Flink 提交**：writer 在 checkpoint 完成时把 committable 交给 committer 发布 snapshot，即与 Flink checkpoint 对齐的两阶段提交。[来源: https://paimon.apache.org/docs/master/learn-paimon/small-files/]
- **Time travel**：批读 `scan.snapshot-id` / `scan.timestamp-millis` / `scan.tag-name` / `scan.watermark`，Flink 1.18+ 支持 `FOR SYSTEM_TIME AS OF`；`incremental-between='12,15'` 读取 (12,15] 增量；流读支持 `from-snapshot-full`。[来源: https://paimon.apache.org/docs/master/flink/sql-query/]
- **Tag**：基于 snapshot 保留 manifest 与数据文件，不受 snapshot 过期影响；支持 `tag.automatic-creation`（process-time/watermark/batch）、`tag.creation-period`（daily/hourly/two-hours）、`tag.num-retained-max`。[来源: https://paimon.apache.org/docs/master/maintenance/manage-tags/]
- **Branch**：可从 tag 或空表创建；`t$branch_b1` 读写；`fast_forward` 把分支覆盖回 main；`merge_branch` 仅对开启 `branch-merge.enabled` 的纯 append 表；`scan.fallback-branch` 让批读在分区缺失时回退到流分支。[来源: https://paimon.apache.org/docs/master/maintenance/manage-branches/]
- **回滚**：`rollback_to` 删除目标之后的 snapshot/tag；`rollback_to_as_latest` 以新 snapshot 物化旧状态并保留后续历史。[来源: https://paimon.apache.org/docs/master/maintenance/manage-snapshots/]

## 7. 生态与计算引擎集成

官方兼容矩阵（master 分支）：

| 引擎 | 版本 | 能力 | 维护方 |
| --- | --- | --- | --- |
| Flink | 1.16–1.20、2.0–2.2 | 批/流读写、DDL、CDC、lookup join、procedures | 主仓库 `paimon-flink` |
| Spark | 3.2–3.5、4.0、4.1 | 批读写、DDL、行级操作，Structured Streaming 需 3.3+ | 主仓库 `paimon-spark` |
| Hive | 2.1/2.2/2.3/3.1/2.1-cdh-6.3 | 读、建表、`INSERT INTO`（仅 MR） | 主仓库 `paimon-hive` |
| Trino / Presto | 独立发布 | 读、DDL、insert、time travel | `apache/paimon-trino`、`apache/paimon-presto` 独立仓库 |
| StarRocks | 3.1+ | 外部 catalog 只读 | StarRocks 项目自行维护 |
| Doris | REST catalog 需 3.1+ | 外部 catalog 只读 | Doris 项目自行维护 |
| Amoro | 0.8.1 指南 | 元数据浏览、Terminal 查询 | Amoro 项目 |

[来源: https://paimon.apache.org/docs/master/ecosystem/; https://paimon.apache.org/docs/master/ecosystem/trino/; https://paimon.apache.org/docs/master/ecosystem/hive/]

**Flink 深度能力**：

- 流读默认"先读最新全量快照再跟随变更"，`scan.mode=latest` 只读新增；`consumer-id` 记录消费位点并阻止所需快照过期，支持 `exactly-once`/`at-least-once` 两种模式。[来源: https://paimon.apache.org/docs/master/flink/sql-query/; https://paimon.apache.org/docs/master/flink/consumer-id/]
- Lookup join 支持主键表与 append 表；提供 retry、async retry、shuffle lookup（Flink 2.0+，固定 bucket，各 subtask 只缓存部分 bucket）、`max_pt()` 动态分区、独立 Query Service 作业。[来源: https://paimon.apache.org/docs/master/flink/sql-lookup/]

**Paimon CDC**：通过 `paimon-flink-action` jar 提供 `mysql_sync_table/database`、`postgres_sync_table`、`mongodb_sync_table/database`、`kafka_sync_table/database`（canal-json/debezium-json/debezium-avro/maxwell-json/ogg-json/json/aws-dms-json/debezium-bson）、`pulsar_sync_*`；依赖基线为 Flink CDC 3.5.0。schema evolution 支持加列、字符串/整数/浮点类型加宽等，**不**同步 drop/rename 列与 rename 表。反向地，Paimon 也可作为 Flink CDC YAML pipeline 的 source。[来源: https://paimon.apache.org/docs/master/cdc-ingestion/; https://paimon.apache.org/docs/master/cdc-ingestion/schema-evolution/]

### 7.1 查询引擎支持细节（2026-09-28 按各引擎官方文档核实）

Paimon 官方生态页把 StarRocks / Doris 的能力细节交给对方项目文档维护，并强调"外部维护的集成有自己的发布周期"。[来源: https://paimon.apache.org/docs/master/ecosystem/] 以下为逐一核实结果：

| 引擎 | 读 | 写 | catalog 类型 | 主键表 / DV | time travel | 系统表 | 增量读 | 依赖的 Paimon 版本 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Trino**（`apache/paimon-trino`，独立发布） | ✅ | ✅ INSERT，仅限 fixed bucket 主键表与 `bucket=-1` 的非主键表；支持 `CREATE SCHEMA/TABLE`、`ADD COLUMN` | 文档仅示例 filesystem catalog | 未提及 DV | ✅ `FOR TIMESTAMP AS OF` / `FOR VERSION AS OF <snapshot-id 或 tag>` | 未提及 | 未提及 | 文档示例基于 Trino 440 连接器、JDK 21；各 revision 自带 Paimon 依赖 |
| **StarRocks**（3.1+，官方标记 Beta） | ✅ | ❌ "cannot ... drop, delete, or insert data into your Paimon cluster"，但可 `INSERT INTO` 内表做 ETL | `filesystem`、`hive` | 主键表可读；提供 `$ro` 读 compaction 后数据（可能滞后于最新 snapshot）；DV 未提及 | 未提及 | `$partitions`、`$ro`（版本相关） | 未提及 | 未提及 |
| **Doris**（catalog 3.1+；REST catalog 3.1+；JDBC catalog 4.1.0+） | ✅（native Parquet/ORC reader + JNI reader 混合，主键表 merge read 走 JNI） | ❌ 当前文档："only read operations ... Write operations ... will be supported in the future"（见下方说明） | `filesystem`、`hms`、`dlf`、`jdbc`、`rest` | ✅ 明确 "Supports reading Paimon Deletion Vector" | ✅ `@options('scan.snapshot-id'/'scan.tag-name'/'scan.timestamp'...)`，也支持 `@branch(...)` | ✅ `$snapshots`、`$branches`、`$tags`、`$audit_log`、`$binlog`、`$manifests`、`$partitions`、`$ro` 等 | ✅ `@incr(startSnapshotId/endSnapshotId 或 timestamp, incrementalBetweenScanMode=delta/changelog/diff)` | 1.3.1 |

[来源: https://paimon.apache.org/docs/master/ecosystem/trino/; https://paimon.apache.org/docs/master/ecosystem/starrocks/; https://paimon.apache.org/docs/master/ecosystem/doris/; https://docs.starrocks.io/docs/data_source/catalog/paimon_catalog/; https://docs.starrocks.io/docs/data_source/feature-support-data-lake-analytics/; https://github.com/apache/doris-website/blob/master/docs/lakehouse/catalogs/paimon-catalog.mdx; https://github.com/apache/doris-website/blob/master/docs/lakehouse/best-practices/doris-paimon-capability-matrix.mdx]

**需要注意的几点**：

- **Doris 写入能力存在文档冲突**：Doris master 分支的 Paimon catalog 页仍写"仅支持读，写入未来支持"；但同一分支的 *Paimon Catalog Capability Matrix* 页已把 `APPEND / OVERWRITE / UPDATE / DELETE / MERGE_INTO / UPSERT / PARTIAL_UPDATE / OPTIMISTIC_CONCURRENCY` 全部标为 ✅，`WRITE_TO_BRANCH / MULTI_TABLE_COMMIT / ROLLBACK / EXPIRE / 视图` 标为 ❌。master 文档对应尚未发布的 dev 版本，已发布版本仍应视为只读；落地前需以目标 Doris 版本的发布文档为准。
- **版本更新（2026-09-28）**：上表的 1.3.1 是旧的 master 文档/源码调研快照；本项目目标 Doris 4.1.4 发布标签的 `fe/pom.xml` 依赖 Paimon **1.4.2**。它读取 Paimon 2.0.0 DV 表仍需联调，见 [M0 发布版能力矩阵](version-capability.md)。
- **StarRocks 官方文档对 Paimon 的描述很简略**：feature-support 页只有一句 "Paimon Catalog is supported from v3.1.0 onwards"，DV、time travel、REST catalog 都未提及，不能据此判断"不支持"，只能判定"官方未声明"。
- **Trino 连接器与 Paimon 主仓库版本解耦**：`apache/paimon-trino` README 未列版本矩阵，也没有 GitHub Release；需按仓库分支的 `pom.xml` 匹配 Trino 与 Java 版本。
- Paimon 官方生态页**未提及** DuckDB、Athena、BigQuery、Snowflake、Dremio、Kyuubi、Gravitino 等引擎，这些不在官方兼容矩阵内。

## 8. Catalog 与元数据

| metastore | 元数据位置 | 备注 |
| --- | --- | --- |
| `filesystem`（默认） | warehouse 文件系统 | 不支持 view / 持久化 function |
| `hive` | Hive Metastore | 默认不同步分区，需 `metastore.partitioned-table=true`；可提供 lock |
| `jdbc` | 关系库（MySQL/PostgreSQL 等） | view 存于 `paimon_views` 表；可提供 lock |
| `rest` | 远端 HTTP 服务 | 支持 view、function、snapshot commit、临时凭证 |

[来源: https://paimon.apache.org/docs/master/concepts/catalog/; https://paimon.apache.org/docs/master/concepts/views/; https://paimon.apache.org/docs/master/concepts/functions/]

**REST Catalog 规范**：客户端配置 `metastore=rest`、`uri`、`warehouse`（服务端 catalog 标识，非路径）与 `token.provider`（`bear` 或 `dlf`）；流程为 `GET /v1/config` 获取 defaults/overrides 与 `prefix`，再访问 `/v1/{prefix}/databases/...` 下的 tables、commit/rollback/snapshots、token/auth、partitions、branches/tags、consumers、views/functions；契约由公开的 OpenAPI YAML 定义，服务端可选实现。[来源: https://paimon.apache.org/docs/master/concepts/rest/; https://paimon.apache.org/docs/master/concepts/rest/rest-api/]

**Iceberg 兼容层**：表选项 `metadata.iceberg.storage` = `disabled`（默认）/`table-location`/`hadoop-catalog`/`hive-catalog`/`rest-catalog`；每次 Paimon 提交后生成 Iceberg manifest 与 snapshot 元数据，指向同一批数据文件。主键表在无 Iceberg DV 时只发布最高层级文件（需 full compaction），开启 `metadata.iceberg.format-version=3` + `deletion-vectors.enabled` + `deletion-vectors.bitmap64` 后可发布 L0 以上文件与 DV。官方警告：写入、schema 变更、compaction、过期与清理必须经由 Paimon。[来源: https://paimon.apache.org/docs/master/iceberg/; https://paimon.apache.org/docs/master/iceberg/primary-key-table/]

## 9. 运维特性

- **Snapshot 过期**：writer 提交时自动执行；`snapshot.time-retained` 默认 1 h，`snapshot.num-retained.min` 10，`num-retained.max` 无上限，`snapshot.expire.limit` 10，`snapshot.expire.execution-mode` sync/async。保留过短会导致长批查询找不到文件或流作业重启失败（用 consumer-id 保护）。[来源: https://paimon.apache.org/docs/master/maintenance/manage-snapshots/]
- **孤儿文件**：`sys.remove_orphan_files` 默认只删 1 天前的未引用文件；管理型 BLOB 包用 `remove_orphan_blobs`；空目录默认不清理（对象存储代价高），可开 `snapshot.clean-empty-directories`。[来源: 同上; https://paimon.apache.org/docs/master/primary-key-table/blob-storage/]
- **Compaction**：默认由 writer 内嵌执行；可用 `num-sorted-run.stop-trigger`、`lookup-wait=false` 等做异步 compaction；多写者场景推荐 `write-only=true` + **dedicated compaction job**（`sys.compact` / `sys.compact_database`，`divided`/`combined` 模式，`minor`/`full` 策略），`write-only` 同时跳过 snapshot 过期，需由专职作业接管。还提供 sort compact（zorder/hilbert，仅 `bucket=-1` 批模式）、历史分区 compact、`compaction.skip-expired-partitions`。[来源: https://paimon.apache.org/docs/master/maintenance/dedicated-compaction/; https://paimon.apache.org/docs/master/primary-key-table/compaction/]
- **小文件治理**：根源在 checkpoint 间隔 × 活跃分区/bucket 数；手段包括拉长 checkpoint、`write-buffer-size`/`write-buffer-spillable`、`precommit-compact`、后台/专职 compaction、合理分区与 bucket 数。[来源: https://paimon.apache.org/docs/master/learn-paimon/small-files/; https://paimon.apache.org/docs/master/append-table/streaming/]
- **Metrics**：表粒度 Gauge/Counter/Histogram，覆盖 scan、commit、write、write buffer、compaction、lookup、blob fetch，桥接至 Flink metrics，并实现 FLIP-33 标准 source/sink 指标。[来源: https://paimon.apache.org/docs/master/maintenance/metrics/]
- **分区生命周期**：`partition.expiration-time` 配合 `values-time`/`update-time` 策略过期分区；`partition.mark-done-action` 通知下游。[来源: https://paimon.apache.org/docs/master/maintenance/manage-partitions/]

## 10. Python / 非 JVM 访问

- **PyPaimon** 是 Paimon 的 Python 实现，代码位于主仓库 `paimon-python/`，与 Paimon 共用版本号（PyPI `pypaimon==2.0.0`）；旧仓库 `apache/paimon-python` README 已声明废弃。核心 API 不依赖 JDK/Flink/Spark。[来源: https://paimon.apache.org/docs/master/pypaimon/; https://paimon.apache.org/docs/master/project/releases/; https://github.com/apache/paimon-python]
- **能力**：catalog 连接、建表；批写（Arrow/pandas，overwrite）；批读（谓词/投影下推，输出 Arrow/pandas/DuckDB/迭代器，增量读、shard 读、`explain()`）；流读 + consumer 管理；tag/branch；多模态 API（BLOB、向量、全文检索）；Ray Data、Daft、PyTorch、SQL、CLI 等 extras；基础包要求 Python 3.6+，多数 extras 需 3.8/3.10+。[来源: https://paimon.apache.org/docs/master/pypaimon/installation/; .../pypaimon/reading/; .../pypaimon/streaming/]
- **限制**：Python 提交不合并 manifest（需 Java 侧 `compact_manifest`）；文档提示"已发布包可能尚未包含全部描述特性"。[来源: https://paimon.apache.org/docs/master/pypaimon/writing/]
- **Rust**：`apache/paimon-rust`，"under active development (0.4.0 in development)"，提供原生读取器（TPC-DS 与 DataFusion Parquet 读取器差距 <10%）、多对象存储、REST Catalog、DataFusion SQL 集成、C/Go/Python bindings。[来源: https://paimon.apache.org/docs/rust/; https://github.com/apache/paimon-rust]
- **C++**：`apache/paimon-cpp`，Arrow C Data Interface 交换数据；可写 append 与 PK 表（含 compaction），但 commit 仅支持 append 表；读 append 表与 PK 表（DV / MOR）；ORC/Parquet/Avro；`PAIMON_ENABLE_REST=ON` 支持 REST catalog。[来源: https://paimon.apache.org/docs/master/program-api/cpp-api/; https://github.com/apache/paimon-cpp]

## 11. 典型使用场景与局限

官方 Scenario Guide 的决策表：CDC/upsert → PK 表 `deduplicate`；多流更新实体列 → `partial-update` + sequence group；累加指标 → `aggregation`；保留首条 → `first-row`；批 ETL/日志/分区覆盖 → unaware-bucket append；等值过滤或 bucketed join → fixed-bucket append；分区+bucket 内保序 → ordered bucketed append；BLOB/向量检索 → 多模态 Data Evolution 表。[来源: https://paimon.apache.org/docs/master/learn-paimon/scenario-guide/]

文档明确标注的 caveats：

- dynamic bucket 下同一分区只允许一个写作业，`write-only` 也无法解除。[来源: .../primary-key-table/data-distribution/]
- 跨分区 upsert 依赖本地磁盘索引，大表启动与存储成本高。[来源: 同上]
- `lookup`/`full-compaction` producer 与 `full-compaction.delta-commits` 对写性能影响大，建议全量同步阶段先关闭。[来源: .../maintenance/write-performance/]
- MOW/`first-row` 批读默认看不到未经 lookup compaction 的 L0 数据。[来源: .../primary-key-table/table-mode/]
- 可空主键表不能在 Flink 上以 `none` producer 做更新流读。[来源: .../primary-key-table/]
- 对象存储 rename 非原子，多写者需 Hive/JDBC lock 或 REST catalog。[来源: .../concepts/concurrency-control/]
- Hive 只能 MR 引擎 `INSERT INTO`；Iceberg 侧读 PK 表存在 compaction 带来的可见性延迟。[来源: .../ecosystem/hive/; .../iceberg/primary-key-table/]
- CSV/Text/JSON 格式与 row tracking 均标注 experimental；`VARIANT` 需 Flink 2.0+/Spark 4.0+。[来源: .../concepts/spec/fileformat/; .../append-table/row-tracking/; .../concepts/data-types/]

## 12. 与本项目（Colvenna Lake）的相关性判断

若 Colvenna Lake 决定基于 Paimon 构建，需要尽早确定的关键决策点：

1. **表类型**：数据是"按 key 合并的实体状态"（PK 表）还是"独立追加的事件"（append 表）；需要 SQL `UPDATE/DELETE` 不等于必须用 PK 表。
2. **bucket 策略**：单写作业且 key 分布未知选 dynamic（默认）；多写者或需要 bucket 裁剪/bucketed join 选 fixed（需预估 200 MB–1 GB/bucket 并规划 rescale）；批量导入且分区量差异大可评估 postpone。
3. **merge engine 与顺序**：CDC 用 `deduplicate` + `sequence.field`；多源拼宽表用 `partial-update` + sequence group；指标累加用 `aggregation` 并核对各函数的回撤支持。
4. **changelog producer**：只做批分析留 `none`；上游已是完整 CDC 用 `input`；下游需要完整变更且要低延迟用 `lookup`（预算 compaction 资源）；可接受分钟级延迟用 `full-compaction`。
5. **table mode**：分析读多选 MOW（DV），同时确认下游引擎（Trino/StarRocks/Doris/Iceberg reader）对 DV 的支持。
6. **catalog 选型**：单引擎实验用 filesystem；多引擎共享且已有 HMS 用 hive；需要 view/function/权限/托管快照提交或跨语言（Rust/C++/Python）统一接入优先 REST catalog；对象存储上多写者必须解决锁或用 REST。
7. **运维基线**：明确 snapshot 保留、tag 策略、是否拆出 dedicated compaction job（`write-only`），以及 Iceberg 兼容是否需要开启。

## 13. AWS S3 部署要点（2026-09-28 核实）

### 13.1 快照提交在 S3 上不是原子的，多写者必须加锁

- `RenamingSnapshotCommit` 源码注释："when the file system is local or HDFS, rename is atomic. But if the file system is object storage, we need additional lock protection"；提交逻辑为 `lock.runWithLock(() -> !fileIO.exists(newSnapshotPath) && fileIO.tryToWriteAtomic(...))`。[来源: https://github.com/apache/paimon/blob/master/paimon-core/src/main/java/org/apache/paimon/catalog/RenamingSnapshotCommit.java]
- `FileIO.tryToWriteAtomic` 默认实现是"写临时文件 + rename"。[来源: https://github.com/apache/paimon/blob/master/paimon-common/src/main/java/org/apache/paimon/fs/FileIO.java]
- **`S3FileIO` 没有覆写 `tryToWriteAtomic`**，沿用 rename 路径，仅重写了 `newTwoPhaseOutputStream`（multipart upload）和配置映射；对比 `OSSFileIO` 覆写了该方法并用 `x-oss-forbid-overwrite: true` 做条件写。[来源: https://github.com/apache/paimon/blob/master/paimon-filesystems/paimon-s3-impl/src/main/java/org/apache/paimon/s3/S3FileIO.java; https://github.com/apache/paimon/blob/master/paimon-filesystems/paimon-oss-impl/src/main/java/org/apache/paimon/oss/OSSFileIO.java]
- Hadoop S3A 文档："Directory renames are not atomic ... callers cannot safely rely on atomic renames as part of a commit algorithm"；"S3 ... doesn't support an atomic create-no-overwrite operation"（S3A 层面）。[来源: https://hadoop.apache.org/docs/stable/hadoop-aws/tools/hadoop-aws/index.html]
- AWS S3 自身已支持 `If-None-Match` 条件写（PutObject / CompleteMultipartUpload / CopyObject，需 SigV4），但 Paimon master 的 S3 实现尚未使用。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html]

**结论**：AWS S3 上单表多写者（多个 Flink/Spark 作业、或写入作业 + 独立 compaction 作业）必须使用带锁的 catalog 或 REST catalog，filesystem catalog 只能单写者。

### 13.2 AWS 上可用的锁 / catalog 方案

| 方案 | 可行性 | 依据 |
| --- | --- | --- |
| Hive catalog + `lock.type=hive`，HMS 自建或跑在 EMR | ✅ | `lock.type` 文档取值含 `hive`、`zookeeper` [来源: https://github.com/apache/paimon/blob/master/docs/generated/catalog_configuration.html] |
| Hive catalog 用 Glue 做后端（`metastore.client.class` 指向 awslabs Glue 客户端） | ❌ 无锁 | awslabs 客户端 `GlueMetastoreClientDelegate.lock()` 直接 `throw new UnsupportedOperationException("lock is not supported")`；仓库仍在维护（最近推送 2026-05）[来源: https://github.com/awslabs/aws-glue-data-catalog-client-for-apache-hive-metastore] |
| JDBC catalog + `lock.type=jdbc`，后端用 RDS/Aurora MySQL 或 PostgreSQL | ✅ 推荐 | 官方："Setting `lock.enabled = true` (with `lock.type = jdbc`) is still recommended for multi-process deployments" [来源: https://github.com/apache/paimon/blob/master/docs/docs/concepts/views.md]。**注意文档不一致**：Spark 侧 `spark/catalogs.md` 仍写 "lock configuration is only supported for MySQL and SQLite"，但源码 `DistributedLockDialectFactory` 已注册 `PostgresqlDistributedLockDialect`（提交 "[core] Support lock for postgres catalog (#5005)"，2025-02-10），以源码为准，PoC 需实测 [来源: https://github.com/apache/paimon/blob/master/paimon-core/src/main/java/org/apache/paimon/jdbc/DistributedLockDialectFactory.java; https://github.com/apache/paimon/blob/master/docs/docs/spark/catalogs.md] |
| Paimon REST catalog | ⚠️ 需自建服务端 | 文档只点名阿里云 DLF 作为 token provider，认证仅 `bear` / `dlf`，未提及 AWS/SigV4/IAM，也未列出任何开源服务端实现 [来源: https://paimon.apache.org/docs/master/concepts/rest/] |

### 13.3 通过 Iceberg 兼容模式接入 Glue / Athena

- Paimon 官方文档有 **AWS Glue Catalog** 小节：设置 `metadata.iceberg.hive-client-class = com.amazonaws.glue.catalog.metastore.AWSCatalogMetastoreClient`，并可用 `metadata.iceberg.glue.skip-archive` 跳过 Glue 表版本归档。[来源: https://github.com/apache/paimon/blob/master/docs/docs/iceberg/hive-catalog.md]
- 生态页专门写了 **AWS Athena**："Athena discovers Iceberg tables through AWS Glue ... Athena documents support for Iceberg v2 tables. Use the full-compaction mode for primary key tables when querying with a reader that cannot consume v3 deletion vectors."[来源: https://github.com/apache/paimon/blob/master/docs/docs/iceberg/ecosystem.mdx]
- Athena 侧限制：Iceberg 1.4.2、"Only Iceberg tables created against the AWS Glue catalog ... are supported"、"Athena only creates and operates on Iceberg v2 tables"。[来源: https://docs.aws.amazon.com/athena/latest/ug/querying-iceberg.html]
- 也可走 Glue 的 Iceberg REST endpoint（`https://glue.<region>.amazonaws.com/iceberg`，需 `rest.sigv4-enabled=true`、`rest.signing-name=glue`、`warehouse=<account-id>`）；Paimon 把 `metadata.iceberg.rest.*` 前缀选项透传给 Iceberg REST 客户端，源码 `IcebergRestMetadataCommitter` 亦针对 Glue "do not auto-assign a table location" 与 `s3://` scheme 做了处理。[来源: https://docs.aws.amazon.com/glue/latest/dg/connect-glu-iceberg-rest.html; https://paimon.apache.org/docs/master/iceberg/rest-catalog/; https://github.com/apache/paimon/blob/master/paimon-iceberg/src/main/java/org/apache/paimon/iceberg/IcebergRestMetadataCommitter.java]
- Iceberg REST 发布的限制：tag 创建/删除 "not guaranteed to propagate"；含 GEOMETRY/GEOGRAPHY 列的表不能用 REST 发布；需 JDK 11+ 与 `paimon-iceberg` jar。[来源: https://paimon.apache.org/docs/master/iceberg/rest-catalog/]

### 13.4 AWS 托管计算 / 查询服务对 Paimon 的支持

| 服务 | 状态 | 依据 |
| --- | --- | --- |
| Amazon EMR 7.14 | 自带 Flink 1.20.0、Spark 3.5.8、Trino 479、Hive 3.1.3、Iceberg 1.10.1、Hudi 1.0.2、Delta 3.3.2；**应用列表无 Paimon**，需自行放置 `paimon-flink` / `paimon-spark` / `paimon-s3` jar | [来源: https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-release-app-versions-7.x.html] |
| Amazon Managed Service for Apache Flink | 支持 Flink 1.20.5 与 2.x；connector 随应用 jar 打包，官方连接器列表未提及 Paimon | [来源: https://docs.aws.amazon.com/managed-flink/latest/java/flink-1-20.html; https://docs.aws.amazon.com/managed-flink/latest/java/how-flink-connectors.html] |
| Amazon Athena | 不能直读 Paimon，只能读 Paimon 经 Glue 发布的 Iceberg v2 元数据 | 见 13.3 |
| Doris / StarRocks | 需自托管（EC2/EKS）或厂商托管服务；两者的 Paimon catalog 均列出 S3 及 IAM 认证方式 | 见 7.1 |

### 13.5 K8s 多云部署（S3 作为统一对象存储，2026-09-28 核实）

**存储层一致性**：Paimon 所有对象存储实现中只有 `OSSFileIO` 覆写了 `tryToWriteAtomic`（条件写）；`S3FileIO`、`GSFileIO`、`AzureFileIO`、`COSNFileIO`、`OBSFileIO` 均继承 `HadoopCompliantFileIO`、沿用 rename 路径。因此无论落在哪家云的 S3 或 S3 兼容存储上，多写者都需要 catalog 锁，行为一致。[来源: https://github.com/apache/paimon/tree/master/paimon-filesystems] S3 兼容存储（MinIO、COS、IBM COS）只需改 endpoint，standalone MinIO 需 `s3.path.style.access=true`。[来源: https://paimon.apache.org/docs/master/maintenance/filesystems/]

**JDBC catalog 锁的后端**：源码提供 `MysqlDistributedLockDialect`、`PostgresqlDistributedLockDialect`、`SqlLiteDistributedLockDialect` 三种方言；配置项 `catalog-key`（默认 `jdbc`）、`lock-key-max-length`（默认 255，lock key 由 catalog-key + database + table 拼接）。[来源: https://github.com/apache/paimon/tree/master/paimon-core/src/main/java/org/apache/paimon/jdbc; https://github.com/apache/paimon/blob/master/docs/generated/jdbc_catalog_configuration.html] 在 K8s 多云场景下，可用各云托管 PostgreSQL/MySQL，或在集群内自建 PostgreSQL，Paimon 侧配置不变。

**各组件的官方 K8s 部署方式**：

| 组件 | 官方方式 | 关键事实 |
| --- | --- | --- |
| Flink | Apache Flink Kubernetes Operator（Apache 官方） | application / session / session job 三类 CR；native 与 standalone 模式；有状态升级、回滚、蓝绿部署、声明式 savepoint、K8s HA、autoscaler、autotuning；最新稳定版 1.16 [来源: https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/concepts/overview/] |
| Spark | 原生 `spark-submit --master k8s://`（cluster/client）；Apache Spark Kubernetes Operator（Spark 官方子项目） | 原生模式：K8s 无 external shuffle service，动态分配需 `shuffleTracking`；最新文档要求 K8s ≥ 1.34。Operator：CRD `SparkApplication` / `SparkCluster`，Helm 安装，示例基于 Spark 4.2.0，README 未标注成熟度 [来源: https://spark.apache.org/docs/latest/running-on-kubernetes.html; https://github.com/apache/spark-kubernetes-operator] |
| Trino | trinodb/charts Helm chart | chart 1.42.2 对应 Trino 480；Paimon 插件需自行打进镜像（chart 首页未提及） [来源: https://github.com/trinodb/charts] |
| Doris | apache/doris-operator（Apache 官方） | CRD `DorisCluster`；"Support deploy storage-compute separation mode"；K8s 1.19+；组件最低 8c8g；有 helm-charts 目录；Releases 区为空 [来源: https://github.com/apache/doris-operator] |
| StarRocks | StarRocks Kubernetes Operator | CR `StarRocksCluster`，含 FE/BE/CN spec；自述 "level 2 operator"；shared-data 模式另有页面；K8s 版本要求未提及 [来源: https://docs.starrocks.io/docs/deployment/sr_operator/] |

**验证环境决定（2026-09-28）**：首个验证环境直接使用 AWS S3，不用 MinIO 模拟，S3 费用不是约束。多云可移植性在后续接入其他云的 S3 兼容存储时再验证。S3 侧建议同时配置：为 Paimon 桶加 `AbortIncompleteMultipartUpload` 生命周期规则（Paimon 的 `S3TwoPhaseOutputStream` 走 multipart upload，作业异常退出会留下未完成分片，AWS 文档建议以生命周期规则清理 [来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html 末尾 Note]）；遇到连接池超时时按 Paimon 文档设置 `fs.s3a.connection.maximum=1000` [来源: https://paimon.apache.org/docs/master/maintenance/filesystems/]。

**多云下需要注意的差异**：

- Doris 的 Paimon catalog 存储列表为 HDFS、S3、GCS、Ozone、OSS、COS、OBS、MinIO，**未列 Azure**；StarRocks 列出 S3、Azure Blob/ADLS、GCS、MinIO。如果某朵云不用 S3 兼容存储而用原生接口，需按此表核对。[来源: 见 7.1]
- Iceberg 兼容模式在多云下应发布到自建的 Iceberg REST catalog 而非 Glue，以保持云中立；Paimon 只透传 `metadata.iceberg.rest.*`，具体服务端（Polaris / Lakekeeper / Gravitino 等）与 Paimon 的兼容性官方文档未列出，需自行验证。

## 14. 流式接入方案（K8s + S3 + JDBC catalog，2026-09-28 核实）

### 14.1 三条接入路径

| 路径 | 适用 | 关键事实 |
| --- | --- | --- |
| **Paimon CDC action**（`paimon-flink-action` jar） | 数据库直连 CDC、Kafka/Pulsar 上的 CDC 事件 | 官方支持 MySQL（表/库）、PostgreSQL（仅表）、MongoDB（集合/库）、Kafka、Pulsar；自动建表、运行中应用 schema 变更；官方给出 Flink Kubernetes Operator 的 `FlinkDeployment` 示例：action jar 打进镜像，`entryClass=org.apache.paimon.flink.action.FlinkActions`，参数走 `job.args`，"Do not run `flink run` inside the container" [来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/index.mdx; https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/mysql-cdc.mdx] |
| **Flink CDC YAML pipeline → Paimon sink** | 想用 Flink CDC 的 pipeline 框架（route/transform）时 | Flink CDC 3.6 文档：只支持 Paimon 主键表；"Not support exactly-once. The connector uses at-least-once + primary key table for idempotent writing"；`catalog.properties.metastore` 文档写 "supports filesystem and hive"，未提 jdbc/rest [来源: https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/pipeline-connectors/paimon/] |
| **Flink SQL `INSERT INTO ... SELECT`** | 事件流、已有 Flink SQL 作业、需要自定义转换 | 流模式需开 checkpoint，"Data becomes visible through committed snapshots"；schema 固定，源加列不会自动同步（这是 CDC action 与 SQL 的核心差异）[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/sql-write.mdx; cdc-ingestion/index.mdx "What Is Schema Evolution"] |

### 14.2 CDC action 的关键行为

- **同步模式**：`mysql_sync_database` 有 `divided`（默认，每表一个 sink，新表需带 savepoint 重启并扩大过滤）与 `combined`（共享 sink，运行中自动纳入新建表）两种；`--table_conf_by_table` 可为不同源表设不同 bucket 等表属性，但 `sink.parallelism`、writer/committer 资源必须全局设置。[来源: mysql-cdc.mdx]
- **Kafka 格式**：`canal-json`、`debezium-json`（建议保留 `schema` 字段以获得类型）、`debezium-avro`（需 schema registry）、`maxwell-json`、`ogg-json`、`json`（仅 insert，可建无主键 append 表）、`aws-dms-json`、`debezium-bson`；缺类型的字段映射为 `STRING`；库同步需要事件携带库表名用于路由。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/kafka-cdc.mdx]
- **Schema 演进边界**：加列 ✅；字符串/二进制加长 ✅；整数/浮点同族加宽 ✅；非字符串→字符串需 `--type_mapping allow-non-string-to-string`；**删列保留原列、改名视为新列、改表名不改 Paimon 表**；不迁移主键与分区键；不兼容变更会让作业失败而非忽略。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/schema-evolution.md]
- **类型映射默认值**：MySQL `TINYINT(1)`→`BOOLEAN`、`BIGINT UNSIGNED`→`DECIMAL(20,0)`、PostgreSQL 无精度 `NUMERIC`→`DECIMAL(38,18)`；可用 `--type_mapping tinyint1-not-bool,to-nullable,char-to-string,...` 调整。[来源: 同上]
- **计算列**：`--computed_column 'pt=date_format(create_time,yyyy-MM-dd)'` 派生分区字段；epoch 毫秒用 `date_format(col,yyyy-MM-dd,3)`。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/action-configuration.mdx]
- **checkpoint**：`-Dexecution.checkpointing.interval=<interval>`；若未开启，action 默认以 **180 s** 开启。[来源: 同上]
- **PostgreSQL**：需要 replication slot（示例 `slot.name=paimon_cdc`），只有 `postgres_sync_table`，没有库级 action。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/postgres-cdc.mdx]

### 14.3 写入侧表配置与并发约束

- **延迟由 checkpoint 间隔决定**：提高吞吐的官方建议是加大 checkpoint 间隔、`max-concurrent-checkpoints=3`、加大 `write-buffer-size`、开 `write-buffer-spillable`；`sink.parallelism` 建议 ≤ bucket 数且最好相等。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/write-performance.md]
- **changelog producer**：`lookup` / `full-compaction` 与 `full-compaction.delta-commits` 对写性能有影响；bucket 太少时 full-compaction 可能导致 checkpoint 超时（Flink 默认 10 min），可调 `execution.checkpointing.timeout`。[来源: 同上]
- **dynamic bucket 的硬限制**："Only one job may write to a given partition in dynamic-bucket mode ... `write-only` with a dedicated compaction job does not remove this restriction."[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/data-distribution.md]
- **多作业写同一分区**（如多个流写 partial-update 表）：必须 `write-only=true` + 独立 compaction 作业；`write-only` 同时跳过 snapshot 过期，需由 compaction 作业接管；`compact_database` 支持 `--mode combined` 自动纳入新表，流模式用 `minor` 策略持续监控，批模式用 `full`。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/dedicated-compaction.mdx]
- **下游流读**：`consumer-id` 记录读取位点并阻止所需快照过期，需先设 `consumer.expiration-time` 防止废弃 consumer 无限保留历史；默认 `exactly-once` 模式对齐 checkpoint。[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/consumer-id.mdx]
- **Paimon 作为 Flink CDC source**：可用 Paimon pipeline source connector 把 Paimon 表变更推到 Doris 等外部系统，支持发现新建表。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/flink-cdc.mdx]

### 14.4 事件流（日志 / 埋点）接入 append 表的完整流程

**表模型**：append 表无主键，"Each inserted row is stored as a new record, including rows whose values duplicate an existing record"；默认 `bucket = -1`（unaware-bucket），无流式行序保证；需要按某列裁剪或桶内有序时用 bucketed append（正整数 `bucket` + `bucket-key`，有序需 `bucket-append-ordered = true`）。[来源: https://github.com/apache/paimon/blob/master/docs/docs/append-table/index.mdx; append-table/bucketed.mdx]

**写入路径**：

- Flink SQL 流模式 `INSERT INTO ... SELECT` 自 Kafka 源，必须开 checkpoint，"New data becomes visible after a snapshot is committed; end-to-end latency depends on checkpointing, commit time, and the reader's discovery interval"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/append-table/streaming.mdx]
- 或用 `kafka_sync_table --kafka_conf value.format=json`，官方示例即"records such as logs that contain only inserts"，通过 `--computed_column 'pt=date_format(event_tm, yyyyMMdd)'` 派生分区、自动建无主键表，并随事件自动加列。[来源: https://github.com/apache/paimon/blob/master/docs/docs/cdc-ingestion/kafka-cdc.mdx]

**小文件治理三选一**（官方表）：

| 方式 | 何时运行 | 配置 |
| --- | --- | --- |
| Pre-commit compaction | 提交前合并同分区新写文件，计入可见延迟 | `precommit-compact = true`（默认 false） |
| 入湖作业内后台 compaction | unaware-bucket 表的 Flink sink 默认加 compact coordinator + workers，异步但占用作业资源 | 默认行为 |
| Dedicated compaction | 独立作业合并已提交文件 | 入湖作业 `write-only = true`，另跑 dedicated compaction；`write-only` 同时跳过 snapshot 过期 |

开启 `clustering.incremental = true` 后 sink 不再加后台 compaction，需调度 incremental clustering 作业兼做小文件合并。[来源: append-table/streaming.mdx; append-table/incremental-clustering.mdx]

**分区生命周期**：

- 过期：`partition.expiration-time` + `partition.expiration-check-interval` + `partition.timestamp-formatter`（`values-time` 策略，默认）或 `partition.expiration-strategy = update-time`；过期是逻辑删除，物理删除随 snapshot 过期；空目录默认不清理，"on object stores (e.g. OSS, S3) this may cause performance issues"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/maintenance/manage-partitions.md]
- 完成信号：`partition.time-interval` + `partition.idle-time-to-done` + `partition.mark-done-action`（`success-file` 默认写 `_SUCCESS`；`done-partition`；`http-report`；`custom`），"This is a scheduling signal; late records may still arrive"。[来源: https://github.com/apache/paimon/blob/master/docs/docs/flink/sql-write.mdx]

**查询加速**（写入前配置）：分区裁剪；bucket 裁剪（bucketed 表上对完整 bucket-key 的 `=` / `IN`）；文件 min/max 统计 + incremental clustering（`clustering.columns`）；file index：`file-index.bloom-filter.columns`（等值）、`file-index.bitmap.columns`（等值/集合）、`file-index.range-bitmap.columns`（范围）；已有文件用 `sys.rewrite_file_index` 补建索引；聚合下推。[来源: https://github.com/apache/paimon/blob/master/docs/docs/append-table/query-performance.md]

**作为队列被下游流读**：默认先读最新快照再跟随新记录，`scan.mode = latest` 只读新增；可声明 event-time watermark；unaware-bucket 不保证行序；流读默认忽略 `INSERT OVERWRITE`，`streaming-read-append-overwrite = true` 可读入覆盖新增行但不发 retraction；"Row tracking and deletion vectors do not make a regular append-table stream a complete row-level change feed"。[来源: append-table/streaming.mdx]

**行级修正**：Spark SQL `DELETE` / `UPDATE` / `MERGE INTO`，可选重写文件或 deletion vector；row tracking 提供隐藏行 ID 与版本。[来源: append-table/index.mdx]

### 14.5 大流量下的扩展性（append 表）

**官方文档不提供吞吐基准数字**，以下是从架构与配置项推出的扩展边界（推断项已标注）：

- **写入并行度水平扩展**：unaware-bucket append 表 "flexible ingestion without distributing rows by a bucket key"，writer 之间无 bucket 约束；`sink.parallelism` 可独立设置。推断：写入吞吐随并行度线性扩展，上限来自 Flink 资源与 S3。[来源: append-table/index.mdx; write-performance.md]
- **单点是 committer**："Committer node may use a large memory if the amount of data written to the table is particularly large"；官方方案是 Flink 细粒度资源管理 + `sink.committer-memory` / `sink.committer-cpu` 单独给 committer 加资源；`commit.publish-thread-num` 与 `commit.cleanup-thread-num` 默认 64 线程。[来源: write-performance.md; core_configuration.html]
- **每次 checkpoint 的文件数 ≈ 并行度 × 活跃分区数**（推断），checkpoint 越短小文件越多；官方提高吞吐的手段是加大 `execution.checkpointing.interval`、`max-concurrent-checkpoints=3`、加大 `write-buffer-size`（默认 256 MB）、`write-buffer-spillable`（默认 true）；append 表另有 `write-buffer-for-append`（默认 false，"Whether the write use write buffer to avoid out-of-memory error"）。[来源: write-performance.md; core_configuration.html]
- **元数据增长**：manifest 自动合并（`manifest.target-file-size` 8 MB、`manifest.full-compaction-threshold-size` 16 MB）；固定的 Paimon 2.0.0 `CoreOptions` 只提供 `manifest.merge-min-count`（默认 30），没有 `manifest.merge.skip-on-write-only`。维护作业可以覆写较低阈值，写入端达到自身阈值时仍可能执行合并，不能把该版本称为完全移出写入端。snapshot 过期默认 `sync`，"If there are too many files, it may take a long time and block stream processing"，可改 `async`。[来源: core_configuration.html；Paimon 2.0.0 `paimon-api` sources 的 `CoreOptions.java` 与 `paimon-core` sources 的 `FileStoreCommitImpl.java`]
- **写初始化**：同时写大量分区时 writer 需读历史文件，`sink.writer-coordinator.enabled` 在 JobManager 缓存 manifest（默认 2 GB），高负载下需关 `cache-soft-values` 防止缓存抖动。[来源: write-performance.md]
- **compaction 必须跟上**：unaware-bucket 的后台 compaction 与写入争资源，"Size those resources for both ingestion and compaction"；大流量下应 `write-only=true` 并独立扩缩 compaction 作业。[来源: append-table/streaming.mdx]
- **S3 请求速率**："at least 3,500 PUT/COPY/POST/DELETE or 5,500 GET/HEAD requests per second per partitioned Amazon S3 prefix. There are no limits to the number of prefixes"；扩容"happens gradually and is not instantaneous"，期间可能出现 503 Slow Down。Paimon 分区目录天然形成多前缀（推断：按 dt/hr 分区即可分散写入热点）。[来源: https://docs.aws.amazon.com/AmazonS3/latest/userguide/optimizing-performance.html]
- **checkpoint 超时**：默认 10 min，full-compaction 或资源不足时可能超时，可调 `execution.checkpointing.timeout`。[来源: write-performance.md]

**容量规划要监控的指标**：commit 耗时与 committer 内存、每个 snapshot 的新增文件数与平均文件大小、compaction 积压（未合并小文件数）、manifest 总大小、S3 503 次数。指标接口见第 9 节 metrics。

### 14.6 CDC 更新链路：主键表内部机制（2026-09-28 核实）

**四个独立决策**（官方主键表概览的设计表）：merge engine（同 key 如何合成一行）、sequence 与 row kind（谁优先、是插入还是撤回）、table mode（读时合并 / 写时合并 / 全量重写）、changelog producer（下游流读拿到什么）。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/index.md]

**存储结构**：每个分区内每个 bucket 一棵 LSM；writer 缓冲、排序、刷出 L0 文件（每个 L0 文件算一个 sorted run，每个非空高层算一个 run）；`num-sorted-run.compaction-trigger` 默认 5 触发 compaction，`num-sorted-run.stop-trigger` 默认 trigger+3 时 writer 暂停等待（write stall）。[来源: primary-key-table/index.md; compaction.md]

**Table mode 对比**（官方表）：

| 模式 | 配置 | 读路径 | 主要代价 |
| --- | --- | --- | --- |
| MOR（默认） | 无 | 合并重叠 sorted run | 读 CPU/内存随版本数增长；非主键列过滤不能提前下推 |
| COW | `full-compaction.delta-commits = 1` | 读全量合并结果 | 每次提交全量 compaction，写放大；与 `lookup` producer 不兼容 |
| MOW | `deletion-vectors.enabled = true` | 读文件并跳过 DV 标记的行位置 | writer 在 lookup compaction 时查旧行、维护 DV；默认批读跳过未 compaction 的 L0，writer 默认等待 lookup compaction；可用 `deletion-vectors.merge-on-read=true` 读入未 compaction 数据 |

官方建议："For a typical `deduplicate` table with frequent analytical reads, consider MOW"；已有表改 DV 需 `deletion-vectors.modifiable` + full compaction 迁移。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/table-mode.md]

**乱序处理**：默认按内部序号，"does not necessarily match the order in the source system"；设 `sequence.field` 为源版本/时间戳字段，可多字段 `'update_time,source_offset'` 逐级比较；`sequence.field.sort-order` 默认 ascending。Flink SQL 需设 `table.exec.sink.upsert-materialize = NONE`，否则 sink materializer 可能重排记录。`rowkind.field` 用于把 `+I/-U/+U/-D` 编码在数据列的源。[来源: primary-key-table/sequence-rowkind.mdx; merge-engine/index.md]

**deduplicate 语义**：保留最新完整行；最新记录为 `-D`/`-U` 则删除逻辑行；新行中的 null 覆盖旧值（需"null 表示不改"用 partial-update）；`ignore-delete=true` 仅在应丢弃撤回记录时使用。[来源: merge-engine/index.md]

**changelog producer**（DV 表支持 none/input/lookup，不支持 full-compaction）：

| producer | 旧值来源 | 可见时机 | 适用 |
| --- | --- | --- | --- |
| `none` | 消费端状态（Flink 加 normalize 算子，状态成本随 key 数增长） | 增量快照读 | 消费端接受 upsert |
| `input` | 源提供完整前后镜像 | 提交的 changelog 文件 | 数据库 CDC 已带 before 镜像 |
| `lookup` | compaction 时查旧行 | lookup compaction 提交后；writer 默认等待，`write-only` 时不等 | 输入无前镜像但下游需要完整变更 |
| `full-compaction` | 相邻两次全量 compaction 的差 | full compaction 后，中间更新可能被折叠 | 可接受周期性延迟 |

lookup 的缓存：`lookup.cache-max-memory-size` 默认 256 MB、`lookup.cache-file-retention` 1 h。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/changelog-producer.md]

**bucket 模式对 CDC 的影响**：

- Fixed：`Math.abs(key_hashcode % numBuckets)`，MOR 下每 bucket 200 MB–1 GB 为初始参考，改桶数需离线 rescale。
- Dynamic（默认）：key→bucket 索引，`dynamic-bucket.target-row-num` / `initial-buckets` / `max-buckets`；**同一分区只允许一个写作业**；活跃分区每 1 亿 key 约需 1 GB 索引内存。
- 跨分区更新（主键不含全部分区列）：仅 dynamic bucket 维护 key→分区映射（本地磁盘，启动时读全部已有 key 初始化，可用 `cross-partition-upsert.index-ttl` 限制）；deduplicate 下旧分区删、新分区插；fixed/postpone 不维护映射，要求输入自带旧分区行的撤回。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/data-distribution.md]

**partial-update 拼宽表的删除处理**：默认无 sequence group 时 `-D`/`-U` 被拒绝；`ignore-delete` 忽略；`partial-update.remove-record-on-delete` 整行删除（不能与 sequence group 组合）；sequence group 按组版本撤回受保护字段，`partial-update.remove-record-on-sequence-group` 指定哪些组的删除可删整行。流读完整合并行需 `lookup` 或 `full-compaction` producer。[来源: https://github.com/apache/paimon/blob/master/docs/docs/primary-key-table/merge-engine/partial-update.md]

**查询侧**：MOR 表可用 read-optimized 系统表（`$ro`）读已 compaction 数据；DV 表在支持的 connector 上可做元数据 `COUNT(*)` 下推；file index 仅对全量 compaction 文件或 DV 表有效；2.x 新增 Primary-Key Indexes（BTree/Bitmap/Vector/Full Text 等，bucket 本地、随 compaction 生命周期维护）。[来源: primary-key-table/query-performance.md; global-index.mdx]

### 14.7 CDC 链路是否需要 Kafka 中转

- **Paimon 官方 CDC action 直连源库**：`mysql_sync_database` 依赖 `flink-sql-connector-mysql-cdc-3.5.0` 与 MySQL 驱动，不经过消息队列。[来源: cdc-ingestion/mysql-cdc.mdx]
- **Flink CDC MySQL 源的读取模型**：快照阶段并行分块读取，"After all snapshot chunks finished, the source will continue to read binlog in a single task"；不需要全局读锁与 RELOAD 权限；每个作业以独立 server-id 作为一个 MySQL 从库加入集群，"if different jobs share a same server id, it may result to read from wrong binlog position"；`connection.pool.size` 默认 20。推断：N 个直连作业 = N 个 binlog 连接。[来源: https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/flink-sources/mysql-cdc/]
- **Paimon 表可作为层间队列**：主键表 `changelog-producer=input` 保存完整变更，下游 Flink 用 `consumer-id` 流读；append 表流读见 14.4。延迟下限是 checkpoint 间隔。[来源: changelog-producer.md; consumer-id.mdx]
- **2.x 已无 Kafka 外部 log system**：master 分支 core / flink connector 配置表中没有 `log.*` 选项，源码树中没有 KafkaLogStore 相关类，docs 中也无相关页面（1.x 及更早版本曾有 `log.system=kafka` 双写以获得低于 checkpoint 的读延迟，此处仅为背景，未在 2.x 一手材料中核实）。[来源: docs/generated/core_configuration.html; docs/generated/flink_connector_configuration.html; GitHub tree 2026-09-28]
- **经 Kafka 时的格式要求**：`kafka_sync_*` 支持 debezium-json（建议保留 `schema` 字段以获得类型）、canal-json、maxwell-json、ogg-json、aws-dms-json 等；缺类型的字段映射为 `STRING`。[来源: kafka-cdc.mdx]

**判断**：单一消费方（只进 Paimon）时不需要 Kafka；出现以下情形之一再加：同一 binlog 有多个异构消费方且 DBA 限制连接数；需要独立于源库 binlog 保留期的回放缓冲；层间需要亚秒级延迟（Paimon 做不到，且 2.x 无 Kafka log system 替代）。

### 14.8 CDC 数据源支持矩阵（Paimon 2.x master，Flink CDC 3.5/3.6）

**Paimon CDC action 官方支持的源**（依赖基线 Flink CDC 3.5.0）：

| 源 | 同步到一张表 | 同步整库 | 关键约束 |
| --- | --- | --- | --- |
| MySQL（含 RDS/Aurora/PolarDB MySQL、MariaDB） | `mysql_sync_table` | `mysql_sync_database` | 源表必须有主键；`divided`/`combined` 模式 |
| PostgreSQL | `postgres_sync_table`（支持 `schema-name` 正则合并多 schema） | **无库级 action** | 需 replication slot；分区场景需 `REPLICA IDENTITY FULL` 使 update/delete 带旧值 |
| MongoDB | `mongodb_sync_table` | `mongodb_sync_database` | 字段全部映射为 `STRING`；以 `_id` 为主键；`schema.start.mode=dynamic`（默认，从首个文档推断）或 `specified`（JSONPath 指定字段） |
| Kafka | `kafka_sync_table` | `kafka_sync_database` | 格式：canal-json / debezium-json / debezium-avro / maxwell-json / ogg-json / json / aws-dms-json / debezium-bson |
| Pulsar | `pulsar_sync_table` | `pulsar_sync_database` | 格式同 Kafka（debezium-json/avro 等） |
| Flink DataStream API | 自定义 CDC 记录 | 多表 sink 见 API 指南 | 自行构造 `RichCdcRecord` |

[来源: cdc-ingestion/index.mdx; mysql-cdc.mdx; postgres-cdc.mdx; mongo-cdc.mdx; kafka-cdc.mdx; pulsar-cdc.mdx]

**Flink CDC 3.6 自身的源**（可经 Flink SQL `INSERT INTO` 写 Paimon，但无 schema 自动演进）：mysql、postgres（9.6–14）、oracle（11/12/19/21）、sqlserver（2012–2019）、mongodb（3.6–7.0）、db2（11.5）、tidb、oceanbase、vitess。其中 MySQL/PostgreSQL/Oracle/SQL Server/MongoDB/Db2 支持增量快照与 exactly-once；TiDB/Vitess 仅无锁读；OceanBase 四项均不支持。[来源: https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/flink-sources/overview/]

**Flink CDC 3.6 YAML pipeline 的源**：仅 MySQL、PostgreSQL、Oracle 三种；sink 含 Paimon（0.6–1.3）、Iceberg、Hudi、Doris、StarRocks、Kafka、Elasticsearch、Fluss、MaxCompute、OceanBase。[来源: https://nightlies.apache.org/flink/flink-cdc-docs-stable/docs/connectors/pipeline-connectors/overview/]

**推断**：Oracle / SQL Server / Db2 进 Paimon 有两条路：Flink CDC YAML pipeline（Oracle 可，SQL Server/Db2 不可）或 Debezium → Kafka → `kafka_sync_*`（都可，且保留 Paimon 的 schema 演进）。

## 未能核实的问题

- **PIP 清单**：只抓到 PIP 流程页（定义、投票规则），未逐条获取 REST catalog、postpone bucket、data evolution 等具体 PIP 的编号与内容。[来源: https://cwiki.apache.org/confluence/display/PAIMON/Paimon+Improvement+Proposals]
- **2.0.0 版本亮点**：GitHub Release 正文只给出下载链接与 `release-1.4.2...release-2.0.0` 的 compare 链接，未找到官方版本 highlights 页面，故本文未列 2.0 新特性清单。
- **"streaming lakehouse" 一词**：一手资料中使用的是 "Realtime Lakehouse" / "streaming data lake platform"，未找到官方使用该精确措辞的出处。
- **与 Hudi / Delta Lake 的对比**：官方文档与 README 均未提及，本文未作任何对比。
- **GitHub Releases 网页**：页面部分加载失败，release 日期改用 GitHub REST API 核实。
- **paimon-rust / paimon-cpp 版本与能力边界**：仅依据仓库首页与文档站首页摘要，未核对源码与 release tag（两仓库 Releases 区均为空）。
- **StarRocks / Doris 侧支持细节**：已于 2026-09-28 补充核实（见 7.1），但 Doris 写入能力在其 master 文档内部存在冲突，已发布版本的实际能力需以对应版本文档确认；StarRocks 官方文档未声明 DV / time travel / REST catalog 支持情况。
- **Paimon REST catalog 开源服务端实现**：官方文档未列出，GitHub 代码搜索因 API 限流未完成；Gravitino / Polaris 等是否实现 Paimon REST 契约未核实。
- **AWS 官方对 Paimon on EMR 的说明**：仅核实 EMR 应用列表无 Paimon，未检索 AWS Big Data Blog 等一手博客。
- **Doris K8s 官方文档页**：`deploy-on-kubernetes` 页面抓取为空（JS 渲染），改用 apache/doris-operator 仓库首页核实；doris-operator 与 spark-kubernetes-operator 的最新 release 版本号均未能从页面获取。
- **Flink CDC Paimon sink 对 JDBC catalog 的支持**：Flink CDC 3.6 文档只写 filesystem 与 hive，JDBC/REST 是否可通过 `catalog.properties.*` 透传未核实；Paimon 自家 CDC action 的 `--catalog_conf metastore=jdbc` 路径未逐字核实但与 catalog 通用配置一致。
- **吞吐基准**：Paimon 官方文档无任何 TPS/MB/s 基准；用户案例（阿里、字节、快手等）均为厂商或会议材料，未采信。
- **docs 站 URL**：不带尾斜杠的路径返回 301，本文改用带尾斜杠形式并抽样确认 200，未逐条跟随全部重定向。
