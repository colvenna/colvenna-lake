# M1 主键表、append 表与变更流语义

本工作对应 Issue #11，依据
[HLD 7.4、8.2、8.4、9 节](../../design/hld-realtime-lakehouse.md)。
实验在 AWS `work` 账号 `<ACCOUNT_ID>` 的 `us-west-2` 完成，使用私有 EKS、RDS、
JDBC catalog 和真实 S3。所有数据均为合成数据。

## 接收口径

| 场景 | 增量消费 | 固定快照批读 | 接收结论 |
| --- | --- | --- | --- |
| 主键表有效 insert/update/delete | 按快照顺序应用 `input` changelog，并核对前镜像 | `scan.mode=from-snapshot` 固定终点 | 行集、值、版本一致，前镜像无偏差 |
| 主键表历史非法源版本 | 保留实际产生的变更，不跳过错误 | 固定终点 | 保留 RED，不以之后的正确结果掩盖历史错误 |
| append 表正常追加 | `scan.mode=latest` 在读端启动后只读新增行 | 两次固定快照 | 新增行一致；无主键、不保证物理顺序 |
| append 表分区覆盖 | 默认流读没有旧行撤回 | 固定快照反映覆盖结果 | 必须显式重算下游；不能把覆盖称为完整行级 changelog |
| MySQL 序列与主键异常 | 源库在提交前拒绝，避免污染 `input` 前镜像 | 源行保持原值 | 分别核验缺失、`NULL`、同值、回退、删除后重插与改主键 |

主键表的 `sequence.field=version_no`、`changelog-producer=input`、
`write-only=true` 与单 bucket 来自 #8 的实际 ODS 表。此处测试 Paimon 2.0.0
原生读取路径；现有 #8 表未启用 DV，隔离的 #11 主键表另测 DV。
Doris 对 DV/MOR 的可见性与性能由 #14 另行验收。
Kafka 事件表与隔离测试表均为 `bucket=-1` 的无主键 append 表。

## 重放方法与隔离

在 Issue #11 干净提交上执行：

```bash
mvn -B -f infra/m1/issue11/pom.xml clean verify
bash infra/m1/issue11/run-probe.sh accounts 1 4
bash infra/m1/issue11/run-probe.sh accounts 21 24
bash infra/m1/issue11/run-probe.sh accounts 1 24  # 预期 RED
bash infra/m1/issue11/run-probe.sh clean
bash infra/m1/issue11/run-probe.sh append
COVE_SOURCE_WRITES_PAUSED=confirmed \
  bash infra/m1/issue11/run-source-sequence.sh enforce  # 仅迁移旧的合成源表
bash infra/m1/issue11/run-source-sequence.sh
```

`run-probe.sh` 只使用 CDK 栈已创建的私有 ECR 镜像、私有 S3 仓库、JDBC catalog
和 EKS；每次从干净提交重新构建 Java jar，以 SHA-256 核对后挂载。
Pod 只落在私有节点，不开放
Service/Ingress/hostPort，catalog 密码仅通过 Kubernetes Secret 引用，
S3 由 IRSA 授权。append 实验创建唯一 `issue11_append_*` 表，结束时删除 catalog
表并核对该 S3 前缀无剩余对象。MySQL 客户端 Pod 不挂载 Kubernetes
服务账号令牌，管理员密码从 Secrets Manager 经 stdin 写入临时 Secret，
每次执行后删除临时 Secret 与 Pod。所有证据写入被忽略的
`infra/m1/evidence/issue11-*`。

## 实测结果

