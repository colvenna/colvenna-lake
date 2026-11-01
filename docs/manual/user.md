---
title: "Colvenna Lake 用户手册"
date: 2026-10-20
status: draft
issue: 161
---

# 用户手册

**Colvenna Lake** · 基于 [Apache Paimon](https://paimon.apache.org/) 的声明式实时湖仓 / *Declarative real-time lakehouse, powered by Apache Paimon*

本手册面向平台的使用者：

- 数据工程师：接入数据源，声明分层表与任务；
- 分析师：查看板与 BI；
- 平台管理员：管理用户、豁免与合规删除，查看告警。

手册只写已经交付的行为。各能力的限制与未验证的项写在相应各节的末尾，第 17 节另列平台层面的几项。平台怎样安装、升级与卸载，见[安装部署手册](install.md)；备份与恢复对使用者的影响见第 16 节。每个主要流程对应的验收用例见[附录 A](#附录-a-流程与-uat-用例对照)。

**示例怎样校验过。**

- 每段 YAML 都在 2026-10-20 新建的 `uat` 环境上以 `kubectl apply --dry-run=server --validate=strict` 校验通过；只有几行的片段，放进完整的声明后校验。
- 声明改写自验收测试实际应用过的声明，各处注明出自哪个测试；测试脚本都在 `tests/acceptance/<能力>/` 下。`colvenna` 与 `kubectl` 的命令写法与验收测试相同。
- 示例中的主机名、库名、Secret 名都是占位，换成你自己的即可。

## 1. 概念

### 1.1 一份数据走过的路

```text
源库 (MySQL / PostgreSQL / MongoDB)、Kafka topic
   │  LakeSource：平台建作业、建表，持续接入
   ▼
ODS 表（接入库，Paimon）
   │  LakeTable：一条 Flink SQL，常驻的流作业
   ▼
DWD / DWS 表（分层表，Paimon）
   ├─ LakeDashboard ──► 平台 Doris ──► 看板查询、BI（Superset）
   ├─ LakeIcebergPublication ──► Polaris ──► Spark、Trino 等外部读端
   └─ readers ──► 其他经 colvenna-catalog 读湖的引擎
```

围绕这条路，还有几种声明：

- `LakeTask`：一次批处理或维护，例如压缩、快照过期、打 tag、回刷、质量校验；
- `LakeQuality` 与 `LakeWaiver`：批量质量规则与门禁，以及对一次校验的豁免；
- `governance`（写在 `LakeSource`、`LakeTable` 里）：负责人、级别、个人数据、保留期与列标签；
- `LakeErasure`：在所有受管副本里删除一个数据主体。

湖里的每张表都是 Apache Paimon 表，存放在环境的仓库桶里，元数据由平台自带的 REST catalog 服务 **colvenna-catalog** 管理。所有写入与读取都经过 colvenna-catalog：它在服务端原子地提交快照，并按表签发临时的对象存储凭证。colvenna-catalog 是同一系列的独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog)，每套环境都装 Colvenna Lake 固定的它的发布版，现在是 v1.0.0（由它的候选 v1.0.0-rc.4 晋升而来，[`deploy/images/colvenna-catalog.json`](../../deploy/images/colvenna-catalog.json)）；它的接口、token 与指标在本手册里照常使用，实现设计见它的 [LLD](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0/docs/design/colvenna-catalog-lld.md)。

### 1.2 声明与受管命名空间

你用 Kubernetes 自定义资源（下称"声明"）告诉平台要什么，**colvenna-operator**（下称 Operator）负责做出来，并把进展写进声明的 `status`。

- 声明只在**受管命名空间**里生效，默认是 `colvenna-data`。Operator 不看别的命名空间。
- 一个声明的所有产物都留在它的命名空间里：Flink 作业、Kubernetes Job、Secret，以及它在 colvenna-catalog 里的身份。
- 准入与授权都在服务端：Operator 与 colvenna-catalog 执行检查。用 `colvenna` CLI、控制台还是 `kubectl apply` 提交，结果相同。
- 受管命名空间由平台管理员增加：用同一个 `MANAGED_NAMESPACES` 先部署 Operator 栈，再装 Operator（[安装部署手册](install.md)第 4 节）。每个受管命名空间有自己的作业角色，只能访问自己的 Flink 状态。一个命名空间的名字不能是另一个的开头，例如不能同时有 `colvenna-data` 与 `colvenna-data-b`。

八种声明，API 组都是 `colvenna-lake.io/v1alpha1`：

| 声明 | 短名 | 用途 | 本手册 |
| --- | --- | --- | --- |
| `LakeSource` | `ls` | 接入一个数据库或一组 Kafka topic，产出 ODS 表 | 第 3 节 |
| `LakeTable` | `lt` | 以一条 SQL 从上游表算出一张分层表 | 第 4 节 |
| `LakeTask` | `ltk` | 一次批处理或维护，只运行一次 | 第 5 节 |
| `LakeQuality` | `lq` | 一张表的批量质量规则与发布门禁 | 第 6 节 |
| `LakeWaiver` | `lw` | 接受一次校验里未通过的规则 | 第 6 节 |
| `LakeErasure` | `ler` | 删除一个数据主体 | 第 7 节 |
| `LakeDashboard` | `ldb` | 在平台 Doris 上服务一组看板查询 | 第 8 节 |
| `LakeIcebergPublication` | `lip` | 把一张表以 Iceberg 元数据只读发布 | 第 9 节 |

### 1.3 库与表

- 一个 `LakeSource` 独占一个**接入库**（`spec.target.database`），表名与源表名相同，例如 `ods_shop.orders`。接入库与用户的读端隔离：除了本源，没有谁能读整个库。
- `LakeTable` 写进命名空间的**分层库**，例如 `dwd_shop.orders`。分层库不能是某个源的接入库。
- 表名写作 `<库>.<表>`，库名与表名都只用小写字母、数字与下划线，以字母开头。

### 1.4 身份与权限

| 谁 | 身份 | 能做什么 |
| --- | --- | --- |
| `LakeSource`、`LakeTable`、`LakeTask`、`LakeDashboard`、`LakeErasure` | colvenna-catalog 里的 principal `<kind>.<命名空间>.<名字>.<uid 前 8 位>`；token 在 Secret `<名字>-catalog-token` | 只有它需要的授权：源对自己的接入库有库级 `MANAGE`；分层表对上游有表级 `READ`、对目标有表级 `WRITE`；看板与删除请求只读它们涉及的表 |
| 你在 `readers` 里列出的读端 | 一个已存在的 colvenna-catalog principal | 受治理的表发布后，得到表级 `READ` |
| 看板用户 | Doris 用户 `<命名空间>.<看板>.<用户>` | 只能查授给它的看板查询对象 |
| Iceberg 读端 | Polaris 的 catalog role | 只读，凭证由 Polaris 按表签发 |
| 人 | Kubernetes 身份（EKS 上即 IAM 身份）；Web 入口的 Cognito 用户 | 由 Kubernetes RBAC 与入口的用户组决定 |

删掉再建同名声明，会得到一个新的 principal：uid 不同，停用的 principal 也不能复用。

### 1.5 状态怎么看

声明的 `status` 大多有这几样（`LakeWaiver` 没有条件，只有阶段、消息与事件）：

- `phase`：一个词的总体状态，例如 `Running`、`Degraded`、`Failed`。`kubectl get` 与 `colvenna get` 显示它。
- `message`：一句话说明。
- `conditions`：Kubernetes 风格的条件，每个有 `type`、`status`、`reason`、`message`。出问题时先看哪个条件为 `False`，它的 `reason` 是稳定的英文词，`message` 写明原因与做法。
- 事件：Operator 在状态变化时发 Kubernetes 事件，类型为 `Normal` 或 `Warning`。

```bash
kubectl -n colvenna-data get ls,lt,ltk,lq,lw,ler,ldb,lip
colvenna -n colvenna-data describe source orders-mysql          # 状态、条件、作业、表与最近 20 条事件
kubectl -n colvenna-data get events --field-selector involvedObject.name=orders-mysql,type=Warning
```

条件只在 `status` 于 True 与 False 之间翻转时发 Warning 事件。原因变了而状态仍为 False 时不再发事件，所以要以 `status` 为准。

## 2. 开始之前

**集群访问。**

- kubeconfig 在安装机器的 `deploy/.state/<环境>/kubeconfig`。EKS 端点只接受安装者的出口地址，见[安装部署手册](install.md)第 5 节。
- 平台不附带给数据工程师的 Role。集群管理员按需授予受管命名空间里 `colvenna-lake.io` 资源的权限。
- 批准豁免另要 ClusterRole `colvenna-operator-waiver-approver`（第 6.5 节）。

**`colvenna` CLI。** 在仓库里运行 `colvenna-cli/check.sh build`（需要 Docker），得到 `colvenna-cli/dist/colvenna-linux-amd64`、`colvenna-linux-arm64` 与 `colvenna-darwin-arm64`。CLI 用你的 kubeconfig 与 Kubernetes 身份，只调 Kubernetes API，见第 11 节。

**口令与证书。** 声明里不写口令，只引用同一命名空间里的 Secret 与 ConfigMap：

```bash
# 用户名与口令；口令从文件读，不出现在命令行
kubectl -n colvenna-data create secret generic orders-mysql-cdc \
  --from-literal=username=colvenna_cdc --from-file=password=./cdc-password.txt
# 源端的 CA 证书，键名必须是 ca.pem
kubectl -n colvenna-data create configmap orders-kafka-ca --from-file=ca.pem=./kafka-ca.pem
```

安装脚本已在每个受管命名空间里放好 ConfigMap `rds-ca`，内容是 AWS RDS 的全局 CA 证书包，源库是 RDS 时直接引用它。

**Web 入口。** 控制台与 BI 的地址是 `https://console.<入口域名>/` 与 `https://bi.<入口域名>/`。入口域名由平台管理员安装时定下，默认 `colvenna-<环境>.<托管区>`（[安装部署手册](install.md)第 1 节），例如托管区 `example.com` 里的环境 `uat` 是 `https://console.colvenna-uat.example.com/`。账号由平台管理员添加，见第 12.1 节。

## 3. 数据源：LakeSource

一个 `LakeSource` 接入一个源库，或一组 Kafka topic。Operator 为它：

1. 在 colvenna-catalog 建接入库（`spec.target.database`），在库上记下属主；
2. 建它的 catalog 身份与 token；
3. 逐张检查源表是否满足接入条件（下称"准入"），把通过的表放进作业；
4. 部署并托管 Flink 作业，作业建 ODS 表并持续写入；
5. 对每张表核对源库与湖里的行，一致后把这张表标为 `Ready`；
6. 表受治理时，把它发布给 `readers`。

| `spec.type` | 作业 | ODS 表 | 写入方式 |
| --- | --- | --- | --- |
| `mysql` | 每个源一个，`<名字>-cdc`，读 binlog | `<接入库>.<源表名>`，主键表 | 只写，不压缩（write-only）；压缩由你的 `LakeTask` 完成 |
| `postgres` | 每张表一个，`<名字>-<表名>`，各有自己的复制槽 | 同上；分区表读它的叶子分区，写进一张表 | 同上 |
| `mongodb` | 每个源一个，`<名字>-cdc`，读 change stream | `<接入库>.<集合名>`，主键 `_id`，其他顶层字段都是 STRING，嵌套文档与数组写成 JSON 文本 | 写入端自己压缩 |
| `kafka` | 每个源一个，`<名字>-events` | `<接入库>.<target.table>` 与拒绝表 `<target.table>_rejections`，append 表，按事件时间分区 | 只写 |
| `mysql` 加 `spec.transport.kafka` | `<名字>-kafka`，读 Debezium 写到 Kafka 的变更 | 与直连 MySQL 相同 | 写入端自己压缩 |

### 3.1 共同的字段

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `type` | 必填 | `mysql`、`postgres`、`mongodb`、`kafka` |
| `target.database` | 必填 | 接入库，只属于这一个源 |
| `target.bucket` | `1` | 主键表的固定桶数，1–1024；Kafka 事件表不用 |
| `target.tableOptions` | — | 额外的 Paimon 表选项，只接受下列键，以及任何 `file-index.*`：`snapshot.time-retained`、`snapshot.num-retained.min`、`snapshot.num-retained.max`、`changelog.time-retained`、`changelog.num-retained.min`、`changelog.num-retained.max`、`consumer.expiration-time`、`deletion-vectors.enabled`、`file.format`、`file.compression`、`target-file-size`、`write-buffer-size`、`write-buffer-spillable`、`num-sorted-run.compaction-trigger`、`num-sorted-run.stop-trigger`、`metadata.iceberg.storage`（只能是 `table-location`）、`metadata.iceberg.previous-versions-max`（1–999） |
| `tables.include`、`tables.exclude` | 全部表 | 正则表达式，按整个表名匹配，排除优先；Kafka 源不用 |
| `job.parallelism` | `1` | 1–64。数据库源的作业部署后并行度固定，见第 3.8 节 |
| `job.checkpointInterval` | `30s` | 也是数据可见的最短延迟；Kafka 源至少 `10s` |
| `job.jobManager`、`job.taskManager` | 1 CPU；`2048m`、`3072m` | `cpu` 与 `memory`；CPU 上限是请求的两倍 |
| `governance` | — | 负责人、级别、个人数据、保留期、列标签，见第 7 节。不写则按未分类处理，不发布 |
| `readers` | — | 发布给哪些 colvenna-catalog principal，最多 32 个 |
| `deletionPolicy` | `Retain` | 删除声明时 ODS 表保留（`Retain`）还是删掉（`Delete`） |
| `suspend` | `false` | `true` 时停下作业、保留读到的位置 |
| `monitoring.heartbeat` | — | 只用于 MySQL 与 PostgreSQL：Operator 定时写一行心跳，用来测端到端延迟，见第 14.4 节 |

所有 `*Secret` 字段的写法相同：`{name: <Secret 名>, usernameKey: username, passwordKey: password}`，两个键名可省略。

### 3.2 MySQL

**源库的设置。** `log_bin` 打开，`binlog_format=ROW`，`binlog_row_image=FULL`。RDS 上 binlog 需要自动备份保留期大于 0；验收环境另把 binlog 保留设为 24 小时（`CALL mysql.rds_set_configuration('binlog retention hours', 24)`）。Operator 每一轮都检查这三项。

**CDC 用户**（`connection.credentialsSecret`）：

- `SHOW DATABASES`、`REPLICATION SLAVE`、`REPLICATION CLIENT ON *.*`，以及源库的 `SELECT`；
- 连接要求 TLS（验收用户都是 `REQUIRE SSL`）。MySQL 8.4 的默认认证插件 `caching_sha2_password` 经 TLS 登录，不需要取服务器的公钥；验收环境的 CDC 用户就用它（RDS MySQL 8.4）。
- MySQL 只向有 `TRIGGER` 权限的用户显示触发器，而准入要看触发器。所以要么给 CDC 用户这个库的 `TRIGGER`，要么另设 `connection.inspectorSecret`：一个有 `SELECT, TRIGGER ON <库>.*` 的用户，只用于准入检查。

**源表约定。** 每张表都要满足下面几条，否则不接入（表的状态为 `Rejected`，原因见第 3.7 节）：

- 有主键；表名只含小写字母、数字与下划线；
- 有一个版本列，默认 `version_no BIGINT NOT NULL`（有符号），不在主键里，带强制的 `CHECK (version_no > 0)`。每次更新都要把它调大，它就是 Paimon 的 `sequence.field`。列名不同时用 `target.versionColumn` 指定；
- 一个 BEFORE UPDATE 触发器：版本没有变大时 `SIGNAL`；
- 一个 BEFORE UPDATE 触发器：主键变化时 `SIGNAL`；
- 一个 BEFORE INSERT 触发器，把主键写进一张保留表（触发器体里要有 `INSERT INTO`），保证删掉的主键不会再被用；
  - RDS MySQL 8.4 打开 binlog 时，管理账号（有 `SET_ANY_DEFINER`，没有 `SUPER`）要在参数组里把 `log_bin_trust_function_creators` 设为 1 才能建这几个触发器，否则报 `ERROR 1419`；验收环境的测试源就是这样设的；
- 列类型只用 `tinyint`、`smallint`、`mediumint`、`int`、`bigint`、`decimal`、`float`、`double`、`char`、`varchar`、`tinytext`、`text`、`mediumtext`、`longtext`、`date`、`datetime`。二进制类型（`binary`、`varbinary`、各种 `blob`、`bit`）不接入。

验收测试按下面的样子建表（`tests/acceptance/cdc/mysql_contract.py`）：

```sql
CREATE TABLE shop.orders (
    id BIGINT NOT NULL, customer_id BIGINT NOT NULL, amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(32) NOT NULL, note VARCHAR(64) NULL,
    version_no BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT orders_positive_version CHECK (version_no > 0) ENFORCED
) ENGINE=InnoDB;
DELIMITER //
CREATE TRIGGER shop.orders_version_guard BEFORE UPDATE ON shop.orders FOR EACH ROW
BEGIN
    IF NEW.version_no <= OLD.version_no THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'NON_MONOTONIC_VERSION_NO';
    END IF;
END//
CREATE TRIGGER shop.orders_key_immutable BEFORE UPDATE ON shop.orders FOR EACH ROW
BEGIN
    IF NEW.id <> OLD.id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PRIMARY_KEY_IMMUTABLE';
    END IF;
END//
CREATE TRIGGER shop.orders_key_reserve BEFORE INSERT ON shop.orders FOR EACH ROW
BEGIN
    INSERT INTO shop_guard.used_keys (source_table, source_key)
    VALUES ('orders', CONCAT_WS('|', CAST(NEW.id AS CHAR)));
END//
DELIMITER ;
```

**连接字段。**

| 字段 | 说明 |
| --- | --- |
| `connection.host`、`connection.port` | 端口默认 3306 |
| `connection.database` | 源库 |
| `connection.credentialsSecret`、`connection.inspectorSecret` | 见上 |
| `connection.caConfigMap` | 源库的 CA（键 `ca.pem`）。给了就校验服务端证书与主机名（`VERIFY_IDENTITY`）；不给仍要求 TLS，但不校验证书，条件 `SourceVerified` 为 False 并发 Warning `TlsNotVerified` |
| `connection.serverTimeZone` | 源库的时区，用于 `TIMESTAMP` 列 |

**server-id。** 每个 MySQL 源从 Operator 的 `mysqlServerIdRange`（默认 `6000-6999`）里分得一段 server-id，记在 `status.serverIdRange`。这段范围不能与源库的其他复制客户端重叠。

示例：接入 `shop` 库的全部表（`run-cdc.sh` 的 MySQL 部分）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: orders-mysql, namespace: colvenna-data}
spec:
  type: mysql
  connection:
    host: orders-db.example.us-west-2.rds.amazonaws.com
    database: shop
    credentialsSecret: {name: orders-mysql-cdc}
    inspectorSecret: {name: orders-mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  target:
    database: ods_shop
    bucket: 2
  job:
    jobManager: {cpu: 0.5, memory: 1536m}
    taskManager: {cpu: 0.5, memory: 2048m}
```

示例：只接入两张表，带治理并发布给读端 `doris-bi`，删除声明时一并删表（`run-governance.sh`）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: crm-mysql, namespace: colvenna-data}
spec:
  type: mysql
  connection:
    host: crm-db.example.us-west-2.rds.amazonaws.com
    database: crm
    credentialsSecret: {name: crm-mysql-cdc}
    inspectorSecret: {name: crm-mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, customers]}
  target: {database: ods_crm, bucket: 2}
  governance:
    owner: crm-team
    level: L3
    categories: [personal]
    retention: 24h
    subjectKeys: {orders: [customer_id], customers: [id]}
  readers: [doris-bi]
  deletionPolicy: Delete
```

### 3.3 PostgreSQL

**源库的设置。** `wal_level=logical`（RDS 上 `rds.logical_replication=1`）；`max_replication_slots` 与 `max_wal_senders` 够用：每张表一个复制槽。

**CDC 用户**：有 `REPLICATION` 属性，或是 `rds_replication` 的成员；另有库的 `CONNECT`、schema 的 `USAGE` 与表的 `SELECT`。

**publication。** 由源库的所有者建，`publish_via_partition_root = false`，列出要接入的表；分区表列出它的叶子分区。平台不建、也不改 publication。新表要接入时，在源库 `GRANT SELECT` 并 `ALTER PUBLICATION ... ADD TABLE`。

**源表约定。**

- 有主键，主键里没有 `varchar` 或 `char` 列；
- 版本列 `version_no bigint NOT NULL CHECK (version_no > 0)`，规则同 MySQL；
- 一个 BEFORE UPDATE 触发器函数：版本没有变大时 `RAISE EXCEPTION`（`BEFORE UPDATE OF <列>` 形式的触发器不算）；
- 一个 BEFORE INSERT 触发器，把主键写进保留表；
- 表与每个叶子分区都是 `REPLICA IDENTITY FULL`；分区最多一层；
- 列类型只用 `int2`、`int4`、`int8`、`numeric`、`float4`、`float8`、`varchar`、`bpchar`、`text`、`date`、`timestamp`；不用 `bytea`。

PostgreSQL 不需要单独的主键变化触发器：作业用完整的前镜像核对主键。

示例（`run-cdc.sh` 的 PostgreSQL 部分）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: orders-pg, namespace: colvenna-data}
spec:
  type: postgres
  connection:
    host: orders-pg.example.us-west-2.rds.amazonaws.com
    database: shop
    schema: public
    publication: colvenna_shop
    credentialsSecret: {name: orders-pg-cdc}
    caConfigMap: rds-ca
  target:
    database: ods_shop_pg
  deletionPolicy: Delete
