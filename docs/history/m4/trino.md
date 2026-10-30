---
title: "M4 已有 Trino 的接入边界"
date: 2026-10-18
status: complete
issue: 36
---

# M4 已有 Trino 的接入边界

本页记录 Issue #36 的验收：用户已有 Trino、确需统一 SQL 入口时，它读湖表的实际能力与责任边界。Trino 不进平台的默认安装（HLD 10.2）。原生 Paimon 连接器（`apache/paimon-trino`）没有发布版、不支持 Paimon 2.0、丢弃按表凭证，所以可行的路径是 Trino 的 Iceberg 连接器读 #34 发布到 Polaris 的表（[调研](../../research/trino.md)，开放层见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的"开放层"一节与 [M4 开放层验收](iceberg.md)）。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。验收里的 Trino 是用户 Trino 的替身：Trino 484，一个兼做 worker 的 coordinator，没有 Service、NetworkPolicy 不放行任何入站，没有自己的 AWS 身份，只能经 `kubectl exec`（集群的 IAM 认证）查询。第二朵云未验证（#33 未开展）。

**证据的来源版本**：结果来自提交 `713ccb8`（rebase 到 main 之后是 `98fd273`，只多了 main 上 #131 的删除补验：另一个验收脚本与它的文档），私有证据目录 `m4-36/run-t8-713ccb8/`（t5 `afe61cd` 先验收了 append 表，t7、t8 加上主键表，见"过程中的发现"）。镜像：Operator `cove-operator@sha256:41274079…`，作业 `cove-jobs@sha256:2f663410…`（kafka）与 `cove-jobs@sha256:7f9ce263…`（mysql）；Trino `trinodb/trino:484@sha256:2704eb53…`；Polaris 1.8.0（#34 安装）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 目标 Trino 与 Paimon 版本组合能读取指定样例表，结果与 Doris 或 Paimon 原生读取一致 | 通过（Trino 484 + Iceberg 连接器 + Polaris 1.8.0，Paimon 2.0.0 写入的 append 表与主键表） | append 表：8 列全部比对，Trino 与 Paimon 原生读取（`LakeDump`）相同，第一批 40 行，再写一批后 65 行；Trino 看到的最新快照就是发布的快照 12；`FOR VERSION AS OF 12` 读出 40 行；元数据表 `clicks$files` 可读。主键表：第一次全量压缩之前它不发布（`Pending`，没有授权），Trino 找不到它；压缩后 6 列全部比对，60 行与 Paimon 相同；MySQL 改 10 行、删 5 行之后，下一次全量压缩之前 Trino 读到的仍是压缩时的 60 行，压缩后 55 行与 Paimon 相同，10 行是 `paid`，删掉的没有了 |
| 认证、对象存储访问与用户授权的独立边界有负面测试 | 通过 | 认证：不带凭证的 catalog 被 Polaris 拒（`NotAuthorizedException`），错误的 client secret 拿不到 token（`unauthorized_client`）。对象存储：不要签发凭证时读不到元数据文件（没有凭证可用），写数据文件时 S3 拒绝（`AccessDeniedException`，签发的角色只读）。授权：没有授权的 principal 读不了表，也读不了它的元数据表（`ForbiddenException`）；删表、建表被 Polaris 拒；写入之后表仍是 65 行。Trino 自身：没有 Service，其他 Pod 连不上它的端口，它自己的 Pod 连得上（正向对照） |
| 不满足条件时支持矩阵明确标记为不支持，不影响默认 Doris 路径 | 通过 | 支持矩阵（[调研](../../research/trino.md)）把原生 Paimon 连接器、写入与 DDL、按 Trino 用户继承授权标为不支持，把其他版本、Starburst、容错执行、第二朵云标为未验证；`apache/paimon-trino` 在 Maven Central 上没有制品（404）；#36 不改产品代码和 chart，operator chart 渲染出的对象里没有 Trino |

## 运行

