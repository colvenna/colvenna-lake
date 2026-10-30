---
title: "M1 MySQL CDC 私有源库与接入验证"
date: 2026-09-28
status: complete
issue: 8
---

# M1 MySQL CDC 私有源库与接入验证

本工作对应 Issue #8。目标是在 AWS `work` 账号 `<ACCOUNT_ID>` 的 `us-west-2`，以 Paimon 2.0.0 自带的 MySQL CDC action 和 Flink CDC 3.5.0，把合成 MySQL 主键表的初始数据、后续变更及新增源表同步到真实 S3 上的 ODS。完整接收条件还包括源表/目标表对账、删除与乱序、schema 演进、断线恢复以及 binlog 保留窗口；仅建好源库或看到作业 Ready 都不算通过。

按 Issue #8 的三条接收条件，当前状态为 **COMPLETE**，判定依据见 [结项判定](#结项判定)；M1 的四个 issue（#8、#14、#15、#16）均已按接收条件结项。下文其余各节按时间保留各轮记录，其中的 RED、“尚未满足”等是当时的状态。

## 结项判定

2026-10-12 按 Issue #8 原文的三条接收条件结项。前两条除下文 2026-09-28 各轮私有运行外，还有 #16 的两次运行作证（方法与结果见[容量基线报告](capacity-baseline.md)，私有证据在 `<证据目录>/m1-baseline-20261011062753-1289437/` 与 `m1-faults-20261012044000-1011377/`）：24 小时基线 `20261011062753_1289437` 由同一 `cove-mysql-cdc` 整库作业把 8,640 万次确定性 insert/update/delete 同步到 DV ODS 主键表；故障运行 `20261012044000_1011377` 在 1,509.5 万次变更期间对该作业注入暂停、TaskManager 崩溃、S3 与 JDBC catalog 不可用。两次运行的观测器都逐个读取全部已提交快照。

| 接收条件 | 证据 | 判定 |
| --- | --- | --- |
| 初始与增量同步后，目标表按主键与源库核对一致 | 2026-09-28 各轮初始、增量与新表行集逐行对账。24 小时基线的负载是确定性的：主键连续，每行的金额、客户与版本由主键和变更序号决定。ODS 每个快照的行数、版本 2 行数、键界与金额和都恰好等于源序列的某个前缀，且逐行按公式核对的不符行为 0，这与逐键对账等价。运行结束后 ODS、DWD 最后快照与 MySQL 终态逐项相同（300,000 行、键 28,700,001–29,000,000、金额和 179,850,000、前缀 86,400,000）；故障运行结束后同样一致（395,300 行、前缀 15,095,300） | PASS |
| 更新、删除、重复事件和乱序事件不会产生错误最终行或错误变更流 | 基线 2,881 个含数据的 ODS changelog 快照逐个满足：每次变更恰好一行正序号记录、每次更新恰好一条 v1 撤回、相邻快照首尾相接，无缺口、无重叠、无重复，正确性发现 0。重复事件：RDS 重启后同一作业恢复，恰好一次 INSERT；故障运行中作业暂停 10 分钟后从 checkpoint 恢复，提交落地后 15 秒与落地后立即被杀 TaskManager（两次都从刚提交的 checkpoint 恢复并重放其后的 binlog），S3 不可用时停滞、catalog 不可用时重启，ODS 快照与 changelog 仍逐个满足上述检查，同一写入方的提交标识严格递增、无重复提交，发现 0。乱序与重复版本：源库触发器在提交前以 SQLSTATE `45000` 拒绝回退和重复版本（真实运行）；已提交的违规事件由 CDC guard 让作业明确失败，这一点由单元测试覆盖，旧版“在 CDC 层跳过”的真实演练结果为 RED 并保留在下文，接入前提是源端执行版本约束 | PASS |
| 受支持的 schema 变更继续同步；不支持的变更明确失败并告警 | 2026-09-28：`accounts.note` 增列与 `VARCHAR(64→128)` 扩宽继续同步并逐行核对；主键 `INT→VARCHAR(16)` 让隔离作业 FAILED，异常指向不支持的类型转换，Flink Operator 发出同因 Warning 事件，主作业不受影响。2026-10-12 在共享作业上补测 HLD 第 7 节所列的同族加宽：`INT→BIGINT`、`FLOAT→DOUBLE` 后 ODS schema 演进为 `BIGINT`、`DOUBLE`，超出原类型的值（`9007199254740993`、`0.1234567890123`）与 MySQL 逐行相同（`m1-baseline-20261011062753-1289437/` 下的 `SCHEMA_WIDEN_*.json`、`TABLE_DUMP_WIDEN2-*.json`）。二进制类型见下 | PASS（二进制列不受支持，见下） |

