---
title: "M5 BI 查询界面"
date: 2026-10-19
status: complete
issue: 136
---

# M5 BI 查询界面

本页记录 Issue #136 的验收：BI 用户在浏览器里对平台的 Doris 做 ad-hoc SQL 查询、建图表与看板，读到的数据与权限和直连 Doris 时一致。界面是 Apache Superset 6.1.0，在 [#135](entry.md) 的入口后面（`https://bi.cove.<COVE_HOSTED_ZONE_NAME>`）。设计见 [Web 入口与控制台 LLD](../../design/colvenna-console-lld.md) 的"BI 查询界面"，依据 HLD 10.1、12.1–12.3。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），与 M1–M4 同一个 EKS 集群、同一个平台 Doris（4.1.4，#32），只用合成数据。

**证据的来源版本**：结果来自提交 `b57ffaf`，私有证据目录 `m5-136/run-b5-b57ffaf/`。镜像：BI `m5-bi@sha256:8fbe546a…`（`apache/superset:6.1.0-py311@sha256:95b060f3…` 加驱动与 `cove_bi`），Operator `cove-operator@sha256:f260309d…`，作业 `cove-jobs@sha256:f9924a09…`（mysql）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 经 SSO 登录的用户在 SQL Lab 与看板里只读到自己在 Doris 中被授予的对象；脱敏列与行过滤和直连 Doris 时一致；越权查询被拒 | 通过 | 三个人在同一个看板里各是一个 Doris 用户。在 SQL Lab 里，alice 读到 15 行 `created`，bob 读到 15 行 `shipped`，`customer_id` 与 `version_no` 都是 NULL、`note` 都是 `xxxx nnn-nnnn`；carol 读到的 10 行与她用 mysql 客户端直连 Doris 读到的逐行相同。湖表、`information_schema`、没有授予的查询都被 Doris 以 Permission denied 拒绝，报错里是本人的 Doris 用户；写入被 Superset 拒绝。浏览库、表、列在没有 `information_schema` 时可用。不是看板用户的 dave 看不到数据库，用 id 去查也被拒 |
| Doris 的查询审计能把每条来自 Superset 的查询对应到最终用户 | 通过 | 这次运行里从 BI 的 Pod 连到 Doris 的只有三个人各自的 Doris 用户，没有共享账号，也没有别的用户：按人追溯以 Doris 用户为准。取数的 16 条查询（SQL Lab、图表、导出）前面都有 `/* cove-bi user=<登录名> sub=<sub> */`，注释里的人都对应语句的 Doris 用户，包括 bob 导出 alice 存下的查询那一次。不带注释的是驱动与 Superset 自己发的连接设置和浏览时的 `SHOW` |
| 结果缓存、导出与共享的看板不让一个用户看到另一个用户无权看到的数据 | 通过 | alice 保存的表格图表放在一个已发布的看板上：alice 看两次都是她的 15 行，bob 看两次都是他的 15 行（不强制刷新；Superset 的数据缓存是关着的），dave 看不到。图表与 SQL Lab 的 CSV 导出都是导出者自己的行，bob 导出 alice 存下的查询，得到的是他自己的 `shipped` 行。没有 results backend（`RESULTS_BACKEND_NOT_CONFIGURED_ERROR`），报表与告警关闭 |
| Superset 只经 #135 的入口可达；未认证访问它的页面与 API 都被拒 | 通过 | 没有会话时，页面、SQL Lab、API、`/health`、`/signed-out` 都只得到 302 到 Cognito 的授权端点。Superset 自己的 API 登录、刷新、登录页与 guest token 都是 404，API token 是 401。集群里的 Pod 连不上 BI（按 Pod IP 与按 Service 都超时，对照的 cove-catalog 回 401）；经 port-forward 送到 Pod 的伪造身份都是 401 |

## 运行

