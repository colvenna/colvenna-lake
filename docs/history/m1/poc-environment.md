---
title: "M1 真实 S3 PoC 环境"
date: 2026-09-28
status: complete
issue: 6
---

# M1 真实 S3 PoC 环境

本环境服务 Issue #6 以及后续 M1 数据平面验收。资源只在 AWS profile `work` 对应的账号 `<ACCOUNT_ID>`、区域 `us-west-2` 创建；脚本在写入前检查实际 STS 身份。只使用合成数据。生产 Kafka 是外部依赖，本栈不创建生产 Kafka。

M1 已于 2026-10-12 结项，各项结论见 [README](../../../README.md) 与 [M1 基线报告](capacity-baseline.md)。本页于 2026-10-13 按结项清理后的实际环境更新（Issue #105）；各组件的部署入口见 [组件部署索引](#组件部署索引)。

## 资源与边界

| 资源 | 本阶段配置 | 作用和成本边界 |
| --- | --- | --- |
| EKS | Kubernetes 1.35，写入和查询各一个 managed node group，起始各 1 个节点，上限分别为 4 和 3 | 1.35 在 2026-09-28 仍属标准支持；空闲时也持续产生控制面与 EC2 费用 |
| EKS 启动入口 | 一枚不关联实例或网络接口的弹性公网 IP，仅供创建与更新期间作为 API 来源 `/32` | 独立 EIP 持续计费；CDK 删除时释放 |
| VPC | 两个 AZ、一个 NAT Gateway、S3 Gateway Endpoint，数据库在隔离子网 | NAT Gateway 持续计费；不得复用其他项目 VPC |
| S3 | 专用 warehouse 桶，独立 `flink-state/checkpoints/` 与 `flink-state/savepoints/` 前缀，SSE-S3、TLS、阻断公网访问、关闭版本控制、7 天清理未完成 multipart upload | 桶在 CDK destroy 时保留，需单独核对对象并清理；warehouse 和 state 分别启用 CloudWatch 请求指标 |
| RDS | PostgreSQL 16 `db.t4g.small`、20 GiB 起、100 GiB 自动扩容上限、私网、1 天备份 | CDK destroy 留最终快照；口令只存在 Secrets Manager 与运行时 Kubernetes Secret |
| IRSA | ODS、DWD、DWS、维护、查询独立 Kubernetes 服务账号；Flink ODS session 使用单独服务账号和 `cove-lake-ods` 名字空间 | 写入限定目标数据库前缀；查询账号无 S3 对象写权限；可创建 TaskManager Pod 的 Flink 身份不能调用其他层级服务账号 |
| EBS CSI | 固定附加组件版本 `v1.66.0-eksbuild.1` | 供后续有状态引擎 PVC 使用 |
| ECR | 项目私有 `cove-lake/m1-flink` 仓库，最多保留 5 个镜像 | 镜像绑定源码 SHA 和 digest；CDK 清理时自动删除本仓库镜像 |

上表是基础栈 `CoveLakeM1Poc` 的资源。M1 期间各 issue 又在同一集群和账号上追加了下列栈，状态为 2026-10-13 的核对结果：

