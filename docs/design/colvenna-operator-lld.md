---
title: "colvenna-operator LLD"
date: 2026-10-19
status: implemented
inputs:
  - docs/design/hld-realtime-lakehouse.md
  - https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/docs/design/colvenna-catalog-lld.md
  - docs/design/acceptance-contract.md
---

# colvenna-operator LLD

本文是 M3 声明式接入与运维的实现设计。总体架构与取舍见 [HLD](hld-realtime-lakehouse.md) 第 7、9、11、12 节；控制平面见 Colvenna Catalog 仓库里、Colvenna Lake 固定版本的 [colvenna-catalog LLD](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/docs/design/colvenna-catalog-lld.md)。本页随 M3 各 issue 增补：目前写到 #24 的 `LakeSource`（MySQL、PostgreSQL）、#25 的 Kafka 事件源、#26 的 `LakeTable`（DWD/DWS 分层表）、#27 的 `LakeTask`（批任务与维护任务）、#28 的治理声明与 `LakeErasure`（个人数据删除）、#29 的监控与流内数据质量、#32 的看板服务；M4 起增补 #38 的批量质量校验（`LakeQuality`、`LakeWaiver`）、#37 的血缘、#34 的 Iceberg 开放层（`LakeIcebergPublication`）与 #35 的列标签、脱敏与行过滤；M5 起增补 #136 的 BI 登录名（看板的 Doris 用户写进 BI 的凭证 Secret），以及 #138 控制台操作所涉及的部分（控制台扮演的两个用户的豁免批准人、数据负责人角色的 `patch`）。控制台本身见 [Web 入口与控制台 LLD](colvenna-console-lld.md)。

## 组件

| 组件 | 语言与版本 | 职责 |
| --- | --- | --- |
| `colvenna-operator/` | Java 21；Java Operator SDK 5.5.0、fabric8 7.8.0、`flink-kubernetes-operator-api` 1.16.1 | 协调声明式资源：catalog 身份、Flink 作业、表状态。版本与集群上的 Flink Kubernetes Operator 1.16.1 一致（HLD 6.6） |
| `colvenna-jobs/` | Java 11（`--release 11`），跑在 Flink 1.20.4 基础镜像（`deploy/images/flink-base`）上 | Operator 部署的作业入口：MySQL 整库 CDC、PostgreSQL 单表 CDC、MongoDB CDC（`MongoCdcJob`）、Kafka 承载的 CDC（`KafkaCdcJob`）、Kafka 事件入湖、分层表的 SQL 作业与查询校验、任务的批运行与批量质量校验（`TaskJob`）、删除流程的检查（`ErasureProbe`）；以及取证工具 `LakeDump`、`Consumers`、`SqlQuery` |
| `colvenna-cli/` | Go | 薄客户端：`colvenna apply/get/describe/suspend/resume/delete/wait`，批任务与维护 `colvenna backfill`、`colvenna maintain compact/expire/orphans/tag`，删除 `colvenna forget`，质量 `colvenna check`、`colvenna waive`，以及 `colvenna version`；资源类型 `source`、`table`、`task`、`erasure`、`dashboard`、`quality`、`waiver`、`iceberg`。只调 Kubernetes API，不做任何准入判断；操作的对象由 `colvenna-cli/ops` 生成，控制台共用这个包 |
| `deploy/helm/colvenna-operator` | Helm | CRD、Operator、RBAC，以及受管命名空间里作业所需的服务账号、CA 和权限 |
| `deploy/aws/src/operator-stack.ts`、`deploy/scripts/operator/` | TypeScript CDK、bash | `ColvennaLake<Env>Operator` 栈（镜像仓库、作业的 IRSA 角色）、构建与安装脚本；EKS 上的验收脚本在 `tests/acceptance/` |

**准入在服务端。** 分类、准入和授权都在 Operator 与 colvenna-catalog 里执行。CLI 失效或被绕过（直接 `kubectl apply`），也不会放宽任何检查。

**受管命名空间。** Operator 只监视受管命名空间（`managedNamespaces`，默认 `colvenna-data`），也只在那里有权限：八种声明（`LakeSource`、`LakeTable`、`LakeTask`、`LakeErasure`、`LakeDashboard`、`LakeQuality`、`LakeWaiver`、`LakeIcebergPublication`）及其状态，其中 `LakeTask` 还能创建与删除（删除流程与源建的维护任务）；FlinkDeployment，以及删除 FlinkStateSnapshot（删除流程处置旧的 savepoint）；Secret、Event、查询校验与任务运行用的 Job，读取 ConfigMap，以及列出 Pod（读校验与运行的结果）。受管命名空间之外，它只有按名字限定的两个 Secret：平台 Doris 的 root 口令（`get`，配置了 `doris.host` 时，见"看板服务"），以及 BI 的 Doris 凭证 Secret（`get`、`update`，配置了 `bi.namespace` 时）。除了选主用的 lease（以及上报血缘时，记录上报内容的那个 ConfigMap，见"血缘"），它在自己的命名空间里也没有别的权限，更没有集群级权限。

命名空间由安装脚本创建并打标签，chart 不拥有它们，卸载 chart 不会删掉其中声明的数据源。所打的标签是 `restricted` Pod Security 与 `colvenna-lake.io/catalog-client=true`。chart 在这些命名空间里：

- 放入 colvenna-catalog 的 CA；
- 创建作业服务账号 `colvenna-flink`，绑定这个命名空间自己的 IRSA 角色（`jobs.roleArns.<namespace>`；`ColvennaLakeOperator` 栈为每个受管命名空间建一个 `colvenna-lake-jobs-<namespace>`）。角色只信任本命名空间的这个服务账号，只能访问 `flink-state/<namespace>/` 下的 Flink 状态，访问不到表数据，也访问不到别的命名空间的状态。删除流程的检查以同一个服务账号运行，所以也只查得到、删得掉本命名空间的状态。角色还能以不带结尾斜杠的 `flink-state/<namespace>` 为前缀列举：Hadoop S3A 列一个目录之前先 HEAD 这个路径本身，没有这项列举权限时 S3 回 403 而不是 404，删除检查列命名空间的状态目录就会失败。这个前缀也会列出名字以它开头的命名空间的状态，所以栈不接受一个受管命名空间的名字是另一个的开头（例如 `colvenna-data` 与 `colvenna-data-b`）。加受管命名空间时，先用同一个 `MANAGED_NAMESPACES` 部署栈（`deploy/scripts/operator/deploy-stack.sh`），再装 Operator（`install-operator.sh`）；受管命名空间没有对应的角色时，安装脚本在改动任何东西之前停下，chart 也渲染失败；
- 建一条 NetworkPolicy：作业 Pod 只接受同一命名空间的连接，以及来自 Operator 和 Flink Kubernetes Operator 所在命名空间、目标端口为 8081 的 REST 连接。Flink REST 不认证、并且接受作业提交（HLD 12.1），所以不能让集群里任何 Pod 都能连上。

安装脚本每次都会以 server-side apply 应用 CRD：Helm 只在首次安装时安装 `crds/`。

Operator 本身没有 AWS 角色。它读表用自己的 catalog 身份和按表数据凭证。它对湖的写入只有四种：补上 Kafka 承载的 CDC 漏掉的删除（见"Kafka 承载的 CDC"中的"采集器重新快照"）；把质量门禁的每次决定写进 `quality_<ns>.decisions`；给未通过的校验快照打、删 `colvenna-check-*` tag（见"批量质量校验"）；删除 `LakeTable` 时删掉它在上游表上的 consumer，免得它继续钉住上游的快照。

## Catalog 身份

每个声明式资源对应一个 colvenna-catalog principal，名字是 `<kind>.<namespace>.<name>.<uid 前 8 位>`。带 uid 是因为停用的 principal 不能复用（colvenna-catalog 的管理面限制）：删掉再重建同名资源，会得到一个新身份。

| 项 | 做法 |
| --- | --- |
| token | 有效期 30 天，写进资源所属的 Secret `<name>-catalog-token`（key `token`），Secret 带 owner reference。剩不到 10 天时签发新 token。Paimon 客户端不会自己刷新 bearer token，所以新 token 写进 Secret 的同时，token id 写进作业的 pod 模板注解，作业因此重启换用新 token；作业全部重新运行后，才吊销旧 token |
| grant | 精确到资源所需的最小集合。`LakeSource` 对自己的目标库有 `MANAGE`（CDC action 要建表、改 schema）；`LakeTable` 对每张上游表有表级 `READ`、对目标表有表级 `WRITE`，建表由 Operator 完成。grant 有效期 90 天，剩不到 30 天时续期。管理面没有更新接口，续期就是删掉旧 grant 再建新的，中间有毫秒级的空窗。多余的 grant 会被撤销 |
| 删除 | 撤销全部 grant 和 token，停用 principal |

**目标库的归属。** 一个目标库只能属于一个 `LakeSource`，否则两个源会共用对方的 `MANAGE`。

- Operator 建库时，在 database option `colvenna-lake.owner` 中记下 `LakeSource/<namespace>/<name>/<uid>`；
- 目标库已存在但不是这个源的，判为 `Failed`（`TargetConflict`），不会接管；
- 两个声明写同一个目标库时，后创建的那个同样判为 `TargetConflict`。

**接入区隔离（HLD 7.3、12.5）。** 新表只能落进与用户读端隔离的接入区。Operator 每轮检查所有 grant：除了本源和 admin 平台身份，只要还有别的 principal 能读目标库（CATALOG 级或这个库的 DATABASE 级 grant），条件 `TargetIsolated` 就为 False，发 Warning 事件，并且不再准入新表（原因 `TargetNotIsolated`）。已在作业中的表不受影响。按表发布（table 级 grant）不会破坏隔离。

Operator 自己的身份由安装脚本建立：`colvenna-operator` 是 admin principal，另有 catalog 级 `MANAGE`，有效期 90 天（`install-operator.sh`）。平台身份 `admin` 的 catalog 级 `MANAGE` 由 `install-catalog.sh` 建立，有效期 7 天。两条 grant 都经 `catalog-admin.sh` 的 `ensure_grant`，每次运行这两个脚本都按同一个规则核对（[#22](https://github.com/colvenna/colvenna-lake/issues/22)）：catalog 对同一个 principal、范围、库、对象、权限与 effect 只留一条 grant，不论是否过期，再建一条只得到 409；所以已有的那条已过期、剩余有效期不到余量（`GRANT_RENEW_DAYS`，默认是有效期的一半：`admin` 3.5 天，Operator 45 天，测试用的 1 天的 grant 是 12 小时）、到期晚于条款允许的（晚于现在加有效期再加 1 天）或者列不同时，删掉它，再按条款新建，两次请求之间这条 grant 不存在；其余情况保留。另一次安装同时续期同一条 grant，先删掉了它（删除得到 404）或先建好了新的（新建得到 409）时，只再读一次：那一条符合条款就保留，否则失败。`install-operator.sh` 续期 Operator 的 grant 时，运行中的 Operator 可能短暂得到 403：一是删与建之间的空窗，二是另一个 catalog 副本在空窗里读到的"没有 grant"会缓存一个认证缓存周期（默认 2 秒，`COLVENNA_AUTH_CACHE_TTL`）。这时那一次 reconcile 失败，资源短暂为 `Degraded`（`reconcile failed: …`），Operator 随后重试。这个空窗避不开：catalog 的管理面没有更新 grant 的接口，唯一键又不允许先建新的、再删旧的。两次安装之间没有自动续期：`admin` 的 grant 最晚在上一次新建之后 7 天过期，Operator 的 90 天。两个身份的 token 也只在运行安装脚本时续签，规则见下文（[#23](https://github.com/colvenna/colvenna-lake/issues/23)）。

Operator 的 token 在 Secret `colvenna-operator-catalog-token` 里，只在运行 `install-operator.sh` 时续签，两次运行之间不会自动轮换。每次运行都先用这个 token 调一次管理面（列出这个 principal 的 token，每个都带 id 与过期时间，见 Colvenna Catalog v1.0.0 的[管理面 API](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/docs/api/admin-v1.yaml)），再按结果处理：

- Secret 不存在，或 catalog 拒绝其中的 token（401：过期、撤销、principal 停用，或恢复元数据库之后不认识它）：用平台的管理员 token 签发新的；principal 不在了就先重建为 admin principal。
- token 有效，但剩余有效期不足 `TOKEN_RENEW_DAYS`（默认 30 天）：同样签发新的。catalog 只存 token 的 SHA-256（[`TokenService`](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/src/main/java/com/colvenna/catalog/auth/TokenService.java)），从列表里认不出 Secret 里的是哪一个，所以安装脚本签发时把新 token 的 id 记在同一个 Secret 的键 `id` 里，剩余有效期就按这个 id 的 token 算；这个 id 不在未撤销的 token 里也续签。没有记 id 的 Secret（之前的安装留下的）按这个 principal 未撤销、描述为 `colvenna-operator-catalog-token` 的 token 中最晚的过期时间算，一个也没有就续签。
- 管理面回的既不是 200 也不是 401（例如 503，或者 port-forward 断了、没有回答）：这一步失败，什么都不签发、不写。
- 否则保留。

新 token 的有效期是 `OPERATOR_TOKEN_TTL`（默认 `P90D`），与它的 id 一起写进 Secret 之后立即核对 catalog 接受它；旧 token 不撤销，到期自然失效。Operator 不需要重启：它每次调用 catalog 都重读挂载的 token 文件，Paimon 客户端在文件变化时重建（`LakeReader`），chart 挂载的是整个 Secret（没有 `subPath`），kubelet 更新挂载的文件之后就用上新 token，延迟最长为 kubelet 的同步周期加缓存的传播时间（Kubernetes 文档 [Secrets：Using Secrets as files from a Pod](https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets-as-files-from-a-pod)；以 `subPath` 挂载的不会更新）；在那之前，续签时还有余量的旧 token 照常可用。签发要用平台的管理员 token，它由 `install-catalog.sh` 按同样的规则续签，失效时在 catalog 的 Pod 里用 `bootstrap-admin` 重签（[`Main`](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/src/main/java/com/colvenna/catalog/Main.java) 的 `bootstrap-admin` 只需要元数据库，日志里写出所签 token 的 id），所以 `install.sh` 先装 catalog、后装 Operator。至少每 `TOKEN_RENEW_DAYS` 天运行一次这两步，token 就不会过期；过期了，下一次运行会重签，Operator 在那之前访问不了 catalog。

## 作业形态

所有作业都是 application 模式的 `FlinkDeployment`，归资源所有（controller owner reference），并且：

| 方面 | 设置 |
| --- | --- |
| 升级 | Kubernetes HA。CDC 作业用 `upgradeMode: last-state`：spec 变化后，Flink Operator 从 HA 元数据记录的最新 checkpoint 恢复。SQL 作业用 `savepoint`（HLD 9.1），见下文 LakeTable |
| 状态目录 | checkpoint、savepoint、HA 都在 `<checkpointRoot>/<namespace>/<deployment>-<uid 前 8 位>/` 下，每个作业独占一个前缀；保留最近 3 个 checkpoint |
| 重启策略 | `exponential-delay`，不设上限。作业进入终态 FAILED 时，Flink 会清掉 last-state 升级需要的 HA 元数据；而 Operator 正是靠升级把出故障的表移出作业，所以不能让作业进入终态 |
| 暴露面 | REST 服务只建 ClusterIP；`web.submit/cancel/rescale` 都关掉（HLD 12.1） |
| CPU | 请求按声明（`job.jobManager`、`job.taskManager` 的 `cpu`、`memory`；默认各 1 CPU，JobManager 2048m（分层表 1536m）、TaskManager 3072m），上限为请求的两倍（`cpu.limit-factor=2`），节点空闲时可以多用 |
| 信任库 | init 容器把 JDK cacerts 加上 colvenna-catalog CA，写到 emptyDir，经 `env.java.opts.all` 生效 |
| 凭证 | catalog token 从 Secret 挂载；源库口令经 `secretKeyRef` 注入。作业参数和 Flink 配置里都没有秘密 |
| 日志 | Flink CDC 和 Debezium 只到 WARN：二者在 INFO 级会打印连接口令（M1 #9 的发现） |
| Pod | 非 root（uid 9999）、seccomp `RuntimeDefault`、去掉所有 capability、不允许提权 |

**变更识别。** Operator 把期望 spec 的哈希记在 FlinkDeployment 注解里，只在哈希变化时更新。以下变化都会让作业以 last-state 重启：

- 表集合、资源、checkpoint 间隔或镜像变化；
- 源口令 Secret 的 resourceVersion 变化；
- catalog token 换新。

**作业从哪里恢复。** 每次作业重新启动（升级、故障转移、恢复），Operator 都从 JobManager 读出它恢复自哪个 checkpoint 或 savepoint，记在 `restoredFrom`，并发 `JobRestored` 事件。之前有状态、这次却从头开始的作业，发 Warning `JobStartedWithoutState`。这对应 HLD 7.3 的"核对恢复前后 checkpoint"。

Operator 在作业运行时每轮都读一次：last-state 升级沿用原来的 job id，TaskManager 故障转移连提交时间都不变，单看 job id 发现不了重启（2026-10-15 的 r4 因此漏记了三次恢复）。新的一次提交以作业的开始时间区分。

**作业不会被悄悄重建。** 一个作业一旦运行过（状态里有它的 job id），它的 FlinkDeployment 却不见了（被人在 Operator 之外删掉），那么作业状态就丢了。重新从头建作业，会把快照再写一次进已有数据的目标表。所以 Operator 不重建，而是把它的表判为 `Failed`（`JobStateLost`）并发 Warning `JobLost`。新建作业之前，Operator 也会确认它的每张目标表都没有快照。

**并行度锁定。** Paimon 2.0.0 CDC action 的算子没有设置固定 uid。并行度一变，算子链和自动生成的算子 id 跟着变，checkpoint 状态就无法恢复（`There is no operator for the state ...`），作业会一直起不来。2026-10-15 在 EKS 上实测如此。所以作业部署后，Operator 保持当时的并行度：声明里改了并行度，状态条件 `SpecApplied` 报 `ParallelismPinned` 并发 Warning 事件，作业不动。要改并行度，需要删除并重建这个源。

**升级卡住。** 如果某个变更导致新作业在初始化时失败（例如以后有别的拓扑变化），Flink 会把这个作业判为全局终态并清掉 HA 数据，Flink Operator 于是一直等待"可升级状态"。Operator 发现 `Job Not Found` 持续 3 分钟，就把 `JobRunning` 置为 `UpgradeStuck` 并发 Warning 事件。恢复步骤见下文"运维"。

## LakeSource

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: shop, namespace: colvenna-data}
spec:
  type: mysql                         # 或 postgres
  connection:
    host: shop-db.example.internal
    database: shop
    credentialsSecret: {name: shop-cdc}        # 键 username / password，可用 usernameKey / passwordKey 改
    inspectorSecret: {name: shop-inspect}      # 可选，只用于读触发器定义
    serverTimeZone: UTC
    # postgres 还需要 schema 和 publication
  tables: {include: ["orders", "order_.*"], exclude: [".*_tmp"]}
  target:
    database: ods_shop                # 只属于这个源
    versionColumn: version_no         # 默认 version_no
    bucket: 2                         # 1–1024，默认 1
    tableOptions: {}                  # 不能覆盖受管选项
  job: {parallelism: 1, checkpointInterval: 30s}
  suspend: false
  deletionPolicy: Retain              # 或 Delete
