---
title: "Colvenna Lake 安装部署手册"
date: 2026-10-20
status: draft
issue: 162
---

# 安装部署手册

本手册说明在 AWS 账号里怎样从零装起一套完整的 Colvenna Lake 环境，以及怎样验证、升级、卸载，怎样备份与恢复（第 11 节）。产品名 2026-10-24 由 Cove Lake 改为 Colvenna Lake，资源名与组件名 2026-10-25 随之改名（[商标与命名](../../release/trademarks.md#7-产品名从-cove-lake-到-colvenna-lake)）；旧名字的环境不能升级过来，先在它安装时的提交上卸载，再从零安装。

主线是两个脚本：

- `deploy/install.sh`：按顺序装完整套环境；
- `deploy/uninstall.sh`：拆除一套环境，并核对账号里没有残留。

第 4 节逐步说明 `install.sh` 调用的每个安装步骤，出错时可以只重跑那一步。2026-10-20 至 21 日，按本手册在工作账号里从零建了两套环境：`us-west-2` 的 `uat` 与 `us-east-2` 的 `uat2`；2026-10-22 又在 `us-east-2` 以只装平台的方式建了 `plat`，并在上面演练了元数据恢复。实测结果见第 10 节。

## 1. 装的是什么

一套环境由 5 个平台栈组成；加 `--fixtures` 时，再加 2 个测试源栈。集群里另有若干 Helm release。栈名中的 `<Env>` 是环境名首字母大写。

| 组件 | 栈 | 集群里 | 说明 |
| --- | --- | --- | --- |
| 基础 | `ColvennaLake<Env>Foundation` | Flink Kubernetes Operator 1.16.1、Doris Operator 25.8.0 | VPC（2 个可用区、1 个 NAT 网关）；EKS 1.36，写入节点 t3.xlarge、查询节点 r6i.xlarge，各 2 台；控制面的 api、audit、authenticator 日志，与各 RDS 导出的日志一样保留 365 天；VPC CNI、EBS CSI、kube-proxy、CoreDNS 托管插件，执行 NetworkPolicy；项目 RDS PostgreSQL 16（colvenna-catalog、Polaris、Marquez、Superset 的元数据库）；仓库桶，以及审计日志桶（仓库桶的 S3 访问日志与 VPC 流日志，保留 365 天）；Flink 基础镜像、查询引擎镜像（基于 Apache Doris 4.1.4，仓库 `query-engine`）与第三方镜像的 ECR 仓库 |
| 控制平面 | `ColvennaLake<Env>Catalog` | colvenna-catalog：独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog) 的发布版，现在固定 v1.0.0-rc.1（[`deploy/images/colvenna-catalog.json`](../../deploy/images/colvenna-catalog.json)） | catalog 的镜像仓库（放发布版镜像的副本）、IRSA 角色与数据库密钥；服务端自建私有 CA 与 TLS |
| 声明式接入 | `ColvennaLake<Env>Operator` | colvenna-operator、平台 Doris（1 FE、1 BE）、Prometheus 与 Grafana | Operator 与作业的镜像仓库；每个受管命名空间一个作业角色 |
| 开放层 | `ColvennaLake<Env>OpenLayer` | Polaris 1.8.0、Marquez 0.51.1 | Polaris 与 Iceberg 读端的角色 |
| Web 入口 | `ColvennaLake<Env>Entry` | AWS Load Balancer Controller 3.6.0、colvenna-console、BI（Superset 6.1.0） | Cognito 用户池（强制 TOTP MFA）、ACM 证书、公网 ALB、`console.` 与 `bi.` 两个域名 |
| 测试夹具（`--fixtures`） | `ColvennaLake<Env>TestMysql`、`ColvennaLake<Env>TestPostgres` | Strimzi 1.2.0 | 代替用户数据库的 RDS MySQL 8.4 与 PostgreSQL 16、开发用 Kafka 所需的 Operator、Spark 读端镜像；只供验收测试使用，平台本身不需要 |

平台不安装生产用的 Kafka、MongoDB 或源数据库，这些都是用户已有的系统。开发用的 Kafka 与 MongoDB 是可选项，见第 5 节。

**费用。** 一套环境每月的费用见[成本估算](cost.md)：分只装平台与带 `--fixtures` 两种，逐项列出固定费用，并给出按量计费的项，节点缩到 0、卸载或直接删栈以后仍计费的项，变化因素，以及换区域时的重算方法。

### 环境名

每套环境都有名字，由 `deploy/env.sh` 的 `COLVENNA_ENV` 指定，脚本不设默认值。

- 名字以小写字母开头，只含小写字母与数字，最长 8 个字符。
- 同一账号里可以并存多套环境，在同一区域或不同区域都可以，各自的名字互不相干。
- 名字在整个账号里唯一：IAM 角色与入口的 DNS 名是全局的，两个区域不能各有一套同名的环境。新环境的名字已被别的区域的环境占用时（已有以 `colvenna-lake-<名字>-` 或本环境的栈名开头的 IAM 角色），`install.sh` 在改动任何东西之前停下。
- `m`、`m0`–`m9` 与 `poc` 是保留名：开发阶段的 PoC 环境使用 `ColvennaLakeM1Poc`、`colvenna-lake-m2-catalog` 这类名字，它由 tag `history/m1-m5` 下的脚本管理，见[开发记录](../history/README.md)。
- `release` 也是保留名：发布仓库是 `colvenna-lake-release/…`，见[发布候选](../../release/README.md)。

以 `COLVENNA_ENV=uat` 为例：

| 什么 | 名字 |
| --- | --- |
| 栈 | `ColvennaLakeUatFoundation`、`ColvennaLakeUatCatalog` 等 |
| ECR 仓库 | `colvenna-lake-uat/colvenna-jobs`、`colvenna-lake-uat/query-engine` 等 |
| IAM 角色 | `colvenna-lake-uat-catalog`、`colvenna-lake-uat-jobs-colvenna-data` 等 |
| 密钥、日志组 | `colvenna-lake-uat/catalog-db`、`/colvenna-lake-uat/console-audit` |
| Cognito 用户池与登录域名前缀 | `colvenna-lake-uat`、`colvenna-lake-uat-<账号>` |
| 入口域名（默认） | `console.colvenna-uat.<托管区>`、`bi.colvenna-uat.<托管区>` |
| 本地状态（kubeconfig、镜像记录、证据） | `deploy/.state/uat/` |

CDK 应用以 `-c env=<名字> -c account=<账号> -c component=<组件>` 接收环境名、账号和组件，每次合成一个栈。

集群内的名字不随环境变化，因为每套环境都有自己的 EKS 集群：

- 受管命名空间默认是 `colvenna-data`；
- 平台 Doris 在 `colvenna-doris`，colvenna-catalog 在 `colvenna-catalog`，Operator 在 `colvenna-system`。

### 区域

每套环境装在一个标准 AWS 区域（分区 `aws`），由 `COLVENNA_REGION` 指定，例如 `us-west-2`、`us-east-2`、`eu-central-1`。中国区（`cn-…`）与 GovCloud（`us-gov-…`）是另外的分区，不支持。

- 新环境第一次运行 `install.sh` 时给出 `COLVENNA_REGION`。`install.sh` 核对过环境名以后，把它记进本地状态 `deploy/.state/<环境>/region`；之后的安装、升级、卸载与验收测试都从这里读，不必再给，再给一个不同的区域会被拒绝。名字核对不通过、或读不到那个区域的基础栈时（例如账号没有开通那个区域），什么都不记。基础栈只能经 `install.sh` 部署，单独运行 `deploy/scripts/foundation/deploy.sh` 要求区域已经记下。
- 各脚本据此导出 `AWS_REGION`；CDK 应用、ECR 地址、colvenna-catalog、控制台与 BI 的 `region`、Polaris 的存储配置都随它。
- 一套环境只在一个区域里，没有跨区域的复制、容灾或读写。
- 本地状态里没有 `region` 文件的环境（2026-10-21 之前装的，或换了一个工作区），给出 `COLVENNA_REGION` 重跑一次 `install.sh`（或 `install.sh --only foundation`）就会记下；在此之前，每条命令都要带上 `COLVENNA_REGION`。
- `uninstall.sh` 核对没有残留之后，删掉记下的全部设置（区域与下一节的账号、托管区、入口域名），这个名字就可以在任何区域再用。

### 账号、托管区与入口域名

安装器不绑定任何账号与域名。装进哪个账号、入口的名字放进哪个托管区，都由环境变量给出，与区域一样，没有默认值（入口域名除外）：

| 变量 | 必填 | 含义 |
| --- | --- | --- |
| `COLVENNA_ACCOUNT` | 是 | 要装进的 AWS 账号，12 位数字。当前凭证必须属于这个账号，否则在改动任何东西之前拒绝 |
| `COLVENNA_HOSTED_ZONE_ID` | 是 | 该账号里一个公有 Route 53 托管区的 ID（`Z…`）。入口的名字建在这个区里，入口的证书也用这个区里的记录校验 |
| `COLVENNA_HOSTED_ZONE_NAME` | 是 | 这个托管区的名字，例如 `example.com`。`install.sh` 核对托管区存在、是公有区、名字一致 |
| `COLVENNA_ENTRY_DOMAIN` | 否 | 入口域名，应用在 `<应用>.<入口域名>`。默认 `colvenna-<环境>.<托管区名>`；必须是小写，在托管区之下，不能就是托管区本身 |

- 这四项与区域一起，由 `install.sh` 在核对通过、环境名也核对过以后，记进本地状态 `deploy/.state/<环境>/`：`account`、`hosted-zone-id`、`hosted-zone-name`、`entry-domain`，每项一个文件。之后从这里读，不必再给；再给一个不同的值会被拒绝。
- 本地状态里还没有这几项的环境（#212 之前装的），也要在下一次运行 `install.sh` 时给出一次，之后就记下了。给的入口域名必须是它的入口栈正在用的那个，否则拒绝，并说出那个域名。
- 这样的环境直接卸载时也一样（#227）：`uninstall.sh` 在删除任何东西、记下任何设置之前，核对托管区是这个账号的公有区、名字一致，并把入口域名与入口栈正在用的比对，不符就停下。读入口栈出错（限流、无权限）时也停下，不当作"没有入口栈"。入口栈已经不在时（入口没装成，或之前的卸载已经删掉它）无从比对，按给出的入口域名（默认 `colvenna-<环境>.<托管区名>`）删除记录。已经记下的托管区不再核对：安装时核对过，之后若被删掉，也不该挡住拆除其余的资源。
- 卸载时删除入口域名本身及其下的全部 DNS 记录，所以一个入口域名只给一套环境用。新环境安装前，`install.sh` 核对它是空的：托管区里没有在它之上或之下的记录；它不在另一套环境的入口（`console.` 或 `bi.` 记录）之下；也不与本工作区记下的别的环境的入口域名相同、相互包含。
- 只在一个账号里实测过（维护者的工作账号，第 10 节）；在第二个账号里安装还没有验证。

## 2. 前置条件

**账号与身份。**

- 脚本装进 `COLVENNA_ACCOUNT` 指定的账号；区域、账号与托管区见第 1 节。
- 凭证用调用方自己的：`AWS_PROFILE` 指定的 profile，或默认的凭证链。脚本不固定任何 profile；运行安装、卸载或验收命令之前，先导出目标账号的 profile（`export AWS_PROFILE=<目标账号的 profile>`）。每个脚本的第一项检查是当前凭证属于 `COLVENNA_ACCOUNT`，不属于就在调用其他 AWS 接口之前拒绝。
- 安装者的身份需要能创建 VPC、EKS、RDS、IAM 角色、ECR、S3、Cognito、ACM、Route 53 记录与 CloudFormation 栈，并能读 SSM 参数 `/cdk-bootstrap/hnb659fds/version`（`ssm:GetParameter`，预检核对区域已做 CDK bootstrap）。
- 若当前身份是 assumed role（例如 EC2 实例角色），`deploy/scripts/foundation/deploy.sh` 取该角色本身的 ARN 作为集群管理员，写进 EKS access entry。
- 环境所在的区域已做过 CDK bootstrap（那个区域有 `CDKToolkit` 栈），版本不低于 6（各栈模板里的 `CheckBootstrapVersion` 规则的要求）。`install.sh` 在开始时读 SSM 参数 `/cdk-bootstrap/hnb659fds/version`（默认 qualifier）核对，没有就停下，什么都不记。补做的命令是 `(cd deploy/aws && npm ci && npx cdk bootstrap aws://<账号>/<区域> --app '')`：`--app ''` 让 CDK CLI 不合成本仓库的应用，应用只在部署脚本给齐上下文时才能合成，不加它会报 `-c account=<account>: …`。

**区域的条件。** 所选区域要提供基础栈的 Kubernetes 版本（1.36），并有至少两个可用区同时提供 t3.xlarge 与 r6i.xlarge。版本与两种节点类型写在 `deploy/aws/src/foundation-spec.json`，栈与安装器都读这一处。基础栈的两个可用区由安装器给出，CDK 不再查找：已有的基础栈沿用它 VPC 现在的可用区，所以 VPC 不会移动；新环境取按名字排序、同时提供这两种节点类型的前两个，例如 `us-west-2a`、`us-west-2b`，或 `us-east-2a`、`us-east-2b`。

**配额。** 在环境所在的区域里，一套环境另外占用：1 个 VPC、1 个 NAT 网关、1 个公网 ALB、3 个 RDS 实例（其中两个是测试源），以及 2 个弹性 IP。两个弹性 IP 中，一个给 NAT 网关；另一个是"引导地址"：EKS 公网端点建好时，先只放行这个从不发起连接的地址，再收窄到安装者的出口地址，所以集群从创建起就不对外开放。

**安装前的检查。** `install.sh` 在改动任何东西、记下任何设置之前，只读地核对下面几项（`deploy/scripts/lib/preflight.sh`），不满足就停下，并说明缺什么、实际有多少：

