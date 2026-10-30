---
title: "Apache Amoro 调研：能否帮助 Colvenna Lake、colvenna-* 组件可借鉴什么"
date: 2026-10-24
status: draft
issue: 264
sources:
  - https://github.com/apache/amoro （master = 6af75bbe23b79974900997b85f9629d7c1623311，2026-10-21；标签 v0.8.1-incubating = bc906c099d674561734ccd8a6ffa74ded9741994；标签 v0.9.0-rc13 = 5a64126b40e86f9fb4e0ddf5e76a2dab007c9c2f）
  - https://api.github.com/repos/apache/amoro/releases ; https://api.github.com/repos/apache/amoro/git/refs/tags ; https://github.com/apache/amoro/releases/tag/v0.8.0-incubating
  - https://amoro.apache.org/docs/latest/ ; https://amoro.apache.org/docs/latest/paimon-format/ ; https://amoro.apache.org/docs/latest/self-optimizing/ （网站文档与 master 的 docs/ 目录同源，内容随版本变化；正文改引 master 固定提交下的 docs/ 路径与行号）
  - https://incubator.apache.org/projects/amoro.html ; https://projects.apache.org/json/foundation/podlings.json
  - https://lists.apache.org/thread/0j80o73649qwg1p590h58tyykxzk8no9 （[VOTE] Release Apache Amoro (incubating) 0.9.0-incubating RC13）
  - https://github.com/apache/amoro/pull/4068 ; https://github.com/apache/amoro/pull/4234 ; https://github.com/apache/amoro/issues/3916
---

# Apache Amoro 调研：能否帮助 Colvenna Lake、colvenna-* 组件可借鉴什么

