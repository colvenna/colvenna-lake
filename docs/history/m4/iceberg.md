---
title: "M4 开放层：Iceberg 兼容发布"
date: 2026-10-18
status: complete
issue: 34
---

# M4 开放层：Iceberg 兼容发布

本页记录 Issue #34 的验收：选定的 Paimon 表以 Iceberg 兼容的形式发布到 Iceberg REST 目录，只认 Iceberg 的外部读端只读地读到正确、受控的视图。设计见 [cove-operator LLD](../../design/colvenna-operator-lld.md) 的"开放层"一节，依据 HLD 10.3、12.1、12.5；调研见 [m4-iceberg-open-layer.md](../../research/iceberg-open-layer.md)。写入端只在表目录里写 Iceberg 元数据，由 Operator 核对后登记到 Apache Polaris 1.8.0 的 EXTERNAL 目录 `cove_open`；验收的读端是 Spark 3.5.8 + Iceberg 1.10.2。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群和 M2 控制平面上，只用合成数据。源是 M1 的 RDS MySQL 8.0 与 M2 的开发 Kafka。第二朵云未验证（#33 未开展），Glue 与 Athena 未验证；Trino 读这些表由 #36 验收。

**证据的来源版本**：结果来自提交 `6481455`（rebase 到 main 之后是 `031ca0a`，树相同），私有证据目录 `m4-34/run-i5-6481455/`。镜像：Operator `cove-operator@sha256:89206a15…`，作业 `cove-jobs@sha256:c9bbbcf7…`（mysql）、`cove-jobs@sha256:39974e66…`（kafka）与 `cove-jobs@sha256:97b7ebd6…`（sql）；Polaris `apache/polaris:1.8.0@sha256:347615e7…`；读端 Spark 3.5.8 + Iceberg 1.10.2（`infra/m4/readers/spark`）。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 选定读端与 Paimon 查询结果在规定可见性窗口内一致 | 通过 | append 表：Iceberg 快照 19 的 50 行与 Paimon 快照 19 相同，快照号相同，提交到发布 16 s；加列后 65 行相同。主键表：全量压缩后 100 行与 Paimon 相同；改 10 行、删 5 行后等于 Paimon 的 `$ro`，下一次全量压缩后 95 行与 Paimon 相同。每次提交到目录登记 2–28 s，窗口 90 s |
| 外部读端不能修改 Paimon 权威数据，也不能绕过已声明的表级访问边界 | 通过 | 读端的写入（S3 拒绝）、删表、建表、加列、改名（403）都被拒；没有授权的 principal 403；去掉读端的 catalog role 后 403，加回后又能读；不带 token 的五个接口都是 401，管理端口从读端命名空间连不上；签发的凭证读不了别的表（403）、写不了（AccessDenied），796 s 后过期；读端 Pod 没有 AWS 身份 |
| 提交、schema 变更、tag 与删除后的发布行为及不支持项有可验证记录 | 通过 | 提交、加列、tag、删除、L3 撤下与恢复、Polaris 宕机与追上、删除发布，各有 EKS 检查（见"结果"）；不支持项逐项写明怎样核对（见"不支持项"）；删除流程对 Iceberg 元数据的处理有单元测试 |

可见性窗口的定义（LLD "读端看到什么"）：append 表的一次提交，在 Operator 的下一轮（30 秒）加上读端自己的元数据缓存之内可见，验收以 90 秒为界；主键表看到的是最近一次全量压缩后的数据（等于 Paimon 的 `$ro`），窗口是全量压缩的间隔（写入端自己压缩的表默认 1 小时，ODS 表由用户定期跑的全量压缩 `LakeTask` 决定）加上一轮。

## 运行

`infra/m4/scripts/install-polaris.sh` 装 Polaris（库在项目的 RDS 上，只有 ClusterIP 服务，NetworkPolicy 只放行带标签的命名空间，所有 API 都要 OAuth2 token），`infra/m3/scripts/install-operator.sh` 让 Operator 以它的 principal 发布。`infra/m4/flows/iceberg/run-iceberg.sh` 依次做下面的步骤，每项检查写一行 PASS/FAIL；读端是读端命名空间里的 Spark Pod，没有自己的 AWS 身份，只用 Polaris 签发的凭证：