```

**作业。**

- MySQL：每个源一个 `mysql_sync_database` 作业，combined 模式（HLD 7.3）。作业的读端用一段 MySQL server-id：Operator 从 Helm 值 `mysqlServerIdRange`（默认 `6000-6999`）里给每个源分一段（至少 16 个，不少于并行度），记在 `status.serverIdRange`；之后只有声明的并行度超过这一段的大小时才重新分一段；多个源接同一个 MySQL 时互不冲突，这个区间也不能与源库上其他复制客户端的 server-id 重叠。
- PostgreSQL：每张表一个 `postgres_sync_table` 作业（PostgreSQL 没有库级 action），各用一个 replication slot，slot 名为 `colvenna_<uid 前 8 位>_<表名>`，超过 PostgreSQL 的 63 个字符时截短并接上整名的哈希，两张表不会共用一个 slot。publication 由源库所有者创建，作业只读不建。分区表从叶子分区读入同一张 Paimon 表（只支持一层分区），与 M1 一致。

两种作业都只按精确表名捕获 Operator 准入的表，没有准入的表永远不会被捕获。MySQL 作业把 `TINYINT(1)` 当数字而不是布尔值（`tinyint1-not-bool`），否则 0 和 1 以外的值会丢失。目标表的受管选项为：

- `merge-engine=deduplicate`、`changelog-producer=input`、`write-only=true`；
- `sequence.field=<versionColumn>`、`bucket=<bucket>`；
- `consumer.expiration-time=1 d`，可以在 `tableOptions` 里改。下游分层表以 consumer 流读这些表，而 Paimon 不允许流读没有设置这一项的表：废弃的 consumer 不能无限期保留快照（HLD 9）。

`tableOptions` 只接受保留期、文件布局和写缓冲相关的选项（快照与 changelog 保留、`consumer.expiration-time`、`deletion-vectors.enabled`、文件格式与压缩、`target-file-size`、写缓冲、sorted run 触发，以及 `file-index.*`）。其余选项可能改变 M1 验证过的语义，比如合并引擎、删除处理或排序，一律拒绝（`InvalidSpec`）。

**校验源库证书。** 声明 `connection.caConfigMap`（key `ca.pem`，例如 RDS 的 CA 合集）后：

- Operator 的探测与对账，MySQL 用 `VERIFY_IDENTITY`，PostgreSQL 用 `verify-full`；
- MySQL 作业的 JDBC 与 Debezium 连接，以及 PostgreSQL 作业的复制流，都校验服务端证书和主机名。作业 Pod 的 init 容器把合集中的每张证书逐一导入 JVM 信任库；
- 例外：Paimon PostgreSQL action 读 schema 用的 JDBC 连接不能配置 SSL 参数（`PostgresActionUtils.getConnection`），只走服务端强制的 TLS，不校验证书。

不声明 CA 时，TLS 仍然必需，但 MySQL（`sslMode=REQUIRED`）与 PostgreSQL（`sslmode=require`）都不校验服务端证书；条件 `SourceVerified` 为 False（`NoCaConfigured`），并发 Warning `TlsNotVerified`。MongoDB 与 Kafka 源不声明 CA 时按 JVM 的默认信任库校验，见各自一节。声明的 ConfigMap 不存在或没有 `ca.pem` 时，`SourceReady` 与 `SourceVerified` 都为 False（`CaMissing`）。

**源端守卫。** `colvenna-jobs` 在解析器之前加了一层守卫（`SourceVersionGuard`，沿用 M1/M2 的版本守卫）：

- 更新必须抬高版本，否则作业失败；
- PostgreSQL 的更新和删除必须带完整前镜像，更新不能改主键。

失败信息里写明表名，供 Operator 归因。

### 协调循环

每 30 秒，以及作业变化时，协调一次：

1. 校验声明：正则能编译，`tableOptions` 不覆盖受管选项，目标库不与其他源冲突。不通过就判为 `Failed`，不建任何东西。
2. catalog：确保目标库存在；同步身份、token 与 grant。
3. 归因：从各作业 JobManager 的 REST 读取最近的异常。
   - Paimon 的 schema 演进错误（`... of Paimon table <db>.<table>`）和源端守卫的错误都带表名，直接归到那张表；
   - 单表作业遇到确定性的 schema 错误，归到它唯一的表。
4. 探测源库：服务端前提和每张表的结构。探测失败时，作业保持原样：源库连不上，不能当作源库没有表。
5. 对每张选中的表做准入，算出各表的状态。
6. 应用作业：表集合变了，就以 last-state 升级作业。
7. 观察作业：记录 lifecycle、job 状态与错误，识别卡住的升级。
8. 对账：作业以当前 spec 运行时，对每张 `Snapshotting` 的表、以及待重新对账的 `Ready` 表逐行对账。
9. 汇总 phase、条件与指标。

### 表状态

| 状态 | 含义 | 去向 |
| --- | --- | --- |
| `Snapshotting` | 已准入并加入作业，还没有对上源端 | 对账通过 → `Ready` |
| `Ready` | 源表的每个主键和版本都在目标表里对上 | 之后出现不支持的变化 → `Failed` |
| `Rejected` | 没有准入，不进作业；原因见下表，另外还有 `TargetNotEmpty`（目标表已有数据）、`TargetNotIsolated`、`SourcePrerequisites` | 源端修好、目标表清空或隔离恢复后重新判定 |
| `Failed` | 已移出作业：作业因这张表失败，源端变化后不再满足准入或主键变了，或者作业状态丢失（`JobStateLost`） | 保持不变，即使源端恢复也不会自动重新加入（目标表已经漏掉了这段时间的变更）。需要人工处置 |

一张表只有在目标表还没有快照时才能加入作业。原因是向已有数据的目标表再做一次快照，会在 changelog 里重复插入已有的行，并保留此后已删除的行。所以取消选择又重新选回的表、失败后重新选回的表，都会判为 `TargetNotEmpty`。要重新接入，需要先删掉目标表。

已经不再被选中、或源库里已没有的表，会从状态中移除。状态列表的长度以选中的表数为上限。每次状态转换都发一个事件：转到 `Rejected` 或 `Failed` 是 Warning，其他是 Normal。

### 准入

规则就是 HLD 7.4 的源端契约，加上实测的类型边界：

| 原因 | 判定 |
| --- | --- |
| `UnsupportedName` | 表名不全是小写字母、数字和下划线，或列名不是普通标识符 |
| `NotReadable` | CDC 用户看不到表的任何列：两种源都只列出用户有权限的列，说明它对这张表没有 SELECT |
| `NoPrimaryKey` | 没有主键 |
| `VersionInPrimaryKey` | 版本列是主键的一部分 |
| `VersionColumnMissing` / `VersionColumnType` | 没有版本列，或版本列不是有符号 `BIGINT NOT NULL` |
| `VersionCheckMissing` | 没有生效的 `CHECK (<version> > 0)`，省略版本时会写成 0 |
| `SourceGuardsNotVisible` | MySQL 用户看不到触发器定义（需要 TRIGGER 权限，或另配 `inspectorSecret`） |
| `VersionGuardMissing` | 没有 BEFORE UPDATE 触发器拒绝不上升的版本 |
| `KeyGuardMissing` | MySQL 没有 BEFORE UPDATE 触发器拒绝改主键。PostgreSQL 有完整前镜像，由作业守卫拒绝 |
| `KeyReservationMissing` | 没有 BEFORE INSERT 触发器登记新主键，删除的主键可能被重新使用 |
| `UnsupportedBinaryColumn` | 有二进制列（MySQL 的 `binary`、`varbinary`、blob、`bit`；PostgreSQL 的 `bytea`），见下文 |
| `UnsupportedType` | 列类型不在已验证的清单里。MySQL：整数各宽度、`decimal`、`float`、`double`、`char`、`varchar`、四种 text、`date`、`datetime`；PostgreSQL：`int2/4/8`、`numeric`、`float4/8`、`varchar`、`bpchar`、`text`、`date`、`timestamp`。这些类型都由 #24 的运行逐列核对过；其他类型（如 MySQL `timestamp`、`json`、`enum`，PostgreSQL `timestamptz`、`bool`、`uuid`）待验证后再加入 |
| `UnsupportedKeyType` | PostgreSQL 主键里有 `varchar` 或 `char` 列，见下文 |
| `ReplicaIdentityNotFull` | PostgreSQL 的表或叶子分区不是 `REPLICA IDENTITY FULL` |
| `NotInPublication` | PostgreSQL 的表或叶子分区不在 publication 里 |
| `UnsupportedPartitioning` | PostgreSQL 分区多于一层，或分区表没有分区 |
| `PrimaryKeyChanged` | 源端主键与现有目标表的主键不同，主键不能原地变更 |

服务端前提确定不满足时，整个源判为 `SourceReady=False`，不准入新表，并且挂起已有作业：

- MySQL：`log_bin`、`binlog_format=ROW`、`binlog_row_image=FULL`；
- PostgreSQL：`wal_level=logical`、复制权限、publication 存在且 `publish_via_partition_root=false`。

比如 `binlog_row_image` 不再是 `FULL` 时，变更里的行不完整，继续写会损坏目标表。前提恢复后，作业从挂起的位置继续。源库只是暂时连不上时，作业保持原样。

**源表全部消失。** 探测成功，但作业里的表一张都看不到了，这更像是权限丢失或连接的库被改了，而不是表真的被删了。此时 Operator 保留作业及其状态，判 `SourceReady=False`（`TablesVanished`），不删除作业。

**二进制列。** Paimon 2.0.0 CDC 写入端无法原样写入二进制值：

- Paimon 把 MySQL 的 `BINARY(n)` 和 `VARBINARY(n)` 都映射为 `VARBINARY(n)`，写入时用 `String.getBytes(UTF_8)` 把 CDC 值转成字节（`TypeUtils.castFromCdcValueString`）；
- Debezium 默认模式下，值先被解码成字符串，非 UTF-8 字节在这一步就已损坏；
- 改用 `binary.handling.mode=base64` 时，存进去的是 base64 文本，长度超出列宽，作业失败。2026-10-15 在 EKS 上实测到：`Length of type VARBINARY(16) NOT NULL is 16, but casting result has a length of 24`。

因此含二进制列的表一律拒绝，并发 Warning 事件。这就是 #8 结项时记下的修复方向的结论。Flink CDC 3.7 能否改变这一点，由 #113 复测。

**PostgreSQL 的字符主键。** Debezium 报出的 PostgreSQL `varchar`/`char` 不带长度，Paimon 因此会把这类列演进成 `STRING`。主键列不能改类型，colvenna-catalog 拒绝这次变更（`Cannot update primary key`），作业从此失败。Paimon 的 PostgreSQL 类型映射只有"全部转字符串"一种选项，没有只转字符列的选项。所以主键含这类列的表拒绝，提示改用 `text` 或整数主键。非主键的 `varchar` 列照常演进为 `STRING`。

### 新表与 schema 变化

- **新表**：选中范围内出现新表时，按准入判定；通过的加入作业的表清单，作业以 last-state 重启。Flink CDC 默认开启 newly-added-table 扫描，只在恢复时生效，所以新表会在这次重启后做快照（HLD 7.3 的 M1 发现）。表的状态只在对账通过后才变成 `Ready`，不凭"有快照"判定（PR #76 评审意见 2）。不合格的表单独拒绝，不影响其他表（评审意见 1）；新表同样要过源端契约（评审意见 3）。
- **支持的变化**：加列、字符串与数值同族加宽，由 Paimon 自动演进到目标表。收窄会被 Paimon 忽略，值仍放得进原来较宽的类型。删列时目标表保留原列，改名按新列处理（HLD 7.5）。
- **不支持的变化**：例如 `DECIMAL→VARCHAR`。作业失败后，Operator 从异常里认出表名，把这张表判为 `Failed` 并移出作业，同一个源的其他表继续运行；同时发 Warning 事件。主键变化 Paimon 自己不会报错，Operator 对比源端主键与目标表主键时发现，判为 `PrimaryKeyChanged`。

### 对账

对账在作业以当前 spec 运行时进行，频率每张表每 30 秒最多一次。持续写入时，目标表总比源端晚一两个 checkpoint，所以分两次读：

1. 先读目标表最新快照的主键和版本（T1），再读源端（S）。两边一致的主键直接通过，其余的是候选。
2. 等目标表提交了一个比读 S 晚至少两个 checkpoint 间隔（不少于一分钟）的快照，再读一次目标表（T2）。正常运行的作业到这时已经应用了读 S 之前的全部变更，所以每个候选都必须已经追上：S 有这一行时，T2 里的版本不低于 S；S 已没有这一行时，T2 里也没有（主键不复用，删掉的主键不会再出现）。

目标表的版本高于源端（源端从没有过的版本）一律判为不一致。其余不一致的情况是缺行、旧值或多出的行，下一轮重新开始。2026-10-15 的 r3 发现：原先"先读源端再读目标表"的做法，在快照期间持续写入时永远通不过，因为目标表总在追赶。

- 主键文本统一编码，两边可比；PostgreSQL 按主键列自身的类型回查，以便用上主键索引；
- 状态消息里不出现主键值，只出现哈希：主键可能是个人数据，而能读这个源状态的人未必能读数据；
- 每张表最多读 `reconcileMaxRows` 行（默认 200 万）。超出时这张表停在 `Snapshotting`，原因记为 `TooLarge`，并发 Warning 事件。

### 修改、暂停与删除

| 操作 | 行为 |
| --- | --- |
| 改表选择、资源、checkpoint 间隔、口令、CA | 作业以 last-state 升级。源库连不上时也照样下发 |
| 改并行度 | 保持原值，并报 `ParallelismPinned` |
| `suspend: true` | 作业以 last-state 挂起，位置保留。PostgreSQL 的 slot 会继续保留 WAL，状态消息里会写明 |
| `suspend: false` | 从挂起的位置继续 |
| 删除 | finalizer 先删除作业并等待它们消失；再删除 PostgreSQL 的 replication slot；`deletionPolicy: Delete` 时删除目标表（包括已离开源的表的目标：源端删掉或改名的表，`status.retired` 记着），库空了就删库；然后撤回发布给 `readers` 的表级授权，最后撤销 catalog 身份。默认 `Retain` 保留目标表 |

表被移出作业时，PostgreSQL 的单表作业随之被删，它的 slot 会记进 `status.pendingSlots`。作业异步退出，退出前 slot 一直被占用，所以 Operator 每轮重试，直到 slot 删掉或已不存在；期间条件 `SlotsReleased` 为 False。删除整个源时也是一样：等最多 5 分钟让作业放开 slot，仍删不掉就发 Warning 事件 `SlotNotDropped`，提示仍在保留 WAL 的 slot。

### 状态与告警

| 渠道 | 内容 |
| --- | --- |
| `status.phase` | `Pending`（还没有匹配的源表，或 Kafka 源的作业还没建）、`Provisioning`（有表在快照、作业在启动或暂停，或 Kafka 源的作业还没运行起来）、`Running`、`Degraded`（源未就绪或 colvenna-catalog 不可达、有表被拒或失败、数据库源的作业没在运行，或对账出错）、`Suspended`、`Failed`（声明无效或目标冲突） |
| 条件 | `SpecValid`、`CatalogReady`、`TargetIsolated`、`SourceReady`、`SourceVerified`、`SpecApplied`、`SlotsReleased`、`JobRunning`、`Governed`、`Published`、`Ready`；MongoDB 源另有 `RetentionWindow`，Kafka 事件源另有 `TargetReady`，Kafka 承载的 CDC 另有 `TransportReady` |
| 事件 | 表状态转换、作业创建/更新/删除/恢复/丢失、slot 删除、`ParallelismPinned`、`UpgradeStuck`、`TargetReadable`、`TlsNotVerified`、目录或源库不可达 |
| 指标 | 在 9100 端口：`colvenna_source_phase`、`colvenna_source_table_info{table,state,reason}`、`colvenna_source_job_running`、`colvenna_source_pending_slots`；NetworkPolicy 只允许带 `colvenna-lake.io/monitoring=true` 标签的命名空间访问。告警规则在 #29 中加入 |

暂停状态取自作业实际状态：所有作业都已挂起且以当前 spec 运行，才判为 `Suspended`；在此之前是 `Provisioning`（正在挂起）。

## Kafka 事件源

`type: kafka` 的 `LakeSource` 接入用户已有的 Kafka（HLD 8）。

- **作业。** 每个源一个 DataStream 作业，按声明的字段解析每条记录，写入一张事件表和一张拒绝表。HLD 8.1 写的是 Flink SQL 作业，这里改用 DataStream，因为拒绝原因、拒绝表不存载荷、按表恢复位点都需要自己控制。
- **集群。** 平台不安装生产 Kafka，验收用的开发 Kafka 见 [M2 控制平面运维](../history/m2/control-plane-operations.md)。
- **认证。** 只走 TLS 上的 SASL：SCRAM-SHA-256/512（MSK、Strimzi），或 PLAIN（Confluent Cloud 的 API key），由 `spec.kafka.mechanism` 选择，默认 `SCRAM-SHA-512`。Kafka 承载的 CDC（`spec.transport.kafka.mechanism`）只接受 SCRAM。MSK IAM 与 mTLS 不在首版范围。
- **证书。** 探测与作业都校验集群的证书和主机名。声明了 `kafka.caConfigMap` 就按它校验，`SourceVerified` 为 True（`Verified`），ConfigMap 里没有 `ca.pem` 时为 False（`CaMissing`）；不声明时按 JVM 的默认信任库校验（JDK 自带的 CA 加上 colvenna-catalog 的 CA），`SourceVerified` 同样为 True，原因 `DefaultTrustStore`，不发 Warning。

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: clicks, namespace: colvenna-data}
spec:
  type: kafka
  kafka:
    bootstrapServers: kafka-bootstrap.kafka.svc:9093
    topics: [clicks]
    credentialsSecret: {name: clicks-kafka}     # SCRAM 用户：username / password
    caConfigMap: kafka-ca                       # 集群 CA（key ca.pem）
    startingOffsets: earliest                   # 表里还没读过的分区从哪里开始
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: event_type, type: STRING, required: true, values: [click, view]}
      - {name: amount, type: "DECIMAL(12,2)", min: 0}
    eventTime: {field: event_time, format: rfc3339}   # 或 epoch-millis
    maxOutOfOrderness: 5m
    unknownFields: keep                         # 未声明的字段进 extra_json；reject 则拒绝
  target: {database: ods_clicks, table: clicks, partitioning: daily}   # 或 hourly
```

**两张表。** 都由 Operator 按声明建立，作业只写：

- 事件表：声明的字段，加 `event_time_ms`、`dt`（`hourly` 时还有 `hr`）、`extra_json`（`unknownFields: keep` 时，这是默认；`reject` 时没有这一列），以及记录在 Kafka 中的位置 `_topic`、`_partition`、`_offset`。按 `dt`（与 `hr`）分区，`bucket=-1`、`write-only=true`，空闲分区写 `_SUCCESS` 标记，供下游批任务调度（HLD 8.2）；
- 拒绝表 `<table>_rejections`：只记原因码、涉及的字段、检测时间和记录位置，不存载荷，因为载荷可能带个人数据（M1 #10）。

原因码：

- `INVALID_SIZE`：空记录，或超过 1 MiB；
- `NOT_JSON_OBJECT`、`MISSING_FIELD`、`WRONG_TYPE`、`TOO_LONG`、`NOT_ALLOWED_VALUE`、`OUT_OF_RANGE`、`BAD_EVENT_TIME`；
- `TOO_OLD`、`TOO_NEW`：超出 `acceptPast`/`acceptFuture`；
- `UNKNOWN_FIELD`；
- `LATE`：事件时间落在 watermark 之后，即晚于 `maxOutOfOrderness`。

记录在 source 里就完成解析，所以 watermark 按每个分区自己的事件时间推进。一个分区读得快（积压、恢复后追赶），不会让其他分区的事件被判 `LATE`；空闲超过 `idleness` 的分区也不会拖住 watermark。

**消费与去重。** Kafka 位点随 checkpoint 保存，与两张表的提交一致，作业重启、升级都不会漏读或重读。

作业状态丢失时（FlinkDeployment 被删），Operator 直接重建作业，不判 `JobStateLost`。两张表本身就是消费记录：每条记录恰好进其中一张。两张表每个 checkpoint 都提交（`commit.force-create-snapshot`，没有数据也提交），但它们是先后提交的，作业恰好在两次提交之间被删时，一张表会多出一个 checkpoint。新作业的处理方法：

1. 以两张表各自最后一次提交中较早的一次为准，两张表在这一点上一致；
2. 从这一点上表中每个分区的最大位点之后继续读；
3. 领先那张表在这一点之后已经写入的记录直接丢弃，不再写一遍。

同一 checkpoint 的两次提交相隔毫秒，相邻 checkpoint 至少相隔 10 秒，所以按提交时间就能区分；为此 Kafka 源的 checkpoint 间隔不能低于 10 秒。表里从未出现过的分区从 `startingOffsets` 开始。consumer group 只用于报告积压，从不决定起点。

**修改。**

- 加可选字段：Operator 在最后一个声明字段之后加列，作业以 last-state 重启。加列之前到达的同名字段仍在 `extra_json` 里；
- 改字段类型、删字段、加必填字段：已有的行跟不上，判为 `SchemaChanged`，不应用。作业继续按上次应用的字段运行（`status.appliedEvents`），暂停、换口令、重建照常生效；
- 改 topic 或集群地址：恢复出来的位点对新的 topic 或集群没有意义，所以 Operator 删掉作业重建，新作业从表中的位点继续；
- 改目标表：判为 `TargetChanged`，不应用，因为旧表和其中的位点会被留下。要换表就声明一个新源；
- 换口令、换 CA：作业重启换用；
- 并行度：可以修改，位点与并行度无关。

**表选项。** 可以声明 `file-index.bloom-filter.columns`、`file-index.bitmap.columns`（HLD 8.2）。分区过期暂不开放：表是作业的消费记录，过期会让它从头重读，由 #28 处理。表是 write-only，没有数据的 checkpoint 也会留下快照，这些快照要等 #27 的维护作业来过期。

**状态。**

- `status.kafka`：以源自己的用户探测集群，记录每个 topic 的分区数、末端位点、group 已提交位点和积压。探测会从每个分区末端拉取一次，所以能看出用户能 Describe 却不能读的情况。探测失败时 `SourceReady` 为 False，并发 Warning `Source<原因>`（Kafka 承载的 CDC 是条件 `TransportReady` 为 False，Warning `Transport<原因>`），原因为：
  - `CaMissing`：声明的 `caConfigMap` 不存在或没有 `ca.pem`；
  - `CredentialsMissing`：凭证 Secret 不存在或缺键；
  - `Unreachable`；
  - `TlsFailed`：集群 CA 或主机名不对；
  - `AuthenticationFailed`：口令失效；
  - `NotAuthorized`：缺读 topic 或 group 的 ACL；
  - `TopicMissing`；
- `status.events`：两张表最新快照的行数，以及按原因统计的拒绝数。拒绝数超过 `reconcileMaxRows` 时只统计前面那部分，此时 `rejectedByPartial` 为 true；
- 指标：`colvenna_source_kafka_lag{topic}`、`colvenna_source_events_accepted`、`colvenna_source_events_rejected{reason}`。

**删除。** `Delete` 删除两张表；consumer group 留在用户的 Kafka 里。

**发布。** 事件表与拒绝表按数据库源的规则发布给 `readers`（见"治理与个人数据删除"）：两张表都受治理时才发布（条件 `Governed`、`Published`）。事件源的表没有受管的保留期，也不能做个人数据删除（见"已知限制"），所以含个人数据或没有分类的事件源不发布（`PersonalEvents`、`Unclassified`）。M3 的事件源接受 `readers` 却没有发布，这一点在 #38 补上：批量质量的门禁要拦的正是按天分区的事件表。

## Kafka 承载的 CDC

MySQL 源可以改从用户的 Kafka 读变更（HLD 7.6），不再直连 binlog。适用于两种情况：需要独立于 binlog 保留期的回放缓冲；同一 binlog 有多个消费方、DBA 限制连接数。采集器（Debezium MySQL connector，或 Debezium Server）和 Kafka 由用户提供。目标表、主键表设计和下游都不变。

```yaml
spec:
  type: mysql
  connection: {...}                  # 仍用于准入与对账
  transport:
    kafka:
      bootstrapServers: kafka-bootstrap.kafka.svc:9093
      topicPrefix: shop-cdc          # connector 的 topic.prefix，表的 topic 为 <prefix>.<database>.<table>
      credentialsSecret: {name: shop-kafka}
      caConfigMap: kafka-ca
      overlap: 10m                   # 无状态启动时，从目标表最后一次写入前多久开始读
```

**事件格式。** Debezium JSON，有两个要求：

- 带 schema（JSON converter 打开 `schemas.enable`），否则 Paimon 把每列都当成字符串；
- connector 设置 `column.propagate.source.type=.*`，否则 Paimon 按猜出的类型（如把 VARCHAR(32) 当 STRING）加宽目标表的列。

DECIMAL 列两种形式都接受：JSON converter 默认的 base64（未缩放值的字节，`decimal.format=BASE64`），和数值（`NUMERIC`）。Paimon 的解析器只读数值，作业先把 base64 换成数值再交给它。

以下记录不是可用的行变更，作业丢弃并计数（`status.transport.unparsable`），同时发 Warning `UnparsableEvents`：

- 不是 JSON；
- 没有 schema，或 schema 里没有源端列类型；
- `op` 不是 c/r/u/d；
- delete 没有 before 镜像。

日志只记 topic、分区和 offset，不记载荷。作业自己构建 Kafka source，因为 Paimon 自带的反序列化器碰到这类记录会打印载荷，并且让作业一直失败。delete 之后的 tombstone 不是变更，直接跳过。

topic 必须是 `cleanup.policy=delete`：压缩（compact）的 topic 只保留每个键的最后一条，会丢掉删除事件。探测发现压缩的 topic 时，`TransportReady` 为 False，原因 `TopicCompacted`。为此源的 Kafka 用户还需要 DescribeConfigs 权限。

源端契约先于一切检查：版本没有上升的变更仍然让作业停止，与直连相同（HLD 7.4），不会被当作重复变更丢掉。

**重复与乱序。** Kafka 可能重复投递（采集器 at-least-once、回放、重新快照），也可能乱序。按以下方式处理：

- 目标表改用 `changelog-producer=lookup`、`changelog-producer.row-deduplicate=true`、`write-only=false`，与 `sequence.field` 配合。旧版本被丢弃，原样重写的行不产生 changelog，所以表和 changelog 都不变；
- 作业里的 guard 记住每行最近见过的版本，最多 20 万行，只在内存里。版本不高于已见或已删版本的变更直接丢弃，计入 `status.transport.stale`；
- 采集器丢了位点、从头重新快照时，快照里只有现存的行。它没有位点的那段时间里被删的行，不在任何记录里，表里会一直留着。处理见下文"采集器重新快照"；
- guard 记不住的行，由上一条机制吸收。只有被删除的行吸收不了：lookup 压缩很快就丢掉删除标记，之后再回放它的旧变更，它会重新出现，直到删除也被回放，下游会看到一次插入和一次删除。作业启动时从目标表的 changelog 读出最近被删除的行交给 guard，覆盖 overlap；更早被删、或者在本次运行中被删但已被挤出这 20 万行的行，不在保护范围内。

**采集器重新快照。** 采集器丢了位点（例如它的 offset 存储没了）就会从头快照。新快照不会带来它没有位点期间的删除，只有源库知道这些行已经没了：