```

`connection.schema` 与 `connection.publication` 必填。给了 `caConfigMap` 时作业以 `verify-full` 连接，否则以 `require`。

删除 PostgreSQL 源时，Operator 删掉它的复制槽。槽还被占用时每 10 秒重试，5 分钟后仍删不掉就发 Warning `SlotNotDropped` 并列出这些槽。它们会一直占着 WAL，要在源库手工 `pg_drop_replication_slot`。

### 3.4 MongoDB

**源库**：副本集（单机没有 change stream；这一版不接受分片集群），版本 4.0–7.x，连接必须是 TLS。

**用户**：库的 `find` 与 `changeStream`。另给 `local` 库的 `read`，Operator 才能读 oplog 的时间窗；没有它，条件 `RetentionWindow` 为 False，原因 `Unknown`。

**集合**：集合名只含小写字母、数字与下划线；`_id` 必须是 ObjectId 或字符串（按 1000 个文档抽样判断，两种混用也不行）。

| 字段 | 说明 |
| --- | --- |
| `connection.host` | 副本集的一个成员，驱动自己发现其余成员；端口默认 27017 |
| `connection.database` | 源库 |
| `connection.authSource` | 用户所在的库，默认 `admin` |
| `connection.caConfigMap` | 私有 CA 必须给，否则连不上（`SourceUnreachable`） |
| `connection.minOplogWindow` | oplog 至少要能回溯多久，默认 `24h`；不足时发 Warning，作业照常运行 |

MongoDB 源不用 `target.versionColumn`，写了会被判为无效。

**作业启动后出现的集合不会加入。** 作业启动时取定接入哪些集合。之后新建的集合、放宽 `include` 新匹配到的集合、修好了的被拒集合，状态都是 `Rejected`，原因 `AddedAfterStart`。要接入它们，另声明一个 `LakeSource`，用另一个接入库。原因是上游缺陷 FLINK-40741：中途加入的集合在一次故障转移后会静默丢掉变更，上游只修了 MySQL 连接器。

示例：一个源接入列出的集合与一个模式，另一个源接入后来才有的集合 `products`（`run-mongo.sh`）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: orders-mongo, namespace: colvenna-data}
spec:
  type: mongodb
  connection:
    host: mongo-0.mongo.shop.svc
    database: shop
    credentialsSecret: {name: orders-mongo-cdc}
    caConfigMap: orders-mongo-ca
  tables: {include: [orders, customers, "prod.*"]}
  target: {database: ods_shop_mongo, bucket: 2}
  deletionPolicy: Delete
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: products-mongo, namespace: colvenna-data}
spec:
  type: mongodb
  connection:
    host: mongo-0.mongo.shop.svc
    database: shop
    credentialsSecret: {name: orders-mongo-cdc}
    caConfigMap: orders-mongo-ca
  tables: {include: [products]}
  target: {database: ods_shop_products}
  deletionPolicy: Delete
```

源端换掉一个集合（删掉重建，或改名覆盖）时，这张表判为 `Failed`，原因 `SourceReplaced`。

### 3.5 Kafka 事件

Kafka 事件源把 JSON 事件写进 append 表。平台不部署生产用的 Kafka，你提供已有集群的地址与认证信息。

**Kafka 的要求。**

- TLS 监听端口加 SASL：`SCRAM-SHA-512`（默认）、`SCRAM-SHA-256` 或 `PLAIN`。不支持 MSK IAM 与 mTLS。
- 用户对每个 topic 有 `Read` 与 `Describe`，对前缀为 `colvenna.` 的消费组有 `Read`。消费组是 `colvenna.<命名空间>.<名字>.<uid 前 8 位>`，只用于上报滞后；作业从表里记下的 offset 续读。
- 每个事件是一个 JSON 对象，不超过 1 MiB。

**字段。**

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `kafka.bootstrapServers` | 必填 | `host:port[,…]` |
| `kafka.topics` | 必填 | 1–16 个 |
| `kafka.credentialsSecret`、`kafka.mechanism` | `SCRAM-SHA-512` | SASL 用户 |
| `kafka.caConfigMap` | — | 不给则用 JVM 默认的信任库 |
| `kafka.startingOffsets` | `earliest` | 表还没读过的分区从哪里开始：`earliest` 或 `latest` |
| `events.fields[]` | 必填 | `name`、`type`（`STRING`、`BOOLEAN`、`INT`、`BIGINT`、`DOUBLE`、`DECIMAL(p,s)`），以及可选的 `required`、`maxLength`、`values`、`min`、`max` |
| `events.eventTime.field`、`.format` | `rfc3339` | 事件时间字段，`rfc3339`（必须带时区偏移）或 `epoch-millis`；这个字段不再列进 `fields` |
| `events.maxOutOfOrderness` | `5m` | 每个分区的乱序上限，更晚的判为 `LATE` |
| `events.idleness` | `1m` | 空闲多久的分区不再拖住水位线 |
| `events.acceptPast`、`events.acceptFuture` | `30d`、`1d` | 太旧（`TOO_OLD`）或太超前（`TOO_NEW`）的事件被拒 |
| `events.unknownFields` | `keep` | 未声明的字段放进 `extra_json`（`keep`），或整条拒绝（`reject`） |
| `target.table` | 必填 | 事件表名 |
| `target.partitioning` | `daily` | 按 `dt`（`daily`）或 `dt, hr`（`hourly`）分区，按 UTC 的事件时间 |

事件表的列：声明的字段，然后是平台加的 `event_time_ms`、`dt`、`hr`（按小时分区时）、`extra_json`（`unknownFields: keep` 时）、`_topic`、`_partition`、`_offset`。这些名字不能再声明。

**拒绝表。** 不合规的事件不进事件表，而是在 `<target.table>_rejections` 里记一行：原因、字段、发现时间与 offset，不存事件内容。原因有 `INVALID_SIZE`、`NOT_JSON_OBJECT`、`MISSING_FIELD`、`WRONG_TYPE`、`TOO_LONG`、`NOT_ALLOWED_VALUE`、`OUT_OF_RANGE`、`BAD_EVENT_TIME`、`TOO_OLD`、`TOO_NEW`、`UNKNOWN_FIELD`、`LATE`。`status.events` 按原因计数，并以告警 `ColvennaEventsRejected` 提醒。

**分区完成信号。** 一个分区空闲 1 分钟后，作业在分区目录写 `_SUCCESS` 文件，编排器可以用 S3 sensor 等它，再触发下游批任务。

示例（`run-kafka.sh`）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: orders-events, namespace: colvenna-data}
spec:
  type: kafka
  kafka:
    bootstrapServers: kafka-bootstrap.kafka.svc:9093
    topics: [orders-events]
    credentialsSecret: {name: orders-events-kafka}
    caConfigMap: orders-events-kafka-ca
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: event_type, type: STRING, required: true, values: [click, view, purchase]}
      - {name: amount, type: "DECIMAL(12,2)", min: 0, max: 100000}
      - {name: user_id, type: BIGINT}
    eventTime: {field: event_time}
    maxOutOfOrderness: 1m
    idleness: 30s
  target: {database: ods_orders_events, table: events}
  governance: {owner: web-team, level: L2, retention: 7d}
  readers: [doris-bi]
  deletionPolicy: Delete
```

**后来能改什么。** 在 `fields` 末尾加可选字段：表加一列，作业重启，之前的值仍在 `extra_json` 里。删字段、改名、改类型、加必填字段、调换顺序都被拒绝（`SpecApplied` 为 False，原因 `SchemaChanged`），作业照旧运行；要这样改，另声明一个源。改 `target.database` 或 `target.table` 同样被拒（`TargetChanged`）。增减 topic 或改地址时，作业重建，从表里记下的 offset 续读。

### 3.6 Kafka 承载的 CDC

MySQL 源可以改为从你的 Kafka 读 Debezium 写下的变更，而不是直连 binlog：同一份 binlog 有多个消费方，或者需要独立于 binlog 保留期的回放缓冲时，用这种方式。

**要求。**

- 第 3.2 节的全部要求：源库仍需可直连，准入与核对都直接查源库。
- 你的采集器（Debezium MySQL connector 或 Debezium Server）写 Debezium JSON：
  - 带 schema：JSON converter 打开 `schemas.enable`，否则每列都被当成字符串；
  - 设 `column.propagate.source.type=.*`，否则目标表的列会按猜出的类型加宽；
  - `topic.prefix` 与声明的 `topicPrefix` 相同，每张表的 topic 是 `<topicPrefix>.<源库>.<表>`；
  - DECIMAL 用默认的 base64 或 `NUMERIC` 都可以。
- 采集器的 `database.server.id` 不能落在 Operator 的 server-id 范围（默认 6000–6999）里；验收用 7100。
- topic 的 `cleanup.policy=delete`。压缩的 topic 判为 `TopicCompacted`。
- Kafka 用户对每个表的 topic 有 `Read`、`Describe`、`DescribeConfigs`，对 `colvenna.` 前缀的消费组有 `Read`；只支持 SCRAM。
- 目标表的快照保留期要长于两倍的 `overlap`，并且不设 `snapshot.num-retained.max`。

**怎样切换。** 在一个已经 `Running` 的直连源上加 `spec.transport.kafka`。Operator 删掉直连作业，建 `<名字>-kafka`；新作业先把每张目标表压缩一次，再把表改为 lookup changelog，并发 `ChangelogSwitched` 事件。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `transport.kafka.bootstrapServers`、`.topicPrefix`、`.credentialsSecret`、`.caConfigMap` | — | 同 Kafka 事件源 |
| `transport.kafka.mechanism` | `SCRAM-SHA-512` | 只能是 SCRAM |
| `transport.kafka.overlap` | `10m` | 1 分钟到 24 小时。作业无状态启动时，从目标表最后一次写入之前多久开始读 |

示例（`run-kafka-cdc.sh` 切换之后的声明）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: shop-mysql-kafka, namespace: colvenna-data}
spec:
  type: mysql
  connection:
    host: orders-db.example.us-west-2.rds.amazonaws.com
    database: shop
    credentialsSecret: {name: orders-mysql-cdc}
    inspectorSecret: {name: orders-mysql-inspect}
    caConfigMap: rds-ca
    serverTimeZone: UTC
  tables: {include: [orders, segments]}
  transport:
    kafka:
      bootstrapServers: kafka-bootstrap.kafka.svc:9093
      topicPrefix: shop-cdc
      credentialsSecret: {name: shop-mysql-kafka}
      caConfigMap: shop-mysql-kafka-ca
  target: {database: ods_shop_kafka, bucket: 2}
  deletionPolicy: Delete
```

作业会核对没有送到的删除：采集器重新快照时漏掉的删除，Operator 与源库比对后补上（事件 `DeletesLost`、`DeletesRepaired`）。采集器漏掉的插入或更新，Operator 不会自己补：它发 Warning `RowsMissing`，处理方法见第 3.7 节末尾。不能解析的记录计入 `status.transport.unparsable`，并发 Warning `UnparsableEvents`。

### 3.7 状态

```bash
kubectl -n colvenna-data get ls                  # TARGET、TYPE、PHASE、READY（Ready 的表数/匹配的表数）
colvenna -n colvenna-data wait source orders-mysql --for table/orders=Ready --timeout 15m
```

**阶段。**

| `phase` | 含义 |
| --- | --- |
| `Pending` | 还没有匹配的表 |
| `Provisioning` | 作业在启动、升级或挂起，或有表在快照 |
| `Running` | 作业在运行，全部表 `Ready` |
| `Degraded` | 源库连不上、作业没在运行，或有表 `Rejected`、`Failed` |
| `Suspended` | 作业挂起，保留着位置。你设了 `suspend: true`，或者源库的设置不满足要求时 Operator 自己挂起 |
| `Failed` | 声明无效，或接入库属于别的源。Operator 不按这份声明部署或改动作业；已在运行的作业保持原样 |

**条件。**

| 条件 | 为 False 时的原因 |
| --- | --- |
| `SpecValid` | `InvalidSpec`（消息写明哪个字段）、`TargetConflict`（接入库已属于别的源） |
| `CatalogReady` | `CatalogUnavailable` |
| `SourceReady` | `CaMissing`、`CredentialsMissing`、`SourcePrerequisites`（源库设置不满足，作业被挂起）、`SourceUnreachable`、`TablesVanished`；Kafka 源另有 `Unreachable`、`TlsFailed`、`AuthenticationFailed`、`NotAuthorized`、`TopicMissing` |
| `SourceVerified` | `NoCaConfigured`（MySQL、PostgreSQL 没有给 CA）、`CaMissing` |
| `TransportReady` | 经 Kafka 承载时，同 Kafka 源的原因，加 `TopicCompacted` |
| `TargetIsolated` | `TargetReadable`：有别的 principal 能读整个接入库，新表不再准入 |
| `JobRunning` | `NotRunning`、`Suspended`、`Suspending`、`NoTables`、`UpgradeStuck` |
| `SpecApplied` | `ParallelismPinned`；Kafka 源另有 `SchemaChanged`、`TargetChanged` |
| `RetentionWindow` | MongoDB：`Short`、`Unknown` |
| `SlotsReleased` | PostgreSQL：`SlotsHeld` |
| `Governed`、`Published` | 见第 7.2 节 |
| `Ready` | 阶段不是 `Running` |

**每张表的状态**在 `status.tables[]`：

| `state` | 含义 |
| --- | --- |
| `Snapshotting` | 已准入，作业在快照，还没核对 |
| `Ready` | 按主键与版本逐行核对，与源库一致 |
| `Rejected` | 没有准入。每一轮重新判断，源表改好后自动准入 |
| `Failed` | 已移出作业，不会自己恢复 |

`Rejected` 的原因，按检查顺序：`UnsupportedName`、`NotReadable`、`NoPrimaryKey`、`VersionInPrimaryKey`、`UnsupportedKeyType`、`UnsupportedBinaryColumn`、`UnsupportedType`、`VersionColumnMissing`、`VersionColumnType`、`VersionCheckMissing`、`SourceGuardsNotVisible`、`VersionGuardMissing`、`KeyGuardMissing`、`KeyReservationMissing`、`UnsupportedPartitioning`、`ReplicaIdentityNotFull`、`NotInPublication`，以及 `TargetNotEmpty`（目标表已有数据）、`TargetNotIsolated`、`SourcePrerequisites`、`AddedAfterStart`（MongoDB）、`PrimaryKeyChanged`。每个原因的 `message` 写明缺什么。

`Failed` 的原因：`JobFailed`（作业因这张表失败，消息是作业的错误）、`JobStateLost`、`SourceReplaced`、`PrimaryKeyChanged`，以及已在作业里的表又不满足准入条件时的那个准入原因。

核对每 30 秒最多一次，单表最多读 2,000,000 行（Operator 的 `reconcileMaxRows`）。更大的表停在 `Snapshotting`，原因 `TooLarge`，并发 Warning `TableTooLarge`。

核对先读目标表，再读源库：两边不一致的键，等目标表追上源库的读取时刻之后再看一次。"追上"指目标表在那之后又有提交。没人写的表不会再有提交，这时看作业的进度（#238），前提是 MySQL 源声明了 `monitoring.heartbeat`，且心跳表与这张表在同一个作业里：直连时，湖里最新的心跳是在读取时刻之后写的即可（作业按 binlog 的顺序读全部表，一起提交）；经 Kafka 承载时，还要这张表的话题在那个心跳提交之后已被作业消费到末尾（`status.kafka.topics[]` 的 `committedOffset` 等于 `endOffset`），因为各表的话题之间没有顺序。PostgreSQL 源每张表一个作业与复制槽，MongoDB 源没有心跳，没有声明心跳的 MySQL 源也一样：它们只认新的提交，没人写的表要等下一次写入才复核完。

