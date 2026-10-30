---
title: "Colvenna Lake Web 入口与控制台 LLD"
date: 2026-10-19
status: implemented
---

# Colvenna Lake Web 入口与控制台 LLD

本文是 M5 的实现设计：平台的 Web 界面（管理控制台、BI 查询界面）共用的公网入口与统一登录，以及管理控制台本身。依据 [HLD](hld-realtime-lakehouse.md) 第 11 节（运维）与 12.1–12.3 节（暴露面、访问控制、审计）。目前有"入口与登录"（#135）、"BI 查询界面"（#136）、"管理控制台：流水线全景"（#137）与"管理控制台：受控操作"（#138）四节。

所有资源都在环境的 AWS 账号与区域里（`COLVENNA_ACCOUNT`、`COLVENNA_REGION`，见[安装部署手册](../manual/install.md)第 1 节），与平台的其他组件同一个 EKS 集群。

## 入口与登录

### 组成

| 部分 | 位置 | 做什么 |
| --- | --- | --- |
| `ColvennaLake<Env>Entry` 栈 | `deploy/aws/src/entry-stack.ts`（`deploy/scripts/entry/deploy-stack.sh` 部署） | Cognito 用户池与它的组、登录域名与客户端；`*.<入口域名>`（默认 `colvenna-<env>.<托管区>`，`COLVENNA_ENTRY_DOMAIN`）的证书与各应用在托管区里的 DNS 记录；面向公网的 ALB、它的安全组与访问日志桶；每个应用一个目标组；控制台与 BI 的 Pod 各自的 IRSA 角色与镜像仓库（`colvenna-lake/colvenna-console`、`colvenna-lake/colvenna-bi`），以及 Load Balancer Controller 的 IRSA 角色；控制台的审计日志组与 Cognito 的用户活动日志组 |
| AWS Load Balancer Controller 3.6.0 | `deploy/scripts/entry/install-lbc.sh`，`kube-system` | 只做一件事：按 `TargetGroupBinding` 把应用的 Pod IP 注册进栈建好的目标组 |
| colvenna-console | `colvenna-console/`（Go），`deploy/helm/colvenna-console`，命名空间 `colvenna-console` | 控制台的服务端：验证每个请求的身份与角色，审计每个请求，渲染页面（服务端 HTML，没有前端构建） |

负载均衡器、监听器、规则和认证动作都在 CDK 里，由单元测试（`deploy/aws/test/entry-stack.test.ts`）约束：每条规则都是先 `authenticate-cognito` 再 `forward`，默认动作只回 404。Controller 不建、也不能建任何负载均衡器（见"网络"）。

### 一次请求

1. 浏览器访问 `https://console.<入口域名>`，例如托管区 `example.com` 里的环境 `uat` 默认是 `https://console.colvenna-uat.example.com`。ALB 只接受 TLS 1.2 与 1.3（`ELBSecurityPolicy-TLS13-1-2-Res-2021-06`），HTTP 一律 301 到 HTTPS，不认识的主机名回 404。
2. 主机名命中应用的规则，规则的第一个动作是 Cognito 认证。没有有效会话时，ALB 302 到用户池的授权端点，进入 Cognito 托管的登录页（经典版）：用户名、口令，然后是 TOTP。第一次登录在这里绑定 TOTP 应用。
3. 登录成功后，ALB 用授权码换来 token、取 userinfo，写下加密的会话 cookie（`colvenna-console-auth-0` 起，最多分 4 片），再带着两个头转发给 Pod：
   - `x-amzn-oidc-data`：ALB 用 ES256 签名的用户声明，头部有 `signer`（ALB 的 ARN）、`iss`（用户池）、`client`、`exp`；
   - `x-amzn-oidc-accesstoken`：用户的 Cognito access token（RS256），带 `cognito:groups`、`auth_time`。
4. 控制台对两个 token 都验签，并核对：
   - ALB 的声明：算法只认 ES256，公钥按 `kid` 从 `https://public-keys.auth.elb.<区域>.amazonaws.com/<kid>` 取（`kid` 必须是 UUID，取到后缓存；端点说没有的 `kid` 一分钟内不再取，其他失败下一个请求再取）；`signer` 是这个 ALB、`iss` 是这个用户池、`client` 是这个客户端、没有过期；
   - access token：算法只认 RS256，公钥取自用户池的 JWKS（遇到不认识的 `kid` 时重新加载，至多每分钟一次）；`iss`、`client_id` 相符，`token_use` 是 `access`，没有过期（容许 30 秒时钟偏差）；
   - 两者的 `sub` 相同。

   任何一项不满足，回 401；验证通过但没有角色，回 403。客户端自己带来的同名头会被 ALB 替换，即使到了 Pod，签名也过不了。