1. 作业把这种快照的记录计入 `status.transport.resnapshot`：本次运行里已经流读过的表又来了快照记录，或者快照时间晚于目标表最后一次写入。增量快照（`source.snapshot=incremental`）不丢位点，不计。
2. 计数增加时，Operator 发 Normal 事件 `SnapshotAgain`，把这些 `Ready` 的表标为待对账（`recheckSince`）。等快照停下：计数每次增加都重新计时，等四个 checkpoint 间隔，至少两分钟。然后按"对账"一节与源库重新对账。
3. 源库已经没有、目标表追上之后仍然有的行，就是没送到的删除。Operator 发 Warning `DeletesLost`，以 last-state 挂起作业（`status.repair`），写入这些删除，再恢复作业，发 Normal 事件 `DeletesRepaired`。
4. 删除完之后再对账一次，对上了发 `TableReconciled`。

几点说明：

- 作业是这些表唯一的写入者：运行中的写入者看不到别人的提交，它恢复时才重新读表的文件，此后的 lookup 压缩把删除送进 changelog，下游照常收到。
- 每条删除带着这一行的值，版本加一，所以之后重放这一行的最后一次变更，不会把它带回来。
- Operator 的提交以 `colvenna-repair` 为 commit user 前缀。作业无状态启动时推算起点，不把它算作目标表的最后一次写入。
- 主键可能是个人数据：要删的主键只放在 Operator 内存里，状态里只有表名。Operator 重启后丢了它们，就恢复作业，重新对账。
- 对账以 `reconcileMaxRows` 为上限，超出时发 Warning `TableTooLarge`，表不会被修复。
- 复核要等目标表追上源库的读取时刻。没人写的表不再提交，这时改看作业的进度（#238），只用于声明了心跳、心跳表与这张表在同一个作业里的 MySQL 源：心跳由源库写，与源库的变更同序。直连时，湖里最新的心跳写于读取时刻之后，就说明作业已按 binlog 的顺序提交了此前的全部变更。经 Kafka 承载时各表的话题之间没有顺序：心跳进湖只说明采集器读过了读取时刻，还要这张表的话题在心跳提交之后被作业的 consumer group 消费到末尾（已提交位点等于末端，不是延迟为零：话题重建后 group 的位点可能超过末端）。group 的位点只用来报告积压、不作起点（见下），在这里也只作"已读到"的证据，它与表的提交是同一次 checkpoint 完成后的两个回调，可能差几秒：所以缺行要在连续两次复核里都在，才发 Warning `RowsMissing`，不靠新提交判定时，补删也要连续两次。PostgreSQL 每张表一个作业与复制槽，MongoDB 没有心跳：只认新的提交。
- 等新提交超过应等到的时刻 10 分钟（#245），每次源库读取报一次：有进度信号的源（带心跳的 MySQL）发 Warning `TargetNotCommitting`，作业没有把变更传下来；其他源发 Normal 事件 `WaitingForCommit`，空闲的表本来就要等下一次写入。
- 作业在采集器首次快照期间重启，也可能计入快照记录；这时对账对得上，只多了一次对账。

**无状态启动的起点。** 适用于从直连切换过来、作业被删后重建两种情况。consumer group 的位点与表的提交互不相干，只用来报告积压，不能作为起点。起点由作业从目标表推出：

- 写过的目标表，它的 topic 从所有目标表最后一次写入（APPEND 快照，不算压缩）之前 `overlap` 处开始读；
- 从未写过的目标表，它的 topic 从头读；
- 这段时间内被删除的行（向前再取一个 `overlap`），在 guard 里标为已删除。

条件：`overlap` 要长于上一个写入者停止时的延迟，也要长于采集器的延迟；快照保留时间要长于两倍 `overlap`。以下情况作业以 `POSITION_UNKNOWN` 失败，不去猜：

- 表里只剩压缩快照；
- 保留时间不够；
- 声明了 `snapshot.num-retained.max`，无法知道过期掉的快照有多旧。

两倍 `overlap` 内被删除的行超过 200 万时，guard 装不下，作业以 `OVERLAP_TOO_LONG` 失败，需要缩短 `overlap`。

这个失败只在 source 真的需要起点时才生效；从 checkpoint 恢复的作业不需要起点，照常运行。

保留期已经删掉的记录里，可能有表缺的变更。以下情况无法证明没有：

- 从头读的分区开头已被删掉；
- 定时起点的分区，剩下最早的一条已经晚于起点，或者一条也不剩。

这时作业照常启动，但计入 `status.transport.unverified`，并发 Warning `TransportUnverified`。

**切换。** 给运行中的直连源加上 `spec.transport.kafka`：

1. Operator 先删除直连作业，等它的 FlinkDeployment 消失，确保它最后一次提交已落盘；
2. 创建 Kafka 作业 `<name>-kafka`，使用 kafka 镜像，以 Kafka 用户登录；
3. 作业在写入任何数据之前处理每张直连留下的目标表：先在 `input` changelog 下做一次完整压缩，这不会产生 changelog；再把表改成上面的 changelog 设置。顺序不能反：直连写入端是 write-only，行都在没有压缩过的 level 0 文件里，先改成 lookup 的话，第一次 lookup 压缩会把这些行全部再发布一遍；
4. Operator 看到表已切换后发 Normal 事件 `ChangelogSwitched`，并在表状态的 `changelog` 中记录。

在 overlap 内重读的变更被丢弃或吸收；overlap 之后的变更正常写入。表和下游 consumer 都不变，下游流读没有重放，也没有缺口。

改集群地址或 `topicPrefix` 时，恢复出来的位点失去意义，Operator 删掉作业重建，新作业从表推出起点。反向切换（去掉 transport）时，新的直连作业会对已有数据的表重新快照，所以会被拒绝（`JobStateLost`），需要重新声明。

**状态。** `TransportReady` 条件以源的 Kafka 用户探测各表的 topic，结果和 group 积压写进 `status.kafka`，原因码与 Kafka 事件源相同，另有 `TopicCompacted`。`status.transport` 记录作业本次运行的 `unparsable`、`stale`、`unverified`。这些是 Flink 指标，任务重启后归零，所以计数一有变化就告警；每条被丢弃的记录都在作业日志里。

## MongoDB 源

`type: mongodb` 的 `LakeSource` 接入 MongoDB 的集合（HLD 7.2）。按 `tables.include` 选择集合，可以是单个集合，也可以是数据库范围。每个集合进一张目标表：以 `_id` 为主键，其他顶层字段都是 STRING，嵌套文档和数组存为 JSON 文本。

```yaml
spec:
  type: mongodb
  connection:
    host: mongo-0.mongo.shop.svc    # 副本集成员；驱动从它发现整个副本集
    database: shop
    credentialsSecret: {name: shop-mongo}
    caConfigMap: mongo-ca           # 可选；作业把 CA 加进 JVM 的信任库
    authSource: admin
    minOplogWindow: 24h             # oplog 至少要覆盖多久，不足则告警
  tables: {include: [orders, customers, products]}
  target: {database: ods_shop}
```

**证书。** 连接一律 TLS，探测与作业都校验服务端证书和主机名。声明了 `connection.caConfigMap` 就按它校验（作业 Pod 的 init 容器把它加进 JVM 信任库），`SourceVerified` 为 True（`Verified`）。不声明时，探测（`SSLContext.getDefault()`）与作业（连接串里的 `tls=true`）都按 JVM 的默认信任库校验，也就是 JDK 自带的 CA 加上 colvenna-catalog 的 CA；`SourceVerified` 同样为 True，原因 `DefaultTrustStore`，不发 Warning。证书由其他私有 CA 签发的 MongoDB 必须声明 CA，否则探测连不上（`SourceUnreachable`）。

**准入。** Operator 以源的用户连接，检查以下各项，任何一项不满足，作业都暂停：

- 是副本集：单机没有 change stream；分片集群在这一版没有验证，也不接受；
- 版本 4.0 到 7.x，这是 Flink CDC 3.5 支持的范围；
- 用户对该库有 `find` 与 `changeStream` 权限。

逐个集合再抽样查 `_id` 的类型。只接受 ObjectId 或字符串，因为这两种目标表能按原样保存；其他类型（例如整数）判为 `UnsupportedKeyType`，不纳管。作业对每个变更再查一次，抽样漏掉的键也会被拦下：整数 `5` 与字符串 `"5"` 在目标表里会成为同一行。MongoDB 的 change stream 按集合有序，所以不需要版本列，也不需要源端触发器。

**作业启动后出现的集合不加入它（`AddedAfterStart`）。** MongoDB 源的范围在作业启动时取定：之后才进入范围的集合（新建的、放宽 `include` 才选中的、修好了的被拒集合）判为 `Rejected`，并发 Warning，要读它就另声明一个 `LakeSource`（用另一个目标库；新作业启动时一并快照）。原因：Flink CDC 3.5 往运行中的作业加集合靠它的 newly-added-table 流程；这个流程最后一步（stream split 换上新集合的快照信息）之后、下一个 checkpoint 完成之前如果发生 failover，作业从更早的 checkpoint 恢复，enumerator 却留在"已完成"，stream split 从此没有这个集合，它的变更被静默丢弃（FLINK-40741，上游只修了 MySQL 连接器，MongoDB 用的通用框架 3.7 仍未修）。Paimon 的 committer 在从 checkpoint 恢复后会有意让作业失败一次，正好落在这个窗口里：#30 的 EKS 验收就这样丢了后加集合的全部变更，本地也稳定复现。作业启动时就在的集合走初始快照，failover 不影响。

Operator 记下每个集合加入作业时的 UUID。集合被删除后重建，或被另一个集合 rename 覆盖，change stream 都不会告诉作业，但 UUID 会变。这时该表判为 `Failed`（`SourceReplaced`），因为目标错过了这些变更；要恢复只能全量重读：另声明一个源（另一个目标库）读它。

**oplog 窗口。** `status.retention` 记录 oplog 从最早到最新覆盖多少小时。这需要用户能读 `local.oplog.rs`，没有权限时记为未知。短于 `minOplogWindow` 或未知时，条件 `RetentionWindow` 为 False（原因 `Short` 或 `Unknown`，足够时为 `Sufficient`），并发 Warning `RetentionWindowShort` 或 `RetentionWindowUnknown`，但作业照常运行。作业停机超过这个窗口就无法续读。续读点已不在 oplog 里时（`ChangeStreamHistoryLost`），该作业的所有表判为 `Failed`，需要重新声明再全量读，不会悄悄跳过变更。

**作业。** 整个源一个作业，即 Paimon 的 MongoDB 整库同步，只读已准入的集合。作业自己构建 source，原因有二：

- newly-added-table 扫描保持开启：作业带着更少的集合恢复时，从 stream split 里去掉它们（Operator 不往运行中的作业加集合，见上文）；
- 连接强制 TLS；
- 开启 heartbeat：集合长时间没有变更时，续读点也随 oplog 前移，checkpoint 里不会留下 oplog 已经丢掉的续读点。

目标表使用 `changelog-producer=lookup`、`row-deduplicate`、`write-only=false`，因为删除事件只带键，更新事件不带前镜像，changelog 的前镜像只能来自表中原有的行。

作业里的 guard 处理两种变更：

- 文档在 change stream 回查之前就被删除的更新，没有文档可写。跳过并计数（`colvennaUpdateGone`），随后的删除会把该行删掉；
- `_id` 既不是 ObjectId 也不是字符串的变更。作业以 `SOURCE_UNSUPPORTED_KEY table=<集合>` 停止，Operator 只把这张表移出作业（`Failed`），其余照常。

Flink CDC 只把文档变更交给作业，drop、rename 由 Operator 从源端的集合清单发现：集合消失时，表移出作业，并发 Warning `TableGone`，目标保留已有的行；集合被替换时按上文判为 `SourceReplaced`。

**新字段。** Paimon 按文档推断列，新出现的顶层字段在目标表上加一列 STRING。

**对账。** 按 `_id` 比较源端与目标的键集合，内容由验收比对逐字段检查。

## LakeTable

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: orders-dwd, namespace: colvenna-data}
spec:
  target: {database: dwd_shop, table: orders}      # 库属于本命名空间的分层表
  primaryKey: [order_id]
  upstream: [ods_shop.orders, ods_shop.segments]   # 查询读到的每张表
  query: |
    SELECT o.id AS order_id, s.label AS segment, o.amount, o.version_no
    FROM (SELECT *, PROCTIME() AS pt FROM ods_shop.orders) AS o
    JOIN ods_shop.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id
  bucket: 2
  tableOptions: {}                                 # 同 LakeSource 的白名单
  job: {parallelism: 1, checkpointInterval: 30s}
  suspend: false
  deletionPolicy: Retain                           # 或 Delete
```

每张表一个常驻 Flink SQL 作业，`INSERT INTO <target> <query>`（HLD 9.1）。作业入口 `SqlJob` 用代码注册 catalog，token 不出现在 SQL 文本和执行计划里。用户不接触 FlinkDeployment。HLD 9.1 列出的 consumer-id、write-only 与升级模式由 Operator 决定，不作为声明字段，理由见下文。

### 依赖

- **上游的范围。** 命名空间是边界：`LakeTable` 只能读本命名空间 `LakeSource` 的 ODS 表，以及本命名空间其他 `LakeTable` 的目标表。依据是库或表上的 `colvenna-lake.owner`。否则能在命名空间里声明表的人，就能把目录里任何一张表复制到自己能读的表里。越界判为 `UpstreamNotAllowed`。
- **上游不存在。** 状态为 `Pending`（`UpstreamMissing`），等上游出现，例如下游声明得比上游早。
- **环。** 读自己判为 `InvalidSpec`。经过其他表绕回自己判为 `DependencyCycle`。每张表的边取它声明的和它正在运行的上游之并集。检查和登记在同一把锁里，所以两张互读的表无论同时声明还是先后修改，都不会同时通过。
- **目标。** 目标库由 Operator 建，记下 `colvenna-lake.owner=LakeTables/<namespace>`；`LakeSource` 的库和别处的库都不能作目标（`TargetConflict`）。目标表记下所属的 `LakeTable`，不接管别人的表。两个声明写同一张表时，先创建的保留它（同一秒内按 uid），另一个判为 `TargetConflict`。

### 查询校验

每次查询、上游、主键或目标变化，Operator 起一个 Kubernetes Job 跑 `SqlValidate`。它用的是作业镜像里同一个 Flink 1.20 planner 和连接器，以这张表自己的 catalog 身份运行，所以只读得到声明的上游。Job 没有 Kubernetes API 凭证，根文件系统只读，最长 5 分钟。结论写在 Pod 的终止消息里（gzip、base64 的 JSON），只有校验程序会写这个文件，查询文本里的内容进不了结论。

| 原因 | 判定 |
| --- | --- |
| `InvalidQuery` | 不是单条合法的 SELECT |
| `UpstreamNotDeclared` | 查询读了没声明的表（catalog 返回 403） |
| `UpstreamMismatch` | 声明了、查询却不读的表 |
| `OutsideCatalog` | 读了 `colvenna` 以外的 catalog |
| `UnsupportedColumn` | 输出列没有名字，或是时间属性列 |
| `KeyColumnMissing` | 主键列不在输出里 |
| `UnorderedChangelog` | 同一主键的更新可能乱序到达 sink，见下文 |

校验通过后，结论里有输出 schema（Paimon 字段）、流读的表和 lookup 的表。作业启动时还会再校验一次，因为上游 schema 可能已经变了。这次再校验和校验 Job 一样用 planner 的默认配置，所以同样查得出 `UnorderedChangelog`。声明了 `quality` 的表，作业在再校验之后才把 `table.exec.sink.upsert-materialize` 设为 `NONE`，再跑自己的语句：`quality` 的检查是 changelog 上的过滤，可能让 planner 加上 upsert materializer，而 Paimon 不接受它。

### 目标表与 changelog

目标表按校验得到的 schema 建：主键列为 NOT NULL，`merge-engine=deduplicate`、`changelog-producer=lookup`，`consumer.expiration-time` 默认 `1 d`（同 LakeSource）。

**changelog 由 Paimon 生成。** 下游（如 DWS）流读目标表的 changelog，每条撤回必须等于表中原来的那一行，否则下游聚合会多减或少减。作业自己发出的撤回做不到这一点：

- lookup join 在订单变化时会重新查维表。维表改过之后，旧行的撤回带的是新的维度值。
- 升级后的查询用新逻辑计算撤回。

所以目标表用 `changelog-producer=lookup`：Paimon 在压缩时，拿每个变化的主键和表中原来的行比较，生成 changelog，不信任作业发出的撤回。这种 changelog 需要压缩，所以分层作业的写入端自己压缩，不是 write-only。这与 HLD 11.1 的设想不同：HLD 设想分层作业 write-only，由维护作业生成 changelog。#27 的维护任务是按需的一次运行，不是常驻的压缩者：changelog 若由它生成，下游的可见延迟就取决于任务多久跑一次，所以写入端仍自己压缩，维护任务只做额外的整理（如全量压缩）。

目标表还设 `changelog-producer.row-deduplicate=true`：重写一行而值没变时，不产生 changelog。历史回刷（见 "LakeTask"）会重写每一行，下游只应看到真正变化的那些。

**同一主键的更新必须有序。** 典型的反例是 join 键不在目标主键里的普通 join：同一行的旧值和新值来自不同的 join 子任务，到达 sink 的先后不定，表里的值可能被旧值覆盖。planner 会为此加 SinkUpsertMaterializer，它要在状态里保存每个主键的所有行版本。M3 不支持这种查询，校验程序探测到 planner 会这样做，就判为 `UnorderedChangelog`。改用 lookup join 或以 join 键为主键可以绕开。

### 作业与升级

作业是 savepoint 升级模式的 FlinkDeployment，其余同"作业形态"。每张流读的上游都带 `consumer-id`（`<namespace>.<name>.<uid 前 8 位>`，通过 Paimon 的 `paimon.colvenna.<db>.<table>.consumer-id` 动态选项设置），lookup 表不设。`status.upstream` 记录每张上游的 consumer 位置与落后的快照数（`lag`），指标为 `colvenna_table_upstream_lag_snapshots`。

| 变化 | 行为 |
| --- | --- |
| 资源、并行度、checkpoint 间隔、token、镜像 | 以 savepoint 有状态升级：旧作业停在 savepoint，新作业从它恢复 |
| 查询（输出 schema 与流读的表不变） | 先校验，再以 savepoint 升级。已有的行要等它的上游行变化时才按新查询重算；下游始终与目标表一致，因为 changelog 来自表中的行。历史回刷是单独的流程 |
| 输出 schema 或主键变化 | 不应用（`SchemaChanged`），旧作业照常运行。已有的行不会随新查询变化，需要新声明一张表 |
| 流读的上游表集合变化 | 不应用（`StreamsChanged`）：新加的流读表没有 consumer，作业分不清它和丢失的 consumer；去掉的表会留下 consumer |
| 目标表变化 | 不应用（`TargetChanged`）：作业状态不能搬到另一张表 |
| 声明暂时无效（校验未过、依赖不满足） | 已运行的作业保持原样，`phase: Degraded`，原因写在条件里 |
| `suspend: true` / `false` | 以 savepoint 挂起，再从它继续 |

schema 是否相容，Operator 与作业用同一条规则判断：列名与类型相同；目标的 NOT NULL 列不能收到可能为 null 的值。

**失败回退。** 一次升级在 10 分钟内没有以新 spec 运行起来，Operator 用这次升级取得的 savepoint，把作业重新部署回上一份稳定的 spec。M1 #13 在 PoC 集群上测到，作业从停止到 RUNNING 最长约 7.7 分钟。典型的失败原因是查询变了，savepoint 里的算子状态对不上新计划（`Cannot map checkpoint/savepoint state for operator ...`）。

重新部署通过 `savepointRedeployNonce` 完成。之后 `status.rolledBack` 记下失败的变化，`SpecApplied` 为 False（`RolledBack`），并发 Warning `UpgradeRolledBack`。声明再次变化之前，这个变化不会重试；改回原来的声明，条件即恢复。改成别的声明时，先等回退的作业运行起来（`SpecApplied` 为 False，原因 `RollingBack`，最多 10 分钟），再以它的 savepoint 升级：还没运行的作业取不了 savepoint，升级也就没有可回退的地方。

为什么不用 Flink Operator 自带的回滚：它要求 HA 元数据还在，或者新的 JobManager 根本没启动过。而作业恢复失败时，Flink 会删掉 HA 元数据，正好卡在中间（#24 的升级卡住也是这个原因）。

**回退的前提是能证明新作业没写过目标表**，否则旧作业会把同样的输入再处理一遍，叠加在新作业的结果上，产生重复的业务效果。

Paimon 只在 checkpoint 完成时提交快照，快照记下该 checkpoint 的编号，而 checkpoint 编号跨 savepoint 连续。升级开始时，Operator 从旧作业的 JobManager 记下它用过的最大 checkpoint 编号 C（`status.upgrade.fromCheckpoint`）：已完成、失败与进行中的 checkpoint、savepoint，以及它恢复所用的那个，一个都没有时为 0；同时记下它完成过几个 checkpoint（`fromCheckpointsCompleted`）。旧作业接着做的 savepoint 不早于 C+1，它的提交都不晚于这个 savepoint；新作业从这个 savepoint 恢复，提交都在它之后。证明有两种：

- 新作业的 JobManager 报告它完成的 checkpoint 数为 0，而且它恢复所用的正是这次升级取的 savepoint。只看数目不够：JobManager 重启后数目从 0 重新算，但重启的 JobManager 从作业自己最近的 checkpoint 恢复，不是从那个 savepoint；
- 否则（问不到 JobManager，例如恢复失败后 Flink 已把作业清掉；或它恢复的不是那个 savepoint），目标表最新快照的 checkpoint 编号不大于 C+1。

C 不能只取旧作业最后完成的 checkpoint。旧作业刚从 savepoint 恢复、还没完成第一个 checkpoint 就开始升级时（checkpoint 间隔之内），它没有完成过 checkpoint，这时 C 是它恢复所用的那个编号（#279：`v1.0.0-rc.3` 的 UAT 里，升级在旧作业 RUNNING 之后 16 秒开始；只取已完成的 checkpoint 时 C 为空，安全的回退被拒）。

C+1 是下界，不是 savepoint 的确切编号：Operator 读过旧作业的 checkpoint 之后、savepoint 触发之前，若旧作业又触发了一个 checkpoint，savepoint 就是 C+2 或更晚，它的提交晚于 C+1，第二种证明不成立，回退被拒。这样的拒绝在安全的一侧，不会误判为没写过。savepoint 的确切编号读不到：Flink Operator 记录的 savepoint（`upgradeSavepointPath`、`FlinkStateSnapshot`）只有路径与时间，旧作业停下后它的 JobManager 也不在了，Operator 又没有读 Flink 状态目录（savepoint 的 `_metadata`）的权限。新作业的 JobManager 答得上时，第一种证明不受这个影响。

Flink Operator 记录的作业开始时间不能作证据：作业恢复失败时它不更新，仍是旧作业的时间（2026-10-15 的 #26 试跑中实测如此）。

此外，升级必须确实留下了新的 savepoint，不能是 last-state 回落时的占位路径。回退用的是 Flink Operator 记下的 `upgradeSavepointPath`，即这次升级取的那个；`status.upgrade.fromSavepoint` 是升级之前的那个，只用来判断升级有没有取新的。

**证明不了时。** 不回退，判为 `UpgradeFailed`，条件里写明依据与能做的事。Operator 每一轮都再判一次，证据变了（例如目标表又读得出了）就回退。

| 依据 | 能做的事 |
| --- | --- |
| 新作业完成过 checkpoint | 它运行过，可能已提交；重启策略不让它进入终态，HA 元数据还在。改声明后 Flink Operator 以 last-state 应用，从新作业自己最近的 checkpoint 继续，不会重复处理输入。或者重建这张表 |
| 其余（问不到新作业的 JobManager 或它的数目不作数，而 C 未知、目标表读不出，或最新快照晚于 C+1） | 消息给出怎样看新作业写没写：目标表在 checkpoint C+1 之后提交的快照（C 未知时，升级开始之后提交的）是新作业的。新作业运行过就改声明，同上一行；否则重建这张表 |
| 升级没有取得新的 savepoint | 改声明或改回：作业从它自己最近的 checkpoint 继续。或者重建这张表 |
| 没有运行过的上一版 | 修正声明 |

重建是用户手册第 4.3 节的做法：另声明一张表，或以 `deletionPolicy: Delete` 删掉后重新声明，作业从空状态开始，不会重复处理输入。Operator 不提供跳过证明的强制回退：新作业写过时，它会让旧作业把同样的输入再处理一遍。

**被拒之后改声明。** 改回原来的声明或改成修正后的声明时，Operator 先按当时的证据再判一次，不再等 10 分钟：能证明，就立即从升级取的 savepoint 回到上一版（改回的声明随即生效）；修正后的声明等回退的作业运行起来，再以 savepoint 升级，最多等 10 分钟。仍不能证明，声明照常交给 Flink Operator，重新计时。以前的做法是直接把声明交给 Flink Operator，而作业没有可以 savepoint 的运行实例，也没有 HA 数据，Flink Operator 应用不了，作业一直停着（#279）。这样触发的回退，那一轮里 `JobRunning` 为 `RollingBack`，Operator 很快再看一次。

**丢失上游位置。** 作业启动时检查每张流读上游的 consumer（M1 #13 的规则）。consumer 不见了而目标表已有数据，或者 consumer 落后于上游最早保留的快照，都说明有变更可能已过期、没被读到。作业以 `REBUILD_REQUIRED` 拒绝启动，状态为 `RebuildRequired`，需要重建这张表。

**作业被删。** 运行过的作业在 Operator 之外被删除时，判为 `JobStateLost`，不重建：从头重建会让聚合等有状态算子从零开始，结果覆盖到已有的行上。

### 状态

| 渠道 | 内容 |
| --- | --- |
| `status.phase` | `Pending`、`Validating`、`Provisioning`、`Running`、`Degraded`、`Suspended`、`Failed`（声明无效、依赖不满足或冲突，没有部署作业；查询校验之前已建好的目标库与 catalog 身份会留着） |
| 条件 | `SpecValid`、`DependenciesReady`、`CatalogReady`、`QueryValid`、`QualityValid`、`TargetReady`、`SpecApplied`、`JobRunning`、`Governed`、`Published`、`Ready` |
| `status.fencedBy` | 回刷的栅栏让作业挂起之后，记下栅栏属于哪个 `LakeTask`（见 "LakeTask"） |
| 事件 | `QueryAccepted`、`TableCreated`、`JobCreated`/`JobUpdated`/`JobUpgraded`/`JobRestored`、`JobStartedWithoutState`、`JobLost`、`JobNotRunning`、`UpgradeRolledBack`、`UpgradeFailed`、`RebuildRequired`、`TargetRetained`、`NotGoverned`、各拒绝原因 |
| 指标 | `colvenna_table_phase`、`colvenna_table_job_running`、`colvenna_table_upstream_lag_snapshots` |

### 删除

finalizer 先删作业并等它消失，再删掉作业在各上游上的 consumer（否则它会一直挡住上游快照过期），然后撤销身份。`deletionPolicy: Delete` 时删除这张表建的目标表，即使作业从没部署过。如果还有别的 `LakeTable` 读它，就保留并发 Warning `TargetRetained`。

## LakeTask

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: orders-backfill-20261015, namespace: colvenna-data}
spec:
  type: backfill                     # 或 compact、expireSnapshots、removeOrphanFiles、createTag、deleteTag、check
  table: {database: dwd_shop, table: orders}
  retries: 2                         # 首次之后的重试次数，0 到 5
  timeout: 1h                        # 一次运行的上限
  resources: {cpu: 0.5, memory: 2048m}
  # 各类型自己的参数，例如：
  # compact: {full: true, partitions: ["dt=2026-10-14"], manifests: true}
  # expireSnapshots: {retainMin: 10, olderThan: 1h}
  # removeOrphanFiles: {olderThan: 1d}
  # createTag: {name: eod-20261015, timeRetained: 7d}
```

