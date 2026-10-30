---
title: "已有 Trino 读湖的路径与边界"
date: 2026-10-17
status: decided
issue: 36
---

# 已有 Trino 读湖的路径与边界

本文记录 #36 开工前的调研结论，验收见 [M4 已有 Trino 的接入边界](../history/m4/trino.md)。Trino 的结论以 484（2026-10-15 发布，`trinodb/trino` tag `484`）的源码和文档为准，Paimon 以 2.0.0 发布源码为准，`apache/paimon-trino` 以 `main@077403be`（2026-03-17）为准。

## 结论

1. **原生 Paimon 连接器不可用。**
   - `apache/paimon-trino` 没有 Release，也没有 tag（GitHub API 的 [releases](https://api.github.com/repos/apache/paimon-trino/releases) 与 [tags](https://api.github.com/repos/apache/paimon-trino/tags) 都为空，2026-10-17 核对）；Maven Central 没有制品（[`paimon-trino-476/maven-metadata.xml`](https://repo1.maven.org/maven2/org/apache/paimon/paimon-trino-476/maven-metadata.xml) 返回 404，验收检查 `paimon-trino-unreleased` 每次运行都核对），只有 Apache snapshots 仓库里的夜间快照（[maven-metadata](https://repository.apache.org/content/repositories/snapshots/org/apache/paimon/paimon-trino-476/1.3-SNAPSHOT/maven-metadata.xml)）。ASF 发布政策规定，快照"are not intended for use by the general public"（[release policy](https://www.apache.org/legal/release-policy.html#release-types)）。
   - 它内置的读取器是 Paimon 1.3.1（[`pom.xml`](https://github.com/apache/paimon-trino/blob/077403be7d3b289ef6c2ac34c0df2f24697ce79a/pom.xml#L40-L43) 中 `paimon.version=1.3.1`、`trino.version=476`），没有面向 Paimon 2.0 的分支或 PR；最新的未合并 PR [#127](https://github.com/apache/paimon-trino/pull/127) 也只升到 Paimon 1.4.2。
   - 它把 Trino 自己的文件系统设为 preferIO，并忽略 REST catalog 签发的按表 data token（[`TrinoCatalog.java:84-97`](https://github.com/apache/paimon-trino/blob/077403be7d3b289ef6c2ac34c0df2f24697ce79a/src/main/java/org/apache/paimon/trino/catalog/TrinoCatalog.java#L84-L97)）。所以它读 S3 用的是 Trino 自己的身份，违背 HLD 12.2"按表短期凭证"的边界。
   - Trino 主仓库也没有 Paimon 连接器：[#26634](https://github.com/trinodb/trino/pull/26634) 和 [#30913](https://github.com/trinodb/trino/pull/30913) 都因长期无活动被关闭，484 的 `plugin/` 下没有它。
2. **可用的路径是 Trino 的 Iceberg 连接器读开放层。** #34 已把 Paimon 表以 Iceberg 元数据发布到 Polaris 1.8.0 的 EXTERNAL catalog（[开放层调研](iceberg-open-layer.md)）。验收只用 Trino 484，它这一侧要的能力都在发布版的文档里：
   - REST catalog、OAuth2 客户端凭证（`iceberg.rest-catalog.oauth2.credential`）与 vended credentials（`iceberg.rest-catalog.vended-credentials-enabled`）（[metastores](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/object-storage/metastores.md)，"REST catalog"一节）；
   - 开启 vended credentials 时必须启用原生 S3 文件系统（`fs.s3.enabled=true`，[release-484](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/release/release-484.md)）。
   - #34 只发布 Iceberg v2 表，不涉及 Iceberg v3。更早的 Trino 版本未验证。
3. **Trino 自己的边界要单独验收，不继承 Doris 的结论**（HLD 12.2）。
   - 未配置访问控制时，Trino 默认放行一切（[built-in-system-access-control](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/security/built-in-system-access-control.md)）；`/v1/info` 等端点始终匿名（[file-system-access-control](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/security/file-system-access-control.md)）。
   - Iceberg REST catalog 以一个 catalog 级的凭证访问 Polaris（`iceberg.rest-catalog.oauth2.credential` 是 catalog 的配置项，[metastores](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/object-storage/metastores.md)），所以 Trino 的每个用户都拿到这个 principal 的权限；平台不能把它的授权逐用户继承到 Trino（验收检查 `trino-users-share-the-principal`）。
   - 签发的凭证只是平台这一侧的边界。用户的 Trino 若另有能读仓库的 AWS 身份（节点角色、IRSA），它不经 Polaris 也能读文件；HLD 12.2 要求"Pod 与查询引擎不能另有宽 S3 角色"，这一点由接入的用户负责，平台验收的是替身 Trino 没有自己的身份时读不到数据。
   - 容错执行的连接器列表里有 Iceberg，没有 Paimon（[fault-tolerant-execution](https://github.com/trinodb/trino/blob/484/docs/src/main/sphinx/admin/fault-tolerant-execution.md)）；#36 不验证容错执行。

## 支持矩阵

| 组合 | 状态 | 依据 |
| --- | --- | --- |
| Trino 484 + Iceberg 连接器 + Polaris 1.8.0 读 Paimon 2.0.0 写入的 append 表（#34 发布，Iceberg v2，OAuth2，vended credentials）：读、time travel、元数据表 | 支持，已验收 | [验收](../history/m4/trino.md) |
| 同一路径读 #34 发布的主键表（全量压缩后的数据） | 支持，已验收 | [验收](../history/m4/trino.md) |
| 其他 Trino 版本、Starburst | 未验证 | — |
| `apache/paimon-trino`（任何版本）或 Trino 内置 Paimon 连接器 | 不支持 | 无发布版、不支持 Paimon 2.0、丢弃按表凭证 |
| 未发布到开放层的表（L3 及以上、个人数据、deletion vector 表、有未复核列的表） | 不支持 | #34 不发布它们 |
| Trino 写入、DDL | 不支持 | 写数据文件时 S3 拒绝（签发的凭证只读），删表与建表 Polaris 拒绝（catalog role 只有读权限） |
| 按 Trino 用户继承平台授权 | 不支持 | 授权以 catalog principal 为单位 |
| 从 Trino 导出到用户的其他 catalog | 平台不管 | 读进 Trino 之后的数据归用户的 Trino 管；平台只保证发出去的是 L2 及以下、已复核的表 |
| Trino 容错执行 | 未验证 | — |
| 第二朵云 | 未验证 | #33 未开展 |
| 默认安装 | 不含 Trino | HLD 10.2；#36 不改产品代码与 chart |

> 注：本文中的 issue 与 PR 编号（如 #36）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
