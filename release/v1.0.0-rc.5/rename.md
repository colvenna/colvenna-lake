---
title: "Colvenna Lake v1.0.0-rc.5 发布检查：改名门禁"
date: 2026-10-28
status: final
issue: 181
---

# v1.0.0-rc.5 发布检查：改名门禁

本文是发布候选 `v1.0.0-rc.5` 的改名门禁记录，是 #271 2026-10-25 的补充（“Release 前全面改名”）要求的：发布检查要记录候选 SHA、制品 digest、扫描规则、命中与例外清单、迁移及复测结果；改名扫描和适用验证 PASS 之后才能发布，这一项未完成，或必需结果为 FAIL、UNKNOWN、NOT_RUN 时，发布结论为 NO-GO。GitHub CI 的豁免不豁免这一项。

本文不写出旧名：命中与例外只描述它们是什么、在哪里，不照抄原文；原文在私有证据里（第 8 节）。`v1.0.0-rc.4` 没有这份记录：它的门禁在 catalog 镜像里发现了第一方的旧名（#286），候选因此停止（[rc.4 的 UAT 报告](../../docs/uat/reports/v1.0.0-rc.4/report.md)第 3 节）。

## 1. 结论

改名门禁：**PASS**。

| 项 | 结果 | 依据 |
| --- | --- | --- |
| 源码扫描 | PASS：在 tag 上，`deploy/scripts/rename/old_name.py` 对 git 跟踪的全部 783 个文件退出 0；旧名只出现在例外清单允许的地方 | 第 3.1、5 节 |
| 制品扫描 | PASS：清单的 14 个镜像按 digest 读取，共 3,926,643 个文件，357 处命中，全部是第三方原文；第一方的命中 0 处（rc.4 是 1 处，即 #286 的锁键，已由 PR #288 修复） | 第 3.2、4 节 |
| 例外清单 | 27 条，都在历史记录、改名之前的候选的记录、rc.3 记录里的指定行、改名的沿革与这个检查自己的文件里，逐条有理由；当前的设计、手册、代码、测试与新的发布物都没有整目录的例外 | 第 5 节 |
| 按类别核对映射 | 适用的类别都已改名，并有扫描或运行核对；不适用的写明依据 | 第 6 节 |
| 仓库元数据 | PASS：仓库名、描述、主页地址与 topic 都没有旧名；主页地址在 rc.4 的门禁时改正 | 第 6 节 |
| 迁移 | 不需要：每套环境都是改名之后按新名字从零安装的，没有正式发布过的接口 | 第 7 节 |
| 复测 | PASS：本候选的完整 UAT（`us-west-2` 43/43、`us-east-2` 41/41）与安全检查（两个区域各 64/64）都在新名字下运行 | 第 7 节 |

