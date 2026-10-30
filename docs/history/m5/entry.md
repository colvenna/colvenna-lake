---
title: "M5 Web 入口与统一登录"
date: 2026-10-19
status: complete
issue: 135
---

# M5 Web 入口与统一登录

本页记录 Issue #135 的验收：浏览器用户经一个公网入口单点登录后使用平台的 Web 界面，未登录的请求一律被拒。设计见 [Web 入口与控制台 LLD](../../design/colvenna-console-lld.md) 的"入口与登录"，依据 HLD 12.1–12.3。入口后面是管理控制台（#137、#138 在它上面扩展）与 BI 查询界面（[#136](bi.md)）。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），与 M1–M4 同一个 EKS 集群，只用合成的用户。入口的域名是 `*.cove.<COVE_HOSTED_ZONE_NAME>`，控制台是 `https://console.cove.<COVE_HOSTED_ZONE_NAME>`，身份源是 Cognito 用户池（账号没有启用 IAM Identity Center）。

**证据的来源版本**：结果来自提交 `d549388`，私有证据目录 `m5-135/run-e2-d549388/`。镜像 `cove-console@sha256:8140cf39…`；AWS Load Balancer Controller 3.6.0（chart 按校验和固定，镜像 `sha256:337a9bf9…`）。验收时 ALB 的会话时长临时设为 600 秒（`SESSION_TIMEOUT_SECONDS=600`），以便在运行内看到会话到期；运行之后恢复为默认的 8 小时。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 未认证的请求在每个路径上都被拒：没有会话、会话过期或伪造、绕过 ALB 直连后端；只有登录流程可达 | 通过 | 没有会话时，6 个路径（包括 `/healthz`、静态文件、`/logout` 与不存在的路径）和 POST `/logout` 都只得到 302 到 Cognito 的授权端点，响应里没有控制台的内容。伪造的会话 cookie 得到 302，伪造的授权码得到 401。会话在登录后 614 秒（时长 600 秒）回到登录。HTTP 301 到 HTTPS，TLS 1.1 握手失败，入口不服务的主机名回 404，Cognito 不开放自助注册。集群里的 Pod 连不上控制台（按 Pod IP 与按 Service 都超时，对照的 cove-catalog 回 401）；经认证的 port-forward 绕过网络策略，送到 Pod 的伪造身份（不带身份头，以及 `alg: none` 的、签名不对的两种声明）都得到 401 |
| 登录后用户得到所映射的角色；登出、会话过期、在身份源停用用户之后，访问被拒 | 通过 | 登录要口令加 TOTP：口令错停在登录页，TOTP 错停在 MFA 页。9 个用户按所在的组得到 `admin`、`operator`、`bi`（两个组的用户两个角色都有），没有组的用户被控制台以 403 拒绝。客户端自己带的身份头被 ALB 换掉，读到的仍是本人。登出回到登录页，浏览器的会话立即失效，事先复制的会话 cookie 在 237 秒后失效。被停用的用户 237 秒后失去会话、再登录时被拒（"User is disabled."）。改组在 238 秒后生效。三者都在 access token 的寿命（300 秒）之内 |
| 登录、登出与拒绝都写入审计，可按用户追溯 | 通过；被停用账号的登录被拒只能关联 | 9 个用户的每次登录在控制台审计里都有 `signin` 事件（按用户名与登录时间窗核对），在用户池的用户活动日志里都有口令通过的记录；口令错、TOTP 错各有一条 `Fail`，写明失败的步骤；有角色的用户的请求都按 sub 与登录时刻记下；没有角色、伪造身份的拒绝与登出都有记录；ALB 的访问日志里有它在认证前拒绝的请求。被停用的账号尝试登录时，用户活动日志只记了口令通过，拒绝记在 CloudTrail 里、不带用户名，两者按时间与来源地址对上了（见"限制"） |
| HLD 12.1 的暴露面清单与实际部署一致 | 通过 | 这个 VPC 里只有一个面向公网的负载均衡器，就是入口的 ALB；它只有 80（跳转）与 443 两个监听器，443 上的每条规则都是先认证再转发，默认回 404。集群里没有 `LoadBalancer`、`NodePort` 类型的 Service，也没有 Ingress。表里的其他行：EKS 的公网端点只对一个 `/32` 开放，VPC 里的数据库都不公开，账号级与平台两个桶的公共访问拦截都开着 |

## 运行