| 栈 | Issue | 内容 | CDK 源码 | 2026-10-13 状态 |
| --- | --- | --- | --- | --- |
| `CoveLakeM1MysqlSource` | #8 | RDS MySQL 源库、ECR `cove-lake/m1-mysql-cdc` | `src/mysql-source-app.ts` | 存在 |
| `CoveLakeM1PostgresSource` | #9 | RDS PostgreSQL 源库、ECR `cove-lake/m1-postgres-cdc` | `src/postgres-source-app.ts` | 存在 |
| `CoveLakeM1KafkaIngest` | #10 | ECR `cove-lake/m1-kafka-ingest` | `src/kafka-ingest-app.ts` | 存在 |
| `CoveLakeM1Issue12Maintenance` | #12 | 维护作业角色 | `src/maintenance-role-app.ts` | 存在 |
| `CoveLakeM1Issue13Layered` | #13 | 分层作业角色 | `src/layered-role-app.ts` | 存在 |
| `CoveLakeM1Issue14Doris` | #14 | Doris 读取、seed、维护三个角色 | `src/doris-role-app.ts` | 存在 |
| `CoveLakeM1Issue14Images` | #14 | ECR `cove-lake/m1-doris` | `src/doris-image-app.ts` | 存在 |
| `CoveLakeM1Issue14Capacity` | #14 | 专用写入、查询节点组 | `src/doris-capacity-app.ts` | 2026-10-13 已删除 |
| `CoveLakeM1Issue15Backfill` | #15 | 回刷作业与 Doris 只读两个角色 | `src/backfill-role-app.ts` | 存在 |
| `CoveLakeM1Issue17Batch` | #17 | 批作业角色 | `src/batch-role-app.ts` | 存在 |
| `CoveLakeM1Issue16Capacity` | #16 | 负载节点组 `cove-lake-m1-issue16-load`（r6i.4xlarge） | 不在 main，见 [不在 main 上的步骤](#不在-main-上的步骤) | 存在 |
| `CoveLakeM1Issue16WriterCapacity` | #16 | 写入扩容节点组 `cove-lake-m1-issue16-writers`（r6i.4xlarge） | 不在 main | 存在 |
| `CoveLakeM1MixedDorisReader` | #16 | Doris 只读角色 `cove-lake-m1-issue16-mixed-doris` | 不在 main | 存在 |