5. 角色只来自用户池的组，没有其他来源：

   | 组 | 角色 | 用途 |
   | --- | --- | --- |
   | `colvenna-admin` | 平台管理员 | 管理平台 |
   | `colvenna-operator` | 运维 | 运行流水线 |
   | `colvenna-bi` | BI 用户 | 经 BI 界面查询 |

   其他组不给任何角色。组与角色的对应只定义在栈里（`ROLE_GROUPS`），以输出 `RoleGroups` 交给各个应用（控制台的 `COLVENNA_ROLE_GROUPS`），应用不另写一份；角色本身的含义由应用决定。BI 查询界面与控制台用同一个用户池、同一个客户端，各自的会话 cookie 名不同。设计上，Cognito 自己的登录会话还在时（1 小时），登录过一个应用的用户打开另一个不必再输口令（BI 查询界面的验收见 [M5 BI 查询界面](../history/m5/bi.md)）。

### 会话

- **刷新。** access token 与 ID token 有效期 5 分钟，refresh token 12 小时。access token 过期时，ALB 用 refresh token 刷新用户的声明；刷新失败（用户被停用、在别处登出、refresh token 过期）就回到登录。所以停用账号、改组、登出，最迟在一个 access token 的寿命（5 分钟）内生效。
- **会话时长。** ALB 的会话时长默认 8 小时（`SESSION_TIMEOUT_SECONDS`），从登录时起算，期间的刷新不延长它；到时回到 Cognito。Cognito 自己的登录会话（1 小时）还在时，用户不必再输入口令。
- **登出。** 页面上的"退出登录"以 POST 发到 `/logout`，`Origin` 必须是控制台自己。控制台调用 `AdminUserGlobalSignOut` 作废这个用户的全部 refresh token，然后跳到 `/signed-out`；在那里把 ALB 的会话 cookie 设为过期，再跳到 Cognito 的 `/logout`，Cognito 结束自己的登录会话后回到首页，于是进入登录页。两步都和其他路径一样先经登录。cookie 不在第一步删：access token 过期时，ALB 会在那一步刷新会话，并在同一个响应里、在控制台的头之后写入新的 cookie；到第二步 token 是新的，ALB 不刷新，删掉的 cookie 就不会被写回。被复制走的会话 cookie 在它的 access token 过期后失效（≤5 分钟）。因为作废的是这个用户的全部 refresh token，同一用户在别的浏览器、在 BI 界面的会话也在 5 分钟内结束。

### 用户

- 用户池不开放自助注册，用户只由管理员建（`deploy/scripts/entry/users.sh add <用户名> <邮箱> <组>`，Cognito 把临时口令发到邮箱）。口令至少 14 位，含大小写字母、数字和符号；必须 TOTP，不用短信。客户端有 secret（只有 ALB 持有），OAuth 只开放授权码流程，API 只开放 token 刷新（`ALLOW_REFRESH_TOKEN_AUTH`）：口令、SRP 与自定义的登录流程都关着，登录只能走托管登录页。客户端不能修改用户属性。
- 用户池在 Plus 计划上，威胁防护是"仅审计"模式：记录每次登录的风险评估，不据此拦截（必须 TOTP 已经覆盖了它会要求的额外验证）。用户池开了删除保护。

### 网络

- ALB 在 VPC 的两个公有子网（M1 的 VPC 是 `10.84.0.0/24`、`10.84.1.0/24`），安全组入站只有 443 与 80，出站只有到任意 IPv4 的 443（认证动作要调用 Cognito 的 token 与 userinfo 端点，它们没有固定地址）和到集群安全组的 8443。
- 集群安全组只多了一条规则：放行来自 ALB 安全组的 8443。Pod 只有 ClusterIP 的 Service（供 `TargetGroupBinding` 找到 Pod），NetworkPolicy 只放行来自两个公有子网的 8443；集群里任何 Pod 都连不上控制台。kubelet 的探针来自节点，不受 NetworkPolicy 约束。
- ALB 到 Pod 也是 HTTPS：Pod 启动时生成自签名证书，ALB 不校验目标的证书，只用它加密。
- Controller 只处理 `TargetGroupBinding`：不建 IngressClass、不认 `kubernetes.io/ingress.class` 注解、关掉 Service 控制器与 Service 的变更 webhook、关掉 Ingress 校验。它的 IAM 角色只能查看负载均衡与网络资源，并在栈建的目标组上注册、注销目标；没有任何 `Create`、`Modify`、`Delete` 权限，所以即使有人建了 Ingress 或 `LoadBalancer` 类型的 Service，也开不出新的入口。它的 Pod webhook 都要求命名空间或 Pod 主动打标签才生效。

### 审计

| 记录什么 | 在哪里 | 按什么追溯 |
| --- | --- | --- |
| 登录的口令一步：通过记为 `InProgress`（接着是 TOTP），口令错或 TOTP 错记为 `Fail` 并写明失败的步骤；带风险评估与来源地址。验收中，被停用的账号口令通过后由登录页拒绝，日志里只有那条 `InProgress` | 用户池的用户活动日志，导出到日志组 `/aws/vendedlogs/colvenna-lake/user-activity`，保留 1 年 | `userName`、`userSub` |
| 登录完成（`signin`：Pod 第一次见到这次登录，依据是只有走完口令与 TOTP 才拿得到的 access token 的 `auth_time`）；之后的每个请求、每次拒绝（没有身份、签名不对、没有角色、跨站的登出）与每次登出 | 日志组 `/colvenna-lake/console-audit`，每个 Pod 一个日志流，保留 1 年；同时打在 Pod 日志里。同一次登录，每个 Pod 记一次，Pod 重启后会再记一次 | `sub`、`username`、`signedIn`（登录时刻，一次登录一个值） |
| ALB 处理的每个请求，包括它在认证前就拒绝的（302 到登录、伪造的授权码 401、未知主机 404） | 访问日志桶（`AccessLogBucket`），每 5 分钟一批，保留 1 年 | 来源地址、时间、user agent、`error_reason` |
| 托管登录页的请求与用户池的管理操作，包括被停用的账号被拒（`Login_Error_POST`，"User is disabled."） | 账号的 CloudTrail | 登录事件里 Cognito 隐去了用户名，只有部分事件带 `sub`；被拒的登录要按时间与来源地址与用户活动日志关联 |

