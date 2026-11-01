# 授权与执行方

本页说明 colvenna-catalog 的访问控制由谁执行、能保证什么、不能保证什么，供平台运维与数据 Owner 使用。授权的设计细节见 [colvenna-catalog LLD 的“授权”一节](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/docs/design/colvenna-catalog-lld.md#授权)：colvenna-catalog 是独立产品 Colvenna Catalog，LLD 在它的仓库里，链接指向 Colvenna Lake 固定的版本。

## 身份

colvenna-catalog 只认识**服务身份**（principal），每个服务身份用 API key（`Authorization: Bearer`）访问。Operator 为每个 `LakeSource`、`LakeTable`、`LakeDashboard`、`LakeTask` 与 `LakeErasure` 各建一个服务身份，并只授予它需要的权限：它们的 Flink 作业、检查作业和平台 Doris 里这个看板的 catalog 各用自己的身份。平台之外的引擎（例如 M2 验收用的 Spark）由管理员另建服务身份。

colvenna-catalog **看不到终端用户**：Doris 的用户 A 和用户 B 经同一个看板查询同一张表时，到达 colvenna-catalog 的都是这个看板的服务身份。终端用户身份只记录在 Doris 自己的审计日志中，两者不能互相冒充；把查询归到具体终端用户，须结合 Doris 审计日志。

## 授权

每条授权写明以下内容：

- **对象与级别**：对哪一级对象（目录、库、表、视图、函数）授予或拒绝哪一级权限（`READ`、`WRITE`、`MANAGE`）。
- **审批信息**：用途、环境、审批人、审批单和失效时间。省略环境时取服务端的 `COLVENNA_ENVIRONMENT`。

到期的授权自动失效，失效后该身份不再拿到新的数据凭证。管理员身份只能使用管理面，访问数据同样需要显式授权。

**默认库**：Flink 与 Spark 的 Paimon catalog 启动时会查找默认库 `default`，看不到就报错。所以安装时会建好这个库，每个服务身份都能看到它，但库里的对象仍要靠授权才能访问；对它的 `READ` 拒绝仍然有效。

**从没有授权的版本升级**：升级时，模式迁移只建立授权表，不为已有的服务身份补授权。所以升级之后、恢复各作业之前，要先为每个服务身份授予它需要的权限，否则它的请求一律被拒绝。

## 执行方

| 控制 | 执行方 | 能保证什么 | 不能保证什么 |
| --- | --- | --- | --- |
| 列举、读取、创建、修改、删除库与表等目录对象 | colvenna-catalog | 每个请求在执行前判定，没有授权或命中 `DENY` 的请求被拒绝并审计 | — |
| 提交快照、回滚、tag、分支 | colvenna-catalog | 没有 `WRITE` 的身份无法发布快照 | 能直接写 S3 的身份（例如未切换的 JDBC 写入方）绕过 catalog 写出的快照，只能事后发现并隔离表（`FENCED`） |
| 表文件的读与写 | AWS IAM 会话策略（按表数据凭证） | 每份凭证只覆盖一张表的前缀；只有 `READ` 时只能读对象，以及写本表 `consumer/` 下的消费进度 | 只在启用 `COLVENNA_DATA_TOKEN_MODE=aws-sts` 时成立；未启用时，表文件访问只受引擎 Pod 自身的 IAM 角色约束，`READ` 授权挡不住 S3 写入。引擎角色本身也必须收紧，不得能直接读写表数据 |
| 撤销与到期 | colvenna-catalog + AWS STS | 撤销或到期最迟一个认证缓存周期（默认 2 秒）后生效，此后不再签发、续签凭证 | 已签发的数据凭证在有效期内仍可使用（默认最长 3 小时）。要立即作废，须在数据角色上撤销活动会话，再重启 colvenna-catalog 副本与相关作业，其间表数据不可用 |
| 列级判定 | Paimon 客户端 | 表开启 `query-auth.enabled` 时，客户端先调用 `/auth`，不允许读的列被拒绝 | 不是隔离边界：持有该表数据凭证的引擎可以直接读出所有列的文件。敏感列应放进独立的表或前缀。各引擎是否执行 query-auth 未验证，平台保持关闭 |
| Doris 终端用户的库、表、列、行权限 | Doris RBAC | 由 Doris 按其用户与角色判定 | colvenna-catalog 不参与，也不审计这一层；Doris 外部 Paimon 表上的列白名单、行策略须逐个入口验证 |
| 行过滤、列脱敏 | Doris（平台 Doris 的看板读路径，M4 #35） | Operator 按列标签生成脱敏视图与 Doris 行策略，看板用户只能读这些对象，湖表、系统表、文件类表函数与直接存储都被拒绝（[验收](../history/m4/governance.md)） | 只在这条读路径上执行；行过滤不当作保密边界。colvenna-catalog 不提供行列策略，推迟到有正式 Management API 的版本（HLD 6.2） |
| 管理面（服务身份、API key、授权、审计查询） | colvenna-catalog | 只有管理员身份能调用 | — |

## 审计

以下事件都会记入审计：

- **目录访问**：每次访问目录对象，允许与拒绝都记。
- **写操作**：每次写操作，包括提交，以及 compare-and-set 失败的提交，记为 `FAILED`。
- **凭证签发**：每次签发数据凭证，含缓存命中和失败。
- **授权变更**：每次授权的增删。

每条记录关联：

- 请求 id 与时间；
- 服务身份；
- 动作、资源、结果；
- 策略版本，以及据以允许的授权 id（可追溯到审批单）。

审计经管理面 `GET /colvenna/admin/v1/audit` 查询。

不在 colvenna-catalog 审计范围内的有两类：

- **查询内容与导出**：引擎内的 SQL 与结果。
- **终端用户身份**：见上文“身份”。

这两部分以引擎（如 Doris）的审计为准。
