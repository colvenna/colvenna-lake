---
title: "Colvenna Lake <候选> 发布检查：暴露面与拒绝证据"
date: <YYYY-MM-DD>
status: template
issue: 208
---

# <候选> 发布检查：暴露面与拒绝证据

本文是发布检查报告的安全一节，对应 #208。复制到 `release/<候选>/security.md` 后，按下面的步骤在每个区域的候选环境上运行，再把结果填进来。尖括号里的内容都要替换；留着尖括号或“待补”的报告不算完成。

## 1. 结论

| 区域 | 环境 | 清点的检查 | 三类拒绝 | Lambda 函数 URL | 证据里没有凭证 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| `<区域>` | `<环境>` | <项数> 项全部 PASS | 每个公网资源的三类都 PASS 或 N/A | 0 | PASS | <通过 / 不通过> |
| `<另一个区域>` | `<环境>` | <项数> 项全部 PASS | 每个公网资源的三类都 PASS 或 N/A | 0 | PASS | <通过 / 不通过> |

判定：

- 任一项 FAIL 或 MISSING，安全一节即不通过，发布结论为 `NO-GO`。
- N/A 单独计数，不算通过也不算失败；每个 N/A 都写明理由，理由见矩阵与检查的细节。
- 公网可达的资源都必须有有效的认证与授权，并实测三类请求被拒：未认证、有身份但越权、绕过预期入口。只有 HTTPS、隐藏入口或网络限制，不能代替认证。
- 账号所有已启用区域（标准区域与显式启用的区域）都不得有任何 Lambda 函数 URL，不分归属。

范围：

- 本节检查每个环境自己的暴露面：环境部署的资源与发布镜像仓库。
- 账号里其他项目的资源（负载均衡器、安全组等）列在 `others.tsv` 供参考，不计入结论。
- 环境外的桶只受账号级 S3 公共访问阻止的约束（`s3-account-blocks-public-access` 检查）；其桶策略与 ACL 不单独检查。

本节以外发现的问题：<没有 / 列出，并链接对应的 issue>

## 2. 候选与执行

| 项 | 值 |
| --- | --- |
| 候选 | `<候选>`，tag 指向 `<完整 SHA>` |
| 测试脚本的提交 | `<完整 SHA>`（`tests/acceptance/security/`） |
| 执行身份 | `<执行身份的说明，不写 ARN>`，账号 `<ACCOUNT_ID>` |
| UAT 证据 | `<区域>`：`<UAT 证据目录>`；`<另一个区域>`：`<UAT 证据目录>` |

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
- 然后它生成拒绝矩阵，写出本环境的一节 `<证据目录>/section.md`，贴进第 4 节。`section.md` 已按仓库的脱敏规则把账号、ARN、域名与 URL、资源 ID、生成的资源名后缀与 IP 地址换成占位符（`exposure.py redact` 对已有的文件做同样的替换），原值只在证据里。同一个地址在整节（`redact` 是整个文件）里只用一个编号；EKS 端点公开且只放行一个 /32 时，这个地址就是执行机的出口地址，写作 `<执行机的出口地址>`，否则与其他地址一样编号。最后检查证据里没有凭证，见第 5 节。
- 矩阵引用 UAT 自己的负向检查（UAT-ENT-01、UAT-BI-01、UAT-CAT-05），读的是所给 UAT 证据的 `summary.tsv`，同一测试取最后一次重跑。不给 UAT 证据时，这些引用记为“UAT 未提供”，只凭本次的探测判定。

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
| Cognito 用户池（登录域名与 API） | 没有 token 取不到用户信息；没有客户端密钥取不到 token | 错误口令停在登录页；另引 UAT-ENT-01 的错误验证码与停用用户；另有一项 N/A，矩阵里的理由原文：“the app-level unauthorized class is covered by the console and BI rows; the hosted UI returns no access token, and enabling a password flow would bypass TOTP” | 注册页跳到错误页；拿着客户端密钥直接调 API，注册与口令登录（绕过 TOTP）都被拒 |
| 镜像仓库 | 匿名与伪造口令得到 401 | `colvenna-console` 服务账号（没有 ECR 权限）列本环境一个仓库的镜像，得到 AccessDenied（`deny-ecr-underprivileged`）；仓库策略不允许所有人（清点的检查） | N/A：取镜像只有仓库 API 一条路，只认 IAM 凭证 |
| NAT 与未关联的 EIP | 80、443 都连不上；只探有对照的端口，对照检查 `deny-address-positive-control`（执行机能连上 checkip.amazonaws.com 的 80、443）也在这一格里 | N/A：没有监听 | N/A：没有监听 |

集群内的服务（colvenna-catalog、[Apache Polaris](https://polaris.apache.org/)、Marquez、Prometheus、Grafana、基于 [Apache Doris](https://doris.apache.org/) 的查询引擎、开发用的 [Apache Kafka](https://kafka.apache.org/)）不在公网可达的范围内。它们的认证与网络隔离由 UAT 的负向用例覆盖，见候选版本的 [UAT 用例](https://github.com/colvenna/colvenna-lake/blob/<候选>/docs/uat/cases.md)第 6 节。

## 4. 各区域的结果

<把第一个区域的 `section.md` 贴在这里>

<把另一个区域的 `section.md` 贴在这里>

## 5. 证据与访问

- 原始证据在 `<执行机的私有证据目录>/issue-208/<候选>/<env>/`，只有执行机的账号能读，不入库。每个目录有：
  - `raw/`：AWS 与集群的原始回答；
  - `inventory.tsv`、`others.tsv`：本环境与发布仓库的资源、账号里的其他资源；
  - `verdicts.tsv`：每项检查；
  - `denials/`：各探测的状态码与登录到达的位置；
  - `matrix.tsv`、`matrix.md`：拒绝矩阵与逐项证据；
  - `section.md`：第 4 节的内容。
- 证据不含凭证：测试口令、TOTP 密钥、会话 cookie、客户端密钥与服务账号 token 只放在运行期间的私有临时目录里，不写进证据，退出时删除。
- 两项扫描在最后运行，扫证据目录里的每个文件，包括 `section.md`：
  - `evidence-has-no-actual-credentials`：在临时文件删除之前，查找本次运行的这些值的完整副本。细节里写明每类扫了几个值；本次运行产生了某类凭证却一个值也没找到，也是 FAIL。
  - `evidence-has-no-secrets`：用模式查找 AWS 访问密钥、私钥、签名的 JWT、客户端密钥与 bearer token。
  - 发现即 FAIL，只报文件与类型或行号，不报匹配的内容。
  - 扫描的结果写进 `verdicts.tsv` 和 `section.md` 之后，再扫一遍；这一遍发现了什么，也记为 FAIL。

## 商标

> Apache, Apache Polaris, Polaris, Apache Doris, Doris, Apache Kafka and Kafka are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](https://github.com/colvenna/colvenna-lake/blob/<候选>/release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #208）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