1. **源与发布。** MySQL 源把 `orders`（主键表，100 行）接入 ODS，Kafka 源把 `clicks`（append 表，按天分区）接入 ODS，两张表都带 `metadata.iceberg.storage: table-location`。两份 `LakeIcebergPublication` 列出复核过的列，把表发布给读端的 catalog role。
2. **主键表。** 第一次全量压缩之前不发布（元数据含有未压缩的文件，`MetadataIncomplete`），读端找不到表；全量压缩之后，Iceberg 的行与 Paimon 相同；MySQL 改 10 行、删 5 行之后，Iceberg 等于 Paimon 的 `$ro`，下一次全量压缩之后与 Paimon 相同。全量压缩不压缩 manifest，元数据链不断。
3. **append 表。** 每批事件的提交在窗口内发布；Iceberg 快照 N 的行与 Paimon 快照 N 相同，快照号相同。
4. **加列与 tag。** Kafka 源加一个字段：新列不在发布的 `columns` 里，表撤下，读端找不到；把它写进 `columns` 后重新发布，读端看到新列，行与 Paimon 相同。建一个 tag，读端在 `refs` 里看到它。
5. **门禁与故障。** 表改为 L3：撤下，读端找不到；改回 L2：重新发布。Polaris 停掉：写入照常，发布报不可用；恢复后追上 Paimon 的最新快照。
6. **边界。** 从 `readers` 去掉读端的 catalog role：它读不到了，加回来又能读。读端不能写、删、建表、加列、改名；没有授权的 principal 读不到；不带 token 访问目录配置、命名空间、`loadTable` 与管理 API 都是 401；读端命名空间连不上管理端口；签发的凭证只能读这张表的文件、不能写，15 分钟内过期；读端 Pod 没有别的途径读数据。
7. **撤下。** 删掉两份发布：目录里没有这两张表；之前签发的凭证仍能读到过期。

## 结果

64 项检查全部通过（`verdicts.tsv`）。

| 步骤 | 检查 | 结果 |
| --- | --- | --- |
| 主键表，压缩前 | `orders-withheld-until-compacted`、`orders-not-served-before-compaction` | 发布为 `Pending`（`MetadataIncomplete`），没有授权；读端 `TABLE_OR_VIEW_NOT_FOUND` |
| 主键表，第一次全量压缩 | `compaction-keeps-the-metadata-chain`、`iceberg-equals-paimon-after-compaction` | 任务结果"manifests not compacted: the table keeps Iceberg metadata"；快照 4 发布（提交后 12 s），100 行与 Paimon 相同 |
| 主键表，改与删 | `iceberg-is-ro-after-changes`、`iceberg-equals-paimon-after-changes`、`deleted-orders-gone`、`updated-orders-visible` | 快照 7 发布，100 行等于 `$ro`；第二次全量压缩后快照 8 发布（4 s），95 行与 Paimon 相同，被删的 5 行没有了，10 行是 `paid` |
| append 表 | `visibility-within-window`、`append-snapshot-equals-paimon`、`iceberg-snapshot-is-paimon-snapshot` | 快照 19 提交到发布 16 s；`VERSION AS OF 19` 的 50 行两边相同；Iceberg 的 `snapshots` 表有快照 19 |
| 加列 | `unreviewed-column-withdrawn`、`unreviewed-column-not-served`、`reviewed-column-republished`、`added-field-visible`、`rows-with-added-field-equal` | 加字段 `campaign` 后 1 s 内撤下，读端找不到表；写进 `columns` 后 7 s 重新发布；`DESCRIBE` 有 `campaign`，65 行两边相同 |
| tag | `tag-visible` | `refs` 里有 `eod-i5`（TAG） |
| 门禁 | `withdrawn-when-restricted`、`withdrawn-table-not-found`、`republished-when-l2` | 改为 L3 后撤下（`Restricted`），读端找不到；改回 L2 后重新发布 |
| Polaris 宕机 | `publication-unavailable`、`writers-unaffected`、`catalog-caught-up` | 发布报 `Unavailable`，源作业照常 `Running`；恢复后从快照 28 追到 37 |
| 撤销与写 | `revoked-reader-refused`、`reader-granted-again`、`reader-cannot-*` | 去掉 catalog role 后 `LOAD_TABLE` 403，加回后又能读；`INSERT` 写数据文件时 S3 拒绝，`DROP`、`CREATE`、`ADD COLUMN`、`RENAME` 都是 403 |
| 匿名与网络 | `anonymous-*-refused`、`management-port-closed-to-readers` | `loadTable`、`/config`、命名空间、管理 API 的 catalogs 与 principals 都是 401；8182 从读端命名空间连不上 |
| 签发的凭证 | `vended-*`、`reader-pod-has-no-other-way` | 只读这张表的目录：读别的表 403，写 AccessDenied；796 s 后过期；读端 Pod 自己没有凭证 |
| 撤下 | `withdrawn-orders`、`withdrawn-clicks`、`vended-credentials-outlive-withdrawal-until-expiry` | 删掉发布后 `loadTable` 404；之前签发的凭证仍能读，离过期还有 770 s |