`infra/m4/flows/trino/run-trino.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL：

1. **源与发布。** Kafka 源把 `clicks`（append 表，按天分区）、MySQL 源把 `orders`（主键表，60 行）接入 ODS，两张表都带 `metadata.iceberg.storage: table-location`；两份 `LakeIcebergPublication`（各列出复核过的列）把它们发布给一个 catalog role。Polaris 里建两个 principal：Trino 的（得到这个 catalog role）和一个局外人的（什么也没有）。
2. **Trino。** `infra/m4/scripts/install-trino.sh` 装 Trino 484，带五个 catalog：正确的一个（OAuth2 客户端凭证、vended credentials、原生 S3 文件系统），以及四个必须失败的：不带凭证、错误的 secret、不要签发凭证、局外人的 principal。catalog 的配置里有凭证，放在 Secret 里；证据里只留去掉凭证的副本。
3. **读 append 表。** Trino 读 `clicks` 的全部 8 列，与 Paimon 原生读取逐行比对；查 `clicks$snapshots` 的最新快照，与发布的快照比对；再写一批，读出的行仍与 Paimon 相同；以 `FOR VERSION AS OF` 读第一次发布的快照；读 `clicks$files`。
4. **读主键表。** 第一次全量压缩之前查 `orders`；全量压缩（`LakeTask`）之后读全部 6 列，与 Paimon 逐行比对；MySQL 改 10 行、删 5 行，等变更到湖里，再读一次（应是压缩时的行）；第二次全量压缩之后再读，与 Paimon 逐行比对，并数 `paid` 与被删的行。
5. **边界。** 四个必须失败的 catalog 各查一次，错误必须说出是哪道边界拒的（Polaris、OAuth2、S3）；以正确的 catalog 写入、删表、建表；之后行数不变。两个 Trino 用户（alice、bob）读到的行相同。
6. **网络与安装。** Trino 的命名空间里没有 Service、Ingress；Trino 自己的 Pod 连得上自己的 8080，另一个命名空间的 Pod 连不上；Maven Central 上没有 `paimon-trino`；operator chart 渲染出的对象里没有 Trino。

## 结果

40 项检查全部通过（`verdicts.tsv`）。

| 检查 | 结果 |
| --- | --- |
| `trino-reads-what-paimon-reads`、`trino-follows-new-commits` | 8 列，40 行与 65 行，两边相同 |
| `trino-sees-the-published-snapshot`、`time-travel-reads-the-earlier-snapshot` | 最新快照 12 即发布的快照；`FOR VERSION AS OF 12` 读出 40 行 |
| `metadata-tables-readable` | `clicks$files` 列出 2 个数据文件 |
| `orders-withheld-until-compacted`、`orders-not-served-before-compaction` | 第一次全量压缩之前：发布 `Pending`、没有授权；Trino 得到 `Schema 'ods_m4tp_t8' does not exist`（目录里还没有它） |
| `trino-reads-the-compacted-orders` | 全量压缩后发布，6 列 60 行，两边相同 |
| `changes-reached-the-lake`、`trino-reads-the-compacted-orders-until-the-next-compaction` | 改 10 行、删 5 行，24 s 后 Paimon 读到 55 行；Trino 读到的仍是压缩时的 60 行（主键表的 Iceberg 视图是全量压缩后的数据，等于 Paimon 的 `$ro`：ODS 表只有这里的任务压缩它，写入端不压缩） |
| `trino-reads-the-changed-orders`、`updated-orders-visible`、`deleted-orders-gone` | 第二次全量压缩后 55 行两边相同，10 行 `paid`，`id > 55` 的 0 行 |
| `anonymous-gets-nothing` | `Cannot obtain metadata`，原因 `NotAuthorizedException: Not authorized` |
| `wrong-secret-gets-no-token` | `Cannot obtain metadata`，原因 `unauthorized_client: The client is not authorized` |
| `no-vended-credentials-no-data` | 读元数据文件时 `Unable to load credentials from any of the providers`（Pod 没有 AWS 身份，节点的实例角色也拿不到） |
| `ungranted-principal-reads-nothing`、`ungranted-principal-reads-no-metadata-table` | `ForbiddenException`：局外人的 principal 不能 `LOAD_TABLE` |
| `insert-refused` | 写 Parquet 文件时 S3 `AccessDeniedException`：`cove-lake-m4-iceberg-read` 的会话不能 `s3:PutObject` |
| `drop-refused`、`create-refused`、`nothing-written` | 删表、建表 `ForbiddenException`；之后仍是 65 行 |
| `trino-users-share-the-principal` | alice 与 bob 都读到 65 行：Trino 的用户分不开，谁都拿 catalog principal 的权限 |
| `trino-has-no-service`、`trino-listens-on-its-address`、`trino-unreachable-from-other-pods` | 没有 Service、Ingress；Pod 自己连得上 `:8080`，`cove-m3` 的 Pod 连不上 |
| `paimon-trino-unreleased`、`trino-not-in-the-platform-install` | Maven Central 404；chart 里没有 Trino |

## 过程中的发现

- t1：Trino 读出的行与 Paimon 相同，该拒的也都拒了，但验收脚本调用判定程序时漏了检查的名字，判定程序把 CSV 的路径当成了命令，10 项判定全部失败。Trino 的错误信息（`Cannot obtain metadata`、`Failed to load table`）不说原因，改为以 `--debug` 运行，取出它的 `Caused by` 行。
- t2：21 项通过，但伪造的 `INSERT` 因为缺了 NOT NULL 列，在 Trino 里就被拒了，没有碰到 S3 和 Polaris。改为给出全部必填列；t3 起它在写数据文件时被 S3 拒绝。
- 评审：读取只比对了 2 列；拒绝的匹配词里有 `401`、`403`，查询 id 里的数字就能命中；没有不带凭证的请求；网络探测没有正向对照；几处命令失败会让脚本中止或误判通过；调研里有几条外部事实缺出处。都已改正。
- t4：23 项通过；Trino 484 不接受整数字面量作为表版本（`Unsupported type for table version: integer`），time travel 改为 `CAST(<快照> AS BIGINT)`。t5 26 项全部通过（只有 append 表）。
- 之后补验主键表（t6–t8）：t6 的等待条件用了验收脚本里没有的函数，停掉重跑；t7 38 项全部通过；按评审意见补上压缩前的发布状态、等变更的判定和失败保护后，t8 40 项全部通过。

## 限制

- 只验收了 Trino 484；其他 Trino 版本、Starburst、容错执行都未验证。主键表的 Iceberg 视图是最近一次全量压缩后的数据，Trino 读到的新鲜度取决于全量压缩的间隔（见 [M4 开放层验收](iceberg.md)）。
- 授权以 Polaris 的 catalog principal 为单位：Trino 的每个用户都拿到它的权限，平台的授权不按 Trino 用户继承。Trino 自己的认证与访问控制由用户负责（Trino 默认放行一切）。
- 签发的凭证是平台这一侧的边界：替身 Trino 没有自己的 AWS 身份，所以读不到数据。用户的 Trino 若另有能读仓库的身份，就能绕过 Polaris；HLD 12.2 要求查询引擎不能另有宽 S3 角色，由接入的用户保证。
- 读进 Trino 之后的数据（导出、CTAS 到用户的其他 catalog）归用户的 Trino 管；平台只保证发出去的是 L2 及以下、没有个人数据、列已复核的表。
- 第二朵云未验证（#33 未开展）。

> 注：本文中的 issue 与 PR 编号（如 #36）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