表中源码路径相对 `infra/m1`，各栈的部署入口见 [组件部署索引](#组件部署索引)。集群内另有两套 Strimzi Kafka：#10 的 `cove-issue10`（`issue10/dev-kafka.yaml`）和 #16 的单 broker `cove-issue16`（4 TiB gp3，不在 main）。

节点组规模与源码不一致：基础写入、查询节点组在 `src/stack.ts` 与已部署模板中 `desiredSize` 都为 1，2026-10-13 实际为 2，是部署后在栈外调整的。`CoveLakeM1Issue16Capacity` 的已部署模板为 `minSize` 2、`desiredSize` 6、`maxSize` 6，而其部署 SHA 的源码为 1/1/1，即该栈以未提交的改动部署。

2026-09-29 核对 `work/us-west-2`：本项目 EKS 集群为
Kubernetes 1.35、`ACTIVE`，`upgradePolicy.supportType=EXTENDED`。
收到的 Amazon EKS 生命周期通知指出，1.35 标准支持至
2027-03-27，扩展支持至 2028-03-27；此后仍运行 1.35 的集群
将自动更新至 1.36。`EXTENDED` 是当前配置的版本策略，
不表示 2026-09-29 已进入扩展支持期。若此 PoC 在标准支持
结束后仍需保留，须在 2027-03-27 前核定升级窗口、兼容性和费用；
M1 本轮没有执行 EKS 版本升级。

CDK 位于 `infra/m1`（在早期私有仓库的 tag `history/m1-m5` 下）。EKS 公网 API 创建时先限制在未关联网络接口的项目 EIP `/32`；栈内 Kubernetes 清单将默认匿名开放的 `/livez`、`/readyz`、`/healthz` 从 `system:public-info-viewer` 绑定中移除。只有 CloudFormation 确认这项清单应用成功且 EIP 仍未关联后，`deploy.sh` 才将公网来源改为部署主机出口 IP `/32`。完成后核对实际 IAM、RBAC 与 CIDR 配置。出口 IP 变化时重跑部署脚本，并在重新开放入口前核对账号与集群身份。RDS PostgreSQL 入站只允许 EKS 节点安全组访问。

## 部署与验收

在 `infra/m1` 目录执行：

```bash
bash scripts/install-tools.sh
bash scripts/deploy.sh
bash scripts/setup-operators.sh
bash scripts/build-flink-image.sh
bash scripts/deploy-flink-session.sh
bash scripts/smoke.sh
bash scripts/security.sh
```

`install-tools.sh` 把固定版本的 `kubectl` 和 Helm 放在忽略的 `.tools/`，不会修改系统路径。`deploy.sh` 运行 TypeScript 检查、CDK 模板测试，并把 stack 输出和专用 kubeconfig 放在忽略的 `evidence/`。EKS 1.35 的两个节点组明确使用 AL2023；CDK 默认的 AL2 镜像不支持该版本。`setup-operators.sh` 使用 SHA-256 固定的 Apache Flink Kubernetes Operator `1.16.1` 和 Doris Operator `25.8.0` chart；安装前解析渲染后的清单，拒绝 LoadBalancer、NodePort、`externalIPs`、`hostPort`、`hostNetwork` 及未经复核的路由。Flink webhook 在 PoC 关闭，因此不需在这一关部署 cert-manager。Operator 仅提供托管底座，不等于 Flink 1.20.4 / Paimon 2.0.0 / Doris 4.1.4 的数据正确性已通过。

`smoke.sh` 先核对干净工作树的 HEAD 与 CDK stack 的 `SourceSha` 输出一致，再绑定 STS 身份、CloudFormation stack、EKS 节点/Pod、RDS 实例和 S3 对象 ETag，分别从 ODS IRSA Pod 写读真实 S3、从集群 Pod 经强制 TLS 对私网 PostgreSQL 创建临时表并读回。脚本分别核对 EKS API/audit/authenticator 日志、RDS PostgreSQL 日志导出、warehouse 与 Flink state 的 S3 请求指标配置，并保存 Kubernetes 事件，供后续分系统故障验证定位。原始数据库口令不写进日志或证据目录。证据路径为 `infra/m1/evidence/smoke-<UTC 时间>`。失败时保留 Job 描述和日志，不把超时算作连通通过。

`security.sh` 用已存在的 S3 对象验证匿名请求显式返回 403，核对账户级与桶级 Block Public Access 均全部开启，并验证未经认证的 EKS `/version`、`/livez`、`/readyz`、`/healthz`、Secret 列表及 Flink Pod port-forward 等路径返回 401/403、EKS 公网 CIDR 只包含部署主机 `/32`、RDS 没有公网地址且入站只允许 EKS 节点安全组、集群没有公开 Service/Ingress/Gateway/Route、项目 EC2 无公网 IP 且 VPC 无公网负载均衡器。项目 Pod 不允许使用主机网络；AWS GuardDuty 托管 DaemonSet 在私有节点上使用主机网络，脚本仅对这个精确身份例外。脚本还验证 Flink 运行身份仅在独立命名空间列出 Pod，不能创建 Service、跨命名空间列 Pod、读取 Secret 或 port-forward；原 ODS 身份仍无 Pod 管理权限，并用两个 IRSA 身份验证跨数据库前缀读取被 S3 明确拒绝。每个拒绝结果单独留证；连接失败或空结果不能冒充授权拒绝。当前 PoC 不提供公开页面。后续若增加任何公开页面或资源，开放前必须明确身份验证方式，并对实际入口完成匿名和越权拒绝测试；没有认证或测试未通过时不开放。

`build-flink-image.sh` 从固定 digest 的 Flink 1.20.4 官方镜像及 SHA-256 核对过的 Paimon 2.0.0、S3、Hadoop uber 和 PostgreSQL JDBC jar 构建 `linux/amd64` 镜像，推入项目私有 ECR，并将不可变镜像 digest 留在 `evidence/flink-image-ref.txt`。Paimon S3A 在 EKS IRSA 下的 catalog 配置须显式指定 `s3.aws.credentials.provider=software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider`；否则默认凭证链找不到 Web Identity token。CDC 源连接器与 Doris 读取的正确性分别在 #8、#9 与 #14 验证。

ECR 基础扫描已在上游 Flink 镜像的系统包中发现 Critical 漏洞；当前镜像仅供私有集群内的合成数据 PoC 使用，不能作为生产安全基线，也不得发布公网服务。

2026-10-13 核对：VPC CNI 的 policy agent（`aws-node` DaemonSet 中 `aws-eks-nodeagent` 的 `--enable-network-policy=true`）与控制器（`kube-system/amazon-vpc-cni` 的 `enable-network-policy-controller=true`）均已开启，NetworkPolicy 会被实际执行。集群中只有 Strimzi 为两套 Kafka 生成的策略及对应的 4 个 PolicyEndpoint，数据平面工作负载之间没有默认拒绝策略。#14、#15 的会话脚本要求开始前两个开关都为 `false` 且没有 PolicyEndpoint，会话内开启、结束时恢复为 `false`（见 [Doris 安全边界](doris-paimon.md#安全边界)）；#16 的故障运行用 NetworkPolicy 拦截 S3 与 catalog。两个开关当前为何保持开启，仓库文档中没有记录；按当前状态，#14、#15 的会话脚本会拒绝启动。接入敏感数据前仍须补齐默认拒绝策略并做跨组拒绝测试。

Paimon 标准 JDBC catalog 会探测共享 `warehouse/` 根目录，细粒度 IRSA 身份会收到 S3 403。#7 改用同一发布版的 `JdbcCatalogFactory` 与配置后的 `FileIO` 构造 catalog，跳过根目录预探测（见 [JDBC 多写者核对内容](jdbc-multiwriter.md#核对内容)）；共享 CDC 作业 `MySqlCdcJob.scopedCatalog` 与基线工具的 `BaselineCatalog.open` 沿用这条受限初始化路径。`security.sh` 的通过只表示所列公网入口与 IRSA 边界实测通过，不表示镜像漏洞或未来数据治理关口已完成。

`deploy-flink-session.sh` 将该 digest 部署为使用独立 ODS 前缀 IRSA 的 Flink 1.20 session 集群，REST Service 明确为 `ClusterIP`，JobManager 与 TaskManager 只落写入节点。脚本核对 FlinkDeployment 的实际观测代次、JobManager Deployment rollout 和运行中 Pod 的镜像 digest，避免将旧代次的 `READY` 误判为新部署通过。Flink JobManager 在 `cove-lake-ods` 中只获得命名空间级 Pod、ConfigMap 管理权限和 Service 读取权限，不能创建公网 Service，也不能调用 `cove-lake` 中的 DWD、DWS 等服务账号；两个 PoC 名字空间均启用 Pod Security `baseline`。FlinkDeployment 的公开 ingress、REST NodePort/LoadBalancer 配置会在提交前被拒绝。

M1 的 #7–#17 在本环境上以 Flink 1.20.4、Paimon 2.0.0、Flink CDC 3.5.0、Doris 4.1.4 分别完成了 JDBC 锁、CDC、append、维护、分层、Doris DV、回刷、批处理和 24 小时负载/故障验证，入口见 [组件部署索引](#组件部署索引)；本环境健康检查不能替代这些关口。M1 的 p95/p99、RPO/RTO 只按 [M0 验收契约](../../design/acceptance-contract.md#2-延迟正确性和故障目标) 的样本、故障和最终数据集合判断。

## 组件部署索引

下表按依赖顺序列出 M1 各组件。各组件的命令以链接文档为准，本页不重复；只有 24 小时基线没有成文的部署顺序，在下节列出。脚本路径相对 `infra/m1`，都要求干净、已提交的源码与 AWS profile `work`。

| 顺序 | 组件 | Issue | 部署步骤 | 前置条件 |
| --- | --- | --- | --- | --- |
| 1 | 基础环境：EKS、VPC、S3、RDS PostgreSQL（JDBC catalog）、Flink/Doris Operator、Flink 镜像与 session | #6 | 本页 [部署与验收](#部署与验收) | AWS `work` 身份 |
| 2 | JDBC catalog 多写者与锁 | #7 | [前提和运行](jdbc-multiwriter.md#前提和运行)：`issue7/run-multiwriter.sh` | 1 |
| 3 | MySQL 源库与共享整库 CDC 作业 `cove-mysql-cdc` | #8 | [可重放验证路径](mysql-cdc.md#可重放验证路径)：`issue8/deploy-source.sh`、`source-client.sh bootstrap`、`build-image.sh`、`deploy-cdc-job.sh` | 1 |
| 4 | PostgreSQL 源库与 CDC 作业 `cove-postgres-cdc` | #9 | [可重放路径](postgres-cdc.md#可重放路径)：`issue9/deploy-source.sh`、`build-image.sh`、`deploy-cdc-job.sh` | 1；部署前后核对 3 健康 |
| 5 | 开发 Kafka `cove-issue10`、Strimzi Operator 与 Kafka 接入作业 | #10 | [执行顺序](kafka-ingest.md#执行顺序)：自 `issue10/deploy-image-repo.sh`、`deploy-dev-kafka.sh` 起 | 1 |
| 6 | 主键表与 append 表语义 | #11 | [重放方法与隔离](table-semantics.md#重放方法与隔离)：`issue11/run-probe.sh`、`run-source-sequence.sh` | 3；5 的镜像仓库 `CoveLakeM1KafkaIngest` |
| 7 | 独立维护 | #12 | [安全与运行](maintenance-retention.md#安全与运行)：`issue12/deploy-role.sh`、`run-probe.sh` | 5 的镜像仓库 |
| 8 | DWD/DWS 分层加工 | #13 | [安全和清理](layered-processing.md#安全和清理)：`issue13/deploy-role.sh`、`run-probe.sh` | 5 的镜像仓库 |
| 9 | Doris 4.1.4 与自建 Paimon 2.0.0 读取器 | #14 | [安全边界](doris-paimon.md#安全边界)，读取器构建与会话见 [使用入口](paimon2-reader-candidate.md#使用入口)：`issue14/prepare-paimon2-reader.py`（需原版 Doris 的 BE 连接器、preload 与 UDF JAR）、`deploy-image-repo.sh`、`build-images.sh`、`run-persistent-session.sh --paimon2`；会话脚本自行执行 `deploy-role.sh` 与 `deploy-capacity.sh`，`CoveLakeM1Issue14Capacity` 已存在时拒绝启动 | 1；源码须包含 #13；两个 NetworkPolicy 开关为 `false` 且没有 PolicyEndpoint（当前不满足） |
| 10 | 千万行、7 天窗口查询数据集与看板 | #14 | [七天查询数据集](static-query-10m.md) 与 `issue14/query-load-dataset.md`（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/query-load-dataset.md`）；两篇只记录数据集与实测，**没有成文的部署命令** | 9 |
| 11 | 历史回刷与下游收敛 | #15 | [结项判定](backfill-convergence.md#结项判定)，参数见 [历史覆盖验收分支](backfill-convergence.md#历史覆盖验收分支)：`issue15/run-doris-convergence.sh`；脚本自行执行 `issue15/deploy-role.sh` 与 `issue14/deploy-capacity.sh`，退出时删除容量栈 | 5 的镜像仓库；9 的 Doris 镜像；NetworkPolicy 开关要求同 9 |
| 12 | 批处理边界 | #17 | [验证方法](batch-engine-boundary.md#验证方法)：`issue17/deploy-role.sh`、`run-probe.sh` | 5 的镜像仓库 |
| 13 | 24 小时基线、故障运行与写入扩容 | #16 | [故障与扩容的做法](capacity-baseline.md#故障与扩容的做法)，部署顺序见下节 | 3；5 的 Strimzi Operator、catalog 凭证与镜像仓库；9 的 Doris 镜像仓库；11 的 `CoveLakeM1Issue15Backfill` 角色；`query` 压测还需 #14 保留的 Doris 集群 `issue14-doris-20261002011424-165973`（已于 2026-10-13 删除）；以及 [不在 main 上的步骤](#不在-main-上的步骤) |

### 24 小时基线的部署顺序

基线工具在 `issue16-baseline`，每次运行用一个 `PLAN.json`（`run_id`、`evidence`；借用另一次运行的 Doris 时另写 `doris_run`），命令形如 `python3 baseline.py --plan PLAN.json <子命令>`。故障运行 `20261012044000_1011377` 按以下顺序部署，24 小时基线相同，只是用自己的 Doris、不执行 `doris-reader`：

1. `storage`、`clock`、`rbac`：本次运行的加密 gp3 StorageClass、各节点时钟测量 DaemonSet，以及 CDC 分层、compaction 与观测器的服务账号和 RBAC。
2. `artifacts --name jars`：把基线 jar 与层作业 jar 打成不可变 ConfigMap。
3. `tables`、`observer`、`layers`：在共享 CDC 看到新表之前建好 Paimon 表，再部署前缀观测器、Kafka 事件观测器、DWD/DWS 流作业与独立 compaction。
4. `kafka-topic`、`kafka-secret`、`ingest --restart-strategy exponential-delay`：本次运行的 topic、用户与凭证，以及 Kafka 接入作业。
5. `source --server-uuid <源库 server_uuid>`、`cdc-restart`：启动 Source 生产者，它建好 MySQL 源表后暂不写入、等待放行；再让共享 CDC 作业从 checkpoint 重启以接上新表。
6. `doris-reader`（仅借用 Doris 时）、`prober`、`mv`、`readiness`、`release`：Doris 探针与看板 MV 就绪、`readiness` 确认所有作业运行后，`release` 放行 Source 写入。
7. `producer --records <条数>`、`harvest`、`metrics`；查询压测用 `query`。24 小时运行期间如需避开每晚补丁重启，经批准后用 `patch-guard` 临时摘除节点 `Patch Group` 标签，结束后 `patch-restore`。

稳态之后用 `faults.py` 按 `suspend`、`kill-tm`、`block`、`conflict` 注入故障，写入扩容用 `scale-acl` 与 `scale`；最终由 `analyze.py` 出结果。共享 CDC 作业 `MySqlCdcJob` 继承 `MySqlSyncDatabaseAction`，同步整个 `source_db`；新表只能经 `cdc-restart` 接入。因此删除运行表时须先删 MySQL 源表、再重启共享 CDC、最后删 Paimon 表，否则重启后的整库同步会为仍在源库中的表重新建表。

### 不在 main 上的步骤

以下步骤或资源由未合入的分支或私有脚本完成，按本页无法从 main 复现：

- **#16 负载与写入容量、持久 Kafka**：三个栈的源码都不在 main，部署时的 `SourceSha` 分别为：
  - `CoveLakeM1Issue16Capacity`：`71c6be8`，在 `codex/issue-16-kafka-destination` 等未合入分支上；
  - `CoveLakeM1Issue16WriterCapacity`：`4193471`，在已关闭 PR #73 的保留分支 `codex/issue-16-load`（GitHub 上的 head 为 `07b6bc7`）上；
  - `CoveLakeM1MixedDorisReader`：`619e2ca`，未推送到 GitHub 上的任何分支。

  `cove-issue16` Kafka 的部署与销毁脚本 `issue16/deploy-persistent-kafka.sh`、`destroy-persistent-kafka.sh` 在 `codex/issue-16-load`。该分支的 `issue16/destroy-capacity.sh` 只处理 `CoveLakeM1Issue16Capacity`，要求部署 SHA 是当前 HEAD 的祖先，且 #16 的 Kafka 存储与作业已不存在；`71c6be8` 不是 `07b6bc7` 的祖先，`cove-issue16` 的 Kafka PVC 也仍在，因此按现状会被拒绝。另两个栈没有销毁脚本。
- **基线所用的 Doris**：集群 `issue15-doris-<运行 ID>`、运行专用只读 IAM 角色 `cove-lake-m1-issue16-reader-<运行 ID>`，以及承载它的 m6i.4xlarge 节点组 `cove-issue16-qual-20261011011523-3129925`（非 CDK，2026-10-13 已缩到 0），由私有准备脚本创建；该节点组带标签 `Purpose=owned-issue16-repair-qualification`、`RunId=20261011011523_3129925`，由正式基线之前的那次 #16 运行创建，基线沿用。ECR `cove-lake/m1-regional-source` 也不属于任何 CDK 栈，镜像构建见 基线镜像说明（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue16-baseline/images/README.md`）。
- **`baseline.py` 的外部路径**：kubectl 与 kubeconfig 取自 `COVE_M1_LOAD_DIR` 指向的运维检出；Kafka 生产者 jar 取自 `PACING` 指向的 `88612de` 检出；层作业 jar 取自 `BACKFILL_JAR` 指向的私有预构建产物；`query` 的 Doris 凭证取自 `QUERY_CREDENTIAL` 指向的私有证据文件。四者都是 `baseline.py` 中的常量。
- **编排脚本**：上节的部署顺序、故障与扩容排程由私有运维脚本驱动，未入库。

## 费用与清理

基础栈 `CoveLakeM1Poc` 仅创建 1 个 EKS 集群、1 个 NAT Gateway、1 个独立未关联 EIP、2 个起始工作节点、1 个 RDS 实例、1 个 warehouse 桶；不得把节点组上限误当作已运行节点数。每次基线测试后核对 node group 实际 `desiredSize`、RDS 状态、S3 用量及 `Project=cove-lake` 资源费用。

M1 结项清理（2026-10-13）后，集群共 11 个节点：基础写入、查询节点组各 2 个，`cove-lake-m1-issue16-load` 6 个 r6i.4xlarge，`cove-lake-m1-issue16-writers` 1 个 r6i.4xlarge；持续运行的工作负载是 `cove-mysql-cdc`、`cove-postgres-cdc`、`cove-flink-session` 的 JobManager、两套 Kafka，以及 Flink、Doris、Strimzi 三个 Operator；集群中没有 Doris 集群。费用主要来自负载节点组。

无活动验证时不应长期保留运行中的 EKS/RDS/NAT；计划结束时按以下顺序清理：

1. 归档 `evidence/` 中可公开的配置摘要和合成数据结果，确认没有口令或真实数据；记录 stack ID、源 SHA、Operator/镜像版本与测试时段。
2. 停止 `cove-lake-ods` 中的 CDC、接入作业与 Flink session、`cove-lake-kafka` 中的 Kafka 集群和 `cove-lake` 中的 Doris 工作负载，卸载 Flink、Doris 与 Strimzi Operator，确认没有仍在使用的 PVC。
3. 删除 [追加的栈](#资源与边界) 与非 CDK 资源。main 上只有 #14 容量栈的 `issue14/destroy-capacity.sh`：它会拒绝删除仍承载非 DaemonSet Pod 的节点，并要求栈输出的 `SourceSha` 是当前 HEAD 的祖先、容量源码无差异；部署 SHA 不在 main 历史上时，须在该 SHA 的检出上运行。#16 的栈见 [不在 main 上的步骤](#不在-main-上的步骤)；其余角色、ECR 与源库栈在 main 上没有销毁脚本。非 CDK 的节点组 `cove-issue16-qual-*` 与 ECR `cove-lake/m1-regional-source` 须单独删除。
4. 在 `infra/m1` 使用相同 AWS 身份与区域运行 `npx cdk destroy CoveLakeM1Poc -c adminPrincipalArn=<实际 work IAM ARN> -c operatorCidr=<出口 IPv4/32> -c sourceSha=<当前部署 SHA>`，等待 CloudFormation 完成，并核对 EKS、节点组、NAT、RDS 已消失。
5. CDK 设计上保留 warehouse 桶和 RDS 最终快照。分别盘点合成对象、S3 未完成分片、RDS 快照与计费后，按各自保留决定定点删除；不能把 `cdk destroy` 当作这些副本已清理的证据。

CDK bootstrap 栈属于账号共享设施，不在本项目清理范围。后续如有 24 小时负载运行，应在启动前记录窗口、节点数及预计费用，并在结束后立即恢复起始容量或清理环境。

首次失败栈的回滚中，GuardDuty 在项目 VPC 自动创建的 `guardduty-data` 接口端点及 `GuardDutyManagedSecurityGroup` 阻塞了子网和 VPC 删除。若再次出现，先确认该 VPC 已无 EKS、EC2、RDS 工作负载，再仅删除该 VPC 内带 `GuardDutyManaged=true` 标签的端点及安全组；不得关闭账户级 GuardDuty。此次失败栈的 OIDC 自定义资源删除也曾因空 ARN 报错，需按 EKS issuer 核对归属后清理残留 provider。

> 注：本文中的 issue 与 PR 编号（如 #6）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
