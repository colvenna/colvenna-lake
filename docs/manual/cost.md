---
title: "Colvenna Lake 成本估算"
date: 2026-10-22
status: draft
issue: 169
---

# 成本估算

本文估算一套 Colvenna Lake 环境每月的 AWS 费用，回答三个问题：大约花多少钱，钱花在哪里，随什么变化。安装方法见[安装部署手册](install.md)。

**口径。**

- 价格是按需（On-Demand）单价，取自 AWS Price List API（`aws pricing get-products`），取价日期为 2026-10-22。与 2026-10-21 取的单价逐项相同。
- 基准区域是 `us-west-2`，换区域的重算见第 8 节。
- 一个月按 730 小时计，金额单位为美元。
- 卷与镜像的大小以 GiB 计，直接乘以 AWS 价目里按 GB 标的单价。
- 资源的数量与配置按 `deploy/` 的代码读出，核对的是发布候选 `v1.0.0-rc.1`：tag 指向 `d5acb09e`，镜像构建自 `9df04e6f`（2026-10-22）。数量要看运行情况的项，按 `uat` 环境 2026-10-20 至 22 日的观测填写。表里分三种标注：
  - "实测"：读到的数字；
  - "推算"：由实测数字与单价算出；
  - "未测"：只给单价和来源。
- 不含税、支持计划、Savings Plans、预留实例、免费套餐与企业折扣。
- 不含运维人力：安装、升级、监控、排障与值守的人工都不计入。
- 只做估算，没有与实际账单（Cost Explorer）核对，这是 2026-10-21 的决定。

**对照发布候选。** 按 `v1.0.0-rc.1` 核对了三处：

- `deploy/aws/src` 从 `fc9407f8` 到 `9df04e6f` 的改动：账号、托管区与入口域名成为环境的设置（#212），Kubernetes 版本与两种节点类型移进 `foundation-spec.json`，并新增发布镜像仓库的栈 `ColvennaLakeRelease`（#201）。各环境资源的数量与规格都没有变，三个 RDS 的备份保留期仍是 1 天；
- 发布镜像仓库的实际仓库与镜像大小，见第 5.2 节；
- `uat` 以 `9df04e6f` 安装以后各仓库的镜像数，见第 2 节。

## 1. 总览

| 安装方式 | 每月固定费用 | 约合每天 | 节点缩到 0 以后，每月 |
| --- | --- | --- | --- |
| 只装平台（`deploy/install.sh`） | 约 $848 | 约 $28 | 约 $199 |
| 带 `--fixtures`（加两个测试源） | 约 $902 | 约 $30 | 约 $253 |

- 固定费用是资源存在就按小时或按月计的部分，见第 2、3 节。
- 节点缩到 0 以后仍在计费的项，以及卸载或直接删栈以后留下的费用，见第 5 节。
- 另有随数据量、作业、用户与查询变化的按量部分，见第 4 节。数据量与请求量小的时候，它每月只有几美元到几十美元；数据量和请求量大时，S3 是主要的增长项。
- 账号级的服务不计入上表，见第 6 节。工作账号开着 GuardDuty，它按每套环境的节点、数据库与请求另计费用，推算约每月 $50。
- 4 台节点占平台固定费用的约 72%（$611）。节点之外的大项是：
  - EKS 控制面（$73）与它的日志（$32）；
  - NAT 网关（$33）；
  - ALB 与公网地址（$31）。
- **一件事会让费用上涨**，详见第 7 节：EKS 1.36 的标准支持在 2027-08-02 结束，之后不升级，控制面每月多 $365。

## 2. 只装平台：逐项

| 项 | 数量与配置 | 单价 | 每月 | 代码 |
| --- | --- | --- | --- | --- |
| EKS 控制面 | 1 个集群，Kubernetes 1.36 | $0.10/小时 | $73.00 | `deploy/aws/src/foundation-stack.ts` |
| 写入节点 | t3.xlarge（4 vCPU、16 GiB）×2，按需 | $0.1664/小时 | $242.94 | 同上；台数为 `WRITE_NODES`，默认 2 |
| 查询节点 | r6i.xlarge（4 vCPU、32 GiB）×2，按需 | $0.252/小时 | $367.92 | 同上；台数为 `QUERY_NODES`，默认 2 |
| 节点根卷 | 写入节点每台 80 GiB、查询节点每台 100 GiB，共 360 GiB，gp3 | $0.08/GB-月 | $28.80 | 同上 |
| 平台的卷 | Doris FE 10 GiB、BE 20 GiB、Prometheus 10 GiB，共 40 GiB，gp3 | $0.08/GB-月 | $3.20 | `deploy/scripts/doris/install-doris.sh`、`deploy/helm/colvenna-monitoring/values.yaml` |
| NAT 网关 | 1 个，在第一个可用区 | $0.045/小时 | $32.85 | `deploy/aws/src/foundation-stack.ts` |
| 公网 IPv4 | 4 个：NAT 网关 1 个；引导地址 1 个，从不关联，闲置也计费；ALB 2 个 | $0.005/小时 | $14.60 | 同上；`deploy/aws/src/entry-stack.ts` |
| ALB | 1 个，面向公网 | $0.0225/小时 | $16.43 | `deploy/aws/src/entry-stack.ts` |
| 项目 RDS | db.t4g.small，PostgreSQL 16，单可用区 | $0.032/小时 | $23.36 | `deploy/aws/src/foundation-stack.ts` |
| 项目 RDS 的存储 | gp2 20 GiB（代码没有指定类型，CDK 默认 gp2），可自动扩到 100 GiB | $0.115/GB-月 | $2.30 | 同上 |
| Secrets Manager | 2 个：项目 RDS 的主账号、catalog 的数据库账号 | $0.40/个-月 | $0.80 | 同上；`deploy/aws/src/catalog-stack.ts` |
| S3 请求指标 | 仓库桶 2 个过滤器（`warehouse/`、`flink-state/`），实测 23 个指标 | $0.30/指标-月 | $6.90 | `deploy/aws/src/foundation-stack.ts` |
| EKS 控制面日志 | api、audit、authenticator 三类，实测约 90 MiB/小时，每月约 64 GiB | 写入 $0.50/GB | $32.00 | 同上 |
| ECR | 平台的 7 个仓库，实测约 32.5 GiB | $0.10/GB-月 | $3.25 | 各栈 |
| **合计** | | | **$848.35** | |