等新提交时，表的 `message` 写明目标表最后一次提交的时刻与应等到的时刻；作业在运行、却过了应等到的时刻 10 分钟仍没有新提交（#245）：带心跳的 MySQL 源发 Warning `TargetNotCommitting`，因为心跳也没有越过读取时刻，说明作业没有把源库的变更传下来，看作业的 checkpoint 与日志可以知道原因；其他源发 Normal 事件 `WaitingForCommit`，多半是这张表此后没有变更，下一次写入后核对就会完成。

追上之后同一批键在连续两次核对里都不一致，说明变更流丢了它们，不会自己补上：表的 `message` 写明行数与示例键，并发 Warning `RowsMissing`（每批缺口一次）。示例键是键的 SHA-256 的前 12 位十六进制，按 `<长度>:<值>|` 逐列拼接后计算；单列主键在 MySQL 里可以这样找回：`SELECT id FROM orders WHERE LEFT(SHA2(CONCAT(CHAR_LENGTH(id), ':', id, '|'), 256), 12) IN ('…')`。处理：

- 源库有、目标表缺少或版本更旧的行：让源库再发一次这些行，例如对这些键执行一次只把版本列加一的 `UPDATE`（找不到具体的键时，对整张表执行）；变更流把新版本送到目标表后，下一次核对通过。
- 目标表多出、源库已删掉的行：`Ready` 的表由 Operator 自动补删（第 3.6 节的 `DeletesLost`；不靠新提交判定时，要连续两次核对都缺才补）。`Snapshotting` 的表没有自动补删，删掉这张表的声明与目标表后重新声明。

### 3.8 修改、暂停与删除

| 改动 | 结果 |
| --- | --- |
| 改 `tables`，或源库新建一张表 | 新匹配、目标表为空的表准入；作业从 checkpoint 升级，新表在恢复时快照（MongoDB 见第 3.4 节） |
| 取消选择一张表，或源库删掉它 | 表移出作业，目标表保留，记在 `status.retired`；源库删表时发 Warning `TableGone` |
| 再选回一张目标表已有数据的表 | `Rejected`，原因 `TargetNotEmpty` |
| 源库加列、字符串变长、数值类型变宽 | ODS 表自动跟着变 |
| 源库删列、改列名 | ODS 表保留原列；改名视为新增一列 |
| 不兼容的类型变化（如 `DECIMAL` 改 `VARCHAR`） | 这张表 `Failed`（`JobFailed`），其他表照常 |
| 改资源、`checkpointInterval`，或换口令 Secret、CA ConfigMap 的内容 | 作业从最近的 checkpoint 重启并换用新值 |
| 改 `job.parallelism` | 不生效：`SpecApplied` 为 False，原因 `ParallelismPinned`，并发 Warning；改回原值即恢复。要换并行度，删掉并重建这个源 |
| `suspend: true`，之后 `false` | 停下作业、保留位置，之后从停下的地方继续。PostgreSQL 源挂起期间复制槽仍占着 WAL |
| 在 Operator 之外删掉作业的 FlinkDeployment | 直连的数据库源与 MongoDB 源：Operator 不重建，以免把快照再写一遍，表判为 `Failed`（`JobStateLost`），并发 Warning `JobLost`，只能重新声明。Kafka 事件源与经 Kafka 承载的 CDC：Operator 重建作业（事件 `JobRecreated`），从表里记下的位置续读 |
| 改 `target.database`、`target.versionColumn`、`connection.database` 或 `type` | 不支持。API 不会拒绝，但作业的状态对不上新的库，改动之后的行为没有验证过。要换，另声明一个源 |

**删除。** `colvenna delete source <名字>` 或 `kubectl delete`：

1. 停掉并删除作业；PostgreSQL 源还要删复制槽；
2. `deletionPolicy: Delete` 时删掉 ODS 表（含 `status.retired` 里的），库空了也删掉；
3. 撤回发布给 `readers` 的授权，吊销 token，停用 principal；
4. 发 `Deleted` 事件。

以默认的 `Retain` 删除后，接入库与表都还在，库上记的属主仍是已删掉的那个源。所以新声明的源**不能**再用同一个接入库，会判为 `TargetConflict`。要复用这个库名，删除前把 `deletionPolicy` 改成 `Delete`，或者请平台管理员在 colvenna-catalog 里删掉这个库。

### 3.9 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| `Failed`，`TargetConflict`："target database … already belongs to LakeSource …" | 另一个先建的源用了这个接入库。换一个 `target.database` |
| `Failed`，`TargetConflict`："… exists and is not this source's (owner …)" | 库属于别的源，或者属于以 `Retain` 删掉的旧源。见第 3.8 节 |
| `SourceReady` 为 False，`CredentialsMissing` | Secret 不存在，或缺 `username`、`password` 键（或你指定的键）。声明了 `inspectorSecret` 时它也必须存在 |
| `SourceReady` 为 False，`CaMissing` | ConfigMap 不存在，或没有 `ca.pem` 键 |
| `SourceReady` 为 False，`SourcePrerequisites`，阶段 `Suspended` | 源库设置不满足，消息逐项列出，例如 `binlog_row_image=MINIMAL`。改好后作业从原位置继续 |
| `SourceReady` 为 False，`SourceUnreachable` | 网络、TLS 或口令。作业保持原样 |
| 表 `Rejected`，`NotReadable` | CDC 用户没有这张表的 `SELECT` |
| 表 `Rejected`，`SourceGuardsNotVisible` | MySQL 用户看不到触发器：给 `TRIGGER`，或设 `inspectorSecret` |
| 表 `Rejected`，`Version…`、`KeyGuardMissing`、`KeyReservationMissing` | 源表约定缺了一项，按第 3.2、3.3 节补上 |
| 表 `Rejected`，`TargetNotEmpty` | 目标表已有数据。请平台管理员删掉目标表（`colvenna` 没有删表的命令），或另声明一个源、用新的接入库 |
| 表 `Failed`，`JobFailed` | 不兼容的结构变化，或源表的守卫被违反（消息里有 `NON_MONOTONIC_SOURCE_VERSION`、`SOURCE_KEY_CHANGED` 等）。`Failed` 不会自己恢复：取消选择这张表，删掉它的目标表，再选回来 |
| `TargetIsolated` 为 False | 有 principal 在接入库上有库级或 catalog 级授权。撤销它，改用 `readers` 做表级发布 |
| `JobRunning` 为 False，`UpgradeStuck` | 一次升级在作业初始化时失败，Flink 清掉了恢复用的数据。需要平台管理员从最近的 checkpoint 手工重新部署 |
| 阶段 `Degraded`，"No free MySQL server-id range in 6000-6999" | server-id 用完了。平台管理员扩大 `mysqlServerIdRange` |
| Kafka 源 `NotAuthorized` | 缺 topic 的 `Read`、消费组的 `Read`，或（经 Kafka 承载时）`DescribeConfigs` |
| Kafka 源 `TopicMissing` | topic 不存在，或名字写错 |
| Kafka 源 `AuthenticationFailed` | 口令错了或换过。更新 Secret，会自动恢复 |
| `Published` 为 False，`ReaderMissing` | `readers` 里的 principal 在 colvenna-catalog 里不存在，请平台管理员先建好 |

### 3.10 限制

- 数据库源的作业部署后并行度固定（`ParallelismPinned`）。Kafka 事件源跟随声明的并行度。
- 二进制列、PostgreSQL 的字符主键与其他未验证的类型（例如 MySQL `timestamp`、`json`、`enum`，PostgreSQL `timestamptz`、`bool`、`uuid`）不接入。
- Paimon 的 PostgreSQL action 读 schema 时用的连接不校验服务端证书。
- MongoDB：只接受 ObjectId 或字符串 `_id`，字段都是 STRING；只验证过副本集；作业启动后的新集合要另建源。
- PostgreSQL 分区表后来新增的叶子分区，要重启作业才会读到。读新叶子的这条路径用的是与 MongoDB 共用的 `flink-cdc-base` 框架，同类的故障转移丢数缺陷（FLINK-40741）在上游没有修；本地 12 次没有复现，记为剩余风险。
- Kafka 承载的 CDC 只支持 MySQL 与带 schema 的 Debezium JSON；删除的补写只按整数或字符串主键识别。经 Kafka 时新增表、改 `bootstrapServers` 或 `topicPrefix`、去掉 `transport` 都没有验收。
- Kafka 事件源：`PLAIN` 机制没有在 EKS 上验收（验收都用 SCRAM-SHA-512）；个人数据的事件表不发布，也不在删除范围内。
- 心跳只有 MySQL 与 PostgreSQL 源。PostgreSQL 的心跳，以及经 Kafka 承载的 MySQL 源上的心跳，都没有验收。

## 4. 分层表：LakeTable

一个 `LakeTable` 用一条 Flink SQL `SELECT` 从上游表算出一张目标表，例如 DWD 或 DWS。Operator：

1. 在一个 Job 里以这张表自己的 catalog 身份校验查询（最长 5 分钟）；
2. 按查询的输出建目标表，主键是 `spec.primaryKey`；
3. 部署常驻的流作业 `<名字>-table`，执行 `INSERT INTO <目标表> <查询>`；
4. 在每张流读的上游表上建一个 consumer，让上游的快照在作业读完前不被过期；
5. 表受治理时发布给 `readers`。

目标表是主键表，合并引擎 `deduplicate`，changelog 由 lookup 生成。所以下游读到的变更来自表里真正存过的行，写入端也会自己压缩。

### 4.1 字段

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `target.database`、`target.table` | 必填 | 目标表。库不存在就建；不能是某个 `LakeSource` 的接入库 |
| `primaryKey` | 必填 | 1–16 列，都要在查询的输出里 |
| `query` | 必填 | 一条 `SELECT`，表名写 `<库>.<表>`。每个输出列都要有小写的名字，输出就是目标表的结构 |
| `upstream` | 必填 | 查询读的**每一张**表，流读与 lookup 的都要列。只能是本命名空间的 ODS 表或分层表 |
| `bucket` | `1` | 只在建目标表时使用 |
| `tableOptions` | — | 白名单同第 3.1 节；只在建目标表时使用，之后改动不生效。治理写进表的保留选项（第 7.3 节）也一样 |
| `job.*` | 1 CPU；JobManager `1536m`、TaskManager `3072m` | 同第 3.1 节；分层表的并行度可以改，作业以 savepoint 升级 |
| `quality` | — | 流内检查，见第 4.4 节 |
| `governance`、`readers` | — | 见第 7 节；`governance.subjectKey` 列出目标表里标识数据主体的列 |
| `suspend` | `false` | 以 savepoint 停下作业，上游的 consumer 保留位置 |
| `deletionPolicy` | `Retain` | `Delete` 时删除声明会删掉目标表，除非还有别的 `LakeTable` 读它 |

### 4.2 查询的规则

校验用的是作业镜像里的 Flink 1.20 planner，以这张表的身份运行，所以只读得到 `upstream` 里列出的表。

| 被拒原因（条件 `QueryValid`） | 什么时候 |
| --- | --- |
| `InvalidQuery` | 不是一条有效的 `SELECT`；消息是 planner 的根因 |
| `UpstreamNotDeclared` | 读了没列进 `upstream` 的表 |
| `UpstreamMismatch` | `upstream` 列了查询没读的表 |
| `OutsideCatalog` | 读了 `colvenna` 以外的 catalog |
| `UnsupportedColumn` | 输出列没有小写名字，或是时间属性（例如把 `PROCTIME()` 列放进了输出） |
| `KeyColumnMissing` | 输出里没有某个主键列 |
| `UnorderedChangelog` | 同一主键的更新可能乱序到达，例如在主键以外的列上做普通 join。改用 lookup join，或以 join 键作主键 |
| `ValidationError` | 校验的 Pod 失败或没有给出结论；不是结论，下一轮重试 |

`UpstreamMissing` 不算被拒：上游表还没建好时，表停在 `Pending`，等上游出现后自己继续。所以 DWS 可以先于 DWD 声明。

维表用 lookup join（`FOR SYSTEM_TIME AS OF`），把 `PROCTIME()` 放在子查询里，不放进输出：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: orders-dwd, namespace: colvenna-data}
spec:
  target: {database: dwd_shop, table: orders}
  primaryKey: [order_id]
  upstream: [ods_shop.orders, ods_shop.segments]
  query: |
    SELECT o.id AS order_id, o.customer_id, s.label AS segment, o.amount, o.state, o.version_no
    FROM (SELECT *, PROCTIME() AS pt FROM ods_shop.orders) AS o
    JOIN ods_shop.segments FOR SYSTEM_TIME AS OF o.pt AS s ON o.customer_id = s.id
  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
```

校验通过后，`status.validation.streams` 是 `[ods_shop.orders]`，`lookups` 是 `[ods_shop.segments]`。聚合的 DWS：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTable
metadata: {name: segments-dws, namespace: colvenna-data}
spec:
  target: {database: dws_shop, table: segments}
  primaryKey: [segment]
  upstream: [dwd_shop.orders]
  query: |
    SELECT segment, COUNT(*) AS order_count, SUM(amount) AS amount_total
    FROM dwd_shop.orders GROUP BY segment
  governance: {owner: analytics, level: L2, retention: 7d}
  deletionPolicy: Delete
```

两个例子改写自 `run-tables.sh` 的声明。

### 4.3 修改与升级

| 改动 | 结果 |
| --- | --- |
| 资源、并行度、`checkpointInterval`、流内检查的取值 | 作业以 savepoint 升级（事件 `JobUpdated`、`JobUpgraded`） |
| 查询（输出结构与流读的上游都不变） | 重新校验，再以 savepoint 升级。已有的行只在它们的上游行变化时才重算；要全部重算，用回刷（第 5.3 节） |
| 输出结构或主键 | 不应用：`TargetReady` 为 False，原因 `SchemaChanged`，旧作业继续运行，阶段 `Degraded` |
| 流读的上游换了 | 不应用：`StreamsChanged` |
| 目标表 | 不应用：`TargetChanged` |
| 加上或去掉 `quality` | 不应用：`QualityChanged` |
| `bucket`、`tableOptions`，以及治理的 `retention`、`categories` | 已建的目标表上的表选项不跟着改：补上或收紧保留期后 `Governed` 会变为 True，但表仍按建表时的选项保留历史。要按新的保留期，另声明一张表 |

上面"不应用"的改动，都要另声明一张表。

**升级失败时回退。** 新的 spec 10 分钟内没有运行起来，Operator 从升级时取的 savepoint 回到上一版：`SpecApplied` 为 False，原因 `RolledBack`，并发 Warning `UpgradeRolledBack`。同一改动不会再试，直到你改了声明：改回原来的声明即恢复；改成别的声明，要等回退的作业运行起来才以 savepoint 升级（这期间 `SpecApplied` 为 False，原因 `RollingBack`）。回退需要三个条件都成立：有上一个运行过的版本；升级取了新的 savepoint；能证明新作业没有写过目标表，即它从这次升级的 savepoint 恢复之后没有完成过 checkpoint，或者目标表最新的提交不晚于旧作业的 savepoint。升级落在旧作业第一个 checkpoint 之前（例如刚升级完又改）也能证明。

**回退被拒时**，`JobRunning` 为 False，原因 `UpgradeFailed`，并发同名 Warning。Operator 每一轮都再判一次，证据够了（例如目标表又读得出了）就回退。消息写明依据与能做的事：

- 新作业完成过 checkpoint：它运行过，可能已经写了目标表。改声明，作业从新作业自己最近的 checkpoint 继续；或者重建这张表（见下文"状态丢了时重建"）。
- 证明不了（例如升级开始时问不到旧作业的 JobManager）：消息说明怎样看新作业写没写。目标表在消息给出的 checkpoint 编号之后提交的快照是新作业的（Paimon 系统表 `<表>$snapshots` 的 `commit_identifier`）；不知道编号时，看升级开始之后提交的快照。新作业运行过就改声明，同上；否则重建这张表。不要手工改 `<名字>-table` 去强制回退：新作业写过时，旧作业会把同样的输入再处理一遍。
- 被拒之后改回或修正声明，Operator 先按当时的证据再判一次，能证明就立即回退，不再等 10 分钟。

**状态丢了时重建。** 两种情况：

- 上游的 consumer 不在了，例如挂起的时间超过上游的 `consumer.expiration-time`（默认 1 天），作业报 `RebuildRequired`；
- 有人在 Operator 之外删掉了作业，报 `JobLost`。

这两种情况都要重建这张表：另声明一张，或以 `Delete` 删掉后重建。

### 4.4 流内检查与隔离表

```yaml
  quality:
    notNull: [customer_id]
    allowed: {state: [created, paid, shipped]}
    ranges: {amount: {min: '0'}}
```

- `notNull`：这些列不能为 NULL。
- `allowed`：列值必须在列表里，按文本比较；NULL 通过。
- `ranges`：闭区间，可只写一端；只用于数值、`DATE`、`TIMESTAMP` 列；NULL 通过。

不通过的行不写进目标表，而是写进同库的 `<目标表>_quarantine`。隔离表的主键与目标表相同，另有 `colvenna_rule`（第一条没通过的检查，形如 `allowed(state)`）与 `colvenna_flagged_at`。同一主键后来的合格版本会离开隔离表。`status.quality` 按检查计数，最多数到 100,000 行，并以 Warning `RowsQuarantined` 与告警 `ColvennaRowsQuarantined` 提醒。隔离表随目标表一起治理、发布与删除。

### 4.5 状态

| `phase` | 含义 |
| --- | --- |
| `Validating` | 正在校验查询 |
| `Pending` | 在等上游表，或 colvenna-catalog 暂时不可用 |
| `Provisioning` | 作业在启动、升级或挂起 |
| `Running` | 作业在运行当前的声明 |
| `Degraded` | 已有作业在运行，但声明的改动没有应用，或者作业需要重建 |
| `Suspended` | 你挂起了它，或者回刷、压缩任务暂时挂起了它（`status.fencedBy`） |
| `Failed` | 还没有作业，声明被拒 |

