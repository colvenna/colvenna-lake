---
title: "M4 列标签、脱敏与行过滤"
date: 2026-10-17
status: complete
issue: 35
---

# M4 列标签、脱敏与行过滤

本页记录 Issue #35 的验收：正式的列分类标签，以及由引擎执行的列脱敏和行过滤。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的"列标签、脱敏与行过滤"一节（含支持矩阵），依据 HLD 6.2、12.1、12.5–12.7；调研见 [m4-governance-enforcement.md](../../research/governance-enforcement.md)。唯一开放的读路径是平台 Doris 上的看板：用户只能查 Operator 建的对象，对象读湖表时经过 Operator 生成的脱敏视图，用户的行由 Doris 的行策略限定。其他读路径读整张表，保持表级授权与表级分类。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源库是 M1 的 RDS MySQL 8.0，看板在平台的 Doris 4.1.4 上（#32）。第二朵云未验证（#33 未开展）。

**证据的来源版本**：结果来自提交 `3ff8d1d`，私有证据目录 `m4-35/run-g5-3ff8d1d/`。镜像：Operator `cove-operator@sha256:fd791fb3…`，作业 `cove-jobs@sha256:0d03198b…`（mysql）；Doris 是 M1 的 4.1.4 镜像（带自建的 Paimon 2.0.0 读取器），BE 以 `user_files_secure_path = /opt/apache-doris/be/user_files_none` 运行（`SHOW BACKEND CONFIG` 核对）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 目标用户、引擎和直接存储访问分别通过允许与拒绝测试 | 通过 | 用户：分析员只看到 `state = 'created'` 的 15 行，`customer_id` 与 `version_no` 为 NULL，`note` 被替换成 `xxxx nnn-nnnn`，金额照常；副本里也只有它的行；没有条件的用户 0 行；未声明的用户、错误口令和空口令都登录不了。引擎：用户读不到湖表、系统表、脱敏视图和视图定义，建不了视图和行策略，写不了副本；FE 只向客户端命名空间开放 MySQL 协议，HTTP 与 Arrow Flight SQL 端口连不上。直接存储：`s3()` 的默认凭证链、实例角色、另一个角色都被 IAM 拒绝，`local()` 读不到 BE 的任何文件，`http()` 到实例元数据和 cove-catalog 是 401，`INTO OUTFILE` 写不进仓库，没有 AWS 身份的 Pod 读不到桶 |
| schema 变化、策略冲突和身份缺失时按声明规则拒绝或停止发布 | 通过 | 源端加列 `phone`：Operator 判它为 `redact`，Doris 里的脱敏视图以 `NULLIF` 输出它，分析员经 `SELECT *` 的对象读到的全是 NULL。标签高于表的级别：表不受治理（`LabelInvalid`），看板 `Failed`，用户被撤下（登录失败），改回之后重新服务。身份缺失：没有声明条件的用户在每个对象上都有 `1 = 0` 的策略，未声明的用户没有账号 |
| 支持矩阵明确覆盖范围，未验证的引擎和接口保持关闭 | 通过 | LLD 的支持矩阵逐项写明读路径与接口的状态；本次运行实测了其中关闭的每一项（见"结果"）；Ranger、Paimon query-auth、Doris 原生脱敏不启用；湖表的 `readers`、Iceberg 开放层与 Trino 只按表级授权与分级（Iceberg 只发布 L2 及以下、无个人数据、列已复核的表，见 [M4 开放层验收](iceberg.md) 的 `withdrawn-when-restricted`） |

## 运行