- **EKS 控制面日志。**
  - 写入量与负载关系不大：空闲的开发集群稳定在每小时 90 MiB，`uat` 在安装与 UAT 期间是每小时 76–117 MiB。
  - 日志组保留 365 天，与控制台审计日志、Cognito 事件日志一样，审计记录在三处保留同样久。按开发集群实测，压缩后的存储量约为写入量的 13%，即每月新增约 8 GiB，存储费每月随之增加约 $0.25；一年后稳定在约 96 GiB，每月约 $2.9。
- **ECR。**
  - 多数仓库按镜像数保留，上限是 5 到 20 个。`query-engine` 仓库（查询引擎，基于 Apache Doris 4.1.4；#258 以前叫 `doris`）只给构建产物（`build-` 前缀）保留 4 个，固定的 FE 与 BE 镜像永不过期，重新固定以后旧的也一直保留（tag 带 digest，#278）。
  - 从零安装以后，平台的 7 个仓库约 10.8 GiB，`test-readers` 约 0.7 GiB（2026-10-21 在 `us-east-2` 从零安装的 `uat2` 实测）。
  - 每升级一次就多一批镜像，直到上限。`uat` 在 11 个提交上安装或升级过；以 `9df04e6f` 安装以后（2026-10-22 取数），9 个仓库共约 40.2 GiB：
    - 平台的 7 个仓库约 32.5 GiB，上表按这个数计：`colvenna-jobs` 20 个，已到上限，14.5 GiB；`doris`（现在的 `query-engine`）4 个，9.7 GiB，是固定的 FE 与 BE 加一次 `build-images.sh` 的构建产物；`colvenna-operator` 10 个，3.2 GiB；`colvenna-catalog` 9 个，2.3 GiB；`flink-base` 6 个，2.1 GiB，另有 3 个镜像索引；`colvenna-bi` 与 `colvenna-console` 各 2 个，0.7 GiB 与 0.03 GiB；
    - `test-readers` 9 个，约 6.5 GiB；`test-spark` 2 个，约 1.2 GiB，见第 3 节；
    - 镜像索引的大小就是它指向的镜像的大小，不重复计入。
  - 各仓库都到上限时，按 `uat` 读到的平均镜像大小，平台的 7 个仓库约 38 GiB，每月约 $3.8。`query-engine` 仓库只在运行 `build-images.sh` 以后才多出构建产物。
  - 从 `v1.0.0-rc.2` 起（#234），平台另有第 8 个仓库 `third-party`，保存第三方镜像的副本（`deploy/images/third-party.tsv`）：每次安装都复制的 14 个约 5.4 GiB，每月约 $0.54；带 `--fixtures` 另 6 个约 6.6 GiB，见第 3 节。这是推算：2026-10-24 从上游读取各镜像索引里全部平台的压缩层之和（MongoDB 只取 linux/amd64，它的两个 Windows 镜像约 5.9 GB 不复制），不是 ECR 实测。仓库没有生命周期规则，重新固定的镜像也一直保留。上表与合计未计入。
- **免费的项。** VPC、子网、S3 网关端点、托管插件（VPC CNI、EBS CSI、kube-proxy、CoreDNS；EKS 的插件不另收费，只占节点的资源）、ACM 证书、SSM 参数，以及 KMS 的 AWS 托管密钥。
- **只在部署时运行的项。** CDK 为建 EKS 集群生成了 9 个 Lambda 函数与 1 个 Step Functions 状态机；基础栈与两个测试源库的栈各有 1 个设日志保留期的 Lambda 函数。它们只在建栈、改栈、删栈时运行，平时不产生费用。
- **入口的日志。** 控制台审计日志与 Cognito 登录事件日志各保留 365 天，ALB 访问日志桶里的对象也保留 365 天。

## 3. `--fixtures` 另加

