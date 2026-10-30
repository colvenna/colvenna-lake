---
title: "M1 Issue #14：Doris 正式版 Paimon 2.x reader 升级核验"
date: 2026-09-30
status: source-and-official-image-audited-live-not-run
project-base: "5f6ab1654f4127cfafc5bbf3ab90901de30c4d7b"
---

# M1 Issue #14：Doris 正式版 Paimon 2.x reader 升级核验

## 结论与证据边界

**截至 2026-09-30，未核实到一个正式发布的 Apache Doris 版本同时在 FE 与 BE JNI 中打包 Paimon 2.x reader，并证明能经 JDBC catalog 正确读取 Paimon 2.x deletion-vector（DV）表的未压实 level-0（L0）merge-on-read。** Paimon `2.0.0` 于 2026-08-07 发布；其后 Doris 官方发布流中的正式版 `4.0.8`、`4.1.4`、`4.1.4.1` 在发布标签源码里分别固定 Paimon `1.1.1`、`1.4.2`、`1.4.2`。下文还检查了较早的 `3.0.6.2`、`4.0.4-release` 至 `4.0.7`、`4.1.0` 至 `4.1.3`，没有发现 2.x 构建依赖。[Paimon 2.0.0 release][p-release]、[Doris 官方 release 列表][d-releases]及[逐标签源码矩阵](#发布标签源码矩阵)。`rc`、master 和未发布分支均不计为正式版。

这个结论分三层：**源码**有 JDBC catalog、通用 DV 文件处理和 FE/JNI 分流，但其正式版 Paimon 依赖仍是 1.x；**构件**核对的官方 `4.1.4` FE 与 BE `linux/amd64` 镜像也含 1.4.2 reader，Docker Hub 官方仓库中没有 `4.1.4.1` 对应的 FE/BE 标签；**现场**本次没有启动 Doris、连接 catalog 或执行查询，不能把版本或镜像检查报成兼容性 PASS，也不把已有 #14 现场 RED 外推为 `4.1.4.1` 的实测结果。[4.1.4 FE 镜像][fe-image]、[BE 镜像][be-image]、[4.1.4.1 FE 标签检查][fe-image-4141]、[BE 标签检查][be-image-4141]、[#14 既有实测](doris-paimon.md)。

## 发布标签源码矩阵

以下 commit 为 `git ls-remote --tags https://github.com/apache/doris.git` 对正式 tag 的解引用值；Paimon 版本取各 commit 的 `fe/pom.xml` 中 `paimon.version`，不是当前分支或滚动文档的值。版本号链接指向 Apache Doris 官方 release，commit 链接固定到相应源码。[Doris release 列表][d-releases]。

| Doris 正式 tag | tag 指向 commit / FE 源码 | BE JNI POM | `paimon.version` |
| --- | --- | --- | --- |
| [3.0.6.2](https://github.com/apache/doris/releases/tag/3.0.6.2) | [`910c4249c521b5e68453336bf8859acd1c0c3a24`][fe-3062] | [scanner][be-3062] | 1.0.1 |
| [4.0.4-release](https://github.com/apache/doris/releases/tag/4.0.4-release) | [`d74dc1fb93680953d598fa118f499aa6e528e578`][fe-404] | [scanner][be-404] | 1.1.1 |
| [4.0.5](https://github.com/apache/doris/releases/tag/4.0.5) | [`59de8c4c524008e8ab2e43b79312f716a3a423a8`][fe-405] | [scanner][be-405] | 1.1.1 |
| [4.0.6](https://github.com/apache/doris/releases/tag/4.0.6) | [`1663f25c16ffab04c425d5cf2a686fe99d4ade81`][fe-406] | [scanner][be-406] | 1.1.1 |
| [4.0.7](https://github.com/apache/doris/releases/tag/4.0.7) | [`35854e7e92a80f79cbfd1ef8070edfa926847825`][fe-407] | [scanner][be-407] | 1.1.1 |
| [4.0.8](https://github.com/apache/doris/releases/tag/4.0.8) | [`bc8ea1bac6d62d92cdebd2e3ac33cc668961ec36`][fe-408] | [scanner][be-408] | 1.1.1 |
| [4.1.0](https://github.com/apache/doris/releases/tag/4.1.0) | [`5960d4cea0e6d2a08f62b45a07a662395d2bbcfc`][fe-410] | [scanner][be-410] | 1.3.1 |
| [4.1.1](https://github.com/apache/doris/releases/tag/4.1.1) | [`b10073ad9ca17cd5685c4dd3b3ef650f256376d0`][fe-411] | [scanner][be-411] | 1.3.1 |
| [4.1.2](https://github.com/apache/doris/releases/tag/4.1.2) | [`aec169d20256a788de448f46737b5fb53ee3b4e3`][fe-412] | [scanner][be-412] | 1.3.1 |
| [4.1.3](https://github.com/apache/doris/releases/tag/4.1.3) | [`7126cf65d96ebc43fce0906f51e92c1a2ccf24a6`][fe-413] | [scanner][be-413] | 1.3.1 |
| [4.1.4](https://github.com/apache/doris/releases/tag/4.1.4) | [`ad35a140c7fd0b842f18c23300bac581f7d04326`][fe-414] | [connector][be-414] | 1.4.2 |
| [4.1.4.1](https://github.com/apache/doris/releases/tag/4.1.4.1)（2026-09-29） | [`5dbec55b399c2c4323f565bb2c6745cacb574416`][fe-4141] | [connector][be-4141] | 1.4.2 |

BE JNI 的构建依赖同一 `fe/pom.xml` 的 Paimon dependency management：至 `4.1.3` 的模块为 `fe/be-java-extensions/paimon-scanner/pom.xml`，`4.1.4`、`4.1.4.1` 为 `fe/be-java-extensions/paimon-connector/pom.xml`；检查的这些模块都声明 `paimon-core` 且不另设 `paimon.version`。因此 `4.1.4.1` 的 **FE 与 BE JNI 源码构建**都仍指向 1.4.2，而不只是 FE 的单侧锁定。[4.0.8 scanner POM][be-408]、[4.1.3 scanner POM][be-413]、[4.1.4 connector POM][be-414]、[4.1.4.1 connector POM][be-4141]、[4.1.4.1 统一版本及 dependency management][fe-4141]。这属于构建声明；下节单独核对已发布镜像中的字节。

## 官方镜像构件核验（只读）

2026-09-30 从 Apache Doris 的 Docker Hub 官方仓库解析 `fe-4.1.4`、`be-4.1.4`：`linux/amd64` manifest 分别为 `sha256:71b5422dfcc204f9adf36298a0457846d186aba5d1d3353e54546764f54f2e47`、`sha256:3273e3f66538f75cd6fb5716f6d48eef18c9f5e04daafdfea6ecd561b47f3c8`。从 registry config 取得的 12 个 FE / 13 个 BE 基础层 `rootfs.diff_ids`，与本机已缓存的项目派生镜像的相同前缀逐一一致；随后仅通过 `docker image save` 标准输出读取**这些与官方镜像相同的基础层**，没有启动容器、拉取新镜像或写入证据文件。官方镜像的包层 digest 分别为 FE `sha256:b9d706a464cca7ef9931eee5c153a045c683b2bc83b4e12a631adfa13bb8b046`、BE `sha256:4c883152b068550dba26286a43204bc152949c934a15520a994af79f9b9f97c3`。[官方 FE 标签][fe-image]、[FE amd64 manifest][fe-manifest]、[官方 BE 标签][be-image]、[BE amd64 manifest][be-manifest]。

| 官方 `4.1.4` 构件 | 读取到的发布层内容 | 构件结论 |
| --- | --- | --- |
| FE | `/opt/apache-doris/fe/lib/` 含 `paimon-api-1.4.2.jar`、`paimon-core-1.4.2.jar`、`paimon-common-1.4.2.jar`、`paimon-format-1.4.2.jar`、`paimon-s3-1.4.2.jar` 等；`paimon-api-1.4.2.jar` 的 SHA-256 为 `7c44e1767d372d0ff24f72ad9d326949dea2590d5e0b2eb6fafd88806d5645c7`，内含 Maven `version=1.4.2`。[FE 镜像][fe-image]、[FE 依赖源码][fe-414] | 已核对的官方 FE 包含 1.4.2，而非 2.x。 |
| BE JNI | `/opt/apache-doris/be/lib/java_extensions/paimon-connector/paimon-connector-jar-with-dependencies.jar` 的 SHA-256 为 `7e55edb47dd7f1c618a015f7755f4a628c052d01ee3e122af404a5efff1988ce`；包内 `META-INF/maven/org.apache.paimon/{paimon-api,paimon-core,paimon-bundle,...}/pom.properties` 均记录 `version=1.4.2`，且 `CoreOptions.class` 不含 `deletion-vectors.merge-on-read` 字符串。[BE 镜像][be-image]、[BE assembly 配置][be-assembly]、[BE 构建依赖][be-414] | 已核对的官方 BE JNI 包含 1.4.2 reader，而非 2.x。 |

另一条在 Paimon 2.0.0 发布后更新的 Doris 正式线 `4.0.8`，Docker Hub 也有 `fe-4.0.8` 与 `be-4.0.8` 官方标签；其 `linux/amd64` manifest 分别为 `sha256:0994b0e017ca024116e9c721b74fbc7293b1edc18ada988cdb1abdfed60cfa2f` 与 `sha256:b56c70cbe32d4ccdea4a166ddf0ed55a38e82ce4eac5a8aceb80480c946ebf76`。本次只解析了这些镜像的标签与 manifest，**没有读取 `4.0.8` 镜像内的 JAR 字节**；它的正式 FE/BE 源码构建锁仍为 Paimon 1.1.1。[4.0.8 FE 镜像][fe-image-408]、[BE 镜像][be-image-408]、[FE POM][fe-408]、[BE POM][be-408]。

`4.1.4.1` 是正式源码 release，但本次查询 Docker Hub 官方仓库的精确标签 `fe-4.1.4.1`、`be-4.1.4.1` 均为 HTTP 404；不能把 `4.1.4` 镜像当成其发布构件，也不能从缺失的 Docker Hub 标签推断 `4.1.4.1` 其他二进制分发包的内容。其源码 POM 仍为 1.4.2。[4.1.4.1 release][d-release-4141]、[FE 标签检查][fe-image-4141]、[BE 标签检查][be-image-4141]、[固定源码][fe-4141]。

## JDBC、DV 与 L0：三个不同命题

1. **JDBC catalog 的入口已发布。** Doris 4.x 版本文档把 Paimon JDBC catalog 标为自 `4.1.0` 起的实验性功能，提供 PostgreSQL + S3 示例；`4.1.4.1` 的 `PaimonJdbcMetaStoreProperties` 解析 URI、用户、驱动并调用 Paimon `CatalogFactory`。Paimon 2.0.0 也正式文档化 JDBC catalog。它们证明目录接入代码与配置存在，未证明跨版本 DV/L0 行集正确。[Doris JDBC 文档][d-jdbc-doc]、[Doris JDBC 源码][d-jdbc]、[Paimon 2.0.0 JDBC 文档][p-jdbc]。
2. **普通 DV 读取支持不等于未压实 L0 可见。** Doris 4.x 文档写明支持读取 Paimon DV；正式 `4.1.4.1` 的 FE 扫描代码可把 deletion file 信息传给 BE，并在 native 与 JNI reader 之间分流；JNI `PaimonJniScanner` 使用 Paimon `ReadBuilder` 读取 split。以上属于通用 DV 与执行入口，不是 Paimon 2.x L0 merge-on-read 的兼容承诺。[Doris DV 文档][d-doc]、[FE 分流源码][d-scan]、[BE JNI 源码][d-jni]。
3. **所需的 L0 语义从 Paimon 2.0.0 才有明确开关。** 2.0.0 的 `deletion-vectors.merge-on-read=true` 使 DV 表 batch scan 不再跳过未压实 L0；默认值为 `false`，且官方配置文档限定它改变的是 **batch scan** 的 L0 可见性，不改变 streaming scan 或 changelog。Doris `4.1.4.1` 所锁的 Paimon `1.4.2` 的 `batchScanSkipLevel0()` 对启用 DV 的表直接返回 true，普通 `DataTableBatchScan` 因而过滤 `level > 0`；Doris `4.1.4.1` 自己的直接 snapshot 读取路径还显式沿用这个过滤判断。版本化文档中的“supports reading Paimon Deletion Vector”不能覆盖这条源码差异。[2.0.0 开关源码][p20-options]、[2.0.0 配置文档][p20-doc]、[1.4.2 选项源码][p14-options]、[1.4.2 batch scan][p14-batch]、[Doris 直接扫描过滤][d-filter]。

## 现场状态与剩余未知

后续已有独立的[配套 Paimon 2.0 自构建候选](paimon2-reader-candidate.md)：
目前仅本地编译与产物字节核验通过，镜像构建及现场 DV/L0 查询仍为
**NOT_RUN**。它不改变本文对正式发布版构件的结论。

本次仅做发布版源码、官方镜像和公开文档核验；**没有运行 JDBC catalog 连接、Paimon 写入、Doris 查询或 AWS 操作**。本仓库现有 [#14 现场记录](doris-paimon.md)报告 `4.1.4` 读取 `2.0.0` DV/L0 的 RED，以及部分维护后快照的有限正确结果；那是项目此前的实测，不是这次新跑的测试，也不是对 `4.1.4.1` 的现场结论。`4.1.4.1` 对目标 JDBC catalog、DV/L0 的 live 状态仍为 **NOT_RUN**，不能因补丁版 tag 存在而改写 #14 的 RED / `NOT_COMPLETE`。

- `4.1.4.1` 的官方 FE/BE 镜像标签当日不存在；其他二进制分发包的 JAR 清单未在本次核验。较早标签的官方镜像 payload 未逐个读取，但其已核对的正式源码 FE 构建依赖都早于 2.x。[官方标签检查][fe-image-4141]、[官方标签检查][be-image-4141]、[逐标签源码矩阵](#发布标签源码矩阵)。
- `4.1.4.1` 在同一 PostgreSQL JDBC catalog、Paimon 2.0.0 的 `deletion-vectors.enabled=true` + `deletion-vectors.merge-on-read=true` 表上，初始 L0、更新/删除后 L0、维护后真实 DV 文件、固定旧快照及 native/JNI 分流的逐行结果和性能仍未知；应以固定镜像 digest、同一 snapshot 的 Paimon 2.0.0 原生行集逐项核对，不能只看查询成功或目录能列出表。[Paimon 2.0.0 L0 配置][p20-doc]、[Doris 扫描源码][d-scan]、[#14 对账记录](doris-paimon.md)。
- 若另行构建 2.x reader，FE 的 split/table 序列化与 BE JNI 的反序列化须作为**同一自构建组合**验证；这种组合不是上述任何官方发布镜像或标签的已验证升级。是否存在未来正式版与对应官方构件，须届时重新查发布页、FE/BE 依赖、镜像字节及现场对账。[FE split 序列化][d-scan]、[BE split 反序列化][d-jni-split]、[当前正式版依赖][fe-4141]。

<!-- 一手材料：源码链接固定到正式发布标签所指 commit；Docker Hub 链接指向 Apache Doris 官方镜像标签 API。 -->
[p-release]: https://github.com/apache/paimon/releases/tag/release-2.0.0
[d-releases]: https://github.com/apache/doris/releases
[d-release-4141]: https://github.com/apache/doris/releases/tag/4.1.4.1
[fe-3062]: https://github.com/apache/doris/blob/910c4249c521b5e68453336bf8859acd1c0c3a24/fe/pom.xml
[fe-404]: https://github.com/apache/doris/blob/d74dc1fb93680953d598fa118f499aa6e528e578/fe/pom.xml
[fe-405]: https://github.com/apache/doris/blob/59de8c4c524008e8ab2e43b79312f716a3a423a8/fe/pom.xml
[fe-406]: https://github.com/apache/doris/blob/1663f25c16ffab04c425d5cf2a686fe99d4ade81/fe/pom.xml
[fe-407]: https://github.com/apache/doris/blob/35854e7e92a80f79cbfd1ef8070edfa926847825/fe/pom.xml
[fe-408]: https://github.com/apache/doris/blob/bc8ea1bac6d62d92cdebd2e3ac33cc668961ec36/fe/pom.xml
[fe-410]: https://github.com/apache/doris/blob/5960d4cea0e6d2a08f62b45a07a662395d2bbcfc/fe/pom.xml
[fe-411]: https://github.com/apache/doris/blob/b10073ad9ca17cd5685c4dd3b3ef650f256376d0/fe/pom.xml
[fe-412]: https://github.com/apache/doris/blob/aec169d20256a788de448f46737b5fb53ee3b4e3/fe/pom.xml
[fe-413]: https://github.com/apache/doris/blob/7126cf65d96ebc43fce0906f51e92c1a2ccf24a6/fe/pom.xml
[fe-414]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/pom.xml#L390-L391
[fe-4141]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/pom.xml#L390-L391
[be-3062]: https://github.com/apache/doris/blob/910c4249c521b5e68453336bf8859acd1c0c3a24/fe/be-java-extensions/paimon-scanner/pom.xml
[be-404]: https://github.com/apache/doris/blob/d74dc1fb93680953d598fa118f499aa6e528e578/fe/be-java-extensions/paimon-scanner/pom.xml
[be-405]: https://github.com/apache/doris/blob/59de8c4c524008e8ab2e43b79312f716a3a423a8/fe/be-java-extensions/paimon-scanner/pom.xml
[be-406]: https://github.com/apache/doris/blob/1663f25c16ffab04c425d5cf2a686fe99d4ade81/fe/be-java-extensions/paimon-scanner/pom.xml
[be-407]: https://github.com/apache/doris/blob/35854e7e92a80f79cbfd1ef8070edfa926847825/fe/be-java-extensions/paimon-scanner/pom.xml
[be-408]: https://github.com/apache/doris/blob/bc8ea1bac6d62d92cdebd2e3ac33cc668961ec36/fe/be-java-extensions/paimon-scanner/pom.xml
[be-410]: https://github.com/apache/doris/blob/5960d4cea0e6d2a08f62b45a07a662395d2bbcfc/fe/be-java-extensions/paimon-scanner/pom.xml
[be-411]: https://github.com/apache/doris/blob/b10073ad9ca17cd5685c4dd3b3ef650f256376d0/fe/be-java-extensions/paimon-scanner/pom.xml
[be-412]: https://github.com/apache/doris/blob/aec169d20256a788de448f46737b5fb53ee3b4e3/fe/be-java-extensions/paimon-scanner/pom.xml
[be-413]: https://github.com/apache/doris/blob/7126cf65d96ebc43fce0906f51e92c1a2ccf24a6/fe/be-java-extensions/paimon-scanner/pom.xml
[be-414]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/be-java-extensions/paimon-connector/pom.xml#L44-L74
[be-4141]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/be-java-extensions/paimon-connector/pom.xml#L44-L74
[be-assembly]: https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/be-java-extensions/paimon-connector/src/main/resources/package.xml#L23-L40
[fe-image]: https://hub.docker.com/v2/repositories/apache/doris/tags/fe-4.1.4
[be-image]: https://hub.docker.com/v2/repositories/apache/doris/tags/be-4.1.4
[fe-image-408]: https://hub.docker.com/v2/repositories/apache/doris/tags/fe-4.0.8
[be-image-408]: https://hub.docker.com/v2/repositories/apache/doris/tags/be-4.0.8
[fe-manifest]: https://registry-1.docker.io/v2/apache/doris/manifests/sha256:71b5422dfcc204f9adf36298a0457846d186aba5d1d3353e54546764f54f2e47
[be-manifest]: https://registry-1.docker.io/v2/apache/doris/manifests/sha256:3273e3f66538f75cd6fb5716f6d48eef18c9f5e04daafdfea6ecd561b47f3c8d
[fe-image-4141]: https://hub.docker.com/v2/repositories/apache/doris/tags/fe-4.1.4.1
[be-image-4141]: https://hub.docker.com/v2/repositories/apache/doris/tags/be-4.1.4.1
[d-doc]: https://github.com/apache/doris-website/blob/bf9892167d06541fac3cfcb28b4e0ff571653ebe/versioned_docs/version-4.x/lakehouse/catalogs/paimon-catalog.mdx#L300-L310
[d-jdbc-doc]: https://github.com/apache/doris-website/blob/bf9892167d06541fac3cfcb28b4e0ff571653ebe/versioned_docs/version-4.x/lakehouse/catalogs/paimon-catalog.mdx#L917-L942
[d-jdbc]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/fe-core/src/main/java/org/apache/doris/datasource/property/metastore/PaimonJdbcMetaStoreProperties.java#L55-L133
[d-scan]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/fe-core/src/main/java/org/apache/doris/datasource/paimon/source/PaimonScanNode.java#L406-L446
[d-jni]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/be-java-extensions/paimon-connector/src/main/java/org/apache/doris/paimon/PaimonJniScanner.java#L196-L244
[d-jni-split]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/be-java-extensions/paimon-connector/src/main/java/org/apache/doris/paimon/PaimonJniScanner.java#L345-L358
[d-filter]: https://github.com/apache/doris/blob/5dbec55b399c2c4323f565bb2c6745cacb574416/fe/fe-core/src/main/java/org/apache/doris/datasource/paimon/source/PaimonScanNode.java#L993-L1004
[p-jdbc]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/catalog.md#L78-L94
[p20-options]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java#L4178-L4187
[p20-doc]: https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/generated/core_configuration.html#L606-L615
[p14-options]: https://github.com/apache/paimon/blob/ac223f47d916f4c8e9c07fd1745db448e4e7d0d8/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java#L3540-L3550
[p14-batch]: https://github.com/apache/paimon/blob/ac223f47d916f4c8e9c07fd1745db448e4e7d0d8/paimon-core/src/main/java/org/apache/paimon/table/source/DataTableBatchScan.java#L57-L73
