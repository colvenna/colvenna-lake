---
title: "Apache Fluss 调研：能否、是否应在 Colvenna Lake 引入"
date: 2026-10-19
status: draft
sources:
  - https://github.com/apache/fluss (tag v1.0.0 = 5c07f88e50a8ff41b0ebc214a0458e5dba60be37：pom.xml、website/docs、helm/、fluss-lake/、fluss-server/ 源码；main = dbd6dd731331567cbfb28ef2db004a0e25c76407)
  - https://github.com/apache/fluss/releases/tag/v1.0.0
  - https://api.github.com/repos/apache/fluss/releases
  - https://fluss.apache.org/docs/ （当前即 1.0 文档；/docs/1.0/ 重定向至此）
  - https://fluss.apache.org/roadmap/ ; https://github.com/apache/fluss/discussions/2342
  - https://cwiki.apache.org/confluence/display/FLUSS/FLUSS+Improvement+Proposals
  - https://incubator.apache.org/projects/fluss.html
  - https://projects.apache.org/json/foundation/committees.json
  - https://lists.apache.org/thread/5q1zz4x47kyd2fl4r2xx8tpqz62k0zdw （[ANNOUNCE] Apache Fluss 1.0.0 released）
  - https://lists.apache.org/thread/2lk4hgl0s0jt42plqn026noq00n1yjr3 （[RESULT][VOTE] 1.0.0 RC3）
  - https://repo1.maven.org/maven2/org/apache/fluss/ （fluss-flink-1.20、fluss-client、fluss-lake-paimon、fluss-flink-tiering 1.0.0 jar）
  - https://github.com/apache/paimon (release-2.0.0 = 604e6d5；release-2.1.0)
  - https://github.com/apache/flink-cdc (release-3.5.0 / 3.6.0 / 3.7.0 的 Fluss pipeline connector)
  - https://github.com/apache/doris (4.1.4、4.1.5-release 源码树) ; https://github.com/apache/doris-website (0afdee0：docs/lakehouse/catalogs/fluss-catalog.md)
  - https://github.com/StarRocks/starrocks (main 249fb4f：fluss_catalog.md；4.1.3 标签)
---

# Apache Fluss 调研：能否、是否应在 Colvenna Lake 引入

> 说明：Fluss 的结论以 **1.0.0 发布标签**（`v1.0.0`，提交 `5c07f88`）的源码与随标签发布的文档（`website/docs/`）为准；同一内容已发布在 `fluss.apache.org/docs/`（2026-10-19 抽查 Paimon 版本矩阵、授权协议表与 Flink 版本表，与标签源码一致）。来源链接中 `github.com/apache/fluss/blob/v1.0.0/...` 为固定版本，`fluss.apache.org/docs/...` 会随下个版本变化。本文**没有部署或运行 Fluss**；"已证实"仅指文档、源码、release notes 或 jar 字节码中可见，不等于在本项目 EKS + S3 + colvenna-catalog 上联调通过。以「推断：」开头的内容是从一手材料推出的判断；Fluss 网站 User Stories 页的第三人称介绍标注「项目方站点表述」，Fluss 博客上署名作者的案例标注「案例观点」。

## 结论先行

- **能不能**：版本层面**可以做隔离验证**。Fluss 1.0.0 提供 Flink 1.20 connector、已核对的 connector、client、`fluss-lake-paimon`、tiering 四个 jar 中 Fluss 自有类为 Java 11 字节码、根 POM 依赖 Paimon **2.0.0**、文档写明 tiering 已对 Paimon 1.4/2.0 测试，与项目的 Flink 1.20.4 / Java 11 / Paimon 2.0.0 对得上。
- **该不该**：**现在不建议进入默认栈或任何生产/敏感数据路径**。主要原因：①项目没有对应需求（HLD 1.1 明确不承诺亚秒级，M1 稳态延迟表中各路径均判定通过）；②查询主引擎 Doris 4.1.4 读不了 Fluss 热数据，Doris 的 Fluss Catalog 只在未发布的 5.0 中且标为实验性；③安全模型与项目约束冲突：无 TLS、仅 SASL/PLAIN、取远端存储临时凭证的 RPC 不做 ACL、按源码 Paimon REST bearer token 可能随表属性下发给客户端；④Fluss 服务端会自行在 Paimon 目录建表/改表（源码），推断：这些变更不经 colvenna-operator 的准入；⑤多一套有状态集群（ZooKeeper + 带本地盘的 TabletServer + tiering 作业），1.0.0 仍有恢复类未关闭 issue。
- 详细的最小切入点、验证关口与重新评估触发条件见第 7 节。

## 1. 项目概况与成熟度