| 项 | 数量与配置 | 单价 | 每月 | 代码 |
| --- | --- | --- | --- | --- |
| RDS 测试 MySQL | db.t4g.small，MySQL 8.4，单可用区 | $0.032/小时 | $23.36 | `deploy/aws/src/test-mysql-stack.ts` |
| RDS 测试 PostgreSQL | db.t4g.small，PostgreSQL 16，单可用区 | $0.032/小时 | $23.36 | `deploy/aws/src/test-postgres-stack.ts` |
| 测试源的存储 | 两个实例各 gp2 20 GiB | $0.115/GB-月 | $4.60 | 两个测试源栈 |
| Secrets Manager | 4 个：两个主账号、`colvenna_cdc`、`colvenna_pgcdc` | $0.40/个-月 | $1.60 | 同上 |
| CloudWatch 告警 | 1 个：PostgreSQL 复制槽积压 | $0.10/个-月 | $0.10 | `deploy/aws/src/test-postgres-stack.ts` |
| ECR | Spark 读端镜像，`uat` 实测 9 个、约 6.5 GiB；到 20 个的上限时约 14.3 GiB，每月约 $1.4。`test-readers` 仓库属于平台，镜像只在带 `--fixtures` 时推送 | $0.10/GB-月 | $0.65 | `tests/fixtures/readers/build-readers.sh` |
| ECR（`v1.0.0-rc.2` 起） | `third-party` 仓库里测试夹具的 6 个第三方镜像（Strimzi、Kafka、Airflow、Debezium Server、Trino、BusyBox），推算约 6.6 GiB（第 2 节的说明）；合计未计入 | $0.10/GB-月 | 约 $0.66 | `deploy/images/third-party.tsv` |
| **合计** | | | **$53.67** | |

- 带 `--fixtures` 的固定费用合计约 **$902.02**。
- 测试 MySQL 用 8.4，标准支持到 2029-07-31，不计 RDS Extended Support。8.0 的标准支持在 2026-07-31 结束，之后按 2 vCPU 计 Extended Support，每月约 $146；装在 8.0 上的环境，升级时就地升到 8.4（[安装部署手册](install.md)第 7 节）。
- Strimzi Operator 跑在已有的查询节点上，不另建 AWS 资源。

**验收测试带来的费用。** 下面几项不是安装建的，跑验收测试时才产生：

- **开发用 Kafka 的卷。** 5 GiB，每月 $0.40。UAT 结束后不删除，直到卸载。
- **catalog 流程用的 Spark 镜像。** `test-spark` 仓库属于平台，镜像只由 `tests/acceptance/catalog/build-spark-image.sh` 推送。`uat` 实测 2 个、约 1.2 GiB，每月 $0.12；到 5 个的上限时约 3.1 GiB，每月约 $0.31。
- **测试 Doris 的卷。** `tests/acceptance/catalog/run-doris-flow.sh` 运行时建一个 10 GiB 的 FE 卷，结束时删除；没删掉就报错退出。设了 `KEEP_DORIS=true` 时卷与集群一起保留，每个每月 $0.80，用完要手工删除。`uninstall.sh` 会删除集群里的全部卷声明。
- **测试用户。** 一次完整的 UAT 建 24 个 Cognito 用户，用后删除。登录过的用户都算当月的 MAU，按 $0.02/个计，当月 $0.48。

## 4. 按量计费的部分

