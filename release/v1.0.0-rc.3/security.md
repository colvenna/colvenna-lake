---
title: "Colvenna Lake v1.0.0-rc.3 发布检查：暴露面与拒绝证据"
date: 2026-10-26
status: final
issue: 181
---

# v1.0.0-rc.3 发布检查：暴露面与拒绝证据

本文是发布候选 `v1.0.0-rc.3` 发布检查报告（#181）的安全一节，方法沿用 #208：两个区域的候选环境在各自的 UAT 结束后，按第 2 节的步骤清点暴露面、实测三类拒绝。第 4 节各区域的结果由脚本生成（`exposure.py section`）。

按仓库的脱敏规则，账号、ARN、域名与 URL、资源 ID、生成的资源名的随机后缀与 IP 地址都换成了占位符（`<ACCOUNT_ID>`、`<ARN>`、`<入口域名>`、`<公网地址 n>`、`<…>` 等），原始证据里保留原值。同一个地址在一节里只用一个编号；EKS 端点放行的执行机出口地址写作 `<执行机的出口地址>`。

## 1. 结论

| 区域 | 环境 | 清点的检查 | 三类拒绝 | Lambda 函数 URL | 证据里没有凭证 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| `us-west-2` | `uat` | 17 项全部 PASS | 33 个公网可达资源（本环境 23 个、发布镜像仓库 10 个）、10 行矩阵，每一类都 PASS 或 N/A（47 项拒绝检查全部 PASS） | 0（17 个已启用区域，482 个函数） | PASS | 通过 |
| `us-east-2` | `uate2` | 17 项全部 PASS | 23 个公网可达资源、10 行矩阵，每一类都 PASS 或 N/A（47 项拒绝检查全部 PASS） | 0（17 个已启用区域，482 个函数） | PASS | 通过 |

两个区域各 64 项检查：64 项 PASS，0 项 N/A，0 项 FAIL，没有 MISSING。

拒绝矩阵里每个区域另有 6 项 N/A，不计入 64 项，各自写明理由：

- NAT 与未关联 EIP 的越权、绕过入口两类：没有监听，4 项；
- 镜像仓库的绕过入口：只有仓库 API 一条路，1 项；
- 用户池越权一类里的一项，1 项。

判定：

- 任一项 FAIL 或 MISSING，安全一节即不通过，发布结论为 `NO-GO`。
- N/A 单独计数，不算通过也不算失败；每个 N/A 都写明理由，理由见矩阵与检查的细节。
- 公网可达的资源都必须有有效的认证与授权（IAM 认证也算），不允许匿名访问；每个都用未认证的请求实测被拒，并实测另两类：有身份但越权、绕过预期入口。只有 HTTPS、隐藏入口或网络限制，不能代替认证。
- 账号所有已启用区域（标准区域与显式启用的区域）都不得有任何 Lambda 函数 URL，不分归属；有一个即 FAIL。

范围：

- 本节检查每个环境自己的暴露面：环境部署的资源与发布镜像仓库。
- 账号里其他项目的资源（负载均衡器、安全组等）列在 `others.tsv` 供参考，不计入结论。
- 环境外的桶只受账号级 S3 公共访问阻止的约束（`s3-account-blocks-public-access` 检查）；其桶策略与 ACL 不单独检查。

本节以外发现的问题（`others.tsv`）：

- 账号里别的项目有若干安全组对任意地址开放 22 或 3389 端口：`us-west-2` 有 5 个开放 22；`us-east-2` 有 1 个开放 22、2 个开放 3389。`us-west-2` 另有一个安全组对任意地址开放全部流量。与 rc.1、rc.2 时的清点相同。它们不属于 Colvenna Lake，不计入本节的结论，没有对应的 issue。
- `v1.0.0-rc.2` 时 `us-west-2` 还留着本项目早先一个环境（M5）的入口。它已在 #271 的拆除中删除：本次 `us-west-2` 的其他资源里，面向公网的负载均衡器从 2 个变为 1 个，对任意地址开放的安全组从 8 个变为 7 个，用户池从 7 个变为 6 个；剩下的负载均衡器与它开放 80、443 的安全组都属于别的项目。
- 别的项目的 API Gateway、CloudFront 与用户池只计数，本节不探测它们的认证。`us-east-2` 对任意地址开放 443 的安全组里，有两个是 GuardDuty 托管的。

## 2. 候选与执行

| 项 | 值 |
| --- | --- |
| 候选 | `v1.0.0-rc.3`，tag 指向 `0cb3e70764d3eaa989c10957654ada4508150f3f`（镜像构建自 `7ea8540949f06bc5ddf6c392c9816e621ee341c7`） |
| 测试脚本的提交 | `0cb3e70764d3eaa989c10957654ada4508150f3f`，即 tag 本身（`tests/acceptance/security/`；两个证据目录的 `scripts-sha.txt` 都是它） |
| 执行身份 | 执行机的 EC2 实例角色（`AWS_PROFILE=work`），账号 `<ACCOUNT_ID>` |
| 运行 | `us-west-2`（`uat`）：运行 id `secrc3w`，2026-10-26T10:57:53Z 至 11:04:39Z；`us-east-2`（`uate2`）：运行 id `secrc3e`，2026-10-26T11:04:53Z 至 11:10:30Z。各在该区域 UAT 的自动用例结束之后（`uat` 10:53Z，`uate2` 10:56Z） |
| UAT 证据 | `us-west-2`：`<证据目录>/rc3/v1.0.0-rc.3/uat-us-west-2`；`us-east-2`：`<证据目录>/rc3/v1.0.0-rc.3/uate2-us-east-2`（[UAT 报告](../../docs/uat/reports/v1.0.0-rc.3/report.md)） |