控制台在写不进审计时拒绝服务：审计队列满了，或者 CloudWatch Logs 已经连续 30 秒写入失败，请求得到 503。拒绝本身照样回给客户端，并尽量记下。Pod 停止时，先停止接收请求、等处理中的请求结束，再把审计队列里余下的事件发完（代码如此，没有单独的测试）。

## BI 查询界面

BI 用户在浏览器里对平台的 Doris 做 ad-hoc SQL 查询、建图表与看板，读到的数据与权限和直连 Doris 时一致（#136）。界面是 Apache Superset 6.1.0，在入口后面的主机名是 `bi.<入口域名>`，登录就是入口的登录。

### BI 的组成

| 部分 | 位置 | 做什么 |
| --- | --- | --- |
| BI 镜像 | `colvenna-bi/`（`deploy/scripts/entry/build-bi.sh` 构建） | `apache/superset:6.1.0-py311` 按 digest 固定，加上 PostgreSQL 与 Doris 的驱动（psycopg2-binary、pydoris、mysqlclient）、boto3，以及平台的扩展 `colvenna_bi` 与 `superset_config.py` |
| `colvenna_bi` | `colvenna-bi/colvenna_bi/` | Superset 进程里运行的扩展，只能用 Superset 的语言（Python）：身份（`identity`、`security`）、连接用哪个 Doris 用户（`credentials`、`mutator`）、没有 `information_schema` 时的元数据浏览（`dialect`）、看板对应的 Superset 数据库与角色（`sync`、`setup`） |
| chart | `deploy/helm/colvenna-bi`（`deploy/scripts/entry/install-bi.sh` 安装），命名空间 `colvenna-bi` | 一个 Web 进程（gunicorn，8443，自签名证书）与同步 sidecar；安装与升级前，一个 Job 跑 `superset db upgrade`、`superset init` 与 `colvenna_bi.setup` |
| Operator 的 `BiCredentials` | `colvenna-operator`，LakeDashboard 的 `users[].sso` | 把带登录名的看板用户的 Doris 身份发布到 BI 命名空间的一个 Secret |

元数据库在项目的 RDS PostgreSQL 上（库与角色 `superset`，校验证书的 TLS）。Superset 的 `SECRET_KEY` 在 Secret 里，安装时生成，重装时保留。

### 一次请求是谁

`colvenna_bi.security` 注册为 Flask 的第一个 `before_request`（`FLASK_APP_MUTATOR`）。每个请求都重新判断是谁，Superset 自己的会话不作数：

1. 验证 ALB 的声明与 Cognito 的 access token，规则与控制台相同（见"入口与登录"）。不通过回 401。
2. 角色：`colvenna-admin` 得到 Superset 的 `Admin`；`colvenna-bi` 得到 `ColvennaBI`，外加这个人作为用户的每个看板一个角色 `Dashboard <命名空间>/<名字>`（只能访问那个看板的 Superset 数据库）。`colvenna-operator` 在这里没有角色，回 403。
3. 第一次见到这个人时建 Superset 用户：用户名是入口的用户名，邮箱是 `<sub>@users.colvenna-lake.invalid`，口令随机、谁也不知道。之后每个请求都把它的角色同步成上面算出的，然后以这个用户处理请求。用户池把同一个用户名给了另一个人（sub 不同）时，旧账号不给新的人，回 403，由管理员处理。改组、停用在入口那边最迟 5 分钟生效；从看板去掉要等 Operator 的一轮、kubelet 同步 Secret 卷与数据库同步，验收中约一分钟。
4. Superset 别的登录方式一律拒绝：`/api/v1/security/login`、`/refresh`、guest token、登录与注册页回 404；带 `Authorization` 头（API token）的请求回 401。
5. 登出是 Superset 菜单的 `/logout/`：只接受本站页面发起的请求（`Sec-Fetch-Site` 为 `same-origin` 或 `none`，没有这个头也拒绝），全局作废这个人的 refresh token，然后跳到 `/signed-out`；那里清掉 BI 主机的 ALB 会话 cookie 与 Superset 的会话 cookie，再经 Cognito 的 `/logout` 回到 BI 首页。两步都先经登录，没有角色的人也能登出（为什么分两步，见"入口与登录"的"会话"）。

### 每个人以自己的 Doris 用户查询