`infra/m5/scripts/deploy-stack.sh` 部署 `CoveLakeM5`，`install-lbc.sh` 装 AWS Load Balancer Controller，`build-console.sh`、`install-console.sh` 构建并安装控制台。`infra/m5/flows/entry/run-entry.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL。浏览器由 `entry.py` 模拟：它像人一样在 Cognito 的托管登录页上填口令、在第一次登录时从页面读出 TOTP 密钥并绑定，此后按 RFC 6238 算出验证码；只跟随到入口与用户池的重定向。

1. **用户。** 在用户池里建 9 个合成用户，口令随机生成、以文件传给 AWS CLI。组：`admin`（`cove-admin`）、`op`（`cove-operator`）、`bi`（`cove-bi`）、`multi`（`cove-admin` 与 `cove-bi`）、`none`（没有组），以及 `out`、`off`、`idle`、`promo` 四个用来结束会话的用户。运行结束时删掉。
2. **没有会话。** 依次请求 `/`、`/api/me`、`/healthz`、`/static/console.css`、`/logout`、一个不存在的路径，再 POST `/logout`：要求都是 302 到用户池的 `/oauth2/authorize`，没有控制台的内容。另外：
   - HTTP 301 到 HTTPS；
   - 限定 TLS 1.1 的握手失败；
   - 经 ALB 的地址访问不服务的主机名（`other.cove.<COVE_HOSTED_ZONE_NAME>`），或直接用 ALB 自带的域名，都回 404；
   - 随机的会话 cookie 得到 302；伪造的授权码得到 401；
   - 用户池的 `/signup` 跳到错误页。
3. **登录。**
   - 用错的口令登录，停在登录页；
   - 每个用户登录，`/api/me` 的角色与组对应；
   - 没有组的用户得到 403；
   - 已绑定 TOTP 的用户输错验证码，停在 MFA 页；
   - 已登录的用户自己带上伪造的 `X-Amzn-Oidc-Data`、`X-Amzn-Oidc-Accesstoken`（声称是管理员），读到的仍是本人。
4. **绕过 ALB。**
   - 从作业命名空间的 Pod，按 Pod IP 和按 Service 连控制台：都没有连上（curl 超时，连接时间 0）；同一个 Pod 连 cove-catalog 得到 401，作为对照；
   - 经 `kubectl port-forward`（IAM 认证）直接到 Pod，不带身份头、带 `alg: none` 的声明、带签名不对且密钥不存在的声明，都得到 401；`/healthz` 是 200。后两种都带着不存在的 `kid`，在取密钥这一步就被拒；只认 ES256 由单元测试覆盖。
5. **结束会话。** 先复制 `out` 的会话 cookie，再让它从页面退出登录：要求经 Cognito 的 `/logout` 回到控制台、再到登录页，之后它的浏览器访问得到 302。同时停用 `off`（只停用，不另行登出），把 `promo` 加进 `cove-operator`。之后每 20 秒检查一次，直到：
   - 复制的 cookie 得到 302；
   - `off` 的会话得到 302；
   - `promo` 的角色变成 `operator` 与 `bi`；
   - `idle` 的会话在会话时长到时得到 302。

   前三项都要在 330 秒内（一个 access token 的寿命加一次检查间隔），最后一项要在会话时长的 −60 到 +120 秒之间。最后 `off` 再登录，要求被拒。
6. **审计。** 等日志投递（用户活动日志与 CloudTrail 都有分钟级的延迟），然后核对：
   - 控制台审计日志组：每个用户都有一条 `signin`，登录时刻在那次登录的时间窗里；有角色的用户的请求，sub 与登录时刻和 `/api/me` 一致；`none` 有"no role"的拒绝；port-forward 的三次伪造有 401 的拒绝和原因；`out` 有登出，原因是"signed out at the user pool"；
   - 用户活动日志：9 个用户都有口令通过（`InProgress`、`Password:Success`）；`bi` 有 `Password:Failure`，`admin` 有 `Mfa:Failure`；
   - `off` 被拒的那次：用户活动日志的口令通过与 CloudTrail 的 `Login_Error_POST`（"User is disabled."）时间相差不到 10 秒，来源地址相同；
   - ALB 访问日志：有本次运行发出的、未到达目标的 302 与 401。
7. **暴露面。** 列出这个 VPC 的负载均衡器、入口的监听器与规则、集群的 Service 与 Ingress，再查 EKS 端点的公网 CIDR、VPC 里的数据库是否公开，以及账号级与平台各个桶的公共访问拦截。

## 结果

运行 e2（`d549388`）的 46 项检查全部通过。

| 检查 | 结果 |
| --- | --- |
| `no-session-*`（7 项）、`http-redirects-to-https`、`legacy-tls-refused`、`unknown-host-gets-nothing`、`forged-session-cookie`、`forged-authorization-code`、`no-self-sign-up` | 6 个路径和 POST `/logout` 都是 302 到 `…amazoncognito.com/oauth2/authorize`；HTTP 是 301 到 `https://…:443/api/me`；TLS 1.1 下 curl 退出码 35；不服务的主机名与 ALB 自带的域名都是 `Not found` 404；伪造的 cookie 302，伪造的授权码 401；`/signup` 跳到 `/error` |
| `wrong-password-refused`、`wrong-mfa-code-refused` | 停在登录页："Incorrect username or password."；停在 MFA 页："The code entered is invalid, please try again." |
| `roles-*`（9 项）、`no-role-refused` | 每个用户的角色与组一致，两个组的用户是 `admin,bi`；没有组的用户 403，页面写着"没有分配角色" |
| `client-identity-headers-not-trusted` | 带着伪造的头，`/api/me` 仍是 `m5e-e2-op` 本人 |
| `pods-unreachable-in-cluster` | Pod IP 与 Service：`000`，连接时间 `0.000000`，退出码 28；对照 cove-catalog：401 |
| `pod-refuses-forged-identity` | 三种伪造都是 401，`/healthz` 200；审计记下的原因：一次没有身份头，两次取不到伪造的密钥（端点回 403） |
| `logout-returns-to-sign-in`、`logout-drops-the-session` | 303 `/logout` → 302 Cognito `/logout` → 302 控制台首页 → 302 授权 → 200 登录页；之后的请求 302 |
| `copied-session-ends-after-logout`、`disabled-user-session-ends`、`group-change-applies` | 237、237、238 秒（上限 330 秒） |
| `session-times-out` | 登录后 614 秒（会话时长 600 秒） |
| `disabled-user-cannot-sign-in` | 停在登录页："User is disabled." |
| `audit-sign-ins-by-user`、`audit-requests-by-user`、`audit-no-role-refusal`、`audit-forged-refusals`、`audit-logout` | 9 个用户都有 `signin`（两个 Pod 各记了一次，共 18 条）；8 个有角色的用户的请求都在；`none` 3 条拒绝；伪造 3 条；登出 1 条。审计里一共 79 条请求、6 条拒绝 |
| `user-activity-sign-ins`、`user-activity-failed-sign-ins`、`disabled-sign-in-traced` | 9 个用户都有口令通过；`bi` 一条 `Password:Failure`，`admin` 一条 `Mfa:Failure`；`off` 的口令通过与 CloudTrail 的拒绝对上了 1 对 |
| `alb-logs-refusals` | 本次运行的 120 行里有未到达目标的 302 与 401 |
| `exposure-only-the-entry`、`exposure-other-rows` | 只有入口这一个面向公网的负载均衡器，规则都是先认证再转发；没有 `LoadBalancer`、`NodePort` 的 Service 与 Ingress；EKS 公网端点只对 `<运维出口地址>/32`；VPC 里的 3 个数据库都不公开；3 处公共访问拦截全开 |