本门禁只判改名。本候选的发布结论另因性能验收 FAIL 为 NO-GO，见[发布说明](notes.md)。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.5`，annotated tag（tag 对象 `7a6eea00011424c96588a064352bfd788f43ad0b`），指向 `546d515716d81f728bcc4b920b1689bd5b065019`（清单 PR #290 的合并提交） |
| 镜像与安装所用的源码 | `b6b884b0a87b24ca318e9140a1b1ebfff9310bdd`（B5，PR #289 的合并提交，含 #286 的修复 PR #288）。tag 的提交与它只差 `release/v1.0.0-rc.5/` |
| 发布清单 | [`release/v1.0.0-rc.5/manifest.json`](manifest.json) |
| 执行 | 源码扫描：10-27 08:26，tag 推送之后，在 tag 上运行 `old_name.py --all`（执行驱动的时间线 `runbook-rc5.md`）。镜像扫描：10-27 08:27:09–08:43:23（`rename-scan/scan.log`），从发布仓库按清单的 digest 读取 |

## 3. 扫描规则

### 3.1 源码：`old_name.py`

规则写在 tag 上的 [`deploy/scripts/rename/old_name.py`](../../deploy/scripts/rename/old_name.py) 的说明里（PR #288 新加）：

- 大小写不敏感。旧名作为一个词、或一个词的开头，出现在文件的文本或文件的路径里，都算命中；在标识符内部、由一个大写字母开头的那一处也算（驼峰写法的中段）。
- 不算：在一个词的中间（recover、discover），以及以它开头的英文词，字母大小写一致（cover、Covenant、COVERAGE、covet、covey）。只有 ASCII 字母算字母，所以紧挨着的中文挡不住它。
- 十六进制字面量（`0x` 加 8 到 16 位）的字节，正读或反读拼出旧名，也算命中：#286 的锁键就是这种写法，文本搜索看不到。
- 例外只能列在 `deploy/scripts/rename/exceptions.tsv`，一条一行：路径 glob、这一行要含的文本（或 `*`，任何行）、理由（第 5 节）。
- 文件按 UTF-8 读；读不成文本的（图片、视频等二进制）只按路径判。
- pre-commit 的钩子 `old-name` 在每次提交时检查暂存的文件，CI 的 `pre-commit run --all-files` 也运行它；`--all` 检查 git 跟踪的每个文件。

结果：在 tag 上 `old_name.py --all` 退出 0。写这份记录时，对 tag 的树（`git archive v1.0.0-rc.5`）跟踪的 783 个文件重新运行，仍退出 0。

### 3.2 制品：镜像扫描

规则在私有证据的 `rename-scan/scan_image.py` 与 `rename-scan/run.sh`（`scan_image.py` 与 rc.4 时的相同，`run.sh` 只差证据目录）：

- 范围：清单里第一方构建的全部镜像，即 `images` 的 12 个产品镜像、`fixtures.images` 的 Spark 读端镜像与 CLI 镜像，共 14 个（第 4.1 节）。第三方镜像（`third_party` 14 个、`fixtures.third_party` 6 个）不扫：它们是上游镜像按 digest 原样复制的，不含本项目的内容；其中原有的名字按 #271 不得改写。Helm chart、安装脚本与清单本身在 tag 的源码里，由第 3.1 节扫描。
- 读取：用 `crane export --platform linux/amd64` 按 digest 从发布仓库导出每个镜像展平后的文件系统，流式读入，不落盘。
- 查什么：每个文件的路径与内容；`.jar`、`.zip`、`.war`、`.whl`、`.egg`（小于 1 GiB）里的每个条目的名字与内容，嵌套的压缩包往下读两层。
- 匹配：大小写不敏感，旧名的四个字母，前面不是字母、后面不是 r 或 n。它比第 3.1 节的规则粗，不排除 covet、covey 一类的英文词，所以命中更多，逐处分类（第 4.2 节）。编进二进制的常量按它的字节出现，例如 Java class 文件里的 long 常量，所以 rc.4 的锁键在镜像里能被直接查到。
- 输出：每处命中一行（镜像、路径、偏移量、前后 40 个字节的上下文），每个镜像末尾一行 `#SUMMARY`（文件数、命中数），写在 `rename-scan/hits.tsv`。14 个镜像的 `*.err` 都是空的，没有读失败的条目。

## 4. 制品与命中

### 4.1 镜像、digest 与命中数

digest 取自 `rename-scan/images.tsv`（即清单的 `repository` 与 `digest`）；文件数与命中数取自 `hits.tsv` 的 `#SUMMARY` 行。