一个 `LakeTask` 是对一张受管表的一次运行（HLD 9.2、9.3、11）：分层表的历史回刷、压缩、快照过期、孤儿文件清理、日终 tag，或批量质量校验（`check`，M4，见"批量质量校验"）。

**只运行一次。** spec 创建后不可修改：CRD 的校验规则 `self == oldSelf` 让 API server 直接拒绝修改。再 apply 同一份声明，什么也不会发生。要再跑一次，就建一个新任务。编排器的重试也落在这一点上：它按名字找到已有的任务，等它的结果，不会再跑一遍。

**表的范围。** 任务的表只能是下面两种之一，依据都是 `colvenna-lake.owner`：

- 本命名空间 `LakeSource` 的 ODS 表（看库的 owner）；
- 本命名空间 `LakeTable` 的目标表（看表的 owner）。

回刷只能对 `LakeTable` 的目标表，并且：

- 这张表的声明已经应用（`SpecApplied`）：回刷跑的是作业正在运行的那个查询；
- 它的查询没有状态。查询校验在物理计划里找聚合、普通 join、temporal join、rank、去重和窗口算子，结论记在 `status.validation.stateful`。有状态的作业从 savepoint 恢复后，会按状态里的旧值继续计算，回刷的结果留不住；lookup join 每次都现查维表，没有这种状态。

压缩写入端不压缩的表（`write-only=true`，例如 binlog 接入的 ODS 表和 Kafka 事件表）时直接运行。写入端自己压缩的表再来一个压缩者，两边的提交会冲突：分层表的压缩像回刷一样先挂起它的作业（围栏），压完再恢复，不需要收敛；lookup changelog 的 ODS 表（Kafka 承载或 MongoDB 的源）没有可挂起的分层作业，拒绝（`WriterCompacts`）。

不满足时判为 `Failed`，原因是 `TableMissing`、`NotManaged`、`NotALakeTable`、`TableNotApplied`、`StatefulQuery` 或 `WriterCompacts`，不建任何 Job。参数本身无效（例如 `check.asOf` 在将来）为 `InvalidSpec`；给含个人数据或没有分类的表打保留超过上限的 tag 为 `RetentionTooLong`（见"治理与个人数据删除"）；`createTag` 的表还没有快照（例如刚开始运行、还没提交过的分层表）为 `NoSnapshot`；`check` 另有自己的原因（见"批量质量校验"）。这些检查只在任务开始前做一次：开始之后，别处的变化（比如 `LakeTable` 的声明改了）不会让它半途而废。

`createTag` 的表没有快照时，任务判为 `Failed`（`NoSnapshot`），与 `check` 一致，不在 `Pending` 里等第一个快照：一张表什么时候提交、会不会提交，任务看不出来，而 `timeout` 只限单次尝试、不限准入，等待就可能没有尽头；`NoSnapshot` 是一个确定的终态，编排器可以据此等表提交之后再建任务。快照的检查排在"一次一个"之后：前面的任务（例如回刷）可能正好提交第一个快照。其余类型不需要最新快照：压缩、过期、清理在空表上什么也不做，`deleteTag` 只看 tag，回刷整表重算。

**一次一个。** 任务"触及"的表是它的表，回刷还包括它读的上游表。触及同一张表的任务依次运行：

- 已经开始、还没结束的任务总在前面；
- 其余按创建时间排（同一秒内按 uid）。

排在后面的任务停在 `Pending`，消息写明在等哪个任务。所以快照过期不会和读它的回刷同时跑。

### 运行

每次尝试是一个 Kubernetes Job，跑 sql 作业镜像里的 `TaskJob`：Pod 内的 Flink batch，符合 M1 #17 的批处理边界（[批处理边界](../history/m1/batch-engine-boundary.md)）。

- **身份。** 任务有自己的 catalog 身份：对这张表 `WRITE`，回刷还对 `LakeTable` 的每张上游 `READ`。
- **隔离。** Job 没有 Kubernetes API 凭证，根文件系统只读，`backoffLimit: 0`，`activeDeadlineSeconds` 等于 `timeout`。
- **结果。** 运行前后的快照号和做了什么，写在 Pod 的终止消息里，只有运行程序会写这个文件。

| 类型 | 做法 |
| --- | --- |
| `compact` | `CALL sys.compact`，`full: true` 时 `compact_strategy => 'full'`，`partitions` 只压这些分区；等它完成（`table.dml-sync`）。全量压缩之后再压缩 manifest，manifest 里只留表现有的文件和它们的统计；写 Iceberg 元数据的表默认不压缩 manifest（见"开放层：Iceberg 发布"），`manifests` 可以显式指定 |
| `expireSnapshots` | `sys.expire_snapshots(retain_min, older_than)`。Paimon 保留每个 consumer 还没读到的快照和每个 tag 的快照，所以流读的读端（包括挂起的读端，只要它的 consumer 还没过期，`consumer.expiration-time` 默认 `1 d`）需要的数据不会被清理。按快照的批读（Doris、批作业、lookup join 的增量刷新）没有 consumer：`olderThan`（默认 `1h`）要长于它们读一个快照的时间，需要长期读的快照用 tag。之后过期到期的 tag（`sys.expire_tags`）和闲置超过 `consumer.expiration-time` 的 consumer：写入端不压缩的表没有别人做这件事 |
| `removeOrphanFiles` | `sys.remove_orphan_files(older_than)`，`olderThan` 至少 1 天：写入端还没提交的文件都比这年轻 |
| `createTag` | 给最新快照打 tag。tag 已经存在就算完成，这是重试时的情形 |
| `deleteTag` | 删掉一个 tag，包括 Paimon 自己按时间建的（名字里有空格，如 `2026-10-15 10`）。已经没有就算完成 |
| `backfill` | 见下文 |

**重试。** 一次尝试失败时（Pod 非零退出、被删除或超时），先等它的 Pod 停下来，再决定下一步：Job 失败时 Pod 可能还在终止，还在写。还有次数，就在退避之后建一个新的 Job 再试：第一次失败结束后等 1 分钟，之后每失败一次翻倍，最多 10 分钟（默认 2 次重试，即等 1 分钟、2 分钟），让表还没就绪、catalog 短暂不可用这类一时的原因有时间过去，不在几秒内用完全部次数。等待时任务仍是 `Running`，消息写明失败的原因与下一次尝试的开始时间，`status.attempts` 与 `status.run` 仍是失败的那一次，到下一次的 Job 建出来才换；开始时间从 `status.history` 里上一次的结束时间算出，Operator 重启也不变。回刷与分层表的压缩在退避期间仍持有围栏。次数用完判为 `Failed`（`RunFailed`），消息里带最后一次的原因。每次尝试的结局记在 `status.history` 里（Job、结束时间、成败与原因）。

每种任务都可以重跑：压缩、过期和清理本身是幂等的，tag 已存在即完成，回刷每次都整表重算。

### 回刷

回刷按 `LakeTable` 当前的查询重算整张目标表，例如维表修正之后。lookup join 只在订单变化时重新查维表，已有的订单不会自己跟上。

1. **栅栏。** 在 `LakeTable` 上加注解 `colvenna-lake.io/fenced-by=<task>/<uid>`（带 uid：删掉再建的同名任务不是同一个栅栏）。`LakeTable` 的协调器看到它，就以 savepoint 挂起作业（与 `suspend: true` 相同），`phase: Suspended`，消息写明被哪个任务挂起。作业挂起之后，协调器在 `status.fencedBy` 里记下这个任务。

   任务处于 `Fencing`，直到 `status.fencedBy` 是它自己，并且 FlinkDeployment 在最新 spec 上挂起。20 分钟内没有挂起（savepoint 失败、声明待校验等），判为 `Failed`（`FenceTimeout`）并去掉注解。只看 FlinkDeployment 不够：加注解之前开始的那一轮协调，可能在之后把作业恢复。同一张表的协调一轮接一轮，每轮读到的表至少和上一轮一样新，所以写下 `fencedBy` 的那一轮之后，没有哪一轮会在注解还在时恢复作业。这样批运行是目标表唯一的写入者。
2. **运行。** batch 下执行 `INSERT INTO <target> <query>`（lookup join 在 batch 下同样可用），再分批删掉目标表里查询已不再产生的主键（按主键列的类型写字面量：数值、字符串、BOOLEAN、DATE、TIMESTAMP；别的类型判为失败）。每行都重写了一次：值没变的行不进 changelog（`row-deduplicate`），变了的行由 lookup changelog 生成正确的前像。Pod 日志里的 `TASK_PROGRESS` 行报告进度。
3. **释放。** 成功后，或重试用完时，去掉注解，作业从 savepoint 继续。它从 consumer 位置重读回刷期间的上游变化，用当前的维表重算；结果与回刷相同，upsert 不会叠加。
4. **收敛。** 任务处于 `Converging`，直到 `LakeTable` 重新 `Running`，并且下游的每一层都读到了回刷的变化。之后才 `Succeeded`。
   - 第一层：每张流读目标表的 `LakeTable`，consumer 都越过了回刷的提交（`nextSnapshot > snapshotAfter`）。
   - 下一层：读端越过了它流读的每一张带着变化的表之后，它在同一个 checkpoint 提交这些变化。30 秒后（那次提交在几秒内完成）记下读端目标表的最新快照，再往下一层的读端要越过它。读端后来又越过一张新带上变化的表（菱形依赖），就重新记一次。
   - 每张表的这个快照、记下的时刻和经过的表记在 `status.frontier`。只做 lookup 的读端不用等。
   - 看板副本：平台 Doris 里读到某张带着变化的表的物化视图和内表，要有一次在那张表提交变化之后开始的刷新（回刷的表以栅栏释放为准，下游的表以读端读到变化之后 30 秒为准）。
   - 30 分钟内没有收敛，判为 `Failed`（`NeedsReconciliation`），`status.waitingFor` 列出没跟上的表。挂起的读端也会这样。

**失败。** 次数用完时也会释放栅栏，作业从 savepoint 继续。这时目标表可能只回刷了一半（已经 upsert、还没删除），任务的消息与 `history` 写明了这一点，可以再建一个回刷任务。`LakeTable` 在回刷中途被删除时，先停下运行，再判为 `Failed`（`TableGone`）。

**取消。** 删除 `LakeTask` 时，还在运行的尝试先被删掉，等它的 Pod 消失之后才释放栅栏，否则恢复的作业会和还在写的批运行同时写表。然后撤销身份。

**身份的回收。** 任务结束（`Succeeded` 或 `Failed`）后立即撤销它的 token 和 grant：结束的任务不再写任何东西。

### 状态与编排器

| 渠道 | 内容 |
| --- | --- |
| `status.phase` | `Pending`、`Fencing`、`Running`、`Converging`、`Succeeded`、`Failed`；后两个是终态 |
| `status` 其他字段 | `attempts`、`run`（当前尝试的 Job）、`history`（每次尝试的结局）、`startedAt`、`finishedAt`、`fence`（`lakeTable`、`fencedAt`、`releasedAt`）、`result`（`snapshotBefore`、`snapshotAfter`、`detail`）、`waitingFor`、`frontier` |
| 条件 | `CatalogReady`、`Finished`（终态时 True 或 False，原因即结论） |
| 事件 | `TaskStarted`、`Fencing`、`AttemptFailed`、`FenceReleased`、`TaskSucceeded`，失败为 `Task<原因>`（如 `TaskRunFailed`、`TaskNeedsReconciliation`） |
| 指标 | `colvenna_task_phase` |

**公开接口是 Kubernetes API。** 创建 `LakeTask`，读 `status.phase` 直到终态。调用方用自己的 Kubernetes 身份和 RBAC，不存在匿名入口。

- CLI 的批命令（HLD 9.2）：`colvenna backfill <db.table> --name <task>`，`colvenna maintain compact|expire|orphans|tag <db.table> --name <task>`，各自的参数（`--full`、`--partition`、`--retain-min`、`--older-than`、`--tag`、`--time-retained`）对应 spec 字段。它们以 server-side apply 建任务：同一条命令再跑一次什么也不改，参数变了会被 API server 拒绝。加 `--wait` 就等任务结束，`Succeeded` 退出码为 0，`Failed` 为 1，超时为 2。
- 也可以 `colvenna apply -f task.yaml` 后 `colvenna wait task <name> --for phase=Succeeded`；另有 `colvenna get tasks` 和 `colvenna describe task <name>`。
- 编排器：用户在自己的编排器里调 CLI 或 Kubernetes API。平台不内置生产 Airflow（issue 边界），也不维护 DAG 模板（HLD 9.2）。#27 验收用 Airflow 2.10 的调度器跑了一个 DAG（`tests/acceptance/tasks/colvenna_lake_task.py`，只用于验收）：它的服务账号只有 `laketasks` 的 `create` 和 `get`；DAG 建任务并轮询，任务 `Failed` 时 Airflow 任务以 Operator 给出的原因失败；重试或重复的 DAG run 按名字找到已有的任务，等它的结果。

## 治理与个人数据删除

Issue #28 交付表级的分类与保留声明、按声明发布，以及跨层删除一个数据主体（HLD 12.4、12.5、12.7，[M0 验收契约第 4 节](acceptance-contract.md#4-合规删除的副本范围与完成证据)）。

### 声明

```yaml
# LakeSource 与 LakeTable 的 spec 里同样写法
governance:
  owner: shop-team                 # 负责人
  level: L3                        # L1 到 L4
  categories: [personal]           # personal（个人数据）、sensitive（敏感个人数据），可省略
  retention: 24h                   # 历史保留多久：旧快照、changelog、停下的读端位置。必填
  subjectKeys:                     # 每张含个人数据的表里，标识数据主体的列
    orders: [customer_id]          # LakeSource 按源表名写；LakeTable 只有一张表，写在 subjectKey
    customers: [id]
readers: [doris-bi]                # 发布：这些 catalog principal 得到表级 READ
```

一个命名空间里所有声明的主体键都标识同一类主体（比如客户编号）：删除请求给出的值会拿到每张表的主体键上去找。

**是否受治理（`Governed`）。** 每张表单独判定，结论写在条件 `Governed` 里，`LakeSource` 的每张表还写在 `status.tables[].governance.governed`：

| 原因 | 判定 |
| --- | --- |
| `Unclassified` | 没有 `governance`、没有 `owner` 或 `level`：按临时 L4 处理，不发布 |
| `RetentionMissing` | 没有声明 `retention`：历史保留多久由声明决定，不能缺 |
| `LevelTooLow` | 个人数据低于 L3，或敏感个人数据低于 L4（HLD 12.5） |
| `SubjectKeyMissing` | 含个人数据的表没有声明主体键，或主体键不是这张表的列：删除时找不到它 |
| `RetentionTooLong` | 声明的 `tableOptions` 保留超出 `retention`；含个人数据的表 `retention` 超过 24 小时，或声明了按个数保留的选项（`snapshot.num-retained.min`、`changelog.num-retained.*`，按个数留下的历史不受时间限制） |
| `LabelInvalid` | 列标签（`governance.columns`，M4 #35，见"列标签"）无效：标的列不是这张表的列，列的级别高于表，列的类别表没有声明，或个人数据列低于 L3、敏感个人数据列低于 L4 |
| `UpstreamUnclassified` | `LakeTable` 读的表没有分类（没有声明覆盖它，或声明缺 `owner`/`level`）：不知道它的级别和类别 |
| `BelowUpstream` | `LakeTable` 的级别低于它读的表的最高级别，或丢了上游的个人数据类别。M3 不提供降级（去标识化）路径 |
| `Governed` | 都满足 |

**有界保留。** Operator 把下面的选项写进每张有 `retention` 的表，声明的 `tableOptions` 不能超出。没有分类的表按临时 L4 的个人数据处理，上限 24 小时：默认值（快照 1 小时、consumer 1 天）本就在上限内，保留不变，声明的值超出就截到上限；按个数保留和全量压缩同含个人数据的表（全量压缩的间隔固定为 1 小时，`compaction.optimization-interval` 不在 `tableOptions` 的白名单里，声明它判为 `InvalidSpec`）：

| 选项 | 值 | 为什么 |
| --- | --- | --- |
| `snapshot.time-retained` | 不超过 `retention` | 旧快照到期就过期，time travel 读不到更早的行 |
| `consumer.expiration-time` | 不超过 `retention` | 停下的读端最多把旧快照钉住这么久 |
| `snapshot.num-retained.min` | `1`（含个人数据和没有分类的表） | 写得少的表不会因为快照数下限一直留着旧快照 |
| `compaction.optimization-interval` | `1 h`（含个人数据和没有分类、写入端自己压缩的表；固定，不能声明） | 写入端为一个桶挑选压缩时（有新的写入之后），距这个桶上次全量压缩超过 1 小时就做一次全量压缩，删掉的行和它的删除记录随之从文件里消失。计时在写入端的内存里，作业重启后重新计；之后没有写入的桶不会再压缩。所以这只是日常删除留在文件里的近似上限，删除流程不依赖它，自己发起压缩（见下文第 3 步）。不用 `full-compaction.delta-commits`：Paimon 2.0 不允许它与 `changelog-producer=lookup` 同时出现，而写入端自己压缩的表都是 lookup changelog |

含个人数据的表 `retention` 最多 24 小时。写入端不压缩的表（`write-only`，binlog 接入的 ODS 表）里，Paimon 不做快照、tag 和 consumer 的过期：这样的源若含个人数据或没有分类，Operator 每小时为每张 Ready 的表建一个它拥有的 `expireSnapshots` 任务（`retain_min` 1，过期早于 `retention` 的快照，然后过期到期的 tag 和闲置超过 `consumer.expiration-time` 的 consumer），上一小时已结束的任务随之删除。作业的 Flink 状态也有界：每个作业只留 3 个 checkpoint，Flink Kubernetes Operator 的 savepoint 历史最多留 7 天（`kubernetes.operator.savepoint.history.max.age`）。

`LakeTask` 不能给含个人数据的表打保留超过 24 小时的 tag（`RetentionTooLong`）；没有声明分类的表按临时 L4 的个人数据处理，同样受限。

**发布。** `readers` 里的 principal 只有在表受治理时才得到表级 `READ`。表不再受治理时（比如声明改了），这些 grant 立即撤销。条件 `Published` 写明发布给了谁，或者为什么没有发布。读端在 catalog 里另外得到的权限（库级或 catalog 级 grant）不由声明管理，也不会被撤销：条件 `Published` 写出哪个读端经由哪个 grant 仍能读这些表。

### 删除一个数据主体（LakeErasure）

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeErasure
metadata: {name: erase-20261015-001, namespace: colvenna-data}
spec:
  subjectSecret: {name: erase-20261015-001, key: values}   # 主体键的值，一行一个
  deadlines: {logical: 24h, physical: 9d}                   # 默认值，也是上限；验收可以缩短