**二进制列为 RED**：同日补测时，一张只含一行 `VARBINARY(16)` 值的新表让共享整库作业的写入端以“Possibly a corrupt record”拒收该行，作业在快照阶段循环重启，同库其余表的同步随之停止约 15 分钟；删除该测试表、再以相同 schema 重建为空表后作业恢复。原因是写入端把未按 base64 编码的二进制值当作非法值；“二进制加长”因此无从验证。二进制列因此按“不支持”处理：失败是明确的（作业异常指向该行），但会停住整库作业，当时的 Kubernetes 事件已过期，未能补证 Warning 告警。修复方向是在 MySQL 源配置 Debezium `binary.handling.mode=base64` 并复测二进制加长；在此之前含二进制列的表不得纳入整库同步，HLD 第 7 节已记下这一前提，修复与接入时的列类型校验在 #24 跟踪。

Issue 边界中的其余项目：

- **整库与新增源表**：一个 `mysql_sync_database` 作业同步整个 `source_db`。Issue 所引用的旧版 HLD 第 7 节写的是“运行中新建的源表自动纳入”；Paimon 2.0.0 与 Flink CDC 3.5.0 做不到：新表只出现目标 schema，没有数据快照（下文 2026-09-28 的 RED）。须从已完成的 checkpoint 做 `last-state` 重启，新表才回填并持续同步；这一步已多次实测（`recover-new-tables.sh`，24 小时基线与故障运行的新表、补测的加宽表都按此纳入），HLD 第 7 节已改写为这一前提。下文“阶段性结论”里“#8 尚未满足该项边界”是改写前的判断；按 issue 的接收条件结项时以有界重启纳入新表，由平台自动完成纳入属于 #24。
- **断线恢复与源端保留窗口**：RDS 重启约 51 秒恢复，同一作业从内部 checkpoint 恢复后 168.7 秒行集追平。早期各轮核验的 binlog 保留为 24 小时，#16 早期运行工具（PR #73 分支的 `mixed_driver.py`）将其调为 72 小时；`source-client.sh` 仍按 24 小时设置并在 `query-binlog` 中要求恰为 24 小时，复跑上文路径前须同步。2026-10-12 读取为 `binlog retention hours=72`、`ROW`/`FULL`、未开启事务压缩（`m1-baseline-20261011062753-1289437/SOURCE_BINLOG.json`）。#16 故障运行中作业崩溃后最长约 10 分钟回到故障前水平，两写者 14.3 分钟，暂停自注入起约 15.5 分钟（TaskManager 崩溃后一次 7 分钟的慢 checkpoint），最长的暂停 10 分钟，都远小于 72 小时保留窗。偏移落到保留窗之外的情形未测。
- **告警**：M1 以 Flink Operator 的 Warning 事件为告警信号；外部通知订阅属于 #29。

不属于本 issue 接收条件、仍未解决的生产项：写入与查询节点共用安全组，不满足 HLD §12.1 的“仅写入节点”网络范围（#20）；镜像扫描仍有 27 项 Critical、83 项 High，生产发布前须消除，只准内部合成数据 PoC。

## 安全与资源边界

MySQL 源库由独立的 TypeScript CDK 栈 `CoveLakeM1MysqlSource` 创建，不改动 #6 的 `CoveLakeM1Poc` 栈。数据库使用现有 VPC 的两条 `database` 隔离子网、加密存储、`PubliclyAccessible=false`；安全组只允许该 EKS 集群安全组访问 TCP 3306。管理员与受限 CDC 用户密码由 AWS Secrets Manager 分别生成，CDC 用私有 ECR 仓库与不可变镜像标签。CDK 部署脚本先核对实际 AWS 身份、集群、子网标签、不同可用区、无公网 IP 和无默认外网路由；部署后再次核对 RDS 和安全组实际状态。不能用公网 MySQL、公开 Flink 页面或匿名 S3 来绕过接入失败。

