---
title: "商标与命名"
date: 2026-10-24
status: draft
issue: 258
---

# 商标与命名

本页记录 Colvenna Lake 怎样使用 Apache 软件基金会（ASF）和其他各方的商标，平台分发的各个镜像怎样命名、依据是什么，以及几项还没有处理的事（#258）。

> 本页的判断是维护者按已公布的政策做的工程解读，不是法律意见。判断时没有征询 Apache Doris PMC 的意见。

## 1. 署名

产品名是 “Colvenna Lake”，不含第三方商标；副标题是 “基于 Apache Paimon 的声明式实时湖仓” / “Declarative real-time lakehouse, powered by Apache Paimon”，其中的 Apache Paimon 说明产品基于什么，写全称并附署名（Colvenna 系列索引 是名称与副标题的唯一来源）。2026-10-24 以前叫 “Cove Lake”，改名的原因与随后技术标识的改名见第 7 节。对外材料（[README](../README.md)、[发布说明模板](notes-template.md)）按下面的规则写：

- 每个 ASF 项目第一次出现、也是最显著的那一次，写全称 “Apache X”，并链接项目官网；之后可以简称。
- 平台分发的查询引擎镜像，写明它是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。
- 文末放商标署名。

用到的 ASF 项目与官网：[Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（Flink CDC 与 Flink Kubernetes Operator 是它的子项目）、[Apache Doris](https://doris.apache.org/)（Doris Operator 是它的子项目）、[Apache Kafka](https://kafka.apache.org/)、[Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/)、[Apache Airflow](https://airflow.apache.org/)、[Apache DolphinScheduler](https://dolphinscheduler.apache.org/)、[Apache Hudi](https://hudi.apache.org/)、[Apache Fluss](https://fluss.apache.org/)、[Apache Maven](https://maven.apache.org/)；[产品说明](../docs/product/press-release.md)另提到 [Apache Hive](https://hive.apache.org/) 与 [Apache Gravitino](https://gravitino.apache.org/)。它们都是 ASF 的顶级项目。Polaris 于 2026 年 2 月、Fluss 于 2026 年 7 月从孵化器毕业，所以不再写 “(Incubating)”。依据是 2026-10-24 读到的 [Incubator 项目列表](https://incubator.apache.org/projects/)，以及 [Polaris](https://incubator.apache.org/projects/polaris.html)、[Fluss](https://incubator.apache.org/projects/fluss.html) 的孵化记录。

README 的署名原文（英文）：

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset, Superset, Apache Airflow, Airflow, Apache DolphinScheduler, DolphinScheduler, Apache Hudi, Hudi, Apache Fluss, Fluss, Apache Maven and Maven are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

README 的署名还说明 Colvenna Lake 不是 ASF 的项目，也列出了其他各方的商标：

| 商标 | 所有者 | 依据 |
| --- | --- | --- |
| Kubernetes | The Linux Foundation | [Linux Foundation 的商标列表](https://www.linuxfoundation.org/legal/trademarks) |
| Amazon Web Services、AWS，以及 Amazon S3、Amazon EKS、Amazon RDS、Amazon Cognito 等服务名 | Amazon.com, Inc. 或其关联公司 | [AWS 商标指南](https://aws.amazon.com/trademark-guidelines/) |
| PostgreSQL、Postgres | PostgreSQL Community Association of Canada | [PostgreSQL 商标政策](https://www.postgresql.org/about/policies/trademarks/) |
| Oracle、Java、MySQL | Oracle 及其关联公司 | [Oracle 商标页](https://www.oracle.com/legal/trademarks.html) |
| MongoDB | MongoDB, Inc. | [MongoDB 商标使用指南](https://www.mongodb.com/legal/trademark-usage-guidelines) |

其余名字用一句 “Other names may be trademarks of their respective owners.” 概括。

## 2. 依据的政策

[ASF 商标 FAQ](https://www.apache.org/foundation/marks/faq/)：

- “Powered by” 的写法 “must include "Apache" before the product mark”。
- 用到 Apache 商标的地方要 “place prominent trademark attributions wherever you use Apache marks”，例如 “...are either registered trademarks or trademarks of the Apache Software Foundation in the United States and/or other countries”，再加上 “No endorsement by The Apache Software Foundation is implied by the use of these marks.”。网站上要链接项目官网。
- “If you distribute modified versions of Apache Software you must do so under a new name.”

[ASF 下游分发政策](https://www.apache.org/foundation/marks/downstream.html)：

- 沿用原名分发，要用完整的 “Apache ProjectName”，代码要与 ASF 的某个发布相同，或者只有政策列出的 “acceptable changes”。
- 不可接受的改动包括 “Applying fixes not currently in ASF source control” 与 “Adding features not currently in ASF source control”。
- 可接受的改动包括 “Including a range of 3rd party JDBC drivers or similar libraries”；ASF 自己的附加组件 “MAY be included”。
- 政策把 Docker 镜像列为下游分发包的例子。

## 3. 各镜像的判断

平台分发镜像有两种方式。一是发布仓库 `colvenna-lake-release/*`：别的账号经 `COLVENNA_RELEASE_PULL_ACCOUNTS` 获得拉取权限，用 `install.sh --release` 安装。二是从源码安装：查询引擎镜像从 `DORIS_IMAGE_SOURCE` 或发布仓库复制。所以下面的镜像都按“向用户分发”看待。

| 镜像 | 内容 | 是否修改了 ASF 软件 | 判断与名字 |
| --- | --- | --- | --- |
| 查询引擎 FE、BE（[`deploy/images/doris/`](../deploy/images/doris/README.md)） | Apache Doris 4.1.4 的官方 FE、BE 镜像（按 digest 固定）。自带的 Paimon 1.4.2 读取器（连同 Jindo 读取器）换成新读取器：用 Apache Doris 源码（提交 `ad35a140`）的几个源文件，对 Apache Paimon 2.0.0 的 jar 编译、合并而成。BE 自带的 `java-udf-jar-with-dependencies.jar` 被改写，去掉了其中的 Paimon SDK 类（`prepare-reader.py` 的 `prepare_shared_udf`，`be.Dockerfile` 的 `COPY reader/be-udf/`）。另加 PostgreSQL JDBC 驱动 42.7.8 | 是。读取器的源文件与 Paimon 2.0.0 都来自 ASF，但把它们组合进 Doris 4.1.4、替换自带的读取器并改写 BE 的 UDF jar，不是 ASF 发布过的 Doris，也不在政策列出的可接受改动之列。按保守的读法，这相当于 “Applying fixes not currently in ASF source control” | 修改版，改用新名字：仓库 `query-engine`（环境里 `colvenna-lake-<env>/query-engine`，发布仓库里 `colvenna-lake-release/query-engine`），清单的键 `query-engine-fe`、`query-engine-be`。文档写明它基于 Apache Doris 4.1.4、改了什么、不是 ASF 的发布、没有得到 ASF 的认可 |
| Flink 基础镜像（[`deploy/images/flink-base/`](../deploy/images/flink-base/Dockerfile)） | Docker Hub 上的官方 Apache Flink 1.20.4 镜像 `flink:1.20.4-scala_2.12-java11`（按 digest 固定），加 Apache Paimon 2.0.0 的 jar（`paimon-flink-1.20`、`paimon-s3`、`paimon-hadoop-uber`、`paimon-flink-action`）与 PostgreSQL JDBC 驱动 42.7.8。另用镜像自带的 `ENABLE_BUILT_IN_PLUGINS` 打开它自带的 S3 插件，并写明运行用户 | 否。Flink 的代码没有改；Paimon 的 jar 是 ASF 的附加组件（“MAY be included”）；JDBC 驱动属于 “Including a range of 3rd party JDBC drivers or similar libraries” | 可接受的改动，不改名：仓库仍是 `flink-base`。文档写明它基于官方 Apache Flink 1.20.4 镜像 |
| 第三方镜像（[`deploy/images/third-party.tsv`](../deploy/images/third-party.tsv)） | 上游镜像按 digest 原样复制进 `colvenna-lake-<env>/third-party` 与 `colvenna-lake-release/third-party`，tag 是 `<名字>-<digest 前缀>`。其中有 ASF 项目的镜像：`flink-kubernetes-operator`（Apache Flink Kubernetes Operator）、`doris-operator`（Apache Doris 的 Doris Operator，上游镜像 `apache/doris:operator-25.8.0`）、`polaris`、`polaris-admin-tool`（Apache Polaris）、`airflow`（Apache Airflow） | 否：没有改动的副本 | 不改名。名字就是上游镜像的名字，说明的是复制的是哪一个 |

其余的产品镜像本来就不用 ASF 的名字：

- `colvenna-jobs`：Flink 基础镜像加本项目的作业代码与 CDC 连接器；
- `colvenna-bi`：官方 Apache Superset 6.1.0 镜像加驱动与本项目的扩展；
- `test-readers`：只在 `--fixtures` 时安装，是官方 Apache Spark 镜像加 Iceberg 与 Paimon 的 jar。

文档里称它们 “built on Apache Superset” 等，用的是 ASF 项目的名字来说明软件本身。

## 4. 改了什么，没改什么

改了名字（镜像的内容与固定的 digest 都没有变，没有重新构建）：

- ECR 仓库：环境的 `<前缀>-<env>/doris` 改为 `<前缀>-<env>/query-engine`（[`foundation-stack.ts`](../deploy/aws/src/foundation-stack.ts)），发布仓库的 `<前缀>-release/doris` 改为 `<前缀>-release/query-engine`（[`release-stack.ts`](../deploy/aws/src/release-stack.ts)）。前缀当时还是产品的旧名，2026-10-25 起是 `colvenna-lake`（第 7 节）；
- 基础栈的输出 `DorisImageRepositoryUri` 改为 `QueryEngineImageRepositoryUri`；发布仓库栈的输出 `RepositoryDoris` 改为 `RepositoryQueryEngine`；
- 发布清单（schema 2）的键 `doris-fe`、`doris-be` 改为 `query-engine-fe`、`query-engine-be`。schema 2 的清单还没有发布过（`v1.0.0-rc.2` 是第一个），所以没有升 schema 版本；
- 文档里对这个镜像的称呼。

当时的 tag 都不含 Doris，改名时没有动它们：环境仓库里固定的 `4.1.4-paimon-2.0.0-fe`、`-be`，构建的 `build-<sha>-fe`、`-be`，发布仓库的 `<候选>-fe`、`-be`。后来为了让重新固定的 digest 有自己的 tag，环境仓库里固定的 tag 换成了 `4.1.4-paimon-2.0.0-fe-<digest 的前 12 位十六进制>`、`-be-<…>`（#278），同样不含 Doris。

下面这些是内部名字，不改。它们不是分发出去的产品名；Kubernetes 资源名按 #258 的边界也不在范围内。

- 设置与变量：`DORIS_IMAGE_SOURCE`、`DORIS_FE_DIGEST`、`DORIS_BE_DIGEST`、`DORIS_TAG`、`DORIS_REPOSITORY`；
- 安装步骤 `doris-image`、`doris`，`manifest.py` 的子命令 `doris-source`；
- 目录与脚本路径：`deploy/images/doris/`、`deploy/scripts/doris/`；
- Kubernetes 的名字：命名空间与集群 `colvenna-doris`、Doris Operator 的资源类型 `DorisCluster`（Doris Operator 本身是上游镜像的原样副本，见第 3 节的第三方镜像）；
- 测试名，BI 的方言，以及 IAM 角色等云资源的名字（例如 `colvenna-lake-<env>-doris`、`DorisEngineRoleArn`）。

文档用 “Doris” 说明这个软件是什么，属于对 ASF 项目名的说明性使用；不用它做镜像的名字。

## 5. 已有的仓库与 `v1.0.0-rc.1`

这一节记的是 #258 改名时（2026-10-24）怎样处理已有的仓库。仓库名的前缀当时还是产品的旧名，下面只写前缀之后的部分。

- **发布仓库。** 发布仓库栈的仓库都设成删栈保留（RETAIN）。改名以后重新部署这个栈，`doris` 仓库离开栈，但仍留在账号里，原有的拉取策略也一并保留。它存着 `v1.0.0-rc.1` 的 FE 与 BE（digest 与现在固定的相同）。rc.1 的 tag 与清单（schema 1，键 `doris-fe`、`doris-be`，仓库 `doris`）都是历史记录，不改；rc.1 只能用它自己 tag 上的工具安装。
- **默认来源。** `manifest.py doris-source` 只认键为 `query-engine-fe`、`query-engine-be` 的清单（schema 2），不把 rc.1 的清单当作默认来源。
- **已有的环境。** 升级时 `foundation` 一步把环境的 `doris` 仓库换成 `query-engine`：CloudFormation 先建新仓库，再清空并删除旧仓库。所以升级时第一次运行要给一个在 `foundation` 之后仍然存在的来源，本环境自己的旧仓库在开始时就被拒绝；`doris` 一步随后让 FE 与 BE 各滚动重启一次：digest 不变，但镜像引用换了仓库。
- **之后。** 2026-10-25 技术标识也改了名（第 7 节，#271）。按 #271 的计划，旧名字的环境全部拆除，留着 rc.1 镜像的旧 `doris` 仓库删除；发布仓库换成新名字的 `colvenna-lake-release/*`，固定的查询引擎镜像与第三方镜像按 digest 复制过去。rc.1 与 rc.2 的清单写的是旧名字的仓库，本仓库的工具不再按它们安装。没有环境还带着旧名字，上面这些升级时的处理（拒绝本环境的旧仓库、UAT-DEP-06 把仓库改名算作预期）也随之删除。

## 6. 宣传视频与 GIF

README 嵌入的英文宣传片是 #209 用 html-to-explainer-video 做的，工程不在本仓库里。旧版的 GIF 与 MP4（文件名也是旧名）还是旧名、旧标志与旧仓库地址，片中第一次出现的 Apache 项目没有写全称，也没有商标署名，不符合第 1 节，2026-10-24 从 README 撤下。

2026-10-25 按新名字重新渲染，旧文件删除，换成 `assets/video/colvenna-lake-promo.gif`（README 嵌入，无声，带字幕）与它链接的 `assets/video/colvenna-lake-promo.mp4`（带配音）。结构、场景、画面风格与片中的数字不变，只改了改名与第 1 节要求的地方：

- 开头与结尾换成系列的主标识、Colvenna Lake 字标与副标题；字幕与配音里的产品名改为 Colvenna Lake，结尾的仓库地址改为改名后的地址。
- 字幕、配音与画面里，每个 ASF 项目第一次出现都写全称：Apache Kafka、Apache Paimon、Apache Flink、Apache Iceberg、Apache Spark；看板写成 “dashboards on a query engine based on Apache Doris”。Trino 不是 ASF 的项目，照原样写。
- 片尾在配音结束后停留约 2.5 秒，放 ASF 商标署名：第 1 节的原文，只列片中出现的 Apache、Apache Paimon、Apache Flink、Apache Doris、Apache Kafka、Apache Iceberg、Apache Spark 及其简称，后接 “Other names may be trademarks of their respective owners.”。
- 技术标识按渲染时的代码出现：YAML 的 API 组，Operator、catalog 与 CLI 的名字，安装命令的设置与步骤名（其中有 `doris`）。这次渲染在技术标识改名（第 7 节）之前，片中的这些标识还是旧前缀，所以同一天又重新渲染了一次（见下）。
- 全片约 80 秒，原来是 72 秒，多出来的是 Apache 全称与片尾的停留。

2026-10-25 又为 #271 重新渲染了一次：技术标识按第 7 节改名，片中的标识与现在的代码一致。结构、场景、画面风格、片中的数字、Apache 全称与片尾的商标署名都不变，只改了这些标识：

- YAML 的 API 组是 `colvenna-lake.io/v1alpha1`；Operator 是 `colvenna-operator`，catalog 是 `colvenna-catalog`（画面、字幕与配音），CLI 是 `colvenna`。
- 安装命令的设置是 `COLVENNA_ENV`、`COLVENNA_REGION`；步骤名照 `deploy/install.sh --list`，改名后没有变。
- 只有说到 `colvenna-catalog` 的那一句重新配音，用的是同一个声音；其余各句不变。全片约 81 秒，多出来的是这一句；片尾照旧在配音结束后停留约 2.5 秒。

2026-10-30 为 #300 只重新渲染了结尾一场：结尾的仓库地址改为公开仓库的 `github.com/colvenna/colvenna-lake`（#271）。同一工程、同一渲染通道，字体、字号、颜色与位置不变；配音、字幕、其余各场与片长（约 81 秒）都不变。

## 7. 产品名：从 Cove Lake 到 Colvenna Lake

**Colvenna 的商标状态：请求者决定先行采用，律师的完整检索与商标申请没有执行，记为 `WAIVED_BY_REQUESTER_NOT_PASS`，不算通过。它不作为 v1.0 的发布门槛（请求者的决定，colvenna-for-clickhouse#30），仍建议尽早完成，尤其是中国第 9、42 类的申请。**

- **改名。** 2026-10-24，系列主品牌由 Cove 改为 Colvenna（col，列；拉丁语 vena，脉、水流；colvenna-for-clickhouse#26），本产品由 Cove Lake 改为 Colvenna Lake。GitHub 仓库随之改名为 `colvenna-lake`（当时在早期的私有仓库下；v1.0 之前迁到 `colvenna/colvenna-lake`，#271）。标志换成系列的主标识（colvenna-for-clickhouse#28），见[品牌资产](../assets/brand/README.md)。
- **放弃 Cove 的原因。** 系列的初筛（colvenna-for-clickhouse#25）发现，“Cove” 在美国、欧盟、英国和中国都有高风险的在先商标：美国的 KOVE（数据存储与备份），以及 N-able 在用的 Cove Data Protection（云备份）；欧盟与英国有 KAHA 的 COVE（SaaS、云计算）；德国有 COVE（服务器软件、SaaS）；中国第 9、42 类有在先的 COVE 注册。
- **本仓库此前的初步检索**（2026-10-24，不是商标检索结论）与此一致：USPTO 里没有 “COVE LAKE”“COVELAKE”，但含 COVE 的有效商标在第 9、42 类有 58 件，其中有 KAHA Pte. Ltd. 的 COVE（第 42 类，软件开发）、Cove Smart, LLC 的 COVE（安防设备监控软件）与仍在审查中的 DATACOVE（第 42 类，数据收集平台与交互式数据库的 SaaS）；N-able 的 Cove Data Protection 在用而未在 USPTO 注册。WIPO、EUIPO/TMview 与中国国家知识产权局没能查。
- **Colvenna 的初筛**（系列的初筛，colvenna-for-clickhouse#25，2026-10-24）：USPTO 的有效商标里没有同名，也没有常见的拼写或读音变体；最接近的 COLVEN 用于车辆监控通信，领域不同。EUIPO/TMview、WIPO、中国国家知识产权局与中文译名都没有查（`NOT_RUN`）。初筛不代替律师检索。

**技术标识也改名。** 2026-10-24 的改名（#258）只处理品牌与分发命名，技术标识当时保留了旧前缀。2026-10-25 维护者决定，项目里不再出现旧名（#271）：上面的在先商标风险同样落在用户每天看到、输入的名字上，CLI 命令、Kubernetes 的 API 组、镜像与 chart、AWS 资源与设置的名字都是。于是技术标识按同一映射全部改名，功能不变：前缀 `cove`、`cove-lake`、`covelake`、`Cove`、`COVE_` 与 Java 包 `com.covelake` 分别改为 `colvenna`、`colvenna-lake`、`colvennalake`、`Colvenna`、`COLVENNA_` 与 `com.colvenna.lake`。这包括组件、目录、镜像、chart、命名空间与 CLI（`colvenna-operator`、`colvenna-catalog`、`colvenna` 等），API 组与标签 `colvenna-lake.io`，栈 `ColvennaLake<Env>…` 与 `ColvennaLakeRelease`，资源前缀 `colvenna-lake-<env>` 与 `colvenna-lake-release`，标签 `Project=colvenna-lake`，`COLVENNA_*` 设置，库、表、角色与指标的前缀 `colvenna_`，Grafana 看板的 uid、OpenLineage 的运行 facet `ColvennaLakeRunFacet` 与 BI 用户的合成邮箱域 `users.colvenna-lake.invalid`。资源名改了，云上与集群里的对象就是新的：旧名字的部署拆除后从零重装，不做迁移（开发阶段不保持升级兼容）。

**colvenna-catalog 成为独立产品。** 之后 colvenna-catalog 移到同一系列的独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog)，本仓库改用它的发布版（#295）。服务端的 Java 包是它自己的 `com.colvenna.catalog`，本仓库不再有 `com.colvenna.lake.catalog`；镜像、chart、命名空间、服务账号、指标 `colvenna_catalog_*` 与设置 `COLVENNA_*` 的名字都不变，所以已装的环境原地接管。

历史记录保持原样，仍是当时的名字：`docs/history/`，以及改名之前的候选（`v1.0.0-rc.2` 与更早的）的 UAT 报告、性能报告与 `release/` 下的记录。

> 注：本文中的 issue 与 PR 编号（如 #258）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