```

主体键的值本身是个人数据，所以只放在 Secret 里：不出现在 spec、状态、事件、日志和作业参数里；源库驱动和检查作业的报错如果复述了它，也会先被遮掉。检查作业把这个 Secret 作为文件挂载。`colvenna forget <名字> --subject-file <文件>`（HLD 12.7）从文件建这个 Secret 和 `LakeErasure`，并让删除请求拥有这个 Secret（删除请求删掉时它一起删掉；建请求失败时立即删掉），可以 `--wait` 到物理完成。

一个值在每张表上按主体键列的类型比较：数字按数值，日期与时间按时刻（带本地时区的时间戳按 UTC 给值），文本忽略大小写和首尾空白（源库常见的排序规则如此）。值的列数与主体键不同，读不成列的类型，或者主体键列不是这些类型，判为"对不上"。

**受理。** Secret 还没建时，删除请求等它；请求（`requestedAt`）之后 24 小时还没受理（等 Secret，或受理时 catalog、Kubernetes API 出错），记一条超期（见"状态"），进入 `SlaBreach`。Secret 里没有值，或期限超过 M0 的上限（逻辑 24 小时、物理 9 天，逻辑不晚于物理），判为 `Failed`。受理时 Operator 记下受理时间 `t0`（事件 `ErasureAccepted` 写出受理用了多久），枚举本命名空间里含个人数据的表，以及它们的副本，写进 `status.copies`。M0 说 `t0` 在清单、保留上限与所有者确认之后：清单和保留上限来自各表负责人自己写的声明，这些声明就是所有者的确认；清单里列为 `Blocked` 的副本要负责人复核。每个副本带负责人（`owner`：声明的 owner；Flink 状态与存储为 `platform`）：

| 副本 | 层面 | 完成的证据 |
| --- | --- | --- |
| `SourceDatabase` 源库的表 | 逻辑（负责人是声明的 owner） | 源库里查不到这个主体的行。平台不替用户删源库，只核对；没删之前湖里的行会被 CDC 带回来 |
| `Topic` 用户的 Kafka topic（Kafka 承载时） | 外部 | 平台不能删 topic 里的记录，只登记和提醒，不计入平台的完成 |
| `Reader` 发布对象 | 外部 | 每个得到 `READ` 的读端单独列出：它读走、另存的副本（Doris、导出）由它的负责人删除 |
| `Rows` 表的当前行 | 逻辑 | 最新快照里查不到这个主体的行（检查作业，记下这个"干净"快照 `clean`）。源库查到过这个主体的行而这张表一次也没数到时，0 不算证据：主体键可能对不上，判为 `Blocked` |
| `History` 表的历史 | 逻辑 | 最早保留的快照不早于 `clean`；保留的快照从 `clean` 起的变更（changelog；没有 changelog 的表是提交新增的文件）里没有这个主体的记录，所以 `clean` 自己的变更含删除记录时它也要过期；最新快照的文件里也没有它的记录（`$ro`、`$audit_log` 等系统表直接读文件）；没有更早的长期 changelog；没有 tag 指向更早的快照，没有 branch；每个 consumer 都已越过它 |
| `Files` 表的文件 | 物理 | 最新快照的每个数据文件单独读（保留删除记录，不套用 deletion vector：它遮住记录，没删掉）都没有这个主体的记录，它的 manifest 列出的文件（新增的和删掉的）的列统计（最小、最大值；文本按列的 `fields.<列>.stats-mode`，没有时按文件所在层的 `metadata.stats-mode.per.level`，再没有时按 `metadata.stats-mode` 截断，截断的值与前缀相同的主体值都算命中）里也没有这个主体的值，这时的快照记为 `compacted`；`compacted` 之前的快照都已过期，保留的 changelog 文件里没有它，没有 tag、branch、consumer 钉住更早的快照；`compacted` 一天之后跑过一次孤儿文件清理（它只删一天以前的文件） |
| `FlinkState` 作业的 Flink 状态 | 物理 | 检查作业以作业的身份（IRSA）列出它的 `checkpoints/`、`savepoints/` 和高可用目录里的 completed-checkpoint 记录（可能内嵌小的状态），没有一个对象早于 `clean`。不知道状态在哪的作业是 `PendingEvidence`。另有一项"jobs no longer declared"：命名空间的状态目录下，不是现存声明的作业的目录（按部署名加属主 uid 前缀；声明删了或重建了，或源去掉了一个作业），全部早于 `clean` 的删掉，有更新对象的列出来 |
| `Storage` 对象存储的版本与复制 | 物理 | 安装脚本核对过仓库桶没有开版本、没有复制，记在 Operator 的设置里，证据写明核对的时间和身份；没有这份证据就是 `PendingEvidence`。删除时不重新核对：之后若有人改了桶的设置，要重跑安装脚本才会发现 |

以下副本是 `Blocked`，带复核期限（`reviewBy`，即逻辑期限）：没有主体键（找不到行，证明不了删除）；Kafka 事件源与 MongoDB 源的表（M3 不支持）；声明不含个人数据却读了个人数据的分层表；这些库里没有声明覆盖的表（声明删了而表留下，或旁边另建的表：不知道它有没有这个主体）；源库的 `LakeSource` 没了；主体键的值和列的类型对不上（这张表还没完成的当前行、历史和文件都是）；源库有过这个主体的行而表里从没数到。源库的核对用受理时记下的主体键，之后改了声明也不变。

**推进。** 检查作业一次只跑一个，上一次结束（或到 20 分钟期限被停掉）两分钟后再跑下一次；它的结果经由终止消息回来（Kubernetes 只保留 4 KiB），所以一次只查 6 张表、2 个作业的状态，下一次接着查后面的；每张表的 tag、branch、consumer 只列出钉住旧快照的，最多 5 个，其余计数，没列出的也算钉住；结果仍然太长（按字节计）时先去掉这些清单、再去掉状态和孤儿目录的对象清单，只留计数；再截短错误信息（完整的在检查作业的日志里）；还是太长就只回一个错误，这一轮什么都不采用，原因写进 `status.message`。一次运行的结果在下一次运行结束前每轮都会再读一遍，只有第一次读到时才据此判断"主体又出现了"（`status.probeTaken`）。读不了的表或作业状态只影响它自己。物理完成之前，已完成的表和源库也一直在查。

1. 源库：等用户在源库删掉这些行（CDC 会把删除带进来）。
2. 当前行：检查作业在每张表上数这个主体的行，为 0 且源库已删时记下 `clean`。之后行又出现，或 `clean` 之后的变更里又有了这个主体新增的行（+I、+U：重放，或主体再次写入又删除；`clean` 自己的变更是删除自己的，不算），或历史已完成后 `clean` 起的变更里又有了它的任何记录（比如只重放了删除记录，它们带着旧行），这张表已完成的三项和各作业的 Flink 状态退回 `Pending`，逻辑完成撤销，并发 Warning `SubjectReturned`；这张表的维护任务按新的一轮重新建（名字带轮次），不沿用上一轮已成功的。源库又有了这个主体的行时同样退回。表删了，它的三项按"表已不存在"完成；同名的表又建出来时重新判定（Warning `TableReturned`）。已经完成的源库核对，在 `LakeSource` 删掉之后仍算完成。

   分层表的流不一定把删除带过来：lookup join 查不到维表行时，会把它关联的变更连同删除一起丢掉，而源库先删了维表行就会这样（2026-10-16 在 EKS 上遇到：客户与订单同一次 checkpoint 提交，DWD 的订单删除在 lookup 时已找不到客户）。所以分层表还有这个主体的行，而它读的、删除流程登记过的每张表都已 `clean` 满 5 分钟，它的流也都读过了这些表的 `clean` 快照（Paimon consumer 的位置，见 `status.upstream`；lookup 读的是表的当前状态，不看位置）时，Operator 建一个回刷的 `LakeTask`，并发 Normal 事件 `ErasureBackfill`。回刷按上游重算整表，删掉不再产生的主键，主体的行随之消失；这张表的 `Rows` 完成时，证据写出回刷任务、它改了多少行，以及这张表最多时有这个主体的多少行。流还没读到 `clean` 时等它；读的表一张也没有登记（不含个人数据）时不回刷：不知道这些行从哪来，`Rows` 一直未完成，到期限时作为残留报告。
3. 文件：文件或 manifest 统计里还有这个主体时，Operator 建一个全量压缩的 `LakeTask`：先压缩文件，再压缩 manifest（manifest 里只剩表现有的文件）；记录又出现时，十分钟后再压缩一次。分层表的写入端自己压缩，这个压缩在围栏后运行（挂起它的作业，见 LakeTask）；不等它每小时一次的全量压缩，因为那是在提交时才判断的，没有新数据就没有提交。压缩也让 `clean` 之后有了新快照，`clean` 才能过期。API server 拒绝的任务只影响它自己那一项。
4. 历史与文件：Operator 跑快照过期（`retain_min` 1，过期 `compacted` 或者还不知道时 `clean` 及更早的快照），删掉指向更早快照的 tag，没过期干净就每 10 分钟再跑；落后的 consumer 只能等它读过去或过期（不超过 `retention`）；branch 由它的负责人删。`compacted` 一天之后跑孤儿文件清理。
5. Flink 状态：所有表的行都干净后，删掉 `clean` 之前的 FlinkStateSnapshot（Operator 不再从它们恢复），检查作业列出每个作业的状态：作业以前的运行留下的 checkpoint 目录（全部早于 `clean` 的；作业在运行时，不管早晚）、`clean` 之前的 savepoint，只要不是作业恢复时要用的（作业不在运行时，它升级用的 savepoint），就整个删掉；正在运行的作业自己的旧 checkpoint 和对应的高可用记录等 Flink 在新的 checkpoint 完成时删除。检查结果只在它所对照的 `clean` 仍然有效时采用。TaskManager 本地磁盘上的 lookup 缓存随 Pod 消失，并随维表的删除刷新。

**状态。** 每个副本是 `Pending`、`Done`、`Blocked`、`PendingEvidence` 或 `External`（不由平台控制），带着证据或原因。总体 `phase`：

- `Accepted`：已受理，在推进；
- `LogicallyErased`：所有平台副本的逻辑层面都完成了；
- `PhysicallyErased`：物理层面也都完成了；
- `Blocked`：有副本被策略挡住，消息列出它们的负责人和复核期限，并发 Warning `ErasureBlocked`；
- `SlaBreach`：过了期限还没完成；
- `Failed`：请求被拒（Secret 里没有值、期限超过上限等，见"受理"），发 Warning `ErasureRefused`。

条件 `LogicallyErased`、`PhysicallyErased` 分别在对应层面完成时为 True（原因 `Erased`，否则 `Pending`，消息列出还没完成的副本），完成时各发一次同名的 Normal 事件。spec 创建后不可修改（`self == oldSelf`），要换主体或期限就另建一个请求。

一个期限（受理、逻辑或物理）过了而对应层面没完成时，记一条到 `status.breaches`（期限、发现时间、还没完成的副本和它们的位置），发 Warning `ErasureOverdue`，条件 `WithinDeadlines` 为 False；之后即使完成，这条记录也保留。一轮推进中途出错（catalog 或 Kubernetes API 不可用、检查作业失败），期限照样判定，消息先写出错的原因。

只有每一项都有证据，才报告完成；外部副本单独列出，不计入平台的完成声明。

## 监控与数据质量

Issue #29 让接入、提交、维护和查询可观测，按源、表、作业追踪（HLD 11.2、12.6、14）。指标的口径与 [M0 验收契约 2.1 节](acceptance-contract.md#21-测量协议) 相同：延迟从源端提交算到终点快照可读，逐层分段，不用"作业在运行"或"任务成功"代替。

### 指标

Operator 与 colvenna-catalog 各在自己的端口（9100）以 Prometheus 文本格式给出指标，只有标记为监控（`colvenna-lake.io/monitoring=true`）的命名空间里的 Pod 能连。

| 指标 | 标签 | 含义与 M0 口径 |
| --- | --- | --- |
| `colvenna_source_latency_seconds` | 源、心跳表 | 源库 → ODS：每条心跳从源端提交到第一个装下它的 ODS 数据提交的时间（M0 2.1 的逐条样本），取这一轮新看到的心跳里最长的 |
| `colvenna_source_freshness_seconds` | 源、心跳表 | 湖里最新的源端变更有多旧（现在 − 可见的最新心跳），每次发布时按当时计算；心跳写不成时不报（`ColvennaHeartbeatFailing` 报）。数据库源的积压也以它表示：变更积压多久 |
| `colvenna_source_heartbeat_ok`、`colvenna_source_clock_skew_seconds` | 源、心跳表 | Operator 写成了最近一次心跳；源库时钟与 Operator 时钟之差（写入前后的中点比），超过 5 秒的心跳不算样本 |
| `colvenna_source_measured_timestamp_seconds`、`colvenna_table_measured_timestamp_seconds` | 源或表 | Operator 上次完整测量这个对象的时间；对账出错时指标停在上次的值，这一项随之变旧 |
| `colvenna_table_upstream_lag_seconds` | 表、上游 | 上一层 → 本层：本层作业还没带走的最早的上游快照，提交至今多久；赶上时为 0。读端的 consumer 在 checkpoint 完成、提交之后才前进，所以这是"上游可读 → 本层可读"的等待 |
| `colvenna_table_layer_latency_seconds` | 表、上游 | 上一层 → 本层：本层最近带走的上游快照，从它的提交到本层装下它的提交用了多久（每次 consumer 前进时取样），即 M0 每段的延迟 |
| `colvenna_table_freshness_seconds` | 表 | 查询新鲜度：查询这张表看到的最新源端变更有多旧，等于各流读上游的新鲜度加上本层落后的时间，取最大；有上游不知道新鲜度时缺省 |
| `colvenna_table_upstream_lag_snapshots`、`colvenna_source_kafka_lag` | 表或源 | 积压：上游未读快照数、Kafka 未消费记录数 |
| `colvenna_catalog_commits_total` | 表、结果 | 提交：`committed`；`conflict`（写入方的基准快照已不是最新，Paimon 客户端会重试）；`fenced`（另一个写入方在 catalog 之外写了快照）；`error`。按 `table` 与维护积压指标关联，得到它属于哪个源或分层表（面板如此画冲突） |
| `colvenna_catalog_requests_total`、`colvenna_catalog_request_seconds` | 动作、状态 | colvenna-catalog 的请求量与延迟分布（含凭证签发 `TOKEN`） |
| `colvenna_job_last_checkpoint_timestamp_seconds`、`colvenna_job_last_checkpoint_duration_seconds`、`colvenna_job_checkpoints_failed` | 作业 | 作业的最近一次 checkpoint（也就是最近一次提交）何时完成、用了多久，本次运行失败了几次（Flink REST） |
| `colvenna_job_taskmanager_heap_used_bytes`、`colvenna_job_taskmanager_heap_max_bytes` | 作业 | 作业里堆用得最多的 TaskManager（写入端与 committer 在这里）用了多少、上限多少（Flink REST） |
| `colvenna_table_commit_files`、`colvenna_table_commit_file_bytes` | 表 | 写入：最近一次数据提交（不算压缩）新增的文件数和平均大小 |
| `colvenna_table_data_bytes`、`colvenna_table_manifest_bytes` | 表 | 最新快照的数据文件与 manifest 的总大小 |
| `colvenna_table_snapshots`、`colvenna_table_data_files`、`colvenna_table_level0_files`、`colvenna_table_small_files` | 表 | 维护积压：保留的快照数、最新快照的数据文件、未合并的 level-0 文件（主键表）、不足目标文件大小四分之一的小文件；每张表最多每 5 分钟数一次 |
| `colvenna_task_phase` | 任务、类型、表 | 维护任务的阶段 |
| `colvenna_table_quarantined_rows`、`colvenna_table_quarantine_newest_timestamp_seconds`、`colvenna_source_events_rejected` | 表与检查、源与原因 | 错误数据的去向：隔离表的行数与最新一行被隔离的时间（按未通过的检查）、事件源的拒绝表行数（按原因） |
| `colvenna_erasure_phase`、`colvenna_erasure_breaches` | 删除请求 | 删除的阶段与超期 |

各层延迟的 p95/p99 由 Prometheus 在一段时间上取分位数（`quantile_over_time`），与 M0 的目标比：每段 p95 ≤2 分钟、p99 ≤5 分钟，整条路径 p95 ≤15 分钟、p99 ≤30 分钟。滞后（lag）是"仍在等待的最早一条变更"等了多久，它在任一时刻不小于任何已到达的变更的延迟，所以按它告警只会提前、不会漏报。

### 心跳

`LakeSource` 可以声明一张心跳表（MySQL 与 PostgreSQL）：

```yaml
monitoring:
  heartbeat: {table: colvenna_heartbeat, credentialsSecret: {name: shop-beat}}   # 只能写这张表的用户
```

这张表（`id` 主键、`beat_at`、版本列）和源的其他表一样满足源端契约，并列在 `tables` 里。Operator 每一轮用源库自己的 UTC 时钟更新它的第 1 行、版本加一（先 UPDATE，第一次才 INSERT：源端保留主键的 INSERT 触发器在每次插入时都会触发），这就是 M0 测量协议里那条带源端提交时间的合成变更。写入语句发出前、返回后各看一次自己的时钟，以中点与写入的时间比出时钟偏差；超过 5 秒，心跳不算样本，新鲜度也不报（M0：超出容许偏差的样本无效）。心跳表 `Ready`（作业已经在读它）之后，写成的心跳记在 `status.heartbeat.pending`（最多 20 条：再多时最早的一条已经给出了延迟的下界，新的不再记），然后从 ODS 按顺序读回上次看过之后的数据提交（压缩提交不算，每轮最多 10 个，其余下一轮接着读）：每条心跳的延迟是它的源端时间到第一个装下它的提交，这一轮新看到的里取最长的，即 `colvenna_source_latency_seconds`；新鲜度取最新的数据提交装着的心跳。心跳表被重建（快照编号重新开始，或看过的那个编号换了提交）时，等着的心跳作废。只有 MySQL 与 PostgreSQL 源能声明心跳（CRD 校验）；没有心跳的数据库源只报维护积压，并由 `ColvennaSourceUnmeasured` 提醒没有测量；MongoDB 与 Kafka 事件源在 M3 没有心跳。

### 数据质量

`LakeTable` 可以声明对查询结果的检查（HLD 12.6 的流内校验）：

```yaml
quality:
  notNull: [customer_id]
  allowed: {state: [created, paid, shipped]}      # 按文本比较
  ranges: {amount: {min: "0"}, ordered_at: {min: "2020-01-01 00:00:00", max: "2030-12-31 23:59:59"}}
```

不通过任何一项的行写进 `<目标表>_quarantine`（同库，主键与目标相同，多两列 `colvenna_rule`——第一项未通过的检查——和 `colvenna_flagged_at`），不进目标表。两张表都按主键写：同一行的新版本通过了，就离开隔离表；不通过，就离开目标表。检查是确定的（值、范围），撤回和插入按同一规则判断，所以不支持相对当前时间的范围。回刷同样按检查分流：通过的行重写目标表，不通过的重写隔离表。

检查要和表一起声明：给运行中的表加上或去掉检查会改变作业的图，原来的 savepoint 不再对得上（`QualityChanged`）；只改检查的值可以。检查引用的列必须在查询输出里，范围只用于数字、日期和时间戳列（`QualityInvalid`）。隔离表随目标表的治理（保留期、个人数据的删除）、随目标表发布给读端、随目标表删除；表的身份对它有 `WRITE`，这张表的回刷任务也是。隔离表上的维护任务按目标表对待：压缩在这张表的作业的围栏后运行，与同一张表的回刷排队；删除流程要去掉隔离表里这个主体的行时，回刷的是目标表（它同时重写隔离表）。`status.quality` 按检查给出隔离表的行数，行数增加时发 Warning `RowsQuarantined`。事件源的拒绝表（Kafka 事件，见上文）是另一条错误数据去向。

### 告警与面板

`deploy/helm/colvenna-monitoring`（`deploy/scripts/monitoring/install-monitoring.sh` 安装）是集群内的 Prometheus 与 Grafana：Prometheus 经各 Service 的 endpoints 逐个 Pod 抓取 Operator 和 colvenna-catalog（只在这两个命名空间里有发现权限），按 `files/rules.yaml` 判定告警；Grafana 的面板（`files/dashboard.json`，由 `deploy/scripts/monitoring/dashboard.py` 生成）按 M0 的路径画图，阈值线由 chart 渲染时从 `values.yaml` 的 `m0` 填入，与告警规则用同一组目标。两者都只有 ClusterIP：Prometheus 只接受 Grafana，以及所在命名空间与 Pod 都带 `colvenna-lake.io/prometheus-reader=true` 标签的来源（M5 #137 控制台的流水线全景）的连接，Grafana 不接受任何 Pod 的连接，人经由需要认证的 `kubectl port-forward` 访问，Grafana 还要登录（管理员口令在安装时生成到 Secret，不打印），没有匿名访问和注册。

| 告警 | 条件 | 依据 |
| --- | --- | --- |
| `ColvennaSourceLatencyP95` / `P99` | 源库 → ODS 30 分钟 p95 超 2 分钟 / 10 分钟内有一次超 5 分钟 | M0 每段目标 |
| `ColvennaSourceStale` | 湖里最新的源端变更超过 5 分钟加一轮心跳（30 秒），立即 | 心跳到不了，延迟就测不到；新鲜度一过 p99，还没到的那条变更已经违约 |
| `ColvennaLayerLatencyP95` / `ColvennaLayerLagP99` | 某层带走的快照 30 分钟 p95 超 2 分钟 / 有上游快照等了超过 5 分钟，立即 | M0 每段目标；等待超过 p99 的变更已经违约 |
| `ColvennaFreshnessP99` | 查询看到的变更超过 30 分钟 | M0 整条路径 |
| `ColvennaRowsQuarantined`、`ColvennaEventsRejected` | 15 分钟内有行被隔离（按最新一行被隔离的时间，不受同时离开隔离表的行抵消）、拒绝表的行在增加 | 错误数据 |
| `ColvennaQuarantineFull` | 一张表的隔离行超过 10000 行半小时 | 源或检查需要修 |
| `ColvennaMetricsDown`、`ColvennaMetricsAbsent`、`ColvennaMeasuresStale`、`ColvennaHeartbeatFailing`、`ColvennaSourceUnmeasured`、`ColvennaLayerUnmeasured`、`ColvennaFreshnessUnmeasured` | 抓不到某个 Pod；一个 Operator 或 colvenna-catalog 都抓不到；某个源或表 5 分钟没有被测量（对账出错，指标停在旧值；判为 Failed 的不算，`ColvennaDegraded` 已报）；心跳写不成；运行中的数据库源没有看到心跳；运行中的表没有滞后指标、没有新鲜度（上游的心跳或新鲜度缺了） | 没有测量就没有延迟告警：缺测量本身告警。心跳写不成是测量故障，不按数据不新鲜告警 |
| `ColvennaDegraded`、`ColvennaCommitFenced`、`ColvennaErasureOverdue` | 源或表 Degraded 或 Failed；表被围栏；删除超期 | 正确性 |
| `ColvennaCommitConflicts`、`ColvennaNoCheckpoint`、`ColvennaCheckpointsFailing`、`ColvennaCompactionBacklog`、`ColvennaSmallFiles`、`ColvennaSnapshotsPiling`、`ColvennaTaskFailed`、`ColvennaCatalogErrors`、`ColvennaCatalogCommitSlow` | 冲突持续、10 分钟没有提交、checkpoint 失败、维护积压、任务失败、catalog 出错或慢 | 运行 |

`deploy/scripts/monitoring/check-rules.sh`（CI 运行）渲染 chart，用 chart 里的 Prometheus 镜像的 promtool 校验配置和规则，跑 `rules-test.yaml`：按 M0 的阈值，超标时触发、未超标时不触发，缺测量时告警；它也核对提交的面板就是 `dashboard.py` 生成的。告警只在 Prometheus 里触发；接收方（Alertmanager、值班系统）由部署方接入。对象级的细节仍在 Kubernetes Warning 事件和各资源的 `status` 里。

## 看板服务（LakeDashboard）

Issue #32 让 BI 与看板查询湖表（HLD 10.1、12.2、14）：固定聚合和少量热数据在声明的副本边界内满足响应与新鲜度目标，明细的权威仍在 Paimon。

**平台的 Doris。** `deploy/scripts/doris/install-doris.sh` 在 query 节点上装一套 Doris（Doris Operator，一个 FE、一个 BE，M1 #14 的查询引擎镜像：基于 Apache Doris 4.1.4 修改构建，加自建的 Paimon 2.0.0 读取器，仓库 `query-engine`）。root 的口令在安装时生成到 Secret（64 位十六进制随机数），不打印。FE 首次启动要从 `fe.conf` 读 root 的 MySQL 口令哈希（`initial_root_password`），所以这个哈希放在 FE 的 ConfigMap 里，口令本身不在。这是"机密只放 Secret"的一个有意例外：哈希本身不能登录，口令有 256 位，也无法离线反推；但截获一次 root 登录的握手，再加上哈希，就能登录，而集群内到 FE 的 MySQL 协议是明文。所以能读这个 ConfigMap 的只有集群管理员和 Doris Operator。Doris 的 Operator 只从 ConfigMap 读 `fe.conf`，这个口令哈希没有别的办法交给 FE。Doris 自带、默认没有口令的 `admin` 改成一个谁也不保存的口令（4.1 不能锁账号）。查询审计日志的表（`__internal_schema.audit_log`）只留 7 个按天的分区（含当天，`dynamic_partition.start = -6`）。FE 的审计文件按天滚动，滚动 5 天后删除。所以一条查询最多在日志里留 7 天。回收站留被删的分区和表 1 天（`catalog_trash_expire_second`），BE 不留 trash（`trash_file_expire_time_sec = 0`，垃圾清理在一小时内）。

**Doris Operator 不建 BE 时。** Doris Operator 25.8.0（`1db0c699`）只在 FE 的 Service 有了就绪地址后才建 BE（`pkg/controller/sub_controller/be/controller.go:63-65`、`sub_controller.go:331-351`），但它不监听 Endpoints。FE 的 Pod 就绪会触发一次协调，状态更新又会再触发几次（状态里每次写入新的 `LastTransitionTime`，`sub_controller.go:641,659`）；如果最后一次协调读到的 Endpoints 缓存里还没有 FE 的地址，之后就没有事件再叫醒它：它的 StatefulSet 与 Service 的 owner reference 不带 `controller: true`（`pkg/common/utils/resource/statefulset.go:37-42`、`metadata.go:140-148`），`Owns()` 的监听因此不触发，只剩 controller-runtime 默认约 10 小时的 resync。这是一场竞态，所以只是有时发生（UAT 的 `catalog-doris` 等了 1484 秒）。上游 [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 仍未修复，26.0.1 与 master 的这几处都没有变。DorisCluster 的监听没有过滤条件（`doriscluster_controller.go:256`），任何改动都会触发协调，所以 `install-doris.sh` 与验收测试在等 BE 时，只要 BE 的 StatefulSet 还不在，就每 10 秒给 DorisCluster 打一次注解 `colvenna-lake/reconcile=<时间>`（`deploy/scripts/lib/common.sh` 的 `touch_doris_without_be`）。

与 HLD 10.1 的部署形态不同：HLD 设想存算分离（内表数据在 S3 的 Storage Vault，Meta Service，按 Compute Group 伸缩）。M3 是存算一体：一个 BE，数据在 gp3 卷上，只有物化视图和内表这些派生副本在上面。存算分离、多 BE 与 File Cache 留给容量与高可用的后续阶段。千万行、7 天窗口、10+1 QPS 的查询档已由 M1 实测（[24 小时基线](../history/m1/doris-paimon.md#结项判定)：固定看板查询 p95 2.5 毫秒，外表点查 p95 1.1 秒）。M3 的验收在平台的 Doris 上测同样的负载形态，但不重复千万行的数据量。FE、BE 只有 ClusterIP，NetworkPolicy 只放行 Doris Operator、colvenna-operator、本命名空间的管理客户端 Pod，以及标记了 `colvenna-lake.io/doris-client=true` 的命名空间。Doris 的 Pod 用 M2 为 Doris 准备的 IAM 角色，它读不到表数据：Doris 读湖只用 colvenna-catalog 按表签发的凭证。

**声明。**

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: sales, namespace: colvenna-data}
spec:
  tables: [dws_x.segments, dwd_x.orders, ods_x.customers]   # 本命名空间声明的表
  governance: {owner: bi-team, level: L2, retention: 7d}     # 它在 Doris 里的副本
  queries:
    - {name: region_totals, serve: materialized, refresh: 1m, sql: "SELECT region, orders, amount FROM dws_x.segments"}
    - {name: order_lookup, serve: external, sql: "SELECT order_id, region, amount FROM dwd_x.orders"}
    - {name: customers_hot, serve: internal, refresh: 1m, key: [id], sql: "SELECT id, name, region FROM ods_x.customers"}
  users:
    - {name: analyst, passwordSecret: {name: sales-analyst}, queries: [region_totals, order_lookup]}
```