| 镜像（清单的键） | 仓库与 digest | 文件 | 命中 | 第一方 |
| --- | --- | --- | --- | --- |
| `flink` | `colvenna-lake-release/flink-base@sha256:a82ed653eb29809fb1d86d36dbda6d857c0f1c7a507480e1f0590e0350dfdabf` | 353,023 | 6 | 0 |
| `catalog` | `colvenna-lake-release/colvenna-catalog@sha256:ca96b23951ec46c79044efce7cd3eafdbf7e0a14e7957aeecac4fa73395c392d` | 92,531 | 3 | 0 |
| `operator` | `colvenna-lake-release/colvenna-operator@sha256:035428df63dcaa0f391ad2d8ea7d5f1e5cf6cb592b3267d54c9505c86d97bf90` | 129,635 | 3 | 0 |
| `jobs-mysql` | `colvenna-lake-release/colvenna-jobs@sha256:f3ee8d4c1410707ecf79978a1ef1d8fe7e67304b7f147b2c96c05605b70008b3` | 367,086 | 6 | 0 |
| `jobs-postgres` | `colvenna-lake-release/colvenna-jobs@sha256:5c6e0ff23d3f661c4e936c791af1ac28201275399fa0b417bc5a2fdb4e77d9bf` | 364,925 | 6 | 0 |
| `jobs-mongodb` | `colvenna-lake-release/colvenna-jobs@sha256:38915372774c007909476b9c68fa0a0f44ee93c517eecfca5dd4d17876c3e326` | 365,737 | 6 | 0 |
| `jobs-sql` | `colvenna-lake-release/colvenna-jobs@sha256:557d8b8dba0cfe060b23338501cfc1cf0209a2d3ed7773f2e06cacd4b942b0e2` | 353,082 | 6 | 0 |
| `jobs-kafka` | `colvenna-lake-release/colvenna-jobs@sha256:63cf9d35ad74bb1a066319a29aa49998828779163d720e486008ac8864bae5e8` | 357,090 | 6 | 0 |
| `console` | `colvenna-lake-release/colvenna-console@sha256:44441678894b41c31a887c8e3bd75727fe9743468f019a281821d57ce3a69b17` | 965 | 0 | 0 |
| `bi` | `colvenna-lake-release/colvenna-bi@sha256:097b202a1ce4d7d1d272c4bf8705900521335f1c03996c41d48ba84d88e12935` | 28,387 | 3 | 0 |
| `query-engine-fe` | `colvenna-lake-release/query-engine@sha256:8ef385893d63adae41f980d626dbf3818320a2fe008841bcca19a1f9b0c6104b` | 491,483 | 149 | 0 |
| `query-engine-be` | `colvenna-lake-release/query-engine@sha256:d98355172b05a3430665033dc47bb1fbe80e0510a7de994d09fc867a0d746645` | 761,668 | 155 | 0 |
| `fixtures.readers-spark` | `colvenna-lake-release/test-readers@sha256:8a3f1d4d52f79e8cfab4934b20d1dc9916dc80f4713735dc9cbb9e7568d34329` | 261,028 | 8 | 0 |
| `cli` | `colvenna-lake-release/colvenna-cli@sha256:536eb52a27af5dce625b897f7b3f42e70942936a7b94a5cf219b88a6c85f1959` | 3 | 0 | 0 |
| 合计 | | 3,926,643 | 357 | 0 |

与 rc.4 的扫描相比：14 个镜像里 10 个是新的 digest（`console`、`bi` 与查询引擎 FE、BE 不变），各镜像的文件数都相同，命中只少了一处，就是 catalog 里 `Migrations.class` 的锁键（rc.4 的 catalog 是 4 处）。

### 4.2 命中的分类

357 处命中逐处归类，没有归不了类的。全部是第三方原文：上游的类名、字段名、地名、单词表与机器码，不是本项目写的，也不是本项目的标识。

