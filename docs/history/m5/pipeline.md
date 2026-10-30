---
title: "M5 管理控制台：流水线全景"
date: 2026-10-19
status: complete
issue: 137
---

# M5 管理控制台：流水线全景

本页记录 Issue #137 的验收：平台管理员与运维在控制台里看到整条流水线，即每个数据源、湖表、分层作业与看板的状态、延迟、积压、告警和数据的流向，并能看出问题出在哪个环节。控制台在 [#135](entry.md) 的入口后面（`https://console.cove.<COVE_HOSTED_ZONE_NAME>/pipeline`）。设计见 [Web 入口与控制台 LLD](../../design/colvenna-console-lld.md) 的"管理控制台：流水线全景"，依据 HLD 11、12.1、12.3、12.6。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），与 M1–M4 同一个 EKS 集群、同一个监控栈（M3 #29）与 Marquez（M4 #37），只用合成数据。

**证据的来源版本**：结果来自提交 `e31ded1`，私有证据目录 `m5-137/run-p2-e31ded1d/`。镜像：控制台 `m5-console@sha256:1eea887b…`，Operator `cove-operator@sha256:f260309d…`（#136 的构建，Operator 的代码此后没有改动），Prometheus `prom/prometheus@sha256:63805ebb…`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 从控制台能沿"源 → ODS → DWD → DWS → Doris 看板"追到每个环节的状态、延迟、积压与当前告警，与 kubectl、Prometheus、Marquez 读到的一致 | 通过 | 控制台画出的流向是：MySQL 源 → ODS 的三张表 → DWD 作业 → DWD 表与它的隔离表 → DWS 作业 → DWS 表 → 看板。每个环节与紧接着读的 kubectl 一致：声明的阶段、Flink 作业的状态、分层作业对上游的滞后（快照数与秒数）、看板每条查询的刷新、表上任务的阶段。9 个节点的 25 个指标，与在控制台读数的同一时刻查 Prometheus 的结果一致（延迟、新鲜度、滞后、检查点、文件数、快照数、副本的年龄）。告警在平时、三次故障中、故障消失后都与 Prometheus 的一致。流向与 Marquez 逐个作业一致 |
| 注入作业挂起、坏数据进隔离表这类故障时，控制台在告警触发的同一时段标出出问题的环节 | 通过 | 坏行进隔离表、DWD 挂起超过 M0 的 p99、源挂起超过 p99：harness 每 15 秒查一次 Prometheus 的告警，看到 firing 之后随即读控制台：1–2 秒后的那次读数里，对应的环节已标为出问题，下游都记为受它影响，上游都不标。告警消失后控制台不再以它为原因；所有告警都消失后，什么也不再标 |
| 控制台不暴露 Secret 与 token，不提供写操作；以控制台后端的身份发出的写请求被 Kubernetes 与 cove-catalog 拒绝 | 通过 | 控制台的接口、全景页和 10 个节点页里，找不到 2279 个值中的任何一个：6 个命名空间的 Secret 的值（其中 JSON 的每个字符串、长值的每一行），看板的口令封、源的身份、用户的会话 cookie 与源表的一行数据。非 GET 的请求都回 405。控制台的 ServiceAccount 只有五种资源的 `get`、`list`，以它的 token 改、删、建都被 Kubernetes 拒绝（Forbidden），改的那一项确实没有生效。它没有 cove-catalog 的身份，它的命名空间连不上 cove-catalog，它的 token 被 cove-catalog 回 401 |
| 按角色区分可见范围；未认证与没有角色的用户被拒 | 通过 | 管理员与运维看到全景，BI 用户与没有角色的用户的三个页面都是 403，没有会话时都是 302 到登录。审计里记着谁看了，谁被拒 |

## 运行