查询清单逐条决定读路径（HLD 10.1）：

| `serve` | Doris 对象 | 何时用 | 新鲜度 |
| --- | --- | --- | --- |
| `external` | 视图，查询时经外表读湖 | 明细、点查、交互查询 | 湖里最新的快照（catalog 不缓存表元数据，M1 #14：缓存会让外表落后 p95 9 分钟） |
| `materialized` | 异步物化视图，`REFRESH COMPLETE ON SCHEDULE EVERY n MINUTE` | 固定聚合（看板） | 上次成功刷新开始时的湖 |
| `internal` | Unique Key 内表，Operator 每 `refresh` 用 `INSERT OVERWRITE` 整表重写 | 少量热维表、高并发点查 | 上次成功重写开始时的湖 |

Doris 的 JOB 不支持 `INSERT OVERWRITE`，所以内表由 Operator 按时重写，而不是交给 Doris 调度；物化视图由 Doris 自己调度。

三条路径都经外表读湖。一个桶里没有 level-0 文件时，Doris 用自己的 native 读取器直接读文件；还有 level-0 文件时，走 Paimon 的 JNI 读取器做 merge 读，要同时打开桶里的全部文件。ODS 的写入端不压缩，所以 Doris 读的 ODS 表要由编排器定期压缩（`LakeTask`，间隔要让 level-0 文件远低于约 300 个，验收用 10 分钟），否则读得越来越慢；`ColvennaCompactionBacklog` 在 level-0 文件超过 50 个持续 30 分钟时告警。JNI 读取器的查询超时被取消后不释放 S3 连接（从线程转储推断），连接池耗尽后只有重启 BE 才能恢复（#32 的运行 d2）；压缩只降低这种暴露。

**身份与权限。** 每个 dashboard 一个 colvenna-catalog principal，只有所声明的表的表级 `READ`；它的 token 进 Doris 里这个 dashboard 自己的 catalog（`lake_<命名空间>_<名字>_<uid 前 8 位>`；dashboard 的数据库 `dash_…`、workload group `wg_…` 同一规则，超过 Doris 的 64 个字符时命名空间与名字换成哈希），续期时用 `ALTER CATALOG` 换上，再撤销旧的。catalog 的属性里有 token，而 Doris 让能读 catalog 里任何一张表的用户 `SHOW CREATE CATALOG`，所以**用户从不得到 catalog 上的权限**：外表路径也经由 dashboard 数据库里的视图授予（实测：只授视图时，用户读得到视图、读不到底表、看不到 catalog）。Doris 用户名为 `<命名空间>.<dashboard>.<名字>`（超过 64 个字符时取哈希）。命名空间和用户名都不含点，所以不同命名空间、不同 dashboard 的用户不会重名；口令取自声明的 Secret，以 MySQL 口令哈希写入（口令本身不发给 Doris），只得到所列查询对象的 `SELECT`。状态里只记哈希的 HMAC（密钥是 root 的口令），不记哈希：截获一次登录时，哈希就能登录。

建对象的不是 root，而是每个 dashboard 自己的 owner 用户（`lakedashboard-<uid>`，口令由 root 的口令派生，不存放）：它只能读这个 dashboard 的 catalog、在它的数据库里建对象。所以查询读不到声明以外的东西，也读不到别的 dashboard 的库；查询里写 Doris 自己的库（`information_schema`、`mysql`、`__internal_schema`）在声明校验时就被拒。root 只有 Operator 与安装脚本用。审计日志里有 `CREATE CATALOG`、`ALTER CATALOG` 带出的 token 明文（Doris 只遮口令）。审计日志与 catalog 定义因此都只在管理账号边界内可见；token 30 天到期，续期后旧 token 被撤销。

每个 dashboard 一个 workload group（`wg_…`），它的用户与 owner 都在其中：`workload.maxConcurrency`（默认 10）个查询同时跑，其余排队，最多排 4 倍，排队超过 10 秒失败。一个看板的突发查询因此占不满别的看板的并发。Doris 4.1 让任何用户都能 `SET workload_group = 'normal'`：撤销 `normal` 的 USAGE 也拦不住，workload policy 也没有改会话变量的动作。所以安装脚本把 `normal` 压到 2 个并发、4 个排队；root 与 admin 改到不设限的 `platform`；Operator 再把每个用户的连接数限为 `maxConcurrency`。用户跳到 `normal`，只会更慢。排队只管执行：FE 的规划在排队之前，外表查询在规划时读 catalog 的元数据，这部分不受限制。

**BI 查询界面的登录名（M5）。** 用户可以带 `sso.username`：入口用户池里的一个人，在 BI 查询界面（Superset，[Web 入口与控制台 LLD](colvenna-console-lld.md) 的"BI 查询界面"）里就是这个 Doris 用户。同一个 dashboard 里，一个登录名（不分大小写）只能给一个用户。Doris 用户建好之后，Operator 把带登录名的用户写进 BI 命名空间的 Secret（Helm 值 `bi.namespace`、`bi.credentialsSecret`，`BiCredentials`）：键是 Doris 用户名，值是登录名、Doris 用户名、口令（就是 `passwordSecret` 里的那个，直连与经 BI 是同一个身份）、dashboard 的数据库与 `<命名空间>/<名字>`。只改这个 dashboard 的条目，与别的 dashboard 同时写时按 resource version 重试；去掉登录名、dashboard 被拒或被删除时撤回。Operator 对这个 Secret 只有 `get` 与 `update`，安装脚本建空的 Secret。没有配置 BI 时，带登录名的用户照常建在 Doris 里，条件 `BiPublished` 为 `False`（`NoBi`）；写 Secret 失败时为 `False`（`BiUnavailable`），下一轮再写，Doris 那边不受影响（`DorisReady` 与授权照旧）。Secret 不存在时，撤回是空操作，dashboard 照样能删除。

Doris 默认给每个新用户 `information_schema` 与 `mysql` 的读权限。前者的 `active_queries` 列出所有用户正在跑的 SQL（实测：普通用户看得到 root 的查询），包括字面量，以及 Operator 建 catalog、换 token 的语句；后者列出所有用户名。Operator 对每个用户和 owner 撤销这两项。`SHOW DATABASES`、`SHOW TABLES` 照常可用，但依赖 `information_schema` 做元数据浏览的 BI 工具要改用 SHOW 语句。Doris 仍向任何用户开放、Operator 撤不掉的：`tasks()`、`jobs()` 列出所有物化视图的名字、所在库、刷新时间和错误信息；`SHOW BACKENDS` 列出 BE 的主机与端口。用户也能 `SHOW CREATE VIEW` 看自己视图的定义，其中有 catalog 名和湖表名，没有 token。这些是元数据，不是表数据，记为边界。HLD 12.2 设想 Doris 用户经 LDAP 认证；M3 的用户是 Operator 管理的 Doris 本地用户，口令来自声明的 Secret，LDAP 与 group 到 role 的映射留给后续。

**治理。** 只能读本命名空间分了类的表（同 LakeTable 的上游规则）。物化视图和内表是副本：有它们的 dashboard 必须声明 `governance`，级别不低于所读的表，读个人数据就必须声明个人数据类别。声明无效或被治理拒绝时，它的 Doris 用户被删掉，谁也查不到；物化视图暂停刷新（`PAUSE MATERIALIZED VIEW JOB`），不再从湖里复制；`phase` 为 `Failed`，条件 `Ready` 为 False。对象留着。声明改好后，用户再建回来，刷新也恢复；被治理拒绝的看板每 30 秒重新判定一次，所以所读表的声明或质量门禁变了，它不改声明也会恢复。

**对账。** 每 30 秒一轮。查询的定义（SQL、`serve`、`refresh`、`key`）不变、对象还在，就什么都不做；对象不在了（被人删了）就重建。`serve` 变了，就按状态里记的原类型删掉旧对象（`DROP VIEW`、`DROP MATERIALIZED VIEW`、`DROP TABLE`，Doris 不允许用错类型删）。状态里不认识的同名对象，三种类型都试着删一次。内表重写失败、物化视图刷新失败时，副本保持原样，查询记下错误，Warning 只在错误变化时发；连接或权限出错时整轮重试，并在下一轮把用户、授权、owner、workload group 全部重做一遍。没有出错时，这些授权在声明变更后或每 10 分钟重做一次：每条授权都写 Doris 的元数据日志，不必每轮都写。物化视图的刷新时间取 Doris 的 `tasks()` 里成功任务的 `UNIX_TIMESTAMP(MAX(StartTime))`，与 FE 的时区无关。

**删除。** 物化视图和内表每次刷新都整表重算，所以删除请求（LakeErasure）把它们各列为一个 `Dashboard` 副本：所读的个人数据表的当前行都完成（`Rows` 为 `Done`）之后，一次在 `clean` 快照之后开始的成功刷新就是证据；表里又有了这个主体，副本退回 `Pending`。视图不留副本。受理之后才建的 dashboard 也会加进来：它建的时候湖里可能还有这个主体。dashboard 声明了查询但还没提供出来时，副本保持 `Pending`。

M0 要求以"指定查询模板的负向查询"作证据；这里的副本多是聚合或维表，不一定带主体键，所以证据是刷新的时序（刷新从 `clean` 之后的湖整表重算），不做负向查询。这一点记为偏差。

物理上，刷新替换下来的数据还留在 Doris 的存储里，列为平台副本 `DorisStorage`：最后一个 `Dashboard` 副本完成 25 小时之后完成（回收站 1 天，再加一小时的垃圾清理）。

Doris 的查询日志可能在 SQL 字面量里带着主体的值，包括已经删掉的 dashboard 的查询。所以只要平台装了 Doris、命名空间里有个人数据，就列为平台副本 `DorisAuditLog`：受理 7 天零一小时之后完成，在 9 天的物理期限之内。分区按天且含当天，一条查询最多留 7 天；多出的一小时留给 Doris 的分区调度。

**回刷。** HLD 10.1 要求物化视图刷新、回刷和合规删除都把派生副本纳入完成检查。所以分层表的回刷（LakeTask）收敛时，也等读到带着变化的表的物化视图和内表：每个副本都要有一次在那张表提交了变化之后开始的刷新（`status.waitingFor` 列出还没刷新的副本）。

**状态与指标。** `status.queries` 记每个查询的对象、上次成功刷新开始的时间和它的年龄；`colvenna_dashboard_refreshed_timestamp_seconds`、`colvenna_dashboard_phase`、`colvenna_dashboard_measured_timestamp_seconds`，告警 `ColvennaDashboardStale`（副本持有的湖旧过 M0 的 p99，10 分钟）、`ColvennaDashboardDegraded`、`ColvennaDashboardUnmeasured`。删除 dashboard 时按名字删掉它的用户、owner、数据库、catalog 和 workload group：名字由 dashboard 本身推出，状态丢了也删得掉。然后撤销它的身份。

## 批量质量校验（LakeQuality）

Issue #38 让用户对历史分区和当前数据做批量质量校验，并依据结果决定是否放行下游发布（HLD 9.2、12.6、12.7）。上文"数据质量"是流内检查：逐行判断，坏行进隔离表。这里的校验以表、快照或分区为单位，按声明的指标打分，结果决定门禁开还是关。两者互不替代。执行路径按 M1 的批处理决策（[批处理边界](../history/m1/batch-engine-boundary.md)），即 Kubernetes Job 里跑 Flink batch，不依赖第三方质量框架。

### 规则

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeQuality
metadata: {name: clicks, namespace: colvenna-data}
spec:
  table: {database: ods_web, table: clicks}     # 本命名空间的 ODS 表或分层表，同 LakeTask
  rules:
    - {name: user-present, notNull: {column: user_id, minRatio: "0.999"}}
    - {name: event-unique, unique: {columns: [event_id]}}
    - {name: volume, rowCount: {min: 1}}
    - {name: fresh, freshness: {column: event_time_ms, maxAge: 2h}}
  gate: {publish: true, maxAge: 26h}            # 可省略：省略时只记录结果，不拦发布
```

每条规则只有一种指标：

| 指标 | 量值 | 通过 |
| --- | --- | --- |
| `notNull` | 非空行占比 | 非空行数 ≥ `minRatio` × 行数（默认 `minRatio` 为 `"1"`，按十进制精确比较）；没有行时通过，空表由 `rowCount` 判断 |
| `unique` | 重复的键个数 | 这些列（任一列为 NULL 的行不参与）没有重复的值；失败行数是重复键涉及的行数 |
| `rowCount` | 行数 | 在 `min`、`max` 之内 |
| `freshness` | 最新一行的年龄（秒） | 校验时刻（见下文 `asOf`）减去列的最大值不超过 `maxAge`。列可以是 `TIMESTAMP`（按 UTC）、`TIMESTAMP_LTZ` 或 `BIGINT`（毫秒时间戳）。没有行时不通过 |

前三种指标作用于这次校验的范围（整表或所选分区）。`freshness` 是整表的性质：即使只校验某些分区，它也按整张表计算。所以对历史分区的校验通常不声明它，或者接受它不通过。

一张表只能有一份 `LakeQuality`。后建的一份判为 `Failed`（`Conflict`），同一秒内按 uid 先后。规则引用的列不存在、类型不对，`minRatio` 不在 0 到 1 之间，或者规则名重复，条件 `RulesValid` 都为 False。表的 schema 变化后，每一轮都重新判定。

**规则版本。** `status.ruleVersion` 是规则按名字排序后的规范 JSON 的 SHA-256 前 12 位。每次校验都记下它运行时的规则版本；门禁只看当前版本的校验结果。改规则后，旧的结果不再算数，直到在新版本下重新校验。版本按内容计算：把规则改回原样，原来那个版本下的结果重新算数，它们正是在这些规则下得出的。

### 校验一次（`LakeTask` 的 `check`）

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: check-clicks-20261016, namespace: colvenna-data}
spec:
  type: check
  table: {database: ods_web, table: clicks}
  check:
    partitions: ["dt=2026-10-16"]      # 可省略：整表
    # snapshot: 1234                    # 可省略：开始时的最新快照
    # asOf: "2026-10-16T08:00:00Z"      # 可省略：开始的时刻
```

校验是一种 `LakeTask`，所以沿用它的语义：只运行一次、同一张表上的任务依次运行、失败重试、终态可等待。另外：

- **准入。** 表必须有 `LakeQuality`（否则 `NoRules`）。它刚建或刚改、还没判定当前的声明时，任务停在 `Pending` 等它，所以两者可以一起 apply；判定的结果是规则不合表时，任务判为 `Failed`（`InvalidRules`）。分区只能给分区表，列名必须是分区键（`InvalidPartitions`）；表要有快照（`NoSnapshot`），给出的快照要还在（`SnapshotMissing`）；`asOf` 不能晚于现在（容许 1 分钟时钟偏差，否则 `InvalidSpec`），否则它能让门禁永不过期。
- **固定输入。** 开始时，Operator 把要校验的快照、`asOf`（默认开始的时刻）和所声明规则的版本写进 `status.check`，此后每次尝试都用它们；某次尝试开始前规则已经变了，任务判为 `Failed`（`RulesChanged`），不会用新规则跑出一个记着旧版本的结果。所以重试的结果与第一次相同，而且新写入的数据不会混进这次校验。要复现一次早先的校验，就新建一个任务，给出同样的 `snapshot`、`asOf` 和分区。
- **身份。** 只读这张表（表级 `READ`，不是其他任务的 `WRITE`），写结果表（见下文）。
- **运行。** `TaskJob` 以 `scan.snapshot-id` 读这个快照：一条聚合查询算出行数与各列的非空数；每条 `unique` 规则一条 `GROUP BY ... HAVING COUNT(*) > 1`；`freshness` 读整表在这个快照的最大值。全部规则算完之后，结果在**一次提交**里写进结果表，所以要么整次校验都在，要么都不在。
- **结论。** 任务 `Succeeded` 表示校验跑完并且结果已写入，与是否通过无关。`status.check.verdict` 为 `Passed` 或 `Failed`，`status.check.failed` 列出未通过的规则，并发 Normal `QualityPassed` 或 Warning `QualityFailed`。运行出错、没有写出结果的任务是 `Failed`（`RunFailed`），它没有结论，门禁不把它当作通过。少见的情形是某次尝试已经提交了结果、之后才出错（例如重试时快照已过期）：这些结果是一次完整的判定，照样算数，任务的状态仍是 `RunFailed`。

`colvenna check <db.table> --name <任务> [--partition dt=...]... [--snapshot N --as-of T] [--wait]` 建这个任务。`--wait` 的退出码：通过为 0，任务失败为 1，等待超时为 2，规则未通过为 3。编排器按退出码决定要不要继续下游的发布步骤。

### 结果与可定位

每个命名空间有一个结果库 `quality_<命名空间>`（`-` 换成 `_`；超过 63 个字符时截短并加哈希），由 Operator 建，`colvenna-lake.owner` 为 `LakeQuality/<命名空间>`。库里两张表：

| 表 | 写入方 | 内容 |
| --- | --- | --- |
| `results`（主键 `checked_table`、`task_uid`、`rule`，按 `checked_table` 分区） | 校验任务 | 每次校验的每条规则一行：任务名与 uid、被校验的表、快照与它的提交时间、`asOf`、分区、规则版本、规则定义（JSON）、指标、量值、阈值、是否通过、失败行数、校验的行数、定位语句、写入时间 |
| `decisions`（append 表） | Operator | 门禁的每一次变化：时间、开或关、原因、依据的校验（任务与 uid、快照、规则版本、未通过的规则）、依据的豁免（名字、uid、批准人、理由） |

主键使结果幂等：同一次校验的重试写出同样的行，覆盖而不叠加。Operator 每轮按分区只读这张表自己的结果，结果表没有新快照时沿用上一次读到的。开发阶段不保证升级兼容：结果库的布局变了，Operator 判 `StoreReady` 为 False，要删掉旧表重建。两张表都不含行数据，只有规则、指标、分区值和名字，因此不属于个人数据的副本。前提是分区键不是个人数据：平台的分区表只有 Kafka 事件表，分区键是日期和小时。它们只有 Operator 和平台管理员能读，用户经由 `LakeQuality` 与 `LakeTask` 的状态看到结论。

**失败数据的定位。** 每条规则的 `locate` 是一条 Flink SQL，在同一个快照上列出未通过的行：`notNull` 是该列为 NULL 的行，`unique` 是重复的键及其行数，`rowCount` 与 `freshness` 是对应的聚合。能读这张表的人就能执行它，结果表里不保存失败行的值。快照可能很快过期（默认保留 1 小时），所以有规则未通过时，Operator 给这个快照打一个 tag `colvenna-check-<任务 uid 前 8 位>`，在 `status.checks[].tag` 中写明，用 `scan.tag-name` 代替 `scan.snapshot-id` 即可再读。tag 的保留期是 7 天与表的保留期（`governance.retention`；含个人数据或没有分类的表最多 24 小时）中较短的一个。Operator 删除自己打的、已过保留期的 tag。快照在打 tag 之前就已过期时，状态写明只能给出计数。删除流程（`LakeErasure`）照常删除指向更早快照的 tag。

### 门禁

门禁只看当前规则版本的结果，按以下步骤判定：