Doris 4.1.4 没有代理用户或 `EXECUTE AS`；Superset 的"以登录用户身份执行"对 Doris 只换用户名、不换口令。所以连接由 `DB_CONNECTION_MUTATOR` 决定，Superset 的数据库里不存口令：

- **映射在声明里。** LakeDashboard 的用户可以带 `sso.username`（入口的用户名；同一看板里一个人只能对应一个用户）。一个人在两个看板里，就是两个 Doris 用户，各属其看板。
- **Operator 发布身份。** 看板的 Doris 用户建好后，Operator 把带登录名的用户写进 `colvenna-bi/colvenna-bi-doris-users`：键是 Doris 用户名，值是登录名、Doris 用户名、口令（就是声明的 `passwordSecret` 里的那个）、看板的 Doris 库与看板名。它只改这个看板的条目；看板被拒、去掉登录名或被删除时撤回。Operator 对这个 Secret 只有 `get` 与 `update`，安装脚本建空的 Secret。Superset 以只读卷挂载它（不用 subPath，随 Secret 更新），卷的数据链接一变就重读。
- **连接时换上本人。** Superset 每建一个 engine（SQL Lab、图表、看板、元数据浏览、导出）都经过 mutator：guest 拒绝；请求的用户不是这个连接的用户拒绝；主机与端口不是平台 Doris 的 FE 拒绝；URI 的库不是某个看板的库、或这个人不是那个看板的用户，拒绝。都通过时，把 URI 里的用户名与口令换成这个人的，不管数据库里原来写的是什么。所以同一个看板，每个人看到的是自己的 Doris 用户能看到的：#32 的授权、#35 的脱敏与行过滤照常生效。
- **审计。** Doris 的审计记的是连接的 Doris 用户，按人追溯以它为准。Superset 发出的每条查询（SQL Lab、图表、导出，包括导出别人存下的查询）前面再加上注释 `/* colvenna-bi user=<用户名> sub=<sub> */`，已有的这种注释先去掉：做法是包裹各条路径都会调用的 `Database.mutate_sql_based_on_config`，而不是 `SQL_QUERY_MUTATOR`（6.1 里它只在拆分语句之前或之后之一被调用）。驱动与 Superset 自己发的连接设置（`SET NAMES`、`SELECT @@…`、`ROLLBACK` 等）与浏览库表时的 `SHOW` 不带注释，它们同样是本人的 Doris 用户。注释只是佐证，不是授权依据（谁都可以自己写这样的注释）。
- **看板对应的 Superset 数据库。** 同步 sidecar 每 30 秒按发布的身份维护：每个看板一个数据库 `<命名空间>/<名字>`，URI 是 `doris://sso@<FE>:9030/internal.<看板库>`（没有口令），关掉 DML、CTAS、CVAS、异步与上传；以及一个只能访问它的角色。看板的人都没了，角色删掉；数据库与上面的数据集、图表留着，但谁也连不上。

### 元数据浏览

Operator 撤销了看板用户的 `information_schema` 与 `mysql`。pydoris 1.2.0 列库、列表、列列用的是 `SHOW`，但判断表是否存在（`has_table`，`get_columns` 先调它）和取表注释查的是 `information_schema`。`colvenna_bi.dialect` 注册为 `doris://` 的方言，把这两项改成 `SHOW FULL TABLES`，表注释不取。每个人浏览到的库与表，就是 Doris 对他自己的 `SHOW` 给出的。

### 不在人与人之间留下结果

- 没有 results backend，也不跑异步 SQL Lab：存下的结果按 key 取，Superset 只核对数据库的访问权限、不核对结果是谁的。SQL Lab 的 CSV 导出由导出的人以自己的身份再查一次。
- 数据缓存、元数据缓存都是 `NullCache`；feature flag `CACHE_QUERY_BY_USER` 打开，以后有人开数据缓存时也按人分开（元数据缓存的键不含人，所以它必须保持关闭）。
- 关掉告警与报表（以 owner 的身份查询再发给别人）、缩略图、嵌入与 guest token、全局异步查询、Jinja 模板、meta DB、SSH 隧道。
- 共享的看板与数据集：每个看的人都以自己的身份查询。看板、数据集的定义（标题、SQL、筛选默认值）对能看到它的人可见。
- 导出（CSV）是导出的人自己能看到的、已经脱敏与过滤的行；允许与否是 `ColvennaBI` 角色的权限。

### 网络与加固

- Pod 只有 ClusterIP 的 Service（给 `TargetGroupBinding`），NetworkPolicy 只放行 ALB 的子网；命名空间带 `colvenna-lake.io/doris-client`，所以连得上 Doris FE 的 MySQL 端口。
- Pod Security "restricted"，以 1000 运行，根文件系统只读（`/tmp` 与 `/app/superset_home` 是 emptyDir）。
- Talisman 的 CSP 只允许本站；会话 cookie 是 Secure、HttpOnly、SameSite=Lax，8 小时；`ENABLE_PROXY_FIX`；Public 角色没有权限，不开放注册，不提供 Swagger UI。
- IRSA 角色只能 `AdminUserGlobalSignOut`。

### 剩余风险与限制

