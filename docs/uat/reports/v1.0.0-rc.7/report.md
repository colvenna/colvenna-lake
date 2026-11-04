---
title: "Colvenna Lake v1.0.0-rc.7 UAT 报告"
date: 2026-11-03
status: final
issue: 2
---

# v1.0.0-rc.7 UAT 报告

本文是发布候选 `v1.0.0-rc.7` 的 UAT 报告，按 Release 归档。`v1.0.0-rc.6` 因产品缺陷 [#22](https://github.com/colvenna/colvenna-lake/issues/22) 是 NO-GO；本候选带着它与同一类的 [#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的修复，以及 [#28](https://github.com/colvenna/colvenna-lake/issues/28) 的修复，catalog 仍是 Colvenna Catalog 的发布版 `v1.0.0`（[#5](https://github.com/colvenna/colvenna-lake/issues/5)）。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)：本次 Release 的必需用例集合共 44 条（第 7 节），两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。性能不在 UAT 内，另见[性能验收](../../../performance/v1.0.0-rc.7.md)；改名门禁另见[改名门禁的记录](../../../../release/v1.0.0-rc.7/rename.md)。

## 1. 结论

- 两个区域的必需用例全部 PASS：`us-west-2` 43 条中 43 条 PASS，`us-east-2` 41 条中 41 条 PASS。两个区域都没有 FAIL、BLOCKED 或 NOT_RUN。`us-east-2` 的 UAT-DEP-07（卸载 `uate2`）在性能运行之后执行，4/4（第 5 节）。必需集合里只要求在另一个区域执行的用例，在第 4 节各区域的表下单列。
- 测试运行都是一次通过：`uat` 51 次、`plat` 3 次、`uate2` 53 次，退出码都是 0，没有重跑，也没有人工判定（没有用 `--overrides`）。执行中没有改动测试。
- **`v1.0.0-rc.6` 的问题，修复在本候选里得到验证（第 5、6 节）：**
  - [#22](https://github.com/colvenna/colvenna-lake/issues/22)（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)，平台身份的 catalog grant 过期后不续期）：UAT-CAT-05 两个区域都在第一次运行 38/38。UAT 之前另在 `uat` 上做了一次运维检查：先让平台身份与 Operator 的 grant 落进续期的余量，再在 tag 上完整运行一次 `install.sh`，两条都重建为各自的有效期（7 天、90 天）。
  - [#23](https://github.com/colvenna/colvenna-lake/issues/23)（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)，平台的两个 catalog token 不续签）与 [#24](https://github.com/colvenna/colvenna-lake/issues/24)（PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)，catalog 的私有 CA 证书不换）：同一次运维检查里，两个 Secret 都换成了新签的 token 并记下 id；CA 证书用同一个密钥与名称重签，catalog 的 Pod 滚动到新证书链，`verify` 通过。之后的 UAT 在同一套环境上运行 Operator 与各引擎的流程，都通过。
  - [#28](https://github.com/colvenna/colvenna-lake/issues/28)（PR [#31](https://github.com/colvenna/colvenna-lake/pull/31)，分层表的层延迟与标记不一致）：UAT-MON-01 两个区域都在第一次运行 16/16。`layer-latency-agrees` 的 10 笔标记中位数与 Operator 导出的层延迟中位数：`uat` 22 秒与 20 秒，`uate2` 28 秒与 27 秒。
  - [#18](https://github.com/colvenna/colvenna-lake/issues/18)、[#20](https://github.com/colvenna/colvenna-lake/issues/20)（PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)，测试缺陷）：UAT-DEP-08 的发布清单拒绝两个区域都在第一次运行 14/14，不再需要 rc.6 时测试侧的绕行。
  - 这几个 issue 在这些检查与 UAT 之后关闭；指标余下的小偏差另由 [#34](https://github.com/colvenna/colvenna-lake/issues/34) 跟踪。
- **[#5](https://github.com/colvenna/colvenna-lake/issues/5) 的接收（切换到 Colvenna Catalog）：**
  - `plat` 与 `uate2` 在 tag 上按清单从零安装（UAT-DEP-02、UAT-DEP-01，都 PASS）；`uat` 从 `v1.0.0-rc.6` 的 tag 原地升级（UAT-DEP-06 PASS）；
  - UAT-CAT-01 到 UAT-CAT-12 在两个区域都以 Colvenna Catalog `v1.0.0` 的发布物运行，全部 PASS。
- **UAT-CAT-04 的覆盖缩小（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）仍然存在。** UAT-CAT-01 与 UAT-CAT-04 的 `streaming-append-commits` 以写入并行度 1 运行，绕开上游 [Apache Paimon](https://paimon.apache.org/) 2.0.0 的缺陷（流模式写有界输入、写入并行度大于 1 时，后结束的写入子任务的收尾数据可能被静默丢弃）。这一步验证带 checkpoint 的写入经 catalog 多次提交且不丢行，但不覆盖多个写入子任务的数据进同一个提交（第 6 节）。
- **[#10](https://github.com/colvenna/colvenna-lake/issues/10)。** `uat` 在 B7 上的 `prep` 仍经 `COLVENNA_CATALOG_REGISTRY` 从维护者的私有副本（`<the catalog's private release registry>`）取 catalog 的发布物；从零安装与按清单重装从发布仓库里的副本取。默认来源 GitHub Container Registry 的实跑要等 catalog 公开（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)）。
- 本报告只给 UAT 的结果。性能见[性能验收](../../../performance/v1.0.0-rc.7.md)，安全见[安全一节](../../../../release/v1.0.0-rc.7/security.md)。本候选能否发布，由发布检查（[#2](https://github.com/colvenna/colvenna-lake/issues/2)）综合 UAT、性能、安全、改名门禁与已知缺陷决定：结论是 **GO**（[发布说明](../../../../release/v1.0.0-rc.7/notes.md)）。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.7`，annotated tag（tag 对象 `d9d58ea1`），指向 `a01301c5`（`a01301c59a79fcb92aa7804359859bc34fae691d`，清单 PR [#35](https://github.com/colvenna/colvenna-lake/pull/35) 的合并提交，下称 C7） |
| 镜像与安装所用的源码 | `3e7344f1`（`3e7344f1ba7e62ab7ed48d2574656a8647a42e4b`，PR [#32](https://github.com/colvenna/colvenna-lake/pull/32) 的合并提交，下称 B7）。C7 与它只差 `release/v1.0.0-rc.7/` |
| 发布清单 | [`release/v1.0.0-rc.7/manifest.json`](../../../../release/v1.0.0-rc.7/manifest.json)，schema 3，内容的种类与 rc.6 的相同；生成时 `check-release.sh` 核对了其中 35 个镜像、colvenna CLI 的二进制与 Colvenna Catalog `v1.0.0` 的镜像与 chart。与 rc.6 的清单相比，从 B7 构建的镜像都是新的 digest（Flink 基础镜像、colvenna-operator、5 个作业镜像、控制台、BI、夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像与 CLI 镜像）；查询引擎 FE、BE 的 digest 不变，第三方镜像（`third_party` 14 个、`fixtures.third_party` 6 个）与 catalog 的镜像、chart 也不变。安装脚本 `install.sh` 的 blob 变了（[#30](https://github.com/colvenna/colvenna-lake/issues/30) 的正式版支持），`uninstall.sh` 与 4 个 chart 的 tree 不变；用例文档与必需用例集合不变 |
| Colvenna Catalog | 发布版 `v1.0.0`，与 rc.6 的相同：tag 指向 `56dc31e2`，镜像与 chart 构建自 `77984fb2`，由它的候选 `v1.0.0-rc.4` 晋升而来；服务端镜像 `sha256:47d8834d463ca4b75d82f2c4f1dbb4466fb04cd8fb07c682e1163b3cffcc4a68`，chart `colvenna-catalog` `1.0.0` `sha256:891ba0366a34b8523ee2eb54dfea1b6cd248e8509e700f983b0d5464dbdb1cbb`，schema 版本 4。固定在 [`deploy/images/colvenna-catalog.json`](../../../../deploy/images/colvenna-catalog.json) |
| 环境 | 三套，在同一个账号里，都是改名之后按新名字安装的。`uat`（`us-west-2`，带 `--fixtures`）保留下来，从 rc.6 的 tag（`6ad52ec4`）原地升级到 B7。`plat`（`us-west-2`，只装平台）在 rc.6 时已卸载；`uate2`（`us-east-2`，带 `--fixtures`）在 rc.6 判定之后、本候选开始之前（11-03 00:05–00:57）以 rc.6 的工具卸载（[rc.6 的报告](../v1.0.0-rc.6/report.md)第 5 节的附注）。两套都在本候选的 tag 上按清单从零安装。部署值只在执行机未提交的设置里，这里用设置的键名 |
| 节点与版本 | 每套环境：写入节点 t3.xlarge ×2、查询节点 r6i.xlarge ×2，两个可用区各一台，AL2023 x86_64。EKS 1.36（`uat` 的平台版本 `eks.15`，性能运行记下的），kubelet `v1.36.5`，系统 Amazon Linux 2023.12.20261102（节点上报的） |
| 元数据库、测试源与存储 | 项目元数据库 RDS PostgreSQL 16.13，单可用区，自动备份保留 1 天（`verify` 读到的）；测试源 RDS MySQL 8.4.10（测试源步骤的输出）；仓库桶 S3（私有、只许 TLS、加密），每套 3 个 EBS 卷。只用合成数据 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B7 上从源码运行，清单由升级后的 `uat` 核对生成。其余运行都在 tag 上，卸载与它的核对也在内。没有测试侧的绕行 |
| `RUN_TAG` | `us-west-2`：`rc7w`；`us-east-2`：`rc7e` |
| 执行身份与执行机 | 执行机的 EC2 实例角色（`AWS_PROFILE=work`）。两个区域的 UAT 在同一台执行机上同时运行 |
| 补丁窗口 | 本候选的准备、两套 UAT 与安全检查（11-03 01:52–21:26）都在两个补丁窗口之间，没有跨过 00:00 UTC。`Patch Group` 标签仍事先去掉，并由核对脚本每 10 分钟核对一次：`uat` 的节点在 01:52，`plat`、`uate2` 的节点在它们安装期间（03:45）。标签被自动补回过，都在下一次核对时去掉（下面是核对发现的时刻）：执行机 03:34，`uat` 的节点 03:44，`uate2` 的节点 04:05；节点故障测试新起的节点，`uate2` 20:08、20:18，`uat` 20:59、21:09（`patch-tag-watch.log`）。性能运行跨过 11-04 00:00 UTC 的窗口，核对脚本那时仍在运行 |

## 3. 执行顺序

两个区域的执行有先后约束：

- 凡是调用 `install.sh` 的阶段，都必须在 tag 上运行。UAT-DEP-06 的升级例外：它在 B7 上运行，清单由它生成，所以在 tag 之前。
- UAT-DEP-05 的“错误区域卸载”要探测另一个区域里可丢弃的环境：`uat` 探测 `uate2`，`uate2` 探测 `plat`，从不探测 `uat`。
- UAT-DEP-07 比对卸载前后其他环境的资源，卸载期间其他环境要保持静止。

| 时间（UTC，2026-11-03） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 00:05–00:57 | `us-east-2` | 以 rc.6 的工具卸载 `uate2`（rc.6 判定之后的决定，记在 rc.6 的报告里） | — |
| 01:22–01:39 | — | PR [#31](https://github.com/colvenna/colvenna-lake/pull/31)（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）、PR [#32](https://github.com/colvenna/colvenna-lake/pull/32)（[#30](https://github.com/colvenna/colvenna-lake/issues/30)）合入，B7 = `3e7344f1`；此前 PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)、[#26](https://github.com/colvenna/colvenna-lake/pull/26)、[#27](https://github.com/colvenna/colvenna-lake/pull/27)（[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24)）、[#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)（[#18](https://github.com/colvenna/colvenna-lake/issues/18)、[#20](https://github.com/colvenna/colvenna-lake/issues/20)）与 rc.6 的记录（PR [#29](https://github.com/colvenna/colvenna-lake/pull/29)、[#33](https://github.com/colvenna/colvenna-lake/pull/33)）已合入 | — |
| 01:52 | — | 从 rc.6 交接 `uat` 的锁与它的运行工作区；补丁标签核对脚本启动 | — |
| 01:53–01:56 | `us-west-2` | 重新部署发布镜像仓库（11 个仓库，拉取账号为空，与上次部署相同） | — |
| 01:55–02:54 | `us-west-2` | `uat` 在 B7 上 `prep`：从 rc.6 的 tag 原地升级（`reinstall`，10/10，安装 27 步 3274 秒），catalog 经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取（[#10](https://github.com/colvenna/colvenna-lake/issues/10)）；之后 `spark-image` | UAT-DEP-06 |
| 02:54–03:03 | `us-west-2` | `make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 03:06–03:17 | — | 清单 PR [#35](https://github.com/colvenna/colvenna-lake/pull/35) 合入（GitHub Actions 8/8），annotated tag 打在合并提交上（03:17:01 推送） | — |
| 03:17–03:59 | `us-west-2` | 运维检查 [#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的修复：在 tag 上对 `uat` 完整运行一次 `install.sh --release v1.0.0-rc.7 --fixtures`，前后核对（第 5 节） | （不属于用例） |
| 03:17–04:10 | — | 改名门禁：在 tag 上的源码扫描与按 digest 的 15 个制品扫描，04:10 分类完（[改名门禁的记录](../../../../release/v1.0.0-rc.7/rename.md)） | — |
| 03:17–04:27 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（27 步，3980 秒），之后 `check-install` | UAT-DEP-02 |
| 03:17–04:54 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，27 步，5552 秒），之后 `check-install --fresh` | UAT-DEP-01 |
| 04:27–05:50 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 936 秒，catalog 停 211 秒）与按时间点（恢复 1052 秒，catalog 停 211 秒） | UAT-DEP-09 |
| 04:54–05:57 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝、重复执行（安装 1172 秒）、中断续装；之后 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 |
| 05:57–06:37 | `us-west-2` | `uat` 与 `uate2` 静止时，在 tag 上卸载 `plat`（05:57–06:35），之后核对 | UAT-DEP-07 |
| 06:37–21:19 | 两个区域 | 两套 UAT 在 tag 上同时运行：`uat` 从 `deploy` 阶段起（21:19 结束），`uate2` 从 `catalog` 阶段起（20:31 结束） | 其余用例 |
| 07:39、11:42、13:41 | — | [#22](https://github.com/colvenna/colvenna-lake/issues/22)（UAT-CAT-05 之后）、[#23](https://github.com/colvenna/colvenna-lake/issues/23)（UAT-SRC-01 之后）、[#28](https://github.com/colvenna/colvenna-lake/issues/28)（两个区域的 UAT-MON-01 之后）关闭；[#24](https://github.com/colvenna/colvenna-lake/issues/24) 在运维检查之后（03:59）关闭 | — |
| 20:40–20:46 | `us-east-2` | `uate2` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 21:19–21:26 | `us-west-2` | `uat` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 21:27–11-04 00:17 | `us-west-2` | 删除 UAT 的 `kafka` 阶段留下的开发用 [Apache Kafka](https://kafka.apache.org/)（21:27:26–21:27:56），`uat` 单独运行性能验收 `rc7p`，PASS（不属于 UAT，见[性能验收](../../../performance/v1.0.0-rc.7.md)） | — |
| 11-04 00:17–01:12 | `us-east-2` | `uat` 静止时，在 tag 上卸载 `uate2`（00:17–01:10），之后核对 | UAT-DEP-07 |

## 4. 统计与逐例结果

两个区域分别统计。各自的分母是本区域必需的用例，按候选上[用例文档](../../cases.md)第 7 节的“区域”一列。

下面的表由 tag 上的 `tests/acceptance/uat_report.py report` 从证据生成，没有人工判定（没有用 `--overrides`）。`--doc` 指向候选 tag 上的用例文档；工具本身、`--cases` 用的 `tests/acceptance/uat-cases.tsv` 与用例文档，main 上的都与 tag 上的相同。表里的列与 rc.6 的报告相同：

- 每例的预期，是用例文档里它的期望结果：期望结果表里的每项检查都出现并为 PASS，项数、最后输出与最后一行都相符；按退出码判定的用例，退出 0。
- “检查”是该例最后一次运行的检查结果，“退出 0”表示按退出码判定的用例；“运行”列出这一例的全部运行。
- “证据”是相对证据根目录 `<证据目录>/rc7/v1.0.0-rc.7/` 的路径。
- UAT-DEP-01、UAT-DEP-02 的用时是安装之后那次检查的用时，安装本身的用时见第 3 节。

### 4.1 `us-west-2`（`uat`、`plat`）

| 结果 | 用例数 |
| --- | --- |
| PASS | 43 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 43 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-02 | PASS | 7 PASS | 2026-11-03T04:25:34Z | 1 分 10 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-11-03T06:45:43Z | 27 分 45 秒 | uat-us-west-2/deploy-repeat (exit 0) | `uat-us-west-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-11-03T07:13:28Z | 7 分 35 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-11-03T06:37:31Z | 3 分 4 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-11-03T01:55:58Z | 57 分 20 秒 | uat-us-west-2/reinstall (exit 0) | `uat-us-west-2/reinstall/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-11-03T06:35:57Z | 1 分 1 秒 | plat-us-west-2/uninstall (exit 0) | `plat-us-west-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-11-03T06:40:35Z | 5 分 8 秒 | uat-us-west-2/deploy-release-refusals (exit 0) | `uat-us-west-2/deploy-release-refusals/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-11-03T04:27:36Z | 82 分 9 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-11-03T07:21:03Z | 3 分 15 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-11-03T07:24:18Z | 1 分 47 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-11-03T07:26:06Z | 4 分 41 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-11-03T07:30:47Z | 5 分 13 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-11-03T07:36:00Z | 1 分 47 秒 | uat-us-west-2/catalog-tokens (exit 0) | `uat-us-west-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-11-03T07:37:47Z | 0 分 34 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-11-03T07:38:21Z | 2 分 29 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-11-03T07:40:50Z | 0 分 37 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-11-03T07:41:27Z | 0 分 10 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-11-03T07:41:37Z | 27 分 48 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-11-03T08:09:25Z | 29 分 21 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-11-03T08:45:36Z | 103 分 2 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-11-03T10:30:08Z | 67 分 27 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-11-03T11:37:35Z | 61 分 14 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-11-03T12:38:49Z | 21 分 18 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-11-03T12:38:49Z | 9 分 59 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-11-03T13:00:07Z | 34 分 53 秒 | uat-us-west-2/monitoring (exit 0) | `uat-us-west-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-11-03T11:37:35Z | 40 分 1 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-11-03T13:35:00Z | 22 分 23 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-11-03T13:57:23Z | 45 分 30 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-11-03T14:42:53Z | 25 分 15 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-11-03T15:08:08Z | 19 分 2 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-11-03T15:27:10Z | 33 分 1 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-11-03T16:00:11Z | 19 分 48 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-11-03T16:22:35Z | 3 分 4 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-11-03T16:27:50Z | 24 分 31 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-11-03T16:52:21Z | 87 分 56 秒 | uat-us-west-2/governance (exit 0) | `uat-us-west-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-11-03T18:20:17Z | 11 分 33 秒 | uat-us-west-2/iceberg-erasure (exit 0) | `uat-us-west-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-11-03T18:31:50Z | 25 分 5 秒 | uat-us-west-2/operations (exit 0) | `uat-us-west-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-11-03T18:56:55Z | 40 分 50 秒 | uat-us-west-2/dashboards (exit 0) | `uat-us-west-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-11-03T19:37:45Z | 10 分 50 秒 | uat-us-west-2/bi (exit 0) | `uat-us-west-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-11-03T19:37:45Z | 49 分 47 秒 | uat-us-west-2/pipeline (exit 0) | `uat-us-west-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-11-03T20:28:47Z | 14 分 13 秒 | uat-us-west-2/entry-stack (exit 0)；uat-us-west-2/entry (exit 0)；uat-us-west-2/entry-stack-back (exit 0) | `uat-us-west-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-11-03T20:44:13Z | 15 分 48 秒 | uat-us-west-2/node-job (exit 0) | `uat-us-west-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-11-03T21:00:01Z | 18 分 41 秒 | uat-us-west-2/node-query (exit 0) | `uat-us-west-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

### 4.2 `us-east-2`（`uate2`）

这张表没有人工判定（没有用 `--overrides`）。它在 UAT-DEP-07（卸载 `uate2`，在性能运行之后）的核对之后重新生成（11-04 01:14）。

| 结果 | 用例数 |
| --- | --- |
| PASS | 41 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-11-03T04:51:55Z | 0 分 46 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-11-03T05:04:23Z | 22 分 8 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-11-03T05:26:31Z | 6 分 45 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-11-03T04:54:12Z | 2 分 48 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-11-04T01:10:42Z | 0 分 59 秒 | uate2-us-east-2/uninstall (exit 0) | `uate2-us-east-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-11-03T04:57:00Z | 7 分 23 秒 | uate2-us-east-2/deploy-release-refusals (exit 0) | `uate2-us-east-2/deploy-release-refusals/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-11-03T06:37:32Z | 4 分 27 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-11-03T06:41:59Z | 2 分 0 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-11-03T06:43:59Z | 4 分 43 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-11-03T06:48:42Z | 5 分 18 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-11-03T06:54:00Z | 2 分 11 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-11-03T06:56:11Z | 0 分 27 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-11-03T06:56:38Z | 2 分 20 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-11-03T06:58:58Z | 0 分 31 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-11-03T06:59:29Z | 0 分 7 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-11-03T06:59:36Z | 27 分 41 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-11-03T07:27:17Z | 29 分 47 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-11-03T08:04:21Z | 102 分 48 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-11-03T09:48:33Z | 70 分 8 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-11-03T10:58:41Z | 64 分 51 秒 | uate2-us-east-2/tables (exit 0) | `uate2-us-east-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-11-03T12:03:32Z | 21 分 33 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-11-03T12:03:32Z | 9 分 35 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-11-03T12:25:05Z | 34 分 12 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-11-03T10:58:41Z | 39 分 35 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-11-03T12:59:17Z | 24 分 13 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-11-03T13:23:30Z | 42 分 45 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-11-03T14:06:15Z | 22 分 44 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-11-03T14:28:59Z | 17 分 47 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-11-03T14:46:46Z | 26 分 47 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-11-03T15:13:33Z | 19 分 31 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-11-03T15:35:40Z | 3 分 8 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-11-03T15:40:38Z | 23 分 49 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-11-03T16:04:27Z | 88 分 1 秒 | uate2-us-east-2/governance (exit 0) | `uate2-us-east-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-11-03T17:32:28Z | 11 分 12 秒 | uate2-us-east-2/iceberg-erasure (exit 0) | `uate2-us-east-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-11-03T17:43:40Z | 26 分 9 秒 | uate2-us-east-2/operations (exit 0) | `uate2-us-east-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-11-03T18:09:49Z | 39 分 40 秒 | uate2-us-east-2/dashboards (exit 0) | `uate2-us-east-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-11-03T18:49:29Z | 10 分 51 秒 | uate2-us-east-2/bi (exit 0) | `uate2-us-east-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-11-03T18:49:29Z | 50 分 52 秒 | uate2-us-east-2/pipeline (exit 0) | `uate2-us-east-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-11-03T19:41:26Z | 14 分 12 秒 | uate2-us-east-2/entry-stack (exit 0)；uate2-us-east-2/entry (exit 0)；uate2-us-east-2/entry-stack-back (exit 0) | `uate2-us-east-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-11-03T19:56:54Z | 16 分 15 秒 | uate2-us-east-2/node-job (exit 0) | `uate2-us-east-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-11-03T20:13:09Z | 17 分 55 秒 | uate2-us-east-2/node-query (exit 0) | `uate2-us-east-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

## 5. 失败、中止与复测

没有失败、中止或复测的运行。三个证据根的 `runs.tsv` 里，每一行的退出码都是 0：`uat-us-west-2/` 51 行（UAT-DEP-06 的 `reinstall` 与 `spark-image`，加上 tag 上的 49 次），`plat-us-west-2/` 3 行（`install-platform`、`metadata-restore`、`uninstall`），`uate2-us-east-2/` 53 行（含 `install` 与 `uninstall`）。两套 UAT 的 `summary` 阶段分别记下 1069 项（`uat`）与 1075 项（`uate2`）PASS 的检查；`uate2` 与 `plat` 的 `summary.tsv` 在卸载的核对之后重新生成，分别是 1079 项与 47 项 PASS，都没有 FAIL。rc.6 时测试侧的绕行（空的 git 钩子目录、以 main 上的测试重跑发布清单拒绝）本次都没有用：[#18](https://github.com/colvenna/colvenna-lake/issues/18)、[#20](https://github.com/colvenna/colvenna-lake/issues/20) 的修复在 B7 里。

**[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的修复的运维检查（不属于用例）。** UAT 之前，在 `uat` 上、在 tag 上做了一次运维检查，证据单独放在 `uat-us-west-2/live-fixes/`（`before.*`、`due.*`、`after.*` 与 `install/`、`run.log`）。它不计入任何用例，只核对修复在真实环境里的行为：

| 修复 | 检查之前的状态 | 怎样让它到期 | 一次完整安装之后 | 结果 |
| --- | --- | --- | --- | --- |
| [#22](https://github.com/colvenna/colvenna-lake/issues/22) 平台的两条 catalog 级 `MANAGE` grant（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)） | `admin` 的 grant（rc.6 时手工续期的那条）11-09 过期；`colvenna-operator` 的 2027-01-23 过期 | 用 `ensure_grant` 以 2 天的条款各重给一次：两条都换成 2 天期的（11-05 过期），落进续期的余量 | 安装照默认条款把两条都删掉重建：`admin` 7 天（剩 6.98 天），Operator 90 天（剩 89.99 天），id 都是新的 | PASS |
| [#23](https://github.com/colvenna/colvenna-lake/issues/23) 平台的管理员 token 与 Operator 的 token（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)） | 两个 Secret 里的 token 是 [#23](https://github.com/colvenna/colvenna-lake/issues/23) 的修复之前签发的，Secret 里没有记 id；B7 上的 `prep` 没有换它们（剩余有效期还多于默认的 30 天） | 安装时设 `TOKEN_RENEW_DAYS=90`，两个 token 的剩余有效期都不到它 | 两个 Secret 都换成新签的 token，各自记下 id | PASS |
| [#24](https://github.com/colvenna/colvenna-lake/issues/24) catalog 的私有 CA 证书（PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)） | 10-25 生成，2029-01-27 过期 | 安装时设 `CA_RENEW_DAYS=900`，CA 证书的剩余有效期不到它 | 用同一个密钥与名称重签，序列号是新的，2029-02-05 过期；catalog 的 2 个 Pod 都换成新的（滚动到新证书链） | PASS |

- 这次安装是在 tag 上的一次完整的 `install.sh --release v1.0.0-rc.7 --fixtures`（27 步都 `ok`，`verify` 在最后，通过）。它对 `uat` 来说是同一提交上的重复执行，不判定任何用例；之后 `deploy` 阶段的 `deploy-repeat`（UAT-DEP-03）照常判定。
- 局限：[#22](https://github.com/colvenna/colvenna-lake/issues/22) 在真实环境里走到的是“落进续期余量的 grant”这条路径，不是“已经过期的 grant”（rc.6 时的情况），后者由离线测试覆盖；[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 是调大续期阈值、让还远没到期的 token 与证书提前续期，没有等到真的过期。
- 之后的 UAT 在同一套环境上运行：UAT-CAT-05 在第一次运行 38/38（rc.6 时它在这里得到 403）；Operator 用续签的 token 照常工作，UAT-SRC-01 的 MySQL 与 PostgreSQL 源 50/50；各引擎用重签的 CA 证书运行 UAT-CAT-01 到 UAT-CAT-12，都通过。

**UAT-DEP-06 与 UAT-DEP-03。** `reinstall`（10/10，用时 57 分 20 秒，`reinstall/kind` 为 `upgrade`）从 rc.6 的 tag（`6ad52ec4`）的安装原地升级到 B7：7 个栈就地更新，25 项有状态资源与 3 个卷保留，5 个组件注解为 B7，换镜像的是 colvenna-operator；catalog 仍是 `v1.0.0`，镜像不变，经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取。之后 `uat` 的 `deploy-repeat` 在 tag 上按清单重装（UAT-DEP-03，11/11）：7 个栈的 161 项资源、4 个节点、22 个工作负载与 3 个卷都没有变，10 个 release 收敛，没有构建镜像。

**UAT-DEP-06 与 [#28](https://github.com/colvenna/colvenna-lake/issues/28) 的升级说明。** [#28](https://github.com/colvenna/colvenna-lake/issues/28) 的修复改变了声明了 `quality` 的分层表的作业图，部署指南第 7 节写明：在这之前部署的这类表，升级之后要按用户手册第 4.3 节逐个重建。升级时 `uat` 上没有任何声明（UAT 结束时会删掉自己的 LakeTable、LakeSource 等，`kubectl` 2026-11-03 读到 0 个），所以这次升级没有要重建的表；UAT-DEP-06 的检查（AWS 有状态资源、组件的注解）也不覆盖用户的作业。这条升级路径没有实测，见第 7 节。

**UAT-DEP-07 的前置条件（`us-west-2`）。** 卸载 `plat`（05:57–06:37）时，其他环境都保持静止：`uate2` 的 `deploy` 与 `prep` 已于 05:56 结束，两套 UAT 在 06:37 才开始；`uat` 上的运维检查在 03:59 结束。`others-untouched` 比对的是其他环境与发布仓库的资源，186 项都与卸载之前相同；卸载删除了 `plat` 的 9 个 Lambda 日志组。补丁标签的核对脚本在这段时间里没有改动任何节点（`patch-tag-watch.log` 在 04:05 与 20:08 之间没有记录）。

**UAT-DEP-07（`us-east-2`）与它的前置条件。** 按用例文档第 7 节，`us-east-2` 要卸载本区域从零新建的 `uate2`。卸载在 tag 上运行（11-04 00:17:51 开始，`uninstall.sh` 01:10:41 退出 0），之后核对 4/4：账号里没有 `uate2` 的任何东西；执行机上没有记着它的区域、账号、托管区与入口域名；没有遗留的桶、数据库、DNS、日志组、OIDC、仓库、角色、密钥、栈、用户池与卷；其他环境与发布仓库在 `us-west-2`、`us-east-2` 的 99 项都与卸载之前相同。卸载删除了 `uate2` 的 11 个 Lambda 日志组，其中 9 个是栈名被截短的。卸载期间其他环境保持静止：`uat` 上的性能运行已于 00:16:54 结束，之后 `uat` 上没有运行任何测试，`plat` 已卸载。补丁标签的核对脚本在这段时间里没有改动任何节点（`patch-tag-watch.log` 在 11-03 21:09 之后没有记录）。

## 6. `v1.0.0-rc.6` 之后：本候选的变更与本次的表现

| 问题 | 本候选的变更 | 本次的表现 |
| --- | --- | --- |
| [#22](https://github.com/colvenna/colvenna-lake/issues/22) 平台身份的 catalog grant 过期后不续期（rc.6 的阻塞项） | PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)：两条 catalog 级 `MANAGE` grant 每次安装都按条款核对，已过期、剩余不到有效期的一半、到期比条款允许的晚一天以上或列不同时删掉重建 | 运维检查 PASS（第 5 节）；UAT-CAT-05 两个区域都在第一次运行 38/38 |
| [#23](https://github.com/colvenna/colvenna-lake/issues/23) 平台的两个 catalog token 不续签 | PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)：每次安装都核对两个 token，被拒或剩余有效期不到 `TOKEN_RENEW_DAYS`（默认 30 天）时重签，并把 id 记进 Secret | 运维检查 PASS；之后 Operator 用新 token 照常工作（UAT-SRC-01 等） |
| [#24](https://github.com/colvenna/colvenna-lake/issues/24) catalog 的私有 CA 证书不换 | PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)：过期或剩余不到 `CA_RENEW_DAYS`（默认 90 天）时用同一个密钥与名称重签 | 运维检查 PASS；catalog 的 Pod 滚动到新证书链，`verify` 与之后的 UAT-CAT-01 到 12 都通过 |
| [#28](https://github.com/colvenna/colvenna-lake/issues/28) UAT-MON-01 的层延迟与标记不一致 | PR [#31](https://github.com/colvenna/colvenna-lake/pull/31)：声明了 `quality` 的分层表，作业对每张流读上游只读一次（原来读两次，两个读端共用一个 Paimon consumer）；部署指南第 7 节写明已部署的这类表升级之后要重建 | UAT-MON-01 两个区域都在第一次运行 16/16：`uat` 标记 22 秒、指标 20 秒，`uate2` 28 秒、27 秒（容差 max(15 秒, 25%)）。指标余下的小偏差（每轮配对提交，偏高至多一个 checkpoint 间隔）由 [#34](https://github.com/colvenna/colvenna-lake/issues/34) 跟踪 |
| [#18](https://github.com/colvenna/colvenna-lake/issues/18)、[#20](https://github.com/colvenna/colvenna-lake/issues/20) 发布清单拒绝的测试缺陷 | PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)，只改测试 | UAT-DEP-08 两个区域都在第一次运行 14/14，没有测试侧的绕行 |
| [#30](https://github.com/colvenna/colvenna-lake/issues/30) 正式版的晋升工具 | PR [#32](https://github.com/colvenna/colvenna-lake/pull/32)：`promote.sh` 把 GO 候选晋升为正式版，不重新构建；`install.sh --release` 与 `check-release.sh` 接受正式版的名字 | 不在 UAT 内：本次只按候选安装；晋升与按正式版安装还没有在 AWS 上实测（部署指南写明），离线测试覆盖 |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) 上游 Paimon 2.0.0：流模式写有界输入、写入并行度大于 1 时，后结束的写入子任务的收尾数据可能被静默丢弃 | （没有变）UAT-CAT-01 与 UAT-CAT-04 的 `streaming-append-commits` 固定写入并行度 1；用户手册第 17 节写明 | 两个区域 UAT-CAT-01 18/18、UAT-CAT-04 19/19。这一步不覆盖多个写入子任务的数据进同一个提交：验收覆盖的缩小仍在，要等上游修复发布、升级 Paimon 之后撤掉并行度 1 |
| [#5](https://github.com/colvenna/colvenna-lake/issues/5)、[#10](https://github.com/colvenna/colvenna-lake/issues/10) Colvenna Catalog 的发布版 | （没有变）固定 `v1.0.0` | UAT-CAT-01 到 12 两个区域全部 PASS；`plat`、`uate2` 从零安装与 `uat` 的升级都 PASS；`plat` 上的两次元数据恢复（UAT-DEP-09）通过：快照恢复 936 秒、catalog 停 211 秒，按时间点恢复 1052 秒、停 211 秒 |
| 早期仓库 #172 的上游竞态：Doris Operator 建好 FE 后有时不再协调（[apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495)，未修） | （没有变） | 平台 Doris 的 7 次安装都不需要催促：`plat`、`uate2` 的从零安装在等待开始 1–2 秒内看到 BE 的 StatefulSet，其余 5 次第一眼就绪。测试用的 Doris 在 `uat` 的 UAT-CAT-03、UAT-CAT-12 各催促 1 次，这些用例都通过（`doris-touches.txt`） |

## 7. 已知缺陷与限制

本候选已知、尚未解决的缺陷与限制：

| 项 | 内容 | 影响 |
| --- | --- | --- |
| [#34](https://github.com/colvenna/colvenna-lake/issues/34) | 分层表的层延迟指标按轮次配对提交：两轮之间落了两个提交时偏高一个 checkpoint 间隔；`lastDataCommit` 先于 position 读取 | 看板与告警 `ColvennaLayerLatencyP95` 用这个指标，偏差离 120 秒的门槛还远；本次 UAT-MON-01 两个区域都通过 |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) | 上游 Paimon 2.0.0 的缺陷：流模式写有界输入、写入并行度大于 1 时，可能静默丢数据。上游 [apache/paimon#9236](https://github.com/apache/paimon/issues/9236) 未修 | 用户自己的 Flink 作业受影响，平台自己的作业不受影响；UAT-CAT-01、UAT-CAT-04 的流写步骤固定并行度 1，覆盖缩小（第 6 节） |
| [#28](https://github.com/colvenna/colvenna-lake/issues/28) 的升级说明 | 声明了 `quality` 的分层表，在修复之前部署的，升级之后要逐个重建（部署指南第 7 节）。Operator 在 savepoint 对不上时回到旧作业，这一点是按 Flink 恢复 savepoint 的规则推断的 | 没有实测：本次升级时 `uat` 上没有这类表，也没有用例走这条路径 |
| [#10](https://github.com/colvenna/colvenna-lake/issues/10) | catalog 的默认来源 GitHub Container Registry 没有实跑 | catalog 公开之前，本次都经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)） |
| 早期仓库 #172 的上游 | [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 产品安装与测试会在需要时催促 Operator 协调，本次被化解 |

其他限制：

- 只有一个 AWS 账号：跨账号安装（从发布镜像仓库跨账号拉取）没有实测；跨区域复制在 `uate2` 上实测了。
- [#22](https://github.com/colvenna/colvenna-lake/issues/22) 的修复在真实环境里走到的是落进续期余量的 grant，[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的是调大阈值之后的提前续期；已经过期的 grant、token 与证书由离线测试覆盖（第 5 节）。
- 正式版的晋升（[#30](https://github.com/colvenna/colvenna-lake/issues/30)）与按正式版安装没有在 AWS 上实测。
- CI 不能替代 UAT：本候选的每个 PR 都在确切的 head 提交上通过 GitHub Actions 的全部 8 个作业。
- 性能不在 UAT 内；吞吐、延迟分位等结论见[性能验收](../../../performance/v1.0.0-rc.7.md)。

## 8. 范围外

见候选上的[用例文档](../../cases.md)第 2 节与第 7 节：colvenna-catalog 自身的升级与回滚（归 Colvenna Catalog 自己的验收）、上游 Apache Flink CDC 的复现、`PERF=1` 的性能检查、`NODE_LOSS=drain` 等其他节点故障方式与其他中断点、离线单元测试、第二朵云（[#1](https://github.com/colvenna/colvenna-lake/issues/1)）。

各区域范围外的用例（`us-east-2` 的 UAT-DEP-02、UAT-DEP-06、UAT-DEP-09，`us-west-2` 的 UAT-DEP-01），按用例文档第 7 节的“区域”一列，在第 4 节各区域的表下单列；落在本区域范围外的运行，列为“其他运行”。[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的运维检查、改名门禁的扫描、安全清点与拒绝探测、性能验收不属于 UAT。

## 9. 证据

证据保存在执行机的 `<证据目录>/rc7/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `v1.0.0-rc.7/uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。
  - `uat.log` 记每个运行的起止与测试所在的提交，`runs.tsv` 每个运行一行，`summary.tsv` 每项检查一行，`<标签>.log` 与 `<标签>/` 是各运行的输出与证据，`<标签>/verdicts.tsv` 是各运行的判定。
  - `uat-us-west-2/` 的 `summary.tsv` 在 UAT 结束时写出；`plat-us-west-2/` 的在卸载的核对之后写出，含卸载的运行；`uate2-us-east-2/` 的在 UAT 结束时写出，卸载的核对之后再写一次，含卸载的运行。
  - `uat-us-west-2/live-fixes/`：[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的运维检查；`uat-us-west-2/manual-purge-dev-kafka.log` 与同名目录：性能运行之前删除开发用 Kafka。
- 安装证据（`install/`、`reinstall/`、`deploy-repeat/install/`）与卸载证据（`uninstall-before/`、`uninstall/`）。
- `v1.0.0-rc.7/reports/`：`us-west-2.md`、`us-east-2.md`（第 4 节的表，tag 上的 `uat_report.py` 生成，没有 `--overrides`）与 `s12.log`。
- `rename-scan/`：改名门禁的扫描，见[改名门禁的记录](../../../../release/v1.0.0-rc.7/rename.md)。
- `v1.0.0-rc.7/security-uat/`、`security-uate2/`：UAT 之后的安全清点与拒绝探测（见[安全一节](../../../../release/v1.0.0-rc.7/security.md)）；`v1.0.0-rc.7/perf-rc7p/`：性能运行。
- 执行驱动脚本与时间线：`ledger.md`（含协调人的决定与 [#28](https://github.com/colvenna/colvenna-lake/issues/28) 对升级的影响的核对）；`s*.sh`（`s1x-live-fixes.sh` 是运维检查），长时间运行的输出在 `run/`；补丁标签的核对脚本与记录 `patch-tag-watch.*`。

日志里出现的执行机上的工作区路径，本报告写作 `<clone>/.worktrees/…`。

## 10. 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中带链接的 issue 与 PR 编号（如 [#22](https://github.com/colvenna/colvenna-lake/issues/22)、PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）与其余的提交（如 `3e7344f1`、`a01301c5`）是本仓库现在的；不带链接的编号（如 #172）来自本项目早期的私有仓库，本仓库里没有对应的页面。