| 项 | 单价 | 随什么变化 | `uat` 的观测 |
| --- | --- | --- | --- |
| S3 存储（仓库桶） | 标准存储 $0.023/GB-月 | 表数据、快照与 Flink 状态的总量。桶没有版本控制，生命周期规则只清理 7 天未完成的分段上传，数据一直留在标准存储 | 实测：一次完整的 UAT 之后 23,504 个对象、47 MiB，每月不到 $0.01 |
| S3 请求 | PUT、COPY、POST、LIST 每百万次 $5；GET、HEAD 等每百万次 $0.40 | 作业的检查点与提交频率、查询扫描的文件数、表的维护（压缩、过期） | 实测 UAT 的一天：`warehouse/` 约 62.5 万次 GET、40.7 万次 HEAD、5.6 万次 PUT、5 千次 DELETE，`flink-state/` 约 4.3 万次。推算约 $0.8 |
| NAT 网关处理的流量 | $0.045/GB | 节点访问公网与 AWS API 的流量：公共镜像、ECR API、STS、Secrets Manager、CloudWatch、EKS。S3（含 ECR 的镜像层）走网关端点，不经 NAT | 实测：安装加 UAT 的第一天 9.9 GiB。推算约 $0.45 |
| 跨可用区流量 | 每个方向 $0.01/GB | 两个可用区之间的 Pod 通信：Doris FE 与 BE、Flink JobManager 与 TaskManager、作业访问 colvenna-catalog 与单可用区的 RDS。只有一个 NAT 网关，第二个可用区的出网流量也要跨可用区 | 未测 |
| 公网出向流量 | 每月前 10 TB $0.09/GB，账号先有全球免费额度 | 控制台与 BI 返回给浏览器的数据 | 未测 |
| ALB 容量单位 | $0.008/LCU-小时 | 新建连接、活动连接、处理的字节与规则评估 | 实测：没有记录到 LCU |
| Cognito | Plus 计划 $0.02/MAU | 当月登录过的入口用户数。用户池是 Plus 计划，威胁防护只审计；MFA 只用 TOTP，不发短信 | 一次 UAT 24 个，见第 3 节 |
| T3 节点超额 CPU | $0.05/vCPU-小时 | 写入节点是 unlimited 模式。t3.xlarge 的基线是每个 vCPU 40%，长期高于基线就按超额计费。一台节点持续满载，每月约多 $88 | 未测 |
| RDS T4g 超额 CPU | $0.075/vCPU-小时 | RDS 的 T4g 实例默认 unlimited。db.t4g.small 的基线是每个 vCPU 20% | 未测 |
| 新增的卷 | gp3 $0.08/GB-月 | 开发用 Kafka、MongoDB，运行中的测试 Doris | 见第 3 节 |
| RDS 存储扩容与快照 | 存储 $0.115/GB-月；超出免费额度的备份 $0.095/GB-月 | 元数据增长时存储自动扩容。备份保留 1 天，在免费额度内。删栈时留下的最终快照要计费，直到 `uninstall.sh` 删除它 | 实测：3 个实例都还是 20 GiB |
| ECR 的增长 | $0.10/GB-月 | 每次升级多一批镜像，到各仓库的保留上限为止 | 见第 2 节 |
| 其他日志 | 写入 $0.50/GB，存储 $0.03/GB-月 | RDS 日志、控制台审计日志与 Cognito 事件日志都保留 365 天 | 未测 |
| 审计日志桶 | VPC 流日志投递到 S3 $0.25/GB；存储 $0.023/GB-月 | 流日志随节点的网络流量；仓库桶的访问日志每个请求一条，随 S3 请求量，投递不收费；都保留 365 天 | 实测（2026-10-21/22，安装期间与空闲时）：流日志 43 分钟 355 KB；空闲的 `uat` 6 小时 13 分钟 4.3 MB，约每月 0.5 GB，投递约 $0.13；访问日志一条约 0.6 KB。推算：UAT 那样一天约 110 万次请求，每天约 0.6 GB，存满一年约 $5/月 |
| Secrets Manager 调用 | 每万次 $0.05 | 安装与升级时读取 | 未测 |
| 安装时搬运镜像 | 跨区域每 GB $0.02，由流出的区域计；从 AWS 流向公网每 GB $0.09 | 镜像经执行安装的机器进出 ECR：`docker` 推送与 `crane` 复制，数据都流经执行机，同一区域内的那一段不收费，目标仓库已有的层不再传。从源码构建时，推送本次构建的镜像，再把查询引擎（基于 Apache Doris）的 FE 与 BE 从来源仓库复制过来，拉一次、推一次。按发布候选安装时（`install.sh --release`，[安装部署手册](install.md)第 3 节），`deploy/scripts/release/install-images.sh` 把每个镜像从清单所指的发布镜像仓库拉到执行机，再推进环境的 ECR；环境里同一标签已是这个 digest 的镜像跳过 | 推算，执行机在 `us-east-1`、来源与环境都在别的区域时：从源码构建推送约 5.3 GiB（带 `--fixtures` 再加 0.7 GiB），复制查询引擎 4.9 GiB，一次约 $0.30；按 `v1.0.0-rc.1` 安装，搬运 12 个产品镜像 10.12 GiB（带 `--fixtures` 再加读端镜像 0.72 GiB），两段都跨区域，一次约 $0.40–0.43。从 `v1.0.0-rc.2` 起 `third-party` 一步另搬运第三方镜像，推算约 5.4 GiB（带 `--fixtures` 约 12 GiB）：按候选安装时从发布仓库，从源码安装时从上游（流入 AWS 不收费，只有推进 ECR 那一段）或发布仓库 |
| 恢复 colvenna-catalog 的元数据 | RDS db.t4g.small $0.032/小时；手动快照超出免费额度时每 GB $0.095/月 | `restore-metadata.sh restore`（[安装部署手册](install.md)第 11.3 节）临时建一个与项目实例同规格的实例，用完删除，加 `--keep-instance` 时保留并照常计费；还留下一个 `before-restore` 手动快照，直到删除或卸载 | 实测（`plat`，2026-10-22）：一次约 12–14 分钟，临时实例不到 $0.01；快照按 20 GiB 计至多每月 $1.90 |

## 5. 节点缩到 0、卸载或直接删栈以后仍计费的费用

### 5.1 节点缩到 0

"节点缩到 0"指把两个节点组都缩到 0 台：作业随之停下，其余资源不动。只停作业而不缩节点，固定费用不变：节点照样按小时计费，省下的只是第 4 节的按量部分。

- 节点缩到 0 不是安装脚本提供的操作。栈里两个节点组的下限都是 1（`minSize: 1`），要用 EKS API 直接改（`aws eks update-nodegroup-config`），与栈里的配置不再一致；之后再运行 `foundation` 步骤会不会把台数改回去，没有验证过。
- 少于默认 2 台写入节点加 2 台查询节点的规模没有验证过，见第 7 节。
- 下表只回答：节点缩到 0 以后，还剩哪些费用。