- **长期凭证。** BI 进程持有每个带登录名的用户的 Doris 口令（在 Secret 里，不在元数据库里）。BI 进程被攻破，这些身份都泄露；每个身份只有它看板对象上的 `SELECT`，带着它自己的脱敏与行过滤。Doris 的 OIDC 认证插件发行版里没有，留作后续选项。
- **同一个口令。** 直连 Doris 与经 BI 用的是同一个 Doris 用户、同一个口令。
- **Superset 的管理员。** `Admin` 能看到所有人保存的查询与 SQL 历史（可能含字面量里的个人数据）；Superset 的元数据库因此是个人数据的又一个副本，还没有纳入删除流程。
- **明文协议。** BI 到 Doris FE 是集群内的明文 MySQL 协议（与 M3 相同）。
- **单副本。** 一个 Web 进程，没有高可用。
- **BI 自身的审计。** Superset 记下的登录、拒绝与登出只在 Pod 日志里（不保留，写不出也不拒绝服务）；按人追溯查询以 Doris 的审计为准。
- **看板删除之后。** Superset 里那个看板的数据库与上面的数据集、图表留着，谁也连不上；要由管理员清理。

## 管理控制台：流水线全景

平台管理员与运维在控制台里看到整条流水线：每个数据源、湖表、分层作业与看板的状态、延迟、积压、告警和数据的流向，并能看出问题出在哪个环节（#137）。全景只读：它不改资源、作业或数据，也不展示表数据、Secret、token 与口令；变更是下一节的受控操作。

### 流水线的组成

| 部分 | 位置 | 做什么 |
| --- | --- | --- |
| `internal/pipeline` | `colvenna-console/internal/pipeline/` | `sources.go` 读 Kubernetes 的声明与 Prometheus 的指标、告警；`model.go` 把它们拼成节点与边，挂上指标与告警，标出问题；`render.go` 画成 SVG |
| 页面与接口 | `colvenna-console/internal/server/` | `GET /pipeline`（全景图与出问题的环节）、`GET /pipeline/node?id=`（一个节点的详情）、`GET /api/pipeline`（同样的内容，JSON） |
| 读权限 | `deploy/helm/colvenna-console/templates/rbac.yaml` | 在 `pipeline.namespaces` 的每个命名空间一个 Role：`lakesources`、`laketables`、`lakedashboards`、`laketasks` 与 `flinkdeployments` 的 `get`、`list` |
| Prometheus 的放行 | `deploy/helm/colvenna-monitoring`（`prometheus.readerLabel`） | Prometheus 的 NetworkPolicy 放行带 `colvenna-lake.io/prometheus-reader` 标签、又在带同一标签的命名空间里的 Pod；控制台的 chart 给它的 Pod 打上这个标签，`install-console.sh` 给它的命名空间打上 |

### 画面从哪里来

每个请求都重新读，什么也不存：画面的来源是 Kubernetes 里声明的 status 与 Prometheus，控制台没有另一份会漂移的状态。

- **节点。** 数据源（`LakeSource`）、湖表（按 `库.表`，同一张表只有一个节点）、分层作业（`LakeTable`）、看板（`LakeDashboard`）。数据源与分层作业带它们的每个 FlinkDeployment 的生命周期与 Flink 作业状态（一个源可以有几个作业）；`LakeTask` 挂在它所处理的那张表上（开始之前按 `spec.table`）。
- **边，即数据的流向。** 取的是 Operator 上报给血缘目录的那份来源，所以与 Marquez 一致（见 [Operator LLD](colvenna-operator-lld.md) 的"血缘"）：
  - 数据源 → 它写的表：CDC 源是 `status.tables` 里有 `target` 的表（`spec.target.database` 下）；事件源是它的表与 `_rejections`。
  - 表 → 分层作业 → 表：planner 为正在运行的查询找到的 `status.applied.streams` 与 `lookups`，写的是 `status.applied.target` 与隔离表。还没有运行时，用声明的 `upstream` 与 `target`。
  - 表 → 看板：看板声明的 `tables`。Marquez 按每条查询的 SQL 实际读到的表；一张声明了、但没有查询读的表，控制台会多画一条边。
- **状态。** status 的 `phase`、`message` 与 conditions，作业的上游滞后（快照数与秒数），看板每条查询的服务方式与刷新，任务的阶段。只取这些字段：status 里的身份（principal、token id）、看板用户的口令封、策略都不取。
- **指标。** 来自 Operator 导出的指标（M3 #29），按标签归到节点；一个节点有几条序列时取最大值（滞后、年龄、计数都是越大越差）：

  | 指标 | PromQL | 节点 |
  | --- | --- | --- |
  | `sourceLatencySeconds`、`sourceFreshnessSeconds` | `colvenna_source_latency_seconds`、`colvenna_source_freshness_seconds` | 数据源 |
  | `kafkaLag` | `sum by (namespace, source) (colvenna_source_kafka_lag)` | 数据源 |
  | `upstreamLagSeconds`、`layerLatencySeconds` | `colvenna_table_upstream_lag_seconds`、`colvenna_table_layer_latency_seconds` | 分层作业 |
  | `quarantinedRows` | `sum by (namespace, laketable) (colvenna_table_quarantined_rows)` | 分层作业 |
  | `secondsSinceCheckpoint` | `time() - colvenna_job_last_checkpoint_timestamp_seconds` | 分层作业或数据源 |
  | `freshnessSeconds`、`level0Files`、`smallFiles`、`snapshots` | `colvenna_table_freshness_seconds`、`colvenna_table_level0_files`、`colvenna_table_small_files`、`colvenna_table_snapshots` | 湖表 |
  | `copyAgeSeconds` | `time() - colvenna_dashboard_refreshed_timestamp_seconds` | 看板 |

  表的快照、文件数与新鲜度是 Operator 从 colvenna-catalog 与湖里读出、以指标给出的；控制台不另外去读 colvenna-catalog（见下文"控制台的身份"）。