| 输入与区间 | 流读和批读 | 具体证据 |
| --- | --- | --- |
| `accounts` 快照 1→4 | PASS | 快照 4 的 DELETE/INSERT 包含一次更新及一次删除；4 条 changelog、0 前镜像偏差，终点 3 行相同。`issue11-table-semantics-20260929025848-1486579/result.tsv` |
| `accounts` 快照 21→24 | PASS | 有效版本 5→6 的 DELETE/INSERT 与终点 3 行一致，0 前镜像偏差。初次 Java 通过但 shell 包装器误报的证据为 `issue11-table-semantics-20260929025807-1484147/result.tsv`；修正后最终重跑在 `issue11-table-semantics-20260929030712-1520994/result.tsv`。 |
| `accounts` 快照 1→24 | RED | 历史回退及重复版本污染了 `input` changelog；16 条变更中 5 次顺序/前镜像偏差，流读最终状态与固定批读不一致。`issue11-table-semantics-20260929025719-1480268/result.tsv`。 |
| 隔离 DV 主键表，默认批读 | RED | `deletion-vectors.enabled=true`、`write-only=true` 的首个已提交快照未返回 L0 行，证明无维护作业时存在可见性窗口；`issue11-table-semantics-20260929031347-1583815/reader-error.txt`。表已清理。 |
| 隔离 DV 主键表，开启 `merge-on-read` | PASS | 从首快照起直接向 Paimon 写入符合源端版本契约的合成历史；7 条更新、删除、新增变更，0 前镜像偏差，终点 2 行与批读一致；`issue11-table-semantics-20260929031651-1617126/result.tsv`，隔离 S3 前缀剩余 0 对象。该读法会付出读时合并成本；实际 MySQL CDC 路径另见 #8 及本表合法区间。 |
| 隔离 append 表 | PASS，且确认覆盖限制 | 快照 1→2 的新行 B 同时可见；快照 3 用 C 覆盖分区后，批读只见 C，流读无撤回；按覆盖后的快照重算下游，再接收快照 4 的 D 后重新与批读一致。四个快照的行数与 ID 聚合分别为 `(1,1)`、`(2,3)`、`(1,1)`、`(2,4)`。`issue11-table-semantics-20260929031749-1621587/result.tsv`；测试表及 S3 前缀已清空。 |
| MySQL 遗留源表 | RED → 修复后 PASS | RDS 会话的 `sql_mode=NO_ENGINE_SUBSTITUTION`，原来省略 `version_no` 的 INSERT 在事务内被接受，`issue11-source-sequence-20260929030334-1507382/result.tsv` 保留 RED，事务已回滚。四表新增 `CHECK (version_no > 0) ENFORCED` 后均拒绝缺失版本、删除后重插和改主键；`accounts` 还拒绝显式 `NULL`、同值、回退。合法新键插入在事务内成功，回滚后源行及保留项均无残留。四表 CHECK、NOT NULL 和主键触发器均生效。最终证据为 `issue11-source-sequence-20260929032512-1665008/result.tsv`。 |

上表证据目录均位于 `infra/m1/evidence/`。初次读取旧 schema 时，
`issue11-table-semantics-20260929025615-1475720/` 因探针访问尚不存在的
`note` 列而失败；代码已按实际行列数处理该字段，后续重跑才得到表中的历史 RED。
约束迁移后的 #8 源库与真实 S3 ODS 再次对账：`accounts=3`、
`orders=2`、`products=3`、`promotions=3` 行均相符；原始文件分别为
`infra/m1/evidence/issue8-source-client-20260929030451-1512041/source-state.tsv`
和 `infra/m1/evidence/issue8-ods-inspect-20260929030509-1511970/ods-state.tsv`
（保存在 #8 worktree）。`cove-mysql-cdc` 的实际代次 9/9、状态 RUNNING。
主键保留迁移后又对账一次，相同行数与内容在 #8 worktree 的
`issue8-source-client-20260929031914-1626762/source-state.tsv` 和
`issue8-ods-inspect-20260929031932-1626691/ods-state.tsv`；CDC
于 03:19:54 UTC 完成新的私有 S3 checkpoint，原始状态位于
`infra/m1/evidence/issue11-post-key-guard-health-202609290321/`。
当前 `cove_cdc` 的授权读取在 #8 worktree 的
`issue8-source-client-20260929032019-1638582/grants.tsv`：只有
`source_db.*` 的 SELECT，没有 `issue8_guard` 的读取授权。