现有 EKS 写入与查询节点组共用节点子网和集群安全组，因此该 RDS 安全组允许两个节点组在网络层到达 MySQL，仍需要数据库账号与 TLS。它满足“非公网、需认证”的 PoC 边界，但**不满足 HLD §12.1 的“仅写入节点”网络范围**；解决前不得把此栈声明为该项生产安全验收通过。只改 #8 的 RDS 安全组无法区分两个节点组，须在基础节点组设计中给写入节点独立的可验证网络身份。

现有 ODS IRSA 仅允许 `warehouse/ods.db/` 前缀，Paimon 标准 catalog 工厂在根目录 S3 探测时会返回 403。`issue8/MySqlCdcJob.java` 通过 Paimon 发布版 action 的扩展点构建同一个 PostgreSQL JDBC catalog，并让 Flink worker 的 `CatalogLoader` 使用相同路径；不增加桶级广泛列表权限。MySQL 与 catalog 密码从私有 Pod 的 Kubernetes Secret 环境变量获取，不放入 FlinkDeployment `job.args`。`bdd982b` 镜像的 Trivy 扫描有 1 项 Critical、39 项 High；基础镜像带来的漏洞未解决，**只准内部合成数据 PoC，禁止公网入口和生产发布**。原镜像是 OCI 索引，ECR `scanOnPush=true` 却没有扫描结果；后续构建关闭 provenance 索引，并要求 ECR 扫描完成，缺失时停止部署。

CDC 用户仅有 `source_db.*` 的 `SELECT` 以及 MySQL CDC 所需的全局 `SHOW DATABASES`、`REPLICATION SLAVE`、`REPLICATION CLIENT`，数据库账号要求 TLS。源端临时客户端 Pod 没有 Kubernetes API token，没有 Service、Ingress 或 hostPort；管理员密码随 Pod 和 Secret 一起删除。持续运行的 Flink CDC 使用 `cove-flinkods` IRSA、只读源库账号、Secret 引用、私有 ECR digest、ClusterIP REST，并关闭 REST 网页提交、取消和缩放入口。所有新建 Pod/FlinkDeployment 先通过 `assert-private-manifests.cjs` 检查。

## 可重放验证路径