- **告警。** Prometheus 里 pending 与 firing 的告警按标签挂到节点：`laketable` → 分层作业，`source` → 数据源，`lakedashboard` → 看板，`table` → 湖表。带着别的命名空间的告警不显示；归不到节点的（例如 `ColvennaMetricsDown`）列在"不属于某一环节的告警"里。
- **读不到的时候。** Kubernetes 读不到，页面回 503；Prometheus 读不到，仍然画出声明的部分，并在页面上列出 `ConsoleMeasuresMissing` 或 `ConsoleAlertsMissing`。

### 定位问题

- **出问题的环节。** `phase` 是 `Degraded` 或 `Failed`，或者有 firing 的告警（pending 的只显示，不算）。页面上列出这些环节与原因。
- **受影响的。** 出问题的环节的下游都标为受影响，并记下受哪些环节影响（`affectedBy`，节点页上可以点过去）；上游不标。下游的环节自己也有告警时，它既是出问题的环节，也记下受谁影响。
- **新鲜度告警。** `ColvennaFreshnessP99`、`ColvennaFreshnessUnmeasured` 量的是查询看到的数据有多旧，即整条路径的结果。上游有一个让数据迟到的问题时，它们只说明这个环节受了影响；这类问题是 `phase` 为 `Degraded`、`Failed`，M0 的延迟告警、源的变更不再到达、作业不再提交（`ColvennaSourceLatencyP95`/`P99`、`ColvennaSourceStale`、`ColvennaLayerLatencyP95`、`ColvennaLayerLagP99`、`ColvennaNoCheckpoint`、`ColvennaCheckpointsFailing`、`ColvennaDegraded`），或者上游一个环节自己的、没有更上游原因的新鲜度告警。上游只有不让数据迟到的问题（例如隔离了坏行）或者没有问题时，这个环节就是出问题的环节。

### 谁能看

- 可见范围按角色：`admin` 与 `operator` 能看全景、节点与接口，两者看到的相同；`bi` 回 403（"流水线与操作只对平台管理员与运维开放"）；没有角色的用户在入口这一步就回 403（"你的账号还没有分配角色"）；没有会话的请求由 ALB 跳到登录。看与被拒都写进审计（`request`、`denied`）。
- 流水线全景的路由只有 `GET`（`/pipeline`、`/pipeline/node`、`/api/pipeline`）；整个控制台另外只有两个 `POST`：`/logout`（见"会话"）与受控操作的 `/operations/{op}`（见"受控操作"）。其他方法回 405，同样写审计。

### 控制台的身份

- **Kubernetes。** 全景用控制台 ServiceAccount 自己的权限读，就是上面那个 Role：五种资源的 `get` 与 `list`，没有 Secret、ConfigMap、`pods/exec`、`pods/portforward` 与 `services/proxy`，也没有写权限。受控操作（下一节）不用它，而是扮演控制台的两个用户之一。
- **Prometheus。** Prometheus 没有认证，可读与否由网络决定：NetworkPolicy 只放行带 `colvenna-lake.io/prometheus-reader` 标签、又在带这个标签的命名空间里的 Pod，即控制台的 Pod。它没有开 admin API、lifecycle 与 remote write 接收，所以连得上也只能读。
- **Marquez：不连。** Marquez 没有认证，能连上就能写（`install-marquez.sh` 只放行 Operator 的命名空间）。边取自 Operator 上报给它的同一份来源，验收时逐个作业比对两者。
- **colvenna-catalog：不持有身份。** colvenna-catalog 最低的权限 `READ` 也会签发表数据的只读 S3 凭证（数据凭证是 `aws-sts` 模式），持有它就能读整个湖。控制台的命名空间也不带 `colvenna-lake.io/catalog-client` 标签，连不上 colvenna-catalog。表的元数据以 Operator 导出的指标为准。
- **AWS。** 控制台的 IRSA 角色是 #135 的：只能写它自己的审计日志组、在用户池里全局登出用户，碰不到平台的数据与作业。
- **Grafana、Marquez、Flink Web UI：不链接，不嵌入。** 三者都只能经 `kubectl port-forward` 访问，而能访问 Flink REST 就等于作业管理员。

### 流水线的剩余风险与限制