- 当前凭证属于 `COLVENNA_ACCOUNT`；托管区在这个账号里、是公有区、名字一致（第 1 节）；
- 环境名没有被别的区域的环境占用（第 1 节）；
- 这次要部署栈时（`foundation`、`sources`、`catalog-stack`、`operator-stack`、`open-stack`、`entry-stack` 之一会运行）：区域已做过 CDK bootstrap，版本不低于 6（上文"账号与身份"）；
- 区域的 EKS 提供 `foundation-spec.json` 的 Kubernetes 版本，处于标准或延长支持；
- 有两个可用区同时提供两种节点类型：新环境是选出的两个，已有的环境是它 VPC 的两个；
- 只对新环境：区域里的 VPC 数加 1 不超过配额 `L-F678F1CE`（VPCs per Region），弹性 IP 数加 2 不超过配额 `L-0263D0A3`（EC2-VPC Elastic IPs）。配额按账号在该区域的实际值读，读不到时用默认值。NAT 网关、ALB 与 RDS 的配额没有检查；
- 入口域名是这套环境自己的（第 1 节"账号、托管区与入口域名"）；
- 从源码安装、并且这次会运行 `doris-image` 时：查询引擎的两个镜像已经在本环境的仓库里，或者来源仓库（下文"查询引擎镜像"）里有这两个 digest，当前凭证读得到。给了 `DORIS_IMAGE_SOURCE` 时先核对它的格式，两个镜像都已在本环境的仓库里也一样。
- 从源码安装、并且这次会运行 `catalog` 时：Colvenna Catalog 发布版的来源里有固定的镜像与 chart 的 digest。默认来源 GitHub Container Registry 与别的匿名来源要把它们给匿名读者；`COLVENNA_CATALOG_REGISTRY` 指向 ECR 时，当前凭证要读得到（见下文"查询引擎镜像"之后的 colvenna-catalog 几条）。

`foundation-spec.json` 的 Kubernetes 版本与栈用的 kubectl layer（`KubectlV36Layer`）绑在一起，不能只改这个文件；要换版本，两者一起改。

**域名与证书。** 需要该账号里的一个公有 Route 53 托管区，由 `COLVENNA_HOSTED_ZONE_ID` 与 `COLVENNA_HOSTED_ZONE_NAME` 指定（第 1 节）。入口域名建在这个区里；入口的 ACM 证书也用这个区里的 DNS 记录自动校验，不需要事先申请证书。托管区的域名要能从公网解析，即上级域名已把它委派过来。第 10 节的实测环境用维护者账号里的一个托管区，下文写作 `<COLVENNA_HOSTED_ZONE_NAME>`。

**安装者机器上的工具。** 下表的版本是第 10 节实测所用的版本。安装在 aarch64 的 Ubuntu 主机上完成，集群节点是 amd64，所以从源码安装时镜像都以 `docker buildx --platform linux/amd64` 构建。按发布候选安装（第 3 节）不构建任何东西：Maven、Docker 与 Go 镜像只有从源码安装才需要，JDK 只需要它的 `keytool`。

| 工具 | 实测版本 | 用途 |
| --- | --- | --- |
| AWS CLI v2 | 2.34.30 | 各脚本 |
| `jq`、`python3`、`git` | 1.7、3.12.3、2.43.0 | 各脚本；`curl`、`openssl`、`flock` 用系统自带的 |
| Node.js 与 npm | 22.23.3、10.9.9 | CDK 应用（`deploy/aws`，`npm ci` 装 CDK 2.1146.0 与 aws-cdk-lib 2.274.0） |
| JDK 与 Maven | 21.0.12、3.8.7 | 从源码安装：构建 colvenna-operator、colvenna-jobs（colvenna-jobs 以 `--release 11` 编译）；colvenna-catalog 不构建，用它的发布版。两种安装都要 JDK 的 `keytool`，用于 Doris 的信任库 |
| Docker 与 buildx | 29.1.3、0.30.1 | 只在从源码安装时：构建全部镜像，需能构建 linux/amd64（aarch64 主机上需 QEMU binfmt）；colvenna-cli 在固定 digest 的 Go 镜像里构建 |
| kubectl、Helm、crane | v1.36.5、v3.19.0、v0.22.1 | 第一步 `tools` 把它们装进 `deploy/.tools`，并校验 SHA-256，不使用系统里的版本 |

**查询引擎镜像。** 平台 Doris 的 FE 与 BE 镜像是基于 Apache Doris 4.1.4 修改构建的查询引擎镜像：自带的 Paimon 1.4.2 读取器换成了自建的 Paimon 2.0.0 读取器，另加 PostgreSQL JDBC 驱动，配方在 [`deploy/images/doris/`](../../deploy/images/doris/README.md)。它不是 ASF 发布的 Apache Doris，所以仓库不以 Doris 命名：本环境的是 `colvenna-lake-<env>/query-engine`，发布仓库的是 `colvenna-lake-release/query-engine`（[商标与命名](../../release/trademarks.md)）。下文的步骤名 `doris-image`、设置 `DORIS_IMAGE_SOURCE` 与 `DORIS_FE_DIGEST` 是内部名字，没有改。

- 安装只运行两个固定 digest 的镜像：`deploy/scripts/lib/common.sh` 的 `DORIS_FE_DIGEST`、`DORIS_BE_DIGEST`。
- `doris-image` 步骤用 crane 按原 digest，把这两个镜像从来源仓库复制进本环境的仓库，tag 是 `<DORIS_TAG>-fe-<digest 的前 12 位十六进制>`、`<DORIS_TAG>-be-<…>`（`lib/common.sh` 的 `doris_tag`），与第三方镜像一样带着 digest：仓库的 tag 不可改写，重新固定的 digest 有自己的 tag，与旧的并存（#278）。来源仓库由 `DORIS_IMAGE_SOURCE` 指定，形如 `<账号>.dkr.ecr.<区域>.amazonaws.com/<仓库>`，可以是任何区域、任何账号的 ECR 仓库，只要当前凭证能从它拉取；别的账号的仓库要有允许本账号拉取的仓库策略。例如发布仓库里的 `colvenna-lake-release/query-engine`，或一套已装好的环境的 `colvenna-lake-<env>/query-engine`。
- 不给 `DORIS_IMAGE_SOURCE` 时，取 `COLVENNA_RELEASE_REGISTRY` 指定的发布仓库里、工作区 `release/` 下最新（按生成时间）的固定这两个 digest 的发布清单（`release/<候选>/manifest.json`）所写的查询引擎仓库（清单不写仓库地址）。只认 schema 2 起（`v1.0.0-rc.3` 起）、仓库是 `colvenna-lake-release/query-engine` 的清单：`v1.0.0-rc.1` 的清单（schema 1）与 `v1.0.0-rc.2` 的清单（产品旧名字的仓库）都不作为默认来源。没有给 `COLVENNA_RELEASE_REGISTRY`，或工作区里没有这样的清单时（下一个候选打出之前都是这样），从源码安装必须给出 `DORIS_IMAGE_SOURCE`：`install.sh` 在开始时就核对，什么都不建就停下。
- 两个镜像都已在本环境的仓库里、带着各自的 tag 时，`doris-image` 不复制、也不登录任何仓库，日志里每个镜像一行 `… (already there)`。已在仓库里、只是不带这个 tag 时（例如在 `build-images.sh` 的构建上重新固定的 digest，tag 是 `build-<sha>-fe`），只登录本环境的仓库，按 digest 加上 tag，不复制，日志里是 `… (there already, tagged now)`。给了格式不对的 `DORIS_IMAGE_SOURCE`（不是 `<账号>.dkr.ecr.<标准区域>.amazonaws.com/<仓库>`）时，即使镜像都已在，也报 "DORIS_IMAGE_SOURCE must be an ECR repository in a standard region"（#227）。
- 拆除一套环境会连同它的仓库一起删掉。所以至少要留一个仓库里有这两个 digest；否则只能用 `deploy/scripts/doris/build-images.sh` 按配方重建，并把 `common.sh` 里的两个 digest 改成新构建的，再在新镜像上重跑验收。
- 从源码安装时，colvenna-catalog 用 Colvenna Catalog 的发布版：`catalog` 一步按 digest 取 `deploy/images/colvenna-catalog.json` 固定的镜像与 chart。默认从 Colvenna Catalog 发布它们的 GitHub Container Registry 匿名拉取（镜像 `ghcr.io/colvenna/colvenna-catalog`，chart `ghcr.io/colvenna/charts/colvenna-catalog`），不需要任何凭证，也不需要 `COLVENNA_RELEASE_REGISTRY`。
- 设置 `COLVENNA_CATALOG_REGISTRY=<仓库地址>[/<前缀>]` 时改从那里取，它的前缀下要有同名的仓库（`colvenna-catalog`、`charts/colvenna-catalog`）与同样的 digest。用途是 catalog 公开之前的候选验收：指向维护者的私有发布仓库，例如 `<账号>.dkr.ecr.<区域>.amazonaws.com/colvenna-catalog-release`。ECR 仓库用当前的 AWS 凭证读，只要拉取权限；别的仓库也是匿名读。
- 两种来源都一样核对 digest、镜像的标签与 chart 的注解（见第 4 节 `catalog` 一步）。`install.sh` 在开始时就核对来源里有这两个 digest：GitHub Container Registry 与别的匿名来源按 OCI 分发接口匿名读清单（`manifest.py catalog-held`），ECR 以 `ecr:BatchGetImage` 读。没有就什么都不建就停下。Colvenna Catalog 公开之前，GitHub Container Registry 里还没有它的包，默认来源会被拒绝（匿名读者得到 403 `DENIED`）；那时要给 `COLVENNA_CATALOG_REGISTRY`。
- 从源码安装时，其余镜像都在本环境自己的 ECR 里从源码构建。按发布候选安装（第 3 节）时，全部镜像都从发布仓库按 digest 复制，查询引擎也一样（清单的 `query-engine-fe`、`query-engine-be`），不再用 `DORIS_IMAGE_SOURCE`。

**网络。** EKS 的公网 API 端点只放行安装者机器的出口 IPv4（/32），并要求认证。安装期间不要更换出口地址。

**干净的提交。** `install.sh` 拒绝在有未提交改动的工作区里运行，构建镜像和部署栈的脚本单独运行时也各自检查，所以装出来的每样东西都追溯到一个提交。安装期间如果要编辑代码，请在另一个工作区里运行安装，例如用 `git worktree add --detach` 新建一个。

## 3. 一条命令安装

`install.sh` 有两种用法：

- **按发布候选安装**（`--release <候选>`）：在候选的 tag 上，安装发布清单固定的镜像与 colvenna CLI，从发布仓库按 digest 复制，安装时不构建任何镜像。用户安装、UAT 都用这种方式，见下文“按发布候选安装”。
- **从源码安装**（不带 `--release`）：在当前提交上构建全部镜像，colvenna-catalog 的除外，它用固定的 Colvenna Catalog 发布版，默认从 GitHub Container Registry 匿名取（catalog 公开之前另给 `COLVENNA_CATALOG_REGISTRY`，见第 2 节）。这是开发用的方式，装出来的是“同一份源码现场构建的镜像”，不一定与验收过的候选逐字节相同。

```bash
export AWS_PROFILE=<profile>                                      # 要装进的账号的凭证，或用默认凭证链
export COLVENNA_ACCOUNT=<账号> COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名>   # 新环境第一次要给
git checkout <候选>                                                                       # 候选的 tag
COLVENNA_ENV=uat COLVENNA_REGION=us-west-2 deploy/install.sh --release <候选> --fixtures  # 按候选安装（v1.0.0-rc.2 起另给 COLVENNA_RELEASE_REGISTRY，见下文）；不加 --fixtures 则只装平台
COLVENNA_ENV=uat COLVENNA_REGION=us-west-2 deploy/install.sh --fixtures  # 从源码安装；新环境第一次要给区域与上面的设置
COLVENNA_ENV=uat deploy/install.sh --fixtures                            # 之后从本地状态读
COLVENNA_ENV=uat deploy/install.sh --list                                # 列出步骤；只要环境名，不要设置
COLVENNA_ENV=uat deploy/install.sh --fixtures --from operator-image      # 某步失败，修好后从那一步继续
COLVENNA_ENV=uat deploy/install.sh --only verify                         # 只跑一步
```

`install.sh` 在改动任何东西之前，先打印这次要装到哪里、装什么：账号与身份、区域、环境、集群（新环境写“none yet”）与版本（`release <候选>` 及镜像所用的提交，或 `source <提交>`）。同样的几行也记进 `install.log`。

- **重跑与续装**：每一步都可以重跑，各脚本会收敛到它要装成的样子。中断或失败后，修好原因，再以 `--from <步骤>` 继续。`flink-image` 要求基础栈记录的部署 SHA 与当前提交一致，所以换了提交就要先重跑 `foundation`；栈没有变化的部分不会改动，实测 4.5 分钟。
- **Helm 步骤中断之后**：安装在 `helm upgrade` 进行中被打断（SSH 断开、Ctrl-C、执行机重启）时，release 会停在 `pending-install`、`pending-upgrade` 或 `pending-rollback`，Helm 拒绝之后的一切操作。各安装脚本的 Helm 操作都经 `deploy/scripts/lib/helm.sh` 的 `helm_upgrade`：它在本工作区的本地状态里记下每次要开始的 revision；续装时，停住的若正是本工作区被打断的那次运行开始的 revision，就先把它回滚到最后一个成功的 revision（从未成功过的 `pending-install` 则卸掉重装），在步骤日志里记一行 `helm_upgrade: … rolling back to revision N`，再升级。别的情况它不动，见第 9 节。
- **节点数**：`WRITE_NODES`、`QUERY_NODES` 指定两个节点组的大小，默认各 2 台。这也是平台、作业与验收测试需要的规模。
- **证据**：`INSTALL_EVIDENCE` 下保存每步一个日志，默认位置是 `deploy/.state/<环境>/install-<UTC 时间>/`；`steps.tsv` 记录每步的结果与秒数，`install.log` 记录起止时间、提交与身份。
- **结束检查**：最后一步 `verify` 的内容见第 4 节表格的最后一行。