条件：`SpecValid`、`DependenciesReady`（`UpstreamMissing`、`UpstreamNotAllowed`、`DependencyCycle`）、`CatalogReady`、`QueryValid`、`QualityValid`、`TargetReady`、`SpecApplied`、`JobRunning`、`Governed`、`Published`、`Ready`。`status.upstream[]` 列出每张流读上游的未读快照数、滞后与逐层延迟，`status.freshnessSeconds` 是查询这张表能看到的最新源端变更有多旧。

```bash
colvenna -n colvenna-data describe table orders-dwd     # 校验结论、作业、恢复来源、各上游的 consumer 进度
colvenna -n colvenna-data wait table orders-dwd --for ready --timeout 15m
```

### 4.6 删除

1. 停掉作业，删掉它在每张上游表上的 consumer；
2. `deletionPolicy: Delete` 时删掉目标表与隔离表。还有别的 `LakeTable` 读它时不删，发 Warning `TargetRetained`；
3. 撤回发布，吊销身份。

以 `Retain` 删掉后，目标表上记的属主仍是旧的那张表，新的 `LakeTable` 不能接着写它（`TargetConflict`）。

### 4.7 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| `Failed`，`UpstreamNotAllowed` | 上游不是本命名空间某个 `LakeSource` 或 `LakeTable` 的表。先用 `LakeSource` 接入它 |
| `Failed`，`DependencyCycle` | 几张表互相读 |
| `Failed`，`TargetConflict` | 目标表属于另一张先建的表，或属于以 `Retain` 删掉的旧声明；或者目标库是某个源的接入库 |
| `QueryValid` 为 False | 按第 4.2 节的原因改查询 |
| `QualityValid` 为 False，`QualityInvalid` | 检查的列不在输出里，或范围用在了不支持的类型上 |
| `JobRunning` 为 False，`RebuildRequired`；或 `SpecApplied` 为 False，`JobStateLost`（并发 Warning `JobLost`） | 状态没了，重建这张表。不要手工删 `<名字>-table` |
| `JobRunning` 为 False，`UpgradeFailed` | 升级没有运行起来，又证明不了新作业没写过目标表。按消息做：新作业运行过就改声明，否则重建这张表（第 4.3 节） |
| `SpecApplied` 为 False，`RollingBack` | 失败的升级刚回退，作业还没运行起来；你改的声明等它运行后再升级，最多等 10 分钟 |
| `TargetReady` 为 False，`TargetLost` | 目标表在 Operator 之外被删掉了，重建这张表 |
| `Governed` 为 False | 见第 7.2 节 |

### 4.8 限制

- 分层表不做 schema 演进，也不改流读的上游，这两种变化都要新声明一张表。
- 只用 `deduplicate` 合并引擎。
- 主键以外的列上的普通 join 会被拒绝（`UnorderedChangelog`）。
- 只能读本命名空间的 ODS 表与分层表，不能读视图与 catalog 函数。
- 不能证明新作业没写过目标表时，失败的升级不会自动回退：新作业运行过就改声明，作业从它的 checkpoint 继续；否则重建这张表（第 4.3 节）。

## 5. 任务：LakeTask

一个 `LakeTask` 是对一张表的一次批处理或维护。**它只运行一次**：建好就开始，结束于 `Succeeded` 或 `Failed`。spec 不能改（"a task runs once as created; create another task to change it"），再运行一次就建一个新名字的任务。平台不带调度器，定期运行由你的编排器建任务。

### 5.1 类型

| `type` | 做什么 | 参数（默认） |
| --- | --- | --- |
| `compact` | 压缩，`full: true` 时合并所有层级 | `compact.full`（`false`），`compact.partitions`（全部），`compact.manifests`（全量压缩时默认也压缩 manifest；表带 Iceberg 元数据时默认不压缩） |
| `expireSnapshots` | 过期旧快照与过期的 tag，并清掉超过 `consumer.expiration-time` 的闲置 consumer。读端 consumer 还需要的、打了 tag 的快照保留 | `retainMin`（10），`olderThan`（`1h`） |
| `removeOrphanFiles` | 删除没有快照、tag 或分支引用的文件 | `olderThan`（`1d`，至少 1 天） |
| `createTag` | 给最新快照打 tag；同名 tag 已存在算完成；表还没有快照时判为 `NoSnapshot`，不运行 | `name`（必填），`timeRetained`（`7d`） |
| `deleteTag` | 删 tag；tag 已经不在算完成 | `name`（必填） |
| `backfill` | 按分层表当前的查询重算整张目标表，见第 5.3 节 | — |
| `check` | 按表的 `LakeQuality` 做一次批量校验，见第 6.2 节 | `partitions`、`snapshot`、`asOf` |

共同的参数：`retries`（首次之后再试几次，默认 2，0–5）、`timeout`（单次尝试的时限，默认 `1h`）、`resources`（默认 0.5 CPU、`2048m`）。

`table` 必须是本命名空间的 ODS 表或分层表（或它的隔离表）。

**个人数据表上的 tag。** 表含个人数据或未分类时，tag 最多保留 24 小时，否则任务判为 `Failed`，原因 `RetentionTooLong`。`createTag` 默认的 `7d` 在这类表上也会被拒，要写 `timeRetained: 24h` 或更短。

**写入端自己压缩的 ODS 表**（Kafka 承载的 CDC、MongoDB 源）不能再由任务压缩：判为 `WriterCompacts`。直连 MySQL、PostgreSQL 与 Kafka 事件源的 ODS 表只写不压，要由你定期压缩与过期：Doris 看板读 ODS 时，验收按 10 分钟一次全量压缩；未合并的 level-0 文件超过 50 个持续 30 分钟时，告警 `ColvennaCompactionBacklog`。

示例（改写自 `run-tasks.sh`）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: compact-orders-20261020, namespace: colvenna-data}
spec:
  type: compact
  table: {database: ods_shop, table: orders}
  compact: {full: true}
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: eod-20261020, namespace: colvenna-data}
spec:
  type: createTag
  table: {database: ods_shop, table: orders}
  createTag: {name: eod-20261020, timeRetained: 2d}
---
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: expire-orders-20261020, namespace: colvenna-data}
spec:
  type: expireSnapshots
  table: {database: ods_shop, table: orders}
  expireSnapshots: {retainMin: 10, olderThan: 1h}
```

第二个任务要求 `ods_shop` 的源已分类、不含个人数据，验收中它的源声明了 `governance: {owner: shop-team, level: L2, retention: 7d}`。

用 CLI 建同样的任务：

```bash
colvenna -n colvenna-data maintain compact ods_shop.orders --name compact-orders-20261020 --full --wait
colvenna -n colvenna-data maintain tag ods_shop.orders --name eod-20261020 --tag eod-20261020 --time-retained 2d --wait
colvenna -n colvenna-data maintain expire ods_shop.orders --name expire-orders-20261020 --older-than 1h
colvenna -n colvenna-data maintain orphans ods_shop.orders --name orphans-orders-20261020
```

`deleteTag` 与 `compact.manifests` 没有对应的 CLI 参数，要用 YAML。

### 5.2 排队、重试与结果

- **排队。** 涉及同一张表的未结束任务一次只跑一个：已开始的优先，其余按创建时间。后来的停在 `Pending`，消息 `waiting for task <名字>, which touches <表>`。回刷还涉及它读的上游表与它的隔离表。
- **准入只在开始前做一次**，开始以后别处的变化不会让它停下。
- **每次尝试是一个 Kubernetes Job** `<任务>-run-<n>`，结束后保留 1 天。一次尝试失败、还有重试次数时发 Warning `AttemptFailed`，退避之后再试：第一次失败后等 1 分钟，之后每失败一次翻倍，最多 10 分钟；默认的 2 次重试即等 1 分钟、2 分钟。等待时任务仍是 `Running`，消息写明失败的原因与下一次的开始时间，`attempts`、`run` 到下一次开始才换。次数用完则 `Failed`，原因 `RunFailed`。下一次尝试还要等上一次的 Pod 停下才开始。
- **结果**在 `status.result`：前后的快照号与一句说明，例如 `expired 34; expired tags No expired tags.`。每次尝试的结果在 `status.history`。

```bash
colvenna -n colvenna-data describe task compact-orders-20261020
kubectl -n colvenna-data logs job/compact-orders-20261020-run-1 -c task     # 一次尝试的日志
```

**阶段**：`Pending`、`Fencing`（等分层表挂起）、`Running`、`Converging`（回刷已提交，在等下游）、`Succeeded`、`Failed`，后两个是终态。条件 `Finished` 在结束时为 True（`Succeeded`）或 False，False 时的原因就是失败的原因（第 5.5 节）；`CatalogReady` 为 False 时任务等 colvenna-catalog 恢复。

### 5.3 回刷

`backfill` 按分层表**当前的**查询重算整张目标表：

1. **围栏**：在 `LakeTable` 上加注解 `colvenna-lake.io/fenced-by`，它的作业以 savepoint 挂起。20 分钟内没挂起，判为 `FenceTimeout`。
2. **运行**：以 Flink 批模式把查询结果 upsert 进目标表（有流内检查时不通过的行进隔离表），再删掉查询不再产出的主键。没有变化的行不产生 changelog。
3. **释放**：作业从 savepoint 恢复，从它的 consumer 位置继续读上游。
4. **收敛**：等到下游每一张流读的表都读过回刷的那次提交，平台 Doris 里读这些表的物化视图与内表也都刷新过，任务才 `Succeeded`。30 分钟内没有收敛，判为 `NeedsReconciliation`，`status.waitingFor` 列出落后的下游。

只有无状态、或只有 lookup join 的分层表能回刷。聚合、普通 join、窗口与区间 join、TopN 与去重的表会被拒（`StatefulQuery`）：回刷不改作业状态，恢复后作业会按状态里的旧值继续算。这种表要重算，就另声明一张。回刷没有范围参数，分层表的目标不分区，所以总是整表重算。

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: orders-backfill-20261020, namespace: colvenna-data}
spec:
  type: backfill
  table: {database: dwd_shop, table: orders}
  retries: 2
```

回刷期间分层表挂起，它的下游看不到新变化，直到回刷结束、作业追上。重试用完时目标表可能只改了一半：已 upsert，还没删多余的行。再建一个回刷即可修正。

### 5.4 由编排器调度

编排器的做法是：建 `LakeTask`，然后轮询 `status.phase`，直到 `Succeeded` 或 `Failed`。它用自己的 Kubernetes 身份，只需要 `laketasks` 的 `create` 与 `get`：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: airflow, namespace: colvenna-data}
rules:
  - apiGroups: [colvenna-lake.io]
    resources: [laketasks]
    verbs: [create, get]
```

- 任务名里带上日期与批次，例如 `eod-20261020`。同一份 YAML 再提交一次什么也不变，所以编排器重跑一个步骤时会找到同一个任务，不会重复执行。
- 也可以在编排器里调用 `colvenna` 的任务命令加 `--wait`，按退出码判断（第 11 节）。
- 平台不内置生产用的 Airflow，也不维护 DAG 模板。验收用 Airflow 2.10 的一个 DAG 跑通了建任务、等待、重跑不重复执行，以及失败时带出 Operator 给的原因。DolphinScheduler 没有验证。
- 没有认证的请求建任务得到 401。

### 5.5 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| 改任务被 API server 拒绝："a task runs once as created" | spec 不能改，建一个新名字的任务 |
| `Failed`，`InvalidSpec` | `createTag`、`deleteTag` 没写 `name`，或 `check.asOf` 晚于现在 1 分钟以上 |
| `Failed`，`NotManaged` 或 `TableMissing` | 表不属于本命名空间的声明，或不存在 |
| `Failed`，`NotALakeTable` | 回刷只能用于分层表的目标表 |
| `Failed`，`StatefulQuery` | 聚合或普通 join 的表不能回刷，另声明一张 |
| `Failed`，`TableNotApplied` | 分层表当前的声明没有应用（`Degraded` 或回退过），先修好它 |
| `Failed`，`RetentionTooLong` | 个人数据或未分类表上的 tag 超过 24 小时 |
| `createTag` 任务 `Failed`，`NoSnapshot` | 表还没有快照，例如分层表刚开始运行、还没提交过。等表有了第一个快照再建任务；删掉失败的任务，`ColvennaTaskFailed` 随之撤掉 |
| `Failed`，`WriterCompacts` | 这张表的写入端自己压缩，不能再由任务压缩 |
| `Failed`，`FenceTimeout` | 分层表 20 分钟内没挂起，例如它在校验或取 savepoint 失败。修好分层表，再建任务 |
| `Failed`，`RunFailed` | 每次尝试都失败。看 Job 日志；必要时加大 `timeout` 或 `resources`，再建任务 |
| `Failed`，`TableGone` | 回刷期间它的分层表被删掉了 |
| `Failed`，`NeedsReconciliation` | 下游 30 分钟内没读到回刷，按 `status.waitingFor` 恢复或修好它们 |
| 长时间 `Pending`，"waiting for task …" | 同一张表上有别的任务没结束 |

### 5.6 限制

- 快照过期只保护有 consumer 的读端；按快照号做的批读要靠 `olderThan` 或 tag 保护。
- 孤儿文件清理只删一天以前的文件。验收中表都不到一天，它删了 0 个文件，真正的删除没有在 EKS 上演练。

## 6. 质量：LakeQuality 与 LakeWaiver

流内检查（第 4.4 节）逐行拦下坏行。批量质量校验则在一个快照上整体衡量一张表，并以结论控制它能否发布：

- `LakeQuality`：一张表的规则，以及门禁；
- `LakeTask` 的 `check`：校验一次，结果写进结果库；
- `LakeWaiver`：接受某次校验里未通过的规则，让门禁照样打开。

### 6.1 规则

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeQuality
metadata: {name: clicks, namespace: colvenna-data}
spec:
  table: {database: ods_web, table: clicks}
  rules:
    - {name: user-present, notNull: {column: user_id, minRatio: "0.99"}}
    - {name: event-unique, unique: {columns: [event_id]}}
    - {name: volume, rowCount: {min: 50}}
    - {name: fresh, freshness: {column: event_time_ms, maxAge: 2h}}
  gate: {publish: true, maxAge: 1d}
```

| 规则 | 字段 | 通过的条件 |
| --- | --- | --- |
| `notNull` | `column`，`minRatio`（默认 `"1"`） | 非空行的占比不低于 `minRatio` |
| `unique` | `columns`（1–8 列） | 没有重复的键；任一列为 NULL 的行不参与 |
| `rowCount` | `min`、`max`，至少给一个 | 行数在区间内 |
| `freshness` | `column`，`maxAge` | 最新一行的时间距 `asOf` 不超过 `maxAge`。列是 `TIMESTAMP`（按 UTC）、`TIMESTAMP_LTZ` 或毫秒的 `BIGINT`；按整表计算 |

- 每条规则恰好一种指标，名字在表内唯一；最多 20 条。
- 一张表只认一份 `LakeQuality`：后建的判为 `Failed`，原因 `Conflict`。
- `gate.publish: true` 时门禁关着就不发布；不写或为 `false` 时照样记录、照样判门禁，只是不拦发布。
- `gate.maxAge`：最新一次校验的 `asOf` 超过这么久，门禁关闭（`Expired`）。

规则与表对不上时（列不存在、类型不对），`RulesValid` 为 False，原因 `InvalidRules`，阶段 `Degraded`，门禁关闭。表结构变化后每一轮重新判断。

### 6.2 校验一次

```bash
colvenna -n colvenna-data check ods_web.clicks --name check-clicks-20261019 --partition dt=2026-10-19 --wait
```

等价的 YAML：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeTask
metadata: {name: check-clicks-20261019, namespace: colvenna-data}
spec:
  type: check
  table: {database: ods_web, table: clicks}
  check:
    partitions: ["dt=2026-10-19"]
```

- 不给 `partitions` 就校验整表。分区只能写分区键，例如 `dt=2026-10-19` 或 `dt=2026-10-19,hr=08`。
- `snapshot` 默认取开始时最新的快照；`asOf` 默认取开始的时刻；YAML 里写 UTC，以 `Z` 结尾，例如 `2026-10-19T08:00:00Z`，`colvenna check --as-of` 接受任何时区并换成 UTC。给定二者可以原样重做一次校验。
- 任务 `Succeeded` 只表示跑完、结果已写进结果库，**与规则是否通过无关**。结论在 `status.check.verdict`（`Passed` 或 `Failed`）。`colvenna check --wait` 的退出码：全部通过 0，有规则未通过 3，任务失败 1，超时 2。
- 编排器按这个退出码决定下游步骤是否继续。门禁只管平台自己的发布。
- 运行中或失败的校验没有结果，不算通过。

### 6.3 门禁

门禁只看**当前规则版本**下已完成的校验：规则一改，旧结果就不算，门禁回到 `NoCheck`；规则改回原样，旧结果重新算数。

| `status.gate.cause` | 开或关 | 含义 |
| --- | --- | --- |
| `NotReady` | 关 | 规则与表对不上，表不存在，或结果库读不到 |
| `NoCheck` | 关 | 当前规则下还没有完成的校验 |
| `Failed` | 关 | 仍有效的校验里有未被豁免的失败规则 |
| `Expired` | 关 | 超过 `gate.maxAge` |
| `Passed` | 开 | 通过；之后的数据与没校验的分区写在消息里 |
| `Waived` | 开 | 失败的规则都被豁免 |

**仍有效的校验。** 一次较新的校验，如果范围覆盖了较早的一次（整表覆盖一切，`dt=a` 覆盖 `dt=a,hr=1`），就取代它。门禁看所有没有被取代的校验。

**门禁关着时，被拦下的有：**

- `LakeSource`、`LakeTable` 把这张表发布给 `readers`：不授予，已授予的撤回，读端得到 403；
- 读这张表的 `LakeDashboard`：判为 `Failed`，用户被删除（登录被拒），物化视图暂停；门禁打开后自动恢复；
- 这张表的 `LakeIcebergPublication`：从 Iceberg 目录撤下（`QualityGate`）。

门禁不拦表的写入作业，也不拦你自己的读端。LakeQuality 每 15 秒判一次，发布方每 30 秒一轮，所以门禁的变化最长约 45 秒后生效。

只校验了部分分区时，门禁打开发布的是整张表，没校验的分区只在消息里写明。要挡住未校验的分区，就校验整表，或由编排器按分区决定。

### 6.4 找出未通过的行

结果库只有 Operator 与平台管理员能读，用户从状态里看结论：

```bash
colvenna -n colvenna-data describe quality clicks       # 门禁、仍有效的校验、通过与失败的规则、最近的决定
```

有规则失败时，Operator 给被校验的快照打 tag `colvenna-check-<任务 uid 前 8 位>`，名字写在 `status.checks[].tag`。tag 保留 7 天与表保留期中较短的一个，个人数据表最多 24 小时。平台管理员可以从结果表的 `locate` 列取得每条规则的定位查询，把其中的 `'scan.snapshot-id'='N'` 换成 `'scan.tag-name'='<tag>'`，在快照过期之后仍能读到当时未通过的行。

### 6.5 豁免

批准豁免要有 ClusterRole `colvenna-operator-waiver-approver`，由平台管理员绑定给数据负责人。能建校验任务的人不一定能豁免。

```bash
colvenna -n colvenna-data waive check-clicks-20261019 --name waive-clicks-20261019 \
  --rule user-present --rule event-unique --reason "the owner accepts the partition's known gaps"