| 类别 | 在哪里 | 命中 | 各镜像 | 为什么是第三方 |
| --- | --- | --- | --- | --- |
| JTS 的字段名 | JTS（`org.locationtech.jts.coverage.CoverageSimplifier`）的两个字段，`cov` 后接 `Edge`、`Edges`。在 Paimon 2.0.0 各 jar 里 shade 的 JTS（`paimon-flink-1.20-2.0.0.jar`、`paimon-format-2.0.0.jar`、FE 的 `paimon-bundle-2.0.0.jar`、BE 的 `paimon-connector-jar-with-dependencies.jar`、读端的 `paimon-spark-3.5_2.12-2.0.0.jar`，以及 FE 的 `hive-catalog-shade-3.1.2.jar` 里 shade 的同一份），与没有 shade 的 JTS（读端的 `iceberg-spark-runtime-3.5_2.12-1.10.2.jar`、BE 的 `hadoop-hudi-scanner-jar-with-dependencies.jar`） | 28 | `flink`、`catalog`、`operator` 与 5 个作业镜像各 2，FE、BE、读端各 4 | JTS 的源码，经 Paimon、Iceberg、Doris 的依赖带进来 |
| JDK 的本地化数据 | 圣诞岛首府的英文名（时区名，`TimeZoneNames_rm` 等）与科威特第纳尔的葡萄牙语名（`CurrencyNames_pt`），在 JDK 的 `lib/modules` 里，FE、BE 的 `lib/src.zip` 里还有它们的源码 | 24 | `flink` 与 5 个作业镜像各 2，`catalog`、`operator` 各 1，FE、BE 各 4，读端 2 | JDK 的 CLDR 与本地化资源 |
| libp11-kit 的机器码 | `libp11-kit.so.0.3.0` 里，函数名 `C_SignRecover` 的字节被编进两条指令的立即数，前 8 个字节与后面的字节分开 | 18 | `flink`、5 个作业镜像、FE、BE、读端各 2 | 系统库（p11-kit）的编译产物 |
| Linux 内核头文件 | FE、BE 镜像里的 `linux-headers-5.15.0-1032-realtime` 与 `linux-realtime-headers-5.15.0-1032`：Intel 的 CPU 核心与 PMIC 的代号（Sunny、Willow、Golden、Palm、Cypress，Crystal、Whiskey、Dollar、Basin、Jolla 后接同一个英文词，意思是小海湾），在 `intel-family.h`、各驱动的 `Kconfig`、内核配置与生成的头文件里；另有以这两个 PMIC 命名的 2 个配置项文件名，以及 dpkg 记下的这些文件的清单 | 158 | FE、BE 各 79 | Linux 内核的源码与发行版的内核头文件软件包 |
| IEEE 的 OUI 注册表 | `usr/share/ieee-data/` 的 `oui`、`oui36`、`iab`、`mam` 的 `.txt` 与 `.csv`：厂商登记的地址里的地名（Lane、Peggy's、Hackett's、Hunt Park、North、Rustic River 后接同一个英文词）与两家公司的名字 | 106 | FE、BE 各 53 | IEEE 的公开注册数据（发行版的 ieee-data 包） |
| Lucene 的英文词干表 | `org/apache/lucene/analysis/en/KStemData2.class`：这个英文词本身，与 covet、covetous、covey，在 FE 的 `lucene-analyzers-common-8.11.2.jar` 与 BE 的 `hadoop-hudi-scanner`、`preload-extensions`、`trino-connector-scanner` 三个 jar 里 | 16 | FE 4，BE 12 | Apache Lucene 的英文词典 |
| Superset 的示例数据 | `app/superset/examples/countries.py` 与它的 `.pyc`：圣诞岛首府的英文名 | 2 | `bi` 2 | Apache Superset 自带的示例数据 |
| Cyrus SASL 的单词表 | `libsasl2.a` 里一张按字母排的四字母大写单词表（前后是 CORE、CORK、CORN、COST 与 COWL、CRAB） | 1 | `bi` 1 | 系统库（Cyrus SASL）的数据 |
| MariaDB 驱动的 pom | `mariadb-java-client-3.0.9.jar` 的 `META-INF/maven/…/pom.xml`：一个 Maven 插件的 groupId，是一家公司的域名倒写，公司名以这四个字母开头 | 1 | FE 1 | MariaDB JDBC 驱动的构建描述 |
| vim 的 Maxima 语法 | `usr/share/vim/vim82/syntax/maxima.vim`：Maxima 的一个函数名（这四个字母后接 `ct`） | 2 | FE、BE 各 1 | vim 自带的语法文件 |
| Doris FE 的网页 JS | `doris-fe.jar` 里的打包脚本 `static/commons~….js`：一份关键字列表里同一个 Maxima 函数名（相邻的词是 cov、cov1、covdiff、covers、crc24sum） | 1 | FE 1 | Apache Doris FE 的网页资源 |
| 合计 | | 357 | | 第一方 0 |

几点说明：

- 本项目自己的 jar 与二进制（`colvenna-catalog.jar`、colvenna-operator、作业 jar、colvenna-bi 的 Python 包、colvenna-console 与 colvenna CLI 的 Go 二进制）里没有命中。
- 第三方原文不改写：#271 要求第三方原有的名字不得错误改写，这些命中都保持原样，不列进例外清单（例外清单只管仓库里的源码）。

## 5. 例外清单

例外清单是 tag 上的 [`deploy/scripts/rename/exceptions.tsv`](../../deploy/scripts/rename/exceptions.tsv)，27 条（main 上的与 tag 上的相同）。每条都写明路径、允许的文本与理由；当前的设计、手册、代码、测试与新的发布物，没有一条是整个文件或整个目录的例外。按类别：

| 类别 | 条数 | 路径 | 允许的范围 | 理由 |
| --- | --- | --- | --- | --- |
| 历史记录 | 1 | `docs/history/*` | 整个文件 | 开发历史，保持当时的名字（#271） |
| 改名之前的候选的不可变记录 | 7 | `release/v1.0.0-rc.1/*`、`release/v1.0.0-rc.2/*`、`docs/uat/reports/v1.0.0-rc.1/*`、`docs/uat/reports/v1.0.0-rc.2/*`、`docs/performance/v1.0.0-rc.1.md`、`docs/performance/v1.0.0-rc.2.md`、`docs/uat/report.md`（2026-10-20 的 UAT 执行报告） | 整个文件 | 改名之前写成，历史证据不回改 |
| `v1.0.0-rc.3` 的记录 | 6 | `docs/uat/reports/v1.0.0-rc.3/report.md`（3 条）、`docs/performance/v1.0.0-rc.3.md`（1 条）、`release/v1.0.0-rc.3/security.md`（2 条） | 只许含指定文本的行 | 执行机上私有证据目录当时的名字、#271 拆除时删除的旧仓库、改名的映射 |
| 改名的沿革 | 9 | `README.md`（1 条）、`assets/brand/README.md`（1 条）、`docs/manual/install.md`（1 条）、`release/trademarks.md`（6 条） | 只许含指定文本的行 | 说明产品原来的名字、为什么放弃它，以及技术标识的映射 |
| 与历史报告对照的旧命名空间 | 1 | `docs/uat/cases.md` | 只许含指定文本的那一行 | 历史验收报告里的一个旧命名空间与它现在的名字对照 |
| 这个检查自己 | 3 | `deploy/scripts/rename/old_name.py`、`test_old_name.py`、`exceptions.tsv` | 整个文件 | 检查的定义、测试与例外清单本身 |