`infra/m5/scripts/build-bi.sh`、`install-bi.sh` 构建并安装 BI（元数据库在项目的 RDS 上），`infra/m3/scripts/build-images.sh`、`install-operator.sh` 装上发布身份的 Operator（检测到 BI 的 Secret 后带上 `bi.namespace`）。`infra/m5/flows/bi/run-bi.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL。浏览器由 #135 的 `entry.py` 模拟，Superset 的 API 由 `bi.py` 调用：先取 CSRF token，再以 JSON 调用页面所用的接口，带本站的 Referer。

1. **用户。** 在用户池里建 6 个合成用户：alice、bob、carol、dave 在 `cove-bi`，erin 在 `cove-operator`，adm 在 `cove-admin`。运行结束时删掉。
2. **湖与看板。** MySQL 源接入 `orders`，列标签与 #35 的运行相同：`customer_id`、`version_no` 涂掉，`note` 打码。看板 `bd-<run>` 以外表视图 `live` 与物化视图 `by_state` 服务它；用户 `analyst`（登录名 alice，行 `state = 'created'`）、`viewer`（bob，`state = 'shipped'`）、`carol`（carol，`id <= 10`），三者都有口令 Secret。要求 Operator 把三人的身份发布到 BI 的 Secret。
3. **没有会话。** 页面、SQL Lab、API、CSRF 端点、`/health`、`/signed-out`：都是 302 到授权端点。
4. **登录。**
   - carol 先登录控制台（口令与 TOTP），再打开 BI：不出现登录页；
   - 每个人的 Superset 角色：alice、bob、carol 是 `CoveBI` 与 `Dashboard cove-m3/bd-<run>`，dave 只有 `CoveBI`，adm 是 `Admin`，erin 得到 403；
   - 看板的数据库在同步之后出现在 alice、bob、carol 的列表里，dave 的列表是空的；
   - Superset 的 API 登录、刷新、登录页、guest token 都是 404，带 `Authorization: Bearer` 的请求是 401。
5. **各人读到的。**
   - 在 SQL Lab 查 `live`：alice、bob 各得到自己的行与脱敏后的列；carol 的结果与 mysql 客户端直连的逐行比较；
   - 湖表、`information_schema`、bob 去查没有授予他的 `by_state`、一条 `INSERT`，都要被拒，报错要出自 Doris（或 Superset 的 DML 限制），而不是别的错误；
   - alice 查 `by_state`，只有她那一行；
   - 列出 schema、表、`live` 的列；
   - dave 用 alice 那个数据库的 id 去查。
6. **共享与导出。** alice 在 `live` 上建数据集；alice 与 bob 以图表的数据接口读它；bob 导出 CSV；dave 读它。alice 再保存一个表格图表到一个已发布的看板，alice、bob 各打开两次，dave 打开一次（按看板的方式，不强制刷新）。alice 导出她在 SQL Lab 的那次查询，bob 也导出 alice 的那次。用一个随机 key 去取 SQL Lab 的结果。查报表接口。
7. **绕过入口。** 同 #135：作业命名空间里的 Pod 连 BI 的 Pod IP 与 Service，以及对照的 cove-catalog；经 port-forward 送不带身份头与伪造的声明到 Pod。
8. **Doris 的审计。** 等一分钟，以 root 读 `__internal_schema.audit_log`（只看这次运行的时间窗）：带 `cove-bi user=` 注释的每一行，注释里的人要对应语句的 Doris 用户；从 BI 的 Pod 来的每一行，Doris 用户都要是这三个人之一，不带注释的只能是连接设置（`SET`、`ROLLBACK`、`SELECT @@…`、`SELECT DATABASE()` 等）与 `SHOW`。
9. **结束。**
   - 去掉 bob 的登录名：他的数据库从列表里消失，用 id 查被拒，打开看板没有行；
   - carol 登出（POST `/logout`，带本站的 Origin 与 `Sec-Fetch-Site`；菜单的 GET `/logout/` 走同一个钩子）：经 `/signed-out` 与 Cognito 的 `/logout` 回到登录页；
   - 删除看板：BI 的 Secret 里没有它的条目，alice 的数据库也消失了。

## 结果

运行 b5（`b57ffaf`）的 54 项检查全部通过。

| 检查 | 结果 |
| --- | --- |
| `source-ready`、`dashboard-ready`、`published-to-the-bi-interface` | 源 212 秒后 `Ready`；看板 12 秒后 `Ready`、`BiPublished`；Secret 里是三个 Doris 用户，各带登录名、库与看板名 |
| `no-session-*`（6 项） | 都是 302 到 `…amazoncognito.com/oauth2/authorize` |
| `one-sign-in-for-both` | 打开 BI 的跳转：302 ×4 之后 200，没有登录页 |
| `roles-*`、`databases-*` | 角色与上面一致；数据库在 16 秒后出现 |
| `refused-*`、`api-token-refused` | 四个路径 404；Bearer 401 |
| `alice-reads-her-rows-masked`、`bob-reads-his-rows-masked`、`carol-reads-as-directly`、`alice-copy-holds-her-rows` | 15 / 15 / 10 行，例如 `[1, None, 1.5, 'created', 'xxxx nnn-nnnn', None]`；carol 两边相同；`by_state` 是 `[['created', 15]]` |
| `lake-table-refused`、`information-schema-refused`、`query-not-given-refused`、`writes-refused` | Doris："Permission denied: user ['cove-m3.bd-b5.analyst'@'%'] …"（bob 那项是 `viewer`）；写入：Superset 的 "This database does not allow for DDL/DML" |
| `browse-schemas`、`browse-tables`、`browse-columns` | 看板库；`live`、`by_state`；`live` 的六列 |
| `dave-reaches-nothing`、`chart-refuses-dave` | SQL Lab：Superset 说数据库不存在（dave 没有它的访问权限，查询到不了 Doris）；图表：403，连接被 BI 的连接钩子拒绝 |
| `chart-shows-each-viewer-their-rows`、`export-is-the-viewers-rows` | alice 15 行 `created`、bob 15 行 `shipped`；bob 的 CSV 只有 `shipped` |
| `saved-dashboard-shows-each-viewer-their-rows` | alice、bob、alice、bob 各 15 行（各自的），dave 404 |
| `sqllab-export-is-the-exporters` | alice 的 CSV 是她的 `created` 行；bob 导出 alice 的查询，得到 `2,,3.00,shipped,xxxx nnn-nnnn,…` |
| `no-results-backend`、`reports-off` | `RESULTS_BACKEND_NOT_CONFIGURED_ERROR`；404 |
| `pod-unreachable-in-cluster`、`pod-refuses-forged-identity` | `000`、连接时间 0、退出码 28；对照 401；伪造 401，`/health` 200 |
| `doris-audit-names-each-person`、`no-other-doris-user-from-the-bi-interface` | 332 行：`analyst` 246、`viewer` 72、`carol` 14（其中 3 行是她直连的 mysql 客户端）。从 BI 来的 329 行里，16 条带注释的查询都对上；其余是 `SELECT @@…`、`SELECT DATABASE()` 等 153 行、`SET NAMES` 80 行、`ROLLBACK` 69 行与浏览时的 `SHOW` 12 行。从 BI 来的只有这三个 Doris 用户 |
| `bob-loses-the-database`、`bob-queries-refused-after`、`bob-sees-no-rows-of-the-dashboard-after` | 16 秒后列表里没有了；查询被拒；看板 404 |
| `logout-returns-to-sign-in` | 经 `/signed-out`、Cognito `/logout` 回到登录页 |
| `credentials-withdrawn`、`alice-loses-the-database` | 0 条留下；78 秒后列表里没有了 |

之前的运行发现并修正了这些问题：

- **镜像：** amd64 镜像在 arm64 上交叉构建时，`uv` 在模拟下崩溃（退出码 139），改用 pip 装进 Superset 的环境。
- **b1：**
  - SQL Lab 的执行接口在 6.1 里不收 `json` 字段，查询都是 400；"被拒"的检查当时只看有没有出错，所以误判通过。现在要求报错出自 Doris 或 Superset 的限制；
  - 看板的角色由同步 sidecar 建，登录时还没有，角色要在数据库出现之后检查；
  - 去掉 bob 的那一步，生成的 YAML 不合法，脚本中止。
- **b2：** SQL Lab 的查询没有带上注释。Superset 的同步执行只设了 `g.user`，没有设请求钩子写入的值；改为从 `g.user` 取用户名，`sub` 从账号的邮箱（`<sub>@users.cove-lake.invalid`）取。
- **b3：** 登录约 5 分钟后再登出，回到的是欢迎页。原因是 ALB 在那个登出请求上刷新了会话，在同一个响应里、在应用的删除头之后写入了新的会话 cookie。登出改为两步：`/logout` 结束会话，`/signed-out` 删 cookie，两步都先经登录。控制台（#135）的登出也一样改了。b5 里 carol 登录约 220 秒后登出，还在 access token 的寿命之内；token 过期之后再登出的情形只在控制台上验证过（[入口的复验](entry.md#issue-136-之后的复验) e5），两者是同一个做法。
- **评审：**
  - 连接钩子原先只拒绝几个与凭证有关的选项，而 mysqlclient 会把 `sql_mode`、`collation` 不转义地写进连接时执行的 SQL，于是改为只允许三个超时选项；
  - 用户池把同一个用户名给了另一个人时，拒绝把旧的 Superset 账号交给他；
  - 看板角色名超过 64 个字符时缩短；
  - 一个看板同步失败不再影响其他看板；
  - BI 的 Secret 写不进时，记为 `BiPublished` 的失败，不再算成 Doris 的失败；
  - `CACHE_QUERY_BY_USER` 在 6.1 里是 feature flag，原先写在顶层不起作用。
- **b4：**
  - 导出别人存下的查询时，语句里还带着原作者的注释。6.1 的 `SQL_QUERY_MUTATOR` 只在拆分语句之前或之后之一被调用，而导出走的是另一边；改为包裹所有路径都会调用的那个方法，并去掉旧注释；
  - dave 与 bob 用 id 去查时，拒绝来自 Superset 的数据库权限，这时查询到不了连接钩子。检查改为认这条报错，连接钩子自己的拒绝由图表那一项与单元测试覆盖。

运行之后的修改：Doris 审计那一项原先只核对带注释的行，从 BI 来、不带注释的取数语句也会放过；改为不带注释的只能是连接设置与 `SHOW`。没有在 EKS 上重跑，用 b5 的证据按新的判定重新判过，仍然通过；往证据里加一条不带注释的 `SELECT * FROM live`，判定失败。

## 限制

- **一个口令。** 直连 Doris 与经 BI 是同一个 Doris 用户、同一个口令；BI 的进程持有每个带登录名的用户的口令。BI 被攻破时这些身份都泄露，每个身份只有它看板对象上的 `SELECT`，带着它自己的脱敏与行过滤。
- **映射生效的时间。** 去掉登录名要等 Operator 的一轮、kubelet 同步 Secret 卷与数据库同步：这次是 16 秒与 78 秒。
- **Superset 的管理员。** `Admin` 能看到所有人保存的查询与 SQL 历史，元数据库因此是个人数据的又一个副本，还没有纳入删除流程。
- **单副本、无异步。** 一个 Web 进程；不跑异步 SQL Lab，长查询受 gunicorn 的 120 秒限制。
- **BI 自身的审计。** Superset 记下的登录、拒绝与登出只在 Pod 日志里；按人追溯查询以 Doris 的审计为准。
- **其他图表类型。** 只验收了表格图表与 SQL Lab；其他图表类型走同一个查询路径，没有逐一验收。

> 注：本文中的 issue 与 PR 编号（如 #136）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