`infra/m3/scripts/install-monitoring.sh` 装上只放行控制台 Pod 的 Prometheus，`infra/m5/scripts/build-console.sh`、`install-console.sh` 构建并安装控制台。`infra/m5/flows/pipeline/run-pipeline.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL；比较由 `compare.py` 做。浏览器由 #135 的 `entry.py` 模拟。

1. **用户与角色。** 建 4 个合成用户：admin（`cove-admin`）、op（`cove-operator`）、bi（`cove-bi`）、none（没有组）。没有会话时请求全景页、接口与节点页；四个人各自请求。
2. **流水线。** MySQL 源（带心跳表）接入 `orders`、`customers`，一个写入器持续改源表；DWD 把订单与客户关联，带非空与枚举检查和隔离表；DWS 按地区汇总；看板在 Doris 里服务 DWS，一条外表视图、一条每分钟刷新的物化视图；DWS 上一个打 tag 的任务（同 `cove maintain tag`）。源表里另有一行标记数据。
3. **与权威来源对照。** 等指标都出来（源的延迟、DWD 的滞后、DWS 的新鲜度、物化视图的年龄），然后每项最多读三次，控制台与对方紧接着读：
   - 流向包含预期的 9 条边；
   - 状态与 kubectl 一致；
   - 指标与 Prometheus 在控制台读数的时刻（`at`）的查询结果一致：以 `time()` 计算的两项允许 2 秒，其余必须相同；
   - 告警与 Prometheus 的告警一致（节点、名字、状态）；
   - 流向与 Marquez 一致：每个作业在 Marquez 里的输入、输出是控制台画在它两边的表，源读的源表名就是它写的 ODS 表名。
4. **不展示的。** 收集 6 个命名空间里的全部 Secret 值、看板的口令封、源状态里的身份、admin 与 op 的会话 cookie 与那行标记数据，在接口、全景页与每个节点页里找（原样、JSON 转义、HTML 转义都找）；对接口与页面发 POST、PUT、DELETE、PATCH。
5. **控制台的身份。**
   - Kubernetes：在作业命名空间与其他四个命名空间、集群范围检查控制台 ServiceAccount 能做什么；以它的短期 token 读、改、删、建与读 Secret。
   - cove-catalog：Deployment 没有挂载或引用任何 Secret，命名空间没有 catalog 客户端的标签；以它的 token 请求 cove-catalog。
   - AWS：它的 IAM 角色只有写自己审计日志组与全局登出用户池用户的权限。
   - Prometheus：从控制台命名空间里一个带标签的 Pod 查询、调 admin、lifecycle 与 remote write；从带标签但命名空间不带的、命名空间带但 Pod 不带的两处查询。
6. **故障。** 每次等 Prometheus 的告警进入 firing，随即读控制台（之后一分钟内再读四次）：
   - 3 个订单写成未知状态，DWD 把它们放进隔离表：`CoveRowsQuarantined`；
   - DWD 挂起，直到 `CoveLayerLagP99`；恢复后等它消失；
   - 源挂起，直到 `CoveSourceStale`；恢复后等它消失。

   每次核对：出问题的环节与原因，下游每个环节都记为受它影响，上游没有标记，告警与 Prometheus 的一致。最后等这次运行的所有告警都消失（隔离告警在最后一行坏数据 15 分钟后消失，失败的检查点 15 分钟，源的 p99 10 分钟）。
7. **审计与清理。** 控制台审计日志组里要有 op、admin 的请求和 bi 的拒绝，以及被拒的写请求。最后删除任务、看板、两张分层表与源，控制台里不再有它们。

## 结果

运行 p2（`e31ded1`）的 39 项检查全部通过。

| 检查 | 结果 |
| --- | --- |
| `no-session-*`（3 项）、`sees-the-pipeline-*`、`refused-the-pipeline-*` | 302 到 `…amazoncognito.com/oauth2/authorize`；admin、op 200，页面里有 SVG；bi、none 三个页面都是 403 |
| `source-ready`、`dwd-running`、`dws-running`、`dashboard-ready`、`task-ended` | 336、80、67、13、12 秒 |
| `path-source-to-dashboard` | 10 条边，预期的 9 条都在（另一条是源到心跳表） |
| `state-is-kubectls` | 源、DWD、DWS `Running`（作业 `RUNNING`），看板 `Ready`，DWS 上的任务 `Succeeded`；滞后与查询的刷新也相同 |
| `measures-are-prometheus` | 9 个节点 25 个指标，在 `08:46:11.64Z` 与 Prometheus 相同；例如源的延迟 31 秒、DWD 层间延迟 14 秒、DWS 新鲜度 46 秒、物化视图 76 秒 |
| `alerts-are-prometheus`（5 项） | 平时两边都没有告警；三次故障中两边相同（例如源挂起时：DWD 的 `CoveRowsQuarantined` firing、源的 `CoveCompactionBacklog` pending、源的 `CoveSourceStale` firing） |
| `lineage-is-marquezs` | 源 → 3 张 ODS 表；DWD 读 `orders`、`customers`，写 DWD 表与隔离表；DWS 读 DWD 表，写 DWS 表；看板读 DWS 表（Marquez 里另有它的两个 Doris 对象） |
| `nothing-secret-or-of-the-data-is-served`、`only-get` | 2279 个值、12 个页面里一个也没有；6 个写请求都是 405 |
| `kubernetes-identity-only-reads`、`kubernetes-refuses-the-consoles-writes`、`the-write-did-not-happen` | 只有五种资源的 `get`、`list`，别处与集群范围什么都没有；改、删、建、读 Secret 都是 Forbidden；DWD 没有被挂起 |
| `console-holds-no-catalog-identity`、`catalog-refuses-the-consoles-token`、`aws-identity-only-its-own-audit-and-sign-out` | 没有 Secret；命名空间只带 `prometheus-reader` 与 Pod Security 的标签；`GET /v1/config`、`POST /v1/cove/databases` 都是 401；IAM 只有 `logs:CreateLogStream`、`logs:PutLogEvents`（审计日志组）与 `cognito-idp:AdminUserGlobalSignOut`（用户池） |
| `prometheus-reads-only-from-the-consoles-namespace`、`prometheus-unreachable-without-the-labels` | 查询 200；admin 两项 500 "admin APIs disabled"，lifecycle 两项 403 "Lifecycle API is not enabled"，remote write 404；cove-catalog 连不上；另两处都连不上（退出码 28，连接时间 0） |
| `quarantine-located` | 告警在坏数据写入 76 秒后 firing，控制台 2 秒后标出 DWD，下游 5 个节点受它影响 |
| `layer-lag-located`、`layer-lag-cleared` | 挂起 350 秒后 firing，控制台 2 秒后；恢复 107 秒后告警消失，控制台不再以它为原因（DWD 仍因隔离告警出问题） |
| `source-stale-located`、`source-stale-cleared` | 挂起 365 秒后 firing，控制台 1 秒后标出源，下游 9 个节点都受它影响；恢复 260 秒后消失（源仍因 `CoveSourceLatencyP99` 出问题，它看过去 10 分钟） |
| `nothing-marked-once-every-alert-resolves`、`alerts-are-prometheus-once-resolved` | 源的告警消失 683 秒后，这次运行的节点都不再有 firing 的告警，控制台什么也不标；两边都只剩源的一条 pending（`CoveCompactionBacklog`） |
| `audit-has-who-looked-and-who-was-refused`、`gone-from-the-console` | op 53 次、admin 1 次读流水线，bi 与 none 各 3 次被拒，admin 的 6 个写请求都在；删除之后控制台里没有节点 |

之前的运行发现并修正了这些问题：

- **p1（`34a2d2b`，rebase 之前的提交，33 项，1 项失败）：** 删掉所有声明之后，接口返回 `"nodes": null`，现在是空的列表。p1 之后又改了几处：
  - Prometheus 的放行从整个命名空间收窄到带标签的控制台 Pod；
  - 探测改为核对 Prometheus 自己的拒绝文字，不只看状态码。
- **评审：**
  - 原先按声明画边，与 Operator 上报给 Marquez 的不一致：源的 `target` 是表名，库在 `spec.target.database`；分层作业应该用 planner 找到的读与写（含隔离表）。
  - 一个源的几个 Flink 作业只显示了最后一个。
  - 任务在开始之前对不上它的表：`spec.table` 是对象。
  - level-0 文件数读成了小文件数。
  - 新鲜度告警的归属原先是：上游只要有问题，就算受影响，所以隔离坏行这类不让数据迟到的问题，会把下游真正的新鲜度问题藏起来。现在只有让数据迟到的上游问题才算原因。
  - 下游节点记下受哪个环节影响（`affectedBy`），故障的核对据此确认标记来自注入的故障，而不是之前留下的告警。
  - "告警消失"原先只看原因里没有了它，现在还要求所有告警都消失后什么也不标。
  - harness 加了 Kubernetes 在其他命名空间的权限、IAM 角色、任务与滞后的对照，以及会话 cookie 的查找。

运行之后的修改只有单元测试，没有在 EKS 上重跑（复核发现）：

- 一个环节已经因为别的原因出问题、又有它自己的新鲜度告警时，原先不把它算作让数据迟到的问题，下游的新鲜度告警会被当成另一个问题；
- Prometheus 的告警接口不是 `success` 时，与查询接口一样算作读不到；
- 节点页的测试改为经过定位，覆盖作业与"受谁影响"；
- harness：源状态里的列表逐项查找，探测之间清掉上一次的响应，最后一项的说明改为"源的告警消失之后"。

## 限制

- **只验收了 MySQL CDC 源。** 事件源（Kafka）的边与 `kafkaLag` 只有单元测试；CDC 源本身没有积压指标，它的积压看它写的 ODS 表的文件数与快照数，以及下游作业的滞后。
- **新鲜度告警没有触发过。** `CoveFreshnessP99` 的阈值是 30 分钟、持续 5 分钟，这次运行没有走到；它的归属规则只有单元测试。
- **看板的边按声明。** Marquez 按每条查询实际读的表；声明了但没有查询读的表，控制台会多画一条边。这次两者相同。
- **没有表的 schema。** 控制台不持有 cove-catalog 的身份，节点上没有列与类型。
- **管理员与运维看到的相同。** 可见范围只分"能看"与"不能看"。
- **Prometheus 按标签放行。** 能在控制台的命名空间里建带那个标签的 Pod 的人，就能读 Prometheus。
- **每次全量读。** 每个请求都列出所管命名空间的全部声明并查询 Prometheus，没有缓存。

> 注：本文中的 issue 与 PR 编号（如 #137）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