```

- `colvenna waive` 把你自己的 Kubernetes 用户名写进注解 `colvenna-lake.io/approved-by`。准入策略要求它等于创建者，之后不能改，所以批准人不能冒名。
- 一份豁免只对一次校验。校验写出结果之后才能建，预先批准判为 `Rejected`。
- 只能列这次校验真正失败的规则，否则 `Rejected`。
- 失败的规则全部被接受（一份或几份豁免）之后，门禁才以 `Waived` 打开。
- 规则改了或有更新的校验取代了它，豁免变为 `Superseded`。
- spec 不能改；删除豁免就是撤回，门禁重新判断。
- 原因写进门禁的决定记录，不要写个人数据。

用 YAML 建时，注解要写成你自己的用户名（`kubectl auth whoami`）：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeWaiver
metadata:
  name: waive-clicks-20261019
  namespace: colvenna-data
  annotations: {colvenna-lake.io/approved-by: "<你的 Kubernetes 用户名>"}
spec:
  check: check-clicks-20261019
  rules: [user-present, event-unique]
  reason: "the owner accepts the partition's known gaps"
```

### 6.6 状态

`LakeQuality`：

| `phase` | 含义 |
| --- | --- |
| `Pending` | 表还不存在，门禁 `NotReady` |
| `Ready` | 规则与表对得上，结果库可读 |
| `Degraded` | 规则与表对不上，或结果库读不到；门禁关闭 |
| `Failed` | 同一张表已有更早的一份（`Conflict`），或表不属于本命名空间（`NotManaged`）；它不是门禁 |

条件：`RulesValid`（`InvalidRules`、`TableMissing`、`Conflict`、`NotManaged`）、`StoreReady`（`StoreConflict`：结果库属于别人或是旧布局；`StoreUnavailable`）、`GateOpen`（状态即门禁开或关，原因即 `status.gate.cause`）。门禁变化时发 `GateOpened` 或 `GateClosed` 事件。

`LakeWaiver` 没有条件，只有阶段：`Pending`（这次校验还没有结果）、`Accepted`、`Rejected`、`Superseded`，消息写明原因。

### 6.7 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| apply 被拒："a rule has exactly one of notNull, unique, rowCount and freshness" | 一条规则写了零个或多个指标 |
| apply 被拒："a rowCount rule gives min, max or both" | `rowCount` 是空的 |
| `Failed`，`Conflict` | 这张表已有一份 `LakeQuality` |
| `Failed`，`NotManaged` | 表不是本命名空间的 ODS 表或分层表 |
| 校验任务 `Failed`，`NoRules` | 建任务时表还没有 `LakeQuality`，这是终态。先建 `LakeQuality`，再建校验任务；`LakeQuality` 刚建或刚改、还没判定规则时，任务会等它 |
| 校验任务 `Failed`，`InvalidPartitions` | 表不分区却给了分区，或给的不是分区键 |
| 校验任务 `Failed`，`NoSnapshot` | 表还没有快照。等表有了第一个快照再建任务；删掉失败的任务，`ColvennaTaskFailed` 随之撤掉 |
| 校验任务 `Failed`，`InvalidRules` | 规则与表对不上，看 `LakeQuality` 的 `RulesValid` |
| 校验任务 `Failed`，`SnapshotMissing` | 指定的快照已经过期 |
| 校验任务 `Failed`，`RulesChanged` | 校验开始后规则变了，重建任务 |
| 门禁一直 `NoCheck` | 刚改过规则，在新规则下再校验一次 |
| 删掉失败的校验任务，门禁仍然关着 | 门禁依据的是结果库，不是任务。新的校验通过或豁免才会打开 |
| 建豁免被拒："annotation colvenna-lake.io/approved-by must be the user creating the waiver" | 注解写了别人。用 `colvenna waive` |
| 建豁免 `Forbidden` | 没有 `colvenna-operator-waiver-approver` 角色 |
| 豁免 `Accepted`，门禁仍关 | 还有失败的规则没被接受 |
| 豁免没有任何状态 | `spec.check` 不是这张表上存在的校验任务。删掉重建 |

### 6.8 限制

- 流内检查只有非空、枚举、范围；批量校验只有非空率、唯一性、行数与新鲜度，没有自定义 SQL 规则与跨表规则（如外键）。
- `freshness` 是整表的性质，校验历史分区时也按整表计算。
- 豁免的批准人靠准入策略保证；集群里没有这条策略时注解可以伪造，Operator 不检查策略是否存在。

## 7. 治理与合规删除

### 7.1 治理字段

`governance` 写在 `LakeSource` 与 `LakeTable` 里：

| 字段 | 说明 |
| --- | --- |
| `owner` | 负责的团队或人 |
| `level` | `L1` 公开候选，`L2` 内部，`L3` 受限，`L4` 高度敏感 |
| `categories` | `personal`（个人数据，至少 L3）、`sensitive`（敏感个人数据，必须 L4） |
| `retention` | 历史保留多久：旧快照、changelog 与闲置读端的位置。个人数据最多 `24h` |
| `subjectKeys` | 只用于 `LakeSource`：每张含个人数据的源表里，标识数据主体的列，例如 `{orders: [customer_id], customers: [id]}` |
| `subjectKey` | 只用于 `LakeTable`：目标表里标识数据主体的列 |
| `columns` | 列标签，`LakeSource` 的键写 `<ODS 表>.<列>`，`LakeTable` 的键写列名；每个标签有 `level`、`categories` 与 `mask`（`redact` 或 `mask`） |

没有 `governance`，或缺 `owner`、`level` 的表是**未分类**的：可以接入，但按临时 L4 的个人数据处理，不发布。

一个命名空间里的所有主体键都标识同一类主体，例如都是客户。

### 7.2 能否发布

每张表每一轮都重新判断条件 `Governed`，原因按判断顺序：

| 原因 | 什么时候 |
| --- | --- |
| `Unclassified` | 没有 `owner` 与 `level` |
| `RetentionMissing` | 没有 `retention` |
| `LevelTooLow` | 个人数据低于 L3，或敏感数据不是 L4 |
| `SubjectKeyMissing` | 个人数据没有主体键，或主体键不是表的列 |
| `RetentionTooLong` | 个人数据的 `retention` 超过 24 小时；或个人数据声明了 `snapshot.num-retained.min`、`changelog.num-retained.*`；或任何表声明的 `snapshot.time-retained`、`changelog.time-retained`、`consumer.expiration-time` 长于 `retention` |
| `LabelInvalid` | 列标签指向不存在的列，级别高于表，类别不在表的类别里，或个人数据列的级别不够 |
| `UpstreamUnclassified` | 分层表读了未分类的表 |
| `BelowUpstream` | 分层表的级别低于它读的表；或读了个人数据，自己却没有声明个人类别 |
| `PersonalEvents` | 分了类的 Kafka 事件源声明了个人数据：这类表没有有界保留，也不在删除范围内，所以不发布。未分类的 Kafka 事件源得到的是 `Unclassified` |

`Governed` 为 True 的表才发布给 `readers`。条件 `Published` 写明发给了谁（`READ on [...] for [...]`）。不受治理时立刻撤回授权，读端得到 403。读端经声明之外的库级或 catalog 级授权仍能读时，平台不撤销那些授权，只在 `Published` 的消息里写出来。

分层表没有降级（去标识化）的路径：读个人数据的表只能也声明为个人数据，级别不低于上游。

### 7.3 有界保留

Operator 按治理声明给表写保留选项：

- `snapshot.time-retained` 与 `consumer.expiration-time` 取 `retention`；你在 `tableOptions` 里声明了更短的值时取你的值；
- Kafka 事件源的表不写这些选项：它也要声明 `retention` 才受治理，但表的历史要你自己用 `tableOptions` 或快照过期任务控制；
- 个人数据与未分类的表：历史最多 24 小时，`snapshot.num-retained.min=1`；写入端自己压缩的表另加 `compaction.optimization-interval=1 h`。这个选项不能自己声明；
- 只写不压的源（直连 MySQL、PostgreSQL）上的个人数据或未分类的表：Operator 每小时为每张 `Ready` 的表建一个 `expireSnapshots` 任务，名字形如 `<源>-retain-<表>-<序号>`，带标签 `colvenna-lake.io/retention=<源>`。你会在 `kubectl get ltk` 里看到它们。

这些选项在建表时写进表。建表之后才补上或收紧 `retention`，已建的分层表不会改写它们（第 4.3 节）；直连源上个人数据与未分类的表，仍由上面的每小时任务按当前声明的保留期过期。

### 7.4 列标签、脱敏与行过滤

列级与行级保护**只在平台 Doris 的看板这一条读路径上执行**：

| 读路径 | 列标签与脱敏 | 行过滤 |
| --- | --- | --- |
| `LakeDashboard` 的查询对象（外表视图、物化视图、内表），包括经 BI 的查询 | 执行 | 执行 |
| `readers` 经 colvenna-catalog 读湖表；Flink、Spark 作业 | 不执行，按表授权 | 不执行 |
| Iceberg 发布与 Trino | 不执行；只发布 L2 及以下、不含个人数据的表 | 不执行 |
| Doris 直接查 Paimon 外表、系统表、文件类表函数、直接读 S3 | 关闭 | 关闭 |

列标签写在源或分层表的 `governance.columns` 里，例如（`run-enforcement.sh`）：

```yaml
  governance:
    owner: shop-team
    level: L3
    categories: [personal]
    subjectKeys: {orders: [customer_id]}
    retention: 24h
    columns:
      orders.id: {level: L1}
      orders.amount: {level: L2}
      orders.state: {level: L2}
      orders.customer_id: {categories: [personal]}
      orders.note: {level: L3, categories: [personal], mask: mask}
```

看板的 `access` 是它的用户可以看清的范围（第 8.2 节）。高于这个范围的列被脱敏：

- `redact`（默认）：显示为 NULL；
- `mask`：字母与数字被替换，例如 `xxxx nnn-nnnn`。

没有标签的列按表的级别与类别判断，所以源库新增的列在标注之前默认 `redact`。

行过滤写在看板用户的 `rows` 上。只要有一个用户声明了 `rows`，没声明的用户就一行也看不到。行过滤不当作保密边界：Doris 没有 security barrier，验收中没有探到泄漏，但这是 Doris 4.1.4 的执行计划行为，不是承诺。

### 7.5 合规删除：LakeErasure

一个 `LakeErasure` 在本命名空间所有受管副本里删除一个数据主体，分两级完成，各有证据与期限：

- **逻辑删除**：每条受管的读路径都不再返回这个主体。默认期限 24 小时，也是上限。
- **物理删除**：任何受管的存储或备份都带不回它。默认期限 9 天，也是上限。

**平台不替你删源库。** 源库里的行要由源库的负责人删除，平台只核对；不删的话，CDC 会把行再带回湖里。

**怎样提出。**

```bash
printf '42\n' > subject.txt          # 一行一个主体；主体键有多列时用 Tab 分隔
colvenna -n colvenna-data forget erase-20261020-001 --subject-file subject.txt
```

`colvenna forget` 建一个由删除请求拥有的 Secret，键值只在 Secret 里：不进声明、状态、事件与日志。也可以自己建 Secret（键默认 `values`）再 apply：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeErasure
metadata: {name: erase-20261020-001, namespace: colvenna-data}
spec:
  subjectSecret: {name: erase-20261020-001}
  deadlines: {logical: 24h, physical: 9d}
```

spec 不能改，一个名字只受理一次。

**受理之后。** Operator 列出这个主体可能所在的每一份副本，写进 `status.copies`，然后每 30 秒推进一次：

| 副本 `kind` | 层 | 完成的证据 |
| --- | --- | --- |
| `SourceDatabase` | 逻辑 | 源库按主体键查不到这个主体的行 |
| `Rows` | 逻辑 | 表的最新快照里 0 行，源库也已删除；记下这个干净的快照 |
| `History` | 逻辑 | 最早保留的快照不早于干净的那个；保留的变更里没有它；没有更早的 tag、分支；读端的 consumer 都已越过 |
| `Files` | 物理 | 文件与 manifest 的统计里没有它，旧快照都已过期，压缩一天后的孤儿文件清理成功 |
| `FlinkState` | 物理 | 作业能恢复的 checkpoint 与 savepoint 都不早于删除 |
| `Dashboard`、`DorisStorage`、`DorisAuditLog` | 逻辑、物理 | 看板的物化视图与内表在删除后整表刷新过；Doris 回收站与查询日志的保留期过去 |
| `Storage` | 物理 | 安装时核对过仓库桶没有对象版本与复制 |
| `Reader`、`Topic` | 外部 | 只登记：读端另存的副本、你的 Kafka 里的变更，由其负责人删除 |

推进中 Operator 自己建需要的任务：分层表在 lookup join 里丢了删除时建回刷，再建压缩、快照过期、删 tag 与孤儿文件清理。它们都带标签 `colvenna-lake.io/erasure=<名字>`。

**阶段**：`Accepted`、`LogicallyErased`、`PhysicallyErased`，或者 `Blocked`（有副本需要负责人复核）、`SlaBreach`（期限已过，对应的层没有完成）、`Failed`（不受理，例如 Secret 里没有值、期限超过上限）。条件 `LogicallyErased`、`PhysicallyErased` 为 False 时消息列出还没完成的副本；`WithinDeadlines` 为 False（`Breached`）时列出超期的记录，超期记录在完成之后也保留。

```bash
colvenna -n colvenna-data describe erasure erase-20261020-001      # 每份副本的层、状态与证据或原因
```

**什么会 `Blocked`。**

- 含个人数据的表没有主体键（未分类的表也算）；
- Kafka 事件源与 MongoDB 源的表：这两种源不在删除范围内；
- 读了个人数据、却没有声明含个人数据的分层表；
- 这些库里没有任何声明覆盖的表；
- 主体的值与主体键的列数或类型对不上；
- 源库数到过这个主体的行，这张表却一次也没数到：主体键可能对不上。

有 `Blocked` 副本的请求到不了逻辑删除，过了逻辑期限就变成 `SlaBreach`。副本清单在受理时就固定了，改声明不会解除 `Blocked`，要另提一个请求。

**期限。** 逻辑删除在验收里约 24 分钟完成（期限 60 分钟）。物理删除最早在压缩一天之后，因为孤儿文件清理只删一天以前的文件；装了 Doris 时，查询日志要到受理后 169 小时才过期。所以物理期限不要短于这些。9 天的物理期限还没有端到端跑完，验收只验证了逾期时如实报告残留（`SlaBreach`，并列出剩下的 `Files`）。超期时告警 `ColvennaErasureOverdue`。

### 7.6 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| 表不发布，`Governed` 为 False | 按第 7.2 节的原因补全声明 |
| 声明 `compaction.optimization-interval` 被拒 | 不在表选项白名单里，Operator 自己设 |
| `LakeErasure` 一直是 "waiting for Secret … to exist"，24 小时后 `SlaBreach` | Secret 没建或名字不对 |
| `LakeErasure` `Failed` | Secret 里没有值，或期限超过 24 小时与 9 天，或逻辑期限晚于物理。另提一个请求 |
| `colvenna forget`："secret … exists: an erasure is accepted once" | 名字用过了，换一个 |
| `Rows` 一直是 "… the source still has them" | 源库还没删 |
| `Storage` 为 `PendingEvidence` | 安装时没有记下存储证据，重跑 Operator 的安装脚本 |

### 7.7 限制

- 删除只覆盖 MySQL 与 PostgreSQL 源的 ODS 表与分层表。
- 平台只核对它控制的读入口与存储；外部读端经 `Reader` 副本告知。
- 从删除之前的状态恢复、确认主体不会重现的演练，不由删除请求本身完成，留待运维验收。
- 存储证据是安装时核对的；之后改了桶的版本或复制设置，要重新安装才会再核对。
- 没有哈希或假名化的脱敏方式。
- BI 的 Superset 元数据库保存着用户的查询历史，还没有纳入删除流程。

## 8. 看板：LakeDashboard

一个 `LakeDashboard` 在平台 Doris 上服务一组固定的看板查询，并为每个用户建 Doris 账号。Doris 经 colvenna-catalog 读湖里的 Paimon 文件，用的是平台自建的 Paimon 2.0.0 读取器。

Operator 为每个看板在 Doris 里建：

- 一个读湖的 catalog（只能读声明的表）与一个库 `dash_<命名空间>_<名字>_<uid>`，库名写在 `status.database`；
- 每条查询一个对象，用户查的就是它；
- 一个 workload group，限制并发；
- 每个用户一个 Doris 用户，只能查授给它的查询对象；
- 需要脱敏时，一个只有看板属主能读的脱敏视图库；用户声明了 `rows` 时，每个用户在每个对象上一条行策略。

### 8.1 三种服务方式

| `serve` | Doris 对象 | 新鲜度 | 适合 |
| --- | --- | --- | --- |
| `external` | 视图，查询时读湖 | 湖的最新快照 | 明细、即时 |
| `materialized` | 异步物化视图，每 `refresh` 整表刷新一次 | 上一次成功刷新开始时的湖 | 聚合 |
| `internal` | Unique Key 内表，Operator 每 `refresh` 以 `INSERT OVERWRITE` 重写；要声明 `key` | 上一次成功重写开始时的湖 | 少量热数据的点查 |

`refresh` 默认 `1m`，以分钟为单位。刷新失败时副本保持原样，错误写进 `status.queries[]`；副本超过 10 分钟没有成功刷新，告警 `ColvennaDashboardStale`。

三种方式都经外部 catalog 读湖。看板读 ODS 时，ODS 表要定期压缩（第 5.1 节）。

### 8.2 字段

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `tables` | 必填 | 查询读的湖表 `<库>.<表>`，1–32 张；看板的身份只被授予这些表 |
| `queries[]` | 必填 | `name`、`serve`、`sql`（一条 `SELECT` 或 `WITH`，表名写 `<库>.<表>`），以及 `refresh`、`key` |
| `users[]` | — | `name`、`passwordSecret`（键默认 `password`）、`queries`（能查哪些），以及 `rows`（行条件）、`sso.username`（BI 登录名，第 13 节） |
| `governance` | — | 副本（物化视图、内表）的负责人与级别。有 `materialized` 或 `internal` 查询时必填，级别不低于所读的表；读了个人数据时要声明个人类别 |
| `access` | `governance` 的级别与类别；都没有时 L2 | 用户可以看清的最高级别与类别，不能高于看板自己的 `governance`；见第 7.4 节 |
| `workload.maxConcurrency` | `10` | 这个看板同时运行的查询数，1–64；排队上限是它的 4 倍，排队超时 10 秒；也是每个用户的最大连接数 |

**查询的限制。** 一条语句，不带 `;`；不读 Doris 自己的库（`information_schema`、`mysql`、`__internal_schema`）；不调表函数（`s3`、`paimon_meta` 等）；不直接写看板的 catalog 名；不读系统表（名字里带 `$`）。

**行条件** 只能比较列与字面量，可以用 `IN`、`LIKE`、`IS NULL`、`BETWEEN`、`AND`、`OR`、`NOT`；不能用函数、子查询与注释，最长 512 个字符。

示例：三种服务方式（改写自 `run-dashboards.sh`）。

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: board, namespace: colvenna-data}
spec:
  tables: [ods_shop.customers, dwd_shop.orders, dws_shop.segments]
  governance: {owner: bi-team, level: L2, retention: 7d}
  queries:
    - {name: segment_totals, serve: materialized, refresh: 1m,
       sql: "SELECT segment, order_count, amount_total FROM dws_shop.segments"}
    - {name: order_lookup, serve: external,
       sql: "SELECT order_id, customer_id, segment, amount, state FROM dwd_shop.orders"}
    - {name: customers_hot, serve: internal, refresh: 1m, key: [id],
       sql: "SELECT id, name, region FROM ods_shop.customers"}
  users:
    - {name: analyst, passwordSecret: {name: board-analyst}, queries: [segment_totals, order_lookup]}
    - {name: ops, passwordSecret: {name: board-ops}, queries: [customers_hot]}
```

