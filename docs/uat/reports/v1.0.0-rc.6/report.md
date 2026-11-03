---
title: "Colvenna Lake v1.0.0-rc.6 UAT 报告"
date: 2026-11-02
status: final
issue: 2
---

# v1.0.0-rc.6 UAT 报告

本文是发布候选 `v1.0.0-rc.6` 的 UAT 报告，按 Release 归档。本候选是本仓库（`colvenna/colvenna-lake`）构建的第一个候选，也是第一个改用独立的 Colvenna Catalog 发布版的候选（[#5](https://github.com/colvenna/colvenna-lake/issues/5)）：catalog 的服务端镜像与 Helm chart 不再在本仓库构建，是 Colvenna Catalog `v1.0.0` 的发布物，按 digest 使用。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)：本次 Release 的必需用例集合共 44 条（第 7 节），两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。性能不在 UAT 内，另见[性能验收](../../../performance/v1.0.0-rc.6.md)；改名门禁另见[改名门禁的记录](../../../../release/v1.0.0-rc.6/rename.md)。

## 1. 结论

- 两个区域分别统计：`us-west-2` 43 条中 PASS 41、FAIL 2（UAT-CAT-05、UAT-MON-01）；`us-east-2` 41 条中 PASS 40、NOT_RUN 1（UAT-DEP-07）。两个区域都没有 BLOCKED。必需集合里只要求在另一个区域执行的用例，在第 4 节各区域的表下单列。
- **UAT-CAT-05（`us-west-2`）FAIL，原因是产品缺陷 [#22](https://github.com/colvenna/colvenna-lake/issues/22)，它是本候选的阻塞项。**
  - 平台身份 `admin` 的 catalog 级 `MANAGE` grant 按验收的条款只有 7 天有效期；已有同样的 grant 时 catalog 回 409，`install.sh` 把它当作成功，留着过期的那条。
  - `uat` 上的这条 grant 在 11-01 14:36 过期，此后两次重装都没有续上。`catalog-tokens` 第一次运行 10 秒就得到 403。
  - 06:13 在 `uat` 上手工续期（删掉过期的那条，经管理面建同样的一条），重跑 `catalog-tokens-2` 38/38。协调人 2026-11-02 决定，绕行之后的通过不算，所以这一例判 FAIL（`--overrides`，第 4.1 节）。
  - 修复在 tag 之后合入 main（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)），不在本候选里。同一类问题（只在 Secret 不存在时才生成、之后不看有效期）另有两处，也已在 main 上修复，留给下一个候选：平台的两个 catalog token（[#23](https://github.com/colvenna/colvenna-lake/issues/23)，PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)）与 catalog 的私有 CA 证书（[#24](https://github.com/colvenna/colvenna-lake/issues/24)，PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)）。
  - `us-east-2` 的 `uate2` 当天从零安装，grant 是新的，UAT-CAT-05 38/38。
- **UAT-MON-01（`us-west-2`）FAIL，原因未查明（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）。** 第一次运行 16 项里只有 `layer-latency-agrees` 失败：10 笔标记的 ODS→DWD 延迟中位数 41 秒，Operator 导出的层延迟中位数 9 秒，超出 max(15 秒, 25%) 的容差。UAT 之后在 `uat` 上单独重跑 `monitoring-2`，16/16（33 秒与 31 秒），但没有修复，第一次的失败没有得到解释，这次重跑不计为 PASS（`--overrides`）。`us-east-2` 16/16（13 秒与 11 秒）。
- **UAT-DEP-07（`us-east-2`）NOT_RUN。** 协调人 2026-11-02 06:16 决定保留环境给 `v1.0.0-rc.7`，所以没有卸载 `uate2`。`us-west-2` 的 UAT-DEP-07（卸载 `plat`）4/4 PASS。
- **测试缺陷两个，修复都在 tag 之后、只改测试。** 它们让 UAT-DEP-08 的发布清单拒绝（`deploy-release-refusals`）停在中途：[#18](https://github.com/colvenna/colvenna-lake/issues/18)（PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)），临时提交执行了 clone 里的 pre-commit 钩子；[#20](https://github.com/colvenna/colvenna-lake/issues/20)（PR [#21](https://github.com/colvenna/colvenna-lake/pull/21)），schema 3 的清单里没有 `images.catalog`。两个区域都以 main 上修好的测试、以 tag 为基准（`REFUSAL_BASE`）重跑，14/14。失败的运行都保留（第 5 节）。测试侧的绕行与产品的修复分开写在第 5 节。
- **候选之前的两个产品缺陷，修复在 B 里。** 准备时在 `uat` 上发现：[#13](https://github.com/colvenna/colvenna-lake/issues/13)（PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)），ECR 按 digest 读镜像时每个 tag 回一份；[#15](https://github.com/colvenna/colvenna-lake/issues/15)（PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)），节点关机留下的 Failed Pod 被当成副本。两次停下的准备都没有生成清单，证据单独保留（第 5 节）。
- **[#5](https://github.com/colvenna/colvenna-lake/issues/5) 的接收（切换到 Colvenna Catalog）：**
  - `plat` 与 `uate2` 在 tag 上按清单从零安装（UAT-DEP-02、UAT-DEP-01，都 PASS）；`uat` 原地接管（UAT-DEP-06 PASS，从哪里升级见第 5 节）；
  - UAT-CAT-01 到 UAT-CAT-12 在两个区域都以 Colvenna Catalog `v1.0.0` 的发布物运行：`us-east-2` 12 条全部 PASS；`us-west-2` 除 UAT-CAT-05（[#22](https://github.com/colvenna/colvenna-lake/issues/22)）外全部 PASS；
  - 本候选是 NO-GO，[#5](https://github.com/colvenna/colvenna-lake/issues/5) 不关闭。
- **UAT-CAT-04 的覆盖缩小（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）。** UAT-CAT-01 与 UAT-CAT-04 的 `streaming-append-commits` 现在以写入并行度 1 运行，绕开上游 [Apache Paimon](https://paimon.apache.org/) 2.0.0 的缺陷（流模式写有界输入、写入并行度大于 1 时，后结束的写入子任务的收尾数据可能被静默丢弃）。这一步仍验证带 checkpoint 的写入经 catalog 多次提交且不丢行，但不再覆盖多个写入子任务的数据进同一个提交（第 6 节）。
- **[#10](https://github.com/colvenna/colvenna-lake/issues/10) 的实跑。** `uat` 在 B 上的 `prep` 经 `COLVENNA_CATALOG_REGISTRY` 从维护者的私有副本（`<the catalog's private release registry>`）按 digest 取 catalog 的发布物，这时 catalog 还没有公开；清单再把同样的两个 digest 复制进发布仓库，从零安装与按清单重装都从那里取。默认来源 GitHub Container Registry 的实跑要等 catalog 公开（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)）。
- UAT-CAT-10 的初始化与基线（06:13:28–06:15:08）与 `catalog-tokens-2`（06:13:32–06:15:14，另一个库）同时运行，与第一次故障的开始（06:15:10）重叠约 4 秒。UAT-CAT-10 的 PASS 不变，第 5 节写明。
- 本报告只给 UAT 的结果。性能见[性能验收](../../../performance/v1.0.0-rc.6.md)，安全见[安全一节](../../../../release/v1.0.0-rc.6/security.md)。本候选能否发布，由发布检查（[#2](https://github.com/colvenna/colvenna-lake/issues/2)）综合决定：必需用例不是全部 PASS，结论是 **NO-GO**，阻塞项是 [#22](https://github.com/colvenna/colvenna-lake/issues/22)。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.6`，annotated tag（tag 对象 `1eea41fd`），指向 `6ad52ec4`（`6ad52ec4824ae4d1fa03c0b4ba1f4bcc67cc3609`，清单 PR [#17](https://github.com/colvenna/colvenna-lake/pull/17) 的合并提交，下称 C） |
| 镜像与安装所用的源码 | `803357a5`（`803357a57160c24c45b7b58a743698e82ff09d0a`，PR [#16](https://github.com/colvenna/colvenna-lake/pull/16) 的合并提交，下称 B）。C 与它只差 `release/v1.0.0-rc.6/` |
| 发布清单 | [`release/v1.0.0-rc.6/manifest.json`](../../../../release/v1.0.0-rc.6/manifest.json)，schema 3：比 `v1.0.0-rc.5` 的 schema 2 多一节 `catalog`，产品镜像里不再有 colvenna-catalog。生成时 `check-release.sh` 核对了其中 35 个镜像、colvenna CLI 的二进制与 Colvenna Catalog `v1.0.0` 的镜像与 chart。与 rc.5 的清单相比，从 B 构建的镜像都是新的 digest（Flink 基础镜像、colvenna-operator、5 个作业镜像、控制台、BI、夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像与 CLI 镜像）；查询引擎 FE、BE 的 digest 不变，第三方镜像（`third_party` 14 个、`fixtures.third_party` 6 个）也不变 |
| Colvenna Catalog | 发布版 `v1.0.0`：tag 指向 `56dc31e2`，镜像与 chart 构建自 `77984fb2`，由它的候选 `v1.0.0-rc.4`（tag 指向 `5a11f151`）晋升而来；服务端镜像 `sha256:47d8834d463ca4b75d82f2c4f1dbb4466fb04cd8fb07c682e1163b3cffcc4a68`（与 rc.4 的相同），chart `colvenna-catalog` `1.0.0` `sha256:891ba0366a34b8523ee2eb54dfea1b6cd248e8509e700f983b0d5464dbdb1cbb`，schema 版本 4。固定在 [`deploy/images/colvenna-catalog.json`](../../../../deploy/images/colvenna-catalog.json)（PR [#12](https://github.com/colvenna/colvenna-lake/pull/12)），清单的 `catalog` 一节记下同样的值与它的晋升记录 |
| 环境 | 三套，在同一个账号里，都是改名之后按新名字安装的。`uat`（`us-west-2`，带 `--fixtures`）保留下来，原地升级：准备之前它运行的是早期私有仓库 #295 的安装，之后经过本仓库的 `e08034c0`，再升到 B（第 5 节）。`plat`（`us-west-2`，只装平台）与 `uate2`（`us-east-2`，带 `--fixtures`）在 tag 上按清单从零安装。部署值只在执行机未提交的设置里，这里用设置的键名 |
| 节点与版本 | 每套环境：写入节点 t3.xlarge ×2、查询节点 r6i.xlarge ×2，两个可用区各一台，AL2023 x86_64。EKS 1.36（`uat` 的平台版本 `eks.15`，性能运行记下的），kubelet `v1.36.5`，系统 Amazon Linux 2023.12.20261102（节点上报的） |
| 元数据库、测试源与存储 | 项目元数据库 RDS PostgreSQL 16.13，单可用区，自动备份保留 1 天（`verify` 读到的）；测试源 RDS MySQL 8.4.10（测试源步骤的输出）；仓库桶 S3（私有、只许 TLS、加密），每套 3 个 EBS 卷。只用合成数据 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B 上从源码运行，清单由升级后的 `uat` 核对生成。其余运行都在 tag 上，卸载与它的核对也在内。例外：UAT-DEP-08 的发布清单拒绝，最后一次以 main 上修好的测试（`c8cf8692`，与 tag 只差 `tests/`）运行，基准是 tag（第 5 节） |
| `RUN_TAG` | `us-west-2`：`rc6w`；`us-east-2`：`rc6e` |
| 执行身份与执行机 | 执行机的 EC2 实例角色（`AWS_PROFILE=work`）。两个区域的 UAT 在同一台执行机上同时运行 |
| 补丁窗口 | 执行跨过 11-02 00:00 UTC 的补丁窗口。`Patch Group` 标签事先去掉：执行机与 `uat` 的节点在 11-01 21:04，`plat`、`uate2` 的节点在它们安装之后（11-02 02:17）；此后由核对脚本每 10 分钟核对一次。窗口时执行机与 `uat` 的节点都没有这个标签（`plat`、`uate2` 那时还没有安装）。标签被自动补回过，都在下一次核对时去掉（下面是核对发现的时刻）：执行机与 `uat`、`plat` 的节点 03:38–03:39，`uate2` 的节点 04:09；节点故障测试新起的节点，`uate2` 18:20、18:31，`uat` 19:41、19:52（`patch-tag-watch.log`）。环境保留给下一个候选，核对脚本在性能运行期间继续运行 |

## 3. 执行顺序

两个区域的执行有先后约束：

- 凡是调用 `install.sh` 的阶段，都必须在 tag 上运行。UAT-DEP-06 的升级例外：它在 B 上运行，清单由它生成，所以在 tag 之前。
- UAT-DEP-05 的“错误区域卸载”要探测另一个区域里可丢弃的环境：`uat` 探测 `uate2`，`uate2` 探测 `plat`，从不探测 `uat`。
- UAT-DEP-07 比对卸载前后其他环境的资源，卸载期间其他环境要保持静止。

| 时间（UTC，2026-11-01/02） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 11-01 20:54–21:04 | — | 取得 `uat` 的锁；补丁标签核对脚本启动 | — |
| 21:20 | — | PR [#12](https://github.com/colvenna/colvenna-lake/pull/12) 合入（`257b0150`）：改 pin 到 Colvenna Catalog `v1.0.0`（[#10](https://github.com/colvenna/colvenna-lake/issues/10)） | — |
| 21:21–21:26 | `us-west-2` | 第一次准备（`257b0150`）：重新部署发布镜像仓库（11 个仓库，新增 `charts/colvenna-catalog`）；`prep` 的 `reinstall` 在 `install.sh` 的开始检查就停下（[#13](https://github.com/colvenna/colvenna-lake/issues/13)），什么也没有装，没有生成清单 | （候选之前） |
| 21:58 | — | PR [#14](https://github.com/colvenna/colvenna-lake/pull/14) 合入（`e08034c0`） | — |
| 22:00–22:44 | `us-west-2` | 第二次准备（`e08034c0`）：`reinstall` 从早期仓库的安装升级，catalog 从 `v1.0.0-rc.1`（schema 2）接管到 `v1.0.0`（schema 4），`verify` 之前的各步都成功，`verify` 失败（[#15](https://github.com/colvenna/colvenna-lake/issues/15)）；`make-manifest.sh` 也拒绝，发布仓库里没有 rc.6 的 tag | （候选之前） |
| 23:15 | — | PR [#16](https://github.com/colvenna/colvenna-lake/pull/16) 合入，B = `803357a5` | — |
| 23:17–23:54 | `us-west-2` | `uat` 在 B 上 `prep`：从 `e08034c0` 原地升级（`reinstall`，10/10，安装 27 步 2061 秒），catalog 经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取（[#10](https://github.com/colvenna/colvenna-lake/issues/10)）；之后 `spark-image` | UAT-DEP-06 |
| 23:54–23:59 | `us-west-2` | `make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 11-02 01:42–01:51 | — | 清单 PR [#17](https://github.com/colvenna/colvenna-lake/pull/17) 合入（GitHub Actions 8/8），annotated tag 打在合并提交上（01:50:48 推送） | — |
| 01:51–02:14 | — | 改名门禁：在 tag 上的源码扫描与按 digest 的 15 个制品扫描，02:14 分类完（[改名门禁的记录](../../../../release/v1.0.0-rc.6/rename.md)） | — |
| 01:51–02:55 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（27 步，3628 秒），之后 `check-install` | UAT-DEP-02 |
| 01:51–03:17 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，27 步，5005 秒），之后 `check-install --fresh` | UAT-DEP-01 |
| 02:55–04:10 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 734 秒，catalog 停 215 秒）与按时间点（恢复 913 秒，catalog 停 212 秒） | UAT-DEP-09 |
| 03:17–04:17 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝（停在 [#18](https://github.com/colvenna/colvenna-lake/issues/18)）、重复执行（1308 秒）、中断续装；之后 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | UAT-DEP-05、UAT-DEP-03、UAT-DEP-04 |
| 04:17–04:19 | `us-east-2` | 发布清单拒绝的第一次重跑 `deploy-release-refusals-2`（tag，绕开钩子）：停在 [#20](https://github.com/colvenna/colvenna-lake/issues/20) | — |
| 04:20–05:00 | `us-west-2` | `uat` 与 `uate2` 静止时，在 tag 上卸载 `plat`（04:20–04:58），之后核对 | UAT-DEP-07 |
| 04:59–05:05 | `us-east-2` | `deploy-release-refusals-3`：main 上修好的测试（`c8cf8692`），基准为 tag，14/14 | UAT-DEP-08 |
| 05:05–19:59 | 两个区域 | 两套 UAT 在 tag 上同时运行：`uat` 从 `deploy` 阶段起（19:59 结束），`uate2` 从 `catalog` 阶段起（18:38 结束） | 其余用例 |
| 05:16–05:22 | `us-west-2` | `uat` 的发布清单拒绝停在 [#20](https://github.com/colvenna/colvenna-lake/issues/20) 之后，以修好的测试重跑 `deploy-release-refusals-2`，14/14 | UAT-DEP-08 |
| 06:09–06:15 | `us-west-2` | `catalog-tokens` 得到 403（[#22](https://github.com/colvenna/colvenna-lake/issues/22)）；06:13 手工续期 grant；`catalog-tokens-2` 38/38，不计入 | UAT-CAT-05 |
| 06:16 | — | 协调人决定：做完整的验收，结论 NO-GO、阻塞项 [#22](https://github.com/colvenna/colvenna-lake/issues/22)；不拆除环境，留给下一个候选 | — |
| 07:08–08:13 | — | PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)、[#26](https://github.com/colvenna/colvenna-lake/pull/26)、[#27](https://github.com/colvenna/colvenna-lake/pull/27)（[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24)）合入 main，不在本候选里 | — |
| 08:55–09:17 | — | 本地 CI 补充（`ci_local.py`）在 tag 上运行，8/8 | — |
| 11:36–12:10 | `us-west-2` | `monitoring` 的 `layer-latency-agrees` FAIL（[#28](https://github.com/colvenna/colvenna-lake/issues/28)） | UAT-MON-01 |
| 18:45–18:52 | `us-east-2` | `uate2` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 19:59–20:32 | `us-west-2` | `uat` 上没有别的运行时，单独重跑 `monitoring-2`，16/16，不计入 | UAT-MON-01 |
| 20:32–20:39 | `us-west-2` | `uat` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 20:39 起 | `us-west-2` | 删除 UAT 的 `kafka` 阶段留下的开发用 [Apache Kafka](https://kafka.apache.org/)（`manual-purge-dev-kafka.log`），`uat` 单独运行性能验收 `rc6p`（不属于 UAT，见[性能验收](../../../performance/v1.0.0-rc.6.md)） | — |
| — | `us-east-2` | 按协调人的决定不卸载 `uate2` | UAT-DEP-07：NOT_RUN |

## 4. 统计与逐例结果

两个区域分别统计。各自的分母是本区域必需的用例，按候选上[用例文档](../../cases.md)第 7 节的“区域”一列。

下面的表由 tag 上的 `tests/acceptance/uat_report.py report` 从证据生成。`--doc` 指向候选 tag 上的用例文档；工具本身、`--cases` 用的 `tests/acceptance/uat-cases.tsv` 与用例文档，main 上的都与 tag 上的相同。表里的列与 rc.5 的报告相同：

- 每例的预期，是用例文档里它的期望结果：期望结果表里的每项检查都出现并为 PASS，项数、最后输出与最后一行都相符；按退出码判定的用例，退出 0。
- “检查”是该例最后一次运行的检查结果，“退出 0”表示按退出码判定的用例；“运行”列出这一例的全部运行，含失败的。
- “证据”是相对证据根目录 `<证据目录>/rc6/v1.0.0-rc.6/` 的路径。
- UAT-DEP-01、UAT-DEP-02 的用时是安装之后那次检查的用时，安装本身的用时见第 3 节。

### 4.1 `us-west-2`（`uat`、`plat`）

这张表有两处人工判定（`--overrides`，文件 `reports/overrides-us-west-2.tsv`，只能把结果判得更差）：工具按最后一次运行把 UAT-CAT-05 与 UAT-MON-01 判为 PASS，人工改为 FAIL。“说明”一列是这两处的理由，原文是英文，这里译成中文，结果与数都没有改。详见第 5 节。

| 结果 | 用例数 |
| --- | --- |
| PASS | 41 |
| FAIL | 2 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 43 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-02 | PASS | 7 PASS | 2026-11-02T02:54:03Z | 0 分 48 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-11-02T05:10:36Z | 36 分 30 秒 | uat-us-west-2/deploy-repeat (exit 0) | `uat-us-west-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-11-02T05:47:06Z | 8 分 0 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-11-02T05:05:46Z | 3 分 41 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-11-01T23:17:19Z | 36 分 27 秒 | uat-us-west-2/reinstall (exit 0) | `uat-us-west-2/reinstall/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-11-02T04:58:08Z | 1 分 1 秒 | plat-us-west-2/uninstall (exit 0) | `plat-us-west-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-11-02T05:16:32Z | 5 分 25 秒 | uat-us-west-2/deploy-release-refusals (exit 1)；uat-us-west-2/deploy-release-refusals-2 (exit 0) | `uat-us-west-2/deploy-release-refusals-2/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-11-02T02:55:28Z | 74 分 24 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-11-02T05:55:06Z | 3 分 46 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-11-02T05:58:52Z | 2 分 10 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-11-02T06:01:02Z | 3 分 29 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-11-02T06:04:31Z | 4 分 54 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | FAIL | 38 PASS | 2026-11-02T06:13:32Z | 1 分 42 秒 | uat-us-west-2/catalog-tokens (exit 1)；uat-us-west-2/catalog-tokens-2 (exit 0) | `uat-us-west-2/catalog-tokens-2/` | 人工判定：第一次运行得到 403：平台身份 `admin` 没有有效的 catalog grant（产品缺陷 [#22](https://github.com/colvenna/colvenna-lake/issues/22)：安装从不续期过期的 grant）；手工续期之后的重跑不计入（协调人 2026-11-02：绕行之后的通过不算） |
| UAT-CAT-06 | PASS | 52 PASS | 2026-11-02T06:09:35Z | 0 分 35 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-11-02T06:10:10Z | 2 分 26 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-11-02T06:12:36Z | 0 分 37 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-11-02T06:13:13Z | 0 分 11 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-11-02T06:13:24Z | 27 分 37 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-11-02T06:41:01Z | 28 分 2 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-11-02T07:15:59Z | 102 分 37 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-11-02T08:59:54Z | 70 分 30 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-11-02T10:10:24Z | 62 分 51 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-11-02T11:13:15Z | 23 分 2 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-11-02T11:13:15Z | 9 分 54 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | FAIL | 16 PASS | 2026-11-02T19:59:32Z | 32 分 27 秒 | uat-us-west-2/monitoring (exit 1)；uat-us-west-2/monitoring-2 (exit 0) | `uat-us-west-2/monitoring-2/` | 人工判定：第一次运行的 `layer-latency-agrees` FAIL（标记 41 秒，指标 9 秒）；原因未查明（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）；没有修复的重跑通过，在 [#28](https://github.com/colvenna/colvenna-lake/issues/28) 解释第一次的失败之前不算 PASS |
| UAT-TSK-01 | PASS | 47 PASS | 2026-11-02T10:10:24Z | 40 分 21 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-11-02T12:09:47Z | 24 分 23 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-11-02T12:34:10Z | 45 分 51 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-11-02T13:20:01Z | 23 分 54 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-11-02T13:43:55Z | 19 分 40 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-11-02T14:03:35Z | 32 分 59 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-11-02T14:36:34Z | 19 分 8 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-11-02T14:58:18Z | 3 分 0 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-11-02T15:03:27Z | 25 分 23 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-11-02T15:28:50Z | 86 分 38 秒 | uat-us-west-2/governance (exit 0) | `uat-us-west-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-11-02T16:55:28Z | 11 分 46 秒 | uat-us-west-2/iceberg-erasure (exit 0) | `uat-us-west-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-11-02T17:07:14Z | 27 分 42 秒 | uat-us-west-2/operations (exit 0) | `uat-us-west-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-11-02T17:34:56Z | 42 分 10 秒 | uat-us-west-2/dashboards (exit 0) | `uat-us-west-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-11-02T18:17:06Z | 10 分 47 秒 | uat-us-west-2/bi (exit 0) | `uat-us-west-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-11-02T18:17:06Z | 52 分 30 秒 | uat-us-west-2/pipeline (exit 0) | `uat-us-west-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-11-02T19:10:52Z | 14 分 20 秒 | uat-us-west-2/entry-stack (exit 0)；uat-us-west-2/entry (exit 0)；uat-us-west-2/entry-stack-back (exit 0) | `uat-us-west-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-11-02T19:26:29Z | 13 分 47 秒 | uat-us-west-2/node-job (exit 0) | `uat-us-west-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-11-02T19:40:16Z | 18 分 14 秒 | uat-us-west-2/node-query (exit 0) | `uat-us-west-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

### 4.2 `us-east-2`（`uate2`）

这张表没有人工判定（没有用 `--overrides`）。UAT-DEP-07 没有运行，工具判为 NOT_RUN，理由见第 5 节。

| 结果 | 用例数 |
| --- | --- |
| PASS | 40 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 1 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-11-02T03:16:09Z | 0 分 48 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-11-02T03:21:42Z | 24 分 42 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-11-02T03:46:24Z | 6 分 48 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-11-02T03:17:09Z | 3 分 21 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | NOT_RUN | — | — | — | — | — | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-11-02T04:59:49Z | 5 分 26 秒 | uate2-us-east-2/deploy-release-refusals (exit 1)；uate2-us-east-2/deploy-release-refusals-2 (exit 1)；uate2-us-east-2/deploy-release-refusals-3 (exit 0) | `uate2-us-east-2/deploy-release-refusals-3/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-11-02T05:05:45Z | 3 分 14 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-11-02T05:08:59Z | 1 分 49 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-11-02T05:10:48Z | 4 分 38 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-11-02T05:15:26Z | 5 分 12 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-11-02T05:20:38Z | 1 分 46 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-11-02T05:22:24Z | 0 分 26 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-11-02T05:22:50Z | 2 分 28 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-11-02T05:25:18Z | 0 分 30 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-11-02T05:25:48Z | 0 分 6 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-11-02T05:25:54Z | 27 分 53 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-11-02T05:53:47Z | 23 分 46 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-11-02T06:22:42Z | 102 分 40 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-11-02T08:06:34Z | 66 分 3 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-11-02T09:12:37Z | 60 分 35 秒 | uate2-us-east-2/tables (exit 0) | `uate2-us-east-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-11-02T10:13:12Z | 19 分 39 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-11-02T10:13:12Z | 9 分 23 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-11-02T10:32:51Z | 31 分 49 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-11-02T09:12:37Z | 39 分 13 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-11-02T11:04:40Z | 24 分 5 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-11-02T11:28:45Z | 41 分 55 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-11-02T12:10:40Z | 23 分 50 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-11-02T12:34:30Z | 19 分 14 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-11-02T12:53:44Z | 26 分 38 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-11-02T13:20:22Z | 18 分 42 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-11-02T13:41:27Z | 3 分 26 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-11-02T13:47:30Z | 24 分 51 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-11-02T14:12:21Z | 88 分 8 秒 | uate2-us-east-2/governance (exit 0) | `uate2-us-east-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-11-02T15:40:29Z | 11 分 16 秒 | uate2-us-east-2/iceberg-erasure (exit 0) | `uate2-us-east-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-11-02T15:51:45Z | 25 分 3 秒 | uate2-us-east-2/operations (exit 0) | `uate2-us-east-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-11-02T16:16:48Z | 39 分 58 秒 | uate2-us-east-2/dashboards (exit 0) | `uate2-us-east-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-11-02T16:56:46Z | 11 分 58 秒 | uate2-us-east-2/bi (exit 0) | `uate2-us-east-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-11-02T16:56:46Z | 51 分 37 秒 | uate2-us-east-2/pipeline (exit 0) | `uate2-us-east-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-11-02T17:49:34Z | 14 分 13 秒 | uate2-us-east-2/entry-stack (exit 0)；uate2-us-east-2/entry (exit 0)；uate2-us-east-2/entry-stack-back (exit 0) | `uate2-us-east-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-11-02T18:04:58Z | 15 分 45 秒 | uate2-us-east-2/node-job (exit 0) | `uate2-us-east-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-11-02T18:20:43Z | 18 分 7 秒 | uate2-us-east-2/node-query (exit 0) | `uate2-us-east-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

## 5. 失败、中止与复测

三个证据根的 `runs.tsv` 共 111 行：`uat-us-west-2/` 54 行，3 行退出 1；`plat-us-west-2/` 3 行，都退出 0；`uate2-us-east-2/` 54 行，2 行退出 1。另有两个候选之前的准备，各自的证据根是 `attempt-257b0150/` 与 `attempt-e08034c0/`，各 2 行，`reinstall` 都退出 1。失败的运行都保留，证据在同名目录；重跑用新的标签与运行 id（`rerun-uat.sh`），不覆盖第一次的结果。各证据根的 `summary.tsv` 在 UAT 之后重新生成：`uat` 1089 项 PASS、3 项 FAIL（含 `monitoring-2` 的 16 项），`uate2` 1084 项 PASS、2 项 FAIL，`plat` 47 项 PASS。

| 区域 | 运行 | 现象 | 原因 | 类别 | 处理 | 复测 |
| --- | --- | --- | --- | --- | --- | --- |
| `us-west-2` | `reinstall`（`prep`），`257b0150`，候选之前 | 136 秒后退出 1：`install.sh` 的开始检查拒绝 `COLVENNA_CATALOG_REGISTRY`，说私有副本里没有 Colvenna Catalog `v1.0.0` 的镜像 `sha256:47d8834d…`。什么也没有装，没有生成清单（候选的名字没有用掉） | ECR 的 `batch-get-image` 按 digest 读时，同一个 digest 带几个 tag 就回几份（实测 2026-11-01，us-west-2）；catalog `v1.0.0` 的镜像在私有副本里有两个 tag，开始检查数到 2 份、不是 1 份，就当成没有 | 产品 | [#13](https://github.com/colvenna/colvenna-lake/issues/13)，PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)（`e08034c0`）：按 digest 核对，不数个数 | 第二次准备的开始检查通过；B 上的 `prep` 也通过 |
| `us-west-2` | `reinstall`（`prep`），`e08034c0`，候选之前 | 2572 秒后退出 1：从早期仓库的安装升级，`verify` 之前的各步都成功，catalog 从 `v1.0.0-rc.1`（schema 2）接管到 `v1.0.0`（schema 4），两个新副本都就绪；`verify` 失败：`colvenna-catalog-…-readyz FAIL not ready`，2 个。随后 `make-manifest.sh` 也拒绝，发布仓库里没有 rc.6 的 tag | 失败的两个 Pod 属于 10-28 的旧 ReplicaSet（期望副本数 0），此前一次补丁重启时节点关机，它们以 `Failed` 结束（"terminated in response to imminent node shutdown"）。`verify`、`make-manifest.sh` 与元数据恢复按标签列 Pod，把已经结束的也当成副本 | 产品 | [#15](https://github.com/colvenna/colvenna-lake/issues/15)，PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)（`803357a5` = B）：已经结束的 Pod 不算副本 | B 上的 `prep`：这两个 Failed 的 Pod 有意保留，`verify` 与 `make-manifest.sh` 都通过（`reinstall/after/pods.json`） |
| `us-east-2` | `deploy-release-refusals`（`deploy`，UAT-DEP-08），tag | 4 项 PASS 之后停下，退出 1：临时提交时 pre-commit 的 `end-of-file-fixer` 改了临时清单并拒绝提交 | 测试的临时提交执行了执行机 clone 里装好的 pre-commit 钩子 | 测试 | [#18](https://github.com/colvenna/colvenna-lake/issues/18)。测试侧的绕行（只用于本候选的测试运行）：以 `GIT_CONFIG_COUNT`、`GIT_CONFIG_KEY_0=core.hooksPath`、`GIT_CONFIG_VALUE_0` 让 git 用一个空的钩子目录。修复在 tag 之后合入 main：PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)，只改测试 | `deploy-release-refusals-2`（tag，带绕行）：停在 [#20](https://github.com/colvenna/colvenna-lake/issues/20)，见下一行 |
| `us-east-2` | `deploy-release-refusals-2`，tag，带上面的绕行 | 5 项 PASS 之后退出 1：`KeyError: 'catalog'`。这次重跑没有设 `COLVENNA_REGION`，临时环境的区域落在 `us-east-1`（下一次设了） | tag 上的测试在 `unknown-digest` 与 `swapped-digests` 两项里还改 `images.catalog`，schema 3 的清单里没有它 | 测试 | [#20](https://github.com/colvenna/colvenna-lake/issues/20)，PR [#21](https://github.com/colvenna/colvenna-lake/pull/21)（`c8cf8692`）在 tag 之后合入 main：改 schema 3 的产品镜像；加 `REFUSAL_BASE`，让修好的测试以 tag 的树为基准。只改测试 | `deploy-release-refusals-3`：在 `c8cf8692` 上运行（与 tag 只差 `tests/`，驱动脚本核对过 `deploy/` 与 `release/` 与 tag 相同），`REFUSAL_BASE` 为 tag，14/14 PASS |
| `us-west-2` | `deploy-release-refusals`，tag，带 [#18](https://github.com/colvenna/colvenna-lake/issues/18) 的绕行 | 5 项 PASS 之后退出 1：`KeyError: 'catalog'`，与预期相同 | 同上一行（[#20](https://github.com/colvenna/colvenna-lake/issues/20)） | 测试 | 同上一行 | `deploy-release-refusals-2`：同样在 `c8cf8692` 上、以 tag 为基准，14/14 PASS |
| `us-west-2` | `catalog-tokens`（`catalog`，UAT-CAT-05），tag | 10 秒后退出 1：`403`，`Table flow_cfrc6w.orders has no permission for admin.` | 平台身份 `admin` 的 catalog 级 `MANAGE`（"platform operations: flow databases, fixtures and cleanup"）由 `install-catalog.sh` 经 `ensure_grant` 建，按条款 7 天有效（`GRANT_DAYS` 默认 7）。已有同样的 grant 时 catalog 回 409，`ensure_grant` 当作成功，留着原来那条，不看它是否过期。`uat` 上的这条 10-25 14:36 建，11-01 14:36 过期；之后在 `e08034c0` 与 B 上各运行过一次 `install.sh`，都没有续上 | 产品 | [#22](https://github.com/colvenna/colvenna-lake/issues/22)。06:13 在 `uat` 上手工绕行：经管理面删掉过期的那条，建同样的一条（11-09 06:13 过期，`uat-us-west-2/manual-admin-grant/`）。修复在 tag 之后合入 main：PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)，过期或不再符合条款的同样 grant 撤掉重建 | `catalog-tokens-2`（tag，绕行之后）：38/38 PASS，不计入：UAT-CAT-05 判 FAIL |
| `us-west-2` | `monitoring`（`ingest`，UAT-MON-01），tag | 2010 秒后退出 1：`layer-latency-agrees` FAIL，10 笔标记的 ODS→DWD 延迟都是 41 秒（中位数 41 秒），Operator 导出的层延迟中位数 9 秒，容差 max(15 秒, 25%)。其余 15 项 PASS，`source-latency-agrees` 18 秒与 13 秒 | 未查明（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）。10 笔标记的延迟相同，说明它们落进了同一个 DWD 提交；指标在当时的窗口里统计了哪些提交、为什么只有 9 秒，还不知道。是测试的取数窗口有问题，还是指标算错了，都还没有排除 | 待查 | [#28](https://github.com/colvenna/colvenna-lake/issues/28) | `monitoring-2`：UAT 之后 `uat` 上没有别的运行时单独重跑，16/16 PASS（`layer-latency-agrees` 33 秒与 31 秒），不计为 PASS |

**测试侧的绕行与产品的修复，分开列：**

- 测试侧的绕行，只在本候选的测试运行里用，不改产品：
  - 空的 git 钩子目录（[#18](https://github.com/colvenna/colvenna-lake/issues/18)）：经 `GIT_CONFIG_*` 环境变量传给 git，用于 tag 上的两套 UAT 运行与 `uate2` 的第一次重跑；只影响测试自己做的临时提交。
  - UAT-DEP-08 的发布清单拒绝以 main 上修好的测试运行（[#20](https://github.com/colvenna/colvenna-lake/issues/20)）：`c8cf8692` 与 tag 只差 `tests/`，`REFUSAL_BASE` 让它的临时检出就是候选的树。
- 产品侧的手工绕行：`uat` 上手工续期 `admin` 的 grant（[#22](https://github.com/colvenna/colvenna-lake/issues/22)）。之后的通过不计入。
- 产品的修复，在本候选里：[#13](https://github.com/colvenna/colvenna-lake/issues/13)（PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)）、[#15](https://github.com/colvenna/colvenna-lake/issues/15)（PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)）。在 tag 之后合入 main，不在本候选里：[#22](https://github.com/colvenna/colvenna-lake/issues/22)（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）、[#23](https://github.com/colvenna/colvenna-lake/issues/23)（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)）、[#24](https://github.com/colvenna/colvenna-lake/issues/24)（PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)）。
- 测试的修复，在 tag 之后合入 main：[#18](https://github.com/colvenna/colvenna-lake/issues/18)（PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)）、[#20](https://github.com/colvenna/colvenna-lake/issues/20)（PR [#21](https://github.com/colvenna/colvenna-lake/pull/21)）。

**UAT-CAT-05 为什么判 FAIL。** 第一次运行在 tag 上、按清单装好的 `uat` 上失败，原因在产品：`install.sh` 不续期平台身份已过期的 grant，任何一套环境从第一次安装起满 7 天就会这样，重跑安装也恢复不了。`uate2` 当天从零安装，grant 还没有过期，所以通过。重跑之前手工续期了 grant，绕开了缺陷本身。协调人 2026-11-02 06:16 决定：绕行之后的通过不算，本候选做完整的验收，结论 NO-GO、阻塞项 [#22](https://github.com/colvenna/colvenna-lake/issues/22)；[#22](https://github.com/colvenna/colvenna-lake/issues/22) 的修复（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）与同一类的 [#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 进入下一个候选。[#22](https://github.com/colvenna/colvenna-lake/issues/22) 仍打开，等修复在真实环境里走到（在一套 grant 已过期的环境上重跑 `install.sh`）。

**UAT-MON-01 为什么判 FAIL。** 重跑之前没有任何修复，第一次的失败也没有解释，所以不能确认它是偶发的时序问题，还是测试或指标的缺陷。[#28](https://github.com/colvenna/colvenna-lake/issues/28) 的接收条件是：找到原因并修正，或者写明理由确认是偶发，并在下一个候选上复测通过。

**UAT-DEP-06：判定的是从 `e08034c0` 的升级。** 判定 UAT-DEP-06 的是 B 上的 `reinstall`（10/10，用时 36 分 27 秒，`reinstall/kind` 为 `upgrade`）：

- 它的起点是第二次准备在 `e08034c0` 上留下的安装，不是早期仓库的安装：`e08034c0` 比 B 少了 [#15](https://github.com/colvenna/colvenna-lake/issues/15) 的修复，那次准备只在 `verify` 上因 [#15](https://github.com/colvenna/colvenna-lake/issues/15) 失败。
- 7 个栈就地更新，25 项有状态资源与 3 个卷保留，5 个组件注解为 B；换镜像的只有 colvenna-operator。catalog 的镜像在第二次准备时已经换成 `v1.0.0` 的，这次不变；catalog 经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取，`verify` 读到 schema 版本 4。
- 从 `v1.0.0-rc.1` 到 `v1.0.0` 的 catalog 接管，是第二次准备做的。它的起点是早期私有仓库 #295 的安装：栈与 4 个组件在 `92344a40`，catalog（`v1.0.0-rc.1`，chart `1.0.0-rc.1`）在同一分支晚两个提交的 `c7d3455b` 上重装过。那次 catalog 一步成功，schema 从 2 迁移到 4，两个新副本都就绪；`verify` 只因 [#15](https://github.com/colvenna/colvenna-lake/issues/15) 失败。所以本候选判定的 UAT-DEP-06 升级不跨 schema；跨 schema 的接管那一次没有作为判定运行通过。
- 之后 `uat` 的 `deploy-repeat` 在 tag 上按清单重装（UAT-DEP-03，11/11）：7 个栈的 161 项资源、4 个节点、22 个工作负载与 3 个卷都没有变，10 个 release 收敛，没有构建镜像。

**UAT-CAT-10 与 `catalog-tokens-2` 的重叠。** UAT-CAT-10 要求单独运行。`catalog-tokens-2`（06:13:32–06:15:14，读写库 `flow_cfrc6w`）与 UAT-CAT-10（库 `flow_ckrc6w_commits`）同时运行：UAT-CAT-10 的初始化与基线在 06:13:28–06:15:08，第一次故障（杀掉一个 catalog 副本）在 06:15:10，重叠的约 4 秒在第一次故障的开头。之后的故障、写入的结束与一致性核对都在 `catalog-tokens-2` 结束之后：726 个快照 id 各发布一次，一致性报告 `consistent=true`，因旧基准被拒绝之后由写入方重试的提交 522 次，先失败后解决的提交 6 次。UAT-CAT-10 的 PASS 不变，在这里写明。

**UAT-DEP-07 的前置条件。** 卸载 `plat`（04:20–04:59）时，其他环境都保持静止：`uate2` 的 `deploy-release-refusals-2` 已于 04:18:45 结束，`deploy-release-refusals-3` 在 04:59:49 才开始；`uat` 的 UAT 在 05:05 开始。`others-untouched` 比对的是其他环境与发布仓库的资源，186 项都与卸载之前相同；卸载删除了 `plat` 的 9 个 Lambda 日志组（7 个栈名被截短的、2 个带完整栈名的）。补丁标签的核对脚本在这段时间里没有改动任何节点（`patch-tag-watch.log` 在 04:09 与 18:20 之间没有记录）。

**UAT-DEP-07（`us-east-2`）为什么没有运行。** 按用例文档第 7 节，每个区域要卸载本区域从零新建的那套环境，`us-east-2` 是 `uate2`。协调人 2026-11-02 06:16 决定不拆除环境、留给下一个候选，所以卸载 `uate2` 的运行没有执行，这一例记为 NOT_RUN，不改成“不适用”。

**附注：UAT-DEP-07（`us-east-2`）的补充证据（2026-11-03）。** 判定之后，协调人 2026-11-03 决定在下一个候选开始之前卸载 `uate2`，好在 `v1.0.0-rc.7` 上从零重装。卸载用本候选 tag 上的工具（`uninstall.sh --yes uate2`，00:05–00:57 UTC），卸载前后核对账号里的资源（`check-uninstall.sh`），4 项检查都 PASS：`uninstall-finished`、`settings-record-gone`、`nothing-of-it-left`（`uate2` 的桶、库、DNS、日志组、OIDC、镜像仓库、角色、密钥、栈、用户池、卷都已不在），`others-untouched`（其他环境与发布仓库的 99 项都与卸载前相同）。证据在 `<证据目录>/uate2-us-east-2/uninstall/`、`uninstall-before/`，`runs.tsv` 多了一行 `uninstall`。这是本候选判定之后补的证据：上文的统计与逐例结果照判定时的写（UAT-DEP-07 NOT_RUN），发布结论仍是 NO-GO，阻塞项仍是 #22。

## 6. `v1.0.0-rc.5` 之后：本候选的变更与本次的表现

| 问题 | 本候选的变更 | 本次的表现 |
| --- | --- | --- |
| [#5](https://github.com/colvenna/colvenna-lake/issues/5) v1.0 改用独立的 Colvenna Catalog | 本仓库删掉 catalog 的服务端与 chart，按 digest 使用 Colvenna Catalog 的发布物：清单 schema 3 的 `catalog` 一节记下版本、两个 digest、构建所自的提交与晋升记录，`check-release.sh` 与 `install.sh --release` 核对它们；从源码安装也用固定的发布版（早期仓库的 PR #298；本仓库的 PR [#11](https://github.com/colvenna/colvenna-lake/pull/11)、[#12](https://github.com/colvenna/colvenna-lake/pull/12)） | UAT-CAT-01 到 UAT-CAT-12：`us-east-2` 全部 PASS，`us-west-2` 除 UAT-CAT-05（[#22](https://github.com/colvenna/colvenna-lake/issues/22)）外全部 PASS。`plat`、`uate2` 从零安装与 `uat` 的接管都 PASS（第 5 节）。`plat` 上的两次元数据恢复（UAT-DEP-09）通过：快照恢复 734 秒、catalog 停 215 秒，按时间点恢复 913 秒、停 212 秒 |
| [#10](https://github.com/colvenna/colvenna-lake/issues/10) 取 catalog 的发布物 | PR [#11](https://github.com/colvenna/colvenna-lake/pull/11)：默认从 GitHub Container Registry 按 digest 匿名取；`COLVENNA_CATALOG_REGISTRY` 可改指别的仓库。PR [#12](https://github.com/colvenna/colvenna-lake/pull/12)：改 pin 到 Colvenna Catalog `v1.0.0`（由它的 GO 候选 `v1.0.0-rc.4` 晋升） | 实跑经 `COLVENNA_CATALOG_REGISTRY`：`uat` 的三次准备都指向维护者的私有副本。第一次在开始检查就停下（[#13](https://github.com/colvenna/colvenna-lake/issues/13)）；第二次按 digest 取到并核对了镜像的 revision 标签与 chart 的 source-commit 注解，之后停在 [#15](https://github.com/colvenna/colvenna-lake/issues/15)；B 上那次通过。从零安装与按清单重装从发布仓库里的副本取（`catalog-release.json` 的 `from` 为 Colvenna Lake 的发布 `v1.0.0-rc.6`）。默认来源的实跑要等 catalog 公开（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)） |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) 上游 Paimon 2.0.0：流模式写有界输入、写入并行度大于 1 时，后结束的写入子任务的收尾数据可能被静默丢弃 | UAT-CAT-01 与 UAT-CAT-04 的 `streaming-append-commits` 固定写入并行度 1；用户手册第 17 节写明这个限制、条件与绕法（早期仓库的 PR #299） | 两个区域 UAT-CAT-01 18/18、UAT-CAT-04 19/19，3000 行都可读、`APPEND` 提交至少 2 次。这一步不再覆盖多个写入子任务的数据进同一个提交：这是验收覆盖的缩小，要等上游修复发布、升级 Paimon 之后撤掉并行度 1 |
| [#13](https://github.com/colvenna/colvenna-lake/issues/13) ECR 按 digest 每个 tag 回一份 | PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)：开始检查按 digest 核对，不数个数 | 第二次准备与 B 上的 `prep` 的开始检查都通过；第一次准备的失败保留在 `attempt-257b0150/` |
| [#15](https://github.com/colvenna/colvenna-lake/issues/15) 已经结束的 catalog Pod 被当成副本 | PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)：`verify`、`make-manifest.sh`、元数据恢复与取 Pod exec 的地方都跳过已经结束的 Pod | B 上的 `prep` 在两个 Failed 的 Pod 还在时，`verify` 与 `make-manifest.sh` 都通过；第二次准备的失败保留在 `attempt-e08034c0/` |
| #293 性能运行的 `adhoc-bounded` 误判（早期仓库） | 早期仓库的 PR #294，只改性能测试 | 不在 UAT 内，见[性能验收](../../../performance/v1.0.0-rc.6.md)；UAT-DSH-01 两个区域都 19/19 |
| `v1.0.0-rc.3` 的三个产品缺陷（早期仓库的 #278、#279、#282） | 修复从 rc.4 起在候选里 | 再次通过：UAT-DEP-03 两个区域都 11/11；UAT-TBL-01 两个区域都 45/45；UAT-DEP-07 在 `us-west-2` 4/4，卸载删除了 `plat` 的 9 个 Lambda 日志组，其中 7 个是栈名被截短的（`/aws/lambda/ColvennaLakePlatFoundatio-…`）。`us-east-2` 的卸载没有运行 |
| 早期仓库 #172 的上游竞态：Doris Operator 建好 FE 后有时不再协调（[apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495)，未修） | （没有变） | 平台 Doris 的 6 次安装里，`plat` 的从零安装催促 1 次（等待开始后 14 秒）；`uat` 的 `deploy-repeat` 在等待开始 1 秒内看到 BE 的 StatefulSet；其余 4 次第一眼就绪。测试用的 Doris（UAT-CAT-03、UAT-CAT-12）在两个区域各催促过，这些用例都通过（`doris-touches.txt`） |

## 7. 已知缺陷与限制

本候选已知、尚未解决的缺陷与限制：

| 项 | 内容 | 影响 |
| --- | --- | --- |
| [#22](https://github.com/colvenna/colvenna-lake/issues/22) | 产品缺陷，阻塞：平台身份 `admin` 的 catalog 级 `MANAGE` grant 7 天后过期，重跑 `install.sh` 也不续期。已在 main 上修复（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)），不在本候选里 | `us-west-2` 的 UAT-CAT-05 FAIL。任何一套环境从第一次安装起满 7 天，平台身份就进不了 catalog 的数据面，Operator 也不能再建库、建表 |
| [#23](https://github.com/colvenna/colvenna-lake/issues/23) | 产品缺陷：平台的管理员 token 与 Operator 的 token 90 天后过期，重跑 `install.sh` 不续签。已在 main 上修复（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)），不在本候选里 | 本次没有走到（token 不到 90 天）；满 90 天后安装与运维脚本、Operator 都进不了 catalog 的管理面 |
| [#24](https://github.com/colvenna/colvenna-lake/issues/24) | 产品缺陷：catalog 的私有 CA 证书 825 天后过期，安装只在 Secret 不存在时生成。已在 main 上修复（PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)），不在本候选里 | 本次没有走到；满 825 天后引擎与脚本连不上 catalog 与 [Apache Polaris](https://polaris.apache.org/) |
| [#28](https://github.com/colvenna/colvenna-lake/issues/28) | UAT-MON-01 的 `layer-latency-agrees` 在 `uat` 上失败一次，原因未查明 | `us-west-2` 的 UAT-MON-01 FAIL；重跑通过，不计入 |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) | 上游 Paimon 2.0.0 的缺陷：流模式写有界输入、写入并行度大于 1 时，可能静默丢数据。上游 [apache/paimon#9236](https://github.com/apache/paimon/issues/9236) 未修 | 用户自己的 Flink 作业受影响，平台自己的作业不受影响；UAT-CAT-01、UAT-CAT-04 的流写步骤固定并行度 1，覆盖缩小（第 6 节） |
| 早期仓库 #172 的上游 | [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 产品安装与测试会在需要时催促 Operator 协调，本次被化解 |

其他限制：

- 只有一个 AWS 账号：跨账号安装（从发布镜像仓库跨账号拉取）没有实测；跨区域复制在 `uate2` 上实测了。
- catalog 的默认来源 GitHub Container Registry 没有实跑：catalog 还没有公开，本次都经 `COLVENNA_CATALOG_REGISTRY` 从私有副本取（[#10](https://github.com/colvenna/colvenna-lake/issues/10)，M3 之后在下一个候选上实跑）。
- CI 不能替代 UAT：本候选的每个 PR 都在确切的 head 提交上通过 GitHub Actions 的全部 8 个作业，tag 上另有一次本地 CI 补充，8/8。
- 性能不在 UAT 内；吞吐、延迟分位等结论见[性能验收](../../../performance/v1.0.0-rc.6.md)。

## 8. 范围外

见候选上的[用例文档](../../cases.md)第 2 节与第 7 节：colvenna-catalog 自身的升级与回滚（归 Colvenna Catalog 自己的验收）、上游 Apache Flink CDC 的复现、`PERF=1` 的性能检查、`NODE_LOSS=drain` 等其他节点故障方式与其他中断点、离线单元测试、第二朵云（[#1](https://github.com/colvenna/colvenna-lake/issues/1)）。

各区域范围外的用例（`us-east-2` 的 UAT-DEP-02、UAT-DEP-06、UAT-DEP-09，`us-west-2` 的 UAT-DEP-01），按用例文档第 7 节的“区域”一列，在第 4 节各区域的表下单列；落在本区域范围外的运行，列为“其他运行”。改名门禁的扫描、安全清点与拒绝探测、性能验收不属于 UAT。

## 9. 证据

证据保存在执行机的 `<证据目录>/rc6/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `v1.0.0-rc.6/uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。
  - `uat.log` 记每个运行的起止与测试所在的提交，`runs.tsv` 每个运行一行，`summary.tsv` 每项检查一行，`<标签>.log` 与 `<标签>/` 是各运行的输出与证据，`<标签>/verdicts.tsv` 是各运行的判定。
  - 三个 `summary.tsv` 在 UAT 之后由 tag 上的 `uat_report.py summary` 重新生成（11-02 20:39）；`plat-us-west-2/` 的含卸载的运行。
  - `uat-us-west-2/manual-admin-grant/`：[#22](https://github.com/colvenna/colvenna-lake/issues/22) 的手工续期（续期之前与之后的 grant，以及一次探测）；`uat-us-west-2/manual-purge-dev-kafka.log` 与同名目录：性能运行之前删除开发用 Kafka。
- `v1.0.0-rc.6/attempt-257b0150/`、`attempt-e08034c0/`：候选之前的两次准备（[#13](https://github.com/colvenna/colvenna-lake/issues/13)、[#15](https://github.com/colvenna/colvenna-lake/issues/15)），各有 `uat-us-west-2/`（`runs.tsv`、`reinstall/`）与 `s1.log`。
- `v1.0.0-rc.6/reports/`：`us-west-2.md`、`us-east-2.md`（第 4 节的表）、`overrides-us-west-2.tsv`（两处人工判定）与 `s12.log`。
- `rename-scan/`：改名门禁的扫描，见[改名门禁的记录](../../../../release/v1.0.0-rc.6/rename.md)。
- `v1.0.0-rc.6/security-uat/`、`security-uate2/`：UAT 之后的安全清点与拒绝探测（见[安全一节](../../../../release/v1.0.0-rc.6/security.md)）；`v1.0.0-rc.6/perf-rc6p/`：性能运行。
- 执行驱动脚本与时间线：`runbook-rc6.md`、`ledger.md`（含协调人的决定）；`s*.sh`，长时间运行的输出在 `run/`；`no-hooks/`：[#18](https://github.com/colvenna/colvenna-lake/issues/18) 的绕行用的空钩子目录；本地 CI 补充的记录 `ci/tag-6ad52ec4/`；补丁标签的核对脚本与记录 `patch-tag-watch.*`。

日志里出现的执行机上的工作区路径，本报告写作 `<clone>/.worktrees/…`。

## 10. 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Polaris, Polaris, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中带链接的 issue 与 PR 编号（如 [#22](https://github.com/colvenna/colvenna-lake/issues/22)、PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）与其余的提交（如 `803357a5`、`e08034c0`）是本仓库现在的；不带链接的编号（如 #172、#293、PR #298）与提交 `92344a40`、`c7d3455b` 来自本项目早期的私有仓库，本仓库里没有对应的页面。