## 删除流程（补验）

主键表、个人数据：个人数据不发布，但写入端照样在表目录里写 Iceberg 元数据，其中的 manifest 记着文件各列的最小值和最大值。`infra/m4/flows/iceberg/run-iceberg-erasure.sh` 在 EKS 上验收删除流程对这类表的处理（运行 e1，提交 `818bada`，rebase 到 main 之后是 `3dd9614`、只多了 #130 的文档索引；私有证据目录 `m4-34/run-e1-818bada/`；镜像：Operator `cove-operator@sha256:18fcae4d…`，作业 `cove-jobs@sha256:20ca4723…`（mysql）与 `cove-jobs@sha256:55d6aa63…`（sql））。MySQL 源把 40 条订单接入 ODS（L3 个人数据，主体键 `customer_id`，带 `metadata.iceberg.storage: table-location`），其中 3 条是客户 42 的；受理客户 42 的删除请求，源库删掉这些行。7 项检查全部通过：

| 检查 | 结果 |
| --- | --- |
| `iceberg-metadata-written` | 表目录 `metadata/` 下有 2 个元数据文件、3 个 manifest 与 manifest list |
| `erasure-counts-the-iceberg-manifests` | 删除流程的文件一项写明"…2 files its manifests list with the subject in their statistics, 1 in its Iceberg metadata; compacting"：Iceberg manifest 里有 1 个条目的统计值是客户 42 |
| `erasure-compaction-rewrites-the-iceberg-metadata` | 删除流程的全量压缩：`manifests compacted; Iceberg metadata written anew at snapshot 9` |
| `files-clean-iceberg-metadata-included` | 源库删除之后 379 s，文件一项变为"the files are clean"（快照 9），只等旧快照过期与孤儿文件清理。这是内容上的证据：删除检查逐个读表目录里的每个 Iceberg manifest、每个条目（删除的条目也算），没有一个的统计值是客户 42，文件一项才会变干净 |
| `no-iceberg-manifest-from-before-the-erasure` | 旁证：以运行者自己的身份列 S3，表目录里的 2 个 manifest 与 manifest list 都写于压缩开始之后，压缩之前的 0 个（只看写入时间，不看内容） |
| `erasure-logical` | 受理后 6 分 14 秒 `LogicallyErased` |

## 写入端自己压缩的分层表（补验）