1. **覆盖关系。** 每次校验覆盖一个范围：整表或一组分区。如果 B 校验的快照比 A 新（快照相同时比写入时间），并且 B 的范围包含 A 的范围，B 就取代 A。整表的范围包含任何分区。
2. **仍有效的校验。** 没有被取代的校验，就是仍然有效的。每个范围都以最近一次覆盖它的校验为准。
3. **开或关。**

| 门禁 | 原因 | 条件 |
| --- | --- | --- |
| 关 | `NoCheck` | 当前规则版本下还没有完成的校验（包括刚改过规则） |
| 关 | `Failed` | 有仍然有效的校验存在未通过且未被豁免的规则 |
| 关 | `Expired` | 声明了 `gate.maxAge`，而最近一次校验的 `asOf` 早于它。用当前的 `asOf` 复查一个旧快照，也算一次对那些数据的校验；要拦住陈旧的数据，声明 `freshness` 规则 |
| 关 | `NotReady` | `RulesValid` 为 False，或结果库读不到。宁可关，不猜 |
| 开 | `Passed` / `Waived` | 其余情况；后者表示有校验靠豁免通过 |

结果表是唯一的依据。删掉 `LakeTask` 不会让门禁回到更早的一次通过，因为失败的结果仍在表里。还在运行或运行出错的校验没有结果，所以不会被当作通过。门禁打开也只说明到某个快照为止的数据：`status.gate.check` 写明依据的快照与范围。此后写入的数据和其他分区还没有校验，状态里写为"之后未校验"（只校验了部分分区时，还写明其他分区未校验），不写为通过。

**影响什么（`gate.publish: true`）。**

- `LakeSource` 与 `LakeTable` 的发布：门禁关闭时，这张表不发给 `readers`，已有的表级 `READ` 撤销。门禁不涉及的表照常发布。条件 `Published` 的消息里写明扣下了哪张表以及门禁的原因；没有表可发布时，原因为 `QualityGate`。门禁打开后重新授予。
- `LakeDashboard`：读到门禁关闭的表时，按治理拒绝处理（见"看板服务"）：用户被删掉，物化视图暂停刷新，副本停在最后一次刷新时的样子。被拒的看板每 30 秒重新判定一次，门禁打开后的下一轮，用户和刷新就恢复，声明不用改。
- `LakeIcebergPublication`：门禁关闭时，发布按 `QualityGate` 拒绝，表从 Iceberg 目录撤下（见"开放层：Iceberg 发布"）。
- 生效时间：门禁的状态由 `LakeQuality` 的协调器写入：它不监听校验任务，每 15 秒重新判定一次。发布方在下一轮协调时（30 秒一轮）撤销或授予，所以从校验结束到发布变化最长约 45 秒。`LakeQuality` 还没判定过门禁时，发布方以原因 `Pending` 先扣下这张表。

不声明 `gate`（或 `publish: false`）时，结果照样记录，门禁照样计算，只是不拦发布。

### 豁免（`LakeWaiver`）

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeWaiver
metadata:
  name: clicks-20261016-late
  namespace: colvenna-data
  annotations: {colvenna-lake.io/approved-by: "<建它的 Kubernetes 用户名>"}
spec:
  check: check-clicks-20261016      # 一次未通过的校验
  rules: [fresh]                    # 接受其中哪些规则
  reason: "上游 03:00–05:00 停机，负责人确认可以发布"
```

- 一份豁免只针对一次校验（任务名在接受时解析为结果里的 uid），不延续到以后的校验：下一次校验又不通过，就要再豁免一次。spec 创建后不可修改。
- 列出的规则都必须是这次校验未通过的规则，否则判为 `Rejected`。可以只接受其中一部分：一次校验所有未通过的规则都被接受（一份或几份豁免），门禁才因它打开。豁免必须建在校验写出结果之后，事先的"预批准"判为 `Rejected`。校验已被更新的校验取代，或表的规则在这次校验之后改了，为 `Superseded`，对门禁不再起作用。校验还没有结果时为 `Pending`；没有批准人注解（准入策略没装）判为 `Rejected`。删掉豁免等于撤回，门禁重新判定。
- **批准人。** chart 安装一条 ValidatingAdmissionPolicy：创建时注解 `colvenna-lake.io/approved-by` 必须等于发起请求的 Kubernetes 用户名，之后不能修改。所以批准人不是自己填写的名字，而是 API server 认证过的身份。`colvenna waive` 用 SelfSubjectReview 取得自己的用户名后填入。经管理控制台建的豁免（M5 #138），请求的用户是控制台扮演的 `colvenna-console:admin`，本人在用户附加信息 `colvenna-lake.io/person` 里。准入规则对 `colvenna-console:admin` 与 `colvenna-console:operator` 两个控制台用户都适用：请求恰好带一个 `colvenna-lake.io/person` 值时，注解必须等于 `<用户>/<本人>`；否则退回到用户名本身（只有控制台能扮演它们、带上这项附加信息，见 [Web 入口与控制台 LLD](colvenna-console-lld.md)）。两个用户里只有 `colvenna-console:admin` 有 `lakewaivers` 的 create 权限，所以经控制台建豁免要管理员角色。EKS 控制面的审计日志另外记录了这次创建。
- **谁能豁免。** 由 Kubernetes RBAC 决定：chart 提供 ClusterRole `<release>-waiver-approver`（`lakewaivers` 的 create、patch、get、list、watch、delete：`colvenna waive` 以 server-side apply 提交豁免，需要 `patch`；以及读 `laketasks` 与 `lakequalities`，用来看自己要豁免的是什么），由管理员绑定给数据负责人。能建校验任务的人不一定能豁免。

门禁的每次变化（开、关、原因或依据的校验、豁免变化）都追加一行到 `decisions`，同时记进 `status.decisions`（最近 20 条），并发事件：打开为 Normal `GateOpened`，关闭为 Warning `GateClosed`；还没有校验（原因 `NoCheck`）而关闭时是 Normal `GateClosed`。每一行写的是这个结论所依据的校验：门禁因失败而关闭时，是未通过的那一次（有几次时取快照最新的，消息里列出全部），否则是最近的一次。写 `decisions` 失败时，下一轮重写，不跳过；结果库长时间不可用时，状态里最多留 100 条没写进去的决定，更早的只在事件和状态历史里出现过。

### 状态与指标

| 渠道 | 内容 |
| --- | --- |
| `status` | `phase`（`Pending`：表不存在；`Ready`；`Degraded`：规则不合表或结果库读不到，门禁关闭；`Failed`：同一张表已有更早的一份，或表不属于本命名空间，它不是门禁），`ruleVersion`，`store`（结果库），`gate`（开或关、原因、消息、自何时、依据的校验与豁免），`checks`（仍然有效的校验：任务、快照、范围、通过与否、tag），`decisions` |
| 条件 | `RulesValid`、`StoreReady`、`GateOpen` |
| 事件 | `GateOpened`、`GateClosed`、`CheckTagged`；任务上的 `QualityPassed`、`QualityFailed`；豁免上的 `WaiverAccepted`、`WaiverRejected` |
| 指标 | `colvenna_quality_gate_open{cause}`、`colvenna_quality_rule_passed{rule}`（最近一次校验）、`colvenna_quality_last_check_timestamp_seconds`、`colvenna_quality_measured_timestamp_seconds` |
| 告警 | `ColvennaQualityGateClosed`：要拦发布的门禁关闭超过 5 分钟；`ColvennaQualityUnmeasured`：10 分钟没有判定 |

删除 `LakeQuality` 时，在 `decisions` 中记一行"门禁移除"，再删除它打的 tag。结果库和结果保留，它们是审计记录。

## 血缘

Issue #37 把平台的作业、表和它们之间的读写关系交给外部的元数据与血缘系统（HLD 12.6–12.7）。Flink 1.20 运行时不产出血缘（FLIP-314 的构图与监听器在 2.0 才有），Paimon 的 CDC 多表 sink 即使在 2.x 上也不产出，Doris 的 LineagePlugin 没有自带的输出端，而且看不到视图（[调研](../research/lineage.md)）。所以由 Operator 发：它知道每个作业读写哪些表，在跑哪个版本。

**发什么。** Operator 以 [OpenLineage](https://openlineage.io/spec/2-0-2/OpenLineage.json) 2-0-2 的 RunEvent 上报，facet 取 OpenLineage 1.54.0 的版本。接收端由用户选择（Helm 值 `lineage.url`），可以带一个 API key（`lineage.apiKeySecret`，作为 bearer token 发送）。验收用 Marquez 0.51.1（`deploy/scripts/open-layer/install-marquez.sh`）。DataHub 与 OpenMetadata 也接收 OpenLineage 的 RunEvent，但没有验收：OpenMetadata 默认只处理 COMPLETE 事件，要求库和 schema 的实体预先存在，流作业要改它的设置。

| 作业 | OpenLineage job（命名空间 `colvenna-lake.<命名空间>`） | 读 | 写 | 依据 |
| --- | --- | --- | --- | --- |
| 源的每个 Flink 作业 | FlinkDeployment 的名字（如 `orders-src-cdc`） | 它承载的源表；Kafka 承载时为对应的 topic；Kafka 事件源为它的 topic | 这些表的 ODS 表；Kafka 事件源为事件表和拒绝表 | 声明并准入的表（`declared`） |
| 分层表的作业 | FlinkDeployment 的名字（如 `dwd-orders-table`） | planner 校验时找到的流读表与 lookup 表（`status.applied.streams`、`lookups`） | 目标表与隔离表 | planner（`planner`） |
| 回刷的每次尝试 | `LakeTask.<名字>`，BATCH | 所属分层表的作业所读的表 | 目标表与隔离表 | planner |
| 看板的每个查询 | `LakeDashboard.<名字>.<查询>` | 查询 SQL 里点名的声明表（它的 Doris catalog 只能读这些表） | Doris 里的视图、物化视图或内表 | 声明（`declared`） |

**一次运行就是一个版本在跑。** Flink 作业的版本是 FlinkDeployment 的 spec 哈希（注解 `colvenna-lake.io/spec-hash`），一次运行是这个版本下的一个 Flink job（run id 由 deployment uid、哈希、Flink job id 确定地算出，重算得到同一个 id）。job facet `sourceCodeLocation` 指向 Kubernetes API 里的这个对象并带上版本（`https://kubernetes.default.svc/apis/.../flinkdeployments/<名字>?version=<哈希>`）：Marquez 按它给作业分版本，而且只保留 https 地址。运行 facet 里有 Flink job id（`externalQuery`）、引擎版本（`processing_engine`），以及自定义 facet `colvennaLake`：声明的种类、名字、uid、generation、deployment、spec 哈希、镜像和证据等级（[schema](../../colvenna-operator/src/main/resources/openlineage/ColvennaLakeRunFacet.json)）。回刷的版本是任务的 uid，每次尝试一个运行；看板查询的版本是它在 Doris 里生效的哈希（`status.queries[].applied`）。

**数据集。** 湖表的命名空间是 `paimon://<colvenna-catalog 的主机>:<端口>`，名字是 `colvenna.<库>.<表>`（与 Flink SQL 里的标识一致），带 colvenna-catalog 的列（名字、Paimon 类型、注释，按顺序）和 `catalog` facet（`framework: paimon`）。列在上报时从 colvenna-catalog 读，所以外部目录看到的 schema 就是权威目录的。不发 S3 位置的 `symlinks`：Marquez 会把它当成另一个数据集。源表按 OpenLineage 的命名约定：`mysql://host:port` 下的 `库.表`，`postgres://host:port` 下的 `库.schema.表`，`kafka://<第一个 bootstrap>` 下的 topic；MongoDB 没有约定，用 `mongodb://host:port` 下的 `库.集合`。Doris 对象是 `doris://<FE>:9030` 下的 `internal.<库>.<对象>`。源表、topic 和 Doris 对象不带列。

**何时发。** 当前的 leader 每分钟（`lineage.intervalSeconds`）对每个受管命名空间算一遍：新出现的运行发 START，跑着的运行有变化（多了一列、多读一张表）时发 RUNNING，结束的发 COMPLETE 或 FAIL；一个作业换了版本、被挂起或删除，先结束它原来的运行（COMPLETE，不带数据集，所以目录保留作业当前的输入输出），再开始新的。升级中、状态还没描述它的 deployment 先不动，等它稳定。发过什么记在 Operator 命名空间的 ConfigMap `<release>-lineage` 里（每个命名空间一个键），所以重启后的 Operator 不会重复开始运行，也能结束前任开始的运行。接收端没收下的事件下一轮再发，包括认证失败和地址错误（401、403、404），修好之后就能补上；接收端判为错误的事件（400、409、413、415、422）计数、记日志后跳过，否则它每一轮都会被拒，并挡住它后面的所有事件。记录读不出来（例如上一版 Operator 写的格式）时从头开始：当前的运行以原来的 id 重新开始，记录里的旧运行不再结束。

| 渠道 | 内容 |
| --- | --- |
| 指标 | `colvenna_lineage_events_sent`、`colvenna_lineage_events_rejected`、`colvenna_lineage_failures`（自 Operator 启动）、`colvenna_lineage_open_runs`、`colvenna_lineage_last_success_timestamp_seconds`；这一轮暂缺的：`colvenna_lineage_unsettled_jobs`（过渡中的作业，沿用上次的运行）、`colvenna_lineage_tables_without_columns`（colvenna-catalog 没有返回的表，不带列上报） |
| 告警 | `ColvennaLineageStale`：一个命名空间 10 分钟没有完整上报；`ColvennaLineageFailing`：上报持续失败 10 分钟；`ColvennaLineageRejected`：接收端拒收了事件 |

**覆盖矩阵。** "验证"一列：EKS 指 `tests/acceptance/lineage/run-lineage.sh` 在集群上验收过，单元测试指只在 `LineageGraphTest` 等测试里覆盖。

| 操作 | 上报 | 验证 | 说明 |
| --- | --- | --- | --- |
| MySQL 源到 ODS | 是 | EKS | 读写按声明并准入的表；运行时没有逐条观测 |
| PostgreSQL、MongoDB 源到 ODS | 是 | 单元测试 | 命名见上文 |
| Kafka 承载的 CDC 到 ODS | 是（topic 到 ODS） | 单元测试 | 源库到 topic 一段是用户的 Debezium，平台不发 |
| Kafka 事件源到 ODS | 是 | 单元测试 | 读 topic，写事件表与拒绝表 |
| ODS 到 DWD、DWS（分层表） | 是 | EKS | planner 找到的读表；SQL 取部署里运行的那份 |
| 分层表换版本（升级、挂起与恢复） | 是 | EKS | 旧运行在新运行开始时结束 |
| 回刷 | 是，BATCH | EKS | 所属分层表的读表 |
| 看板的外表视图 | 是 | EKS | 声明表中被 SQL 点名的 |
| 看板的物化视图、内表 | 是 | 单元测试 | 同上；每次刷新不单独上报 |
| 批量质量校验（`check`） | 否 | — | 读一张表，写结果库，不构成表间数据流 |
| 维护任务：compact、expireSnapshots、removeOrphanFiles、createTag、deleteTag | 否 | EKS（compact） | 同一张表上的维护，不搬运行 |
| 即席查询：Doris SELECT、`SqlQuery`、读端自己的引擎 | 否 | EKS（`SqlQuery`） | 没有平台作业 |
| 删除：`LakeErasure`、DELETE | 否 | — | 不推断为血缘；Operator 不为它们发事件 |
| 列级血缘 | 否 | — | 不推断 |
| Iceberg 发布、读端引擎 | 否 | — | 不在 #37 范围 |
| Flink 运行时血缘 | 不可用 | — | Flink 1.20 没有；升级到 2.x 后再评估 |
| Doris LineagePlugin | 不接 | — | 没有自带输出端，看不到视图，事件尽力而为；外部 catalog 属性里可能带 colvenna-catalog token |

**外部目录的安全。** Marquez 没有认证，所以安装脚本只建 ClusterIP 服务，用 NetworkPolicy 只放行带 `colvenna-lake.io/lineage-client` 标签的命名空间（Operator 所在）和它的 Web UI；人经由 `kubectl port-forward` 访问，由集群的 IAM 认证把关。剩余风险：带这个标签的命名空间里的任何 Pod 都能不带凭证写入 Marquez，所以只给 Operator 的命名空间打标签。DataHub 与 OpenMetadata 自带 token 认证，Operator 以 API key 发送。

## 开放层：Iceberg 发布（LakeIcebergPublication）

Issue #34 让需要开放读取的 Paimon 表以 Iceberg 兼容的形式发布到一个 Iceberg REST 目录，供只认 Iceberg 的外部读端读取（HLD 10.3、12.1）。写入权威仍在 Paimon：外部读端只读，不能改 Paimon 的数据，也只能读到发布给它的表。

### 开放层的组件

| 组件 | 做法 |
| --- | --- |
| Iceberg REST 目录 | Apache Polaris 1.8.0（`deploy/scripts/open-layer/install-polaris.sh`），命名空间 `colvenna-polaris`，元数据库在项目的 RDS 上（库与角色 `polaris`）。只有 ClusterIP，TLS 证书由 colvenna-catalog 的私有 CA 签发；所有 API 都要 OAuth2 token（客户端凭证），不接受匿名请求。chart 默认带一个 LoadBalancer 类型的附加服务，安装时去掉，并检查渲染结果里没有 LoadBalancer、NodePort、Ingress。NetworkPolicy 只放行带 `colvenna-lake.io/iceberg-client=true` 标签的命名空间 |
| 目录 `colvenna_open` | EXTERNAL 类型（Polaris 所谓的 static facade）。Iceberg 客户端对它的建表、提交、改名和 purge 在代码层就被拒绝（400），表只能由通知接口登记。存储配置的角色 `colvenna-lake-iceberg-read` 只能读仓库前缀：Polaris 以它读元数据、给读端签发凭证 |
| 发布者 | colvenna-operator：Polaris principal `colvenna-operator`，在 `colvenna_open` 上只有 `CATALOG_MANAGE_METADATA`（发通知）与 `CATALOG_MANAGE_ACCESS`（给读端的 catalog role 授权），没有任何数据权限 |
| 写入端 | 不变。发布的表带 `metadata.iceberg.storage=table-location`：每次 Paimon 提交，写入端在表目录的 `metadata/` 下写一份 Iceberg 元数据（`v<快照号>.metadata.json`）。这一步不访问 Polaris，也不需要它的凭证；Polaris 不可用时写入照常 |

为什么不用 Paimon 自带的 `rest-catalog` 发布：它在每次提交成功后同步调用 REST 目录，目录不可用时写入作业报错重启，摄入停滞；每个写入进程都要持有目录凭证；平台也无法决定发布哪个快照（[调研](../research/iceberg-open-layer.md)）。

### 发布的声明

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: clicks, namespace: colvenna-data}
spec:
  table: {database: ods_web, table: clicks}   # 本命名空间的 ODS 表或分层表；建好后不能改
  columns: [event_id, user_id, event_time_ms, dt, extra_json, _topic, _partition, _offset]
  readers: [web_readers]                       # colvenna_open 里的 catalog role