| 项 | 只装平台，每月 | 说明 |
| --- | --- | --- |
| EKS 控制面 | $73.00 | 集群还在，就按小时计费 |
| EKS 控制面日志的写入 | 至多 $29.25 | 没有节点时的写入量未测。表里按 `uat` 有节点、没有作业时的写入量计：每小时 82 MiB，每月约 58.5 GiB，是上限 |
| 保留 365 天的日志组的存储 | 约 $0，随保留的日志增长 | EKS 控制面、控制台审计、Cognito 事件与 RDS 的日志，到期前一直计存储费，每 GB 每月 $0.03。2026-10-22 `uat` 上 EKS 的约 0.17 GiB，其余几个共约 0.4 MB；EKS 的一年后约每月 $2.9（第 2 节） |
| 平台的卷 | $3.20 | Doris FE、BE 与 Prometheus 共 40 GiB，是卷声明的卷，不随节点删除 |
| 节点根卷 | $0 | 随节点一起删除 |
| NAT 网关 | $32.85 | 按小时计费；没有流量时，处理费接近 0 |
| 公网 IPv4 | $14.60 | 4 个地址照样计费：NAT 网关 1 个、从不关联的引导地址 1 个、ALB 2 个 |
| ALB | $16.43 | 没有目标，也按小时计费 |
| 项目 RDS | $25.66 | 实例 $23.36，存储 $2.30。RDS 可以手工停止，停止期间只计存储与备份；但最多停 7 天，到期 AWS 自动启动它 |
| Secrets Manager | $0.80 | 2 个密钥 |
| S3 请求指标 | 约 $0 | CloudWatch 只对发布了数据的小时计费，没有请求就没有数据。`uat` 的作业停下以后（2026-10-21 21:50 起）就不再有数据点 |
| ECR | $3.25 | 镜像一直保存，见第 2 节 |
| Cognito | $0 | 按当月登录过的用户（MAU）计，没有人登录就不收费 |
| KMS | $0 | 只用 AWS 托管密钥：RDS 的存储加密、Secrets Manager 与 S3 的托管加密，不另收费 |
| **合计** | **约 $199.04** | 运行时 $848.35 的约 23%，约合每天 $6.5 |

- **带 `--fixtures`。** 另加 $53.67，合计约 $252.71：两个测试源库 $46.72、它们的存储 $4.60、4 个密钥 $1.60、复制槽告警 $0.10、`test-readers` 镜像 $0.65。跑过 UAT 的环境还有开发用 Kafka 的 5 GiB 卷（$0.40）与 `test-spark` 镜像（$0.12），见第 3 节。
- **随数据量计、不随计算停下的项**，单价见第 4 节：
  - 仓库桶里的表数据，每 TB 每月 $23.55；
  - 审计日志桶与入口访问日志桶里已有的日志，保留 365 天，到期前一直计存储费；
  - RDS 超出免费额度的备份。
- **账号级的 GuardDuty**（第 6 节），不计入上表：
  - EKS 运行时监控按节点的 vCPU 计，节点为 0 时不计；
  - EKS 审计日志的分析按控制面的 API 事件计，控制面在就一直计费。第 6 节推算有节点时每个集群约每月 $20，没有节点时会少一些（未测）；
  - RDS 登录事件按数据库的 vCPU 计，只装平台每月 $2，带 `--fixtures` 共 $6；
  - S3 数据事件随请求量，没有作业时接近 0。
- **换区域。** 各项换成目标区域的单价，方法同第 8 节；三个区域的合计见第 8 节的对照表。

### 5.2 卸载以后

`deploy/uninstall.sh` 删除这套环境的全部资源，数据也在内（[安装部署手册](install.md)第 8 节）：

- 仓库桶、审计日志桶与访问日志桶，连同其中的对象；
- RDS 的最终快照，以及这几个数据库实例的其他手工快照；
- 日志组、密钥、用户池与留下的 EBS 卷。

它最后核对账号里没有残留，所以卸载以后，这套环境本身不再产生费用。卸载不保留任何数据：要留下数据，先把它复制到这套环境之外，做法见[安装部署手册](install.md)第 11.5 节。留下的副本按 S3 标准存储每 GB 每月 $0.023、RDS 快照每 GB 每月 $0.095 计费，直到删除；把快照复制到另一个区域，另计跨区域流量每 GB $0.02。

卸载以后仍然计费的，只有不属于任何一套环境的部分：

| 项 | 每月 | 说明 |
| --- | --- | --- |
| 发布镜像仓库 `colvenna-lake-release/*`，在 `us-west-2` | `v1.0.0-rc.1`：$0.67–1.09 | 栈 `ColvennaLakeRelease`（#201），任何环境的卸载都不删除它；标签不可改，不设生命周期规则，每个候选的镜像一直保留。实测（2026-10-22）：9 个仓库，rc.1 的 15 个镜像，即 12 个产品镜像（colvenna-catalog、colvenna-operator、5 个 colvenna-jobs、colvenna-console、colvenna-bi、Flink 基础镜像、当时在 `doris` 仓库里的查询引擎 FE 与 BE）、Spark 读端镜像与 CLI 的镜像，另有 Flink 基础镜像的 1 个镜像索引。各镜像 `imageSizeInBytes` 之和 10.88 GiB，每月 $1.09；同一仓库里共用的层只算一次是 7.52 GiB，$0.75；整个注册表里只算一次是 6.72 GiB，$0.67。ECR 按哪一种计，没有核实（第 10 节）。从 `v1.0.0-rc.2` 起查询引擎的 FE 与 BE 放进新仓库 `colvenna-lake-release/query-engine`（#258），digest 不变；旧仓库 `colvenna-lake-release/doris` 离开了栈，但仍在账号里，保留着 rc.1 的这两个镜像。下一个候选的 FE 与 BE 若按整个注册表只算一次，至多多 10.88 − 4.86 = 6.02 GiB，每月约 $0.60；若按仓库计，新仓库里再存一份，再多 4.86 GiB，每月约 $0.49。与 rc.1 共用的层越多，多得越少。从 `v1.0.0-rc.2` 起另有仓库 `colvenna-lake-release/third-party`（栈里共 10 个仓库，加上留下的 `doris`，账号里 11 个），候选的第三方镜像推算约 12 GiB，每月约 $1.2；digest 不变的镜像下一个候选不再多存 |
| Route 53 托管区 | $0.50 | 全部环境共用，见第 6 节 |
| CDK bootstrap | 不到 $0.01 | 每个区域一套，见第 6 节 |

