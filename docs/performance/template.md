---
title: "发布候选的性能文档模板"
date: 2026-10-22
status: template
issue: 207
---

# 发布候选的性能文档模板

每个发布候选在 `docs/performance/<候选>.md` 写一份独立的性能文档，例如 `docs/performance/v1.0.0-rc.5.md`。方法与运行方式见[性能测试](../uat/performance.md)第 1 节。复制本模板，把尖括号里的内容换成实测的值；空着或写“待补”的项，文档就不算完成。

下面从“# <候选> 的性能验收”起是模板正文。

---

```markdown
---
title: "<候选> 的性能验收"
date: <执行日期>
status: complete
issue: 207
---

# <候选> 的性能验收

## 1. 结论

<PASS、NOT_JUDGED 或 FAIL，即 release.md 的结论，一句话说明依据。NOT_JUDGED 与 FAIL 时，写明哪一行、哪一项检查，差多少。>

本结论只适用于默认规模（写入 t3.xlarge、查询 r6i.xlarge 各 2 台）、下面这档负载与稳态。积压期不在范围内（第 8 节）。它不是容量上限。

## 2. 被测的版本

| 项 | 值 |
| --- | --- |
| 候选 | <候选>，tag 指向 <完整 SHA> |
| 镜像 | 见发布清单 `release/<候选>/manifest.json`；本次运行的 `environment.txt` 与之逐项一致：<是/否> |
| 测试的提交 | <run-release.sh 所在的提交>；`IMAGES_SHA` <值> |
| 生产者与观测器 | `performance-tools.jar` SHA-256 <值> |

## 3. 环境

| 项 | 值 |
| --- | --- |
| 账号与区域 | <账号>、<区域>，环境 <COLVENNA_ENV> |
| EKS | <版本>，节点：<每个节点的规格、角色与可用区> |
| 元数据库 | RDS PostgreSQL <版本>，<实例规格> |
| 测试源 | RDS MySQL <版本>，<实例规格>，binlog <格式> |
| Kafka | 开发用 Kafka <版本>，单 broker，<存储> |
| Doris | <FE、BE 的数量与镜像 digest> |
| 作业资源 | 源与表：<DASHBOARDS_JOB 或默认>；事件源：<EVENTS_JOB 或默认> |
| 执行时间 | <开始 UTC> 至 <结束 UTC>；负载窗口 <起> 至 <止>，排空 <DRAIN_MINUTES> 分钟 |

## 4. 场景、方法与目标

| 负载 | 值 |
| --- | --- |
| MySQL CDC | 一张表，<CDC_RATE> 变更/秒：变更负载 <值>、看板运行的标记行 10、版本标记 3 × <VERSION_RATE> |
| 版本标记 | 每秒 <VERSION_RATE> 个键，每个插入、1 分钟后更新、2 分钟后删除；窗口内每种约 <值> 个 |
| Kafka 事件 | 一张表，<KAFKA_RATE> 条/秒，每条 <EVENT_BYTES> 字节，<分区数> 个分区 |
| 看板 | 每秒 10 次固定看板查询、5 次内表点查、1 次交互点查；中段 <BURST_MINUTES> 分钟突发 |
| 时长 | <MINUTES> 分钟，在 3 分钟预热之后 |

方法按 [性能测试第 1.3 节](https://github.com/colvenna/colvenna-lake/blob/<测试的提交>/docs/uat/performance.md#13-方法)（固定到本次测试的提交），摘要：

- 每条路径按插入、更新、删除分别统计，各要至少 10,000 个有效样本，不足的只观测；
- ODS、DWD 逐键核对版本、校验和与删除；DWS 与 Doris 按前缀判定，删除要等聚合正确撤减；
- 稳态逐层判定：积压由版本标记与 Kafka 回执各自的确认与首次反映的时刻算出，不取样；窗口后三分之一的积压 p95 不比前三分之一高出 0.5 分钟以上，整个窗口的最大积压不超过从源端到这一层的路径的 p99 目标（DWD、DWS、ODS 上的物化视图没有这样的路径，不设上限）；
- DWS 与 Doris 的路径按设计三种变更的延迟相同；ODS、DWD 逐键量，同一时刻的三个操作通常落在同一个快照里；
- 每个样本比较的两个时钟，误差上限之和不超过 1 秒。

目标：[验收契约 2.1 节](../design/acceptance-contract.md#21-测量协议)的稳态 p95 / p99。

## 5. 结果

<贴入 release.md 的两张表，原样。>

| 路径 | 起点 → 终点 | 样本 | 作废 | p50 | p95 | p99 | 最大 | 目标 p95 / p99 | 终点稳态 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| <每条路径的插入、更新、删除各一行；看板查询各一行，只观测的标 OBSERVED> | <…> | <…> | <…> | <…> | <…> | <…> | <…> | <…> | <…> | <…> |

| 检查 | 结果 | 数值 |
| --- | --- | --- |
| MySQL 负载速率（整个窗口） | <…> | <…> |
| Kafka 负载速率（整个窗口） | <…> | <…> |
| 看板负载按时发出 | <…> | <每一组发出的查询数、晚于应发出 1 秒以上的个数、最晚多少 ms> |
| 看板查询没有出错 | <…> | <出错与没有发出的个数> |
| 稳态：<每一层> | <…> | <前后三分之一的 p95、整个窗口的最大积压与它的上限；10 秒格点的斜率（仅观测）> |
| 正确性（版本、校验和、删除、前缀） | <…> | <各层的发现数> |
| 事件恰好一次 | <…> | <重复、缺失> |
| 观测器完整（layers.json、events.json） | <…> | <…> |
| 时钟（layers.json、events.json） | <…> | <每组的测量次数、最大误差、未同步次数> |
| 看板运行的功能检查 | <…> | <通过数 / 总数> |
| 各运行的退出与清理 | <…> | <statuses.json> |

## 6. 原始数据

私有证据目录 `<路径>`（访问受控，不含口令或密钥）：

- `release.md`、`release.json`、`layers.json`、`events.json`、`statuses.json`；
- `dashboards/doris/{marks.log,load.log,load.json}`、`dashboards/verdicts.tsv`；
- `dashboards/release/{versions.log,doris.log,observed.jsonl,clocks.tsv,cdc-load.log}`；
- `events/{producer.log,observed.jsonl,clocks.tsv}`；
- `environment.txt`、`run.log`、`timeline.tsv`。

## 7. 历史基线的适用性

引用开发阶段的结论时，逐项写明当时的版本与配置、与本候选的差异，以及为什么仍然适用或需要补测。不能说明的，写“不适用”，不引用。前两列的每个值后面写了出处；没有记录的写“未记录”。

| 项 | M1 容量基线（2026-10-11–12） | M3 看板服务（2026-10-16，`7765092`） | 本候选 | 影响 |
| --- | --- | --- | --- | --- |
| Flink / Paimon | 1.20.4 / 2.0.0（[基线报告](../history/m1/capacity-baseline.md)“环境与版本”） | 1.20.4 / 2.0.0（`7765092` 的 `colvenna-jobs/pom.xml`） | <…> | <…> |
| MySQL CDC 连接器 | Flink CDC 3.5.0（基线报告“环境与版本”） | 3.7.0-1.20（`7765092` 的 `infra/m3/scripts/build-images.sh`） | <…> | <…> |
| Catalog | JDBC catalog，RDS PostgreSQL 16 db.t4g.small（基线报告“环境与版本”） | colvenna-catalog REST（[M3 看板报告](../history/m3/dashboards.md)：看板经 colvenna-catalog 申请授权、token） | <…> | <…> |
| 测试 MySQL | 8.0.46，db.t4g.small（基线报告“环境与版本”） | 8.0.46，db.t4g.small（`7765092` 的 `infra/m1/src/mysql-source-stack.ts` 及其测试） | <…> | <…> |
| EKS | 1.35（基线报告“环境与版本”） | 1.35（`7765092` 的 `infra/m1/src/stack.ts`） | <…> | <…> |
| 节点 | 负载 r6i.4xlarge、写入 t3.xlarge、查询 r6i.xlarge、Doris m6i.4xlarge（基线报告“环境与版本”） | 查询 r6i.xlarge ×2，Doris 在上面（M3 看板报告开头）；写入 t3.xlarge，台数未记录 | <…> | <…> |
| 作业 | 共享 CDC 作业，并行度 1；DWD、DWS write-only，由独立 compaction 作业压缩（基线报告“负载与表”） | Operator 声明的源与表（M3 看板报告“运行”第 1 步） | <…> | <…> |
| Doris 镜像 | 见 `infra/m1/issue16-baseline/images`（tag `history/m1-m5`） | `m1-doris` ae19e6… / ecaf6f…（M3 看板报告“证据的来源版本”） | <…> | <…> |
| 负载 | CDC 1,000/秒、Kafka 10,000/秒 × 2 KiB，24 小时（基线报告“负载与表”） | 标记 10/秒加持续写入，40 分钟（M3 看板报告“运行”第 4、5 步）；写入的速率未记录 | <…> | <…> |
| 稳态的判定 | 每 10 秒取积压、10 分钟段取最大值，斜率不超过 0.05 分钟/小时且后三分之一 p95 不高出 0.5 分钟；稳态窗口约 24 小时（基线报告“测量方法”） | 最后 5 分钟的延迟中位数比最初 5 分钟高出不到 1 分钟（M3 看板报告“运行”第 4 步） | 积压不取样，后三分之一 p95 不高出 0.5 分钟，最大积压不超过路径的 p99 目标；窗口 <MINUTES> 分钟，每小时增长慢于 <0.75 ÷ 窗口的小时数> 分钟判不出 | <…> |
| 结论能否沿用 | <哪些结论仍适用，依据是什么；哪些要补测> | <…> | — | — |

## 8. 限制

- 积压期不在本 Release 的范围内：验收契约 2.1 的积压期目标（注入 10 分钟上游暂停，解除后 30 分钟内滞后回落到稳态 p95 以内）没有测。请求方 2026-10-22 定的口径只覆盖稳态。
- 稳态的盲区：增长按前后三分之一的 p95 判，余量 0.5 分钟，所以每小时增长慢于 0.75 ÷ 窗口的小时数 分钟的积压判不出（本次 <MINUTES> 分钟的窗口上 <值> 分钟/小时；120 分钟上 0.375，30 分钟上 1.5）。这是规则的余量，不是测量的限制。
- <默认规模之外（更多节点、更高负载、24 小时）未验证的部分。>
- <测量的偏差：源端时间取提交之后 MySQL 的时钟，不早于真实提交，所以以源端为起点的延迟略为偏小；Doris 路径的首次查到取查询结束的时刻，是保守上界。>
- <只观测的行：内表点查、外表交互点查与 Paimon → Doris 内表，不计入结论。>
- <运行中发现的问题与对应的 issue。>
```
