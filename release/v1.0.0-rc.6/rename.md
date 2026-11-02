---
title: "Colvenna Lake v1.0.0-rc.6 发布检查：改名门禁"
date: 2026-11-02
status: final
issue: 2
---

# v1.0.0-rc.6 发布检查：改名门禁

本文是发布候选 `v1.0.0-rc.6` 的改名门禁记录。[#2](https://github.com/colvenna/colvenna-lake/issues/2) 要求：“改名门禁：候选的源码与发行物（按 digest 解包的镜像）残留扫描 `PASS` 是 GO 的硬条件”。口径来自 #271 2026-10-25 的补充（“Release 前全面改名”）：发布检查要记录候选 SHA、制品 digest、扫描规则、命中与例外清单、迁移及复测结果；改名扫描和适用验证 PASS 之后才能发布，这一项未完成，或必需结果为 FAIL、UNKNOWN、NOT_RUN 时，发布结论为 NO-GO。

本文不写出旧名：命中与例外只描述它们是什么、在哪里，不照抄原文；原文在私有证据里（第 8 节）。本候选是第一个改用 Colvenna Catalog 发布版的候选（[#5](https://github.com/colvenna/colvenna-lake/issues/5)）：catalog 的服务端镜像与 Helm chart 不再在本仓库构建，是 Colvenna Catalog `v1.0.0` 的发布物按 digest 复制进发布仓库的（清单的 `catalog` 一节，见[发布记录的说明](../README.md#colvenna-catalog)）。它们随本候选交付，也按 digest 扫描。

## 1. 结论

改名门禁：**PASS**。

| 项 | 结果 | 依据 |
| --- | --- | --- |
| 源码扫描 | PASS：在 tag 上，`deploy/scripts/rename/old_name.py` 对 git 跟踪的全部 720 个文件退出 0；旧名只出现在例外清单允许的地方 | 第 3.1、5 节 |
| 制品扫描 | PASS：清单的 14 个镜像与 Colvenna Catalog 的 chart，共 15 个制品，按 digest 读取，共 3,930,425 个文件，357 处命中，全部是第三方原文；第一方的命中 0 处。命中与 rc.5 的 357 处相同，只有一处的偏移量不同 | 第 3.2、4 节 |
| 例外清单 | 21 条，都在历史记录、改名之前的候选的记录、rc.3 记录里的指定行、改名的沿革与这个检查自己的文件里，逐条有理由；当前的设计、手册、代码、测试与新的发布物都没有整目录的例外 | 第 5 节 |
| 按类别核对映射 | 适用的类别都已改名，并有扫描或运行核对；不适用的写明依据 | 第 6 节 |
| 仓库元数据 | PASS：仓库名、描述与主页地址都没有旧名，仓库没有 topic；打开的 issue 的标题也没有（扫描时 6 个，写这份记录时 11 个） | 第 6 节 |
| 迁移 | 不需要：每套环境都是改名之后按新名字安装的，没有正式发布过的接口 | 第 7 节 |
| 复测 | 完成：本候选的验收都在新名字下运行。两个区域的完整 UAT 与安全检查都已完成：UAT `us-west-2` 43 条中 PASS 41、FAIL 2，`us-east-2` 41 条中 PASS 40、NOT_RUN 1；安全两个区域各 64 项全部 PASS。UAT 的 FAIL 与 NOT_RUN 都不涉及名字（[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#28](https://github.com/colvenna/colvenna-lake/issues/28)，以及按决定保留环境，见第 7 节） | 第 7 节 |

本门禁只判改名：按 [#2](https://github.com/colvenna/colvenna-lake/issues/2)，源码扫描退出 0、制品里第一方的命中 0 处，判为 PASS。本候选的发布结论另见[发布说明](notes.md)，那里汇总 UAT、性能、安全与 CI。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.6`，annotated tag（tag 对象 `1eea41fdb19c363824f8053e6e7c9bb0aa70decc`），指向 `6ad52ec4824ae4d1fa03c0b4ba1f4bcc67cc3609`（清单 PR [#17](https://github.com/colvenna/colvenna-lake/pull/17) 的合并提交） |
| 镜像与安装所用的源码 | `803357a57160c24c45b7b58a743698e82ff09d0a`（B，PR [#16](https://github.com/colvenna/colvenna-lake/pull/16) 的合并提交，含 [#15](https://github.com/colvenna/colvenna-lake/issues/15) 的修复）。tag 的提交与它只差 `release/v1.0.0-rc.6/` |
| 发布清单 | [`release/v1.0.0-rc.6/manifest.json`](manifest.json) |
| Colvenna Catalog | 发布版 `v1.0.0`（tag 指向 `56dc31e216f6fb2faa5635643599b6eb089d675c`，镜像由 `77984fb24823acc53e4c1e2d00747d494961552d` 构建），固定在 [`deploy/images/colvenna-catalog.json`](../../deploy/images/colvenna-catalog.json)；服务端镜像与 chart 的副本见清单的 `catalog.image`、`catalog.chart` |
| 执行 | 时间都是 UTC。源码扫描：11-02 01:51:29，tag 推送（01:50:48）之后，在 tag 的检出上运行 `old_name.py --all`（`rename-scan/run.stdout` 记有检出的提交）。制品扫描：11-02 01:51:31–02:09:26（`rename-scan/run.stdout`），从发布仓库按清单的 digest 读取；02:14 逐处分类完（执行驱动的时间线 `ledger.md`） |

## 3. 扫描规则

### 3.1 源码：`old_name.py`

规则写在 tag 上的 [`deploy/scripts/rename/old_name.py`](../../deploy/scripts/rename/old_name.py) 的说明里（PR #288 新加；它与它的测试 `test_old_name.py` 都与 rc.5 的 tag 上的相同）：

- 大小写不敏感。旧名作为一个词、或一个词的开头，出现在文件的文本或文件的路径里，都算命中；在标识符内部、由一个大写字母开头的那一处也算（驼峰写法的中段）。
- 不算：在一个词的中间（recover、discover），以及以它开头的英文词，字母大小写一致（cover、Covenant、COVERAGE、covet、covey）。只有 ASCII 字母算字母，所以紧挨着的中文挡不住它。
- 十六进制字面量（`0x` 加 8 到 16 位）的字节，正读或反读拼出旧名，也算命中：#286 的锁键就是这种写法，文本搜索看不到。
- 例外只能列在 `deploy/scripts/rename/exceptions.tsv`，一条一行：路径 glob、这一行要含的文本（或 `*`，任何行）、理由（第 5 节）。
- 文件按 UTF-8 读；读不成文本的（图片、视频等二进制）只按路径判。
- pre-commit 的钩子 `old-name` 在每次提交时检查暂存的文件，CI 的 `pre-commit run --all-files` 也运行它；`--all` 检查 git 跟踪的每个文件。

结果：在 tag 上 `old_name.py --all` 退出 0（`rename-scan/source.txt`）。写这份记录时，对 tag 的树（`git archive v1.0.0-rc.6`）跟踪的 720 个文件重新运行，仍退出 0。

### 3.2 制品：镜像与 chart 扫描

规则在私有证据的 `rename-scan/scan_image.py` 与 `rename-scan/run.sh`。`scan_image.py` 与 rc.5 时的相同；`run.sh` 另加了在 tag 上的源码扫描、catalog 的镜像与 chart，凭证只写进这次运行自己的临时 Docker 配置，结束时删除。

- 范围：清单里第一方的全部镜像，即 `images` 的 11 个产品镜像、`fixtures.images` 的 Spark 读端镜像、CLI 镜像（`cli`）与 Colvenna Catalog 的服务端镜像（`catalog.image`），共 14 个；另加 Colvenna Catalog 的 Helm chart（`catalog.chart`），共 15 个制品（第 4.1 节）。第三方镜像（`third_party` 14 个、`fixtures.third_party` 6 个）不扫：它们是上游镜像按 digest 原样复制的，不含本项目的内容；其中原有的名字按 #271 不得改写。本仓库的 4 个 Helm chart、安装脚本与清单本身在 tag 的源码里，由第 3.1 节扫描。
- 读取：镜像用 `crane export --platform linux/amd64` 按 digest 从发布仓库导出展平后的文件系统，流式读入，不落盘。chart 是 OCI 制品：按 digest 读它的清单，取媒体类型为 `application/vnd.cncf.helm.chart.content.v1.tar+gzip` 的那一层，按这一层的 digest 读出、解压，同样逐个文件读。
- 查什么：每个文件的路径与内容；`.jar`、`.zip`、`.war`、`.whl`、`.egg`（小于 1 GiB）里的每个条目的名字与内容，嵌套的压缩包往下读两层。
- 匹配：大小写不敏感，旧名的四个字母，前面不是字母、后面不是 r 或 n。它比第 3.1 节的规则粗，不排除 covet、covey 一类的英文词，所以命中更多，逐处分类（第 4.2 节）。编进二进制的常量按它的字节出现，例如 Java class 文件里的 long 常量，所以 #286 那样的锁键在镜像里能被直接查到。
- 输出：每处命中一行（制品、路径、偏移量、命中处前 20 个字节到后 24 个字节的上下文），每个制品末尾一行 `#SUMMARY`（文件数、命中数），写在 `rename-scan/hits.tsv`；逐处的归类在 `rename-scan/classified.tsv`。15 个制品的 `*.err` 都是空的，`hits.tsv` 里也没有读不出的条目。

## 4. 制品与命中

### 4.1 制品、digest 与命中数

digest 取自 `rename-scan/images.tsv`（即清单的 `repository` 与 `digest`）；文件数与命中数取自 `hits.tsv` 的 `#SUMMARY` 行。

| 制品（清单的键） | 仓库与 digest | 文件 | 命中 | 第一方 |
| --- | --- | --- | --- | --- |
| `flink` | `colvenna-lake-release/flink-base@sha256:3f9dac3e5980c9842cdceb19398a9163815bd861030ff9f318159a50b9f9e160` | 353,023 | 6 | 0 |
| `operator` | `colvenna-lake-release/colvenna-operator@sha256:3cca0b9b9f1b5e7f98fb516e9cb29b2bc03f204a9050aa9f14d1bfe5c9903af6` | 129,635 | 3 | 0 |
| `jobs-mysql` | `colvenna-lake-release/colvenna-jobs@sha256:54b81c49bbc8d3962717308c2780dce9046a171fbd6d43d947f8585de55306d4` | 367,086 | 6 | 0 |
| `jobs-postgres` | `colvenna-lake-release/colvenna-jobs@sha256:7dd3c80a68f3c96741dce1fa95064576883e701327abd81397e7e06cb1b2459c` | 364,925 | 6 | 0 |
| `jobs-mongodb` | `colvenna-lake-release/colvenna-jobs@sha256:0d647168e15cad2ca7f650d2a64d90774ca2ad7be40d4df7a182984201b6a073` | 365,737 | 6 | 0 |
| `jobs-sql` | `colvenna-lake-release/colvenna-jobs@sha256:f019fc3d6d4c3b3bf33f9a2e1dd41fcf4043cf43182d328e49a82868a6f7a418` | 353,082 | 6 | 0 |
| `jobs-kafka` | `colvenna-lake-release/colvenna-jobs@sha256:e69df91a6e97ede7f0afcb901c8504f43b56d53924e70d8215e1003d67a51b4f` | 357,090 | 6 | 0 |
| `console` | `colvenna-lake-release/colvenna-console@sha256:b8a8fa05f63ce6af89d4eba2d1eac4d0217c8643dd63ec1bd39648fbaecbc676` | 965 | 0 | 0 |
| `bi` | `colvenna-lake-release/colvenna-bi@sha256:6393395a17b9801af413fec4458f99cd3eda31abe0e53ebf53e3b573dc21ba2b` | 28,386 | 3 | 0 |
| `query-engine-fe` | `colvenna-lake-release/query-engine@sha256:8ef385893d63adae41f980d626dbf3818320a2fe008841bcca19a1f9b0c6104b` | 491,483 | 149 | 0 |
| `query-engine-be` | `colvenna-lake-release/query-engine@sha256:d98355172b05a3430665033dc47bb1fbe80e0510a7de994d09fc867a0d746645` | 761,668 | 155 | 0 |
| `fixtures.readers-spark` | `colvenna-lake-release/test-readers@sha256:dfda6b37d6f63a2eea6001f32691f36a03d4ba0416bdc31893494d61acc428f1` | 261,028 | 8 | 0 |
| `cli` | `colvenna-lake-release/colvenna-cli@sha256:2c054b13d0a48b3d509e9a72ce2c43ade235bcb27641138d6af19492dd21e9a2` | 3 | 0 | 0 |
| `catalog.image` | `colvenna-lake-release/colvenna-catalog@sha256:47d8834d463ca4b75d82f2c4f1dbb4466fb04cd8fb07c682e1163b3cffcc4a68` | 96,304 | 3 | 0 |
| `catalog.chart` | `colvenna-lake-release/charts/colvenna-catalog@sha256:891ba0366a34b8523ee2eb54dfea1b6cd248e8509e700f983b0d5464dbdb1cbb` | 10 | 0 | 0 |
| 合计 | | 3,930,425 | 357 | 0 |

与 [rc.5 的扫描](../v1.0.0-rc.5/rename.md)相比：

- 命中相同：按制品、路径与上下文逐处比对 rc.5 的 357 处，本候选一处不多、一处不少。唯一的差别在 catalog 镜像 JDK 的 `lib/modules` 里：同一个文件里的同一处时区名，偏移量不同（rc.5 是 51,364,184，本候选是 51,551,913），上下文里另有两个字节不同。
- 镜像：14 个里 12 个是新的 digest，查询引擎 FE、BE 不变。各镜像的命中数都相同；文件数除两个外都相同：`bi` 少 1 个（rc.5 是 28,387 个）；catalog 现在是 Colvenna Catalog 的发布版，不再是本仓库构建的，96,304 个文件（rc.5 是 92,531 个），3 处命中的路径与 rc.5 的相同。
- chart：Colvenna Catalog 的 chart 第一次按 digest 扫描（rc.5 的 colvenna-catalog chart 还在本仓库的源码里，由源码扫描查），10 个文件，0 处。

### 4.2 命中的分类

357 处命中逐处归类（`classified.tsv`），没有归不了类的，归类与 rc.5 的相同。全部是第三方原文：上游的类名、字段名、地名、单词表与机器码，不是本项目或 Colvenna Catalog 写的，也不是它们的标识。

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

- 本项目自己的 jar 与二进制（colvenna-operator、作业 jar、colvenna-bi 的 Python 包、colvenna-console 与 colvenna CLI 的 Go 二进制）里没有命中；Colvenna Catalog 自己的 `colvenna-catalog.jar` 与它的 chart 的 10 个文件里也没有。catalog 镜像的 3 处都在它带的 `paimon-format-2.0.0.jar` 与 JDK 里。
- 第三方原文不改写：#271 要求第三方原有的名字不得错误改写，这些命中都保持原样，不列进例外清单（例外清单只管仓库里的源码）。

## 5. 例外清单

例外清单是 tag 上的 [`deploy/scripts/rename/exceptions.tsv`](../../deploy/scripts/rename/exceptions.tsv)，21 条（main 上的与 tag 上的相同）。每条都写明路径、允许的文本与理由；当前的设计、手册、代码、测试与新的发布物，没有一条是整个文件或整个目录的例外。按类别：

| 类别 | 条数 | 路径 | 允许的范围 | 理由 |
| --- | --- | --- | --- | --- |
| 历史记录 | 1 | `docs/history/*` | 整个文件 | 开发历史，保持当时的名字（#271） |
| 改名之前的候选的不可变记录 | 4 | `release/v1.0.0-rc.2/*`、`docs/uat/reports/v1.0.0-rc.2/*`、`docs/performance/v1.0.0-rc.2.md`、`docs/uat/report.md`（2026-10-20 的 UAT 执行报告） | 整个文件 | 改名之前写成，历史证据不回改 |
| `v1.0.0-rc.3` 的记录 | 3 | `docs/uat/reports/v1.0.0-rc.3/report.md`（2 条）、`release/v1.0.0-rc.3/security.md`（1 条） | 只许含指定文本的行 | #271 拆除时删除的旧仓库、改名的映射 |
| 改名的沿革 | 9 | `README.md`（1 条）、`assets/brand/README.md`（1 条）、`docs/manual/install.md`（1 条）、`release/trademarks.md`（6 条） | 只许含指定文本的行 | 说明产品原来的名字、为什么放弃它，以及技术标识的映射 |
| 与历史报告对照的旧命名空间 | 1 | `docs/uat/cases.md` | 只许含指定文本的那一行 | 历史验收报告里的一个旧命名空间与它现在的名字对照 |
| 这个检查自己 | 3 | `deploy/scripts/rename/old_name.py`、`test_old_name.py`、`exceptions.tsv` | 整个文件 | 检查的定义、测试与例外清单本身 |

与 rc.5 的 27 条相比少了 6 条，都是在本仓库的初始提交（2026-10-30）里删去的：本仓库没有带过来 `v1.0.0-rc.1` 的记录，它的 3 条整文件例外随之删去；rc.3 的 UAT 报告、性能文档与安全一节里执行机上私有证据目录当时的名字，迁移时换成了占位符 `<证据目录>`，那 3 条也删去。

本候选的记录（本文、UAT 报告、性能验收、安全一节与发布说明）不需要例外：它们用新名字写成，命中只描述、不照抄，`old_name.py` 对它们退出 0。

## 6. 按类别核对映射

映射是 #271 第 2 节由维护者确认的那张表，写进了候选版本的[商标与命名](../trademarks.md)第 7 节。下表按 #271 补充里“共同接收条件”列出的类别逐项核对；“核对”一列写本次用什么证明它。

| 类别 | 适用 | 新的名字 | 核对 |
| --- | --- | --- | --- |
| 产品、控制台与文档 | 是 | 产品 Colvenna Lake（#258），控制台 colvenna-console；文档全部用新名字 | 源码扫描（文档在内；例外只在第 5 节的地方）；`console` 镜像 965 个文件，0 处 |
| 包、模块、类与 CLI | 是 | Java 包 `com.colvenna.lake.*`，Python 模块 `colvenna_bi`，Go 模块 `github.com/colvenna/colvenna-lake/…`，CLI `colvenna`；Colvenna Catalog 的包 `com.colvenna.catalog` | 源码扫描；镜像扫描里本项目的 jar、Python 包与 Go 二进制，以及 Colvenna Catalog 的 jar，都没有命中（第 4.2 节） |
| 环境变量与配置 | 是 | `COLVENNA_*` | 源码扫描；本候选的 `plat`、`uate2` 从零安装与 `uat` 的升级都用 `COLVENNA_*` 的设置，发布仓库与 catalog 的来源也由它们给出（`COLVENNA_RELEASE_REGISTRY`、`COLVENNA_CATALOG_REGISTRY`） |
| HTTP、OpenAPI、schema 与系统列 | 部分 | Kubernetes API 组 `colvenna-lake.io` 与它的 CRD（`LakeSource`、`LakeTable` 等），库、表与列的前缀 `colvenna_` | 源码扫描（CRD 的 schema 在 chart 里）。仓库里没有单独的 OpenAPI 或 schema 文件，这一项没有别的对象；本仓库的 HTTP 接口在源码里，随源码扫描；catalog 的 HTTP 接口在 Colvenna Catalog 里，随它的镜像扫描 |
| 元数据、SQL 与策略 | 是 | 库、表与角色的前缀 `colvenna_`，Doris 的用户与工作负载组（`colvenna-data.*`、`wg_colvenna_data_*`） | 源码扫描，十六进制字面量也查。catalog 元数据库迁移锁的键（#286）现在在 Colvenna Catalog 里：本候选的 catalog 镜像没有第一方的命中 |
| 指标、日志与标签 | 是 | 指标前缀 `colvenna_`，Grafana 看板的 uid，日志组 `/colvenna-lake-<env>/…`，Kubernetes 标签 `colvenna-lake.io/…`，资源标签 `Project=colvenna-lake` | 源码扫描；UAT-DEP-07 卸载 `plat` 之前对两个区域的清点里，日志组与其他资源都没有旧名（见下面“镜像、Chart、IaC 与资源前缀”一行） |
| 文件和目录 | 是 | `colvenna-*` 的目录与文件 | `old_name.py` 也查路径；镜像与 chart 扫描也查路径，路径里的命中只有内核头文件的 2 个配置项文件名（FE、BE 各 2 处，第三方） |
| 镜像、Chart、IaC 与资源前缀 | 是 | 仓库 `colvenna-lake-release/*` 与 `colvenna-lake-<env>/*`，本仓库的 4 个 chart `colvenna-*` 与 Colvenna Catalog 的 chart `colvenna-catalog`，栈 `ColvennaLake<Env>…` 与 `ColvennaLakeRelease`，桶名前缀 `colvennalake<env>` | 清单（第 4.1 节）；两套环境的 `installed-from-nothing`（`plat` 5 个栈、`uate2` 7 个栈，都在本候选安装时新建）；UAT-DEP-07 卸载 `plat` 之前对两个区域的账号资源的清点（栈、角色、日志组、仓库、桶、数据库、DNS 等 42 份清单）没有旧名 |
| 安装、卸载、备份与恢复 | 是 | `deploy/install.sh`、`deploy/uninstall.sh`、`deploy/scripts/catalog/` 的元数据恢复 | PASS：UAT-DEP-02、UAT-DEP-01（`plat`、`uate2` 在 tag 上从零安装），UAT-DEP-06（`uat` 的升级），UAT-DEP-03、UAT-DEP-04、UAT-DEP-05、UAT-DEP-08（两个区域），UAT-DEP-09（`plat` 上的元数据恢复），UAT-DEP-07（`us-west-2` 卸载 `plat`）。`us-east-2` 的 UAT-DEP-07 没有运行：环境按决定保留给下一个候选（[UAT 报告](../../docs/uat/reports/v1.0.0-rc.6/report.md)第 5 节） |
| 测试、夹具与示例 | 是 | `tests/` 下全部用新名字 | 源码扫描；本候选的 UAT、性能与安全都用这些测试运行 |
| workflow 与发布脚本 | 是 | `.github/workflows/`、`deploy/scripts/release/` | 源码扫描；清单由 `make-manifest.sh` 生成，`check-release.sh` 核对了 35 个镜像、CLI 的二进制与 Colvenna Catalog `v1.0.0` 的镜像与 chart |
| 生成的图、SVG、HTML、PNG 与视频 | 是 | 标志、架构图与宣传视频 | SVG 与 HTML 是文本，在源码扫描里；PNG、GIF 与 MP4 的内容不在扫描里，只按路径判。宣传视频与 GIF 由 PR #270 按 Colvenna Lake 重新渲染，PR #275 改用新的技术标识；#300（PR #301）只把结尾一幕重新渲染成新的仓库地址，旁白与其余各帧不变。之后 `assets/` 没有改动。本次没有逐帧复查 |
| 仓库元数据与当前任务标题 | 是 | 仓库 `colvenna/colvenna-lake` | 2026-11-02 读到的仓库名、描述与主页地址都没有旧名，主页地址指向本仓库的部署指南；仓库没有 topic。扫描时 6 个打开的 issue 的标题没有旧名。写这份记录时（2026-11-02 21:12 之后）打开的是 11 个：[#1](https://github.com/colvenna/colvenna-lake/issues/1) 到 [#6](https://github.com/colvenna/colvenna-lake/issues/6)、[#10](https://github.com/colvenna/colvenna-lake/issues/10)（当天重新打开）、[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24)、[#28](https://github.com/colvenna/colvenna-lake/issues/28)；用 `old_name.py` 重新查过这 11 个标题与仓库名、描述、主页地址、topic，退出 0 |
| 消费者与在途分支 | 是 | 本候选改用独立的 Colvenna Catalog 的发布版（[#5](https://github.com/colvenna/colvenna-lake/issues/5)），它的包名 `com.colvenna.catalog` | 它的镜像与 chart 随本候选按 digest 扫描（第 4 节）；`old-name` 钩子在 pre-commit 与 CI 里，合入时拦住重新带进来的旧名 |
| 最终的仓库地址 | 部分 | 现在的地址 `colvenna/colvenna-lake`，就是公开时的地址，仓库仍是私有的；正式版 GO 之后改为公开（#271 第 4 节） | 源码里的仓库链接与 Go 模块都指向现在的地址，不靠旧仓库名的重定向（旧仓库名会被源码扫描查出）。公开之后再核对一次 |

## 7. 迁移与复测

**迁移：不需要。** 每套环境都是改名之后按新名字安装的，没有旧名字的部署要迁移或导入：

- `plat` 与 `uate2` 在本候选的 tag 上按清单从零安装（UAT-DEP-02、UAT-DEP-01，都 PASS）；
- `uat` 是按 #271 拆除全部旧部署之后，在改名的提交 `045d426c` 上从零安装的，之后每个候选原地升级；本候选准备时把它从 `92344a40` 原地升到 B（中间经过本仓库的 `e08034c0`），两头都是新名字；
- 三套环境的 catalog 都是 Colvenna Catalog 的发布版 `v1.0.0`，它按新名字开发与发布。

本仓库还没有正式发布过，开发中的接口直接改名，不保留旧名的兼容层（[商标与命名](../trademarks.md)第 7 节；开发阶段不保持升级兼容）。

**复测：完成。** 本候选的全部验收都在新名字下运行，是 #271 要求的“从干净环境用新名称完成构建、安装与受影响的功能、安全和恢复验收”：

- 从零安装与恢复：UAT-DEP-02（`plat`，`us-west-2`，只装平台）、UAT-DEP-01（`uate2`，`us-east-2`）、UAT-DEP-09（`plat` 上的元数据恢复）与 UAT-DEP-07（`us-west-2` 卸载 `plat`，其他环境与发布仓库的 186 项不变）都 PASS。
- 两个区域的完整 UAT 已完成（[UAT 报告](../../docs/uat/reports/v1.0.0-rc.6/report.md)）：`us-west-2` 43 条中 PASS 41、FAIL 2，`us-east-2` 41 条中 PASS 40、NOT_RUN 1。FAIL 的 UAT-CAT-05 是平台身份的 catalog grant 过期后不续期（[#22](https://github.com/colvenna/colvenna-lake/issues/22)），UAT-MON-01 是一次原因未明的层延迟不一致（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）；NOT_RUN 的是 `us-east-2` 的 UAT-DEP-07，环境按决定保留给下一个候选。[#22](https://github.com/colvenna/colvenna-lake/issues/22) 是 grant 的有效期，NOT_RUN 是保留环境的决定；[#28](https://github.com/colvenna/colvenna-lake/issues/28) 的原因还没有查明，它的现象是两个延迟的数值不一致。三者都不涉及名字。
- 两个区域的安全清点与拒绝探测已完成（[安全一节](security.md)），各 64 项全部 PASS。
- 性能验收的结果见性能文档（`docs/performance/v1.0.0-rc.6.md`）。三者由[发布说明](notes.md)汇总；本候选的发布结论是 NO-GO，原因不在改名。

## 8. 证据

私有证据在执行机的 `<证据目录>/rc6/`，不入库：

- `rename-scan/`：`source.txt`（在 tag 上运行 `old_name.py --all` 的输出与退出码）、`images.tsv`（15 个制品的仓库与 digest）、`hits.tsv`（每处命中一行，每个制品一行 `#SUMMARY`）、`classified.tsv`（每处命中的归类）、`scan.log`、`run.stdout`、各制品的 `*.err`（都是空的）、`manifest.json`（从 tag 读出的清单）、`scan_image.py` 与 `run.sh`；
- `ledger.md`：执行驱动的时间线，记有 tag 的推送、改名扫描的开始与结论，以及第 7 节引用的各项运行；
- `v1.0.0-rc.6/plat-us-west-2/` 与 `v1.0.0-rc.6/uate2-us-east-2/`：第 6、7 节引用的从零安装、元数据恢复、卸载与卸载之前的清点（`summary.tsv`、`install/verdicts.tsv`、`uninstall-before/`）；
- `v1.0.0-rc.6/uat-us-west-2/`、`v1.0.0-rc.6/security-uat/`、`v1.0.0-rc.6/security-uate2/` 与 `v1.0.0-rc.6/reports/`：第 1、7 节引用的完整 UAT、安全检查与各区域的结论；
- rc.5 的同一扫描在 `<证据目录>/rc5/rename-scan/`，用于第 4.1 节的对照。

`hits.tsv` 与 `classified.tsv` 里的上下文是原文，含旧名；本文只描述它们。

> 注：本文中不带链接的 issue 与 PR 编号（如 #271、PR #288）和提交（如 `045d426c`、`92344a40`）来自本项目早期的私有仓库，本仓库里没有对应的页面；带链接的编号（如 [#2](https://github.com/colvenna/colvenna-lake/issues/2)）与其余的提交（如 `803357a5`、`e08034c0`）是本仓库现在的。