用户池的威胁防护把这些自动化登录评为 MEDIUM、`ACCOUNT_TAKEOVER`（日志没有记下原因）。威胁防护是仅审计模式，所以没有拦截；改为完整模式之前，要先确认它对真实用户的判定。

之前的运行发现并修正了这些问题：

- **部署：** 用户活动日志的导出不接受以 `:*` 结尾的日志组 ARN，改为日志组本身的 ARN；那次失败回滚时留下的空日志组（`RETAIN`）在重新部署前删掉。
- **e1（38 项，3 项失败，都在 harness）：**
  - 没有角色的用户登录后落在控制台的 403 页面，模拟浏览器只把 200 算作回到了应用，判成登录失败，后面的检查也因此失败。改为回到应用的主机名就算登录成功。
  - 用户活动日志不把 TOTP 通过记为一次 `Pass`：口令通过时记 `InProgress`，失败时记 `Fail` 并写明步骤。按这个格式重写了检查；e1 里"登录失败"那一项是误判通过的，它把 `InProgress` 也算作了失败。
- **评审之后：**
  - 组到角色的对应只定义在栈里，以输出交给应用；
  - 控制台记下 `signin` 事件；Pod 停止时先等请求处理完再发完审计（没有单独的测试）；
  - 缺少 `auth_time` 的 token 被拒，不认识的密钥 ID 一分钟内不再去取；
  - 客户端去掉没有用到的口令登录流程，只留 token 刷新；
  - harness 的上限收紧到 330 秒，绕过检查加了对照，暴露面检查加上了表里的其他行，ALB 日志跨零点也能取到。

运行之后的修改只有单元测试，没有在 EKS 上运行：