示例：脱敏与行过滤（`run-enforcement.sh`）。`ods_shop.orders` 带第 7.4 节的列标签，是 L3 个人数据：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: orders-board, namespace: colvenna-data}
spec:
  tables: [ods_shop.orders]
  governance: {owner: bi-team, level: L3, categories: [personal], retention: 24h}
  access: {level: L2}
  queries:
    - {name: live, serve: external,
       sql: "SELECT id, customer_id, amount, state, note, version_no FROM ods_shop.orders"}
    - {name: by_state, serve: materialized, refresh: 1m,
       sql: "SELECT state, COUNT(*) AS n, SUM(amount) AS total FROM ods_shop.orders GROUP BY state"}
  users:
    - {name: analyst, passwordSecret: {name: orders-board-analyst}, queries: [live, by_state], rows: "state = 'created'"}
    - {name: viewer, passwordSecret: {name: orders-board-viewer}, queries: [live]}
```

验收中，`status.exposure[0].masked` 是 `customer_id:redact,note:mask,version_no:redact`。`analyst` 只看到 `created` 的行，`note` 显示为 `xxxx nnn-nnnn`；`viewer` 没有行条件，所以一行也看不到。

### 8.3 连接 Doris

- 地址：集群内的 `colvenna-doris-fe-service.colvenna-doris.svc:9030`，MySQL 协议。
- 客户端所在的命名空间要带标签 `colvenna-lake.io/doris-client=true`，NetworkPolicy 只放行这些命名空间的 9030 端口。FE 的 HTTP 端口与 Arrow Flight 不开放。
- 用户名 `<命名空间>.<看板>.<用户>`，例如 `colvenna-data.board.analyst`；口令是 `passwordSecret` 里的值（去掉首尾空白）。
- 查 `<status.database>.<查询名>`。下面的命令在带上述标签的命名空间里的 Pod 中运行：

```bash
db=$(kubectl -n colvenna-data get ldb board -o jsonpath='{.status.database}')
MYSQL_PWD="$(cat ./analyst-password.txt)" mysql -h colvenna-doris-fe-service.colvenna-doris.svc -P 9030 \
  -u 'colvenna-data.board.analyst' -e "SELECT * FROM \`$db\`.segment_totals"
```

用户读不到湖表、catalog、审计日志、别的看板，也读不到 `information_schema` 与 `mysql`。`SHOW DATABASES` 与 `SHOW TABLES` 可以用；BI 工具浏览元数据时要走 `SHOW`。

**并发。** 每个用户最多 `maxConcurrency` 个连接，再多的连接被拒绝。整个看板同时运行 `maxConcurrency` 个查询，其余排队。排队只管执行阶段，FE 的规划在排队之前，不受这个限制。平台另有一个 `normal` 组，限 2 个并发，用户可以 `SET workload_group = 'normal'` 切过去，但连接数上限照样有效。

**BE、FE 丢失之后。** Operator 下一轮重建缺了的 catalog、对象、用户与行策略。验收中删掉 BE 的 Pod 后，每条路径在 143 秒内恢复；删掉 FE 的 Pod 后，在 126 秒内恢复（[M3 看板验收](../history/m3/dashboards.md)）。

### 8.4 状态

| `phase` | 含义 |
| --- | --- |
| `Pending` | 没有配置 Doris |
| `Ready` | 每条查询都在服务 |
| `Degraded` | 有查询或用户没做成，或 Doris、colvenna-catalog 出错 |
| `Failed` | 声明无效（`SpecInvalid`），或被治理拒绝（`GovernanceRefused`） |

条件：`SpecValid`、`Ready`（`QueriesFailed` 时列出没有服务的查询）、`DorisReady`、`CatalogReady`、`BiPublished`。被治理拒绝时，用户被删除、物化视图暂停，Doris 里的对象保留；Operator 每 30 秒重新判断，原因消除后自动恢复。colvenna-catalog 不可用时，Operator 撤销所有用户的授权：出错不放宽。

### 8.5 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| `Pending`，`NoDoris` | 平台没有装 Doris，或 Operator 没有配置它 |
| `Failed`，`SpecInvalid` | 消息写明哪条查询或哪个用户不合规则；改好后下一个 generation 重新判断 |
| `Failed`，`GovernanceRefused`："… no declaration of this namespace classifies …" | 读了未分类的表，先给源或分层表补上 `governance` |
| `Failed`，"materialized and internal queries copy the lake into Doris …" | 有副本却没有 `governance` |
| `Failed`，"… held back from publication by their quality gates …" | 所读的表门禁关着。门禁打开后自动恢复 |
| `Degraded`，"no Secret … for <用户>" | 口令 Secret 不存在，或缺 `password` 键 |
| `Degraded`，`QueryFailed` | Doris 拒绝了对象或刷新，事件消息写明原因 |
| `BiPublished` 为 False，`NoBi` | 有用户写了 `sso`，但 Operator 没有配置 BI。安装脚本在 BI 装好后重装 Operator 时会配置它（[安装部署手册](install.md)第 4 节的 `operator-again` 步骤） |
| `BiPublished` 为 False，`BiUnavailable` | 写不进 BI 的凭证 Secret `colvenna-bi/colvenna-bi-doris-users` |
| 用户的第 N 个连接被拒 | 超过了 `maxConcurrency` |
| 读 ODS 越来越慢，最后 BE 出错 | level-0 文件积压。定期全量压缩；读取器的 S3 连接一旦泄漏，压缩救不回来，要重启 BE |

### 8.6 限制

- 平台 Doris 只有一个 FE、一个 BE，没有高可用；客户端到 FE、FE 到 BE 都是集群内的明文 MySQL 协议。
- 物化视图按固定周期整表刷新（Paimon 基表不能 ON COMMIT 刷新）；内表适合少量热数据。
- 用户是 Operator 管理的 Doris 本地用户，没有 LDAP。
- Doris 仍向任何用户开放 `tasks()`、`jobs()`、`SHOW BACKENDS` 与自己视图的定义（只有元数据）。
- 看板的性能目标（负载下的响应时间、各路径的新鲜度）不在 UAT 里重测，见[性能测试](../uat/performance.md)。

## 9. Iceberg 发布：LakeIcebergPublication

一个 `LakeIcebergPublication` 把一张表以 Iceberg 元数据只读发布到 Polaris 的 catalog `colvenna_open`，供 Spark、Trino 等外部引擎读同一批文件。Iceberg 元数据由表自己的写入作业写在表目录旁边；Operator 只在 Polaris 里登记最新的版本，并给读端授权。

### 9.1 能否发布

每一轮都重新判断（条件 `Publishable`），全部满足才发布：

1. 表是本命名空间的 ODS 表或分层表（`NotManaged`、`TableMissing`）；
2. 它的声明 `Governed` 为 True（`NotGoverned`）；
3. 级别不高于 L2，且不含个人数据（`Restricted`）。Iceberg 读端拿到的是整张表，平台的用户策略对它们不起作用；
4. 质量门禁开着（`QualityGate`）；
5. 建表时声明了 `tableOptions: {metadata.iceberg.storage: table-location}`（`NoIcebergMetadata`）；
6. 没有打开 deletion vector（`DeletionVectors`）；
7. 表的每一列都列进了 `spec.columns`（`ColumnsUnreviewed`）。源端新增的列在复核并列进来之前，整张表都不发布。

不再满足时，表从目录撤下（阶段 `Withheld`），读端授权收回；数据文件不动。已签发的凭证在过期前（最长 15 分钟）仍能读。

**主键表与 append 表。**

- append 表：Iceberg 的快照与 Paimon 的快照一一对应。
- 主键表：Iceberg 只显示完全压缩过的文件，相当于 Paimon 的 `$ro`。第一次全量压缩之前不发布（`MetadataIncomplete`）。直连源的 ODS 主键表只写不压，要你定期运行全量压缩的 `LakeTask`。写入端自己压缩的表（分层表，以及 MongoDB 源与经 Kafka 承载的 CDC 的 ODS 表）由 Operator 固定 1 小时的全量压缩间隔。写入端只在有新写入时压缩，所以没有新写入时，最后一次改动要等到下一次写入之后才发布。

一次提交要等 Operator 的下一轮（30 秒），再加上读端自己的元数据缓存，才会被读端看到。验收中 append 表 16 秒发布，判定的上限是 90 秒（[M4 Iceberg 验收](../history/m4/iceberg.md)）。

### 9.2 声明

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeIcebergPublication
metadata: {name: clicks, namespace: colvenna-data}
spec:
  table: {database: ods_web, table: clicks}
  columns: [event_id, user_id, event_time_ms, dt, extra_json, _topic, _partition, _offset]
  readers: [web_readers]
```

- `table` 建好以后不能改；一张表只有一个发布，后建的判为 `Failed`（`Conflict`）。
- `readers` 是 `colvenna_open` 里的 catalog role，最多 20 个。

它发布的表来自这样一个源（`run-iceberg.sh`），注意 `metadata.iceberg.storage`：

```yaml
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: web-clicks, namespace: colvenna-data}
spec:
  type: kafka
  kafka:
    bootstrapServers: kafka-bootstrap.kafka.svc:9093
    topics: [web-clicks]
    credentialsSecret: {name: web-clicks-kafka}
    caConfigMap: web-clicks-kafka-ca
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: user_id, type: BIGINT}
    eventTime: {field: event_time}
  target:
    database: ods_web
    table: clicks
    tableOptions: {metadata.iceberg.storage: table-location, snapshot.time-retained: 1 d}
  governance: {owner: web-team, level: L2, retention: 7d}
  deletionPolicy: Delete
```

### 9.3 读端

**平台管理员先建读端**（用 Polaris 的 root 凭证，Secret `colvenna-polaris/polaris-root`）：

1. 在 `colvenna_open` 里建 catalog role，例如 `web_readers`；
2. 建一个 principal role，把 catalog role 赋给它；
3. 建 principal，得到 `clientId` 与 `clientSecret`，把 principal role 赋给它；
4. 在发布的 `readers` 里写上这个 catalog role。

验收脚本里有这几步的 Polaris 管理 API 调用（`tests/acceptance/iceberg/run-iceberg.sh`）。仓库里没有为读端准备的管理脚本。

**读端的连接。**

- 读端的命名空间要带标签 `colvenna-lake.io/iceberg-client=true`；Polaris 的证书由 colvenna-catalog 的 CA 签发。平台管理员从 Secret `colvenna-catalog/colvenna-catalog-ca-key` 取出 `ca.crt` 交给读端；这个 Secret 里还有 CA 的私钥 `ca.key`，不要整个复制。
- 端点 `https://polaris.colvenna-polaris.svc:8181/api/catalog`，warehouse `colvenna_open`，OAuth2 client credentials，scope `PRINCIPAL_ROLE:ALL`，请求头 `X-Iceberg-Access-Delegation: vended-credentials`。
- 读端不需要自己的 AWS 身份。Polaris 按表签发只读凭证，15 分钟有效，范围只到这张表的目录。

Spark 3.5.8 + Iceberg 1.10.2（验收的读端）：

```text
spark.sql.catalog.open                                  org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.open.type                             rest
spark.sql.catalog.open.uri                              https://polaris.colvenna-polaris.svc:8181/api/catalog
spark.sql.catalog.open.warehouse                        colvenna_open
spark.sql.catalog.open.credential                       <client-id>:<client-secret>
spark.sql.catalog.open.scope                            PRINCIPAL_ROLE:ALL
spark.sql.catalog.open.header.X-Iceberg-Access-Delegation vended-credentials
spark.sql.catalog.open.io-impl                          org.apache.iceberg.aws.s3.S3FileIO
spark.sql.catalog.open.client.region                    <环境的区域，例如 us-west-2>
spark.sql.catalog.open.cache-enabled                    false
```

然后 `SELECT … FROM open.ods_web.clicks`。

已有的 Trino（验收用 Trino 484）以 Iceberg 连接器读，catalog 属性文件含凭证，要放在 Secret 里：

```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=https://polaris.colvenna-polaris.svc:8181/api/catalog
iceberg.rest-catalog.warehouse=colvenna_open
iceberg.rest-catalog.security=OAUTH2
iceberg.rest-catalog.oauth2.credential=<client-id>:<client-secret>
iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL
iceberg.rest-catalog.oauth2.server-uri=https://polaris.colvenna-polaris.svc:8181/api/catalog/v1/oauth/tokens
iceberg.rest-catalog.vended-credentials-enabled=true
iceberg.rest-catalog.metrics-reporting-enabled=false
fs.s3.enabled=true
s3.region=<环境的区域，例如 us-west-2>
```

Trino 的 JVM 要信任 colvenna-catalog 的 CA。查询写 `SELECT … FROM iceberg.ods_web.clicks`；元数据表如 `"clicks$snapshots"`；time travel 写 `FOR VERSION AS OF CAST(<快照号> AS BIGINT)`（Trino 484 不接受整数字面量）。

**读端做不到的。** 写入、改 schema、建分支或 tag，都被目录拒绝。没有授权的表看不到，元数据表也看不到。拿到的凭证写不了、读不了别的表。

### 9.4 状态

| `phase` | 含义 |
| --- | --- |
| `Pending` | 没有配置 Polaris，或还没有发布过任何快照 |
| `Published` | 目录指向最新的快照，`status.published.snapshot` 是它 |
| `Withheld` | 现在不能发布（原因见第 9.1 节，包括表不存在 `TableMissing`），已从目录撤下 |
| `Failed` | 同一张表已有更早的发布 |

条件：`Publishable`、`Published`、`ReadersReady`（`ReaderMissing`：catalog role 不存在）。`status.lag` 是还没发布的快照数，以及其中最新那个已等了多久。

### 9.5 常见错误

| 现象 | 原因与处理 |
| --- | --- |
| `Withheld`，`Restricted` | 表高于 L2 或含个人数据。改为发布一张只含可公开列的 L2 分层表 |
| `Withheld`，`NoIcebergMetadata` | 建表时没有声明 `metadata.iceberg.storage: table-location`。给已有的表补这个选项是否生效没有验证；稳妥的做法是声明时就带上 |
| `Withheld`，`ColumnsUnreviewed` | 表有没列进 `columns` 的列，消息列出它们。复核后加进去 |
| `MetadataIncomplete` | 主键表还没有完全压缩。直连源的 ODS 表与分层表运行一次 `compact: {full: true}` 的任务（分层表的作业会先被挂起）；MongoDB 源与经 Kafka 承载的 CDC 的 ODS 表等写入端下一次全量压缩，对它们建压缩任务会判为 `WriterCompacts` |
| `ReadersReady` 为 False | 请平台管理员在 `colvenna_open` 里建这个 catalog role |
| `Published` 为 False，`Unavailable` | Polaris 不可用。写入不受影响，恢复后自动追上 |
| 读端 401 或 403 | token 或 scope 不对；principal role 与 catalog role 没有关联；catalog role 不在 `readers` 里 |

### 9.6 限制

- 只验收了 Spark 3.5.8 + Iceberg 1.10.2 与 Trino 484 两种读端。其他版本、Starburst、Trino 的容错执行都没有验证。
- 授权以 Polaris 的 principal 为单位：Trino 的每个用户都拿到同样的权限，Trino 自己的认证与访问控制由你负责（Trino 默认放行一切）。
- 新列在 Operator 撤下表之前（30 秒内）就已写进表目录。持有未过期凭证的读端可以绕过目录，直接从 S3 读到它。要真正挡住的数据（个人数据、高于 L2）根本不发布。
- 分支与 deletion vector 表不能发布；分层表 1 小时的全量压缩保底没有实测。

## 10. 血缘

配置了 Marquez 时，Operator 依据声明与 planner 的校验结果，以 OpenLineage 事件上报血缘：

- 作业：源的 Flink 作业、分层表的作业、回刷的每次尝试、看板的每条查询；
- 数据集：湖表（`paimon://colvenna-catalog.colvenna-catalog.svc:8181`，名字 `colvenna.<库>.<表>`，带列）与看板的 Doris 对象（`doris://…`）。
- 命名空间：作业在 `colvenna-lake.<命名空间>` 下，例如 `colvenna-lake.colvenna-data`。