### 按发布候选安装

每个发布候选在 `release/<候选>/manifest.json` 里有一份发布清单：全部产品镜像、测试夹具镜像与第三方镜像在发布仓库（ECR 的 `colvenna-lake-release/…`）里的仓库名、tag 与 digest，colvenna CLI 二进制的 SHA-256，Helm chart、安装脚本与 UAT 用例文档的版本，支持的配置与必需用例集合。从 `v1.0.0-rc.2` 起清单不写发布仓库的账号与区域：安装时由 `COLVENNA_RELEASE_REGISTRY=<账号>.dkr.ecr.<区域>.amazonaws.com` 指定（每次 `--release` 都要给，包括 `--from`、`--only` 重跑）。候选怎样生成、tag 指向哪里、维护者的发布仓库怎样取得，见[发布候选](../../release/README.md)。

```bash
export AWS_PROFILE=<目标账号的 profile>
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com   # v1.0.0-rc.2 起的候选
git checkout <候选>
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> \
  deploy/install.sh --release <候选> [--fixtures]            # 新环境；之后只需 COLVENNA_ENV 与 COLVENNA_RELEASE_REGISTRY
```

**先检查，再动手。** 在打印目标、记下区域、动任何资源之前，`install.sh` 先运行 `deploy/scripts/release/check-release.sh`，以下任一项不满足就停下并列出原因：

- 给了 `COLVENNA_RELEASE_REGISTRY`，形如 `<账号>.dkr.ecr.<标准区域>.amazonaws.com`；
- 清单存在、完整、字段合法，并且就是所给候选的（`deploy/scripts/release/manifest.py validate`），其中的第三方镜像就是 tag 上 `deploy/images/third-party.tsv` 固定的；
- 工作区没有未提交的改动，并且与镜像所用的提交（清单的 `source.built_from`）相比，只有 `release/` 下不同。候选的 tag 正是这样；之后的 main 改过别的文件，会被拒绝。本地没有那个提交时，先 `git fetch origin --tags`；
- 清单的两个查询引擎 digest（`query-engine-fe`、`query-engine-be`）就是 `deploy/scripts/lib/common.sh` 固定的（`install-doris.sh` 运行的正是它们）；
- 清单固定的每个镜像（带 `--fixtures` 时也包括测试夹具的；第三方镜像也一样）与 colvenna CLI，都在 `COLVENNA_RELEASE_REGISTRY` 里，以清单写的 digest、带着清单写的 tag；
- 发布仓库里 colvenna CLI 的镜像，装的正是清单记下 SHA-256 的那三个二进制（下载它唯一的一层逐个核对）。安装到别的账号时，发布仓库要先允许这个账号拉取（[发布候选](../../release/README.md)的 `COLVENNA_RELEASE_PULL_ACCOUNTS`），否则这项检查就停下。

这项检查只读，也可以单独运行：`COLVENNA_RELEASE_REGISTRY=<发布仓库> deploy/scripts/release/check-release.sh <候选> --fixtures`。`tests/acceptance/deploy/run-release-refusals.sh` 逐项验证上面的拒绝：没有给发布仓库、不存在的候选、别的候选的清单、缺镜像的清单、发布仓库里没有的 digest、互换了的 digest、不是 `common.sh` 固定的 Doris digest、不是 `third-party.tsv` 固定的第三方 digest、与 CLI 镜像不符的二进制 SHA-256、`release/` 以外有改动的工作区、未提交的改动、同一提交上已从源码安装过的环境，并核对每次都在记下区域之前停下、账号里没有多出任何栈。

**不构建，只复制。** 下面几步改为 `deploy/scripts/release/install-images.sh`，用 crane 按 digest 从 `COLVENNA_RELEASE_REGISTRY` 复制进本环境的仓库（跨账号、跨区域都可以），再像构建脚本那样记录引用（`deploy/.state/<环境>/` 下的同名文件），所以后面的步骤与验收测试不必改动。复制以后各镜像在本环境仓库里以当前提交为 tag，与构建时一样；第三方镜像与查询引擎除外，它们的 tag 带着 digest，与从源码安装时相同。已有同名 tag 而 digest 不同时停下。

| 步骤 | 按候选安装时 |
| --- | --- |
| `third-party` | 复制平台的第三方镜像（带 `--fixtures` 时也复制测试夹具的）进 `colvenna-lake-<env>/third-party`，tag 与从源码安装时相同 |
| `flink-image` | 复制 `flink` |
| `catalog` | 复制候选里 Colvenna Catalog 的镜像与 chart（清单的 `catalog`，`fetch-catalog.sh --release <候选>`，digest 与从源码安装时相同，同样核对构建它们的提交），再 `install-catalog.sh` |
| `operator-image` | 复制 `operator` 与五个 `jobs-…` |
| `doris-image` | 复制查询引擎的 FE 与 BE（清单的 `query-engine-fe`、`query-engine-be`，两个 digest 已在检查时与 `lib/common.sh` 核对），tag 与 `copy-images.sh` 的相同（`<DORIS_TAG>-fe-<digest 前缀>`、`-be-<…>`，不是候选名），所以跨越一次重新固定的升级不会撞上旧 digest 的 tag（#278）；tag 已在时不复制 |
| （`readers`） | 复制 `readers-spark` |
| `console`、`bi` | 复制，再 `install-console.sh`、`install-bi.sh` |
| `cli` | 从发布仓库的 `colvenna-cli` 镜像取出三个二进制，再逐个核对 SHA-256，放进 `colvenna-cli/dist/`。验收测试用 colvenna CLI 时按 SHA-256 认它们（`tests/acceptance/common.sh` 的 `colvenna_cli`），不会在上面重新构建 |

其余步骤（栈、Helm release、`verify`）与从源码安装相同，Helm chart 与脚本都取自 tag 的工作区。按候选安装不需要 Maven、Docker 与 Go；仍需要 JDK 的 `keytool`（`doris` 步骤建信任库）与 Node.js（部署栈）。重跑、`--from`、`--only` 的用法不变，每次都要带 `--release`。

**升级到一个候选。** 在已有环境上检出新候选的 tag，以 `--release` 重跑 `install.sh`，各步照常收敛（第 7 节）。

## 4. 步骤说明

`install.sh` 依次调用下表的脚本，括号里的步骤只在加了 `--fixtures` 时执行。

单独运行某一步时，先 `export COLVENNA_ENV=<名字>`。下面几个脚本还要再带一个证据目录参数：`install-catalog.sh`、`install-marquez.sh`、`install-polaris.sh`、`install-bi.sh`、`check-health.sh`、`verify-catalog.sh`。

耗时取自第 10 节的那次实测。

