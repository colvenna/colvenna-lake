---
title: "开放层：Iceberg 兼容发布的路径与目录选型"
date: 2026-10-17
status: decided
issue: 34
---

# 开放层：Iceberg 兼容发布的路径与目录选型

本文记录 #34 开工前的调研结论，决定见 [colvenna-operator LLD](../design/colvenna-operator-lld.md) 的"开放层"一节。Paimon 的结论以 2.0.0 发布源码（tag `release-2.0.0`）为准，Polaris 以 `apache-polaris-1.8.0` 为准。标为"实测"的结论来自 2026-10-17 在 PoC 集群上的手工实验，其余来自源码和文档。

## 结论

1. **写入端只写元数据，不碰目录。** 发布的表设 `metadata.iceberg.storage=table-location`：每次 Paimon 提交，写入端在表目录的 `metadata/` 下写 `v<快照号>.metadata.json`，快照号与 Paimon 相同，`location` 就是 Paimon 表目录。这一步不需要 Iceberg 的 jar，也不需要目录凭证，写的文件在 colvenna-catalog 按表签发的凭证范围内（实测）。
2. **不用 Paimon 自带的 `rest-catalog` 发布。** 它在快照提交成功后同步调用 REST 目录（`FileStoreCommitImpl.java:1239-1247`）；目录出错时异常抛给 Flink，作业重启（apache/paimon#8875，未修），所以目录宕机等于摄入停滞。每个会提交这张表的进程（写入作业、维护任务）都要带 iceberg 依赖和目录凭证，凭证若写进表选项还会持久化在 schema 文件里。2.0.0 的重试路径还会让目录删表重建（apache/paimon#9036，2.1.0 修复）。平台也无法选择发布哪个快照。
3. **只稳妥支持 Iceberg v2。** 2.0.0 写出的 v3 元数据缺 `next-row-id`、`first-row-id`，Iceberg 1.9 起拒绝解析（apache/paimon#8972）。所以 deletion vector 表不发布；主键表用 v2：Iceberg 只看到最高层的文件，即全量压缩之后的数据，等于 Paimon 的 `$ro`（`IcebergCommitCallback.java:902-912`，`docs/docs/iceberg/primary-key-table.mdx:113-147`）。append 表每次提交都完整可见。
4. **默认值要改。** `metadata.iceberg.previous-versions-max` 默认 0，每次提交都删掉旧的元数据文件；目录每次 `loadTable` 都重读它登记的那一份，所以发布的表要保留一些旧版本（平台默认 30）。
5. **目录选 Apache Polaris 1.8.0 的 EXTERNAL catalog。** 它是只读的外观（static facade）：Iceberg 客户端的建表、提交、改名、purge 在代码层被拒绝（`IcebergCatalogHandler.java` 437、1344、1416、1457 行，400），表只能经通知接口（`POST .../tables/{t}/notifications`，CREATE/UPDATE/DROP）登记；通知带单调递增的时间戳，不比上次新的返回 409，所以晚到的旧通知盖不掉新的（`LocalIcebergCatalog.java:3081-3167`）。DROP 只删登记，不删文件。Polaris 用存储配置里的角色 AssumeRole，自己读元数据文件，并按表的 `location` 给读端签发凭证；有效期由 `STORAGE_CREDENTIAL_DURATION_SECONDS` 决定，凭证缓存时长必须更短。
6. **不评估 Lakekeeper 与 Gravitino。** 选 Polaris 的依据是第 5 条：只读由目录的代码保证，不靠授权配置，DROP 不删文件，这些都在 1.8.0 源码和实测里核实过。另两个目录没有做同等的核实，M4 不用它们；要换目录，先按第 5 条逐项核实。
7. **读端验收用 Spark 3.5.8 + Iceberg 1.10.2**（REST、OAuth2 客户端凭证、vended credentials，实测）。Trino 读这些表留给 #36，由它的调研核实版本；其他 Iceberg 读端（如 PyIceberg）未评估。平台不发布 deletion vector 表（第 3 条），读端不需要读 DV。

## 实测（2026-10-17，PoC 集群）

| 检查 | 结果 |
| --- | --- |
| Paimon 写入（Spark，REST catalog，按表凭证）带 `table-location` | 表目录下出现 `metadata/v1.metadata.json`、`v2.metadata.json`，format-version 2，快照 1、2，`location` 为 Paimon 表目录 |
| Operator 身份发 UPDATE 通知 | 204；同一时间戳再发 409 |
| 给 catalog role 授表级 `TABLE_READ_DATA` | 201 |
| 读端（Spark，Iceberg REST，vended credentials）读表 | 与 Paimon 相同的 3 行，快照号 1、2 |
| 读端 `INSERT` | 写数据文件时 S3 拒绝：签发的角色没有 `s3:PutObject` |
| 读端 `DROP TABLE` | 403：读端没有 `TABLE_DROP` |
| 不带 token 访问 | 401 |
| `STORAGE_CREDENTIAL_DURATION_SECONDS=900` 而缓存时长取默认 | 通知返回 400："STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS should be less than STORAGE_CREDENTIAL_DURATION_SECONDS"；缓存设为 300 后正常 |
| Polaris 1.8.0 chart 默认值 | 带一个 LoadBalancer 类型的附加服务（8183 端口），安装时置空 |

## 边界

- 门禁管的是目录里的登记，不是数据：签发的凭证能读整张表的目录（包括尚未发布的 Paimon 文件），撤下前拿到的凭证在有效期内仍能用。所以受限数据（高于 L2、个人数据）不发布，而不是靠撤下。
- Polaris 有并发更新的已知问题（apache/polaris#4658、#5621），每张表只由一个发布者串行通知。
- 2.0.0 的回滚会留下内容过时、文件名正确的元数据文件；发布者按"文件里的当前快照等于 Paimon 的最新快照"核对后才发布。
- 2.0.0 以前一份元数据为底生成下一份（`IcebergCommitCallback.createMetadataWithBase`）。前一份不在时从头重建（`createMetadataWithoutBase`），只取不需合并的 split（`DataSplit::rawConvertible`），不按层过滤：主键表的第一份元数据含有尚未压缩的 level 0 文件，而有未压缩改动的 bucket 在重建时整个缺失。manifest 压缩（`FileStoreCommitImpl.compactManifest`）提交快照时不跑提交回调，正会造成这种重建（EKS 首轮实测：视图从 100 行变成 0 行）。所以发布者核对元数据持有的文件恰好是快照的最高层文件，平台的全量压缩对这类表默认不压缩 manifest。从头重建时，Paimon 删掉所有更早的 Iceberg manifest 与 manifest list（`expireAllBefore`），只留 `v<N>.metadata.json`（其中没有文件级的统计值）：删除流程的压缩正是利用这一点，照样压缩 manifest（`TaskJobTest` 复现）。

> 注：本文中的 issue 与 PR 编号（如 #34）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