ODS 表的写入端不压缩，主键表要靠全量压缩的 `LakeTask` 才看得到改动；分层表（`LakeTable`）的写入端自己压缩（lookup changelog），Operator 给写 Iceberg 元数据的这类表设 `compaction.optimization-interval`（1 小时），让写入端在有新写入时至少每小时做一次全量压缩（LLD "治理与个人数据删除"的表选项）。`infra/m4/flows/iceberg/run-iceberg-layered.sh` 在 EKS 上验收这条路径（运行 y2，提交 `e2e9712`，私有证据目录 `m4-34/run-y2-e2e9712/`；镜像：Operator `cove-operator@sha256:ccfad3a2…`，作业 `cove-jobs@sha256:3af74489…`（mysql）与 `cove-jobs@sha256:42419984…`（sql））：MySQL 源把 60 条订单接入 ODS，`LakeTable` 由它们做出 DWD 的 `orders`（带 `metadata.iceberg.storage: table-location`），发布给读端；每 4 分钟改一行，像在用的源一样让写入端一直在写；整个过程没有任何压缩任务。10 项检查全部通过：

| 检查 | 结果 |
| --- | --- |
| `writer-gets-the-interval` | DWD 表的选项里有 `compaction.optimization-interval = 1 h` |
| `published-by-the-writer`、`iceberg-is-the-writers-compacted-rows` | 写入端提交第一批数据之后，它自己的压缩（快照 3）就把这些行放进了最高层，提交后 7 s 发布；Spark 以 Iceberg 读到的 60 行等于 Paimon 的 `$ro` |
| `changes-visible-by-the-writers-compaction` | MySQL 把 10 条订单改为 `paid`，664 s 后读端看到它们（每 300 s 查一次）：写入端在改动后约 9 分钟的压缩（快照 16）把改动并入了最高层 |
| `iceberg-is-the-writers-compacted-rows-after-changes`、`iceberg-has-the-paid-orders`、`ro-has-the-paid-orders` | 之后 Iceberg 仍等于 `$ro`，两边都有这 10 条 `paid` |
| `no-compaction-task-needed` | 发布从快照 3 到快照 16，没有任何 `LakeTask` 压缩这张表 |

这次可见性由写入端平常的压缩带来（一次每 4 分钟写一行的运行），比 1 小时的上限早得多；1 小时的全量压缩是没有别的压缩时的保底，这次没有等到它触发。表上没有写入时，写入端不压缩，最后一次改动要等下一次写入才会进入最高层。y1（`325f38d`）先跑过一次，结果相同（12 s 发布、663 s 可见）；评审之后改了检查的名字、在两边断言 `paid` 的行数、补上退出时的清理，于是重跑了 y2。

## 不支持项

| 项 | 行为 | 怎样核对的 |
| --- | --- | --- |
| 在 Iceberg 侧写入、删表、建表、加列、改名 | 拒绝（403，或 S3 拒绝写） | EKS（`reader-cannot-*`） |
| deletion vector 表 | 不发布（`DeletionVectors`） | 单元测试（`LakeIcebergPublicationReconcilerTest`） |
| 未复核的新列 | 表撤下，直到写进 `columns`（`ColumnsUnreviewed`） | EKS（`unreviewed-column-*`）与单元测试 |
| 主键表在第一次全量压缩之前 | 不发布（`MetadataIncomplete`） | EKS 与单元测试（`LakeReaderTest`） |
| manifest 压缩之后的元数据 | 不完整时保持已发布的版本（`MetadataIncomplete`），下一次全量压缩之后恢复 | 单元测试（`LakeReaderTest`、`TaskJobTest`） |
| 分支、Paimon 回滚 | 只发布主分支；回滚留下的过时元数据按"文件里的当前快照等于 Paimon 最新快照"核对后才发布（`MetadataMissing`） | 设计；没有测试 |
| Glue、Athena、第二朵云 | 未验证 | — |

## 过程中的发现