| 步骤 | 脚本 | 做什么 | 怎么确认 | 实测耗时 |
| --- | --- | --- | --- | --- |
| `tools` | `deploy/scripts/foundation/install-tools.sh` | 下载 kubectl、Helm 与 crane，并校验 SHA-256 | 三个文件在 `deploy/.tools/` | 1 s |
| `foundation` | `deploy/scripts/foundation/deploy.sh` | 部署基础栈（两个可用区见第 2 节"区域的条件"）；把集群的升级策略设为 `EXTENDED`，打印当前 Kubernetes 版本标准支持的截止日期，不到 90 天时告警（第 7 节）；把 EKS 公网端点从引导地址收窄到安装者的出口 /32；确认匿名请求 `/version`、`/livez`、`/readyz`、`/healthz` 都得到 401 或 403；写 kubeconfig `deploy/.state/<环境>/kubeconfig` | 脚本最后打印 `public API limited to <IP>/32` | 22 min |
| `third-party` | `deploy/scripts/foundation/copy-third-party.sh` | 用 crane 按原 digest 把 `deploy/images/third-party.tsv` 里平台的第三方镜像（带 `--fixtures` 时也包括测试夹具的）复制进 `colvenna-lake-<env>/third-party`，并核对复制后的 digest；给了 `COLVENNA_RELEASE_REGISTRY` 且发布仓库里有的从发布仓库复制，否则从上游。之后集群只从这份副本拉它们（[第三方镜像](../../release/README.md#第三方镜像)） | `aws ecr describe-images --repository-name colvenna-lake-<env>/third-party` 列出每个名字的 tag | 未实测 |
| `engines` | `deploy/scripts/foundation/setup-operators.sh` | 安装 Flink Kubernetes Operator 与 Doris Operator，镜像都是本环境的副本；两个 chart 都校验 SHA-256，渲染出的清单不得有公网 Service 或 Ingress | CRD `flinkdeployments.flink.apache.org`、`dorisclusters.doris.selectdb.com` 存在 | 1 min |
| `flink-image` | `deploy/scripts/foundation/build-flink-image.sh` | 构建 Flink 1.20.4 + Paimon 2.0.0 基础镜像，推到本环境的 `flink-base` 仓库，记录 digest | `deploy/.state/<环境>/flink-image-ref.txt` | 0.5 min |
| （`sources`） | `tests/fixtures/sources/deploy-sources.sh` | 在基础栈的隔离数据库子网里部署 MySQL 与 PostgreSQL 测试源，只放行 EKS 集群安全组 | 两个栈输出的 `SourceSha` 等于当前提交 | 19 min |
| `catalog-stack` | `deploy/scripts/catalog/deploy-stack.sh` | catalog 的 ECR、服务端、数据与测试引擎的角色、数据库密钥 | 栈 `…Catalog` 完成 | 1.5 min |
| `catalog` | `deploy/scripts/catalog/fetch-catalog.sh`、`install-catalog.sh` | 取 `deploy/images/colvenna-catalog.json` 固定的 Colvenna Catalog 发布版，默认从 GitHub Container Registry 匿名取（`COLVENNA_CATALOG_REGISTRY` 可以改指别的仓库，见第 2 节）：先按 digest 读镜像与 chart（`helm pull oci://…@sha256:…`），核对二者都由该版本的 `built_from` 构建（镜像的标签 `org.opencontainers.image.revision`、chart 的注解 `colvenna-catalog/source-commit`），镜像的平台是 linux/amd64，不符就停下，什么都不复制；再把镜像按 digest 复制进 `colvenna-lake-<env>/colvenna-catalog`（tag 是 catalog 的版本，例如 `v1.0.0-rc.1`），chart 存在 `deploy/.state/<环境>/catalog/`；在项目 RDS 上建库与角色；签发私有 CA 与服务端证书；以 Colvenna Lake 的取值 Helm 安装这个 chart（授权环境即 `COLVENNA_ENV`；命名空间、release、服务账号、Secret、数据库与账号沿用 `colvenna-catalog`、`colvenna_catalog`，所以 [#5](https://github.com/colvenna/colvenna-lake/issues/5) 之前装的环境原地接管，元数据、token 与授权不变）；bootstrap 管理员 token 直接写进 Secret，不打印。两个副本分在两台写入节点上：丢了一台写入节点时，它上面的副本等节点组补回新节点再起，不挤到剩下的那台上（写入节点组只有一台时，两个副本都在它上面，`verify` 的 `catalog-spread` 照样通过）。其他平台组件里，控制台也是两个副本，不限节点、分布只是优先：丢一台节点时，被调走的副本在另外三台里挑没有副本的那台，只有其余节点都放不下时才会与另一个副本同在一台；BI、Operator、Grafana 与 Prometheus 各一个副本 | `kubectl -n colvenna-catalog get pods -o wide` 两个副本 Ready，在两台节点上（写入节点组只有一台时在同一台上） | 2 min |
| （`strimzi`） | `tests/fixtures/kafka/install-strimzi.sh` | Strimzi 1.2.0，只看管 `colvenna-lake-kafka` | `strimzi-cluster-operator` 就绪 | 1 min |
| `operator-stack` | `deploy/scripts/operator/deploy-stack.sh` | Operator 与作业的镜像仓库；每个受管命名空间一个作业角色，命名空间由 `MANAGED_NAMESPACES` 指定，默认 `colvenna-data` | 栈输出 `JobRoleArns` | 1.5 min |
| `operator-image` | `deploy/scripts/operator/build-images.sh` | 构建 colvenna-operator 与五个 colvenna-jobs 镜像（连接器 jar 校验 SHA-256），记录 digest | `deploy/.state/<环境>/operator-images-<sha>.json` | 2 min |
| `doris-image` | `deploy/scripts/doris/copy-images.sh` | 用 crane 按原 digest 把固定的查询引擎 FE、BE（基于 Apache Doris 4.1.4）复制进 `colvenna-lake-<env>/query-engine`，tag `<DORIS_TAG>-fe-<digest 前缀>`、`-be-<…>`（仓库里已有的 digest 只加 tag），并核对 tag 下的 digest | 输出的两个 digest 与 `DORIS_FE_DIGEST`、`DORIS_BE_DIGEST` 相同 | 2 min |
| `doris` | `deploy/scripts/doris/install-doris.sh` | 平台 Doris：1 FE、1 BE，gp3 加密卷，root 口令只进 Secret。NetworkPolicy 只放行 FE 与 BE 之间、Doris Operator、Operator 的命名空间，以及带 `colvenna-lake.io/doris-client` 标签的命名空间 | FE、BE Ready | 5 min |
| `operator` | `deploy/scripts/operator/install-operator.sh` | 受管命名空间、RDS CA、CRD；Operator 自己的 catalog 身份与 token；Helm 安装。这一步已能发现上一步装好的 Doris | `kubectl -n colvenna-system rollout status deployment/colvenna-operator` | 1 min |
| `monitoring` | `deploy/scripts/monitoring/install-monitoring.sh` | Prometheus（带告警规则）与 Grafana；Grafana 管理员口令只进 Secret，只能经 kubectl port-forward 访问 | 两个 Pod Ready | 0.5 min |
| （`source-logins`） | `tests/fixtures/sources/sources.sh prepare` | 在测试源上建 CDC 登录：MySQL `colvenna_cdc` 带复制权限，binlog 保留 24 小时；PostgreSQL `colvenna_pgcdc` 带 `rds_replication`。再把验收测试用的登录写成受管命名空间里的 Secret | `kubectl -n colvenna-data get secret mysql-cdc postgres-cdc mysql-inspect` | 1 min |
| `open-stack` | `deploy/scripts/open-layer/deploy-stack.sh` | Polaris 与 Iceberg 读端的角色 | 栈 `…OpenLayer` 完成 | 2 min |
| `marquez` | `deploy/scripts/open-layer/install-marquez.sh` | Marquez，库在项目 RDS 上；只有 ClusterIP，NetworkPolicy 只放行 Operator 的命名空间 | Pod Ready | 1 min |
| `polaris` | `deploy/scripts/open-layer/install-polaris.sh` | Polaris 与 `colvenna_open` catalog，所有 API 都要 OAuth2 token；为 Operator 建 Polaris principal | Pod Ready | 2 min |
| （`readers`） | `tests/fixtures/readers/build-readers.sh` | Spark Iceberg 读端镜像，供验收测试使用 | `deploy/.state/<环境>/readers-<sha>.json` | 0.5 min |
| `entry-stack` | `deploy/scripts/entry/deploy-stack.sh` | Cognito 用户池（无自助注册、强制 TOTP、由管理员建用户）、ACM 证书、公网 ALB（TLS，HTTP 重定向到 HTTPS）、Route 53 记录、Pod 角色、访问日志桶 | 栈 `…Entry` 完成 | 4 min |
| `lbc` | `deploy/scripts/entry/install-lbc.sh` | AWS Load Balancer Controller，只把 Pod 绑定到栈建好的目标组，不能建负载均衡器 | Pod Ready | 0.5 min |
| `console` | `deploy/scripts/entry/build-console.sh`、`install-console.sh` | 构建并安装管理控制台 | 目标组报告目标健康 | 3 min |
| `bi` | `deploy/scripts/entry/build-bi.sh`、`install-bi.sh` | 构建并安装 BI（Superset），元数据库在项目 RDS 上 | 目标组报告目标健康 | 2.5 min |
| `operator-again` | `deploy/scripts/operator/install-operator.sh` | 再装一次 Operator，这次它还能发现 Marquez、Polaris 与 BI | Operator 的 Helm values 里 `doris.host`、`lineage.url`、`polaris.uri`、`bi.namespace` 都不为空 | 1 min |
| `cli` | `colvenna-cli/check.sh build` | 构建 `colvenna` CLI（linux/amd64、linux/arm64、darwin/arm64） | `colvenna-cli/dist/` | 2.5 min |
| `verify` | `deploy/install.sh` 内 | `deploy/scripts/catalog/check-health.sh`：colvenna-catalog 副本、分布、PodDisruptionBudget、数据库与 schema 版本、两个 Engine Operator。`verify-catalog.sh`：对 colvenna-catalog 的 14 项访问检查。Operator 在运行。入口两个域名对没有会话的请求返回 302，跳到本环境的 Cognito | `steps.tsv` 最后一行为 `verify ok` | 3 min |

## 5. 安装之后

**入口用户。** 用户池不允许自助注册，由管理员添加：

```bash
COLVENNA_ENV=uat deploy/scripts/entry/users.sh add alice alice@example.com colvenna-admin   # 或 colvenna-operator、colvenna-bi
```

Cognito 会发邮件给出临时口令。用户首次登录时设新口令，并绑定 TOTP 应用。之后访问 `https://console.<入口域名>/` 与 `https://bi.<入口域名>/`，例如托管区 `example.com` 里的环境 `uat` 默认是 `https://console.colvenna-uat.example.com/`。同一脚本还提供 `groups`、`disable`、`remove`。

**集群访问。** kubeconfig 在 `deploy/.state/<环境>/kubeconfig`，由各脚本按需更新。EKS 端点只接受安装者的出口地址。

**Grafana。**

1. 运行 `kubectl -n colvenna-monitoring port-forward svc/colvenna-monitoring-grafana 3000:3000`。
2. 打开 `http://localhost:3000`，用户名 `admin`。
3. 口令在同一命名空间的 Secret `colvenna-grafana-admin` 里，键为 `admin-password`。

**开发用 Kafka（可选）。** 需要先装好 Strimzi，即带 `--fixtures` 安装：

```bash
COLVENNA_ENV=uat tests/fixtures/kafka/dev-kafka.sh install <证据目录>             # 装好 Kafka、topic 与用户
COLVENNA_ENV=uat tests/fixtures/kafka/dev-kafka.sh check <证据目录> produce 10    # 写 10 条，并做拒绝测试
COLVENNA_ENV=uat deploy/scripts/catalog/check-health.sh <证据目录> --dev-kafka    # 健康检查里加上 Kafka
COLVENNA_ENV=uat tests/fixtures/kafka/dev-kafka.sh purge <证据目录> --yes         # 删除，数据也删
```

它只用于开发与测试，生产用的 Kafka 由用户提供。开发用 MongoDB 由验收测试自己安装与删除（`tests/fixtures/mongo/dev-mongo.sh`）。

**声明与查看。** 用 `colvenna` CLI 或 `kubectl` 在受管命名空间（默认 `colvenna-data`）里声明数据源与表，见[用户手册](user.md)。

**验收测试。** 验收测试在 `tests/acceptance/<能力>/`，在带 `--fixtures` 装好的环境上运行，`tests/acceptance/run-uat.sh` 按 UAT 的顺序运行全部用例。用例见 [UAT 测试用例](../uat/cases.md)，各发布候选在两个区域的执行结果按候选归档在 `docs/uat/reports/<候选>/`，最新的是 [v1.0.0-rc.5 的 UAT 报告](../uat/reports/v1.0.0-rc.5/report.md)；发布候选之前的一次见 [UAT 执行报告](../uat/report.md)。

## 6. 安全要点

- **没有匿名的公网入口。**
  - EKS API 要求认证，并只放行安装者的地址；匿名请求 `/version` 等也会被拒绝。
  - Web 入口必须经 Cognito 认证（强制 TOTP）后才转发请求。
  - 其余服务只在集群内可达：
    - colvenna-catalog 只有 ClusterIP，走自建 CA 的 TLS 与 bearer token，并按命名空间标签限制客户端；
    - Polaris 要求 OAuth2；
    - Doris 要求账号口令，Grafana 要求登录；
    - Marquez 与 Prometheus 自身不做认证，只靠 NetworkPolicy 放行指定的命名空间。
- **NetworkPolicy 确实生效。**
  - 基础栈把 VPC CNI 作为托管插件安装，并打开它的 network policy agent（`enableNetworkPolicy`）。2026-10-20 实测：没有这个插件时，新建的 EKS 1.35 集群里 `aws-node` 以 `--enable-network-policy=false` 运行，NetworkPolicy 全都不生效。
  - 核对方法：`kubectl -n kube-system get ds aws-node` 的第二个容器参数是 `--enable-network-policy=true`；`verify` 的检查"未打标签的命名空间被拦截"通过。
- **最小权限。**
  - 每个 IRSA 角色只信任自己的服务账号。
  - 作业角色只能访问本命名空间的 Flink 状态（`flink-state/<命名空间>/`）。
  - 引擎读写表数据，只能用按表签发的临时凭证：Paimon 表由 colvenna-catalog 签发，Iceberg 读端由 Polaris 签发。
- **口令与 token** 只进 Kubernetes Secret 或 Secrets Manager，脚本不打印它们。
- **仓库桶** 禁止公网访问，强制 TLS，服务端加密。
- **审计日志。** 仓库桶的 S3 服务器访问日志（谁读写了哪些表文件）与 VPC 流日志写进基础栈的审计日志桶，保留 365 天；这个桶同样禁止公网访问、强制 TLS、服务端加密。
- **合规检查。** CDK 应用合成每个栈时都运行 cdk-nag 的 AWS Solutions 规则（`deploy/aws/src/nag.ts`）。没有在资源旁写明理由确认的错误级发现会让合成失败，部署也随之失败；`npm test` 逐栈检查，错误级与警告级的都拦。确认了哪些、理由是什么，见各栈的 `acknowledge(...)`。
- **暴露面核对。** 装好之后，`tests/acceptance/security/inventory.sh` 清点账号在该区域里公网可达的资源，分出本环境的与别的，并检查没有 Lambda 函数 URL、没有匿名的公开资源；`tests/acceptance/security/run-denials.sh` 再对本环境的每个公网资源实测三类请求被拒：未认证、有身份但越权、绕过入口。发布检查的安全一节按 `release/security-template.md` 填写。
- **版本固定。**
  - 平台自己的镜像与集群里运行的全部第三方镜像都按 digest 固定；第三方镜像只固定在 `deploy/images/third-party.tsv` 一处，安装时复制进本环境的仓库，集群从那里拉，不依赖上游一直保留那个 digest（[第三方镜像](../../release/README.md#第三方镜像)）。
  - 下载的 chart 与工具都校验 SHA-256。

## 7. 升级

在新的提交上重跑 `install.sh`，或只重跑其中的步骤：

- 每个脚本都会收敛到新提交要装成的样子；
- `install-operator.sh` 每次都以 server-side apply 更新 CRD；
- colvenna-catalog 也可以单独升级到当前提交固定的 Colvenna Catalog 版本：`COLVENNA_ENV=uat deploy/install.sh --only catalog`（catalog 公开之前另给 `COLVENNA_CATALOG_REGISTRY=<私有发布仓库>`）；按候选安装的环境用 `install.sh --release <候选> --only catalog`。这一步先取发布版并核对（`fetch-catalog.sh`），再由 `install-catalog.sh` 每次给出全部取值，不沿用 Helm release 里存着的旧取值：[#5](https://github.com/colvenna/colvenna-lake/issues/5) 之前装的环境存的取值里没有客户端与监控命名空间的标签（那是 Colvenna Lake 原来的 chart 的默认值），沿用的话 catalog 的 chart 会换上自己的默认标签，NetworkPolicy 随之拒绝 Colvenna Lake 的全部客户端与 Prometheus。
- **升级到一个新的候选**：检出新候选的 tag，`COLVENNA_ENV=uat deploy/install.sh --release <新候选> [--fixtures]`。各步照常收敛，镜像从发布仓库复制，不构建。同一个提交上，一套环境只能用一种方式安装：`deploy/.state/<环境>/install-mode` 记着上一次的方式，在同一提交上换一种方式会被拒绝；照上一次的方式重跑（带或不带 `--release <候选>`），或检出别的提交。`uninstall.sh` 拆完时连同这个记录一起删掉，之后可以用任一种方式从零安装。
- **改名之前装的环境**：查询引擎镜像改名（#258）与技术标识改名（#271）之前装的环境都是产品旧名字的，不能升级到现在的提交：先在它安装时的提交上卸载，再从零安装。
- 测试 MySQL 的主版本随测试源栈升级：装在 8.0 上的环境，`--fixtures` 的 `sources` 步骤把它就地升到 8.4（RDS 的主版本升级，数据保留；2026-10-21 在 `uat3` 上实测：预检查约 20 秒，停机约 2 分钟，整步 691 秒）；随后的 `source-logins` 把测试用户改为 `caching_sha2_password`。

2026-10-20 的 UAT 在第 10 节装好的环境上，以新提交 `aacb39cc` 重跑了整个 `install.sh --fixtures`：26 步都收敛为 `ok`，用时 2085 秒，之后的全部用例都在升级后的环境上运行（[UAT 执行报告](../uat/report.md)）。2026-10-21 又两次升级 `uat`：在 `0e97dd95`（区域参数化）上，第一次运行时给出 `COLVENNA_REGION=us-west-2`，补上区域记录，26 步都收敛为 `ok`，用时 2135 秒；在评审后修改的 `0f9efdba` 上再跑一次，26 步都 `ok`，用时 2184 秒；最后在 `9e6b39d0` 上跑 `--only foundation`，用时 254 秒，可用区保持 `us-west-2a`、`us-west-2b`。项目处于开发阶段，不保证新旧版本之间的兼容或回退。

**Kubernetes 版本。** 集群现在是 1.36，标准支持到 2027-08-02。1.37 已经发布（标准支持到 2027-12-01），但 CDK 还没有它的 kubectl layer。

- 升级策略是 `EXTENDED`：到期不升级时，AWS 不会只把控制面升上去（节点、插件与平台组件都没有在新版本上验证过），而是开始计 Extended Support，控制面每月多约 $365（[成本估算](cost.md)第 7 节）。基础栈建集群时设好策略，`foundation` 步骤也把已有集群改成这个策略。
- 控制面、两个节点组、kube-proxy 与 CoreDNS 托管插件的版本都写在 `deploy/aws/src/foundation-stack.ts`。升一个小版本要一起改：`kubernetesVersion`、kubectl layer（`@aws-cdk/lambda-layer-kubectl-v<小版本>`）、`deploy/scripts/foundation/install-tools.sh` 的 kubectl，以及 kube-proxy、CoreDNS 插件的版本（取 `aws eks describe-addon-versions --kubernetes-version <版本>` 的默认版本）。VPC CNI 与 EBS CSI 插件的版本要在新版本上也兼容。
- 然后在新提交上重跑 `install.sh`。`foundation` 一步里先由 CloudFormation 升控制面，再把 kube-proxy、CoreDNS 换成新版本的插件（它们可以比节点上的 kubelet 新一个小版本）；接着 `deploy.sh` 逐个升级两个节点组（`aws eks update-nodegroup-version`，换成新版本的最新 AMI），节点逐台替换，Pod 随之迁走、重启。节点组的版本不写在栈里：EKS 不允许节点组的版本更新与它的其他改动一起做，而每次部署都会改它的 `SourceSha` 标签。之后各步照常收敛。EKS 每次只能升一个小版本，跨多个版本要逐个提交、逐次升级。
- 控制面升上去以后，若同一次部署里后面的资源失败，CloudFormation 回滚时会让 EKS 把控制面版本退回（EKS 的 `VersionRollback`；2026-10-21 在 `uat` 上，1.36 约 8 分钟后退回 1.35）。回滚本身失败、栈停在 `UPDATE_ROLLBACK_FAILED` 时，先核对卡住的资源实际上还是旧的样子，再 `aws cloudformation continue-update-rollback --stack-name ColvennaLake<Env>Foundation --resources-to-skip <它们的逻辑 ID>`，修好原因后再跑 `foundation`。
- 1.36 的依据：EKS 对 `uat`（1.35）的升级检查全部通过（2026-10-21 `aws eks list-insights`：插件兼容、kubelet 与 kube-proxy 的版本差、1.37 与 1.38 将移除的 API 都没有用到）；AL2023 的 1.36 节点 AMI 已发布（`1.36.4-20261012`）；Doris Operator 25.8.0 要求 Kubernetes 1.19 以上；VPC CNI、EBS CSI、kube-proxy、CoreDNS 的固定版本都在 1.36 的插件版本列表里。Flink Kubernetes Operator 1.16.1、AWS Load Balancer Controller 3.6.0 与 Strimzi 1.2.0 没有在上游文档里找到 1.36 的说明，由下面的实测验证。
- 实测（2026-10-21，`uat`，1.35→1.36，提交 `eb3936a7`）：`foundation` 一步 1731 秒，其中控制面约 7 分钟，两个插件约半分钟，两个节点组各约 8.5 分钟；之后 `verify` 与 catalog 的 Flink、Spark、Doris 流程都通过。更早的一次尝试把节点组版本写在栈里，因为上面说的限制而失败，控制面随回滚退回了 1.35。从零装在 1.36 上的实测见第 10 节 `uat3`。

## 8. 卸载

```bash
COLVENNA_ENV=uat deploy/uninstall.sh --yes uat
```

`--yes` 后必须再写一遍环境名；区域从本地状态读。入口的 DNS 记录是全局的，所以所给区域里既没有这套环境的栈、也没有开始过的卸载时，`uninstall.sh` 在删除任何东西之前停下；通过这项检查以后它也记下区域，中断后续跑不能换区域。本地状态还没有记下托管区或入口域名的环境（#212 之前装的），同样在删除之前核对给出的托管区与入口域名，见第 1 节"账号、托管区与入口域名"。卸载会删除一切，数据也在内，什么也不保留；要留下数据，卸载之前先按第 11.5 节把它复制出来：

1. **集群里。**
   - 趁各 Operator 还在，先删声明（`Lake*`）、Flink 部署、Doris 集群与 Kafka 资源。
   - 再卸载应用的 Helm release，入口的先卸，好让 Load Balancer Controller 释放目标组绑定。
   - 然后删除命名空间，再卸载各控制器（Load Balancer Controller、Strimzi、Doris Operator 与 Flink Operator）。
   - 最后删除全部卷声明，直到集群的 EBS 卷都已删除。
2. **栈。** 先删除 `restore-metadata.sh restore --keep-instance` 留下的临时实例（`<基础栈名小写>-restore-…`，第 11.3 节）：它们在基础栈的子网与安全组里，会让基础栈删不掉。然后依次删除入口、开放层、Operator、catalog、两个测试源和基础栈。
3. **栈有意保留或留下的资源**，逐一删除：
   - 仓库桶、审计日志桶与入口的访问日志桶，先关掉它们的访问日志，再清空；日志投递有延迟，清空后又写进对象的桶会再清空一次；
   - 各 RDS 实例名下的全部手动快照，最终快照也在内（第 11.5 节）；
   - Cognito 用户池，先关闭删除保护；
   - 日志组，包括 EKS、RDS 与 CDK Lambda 的；
   - 密钥，跳过恢复期；
   - 集群的 OIDC provider；
   - 入口的 DNS 记录，含证书校验记录；
   - 仍然存在的 EBS 卷。
4. **核对。** 检查账号里不再有本环境的资源：栈、IAM 角色、ECR 仓库、桶、数据库、快照、密钥、日志组、用户池、卷、DNS 记录，以及带 `Environment=<环境>` 标签的资源。
   - 2026-10-20 实测：资源删除后，标签索引还会把它列出一段时间，已删的 NAT 网关约一小时。所以对标签索引列出的每个资源都逐个再查一次，只有确实还在的才算残留。
   - 最多核对三次，每次间隔 2 分钟（等标签索引与密钥的删除落定）；仍有残留就列进 `leftovers.txt`，并以失败退出。

证据保存在 `UNINSTALL_EVIDENCE`，默认是 `deploy/.state/<环境>/uninstall-<UTC 时间>/`。卸载中途失败后可以直接重跑：要删的资源记在 `deploy/.state/<环境>/uninstall.noted`，栈删掉以后也找得到，核对通过后才删除这个文件。

新目录结构下的卸载于 2026-10-21 拆除 `uat2` 时首次实测；此前同一流程在旧目录结构上跑过一次。两次的结果都见第 10 节。

## 9. 故障排查

| 步骤或现象 | 原因与处理 |
| --- | --- |
| 删除基础栈时 VPC 删不掉 | 2026-10-20 实测：GuardDuty 在 VPC 里自动建了 `guardduty-data` 接口端点和 `GuardDutyManagedSecurityGroup`，它们占着子网。`uninstall.sh` 只删除该 VPC 内带 `GuardDutyManaged=true` 标签的端点与安全组，然后重试；不要关闭账号级的 GuardDuty |
| `verify` 报 "unlabelled namespace is blocked by NetworkPolicy expected=000 actual=200" | 集群没有执行 NetworkPolicy，通常是基础栈还没有 VPC CNI 托管插件。重跑 `--only foundation`，确认 `aws-node` 的参数为 `--enable-network-policy=true`，再跑 `--from verify` |
| `verify` 报 catalog-schema 不符 | 数据库的 schema 版本与当前提交固定的 Colvenna Catalog 版本（`deploy/images/colvenna-catalog.json` 的 `schema_version`）不一致，例如换了提交却没重跑 `catalog`。从 `catalog` 重跑 |
| `install.sh` 开始时报 "No Colvenna Catalog … for an anonymous reader in ghcr.io/colvenna: … HTTP 403 DENIED"，或 `catalog` 步骤报 "Cannot read ghcr.io/colvenna/…" | 固定的版本在 GitHub Container Registry 里还没有公开（Colvenna Catalog 公开之前都是这样），或网络到不了 `ghcr.io`。给 `COLVENNA_CATALOG_REGISTRY=<仓库地址>[/<前缀>]`，指向保存着同样 digest 的仓库，例如维护者的私有发布仓库 |
| `install.sh` 开始时报 "COLVENNA_CATALOG_REGISTRY: … holds no …"、"… HTTP 404 …: not there"，或 `catalog` 步骤报 "MANIFEST_UNKNOWN" | `COLVENNA_CATALOG_REGISTRY` 指的仓库里没有固定的那个 digest：前缀不对、那里是另一个版本，或者同名的 tag 下是重新构建的另一个 digest。按 digest 取，所以不会改用它；给出保存着同样 digest 的仓库 |
| 报 "COLVENNA_CATALOG_REGISTRY='…': a registry and prefix …" 或 "… an ECR registry is …" | 格式不对：只写仓库地址与前缀，不带 `https://`、末尾的 `/`、tag 或 digest；ECR 仓库要在标准区域 |
| `catalog` 步骤报 "not the pinned catalog release …'s" | 按 digest 取到的镜像或 chart 不是固定版本的源码提交构建的（标签或注解不符），或平台不是 linux/amd64。什么都没有复制，不要安装；核对 `deploy/images/colvenna-catalog.json` 与来源仓库 |
| `verify` 或 `check-health.sh` 报 `metadata-database FAIL` | 项目 RDS 实例不在照常接受连接的状态。细节的第一个值是当时的状态，例如 `stopped 16.13 False 1`。下面这些状态判为正常：`available`；每天自动备份时的 `backing-up`；不停机的配置变更 `configuring-enhanced-monitoring`、`configuring-iam-database-auth`、`configuring-log-exports`、`resetting-master-credentials`；后台的存储处理 `storage-optimization`、`storage-initialization`（#237 之前只认 `available`，碰上备份窗口就误报）。其余状态都判为失败：`stopped`、`stopping`、`starting`、`rebooting`、`modifying`、`upgrading`、`maintenance` 等，是实例被停了，或 RDS 正在做可能停机的变更，等它回到 `available` 后用 `--from verify` 重跑。存储自动扩容（上限 100 GiB）也会让实例短暂处于 `modifying`，这时数据库照常可用，但检查仍判失败，同样等它结束后重跑；`storage-full`、`failed`、`inaccessible-*`、`incompatible-*` 等要先按实例的 RDS 事件（`aws rds describe-events --source-type db-instance --source-identifier <实例名>`）处理 |
| 任何脚本报 "COLVENNA_REGION=&lt;region&gt;: environment … standard AWS region" | 新环境没有给区域，或给的不是标准区域。第一次运行 `install.sh` 时给 `COLVENNA_REGION`，见第 1 节"区域" |
| 报 "Environment … is in …, not …" | 给的 `COLVENNA_REGION` 与本地状态记下的不同。环境不能换区域；要在另一个区域装，用另一个环境名。若记下的区域是给错的，而那个区域里什么都没装，删掉 `deploy/.state/<环境>/region` 再给对的区域 |
| 任何脚本报 "COLVENNA_ACCOUNT=&lt;account&gt;: …" 或 "COLVENNA_HOSTED_ZONE_ID=&lt;Z...&gt; COLVENNA_HOSTED_ZONE_NAME=&lt;name&gt;: …" | 新环境没有给账号或托管区，或格式不对。第一次运行 `install.sh` 时给出，见第 1 节"账号、托管区与入口域名" |
| 报 "The credentials are of AWS account …, not …" | 当前凭证属于另一个账号。设好 `AWS_PROFILE`（或默认凭证链），使它属于 `COLVENNA_ACCOUNT` |
| CDK 步骤（`foundation`、`catalog-stack`、`operator-stack`、`open-stack`、`entry-stack`，以及 `--fixtures` 的 `sources`）报 "The CDK CLI resolved no AWS account from the credentials …"；`v1.0.0-rc.1` 报的是 "The credentials are of account undefined, not -c account=…" | CDK CLI 启动时向 STS 问一次凭证属于哪个账号，把答案作为 `CDK_DEFAULT_ACCOUNT` 交给 CDK 应用；应用只在它等于 `-c account`（`COLVENNA_ACCOUNT`）时合成，所以栈不会落到别的账号。执行机负载很高时，经实例元数据取凭证或问 STS 可能没有及时回答，CLI 这一次就没有账号，而且整次运行都没有：即使替它把 `COLVENNA_ACCOUNT` 交给应用，随后的部署也会以 "Need to perform AWS calls for account …, but no credentials have been configured" 停下，应用的核对也成了拿账号核对它自己。所以安装器不替 CLI 补账号，而是由 `cdk_deploy`（`deploy/scripts/lib/cdk.sh`）重跑这一次 `cdk deploy`：只有这条报错才重跑，最多共 3 次、间隔 30 秒（`CDK_DEPLOY_ATTEMPTS`、`CDK_DEPLOY_RETRY_WAIT`），每次都由 CLI 重新解析、应用重新核对；调用方环境里的 `CDK_DEFAULT_ACCOUNT`、`CDK_DEFAULT_REGION` 不传给 CLI。报这条错时什么都还没部署，日志里有一行 `cdk_deploy <栈>: … (run 1 of 3), nothing deployed; running it again in 30s (issue #233)`。3 次都失败会报 "… in 3 runs; check that they answer"：用 `aws sts get-caller-identity` 确认凭证能用（负载高时等它降下来），再 `--from <这一步>` 续装。"The credentials are of account <另一个账号>, not -c account=…" 不重跑：凭证属于另一个账号，按上一行处理。见 #233 |
| 报 "Cannot read hosted zone …"、"Hosted zone … is private" 或 "Hosted zone … is …, not COLVENNA_HOSTED_ZONE_NAME …" | 托管区不在这个账号里（或读不到）、是私有区，或名字与 `COLVENNA_HOSTED_ZONE_NAME` 不符。入口需要该账号里的公有托管区 |
| 报 "COLVENNA_ENTRY_DOMAIN … is not free"、"… is under …, another environment's entry" 或 "… overlaps environment …" | 新环境的入口域名已有记录、落在另一套环境的入口之下，或与本工作区记下的另一套环境的入口域名相同、相互包含。卸载会删掉入口域名之下的全部记录，换一个只属于这套环境的 `COLVENNA_ENTRY_DOMAIN` |
| 报 "Environment …'s entry stack serves …, not …" | #212 之前装的环境第一次给设置时（`install.sh`，或直接 `uninstall.sh`），给的入口域名与它入口栈用的不同。按提示给出那个域名 |
| 报 "Account … has not CDK-bootstrapped …" 或 "The CDK bootstrap of … is version …" | 这个区域没有做过 CDK bootstrap，或版本低于 6。按提示运行 `(cd deploy/aws && npm ci && npx cdk bootstrap aws://<账号>/<区域> --app '')`，再重跑 `install.sh`；见第 2 节"账号与身份" |
| 报 "Cannot read the CDK bootstrap version of …" | 读不到 SSM 参数 `/cdk-bootstrap/hnb659fds/version`，又不是"参数不存在"：没有 `ssm:GetParameter` 权限、调用被限流，或凭证过期。处理好后重跑 |
| 报 "DORIS_IMAGE_SOURCE must be an ECR repository in a standard region, …" | 给的 `DORIS_IMAGE_SOURCE` 不是 `<账号>.dkr.ecr.<区域>.amazonaws.com/<仓库>`，或区域不是标准区域。改正或不给它（两个镜像已在本环境仓库里时可以不给） |
| 报 "No source of the query engine's images (based on Apache Doris 4.1.4)" 或 "… does not hold the query engine's …" | 从源码安装，而 `DORIS_IMAGE_SOURCE` 没给，`COLVENNA_RELEASE_REGISTRY` 也没给（或工作区里没有固定这两个 digest、仓库是 `colvenna-lake-release/query-engine` 的 schema 2 起的发布清单，下一个候选打出之前都没有）；或者给的仓库里没有这两个 digest、当前凭证读不到。给一个有这两个 digest 的仓库：发布仓库的 `colvenna-lake-release/query-engine` 或已装好的环境的 `colvenna-lake-<env>/query-engine`；或者给出发布仓库，工作区里要有固定这两个 digest 的 schema 2 起的清单 |
| `install.sh --release` 报 "COLVENNA_RELEASE_REGISTRY=&lt;account&gt;.dkr.ecr.&lt;region&gt;.amazonaws.com: …" | 没有给发布仓库，或格式不对。清单从 `v1.0.0-rc.2` 起不写仓库地址：每次按候选安装（包括 `--from`、`--only` 重跑）都给出保存这个候选的发布仓库 |
| `third-party` 步骤报 "MANIFEST_UNKNOWN" 或 "… has digest …, not …" | 上游已经删掉 `deploy/images/third-party.tsv` 固定的那个 digest（quay.io 重建 tag 后常见），发布仓库里也没有。给出保存着它的发布仓库（`COLVENNA_RELEASE_REGISTRY`），或在新提交里把那一行重新固定到上游当前的 digest（[第三方镜像](../../release/README.md#第三方镜像)） |
| 报 "Environment … has COLVENNA_…=…, not …" | 给的值与本地状态记下的不同。账号、托管区与入口域名在环境的一生中不变；要换，用另一个环境名 |
| 任何脚本报 "Cannot read the caller's account in … (AWS STS)" | 第一项检查在这个区域问不到当前凭证属于哪个账号：区域没有端点、账号没有开通这个区域，或凭证过期。换一个区域，或处理好凭证后重跑 |
| `install.sh`、`uninstall.sh` 或 `foundation` 报 "Cannot read … in …" | 读不到基础栈（或 #212 之前装的环境的入口栈），又不是"栈不存在"：账号没有开通这个区域、调用被限流、没有权限，或凭证过期。处理好后重跑 |
| `foundation` 报 "No region recorded for …" | 单独运行了 `deploy.sh`，而这套环境的区域还没记下。改用 `install.sh --only foundation` |
| `install.sh` 报 "… IAM roles of its name exist (…): another region holds it, or an uninstall did not finish" | 这个环境名已被另一个区域的环境占用（IAM 角色是全局的），或者上次卸载没有完成。换一个名字，或在那套环境的区域里拆掉它、重跑没完成的卸载 |
| Helm 步骤报 "another operation (install/upgrade/rollback) is in progress" | 上一次运行在 Helm 操作进行中被打断，release 停在 `pending-*`。从本工作区重跑那一步即可：`helm_upgrade`（`deploy/scripts/lib/helm.sh`）先恢复它，日志有一行 `helm_upgrade: <命名空间>/<release>: revision N pending-…, left by this workspace's killed run; rolling back to revision M`，从未成功过的 `pending-install` 则是 `… uninstalled …, to install it again`。判断的依据：本工作区对这个 release 的每次 Helm 操作都持有 `deploy/.state/<环境>/helm-<命名空间>-<release>.lock`（它启动的 helm 也持有，helm 退出之前锁不会放开），并在 `.lock.run` 里记下这次要开始的 revision 与 `running`，结束时改为 `finished`。拿到锁时记录仍是 `running`，说明那次运行被杀掉了，它的 helm 也已不在；只有停住的 revision 正是这条记录里的那个，才自动恢复 |
| Helm 步骤报 "helm_upgrade: … left alone: revision N is pending-… and not this workspace's" | release 停在 `pending-*`，但那个 revision 不是本工作区被打断的运行开始的：可能是另一个工作区、或有人手动运行的 helm，也可能还在运行，所以不自动处理，也不按时间猜。锁只在一个工作区内有效：两个工作区（worktree）对同一套环境运行安装不会互相等待，所以一套环境同一时间只从一个工作区安装（共享环境用 `uat.lock` 这样的约定来排队）。确认没有别的 Helm 操作在跑之后，按提示手动恢复（`helm -n <命名空间> rollback <release> <最后一个 deployed 的 revision>`，从未成功的则 `helm -n <命名空间> uninstall <release>`），再重跑这一步 |
| `uninstall.sh` 报 "No stack of … in …, and no uninstall begun" | 所给区域里没有这套环境。检查 `COLVENNA_REGION` 与 `deploy/.state/<环境>/region` |
| `install.sh` 或 `foundation` 报 "No two availability zones of … offer both node types, …: N of M do (…)" | 所选区域没有两个可用区同时提供两种节点类型，括号里列出每个可用区缺的类型。换一个区域 |
| 报 "The VPC … is in … availability zones of …" | 已有环境的 VPC 所在的可用区不再提供某种节点类型，或 VPC 不是两个可用区。环境不能换可用区；用另一个环境名在别的区域装 |
| 报 "EKS in … does not offer Kubernetes …, the foundation's" | 这个区域还没有（或已不再提供）`foundation-spec.json` 的 Kubernetes 版本。换一个区域 |
| 报 "… has N VPCs of its quota of Q …" 或 "… has N Elastic IPs of its quota of Q …" | 新环境在这个区域放不下：VPC 要 1 个、弹性 IP 要 2 个。拆掉不用的环境、释放未用的弹性 IP，或在 Service Quotas 里提高配额 |
| `flink-image` 报部署的栈与镜像的 SHA 不符 | 基础栈是在另一个提交上部署的。在当前提交上重跑 `foundation` |
| `doris` 的日志里有 "No colvenna-doris-be StatefulSet yet: touched DorisCluster colvenna-doris …" | FE 就绪后，Doris Operator 有时不再协调，BE 建不出来（Doris Operator 25.8.0 的已知问题，[apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495)）。`install-doris.sh` 等 BE 时每 10 秒给 DorisCluster 打一次注解，促它重新协调，BE 随后建出即正常。15 分钟后仍报 "Doris be did not become ready"，查 FE 是否就绪和 Doris Operator 的日志，再从 `doris` 重跑 |
| `doris-image` 报找不到来源镜像 | 来源仓库在安装前的检查之后被删除。把 `DORIS_IMAGE_SOURCE` 设为一个有这两个 digest 的仓库（发布仓库的 `colvenna-lake-release/query-engine`，或别的环境的仓库），再 `--from doris-image`；没有这样的仓库时，见第 2 节"查询引擎镜像" |
| `entry-stack` 长时间停在证书上 | ACM 证书靠托管区里的 DNS 记录自动校验，栈会一直等到校验通过。确认托管区是公有区，且它的域名已从上级域名委派过来、能从公网解析 |
| `console` 或 `bi` 的目标一直不健康 | 目标组只有在 Pod Ready 后才报健康，而应用 Pod 的 NetworkPolicy 只放行公网子网的地址段。先查 Pod 是否 Ready，再查 `kubectl get targetgroupbindings -A` 与 Load Balancer Controller 的日志 |
| 换了网络后 `kubectl` 连不上集群 | EKS 端点只放行部署时的出口地址。重跑 `foundation`，它会把端点收窄到新的地址 |
| 节点在 00:00 UTC 前后重启 | 账号的自动化会给新实例打 `Patch Group=DEV` 标签，维护窗口每天 00:00 UTC 对这类实例执行补丁并按需重启（见 [M1 基线报告](../history/m1/capacity-baseline.md#运维发现)）。2026-10-20 核对，`uat` 的 4 个节点都带这个标签。长时间的作业与验收测试要避开这个时段 |

## 10. 实测：从零安装与拆除

> **维护者的执行机（记录）。** 下面的实测都在维护者的执行机上运行。那里的 shell 默认导出 `AWS_PROFILE=bedrock`，属于另一个账号；不导出任何 profile 时，默认凭证链是这台主机的 EC2 实例角色。所以在那里每条命令之前都先 `export AWS_PROFILE=work`（2026-10-22 实测：用默认的 profile，`install.sh` 在第一项检查就拒绝了）。

下面的实测都在维护者的工作账号 `<ACCOUNT_ID>`（`COLVENNA_ACCOUNT`）里，入口用托管区 `<COLVENNA_HOSTED_ZONE_NAME>`（`COLVENNA_HOSTED_ZONE_NAME`）。具体的值只记在执行机未提交的设置里（`deploy/.state/<环境>/`）。2026-10-22 之前的几次（`uat`、`uat2`、`uat3`）早于 #212：当时账号、托管区与 AWS profile `work` 写在脚本里；之后的安装经第 1 节的变量给出同样的值。

### `uat`：`us-west-2`，2026-10-20

2026-10-20，在工作账号里按本手册从零建了 `COLVENNA_ENV=uat`（`deploy/install.sh --fixtures`），身份为执行机的 EC2 实例角色，安装主机是 aarch64。证据保存在安装主机上，未入库。

**安装。**

- 从提交 `d96bb559` 开始安装。前 25 步一次通过，合计 4619 秒（约 77 分钟），各步耗时见第 4 节。
- `doris-image` 复制出 FE `sha256:4608a0cb…`、BE `sha256:956817fd…`，Doris 的 Pod 运行的就是这两个镜像。
- `verify` 先后暴露两个问题，修复后续装通过：
  1. `verify-catalog.sh` 用 `ENGINE_NAMESPACE` 作客户端命名空间，而这个变量只有验收测试才定义。改为检查时自建、检查后删除自己的命名空间（`550bd749`）。
  2. **新集群没有执行 NetworkPolicy。** VPC CNI 的 agent 以 `--enable-network-policy=false` 运行，未打标签的命名空间能连到 colvenna-catalog。开发环境此前是手工打开的，从零安装从来没有打开过。修复后，基础栈以 `vpc-cni` 托管插件（`v1.22.4-eksbuild.3`）打开 `enableNetworkPolicy`（`4879c990`）。
- 在 `4879c990` 上重跑 `--only foundation`，用时 265 秒，之后 `aws-node` 为 `--enable-network-policy=true`、4/4 就绪。再跑 `--from verify`，用时 177 秒，通过：
  - colvenna-catalog 两个副本分布在两个节点上；数据库 schema 版本为 2，与这个提交的迁移数一致；
  - 14 项访问检查全部符合预期：
    - 无 token、伪造、过期、撤销、停用主体的 token 都得到 401；
    - 服务身份调用管理 API 得到 403；
    - 明文 HTTP 得不到响应；
    - 健康端口只在 Pod 的 127.0.0.1 上可达；
    - 未打标签的命名空间被拦截；
  - Operator 已就绪；
  - console 与 bi 对未登录请求都 302 到 `https://colvenna-lake-uat-<ACCOUNT_ID>.auth.us-west-2.amazoncognito.com`；
  - 随后用 `kubectl get pods -A` 核对，38 个 Pod 全部 Running 或 Completed。
- 按配方重新构建了一次 Doris 镜像，以作核对（`deploy/scripts/doris/build-images.sh`，`4879c990`）。新镜像是 FE `sha256:2d62da37…`、BE `sha256:2d2a92d8…`；它们的读取器与两个固定镜像里的逐个 jar 比对，SHA-256 全部一致。

**拆除。**

- `uat` 保留，供日后使用，没有拆除。新目录结构下的拆除在 `uat2` 上实测，见下一小节。
- 此前用旧目录结构的同一套流程拆过一套旧命名的 `uat`：逐个核实，资源都已删除；但残留检查把标签索引里已删除的 NAT 网关、VPC、endpoint 与用户池当成了残留，脚本以失败退出。第 8 节所说的逐个再查，就是因此加上的。

### `uat2`：`us-east-2`，2026-10-21

在第二个标准区域从零建了 `COLVENNA_ENV=uat2`，验证一套环境的区域不是写死的。身份与安装主机同上，证据同样未入库。

**安装。**

- 在提交 `0e97dd95` 上运行 `COLVENNA_REGION=us-east-2 deploy/install.sh --fixtures`。`DORIS_IMAGE_SOURCE` 设为 `uat` 当时在 `us-west-2` 的查询引擎仓库（那时还叫 `doris`，前缀是产品的旧名），`doris-image` 跨区域复制了那两个固定 digest。
- 26 步一次通过，合计 4705 秒（约 78 分钟）。最长的两步是 `sources`（1309 秒，建两个测试源 RDS）与 `foundation`（1150 秒）。
- 基础栈选的可用区是 `us-east-2a`、`us-east-2b`。
- `verify` 通过：
  - 14 项访问检查全部符合预期；
  - console 与 bi 对未登录请求都 302 到 `https://colvenna-lake-uat2-<ACCOUNT_ID>.auth.us-east-2.amazoncognito.com`。
- 4 台节点同样被账号的自动化打上了 `Patch Group=DEV`（见第 9 节）。

**验收。**

- 用 `tests/acceptance/run-uat.sh` 与 `rerun-uat.sh` 跑了与区域有关的用例：
  - catalog 阶段的 9 项：Flink、Spark、Doris、session 作业、数据 token、授权、访问检查、健康检查与日志；
  - CDC；
  - 经 Polaris 的 Iceberg 发布，事先装好开发用 Kafka；
  - BI；
  - Web 入口阶段。
- 17 次运行都以 0 退出，297 行 PASS，没有 FAIL，用时约 2 小时 20 分钟。

**拆除。**

- 验收之后，按第 8 节运行 `COLVENNA_ENV=uat2 deploy/uninstall.sh --yes uat2`（提交 `0e97dd95`）。用时约 49 分钟，以 0 退出。这是新目录结构下第一次实测卸载。
- 删除 7 个栈约 46 分钟，其中基础栈约 30 分钟。这次没有遇到 GuardDuty 的接口端点（见第 9 节）。
- 随后删除了栈留下的资源：两个桶（先清空）、三个 RDS 最终快照、`us-east-2` 的 Cognito 用户池、14 个日志组，以及证书校验的 DNS 记录。
- 第一次核对就没有发现残留，`leftovers.txt` 为空。本地状态里的 `region` 与 `uninstall.noted` 随之删除，这个名字可以在任何区域再用。
- 评审后加的几处保护，在 `0f9efdba` 与 `9e6b39d0` 上用不会改动资源的命令实测过：
  - 给 `uat` 一个别的区域，`install.sh` 报出那 26 个 IAM 角色后停下，也不记下区域；
  - 给中国区或 GovCloud 的区域，脚本直接拒绝；
  - 给一个账号没有开通的区域（`ap-south-2`），`install.sh` 报读不到基础栈后停下，不记下区域；
  - 区域还没记下时单独运行 `foundation/deploy.sh`，它要求经 `install.sh` 部署；
  - 一套只记了区域、什么都没装的环境，`uninstall.sh` 换区域时报区域不符，不换区域时报 "No stack of … and no uninstall begun"，都在删除任何东西之前停下。

### `uat3`：`us-east-2`，2026-10-21，EKS 1.36

在 EKS 1.36 上从零建了 `COLVENNA_ENV=uat3`，验证 #178 的新版本，也供 #177 在上面把测试 MySQL 从 8.0 就地升到 8.4。身份与安装主机同上，证据未入库。

**安装。**

- 在提交 `b4ee4943` 上运行 `COLVENNA_REGION=us-east-2 deploy/install.sh --fixtures`，`DORIS_IMAGE_SOURCE` 同 `uat2`。
- 第一次运行停在 `catalog`：安装主机的 `JAVA_HOME` 指向一个不存在的 JDK，Maven 起不来。设好 `JAVA_HOME` 后，用 `--from catalog` 接着装完。两段合计 26 步都是 `ok`，共 6292 秒。前一段 2759 秒，其中 `foundation` 1255 秒、`sources` 1311 秒；后一段 3533 秒，主机负载很高，`bi` 镜像一步用了 1066 秒。
- 集群是 1.36、升级策略 `EXTENDED`；两个节点组是 1.36（AMI `1.36.4-20261012`）；VPC CNI、EBS CSI、kube-proxy、CoreDNS 四个插件都是 ACTIVE。`foundation` 打印 "Kubernetes 1.36 (EXTENDED): standard support ends 2027-08-02"。
- EKS 控制面日志组与 5 个 RDS 日志组都保留 365 天：日志组还不存在的新集群上，基础栈也设上了（#179）。
- `verify` 通过，14 项访问检查都符合预期。

**验收。** 在这套环境上跑了 catalog 的 Flink 流程，并在测试 MySQL 就地升到 8.4 之后，跑了 UAT-SRC-01、UAT-TBL-01、UAT-TSK-01、UAT-SRC-04 与 UAT-GOV-02（见 #177）。

### `plat`：`us-east-2`，2026-10-22，只装平台（第一轮）

在第二个标准区域从零建了一套不带 `--fixtures` 的环境 `COLVENNA_ENV=plat`，验证默认的只装平台路径与第 11 节的元数据恢复，然后拆除（#205）。身份与安装主机同上，证据未入库。这一轮用的是评审之前的脚本，评审后的脚本在第二轮重新验证，见下一小节。

**安装。**

- 在提交 `fc9407f8` 上运行 `COLVENNA_ENV=plat COLVENNA_REGION=us-east-2 deploy/install.sh`，不带 `--fixtures`。
- 第一次运行停在 `doris-image`：没有设 `DORIS_IMAGE_SOURCE`，脚本到本区域找开发环境的 `colvenna-lake/m1-doris`，而 `us-east-2` 没有这个仓库（见第 2 节"查询引擎镜像"）。把它设为 `uat` 的仓库后，以 `--from doris-image` 续装完成。
- 两段合计：22 个平台步骤都是 `ok`，共 3336 秒（约 56 分钟），其中 `foundation` 1182 秒、`doris` 313 秒、`entry-stack` 239 秒；4 个夹具步骤（`sources`、`strimzi`、`source-logins`、`readers`）都记为 `skipped (no --fixtures)`。
- `verify` 通过：colvenna-catalog 两个副本在两个节点上，schema 版本为 2；14 项访问检查都符合预期；console 与 bi 对未登录的请求都 302 到 `https://colvenna-lake-plat-<账号>.auth.us-east-2.amazoncognito.com`。
- 平台检查的 7 项都通过（当时的 `tests/acceptance/deploy/check-platform-only.sh`；它的检查后来并入 `check-install.sh --platform`，即 UAT-DEP-02）：平台步骤都 `ok`、夹具步骤都跳过；`verify` 通过；没有测试源的栈，也没有它们的密钥；没有 Strimzi 的 release、命名空间或 CRD；读端镜像仓库是空的，也没有它的记录；受管命名空间里没有测试源的登录。

**元数据恢复。** `tests/acceptance/catalog/run-rds-restore.sh`，用的是评审之前的恢复脚本与演练脚本（git blob `1c3cac70cfe8`、`e4a61fe3b95c`；当时检出的分支提交已因变基不复存在），镜像仍是 `fc9407f8` 的：

- 恢复点之前，写入 t1（10 次提交）、t2（5 次）与 gone（3 次）；以项目 RDS 的手动快照作恢复点；之后 t1 再提交 1 次，新建 t3（5 次），删掉 gone。
- `restore --snapshot` 共 775 秒：临时实例约 7.8 分钟后可用，导出 4 秒，`before-restore` 快照约 2.9 分钟，colvenna-catalog 停止 12 秒（`catalog-stopped-seconds.txt`：从缩到 0 到重新就绪），删除临时实例约 1.8 分钟。
- 恢复之后：catalog 列出的表与恢复点相同（gone、t1、t2）；t1、t2 的状态（行数、行校验和、各数值列之和、最新快照与 schema）以及 15 个快照逐个的读取结果都与恢复点相同。`check` 报告 t1 为 `behind`（快照文件 11 超过了指针 10），t2 为 `consistent`，gone 为 `missing`，t3 为 `new`。
- `reconcile --unregister-missing` 之后：t1 的指针 10→11，t3 重新登记，gone 去掉；三张表都是 `consistent`，列出的表、状态与 21 个快照的读取结果都与恢复之前相同，t1 与 t3 都能再写入。上一次演练留下的三张表也在这个 catalog 里，恢复点晚于它们的创建，恢复后同样 `consistent`。
- 挂接：把 t2 的目录复制到另一个库的路径下，以复制开始的时间为恢复点运行 `reconcile`，库被建出、表被登记，结果为 `consistent`。删掉原来的 t2 及其文件之后，复制出来的表，状态与 5 个快照的读取结果都与原表相同。
- 17 项检查全部通过。临时实例、恢复点快照与 `before-restore` 快照都已删除。
- 第一次演练（同样的步骤）里，两项"删表后目录为空"的检查误报失败：脚本读列表结果的 `KeyCount`，而 AWS CLI 分页合并之后的结果里没有这个字段；目录其实已经空了。其余 15 项通过。修正之后再跑，就是上面的结果。

**留下数据与拆除。**

- 卸载之前，按第 11.5 节做了手动快照 `plat-keep`，并复制了两份：一份在同一区域，一份在 `us-west-2`。两份副本都仍记着原来的实例名。
- 运行 `COLVENNA_ENV=plat deploy/uninstall.sh --yes plat`：删除 5 个栈约 29 分钟，其中基础栈约 25 分钟；随后删掉 3 个桶、Cognito 用户池、13 个日志组，以及 RDS 的最终快照、`plat-keep` 与同一区域的那份副本。
- 第一次运行以失败退出：残留核对三次都列出 VPC 流日志 `fl-0034c535…`。流日志其实已随 VPC 删除，`describe-flow-logs` 返回空列表，是标签索引还留着它；而核对对标签索引列出的流日志不逐个再查（流日志是 #196 加的，此后还没有实测过卸载）。修正之后（流日志改为逐个再查，即本次合并的 `uninstall.sh` 里的做法）续跑，用时约 1 分钟，以 0 退出，没有残留；本地状态里的 `region` 与 `uninstall.noted` 随之删除。
- `us-west-2` 的那份副本仍在，卸载没有动它，核对后已手工删除。`us-west-2` 的 `uat` 各栈卸载前后没有变化；`us-east-2` 只少了 `plat` 的栈。

### `plat`：`us-east-2`，2026-10-22，只装平台（第二轮）

按评审修改了恢复脚本与卸载脚本之后，在 `us-east-2` 再从零建了一次 `plat`，用评审后的脚本重新演练，然后拆除。身份与安装主机同上，证据未入库。安装与卸载在本分支变基之前的一个提交上运行（当时的 `install.sh`、`uninstall.sh` 为 git blob `112fc1c619de`、`b09114869b82`，之后主干上别的 issue 又改过它们）；恢复脚本与演练脚本就是本次合并的版本（git blob `73c4ac0abb97`、`4bc71643c523`）。

**安装。** 在上述提交上运行 `COLVENNA_ENV=plat COLVENNA_REGION=us-east-2 deploy/install.sh`，这次一开始就设了 `DORIS_IMAGE_SOURCE`（`uat` 的仓库）。一次通过：22 个平台步骤都是 `ok`，共 3667 秒（约 61 分钟），4 个夹具步骤都跳过；`verify` 通过，14 项访问检查都符合预期。平台检查（当时的 `check-platform-only.sh`）的 7 项都通过。

**拒绝。** 下面四种 `restore` 都在改动任何东西之前停下，没有建临时实例：不带 `--yes`；`--yes` 后写了别的环境名；`--latest` 不带 `--yes`；`--snapshot` 给的是账号里另一个项目的 RDS 实例的快照（报 "is of …, not of plat's project database"）。

**从快照恢复。** `run-rds-restore.sh <运行 id> <证据目录> snapshot`（恢复脚本与演练脚本即本次合并的版本；按时间点恢复也一样），17 项检查全部通过：

- `restore --snapshot` 共 706 秒：临时实例约 6.7 分钟后可用；colvenna-catalog 停止 9 秒后 Pod 全部退出，随后 `before-restore` 快照约 2.9 分钟，替换与重新启动约 9 秒，colvenna-catalog 共停止 192 秒；删除临时实例约 1.4 分钟。
- 其余结果与第一轮相同：恢复之后列出的表、t1 与 t2 的状态和 15 个快照都与恢复点相同，`check` 分出 `behind`、`consistent`、`missing`、`new` 各一张表；reconcile 之后与恢复之前相同、能再写入；复制出来的表在原表删掉之后读出相同。
- 恢复点快照与 `before-restore` 快照都由演练自己删除。

**按时间点恢复。** `run-rds-restore.sh <运行 id> <证据目录> time`，以自动备份恢复到恢复点那一秒（`--time 2026-10-22T10:48:06Z`），并加了 `--keep-instance`，17 项检查全部通过：

- 恢复点之后约 4.5 分钟，RDS 最近能恢复到的时刻（10:48:11）已经越过恢复点，于是开始恢复。当时的可恢复区间从 09:10:39（实例建好之后的第一次备份）起。
- `restore --time` 共 845 秒：临时实例约 10.6 分钟后可用，比从快照恢复慢约 4 分钟；colvenna-catalog 共停止 189 秒。临时实例按 `--keep-instance` 保留。
- 恢复之后与 reconcile 之后的结果与从快照恢复相同。上一次演练的三张表建在这个恢复点之前，恢复后同样 `consistent`。
- `--latest` 与 `--time` 调的是同一个 RDS 接口，只是恢复时刻由 RDS 取最近可恢复的那一刻，没有单独演练。

**拆除。** 运行 `COLVENNA_ENV=plat deploy/uninstall.sh --yes plat`（同一提交），用时约 36 分钟，以 0 退出：

- 删栈之前，先删除了 `--keep-instance` 留下的临时实例，约 3.5 分钟；
- 删除 5 个栈约 29 分钟；随后删掉 3 个桶、RDS 的最终快照、Cognito 用户池与 13 个日志组；
- 第一次核对就没有残留，`leftovers.txt` 为空；本地状态里的 `region` 与 `uninstall.noted` 随之删除；
- `us-west-2` 的 `uat` 各栈卸载前后没有变化，`us-east-2` 只少了 `plat` 的栈。

## 11. 维护与恢复

本节说明一套环境的数据保存在哪里、项目 RDS 怎样备份与恢复、仓库桶怎样保留、卸载时怎样留下数据，以及怎样重建一套环境。元数据的恢复在 `plat` 上实测过，见第 10 节。

### 11.1 数据在哪里

| 数据 | 位置 | 平台怎样保存 |
| --- | --- | --- |
| 表的数据，以及 Paimon 自己的元数据文件（快照、manifest、schema、tag、分支） | 仓库桶的 `warehouse/<库>.db/<表>/` | 见第 11.4 节。表内保留多少历史，由快照过期与有界保留决定 |
| colvenna-catalog 的元数据：库与表、表的路径、每个分支发布到的快照（指针）、principal 与 token（只存哈希）、授权、审计记录、提交意图 | 项目 RDS 的 `colvenna_catalog` 库 | RDS 自动备份与手动快照，见第 11.2 节 |
| Polaris（Iceberg 发布的目录）、Marquez（血缘）、Superset（BI 的看板与图表） | 项目 RDS 的 `polaris`、`marquez`、`superset` 库 | 同上，但第 11.3 节的恢复流程只恢复 `colvenna_catalog` |
| Flink 作业的 checkpoint 与 savepoint | 仓库桶的 `flink-state/<命名空间>/` | 同仓库桶 |
| 声明（`Lake*`）与 Secret | 集群 | 平台不另做备份。声明请保存在你自己的版本库里 |
| 仓库桶的访问日志、VPC 流日志、入口的访问日志 | 审计日志桶、入口的访问日志桶 | 365 天后过期 |

### 11.2 项目 RDS 的备份

- **自动备份。** 基础栈给项目 RDS 设了 1 天的备份保留期（`deploy/aws/src/foundation-stack.ts` 的 `backupRetention`）。RDS 每天做一次快照，并持续保存事务日志，所以可以恢复到保留期内的任一时刻；最近能恢复到的时刻（`LatestRestorableTime`）通常在当前时刻之前 5 分钟以内。1 天是开发环境的设置，生产环境请按恢复的要求调整（RDS 最长 35 天）。
- **手动快照。** 运行下面的命令，脚本等快照可用后打印它的名字与时间：

  ```bash
  COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh snapshot <快照名>
  ```

  手动快照不随保留期过期，但卸载会删掉这个实例名下的全部手动快照（第 11.5 节）。
- **删除保护与最终快照。** 项目 RDS 没有开删除保护。删栈时，CloudFormation 会先给它留一个最终快照，随后 `uninstall.sh` 又把这个快照删掉。

### 11.3 恢复 colvenna-catalog 的元数据

适用于 `colvenna_catalog` 库损坏或数据被误改，要让 colvenna-catalog 回到某一时刻的元数据。这一流程不改动表文件。

```bash
COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh restore --snapshot <快照名> --yes uat <证据目录>          # 从本环境的手动或自动快照恢复
COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh restore --time 2026-10-22T07:30:00Z --yes uat <证据目录> # 恢复到保留期内的某一时刻
COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh restore --latest --yes uat <证据目录>                   # 恢复到最近能恢复的时刻
COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh check <证据目录>                           # 核对 catalog 与表文件
COLVENNA_ENV=uat deploy/scripts/catalog/restore-metadata.sh reconcile --unregister-missing <证据目录>  # 让 catalog 与表文件重新一致
```

`--yes` 后必须再写一遍环境名。`--snapshot` 只接受本环境项目实例的快照，别的实例的快照在动手之前就被拒绝：它们记的是别处的表路径。

**`restore` 做什么。**

1. 由 RDS 把快照或时间点恢复成一个临时实例 `<基础栈名小写>-restore-<时间>`，规格、子网组、安全组与参数组都与项目实例相同，不能从公网访问。
2. 在 `colvenna-catalog` 命名空间里起一个 PostgreSQL 工具 Pod，以 colvenna-catalog 自己的数据库登录，用 `pg_dump` 导出临时实例上的 `colvenna_catalog`；再把恢复点上已被围栏的表记进 `<证据目录>/restore-fenced.txt`。
3. 停止 colvenna-catalog（副本数改为 0），等到它的 Pod 全部退出；5 分钟后还有 Pod 在，就以失败退出，什么也不替换。
4. 给项目实例做一个手动快照 `<实例名>-before-restore-<时间>`。这时 colvenna-catalog 已经停了，快照里正是要被替换的内容；万一恢复错了，可以从它再恢复一次。
5. 在一个事务里用导出的内容替换项目实例上的 `colvenna_catalog`（`pg_restore --clean`），再启动 colvenna-catalog。替换失败时事务回滚，库保持原样，colvenna-catalog 照常启动。
6. 删除临时实例。加 `--keep-instance` 时保留：它照常计费，直到你删除它，或 `uninstall.sh` 在删栈之前删除它（第 8 节）。

恢复点写进 `<证据目录>/restore-point.txt`，`check` 与 `reconcile` 从这里读。任何一步失败，colvenna-catalog 都会重新启动。整个流程的演练脚本是 `tests/acceptance/catalog/run-rds-restore.sh`，在 UAT 里作为 UAT-DEP-09 由 `tests/acceptance/deploy/run-metadata-restore.sh` 运行（[UAT 用例](../uat/cases.md)）。

这一流程不换实例：项目实例仍是基础栈管理的那一个，端点、密钥和 CloudFormation 的记录都不变，之后的 `install.sh` 照常收敛。如果直接把 colvenna-catalog 指到 RDS 恢复出来的新实例，新实例不在栈里，`install.sh` 会把 colvenna-catalog 改回原实例，卸载也不会删除它，所以不这样做。

**停服。** colvenna-catalog 从第 3 步停止到第 5 步重新就绪，期间正是 `before-restore` 快照的那几分钟：实测 189–192 秒（`<证据目录>/catalog-stopped-seconds.txt`）。整个 `restore` 从快照恢复实测约 12 分钟，按时间点恢复约 14 分钟，大半是 RDS 建临时实例（第 10 节 `plat` 第二轮）。colvenna-catalog 停止期间，引擎与 Operator 对它的请求失败，按各自的重试等待。

**恢复之后要核对。** 元数据回到了恢复点 T，表目录里的文件却一直写到了恢复之前。`check` 逐一核对，结果写进 `<证据目录>/check.tsv`；`reconcile` 先核对，再按类别处理，最后再核对一次：

| 类别 | 含义 | `reconcile` 怎样处理 |
| --- | --- | --- |
| `consistent` | 与表文件一致 | 不处理 |
| `behind` | T 之后表又有提交（快照文件超过了 catalog 的指针），或换了新的 schema，或恢复之后有写入、因此被围栏 | reconcile 这张表：指针追上文件里最新的快照，分支也按文件补齐或去掉。T 之后的提交都保留，围栏解除 |
| `new` | 仓库里有、catalog 不列的表目录，它的第一个 schema 文件晚于 T：T 之后新建的表 | 以目录名重新登记（register）。如果库也是 T 之后建的，先建库，但建库时的选项已经丢失。目录名以 `-` 加 8 位十六进制结尾的不自动登记：这是建表时原路径被占用而取的路径，表名不是目录名，要由管理员按原名登记 |
| `missing` | catalog 还列着、目录已经没有了：T 之后删掉的表 | 只报告；加 `--unregister-missing` 时从 catalog 去掉 |
| `inconsistent` | 其他不一致，例如数据文件缺失；或者恢复点上就已被围栏（`restore` 把这些表记在 `<证据目录>/restore-fenced.txt`）：那是 colvenna-catalog 之外有写入方的信号 | 不处理，按报告逐表处理。被围栏的表，确认那个写入方已经停下之后，由管理员 reconcile |
| `other` | catalog 列着、没有路径的对象：视图 | 不处理 |
| `error` | 读不到这张表的一致性报告 | 什么都不知道，所以 `check` 以失败退出，`reconcile` 什么都不改。列出 catalog 或仓库桶失败时，`check` 与 `reconcile` 也直接失败，不会把读不到当成"没有" |
| `unlisted` | catalog 不列、T 之前就有的目录：曾经注销或有意留下的表目录 | 不处理 |

`behind` 的表在 reconcile 之前拒绝新的提交，所以要先 reconcile，再恢复写入。

**恢复不回来的。** T 之后只记在 `colvenna_catalog` 里的变化都会丢失：

- T 之后新建的 principal 与签发的 token 失效，要重新签发。Operator 的 token 若是 T 之后签发的，先删掉 `colvenna-system` 命名空间里的 Secret `colvenna-operator-catalog-token`，再运行 `install.sh --only operator`：这一步只在 Secret 不存在时签发新 token。引擎与读端的 token 由签发它们的人重新签发。
- T 之后撤销的 token、停用的 principal、收回的授权，恢复后又会生效，要重新撤销、停用或收回。T 之后新加的授权要重新加。
- T 之后的改名会还原：表又回到旧的名字，路径不变。
- T 之后的审计记录与提交意图。

**要让表的数据也回到 T。** 先 reconcile，再把这张表回滚到 T 时的快照，例如用 Paimon 的 rollback（经 colvenna-catalog）。回滚删除之后的快照；不再被任何快照、tag 或分支引用的文件，受管命名空间里的表可以用 `removeOrphanFiles` 任务清理（[用户手册](user.md)第 5.1 节），其他表用引擎执行 Paimon 的 `remove_orphan_files` 过程。这一步没有实测。

**范围。**

- 恢复只针对 `colvenna_catalog`。同一个临时实例上也有 `polaris`、`marquez` 与 `superset` 三个库，但它们的恢复不在这一流程里，没有演练过。
- 恢复与重新挂接只在一个版本上演练过：备份与恢复都是同一版本的 colvenna-catalog。用一个版本的备份恢复到升级后的另一个版本，没有验证，也不承诺可行。

### 11.4 仓库桶的保留

- **仓库桶。**
  - 不开版本（`versioned: false`），没有跨区域复制。
  - 生命周期规则只有一条：清理 7 天还未完成的分段上传。
  - 删栈时 CloudFormation 保留这个桶（`RemovalPolicy.RETAIN`），由 `uninstall.sh` 清空后删除。
- **表内保留多少数据，由表自己的设置决定。** 快照过期由 `LakeTask` 的 `expire` 任务做；有界保留与合规删除的规则见[用户手册](user.md)第 7.3、7.5 节。快照过期之前，可以按快照号或 tag 读到旧数据。
- **删表就删文件。** 以 `deletionPolicy: Delete` 声明的表随声明删除，管理员也可以直接删表；两种删除都会删掉表目录。平台没有回收站，删掉的文件不能从平台恢复。
- **审计日志桶与入口的访问日志桶**：对象 365 天后过期，删栈时同样保留，同样由 `uninstall.sh` 删除。

### 11.5 卸载时留下数据

`uninstall.sh` 什么也不保留，也没有保留某一部分的选项。在环境所在的区域里，它删除：

- 仓库桶、审计日志桶与入口的访问日志桶，连同其中的对象；
- 各 RDS 实例名下的全部手动快照：CloudFormation 删栈时留下的最终快照，第 11.2 节做的手动快照，第 11.3 节的 `before-restore` 快照，以及在同一区域复制出来的快照（副本仍记着原来的实例名）；自动备份随实例一起删除；
- 日志组、密钥、用户池与其余资源，见第 8 节。

最后的核对还会把这个区域里实例名以本环境栈名开头的任何快照列为残留，并以失败退出。所以要留下的东西，都要在卸载之前复制到这套环境之外：

1. **表数据。** 把仓库桶的 `warehouse/` 复制到环境之外你自己的桶里；需要作业的 checkpoint 时，再复制 `flink-state/`：

   ```bash
   aws s3 sync s3://<仓库桶>/warehouse/ s3://<你的桶>/<前缀>/warehouse/
   ```

   仓库桶的名字是基础栈的输出 `WarehouseBucket`。你的桶不要以本环境的栈名开头（例如 `colvennalakeuat…`），否则卸载的核对会把它当成残留。
2. **元数据留档。** 先做一个手动快照（第 11.2 节），再把它复制到另一个区域。卸载只看环境所在的区域，另一个区域的副本不受影响：

   ```bash
   aws rds copy-db-snapshot --region <另一个区域> --source-region <环境的区域> \
     --source-db-snapshot-identifier arn:aws:rds:<环境的区域>:<账号>:snapshot:<快照名> \
     --target-db-snapshot-identifier <副本名> --kms-key-id alias/aws/rds
   ```

   这份副本只用于留档。

**删栈之后不能从最终快照恢复。** 卸载会删掉最终快照。复制到环境之外的副本也不能用 `restore-metadata.sh` 恢复进重建出来的环境：它只接受本环境项目实例的快照，因为副本里的表路径指向旧环境的仓库桶，新环境里不存在。需要时，可以手工把副本恢复成一个单独的 RDS 实例，查看旧的授权与审计记录。重建之后要挂回表数据，按第 11.6 节重新登记。
3. **审计日志。** 需要时同样复制两个日志桶。
4. **声明。** 保存在你自己的版本库里。

这些复制出来的桶和快照不属于任何栈，费用自付，不再需要时由你自己删除。

### 11.6 重建一套环境

重建平台本身：按第 8 节卸载，再按第 3 节以同一个名字或新名字安装，得到一套空的环境。

**重新挂接保留的表。** 新环境的仓库桶是新建的，桶名由 CloudFormation 生成，所以表的路径会变。Paimon 表目录里的文件只按目录内的相对位置互相引用，因此把表目录原样复制到新仓库桶的 `warehouse/<库>.db/<表>/` 下，再在 colvenna-catalog 里按新路径登记，表就回来了，快照历史、tag 与分支都在：

1. 记下复制开始的时间 T，把保留的 `warehouse/` 复制进新环境的仓库桶。
2. 以 T 为恢复点运行 `reconcile`。复制进来的目录都晚于 T，所以都按目录名登记，缺的库先建：

   ```bash
   RESTORE_POINT=<T> COLVENNA_ENV=<新环境> deploy/scripts/catalog/restore-metadata.sh reconcile <证据目录>
   ```

3. 按 `check.tsv` 核对每张表都是 `consistent`。
4. 重新签发 principal 与 token、重新授权，再重新应用声明。

**限制。**

- 实测覆盖的是挂接这一步：在 `plat` 上，把一张表的目录复制到同一仓库桶里另一个库的路径下，按上面的步骤登记；删掉原表连同它的文件之后，复制出来的表读出的状态与快照历史都与原表相同（第 10 节）。在两套环境之间完整地重建一次，没有实测。
- 旧环境 `colvenna_catalog` 里的授权、principal、token 与审计记录不迁移。Iceberg 发布、血缘与 BI 的看板也不迁移，要重新声明或重建。
- 重新应用的声明接着写这些登记回来的表，没有验证过。

> 注：本文中的 issue 与 PR 编号（如 #212）是本项目早期私有仓库的编号，本仓库里没有对应的页面。其中带链接的 issue 编号（如 [#5](https://github.com/colvenna/colvenna-lake/issues/5)）是本仓库现在的编号。