> 说明：本文**没有部署或运行 [Apache Amoro (incubating)](https://amoro.apache.org/)**。Amoro 一侧的结论来自发布标签与 master 的源码、随源码发布的文档、release notes 与 ASF 记录；"已证实"只指在这些材料里可见，不等于在 Colvenna Lake 的 EKS + S3 + colvenna-catalog 上联调过。源码里直接可见的写成事实；由此推出的后果以「推断：」开头，没有实测的写明"未实测"或"未验证"。源码出处写成"版本 路径:行号"：`master` 指提交 `6af75bb`（2026-10-21），`0.9.0-rc13` 与 `0.8.1` 指标签 `v0.9.0-rc13` 与 `v0.8.1-incubating`；路径前缀 `AMS/` = `amoro-ams/src/main/java/org/apache/amoro/server/`，`ICE/` = `amoro-format-iceberg/src/main/java/org/apache/amoro/`。完整链接形如 `https://github.com/apache/amoro/blob/6af75bbe23b79974900997b85f9629d7c1623311/<路径>#L<行>`。Colvenna Lake 一侧的出处是本仓库的 `路径:行号`（main `83c03c76`）。colvenna-catalog 的服务端源码与 LLD 在 #295 移出了本仓库，它们的出处改为 Colvenna Catalog 仓库 `colvenna/colvenna-catalog` 标签 `v1.0.0-rc.1`（提交 `698fb185`）下的 `路径:行号`，前缀 `CAT/` 指那个仓库的根目录；那里的包名是 `com.colvenna.catalog`，所引的内容与移出前相同，行号按那里的核对过。

## 结论先行

- **能不能直接接入：不能。** Colvenna Lake 的主格式是 [Apache Paimon](https://paimon.apache.org/)。Amoro 对 Paimon 只展示元数据、在 Terminal 里跑 [Apache Spark](https://spark.apache.org/) SQL；自动优化（self-optimizing）与表维护只处理 [Apache Iceberg](https://iceberg.apache.org/) 和 Amoro 自己的两种 Mixed 格式（第 2 节）。社区两次给 Paimon 加维护或优化的 PR 都没有合并。Amoro 的 Paimon catalog 只能接 hive、hadoop、filesystem 三种元存储，接不了 colvenna-catalog（Paimon REST）。Iceberg 开放层的元数据由 Paimon 写入端生成，登记在 [Apache Polaris](https://polaris.apache.org/) 的 EXTERNAL catalog 里，这个 catalog 对提交一律返回 400；推断：Amoro 的提交同样会被拒（第 4 节）。
- **该不该接入：不该，只做浏览也不该。**
  - Amoro 内置的 Paimon 客户端是 1.2.0，读 2.0.0 的表未验证。
  - 推断：绕过 colvenna-catalog 直读 warehouse，需要整个 warehouse 前缀的读权限，会绕开按表授权。
  - Iceberg REST 端点在 dashboard 认证过滤的白名单里，`RestCatalogService` 自己没有认证代码；[Apache Thrift](https://thrift.apache.org/) 端口用的传输不带 SASL 或 TLS。推断：两者都能不经认证访问（未实测），与"对外暴露面必须认证"冲突。
  - 它还会成为第二套决定何时压缩、何时过期的管理面。HLD 不引入 Dinky、[Apache StreamPark](https://streampark.apache.org/)，就是出于同样的理由。
- **值得借鉴：自动优化的调度模型。** Amoro 的管理服务（AMS）持续评估每张表，按表属性决定做 minor、major 还是 full，按优化组的线程配额和调度策略挑表；规划与提交在 AMS，改写在执行器，失败按任务重试；表的状态机和健康分对外可见。Colvenna Lake 已经有同类信号：Operator 每 5 分钟统计 level-0、小文件、快照数。也有"Operator 按轮自动建 `LakeTask`"的先例：个人数据 ODS 表每小时的快照过期。差的是从信号到调度这一步、跨表的并发上限和可声明的策略。这部分的设计在 #265 跟踪（第 5.1 节）。
- **其余借鉴项价值较小**：控制台按健康分或"最久未维护"排序，CLI 显示维护状态，colvenna-catalog 用提交统计做表级预筛。还有一条反面教训：将来 colvenna-catalog 提供 Iceberg REST 时，端点必须认证（第 5.2–5.6 节）。

## 1. 项目概况与成熟度

- **定位**：ASF 项目索引（projects.apache.org）的描述是 "a Lakehouse management system built on open data lake formats like Apache Iceberg and Apache Paimon" [来源: https://projects.apache.org/json/foundation/podlings.json]。文档首页说，AMS 负责自动优化、数据过期和统一 catalog；插件有三类：执行器（Optimizer）、Terminal（本地 Spark 或 [Apache Kyuubi](https://kyuubi.apache.org/)）、LogStore（[Apache Kafka](https://kafka.apache.org/) 或 [Apache Pulsar](https://pulsar.apache.org/)）（master `docs/_index.md:24-41`）。
- **ASF 状态：仍在孵化。** 2024-03-11 进入孵化器，状态页没有毕业记录，退出清单一项也没勾 [来源: https://incubator.apache.org/projects/amoro.html]。项目索引记为 `podling: true`、`pmc: incubator`、`started: 2024-03` [来源: https://projects.apache.org/json/foundation/podlings.json]。
- **发布**：
  - 孵化期的正式版有 0.7.0-incubating（2024-08-02）、0.7.1-incubating（2024-11-20）、0.8.0-incubating（2025-05-08）和 0.8.1-incubating（2025-09-11，最新）[来源: https://api.github.com/repos/apache/amoro/releases]。
  - 0.9.0-incubating 从 rc1（2026-05-08）打到 rc13（2026-10-11）[来源: https://api.github.com/repos/apache/amoro/git/refs/tags]。RC13 的投票 2026-10-14 在 dev@ 发起，2026-10-19 仍有回复，没找到结果邮件 [来源: https://lists.apache.org/thread/0j80o73649qwg1p590h58tyykxzk8no9]。
  - 推断：距上一个正式版已 13 个月，0.9.0 何时发布未知。
- **活跃度（2026-10-24 抓取）**：1,197 star、398 fork；默认分支近 52 周 271 次提交、近 13 周 81 次；近 90 天合并 PR 83 个；open issue 43 个 [来源: https://api.github.com/repos/apache/amoro ; https://api.github.com/repos/apache/amoro/stats/participation ; https://api.github.com/search/issues?q=repo:apache/amoro+is:pr+is:merged+merged:2026-07-24..2026-10-24]。
- **版本与兼容**（版本号取自各版本根目录的 `pom.xml`：0.8.1 `:74-75`、`:104-107`、`:127`、`:145`；master `:74-75`、`:106-109`、`:134`、`:152`）：

  | | 0.8.1 | 0.9.0-rc13 与 master | Colvenna Lake | 结论 |
  | --- | --- | --- | --- | --- |
  | Java | 8 | 11，另有 java17 profile | colvenna-jobs 11（`colvenna-jobs/pom.xml:14`）；Operator、catalog 21（`colvenna-operator/pom.xml:13`、`CAT/pom.xml:13`） | — |
  | AMS 在 Java 21 上运行 | 编译目标 8 | 构建 profile 只有 java11（JDK `[11,17)`）和 java17（master `pom.xml:1933-1946`）；CI 只测 JDK 11、17（master `.github/workflows/core-hadoop3-ci.yml:40`）；官方镜像基于 `eclipse-temurin:11-jdk-jammy`（master `docker/amoro/Dockerfile:26`、`:43`） | Operator、catalog 跑在 21 上 | 未验证 |
  | Apache Paimon | 0.9.0 | 1.2.0 | 2.0.0（`colvenna-jobs/pom.xml:16`、`colvenna-operator/pom.xml:19`、`CAT/pom.xml:15`） | 读 2.0.0 的表未验证（4.1） |
  | Apache Iceberg | 1.6.1 | 1.8.1 | 元数据由 Paimon 写；catalog 是 Polaris 1.8.0（`docs/research/iceberg-open-layer.md:18`） | Iceberg 1.8.1 客户端连 Polaris 1.8.0 未验证；EXTERNAL catalog 拒绝写入（4.2） |
  | [Apache Flink](https://flink.apache.org/) | 1.20.0 | 1.20.3 | 1.20.4（`colvenna-jobs/pom.xml:17`） | — |
  | [Apache Hadoop](https://hadoop.apache.org/) | 3.4.0，另有 2.10.2 的 profile | 3.4.3。0.9.0-rc13 仍有 2.10.2 的 profile；master 的 `6af75bb` 本身就是删掉 Hadoop 2 支持的提交（[AMORO-4451] #4452） | — | — |
  | Spark | Terminal 3.3.2（`pom.xml:127`），Spark 执行器 3.3.3（`amoro-optimizer/amoro-optimizer-spark/pom.xml:36`） | 3.5.8（master `pom.xml:134`） | 不安装（`docs/design/hld-realtime-lakehouse.md:136`） | — |
  | Kubernetes 执行器与 Helm chart | — | chart 不限定 Kubernetes 版本（master `charts/amoro/Chart.yaml:18-29`；`values.yaml:18-20` 默认取 Helm 探测到的版本）。默认只建命名空间级 Role（`values.yaml:71-77`），权限是：增删 pods、deployments、services，增改删 configmaps，读 secrets（`charts/amoro/templates/_rbac.tpl:17-33`）。执行器用 fabric8 kubernetes-client 7.4.0 建、删 Deployment（master `pom.xml:154`；`AMS/manager/KubernetesOptimizerContainer.java:175`、`:390`） | EKS 1.36（`deploy/aws/src/foundation-spec.json:2`） | 在 EKS 1.36 上未验证。Role 对 secrets 的 get 不限定名字，能读命名空间里的全部 Secret；推断：AMS 要放进单独的命名空间 |

- **0.9.0 的新东西**（0.9.0-rc13 已含，master 同）：Lance 格式（只展示）、按格式插件化的维护流程（`ProcessFactory`）、执行器动态扩缩、dashboard 的 RBAC（0.9.0-rc13 的 `amoro-format-lance/`、`AMS/process/`、`AMS/optimizing/dra/`、`AMS/authorization/`）。

## 2. 各表格式的实际能力

| 能力 | Iceberg | Mixed-Iceberg | Mixed-Hive | Paimon | [Apache Hudi](https://hudi.apache.org/) |
| --- | --- | --- | --- | --- | --- |
| 自动优化（minor / major / full） | ✔ | ✔ | ✔ | ✘ | ✘ |
| 快照过期、孤儿文件清理、悬空 delete 文件清理 | ✔ | ✔ | ✔ | ✘ | ✘ |
| 数据过期、自动打 tag | ✔ | ✔ | ✔ | ✘ | ✘ |
| 元数据浏览（schema、文件、快照、DDL、分区） | ✔ | ✔ | ✔ | ✔，另有压缩历史、tag、分支、consumer | ✔，只能挂 Hive 元存储 |
| Terminal（Spark SQL） | ✔ | ✔ | ✔ | ✔ | 未核实 |

依据：

- **自动优化与维护的总闸**：`RuntimeHandlerChain.formatSupported` 只放行 ICEBERG、MIXED_ICEBERG、MIXED_HIVE，注释写着 "Currently, paimon is unsupported"（0.8.1 `AMS/table/RuntimeHandlerChain.java:134-137`；0.9.0-rc13 与 master 同文件 `:135-138`）。加表、状态变化、配置变化等运行时处理，都先过这道判断（0.8.1 同文件 `:57`、`:70`、`:84`、`:97`、`:114`）。
- **维护器**：
  - `TableMaintainers.create` 只为 Mixed 与 Iceberg 建维护器，其他格式抛 "Unsupported table type"（master `AMS/optimizing/maintainer/TableMaintainers.java:50-61`）。
  - 0.9.0 的新流程里，`IcebergProcessFactory` 只登记三种 Iceberg 系格式（master `AMS/process/iceberg/IcebergProcessFactory.java:100-101`），也是 `ProcessFactory` 唯一注册的实现（master `amoro-ams/src/main/resources/META-INF/services/org.apache.amoro.process.ProcessFactory:19`）。
  - 它提供的动作有快照过期、孤儿清理、悬空 delete 文件清理、数据过期、自动 tag 和 [Apache Hive](https://hive.apache.org/) 同步（master `AMS/process/iceberg/` 下的各 `*Process.java`）。
- **Paimon**：
  - 文档的原话是 "Amoro supports displaying metadata information in the Paimon format, including Schema, Options, Files, Snapshots, DDLs, and Compaction information"（0.8.1 与 master `docs/formats/overview.md:47`），以及 "they can operate on Paimon tables using Spark SQL in the Terminal"（master `docs/formats/paimon.md:32-33`）。
  - 表描述器能列快照、分区、文件、优化历史、tag、分支和 consumer（master `amoro-format-paimon/src/main/java/org/apache/amoro/formats/paimon/PaimonTableDescriptor.java:110-578`）。
  - master 里找不到 Paimon 的优化规划或维护代码。
  - 社区有过两次尝试，都没有合并：PR #4068 是 Paimon 的远程维护框架，把快照过期、孤儿清理放到 Spark 执行器上，2026-03-17 因无人跟进关闭；PR #4234 只对 append 无桶表做小文件调度，2026-05-25 关闭 [来源: https://github.com/apache/amoro/pull/4068 ; https://github.com/apache/amoro/pull/4234]。
- **Hudi**：
  - `amoro-format-hudi` 模块在 0.8.1 和 master 里都有，只提供 catalog 和表描述器（master `amoro-format-hudi/src/main/java/org/apache/amoro/formats/hudi/`）。
  - 它只能挂 Hive 元存储（master `AMS/dashboard/controller/CatalogController.java:144`），不在文档的格式列表里（master `docs/formats/overview.md:42-47`），也过不了 `formatSupported`。
- **默认开着的维护**：
  - 默认开：`self-optimizing.enabled`；`table-expire.enabled`，快照保留 720 分钟；`clean-dangling-delete-files.enabled`。
  - 默认关：孤儿清理、数据过期、自动 tag。
  - 出处：master `ICE/table/TableProperties.java:75-77`、`:154-162`、`:202-214`、`:251-253`。
  - 推断：一个 Iceberg catalog 登记进 Amoro，默认就会对其中的表做自动优化、12 小时快照过期和悬空 delete 文件清理。

## 3. 自动优化与维护怎么运转（Iceberg 路径）

### 3.1 触发条件

- **文件分类**：小于 `target-size / fragment-ratio` 的是碎片文件（fragment），其余是 segment；默认 128 MB / 8 = 16 MB（master `docs/concepts/self-optimizing.md:70-83`；master `ICE/table/TableProperties.java:97-107`）。
- **按分区判断**（master `ICE/optimizing/plan/CommonPartitionEvaluator.java:403-432`；参数默认值见 master `ICE/table/TableProperties.java:113-131`）：
  - minor：碎片文件与 equality delete 文件合计不少于 `self-optimizing.minor.trigger.file-count`（默认 12），或者多于 1 个且距上次 minor 已超过 `minor.trigger.interval`（默认 1 h）；
  - major：未达标的 segment 合计不小于 target-size、最小的两个能合成一个，或者有需要改写的 segment（重复数据比例阈值 `major.trigger.duplicate-ratio`，默认 0.1）；
  - full：只在到了 `full.trigger.interval` 时考虑（默认 -1，即从不），并且有 delete 文件、两个以上碎片或未达标 segment 等。
- **按元数据预筛**（可选）：
  - 只看快照摘要里的文件数和平均大小。没有 delete 文件、平均大小超过 `target-size × min-target-size-ratio`（默认 0.75）时，就不去读 manifest。
  - 只对 Iceberg 和两种 Mixed 格式生效；Paimon、Hudi 不支持，源码注释的原因是取统计要遍历 manifest（master `ICE/optimizing/evaluation/MetadataBasedEvaluationEvent.java:39-95`，格式判断在 `:80-88`）。
  - 开关是 `self-optimizing.evaluation.fallback-interval`，默认 -1，不开（master `ICE/table/TableProperties.java:141-151`）。
- **只评估有新提交的表**：表要处于 PENDING，当前快照也要和上次优化时不同，才进入候选；同时受 `self-optimizing.min-plan-interval` 节流，默认 60 s（master `AMS/optimizing/SchedulingPolicy.java:111-127`；master `ICE/table/TableProperties.java:136-138`）。

### 3.2 调度：优化组、配额与策略

- **优化组与配额**：每张表经 `self-optimizing.group` 属于一个优化组，默认 `default`。`self-optimizing.quota` 是它能占的执行线程：小数是占组内线程的比例，整数是线程数，默认 0.5（master `ICE/table/TableProperties.java:87-91`；master `AMS/table/DefaultTableRuntime.java:256-265`）。
- **调度策略**：组的 `scheduling-policy` 决定挑表顺序（master `AMS/optimizing/SchedulingPolicy.java:48-65`；master `AMS/optimizing/sorter/QuotaOccupySorter.java:27-48`；master `AMS/optimizing/sorter/BalancedSorter.java:27-48`；1 小时窗口见 master `AMS/AmoroServiceConstants.java:25`）。
  - 默认 `quota`：过去 1 小时的实际占用除以配额，比值越小越先调度。
  - `balanced`：最久没优化的表先调度。
  - 文档的解释是：quota 下资源消耗大的表可能长时间得不到优化，balanced 让各表的进度接近（master `docs/concepts/self-optimizing.md:145-179`）。
- **执行器**：组的容量来自执行器。
  - 执行器容器有四种：本机、Flink、Spark、Kubernetes（master `AMS/manager/LocalOptimizerContainer.java`、`FlinkOptimizerContainer.java`、`SparkOptimizerContainer.java`、`KubernetesOptimizerContainer.java`）。
  - 0.9.0 起，执行器能按积压动态扩缩，配置项有最小/最大并行度、积压超时、空闲超时、缩容冷却和排空超时（0.9.0-rc13 与 master `AMS/optimizing/dra/DynamicAllocationConfig.java:43-50`）。

### 3.3 规划、执行与提交

- **规划在 AMS**：`OptimizingQueue` 在 planExecutor 上异步规划（master `AMS/optimizing/OptimizingQueue.java:369`、`:408`）。任务按 `self-optimizing.max-task-size-bytes` 切分，默认 128 MB（master `ICE/table/TableProperties.java:103-104`）。
- **执行在执行器**：执行器经 Thrift 拉取任务、确认、执行、回报（master `amoro-optimizer/amoro-optimizer-common/src/main/java/org/apache/amoro/optimizer/common/OptimizerExecutor.java:133-140`）。0.8.0 的 release notes 列了 "Execution table planning and commit in optimizer #3315" [来源: https://github.com/apache/amoro/releases/tag/v0.8.0-incubating]。但 master 执行器的公共模块里只有拉取和执行任务的代码，规划与提交仍由 AMS 完成。
- **提交在 AMS**：
  - 由 `OptimizingCommitExecutor` 执行（master `AMS/scheduler/inline/OptimizingCommitExecutor.java:29-66`）。
  - 非主键表用 Iceberg 的 `RewriteFiles`，并以规划时的快照调用 `validateFromSnapshot`（master `AMS/optimizing/UnKeyedTableCommit.java:305-322`）。
  - 写入端在这期间做了冲突的改动，Iceberg 的校验就让提交失败，抛 `OptimizingCommitException`（同文件 `:273-278`）。整轮记为 FAILED，本轮不重试，留给下一次规划（master `AMS/optimizing/OptimizingQueue.java:921-944`）。
  - 也就是说，Amoro 不挂起写入端，靠 Iceberg 的乐观并发和重新规划处理冲突。
- **失败重试**：单个任务失败后重试，最多 `self-optimizing.execute.num-retries` 次（默认 5）。重试用完时，若 `self-optimizing.allow-partial-commit=true`，就提交已经成功的任务，否则整轮失败（master `AMS/optimizing/OptimizingQueue.java:779-805`；master `ICE/table/TableProperties.java:83-95`）。

### 3.4 状态机与可观测性

- **表的优化状态**：IDLE、PENDING、PLANNING、MINOR_OPTIMIZING、MAJOR_OPTIMIZING、FULL_OPTIMIZING、COMMITTING（master `AMS/optimizing/OptimizingStatus.java:23-30`）。
- **指标**（master `docs/user-guides/metrics.md`）：
  - 表级：每个状态都有"是否处于"和"已持续多久"两个指标；另有距上次 minor、major、full 的时长，以及 `table_optimizing_lag_duration_mills`（`:42-64`）。
  - 组级：待执行任务、线程数、积压时长、扩缩次数（`:70-96`）。
- **健康分**（0–100）：
  - 小文件 40 分、equality delete 40 分、position delete 20 分，偏离阈值越多扣得越多（master `ICE/optimizing/plan/CommonPartitionEvaluator.java:444-466`；0.8.1 已有，同文件 `:395`）。
  - 它导出成指标 `table_summary_health_score`（master `AMS/table/TableSummaryMetrics.java:200-204`）。
  - Overview 页的表排行可以按表大小、文件数或健康分排序；还没算出健康分的表排在最后（master `AMS/dashboard/controller/OverviewController.java:71-94`，健康分在 `:82-91`；取值见 master `AMS/dashboard/OverviewManager.java:215`）。
  - 文档里没有算法：自动优化的概念文档全文没有提到健康分（master `docs/concepts/self-optimizing.md`），指标文档只列了名字和一句 "Health score of the table"（master `docs/user-guides/metrics.md:138`）。

### 3.5 表维护

维护全部由表属性声明，AMS 定时执行，只对 Iceberg 系格式生效（master `ICE/table/TableProperties.java:154-272`）：

- **快照过期**：`table-expire.enabled` 默认开，`snapshot.keep.duration` 默认 720 min；
- **孤儿文件清理**：`clean-orphan-file.enabled` 默认关；文件至少存在 `clean-orphan-file.min-existing-time-minutes`（默认 2 天）才清；
- **悬空 delete 文件清理**：`clean-dangling-delete-files.enabled` 默认开（`:209-211`）；
- **数据过期**：`data-expire.*` 默认关；可以按字段、分区级或文件级、按保留时长删除；
- **自动打 tag**：`tag.auto-create.*` 默认关；可按每日、每小时或每月打，可设偏移、最大延迟和格式。

## 4. 与 Colvenna Lake 的兼容与冲突

### 4.1 Paimon 主表

- **没有维护能力**：Amoro 不维护 Paimon 表（第 2 节）。
- **接不上 colvenna-catalog**：
  - Amoro 给 Paimon 提供的 catalog 只有 hive、hadoop、filesystem 三种元存储，没有 rest（master `AMS/dashboard/controller/CatalogController.java:142-188`）；colvenna-catalog 是 Paimon REST catalog 的服务端（`CAT/docs/design/colvenna-catalog-lld.md:13`）。
  - 表级授权和按表凭证由 colvenna-catalog 执行（`docs/design/hld-realtime-lakehouse.md:165`、`:460`）。推断：换用 filesystem catalog 直读 S3 上的 warehouse，Amoro 要拿到整个 warehouse 前缀的读权限，这条路径不经 colvenna-catalog，和按表授权的设计冲突。
- **客户端版本**：
  - 0.9.0-rc13 与 master 内置 Paimon 1.2.0（0.8.1 是 0.9.0），我们的表是 2.0.0。旧客户端读新表的元数据是否正确，没有验证过。
  - 先例：M1 时 [Apache Doris](https://doris.apache.org/) 内置的 Paimon 1.4.2 读 Paimon 2.0.0 的 DV L0 数据，就产生过错误行集（`docs/design/hld-realtime-lakehouse.md:392`）。
- **S3 凭证**：
  - 认证方式为 `ak/sk` 时，Amoro 把静态密钥写进 Paimon catalog 的属性；其他方式交给 Hadoop 配置（master `amoro-format-paimon/src/main/java/org/apache/amoro/formats/paimon/PaimonCatalogFactory.java:53-83`）。
  - 有用户报告 Paimon catalog 用 IRSA（WebIdentity）访问 S3 不成功；这个 issue 没人答复，2026-05 因过期关闭 [来源: https://github.com/apache/amoro/issues/3916]。
  - Colvenna Lake 一律用 IRSA 与按表 STS 凭证，配置里不写静态密钥（`docs/design/hld-realtime-lakehouse.md:165`、`:477`）。IRSA 路径能不能用，未验证。

### 4.2 Iceberg 开放层

- **现状**：开放层的 Iceberg 元数据由 Paimon 写入端写在表目录里（`metadata.iceberg.storage=table-location`），再由 Operator 以通知登记到 Polaris 的 EXTERNAL catalog（`docs/design/hld-realtime-lakehouse.md:418`）。EXTERNAL catalog 对建表、提交、改名、purge 都返回 400（`docs/design/colvenna-operator-lld.md:1148`、`:1222`）。
- **经 Polaris 登记进 Amoro**（REST catalog 类型）：自动优化和默认开启的快照过期都要提交。推断：提交都会被拒，结果是一轮轮失败的优化与维护记录，不会损坏数据，但也没有任何收益。
- **绕过 Polaris，用 hadoop catalog 直接指向表目录**：推断：
  - Amoro 写出的新元数据会和 Paimon 下一次提交生成的 Iceberg 元数据互相覆盖，读端看到的内容取决于谁最后写；
  - Paimon 不知道 Amoro 改写出的数据文件，它的孤儿清理可能把这些文件当成孤儿删掉。
  - 不可接受。
- **新鲜度问题帮不上**：开放层主键表的新鲜度取决于全量压缩（`docs/design/hld-realtime-lakehouse.md:419`）。这是 Paimon 一侧的压缩调度问题。

### 4.3 认证与暴露面

- **默认口令**：管理员默认是 `admin` / `admin`（master `dist/src/main/amoro-bin/conf/config.yaml:18-20`；master `docs/configuration/ams-config.md:45-46`）。dashboard 登录可以接 LDAP。RBAC 只在 0.9.0 里有，默认关（master `dist/src/main/amoro-bin/conf/config.yaml:37-70`；master `docs/configuration/ams-config.md:77`）。
- **Iceberg REST 端点**：
  - `/api/iceberg/rest/*`（包括建表、提交、删表）在 dashboard 认证过滤的白名单里（master `AMS/dashboard/DashboardServer.java:413-416`、`:488-511`；0.9.0-rc13 同文件 `:507`）。
  - `RestCatalogService` 自己没有认证代码（master `AMS/RestCatalogService.java:107-163`）。
  - 推断：不认证就能读写 AMS 的内部 catalog。未实测。
- **Thrift 端口**：
  - 1260（table service）和 1261（optimizing service）用的是不带 SASL、不带 TLS 的 `TFramedTransport`（master `AMS/AmoroServiceContainer.java:570-575`；master `dist/src/main/amoro-bin/conf/config.yaml:24-32`）。
  - 推断：执行器注册和领取任务都不认证。未实测。
- **与平台规定冲突**：
  - Colvenna Lake 规定不允许任何未经认证的服务或资源暴露（`docs/design/hld-realtime-lakehouse.md:465`）。Polaris 也要求 OAuth2 客户端凭证，实测拒绝匿名请求（同文件 `:475`）。
  - 要部署 Amoro，至少要做到：1260、1261、1630 端口只留在集群内，用 NetworkPolicy 隔离；换掉默认口令；REST 端点不能对外。

### 4.4 运维代价

- **多一套有状态服务**：AMS 加元数据库（MySQL、PostgreSQL 或 [Apache Derby](https://db.apache.org/derby/)），HA 靠 [Apache ZooKeeper](https://zookeeper.apache.org/) 或数据库（master `AMS/ha/ZkHighAvailabilityContainer.java`、`DataBaseHighAvailabilityContainer.java`），再加执行器；Helm chart 在 master `charts/amoro/`。
- **多一套管理面**：HLD 不引入 Dinky、StreamPark，理由是它们会和 Operator 的托管冲突（`docs/design/hld-realtime-lakehouse.md:370`）。Amoro 会成为第二个决定"什么时候压缩、什么时候过期"的地方。

## 5. 各组件可借鉴什么

### 5.1 colvenna-operator：把积压信号变成调度（重点）

设计在 #265 跟踪。

**现状。**

- **信号已经有。**
  - `Backlog` 每 5 分钟统计一次每张表的快照数、数据文件数、主键表未合并的 level-0 文件、小于目标大小 1/4 的小文件，以及 manifest 大小（`colvenna-operator/src/main/java/com/colvenna/lake/operator/lake/Backlog.java:16-24`；`colvenna-operator/src/main/java/com/colvenna/lake/operator/lake/LakeReader.java:311-333`）。
  - 它也能找到最近一次数据提交（`LakeReader.java:341-344`、`:380-388`）。
- **只告警，不调度。**
  - 告警规则（`deploy/helm/colvenna-monitoring/files/rules.yaml:199-216`）：level-0 超过 50 个、持续 30 分钟，告警 `ColvennaCompactionBacklog`；小文件超过 1000 个、持续 1 小时，告警 `ColvennaSmallFiles`；快照超过 5000 个、持续 1 小时，告警 `ColvennaSnapshotsPiling`。
  - 手册把只写不压的 ODS 表的压缩与过期交给用户的编排器（`docs/manual/user.md:807`、`:1782`；`docs/design/hld-realtime-lakehouse.md:364`、`:370`、`:433-434`）。
- **已有自动建任务的先例。**
  - `Retention` 每小时为同时满足两个条件的源建快照过期任务：按个人数据处理，且只写不压。
  - 每张 Ready 的表一个 `expireSnapshots` 的 `LakeTask`，任务名带轮次号，旧轮次里已完成的删掉。
  - 出处：`colvenna-operator/src/main/java/com/colvenna/lake/operator/source/Retention.java:21-79`，调用处 `colvenna-operator/src/main/java/com/colvenna/lake/operator/source/LakeSourceReconciler.java:322`。
- **执行与互斥。**
  - `LakeTask` 在 Kubernetes Job 里以 Flink 批模式运行（`colvenna-operator/src/main/java/com/colvenna/lake/operator/task/TaskRuns.java:22-26`）。
  - 同一张表的任务按创建顺序一个一个跑（`colvenna-operator/src/main/java/com/colvenna/lake/operator/task/LakeTaskReconciler.java:69-75`、`:397-401`、`:549-569`）。
  - 写入端自己压缩、又没有可挂起作业的表，压缩任务被拒绝，原因 `WriterCompacts`（同文件 `:386-396`；`docs/design/colvenna-operator-lld.md:640`、`:642`）。
  - 任务有 `retries`（默认 2）、`timeout`（默认 1 h）和 `resources`（`colvenna-operator/src/main/java/com/colvenna/lake/operator/crd/LakeTaskSpec.java:40-50`）。
- **写入端自己压缩的表只有部分有保底。** 只有按个人数据处理（包括未分类）或带 Iceberg 元数据的表，Operator 才给它设 `compaction.optimization-interval=1h` 作为全量压缩的保底；其余写入端自压缩的表不设（`colvenna-operator/src/main/java/com/colvenna/lake/operator/governance/GovernancePolicy.java:275-281`；`colvenna-operator/src/main/java/com/colvenna/lake/operator/lake/TableOptions.java:108-121`；`docs/design/hld-realtime-lakehouse.md:432`）。

**可借鉴的做法。**

| Amoro 的设计 | 借到 Operator | 前提与差距 |
| --- | --- | --- |
| 触发规则由表属性声明：minor 看碎片数与间隔，full 看间隔（3.1） | 在 `LakeSource` 或 `LakeTable` 的声明里加维护策略，例如是否自动压缩、level-0 或小文件阈值、最长间隔、全量压缩间隔；Operator 按 `Backlog` 的计数自动建 compact `LakeTask` | 只对只写不压的表，`WriterCompacts` 规则保持不变。两档映射到现有任务：非全量 `sys.compact` 对应 minor，`full` 对应 full（`colvenna-jobs/src/main/java/com/colvenna/lake/jobs/sql/TaskJob.java:102-105`） |
| 只评估有新提交的表，规划受最小间隔节流（3.1） | 上次自动压缩之后没有新的数据提交，就不再建任务；同一张表两轮之间设最小间隔 | `LakeReader` 已能找到最近一次数据提交（`LakeReader.java:380-388`），还要把"上次自动压缩时的快照"记进 status |
| 优化组、线程配额和组级调度策略（3.2） | 自动调度上线之前，先给命名空间设一个维护任务的并发与资源上限，再按配额式或最久未维护优先式排队 | 现在没有跨表的并发上限，只按表串行（`LakeTaskReconciler.java:549-569`）。推断：自动任务会和用户手动的任务、回刷抢同一批节点 |
| 表的优化状态机和"距上次优化"的指标（3.4） | 在 status 里记每张表的维护状态（空闲、等待、运行中）和上次压缩、过期的时间，导出类似 `since_last_*` 的指标 | 有了自动调度，`ColvennaCompactionBacklog` 的处置要从"安排任务"改成"自动任务失败或跟不上" |
| 一轮失败本轮不重试，留给下一轮重新规划（3.3） | 自动建的任务 `retries=0`，靠下一轮重新评估 | 避免和 `LakeTask` 自带的重试叠加，形成任务风暴 |
| 健康分（3.4） | 可选：把 level-0、小文件、快照三项合成一个分数，供控制台排序（5.4） | 分数只是展示；调度仍按明确的阈值 |

**不借鉴的部分。**

- AMS 式的常驻规划服务和 Thrift 执行器协议：Operator 加 Kubernetes Job 已经覆盖了这些。
- 处理并发冲突的办法：Amoro 靠 Iceberg 的乐观并发加重新规划。Paimon 一侧采用官方推荐的只写不压加独立压缩，本来就是为了避免文件级冲突（`docs/design/hld-realtime-lakehouse.md:117`）。
- 设计时还要和 Paimon 自带的方案比一比：常驻的 `compact_database` 压缩作业。HLD 原计划用它覆盖 append 表，M3 没有部署（`docs/design/hld-realtime-lakehouse.md:334`）。

### 5.2 colvenna-jobs

- **现状**：
  - `TaskJob` 只调用 Paimon 的 procedure：`sys.compact`（可选全量、可选分区）、`expire_snapshots`、`expire_tags`、`remove_orphan_files`、`create_tag`（`colvenna-jobs/src/main/java/com/colvenna/lake/jobs/sql/TaskJob.java:102-224`）。
  - `compact.partitions` 已经能限定分区（`colvenna-operator/src/main/java/com/colvenna/lake/operator/crd/LakeTaskSpec.java:85-92`）。
- **可借鉴：挑分区。** Amoro 逐个分区评估，只改写需要的分区，并按估算的代价排序（master `ICE/optimizing/plan/CommonPartitionEvaluator.java:325-375`）。我们缺的是"挑哪些分区"，这属于 Operator 的规划（5.1），执行端不用改。
- **不借鉴**：Amoro 执行器里的 Iceberg 改写逻辑，格式不同。

### 5.3 colvenna-catalog

- **反面教训**：
  - Amoro 把 Iceberg REST 端点放进了认证白名单（4.3；推断：不认证即可访问，未实测）。
  - colvenna-catalog 用 API key 语义的 Bearer token（`CAT/docs/design/colvenna-catalog-lld.md:197-201`）。将来 colvenna-catalog 也提供 Iceberg REST 时，端点必须同样认证。
- **表级预筛（价值低，推断，未验证）**：
  - colvenna-catalog 记录的统计：每个分支有累计的记录数、文件数和文件大小（`CAT/src/main/java/com/colvenna/catalog/catalog/Records.java:80-90`；`CAT/src/main/resources/db/migration/V1__catalog.sql:41-54`）；按分区的统计只对 `metastore.partitioned-table=true` 的表记录（`CAT/src/main/java/com/colvenna/catalog/catalog/PartitionCatalog.java:37-41`；`CAT/docs/design/colvenna-catalog-lld.md:41`、`:210`）。产品代码里没有地方设这个选项，只有 Flink 验收用例设了（`tests/acceptance/catalog/flink/src/main/java/com/colvenna/lake/acceptance/flow/FlinkCatalogFlow.java`），所以平台的表只有分支级统计。
  - 设想：参照 Amoro 按元数据预筛的思路（见 3.1），Operator 先用"文件大小 ÷ 文件数"在表级排除平均文件已经够大的 append 表，省掉一部分 `Backlog` 每 5 分钟读全部 data split 的开销（`LakeReader.java:311-333`）。
  - 局限：做不到按分区挑选。主键表的 level-0 数不在统计里，这个办法对主键表无效。分支级统计是把每次提交带来的增量累加上去（`CAT/src/main/java/com/colvenna/catalog/catalog/CommitService.java:515-522`），压缩提交后它能不能反映文件数下降，要先核实。
- **没有功能重叠**：Amoro 不提供 Paimon REST catalog，对 Paimon 只做展示（4.1）。

### 5.4 colvenna-console

- **现状**：
  - 节点页显示新鲜度、level-0 文件、小文件和快照数，并列出挂在这张表上的任务（`colvenna-console/internal/pipeline/model.go:441-444`；`docs/design/colvenna-console-lld.md:173`；`docs/manual/user.md:1657`）。
  - 受控操作可以触发压缩和过期等任务（`docs/manual/user.md:1662-1672`）。
- **可借鉴**：
  - Amoro 的 Overview 页能把表按健康分排序（master `AMS/dashboard/controller/OverviewController.java:82-91`）；表页列出每轮优化的类型、耗时和输入输出文件；对 Paimon 表，它从快照列压缩历史（master `amoro-format-paimon/src/main/java/org/apache/amoro/formats/paimon/PaimonTableDescriptor.java:378`）。
  - 控制台可以加"积压最重"和"最久未维护"两种排序，节点页加上次压缩和过期的时间。
  - 前提是 Operator 先给出这些状态（5.1）；控制台只读展示，不自己计算。
- **不借鉴**：Terminal 和 catalog 管理页。控制台按设计不展示表数据（`docs/manual/user.md:1652`）。

### 5.5 colvenna-cli

- **现状**：`colvenna maintain compact|expire|orphans|tag` 一次建一个任务（`colvenna-cli/internal/cli/batch.go:75-128`），CLI 只调 Kubernetes API（`docs/manual/user.md:1581`）。
- **可借鉴**：如果 5.1 把维护状态写进声明的 status，`colvenna get` 和 `colvenna describe` 就能直接显示每张表的积压和上次维护时间（`colvenna-cli/internal/cli/describe.go:17-18`），不用访问 Prometheus。
- **其他没有可借鉴的，原因有两条**：
  - Amoro 没有可对照的命令行工具：对外的程序接口只有 REST API 的 OpenAPI SDK（master `amoro-openapi-sdk/`，只在 `openapi-sdk` profile 里构建，`pom.xml:2020-2022`），以及给 AI 代理用、只提供查询工具的 MCP 服务（master `amoro-mcp-server/README.md:22-29`）。它的维护由 AMS 自动执行，不需要人逐表下命令。
  - colvenna CLI 是薄客户端，不做判断（`docs/manual/user.md:1581`），积压和调度的计算本来就不该放在它这里。

### 5.6 colvenna-bi

- **不借鉴。**
  - Amoro 的 Terminal 用 catalog 的认证信息执行 SQL（master `docs/admin-guides/using-kyuubi.md:41`），不区分登录用户。
  - colvenna-bi 的 [Apache Superset](https://superset.apache.org/) 把 SSO 用户映射成 Doris 用户、带行条件（`docs/manual/user.md:1304`、`:1706`），SQL Lab 已经覆盖即席查询（`docs/manual/user.md:1717`）。

### 5.7 汇总

| 组件 | 借鉴项 | 价值 | 前提 |
| --- | --- | --- | --- |
| colvenna-operator | 声明式维护策略 + 按积压自动建压缩任务 + 跨表并发上限 + 维护状态（#265） | 高：把只写不压 ODS 表的压缩从用户的编排器收回平台 | 并发与资源上限先行；只对只写不压的表 |
| colvenna-jobs | 按分区挑选（规划放在 Operator） | 中 | 依赖 5.1 |
| colvenna-catalog | Iceberg REST 必须认证（反面教训）；用分支级统计做表级预筛 | 低 | 预筛只适用于 append 表，要先核实统计语义 |
| colvenna-console | 积压与"最久未维护"排序、上次维护时间 | 中 | 依赖 5.1 的 status |
| colvenna-cli | `get`、`describe` 显示维护状态 | 低 | 依赖 5.1 的 status |
| colvenna-bi | 无 | — | — |

## 6. 什么时候重新评估

出现下面任一情况，再看一次：

- Amoro 的 `formatSupported` 或 `ProcessFactory` 加入 PAIMON，并且覆盖 Paimon 主键表的压缩规划，不只是 append 无桶表；同时客户端跟上 Paimon 2.x。
- Amoro 支持 Paimon REST catalog，并能用 IRSA 或默认凭证链访问 S3。
- Iceberg REST 端点与 Thrift 端口有了认证。
- 0.9.0 正式发布，之后恢复稳定的发布节奏，或者项目毕业。
- Colvenna Lake 的开放层改成由 Iceberg 作为写入格式，不再由 Paimon 生成元数据。到那时，Amoro 对 Iceberg 的自动优化才有可能适用。

> 注：本文中的 issue 与 PR 编号（如 #295）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
