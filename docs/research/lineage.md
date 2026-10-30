---
title: "血缘：谁来发事件，交给哪个目录"
date: 2026-10-17
status: decided
issue: 37
---

# 血缘：谁来发事件，交给哪个目录

本文记录 #37 开工前的调研结论，设计见 [colvenna-operator LLD](../design/colvenna-operator-lld.md) 的"血缘"一节，验收见 [M4 血缘与目录发现](../history/m4/lineage.md)。Flink 以 1.20.4 为准，Paimon 以 2.0.0 发布源码为准，Doris 以 4.1.4 为准，OpenLineage 以 1.54.0（规范 2-0-2）为准，Marquez 以 0.51.1 为准。标为"实测"的结论来自 2026-10-17 在 PoC 集群上的手工实验。

## 结论

1. **Flink 1.20 运行时不产出血缘。**
   - 1.20 只有 FLIP-314 的接口类（[FLINK-33210](https://issues.apache.org/jira/browse/FLINK-33210)）。由 StreamGraph 构图（`StreamGraph.getLineageGraph()`）和作业状态监听器 `JobStatusChangedListener`（[FLINK-33212](https://issues.apache.org/jira/browse/FLINK-33212)）都在 2.0 才有：`flink-core/.../JobStatusChangedListener.java` 在 `release-1.20.4` 不存在，在 `release-2.0.0` 存在。
   - Paimon 2.0.0 的文档写明 "Data Lineage support is available only for Flink 2.0 and above"（`docs/docs/flink/lineage.md`）。即使在 2.x 上，CDC 多表 sink 的末端也是普通的 `DiscardingSink`，不产出 sink 血缘（`paimon-flink-cdc/.../FlinkCdcMultiTableSink.java`）；colvenna-jobs 的 CDC 作业正是 COMBINED 多表模式。
   - OpenLineage 的 Flink 1.x 集成"does not support Flink SQL"，也不认 Paimon 和 Flink CDC（[openlineage flink1](https://openlineage.io/docs/integrations/flink/flink1)）。
2. **Doris 的 LineagePlugin 不够用。** 4.0.6 起有这个 SPI，但"Doris does not ship a built-in sink"，投递"is asynchronous and best effort"；它不为 SELECT、视图、UPDATE、DELETE 和导入发事件（[data-lineage](https://github.com/apache/doris-website/blob/master/versioned_docs/version-4.x/data-governance/data-lineage.md)）。看板的外表视图因此没有事件。
3. **所以由 Operator 发。** 它知道每个作业读写哪些表（源的准入表、planner 校验找到的读表、看板查询点名的声明表），知道作业在跑哪个版本（FlinkDeployment 的 spec 哈希与 Flink job id），也能从 colvenna-catalog 读到每张表的列。事件按 OpenLineage 2-0-2 的 RunEvent 发（[spec](https://openlineage.io/spec/2-0-2/OpenLineage.json)），facet 取 1.54.0 的版本。
4. **命名。** OpenLineage 的命名表里有 MySQL、PostgreSQL、Kafka、S3，没有 Paimon、Doris、MongoDB（[naming](https://openlineage.io/docs/spec/naming)）。湖表用 `paimon://<colvenna-catalog>` 下的 `colvenna.<库>.<表>`，与 Flink SQL 的标识一致；Doris 对象用 `doris://<FE>:9030` 下的 `internal.<库>.<对象>`；MongoDB 用 `mongodb://host:port` 下的 `库.集合`。
5. **目录：验收用 Marquez 0.51.1。** 它是 OpenLineage 的参考实现，接受 RunEvent（`OpenLineageResource.java`），并按 `sourceCodeLocation.url` 给作业分版本（`marquez/common/Utils.java` 的 `newJobVersionFor`）。它没有认证（[MarquezProject/marquez#2947](https://github.com/MarquezProject/marquez/issues/2947)），所以只能在集群内、经 NetworkPolicy 访问。DataHub 与 OpenMetadata 也有 OpenLineage 端点，带 token 认证，但没有验收：
   - DataHub 只解析 RunEvent，平台名取 namespace 的 scheme（[datahub openlineage](https://docs.datahub.com/docs/lineage/openlineage)）；
   - OpenMetadata 默认只处理 COMPLETE 事件，要求库和 schema 的实体预先存在，也不更新已有表的 schema（`OpenLineageResource.java`、`OpenLineageEntityResolver.java`，tag `2.0.5-release`）。

## 实测（2026-10-17，PoC 集群，Marquez 0.51.1）

| 检查 | 结果 |
| --- | --- |
| `sourceCodeLocation.url` 为 `k8s://…` | 作业的 location 为空，版本不随它变化 |
| `sourceCodeLocation.url` 为 `https://…` | 保留；换 URL 即新版本 |
| 带 `symlinks`（S3 位置）的输出数据集 | 位置被当作作业的另一个输出数据集 |
| STREAMING 作业：START v1、不带数据集的 COMPLETE v1、START v2 | 两个版本；v1 的运行 `COMPLETED`，v2 的 `RUNNING`；作业的输入输出保留 |
| `jobType` facet 不带 `jobType` 字段 | 422：`job.facets.jobType.jobType must not be null`（规范里它是可选的） |
| 同一作业两次运行的事件时间 | 最新运行取事件时间最晚的那次 |
| 数据集的列 | 按名字与类型保存，不保存顺序 |

> 注：本文中的 issue 与 PR 编号（如 #37）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
