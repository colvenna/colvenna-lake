---
title: "M0 设计退出核对"
date: 2026-09-28
status: design-baseline
---

# M0 设计退出核对

M0 是**设计收敛**，验收对象是版本证据、明确的产品边界与可执行的后续关口。下表不声称代码、AWS 环境或端到端性能已验证；M1/M2/M3 的运行结果仍为 `NOT_RUN`。当版本或部署目标改变时，要重审对应矩阵和验收契约。

| Issue 与逐项接收条件 | 当前设计证据 | 后续运行关口 |
| --- | --- | --- |
| #1 版本：固定发布版、标记 master-only、M1 JDBC/M2 REST 前提、Doris 与 StarRocks 取舍 | [能力矩阵](../../research/version-capability.md)给出发布标签提交、Doris 4.1.4 内置 Paimon 1.4.2、Paimon 2.0.0 没有 Management API、`data-token.enabled` 默认关闭；[HLD 4.3/6.4/10.1/17](../../design/hld-realtime-lakehouse.md)与 [README](../../../README.md)采用同一候选。 | M1 在真实 S3 对账 JDBC + DV；M2 验 REST bearer、原子提交、`/token` 与 S3 拒绝；失败时不以 1.4.2 或 StarRocks 自动替代通过。 |
| #2 身份与授权：每个入口的身份、执行点、旁路、表/列/行分界及拒绝场景 | [验收契约第 1 节](../../design/acceptance-contract.md#1-身份授权和绕过路径)逐一列 Flink、Spark、Doris、S3、Iceberg、Trino，包含跨表读取、越权列/行与替代入口；[HLD 12.1–12.2](../../design/hld-realtime-lakehouse.md)和 [FAQ](../../product/press-release.md)明确凭证只是对象路径隔离。 | M2 每个客户端和每个开放读入口独立作正反测试；未验证的敏感入口关闭。 |
| #3 SLO 与恢复：路径起止、负载、p95/p99、1 分钟目标修订、正确性与 RPO/RTO、故障注入 | [验收契约第 2 节](../../design/acceptance-contract.md#2-延迟正确性和故障目标)设源端→ODS、层间、外表、MV 与整链路的起止/稳态负载/目标，解释 1 分钟改为 5 分钟的预算并定义积压及提交结果未知；[HLD 16](../../design/hld-realtime-lakehouse.md)列注入。 | M1 按样本数与故障矩阵实测；样本不足或恢复后数据未收敛不得报通过。 |
| #4 回刷：自动传播与显式重算、同分区流批冲突、更新/删除/重放/重复执行 | [验收契约第 3 节](../../design/acceptance-contract.md#3-历史更正与分层回刷)定义来源版本、围栏、依赖图、逐层快照/聚合核对与完成门；[HLD 9/16](../../design/hld-realtime-lakehouse.md)不再把 `OVERWRITE` 当作自动撤回流。 | M1/M3 注入失败、并发和重复执行，对账 ODS/DWD/DWS/Doris，遗漏下游时不得发完成信号。 |
| #5 删除：副本清单、责任/保留上限、逻辑与物理证据、失败处置 | [验收契约第 4 节](../../design/acceptance-contract.md#4-合规删除的副本范围与完成证据)覆盖 Paimon、Flink 状态、Doris、Iceberg、S3 版本/备份及外部回执，给出受限准入的 24 小时/9 天设计目标、完成证据和阻断/逾期状态；[HLD 12.4](../../design/hld-realtime-lakehouse.md)、[README](../../../README.md)、[FAQ](../../product/press-release.md)不再用保留期公式冒充删除 SLA。 | M1/M3 按每份受管副本实测并取证，无法遍历或外部未回执时只能报告已证范围。 |

设计结论不会把 `NOT_RUN` 改写为 `PASS`。本仓库当前没有实现、部署或客户验收材料，M0 退出只表示上述设计决策已经落入仓库并可据此执行后续里程碑。

> 注：本文中的 issue 与 PR 编号（如 #1）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