- **Prometheus 按标签放行。** 能在控制台的命名空间里建 Pod、并给它打上那个标签的人，就能读 Prometheus。指标里有库、表、作业与看板的名字，没有表数据与凭证。
- **没有表的 schema。** 因为不持有 colvenna-catalog 的身份，节点上没有列与类型；要看 schema 走 Marquez（经 port-forward）或 colvenna CLI。
- **每次全量读。** 每个请求都列出所配命名空间的全部声明，并做十几次 Prometheus 查询，没有缓存；声明很多时页面会变慢。
- **归属靠标签。** 指标与告警挂到哪个节点，依赖 Operator 给的标签；只带 `table` 标签的 colvenna-catalog 告警挂到湖表，归不到节点的列在页面底部。
- **定位是按图推断。** "受影响"是按声明的流向往下游推，不是实际观测到的：一个环节出问题时，下游即使数据还新也标为受影响。新鲜度告警的归属按上面的规则推断，验收中没有触发过新鲜度告警，这条规则只有单元测试。
- **只验收了 MySQL CDC 源。** 事件源（Kafka）的边与 `kafkaLag` 只有单元测试；CDC 源本身没有积压指标，积压看它写的 ODS 表（文件数、快照数）与下游作业的滞后。

## 管理控制台：受控操作

有权限的运维在控制台里完成 colvenna CLI 已有的变更，每个操作都经过授权、必要时先确认，并留下审计（#138）。控制台不新增 CLI 没有的操作语义：操作的对象由 CLI 自己的代码生成，经 Kubernetes API 生效，由 API server 的 RBAC 与准入、由 Operator 照常判定。

### 操作的组成

| 部分 | 位置 | 做什么 |
| --- | --- | --- |
| `colvenna-cli/ops` | CLI 模块里的公开包 | 每个操作生成什么对象、怎么提交：server-side apply（field manager 是调用方的）、挂起与恢复、删除、各种 `LakeTask`、`LakeWaiver`、合规删除；`colvenna` 的命令与控制台都用它 |
| `internal/operations` | `colvenna-console/internal/operations/` | 控制台的操作清单（名字、角色、是否要确认、表单字段）、以本人身份的 Kubernetes 客户端、确认的签名 |
| 页面与接口 | `colvenna-console/internal/server/operations.go` | `GET /operations`（表单），`POST /operations/{操作}`（预览或执行） |
| RBAC | `deploy/helm/colvenna-console/templates/rbac.yaml` | 控制台能扮演的两个用户与用户附加信息；这两个用户在所管命名空间的权限 |
| 豁免的准入 | `deploy/helm/colvenna-operator/templates/waivers.yaml` | 经控制台建的豁免，审批人是 `<控制台用户>/<本人>` |
| 确认的密钥 | Secret `colvenna-console/colvenna-console-confirm`（`install-console.sh` 生成） | 签名确认；各个 Pod 用同一把 |

### 有哪些操作

| 操作 | 对应的 CLI | 角色 | 确认 |
| --- | --- | --- | --- |
| 应用声明 | `colvenna apply -f` | 运维 | — |
| 挂起、恢复 | `colvenna suspend`、`colvenna resume` | 运维 | — |
| 回刷 | `colvenna backfill` | 运维 | 要 |
| 压缩、打 tag | `colvenna maintain compact/tag` | 运维 | — |
| 过期快照、清理孤儿文件 | `colvenna maintain expire/orphans` | 运维 | 要 |
| 质量检查 | `colvenna check` | 运维 | — |
| 删除声明 | `colvenna delete` | 管理员 | 要 |
| 豁免 | `colvenna waive` | 管理员 | — |
| 合规删除 | `colvenna forget` | 管理员 | 要 |

管理员也能做运维的操作。`colvenna get`、`describe`、`wait` 只读，见流水线全景。经"应用声明"不能建 `LakeErasure`、`LakeWaiver`，以及回刷、过期快照与清理孤儿文件的 `LakeTask`：删除请求的主体要放进 Secret，豁免要写明审批人，后三者要先确认，它们有自己的操作；种类按 CLI 认的任何名字判断。清单里的对象也不能属于表单之外的命名空间。哪些操作只给管理员，是这样定的：删掉声明会按 `deletionPolicy` 删表，合规删除涉及个人数据，豁免是数据负责人的判断。

### 以本人的身份

控制台以它自己的 ServiceAccount 调用 Kubernetes API，扮演两个专用的用户之一，并把本人作为用户附加信息带上：

- 管理员扮演 `colvenna-console:admin`，运维扮演 `colvenna-console:operator`；附加信息 `colvenna-lake.io/person` 是本人的用户名，`colvenna-lake.io/sub` 是 sub。请求的 User-Agent 是 `colvenna-console/<用户名>`。
- 控制台的 ClusterRole 只允许扮演这两个用户（`resourceNames`）和这两项附加信息，不能扮演别的用户、组或 ServiceAccount。扮演任意用户名会让控制台能冒充集群里的任何身份，所以没有采用"每人一个 Kubernetes 用户"。
- 这两个用户的权限由所管命名空间里的 Role 给出：`colvenna-console:operator` 能读、建、改声明与任务（不能删）；`colvenna-console:admin` 另外能删除这些资源，能建 `LakeErasure`，能建、改 `LakeWaiver`（豁免是 server-side apply，要 `patch`），能建 Secret（只能建，不能读）。Operator chart 给数据负责人的豁免 ClusterRole 同样补上了 `patch`：`colvenna waive` 也是 server-side apply。控制台自己的角色判断之外，API server 再按这两个用户判断一次。
- 集群的审计日志里，每个请求的 `user` 是控制台的 ServiceAccount，`impersonatedUser` 是它扮演的用户，附加信息里有本人。
- 豁免的审批人：`ops.Whoami` 读 SelfSubjectReview，得到 `colvenna-console:admin/<用户名>`；豁免的准入策略对这两个用户要求注解等于这个值，对其他用户仍要求等于用户名。