不上报的：批量校验、维护任务、即席查询、删除、列级血缘、Iceberg 发布与外部读端。血缘反映的是声明，不是运行时观测。

Marquez 自身不认证，只有 ClusterIP，NetworkPolicy 只放行带 `colvenna-lake.io/lineage-client=true` 标签的命名空间，安装时只有 Operator 的命名空间带它。带这个标签的命名空间里，任何 Pod 都能不带凭证写入血缘。查看时用 `kubectl port-forward`：

```bash
kubectl -n colvenna-lineage port-forward svc/marquez-web 3000:3000     # 浏览器打开 http://localhost:3000
```

上报失败或被拒时，告警 `ColvennaLineageStale`、`ColvennaLineageFailing`、`ColvennaLineageRejected`。只验收了 Marquez；PostgreSQL、MongoDB、Kafka 源与物化视图、内表的血缘只有单元测试。

## 11. colvenna CLI

`colvenna` 是一个薄客户端：它只调 Kubernetes API，以你的 kubeconfig 与身份提交声明，不做任何准入判断。控制台的受控操作与 CLI 生成同样的对象（共用 `colvenna-cli/ops` 包）。

**连接。** 全局参数 `--kubeconfig`、`--context`、`-n/--namespace`；不给 `-n` 时取 context 的命名空间。`colvenna version` 打印版本与 API 版本。

**种类。** 命令里的种类可以写别名：`source`（`ls`）、`table`（`lt`）、`task`（`ltk`）、`quality`（`lq`）、`waiver`（`lw`）、`erasure`（`ler`）、`dashboard`（`ldb`）、`iceberg`（`lip`），以及各自的复数与完整的种类名。

| 命令 | 做什么 |
| --- | --- |
| `colvenna apply -f <文件>...` | 以 server-side apply 提交声明；`-f -` 读标准输入；一个文件里可以有多份。按顺序提交，在第一份被拒的地方停下，前面的已生效 |
| `colvenna get <种类> [名字] [-o json\|yaml]` | 列出或取一个；表格的列随种类 |
| `colvenna describe <种类> <名字>` | 状态、条件、作业、表与最近 20 条事件 |
| `colvenna suspend <种类> <名字>`、`colvenna resume <种类> <名字>` | 设 `spec.suspend`；用于 `LakeSource` 与 `LakeTable` |
| `colvenna delete <种类> <名字> [--wait=false] [--timeout 10m]` | 删除。默认等 Operator 清理完；表怎么处理由声明的 `deletionPolicy` 决定 |
| `colvenna wait <种类> <名字> --for ready\|phase=<阶段>\|table/<表>=<状态> [--timeout 10m]` | 等到条件成立。等的表变成 `Failed`、或声明 `Failed` 时立即失败；`Rejected` 不算最终，继续等 |
| `colvenna backfill <库.表> --name <任务>` | 建回刷任务（第 5.3 节） |
| `colvenna maintain compact <库.表> --name <任务> [--full] [--partition k=v[,k=v]]...` | 压缩 |
| `colvenna maintain expire <库.表> --name <任务> [--retain-min 10] [--older-than 1h]` | 过期快照 |
| `colvenna maintain orphans <库.表> --name <任务> [--older-than 1d]` | 清理孤儿文件 |
| `colvenna maintain tag <库.表> --name <任务> --tag <名字> [--time-retained 7d]` | 打 tag |
| `colvenna check <库.表> --name <任务> [--partition …] [--snapshot N] [--as-of <RFC 3339>]` | 批量校验（第 6.2 节） |
| `colvenna waive <校验任务> --name <豁免> --rule <规则>... --reason <原因>` | 豁免（第 6.5 节） |
| `colvenna forget <名字> --subject-file <文件> [--logical 24h] [--physical 9d] [--wait] [--wait-timeout 216h]` | 合规删除（第 7.5 节）；`--wait` 等到 `PhysicallyErased`，默认最多 9 天 |

任务类命令的共同参数：`--retries`（默认 2）、`--timeout`（单次尝试，默认 `1h`）、`--wait`、`--wait-timeout`（默认 `4h`）。同一条命令再跑一次会找到同一个任务；参数变了则被拒绝，因为任务的 spec 不能改。

**退出码。** `0` 成功；`1` 失败（包括任务 `Failed`、等的条件不可能达到）；`2` 等待超时；`3` `colvenna check --wait` 有规则未通过。错误以 `error: <消息>` 写到标准错误。

```bash
colvenna -n colvenna-data apply -f orders-mysql.yaml
colvenna -n colvenna-data wait source orders-mysql --for table/orders=Ready --timeout 15m
colvenna -n colvenna-data get tables
colvenna -n colvenna-data backfill dwd_shop.orders --name orders-backfill-20261020 --wait --wait-timeout 30m
colvenna -n colvenna-data delete source orders-mysql
```

**限制。** 没有删 tag 的命令（用 YAML 的 `deleteTag` 任务），没有安装与诊断命令（`colvenna install`、`colvenna doctor` 都没有交付）。

需要的 Kubernetes 权限：`apply`、任务类命令要对应资源的 `create` 与 `patch`；`describe` 还要能列 `events`；`waive` 另要 `create selfsubjectreviews`；`forget` 要 `create secrets` 与 `create lakeerasures`。

## 12. 管理控制台

控制台在 `https://console.<入口域名>/`（第 2 节）。它展示流水线全景，并提供与 CLI 相同的受控操作。

### 12.1 账号与登录

平台管理员在安装机器上添加用户（[安装部署手册](install.md)第 5 节）：

```bash
COLVENNA_ENV=uat deploy/scripts/entry/users.sh add alice alice@example.com colvenna-admin   # 一个组：colvenna-admin、colvenna-operator 或 colvenna-bi
COLVENNA_ENV=uat deploy/scripts/entry/users.sh groups alice colvenna-admin colvenna-bi           # 把组设为这些；不给组即去掉全部角色
COLVENNA_ENV=uat deploy/scripts/entry/users.sh disable alice                              # 停用并结束会话
COLVENNA_ENV=uat deploy/scripts/entry/users.sh remove alice
```

- 不开放自助注册。新用户收到 Cognito 发来的临时口令（3 天有效），首次登录时设新口令（至少 14 位，含大小写字母、数字与符号），并绑定 TOTP 应用。之后每次登录都要输入 TOTP 码；不用短信。
- 会话：登录后 8 小时有效，从登录起算，刷新不延长。身份每 5 分钟向 Cognito 刷新一次，所以停用、改组、登出最迟 5 分钟后生效。
- 登出：页头的"退出登录"。它作废这个用户的全部会话，包括别的浏览器里的、BI 的。
- 控制台与 BI 共用一次登录。脚本没有重新启用账号的命令。
- 验收测试直接调用 Cognito API 建测试用户，没有经过 `users.sh`。

### 12.2 角色

| 用户组 | 角色 | 能做什么 |
| --- | --- | --- |
| `colvenna-admin` | 平台管理员 | 全景、节点页，全部受控操作 |
| `colvenna-operator` | 运维 | 全景、节点页，运维的受控操作 |
| `colvenna-bi` | BI 用户 | 控制台只有首页；用 BI（第 13 节） |
| 其他 | — | 所有页面都是 403："你的账号还没有分配角色，请联系平台管理员。" |

### 12.3 流水线全景

`/pipeline` 画出数据源、湖表、分层作业与看板，以及它们之间的边，数据从左向右流。它只读：不改资源，不展示表数据、Secret 或口令。

- **红色**：出问题的环节：`phase` 为 `Degraded` 或 `Failed`，或有正在触发（firing）的告警。
- **琥珀色**：受影响的环节：出问题环节的所有下游。
- 新鲜度告警（`ColvennaFreshnessP99` 等）是整条路径的结果。上游有让数据迟到的问题时，它只记为 `alert <名> (from upstream)`，问题算在上游。
- 节点页（`/pipeline/node?id=…`）列出状态、受谁影响、作业、指标、告警、条件、上游的滞后、看板查询的刷新与挂在这张表上的任务。
- 归不到某个节点的告警（如 `ColvennaMetricsDown`）列在全景页底部。

控制台不链接、也不嵌入 Grafana、Marquez 与 Flink 的 Web UI，它们只能经 `kubectl port-forward` 访问。控制台只显示 `colvenna-data` 一个命名空间，即使平台管理着更多命名空间。

### 12.4 受控操作

`/operations` 页上，每个操作是一个表单：

| 操作 | 对应的 CLI | 谁能做 | 要确认 |
| --- | --- | --- | --- |
| 应用声明 | `colvenna apply -f` | 运维、管理员 | — |
| 挂起、恢复 | `colvenna suspend`、`colvenna resume` | 运维、管理员 | — |
| 压缩、打 tag、质量检查 | `colvenna maintain compact`、`colvenna maintain tag`、`colvenna check` | 运维、管理员 | — |
| 回刷、过期快照、清理孤儿文件 | `colvenna backfill`、`colvenna maintain expire`、`colvenna maintain orphans` | 运维、管理员 | 要 |
| 删除声明 | `colvenna delete` | 管理员 | 要 |
| 豁免 | `colvenna waive` | 管理员 | — |
| 合规删除 | `colvenna forget` | 管理员 | 要 |

- **应用声明**不接受 `LakeErasure`、`LakeWaiver`，以及回刷、过期快照、清理孤儿文件这三类任务：它们要走各自的操作，后三类需要确认。也不接受表单之外命名空间的对象。
- **确认**：第一步只列出影响范围，例如删除源时它的表怎么处理、哪些下游受影响。确认在 5 分钟内、只对这一次的内容有效，改了任何字段都要重新预览。对象自预览以来变了（例如有人改了 `deletionPolicy`），执行时得到 409。看不出影响范围时不给确认。
- **以本人身份。** 控制台以受限的 Kubernetes 用户提交：管理员是 `colvenna-console:admin`，运维是 `colvenna-console:operator`，并在请求里带上本人。运维用户不能删除声明、不能建删除请求与豁免、不能建 Secret；两者都不能读 Secret。
- 删除只发出请求，不等 Operator 清理完；结果到节点页看。

### 12.5 审计

| 记录 | 位置 |
| --- | --- |
| 控制台：登录、每个请求、拒绝、登出、每个操作（预览、请求与结果） | CloudWatch 日志组 `/colvenna-lake-<环境>/console-audit`，保留 1 年 |
| Cognito 用户活动：口令或 TOTP 错误、风险评估、来源地址 | `/aws/vendedlogs/colvenna-lake-<环境>/user-activity`，保留 1 年 |
| ALB 访问日志，包括认证之前就拒绝的请求 | 入口栈的访问日志桶，保留 1 年 |
| 控制台以本人身份发出的 Kubernetes 请求 | EKS 集群审计日志：CloudWatch 日志组 `/aws/eks/<集群名>/cluster`，保留 1 年 |

合规删除的主体值不进审计。审计写不进时，操作返回 503，什么也不改。

### 12.6 限制

- 控制台能做它扮演的两个用户能做的事：控制台被攻破时，攻击者能以 `colvenna-console:admin` 改、删所管命名空间的声明、建 Secret，但不能读 Secret，也不能扮演别的 Kubernetes 用户。不过它在请求里写的"本人"由它自己给出，被攻破时可以写成任何人，审计与豁免的批准人都会记成那个人。
- 一次一个操作，没有批量与撤销；不可逆的操作确认之后不能撤回。合规删除的主体值经过浏览器。
- 登出、停用或改组之后，一个被复制走的会话 cookie 还能用到它的 access token 过期，最长 5 分钟。
- 只覆盖 `colvenna-data` 一个命名空间；流水线全景只验收了 MySQL CDC 源，Kafka 事件源的边与 `kafkaLag` 只有单元测试。
- 用的是 Cognito 经典版托管登录页；入口没有 WAF，只部署在一个区域。默认 8 小时的会话时长没有在运行中等到期，验收用的是 600 秒。

## 13. BI 查询

BI 是 Apache Superset 6.1.0，地址 `https://bi.<入口域名>/`（第 2 节），与控制台同一次登录。

**谁能查什么。**

- `colvenna-bi` 组的用户：只能看到自己作为 `sso` 用户所在的看板，每个看板在 Superset 里是一个数据库 `<命名空间>/<看板名>`。看板的 `users[].sso.username` 把入口的登录名映射到一个 Doris 用户：

  ```yaml
    users:
      - {name: analyst, passwordSecret: {name: orders-board-analyst}, queries: [live, by_state],
         rows: "state = 'created'", sso: {username: alice}}
  ```

- 每个人以自己的 Doris 用户查询，所以看板的授权、脱敏与行过滤照常生效。BI 不另做一层过滤。
- `colvenna-admin` 组的用户是 Superset 的 `Admin`。`colvenna-operator` 组的用户没有 BI 角色，得到 403。

**能做的。** SQL Lab 的即席查询、数据集、图表、Superset 仪表板，导出 CSV（导出的是本人能看到的、已脱敏已过滤的行）。共享的仪表板与数据集，每个查看的人都以自己的身份查询。结果最多 10,000 行。查询是同步的：入口负载均衡器的空闲超时是 120 秒，SQL Lab 里超过这个时间的查询拿不到结果；配置没有改 Superset 的 `SUPERSET_WEBSERVER_TIMEOUT`（默认 60 秒），图表与仪表板的请求按它超时。这两个时限都没有专门验收。

**被拒绝的。** 写入（DML、DDL）；查湖表、`information_schema` 或没授予的对象（Doris "Permission denied"）；别的看板的数据库；API token、Superset 自己的登录与 guest token；报表、告警、嵌入与 Jinja 模板。

**生效时间。** 改组、停用最迟 5 分钟生效。从看板去掉一个人的登录名，或删掉看板，要等 Operator 一轮、Secret 卷同步与 BI 的数据库同步。验收中，去掉登录名 16 秒后这个人看不到那个数据库，删掉看板 78 秒后其余的人也看不到了（[M5 BI 验收](../history/m5/bi.md)）。

**限制。**

- 直连 Doris 与经 BI 是同一个 Doris 用户、同一个口令，BI 进程持有每个带登录名用户的口令。
- Superset 的 `Admin` 能看到所有人保存的查询与 SQL 历史，元数据库因此是个人数据的又一个副本，还没有纳入删除流程。
- 只验收了表格图表与 SQL Lab；其他图表类型走同一条查询路径，没有逐一验收。
- 一个 Web 进程，没有异步 SQL Lab。BI 到 Doris FE 是集群内的明文 MySQL 协议。
- 看板删除之后，Superset 里它的数据库、数据集与图表还留着，但谁也连不上，要由管理员清理。

## 14. 告警与指标

### 14.1 打开 Grafana 与 Prometheus

两者都只有 ClusterIP，只能经 `kubectl port-forward` 访问：

```bash
kubectl -n colvenna-monitoring port-forward svc/colvenna-monitoring-grafana 3000:3000        # http://localhost:3000，用户 admin
kubectl -n colvenna-monitoring get secret colvenna-grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
kubectl -n colvenna-monitoring port-forward svc/colvenna-monitoring-prometheus 19090:9090    # http://127.0.0.1:19090/alerts
```

Grafana 只有一个仪表板 "Colvenna Lake 运行"（uid `colvenna-lake`），分为延迟、积压、写入与提交、维护积压、数据质量与治理几行，阈值线与告警用同一组目标。

**平台没有 Alertmanager。** 告警只在 Prometheus 里触发，接收方（Alertmanager、值班系统）由部署方接入。控制台的全景与节点页也显示正在等待与正在触发的告警。

### 14.2 延迟的目标

| 段 | p95 | p99 |
| --- | --- | --- |
| 每一段（源库 → ODS，上一层 → 本层） | 120 秒 | 300 秒 |
| 整条路径（源库 → 查询这张表） | 900 秒 | 1800 秒 |
| Paimon → Doris 副本 | 300 秒 | 600 秒 |
| 看板查询 | 3 秒 | 10 秒 |

### 14.3 告警

| 告警 | 什么时候 | 先看什么 |
| --- | --- | --- |
| `ColvennaSourceLatencyP95`（warning）、`ColvennaSourceLatencyP99`（critical） | 源库 → ODS 的心跳延迟 30 分钟 p95 超过 120 秒、持续 10 分钟；10 分钟内最大值超过 300 秒 | `colvenna describe source`，checkpoint 类告警，Grafana 的延迟行。源恢复后积压的心跳到达也会触发 |
| `ColvennaSourceStale` | 湖里最新的源端变更超过 330 秒 | 源是否挂起或失败、作业是否运行、心跳是否写成 |
| `ColvennaLayerLatencyP95`、`ColvennaLayerLagP99` | 逐层延迟 p95 超过 120 秒；本层有上游变更等了 300 秒以上 | `colvenna describe table` 的上游与滞后；是否挂起、回刷、checkpoint 失败 |
| `ColvennaFreshnessP99` | 查询一张分层表看到的数据旧于 1800 秒，持续 5 分钟 | 先看上游：控制台标 "(from upstream)" 时，问题在上游 |
| `ColvennaDashboardStale` | 看板副本超过 600 秒没有成功刷新 | `colvenna describe dashboard`，节点页里查询的刷新 |
| `ColvennaDashboardDegraded` | 看板 `Degraded` 或 `Failed` 持续 5 分钟 | 状态与 Warning 事件 |
| `ColvennaDashboardUnmeasured` | Operator 5 分钟没看过这个 `Ready` 的看板 | Operator 是否正常 |
| `ColvennaQualityGateClosed` | `publish: true` 的门禁关闭 5 分钟 | `colvenna describe quality` 的原因；再校验或豁免 |
| `ColvennaQualityUnmeasured` | 10 分钟没有判过门禁 | Operator |
| `ColvennaLineageStale`、`ColvennaLineageFailing`、`ColvennaLineageRejected` | 血缘 10 分钟没上报成功；持续失败；被 Marquez 拒收 | Operator 日志与 Marquez |
| `ColvennaRowsQuarantined` | 15 分钟内有行进了隔离表 | 隔离表的 `colvenna_rule` |
| `ColvennaQuarantineFull` | 隔离表超过 10,000 行，持续 30 分钟 | 源或检查需要修 |
| `ColvennaEventsRejected` | 15 分钟内有事件被拒 | 拒绝表，按 `reason` |
| `ColvennaDegraded` | 源或分层表 `Degraded`、`Failed` 持续 5 分钟 | `colvenna describe` 的条件与事件 |
| `ColvennaCommitFenced` | 有提交被 colvenna-catalog 围栏：有快照不是经 catalog 提交的 | 正确性问题，交平台管理员排查 |
| `ColvennaErasureOverdue` | 删除请求超期 | `colvenna describe erasure` 列出还剩哪些副本 |
| `ColvennaMetricsDown`、`ColvennaMetricsAbsent` | 抓不到 Operator 或 colvenna-catalog 的指标 | 它们的 Pod；这期间别的告警都缺 |
| `ColvennaHeartbeatFailing` | 心跳写不进源库，持续 5 分钟 | 心跳用户的凭证与权限。这是测量故障，不代表数据不新鲜 |
| `ColvennaMeasuresStale` | 源或分层表 300 秒以上没测量，持续 5 分钟 | Operator 日志与对象事件 |
| `ColvennaSourceUnmeasured`、`ColvennaFreshnessUnmeasured`、`ColvennaLayerUnmeasured` | 运行中的源没有延迟、分层表没有新鲜度或滞后，持续 15 分钟 | 声明心跳，或查上游 |
| `ColvennaCommitConflicts` | 一张表的提交冲突超过每秒 0.05 次，持续 10 分钟 | 两个写入方在竞争 |
| `ColvennaNoCheckpoint`、`ColvennaCheckpointsFailing` | 10 分钟没有 checkpoint（也就没有提交）；15 分钟内有 checkpoint 失败 | 作业的日志与状态 |
| `ColvennaCompactionBacklog`、`ColvennaSmallFiles`、`ColvennaSnapshotsPiling` | level-0 文件超过 50 个持续 30 分钟；小文件超过 1000 个、快照超过 5000 个持续 1 小时 | 安排压缩、快照过期任务 |
| `ColvennaTaskFailed` | 有任务 `Failed` | `colvenna describe task` |
| `ColvennaCatalogErrors`、`ColvennaCatalogCommitSlow` | colvenna-catalog 的 5xx 超过每秒 0.05 次；提交 p99 超过 2 秒；各持续 10 分钟 | colvenna-catalog 的健康检查（`deploy/scripts/catalog/check-health.sh`） |