### 5.3 不经 `uninstall.sh` 直接删栈以后

在 CloudFormation 控制台或用 `cdk destroy` 直接删栈，会留下栈有意保留的资源。它们一直计费，直到手工删除：

| 留下的资源 | 每月 |
| --- | --- |
| 仓库桶、审计日志桶与入口访问日志桶（`RETAIN`） | 按其中的数据量，标准存储每 GB $0.023 |
| RDS 的最终快照（`SNAPSHOT`）：只装平台 1 个，带 `--fixtures` 3 个 | 超出免费额度时每 GB $0.095；按分配的 20 GiB 计，每个至多 $1.90 |
| 集群删掉而卷声明没删时留下的 EBS 卷：平台的 40 GiB；跑过 UAT 的环境还有开发用 Kafka 的 5 GiB | $3.20；Kafka 的卷 $0.40 |
| 保留 365 天的日志组：EKS 控制面、控制台审计、Cognito 事件与 RDS 的日志 | 按已存的量，每 GB $0.03 |
| CDK Lambda 函数的日志组：多数没有设保留期，每个几 KB 到几十 KB | 约 $0 |
| Cognito 用户池（`RETAIN`，开着删除保护） | 用户池本身不收费，只按 MAU 计 |

所以删除一套环境，要用 `uninstall.sh`。

## 6. 账号级的服务

下面的服务是账号或区域级的设置，不由 Colvenna Lake 创建，所以第 1 节的合计不含它们。但它们会按每套环境的规模计费。

**GuardDuty。** 工作账号在 `us-west-2`、`us-east-2` 与 `eu-central-1` 都有 GuardDuty 的 detector（2026-10-21 查看）。`us-west-2` 打开了 S3 数据事件、EKS 审计日志、RDS 登录事件与运行时监控，这些功能自动覆盖新建的每套环境。下面按 `us-west-2` 的功能与 2026-10-22 的单价（与 2026-10-21 相同）推算，一套环境大约是：

| 功能 | 单价 | 一套环境 |
| --- | --- | --- |
| EKS 运行时监控 | 前 500 个 vCPU，每个每月 $1.50 | 4 台节点共 16 个 vCPU，每月 $24。每多一台节点多 $6 |
| EKS 审计日志 | 前 1 亿条，每百万条 $1.60 | 按集群的 API 活动计。工作账号截至 2026-10-21 的 30 天共 $23.57（GuardDuty 的用量统计），期间主要是一套长期运行的开发集群。推算每个集群约每月 $20 |
| RDS 登录事件 | 每个 vCPU 每月 $1.00 | 项目 RDS 2 个 vCPU，每月 $2；带测试夹具再加 4 个 vCPU，每月 $4 |
| S3 数据事件 | 前 5 亿次，每百万次 $0.80 | 随仓库桶的请求量。按第 4 节 UAT 一天的请求量推算，跑测试的那天约 $0.9。GuardDuty 的用量统计里，`uat` 的仓库桶截至 2026-10-21 共 $0.37（它 2026-10-20 才建） |

GuardDuty 的基础数据源（CloudTrail 管理事件、VPC 流日志与 DNS 日志）按账号的事件量与流量计费，没有按环境拆分（未测）。工作账号截至 2026-10-21 的 30 天合计：CloudTrail 管理事件 $43.81、DNS 日志 $0.41、VPC 流日志不到 $0.01。新账号或新打开的功能有 30 天试用期。

**其他账号级的项。**

- **Route 53。** 放入口名的托管区（`COLVENNA_HOSTED_ZONE_ID`）每月 $0.50，由同一账号里的全部环境共用。标准查询每百万次 $0.40；指向 ALB 的别名记录不收查询费。
- **发布镜像仓库。** `us-west-2` 的 `colvenna-lake-release/*`（栈 `ColvennaLakeRelease`）保存各发布候选的镜像，由全部环境共用；`v1.0.0-rc.1` 每月 $0.67–1.09，见第 5.2 节。
- **CDK bootstrap。** 每个区域做一次。`CDKToolkit` 的资产桶每部署一个版本约多 60 MB，每月不到 $0.01。
- **CloudTrail。** 账号的跟踪只记录管理事件。每个区域的第一条跟踪记录管理事件不收费。
- **AWS Config。** `us-west-2` 没有打开 Config 记录器。