`us-west-2` 的手工清理 `manual-purge-dev-kafka` 在本节之前（10:57:40）运行。它只删除集群内开发用的 [Apache Kafka](https://kafka.apache.org/)，不在公网可达的范围内，不改变本节的清点。

每个区域依次运行两步，每个区域用一个新的证据目录：

```bash
COLVENNA_ENV=<env> tests/acceptance/security/inventory.sh <新的证据目录>
COLVENNA_ENV=<env> tests/acceptance/security/run-denials.sh <运行 id> <同一证据目录> <该环境同一候选的 UAT 证据目录>
```

- 每行检查结果都带上产生它的运行 id：清点的 id 由 `inventory.sh` 生成，记在 `raw/env.json` 的 `inventory_id`；拒绝检查的是命令行给的运行 id。
- `inventory.sh` 只写进新目录，目录里已有证据就拒绝。
- `run-denials.sh` 开始前检查目录（`exposure.py preflight`），以下任一情况都拒绝，什么也不做：
  - 不是本环境的清点；
  - 清点用的脚本提交（`scripts-sha.txt`）与本次不同；
  - 目录里已有拒绝检查（`denials.log` 或 `denials/`），不论运行 id；
  - `verdicts.tsv` 里有不带本次清点 id 的行。
- 矩阵与 `section.md` 只读本次清点与本次拒绝检查的行。

- `inventory.sh` 只读：AWS 的 describe、list、get 调用与 `kubectl get`。它清点账号在该区域的公网可达资源，分出本环境、发布镜像仓库与账号里的其他资源，并做第 3 节的检查。
- `run-denials.sh` 对清点出的每个公网资源发三类请求。它临时建：
  - 两个 Cognito 用户 `sec-<运行 id>-none`（没有组）与 `sec-<运行 id>-op`（`colvenna-operator`）；
  - 命名空间 `colvenna-sec-<运行 id>`（一个服务账号、一个探测 Pod）；
  - `colvenna-console` 命名空间里的探测 Pod `sec-<运行 id>-underprivileged`。

  退出时全部删除。不论哪种模式，退出时还删除被错误接受的请求会留下的东西：注册用户 `sec-<运行 id>-signup`，以及每个桶里的匿名对象 `security-probe/<运行 id>`。
- 然后它生成拒绝矩阵，写出本环境的一节 `<证据目录>/section.md`，贴进第 4 节。`section.md` 已按仓库的脱敏规则把账号、ARN、域名与 URL、资源 ID、生成的资源名后缀与 IP 地址换成占位符（`exposure.py redact` 对已有的文件做同样的替换），原值只在证据里。同一个地址在整节里只用一个编号；EKS 端点公开且只放行一个 /32 时，这个地址就是执行机的出口地址，写作 `<执行机的出口地址>`。最后检查证据里没有凭证，见第 5 节。
- 矩阵引用 UAT 自己的负向检查（UAT-ENT-01、UAT-BI-01、UAT-CAT-05），读的是所给 UAT 证据的 `summary.tsv`，同一测试取最后一次重跑。不给 UAT 证据时，这些引用记为“UAT 未提供”，只凭本次的探测判定。

### 与 rc.2 相比

- 技术标识全面改名（#271，PR #272）：
  - 环境的栈是 `ColvennaLake<Env>*`，仓库是 `colvenna-lake-<env>/*`，桶名以 `colvennalake<env>` 开头，Cognito 的域名前缀是 `colvenna-lake-<env>`；
  - 发布仓库是 `colvenna-lake-release/*`（栈 `ColvennaLakeRelease`，改名之后新部署）；
  - 探测用的命名空间与组是 `colvenna-sec-<运行 id>`、`colvenna-operator`。

  两套环境都是按新名字新装的，VPC、集群、入口与地址都与 rc.2 时不同。
- 发布镜像仓库不再有 `doris` 仓库：rc.2 时按 RETAIN 留着的 `cove-lake-release/doris`，已在 #271 的拆除中删除，新的发布仓库有 10 个仓库。所以 `ecr-no-public-policy` 检查的仓库，`us-west-2` 从 21 个变为 20 个（本环境 10 个、发布 10 个），公网可达资源相应从 34 个变为 33 个；`us-east-2` 仍是 10 个与 23 个。它们与 rc.2 时一样没有仓库策略：没有配置拉取账号，只有本账号的 IAM 身份能拉取。
- `lambda-function-urls-none` 检查的函数，从 491 个变为 482 个（账号所有已启用区域，不分归属）。
- 脱敏工具（#267，PR #273）：`section.md` 已按整节给地址编号，负载均衡器 ID 也已换成占位符。所以第 4 节只在排版上与 `section.md` 不同（第 5 节），rc.2 时的手工改正不再需要。

## 3. 检查什么

清点的检查（`inventory.sh`）：

| 检查 | 通过的条件 |
| --- | --- |
| `lambda-function-urls-none` | 账号所有已启用区域（标准区域与显式启用的区域）的所有函数（不分归属）都没有函数 URL |
| `lambda-no-public-policy` | 本环境的函数没有允许所有人调用的资源策略 |
| `public-addresses-only-nat-and-entry` | 本环境的公网地址只有入口负载均衡器、NAT 网关，以及 EKS 引导 CIDR 用的未关联 EIP |
| `load-balancers-only-the-entry` | 本环境面向公网的负载均衡器只有入口一个 |
| `entry-listeners-sign-in-first` | 80 只跳转到 HTTPS；443 只允许 TLS 1.2、1.3，每条规则先经 Cognito 登录再转发，其余请求得到固定的 404 |
| `eks-endpoint-iam-and-narrow` | EKS 公网端点只认 IAM，只放行 /32 地址；私网端点打开 |
| `kubernetes-no-load-balancer-node-port-ingress` | 集群里没有 LoadBalancer、NodePort 类型的 Service，也没有 Ingress |
| `s3-account-blocks-public-access` | 账号级的 S3 公共访问阻止四项全开 |
| `s3-buckets-private-and-tls-only` | 本环境每个桶：公共访问阻止四项全开、策略不公开、ACL 没有公共授权、拒绝不走 TLS 的请求、默认加密 |
| `cognito-admin-only-mfa-code-flow` | 用户池强制 MFA、不能自助注册；客户端带密钥，只用授权码流程，回调地址都在本环境的入口域名下 |
| `security-groups-open-only-the-entry` | 本环境 VPC 里对任意地址开放的安全组只有入口的，且只开 80 与 443 |
| `api-gateway-none-of-the-environment`、`cloudfront-none-of-the-environment` | 本环境没有 API Gateway 与 CloudFront |
| `rds-none-public` | 本环境的数据库都不公开 |
| `ecr-no-public-policy`、`secrets-no-public-policy` | 本环境与发布镜像仓库的仓库策略、本环境密钥的资源策略都不允许所有人 |
| `dns-names-only-the-entry` | 本环境域名下的地址记录都指向入口 |

三类拒绝（`run-denials.sh`），按资源：

| 资源 | 未认证 | 越权 | 绕过入口 |
| --- | --- | --- | --- |
| 入口后的控制台与 BI | 没有会话、伪造的会话 cookie、伪造的身份头，都跳到 Cognito 登录 | 已登录但没有该应用的角色（控制台：没有组的用户；BI：没有组、`colvenna-operator` 组的用户）得到 403；同一会话带伪造的管理员身份头仍是 403 | 负载均衡器的原始域名、别的主机名得到 404；HTTP 跳到 HTTPS；TLS 1.1 被拒；集群内 Pod 地址的网络隔离由 UAT 的负向用例覆盖 |
| EKS 公网端点 | 匿名、伪造签名的 token 得到 401 | 已认证但没有 RBAC 的服务账号得到 403 | 从端点不放行的地址（集群经 NAT 出网）连不上 |
| S3 桶 | 匿名列举、写入得到 403 | `colvenna-console` 服务账号（它的角色只能写自己的审计日志组、让用户登出，没有 S3 权限）列举每个桶，各自得到 AccessDenied（`deny-s3-<桶>-underprivileged`）；只有本账号的 IAM 身份与桶策略允许的服务能访问；仓库桶另引 UAT-CAT-05 的按表凭证检查 | 本次执行身份自己走明文 HTTP，被桶策略显式拒绝；网站端点返回 NoSuchWebsiteConfiguration |
| Cognito 用户池（登录域名与 API） | 没有 token 取不到用户信息；没有客户端密钥取不到 token | 错误口令停在登录页；另引 UAT-ENT-01 的错误验证码与停用用户；另有一项 N/A，矩阵里的理由原文："the app-level unauthorized class is covered by the console and BI rows; the hosted UI returns no access token, and enabling a password flow would bypass TOTP" | 注册页跳到错误页；拿着客户端密钥直接调 API，注册与口令登录（绕过 TOTP）都被拒 |
| 镜像仓库 | 匿名与伪造口令得到 401 | `colvenna-console` 服务账号（没有 ECR 权限）列本环境一个仓库的镜像，得到 AccessDenied（`deny-ecr-underprivileged`）；仓库策略不允许所有人（清点的检查） | N/A：取镜像只有仓库 API 一条路，只认 IAM 凭证 |
| NAT 与未关联的 EIP | 80、443 都连不上；只探有对照的端口，对照检查 `deny-address-positive-control`（执行机能连上 checkip.amazonaws.com 的 80、443）也在这一格里 | N/A：没有监听 | N/A：没有监听 |

集群内的服务（colvenna-catalog、[Apache Polaris](https://polaris.apache.org/)、Marquez、Prometheus、Grafana、基于 [Apache Doris](https://doris.apache.org/) 的查询引擎、开发用的 Apache Kafka）不在公网可达的范围内。它们的认证与网络隔离由 UAT 的负向用例覆盖，见候选版本的 [UAT 用例](../../docs/uat/cases.md)第 6 节。

## 4. 各区域的结果

### `uat`（us-west-2）

| 项 | 值 |
| --- | --- |
| 账号 | `<ACCOUNT_ID>` |
| VPC、集群 | `<VPC>`、`<EKS 集群>` |
| 入口域名 | `<入口域名>` |
| 时间（UTC） | 2026-10-26T11:02:11Z 至 2026-10-26T11:04:39Z |
| 清点 | 2026-10-26T10:57:53Z inventory-20261026T105752Z-ada068 of uat in us-west-2, account <ACCOUNT_ID>, by `<ARN>`; scripts at 0cb3e70764d3eaa989c10957654ada4508150f3f |
| 拒绝检查 | 2026-10-26T11:02:14Z denials secrc3w of uat in us-west-2 at 0cb3e70764d3eaa989c10957654ada4508150f3f by `<ARN>`; UAT evidence: <证据目录>/rc3/v1.0.0-rc.3/uat-us-west-2 |
| 原始证据 | `<证据目录>/rc3/v1.0.0-rc.3/security-uat` |

检查共 64 项：64 项 PASS，0 项 FAIL。N/A 不算通过也不算失败，理由见细节。

#### 清点的检查（us-west-2）

| 检查 | 结果 | 细节 |
| --- | --- | --- |
| `public-addresses-only-nat-and-entry` | PASS | <公网地址 1> load balancer app/<入口负载均衡器>/<…>; <公网地址 2> NAT gateway (egress only); <公网地址 3> unassociated Elastic IP; <公网地址 4> load balancer app/<入口负载均衡器>/<…> |
| `entry-listeners-sign-in-first` | PASS | 80 redirects to HTTPS; 443 TLS 1.2/1.3, every rule signs in first |
| `load-balancers-only-the-entry` | PASS | internet-facing load balancers of the environment: ['`<ARN>`'] |
| `eks-endpoint-iam-and-narrow` | PASS | public True from ['<执行机的出口地址>/32']; authentication API_AND_CONFIG_MAP; private True |
| `kubernetes-no-load-balancer-node-port-ingress` | PASS | LoadBalancer/NodePort services none; ingresses none |
| `s3-account-blocks-public-access` | PASS | {"PublicAccessBlockConfiguration": {"BlockPublicAcls": true, "IgnorePublicAcls": true, "BlockPublicPolicy": true, "RestrictPublicBuckets": true}} |
| `s3-buckets-private-and-tls-only` | PASS | 3 buckets: colvennalakeuatentry-accesslogs<…>, colvennalakeuatfoundation-auditlogs<…>, <仓库桶> |
| `cognito-admin-only-mfa-code-flow` | PASS | <用户池>: MFA on, administrators create users, code flow with a secret |
| `lambda-function-urls-none` | PASS | 482 functions in ap-northeast-1, ap-northeast-2, ap-northeast-3, ap-south-1, ap-southeast-1, ap-southeast-2, ca-central-1, eu-central-1, eu-north-1, eu-west-1, eu-west-2, eu-west-3, sa-east-1, us-east-1, us-east-2, us-west-1, us-west-2 (12 of the environment), 0 function URLs |
| `lambda-no-public-policy` | PASS | public resource policies: none |
| `security-groups-open-only-the-entry` | PASS | only the entry's group admits any address, on 80 and 443 |
| `api-gateway-none-of-the-environment` | PASS | APIs of the environment: none |
| `cloudfront-none-of-the-environment` | PASS | distributions of the environment: none |
| `rds-none-public` | PASS | 3 instances of the environment; public: none |
| `ecr-no-public-policy` | PASS | 20 repositories of the environment and the release; public: none |
| `secrets-no-public-policy` | PASS | 6 secrets; public: none |
| `dns-names-only-the-entry` | PASS | 3 names, every address one the entry's |

#### 公网可达的资源（本环境与发布镜像仓库）（us-west-2）

| 类型 | 资源 | 归属 | 认证 | 说明 |
| --- | --- | --- | --- | --- |
| address | `<公网地址 1>` | env | the load balancer's listeners | load balancer app/<入口负载均衡器>/<…> |
| address | `<公网地址 2>` | env | none listens | NAT gateway (egress only) |
| address | `<公网地址 3>` | env | none listens | unassociated Elastic IP |
| address | `<公网地址 4>` | env | the load balancer's listeners | load balancer app/<入口负载均衡器>/<…> |
| load-balancer | `<入口负载均衡器>` | env | Cognito sign-in (password + TOTP) before any forward | application <入口负载均衡器的 DNS 名>; listeners [80, 443] |
| eks-endpoint | `<EKS 集群>` | env | IAM (access entries, API_AND_CONFIG_MAP), from <执行机的出口地址>/32 only | private access True |
| s3-bucket | `colvennalakeuatentry-accesslogs<…>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| s3-bucket | `colvennalakeuatfoundation-auditlogs<…>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| s3-bucket | `<仓库桶>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| cognito-user-pool | `<用户池>` | env | password + TOTP; no self sign-up; client secret; authorization code only | domain colvenna-lake-uat-<ACCOUNT_ID> |
| security-group | `<sg-…>` | env | - | the entry's load balancer, ColvennaLakeUatEntry-EntrySecurityGroup7B01EF29-<…>: [('tcp', 80, 80), ('tcp', 443, 443)] |
| ecr-repository | `colvenna-lake-release/colvenna-jobs` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/colvenna-console` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/test-readers` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/third-party` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/query-engine` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/colvenna-jobs` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/colvenna-bi` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/colvenna-bi` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/colvenna-cli` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/colvenna-operator` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/flink-base` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/third-party` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/test-spark` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/flink-base` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/colvenna-catalog` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/colvenna-operator` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/test-readers` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/colvenna-catalog` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-release/query-engine` | release | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uat/colvenna-console` | env | IAM of the account | no repository policy |
| dns-name | `bi.<入口域名>` | env | -> the entry | <负载均衡器的 DNS 名> |
| dns-name | `console.<入口域名>` | env | -> the entry | <负载均衡器的 DNS 名> |

#### 三类拒绝（us-west-2）

| 资源 | 说明 | 未认证 | 越权 | 绕过入口 |
| --- | --- | --- | --- | --- |
| `<公网地址 2>` | NAT gateway (egress only) | PASS（2/2 项） | N/A：nothing listens on it | N/A：nothing listens on it |
| `<公网地址 3>` | unassociated Elastic IP | PASS（2/2 项） | N/A：nothing listens on it | N/A：nothing listens on it |
| `https://console.<入口域名>` | console behind the entry | PASS（7/7 项） | PASS（4/4 项） | PASS（11/11 项） |
| `https://bi.<入口域名>` | bi behind the entry | PASS（5/5 项） | PASS（5/5 项） | PASS（7/7 项） |
| `<EKS 集群>` | the EKS API's public endpoint | PASS（2/2 项） | PASS（1/1 项） | PASS（2/2 项） |
| `colvennalakeuatentry-accesslogs<…>` | S3 bucket | PASS（2/2 项） | PASS（3/3 项） | PASS（2/2 项） |
| `colvennalakeuatfoundation-auditlogs<…>` | S3 bucket | PASS（2/2 项） | PASS（3/3 项） | PASS（2/2 项） |
| `<仓库桶>` | S3 bucket | PASS（2/2 项） | PASS（8/8 项） | PASS（2/2 项） |
| `<用户池>` | Cognito user pool: hosted sign-in and API (domain colvenna-lake-uat-<ACCOUNT_ID>) | PASS（2/2 项） | PASS（3/3 项，另 1 项 N/A：the app-level unauthorized class is covered by the console and BI rows; the hosted UI returns no access token, and enabling a password flow would bypass TOTP） | PASS（4/4 项） |
| `image registry` | ECR repositories of the environment and the release | PASS（1/1 项） | PASS（2/2 项） | N/A：the registry API is the only way to an image; it takes IAM credentials only |

逐项证据见 `matrix.tsv`。

#### 拒绝检查（us-west-2）

| 检查 | 结果 | 细节 |
| --- | --- | --- |
| `deny-console-no-session` | PASS | /: 302 `https://<Cognito 托管登录域名>`; /api/me: 302 `https://<Cognito 托管登录域名>` |
| `deny-console-forged-session-cookie` | PASS | 302 `https://<Cognito 托管登录域名>` |
| `deny-console-forged-identity-headers` | PASS | unsigned administrator headers: 302 `https://<Cognito 托管登录域名>` |
| `deny-bi-no-session` | PASS | /: 302 `https://<Cognito 托管登录域名>`; /api/v1/database/: 302 `https://<Cognito 托管登录域名>` |
| `deny-bi-forged-session-cookie` | PASS | 302 `https://<Cognito 托管登录域名>` |
| `deny-bi-forged-identity-headers` | PASS | unsigned administrator headers: 302 `https://<Cognito 托管登录域名>` |
| `deny-cognito-userinfo-without-token` | PASS | 400 {"error":"invalid_request","error_description":"Authorization header required, but missing from requ |
| `deny-cognito-token-without-secret` | PASS | 400 {'error': 'invalid_client'} |
| `deny-eks-anonymous` | PASS | /version 401; /api 401; /api/v1/namespaces/kube-system/secrets 401; |
| `deny-eks-forged-token` | PASS | a token with a forged signature: 401 |
| `deny-s3-colvennalakeuatentry-accesslogs<…>-anonymous-list` | PASS | 403 |
| `deny-s3-colvennalakeuatentry-accesslogs<…>-anonymous-put` | PASS | 403 |
| `deny-s3-colvennalakeuatfoundation-auditlogs<…>-anonymous-list` | PASS | 403 |
| `deny-s3-colvennalakeuatfoundation-auditlogs<…>-anonymous-put` | PASS | 403 |
| `deny-s3-<仓库桶>-anonymous-list` | PASS | 403 |
| `deny-s3-<仓库桶>-anonymous-put` | PASS | 403 |
| `deny-ecr-anonymous` | PASS | /v2/: 401; colvenna-lake-uat/query-engine tags: 401; forged password: 400 {"errors":[{"code":"DENIED","message":"Your Authorization Token is invalid."}]} |
| `deny-address-positive-control` | PASS | this host connects to checkip.amazonaws.com on 80 443 |
| `deny-address-<公网地址 2>-no-inbound` | PASS | NAT gateway (egress only); accepted on: none of 80 443 (control: deny-address-positive-control) |
| `deny-address-<公网地址 3>-no-inbound` | PASS | unassociated Elastic IP; accepted on: none of 80 443 (control: deny-address-positive-control) |
| `deny-cognito-wrong-password` | PASS | ('login', 'Incorrect username or password.') |
| `deny-console-without-the-role` | PASS | /api/me signed in none: 403; |
| `deny-console-forged-headers-with-a-session` | PASS | the same sessions with unsigned administrator headers: none: 403; |
| `deny-bi-without-the-role` | PASS | /api/v1/database/ signed in none: 403; op: 403; |
| `deny-bi-forged-headers-with-a-session` | PASS | the same sessions with unsigned administrator headers: none: 403; op: 403; |
| `deny-eks-without-rbac` | PASS | a service account without RBAC, authenticated (/version 200): /api/v1/namespaces 403; /api/v1/namespaces/kube-system/secrets 403; /apis/apps/v1/namespaces/colvenna-sec-secrc3w/deployments 403; |
| `deny-s3-colvennalakeuatentry-accesslogs<…>-underprivileged` | PASS | as colvenna-lake-uat-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:aws |
| `deny-s3-colvennalakeuatfoundation-auditlogs<…>-underprivileged` | PASS | as colvenna-lake-uat-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:aws |
| `deny-s3-<仓库桶>-underprivileged` | PASS | as colvenna-lake-uat-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:aws |
| `deny-ecr-underprivileged` | PASS | as colvenna-lake-uat-console: exit 254,  An error occurred (AccessDeniedException) when calling the DescribeImages operation: User: `<ARN>` is not authorized to perform: ecr:DescribeImages on res |
| `deny-entry-load-balancer-name` | PASS | Not found 404 |
| `deny-entry-unknown-host` | PASS | Not found 404 |
| `deny-entry-plain-http` | PASS | 301 `https://console.<入口域名>:443/api/me` |
| `deny-entry-legacy-tls` | PASS | alert protocol version |
| `deny-eks-from-another-address` | PASS | egress <公网地址 2>;<公网地址 5> 000 28;<公网地址 6> 000 28; |
| `deny-s3-colvennalakeuatentry-accesslogs<…>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-colvennalakeuatentry-accesslogs<…>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-s3-colvennalakeuatfoundation-auditlogs<…>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-colvennalakeuatfoundation-auditlogs<…>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-s3-<仓库桶>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-<仓库桶>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-cognito-sign-up-page` | PASS | 302 `<Cognito 托管登录地址>/erro` |
| `deny-cognito-api-sign-up` | PASS | aws: [ERROR]: An error occurred (NotAuthorizedException) when calling the SignUp operation: SignUp is not permitted for this user pool |
| `deny-cognito-api-password-flow` | PASS | the right password of sec-secrc3w-op, with the secret:  aws: [ERROR]: An error occurred (InvalidParameterException) when calling the InitiateAuth operation: USER_PASSWORD_AUTH flow not enabled for this client |
| `denial-matrix-complete` | PASS | 33 internet-reachable resources of the environment, 10 matrix rows, every class refused |
| `evidence-has-no-secrets` | PASS | no credential-like string in <证据目录>/rc3/v1.0.0-rc.3/security-uat |
| `evidence-has-no-actual-credentials` | PASS | none found; scanned 612 files for 3 password, 2 totp, 17 cookie, 1 client-secret, 1 sa-token values (4 shorter than 8 characters not looked for) |

#### 同一区域里账号的其他资源（不属于本环境，不计入结论）（us-west-2）

| 类型 | 公网可达的数量 |
| --- | --- |
| address | 14 |
| api-gateway | 11 |
| cloudfront | 19 |
| cognito-user-pool | 6 |
| eks-endpoint | 2 |
| load-balancer | 1 |
| security-group | 7 |

明细见 `others.tsv`。Lambda 函数 URL 的检查覆盖账号所有已启用区域的所有函数，不分归属。

### `uate2`（us-east-2）

| 项 | 值 |
| --- | --- |
| 账号 | `<ACCOUNT_ID>` |
| VPC、集群 | `<VPC>`、`<EKS 集群>` |
| 入口域名 | `<入口域名>` |
| 时间（UTC） | 2026-10-26T11:08:23Z 至 2026-10-26T11:10:30Z |
| 清点 | 2026-10-26T11:04:53Z inventory-20261026T110452Z-5b6af7 of uate2 in us-east-2, account <ACCOUNT_ID>, by `<ARN>`; scripts at 0cb3e70764d3eaa989c10957654ada4508150f3f |
| 拒绝检查 | 2026-10-26T11:08:26Z denials secrc3e of uate2 in us-east-2 at 0cb3e70764d3eaa989c10957654ada4508150f3f by `<ARN>`; UAT evidence: <证据目录>/rc3/v1.0.0-rc.3/uate2-us-east-2 |
| 原始证据 | `<证据目录>/rc3/v1.0.0-rc.3/security-uate2` |

检查共 64 项：64 项 PASS，0 项 FAIL。N/A 不算通过也不算失败，理由见细节。

#### 清点的检查（us-east-2）

| 检查 | 结果 | 细节 |
| --- | --- | --- |
| `public-addresses-only-nat-and-entry` | PASS | <公网地址 1> unassociated Elastic IP; <公网地址 2> load balancer app/<入口负载均衡器>/<…>; <公网地址 3> load balancer app/<入口负载均衡器>/<…>; <公网地址 4> NAT gateway (egress only) |
| `entry-listeners-sign-in-first` | PASS | 80 redirects to HTTPS; 443 TLS 1.2/1.3, every rule signs in first |
| `load-balancers-only-the-entry` | PASS | internet-facing load balancers of the environment: ['`<ARN>`'] |
| `eks-endpoint-iam-and-narrow` | PASS | public True from ['<执行机的出口地址>/32']; authentication API_AND_CONFIG_MAP; private True |
| `kubernetes-no-load-balancer-node-port-ingress` | PASS | LoadBalancer/NodePort services none; ingresses none |
| `s3-account-blocks-public-access` | PASS | {"PublicAccessBlockConfiguration": {"BlockPublicAcls": true, "IgnorePublicAcls": true, "BlockPublicPolicy": true, "RestrictPublicBuckets": true}} |
| `s3-buckets-private-and-tls-only` | PASS | 3 buckets: colvennalakeuate2entry-accesslogs<…>, colvennalakeuate2foundation-auditlogs<…>, <仓库桶> |
| `cognito-admin-only-mfa-code-flow` | PASS | <用户池>: MFA on, administrators create users, code flow with a secret |
| `lambda-function-urls-none` | PASS | 482 functions in ap-northeast-1, ap-northeast-2, ap-northeast-3, ap-south-1, ap-southeast-1, ap-southeast-2, ca-central-1, eu-central-1, eu-north-1, eu-west-1, eu-west-2, eu-west-3, sa-east-1, us-east-1, us-east-2, us-west-1, us-west-2 (12 of the environment), 0 function URLs |
| `lambda-no-public-policy` | PASS | public resource policies: none |
| `security-groups-open-only-the-entry` | PASS | only the entry's group admits any address, on 80 and 443 |
| `api-gateway-none-of-the-environment` | PASS | APIs of the environment: none |
| `cloudfront-none-of-the-environment` | PASS | distributions of the environment: none |
| `rds-none-public` | PASS | 3 instances of the environment; public: none |
| `ecr-no-public-policy` | PASS | 10 repositories of the environment and the release; public: none |
| `secrets-no-public-policy` | PASS | 6 secrets; public: none |
| `dns-names-only-the-entry` | PASS | 3 names, every address one the entry's |

#### 公网可达的资源（本环境与发布镜像仓库）（us-east-2）

| 类型 | 资源 | 归属 | 认证 | 说明 |
| --- | --- | --- | --- | --- |
| address | `<公网地址 1>` | env | none listens | unassociated Elastic IP |
| address | `<公网地址 2>` | env | the load balancer's listeners | load balancer app/<入口负载均衡器>/<…> |
| address | `<公网地址 3>` | env | the load balancer's listeners | load balancer app/<入口负载均衡器>/<…> |
| address | `<公网地址 4>` | env | none listens | NAT gateway (egress only) |
| load-balancer | `<入口负载均衡器>` | env | Cognito sign-in (password + TOTP) before any forward | application <入口负载均衡器的 DNS 名>; listeners [80, 443] |
| eks-endpoint | `<EKS 集群>` | env | IAM (access entries, API_AND_CONFIG_MAP), from <执行机的出口地址>/32 only | private access True |
| s3-bucket | `colvennalakeuate2entry-accesslogs<…>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| s3-bucket | `colvennalakeuate2foundation-auditlogs<…>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| s3-bucket | `<仓库桶>` | env | IAM and the bucket policy, TLS only | private, TLS only, encrypted |
| cognito-user-pool | `<用户池>` | env | password + TOTP; no self sign-up; client secret; authorization code only | domain colvenna-lake-uate2-<ACCOUNT_ID> |
| security-group | `<sg-…>` | env | - | the entry's load balancer, ColvennaLakeUate2Entry-EntrySecurityGroup7B01EF29-<…>: [('tcp', 80, 80), ('tcp', 443, 443)] |
| ecr-repository | `colvenna-lake-uate2/query-engine` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/colvenna-catalog` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/test-spark` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/colvenna-jobs` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/test-readers` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/colvenna-bi` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/colvenna-console` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/flink-base` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/third-party` | env | IAM of the account | no repository policy |
| ecr-repository | `colvenna-lake-uate2/colvenna-operator` | env | IAM of the account | no repository policy |
| dns-name | `bi.<入口域名>` | env | -> the entry | <负载均衡器的 DNS 名> |
| dns-name | `console.<入口域名>` | env | -> the entry | <负载均衡器的 DNS 名> |

#### 三类拒绝（us-east-2）

| 资源 | 说明 | 未认证 | 越权 | 绕过入口 |
| --- | --- | --- | --- | --- |
| `<公网地址 1>` | unassociated Elastic IP | PASS（2/2 项） | N/A：nothing listens on it | N/A：nothing listens on it |
| `<公网地址 4>` | NAT gateway (egress only) | PASS（2/2 项） | N/A：nothing listens on it | N/A：nothing listens on it |
| `https://console.<入口域名>` | console behind the entry | PASS（7/7 项） | PASS（4/4 项） | PASS（11/11 项） |
| `https://bi.<入口域名>` | bi behind the entry | PASS（5/5 项） | PASS（5/5 项） | PASS（7/7 项） |
| `<EKS 集群>` | the EKS API's public endpoint | PASS（2/2 项） | PASS（1/1 项） | PASS（2/2 项） |
| `colvennalakeuate2entry-accesslogs<…>` | S3 bucket | PASS（2/2 项） | PASS（3/3 项） | PASS（2/2 项） |
| `colvennalakeuate2foundation-auditlogs<…>` | S3 bucket | PASS（2/2 项） | PASS（3/3 项） | PASS（2/2 项） |
| `<仓库桶>` | S3 bucket | PASS（2/2 项） | PASS（8/8 项） | PASS（2/2 项） |
| `<用户池>` | Cognito user pool: hosted sign-in and API (domain colvenna-lake-uate2-<ACCOUNT_ID>) | PASS（2/2 项） | PASS（3/3 项，另 1 项 N/A：the app-level unauthorized class is covered by the console and BI rows; the hosted UI returns no access token, and enabling a password flow would bypass TOTP） | PASS（4/4 项） |
| `image registry` | ECR repositories of the environment and the release | PASS（1/1 项） | PASS（2/2 项） | N/A：the registry API is the only way to an image; it takes IAM credentials only |

逐项证据见 `matrix.tsv`。

#### 拒绝检查（us-east-2）

| 检查 | 结果 | 细节 |
| --- | --- | --- |
| `deny-console-no-session` | PASS | /: 302 `https://<Cognito 托管登录域名>.`; /api/me: 302 `https://<Cognito 托管登录域名>.` |
| `deny-console-forged-session-cookie` | PASS | 302 `https://<Cognito 托管登录域名>.` |
| `deny-console-forged-identity-headers` | PASS | unsigned administrator headers: 302 `https://<Cognito 托管登录域名>.` |
| `deny-bi-no-session` | PASS | /: 302 `https://<Cognito 托管登录域名>.`; /api/v1/database/: 302 `https://<Cognito 托管登录域名>.` |
| `deny-bi-forged-session-cookie` | PASS | 302 `https://<Cognito 托管登录域名>.` |
| `deny-bi-forged-identity-headers` | PASS | unsigned administrator headers: 302 `https://<Cognito 托管登录域名>.` |
| `deny-cognito-userinfo-without-token` | PASS | 400 {"error":"invalid_request","error_description":"Authorization header required, but missing from requ |
| `deny-cognito-token-without-secret` | PASS | 400 {'error': 'invalid_client'} |
| `deny-eks-anonymous` | PASS | /version 401; /api 401; /api/v1/namespaces/kube-system/secrets 401; |
| `deny-eks-forged-token` | PASS | a token with a forged signature: 401 |
| `deny-s3-colvennalakeuate2entry-accesslogs<…>-anonymous-list` | PASS | 403 |
| `deny-s3-colvennalakeuate2entry-accesslogs<…>-anonymous-put` | PASS | 403 |
| `deny-s3-colvennalakeuate2foundation-auditlogs<…>-anonymous-list` | PASS | 403 |
| `deny-s3-colvennalakeuate2foundation-auditlogs<…>-anonymous-put` | PASS | 403 |
| `deny-s3-<仓库桶>-anonymous-list` | PASS | 403 |
| `deny-s3-<仓库桶>-anonymous-put` | PASS | 403 |
| `deny-ecr-anonymous` | PASS | /v2/: 401; colvenna-lake-uate2/query-engine tags: 401; forged password: 400 {"errors":[{"code":"DENIED","message":"Your Authorization Token is invalid."}]} |
| `deny-address-positive-control` | PASS | this host connects to checkip.amazonaws.com on 80 443 |
| `deny-address-<公网地址 1>-no-inbound` | PASS | unassociated Elastic IP; accepted on: none of 80 443 (control: deny-address-positive-control) |
| `deny-address-<公网地址 4>-no-inbound` | PASS | NAT gateway (egress only); accepted on: none of 80 443 (control: deny-address-positive-control) |
| `deny-cognito-wrong-password` | PASS | ('login', 'Incorrect username or password.') |
| `deny-console-without-the-role` | PASS | /api/me signed in none: 403; |
| `deny-console-forged-headers-with-a-session` | PASS | the same sessions with unsigned administrator headers: none: 403; |
| `deny-bi-without-the-role` | PASS | /api/v1/database/ signed in none: 403; op: 403; |
| `deny-bi-forged-headers-with-a-session` | PASS | the same sessions with unsigned administrator headers: none: 403; op: 403; |
| `deny-eks-without-rbac` | PASS | a service account without RBAC, authenticated (/version 200): /api/v1/namespaces 403; /api/v1/namespaces/kube-system/secrets 403; /apis/apps/v1/namespaces/colvenna-sec-secrc3e/deployments 403; |
| `deny-s3-colvennalakeuate2entry-accesslogs<…>-underprivileged` | PASS | as colvenna-lake-uate2-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:a |
| `deny-s3-colvennalakeuate2foundation-auditlogs<…>-underprivileged` | PASS | as colvenna-lake-uate2-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:a |
| `deny-s3-<仓库桶>-underprivileged` | PASS | as colvenna-lake-uate2-console: exit 254,  An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: `<ARN>` is not authorized to perform: s3:ListBucket on resource: "arn:a |
| `deny-ecr-underprivileged` | PASS | as colvenna-lake-uate2-console: exit 254,  An error occurred (AccessDeniedException) when calling the DescribeImages operation: User: `<ARN>` is not authorized to perform: ecr:DescribeImages on r |
| `deny-entry-load-balancer-name` | PASS | Not found 404 |
| `deny-entry-unknown-host` | PASS | Not found 404 |
| `deny-entry-plain-http` | PASS | 301 `https://console.<入口域名>:443/api/me` |
| `deny-entry-legacy-tls` | PASS | alert protocol version |
| `deny-eks-from-another-address` | PASS | egress <公网地址 4>;<公网地址 5> 000 28;<公网地址 6> 000 28; |
| `deny-s3-colvennalakeuate2entry-accesslogs<…>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-colvennalakeuate2entry-accesslogs<…>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-s3-colvennalakeuate2foundation-auditlogs<…>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-colvennalakeuate2foundation-auditlogs<…>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-s3-<仓库桶>-plain-http` | PASS | this run's own identity over plain HTTP: explicit deny in a resource-based policy |
| `deny-s3-<仓库桶>-website` | PASS | &lt;html&gt; &lt;head&gt;&lt;title&gt;404 Not Found&lt;/title&gt;&lt;/head&gt; &lt;body&gt; &lt;h1&gt;404 Not Found&lt;/h1&gt; &lt;ul&gt; &lt;li&gt;Code: NoSuch |
| `deny-cognito-sign-up-page` | PASS | 302 `<Cognito 托管登录地址>/er` |
| `deny-cognito-api-sign-up` | PASS | aws: [ERROR]: An error occurred (NotAuthorizedException) when calling the SignUp operation: SignUp is not permitted for this user pool |
| `deny-cognito-api-password-flow` | PASS | the right password of sec-secrc3e-op, with the secret:  aws: [ERROR]: An error occurred (InvalidParameterException) when calling the InitiateAuth operation: USER_PASSWORD_AUTH flow not enabled for this client |
| `denial-matrix-complete` | PASS | 23 internet-reachable resources of the environment, 10 matrix rows, every class refused |
| `evidence-has-no-secrets` | PASS | no credential-like string in <证据目录>/rc3/v1.0.0-rc.3/security-uate2 |
| `evidence-has-no-actual-credentials` | PASS | none found; scanned 593 files for 3 password, 2 totp, 17 cookie, 1 client-secret, 1 sa-token values (4 shorter than 8 characters not looked for) |

#### 同一区域里账号的其他资源（不属于本环境，不计入结论）（us-east-2）

| 类型 | 公网可达的数量 |
| --- | --- |
| address | 2 |
| api-gateway | 1 |
| cloudfront | 19 |
| cognito-user-pool | 2 |
| security-group | 5 |

明细见 `others.tsv`。Lambda 函数 URL 的检查覆盖账号所有已启用区域的所有函数，不分归属。

## 5. 证据与访问

- 原始证据在执行机的 `<证据目录>/rc3/v1.0.0-rc.3/security-<env>/`（`security-uat`、`security-uate2`），只有执行机的账号能读，不入库。每个目录有：
  - `raw/`：AWS 与集群的原始回答；
  - `inventory.tsv`、`others.tsv`：本环境与发布仓库的资源、账号里的其他资源；
  - `inventory-checks.txt`、`verdicts.tsv`：清点的检查与每项检查；
  - `denials/`：各探测的状态码与登录到达的位置；
  - `matrix.tsv`、`matrix.md`：拒绝矩阵与逐项证据；
  - `section.md`：第 4 节的内容。

  各区域的运行记录另在 `../s9-uat.log`、`../s9-uate2.log`。
- 第 4 节由 `exposure.py section <证据目录> <运行 id>` 重新生成（在本文所在的提交上运行，结果与证据里的 `section.md` 逐字相同；`tests/acceptance/security/` 在 tag 与本文所在的提交之间没有改动），只做了以下排版上的改动，没有改任何结果或数：
  - 小标题带上区域，免得两个区域的小标题重复；
  - S3 网站端点返回的 HTML 转义；
  - 拒绝检查细节里的 URL 放进代码格式；
  - 表格单元格首尾多余的空格去掉。

  地址的编号与 `<执行机的出口地址>` 都是工具给的，没有手工改动（#267 修复之后，整节编号）。
- 证据不含凭证：测试口令、TOTP 密钥、会话 cookie、客户端密钥与服务账号 token 只放在运行期间的私有临时目录里，不写进证据，退出时删除。
- 两项扫描在最后运行，扫证据目录里的每个文件，包括 `section.md`：
  - `evidence-has-no-actual-credentials`：在临时文件删除之前，查找本次运行的这些值的完整副本。细节里写明每类扫了几个值；本次运行产生了某类凭证却一个值也没找到，也是 FAIL。
  - `evidence-has-no-secrets`：用模式查找 AWS 访问密钥、私钥、签名的 JWT、客户端密钥与 bearer token。
  - 发现即 FAIL，只报文件与类型或行号，不报匹配的内容。
  - 扫描的结果写进 `verdicts.tsv` 和 `section.md` 之后，再扫一遍；这一遍发现了什么，也记为 FAIL。

## 商标

> Apache, Apache Polaris, Polaris, Apache Doris, Doris, Apache Kafka and Kafka are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #181）和 tag（如 `v1.0.0-rc.3`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