- 不认识的密钥 ID 只在密钥端点回 403、404 时缓存一分钟；超时、5xx、请求被取消都在下一个请求重试（复核发现：原先一次取失败会让这个 Pod 的所有用户 401 一分钟）；
- 同一个 Pod 上并发的首个请求只记一条 `signin`；
- chart 不再给会话 cookie 名默认值，要从栈的输出传入。

## Issue #136 之后的复验

[#136](bi.md) 改了登出：ALB 在 access token 过期后的那个请求上刷新会话，会在应用删除 cookie 的同一个响应里重新写入会话 cookie，于是登录约 5 分钟后再登出，回到的是控制台而不是登录页（在 BI 的 b3 运行里发现）。现在登出分两步，两步都先经登录：POST `/logout` 在用户池结束会话、写审计，303 到 `/signed-out`；`/signed-out` 删掉 ALB 的会话 cookie，303 到 Cognito 的 `/logout`。第一步上的刷新只影响第一步的响应，第二步时 token 是新的，删掉的 cookie 不会再被写回。没有匿名可达的登出页。

为此 harness 多了四项：没有会话时 `/signed-out` 也是 302 到授权端点（`no-session-signed-out`）；第 10 个用户 `late`（`cove-operator`，`roles-late`）在登录 330 秒之后、会话时长之内从页面登出，要求经 `/signed-out` 与 Cognito 的 `/logout` 回到登录页（`logout-after-the-token-expired`），之后的请求 302（`logout-after-the-token-expired-drops-the-session`）。`logout-returns-to-sign-in` 也要求跳转经过 `/signed-out`。

运行 e5（`aff7d0f`，私有证据目录 `m5-136/run-e5-aff7d0f/`，镜像 `cove-console@sha256:7ca920a7…`，栈来自 `b57ffaf`，会话时长同样临时设为 600 秒）的 50 项检查全部通过。与 e2 不同的结果：

| 检查 | 结果 |
| --- | --- |
| `no-session-signed-out` | 302 到授权端点 |
| `logout-returns-to-sign-in` | 303 `/logout` → 303 `/signed-out` → 302 Cognito `/logout` → 302 控制台首页 → 302 授权 → 200 登录页 |
| `roles-late` | `cove-operator` → `operator` |
| `logout-after-the-token-expired`、`logout-after-the-token-expired-drops-the-session` | 登录后 341 秒登出，跳转与上一行相同，回到登录页；之后 302 |
| `copied-session-ends-after-logout`、`disabled-user-session-ends`、`group-change-applies` | 217、239、239 秒（上限 330 秒） |
| `session-times-out` | 登录后 620 秒（会话时长 600 秒） |
| `audit-sign-ins-by-user`、`user-activity-sign-ins`、`audit-requests-by-user` | 10 个用户都有 `signin` 与口令通过；9 个有角色的用户的请求都在 |
| `disabled-sign-in-traced` | 1 次口令通过、1 条拒绝，按时间与来源地址对上 1 对 |

之前的 e4（`b57ffaf`，47 项通过、3 项失败，都在 harness）：`late` 的登出排在等会话到期的循环之后，到登录后 612 秒才执行，会话已经到期，模拟浏览器重新登录，于是两项都误判失败；被停用账号的那次登录，用户活动日志还没投递到就去核对了。改为在循环里到 330 秒就登出，并等到那条日志出现。

e5 之后 harness 又收紧了 `late` 的判定：没有登录成功就不登出（原先会按登录时刻为 0 计算，空的浏览器登出也会落在登录页）、登出时刻要在会话时长之内、跳转要经过 `/signed-out` 与 Cognito 的 `/logout`。没有在 EKS 上重跑，用 e5 的证据按新的判定重新判过，仍然通过。

## 限制

- **会话时长。** 验收用的是 600 秒；默认的 8 小时没有在运行里等到期，两者是同一条规则的同一个参数。
- **复制的会话。** 登出、停用、改组之后，复制走的会话 cookie 还能用到它的 access token 过期：e2 是 237 秒，e5 是 217 秒，上限 5 分钟。
- **被停用账号的登录。** 拒绝不在用户活动日志里（那里只有口令通过），CloudTrail 的那条又不带用户名，只能按时间与来源地址关联。
- **一次登录、两个应用。** 由 #136 的运行验证：先登录控制台，再打开 BI 时不出现登录页（见 [BI 查询界面](bi.md)）；反方向没有单独验证。
- **登录页与防护。** 用的是 Cognito 的经典版托管登录页；入口前没有 WAF。
- **单区域。** 用户池、证书与 ALB 都在 `us-west-2`。

> 注：本文中的 issue 与 PR 编号（如 #135）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