## 7. 变化因素与换算

| 变化 | 每月费用（`us-west-2`） |
| --- | --- |
| 每多一台写入节点（`WRITE_NODES`） | +$127.87：t3.xlarge $121.47，加 80 GiB 根卷 $6.40 |
| 每多一台查询节点（`QUERY_NODES`） | +$191.96：r6i.xlarge $183.96，加 100 GiB 根卷 $8.00 |
| 每 TB 仓库数据（标准存储） | +$23.55 |
| 每百万次 S3 写请求（PUT、COPY、POST、LIST） | +$5.00；GuardDuty 的 S3 保护另加 $0.80 |
| 每百万次 S3 读请求（GET、HEAD） | +$0.40；GuardDuty 另加 $0.80 |
| 每 GB 经 NAT 网关的流量 | +$0.045 |
| 每 GB 跨可用区流量 | +$0.02（两个方向各 $0.01） |
| 每个当月登录过的入口用户 | +$0.02 |
| 卷每多 1 GiB（Doris、Kafka 等，gp3） | +$0.08 |
| 控制面停在没有标准支持的版本上 | +$365.00：EKS Extended Support 加收 $0.50/小时 |

- **节点数。**
  - 平台本身的 CPU `requests` 约 5.1 个（2026-10-21 在 `uat` 上按 Pod 合计，2026-10-22 复核相同，不含测试夹具与作业）；默认 2 台写入节点加 2 台查询节点，`allocatable` 约 15.7 个 CPU。
  - 2+2 是作业与验收测试需要的规模，更少的节点没有验证过。
  - 节点组的上限是写入 4 台、查询 3 台，满配时节点与根卷约每月 $1,087。
- **控制面版本。** 集群的升级策略设为 `EXTENDED`：2027-08-02 标准支持结束后，AWS 不会强制升级，而是开始计 Extended Support，直到 2028-08-02。`foundation` 步骤每次运行都打印这个日期，不到 90 天时告警。升级见[安装部署手册](install.md)第 7 节。
- **作业与表。** 每张表、每个作业都不另建 AWS 资源，作业跑在已有的节点上，增长体现在 S3 与节点负载上。节点放不下时就要加节点。
- **受管命名空间。** 每个命名空间多一个 IAM 作业角色，不收费。

## 8. 换区域时怎样重算

资源的数量与配置不随区域变化：节点类型、卷的大小与个数都一样，只是单价不同。所以重算就是把第 2、3 节的每一项换成目标区域的单价。

**取单价。** Price List API 的端点固定在 `us-east-1`，用 `regionCode` 过滤目标区域。例如：

```bash
aws pricing get-products --region us-east-1 --service-code AmazonEC2 \
  --filters Type=TERM_MATCH,Field=regionCode,Value=eu-central-1 \
            Type=TERM_MATCH,Field=instanceType,Value=r6i.xlarge \
            Type=TERM_MATCH,Field=operatingSystem,Value=Linux \
            Type=TERM_MATCH,Field=tenancy,Value=Shared \
            Type=TERM_MATCH,Field=preInstalledSw,Value=NA \
            Type=TERM_MATCH,Field=capacitystatus,Value=Used \
            Type=TERM_MATCH,Field=licenseModel,Value="No License required" \
  --query 'PriceList[]' --output text \
  | jq -r '.terms.OnDemand[].priceDimensions[] | "\(.unit) \(.pricePerUnit.USD)"'
```

其余各项再加一个 `usagetype` 过滤，见下表。

- `usagetype` 带区域前缀：`us-west-2` 是 `USW2-`，`us-east-2` 是 `USE2-`，`eu-central-1` 是 `EUC1-`。
- `us-east-1` 不统一：有的服务不带前缀，有的带 `USE1-`。取不到结果时，换另一种写法再试。
- 分档计价的项会返回几档，取 `beginRange` 为 0 的那一档。

| 项 | `service-code` | 过滤条件（`usagetype` 不含区域前缀） |
| --- | --- | --- |
| EKS 控制面、Extended Support | `AmazonEKS` | `usagetype` 为 `AmazonEKS-Hours:perCluster`、`AmazonEKS-Hours:extendedSupport` |
| NAT 网关 | `AmazonEC2` | 每小时 `NatGateway-Hours`，每 GB `NatGateway-Bytes` |
| gp3 卷 | `AmazonEC2` | `EBS:VolumeUsage.gp3` |
| T3 超额 CPU | `AmazonEC2` | `CPUCredits:t3`，另加 `operatingSystem=Linux`，否则会混进 Windows 的价格 |
| 公网 IPv4 | `AmazonVPC` | `PublicIPv4:InUseAddress` |
| ALB | `AWSELB` | `LoadBalancerUsage`、`LCUUsage`，另加 `productFamily=Load Balancer-Application`，否则会混进 CLB、NLB 与 GWLB 的价格 |
| RDS 实例 | `AmazonRDS` | 不按 `usagetype`，按 `instanceType=db.t4g.small`、`databaseEngine`（`PostgreSQL` 或 `MySQL`）与 `deploymentOption=Single-AZ` |
| RDS 存储、备份、超额 CPU | `AmazonRDS` | `RDS:GP2-Storage`、`RDS:ChargedBackupUsage`、`CPUCredits:db.t4g`，另加 `databaseEngine` |
| S3 | `AmazonS3` | `TimedStorage-ByteHrs`、`Requests-Tier1`、`Requests-Tier2` |
| ECR | `AmazonECR` | `TimedStorage-ByteHrs` |
| CloudWatch | `AmazonCloudWatch` | `DataProcessing-Bytes`、`TimedStorage-ByteHrs`、`CW:MetricMonitorUsage`、`CW:AlarmMonitorUsage` |
| Secrets Manager | `AWSSecretsManager` | `AWSSecretsManager-Secrets` |
| Cognito | `AmazonCognito` | `CognitoPlusMAU` |
| 跨可用区流量 | `AWSDataTransfer` | `DataTransfer-Regional-Bytes`。这个服务没有 `regionCode`，改用 `fromRegionCode` 与 `toRegionCode`，都填目标区域 |