`infra/m4/flows/governance/run-enforcement.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL；用户的查询都从客户端命名空间 `cove-m3-bi` 的 Pod 经 MySQL 协议发出，拒绝必须由对应的边界给出（Doris 的权限、IAM、BE 的路径限制、登录失败各有各的错误），不是任何错误都算：

1. **源与标签。** MySQL 源把 `orders`（30 行）接入 ODS，声明为 L3 个人数据，列标签：`id` L1，`amount`、`state` L2，`customer_id` 个人数据（`redact`），`note` 个人数据（`mask`）；`version_no` 没有标签，按表的 L3 个人数据处理。
2. **看板。** 看板复制这张表（自身 L3 个人数据），用户只获准 L2：外表视图 `live`、物化视图 `by_state` 与 `SELECT *` 的外表视图 `everything` 都经 Operator 建的脱敏视图读湖。分析员的条件是 `state = 'created'`，查看员没有条件。
3. **用户看到什么。** 分析员读 `live` 与 `by_state`；查看员数 `live`；未声明的用户、错误口令、空口令登录。
4. **用户够不着什么。** 分析员（先确认它已登录）试读湖表、系统表、脱敏视图、视图定义，建视图、写副本、建行策略，以各种方式调用 `s3()`、`local()`、`http()`、`paimon_meta()`，`INTO OUTFILE` 写仓库；一个没有 AWS 身份的 Pod 列桶；从客户端命名空间探 FE 的端口。
5. **用户能推断什么。** `assert_true` 探针：先在看得到的行上确认它会报错，再在外表视图、物化视图和 `CASE` 写法上探被隐藏的行；`EXPLAIN` 看计划里有什么。
6. **声明规则。** 源端加列；把一个标签设得高于表的级别，再改回。

## 结果

42 项检查全部通过（`verdicts.tsv`）。

| 检查 | 结果 |
| --- | --- |
| `masks-as-labelled` | 脱敏视图 `pv_<uid>_ods_m4g_g5.orders`：`customer_id:redact`、`note:mask`、`version_no:redact` |
| `analyst-sees-masked-columns-of-its-rows`、`analyst-copy-holds-its-rows-only` | 分析员读 `live`：15 行，全是 `created`，`customer_id` 与 `version_no` 为 NULL，`note` 为 `xxxx nnn-nnnn`，金额如实；`by_state` 只有 `created 15` |
| `user-without-a-condition-sees-no-rows` | 查看员数到 0 行 |
| `undeclared-user-cannot-log-in`、`wrong-password-cannot-log-in`、`empty-password-cannot-log-in` | `ERROR 1045 Access denied for user` |
| `analyst-logged-in-for-the-denials` | 下面的拒绝都发生在分析员已登录的会话里 |
| `denied-lake-table`、`denied-system-table`、`denied-masking-view` | 没有 catalog 与影子库的权限（`does not have privilege`、`privilege(s)`） |
| `denied-view-definition`、`denied-create-view`、`denied-write`、`denied-own-row-policy` | `SHOW CREATE TABLE command denied`、`Create_priv`、`LOAD command denied`、`Grant_priv` |
| `denied-s3-default-chain`、`denied-s3-instance-profile`、`denied-s3-another-role` | `cove-lake-m2-doris` 不能 `s3:ListBucket`；实例角色拿不到（实例元数据拒绝）；不能 `sts:AssumeRole` 到 `cove-lake-m4-iceberg-read` |
| `denied-local-files`、`denied-local-absolute-path`、`denied-local-parent-path` | 相对路径与绝对路径都落在空目录 `user_files_none` 里找不到；`..` 被拒 |
| `denied-paimon-meta` | `Can not found function 'paimon_meta'` |
| `denied-http-instance-metadata`、`denied-http-cove-catalog` | 都是 401 |
| `denied-outfile-to-the-warehouse` | BE 写仓库时 `failed to put object` |
| `no-direct-storage` | 没有 AWS 身份的 Pod：`Unable to locate credentials` |
| `only-the-mysql-protocol` | 从 `cove-m3-bi`：9030 通，8030 与 8070 不通 |
| `row-filter-probe-fires-on-a-seen-row` | 在看得到的行上探针报错（`probe`），所以它能用 |
| `row-filter-probe-sees-no-hidden-row-view`、`-copy`、`-case` | 外表视图、物化视图、`CASE` 写法都没有报错：被隐藏的行没有被探到 |
| `explain-shows-the-plan-not-the-rows` | 计划里有脱敏表达式和策略条件，估算行数 30（表里的全部行），没有明文的备注 |
| `new-column-redacted`、`new-column-redacted-in-doris`、`new-column-redacted-for-the-analyst` | 加列后 13 s 判为 `phone:redact`；脱敏视图以 `NULLIF` 输出它；分析员经 `everything` 读到 15 行、0 个非空的 `phone` |
| `conflict-stops-the-table`、`conflict-stops-the-dashboard`、`conflict-withdraws-the-users` | 标签设为 L4：表 `LabelInvalid`，看板 `Failed`，分析员登录失败 |
| `fixed-label-serves-again`、`analyst-reads-again` | 改回后看板 `Ready`，分析员又数到 15 行 |

## 过程中的发现

- 实验：Doris 4.1.4 的 `local()` 表函数不检查调用者的权限，默认能读 BE 目录（`/opt/apache-doris/be`）下的任何文件：一个没有任何权限的临时用户读出了 `be.conf`，同一目录下还有每个看板的内表、物化视图数据和 BE 的日志。这条路径自 M3 的看板起就开着。`install-doris.sh` 给 BE 加了 `user_files_secure_path`，指向一个空目录，并核对每个 BE 实际运行的配置；之后相对路径、绝对路径和 `..` 都读不到。
- 实验：`s3()` 不给 `s3.endpoint` 时在解析阶段就报错（`No supported storage type found`），根本没有碰到 S3，g1 的两项"拒绝"因此是错的理由。给了 `endpoint` 之后，IAM 拒绝 Doris 的角色，root 也一样。
- 评审（第一轮）：查询里直接写看板的 catalog 或 `$` 系统表能绕过脱敏视图；没有标签的表不看 `access`；查询对象重建失败、行策略建不出来时旧对象和授权照旧（会放宽）；cove-catalog 一时答不出就撤下所有用户；不再用的影子库删不掉；`hash` 可以对照还原；验收脚本的几项拒绝接受任何错误。都已改正，`hash` 先标为假名。
- g2：31 项通过；探针没有探到隐藏的行，`EXPLAIN` 不显示影子库名，Doris 把视图里的名字写成全限定，三项按实测改了判定与 LLD。
- 评审（第二轮）：反引号名字里的 `'` 会骗过扫描器（catalog 名、系统表、系统库三项检查和改写都失效）；`paimon_meta` 这类表函数以字符串接收表名；脱敏视图建失败、cove-catalog 答不出列时旧对象照旧服务；副本要等到下一次刷新才脱敏；`hash` 违反 HLD 12.1 的"禁止无密钥哈希"。改为整体扫描反引号名字、整条 SQL 查 catalog 名、禁止表函数、这些错误一律撤销授权、脱敏一变就重建副本、去掉 `hash`；验收脚本区分登录失败和权限拒绝，并以分析员身份读到新列为 NULL。
- g4：41 项通过；`paimon_meta()` 在 Doris 4.1.4 里不存在（`Can not found function`），调研里按源码列出的这条入口实际没有。

## 限制

- 行过滤不当作保密边界：Doris 没有 security barrier；探针这次没有探到隐藏的行，那是 Doris 4.1.4 的计划行为，不是承诺；`EXPLAIN` 的估算行数也说出了被隐藏的行有多少。要让一部分行对某些用户保密，就为他们建只装这些行的对象。
- 没有哈希脱敏：HLD 12.1 禁止对低熵标识符用无密钥哈希，Doris 视图里又藏不住密钥。需要稳定 join 值的假名（KMS 保护密钥的 HMAC 或 token 服务）不在 #35 范围。
- `access` 由看板的声明者给出，只受看板自己的 `governance` 约束，不是表 owner 的审批；命名空间是信任边界。
- 只有 Doris 看板这一条读路径执行列级与行级保护；湖表的 `readers`、Iceberg 开放层、Trino、Flink 与 Spark 读整张表。
- `local()` 的关闭靠 BE 的 `user_files_secure_path`：它是平台安装 Doris 时的配置（`infra/m3/scripts/install-doris.sh`），换镜像时要随 `be-default.conf` 一起核对。
- 第二朵云未验证（#33 未开展）。

> 注：本文中的 issue 与 PR 编号（如 #35）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