1. 在干净提交上运行 `bash infra/m1/issue8/deploy-source.sh`，确认真实 RDS 为隔离子网、加密、非公网，3306 仅接受 EKS 安全组。
2. 运行 `bash infra/m1/issue8/source-client.sh bootstrap`。它在私有 MySQL 客户端内创建 CDC 用户和两张合成主键表，并在任何初始写入或 CDC 作业启动前建立、核验两张表的单调版本触发器；同时配置、核验 `ROW`/`FULL` binlog 及 24 小时保留，密码只通过管道和 Secret 传递。随后运行 `source-client.sh seed`，生成初始行。
3. 在同一干净 SHA 上运行 `bash infra/m1/issue8/build-image.sh` 构建、推送私有不可变镜像；`bash infra/m1/issue8/deploy-cdc-job.sh` 部署 Flink CDC 作业，并核验其状态、实际周期性 checkpoint 的 S3 对象、精确限定的 Kubernetes RBAC，以及 REST Service 类型。
4. 运行 `bash infra/m1/issue8/source-client.sh query` 与 `bash infra/m1/issue8/inspect-ods.sh accounts orders`，用 `python3 infra/m1/issue8/verify-state.py <source-state.tsv> <ods-state.tsv> accounts orders` 对账初始行集和真实 S3 数据文件。`mutate` 后再次对账 insert/update/delete。`schema-add` 后用 `source-client.sh query-note` 取得新列值，验证器加 `--require-account-note --source-note <source-note.tsv>` 核对 schema 与每一行的值。
5. 对新表运行 `source-client.sh new-table-promotions`；该脚本在源端插入首行前建立新表版本触发器。确认源库有行而作业持续 checkpoint 后目标尚无快照，记录原始失败。随后执行 `bash infra/m1/issue8/recover-new-tables.sh`；脚本要求干净源码、与 CDK 栈一致的私有镜像、健康的旧 checkpoint，并验证新 Job 从旧 Job 的 S3 checkpoint 恢复且完成新的 S3 checkpoint。最后以 `source-client.sh query-promotions`、`inspect-ods.sh accounts orders products promotions` 和对账器核对所有表。仅有目标 schema 不能证明新增表数据已接入。
6. 旧的乱序演练在 CDC 层跳过已提交的回退或重复版本 UPDATE，目标最终行未变，但后续更新的源库 before 镜像已经与 ODS 现有行不同，完整 changelog 验证失败。保留该 RED。新源码的 CDC guard 遇到违反源端版本契约的已提交事件会让作业明确失败，避免静默产生错误变更流；正常接入仍要求源库在提交前拒绝这两类更新。初始两表触发器由 `bootstrap` 创建，新表触发器在首个 INSERT 前创建；用 `query-guards` 核验四个触发器。`source-client.sh version-guard` 仅供历史无约束合成库在停写和 CDC 启动前迁移，不能作为常规部署后的补救步骤。用 `reject-regress`、`reject-repeat` 注入被数据库拒绝的写入，证明源端和 ODS 都未变化。再执行 `post-guard-update`，以 `inspect-ods.sh --changelog accounts <start> <end>` 核对真实 S3 变更前后镜像。新表接入前必须具备同一版本约束；无法保证时不能声称 `input` 有正确完整变更流。Paimon 可以在无数据变化时提交空快照，不能要求快照编号不变。
7. 运行 `source-client.sh query-binlog` 核验 `log_bin=ON`、`ROW`、`FULL` 和 24 小时保留，再注入私有 RDS 重启；数据库恢复后执行 `post-reconnect`，以 `query-promotions`、ODS 行集与 changelog 核对新的 binlog 事件。记录从故障注入到行集追平的时间；仅 Pod Ready 不能算恢复。
8. 不兼容 schema 变更使用独立的 `issue8_schema_probe` 数据库及 `cove-mysql-schema-probe` 私有作业，避免破坏持续运行的四表主作业。在已提交且与私有 CDK 栈、ECR digest 一致的源码上运行 `schema-probe.sh start`；先以 `source-client.sh schema-probe-query` 和 `inspect-ods.sh probe_records` 核对初始快照。运行 `source-client.sh schema-probe-widen` 后，用 `inspect-ods.sh --probe-name-width 128` 核对受支持的 `VARCHAR(64→128)` 扩宽到达 ODS。记下扩宽后当前快照 ID，执行 `schema-probe-reject-repeat` 验证源端拒绝重复版本，再运行 `schema-probe-dml` 注入 insert/update/delete；用 `inspect-ods.sh --changelog probe_records <起始快照> <结束快照>` 和 `verify-probe-changelog.py <changelog.tsv>` 核对真实 S3 变更流中的四条精确记录与最终行集。随后运行 `source-client.sh schema-probe-alter` 把主键由 `INT` 改为 `VARCHAR(16)`。运行 `schema-probe.sh observe` 保存作业失败原因、Kubernetes Warning 事件和私有 Service 证据，同时核对主作业仍在 RUNNING 且四表行集不变。证据保存后运行 `schema-probe.sh cleanup`，先删除隔离作业及 Pods，再撤销测试库授权、删除测试库和私有 S3 上的测试 ODS 表。只有明确的不兼容 schema 错误及同因 Warning 告警事件同时出现，才能勾选该接收条件；普通 Pod/OOM/TLS 故障不得冒充。Operator Warning 事件是 M1 的告警信号，此 PoC 未配置外部通知订阅。

上述命令的证据仅写在忽略的 `infra/m1/evidence/issue8-*/`。首次运行须使用空白合成源表；`seed`、`mutate`、`new-table`、`schema-add` 是一次性场景步骤，不能在同一数据库上盲目重复。`source-client.sh` 会在每次运行后删除临时 Pod 和管理员 Secret。

## 当前验证