### 确认

删除、回刷、过期快照、清理孤儿文件与合规删除分两步：

1. 第一次提交只预览，列出影响范围，并在页面里给出确认：
   - 删除数据源与分层表：它的 `deletionPolicy` 与下游；删除看板与其他声明（质量规则、豁免、删除请求、任务、Iceberg 发布）：随之撤下的是什么。都先以本人的身份读出这个对象；
   - 回刷：写这张表的作业与下游（不是这个命名空间的分层作业写的表，没有确认）；
   - 过期快照、清理孤儿文件：哪些快照或文件、之后读不到也恢复不了；
   - 合规删除：键值的个数、期限与这个命名空间写的湖表。

   读不到流水线或这个对象时，看不出影响范围，就不给确认（对象不存在 404、无权读 403、其他 503）。
2. 确认是对这个人、这个操作、这个命名空间与表单里每个字段的 HMAC-SHA256 签名，5 分钟内有效。删除时，预览还把它读到的对象的 uid 与 generation 放进表单、一起签名：执行时对象换了或 spec 变了（比如 `deletionPolicy` 改了），回 409；删除请求本身也带着 uid 的前提条件。第二次提交带着确认才执行；改了任何字段、换了人、过了期，都回 403，什么也不改。

取消就是不提交第二步：什么也没有改，审计里只有一条预览。

### 请求与审计

- 只接受本站页面发起的请求（`Origin` 等于控制台的地址），表单最大 1 MiB；命名空间必须是所管的。
- 需要管理员的操作，运维直接调用接口也回 403；页面上不显示不等于授权。这类拒绝与确认不符一样，记为一条 `operation` 事件，带着操作名与对象。
- 每个操作在审计里有：预览（`previewed`，要确认的操作）、执行前的一条（`requested`），以及结果（API server 的状态码与回答，或者产生的对象）。执行前那条写不进审计时回 503，什么也不改。事件的 `action` 是操作名，`resource` 是 `命名空间/种类/名字`。
- 合规删除的键值是个人数据：不进审计、不进结果页；只在确认页里作为隐藏字段交回给本人，再随第二步提交。

### 与 CLI 的同与不同

- 同：对象由同一份代码生成；都经 Kubernetes API、同样的 RBAC 与准入、同样的 Operator 判定。
- field manager 不同：CLI 是 `colvenna-cli`，控制台是 `colvenna-console`。
- 合规删除的顺序改了（CLI 也一样）：先以 dry run 试建 Secret，名字已被占用就拒绝，不会把别人的 Secret 当成主体；再建 `LakeErasure`（它会等 Secret），最后建它拥有的 Secret。所以只需要建 Secret 的权限，不需要读或改；最后一步失败时，刚建的删除请求随即删掉。
- 删除只发出请求，不等 Operator 清理完；结果在流水线的节点页上看。CLI 的 `--wait` 在控制台里没有对应。

### 操作的剩余风险与限制

- **控制台能做两个用户能做的事。** 控制台被攻破时，攻击者能以 `colvenna-console:admin` 改、删所管命名空间的声明，建 Secret；不能读 Secret，不能扮演别人，也碰不到别的命名空间。
- **本人是控制台说的。** 附加信息里的本人来自控制台验证过的 token；API server 不再核对它。
- **确认的密钥。** 拿到 Secret `colvenna-console-confirm` 的人可以为任何请求伪造确认，但仍要以有这个角色的人登录。
- **没有批量与撤销。** 一次一个操作；不可逆的操作确认之后就不能撤回。

## 入口的剩余风险与限制

- **复制的会话。** 登出、停用、改组之后，一个被复制走的会话 cookie 还能用到它的 access token 过期（≤5 分钟）。缩短不了：5 分钟是 Cognito access token 的下限。
- **登出的范围。** 登出作废的是用户的全部 refresh token，同一用户的其他会话也随之结束。
- **登录页。** 用的是 Cognito 经典版托管登录页，没有品牌定制；新版托管登录页没有验证。
- **没有 WAF。** 入口前没有 WAF 或速率限制，暴力尝试由 Cognito 自身的限流与 TOTP 抵挡。
- **日志投递。** 用户活动日志与 ALB 访问日志都是尽力投递，分别有分钟级与 5 分钟的延迟。
- **被停用账号的登录。** 验收中，拒绝不在用户活动日志里，CloudTrail 里的那条又不带用户名，只能按时间与来源地址关联。
- **单区域。** 用户池、证书和 ALB 都在环境所在的区域；没有做跨区域的高可用。

> 注：本文中的 issue 与 PR 编号（如 #135）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