本候选的记录（本文、UAT 报告、性能验收、安全一节与发布说明）不需要例外：它们用新名字写成，命中只描述、不照抄，`old_name.py` 对它们退出 0。

## 6. 按类别核对映射

映射是 #271 第 2 节由维护者确认的那张表，写进了候选版本的[商标与命名](../trademarks.md)第 7 节。下表按 #271 补充里“共同接收条件”列出的类别逐项核对；“核对”一列写本次用什么证明它。

| 类别 | 适用 | 新的名字 | 核对 |
| --- | --- | --- | --- |
| 产品、控制台与文档 | 是 | 产品 Colvenna Lake（#258），控制台 colvenna-console；文档全部用新名字 | 源码扫描（文档在内；例外只在第 5 节的地方）；`console` 镜像 965 个文件，0 处 |
| 包、模块、类与 CLI | 是 | Java 包 `com.colvenna.lake.*`，Python 模块 `colvenna_bi`，Go 模块 `github.com/amliuyong/colvenna-lake/…`，CLI `colvenna` | 源码扫描；镜像扫描里本项目的 jar、Python 包与 Go 二进制没有命中（第 4.2 节） |
| 环境变量与配置 | 是 | `COLVENNA_*` | 源码扫描；本次三套环境都用 `COLVENNA_*` 的设置安装与卸载，UAT-DEP-05 的配置拒绝两个区域都 49/49 |
| HTTP、OpenAPI、schema 与系统列 | 部分 | Kubernetes API 组 `colvenna-lake.io` 与它的 CRD（`LakeSource`、`LakeTable` 等），库、表与列的前缀 `colvenna_` | 源码扫描（CRD 的 schema 在 chart 里）。仓库里没有单独的 OpenAPI 或 schema 文件，这一项没有别的对象；HTTP 接口在源码里，随源码扫描。UAT 的 catalog、表与看板的用例都在新的 API 组下通过 |
| 元数据、SQL 与策略 | 是 | 库、表与角色的前缀 `colvenna_`，Doris 的用户与工作负载组（`colvenna-data.*`、`wg_colvenna_data_*`），元数据库迁移锁的键（`colvcatL`，#286） | 源码扫描，十六进制字面量也查；catalog 镜像没有第一方的命中；UAT-CAT-06 的授权与 UAT-GOV-01 的执行两个区域都通过 |
| 指标、日志与标签 | 是 | 指标前缀 `colvenna_`，Grafana 看板的 uid，日志组 `/colvenna-lake-<env>/…`，Kubernetes 标签 `colvenna-lake.io/…`，资源标签 `Project=colvenna-lake` | 源码扫描；UAT-MON-01 两个区域都 16/16；两次卸载删除的日志组都是新名字 |
| 文件和目录 | 是 | `colvenna-*` 的目录与文件 | `old_name.py` 也查路径；镜像扫描也查路径，路径里的命中只有内核头文件的 2 个配置项文件名（第三方） |
| 镜像、Chart、IaC 与资源前缀 | 是 | 仓库 `colvenna-lake-release/*` 与 `colvenna-lake-<env>/*`，5 个 chart `colvenna-*`，栈 `ColvennaLake<Env>…` 与 `ColvennaLakeRelease`，桶名前缀 `colvennalake<env>` | 清单（第 4.1 节）；两套环境的 `installed-from-nothing`（`plat` 5 个栈、`uate2` 7 个栈，都在本候选安装时新建）；安全清点列出的本环境资源都是新名字（[安全一节](security.md)） |
| 安装、卸载、备份与恢复 | 是 | `deploy/install.sh`、`deploy/uninstall.sh`、`deploy/scripts/catalog/` 的元数据恢复 | UAT-DEP-01 到 UAT-DEP-09 两个区域都通过（[UAT 报告](../../docs/uat/reports/v1.0.0-rc.5/report.md)） |
| 测试、夹具与示例 | 是 | `tests/` 下全部用新名字 | 源码扫描；本次的 UAT、性能与安全都用这些测试运行 |
| workflow 与发布脚本 | 是 | `.github/workflows/`、`deploy/scripts/release/` | 源码扫描；清单由 `make-manifest.sh` 生成，`check-release.sh` 核对了 34 个镜像与 CLI 的二进制 |
| 生成的图、SVG、HTML、PNG 与视频 | 是 | 标志、架构图与宣传视频 | SVG 与 HTML 是文本，在源码扫描里；PNG、GIF 与 MP4 的内容不在扫描里，只按路径判。宣传视频与 GIF 由 PR #270 按 Colvenna Lake 重新渲染，PR #275 改用新的技术标识；之后 `assets/` 没有改动。本次没有逐帧复查 |
| 仓库元数据与当前任务标题 | 是 | 仓库 `amliuyong/colvenna-lake` | 2026-10-28 读到的描述、主页地址与 10 个 topic 都没有旧名；主页地址在 rc.4 的门禁时发现还指向旧的仓库名，已改为 `https://github.com/amliuyong/colvenna-lake/blob/main/docs/manual/install.md`。7 个打开的 issue 的标题也没有旧名 |
| 消费者与在途分支 | 是 | 独立的 Colvenna Catalog 从本仓库的 catalog 移植（包名 `com.colvenna.catalog`，#295） | `old-name` 钩子在 pre-commit 与 CI 里，合入时拦住重新带进来的旧名 |
| 最终的仓库地址 | 部分 | 现在的地址 `amliuyong/colvenna-lake`；正式版 GO 之后公开到 `colvenna/colvenna-lake`（#271 第 4 节） | 源码里的仓库链接都指向现在的地址，不靠旧仓库名的重定向（旧仓库名会被源码扫描查出）。公开之后的地址到那时再核对 |