- i1：一次全量压缩之后，主键表的 Iceberg 视图从 100 行变成 0 行，六项检查失败。原因是平台的全量压缩紧接着压缩 manifest，而 manifest 压缩提交快照时不跑提交回调，那个快照没有 Iceberg 元数据；Paimon 下一次提交时从头重建，只取不需合并的 split，有未压缩改动的 bucket 整个缺失（`LakeReaderTest` 复现）。改为全量压缩默认不压缩写 Iceberg 元数据的表的 manifest；发布者只在元数据的数据文件恰好是快照要展示的文件时发布，否则保持已发布的版本（`MetadataIncomplete`）。六项中有一项是 harness 的：`spark-sql.sh` 把一行 WARN 日志当成了错误信息。
- i2：45 项通过，4 项是 harness 的预期不对：表在第一次全量压缩之前没有发布，自然没有给读端授权；Spark 3.5 的错误类（`[TABLE_OR_VIEW_NOT_FOUND] …`）里没有 "Exception"，错误信息被抓成了空串。改为按错误类与异常抓取，并把错误输出的末尾一并留下。
- 评审（第一轮）：源端自动加的列会直接到达读端（HLD 12.5），改为发布声明列出复核过的列（`columns`），表里有没列出的列就撤下；`spec.table` 改为不可变；UPDATE 返回 404 原先被当作成功，改为报错，目录丢了登记会被发现并重新登记；删除流程对写 Iceberg 元数据的表永远等不到 manifest 统计值清零（压缩任务跳过了 manifest 压缩），改为删除流程的压缩照样压缩 manifest。
- i3：63 项通过，两项是读端的语句没到 Polaris 就被 Spark 拒了：`INSERT` 漏了运行中加的列，`RENAME` 的目标名写了两次 catalog。改正后 i4 64 项全部通过（`23495c1`）。
- 评审（第二轮）：删除流程压缩 manifest 之后，旧的 Iceberg manifest 要到下一次提交才删掉，空闲的表会一直留着主体的统计值；改为压缩之后做一次空提交，立即重写 Iceberg 元数据，删除检查也数 Iceberg manifest 里的主体统计值（`icebergMentions`）。目录丢登记时如果最新快照发布不了，状态原先仍说"已发布"，改为如实报告。验收脚本里撤下后凭证失效时的失败路径会中止整个脚本，已修正。
- 安装期间的实验（见调研）：`STORAGE_CREDENTIAL_DURATION_SECONDS=900` 而缓存时长取默认时，通知返回 400，缓存设为 300 后正常；Polaris 1.8.0 的 chart 默认带一个 LoadBalancer 附加服务，安装时置空并检查渲染结果。

## 限制

- 门禁管的是目录里的登记，不是数据：签发的凭证能读整张表的目录，包括尚未发布的 Paimon 文件；撤下之前签发的凭证在有效期（最长 15 分钟）内仍能读。所以受限数据（高于 L2、个人数据）根本不发布，而不是靠撤下。
- 主键表只有 full-compaction 模式：读端看到的是最近一次全量压缩后的数据，可见性取决于全量压缩的间隔。ODS 表要有人定期跑全量压缩的 `LakeTask`；写入端自己压缩的分层表不用（见上一节：一次每 4 分钟写一行的运行里，改动约 11 分钟可见），但 1 小时的保底这次没有触发，它的上限没有实测。
- 列复核是整表的：Iceberg 读端拿到的是整张表，没有列级或行级的保护（见 LLD "列标签、脱敏与行过滤"的支持矩阵）。
- 删除流程对写 Iceberg 元数据的表的处理在 EKS 上验收到"文件已干净、逻辑删除完成"（见"删除流程（补验）"）；物理删除还要等旧快照过期和一天后的孤儿文件清理，没有等到。
- 未复核的新列：撤下发生在 Operator 的下一轮（30 秒内）；在那之前写进表目录的文件，持有未过期签发凭证的已授权读端可以直接从 S3 读到。要没有这个窗口，发布显式列出列的分层表（LLD "开放层"的"发布的声明"）。
- 只验收了 Spark 3.5.8 + Iceberg 1.10.2 这一个读端；Trino 由 #36 验收，Glue、Athena 与第二朵云（#33 未开展）未验证。

> 注：本文中的 issue 与 PR 编号（如 #34）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
