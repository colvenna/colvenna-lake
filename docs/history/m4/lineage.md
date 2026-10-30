---
title: "M4 血缘与目录发现"
date: 2026-10-17
status: complete
issue: 37
---

# M4 血缘与目录发现

本页记录 Issue #37 的验收：平台把受支持的作业、表和它们之间的读写关系交给外部的元数据与血缘系统。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的"血缘"一节，依据 HLD 12.6–12.7。Flink 1.20 运行时不产出血缘，Doris 的 LineagePlugin 没有自带输出端、也看不到视图，所以由 Operator 依据声明、planner 的校验结果和 cove-catalog 的 schema 发 OpenLineage 事件；外部目录由用户选择，验收用 Marquez 0.51.1（OpenLineage 的参考实现）。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源库是 M1 的 RDS MySQL 8.0，看板在平台的 Doris 上（#32）。第二朵云未验证（#33 未开展）。

**证据的来源版本**：结果来自提交 `dcb5ac0`，私有证据目录 `m4-37/run-l5-dcb5ac0/`。镜像：Operator `cove-operator@sha256:6b9b51b7…`，作业 `cove-jobs@sha256:e7dd9d8c…`（mysql）与 `cove-jobs@sha256:df3251b3…`（sql）；Marquez `marquezproject/marquez:0.51.1@sha256:0721c976…` 与 `marquez-web:0.51.1@sha256:7312112c…`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 从输入源到 ODS、DWD、DWS 的表级路径可追踪并对应真实作业版本 | 通过 | Marquez 里从 MySQL 的 `orders` 经 ODS、DWD、DWS 到 Doris 视图的每一跳都是那个作业当前的输入和输出；三个 Flink 作业的当前版本都等于各自 FlinkDeployment 的 spec 哈希，最新运行都是 deployment 正在跑的 Flink job；改 DWD 的声明后出现新版本，旧版本的运行 `COMPLETED`；回刷是一次 `COMPLETED` 的 BATCH 运行 |
| 事件缺失或不受支持的操作在覆盖矩阵中可见 | 通过 | 覆盖矩阵（LLD "血缘"）逐项写明上报与否和验证程度；压缩与即席查询前后，Marquez 的作业和从 `orders` 出发的图节点不变；运行时暂缺的（过渡中的作业、读不到列的表、被拒收的事件、失败的上报）有指标和告警 |
| 外部目录展示的表标识、schema 与权威目录一致 | 通过 | 四张湖表在 Marquez 的标识是 `paimon://cove-catalog.cove-catalog.svc:8181` 下的 `cove.<库>.<表>`，字段的名字与类型与 cove-catalog 返回的表一致；源库加列后，31 s 内 Marquez 也有了这一列 |

## 运行