## 7. 迁移与复测

**迁移：不需要。** 每套环境都是改名之后按新名字安装的，没有旧名字的部署要迁移或导入：

- `plat` 与 `uate2` 在本候选的 tag 上按清单从零安装（UAT-DEP-02、UAT-DEP-01）；
- `uat` 是按 #271 拆除全部旧部署之后，在改名的提交 `045d426c` 上从零安装的，之后每个候选原地升级；本候选的升级（UAT-DEP-06）从 rc.4 的 tag 升到 B5，两头都是新名字。

本仓库还没有正式发布过，开发中的接口直接改名，不保留旧名的兼容层（[商标与命名](../trademarks.md)第 7 节；开发阶段不保持升级兼容）。

**复测：** 本候选的全部验收都在新名字下运行，是 #271 要求的“从干净环境用新名称完成构建、安装与受影响的功能、安全和恢复验收”：

- UAT：`us-west-2` 43/43、`us-east-2` 41/41，含从零安装、升级、重复执行、卸载与元数据恢复（[UAT 报告](../../docs/uat/reports/v1.0.0-rc.5/report.md)）；
- 安全：两个区域各 64/64（[安全一节](security.md)）；
- 性能运行也在新名字下运行，它的 FAIL 是一项测试缺陷，与改名无关（[性能验收](../../docs/performance/v1.0.0-rc.5.md)）。

## 8. 证据

私有证据在执行机的 `<证据目录>/rc5/`，不入库：

- `rename-scan/`：`images.tsv`（14 个镜像的仓库与 digest）、`hits.tsv`（每处命中一行，每个镜像一行 `#SUMMARY`）、`scan.log`、`run.stdout`、各镜像的 `*.err`（都是空的）、`manifest.json`（从 tag 读出的清单）、`scan_image.py` 与 `run.sh`；
- `runbook-rc5.md`：执行驱动的时间线，记有在 tag 上运行 `old_name.py --all` 的结果；
- rc.4 的同一扫描在 `<证据目录>/rc4/rename-scan/`，用于第 4.1 节的对照。

`hits.tsv` 里的上下文是原文，含旧名；本文只描述它们。

> 注：本文中的 issue 与 PR 编号（如 #271）、提交（如 `546d5157`）和 tag（如 `d1dd3a46`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