```

在 Iceberg 里，表的名字就是 Paimon 的 `<库>.<表>`：命名空间是库，表名相同。Paimon 的库名全局唯一，所以两个声明不会发布出同名的表。一张表只能有一份发布，后建的一份判为 `Failed`（`Conflict`）。`table` 建好后不能改（CEL 规则 `self == oldSelf`）：改了表，旧表的登记和授权就没人撤了；要换表，另建一份发布。

`columns` 是声明者为这些读端复核过的列。Iceberg 读端拿到的是整张表，不能只给其中几列，所以表里有一列不在 `columns` 里，整张表就不发布（`ColumnsUnreviewed`）。这是 HLD 12.5 的要求：自动演进的表不得在表级读权限下直接暴露未复核的新列。MySQL、PostgreSQL 的 CDC 源会自动加列，Kafka 源的字段是声明加的；两种情况都要把新列写进 `columns`，表才会重新发布。

这道门在目录层：新 schema 的元数据登记到目录之前，表就撤下了。存储层有一个窗口：撤下发生在 Operator 的下一轮（30 秒内），在那之前已写进表目录的新列文件，持有未过期签发凭证（最长 15 分钟，见"安全边界"）的已授权读端可以绕过目录直接从 S3 读到。要连这个窗口也没有，就发布一张显式列出列的分层表（`LakeTable` 不做 schema 演进，见"已知限制"），而不是自动演进的 ODS 表。

读端由平台管理员在 Polaris 里建 principal、principal role，再把 `colvenna_open` 里的 catalog role 分配给它；发布只给声明的 catalog role 授这张表的 `TABLE_READ_DATA`。

### 能否发布

每一轮按以下条件判定，任一不满足就不发布（条件 `Publishable` 为 False），已发布的撤下：

| 原因 | 条件 |
| --- | --- |
| `NotManaged` / `TableMissing` | 表不属于本命名空间的 LakeSource 或 LakeTable，或不存在 |
| `NotGoverned` | 声明这张表的 LakeSource 或 LakeTable 判它不受治理（条件 `Governed`） |
| `Restricted` | 级别高于 L2，或含个人数据（包括没有分类、按临时 L4 处理的表）。HLD 10.3：受限的表默认不发布，Iceberg 读端不继承 colvenna-catalog 和 Doris 的用户策略 |
| `QualityGate` | 表的 `LakeQuality` 要拦发布，而门禁关着（见"批量质量校验"） |
| `NoIcebergMetadata` | 表没有 `metadata.iceberg.storage=table-location`，写入端不写 Iceberg 元数据。要在声明表时就带上它（`tableOptions`），见下文 |
| `DeletionVectors` | 表开了 deletion vector。Iceberg v2 表达不了，Paimon 2.0.0 又发布不出正确的 v3 元数据，所以不发布 |
| `ColumnsUnreviewed` | 表（colvenna-catalog 里的当前 schema）有不在 `columns` 里的列，消息列出这些列；colvenna-catalog 没有返回列时也是 |

`tableOptions` 新增两项可以声明：`metadata.iceberg.storage`（只能是 `table-location`）与 `metadata.iceberg.previous-versions-max`。Operator 给带前者的表把后者默认设为 `30`：Paimon 默认每次提交都删掉旧的元数据文件，而 Polaris 每次 `loadTable` 都重读目录登记的那一份，读端正在用的版本不能马上消失。

### 发布

1. 读表最新的快照 N，在表目录的 `metadata/v<N>.metadata.json` 找到它的 Iceberg 元数据，并核对其中的当前快照就是 N。写入端偶尔没写出这份文件时（例如提交回调出错），保持已发布的版本，状态写明（`MetadataMissing`）。
   再核对这份元数据持有的数据文件，恰好是 Iceberg 应当看到的文件：主键表是快照 N 中最高层的文件（全量压缩之后的数据），append 表是快照 N 的全部文件。不一致时同样保持已发布的版本（`MetadataIncomplete`）。原因是 Paimon 2.0.0 逐个提交以前一份元数据为底生成下一份；前一份不在时（不跑提交回调的提交，例如 manifest 压缩），它从头重建，只取不需合并的 split，主键表里有未压缩改动的 bucket 就从视图里消失（实测，`LakeReaderTest` 复现）。下一次全量压缩把这些文件重写一遍，视图恢复，发布随之继续。第一次全量压缩之前，主键表不发布（第一份元数据含有尚未压缩的文件）。
   所以全量压缩的 `LakeTask` 对写 Iceberg 元数据的表默认不压缩 manifest（`compact.manifests` 可以显式指定）。删除流程（`LakeErasure`）的压缩照样压缩 manifest：Paimon 与 Iceberg 的 manifest 都记着文件各列的最小值和最大值，其中可能有被删主体的键值。压缩 manifest 之后，任务再做一次空提交：写入端的回调随之从头写一份 Iceberg 元数据，Paimon 删掉所有更早的 Iceberg manifest，它们持有的统计值随之消失（`TaskJobTest` 复现）。删除的检查（`ErasureProbe`）把表目录里每个 Iceberg manifest 中、统计值是主体键值的条目计为 `icebergMentions`，和 Paimon manifest 的一样，不为 0 时 `Files` 这一项不算完成，再压缩一次（`ErasureProbeTest` 复现；EKS 验收见 [M4 开放层验收](../history/m4/iceberg.md) 的"删除流程（补验）"）。这时表含个人数据，本来就不发布。
   已发布时，每一轮都核对 Polaris 里还有这张表。不在了（有人绕过 Operator 删了登记，或 Polaris 的库丢了），就当没发布过（事件 `Lost`）：最新快照能发布就重新登记，不能（`MetadataMissing`、`MetadataIncomplete`）就如实报告目录里什么也没有（`Pending`）。
2. 向 `colvenna_open` 发 `UPDATE` 通知，带这份文件的位置和一个单调递增的序号（`status.sequence`；Polaris 拒绝不比上次新的序号，所以晚到的旧通知不会覆盖新的）。第一次发布时，Polaris 建出这张表。
3. 给声明的每个 catalog role 授这张表的 `TABLE_READ_DATA`，多余的授权撤销。表被撤下再登记后，Polaris 上的授权随旧表一起消失，所以每一轮都核对。
4. 状态记下已发布的快照、元数据文件、发布时刻，以及 Paimon 最新快照比它新多少（`status.lag`）。

每张表一次只有一个发布者（Operator 选主，一张表的协调串行），避开 Polaris 并发更新的已知问题（apache/polaris#4658、#5621）。

**撤下。** 不能发布、声明删除或表不见了时，向 `colvenna_open` 发 `DROP` 通知：只删目录里的登记，不删文件。之后读端找不到这张表。已经签发出去的凭证仍能用到过期，Polaris 把有效期设为 STS 的下限 15 分钟。

### 读端看到什么

- **append 表**：Iceberg 快照 N 与 Paimon 快照 N 的行完全相同，快照号也相同。
- **主键表**：Iceberg 只看得到全量压缩之后最高层的文件，等于 Paimon 的 `$ro`；第一次全量压缩之后才发布。可见的延迟取决于多久全量压缩一次：写入端自己压缩的分层表，Operator 给发布的表设 `compaction.optimization-interval`（固定 1 小时，不能声明）（EKS 实测见 [M4 开放层验收](../history/m4/iceberg.md) 的分层表一节：一次每 4 分钟写一行的运行里，写入端平常的压缩约 11 分钟就让改动可见；表上没有写入时，最后一次改动要等下一次写入才会被压缩）；写入端不压缩的 ODS 表，由编排器定期跑全量压缩的 `LakeTask`。
- **可见性窗口**：一次提交到读端看到它，要等 Operator 的下一轮（30 秒），再加上读端自己的元数据缓存。
- **schema 变更**：新的元数据带新 schema。加的列要先写进 `columns`：在那之前表撤下，读端在目录里找不到它（`ColumnsUnreviewed`；存储层的窗口见"发布的声明"）；写进去之后，下一次发布即生效。
- **tag**：Paimon 把 tag 写进最新的元数据文件，读端能看到；删掉的 tag 随之消失。
- **删除**：Paimon 删除行之后，主键表等下一次全量压缩，append 表随下一次提交，读端就看不到了；被删的行仍在 Iceberg 的历史快照里，直到 Paimon 过期这些快照。删除流程（`LakeErasure`）把每个发布列为 `Reader` 外部副本（读端另存的副本由其负责人删除）。
- **不支持**：在 Iceberg 侧写入、改 schema、建分支或 tag（目录拒绝）；deletion vector 表；分支；Paimon 回滚（2.0.0 会留下内容过时的元数据文件，发布者按快照核对时会发现并停在原版本）；`DROP TABLE` 之后的元数据（Operator 先撤下发布）。

### 安全边界

| 读端试图 | 结果 |
| --- | --- |
| 不带 token 访问 Polaris（目录配置、命名空间、`loadTable`、管理 API） | 401 |
| 从读端命名空间连 Polaris 的管理端口（8182，健康检查与指标） | 连不上：NetworkPolicy 只对带 `colvenna-lake.io/monitoring=true` 的命名空间放行 |
| 它的 catalog role 已从 `readers` 去掉后再读 | 403：授权在下一轮撤销；已签发的凭证用到过期 |
| 读没有授给它的表 | 403 |
| 在 Iceberg 侧建表、提交、加列、改名、purge | 400（EXTERNAL 目录）或 403 |
| 删除登记 | 403（读端没有 `TABLE_DROP`） |
| 用签发的凭证写或删 S3 对象 | AccessDenied：签发的角色只能读 |
| 用签发的凭证读别的表的目录 | AccessDenied：会话策略只覆盖这张表的目录 |

门禁管的是目录里的登记，不是数据本身：签发的凭证能读整张表的目录，包括还没发布的 Paimon 文件，也包括撤下前已拿到凭证的 15 分钟。所以要真正挡住的数据（个人数据、高于 L2）根本不发布，而不是靠撤下。

### 发布的状态与指标

| 渠道 | 内容 |
| --- | --- |
| `status` | `phase`（`Pending`、`Published`、`Withheld`、`Failed`），`published`（快照、元数据文件、时刻），`sequence`，`lag`（Paimon 比已发布的新多少个快照、多少秒），`readers` |
| 条件 | `Publishable`、`Published`（没有配置 Iceberg 目录时原因 `NoCatalog`，表还没有快照时 `NoSnapshot`，读 catalog 或 Polaris 出错时 `Unavailable`）、`ReadersReady`（声明的 catalog role 在 Polaris 里不存在时为 False，原因 `ReaderMissing`） |
| 事件 | `Published`（快照变了才发）、`Withheld`、`Withdrawn`、`MetadataMissing`、`MetadataIncomplete`、`Lost` |
| 指标 | `colvenna_iceberg_published{publication}`、`colvenna_iceberg_lag_snapshots`、`colvenna_iceberg_lag_seconds` |

## 列标签、脱敏与行过滤

Issue #35 在已验证的读路径上交付正式的列分类标签，以及由引擎执行的列脱敏和行过滤（HLD 6.2、12.5–12.7、17）。唯一开放的读路径是平台 Doris 上的看板：用户只能查 Operator 建的对象，这些对象读湖表时经过 Operator 生成的脱敏视图，用户的行由 Doris 的行策略限定。其他读路径（湖表的 `readers`、Iceberg 开放层、Trino、Flink 与 Spark）读到的是整张表，保持表级授权与表级分类，不承诺列级或行级的保护（见文末支持矩阵）。调研见 [governance-enforcement.md](../research/governance-enforcement.md)。

### 列标签

```yaml
governance:
  owner: shop-team
  level: L3
  categories: [personal]
  subjectKeys: {orders: [customer_id]}
  retention: 24h
  columns:                                   # LakeSource 按 <ODS 表>.<列>，LakeTable 按列
    orders.id: {level: L1}
    orders.amount: {level: L2}
    orders.customer_id: {categories: [personal]}
    orders.note: {categories: [personal], mask: mask}
```

- 一个标签给列自己的级别与类别（省略级别时取表的），以及给未获许可的读者看什么：`redact`（NULL，默认）或 `mask`（字母与数字被替换）。没有哈希：HLD 12.1 禁止对低熵标识符用无密钥哈希（读者对可猜的值自己算一遍就能对照），而写在 Doris 视图里的密钥会出现在 `EXPLAIN` 里。需要稳定 join 值的假名（KMS 保护密钥的 HMAC 或 token 服务）不在 #35 范围。
- 没有标签的列（没有标签的表就是它的所有列）按表的级别与类别标注。所以源端新增的列在被标注之前，对未获许可的读者都是 `redact`；没有标签、整表高于读者许可的表，每一列都是 `redact`。
- 标签不能高于表的级别，类别必须在表的类别之内，个人数据至少 L3、敏感个人数据 L4；标签所指的列必须存在。违反任何一条，这张表就不受治理（`Governed` 为 False，原因 `LabelInvalid`）：不发布给 `readers`，读它的看板被拒，直到声明改好。源端删列时目标表保留原列，改名按新列处理（见"支持的变化"，HLD 7.5）：新名的列先被 `redact`，旧名的标签仍然有效。
- 标签以声明为准。Paimon 的表选项和列注释可以镜像它们供人阅读，但不用于授权。

### 看板的许可与脱敏视图

`LakeDashboard` 的 `access: {level, categories}` 是它的用户获准看清的范围，不能高于看板自己的 `governance`（没有 `governance` 时不能高于 L2、无类别）；不声明时取 `governance`，两者都没有时是 L2、无类别。这不是表 owner 的审批：看板的 `governance` 由看板的声明者给出，只要求不低于所读的表，所以同一命名空间里的声明者可以把用户的许可设到 L4。命名空间是信任边界（与 M3 一样，表的 `readers` 和看板的用户也都由本命名空间的声明给出）。每一轮协调里，Operator 从 colvenna-catalog 读出所读表当前的列，对每张有标签或整表高于这个许可的表，算出每一列对许可是看清还是被脱敏：

- 有列被脱敏时，Operator 以看板 owner 的身份在影子库 `pv_<uid>_<库>` 里建视图 `<表>`：`SELECT` 每一列，被脱敏的列套上脱敏表达式（`redact` 为 `NULLIF(c, c)`，`mask` 为 `MASK(CAST(c AS STRING))`）。看板查询里这张表的名字被改写成这个视图，表名不变，所以 `orders.id` 这样的列引用照常解析。查询里不能出现看板 catalog 的名字（`<catalog>.<库>.<表>` 会绕过视图；字符串里也不行，表函数以字符串接收表名），不能写带 `$` 的名字（Paimon 的系统表里是原值），也不能调用 Doris 的表函数（`paimon_meta`、`s3`、`local` 等），否则声明无效。识别名字时，反引号里的内容整体当作一个名字，其中的引号不开始字符串。
- 影子库只有 owner 能读写，用户没有任何权限；Doris 查视图时只检查外层对象的权限，所以用户读得到查询对象，读不到视图和湖表。
- 物化视图和内表也从视图读，所以副本里只有脱敏后的值。副本仍按所读的表分级（M3 的规则不变），因为 `mask` 保留了值的长度与格式（只替换字母与数字），结果仍可能关联到个人。
- 列的集合或脱敏方式变了（新增列、改标签），视图按新定义重建，读这张表的查询对象也随之重建：物化视图和内表因此不会留着刚被脱敏的值，等到下一次刷新。不再需要脱敏的表改回直接读湖，视图删掉，不再用到的影子库也删掉（`status.shadowDatabases`）。`status.exposure` 列出每张经视图读的表、视图名与被脱敏的列，事件 `ColumnsMasked`。Doris 建视图时展开列（[调研](../research/governance-enforcement.md)），所以 `SELECT *` 的查询对象在重建时才多出新列，那时它已被脱敏。
- 出错时不放宽：查询对象的定义变了（比如一列刚被脱敏）而 Doris 拒绝新定义时，旧对象删掉，直到能按声明建出来（事件 `QueryWithdrawn`）；脱敏视图或行策略建不出来时，撤销所有用户的授权；colvenna-catalog 暂时答不出表的列时，也撤销所有用户的授权（标签可能已经变了），对象保留，看板 `Degraded`，恢复后重新授权。只有刷新失败时旧副本保留并照常服务。

### 行过滤

```yaml
users:
  - {name: analyst, passwordSecret: {name: analyst}, queries: [live, by_state], rows: "state = 'created'"}
  - {name: viewer, passwordSecret: {name: viewer}, queries: [live]}
```

- 只要一个用户声明了 `rows`，每个用户在它可查的每个查询对象上都有一条 Doris 行策略（RESTRICTIVE）：声明了的按它的条件，没声明的为 `1 = 0`，即一行也看不到。身份没有映射，就没有数据。
- 条件只能比较查询的列与字面量（比较、`IN`、`LIKE`、`IS NULL`、`BETWEEN`、`AND`、`OR`、`NOT`）：不能有函数、子查询、注释或第二条语句，否则声明无效（`SpecInvalid`）。列名由 Doris 在建策略时核对。
- 策略先于授权：新用户在策略建好之后才拿到 `SELECT`。策略按用户、对象和条件命名，所以改条件是先建新策略、再删旧的，其间两条同时生效（更严，不会更宽）。每一轮都对照 `SHOW ROW POLICY`，丢了的策略在授权之前补上。`status.rowPolicies` 记录它们。
- 行策略对 root、admin 和物化视图的刷新不生效：物化视图刷新时读全部的行，用户查副本时才按策略过滤。
- 行过滤不当作保密边界：Doris 没有 security barrier（[调研](../research/governance-enforcement.md)），不保证策略的条件先于用户自己的谓词计算；若用户的谓词先作用到被隐藏的行上，`assert_true` 报错与否就能探出隐藏行的值。验收在外表视图、物化视图副本和 `CASE` 写法上都没有探到（策略的条件被下推到 Paimon 读取，或先算），但这是 Doris 4.1.4 的计划行为，不是承诺。要让一部分行对某些用户保密，就为他们建只装这些行的内表或物化视图，而不是共用一个对象加行策略。脱敏不受此影响：脱敏写在投影里，用户的谓词碰到的是脱敏后的值。
- `EXPLAIN` 显示展开后的计划：湖表的名字、行策略的条件、脱敏表达式、文件大小和估算行数（被隐藏的行也算在内），不显示数据值（验收实测）。估算行数会告诉用户被隐藏的行有多少，这是行过滤不当作保密边界的又一个原因。

### 支持矩阵

| 读路径或接口 | 列标签与脱敏 | 行过滤 | 状态 |
| --- | --- | --- | --- |
| Doris MySQL 协议，看板的查询对象（外表视图、物化视图、内表） | 执行（脱敏视图） | 执行（行策略） | 开放，#35 验收 |
| Doris 直接查 Paimon 外表、系统表、`paimon_meta`、time travel | — | — | 关闭：用户没有 catalog 的权限 |
| Doris 的影子库与视图定义（`SHOW CREATE VIEW`） | — | — | 关闭：用户没有权限 |
| Doris 文件类表函数：`s3()`（默认凭证链、实例角色、`s3.role_arn`）、`local()`、`http()`；`paimon_meta()` | — | — | 关闭：Doris 4.1.4 不检查调用者对文件类表函数的权限，所以靠外部：Doris 的 IRSA 角色读不到仓库、不能扮演别的角色，实例角色拿不到（实例元数据拒绝）；BE 的 `user_files_secure_path` 指向空目录，`local()` 读不到任何文件（默认能读 BE 目录下所有的表数据与日志）；`http()` 到实例元数据与 colvenna-catalog 都是 401；`paimon_meta()` 在 Doris 4.1.4 的 SQL 里没有注册（`Can not found function`）。看板的查询也不能调用表函数 |
| `SELECT ... INTO OUTFILE` | 执行 | 执行 | 写不进仓库（IAM）；导出的是用户本来就看得到的（已脱敏、已过滤的）行，写到哪里平台不管 |
| `EXPLAIN` | — | — | 只有计划（湖表名、策略条件、脱敏表达式、估算行数），没有数据值 |
| Doris FE 的 HTTP（`/api/query`）与 Arrow Flight SQL | — | — | 关闭：NetworkPolicy 只向客户端命名空间放行 MySQL 协议（9030） |
| 湖表的 `readers`（colvenna-catalog 表级 READ） | 不执行 | 不执行 | 表级：读者获准看整张表 |
| Iceberg 开放层（#34）与 Trino（#36） | 不执行 | 不执行 | 只发布 L2 及以下的表 |
| Flink、Spark 作业 | 不执行 | 不执行 | 不是终端用户的读路径 |
| 直接 S3 | — | — | 关闭（IAM） |
| Ranger、Paimon query-auth、Doris 原生脱敏 | — | — | 不启用（Doris 没有不依赖 Ranger 的脱敏；query-auth 未验证） |
| 第二朵云 | — | — | 未验证（#33 未开展） |

## 运维

**从 checkpoint 重新部署卡住的作业。** `UpgradeStuck` 表示作业的 HA 状态已经没有了，需要从它最近一个完成的 checkpoint 重新部署：

1. 在 `state.checkpoints.dir/<jobId>/` 下找编号最大、带 `_metadata` 的 `chk-N`；
2. 在 FlinkDeployment 上设置 `spec.job.initialSavepointPath` 指向它，并把 `spec.job.savepointRedeployNonce` 加一。

Operator 只按自己的 spec 哈希更新 FlinkDeployment，所以这次手动修改会一直保留，直到声明下一次变化。

2026-10-15 的 #24 首次运行中，并行度修改导致作业卡住，按这个步骤恢复了。之后又持续写入 2353 个事务，最终源端与目标逐行一致，完整 changelog 回放无误，见 [声明式 CDC 接入](../history/m3/declarative-cdc.md)。

## 已知限制

- 分层表不做 schema 演进，也不改流读的上游：这两种变化需要新声明一张表；历史回刷是单独的流程。
- 分层表只用 deduplicate 合并引擎；HLD 9 提到的 aggregation 与 partial-update 留待后续。
- 分层作业的写入端自己压缩（lookup changelog），不是 HLD 11.1 设想的 write-only，见上文。
- join 键不在目标主键里的普通 join 会被拒绝（`UnorderedChangelog`），见上文。
- 不能证明新作业没写过目标表的失败升级，不会自动回退：新作业运行过就改声明，否则重建这张表，见"失败回退"。升级开始后旧作业又触发 checkpoint 时，回退会被拒（安全的一侧）。
- 分层表只读本命名空间的 ODS 表与分层表，不能读视图和 catalog 函数（它的身份只有表级 grant）。
- 回刷只修正目标表，不修正作业状态。有状态的查询（如聚合）从 savepoint 恢复后，某个分组再变化时，会按状态里的旧值继续计算。要让这类表与回刷一致，需要新声明一张表，让作业从空状态开始；回刷适合无状态或只有 lookup join 的分层表。
- 回刷期间分层表的作业是挂起的，它的下游看不到新的变化，直到回刷结束、作业追上。
- 回刷按整表重算。分层表的目标不分区，所以没有分区回刷；append 表的分区覆盖（`INSERT OVERWRITE`）留待后续。
- 回刷的收敛等分层表，也等平台 Doris 里读到这些表的物化视图和内表刷新（见"看板服务"）；读端自己的 Doris 不在其中。
- 删除只覆盖 MySQL 与 PostgreSQL 源的 ODS 表和分层表；Kafka 事件源与 MongoDB 源的表列为 `Blocked`。物理完成最早在压缩一天之后（孤儿文件清理的年龄下限）。
- 平台托管的 Doris 的物化视图、内表和查询日志已列入删除（见"看板服务"）；读端自己的 Doris、导出仍按 `Reader` 外部副本列出。声明删掉而表留在一个不再有任何声明的库里时，删除找不到它。
- 删除只核对平台控制的读入口和存储，外部读端经由 `Reader` 副本告知。
- 心跳只有 MySQL 与 PostgreSQL 源；Kafka 事件源与 MongoDB 源只报积压（记录数、快照数），不报时间延迟，Kafka 承载的 CDC 也一样（它的延迟含用户的 Debezium 与 Kafka）。Doris 物化视图与内表的年龄由 LakeDashboard 报告；外表查询的 native reader 分片占比与内外表响应差（HLD 11.2 查询指标）由验收测得，没有常驻指标。
- 心跳按源测一条路径：一个源的表在同一个作业、同一个 checkpoint 里提交，心跳延迟代表它们共同的提交；单独一张表落后（快照中、被拒、对账不一致）只在它的状态、事件和积压里。
- HLD 11.2 的存储指标（S3 503 Slow Down 次数、每次 commit 的 list 次数）在作业的 S3 客户端里，M3 没有导出（需要 Flink 的指标上报器）；提交的耗时与冲突在 colvenna-catalog 的请求延迟与提交结果里。
- 流内检查只有非空、枚举、范围；批量校验只有非空率、唯一性、行数和新鲜度四种指标，没有自定义 SQL 规则和跨表规则（如外键）。门禁只拦平台自己的发布（`readers`、平台 Doris 的看板与 Iceberg 发布）：编排器的下游步骤按 `colvenna check` 的退出码自行决定，用户自己的读端不在其中。
- 恢复演练（从删除之前的状态恢复，主体不得重现）不由删除请求本身完成：Flink 状态以"不留删除之前的对象"为证据，演练留待运维验收。存储证据是安装时核对的，之后改了桶的版本或复制设置要重新安装才会核对。
- 二进制列、PostgreSQL 字符主键和未验证的类型不在支持范围内，见上文。
- Paimon PostgreSQL action 读 schema 的连接不校验服务端证书，见上文。
- MongoDB 源只接受 ObjectId 或字符串 `_id`，字段都是 STRING，不做按字段的类型映射与 JSONPath 选择；不支持分片集群上的验收（M3 只验证了副本集）。
- MongoDB 源的作业启动后出现的集合不加入它（`AddedAfterStart`），要另声明一个源；Flink CDC 的 newly-added-table 流程在 failover 后会静默丢掉新集合的变更（FLINK-40741）。MySQL 连接器 3.5 有同一个缺陷（本机复现 10 次丢 4 次），上游在 3.7 修复，所以 #113 起 MySQL 的 connector 是 3.7.0-1.20（10 次 0 次）。PostgreSQL 的新表是新作业，不走这个流程；分区表新增的叶子分区要重启作业，用的通用框架上游未修，本机 12 次未复现，记为剩余风险（[Flink CDC 3.7 评估](../history/m3/flink-cdc-3.7.md)）。
- Kafka 承载的 CDC 只支持 MySQL 源和 Debezium JSON（带 schema）。PostgreSQL、MongoDB、不能直连的源库（Oracle、SQL Server、Db2）以及 Canal 等格式不在 M3 范围内。
- Kafka 承载的 CDC 的 guard 只按整数或字符串主键识别已删除的行；其他主键类型在 overlap 内被删除的行，可能在删除被回放之前短暂重新出现。
- 血缘是 Operator 依据声明和 planner 校验发的，不是运行时观测：它说明作业被允许、被验证读写哪些表，不证明每条记录的去向；列级血缘、即席查询与删除不发，见"血缘"的覆盖矩阵。只验收了 Marquez；DataHub、OpenMetadata 接收同样的事件，未经验收。
- 数据库 CDC 源的作业（直连、Kafka 承载与 MongoDB）并行度在部署后固定（`ParallelismPinned`）；Kafka 事件源与分层表的作业按声明的并行度升级。
- 卡住的升级需要按上面的步骤人工恢复。
- 平台的两条 catalog 级 `MANAGE` grant（`admin` 与 Operator）只在安装时按规则续期，两次安装之间不续；它们的 catalog token 也只在运行安装脚本时续签（剩余有效期不足 `TOKEN_RENEW_DAYS`，默认 30 天时，见上文），两次安装之间不会自动轮换。
- 对账以 `reconcileMaxRows` 为上限，更大的表需要提高上限，或另外核对。
- 平台的 Doris 只有一个 FE、一个 BE（M3 的容量），没有高可用；FE 与 BE 之间、客户端与 FE 之间是集群内的明文 MySQL 协议。Doris 用户的口令与 catalog 的 token 都只在管理账号边界内可见，但 root 口令经集群网络传给 FE。
- 开发阶段不保证升级兼容：CRD 字段、作业参数的格式都可能变化，不做跨版本回退演练。

> 注：本文中的 issue 与 PR 编号（如 #24）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