`infra/m4/scripts/install-marquez.sh` 装 Marquez（库在项目的 RDS 上，只有 ClusterIP 服务，NetworkPolicy 只放行 Operator 的命名空间），`infra/m3/scripts/install-operator.sh` 让 Operator 向它上报。`infra/m4/flows/lineage/run-lineage.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL，所有判断都读 Marquez 的 API：

1. **管道。** MySQL 库里有 `orders`（500 行）和 `segments`（50 行）；`LakeSource` 把它们接入 ODS；DWD 以 lookup join 关联两者，DWS 按 segment 聚合 DWD；看板以 Doris 外表视图服务 DWS。
2. **路径。** 从 MySQL 的 `orders` 经源作业、ODS、DWD 作业、DWD、DWS 作业、DWS、看板查询，到 Doris 的视图，每一跳都是那个作业当前的输入和输出，血缘图从起点到得了终点；`segments` 经 lookup 到 DWD 也一样。
3. **版本。** 每个 Flink 作业在 Marquez 的当前版本就是它的 FlinkDeployment 的 spec 哈希，最新的运行是 `RUNNING`，运行 facet 里的 Flink job id 就是 deployment 正在跑的那个。
4. **schema。** 四张湖表在 Marquez 的字段，名字与类型都与 cove-catalog 返回的表一致（Marquez 不保存列的顺序，返回的顺序不固定；事件里的 `ordinal_position` 是 cove-catalog 的顺序）；源库给 `orders` 加一列之后，cove-catalog 的 ODS 表多了这一列，Marquez 随后也一致。
5. **新版本。** 改 DWD 的 checkpoint 间隔：deployment 换了 spec 哈希，Marquez 有了新版本，旧版本的运行 `COMPLETED`，新的 `RUNNING`。
6. **回刷。** 对 DWD 回刷：Marquez 有一个 BATCH 作业 `LakeTask.<名字>`，读 ODS 的两张表、写 DWD，运行 `COMPLETED`；DWD 恢复后，它的运行又是 deployment 当前的 Flink job。
7. **不是血缘的操作。** 一次压缩和一次即席查询（`SqlQuery`）之后，Marquez 的作业列表和从 `orders` 出发的血缘图节点都没有变化。
8. **重启。** 删掉 Operator 的 Pod：新的 leader 不重复开始已有的运行。Operator 的指标里没有上报失败。
9. **没有匿名入口。** Marquez 只有 ClusterIP 服务、没有 Ingress；没有标签的命名空间里的 Pod 连不上 API、Web UI 和 admin 端口，Operator 的命名空间连得上 API；不带凭证经 API server 代理访问被拒。
10. **删除。** 删掉看板、分层表和源：它们的作业在 Marquez 里都没有 `RUNNING` 的运行。

## 结果

31 项检查全部通过（`verdicts.tsv`）。

| 作业 | Marquez 的当前版本 | FlinkDeployment 的 spec 哈希 | 最新运行的 Flink job | deployment 的 Flink job |
| --- | --- | --- | --- | --- |
| `ll-l5-cdc`（源） | `dcb17bc6a6f62301` | `dcb17bc6a6f62301` | `bd95b4b5…` | `bd95b4b5…` |
| `lwd-l5-table`（DWD） | `f310c4d805221895` | `f310c4d805221895` | `3ef69808…` | `3ef69808…` |
| `lws-l5-table`（DWS） | `558879e139d39d12` | `558879e139d39d12` | `e5198e01…` | `e5198e01…` |
| `lwd-l5-table` 改声明之后 | `4fc9037304e527f9` | `4fc9037304e527f9` | `10c03ec0…` | `10c03ec0…` |
| `lwd-l5-table` 回刷之后恢复 | `4fc9037304e527f9` | `4fc9037304e527f9` | `212f805b…` | `212f805b…` |

| 检查 | 结果 |
| --- | --- |
| 路径 | MySQL `m4l_l5.orders` → `ll-l5-cdc` → `cove.ods_m4l_l5.orders` → `lwd-l5-table` → `cove.dwd_m4l_l5.orders` → `lws-l5-table` → `cove.dws_m4l_l5.segments` → `LakeDashboard.ld-l5.by_segment` → `internal.<看板库>.by_segment`；`segments` 经 lookup 到 DWD；36 s 内全部出现 |
| 旧版本 | DWD 的运行：`RUNNING 4fc9…`、`COMPLETED f310…` |
| 回刷 | `LakeTask.bf-l5`，BATCH，读 ODS 的两张表、写 DWD，`COMPLETED` |
| 重启 | 删掉 Operator 的 Pod 前后，DWS 的运行列表相同，只有一个 `RUNNING` |
| 指标 | `cove_lineage_failures` 与 `cove_lineage_events_rejected` 为 0 |
| 删除 | 删掉看板、分层表和源之后，四个作业都没有 `RUNNING` 的运行 |

## 覆盖矩阵

见 LLD "血缘"一节的覆盖矩阵；这次运行实测了其中的源到 ODS、分层表、回刷、看板视图，以及压缩与即席查询不上报。

## 过程中的发现

- l1：Marquez 要求 `jobType` facet 带 `jobType` 字段（规范里它是可选的），结束事件没带，被 422 拒收；被拒的事件又挡住了它后面的所有事件。改为结束事件带上作业的类型；被判为格式错误的事件计数、记日志后跳过。
- l2：l1 版本的 Operator 写的上报记录换了格式，新版本读不出来，每一轮都报错，一个事件也没有发出。改为读不出就从头开始（当前的运行以原来的 id 重新开始），未知字段忽略。l1 留下的几个运行在 Marquez 里一直是 `RUNNING`，与这次的作业无关。
- l3：DWD 升级后，Marquez 显示的最新运行是旧版本那次：旧运行的结束事件时间是"现在"，晚于新运行的开始（Flink job 的启动时刻），而 Marquez 以事件时间最晚的运行为最新。改为旧运行在新运行开始的时刻结束。
- l4：Marquez 返回字段的顺序不固定（l3 时一致，l4 时不一致），它不保存列的顺序。schema 的比较改为按名字与类型，顺序以事件里的 `ordinal_position` 为准（单元测试）。
- 实验：`sourceCodeLocation.url` 不是 http(s) 时 Marquez 丢弃它，作业的版本就分不出来；S3 位置的 `symlinks` 被当成另一个输出数据集。改为指向 Kubernetes API 中的对象（带版本），不发 `symlinks`。
- 评审：认证失败与地址错误（401、403、404）原先也被当作永久拒收，API key 过期就会永久丢事件，改为重试；SQL facet 原先取声明的查询，改为取部署里运行的那份；回刷后来的尝试原先沿用第一次的开始时间。

## 限制

- 血缘是 Operator 依据声明和 planner 校验发的，不是运行时观测：它说明作业被允许、被验证读写哪些表，不证明每条记录的去向。
- 只在 EKS 上验收了 MySQL 源、分层表、回刷与看板的外表视图；PostgreSQL、MongoDB、Kafka 源与物化视图、内表只有单元测试（见覆盖矩阵）。删除（`LakeErasure`）没有在这次运行中执行：Operator 没有为它发事件的代码路径。
- 只验收了 Marquez。DataHub、OpenMetadata 接收同样的 OpenLineage 事件（Operator 以 API key 认证），未经验收；OpenMetadata 默认只处理 COMPLETE 事件、要求库和 schema 实体预先存在，流作业要改它的设置。
- Marquez 没有认证：它只在集群内、经 NetworkPolicy 可达，人经由 `kubectl port-forward`（IAM 认证）访问。带标签的命名空间（只有 Operator 的）里的任何 Pod 都能不带凭证写入它。要对外开放 UI，需要在前面加认证代理。
- 源表、Kafka topic 和 Doris 对象不带列；物化视图与内表的每次刷新不单独上报。
- 列级血缘、即席查询、删除、批量质量校验、维护任务、Iceberg 发布不上报（见覆盖矩阵）。
- 第二朵云未验证（#33 未开展）。

> 注：本文中的 issue 与 PR 编号（如 #37）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