- `mvn -B -f infra/m1/issue8/pom.xml clean verify` 以 Java 21 构建 Java 11 目标字节码，执行 Spotless AOSP 风格、Error Prone 和单元测试；回退/重复版本 UPDATE 在解析器前明确失败。项目依赖固定 Paimon 2.0.0、Flink 1.20.4、Flink CDC 3.5.0。仓库 CI 同时运行通用 pre-commit、此 Maven 验证及现有 Go 渲染脚本的 golangci-lint/gofmt/goimports。
- `bash infra/m1/issue8/catalog-smoke.sh` 在无 Service、Ingress、hostPort 的临时 Pod 中验证 action 的受限 JDBC catalog 初始化；结果保存在忽略的 `infra/m1/evidence/issue8-catalog-<UTC>/`，带 `PROBE_ONLY.txt`，**不是 CDC 接入验收**。
- `infra/m1/issue8/deploy-source.sh` 在干净、已提交的源码上运行，使用 CDK 部署私有 MySQL 源库并保存资源身份与安全核对证据。RDS 的 `binlog_expire_logs_seconds` 不能通过此参数组修改；源端 binlog 保留小时数须在创建后由受控私有客户端配置、查询并记录。
- `bash -n infra/m1/issue8/*.sh`、`shellcheck infra/m1/issue8/*.sh` 和 Maven Java 11 编译已通过；源端 SQL 渲染器拒绝含注入符号的合成密码。
- 首次真实 Flink 运行保留为 RED：`infra/m1/evidence/issue8-cdc-job-20260928174315/` 缺少 Flink S3A Web Identity 配置，checkpoint 初始化失败。独立的 `PROBE_ONLY` 诊断补上配置后，TaskManager 又暴露两项前置条件：`cove-flinkods` 缺少读取本作业 Deployment 的权限，以及镜像缺少 Paimon 2.0.0 CDC 运行时的 Avro 1.11.4。修复分别限定为指定 Deployment 的 `get` 权限和固定校验的 Avro JAR 及其 Jackson 依赖；这些 RED 与诊断结果不能替代最终提交 SHA 的重跑结果。
- 源库、ODS 的初始 2 表 5 行和 insert/update/delete 后 2 表 5 行均已在 `610b6af` 私有运行时对账通过；`issue8_products` 新表只有 schema、没有 snapshot 的原始诊断及一次恢复保存在 `infra/m1/evidence/issue8-new-table-recovery-202609281814/PROBE_ONLY.txt`。回退版本的原始 RED 在 `issue8-ods-inspect-20260928182019-1225833/expected-red-compare.txt`；这些历史结果不冒充新 SHA 的通过项。
- `bdd982b412325b84ca849e4ffd059f390ec83388` 的私有源栈核对在 `issue8-source-20260928183102/`，新 Job、私有镜像 digest 和 S3 checkpoint 在 `issue8-cdc-job-20260928183335/`。初始三表 8 行及 `accounts.note` 的 3 个值在 `issue8-ods-inspect-20260928183531-1281946/` 与源端逐行相符。
- 真实 3→2 回退事件的拒收日志、源端预期偏差和 ODS 未变化证据在 `issue8-invalid-version-bdd982b/`；版本 4 的源/ODS 已重新对齐。真实 4→4 重复版本事件的拒收日志、零增量且无 changelog manifest 的空快照、源端预期偏差和 ODS 行集不变证据在 `issue8-equal-version-bdd982b/`；版本 5 的最终三表对账在 `issue8-ods-inspect-20260928184319-1317058/`。
- 新建 `issue8_promotions` 后，持续 checkpoint 仍没有目标快照的 RED 在 `issue8-ods-inspect-20260928184546-1326408/`。正式 `recover-new-tables.sh` 在 `issue8-new-table-recovery-20260928184637/` 证明新 Job 从旧 Job 的 `chk-90` 恢复并完成 `chk-91`；`issue8-ods-inspect-20260928184835-1335359/` 与源端四表 10 行及新增列全部对账通过。
- `8fdfaec` 的新镜像已在 `issue8-image-20260928185430/ecr-scan.json` 得到 ECR `COMPLETE`，扫描报告 27 项 Critical、83 项 High，继续阻断生产。`source-client-20260928185434-1358829/` 再次确认 `log_bin=ON`、`ROW`、`FULL` 和 24 小时保留。`issue8-ods-inspect-20260928185551-1363963/` 的只读 Java 探针从真实 S3 读取了有效更新的 DELETE/INSERT 两条 changelog；脚本验收器因 awk 换行语法错误返回失败，已修正并须在最终 SHA 重跑。更关键的是 `changelog-content-red.txt` 证实这条 DELETE 前镜像虽与 MySQL 源事件一致，却与此前保留的 ODS 行不同；因此旧的“CDC 层跳过非法源更新”不能作为完整变更流的通过证据。
- `1f97ea6` 的私有 CDK 栈与镜像部署证据分别在 `issue8-source-20260928185916/` 和 `issue8-cdc-job-20260928190235/`；ECR 扫描完成仍有 27 Critical、83 High，生产发布阻断。四个 MySQL 源表的单调版本触发器在 `source-client-20260928190150-1389093/` 核对；回退与重复版本写入均由源库在提交前以 SQLSTATE `45000` 拒绝，证据为 `source-client-20260928190418-1398348/` 和 `source-client-20260928190451-1400120/`。被拒事件前后源库和 ODS 的行集、快照核对无错误变化。有效版本 5→6 更新后，`issue8-ods-inspect-20260928190713-1408513/` 四表及新增列对账通过，`issue8-ods-inspect-20260928190758-1410626/` 从真实 S3 changelog 取得与源库和旧 ODS 相符的 DELETE 前镜像与 INSERT 后镜像两条记录。
- `issue8-rds-reboot-20260928190901/` 保留私有 RDS 故障注入、状态时间线、Flink 同一 Job 的内部 checkpoint 恢复、重启后源事件及 ODS 对账。RDS 约 51.3 秒回到 available，仍非公网且加密；Flink restore 计数从 1 增至 2，之后完成新 checkpoint。重启后的 `promotions` 新行在 `issue8-ods-inspect-20260928191117-1429201/` 与源端四表 11 行对账，故障开始到行集追平约 168.7 秒；`issue8-ods-inspect-20260928191206-1432840/` 从真实 S3 changelog 核对到该行恰好一次 INSERT、无重复，故障开始到核验约 208.8 秒。源端 `log_bin=ON`、`ROW`、`FULL`、24 小时保留在 `issue8-source-client-20260928191015-1424010/` 复核。#6 安全脚本在 `security-0928191251/` 再次通过匿名 EKS/S3 拒绝、无公开 Service/Ingress/云资源、IRSA 越权拒绝。
- `54e447d` 的隔离 schema 测试诊断运行在 `issue8-schema-probe-start-20260928192629-1490081/` 标记为 `PROBE_ONLY`：Flink Operator 把 50m CPU request 同时当成 limit，JobManager 启动过慢；只给隔离作业补上 500m CPU limit 后，Job 与私有 S3 checkpoint 正常。`issue8-ods-inspect-20260928193409-1526162/` 与测试源表 1 行相符。MySQL 把主键 `INT` 改为 `VARCHAR(16)` 后，`issue8-schema-probe-observe-20260928193759-1540710/` 中的隔离作业为 FAILED，异常准确指向 `ods.issue8_probe_records.id` 不支持的类型转换；Operator 发出同因 `JobException` 与 `Error` Warning 事件。主作业继续 RUNNING，变更前后四表源端与 ODS 的行和 schema 未变化。此运行的 CPU limit 是诊断补丁，需用最终提交 SHA 重跑；未配置外部通知订阅。
- 提交 `bef403950fafc31c921c5d5d5ccd9bb71abe8a75` 的私有 CDK 源栈、ECR 镜像、主 CDC 作业分别在 `issue8-source-20260928195401/`、`issue8-image-20260928195603/`、`issue8-cdc-job-20260928195708/` 核对；主作业恢复 RUNNING 并完成私有 S3 checkpoint。ECR 扫描为 `COMPLETE`，仍有 27 项 Critical、83 项 High，生产发布继续阻断。隔离作业前后，四张主表的源端与 ODS 行集及 `accounts.note` 对账通过，后测两侧行集与前测逐字节一致；证据为 `issue8-source-client-20260928195939-1637449/`、`issue8-ods-inspect-20260928200000-1639167/`、`issue8-source-client-20260928201110-1702354/`、`issue8-ods-inspect-20260928201139-1705325/`。
- 同一提交上的隔离作业从 `issue8-schema-probe-start-20260928200106-1649243/` 启动，私有 S3 checkpoint 与初始 1 行源/ODS 对账通过。`VARCHAR(64→128)` 扩宽在 `issue8-ods-inspect-20260928200529-1669302/` 得到真实 Paimon schema 确认；重复版本被 MySQL 触发器以 SQLSTATE `45000` 拒绝。快照 3→5 的真实 S3 changelog 在 `issue8-ods-inspect-20260928200851-1682754/` 精确包含 INSERT、UPDATE 的 DELETE/INSERT 前后镜像、最终 DELETE 各 1 条；最终源/ODS 均只有初始行。主键 `INT→VARCHAR(16)` 后，`issue8-schema-probe-observe-20260928201051-1700544/` 确认隔离作业 FAILED、同因 Operator Warning 事件、主作业仍 RUNNING。`issue8-schema-probe-cleanup-20260928201219-1708510/` 清除了隔离 Kubernetes、MySQL 授权/测试库与 ODS S3 对象。
- #6 worktree 的 `security-0928201335/` 在上述运行和清理后再次通过匿名 EKS/S3 拒绝、无公开 Service/Ingress/主机端口/云资源及 IRSA 越权拒绝。RDS 仍为私有且加密。写入与查询节点共享安全组的 HLD §12.1 网络隔离缺口、镜像漏洞和缺少外部 Warning 通知订阅均未消除，不能作为生产安全验收通过。
- #6 worktree 的 `infra/m1/evidence/security-0928184856/` 记录 `bdd982b` 作业部署后的匿名 EKS/S3 拒绝、无公网 Service/Ingress/节点/负载均衡以及 IRSA 越权拒绝；#8 MySQL 实例的私有子网、`PubliclyAccessible=false` 和仅 EKS 安全组 3306 的部署核查在 `issue8-source-20260928183102/`。Trivy 原始 JSON 在 `issue8-image-scan-bdd982b.json`；漏洞仍阻断生产发布。