**三个区域的对照。** 2026-10-22 取价，按第 2、3 节的数量计算：

| 单价 | `us-west-2` | `us-east-2` | `eu-central-1` |
| --- | --- | --- | --- |
| t3.xlarge（每小时） | $0.1664 | $0.1664 | $0.192 |
| r6i.xlarge（每小时） | $0.252 | $0.252 | $0.304 |
| NAT 网关（每小时） | $0.045 | $0.045 | $0.052 |
| NAT 网关处理的流量（每 GB） | $0.045 | $0.045 | $0.052 |
| ALB（每小时） | $0.0225 | $0.0225 | $0.027 |
| gp3（每 GB-月） | $0.08 | $0.08 | $0.0952 |
| RDS db.t4g.small（每小时） | $0.032 | $0.032 | $0.037 |
| RDS gp2（每 GB-月） | $0.115 | $0.115 | $0.137 |
| 日志写入（每 GB） | $0.50 | $0.50 | $0.63 |
| S3 标准存储（每 GB-月） | $0.023 | $0.023 | $0.0245 |
| **只装平台，每月** | **$848.35** | **$848.35** | **$988.53** |
| **带 `--fixtures`，每月** | **$902.02** | **$902.02** | **$1,050.38** |
| **节点缩到 0 以后，只装平台** | **$199.04** | **$199.04** | **$219.74** |
| **节点缩到 0 以后，带 `--fixtures`** | **$252.71** | **$252.71** | **$281.59** |

- 以下各项在这三个区域单价相同：EKS 控制面、公网 IPv4、Secrets Manager、ECR、CloudWatch 指标与告警、ALB 容量单位、跨可用区流量。
- `us-east-2` 的各项单价与 `us-west-2` 全部相同。
- `eu-central-1` 的固定费用约高 17%，主要来自节点。

换区域安装还有两个前提：目标区域要有两个可用区同时提供 t3.xlarge 与 r6i.xlarge，并做过 CDK bootstrap。见[安装部署手册](install.md#区域)第 1 节"区域"，以及第 2 节的"账号与身份"与"区域的条件"。

## 9. 省钱的做法

下面只是建议，本文不改代码与默认值。

- **不跑验收测试时不装 `--fixtures`。**
  - 测试夹具每月约 $53，平台本身用不到。
  - 现在没有只拆测试夹具的脚本，已经装了的只能随整套环境一起卸载。
- **不用的环境整套卸载，而不只是把节点缩到 0。**
  - 节点缩到 0 以后，固定费用仍剩约四分之一，见第 5.1 节。
  - `deploy/uninstall.sh` 删除这套环境的全部资源，数据也在内，并核对没有残留。2026-10-21 拆除 `uat2` 实测约 49 分钟，没有残留。
  - 从零重装一套，实测约 77–78 分钟（`uat` 与 `uat2`）。两次的实测见[安装部署手册](install.md)第 10 节。
- **2027-08-02 前升级 EKS。** 避开控制面的 Extended Support（第 7 节）。
- **长期运行的环境买 Savings Plans 或预留实例。** 节点约占平台固定费用的 72%。折扣率取决于承诺期与付款方式。
- **减少 EKS 控制面日志类型。** 日志组保留 365 天，存储有上限；每月写入的 $32 只能靠减少日志类型来降（现在是 api、audit、authenticator 三类），代价是少了相应的审计记录。

## 10. 未计入与不确定的项

- **跨可用区流量与 NAT 的稳态流量没有实测。** 第 4 节只给了单价和来源。
- **ECR 的镜像大小。** 用的是各镜像 `imageSizeInBytes` 之和。镜像之间共用的层是否只计一次，没有核实；发布镜像仓库里实测，同一仓库或整个注册表里共用的层只算一次时，少约 31% 或 38%（第 5.2 节）。
- **GuardDuty 的 EKS 审计日志费用。** 由账号近 30 天的合计推算，没有按集群拆分。
- **公网出向流量、Cognito 的 MAU 与超额 CPU** 取决于使用方式，没有估入固定费用。
- **没有节点时 EKS 控制面日志的写入量没有实测。** 第 5.1 节按有节点、没有作业时的写入量计，是上限。
- **VPC 流日志的投递费。** 按 S3 里压缩后的对象大小推算。若按压缩前的量计费，会高几倍，没有核实。

> 注：本文中的 issue 与 PR 编号（如 #212）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