- **定位**：仓库描述为 "a streaming storage built for real-time analytics" [来源: https://api.github.com/repos/apache/fluss]；文档首页称其为 "streaming storage system for real-time analytics and AI, designed to serve as the real-time data layer of a Lakehouse architecture"，并称把消息队列、在线 KV、流处理状态后端和湖仓冷存储合到一个底座，同一张表可亚秒级新鲜度读取、按主键点查、并分层到 Iceberg / Paimon / Lance [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/intro.mdx]。
- **ASF 状态**：2025-06-05 进入孵化器；2026-07-02 IPMC 投票通过（30 binding +1）；ASF 董事会 2026-07-15 批准 TLP 决议；**2026-07-16 毕业为顶级项目** [来源: https://incubator.apache.org/projects/fluss.html]。ASF 项目目录记录 PMC 成立于 2026-07，Chair 为 Jark Wu，PMC 15 人 [来源: https://projects.apache.org/json/foundation/committees.json]。
- **最新正式版**：**1.0.0**。GitHub Release 发布时间 2026-09-22T14:00Z，release notes 首句称其为 "the first major release since Fluss graduated to an Apache Top-Level Project" [来源: https://github.com/apache/fluss/releases/tag/v1.0.0]；发布投票 12 票赞成、其中 5 票 binding、无反对 [来源: https://lists.apache.org/thread/2lk4hgl0s0jt42plqn026noq00n1yjr3]；邮件列表 2026-09-21 发出 `[ANNOUNCE] Apache Fluss 1.0.0 released`，含 Maven、crates.io（fluss-rs）与 PyPI（pyfluss）发布 [来源: https://lists.apache.org/thread/5q1zz4x47kyd2fl4r2xx8tpqz62k0zdw]。1.0.0 是毕业后由 PMC 投票的正式 ASF 发布；此前 0.8.0 / 0.9.0 / 0.9.1 为 `-incubating` 版本，属孵化期发布，其 DISCLAIMER 写明孵化状态 "does indicate that the project has yet to be fully endorsed by the ASF" [来源: https://github.com/apache/fluss/blob/v0.9.1-incubating/DISCLAIMER]。
- **发布节奏**：GitHub Release 对象只有 4 个：0.8.0-incubating（2025-11-06）、0.9.0-incubating（2026-03-02）、0.9.1-incubating（2026-05-04）、1.0.0（2026-09-22）[来源: https://api.github.com/repos/apache/fluss/releases]；孵化器页面记录的发布日期为 0.8.0 2025-11-10、0.9.0 2026-03-04、0.9.1 2026-05-04，并注明 0.8.0 是 "the first apache incubating release" [来源: https://incubator.apache.org/projects/fluss.html]。更早的 `v0.5.0`、`v0.6.0`、`v0.7.0` 只有标签（提交日期分别为 2024-11-27、2025-03-10、2025-06-16），不是 ASF 发布 [来源: https://api.github.com/repos/apache/fluss/tags]。推断：次版本间隔约 4–7 个月（0.8.0→0.9.0 约 3.8 个月，0.9.0→1.0.0 约 6.7 个月）。
- **master**：`main` 为 `1.1-SNAPSHOT`（2026-10-19 提交 `dbd6dd7`），且已把 `paimon.version` 升到 **2.1.0** [来源: https://github.com/apache/fluss/blob/dbd6dd731331567cbfb28ef2db004a0e25c76407/pom.xml]；升级提交为 2026-10-13 的 "[server] Upgrade paimon 2.1 to activate concurrent SST lookup for historical partition (#4620)" [来源: https://github.com/apache/fluss/commit/0912b1a723407bf33abd2da2da9a4a3970648200]。Paimon 2.1.0 已于 2026-10-12 发布 [来源: https://github.com/apache/paimon/releases/tag/release-2.1.0]。推断：下一个 Fluss 版本大概率绑定 Paimon 2.1。
- **活跃度（2026-10-19 抓取）**：2,214 star、657 fork；GitHub contributors 接口列出 171 个非匿名贡献者；默认分支近 52 周 1,451 次提交、近 13 周 397 次；2026-07-19 至 2026-10-19 合并 PR 402 个；open issue 约 670、open PR 约 400 [来源: https://api.github.com/repos/apache/fluss ; https://api.github.com/repos/apache/fluss/stats/participation ; https://api.github.com/search/issues?q=repo:apache/fluss+is:pr+is:merged+merged:2026-07-19..2026-10-19 ; https://api.github.com/search/issues?q=repo:apache/fluss+is:issue+is:open ; https://api.github.com/search/issues?q=repo:apache/fluss+is:pr+is:open]。
- **许可证**：Apache License 2.0；NOTICE 署名 "Copyright 2025-2026 The Apache Software Foundation" [来源: https://github.com/apache/fluss/blob/v1.0.0/LICENSE ; https://github.com/apache/fluss/blob/v1.0.0/NOTICE]。
- **生产用户**：没有找到用户公司自己发布（公司域名/公司仓库）的一手声明。Fluss 网站 User Stories 页列出 Alibaba、iQIYI、Fresha（"runs Fluss on Amazon EKS"）、rednote、Ant Group、JD、Cisco Webex，并称托管服务有阿里云与 Ververica；页面为第三人称编辑文字、无具体规模数字（项目方站点表述）[来源: https://fluss.apache.org/user-stories/]。Fluss 博客刊载的 rednote 案例（作者署名 "Head of Kafka & Fluss at Rednote"）称 "Fluss is now running in Rednote's core production indexing path"，单表约每天 10 亿条、10 TB，但未写所用版本（案例观点）[来源: https://fluss.apache.org/blog/rednote-kafka-to-fluss-real-time-indexing/]。

## 2. 架构与核心能力

### 2.1 进程与存储

| 组成 | 一手描述 | 来源 |
| --- | --- | --- |
| CoordinatorServer | 维护元数据、分配 tablet、处理权限；负责扩缩容再平衡、故障切换、建删表 | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/concepts/architecture.mdx] |
| Coordinator HA | 1.0 新增：基于 ZooKeeper 选主，多个 Coordinator 一主多备 | [来源: https://github.com/apache/fluss/releases/tag/v1.0.0 ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-distributed-cluster.md] |
| TabletServer | 数据存储与读写服务，含 LogStore 与 KvStore；同一 bucket 的 LogTablet 与 KvTablet 放在同一 TabletServer | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/concepts/architecture.mdx] |
| LogStore | 只追加，类似 binlog；用于低延迟流读，并作 KvStore 的 WAL；LogTablet 按副本因子多副本 | 同上 |
| KvStore | 支持更新/删除与点查并生成完整 changelog；文档原文 "Currently, replication is not supported for KvTablets"，靠周期快照上传远端 + 回放 changelog 恢复（`kv.snapshot.interval` 默认 10min）；1.0 新增可选 standby 副本（`table.kv.standby-replica.enabled`，默认不开） | 同上；[来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/tiered-storage/remote-storage.md ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/engine-flink/options.md] |
| ZooKeeper | 用于集群协调、元数据存储与配置；文档称将来以 KvStore + Raft 替换 | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/concepts/architecture.mdx] |
| Remote Storage | Log 段异步下沉（默认每 1 分钟一次）与 KV 快照持久化；客户端可直接批量读远端的 log/kv 文件 | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/tiered-storage/remote-storage.md] |

### 2.2 表类型与数据格式

- **Log Table**：只追加，文档类比为 "the typical use cases of Apache Kafka" [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/table-types/log-table.md]。
- **PrimaryKey Table**：支持部分列更新、四种 merge engine（默认 LastRow、FirstRow、Versioned、Aggregation）、按主键 lookup 与前缀 lookup，`$changelog` 虚表输出 insert / update_before / update_after / delete [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/table-types/pk-table.md]。
- **日志格式**：默认 **ARROW**（列式，支持列裁剪）；另有行式 COMPACTED 用于 log 与 KV [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/data-formats.md]。1.0 新增带列统计的 V1 log batch 与服务端谓词裁剪 [来源: https://github.com/apache/fluss/releases/tag/v1.0.0]。
- **保留**：`table.log.ttl` 默认 7 天，`table.kv.ttl` 默认不设 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/engine-flink/options.md]。

### 2.3 Streaming Lakehouse 与 tiering service

- Fluss 集群保留"天级"热数据（亚秒新鲜度）、湖保留"月级"历史数据（分钟级新鲜度）；tiering service 把 Arrow 格式的实时数据压实写入湖格式（Parquet 等）[来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/overview.mdx]。
- **tiering service 是一个独立 Flink 作业**（`fluss-flink-tiering` jar），由 TieringSource / TieringCommitter / No-Op Sink 组成，先提交湖、再更新 Fluss coordinator 中的位点（两阶段），文档称在 Fluss 与湖之间保持 exactly-once，可多实例并行，"stateless" [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/tiering-service.md]。
- 表级开关 `table.datalake.enabled`（默认 false）与 `table.datalake.freshness`（**默认 3min**）；`table.datalake.auto-compaction`、`table.datalake.auto-expire-snapshot` 默认均为 false [来源: 同上]。
- 支持的湖格式：**Paimon、Iceberg、Hudi（1.0 新增）、Lance** [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/overview.mdx ; https://github.com/apache/fluss/releases/tag/v1.0.0]。
- 湖侧表 schema 只能经 Fluss 演进；文档警告不要用外部引擎改湖表 schema，否则 tiering 作业循环重启，且 Fluss 会一直保留未下沉的 log [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-formats/paimon.md]。

### 2.4 Union Read

- 对开了湖分层的表，直接查询表名即合并读：批模式合并 Fluss 与湖的行；流模式先读最新湖快照，再从对齐的 log offset 切到 Fluss；主键表按主键去重、Fluss 版本优先，log 表直接拼接；`表名$lake` 只读湖 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/union-read.md]。
- **Fluss 文档的引擎支持表**：Flink ✅、Spark ✅（1.0 新增 Spark 批 Union Read）、Trino ❌、StarRocks ❌（二者只能用原生湖连接器读湖表）[来源: 同上；https://github.com/apache/fluss/releases/tag/v1.0.0]。Doris 不在该表中，Doris 自己的实现见第 3 节。
- Iceberg 的主键表 Union Read 尚未支持（issue #3048，open）[来源: https://github.com/apache/fluss/issues/3048]。

### 2.5 其它 1.0 能力

- **Fluss Gateway**（Rust 无状态 REST 服务）为 **preview**：支持元数据、DDL 与写入，不支持读取记录与 HTTP 调用方认证 [来源: https://github.com/apache/fluss/releases/tag/v1.0.0 ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/gateway/index.md]。
- **Delta Join** 依赖 **Flink 2.1+** [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/engine-flink/delta-joins.mdx]，项目当前 Flink 1.20 用不上。
- **exactly-once 写入**：文档只对 aggregation merge engine 表明示 Flink 写入 exactly-once（依赖 undo recovery），直接用客户端 API 时不提供 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/merge-engines/aggregation.md]。log 表与默认主键表经 Flink 写入在 failover 后的语义，未找到一手文档的明确声明。

## 3. 生态兼容矩阵

项目当前版本取自本仓库：`colvenna-jobs/pom.xml`（`maven.compiler.release=11`、Paimon 2.0.0、Flink 1.20.4、Flink CDC 3.5.0、MySQL CDC 3.7.0-1.20、Kafka connector 3.4.0-1.20）、`deploy/images/flink-base/Dockerfile`（`flink:1.20.4-scala_2.12-java11`）、`colvenna-operator/pom.xml`（Flink Kubernetes Operator 1.16.1）、`tests/fixtures/kafka/colvenna-dev-kafka/values.yaml`（Strimzi Kafka 4.3.1）、`deploy/scripts/open-layer/install-polaris.sh`（Polaris 1.8.0）、`tests/fixtures/trino/install-trino.sh`（Trino 484）、[M0 能力矩阵](version-capability.md)（Doris 4.1.4）。

| 组件 | 项目当前版本 | Fluss 1.0.0 支持情况 | 结论 | 来源 |
| --- | --- | --- | --- | --- |
| Flink | 1.20.4（Java 11 镜像），Flink Kubernetes Operator 1.16.1 | 发布 `fluss-flink-1.18/1.19/1.20/2.2/2.3` 五个 connector；`fluss-flink-1.20` 按 Flink **1.20.3** 编译 | **版本线匹配**；1.20.3→1.20.4 补丁差异未验证 | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/engine-flink/getting-started.md ; https://github.com/apache/fluss/blob/v1.0.0/fluss-flink/fluss-flink-1.20/pom.xml] |
| Java（客户端/connector） | colvenna-jobs `--release 11`，Flink 镜像 Java 11 | 根 POM `target.java.version=11`；本次下载的 `fluss-flink-1.20`、`fluss-client`、`fluss-lake-paimon`、`fluss-flink-tiering` 1.0.0 jar 中 Fluss 自有类均为 class 版本 55（Java 11），`org.apache.fluss.shaded.*` 下打包的第三方类为 50–52（更低），MANIFEST `Build-Jdk: 11.0.28` | **不需要 Java 17**（字节码已核对）；运行时行为未测 | [来源: https://github.com/apache/fluss/blob/v1.0.0/pom.xml ; https://repo1.maven.org/maven2/org/apache/fluss/fluss-flink-1.20/1.0.0/fluss-flink-1.20-1.0.0.jar] |
| Java（Fluss 服务端） | 不适用（独立部署） | 文档要求 Java 11+，"Java 17 or higher is strongly recommended"；官方镜像基于 `eclipse-temurin:17-jre` | 服务端用官方镜像即可，不影响 colvenna-jobs。注意文档自述 0.9.1 发布二进制曾无法在 Java 11 上运行，故每个版本的二进制都要实测 | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-distributed-cluster.md ; https://github.com/apache/fluss/blob/v1.0.0/docker/fluss/Dockerfile ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-catalogs/glue.md] |
| Paimon（tiering） | **2.0.0** | 根 POM `paimon.version=2.0.0`（release notes："Upgrade Paimon to 2.0.0 (#4093)"）；文档矩阵：Tiering "Paimon 1.4, 2.0 (tested and verified to work)"，Union Read "Paimon 1.1–1.4, 2.0"，"Java 11 or later; Paimon 2.0 adds no higher requirement" | **一手文档声明与 2.0.0 兼容**；`paimon-bundle` 为 provided，需自行放入 Flink lib | [来源: https://github.com/apache/fluss/blob/v1.0.0/pom.xml ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-formats/paimon.md ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/pom.xml] |
| Paimon 目录 | colvenna-catalog（Paimon REST，bearer `token.provider=bear` + `token`） | 文档示例只有 `metastore: filesystem` 与 `hive`；源码 `PaimonLakeCatalog` 把 `datalake.paimon.*` 去前缀后直接交给 Paimon `CatalogFactory.createCatalog` | 推断：配置层面可传 `metastore=rest`；**未找到文档或测试证明 REST/bearer 可用**；且有 token 外泄风险（见 5.3） | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-streaming-lakehouse.md ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/fluss/lake/paimon/PaimonLakeCatalog.java ; https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/rest/bear.md] |
| tiering 写出的 Paimon 表 | ODS 主键表：deduplicate + `sequence.field` + DV + `changelog-producer=input` + fixed bucket | 文档："stored as a standard Paimon table, you can use any engine that supports Paimon to read it"；1.0 新建表为 clean schema（无 `__bucket/__offset/__timestamp`）；源码：主键表设 `changelog-producer=input`，有 bucket key 时 fixed bucket=Fluss bucket 数，否则 `bucket=-1`，并设 `partition.legacy-name=false`；`paimon.` 前缀的表属性透传（可开 DV）；快照 `commit-user=__fluss_lake_tiering` 并带 `fluss-offsets` 属性 | 文档声明是普通 Paimon 表；表选项与项目约定不同（无 `sequence.field`、`write-only` 由 auto-compaction 决定） | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-formats/paimon.md ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/fluss/lake/paimon/utils/PaimonConversions.java ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/fluss/lake/paimon/tiering/PaimonLakeWriter.java] |
| Paimon→Iceberg 兼容发布（M4） | `metadata.iceberg.storage=table-location`，Polaris 1.8.0 EXTERNAL 目录，Iceberg v2 | Fluss 提交时把 `commit.callbacks` 动态选项改为自己的回调；Paimon 2.0.0 的 `IcebergCommitCallback` 由 `metadata.iceberg.storage` 单独决定加入，不经 `commit.callbacks` | 推断：在 Fluss 表上设 `paimon.metadata.iceberg.storage=table-location` 应仍会写 Iceberg 元数据；**未验证**。主键表的 v2 视图依赖全量压缩，而 tiering 默认 `write-only` | [来源: https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/fluss/lake/paimon/tiering/PaimonLakeCommitter.java ; https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-core/src/main/java/org/apache/paimon/AbstractFileStore.java ; [M4 开放层调研](iceberg-open-layer.md)] |
| Iceberg（直接 tiering） | Iceberg v2，读端 Spark + Iceberg 1.10.2、Trino 484 | Fluss 绑定 Iceberg **1.10.1**；支持 hive/hadoop/rest/glue/nessie/jdbc 目录，有 Polaris、Lakekeeper、Glue 指南；主键表映射为 MOR；Polaris 指南中 Fluss 经 REST API 建表与提交快照 | 推断：项目的 Polaris 是只接受通知的 EXTERNAL 目录（拒绝客户端提交），不能直接作 Fluss 的 Iceberg 目标；主键表 Union Read 不支持（#3048） | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-formats/iceberg.md ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-catalogs/polaris.md ; [M4 开放层调研](iceberg-open-layer.md)] |
| Hudi / Lance | 不使用 | Hudi 1.1.0（1.0 新增）；Lance 0.33.0 | 与本项目无关 | [来源: https://github.com/apache/fluss/blob/v1.0.0/pom.xml ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-lance/pom.xml] |
| Flink CDC → Fluss | MySQL SQL connector 3.7.0-1.20，PG/Mongo 3.5.0；Paimon CDC action；YAML Pipeline 已被 #113 否决（绕过准入） | Flink CDC 3.7.0 的 Fluss pipeline connector 可作 source 与 sink，依赖 Fluss **1.0.0**、Flink 1.20（3.5.0 依赖 Fluss 0.7.0，3.6.0 依赖 0.9.0-incubating）；文档未声明投递语义；另可用 Flink SQL `INSERT INTO` Fluss 表（Fluss connector 支持流式写） | YAML 路径与 #113 的否决理由相同（自动建表/加列）；SQL 路径可行性未验证，exactly-once 只对 aggregation 表有明示 | [来源: https://github.com/apache/flink-cdc/blob/release-3.7.0/flink-cdc-connect/flink-cdc-pipeline-connectors/flink-cdc-pipeline-connector-fluss/pom.xml ; https://github.com/apache/flink-cdc/blob/release-3.7.0/docs/content/docs/connectors/pipeline-connectors/fluss.md ; https://github.com/apache/flink-cdc/blob/release-3.7.0/pom.xml ; [Flink CDC 3.7 评估](../history/m3/flink-cdc-3.7.md)] |
| Doris 读 Fluss 本身 | **4.1.4**（自构建 Paimon 2.0.0 读取器） | Doris 4.1.4 与 4.1.5-release 源码树没有 `fluss-scanner` / `fe-connector-fluss`；4.x 版本化文档目录没有 Fluss catalog。master 文档：Fluss Catalog "experimental and has been available since version **5.0.0**"，只读，湖分层表只支持 Paimon，版本对应 Doris 5.0 ↔ Fluss 1.0；截至 2026-10-19 无 5.x 发布 | **4.1.4 读不了 Fluss 热数据，也做不了 Union Read** | [来源: https://github.com/apache/doris/tree/4.1.4/fe/be-java-extensions ; https://github.com/apache/doris-website/tree/fc42f75f28a60e348a61a8c08f63ed48d4db6be6/versioned_docs/version-4.x/lakehouse/catalogs ; https://github.com/apache/doris-website/blob/0afdee0c1d6a5c81e9cd3080b642c85d299ce435/docs/lakehouse/catalogs/fluss-catalog.md ; https://github.com/apache/doris/releases] |
| Doris 读 tiering 后的 Paimon 表 | 4.1.4 经 colvenna-catalog REST 读 Paimon 2.0.0 表（M1/M2 已验收） | Fluss 文档称是普通 Paimon 表 | 推断：可沿用现有 Doris Paimon catalog 读取；新鲜度受 `table.datalake.freshness` 限制；`partition.legacy-name=false`、Fluss 写出的 DV 与 L0 文件的读取正确性**未验证** | 同上两行 |
| StarRocks（M0 备选，不在默认栈） | 4.1.3 | main 有实验性 Fluss catalog（只支持开了湖分层且湖为 Paimon 的表，含 Union Read 示例）；4.1.3 标签无该文档；connector 代码首次提交于 2026-08-03（#72424） | 已发布的 4.1.3 不支持 | [来源: https://github.com/StarRocks/starrocks/blob/249fb4f7df24f11fb428ed4cfcea345e390a1757/docs/en/data_source/catalog/fluss_catalog.md ; https://github.com/StarRocks/starrocks/commit/a2ce0882bea27cf28e75de34a5435e0d700f9e45 ; https://github.com/StarRocks/starrocks/tree/4.1.3/docs/en/data_source/catalog] |
| Trino（用户已有） | 484，仅经 Iceberg 连接器读开放层 | Fluss 文档：Trino 无 Union Read，只能原生湖连接器读湖表；原生只读 Trino connector 仍是 open issue #4197；trinodb/trino 代码搜索 "fluss" 无结果 | 只能读 tiering 后的湖表，项目仍走 Iceberg | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/union-read.md ; https://github.com/apache/fluss/issues/4197 ; https://api.github.com/search/code?q=fluss+repo:trinodb/trino（2026-10-19 total_count 0）] |
| Spark（可选） | 3.5.8，首版不部署 | `fluss-spark-3.4/3.5`，按 Spark 3.5.7 编译；1.0 新增批 Union Read 与 Structured Streaming 写 | 可用但项目不部署 Spark | [来源: https://github.com/apache/fluss/blob/v1.0.0/pom.xml ; https://github.com/apache/fluss/releases/tag/v1.0.0] |
| Kafka | 外部 BYO（MSK 等）；dev 为 Strimzi Kafka 4.3.1 | 有 `fluss-kafka` 模块，但配置文档写明 "Kafka protocol compatibility is still in development"（`kafka.enabled` 默认 false） | 现有 Kafka 生产端不能直接改写 Fluss | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/configuration.md] |
| Kubernetes | EKS；Operator 管 Flink；自有 Helm chart | 官方 Helm chart 1.0.0（Coordinator/Tablet 两个 StatefulSet、headless ClusterIP Service），**不含 ZooKeeper**，需外部 ZK（最低 3.6、推荐 3.8）；无 Operator（FIP-41 "Fluss Kubernetes Operator" 已 accepted、未定目标版本） | 可部署，但多一套有状态组件 | [来源: https://github.com/apache/fluss/blob/v1.0.0/helm/Chart.yaml ; https://github.com/apache/fluss/blob/v1.0.0/helm/templates/svc-coordinator.yaml ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-with-helm.md ; https://cwiki.apache.org/confluence/display/FLUSS/FLUSS+Improvement+Proposals] |
| S3 | 真实 S3 + IRSA；colvenna-catalog 按表签发 STS | `fluss-fs-s3`（Hadoop S3A 3.4.3 + AWS SDK v2）；支持 IRSA 默认凭证链，此时**必须**设 `s3.assumed.role.arn`，服务端用 AssumeRole 给客户端签临时凭证 | 支持；但客户端凭证是集群级、单一角色（见 5.3） | [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/tiered-storage/filesystems/s3.md] |

## 4. 与 Kafka、Paimon 流读的对比

### 4.1 能力对照

| 维度 | Fluss 1.0.0（文档/源码） | Paimon 2.0.0 在本项目中的用法（仓库文档与实测） |
| --- | --- | --- |
| 可见性下限 | 写入后亚秒级可流读；湖侧按 `table.datalake.freshness`（默认 3min）[来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/overview.mdx] | 下限是 Flink checkpoint 间隔，HLD 1.1 明确不承诺亚秒级 [来源: [HLD 1.1](../design/hld-realtime-lakehouse.md#11-非目标)]；M1 实测 ODS changelog 可流读 p95/p99 0.50/0.53 分钟，源库→ODS（DV 批读）p95 1.29 分钟 [来源: [README M1 基线](../../README.md#latency稳态端到端分位数)] |
| 主键更新与 changelog | KV 写入时直接生成完整 `-U/+U` changelog [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/concepts/architecture.mdx] | `input` 依赖上游前镜像并额外写 changelog 文件；`lookup` 在 compaction 时查旧值，增加提交延迟与本地缓存 [来源: [Paimon 调研第 5 节](apache-paimon.md#5-changelog-producer)] |
| 点查 / 维表 | 服务端 RocksDB lookup、前缀 lookup，Flink lookup join [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/table-types/pk-table.md] | Paimon Flink lookup join（异步、动态分区、Query Service）[来源: [HLD 9](../design/hld-realtime-lakehouse.md#9-数仓分层与流批一体)] |
| 部分列更新 / 聚合 | partial update、aggregation（含 RoaringBitmap）、first-row、versioned | partial-update、aggregation、first-row、deduplicate [来源: [Paimon 调研第 4 节](apache-paimon.md#4-merge-engine)] |
| 消除 Flink 大状态 | Delta Join 需 Flink 2.1+ [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/engine-flink/delta-joins.mdx] | 不适用；项目在 Flink 1.20 |
| 历史数据 | log 按 `table.log.ttl`（默认 7 天）保留，KV 保留每个主键的当前值（`table.kv.ttl` 默认不设）；更早的 log 历史依赖分层到湖 | Paimon 即权威副本 |
| 读端生态 | Flink、Spark；Doris/StarRocks 仅未发布版本（实验性）；Trino 无 | Doris 4.1.4 已验收；Trino 经 Iceberg 开放层 |
| 额外组件 | Coordinator、TabletServer（本地盘）、ZooKeeper、tiering Flink 作业、远端存储 | 无（S3 + colvenna-catalog 已有） |

### 4.2 与 Kafka

- Fluss 把 Log Table 定位为 Kafka 式高吞吐日志，但存储为列式 Arrow 并支持服务端列裁剪与谓词裁剪 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/table-design/data-formats.md ; https://github.com/apache/fluss/releases/tag/v1.0.0]。rednote 案例称其迁移动机是 Kafka 全行读放大、冷数据回放经过 broker、历史扫描与实时流量争抢（案例观点）[来源: https://fluss.apache.org/blog/rednote-kafka-to-fluss-real-time-indexing/]。
- 对本项目：Kafka 是用户自带的外部依赖，平台不创建生产 Kafka [来源: [HLD 3.2](../design/hld-realtime-lakehouse.md#32-分层职责)]；SDK/Agent 按 Kafka 协议投递事件。Fluss 的 Kafka 协议兼容仍在开发 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/configuration.md]，HTTP 写入的 Gateway 是 preview 且无调用方认证。推断：Fluss 现在不能替代 M3 的 Kafka 事件源入口，最多作为 Kafka 之后、Paimon 之前的又一层，即在 Kafka→Flink→Paimon 链路上**再加**一个系统，而不是减少一个。
- Fluss 2026 路线图把"Kafka compatibility to allow existing log collectors to write to Fluss"列为未完成项 [来源: https://github.com/apache/fluss/discussions/2342]。

### 4.3 Fluss 能解决的 Paimon 痛点与代价

能解决（文档层面）：

1. **亚秒级流读新鲜度**：不再受 checkpoint 提交间隔限制。
2. **主键点查**：服务端 KV，免去 Flink 侧维表缓存。
3. **changelog 生成成本**：主键表 changelog 在写入时由 KV 产生，tiering 到 Paimon 时以 `changelog-producer=input` 写出，Paimon 下游可继续流读 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/datalake-formats/paimon.md]。
4. **小文件与新鲜度的矛盾**：Fluss 文档的论点是湖格式要低延迟就得频繁提交、产生小文件 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/streaming-lakehouse/overview.mdx]；tiering 可以拉长 freshness，用 Fluss 承担实时读。

代价：

- 推断：Paimon 由 tiering 作业而不是由 CDC 作业直接写，湖侧新鲜度变成 `table.datalake.freshness`（默认 3min）。对 Doris 4.1.4 这类只能读湖的引擎，新鲜度**不会更好**，除非 freshness 调到与现有 checkpoint 相当。
- 多一套有状态系统：ZooKeeper（3 节点）、多副本 TabletServer（RocksDB 与 log 需要本地盘）、Coordinator（HA 需多实例）、常驻 tiering 作业；1.0 升级说明要求按"湖读取 connector → tiering service → Fluss 集群"的顺序滚动 [来源: https://github.com/apache/fluss/releases/tag/v1.0.0]。
- 推断：多一份个人数据副本，Fluss 本地 log、KV、远端 log 段与 KV 快照都要纳入 HLD 12.4 的删除清单。
- Paimon 表的建表与 schema 由 Fluss 掌握（2.3、5.3）。

## 5. 部署、运维与安全

### 5.1 Kubernetes 部署

- Helm chart：默认 Coordinator 1 副本、Tablet 3 副本、`default.replication.factor: 3`；`tablet.storage.enabled` 与 `coordinator.storage.enabled` **默认 false**；Tablet 的 StatefulSet 在未启用时把数据卷设为 `emptyDir`，启用后才建 `volumeClaimTemplates`（ReadWriteOnce）[来源: https://github.com/apache/fluss/blob/v1.0.0/helm/values.yaml ; https://github.com/apache/fluss/blob/v1.0.0/helm/templates/sts-tablet.yaml]。
- Helm 仓库地址 `https://downloads.apache.org/fluss/helm-chart`；chart 不带 ZooKeeper，文档示例用 Bitnami chart 部署 ZK [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-with-helm.md]。
- 支持以 config provider 从挂载的 Secret 或环境变量解析口令（`${directory:...}`、`${env:...}`），1.0 新增并接入 Helm [来源: https://github.com/apache/fluss/blob/v1.0.0/helm/values.yaml ; https://github.com/apache/fluss/releases/tag/v1.0.0]。
- Service 均为 headless ClusterIP（含 metrics Service），chart 默认不建 LoadBalancer [来源: https://github.com/apache/fluss/blob/v1.0.0/helm/templates/svc-coordinator.yaml ; https://github.com/apache/fluss/blob/v1.0.0/helm/templates/svc-metrics-tablet.yaml]。
- 推断：tiering service 是普通 Flink jar（Main-Class `FlussLakeTieringEntrypoint`），可用项目已有的 Flink Kubernetes Operator 以 `FlinkDeployment` 托管；未验证。
- ZooKeeper 依赖：路线图把 "ZooKeeper Removal" 与 "Zero Disks" 列为未完成 [来源: https://fluss.apache.org/roadmap/]；对应 issue #4014 open [来源: https://github.com/apache/fluss/issues/4014]。

### 5.2 认证与授权

- **默认不开认证也不开授权**："all clients can access the system without verification or restrictions" [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/security/overview.md]。
- 认证：每个 listener 选 `PLAINTEXT` 或 `SASL`，**SASL 只支持 PLAIN**（用户名口令）；可写自定义认证插件 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/security/authentication.md]。1.0 支持动态更新的多用户 `security.sasl.plain.credentials` [来源: 同上]。SCRAM、OAUTHBEARER 仍是 open issue（#3881、#3495）[来源: https://github.com/apache/fluss/issues/3881 ; https://github.com/apache/fluss/issues/3495]。
- **无 TLS**：Fluss RPC 源码（fluss-rpc / fluss-server / fluss-client 的 main 代码）中没有 `SslHandler`/`SslContext`；FIP-29 "(m)TLS Support" 为 accepted、未定目标版本；路线图 Security 项为 "(m)TLS for intra-cluster, ZooKeeper, and external clients" [来源: https://github.com/apache/fluss/tree/v1.0.0/fluss-rpc ; https://cwiki.apache.org/confluence/display/FLUSS/FLUSS+Improvement+Proposals ; https://fluss.apache.org/roadmap/]。推断：SASL/PLAIN 口令以明文在集群网络上传输。
- 授权：`authorizer.enabled` 默认 false；ACL 资源为 Cluster / Database / Table 三级，操作为 READ/WRITE/CREATE/DROP/ALTER/DESCRIBE/ALL；没有列级或行级 ACL [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/security/authorization.md]。
- **`GET_FILESYSTEM_SECURITY_TOKEN` 不做 ACL**：文档原文 "Currently no ACL is enforced for this protocol" [来源: 同上]；该 RPC 是集群级的、给出可访问远端存储上所有表文件的临时凭证，按表签发的需求是 open issue #752（"There is a big security risk on this"）[来源: https://github.com/apache/fluss/issues/752]。
- Gateway：1.0 preview 只有 `trust` 模式，"does not authenticate callers or terminate TLS"，文档要求放在带认证、终止 TLS 的入口之后 [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/gateway/index.md]。

### 5.3 与本项目安全约束的冲突点

本项目要求所有集群外可达的服务与资源都必须认证、禁止匿名；按表凭证是对象路径边界；密钥经 Secret 注入、不出现在 CR 中 [来源: [HLD 12.1](../design/hld-realtime-lakehouse.md#121-边界与威胁模型)]。

| 冲突 | 依据 | 影响 |
| --- | --- | --- |
| 默认 PLAINTEXT、授权关闭 | Fluss 安全文档 | 必须显式开 SASL（CLIENT 与 INTERNAL listener、ZK）与 authorizer，并对每个端口做未认证拒绝测试 |
| 无 TLS | 源码与 FIP-29 | 只能限定在集群内并用 NetworkPolicy 收窄；不得经任何 LB 暴露 |
| 远端存储临时凭证无 ACL、集群级 | Fluss 授权文档、#752 | 任一通过认证的客户端可取得 delegation 角色对整个 `remote.data.dir` 的凭证，弱于 colvenna-catalog 按表 STS 的边界 |
| 湖目录凭证可能随表属性下发（推断） | 源码：`LakeStorageUtils.generateDefaultTableLakeOptions` 把全部 `datalake.<format>.*` 服务端配置变成 `table.datalake.<format>.*`，`TableRegistration.toTableInfo` 在开了湖分层的表上合入属性；`MetadataManager.removeSensitiveTableOptions` 只剔除键名含 `password`、`secret`、`key` 的项；Doris 文档亦称 Fluss 返回表属性前只移除这三类 | 推断（源码阅读，未实测）：若按 Paimon bearer 文档在 `server.yaml` 写 `datalake.paimon.token`，键名不含上述三词，**会被下发给任何对该表有 DESCRIBE 权限的客户端**；`s3.session-token` 同理 |
| Fluss 自行建/改 Paimon 表 | `PaimonLakeCatalog.createTable/alterTable` 由 Coordinator 调用 | 推断：Fluss 的目录身份需要库级建表权限，自动建表与加列不经 colvenna-operator 准入，与 #113 否决 YAML Pipeline 的理由同类 |
| tiering 作业的目录与 S3 参数以命令行参数传入 | Fluss 部署文档的 `--datalake.paimon.*` 示例 | 推断：直接写进 `FlinkDeployment.job.args` 会让密钥出现在 CR 中；需另找 Secret 注入方式，未找到一手文档 |
| Gateway 无调用方认证 | Gateway 文档 | 不部署 Gateway |

[来源: https://github.com/apache/fluss/blob/v1.0.0/fluss-server/src/main/java/org/apache/fluss/server/utils/LakeStorageUtils.java ; https://github.com/apache/fluss/blob/v1.0.0/fluss-server/src/main/java/org/apache/fluss/server/zk/data/TableRegistration.java ; https://github.com/apache/fluss/blob/v1.0.0/fluss-server/src/main/java/org/apache/fluss/server/coordinator/MetadataManager.java ; https://github.com/apache/doris-website/blob/0afdee0c1d6a5c81e9cd3080b642c85d299ce435/docs/lakehouse/catalogs/fluss-catalog.md ; https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/fluss/lake/paimon/PaimonLakeCatalog.java ; https://github.com/apache/fluss/blob/v1.0.0/website/docs/install-deploy/deploying-streaming-lakehouse.md]

### 5.4 S3 远端存储

- 远端目录 `remote.data.dir: s3://...`；IRSA / 实例角色走默认凭证链时**必须**配 `s3.assumed.role.arn`，服务端用 AssumeRole 给 Flink/Spark 客户端签临时凭证；文档注明服务端自身访问与 AssumeRole 调用使用同一身份，"Further decoupling is planned" [来源: https://github.com/apache/fluss/blob/v1.0.0/website/docs/maintenance/tiered-storage/filesystems/s3.md]。
- 1.0 把 S3 凭证提供者迁到 AWS SDK v2，自定义 v1 provider 需迁移 [来源: https://github.com/apache/fluss/releases/tag/v1.0.0]。
- Doris 的 Fluss Catalog 文档称 BE 直接读 `remote.data.dir` 中的 KV 快照与归档 log，所以 BE 必须能访问该远端存储；读湖表（`tbl$lake` 与 Union Read）时，Fluss 返回的表属性已剔除键名含 key/secret/password 的项，Paimon 湖存储的凭证要在 Doris catalog 中用 `fluss.lake.paimon.*` 自行配置（排障条目原文 "Fluss does not hand out storage credentials"，指的是湖存储）[来源: https://github.com/apache/doris-website/blob/0afdee0c1d6a5c81e9cd3080b642c85d299ce435/docs/lakehouse/catalogs/fluss-catalog.md]。

## 6. 已知限制与风险

### 6.1 文档与源码可见的限制

- Union Read 只在 Flink、Spark；Trino、StarRocks 无（Fluss 文档）；Iceberg 主键表不支持 Union Read（#3048）。
- KvTablet 不复制，依赖远端快照 + changelog 恢复；standby 副本默认关闭。
- 湖表 schema 只能经 Fluss 改；外部改动会让 tiering 循环重启并暂停 log 清理（#4038 open）[来源: https://github.com/apache/fluss/issues/4038]。
- 只有 SASL/PLAIN、无 TLS、远端凭证 RPC 无 ACL（5.2）。
- Gateway 为 preview；Kafka 协议兼容在开发中；Delta Join 需 Flink 2.1+。
- 升级须遵守湖读取端 → tiering → 集群的顺序；1.0 新建湖表为 clean schema，旧读取端与旧 tiering "cannot handle clean tables safely" [来源: https://github.com/apache/fluss/releases/tag/v1.0.0]。

### 6.2 值得关注的未关闭 issue（2026-10-19）

| Issue | 内容 | 对本项目的意义 |
| --- | --- | --- |
| [#4566](https://github.com/apache/fluss/issues/4566)（1.0.0） | 所有 TabletServer 本地数据丢失后 KV 表可能无法恢复，即使 ZK 元数据、KV 快照与远端 WAL 都在 | EKS 上节点替换、`emptyDir` 默认值下直接相关 |
| [#4362](https://github.com/apache/fluss/issues/4362)（0.9.0） | ZK 会话过期后单 Coordinator 可能不再取得 leader，需重启 | ZK 依赖的运维风险 |
| [#4335](https://github.com/apache/fluss/issues/4335)（0.9.0，生产报告） | 开 DV 的 Paimon tiering 使 ZK 中湖快照元数据超过 `jute.maxbuffer`，Coordinator 与 ZK 反复断连 | Paimon DV + tiering 组合的稳定性风险 |
| [#4721](https://github.com/apache/fluss/issues/4721)、[#4711](https://github.com/apache/fluss/issues/4711) | 首次开启湖分层可绑定到非空湖表；重置 `datalake.enabled` 可不安全地重新绑定 | 若由 Operator 预建 Paimon 表再让 Fluss 绑定，语义仍在变 |
| [#752](https://github.com/apache/fluss/issues/752) | 远端存储 STS 仅集群级，需按表 | 与按表凭证边界冲突 |
| [#3793](https://github.com/apache/fluss/issues/3793)、[#3495](https://github.com/apache/fluss/issues/3495) | mTLS、SASL/OAUTHBEARER 未实现 | 认证能力上限 |
| [#4014](https://github.com/apache/fluss/issues/4014) | 移除 ZooKeeper、内置 Raft 元数据 | 未完成前须自运维 ZK |
| [#4197](https://github.com/apache/fluss/issues/4197) | 原生只读 Trino connector | Trino 读 Fluss 本身仍不可行 |

### 6.3 路线图与 FIP

- FIP 页：Accepted 未发布的有 FIP-29 (m)TLS、FIP-41 Kubernetes Operator、FIP-6 解耦 Flink Union Read 与 Paimon 等；Released 有 FIP-1 湖仓存储设计（0.7）、FIP-2 认证与授权（0.7）、FIP-13 主键表 rebalance（0.9）[来源: https://cwiki.apache.org/confluence/display/FLUSS/FLUSS+Improvement+Proposals]。
- 2026 路线图未完成项包括：Spark/Trino/StarRocks 原生 Union Read、Deletion Vectors 加速 Union Read、在已有湖表上定义 Fluss 表、ZooKeeper 移除、Zero Disks（直写 S3）、Coordinator 多 AZ HA、(m)TLS [来源: https://github.com/apache/fluss/discussions/2342 ; https://fluss.apache.org/roadmap/]。路线图自称 "not a strict delivery commitment"。

## 7. 对 Colvenna Lake 的结论与建议

### 7.1 现在能不能引入

**能做隔离 PoC，不能直接接入现有生产形态的路径。** 版本层面的关键事实都已由一手来源证实：Flink 1.20 connector 存在、Java 11 字节码、Paimon 2.0.0 依赖并经官方测试、S3 + IRSA 有文档、Helm chart 可用。真正的阻断在版本之外：Doris 4.1.4 读不到 Fluss、colvenna-catalog REST 接入无文档、安全边界达不到项目要求。

### 7.2 该不该引入

**当前不该。** 理由按权重：

1. **没有需求缺口。** HLD 把亚秒级端到端延迟列为非目标；M1 稳态延迟表中各路径的 p95/p99 均判定通过（README M1 基线；另有默认 Doris 元数据缓存须设 `ttl=0` 的已知项，与 Fluss 无关）。Fluss 的核心收益（亚秒流读、服务端点查、Delta Join）对应的需求在 HLD 与 R1–R6 中都不存在；Delta Join 还要求 Flink 2.1+。
2. **主查询引擎拿不到收益。** Doris 4.1.4 只能读 tiering 后的 Paimon 表，湖侧新鲜度由 `table.datalake.freshness`（默认 3min）决定。推断：按默认值不优于现在 CDC 直接写 Paimon 的路径；调低 freshness 意味着更频繁的湖提交，能否比现在更好要靠 7.3 的对照量化；Doris 的 Fluss Catalog 在未发布的 5.0 中、标为实验性。
3. **安全边界退化。** 无 TLS、只有 SASL/PLAIN，低于 HLD 12.1 暴露面清单中集群内服务"认证 + TLS"的做法；远端存储凭证集群级且不做 ACL，弱于按表凭证；推断（源码阅读）：湖目录 token 可能随表属性下发，与密钥只经 Secret 注入的原则冲突。
4. **治理准入可能被绕过。** Fluss Coordinator 自行在 Paimon 目录建表、改表（源码），湖表 schema 只能经 Fluss 演进（文档）。推断：这与 colvenna-operator 的准入、分类和读端发布机制冲突（与 #113 否决 YAML Pipeline 同理），由 7.3 关口 4 实测。
5. **运维面扩大。** ZooKeeper、带本地盘的 TabletServer、Coordinator HA、tiering 作业，无 K8s Operator；1.0.0 仍有 #4566、#4362、#4335 这类恢复与稳定性问题。项目自运维已是 HLD 风险表中的一项。

### 7.3 若要做一次最小验证（可选，非推荐路线）

前提：只用合成、非敏感数据，放在独立命名空间与独立 S3 前缀，不进入任何已有 Doris 读入口或 Iceberg 开放层。

- **切入点**：一张合成主键维表（或一张 Kafka 事件 log 表）。由 Flink SQL 写入 Fluss；tiering 写到 colvenna-catalog 中**单独的库**；对照两条读路径：Flink lookup join / 流读 Fluss（亚秒），以及 Doris 4.1.4 经现有 Paimon REST catalog 读 tiering 后的表（freshness 设为 30s–1min）。量化"新鲜度提升 vs 新增组件成本"，与 M1 基线同口径对比。
- **必须先通过的关口**（任何一项失败即停止）：
  1. **版本对齐**：Fluss 1.0.0 + `fluss-flink-1.20`（按 1.20.3 编译）在 Flink 1.20.4 Java 11 镜像上运行；核对 `fluss-lake-paimon` 要求的 `paimon-bundle-2.0.0` 与镜像中已有的 `paimon-flink-1.20-2.0.0.jar` 是否重复或冲突（`fluss-lake-paimon` 自带的 `org.apache.paimon.arrow.converter.Arrow2PaimonVectorConverter` 与 Paimon 2.0.0 `paimon-arrow` 中的类同一全限定名，实际生效哪一份取决于类加载顺序 [来源: https://github.com/apache/fluss/blob/v1.0.0/fluss-lake/fluss-lake-paimon/src/main/java/org/apache/paimon/arrow/converter/Arrow2PaimonVectorConverter.java ; https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-arrow/src/main/java/org/apache/paimon/arrow/converter/Arrow2PaimonVectorConverter.java]）。
  2. **Paimon 2.0 兼容**：tiering 写出的主键表与 append 表能被 Flink（Paimon 2.0.0）和 Doris 4.1.4 自构建读取器正确读取，覆盖 insert/update/delete、`partition.legacy-name=false`、可选的 DV；对账口径同 M1。
  3. **colvenna-catalog REST + bearer**：Coordinator、TabletServer 与 tiering 作业以 `metastore=rest`、`token.provider=bear` 接入；用一个只有 DESCRIBE 权限的低权限 Fluss 用户取表信息，**确认 `table.datalake.paimon.token` 不出现在返回的属性中**；出现即阻断。同时确认 `data-token.enabled` 下按表凭证是否被 Fluss 侧正确使用。
  4. **治理**：由 Operator 预先建好空的 Paimon 表、再让 Fluss 绑定，确认 Fluss 不自行建表，且 #4721/#4711 描述的绑定语义不影响；Fluss 目录身份只授予该库所需权限。
  5. **认证与暴露面**：CLIENT 与 INTERNAL listener 都开 SASL、开 authorizer、ZK 开 SASL；对 Coordinator、Tablet、metrics、ZK 各端口逐一做未认证请求的拒绝测试；所有 Service 保持 ClusterIP，不部署 Gateway。
  6. **S3 远端存储**：IRSA + `s3.assumed.role.arn`，delegation 角色只授 `remote.data.dir` 前缀的只读权限；评估任一认证用户都能取得该凭证的影响面（#752）。
  7. **故障**：TabletServer 用 PVC；注入节点替换、全部本地盘丢失（#4566）、ZK 会话过期（#4362），核对 RPO/RTO 与数据一致性。
  8. **删除与保留**：把 Fluss 本地 log、KV、远端 log 段、KV 快照加入 HLD 12.4 的副本清单，验证主体删除在 Fluss 与湖两侧都生效。
  9. **（如需开放层）Iceberg 共存**：在 Fluss 表上设 `paimon.metadata.iceberg.storage=table-location`，核对每次 tiering 提交后 Iceberg 元数据的生成，以及主键表依赖全量压缩时与 tiering 写入的并发提交。

### 7.4 触发重新评估的条件

满足第 1 条，且第 2–4 条中至少两条成立时，再立项评估：

1. 出现经量化、Paimon + Doris 无法满足的需求：亚秒级流读新鲜度、高 QPS 主键点查（特征服务、实时画像），或 Flink 状态过大需要外置（同时项目计划升级到 Flink 2.1+ 以使用 Delta Join）。
2. Doris 发布 5.0 且 Fluss Catalog 不再标为实验性，并证明能经 Paimon REST catalog（bearer、按表凭证）完成 Union Read。
3. Fluss 发布 (m)TLS（FIP-29）、按表的远端存储凭证或对 `GET_FILESYSTEM_SECURITY_TOKEN` 的 ACL（#752），并确认湖目录 token 不随表属性下发。
4. Fluss 移除 ZooKeeper（#4014）或发布 Kubernetes Operator（FIP-41），且 #4566 这类恢复问题在正式版中修复。

另外：Fluss `main` 已改依赖 Paimon 2.1.0。项目处于开发阶段、升级不要求兼容（见 [M2 控制平面运维](../history/m2/control-plane-operations.md#升级与回退的结果)），但若后续跟进 Fluss 1.1，需同时评估 Paimon 2.1 与 Doris 读取器的兼容性。

### 7.5 已证实与推断的区分

| 陈述 | 状态 | 依据 |
| --- | --- | --- |
| Fluss 已于 2026-07-16 毕业为 ASF 顶级项目，1.0.0 为 PMC 投票的正式发布 | 一手来源已证实 | 孵化器页面、ASF 项目目录、发布投票与公告邮件 |
| Fluss 1.0.0 提供 Flink 1.20 connector | 已证实 | 文档 + POM |
| Fluss connector/client 为 Java 11 字节码 | 已证实 | Maven Central jar 的 class 版本与 MANIFEST |
| Fluss 1.0.0 依赖 Paimon 2.0.0，tiering 对 Paimon 2.0 "tested and verified" | 已证实（官方声明，非本项目实测） | POM + 文档 |
| tiering 写出的是普通 Paimon 表 | 文档声明 | Fluss Paimon 文档 |
| Doris 4.1.4 不能读 Fluss 本身；Doris Fluss Catalog 自 5.0.0 起、实验性 | 已证实 | Doris 4.1.4 源码树、4.x 与 master 文档、Doris 发布列表 |
| Fluss 无 TLS、SASL 只支持 PLAIN、远端凭证 RPC 无 ACL | 已证实 | 文档 + 源码检索 + FIP 页 |
| Paimon REST + bearer 可作 Fluss 的湖目录 | **推断**，未找到文档或测试 | 源码把配置原样交给 Paimon CatalogFactory |
| `datalake.paimon.token` 会随表属性下发给客户端 | **推断**（源码阅读），未实测 | `LakeStorageUtils`、`TableRegistration`、`MetadataManager` |
| Doris 4.1.4 能正确读 tiering 后的 Paimon 表 | **未验证** | 仅有 Fluss "standard Paimon table" 的声明 |
| Doris 4.1.4 经 tiering 读到的新鲜度不优于现在 | **推断**（默认 freshness 3min），未量化 | Fluss tiering 文档 + README M1 基线 |
| Fluss 自行建/改湖表的变更不经 colvenna-operator 准入 | **推断**，未验证 | `PaimonLakeCatalog` 源码 + Fluss Paimon 文档；7.3 关口 4 |
| Paimon Iceberg 兼容发布能与 tiering 共存 | **推断**，未验证 | Paimon `AbstractFileStore` 与 Fluss `PaimonLakeCommitter` 源码 |
| tiering 作业可由 Flink Kubernetes Operator 托管 | **推断**，未验证 | tiering jar 为普通 Flink 入口 |
| Fluss 有生产用户 | 仅项目方站点表述与 Fluss 博客上的案例，无用户公司自有的一手声明 | User Stories 页、rednote 案例 |

## 未能核实的问题

- Fluss 的 Paimon tiering 与 Union Read 是否在 Paimon REST catalog（含 bearer 与 `data-token.enabled`）上被上游测试过：只在 Spark 侧源码中见到 DLF（阿里云 Paimon REST）凭证的处理，没有找到 REST + bearer 的文档或测试。
- Fluss 写入（Flink sink）对 log 表与默认主键表在 failover 后的投递语义（exactly-once / at-least-once）：只找到 aggregation 表的 exactly-once 声明。
- Flink CDC 3.7.0 Fluss pipeline sink 的投递语义：文档未写。
- `paimon-flink-1.20-2.0.0.jar` 能否替代 Fluss 文档要求的 `paimon-bundle-2.0.0.jar` 与 provided 的 `paimon-arrow`。
- Fluss tiering 作业能否从 Kubernetes Secret 获取目录与存储凭证而不把它们写进作业参数。
- Fluss metrics 端口（Prometheus 9249、JMX 9250）是否有认证：没有找到相关文档。
- 用户公司（Alibaba、Fresha 等）自有渠道的生产使用声明与所用版本。