## 私有 PoC 阶段性结论

Issue #8 的初始及增量行集、有效更新/删除的真实 S3 changelog、
新增表经 checkpoint 恢复后的回填、已实测的增列与 `VARCHAR` 扩宽、
不兼容主键变更的明确失败与 Operator Warning、
RDS 重启后的业务追平和源端 24 小时 binlog 前提，
均有上文对应的私有合成数据运行证据。
PR #54
已合并，审核的源提交为 `0c67d870daf5c2c46430d12654edcfe9eaeeadf5`，
合并提交为 `c50bc6a3d353c8bd210c4b7b3f305d04be48c771`。
GitHub Actions 因账号账单限制在作业开始前失败；请求方豁免了
本轮 GitHub CI 合并门禁，其状态为 `WAIVED_BY_REQUESTER_NOT_PASS`，
不能记作测试通过。

已提交非法版本事件产生错误完整 changelog 的历史结果仍为 RED；
新路径必须先在源库拒绝这类写入。运行中新增表只出现目标 schema、
没有数据快照的历史 RED 仍成立，纳入新表须执行已验证的 checkpoint
恢复和行集核对。Issue 所引用旧版 HLD 的“运行中新表自动纳入”
尚未验证，当前仍需要显式恢复，因此 #8 尚未满足该项边界。
[Flink CDC 3.5 的新增表说明](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.5/docs/connectors/flink-sources/mysql-cdc/#scan-newly-added-tables)
也要求扩展监控表范围后从 savepoint 恢复；本项目的 checkpoint
恢复是私有实测结果，不能据此宣称原作业会自动发现并回填。
HLD 所列的其他兼容类型扩宽未在本次运行中验证。
镜像扫描仍有 27 项 Critical、83 项 High；
写入与查询节点共用安全组，外部 Warning 通知订阅也尚未建立。
此结论只接收短期、私有、仅合成数据的 CDC PoC；
生产安全准入和 M1 端到端性能仍未完成。

> 注：本文中的 issue 与 PR 编号（如 #8）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