在 EKS 上实际触发过的有 `ColvennaRowsQuarantined`、`ColvennaLayerLagP99`、`ColvennaSourceStale`、`ColvennaSourceLatencyP99`、`ColvennaCheckpointsFailing`，以及等待中的 `ColvennaCompactionBacklog`；其余告警由规则单元测试逐条验证。新鲜度告警在验收中没有触发过，`ColvennaFreshnessUnmeasured` 没有规则单元测试。

### 14.4 心跳与延迟

在 MySQL 或 PostgreSQL 源上声明心跳，Operator 才能测端到端延迟：

```yaml
  tables: {include: [orders, customers, colvenna_heartbeat]}      # 心跳表也要选上
  monitoring:
    heartbeat: {table: colvenna_heartbeat, credentialsSecret: {name: orders-mysql-beat}}
```

- 心跳表满足源表约定，有 `id INT` 主键、`beat_at DATETIME(6)` 与版本列。心跳用户只有这张表的 `SELECT, INSERT, UPDATE`。
- Operator 每 30 秒以源库自己的 UTC 时钟更新第 1 行，再看这一行第一次出现在哪个 ODS 提交里，由此得到 `colvenna_source_latency_seconds` 与 `colvenna_source_freshness_seconds`。时钟偏差超过 5 秒的心跳不算。
- 一个源的表共用一次提交，所以心跳按源测；单独落后的表只在它的状态与积压里。
- MongoDB 与 Kafka 事件源没有心跳，只报积压。经 Kafka 承载的 MySQL 源可以声明心跳，但没有验收。

### 14.5 关键指标

Operator 与 colvenna-catalog 在端口 9100 导出 Prometheus 指标。Operator 的指标至少带 `namespace` 与资源名两个标签；colvenna-catalog 的指标按动作、表与结果分。

| 指标 | 含义 |
| --- | --- |
| `colvenna_source_phase`、`colvenna_table_phase`、`colvenna_dashboard_phase`、`colvenna_task_phase`、`colvenna_erasure_phase` | 当前阶段为 1 |
| `colvenna_source_latency_seconds`、`colvenna_source_freshness_seconds` | 源库 → ODS 的延迟、湖里最新变更的年龄（心跳） |
| `colvenna_table_layer_latency_seconds`、`colvenna_table_upstream_lag_seconds`、`colvenna_table_upstream_lag_snapshots` | 逐层延迟与滞后 |
| `colvenna_table_freshness_seconds` | 查询这张表看到的数据有多旧 |
| `colvenna_source_kafka_lag`、`colvenna_source_events_rejected` | Kafka 未消费的记录数、被拒的事件数 |
| `colvenna_table_quarantined_rows` | 隔离表的行数，按检查 |
| `colvenna_table_level0_files`、`colvenna_table_small_files`、`colvenna_table_snapshots` | 维护积压，每张表最多每 5 分钟数一次 |
| `colvenna_job_last_checkpoint_timestamp_seconds`、`colvenna_job_checkpoints_failed` | 作业健康 |
| `colvenna_dashboard_refreshed_timestamp_seconds` | 看板副本上次成功刷新的时间 |
| `colvenna_quality_gate_open`、`colvenna_quality_rule_passed` | 门禁与规则 |
| `colvenna_iceberg_published`、`colvenna_iceberg_lag_seconds` | Iceberg 发布与滞后 |
| `colvenna_catalog_commits_total{outcome}` | colvenna-catalog 的提交：`committed`、`conflict`、`fenced`、`error` |

### 14.6 限制

- S3 的 503 Slow Down 次数与每次提交的 list 次数没有导出。

## 15. 常见问题

**新接入的表多久能查到？** 源库的一次提交，至少要过一个 checkpoint 间隔（默认 30 秒）才进入 ODS 的快照。开发阶段的 24 小时基线实测：源库到 Doris ODS 外表 p95 1.70 分钟、p99 2.29 分钟，到 DWS 看板 p95 2.59 分钟。结论只适用于[基线报告](../history/m1/capacity-baseline.md)里的版本、配置与负载。

**源库加了一列，下游会怎样？** ODS 表自动加列。分层表不做 schema 演进：查询的输出不变，新列不会出现在 DWD、DWS 里；要用新列，另声明一张分层表。看板上新列默认脱敏，Iceberg 发布在复核并列进 `columns` 之前整表撤下。

**为什么我的表一直是 `Rejected`？** 看 `status.tables[]` 里这张表的 `reason` 与 `message`，它写明缺了什么，多半是源表约定（第 3.2、3.3 节）或权限。改好后不必做什么，下一轮自动准入。

**`Failed` 的表怎样恢复？** `Failed` 不会自己恢复。取消选择这张表，请平台管理员删掉它的目标表，再选回来；或者另声明一个源。

**能不能改作业的并行度？** 数据库源不能：并行度一变，checkpoint 状态就无法恢复，所以 Operator 保持部署时的并行度（`ParallelismPinned`），要换就重建这个源。分层表与 Kafka 事件源可以改。

**删掉声明后表还在吗？** 默认 `deletionPolicy: Retain`，表保留。保留下来的表不能被新的同类声明接着写（`TargetConflict`）。想连表一起删，删除前把 `deletionPolicy` 改成 `Delete`。

**谁来压缩、过期 ODS 表？** 直连 MySQL、PostgreSQL 与 Kafka 事件源的写入作业只写不压，压缩与快照过期由你用 `LakeTask` 安排，例如在编排器里每天运行，或在 Doris 看板读 ODS 时更频繁地压缩。直连 MySQL、PostgreSQL 源上个人数据与未分类的表，Operator 自己每小时过期一次。

**需要 Airflow 吗？** 分层加工是常驻的流作业，不需要调度器。需要调度的是批处理：回刷、快照过期、打 tag、质量校验，由你的编排器建 `LakeTask` 或调用 `colvenna`。

**为什么分析师在看板上看到的列是 NULL？** 列的级别高于看板的 `access`，被脱敏（`redact`）了。没有标签的列按表的级别判断，所以新列在标注之前也是 NULL。

**为什么一个看板用户一行也看不到？** 同一个看板里有别的用户声明了 `rows`。这时没有声明 `rows` 的用户一行也看不到，给这个用户也声明一个条件。

**怎样把一张表给外部的 Spark 或 Trino？** 声明 `LakeIcebergPublication`（第 9 节）。只有 L2 及以下、不含个人数据、建表时带了 Iceberg 元数据选项的表能发布。

**控制台里为什么看不到 Grafana 的链接？** 控制台有意不链接 Grafana、Marquez 与 Flink 的 Web UI，它们只能经 `kubectl port-forward` 访问，访问者要有集群的 IAM 认证。

**一个 Pod 想读湖表，要做什么？** 平台不给 Pod 直接读仓库桶的权限。读湖要经 colvenna-catalog：用一个 colvenna-catalog principal（由平台管理员建），把它写进源或分层表的 `readers`，引擎以它的 token 读，凭证由 colvenna-catalog 按表签发。colvenna-catalog 只在集群内可达，只放行带 `colvenna-lake.io/catalog-client=true` 标签的命名空间。

## 16. 维护与恢复

备份与恢复由平台管理员做，步骤见[安装部署手册](install.md)第 11 节。这里说明使用者需要知道的几点。

**表的数据不另做备份。**

- 仓库桶不开版本，平台没有回收站。删表就删文件：以 `deletionPolicy: Delete` 声明的表随声明删除，管理员也可以直接删表。删掉的表不能从平台恢复。
- 表内的历史靠快照。快照过期之前，可以按快照号或 tag 读到旧数据。要留住某个时刻更久，用 `createTag` 打 tag（第 5.1 节），并给足 `timeRetained`：默认只保留 7 天，最长 999 天；含个人数据或未分类的表最多 24 小时。tag 到期后，快照过期任务把它清掉。要在这之外长期保存，请平台管理员把数据复制到平台之外（[安装部署手册](install.md)第 11.5 节）。

**元数据可以恢复到某个时刻。** colvenna-catalog 的元数据在项目 RDS 上，自动备份保留 1 天。管理员把它恢复到时刻 T 之后：

- 恢复期间 colvenna-catalog 停止约 3 分钟（实测 189–192 秒），作业对它的请求失败后重试；
- T 之后的提交还在：管理员 reconcile 之后，表追上文件里最新的快照。reconcile 之前，这些表拒绝新的提交；
- T 之后新建的表由管理员重新登记；T 之后删掉的表，管理员从 catalog 去掉；
- T 之后的授权、改名与 token 都会丢失：新签发的 token 失效，要重新签发；T 之后撤销的 token、收回的授权会重新生效，管理员要再撤销一次。

**环境重建之后。** 管理员可以把保留下来的表目录登记进新环境，表的快照历史、tag 与分支都在；但授权、token、Iceberg 发布、血缘与 BI 的看板不迁移。声明请保存在你自己的版本库里，重建之后重新应用；重新应用的声明接着写登记回来的表，没有验证过。

## 17. 限制与未验证项

各节末尾写了该能力的限制，这里汇总平台层面的几项：

- **只在 Amazon EKS 加 S3 上验证过。** 按表凭证由 AWS STS 签发，作业身份用 IRSA，Web 入口用 ALB 与 Cognito。第二朵云的适配没有开展（[#1](https://github.com/colvenna/colvenna-lake/issues/1)）。
- **一套环境在一个区域。** 环境可以装在任一标准 AWS 区域（`COLVENNA_REGION`，见[安装部署手册](install.md)第 1 节），中国区与 GovCloud 不支持；没有跨区域的复制或容灾。
- **开发阶段不保证升级兼容。** CRD 字段与作业参数的格式都可能变化，不做跨版本回退演练。
- **规模。** 验收在写入、查询节点各 2 台的环境上进行，作业多为 0.5 CPU，表在数千到数万行。容量与性能的结论见[性能测试](../uat/performance.md)。
- **你自己的 Flink 作业以流模式写有界输入、写入并行度大于 1 时，可能静默丢数据。** 这是上游 Paimon 2.0.0 的缺陷，记在本仓库的 [#6](https://github.com/colvenna/colvenna-lake/issues/6)。
  - 触发条件，四个同时成立：流模式且开了 checkpoint；输入有界，例如一次性回填、`INSERT INTO ... SELECT` 一个有界的源；写入并行度大于 1，各写入子任务在不同的 checkpoint 之后结束；最后一个写入子任务的输入结束，赶在提交器处理上一个"checkpoint 完成"之前。提交慢或节点忙时更容易碰上。
  - 后果：后结束的写入子任务在输入结束时刷出的数据没有提交，成了孤儿文件；作业照常 `FINISHED`，没有报错。原因在 Paimon 的提交器 [`CommitterOperator`](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-common/src/main/java/org/apache/paimon/flink/sink/CommitterOperator.java#L180-L220)；截至 2026-10-28，[2.1.0](https://github.com/apache/paimon/blob/3a5ffe7870697e2c2605e63413fb5ac5800aae7e/paimon-flink/paimon-flink-common/src/main/java/org/apache/paimon/flink/sink/CommitterOperator.java) 与 master（`5be7e2dd`）的这个文件与 2.0.0 相同。
  - 影响范围：经 colvenna-catalog 以流模式写有界输入的用户作业；问题不在 catalog，任何使用 Paimon 2.0.0 Flink 连接器的部署都一样。平台自己管理的作业不受影响：数据库 CDC 源（MySQL、PostgreSQL、MongoDB 与 Kafka 承载的 CDC）、Kafka 事件源与分层表的作业，输入都是无界的；`LakeTask` 以批模式运行。
  - 绕法：有界写入用批模式（`execution.runtime-mode=batch`，SQL 里是 `SET 'execution.runtime-mode' = 'batch';`）；一定要用流模式时，把写入并行度设为 1，例如在目标表上加 `/*+ OPTIONS('sink.parallelism'='1') */`（Paimon 2.0.0 的写入端按它定并行度，见 [`FlinkTableSinkBase`](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-flink/paimon-flink-common/src/main/java/org/apache/paimon/flink/sink/FlinkTableSinkBase.java#L166)），或者把整个作业的并行度设为 1。验收的 Flink 流程 `FlinkCatalogFlow` 的流写步骤就是这样写的。
- **平台 Doris 没有高可用**，集群内到 Doris 是明文 MySQL 协议。
- **Marquez 与 Prometheus 自身不认证**，只靠 NetworkPolicy 放行指定的命名空间。
- **告警没有接收方**，要由部署方接入 Alertmanager 或值班系统。

## 附录 A. 流程与 UAT 用例对照

每个主要流程都有对应的 UAT 用例，用例的步骤与期望结果见 [UAT 测试用例](../uat/cases.md)，本次 Release 必须执行的用例集合见其中第 7 节。2026-10-20 至 21 日在新建的 `uat` 环境上完整执行了一遍当时的 33 条用例，全部通过，见 [UAT 执行报告](../uat/report.md)；部署运维（UAT-DEP-01–09）与节点故障（UAT-NODE-01、02）的用例是之后加的。每个发布候选都在 `us-west-2`、`us-east-2` 两个区域执行全部必需用例；最新的 `v1.0.0-rc.5` 两个区域全部 PASS，结果见 [v1.0.0-rc.5 的 UAT 报告](../uat/reports/v1.0.0-rc.5/report.md)。

| 流程 | 本手册 | UAT 用例 |
| --- | --- | --- |
| 接入 MySQL、PostgreSQL | 3.2、3.3、3.7、3.8 | UAT-SRC-01 |
| 接入 MongoDB | 3.4 | UAT-SRC-02 |
| 接入 Kafka 事件 | 3.5 | UAT-SRC-03 |
| Kafka 承载的 CDC | 3.6 | UAT-SRC-04 |
| 分层表 DWD、DWS 与升级 | 4 | UAT-TBL-01 |
| 维护任务、回刷、Airflow | 5 | UAT-TSK-01 |
| 流内检查与隔离表 | 4.4 | UAT-MON-01 |
| 批量校验、门禁与豁免 | 6 | UAT-QUA-01 |
| 分级、保留与发布 | 7.1–7.3 | UAT-GOV-02 |
| 列标签、脱敏与行过滤 | 7.4、8.2 | UAT-GOV-01 |
| 合规删除 | 7.5 | UAT-GOV-02、UAT-ICE-04 |
| 看板服务与并发上限 | 8 | UAT-DSH-01 |
| Iceberg 发布 | 9 | UAT-ICE-02、UAT-ICE-03 |
| 已有 Trino 读 Iceberg | 9.3 | UAT-ICE-01 |
| 血缘 | 10 | UAT-LIN-01 |
| `colvenna` CLI 与控制台的受控操作 | 11、12.4 | UAT-CON-01 |
| 流水线全景 | 12.3 | UAT-CON-02 |
| 登录、会话与入口 | 12.1、12.2 | UAT-ENT-01 |
| BI 查询 | 13 | UAT-BI-01 |
| 告警与指标 | 14 | UAT-MON-01 |
| 第二个受管命名空间 | 1.2 | UAT-NS-01 |
| 引擎经 colvenna-catalog 读写、按表凭证 | 1.4、15 | UAT-CAT-01–05、UAT-CAT-12 |
| 从零安装（带 `--fixtures` 与只装平台） | [安装部署手册](install.md)第 3、4 节 | UAT-DEP-01、UAT-DEP-02 |
| 重复执行、中断后续装 | [安装部署手册](install.md)第 3 节 | UAT-DEP-03、UAT-DEP-04 |
| 环境名与区域的检查 | [安装部署手册](install.md)第 1、9 节 | UAT-DEP-05 |
| 升级 | [安装部署手册](install.md)第 7 节 | UAT-DEP-06 |
| 卸载 | [安装部署手册](install.md)第 8 节 | UAT-DEP-07 |
| 按发布清单安装 | —（步骤见 UAT-DEP-08） | UAT-DEP-08 |
| 元数据从备份恢复、重建后挂回表 | 16；[安装部署手册](install.md)第 11 节 | UAT-DEP-09 |
| 节点丢失后作业、看板与入口的恢复 | —（平台自己恢复，用户不用操作） | UAT-NODE-01、UAT-NODE-02 |

## 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Spark, Spark, Apache Superset, Superset, Apache Polaris, Polaris, Apache Airflow, Airflow, Apache DolphinScheduler, DolphinScheduler, Apache Arrow and Arrow are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；平台的查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见[商标与命名](../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #238）是本项目早期私有仓库的编号，本仓库里没有对应的页面。其中带链接的 issue 编号（如 [#1](https://github.com/colvenna/colvenna-lake/issues/1)）是本仓库现在的编号。