## 当前表准入

`ods.issue8_accounts` 快照 1→24 的历史 changelog 状态为 **NOT_QUALIFIED**：
不能向 DWD/DWS、Doris 或其他下游发布该历史流，也不能把目前源库与 ODS
行集相同解释为历史前镜像已修复。表仍仅在私有 PoC 环境中运行，用于保存
原始 RED；目前没有已发布的下游消费者。快照 21→24 的通过只证明该受约束
区间正确，不给整张旧表解除隔离。隔离的 #11 DV 表证明新建表从首快照开始
可满足语义，实验后已清理；任何 CDC 生产路径发布前仍须从受约束源端
新建目标表，完成全历史增量/批读对账和独立入口授权测试。

Paimon 的 append 覆盖实验直接用 2.0.0 的 `withOverwrite` 提交分区覆盖。
它证明当前 Paimon 默认流读的边界；Spark SQL `DELETE/UPDATE/MERGE INTO`
的具体执行路径尚未在此实验测量。覆盖后下游必须按影响范围重算，不能期待
覆盖事务自动向已消费的流发出完整撤回。这里的分析口径是对固定快照的
Paimon 原生批读执行行数与 ID 合计聚合；Doris SQL 查询与 native/JNI 路径
仍由 #14 验收。

## 发布边界

源端若允许回退或重复 `version_no` 进入 binlog，#8 已观测到旧行 DELETE
与新行 INSERT 的序列会破坏完整前镜像。历史 RED 不能用随后安装的触发器消除。
新表须在第一条数据提交前建立、核验同一源端约束；约束无法保证时，
`input` 策略不得宣称完整变更流。

本次又实测到 RDS 默认会话没有严格 SQL 模式：`BIGINT NOT NULL` 单独使用时，
省略该列的写入会以隐式值通过。DDL 现在为初始两表、新增两表及隔离 schema
测试表设置正版本 CHECK；已存在的四张合成源表由幂等的 `enforce` 命令迁移，
同时保留 UPDATE 触发器来拒绝相同或回退的版本。约束必须在真实源库验证，
不能只看 DDL 文本推断其已生效。

删除后原 ID 不可复用。初始建表和新表 DDL 在第一条 INSERT 前建立私有
`issue8_guard.used_keys` 保留表与 `BEFORE INSERT` 触发器，首次写入与业务行在
同一事务保留 ID，DELETE 不释放，`BEFORE UPDATE` 禁止修改 ID。现有合成库的
迁移先保留四表当前 ID，再补录早期已删除的 `accounts.id=3`；实测重插该 ID
返回 MySQL 1062、改主键返回 45000，均未提交。生产接入历史表时必须从完整
历史补齐已删除 ID，并在整个迁移期间暂停业务写入；若历史不全或未停写，
不能宣称已经防止主键重用。本次固定 #8 合成环境仅使用受控私有客户端写入，
`enforce` 还要求停写确认、无其他合成客户端 Pod 及无其他 MySQL 管理会话。
保留表在同一私有、
加密的 RDS 内，CDC 用户只有 `source_db` 读权限，不授予该保留表的读取权限。

当前 ECR 基础镜像扫描仍含 Critical/High 漏洞，运行范围限私有合成数据 PoC，
不作为生产发布依据。没有新增公开页面或资源；若增加公网入口，
开放前必须完成认证、匿名拒绝与越权拒绝实测。
约束迁移和临时 Pod 清理后，#6 的完整线上安全脚本于 2026-09-29
重新通过：现存 S3 对象匿名读取返回 403，EKS 公网 API 仅限运维主机 `/32`
且匿名路径返回 401/403；集群无公开 Service、Ingress、Gateway/Route 或
项目 hostPort，RDS 非公网，跨前缀 IRSA 读取与列举均明确拒绝。
原始证据保存在 #6 worktree 的
`infra/m1/evidence/security-0929030745/`。

> 注：本文中的 issue 与 PR 编号（如 #11）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
