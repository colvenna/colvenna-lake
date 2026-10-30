# 查询引擎镜像（基于 Apache Doris 4.1.4）

平台的查询引擎（看板服务与 BI 查询，`deploy/scripts/doris/install-doris.sh`）运行的是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 修改构建的镜像，配套自建的 Apache Paimon 2.0.0 读取器：Doris 4.1.4 内置的 Paimon 1.4.2 读取器读 Paimon 2.0.0 的 DV 表不正确（见[开发记录](../../../docs/history/m1/doris-paimon.md#结项判定)）。

这不是 Apache 软件基金会（ASF）发布的 Apache Doris，也没有得到 ASF 的认可。镜像改动了官方镜像（见下面的配方），所以用自己的名字分发：仓库是 `query-engine`（环境里 `colvenna-lake-<env>/query-engine`，发布仓库里 `colvenna-lake-release/query-engine`），发布清单的键是 `query-engine-fe`、`query-engine-be`。判断依据见[商标与命名](../../../release/trademarks.md)。目录名、脚本路径、`DORIS_*` 设置与 Kubernetes 资源名（`colvenna-doris`、`DorisCluster`）是内部名字，不改。

相对 Apache Doris 4.1.4 的官方 FE、BE 镜像，改动只有：

- FE 与 BE 自带的 Paimon 1.4.2 读取器（连同 Jindo 读取器）被换成 Paimon 2.0.0 的读取器：用 Apache Doris 源码（提交 `ad35a140`）里的几个源文件，对 Apache Paimon 2.0.0 的 jar 重新编译、合并而成；
- BE 自带的 `java-udf-jar-with-dependencies.jar` 被改写：去掉其中的 Paimon SDK 类，UDF 的接口不变（`prepare-reader.py` 的 `prepare_shared_udf`，`be.Dockerfile` 的 `COPY reader/be-udf/`）；
- 加入 PostgreSQL JDBC 驱动 42.7.8。

## 配方

| 文件 | 内容 |
| --- | --- |
| `fe.Dockerfile`、`be.Dockerfile` | 在 Apache Doris 4.1.4 的 FE、BE 官方镜像（按 digest 固定）上加入 PostgreSQL JDBC 驱动 42.7.8 与读取器 |
| `prepare-reader.py` | 准备读取器：取 Paimon 2.0.0 的 jar 与 Doris 的若干源文件，用 stock BE 镜像自带的三个连接器 jar 作编译类路径编译，再合并成 FE 与 BE 各自的读取器，写出逐个 jar 的 SHA-256 收据（`receipt.json`，随镜像放在 `/opt/apache-doris/paimon2-reader-receipt.json`） |
| `reader-lock.json` | 读取器的全部输入：Doris 源码提交、Paimon jar 与 Doris 源文件的 URL 和 SHA-256，以及 stock BE 连接器 jar 的 SHA-256 |

## 固定的镜像

安装运行的是两个固定 digest 的镜像（`deploy/scripts/lib/common.sh` 的 `DORIS_FE_DIGEST`、`DORIS_BE_DIGEST`）：

- FE：`sha256:8ef385893d63adae41f980d626dbf3818320a2fe008841bcca19a1f9b0c6104b`
- BE：`sha256:d98355172b05a3430665033dc47bb1fbe80e0510a7de994d09fc867a0d746645`

这两个 digest 是 2026-10-25 技术标识改名（#271）之后，在全新安装的环境上用 `build-images.sh` 按本配方重新构建的。与之前固定的镜像相比，读取器的比对只在 FE 里顶替 Paimon 1.4.2 SDK 的几个空 jar（`prepare-reader.py` 的 `OLD_FE_JARS`）上不同：它们的 `META-INF/MANIFEST.MF` 改写成了 `Colvenna-Lake-Removed-SDK`。其余的 jar 逐个相同，说明配方复现了之前验收过的读取器。新镜像在下一个候选（`v1.0.0-rc.3`）的完整验收里验证。

它们按本配方构建（开发阶段，tag `history/m1-m5` 下的 `infra/m1/issue14/build-images.sh`）。安装的 `doris-image` 步骤（`deploy/scripts/doris/copy-images.sh`）用 crane 把它们按原 digest 复制进环境自己的仓库 `colvenna-lake-<env>/query-engine`，tag 是 `<DORIS_TAG>-fe-<digest 的前 12 位十六进制>` 与 `<DORIS_TAG>-be-<…>`（`lib/common.sh` 的 `doris_tag`，`DORIS_TAG` 是 `4.1.4-paimon-2.0.0`），与第三方镜像的 `<名字>-<digest 前缀>` 一样。环境仓库的 tag 不可改写，所以每个固定的 digest 有自己的 tag，重新固定以后新旧并存，按候选安装（`install-images.sh`，tag 相同）也不会撞上旧 digest 的 tag（#278）。仓库里已有这个 digest、只是在别的 tag 下（例如 `build-images.sh` 的 `build-<sha>-fe`）时，只按 digest 给它加上这个 tag，不复制、不重建，digest 不变。来源仓库由 `DORIS_IMAGE_SOURCE` 指定，可以是任何账号、任何区域里有这两个 digest 的 ECR 仓库，例如一套已装好的环境的 `colvenna-lake-<env>/query-engine`；不指定时，取 `COLVENNA_RELEASE_REGISTRY` 指定的发布仓库里、工作区 `release/` 下最新的固定这两个 digest、清单格式为 schema 2 起（`v1.0.0-rc.3` 起）的发布清单所写的仓库（清单只写仓库名，不写仓库地址）。

仓库改名（#258）以前，这两个镜像在名为 `doris` 的仓库里，digest 相同。`v1.0.0-rc.1` 的清单（schema 1）与 `v1.0.0-rc.2` 的清单都写着产品旧名字的仓库（[商标与命名](../../../release/trademarks.md)第 5、7 节），不作为默认来源；来源是发布仓库的 `colvenna-lake-release/query-engine`，或一套已装好的环境的 `colvenna-lake-<env>/query-engine`。

## 重新构建

```bash
COLVENNA_ENV=<env> deploy/scripts/doris/build-images.sh <evidence-dir>
```

在已部署的 foundation 栈上，从已提交的源码构建 FE 与 BE，推到 `colvenna-lake-<env>/query-engine`，标签为 `build-<sha>-fe`、`build-<sha>-be`。没有固定的构建产物，仓库只保留最近两次；固定的镜像（带 `<DORIS_TAG>-fe-<…>`、`-be-<…>` tag 的）永不过期，即使它同时带着 `build-` tag：基础栈的生命周期规则先按 `<DORIS_TAG>-fe-*`、`<DORIS_TAG>-be-*` 选出固定的镜像，FE、BE 各保留至多一千个，`build-` 的规则不能再让它们过期。随后它取出固定镜像里的读取器收据，与本次准备的读取器逐个 jar 比对 SHA-256：一致即说明配方复现了固定镜像里的读取器。新构建的镜像 digest 与固定的不同（镜像层带构建时间），安装仍运行固定的镜像；要改用新镜像，更新 `common.sh` 的两个 digest，并在新镜像上重跑验收；之后的 `doris-image` 给已在仓库里的新镜像加上固定的 tag。

> 注：本文中的 issue 与 PR 编号（如 #271）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
