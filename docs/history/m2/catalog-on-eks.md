---
title: "M2 cove-catalog 在 EKS 上的部署与引擎验收"
date: 2026-10-13
status: complete
issue: 18
---

# M2 cove-catalog 在 EKS 上的部署与引擎验收

本页记录两项验收在真实 AWS 环境中的结果：

- Issue #18：Paimon REST catalog 元数据生命周期；
- Issue #20：按表数据凭证在真实 S3 上的表现。

服务端设计见 [cove-catalog LLD](https://github.com/colvenna/colvenna-catalog/blob/main/docs/design/colvenna-catalog-lld.md)；服务端单元测试与上游契约测试分别随 PR #107 和 #108 合并。所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群上（见 [M1 PoC 环境](../m1/poc-environment.md)），只使用合成数据。

**证据的来源版本**：

- 主要结果来自提交 `743ed5ce2b8428e2a9aaba3579fb0a2b9dfd3526`：
  - 部署 CDK 栈、构建 catalog 镜像（tag 为该 SHA，digest 为 `sha256:6e2d38c8…36a3`）、安装与访问检查；
  - Flink、Spark、Doris 三个流程；
  - 凭证与端点检查，以及续期第一轮。
- 续期第二轮来自提交 `6fbd6f393c0039421a2733371ef4c421990768be`，服务端镜像以该 SHA 重新构建并安装。两者之间的提交只改了续期测试和本页，服务端代码没有变：
  - Spark 与 Doris 的访问节奏；
  - 审计门槛；
  - 空闲模式下 Spark 流程的空指针；
  - Flink 会话集群关闭 `web.rescale.enable`。
- 来源核对方式：
  - 部署、构建与运行作业的脚本（`deploy-stack.sh`、`build-image.sh`、`install-catalog.sh` 和各流程脚本）拒绝在有未提交改动的树上运行；只读的检查脚本不部署任何东西；
  - 安装脚本拒绝未以当前 SHA 打 tag 的镜像；
  - 各作业清单都带 `cove-lake/source-sha` 注解。
- 之后的提交：只改了文档。

原始日志在私有证据目录 `m2-aws/final-743ed5c/`，续期第二轮在其中的 `renewal-active-6fbd6f3/`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| #18 Flink、Spark 和 Doris 的既定读写或只读流程不因缺少目录能力而中断 | **通过** | Flink 18 步、Spark 9 步、Doris 16 项检查全部通过；同一时段 catalog 日志中没有缺失的端点，也没有 501，见 [引擎流程](#引擎流程) |
| #18 对象创建、查找、变更、删除与错误返回在重试后保持一致 | **通过** | 服务端 `RetryConsistencyTest`（PR #107）在丢失响应的情况下逐个验证；本页 Flink 流程覆盖改名、删表与回滚 |
| #18 服务端与客户端对契约版本的兼容结果可重复验证 | **通过** | 上游 `RESTCatalogTest`/`CatalogTestBase` 在 CI 作业 `catalog-verify` 中针对真实服务端运行（106 例：91 通过、15 跳过并逐条写明理由、0 失败）；本页另有三种引擎的实测 |
| #20 有效、过期、撤销和越权身份分别得到预期结果；无静态长期数据密钥进入作业配置 | **通过** | [访问检查](#访问检查)：有效、缺失、伪造、过期、撤销、已停用身份，以及服务身份访问管理面；[按表凭证](#按表凭证)：越过本表的访问一律被拒；对作业清单、引擎命名空间内的对象和流程源码做扫描，均没有 S3 密钥 |
| #20 跨表及跨域直接读取 S3 被拒绝，并有可重复的负面测试 | **通过** | `verify-data-tokens.sh` 在真实 S3 上做 30 项检查，全部符合预期 |
| #20 Flink、Spark 和 Doris 的凭证获取与续期在目标版本组合上通过 | **通过** | 凭证有效期设为 90 分钟。三种引擎在首个凭证过期后都继续读写；持续使用凭证时，三者都在凭证过期前取得新凭证。见 [续期](#续期) |

按身份区分只读与读写属于授权，由 #21 交付。在此之前，任何已认证身份拿到的都是所请求表的读写凭证；跨表隔离不受影响。

## 版本组合

| 组件 | 版本与镜像 |
| --- | --- |
| cove-catalog | 本仓库 `cove-catalog/`，Java 21，Paimon 2.0.0；镜像 `cove-lake/cove-catalog`，按源码 SHA 打不可变 tag |
| Flink | 1.20.4，Paimon 2.0.0，M1 镜像 `cove-lake/m1-flink@sha256:e50e6576…22dc2`，由 Flink Kubernetes Operator 以会话集群运行 |
| Spark | 3.5.8（`apache/spark:3.5.8-scala2.12-java17-ubuntu@sha256:95770969…5adb`），加入 `paimon-spark-3.5_2.12-2.0.0` 与 `paimon-s3-2.0.0`，两者均校验过 SHA-256；镜像 `cove-lake/m2-spark` |
| Doris | 4.1.4，M1 自建的 Paimon 2.0.0 reader 镜像，FE `sha256:ae19e6d6…7a020`、BE `sha256:ecaf6f89…29cfa` |
| 元数据库 | M1 的 RDS PostgreSQL 16 实例上的独立数据库 `cove_catalog` 与角色 `cove_catalog`，`verify-full` TLS |

## 部署形态

`infra/m2` 的 CDK 栈 `CoveLakeM2Catalog` 只创建 IAM、ECR 和 Secrets Manager 资源，集群与存储桶沿用 M1：

- **ECR**：`cove-lake/cove-catalog` 与 `cove-lake/m2-spark` 两个私有仓库，tag 不可变，推送时扫描。
- **`cove-lake-m2-catalog`**：catalog Pod 的 IRSA 角色，只能读写仓库前缀 `warehouse/` 下的对象。
- **`cove-lake-m2-catalog-data`**：catalog 签发按表凭证时承担的数据角色。只信任 catalog 的服务账号，会话最长 12 小时，自身权限同样限于 `warehouse/`；每次签发再叠加只覆盖一张表的会话策略。
- **引擎角色**：
  - `cove-lake-m2-flink` 只能访问 Flink 状态前缀；
  - `cove-lake-m2-spark` 和 `cove-lake-m2-doris` 没有任何 S3 权限；
  - 三者都触及不到表数据，表文件一律通过下发的凭证访问。
- **`cove-lake/m2/catalog-db`**：元数据库角色的随机密码。

Helm chart `deploy/helm/cove-catalog`：

- **副本与暴露**：两个副本，带 PDB；只有 ClusterIP Service，8181 端口只接受 HTTPS。
- **证书**：由安装脚本生成的私有 CA 签发，CA 私钥只保存在集群 Secret 中。
- **网络**：NetworkPolicy 只放行带 `cove-lake.io/catalog-client=true` 标签的命名空间访问 8181。
- **健康检查**：健康端口只监听 `127.0.0.1`，探针在容器内执行 `curl -f`。
- **运行身份**：
  - 以 UID 1000 运行，根文件系统只读，丢弃全部 capability；
  - 不挂载 Kubernetes API token；IRSA 使用 EKS 注入的独立投射令牌。

| 命名空间 | Pod Security 级别 | 内容 |
| --- | --- | --- |
| `cove-catalog` | restricted | cove-catalog 及其一次性 psql Pod |
| `cove-m2` | restricted | Flink 会话集群、Spark 与客户端 Pod |
| `cove-m2-doris` | baseline | Doris FE/BE（Doris 4.1.4 镜像以 root 运行，restricted 会拒绝它；与 M1 的 Doris 相同） |

安装脚本依次完成以下工作：

- 创建数据库角色（psql 从环境变量读取口令）；
- 签发服务端证书；
- 安装 chart；
- 在 Pod 内执行 `bootstrap-admin`，读取管理员 token 后立即删除临时文件，再写入 Secret。

全过程中，口令与 token 不出现在日志或进程参数里；脚本失败时，含凭证的临时 Secret 也会被清理。

## 访问检查

`verify-catalog.sh` 从集群内的探测 Pod 发起请求，token 以 Secret 文件提供，curl 从文件读取请求头：

| 检查 | 期望 | 实测 |
| --- | --- | --- |
| 有效的管理员 token / 服务 token | 200 | 200 / 200 |
| 不带 token、伪造 token | 401 | 401 / 401 |
| 过期的 token（TTL 1 分钟，70 秒后） | 401 | 401 |
| 已撤销的 token | 401 | 401 |
| 已停用身份的 token | 401 | 401 |
| 服务身份访问管理面 | 403 | 403 |
| 不带 token 访问管理面 | 401 | 401 |
| 明文 HTTP 访问 TLS 端口 | 无 HTTP 响应 | 无 |
| 从其他 Pod 访问健康端口 | 不可达 | 不可达 |
| 在 catalog Pod 内访问 `127.0.0.1:8182` / Pod 地址的 8182 | 200 / 不可达 | 200 / 不可达（证明只绑定在 loopback） |
| 未加标签的命名空间，携带有效 token | 被 NetworkPolicy 阻断 | 阻断 |

## 引擎流程

三个流程依次在数据库 `m2_flow_final` 上运行：Flink 建表并写入，Spark 读取 Flink 的表并写入自己的表，Doris 只读两者的结果。

- **作业配置**：只有 catalog 地址和 bearer token 的 Secret 挂载，`data-token.enabled=true`。
- **数据访问**：引擎 Pod 自身的 AWS 身份读不到表数据，没有 IRSA 的 Pod 拿不到任何 AWS 凭证（见下文），因此每次表文件访问都只能使用 catalog 下发的按表凭证。

**Flink 1.20.4**（`FlinkCatalogFlow`，18 步）运行在 Flink Kubernetes Operator 管理的会话集群上：流程的 main 方法在客户端 Pod 中执行，表文件由 TaskManager Pod 写入，TaskManager 各自向 catalog 取用凭证；集群禁用了重启。覆盖的步骤：

- 建库，建主键表与 append 分区表（catalog 管理分区）；
- 批量写入、upsert/update/delete、加列；
- 带 checkpoint 的流式写入，以及带 consumer id 的流读分层；
- tag 创建与按 tag 读、按快照时间旅行；
- 从 tag 建分支并隔离写入；
- `compact`、`expire_snapshots`、`rollback_to`；
- 视图；
- catalog 函数：创建、在查询中调用、列举、删除；
- 改名与删表。

**Spark 3.5.8**（`SparkCatalogFlow`，9 步）：

- 读取 Flink 写的表；
- 按快照 id 与 tag 时间旅行，读取分支，读取 `$snapshots`/`$schemas`/`$tags`/`$files`；
- 新建带 deletion vectors 的主键表；
- `UPDATE`、`DELETE`、`MERGE INTO`，加列；
- 调用 `create_tag` 与 `compact` 存储过程；
- 再写入 Flink 建的表，两个引擎经同一提交权威写同一张表。

**Doris 4.1.4**（16 项只读检查）：

- 行数、演进后的列、Spark 写入的行；
- `FOR VERSION AS OF` 快照与 tag，以及 `@branch`；
- `$snapshots` 与 `$tags`；
- 分区与 DV 表的聚合；
- 强制使用 JNI reader 后的同样读取；
- `EXPLAIN VERBOSE` 显示 `paimonNativeReadSplits=20/20`：append 表的全部 split 都由 native reader 用下发的凭证读取。

M1 用 JDBC catalog 时，Doris 无法读取分支（见 [M1 Paimon 2 reader 评估](../m1/paimon2-reader-candidate.md)）；经 REST catalog 已可读取。

**缺失端点检查**（`check-catalog-logs.sh`）：服务端会记录两类调用：客户端调用了不存在的端点；服务端返回了 501。从三个流程开始起，catalog 日志中这两类都是 0 条。

## 按表凭证

`verify-data-tokens.sh` 先用运维身份确认各探测对象确实存在，否则拿到 403 无法区分“被拒绝”和“对象不存在”。随后以专用的探测身份取 `orders` 表的凭证，用它直接调用 S3：

| 访问 | 期望 | 实测 |
| --- | --- | --- |
| 本表：列举、读、写、删 | 允许 | 允许 |
| 本表：读取不存在的 key | 404（不是 403，S3A 的探测依赖这一点） | 404 |
| 同库兄弟表：列举、读、写 | 拒绝 | 拒绝 |
| 表前缀本身（不带 `/`，会列出 `orders_*`） | 拒绝 | 拒绝 |
| 库目录、整个桶的列举 | 拒绝 | 拒绝 |
| 另一个库的表：读、列举 | 拒绝 | 拒绝 |
| 仓库之外写入 | 拒绝 | 拒绝 |
| Flink、Spark、Doris Pod 以自身 IRSA 角色读、列举表数据 | 拒绝 | 拒绝，并核对了调用身份确为各自的角色 |
| 没有 IRSA 的 Pod（检查是否能回退到节点角色） | 拿不到凭证 | 拿不到任何 AWS 凭证，读、列举均失败 |
| 作业清单、引擎命名空间内的对象（ConfigMap、Job、Pod、DorisCluster）与流程源码中的 S3 密钥 | 无 | 无 |

下发凭证的键分三组：

- `fs.s3a.*`，凭证提供方为 `TemporaryAWSCredentialsProvider`；
- 三个别名 `s3.access-key`、`s3.secret-key`、`s3.aws.credentials.provider`；
- Doris 使用的 `s3.access-key-id`、`s3.secret-access-key`、`s3.session-token`、`s3.region`。

## 续期

服务端须以 `COVE_DATA_TOKEN_DURATION=PT1H30M`（允许的最短值）安装，`run-renewal.sh` 先核对这一点。测试分两轮，每轮调用一次 `run-renewal.sh`。

**第一轮：越过过期时间**。三个客户端同时运行，时长都超过首个凭证的有效期：

- **Flink**：会话集群上带 checkpoint 的流式写入，持续 100 分钟，禁止重启。首个凭证在第 90 分钟过期，之后仍须有提交。
- **Spark**：同一个会话先写读一次，等待 95 分钟后再写读一次；同时持有一个跨越等待期的 Paimon 表对象，用它在等待前后各读一次，因此证明的是使用中的凭证得到续期，而不是重新加载表时重新取用。
- **Doris**：只读检查在 95 分钟后于同一 catalog 上重复执行。

**第二轮：持续使用**。只有 Spark 和 Doris 参加，共 40 分钟：Spark 每 5 分钟读一次持有的表，Doris 每 5 分钟执行一次只读检查。Flink 的流式写入本来就在持续使用凭证，所以不参加。

**审计门槛**：空闲的客户端只会在凭证过期后的下一次使用时取新凭证，所以门槛只约束持续使用凭证的客户端：第一轮约束 Flink，第二轮约束 Spark 与 Doris。第一轮中的 Spark 与 Doris 改为要求过期之后的那次读写或查询通过。对每个被约束的引擎身份，必须存在一张表满足以下条件：

- 先有一次新签发；
- 至少 15 分钟后、在该凭证过期之前，又有一次新签发；
- 两次签发都未命中缓存。

**结果**：

| 引擎 | 第一轮 | 第二轮（`6fbd6f3`） | 过期前续期 |
| --- | --- | --- | --- |
| Flink | 通过：首个凭证过期后又有 15 次提交（共 11913 行），全程没有重启 | 不参加 | 约每 30 分钟一次新签发，例如 16:57:55 签发、17:27:56 再次签发 |
| Spark | 通过：95 分钟后会话与持有的表都能读写 | 通过：持有的表 9 次读取与最后一次写读都正确 | 19:42:50 签发，20:12:55 再次签发（第 30 分钟） |
| Doris | 通过：首轮与 95 分钟后的复查各 16 项检查全对 | 通过：7 次周期检查与最后的复查各 16 项全对 | 新签发相隔 10 到 20 分钟。例如 `events` 19:44:27 签发后，20:05:24 和 20:15:49 又有新签发，都在首个凭证过期之前 |

两种续期节奏的来源：

- **Spark 与 Flink**：用 Paimon 的 `RESTTokenFileIO`，凭证剩余不到 1 小时时，在下一次访问时刷新，即签发后第 30 分钟。
- **Doris**：每轮检查都向服务端请求凭证，审计中每轮都有签发记录，有的命中缓存，有的是新签发。服务端的每个副本各自缓存凭证，缓存的凭证剩余 70 分钟以内时才签发新的；两个副本轮流应答，所以新签发相隔约 10 到 20 分钟。

**测试设计的两处修正**，都在评审中发现：

1. **第一轮的门槛**：`743ed5c` 上的脚本让第一轮也约束空闲的 Spark 与 Doris，所以那次运行的退出码是 1。按修正后的标准：Flink 满足门槛；Spark 与 Doris 过期之后的读写与查询都通过，各自在过期后的下一次使用时取得了新凭证（Spark 18:32、Doris 18:33）。
2. **门槛的间隔**：门槛原先要求两次新签发至少相隔 25 分钟，依据是 Paimon 客户端的刷新点，没有考虑 Doris 这种按查询请求凭证、由服务端缓存决定节奏的客户端。在 `5279850` 上第一次跑第二轮时，Spark 满足门槛，Doris 的检查全对，但新签发只相隔约 20 分钟，未满足 25 分钟（原始数据在 `renewal-active/`）。现改为 15 分钟：仍远离启动时几秒内的连续签发，又低于两种续期点。修正后，在 `6fbd6f3` 上用已提交的脚本重跑了第二轮，结果见上表。

**未覆盖**：单个 Doris 查询的运行时间跨越凭证过期。凭证有效 90 分钟，服务端在剩余 70 分钟以内就换发新凭证，一个查询要运行 70 分钟以上才会遇到这种情况。

## 过程中修复的问题

首轮部署和评审暴露了以下问题，均已在本 PR 中修复：

- **TLS 密钥库口令**：口令文件末尾带换行，Java 报 `Password is not ASCII`；现在写入时去掉换行。
- **运行用户**：UID 10001 在基础镜像中没有 passwd 条目，S3 FileIO 的 Hadoop 登录报 `Invalid UID`；改用镜像自带的 `ubuntu` 用户（1000）。
- **运行期依赖**：Paimon 的 `FileIO.copyFile`（建分支时调用）需要 commons-io。引擎会自带这个库，服务端的测试也只是经测试依赖拿到它；现在服务端自带 commons-io 与 commons-lang3。
- **Doris 的 Pod Security 级别**：见 [部署形态](#部署形态)。
- **Flink 会话集群**：
  - JobManager 需要读取自己的 Deployment，才能创建 TaskManager；
  - Paimon 在同一会话集群的多个作业之间缓存 S3 FileIO，其 HTTP 线程沿用首个作业已关闭的类加载器去查找资源，因此关闭了 Flink 的类加载器泄漏检查（`classloader.check-leaked-classloader: false`）。
- **本地 Flink 运行**：本地集群原先按节点的 16 个 CPU 决定并行度，现在改为按 Pod 的 2 个 CPU；续期数据源改用随机 id，因为 sequence 生成器会把所有尚未发出的 id 存进 checkpoint，长时间运行会撑爆堆。
- **安全加固**（评审后）：
  - 口令和 token 不进进程参数；
  - 失败路径也会清理含凭证的 Secret；
  - 签发失败时不会用空 token 覆盖已有 Secret；
  - 端口转发改用空闲端口，并校验接管端口的确实是自己的进程；
  - 等待作业时，作业一失败就立即返回。
- **流程 SQL**：
  - 同时开启 DV 与 `write-only` 时，批读看不到 level-0 文件；
  - Flink 要求带类型的 `NULL`；
  - 有重载的 `create_branch` 不接受具名参数；
  - `expire_snapshots` 需要 `retain_min`。

## 已知限制

- **Doris 明文显示 bearer token**：Doris 4.1.4 不把 `paimon.rest.token` 列为敏感属性，`SHOW CREATE CATALOG` 会显示明文，FE 日志也可能记录 `CREATE CATALOG` 语句。验收中只有使用随机口令的 root 能访问 FE，集群用完即删；生产环境须限制该语句的权限和 FE 日志的访问。
- **副本分布**：本页的运行中，两个 catalog 副本可能被调度到同一节点。之后 #23 让副本分到不同节点，见 [控制平面的安装、检查与恢复](control-plane-operations.md#高可用)。
- **库目录标记**：删除库后，S3A 在删除表目录时补建的空目录标记 `warehouse/<db>.db/` 会留下来。它是零字节对象，不含数据。
- **CDC action 未在本页覆盖**：HLD 6.4 中的 Flink CDC action（`--catalog_conf metastore=rest`）随 #22 一起，在把 M1 的 CDC 作业切换到 REST 时验收。它用到的建表、改表、提交等目录能力，已由本页的 Flink 流程覆盖。

## 复现

在本仓库的某个提交上依次运行下列脚本。每个脚本都会核对 STS 身份确为 `<ACCOUNT_ID>`；部署与运行作业的脚本拒绝在有未提交改动的树上运行：

```bash
infra/m2/scripts/deploy-stack.sh
infra/m2/scripts/build-image.sh
infra/m2/flows/build-spark-image.sh
infra/m2/scripts/install-catalog.sh <evidence>/install
infra/m2/scripts/verify-catalog.sh <evidence>/verify
infra/m2/flows/run-flink-deployment.sh <run> <evidence>/flink \
  com.covelake.m2.flow.FlinkCatalogFlow m2_flow_<run>
infra/m2/flows/run-spark-flow.sh <run> m2_flow_<run> <evidence>/spark
infra/m2/flows/run-doris-flow.sh <run> m2_flow_<run> <evidence>/doris
infra/m2/scripts/verify-data-tokens.sh m2_flow_<run> <evidence> <evidence>/data-tokens
infra/m2/scripts/check-catalog-logs.sh <flows-start> <evidence>/endpoints
DATA_TOKEN_DURATION=PT1H30M infra/m2/scripts/install-catalog.sh <evidence>/install-renewal
infra/m2/flows/run-renewal.sh <run> m2_flow_<run> <evidence>/renewal
RENEWAL_ACTIVE=true infra/m2/flows/run-renewal.sh <run2> m2_flow_<run> <evidence>/renewal-active
```

`infra/m2/scripts/drop-database.sh` 通过 catalog 删除流程数据库，托管表的目录由服务端清除。

> 注：本文中的 issue 与 PR 编号（如 #18）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
