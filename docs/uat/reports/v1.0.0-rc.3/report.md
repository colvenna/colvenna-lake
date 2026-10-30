---
title: "Colvenna Lake v1.0.0-rc.3 UAT 报告"
date: 2026-10-26
status: final
issue: 181
---

# v1.0.0-rc.3 UAT 报告

本文是发布候选 `v1.0.0-rc.3` 的 UAT 报告，按 Release 归档。它是技术标识全面改名为 colvenna 之后的第一个候选（#271）：改名之前拆除了全部部署，三套环境都按新名字全新安装。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)：本次 Release 的必需用例集合共 44 条（第 7 节），两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。性能不在 UAT 内，另见[性能验收](../../../performance/v1.0.0-rc.3.md)。

## 1. 结论

- 两个区域执行了本 Release 的全部必需用例：`us-west-2` 43 条中 41 条 PASS、2 条 FAIL（UAT-DEP-03、UAT-DEP-07）；`us-east-2` 41 条中 39 条 PASS、2 条 FAIL（UAT-TBL-01、UAT-DEP-07）。两个区域都没有 BLOCKED 或 NOT_RUN。必需集合里只要求在另一个区域执行的用例，在第 5 节各区域的表下单列。
- 四个 FAIL 来自三个产品缺陷（第 6 节）：
  - #278（UAT-DEP-03，`us-west-2`）：查询引擎镜像在环境仓库里的 tag 不随 digest 变，且不可改写。PR #275 重新固定了 digest，于是 `uat` 在 tag 上按清单重装时，在 `doris-image` 步骤失败。PR #280 在本候选之后修复。
  - #279（UAT-TBL-01，`us-east-2`）：DWS（一个 [Apache Flink](https://flink.apache.org/) 作业）的不兼容升级落在旧作业第一个 checkpoint 之前，colvenna-operator 拒绝了一次安全的回滚（"Job Not Found"）；改回声明之后，DWS 也没有恢复。PR #281 在本候选之后修复。
  - #282（UAT-DEP-07，两个区域）：`uninstall.sh` 按完整的栈名清扫 Lambda 日志组。改名之后栈名变长，CloudFormation 在生成的名字里把栈名截到 25 个字符，清扫匹配不到。卸载 `plat` 留下 7 个、卸载 `uate2` 留下 9 个 Lambda 日志组，`uninstall.sh` 却报告 `nothing of <env> is left`。PR #283 在本候选之后修复。
- UAT-DEP-07 的判定经过几次（第 6 节），照实记下：
  - `plat` 的第一次核对因测试缺陷失败（改名后，核对把 `plat` 自己被截短的角色名当成别的环境的），PR #277 修复；在修复的提交上重判，4/4 PASS。
  - `uate2` 的第一次核对又因同类的测试缺陷失败（这次是它自己的 RDS 实例）；用泛化的核对重判，`nothing-of-it-left` 报出了 9 个 Lambda 日志组。
  - 之后用 PR #283 的核对，对两次卸载保存的清点离线重判：两个都是 `nothing-of-it-left` FAIL。所以 UAT-DEP-07 在两个区域都判 FAIL。`us-west-2` 的表里，工具按 `uninstall-2` 判的 PASS 用 `--overrides` 改为 FAIL（第 5.1 节）。遗留的 16 个日志组已在 10-26 手工删除。
- 在 tag 上的 UAT 测试运行有四次失败：`uat` 的 `deploy-repeat`、`uate2` 的 `tables`，以及两套环境卸载之后的核对 `uninstall`。前两次没有复测，要在下一个候选上重跑。卸载不能重做，它的核对在 tag 之后的提交上对同一份证据重判（`uninstall-2`），又做了离线重判。执行中改动的测试只有卸载的核对：改名暴露的两处测试缺陷。UAT-DEP-06 在 tag 之前、`uat` 的 `prep` 里判定，第一次运行就 10/10 通过。
- `v1.0.0-rc.2` 报告里只有离线测试覆盖的路径：#261 的等待这次在真实环境里第一次走到——UAT-DEP-06 的升级换了查询引擎 FE、BE 的镜像，`doris` 步骤 201 秒通过。#233 的重试、#237 的 `backing-up`、#238 的缺行告警，本次仍然没有走到。#245 没有复现。#172 的上游缺陷仍在，产品的规避本次实际生效两次（`plat` 的安装、`uat` 的 UAT-CAT-03）。各项见第 7 节。
- 本报告只给 UAT 的结果。性能见[性能验收](../../../performance/v1.0.0-rc.3.md)，安全见[安全一节](../../../../release/v1.0.0-rc.3/security.md)。本候选能否发布，由发布检查（#181）综合 UAT、性能、安全与已知缺陷决定；必需用例有 FAIL，本候选不能发布。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.3`，annotated tag，指向 `0cb3e707`（PR #276 的合并提交） |
| 镜像与安装所用的源码 | `7ea85409`（PR #275 的合并提交，下称 B）。tag 的提交与它只差 `release/v1.0.0-rc.3/` |
| 发布清单 | [`release/v1.0.0-rc.3/manifest.json`](../../../../release/v1.0.0-rc.3/manifest.json)，schema 2：只记各镜像在发布仓库里的仓库名、tag 与 digest，不写发布仓库的账号与区域，也不写执行身份；发布仓库在安装时由 `COLVENNA_RELEASE_REGISTRY` 给出。内容如下：12 个产品镜像。查询引擎的 FE、BE 在仓库 `colvenna-lake-release/query-engine`：改名之后在 `uat` 上重建，重新固定了 digest（PR #275）。查询引擎镜像是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建，带用 Apache Doris 源码对 [Apache Paimon](https://paimon.apache.org/) 2.0.0 编译的读取器，不是 ASF 的发布，也没有得到 ASF 的认可，见[商标与命名](../../../../release/trademarks.md)。14 个第三方镜像（`third_party`）；夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像，与夹具用的 6 个第三方镜像（`fixtures`）。CLI 二进制的 SHA-256，5 个 Helm chart 的 git tree，`install.sh` 与 `uninstall.sh` 的 blob，UAT 用例文档的提交与 blob，必需用例集合（44 条）与性能口径 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B 上从源码运行，清单由升级后的 `uat` 核对生成。其余全部在 tag 上运行；凡是调用 `install.sh` 的阶段都必须如此，因为按清单安装只接受与 B 只在 `release/` 下不同的检出。例外是卸载之后的核对（`check-uninstall.sh`）：`plat` 的第一次在 tag 上，重判在 `8f8830c7`（PR #277）；`uate2` 的两次分别在 `c9df23d5`（当时的 main，已含 #277）与 `517eb9b8`（泛化的核对，在一个没有合入的分支上；修改之后成为 PR #283 里的 `ff300f1a`）；离线重判用 `e1df35a7`（PR #283 的提交，已随它合入 main）。两次卸载本身都在 tag 上运行 |
| `RUN_TAG` | `us-west-2`：`rc3w`；`us-east-2`：`rc3e`。重跑另取运行 id |

## 3. 环境身份

两个区域在同一个账号里，各自独立。账号、托管区等部署值只记在执行机未提交的设置里（`deploy/.state/<环境>/`），这里用占位符与设置的键名。版本取自安装证据（基础栈的输出、`verify` 读到的元数据库、节点上报的信息）；证据里没有的，照实写明：

| 项 | `us-west-2` | `us-east-2` |
| --- | --- | --- |
| 账号 | `<ACCOUNT_ID>`（`COLVENNA_ACCOUNT`） | 同左 |
| 环境 | `uat`：按 #271 拆除之后，在 `045d426c`（改名 PR #272 的合并提交）上按新名字从零安装（带 `--fixtures`）；之后在 B 上原地升级（UAT-DEP-06），再在 tag 上按清单重装（`deploy` 阶段的 `deploy-repeat`，即 UAT-DEP-03，失败，第 6 节）。`plat`：只装平台，在 tag 上按清单从零安装，演练元数据恢复后卸载 | `uate2`：带 `--fixtures`，在 tag 上按清单从零安装，UAT 之后卸载 |
| 可用区 | `us-west-2a`、`us-west-2b` | `us-east-2a`、`us-east-2b` |
| 节点 | 写入 t3.xlarge ×2（根卷 80 GiB）、查询 r6i.xlarge ×2（100 GiB），两个可用区各一台；AL2023 x86_64 | 同左 |
| EKS | 1.36，升级策略 `EXTENDED`（基础栈的 `KubernetesVersion`、`UpgradeSupportType`）。`uat` 与 `plat` 的节点上报的 kubelet 都是 `v1.36.5`，系统 Amazon Linux 2023.12.20261021。集群的平台版本只有 `uat` 的有记录（`eks.15`，性能运行记下的）；节点镜像的发布版本，证据里没有 | 1.36，`EXTENDED`；kubelet `v1.36.5`，系统 Amazon Linux 2023.12.20261021。平台版本与节点镜像的发布版本，证据里没有 |
| 项目元数据库 | RDS PostgreSQL 16.13（`verify` 读到的），db.t4g.small，20 GiB，单可用区，自动备份保留 1 天，存储加密 | 同左 |
| 测试源（`--fixtures`） | RDS MySQL 8.4.10（测试源步骤的输出）与 PostgreSQL 16.13（tag 上 CDK 固定的版本，安装证据没有输出），db.t4g.small | 同左 |
| 存储 | 仓库桶（S3，私有、只许 TLS、加密）；Doris 与开发用的 [Apache Kafka](https://kafka.apache.org/) 的卷为 EBS | 同左 |
| 镜像 | 每套环境运行自己 ECR 里的副本（`colvenna-lake-<env>/...`，第三方镜像在 `colvenna-lake-<env>/third-party`）。按清单安装时，从 `us-west-2` 的发布仓库（栈 `ColvennaLakeRelease`，仓库 `colvenna-lake-release/*`，改名之后新部署）按 digest 复制 | 同左，从 `us-west-2` 的发布仓库跨区域复制 |
| 入口 | `colvenna-uat.<COLVENNA_HOSTED_ZONE_NAME>`、`colvenna-plat.<COLVENNA_HOSTED_ZONE_NAME>`（Cognito、ALB、ACM） | `colvenna-uate2.<COLVENNA_HOSTED_ZONE_NAME>` |
| 数据 | 只用合成数据：每个测试建自己的库表、写自己的数据 | 同左 |
| 执行身份与执行机 | 执行机的 EC2 实例角色（`AWS_PROFILE=work`）；aarch64 的 Ubuntu 主机（us-east-1）。两个区域的 UAT 在同一台执行机上同时运行 | 同左 |
| 补丁窗口 | 执行跨过 10-26 00:00 UTC 的补丁窗口。执行机的 `Patch Group` 标签此前已经去掉。三个集群全部节点的标签也都去掉了：`uat` 的 4 个节点从 10-25 16:41 起，`plat`、`uate2` 的节点从 18:22 起。之后由脚本每 10 分钟核对一次，防止被自动补回。窗口时执行机与各节点都没有这个标签。窗口之后，标签被自动补回过几次，都在下一次核对时去掉（下面是核对发现的时刻）：执行机与 `uat` 的 4 个节点在 10-26 03:38，`uate2` 的 4 个节点在 04:09；节点故障测试新起的节点，`uat` 10:33、10:43，`uate2` 10:33、10:53。rc.3 的核对脚本 10-26 15:47 停止，由下一个候选的核对脚本接替：保留的 `uat` 继续用于下一个候选，它的节点在那之后恢复标签；`plat` 已卸载 | 同左；`uate2` 在性能验收之后卸载 |

## 4. 执行顺序

两个区域的执行有先后约束：

- 凡是调用 `install.sh` 的阶段，都必须在 tag 上运行。UAT-DEP-06 的升级例外：它在 B 上运行，清单由它生成，所以在 tag 之前。
- UAT-DEP-05 的“错误区域卸载”要探测另一个区域里可丢弃的环境：`uat` 探测 `uate2`，`uate2` 探测 `plat`，从不探测 `uat`。
- UAT-DEP-07 比对卸载前后其他环境的资源，卸载期间其他环境要保持静止。

本候选之前，先按 #271 拆除了全部部署，再按新名字全新部署（下表的前几行）。

| 时间（UTC，2026-10-25/26） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 10-25 10:52–12:40 | 两个区域 | 按 #271 拆除全部部署：卸载 `uat`；删除 M1–M5 的 17 个栈与遗留资源（含 `v1.0.0-rc.2` 时还在的 M5 入口）；删除发布仓库里保留的旧仓库 `cove-lake-release/doris` | — |
| 13:32 | — | 改名 PR #272 合入，`045d426c` | — |
| 13:34–13:40 | `us-west-2` | 部署新的发布镜像仓库 `ColvennaLakeRelease`；按 digest 复制进 `v1.0.0-rc.2` 的查询引擎 FE、BE 与 20 个第三方镜像 | — |
| 13:40–15:24 | `us-west-2` | `uat` 在 `045d426c` 上按新名字从零安装（`--fixtures`，27 步，6125 秒）；`check-install --fresh` 5/5 | —（不属于本候选的判定） |
| 15:37–15:39 | `us-west-2` | 在 `uat` 上用 `build-images.sh` 重建查询引擎镜像（时刻取自证据文件）：读取器的比对只在 FE 里顶替 Paimon 1.4.2 SDK 的几个空 jar 上与原来固定的镜像不同 | — |
| 15:59–16:40 | — | PR #273（#267）、#274（#268）与 #275（重新固定查询引擎的 digest）合入，B = `7ea85409` | — |
| 16:41 | — | 补丁标签的核对脚本启动 | — |
| 16:41–17:26 | `us-west-2` | `uat` 检出 B，`prep` 在其上从源码重装：从 `045d426c` 的安装原地升级（`reinstall`，10/10，安装 27 步 2530 秒）；之后 `spark-image` | UAT-DEP-06 |
| 17:26–17:35 | `us-west-2` | 在 B 上部署发布镜像仓库；`make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 17:36–17:52 | — | 清单 PR #276 合入（本地 CI 9/9），annotated tag 打在合并提交上 | — |
| 17:53–18:51 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（3342 秒），之后 `check-install` | UAT-DEP-02、UAT-DEP-08 |
| 17:53–19:11 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，4553 秒），之后 `check-install` | UAT-DEP-01、UAT-DEP-08 |
| 18:51–20:04 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 732 秒，catalog 停 191 秒）与按时间点（恢复 967 秒，catalog 停 192 秒） | UAT-DEP-09 |
| 19:11–19:45 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝、重复执行、中断续装 | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 |
| 19:45–20:05 | `us-east-2` | `uate2` 的 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | — |
| 20:05–20:50 | `us-west-2` | `uat` 与 `uate2` 静止时卸载 `plat`：卸载 20:05–20:44，在 tag 上核对（`uninstall`，失败）；20:49 在 `8f8830c7` 上重判（`uninstall-2`） | UAT-DEP-07 |
| 20:51–10-26 10:56 | 两个区域 | `uat` 从 `deploy` 阶段起（`deploy-repeat` 在 tag 上按清单重装，失败）、`uate2` 从 `catalog` 阶段起，两套 UAT 同时运行到最后（`uat` 10:53 结束，`uate2` 10:56 结束） | 其余用例 |
| 10-26 10:57 | `us-west-2` | 删除 UAT 的 `kafka` 阶段留下的开发用 Kafka（`tests/fixtures/kafka/dev-kafka.sh purge`，`uat-us-west-2/manual-purge-dev-kafka.log`），让性能运行独占环境 | — |
| 10:57–11:05 | `us-west-2` | `uat` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 11:04–11:10 | `us-east-2` | `uate2` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 11:11–14:00 | `us-west-2` | `uat` 单独运行性能验收 `rc3p`（不属于 UAT，见[性能验收](../../../performance/v1.0.0-rc.3.md)） | — |
| 14:01–14:57 | `us-east-2` | `uat` 静止时卸载 `uate2`：卸载 14:01–14:52；在 `c9df23d5` 上核对（`uninstall`，失败）；14:56 在 `517eb9b8` 上重判（`uninstall-2`，失败） | UAT-DEP-07 |
| 10-26 | 两个区域 | 手工删除两次卸载留下的 16 个 Lambda 日志组（`leftover-lambda-log-groups.txt`）；15:13 用 `e1df35a7`（PR #283）的核对，对两次卸载保存的清点离线重判（`*/uninstall-offline-rejudge.tsv`） | UAT-DEP-07 |

## 5. 统计与逐例结果

两个区域分别统计。各自的分母是本区域必需的用例，按候选上[用例文档](../../cases.md)第 7 节的“区域”一列。

下面的表由 `tests/acceptance/uat_report.py report` 从证据生成。`--doc` 指向候选 tag 上的用例文档：main 上的用例文档在 PR #281 之后给 UAT-TBL-01 加了 8 项检查，用它判定，`uat` 的 UAT-TBL-01 也会因缺项而判 FAIL。工具本身与 `--cases` 用的 `tests/acceptance/uat-cases.tsv`，main 上的与 tag 上的相同。

表里的列：

- 每例的预期，是用例文档里它的期望结果：期望结果表里的每项检查都出现并为 PASS，项数、最后输出与最后一行都相符；按退出码判定的用例，退出 0。
- “检查”一列是实际：该例最后一次运行的检查结果；“退出 0”表示按退出码判定的用例。
- “运行”一列列出这一例的全部运行，含失败的。
- “证据”一列是相对证据根目录 `<证据目录>/rc3/v1.0.0-rc.3/` 的路径。
- UAT-DEP-01、UAT-DEP-02 的用时，是安装之后那次检查的用时；安装本身的用时见第 4 节。

### 5.1 `us-west-2`（`uat`、`plat`）

这张表有一处人工判定（`--overrides`，只能把结果判得更差）：工具按 `uninstall-2` 判 UAT-DEP-07 为 PASS，人工改为 FAIL，理由在“说明”一列，详见第 6 节。

| 结果 | 用例数 |
| --- | --- |
| PASS | 41 |
| FAIL | 2 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 43 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-02 | PASS | 7 PASS | 2026-10-25T18:49:56Z | 0 分 47 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | FAIL | 7 PASS，4 FAIL：install-exit, every-step-done, verify-passed, install-finished | 2026-10-25T20:58:20Z | 16 分 15 秒 | uat-us-west-2/deploy-repeat (exit 1) | `uat-us-west-2/deploy-repeat/` | 退出 1；4 项 FAIL |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-25T21:14:35Z | 6 分 41 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-25T20:51:14Z | 2 分 51 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-10-25T16:41:32Z | 44 分 16 秒 | uat-us-west-2/reinstall (exit 0) | `uat-us-west-2/reinstall/` | — |
| UAT-DEP-07 | FAIL | 4 PASS | 2026-10-25T20:49:24Z | 1 分 0 秒 | plat-us-west-2/uninstall (exit 1)；plat-us-west-2/uninstall-2 (exit 0) | `plat-us-west-2/uninstall-2/` | 人工判定：产品缺陷 #282：卸载 `plat` 留下 7 个 Lambda 日志组，`uninstall.sh` 却报告没有遗留。`uninstall-2` 用的核对（`8f8830c7`）只认得栈名被截短的角色，把这些日志组算作别的环境的；用 PR #283 的核对（`e1df35a7`）对保存的清点离线重判，`nothing-of-it-left` 为 FAIL（`plat-us-west-2/uninstall-offline-rejudge.tsv`）。见第 6 节 |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-25T20:54:05Z | 4 分 15 秒 | uat-us-west-2/deploy-release-refusals (exit 0) | `uat-us-west-2/deploy-release-refusals/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-10-25T18:51:32Z | 72 分 47 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-25T21:21:16Z | 3 分 12 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-25T21:24:28Z | 1 分 42 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-25T21:26:10Z | 4 分 50 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-25T21:31:00Z | 5 分 11 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-25T21:36:11Z | 1 分 39 秒 | uat-us-west-2/catalog-tokens (exit 0) | `uat-us-west-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-25T21:37:50Z | 0 分 33 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-25T21:38:23Z | 2 分 23 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-25T21:40:46Z | 0 分 32 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-25T21:41:18Z | 0 分 9 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-25T21:41:27Z | 27 分 36 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-25T22:09:03Z | 27 分 56 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-25T22:42:24Z | 102 分 47 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-26T00:26:25Z | 66 分 22 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 37 PASS | 2026-10-26T01:32:47Z | 43 分 23 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-26T02:16:10Z | 22 分 18 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-26T02:16:10Z | 9 分 38 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-26T02:38:28Z | 42 分 9 秒 | uat-us-west-2/monitoring (exit 0) | `uat-us-west-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-26T01:32:47Z | 41 分 1 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-26T03:20:37Z | 23 分 0 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-26T03:43:37Z | 43 分 13 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-26T04:26:50Z | 23 分 9 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-26T04:49:59Z | 17 分 6 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-26T05:07:05Z | 27 分 8 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-26T05:34:13Z | 19 分 2 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-26T05:55:58Z | 3 分 2 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-26T06:01:06Z | 24 分 43 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-26T06:25:49Z | 86 分 33 秒 | uat-us-west-2/governance (exit 0) | `uat-us-west-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-26T07:52:22Z | 10 分 41 秒 | uat-us-west-2/iceberg-erasure (exit 0) | `uat-us-west-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-26T08:03:03Z | 27 分 39 秒 | uat-us-west-2/operations (exit 0) | `uat-us-west-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-26T08:30:42Z | 37 分 46 秒 | uat-us-west-2/dashboards (exit 0) | `uat-us-west-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-26T09:08:28Z | 10 分 47 秒 | uat-us-west-2/bi (exit 0) | `uat-us-west-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-26T09:08:28Z | 56 分 6 秒 | uat-us-west-2/pipeline (exit 0) | `uat-us-west-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-26T10:06:17Z | 14 分 10 秒 | uat-us-west-2/entry-stack (exit 0)；uat-us-west-2/entry (exit 0)；uat-us-west-2/entry-stack-back (exit 0) | `uat-us-west-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-26T10:21:55Z | 13 分 56 秒 | uat-us-west-2/node-job (exit 0) | `uat-us-west-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-26T10:35:51Z | 17 分 40 秒 | uat-us-west-2/node-query (exit 0) | `uat-us-west-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

### 5.2 `us-east-2`（`uate2`）

这张表没有人工判定（没有用 `--overrides`）。UAT-DEP-07 由工具按 `uninstall-2` 判为 FAIL；它所用的核对是 tag 之后的提交，见第 6 节。

| 结果 | 用例数 |
| --- | --- |
| PASS | 39 |
| FAIL | 2 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-10-25T19:10:40Z | 0 分 39 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-25T19:18:36Z | 20 分 33 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-25T19:39:09Z | 6 分 9 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-25T19:11:52Z | 2 分 33 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | FAIL | 3 PASS，1 FAIL：nothing-of-it-left | 2026-10-26T14:56:26Z | 0 分 47 秒 | uate2-us-east-2/uninstall (exit 1)；uate2-us-east-2/uninstall-2 (exit 1) | `uate2-us-east-2/uninstall-2/` | 退出 1；1 项 FAIL |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-25T19:14:25Z | 4 分 11 秒 | uate2-us-east-2/deploy-release-refusals (exit 0) | `uate2-us-east-2/deploy-release-refusals/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-25T20:51:44Z | 3 分 5 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-25T20:54:49Z | 1 分 36 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-25T20:56:25Z | 4 分 37 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-25T21:01:02Z | 5 分 31 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-25T21:06:33Z | 1 分 38 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-25T21:08:11Z | 0 分 25 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-25T21:08:36Z | 2 分 14 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-25T21:10:50Z | 0 分 28 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-25T21:11:18Z | 0 分 6 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-25T21:11:24Z | 27 分 21 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-25T21:38:45Z | 27 分 15 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-25T22:11:02Z | 102 分 25 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-25T23:54:29Z | 65 分 32 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | FAIL | 30 PASS，6 FAIL：incompatible-upgrade-rolled-back, incompatible-upgrade-alerted, rolled-back-change-reverted, dws-taskmanager-loss, dws-caught-up | 2026-10-26T01:00:01Z | 106 分 18 秒 | uate2-us-east-2/tables (exit 1) | `uate2-us-east-2/tables/` | 退出 1；6 项 FAIL；缺少：changelog-dws；期望 37 项，出现 36 项 |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-26T02:46:19Z | 23 分 2 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-26T02:46:19Z | 8 分 56 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-26T03:09:21Z | 37 分 33 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-26T01:00:01Z | 38 分 18 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-26T03:46:54Z | 22 分 30 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-26T04:09:24Z | 39 分 37 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-26T04:49:01Z | 21 分 59 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-26T05:11:00Z | 15 分 16 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-26T05:26:16Z | 24 分 29 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-26T05:50:45Z | 18 分 26 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-26T06:11:24Z | 3 分 0 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-26T06:16:13Z | 23 分 5 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-26T06:39:18Z | 86 分 0 秒 | uate2-us-east-2/governance (exit 0) | `uate2-us-east-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-26T08:05:18Z | 11 分 7 秒 | uate2-us-east-2/iceberg-erasure (exit 0) | `uate2-us-east-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-26T08:16:25Z | 25 分 1 秒 | uate2-us-east-2/operations (exit 0) | `uate2-us-east-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-26T08:41:26Z | 38 分 36 秒 | uate2-us-east-2/dashboards (exit 0) | `uate2-us-east-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-26T09:20:02Z | 10 分 26 秒 | uate2-us-east-2/bi (exit 0) | `uate2-us-east-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-26T09:20:02Z | 45 分 45 秒 | uate2-us-east-2/pipeline (exit 0) | `uate2-us-east-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-26T10:06:53Z | 14 分 53 秒 | uate2-us-east-2/entry-stack (exit 0)；uate2-us-east-2/entry (exit 0)；uate2-us-east-2/entry-stack-back (exit 0) | `uate2-us-east-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-26T10:22:58Z | 16 分 1 秒 | uate2-us-east-2/node-job (exit 0) | `uate2-us-east-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-26T10:38:59Z | 17 分 34 秒 | uate2-us-east-2/node-query (exit 0) | `uate2-us-east-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

## 6. 失败、中止与复测

UAT-DEP-06 在 tag 之前、`uat` 的 `prep` 里判定，第一次运行就 10/10 通过。在 tag 上的 UAT 测试运行有四次失败：下表的前两行没有复测；卸载不能重做，后两行是两次卸载的证据被重判的经过。失败的运行都保留在各自证据根的 `runs.tsv` 里，各自的证据在同名目录。

| 区域 | 运行 | 现象 | 原因 | 类别 | 处理 | 复测 |
| --- | --- | --- | --- | --- | --- | --- |
| `us-west-2` | `deploy-repeat`（`deploy`，UAT-DEP-03），tag | 在 tag 上按清单重装 `uat`。前 11 步成功，`doris-image` 步骤 3 秒后失败：`query-engine-fe: colvenna-lake-uat/query-engine:4.1.4-paimon-2.0.0-fe is already sha256:4608a0cb…, not the release's sha256:8ef38589…`。之后的 15 步没有运行。`install-exit`、`every-step-done`、`verify-passed`、`install-finished` 4 项 FAIL。另 7 项 PASS：栈的 161 项资源、节点、10 个 release、22 个工作负载与 3 个卷都没有变，没有构建镜像，不从上游拉镜像 | 查询引擎镜像在环境仓库里的 tag 固定为 `<DORIS_TAG>-fe`、`<DORIS_TAG>-be`，不随 digest 变；环境仓库的 tag 又不可改写。`uat` 在 `045d426c` 上全新安装时，这个 tag 放的是旧 digest（`4608a0cb…`）。PR #275 重新固定 digest 之后，UAT-DEP-06 在 B 上从源码升级：新镜像已经以 `build-*` 的 tag 在仓库里，判为“已在”，没有改这个 tag。按清单安装（`install-images.sh`）要把新 digest 放进同一个 tag，被拒绝。任何一次跨越查询引擎 digest 变化的按候选升级都会这样失败；此前这个 digest 一直没有变过，所以没有暴露 | 产品 | #278，PR #280 在本候选之后修复：环境仓库里的 tag 带上 digest 的前 12 位（`<DORIS_TAG>-fe-<digest 前缀>`），源码安装与按候选安装用同一种写法；源码安装发现 digest 已在、只挂着别的 tag 时，补上这个 tag；生命周期规则不再删除固定的镜像 | 没有复测：在下一个候选上重跑（`uat` 的升级与 UAT-DEP-03 会走到这条路径） |
| `us-east-2` | `tables`（`ingest`，UAT-TBL-01），tag | 不兼容升级（DWS 在聚合之前加过滤）没有回滚：LakeTable 停在 `Degraded`/`UpgradeFailed`，提示 "the upgrade did not start within 10 min (Job Not Found); not rolled back: the new job may have written to the target, and the old one would apply that input again. Fix the declaration"（`tables/status/incompatible-upgrade-rolled-back.json`）。测试按提示改回声明之后，DWS 仍没有恢复，之后丢失 TaskManager、追上与比对也失败：DWS 的 `order_count` 与源库不同。`incompatible-upgrade-rolled-back`、`incompatible-upgrade-alerted`、`rolled-back-change-reverted`、`dws-taskmanager-loss`、`dws-caught-up`、`rows-dws` 6 项 FAIL，`changelog-dws` 没有结果；运行 106 分钟，退出 1 | 调查结论，见 #279：（1）回滚被错误地拒绝。旧作业进入 RUNNING 之后约 16 秒就施加了升级，还在 30 秒的 checkpoint 间隔之内，旧作业没有完成过 checkpoint，`status.upgrade.fromCheckpoint` 为空。新作业按测试的预期没有起来；10 分钟后，`Rollbacks.neverWrote` 因为没有升级前的 checkpoint，判为“可能已写过”，拒绝了这次本来安全的回滚。（2）被拒之后无法恢复。改回的声明被当作没有 savepoint 的普通升级交给 Flink；这时没有运行中的作业，Flink Kubernetes Operator 应用不了。同一个测试在 `uat`、以及 rc.1 与 rc.2 的两个区域都通过：那几次，旧作业从 RUNNING 到升级之间有 22–28 秒，第一个 checkpoint 刚好已经完成 | 产品 | #279，PR #281 在本候选之后修复：升级开始时记下旧作业用过的最大 checkpoint id（没有就记 0），作为“新作业没写过”的基准；被拒之后改回声明，能证明新作业没写过，就从这次升级取的 savepoint 回滚；验收测试 `tables` 加了一步，确定会走到“第一个 checkpoint 之前升级”的路径，UAT-TBL-01 的期望从 37 项改为 45 项 | 没有复测：在下一个候选上两个区域都重跑 |
| `us-west-2` | `uninstall`（UAT-DEP-07），核对在 tag 上 | 卸载 `plat`：`uninstall.sh` 退出 0，报告 "nothing of plat is left"。核对 3 项 PASS；`others-untouched` FAIL，列出的是 `plat` 自己的 15 个角色（`ColvennaLakePlatFoundatio-…`）：它们卸载前在、卸载后不在 | CloudFormation 在生成的 IAM 角色名里把栈名截到 25 个字符；改名后前缀变长，`ColvennaLakePlatFoundation` 成了 `ColvennaLakePlatFoundatio-…`。核对按完整栈名判归属，把这些角色当成了别的环境的 | 测试（改名暴露） | PR #277：角色名的第一段是本环境某个栈名的截断（至少 20 个字符），也算本环境的。PR #283 把这条规则用到所有生成的名字，并改为至少 23 个字符：两个环境的栈名小写之后最多共享前 22 个字符 | `uninstall-2`（`8f8830c7`，同一份证据）：4/4 PASS，其他环境与发布仓库的 192 项没被动过。这个 PASS 后来被离线重判推翻（下文） |
| `us-east-2` | `uninstall` 与 `uninstall-2`（UAT-DEP-07），核对在 `c9df23d5` 与 `517eb9b8` 上 | 卸载 `uate2`：`uninstall.sh` 退出 0，报告 "nothing of uate2 is left"。`uninstall`（`c9df23d5`）：`others-untouched` FAIL，列出的是 `uate2` 自己的 2 个 RDS 实例与 3 个 RDS 日志组（`colvennalakeuate2foundatio-…`、`colvennalakeuate2testpostgr-…`）。`uninstall-2`（`517eb9b8`）：`others-untouched` PASS（105 项）；`nothing-of-it-left` FAIL，列出 9 个 Lambda 日志组：`/aws/lambda/ColvennaLakeUate2Foundati-…` 7 个，`ColvennaLakeUate2TestMysq-…` 与 `ColvennaLakeUate2TestPost-…` 各 1 个 | 第一次：同一类测试缺陷。#277 只认得截短的角色名，RDS 实例名（小写、截法不同）仍按完整栈名判归属。第二次：核对泛化到所有生成的名字（栈名被截短的写法也算本环境的），于是认出了这 9 个日志组是 `uate2` 的、卸载之后还在。它们由 Lambda 在运行时建立，不归栈管理，要由 `uninstall.sh` 按名字清扫；而清扫按完整栈名匹配（`--log-group-name-prefix "/aws/lambda/$s-"`），它自己的遗留检查也一样 | 第一次：测试；第二次：产品 | 测试的部分：`517eb9b8` 泛化的核对，修改之后成为 PR #283 里的 `ff300f1a`。产品的部分：#282，PR #283 在本候选之后修复——卸载的清扫与遗留检查都认得栈名被截短的写法，不会删到名字开头相同的别的环境 | 卸载不能重做；离线重判（下文）。下一个候选的 UAT-DEP-07 在修复之后两个区域重跑 |

**UAT-DEP-07 为什么在两个区域都判 FAIL。**

`plat` 的 `uninstall-2` 通过，只是因为它用的核对（`8f8830c7`）只认得截短的角色名：`plat` 的 7 个 Lambda 日志组（`/aws/lambda/ColvennaLakePlatFoundatio-…`）被算作别的环境的资源，卸载前后都在，`nothing-of-it-left` 与 `others-untouched` 就都通过了。

`uate2` 的 `uninstall-2` 已经说明，这是产品的缺陷：`uninstall.sh` 漏删了这类日志组，又报告没有遗留。

于是 10-26 15:13，用 PR #283 的核对（`tests/acceptance/deploy/deploy_checks.py` 在 `e1df35a7`），对两次卸载保存的清点（`uninstall-before/` 与 `uninstall/after/`）离线重判，结果在两个证据根的 `uninstall-offline-rejudge.tsv`：

| 环境 | `nothing-of-it-left` | `others-untouched` |
| --- | --- | --- |
| `plat` | FAIL：7 个 Lambda 日志组 | PASS：185 项（比 `uninstall-2` 的 192 项少的，正是这 7 个） |
| `uate2` | FAIL：9 个 Lambda 日志组 | PASS：105 项 |

所以 UAT-DEP-07 在两个区域都是 FAIL，原因是产品缺陷 #282。

- `us-west-2` 的表里，工具按 `uninstall-2` 判出的 PASS 用 `--overrides` 改为 FAIL（第 5.1 节）。
- `us-east-2` 的表里，工具按 `uninstall-2` 判为 FAIL，没有人工判定。
- 两次卸载的 `uninstall-finished` 都 PASS：它判的是 `uninstall.sh` 自己的报告，而这个报告正是错的。

遗留的 16 个日志组（`plat` 7 个，`uate2` 9 个）都已在 10-26 手工删除，名字记录在 `leftover-lambda-log-groups.txt`。同一次卸载里，名字带完整栈名的 Lambda 日志组（每套 2 个），与 RDS 的日志组，`uninstall.sh` 都删除了。

**UAT-DEP-03 的失败对 `uat` 之后各用例的影响。** `deploy-repeat` 停在 `doris-image` 时，前 11 步（`tools` 到 `operator-image`）已在 tag 上重新执行；其余各步仍是 UAT-DEP-06 在 B 上装的结果。tag 与 B 只差 `release/`，`deploy-repeat` 自己的检查也表明：栈、release、工作负载的镜像与提交都没有变。

接着 UAT-DEP-04 的中断续装从 `operator-again` 开始，在 tag 上按清单执行了 `operator-again`、`cli` 与 `verify`，3/3 通过。之后各用例运行的镜像，与清单固定的 digest 相同：性能运行的 `environment.txt` 逐项核对过其中 6 个，见[性能验收](../../../performance/v1.0.0-rc.3.md)第 2 节。

**UAT-DEP-06 与 #261 的等待。** UAT-DEP-06 按 `reinstall` 判定（10/10，用时 44 分 16 秒）：从 `045d426c` 的全新安装原地升级到 B，7 个栈就地更新，25 项有状态资源与 3 个卷保留，5 个组件注解为 B，colvenna-catalog 与 colvenna-operator 换上了新镜像。

查询引擎的镜像也换了：`reinstall/before/pods.json` 里的 FE 是 `4608a0cb…`、BE 是 `956817fd…`，`after/pods.json` 里分别是 `8ef38589…`、`d9835517…`。`doris` 步骤 201 秒通过，之后 `verify` 通过。这是 #261 新增的等待第一次在真实环境里走到：它等 FE、BE 的 StatefulSet 滚动完、Pod 运行的正是应用的镜像，才连 FE。等待本身不写日志，证据只有前后的镜像与这一步的用时。

## 7. `v1.0.0-rc.2` 之后：本候选的变更与本次的表现

| 问题 | 在 `v1.0.0-rc.2` 里怎样出现 | 本候选的变更 | 本次执行中的表现 |
| --- | --- | --- | --- |
| #268 稳态判定对 10 秒采样格点上的台阶敏感 | 性能验收 FAIL：两个终点层的格点斜率超过上限，积压并没有增长 | PR #274：积压由每个变更自己的确认与可见时刻算出，不再取样；稳态改为“后三分之一的 p95 不高出前三分之一 0.5 分钟以上，且整个窗口的最大积压不超过路径的 p99 目标”。从本候选起生效，rc.2 不改判（维护者 2026-10-25 的决定） | 性能运行 `rc3p` 的 7 层都稳态，结论 PASS（[性能验收](../../../performance/v1.0.0-rc.3.md)）。不属于 UAT |
| #267 安全一节的地址编号 | rc.2 的安全一节，按单元格给地址编号，靠手工改正 | PR #273：整节给每个地址一个编号；负载均衡器 ID 与生成名字的后缀换成占位符 | 本次的安全一节直接用生成的结果，只做了排版上的改动（[安全一节](../../../../release/v1.0.0-rc.3/security.md)第 5 节）。不属于 UAT |
| #271 技术标识全面改名，拆除全部部署后全新部署 | （rc.2 之后决定） | PR #272 改名（`cove` → `colvenna`，含 API 组、组件、镜像、设置的键名与栈名）；PR #275 在新环境上重建查询引擎镜像，重新固定 digest；发布仓库 `ColvennaLakeRelease` 新部署 | 三套环境都按新名字从零安装：`uat` 在 `045d426c` 上 27 步、`check-install --fresh` 5/5；`plat`、`uate2` 在 tag 上按清单。改名带来的两件事，本次都碰上了：栈名变长，CloudFormation 在生成的名字里把它截短，暴露了两处测试缺陷（PR #277、PR #283 的核对部分）与产品缺陷 #282（UAT-DEP-07）；查询引擎的 digest 第一次变化，暴露了 #278（UAT-DEP-03）。见第 6 节 |
| #261 Doris 步骤等 Operator 换上新的镜像 | 修复已在 rc.2 里，但当时没有哪次运行改变已有 Doris 集群的镜像，只有离线测试覆盖 | （没有变） | 第一次在真实环境里走到：UAT-DEP-06 换了 FE、BE 的镜像，`doris` 步骤 201 秒通过，`verify` 通过（第 6 节） |
| #233 CDK 解析不到账号时重跑；#237 RDS 的 `backing-up`；#238 `RowsMissing` | 这几条修复的路径在 rc.2 里没有走到 | （没有变） | 本次仍然没有走到：证据里没有一行 `cdk_deploy` 的重试；`metadata-database` 的检查读到的状态都是 `available`；`RowsMissing` 没有出现。UAT-NODE-02 与 UAT-SRC-04 两个区域都通过。这几条仍只有离线测试覆盖 |
| #245 MySQL 数据源的一张表久不 Ready | rc.2 没有复现 | （没有变，只有诊断） | 没有复现：证据里 31 次 `source-ready` 检查都 PASS，各测试收集的事件里没有 `TargetNotCommitting`，也没有保存的诊断。UAT-DSH-01 的 `source-ready`：`us-west-2` 247 秒，`us-east-2` 277 秒。根因仍未查明，issue 保持打开 |
| #172 的上游竞态：Doris Operator 建好 FE 后有时不再协调 | rc.2 发生 1 次，被产品的规避化解 | （没有变）；上游 [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 发生 2 次，都被化解：`plat` 的从零安装与 `uat` 的 UAT-CAT-03，各催促 1 次（等待开始后 15 秒）。其余各次不需要催促：`uat` 的升级，`uate2` 的从零安装、UAT-CAT-03 与 UAT-CAT-12，都在等待开始 1 秒内看到 BE 的 StatefulSet；`uate2` 的 `deploy-repeat` 与重装、`uat` 的 UAT-CAT-12，第一眼就 ready。UAT-CAT-03 与 UAT-DSH-01（BE、FE 丢失后恢复）两个区域都通过 |
| #258 宣传视频与 GIF 还是旧名字 | 已从 README 撤下 | PR #270 按 Colvenna Lake 重新渲染，PR #275 改用新的技术标识；已回到 README | 不属于 UAT |
| rc.2 时发布仓库保留的 `cove-lake-release/doris`，与账号里遗留的 M5 环境 | rc.2 的报告与安全一节列为待维护者决定 | 按 #271 的拆除删除 | 新的发布仓库只有本次的 10 个仓库；安全清点里，账号的其他资源中已没有 M5 的入口（[安全一节](../../../../release/v1.0.0-rc.3/security.md)第 1 节） |

## 8. 已知缺陷与限制

本候选已知、尚未解决的缺陷与限制：

| 项 | 内容 | 影响 |
| --- | --- | --- |
| #282 | 已在 main 上修复（PR #283），不在本候选里：卸载漏删栈名被截短的 Lambda 日志组，又报告没有遗留 | 用本候选卸载一套环境，会留下这类日志组，要手工删除（本次 7 个与 9 个，已删除） |
| #278 | 已在 main 上修复（PR #280），不在本候选里 | 按本候选的清单升级一套环境时，若环境仓库里查询引擎的 tag 放的是别的 digest（例如在 PR #275 之前安装的环境），`doris-image` 步骤失败；从零安装不受影响（`plat`、`uate2` 都通过） |
| #279 | 已在 main 上修复（PR #281），不在本候选里 | 本候选里，不兼容升级若落在旧作业第一个 checkpoint 之前，不会自动回滚，改回声明也恢复不了 |
| #245 | rc.1 性能运行里一张表 900 秒内没有 Ready，根因未查明；只有诊断 | 再发生时能看出卡在哪；本次没有复现 |
| #248 | git 历史与已关闭的 issue/PR 正文里仍有旧的部署值。维护者 2026-10-25 决定不改写历史，本仓库保持 private；v1.0 正式版 GO 时，在 `colvenna/` 下建公开仓库，只推一个 init 提交（#271 第 4 节） | 当前的树与本候选的清单里没有部署值 |
| #261 的已知缺口 | 只改配置、不换镜像的 DorisCluster 规格变化，要等 Operator 更新了 StatefulSet 之后才看得出来，`doris` 步骤仍不等它（代码注释写明） | 本次的升级与重装不涉及这种变化 |
| #172 的上游 | [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 产品安装与测试会在需要时催促 Operator 协调，本次实际发生 2 次，都被化解 |

其他限制：

- #233 的重试、#237 的 `backing-up`、#238 的 `RowsMissing`，这几条修复的路径本次在真实环境里仍没有走到（第 7 节），只有离线测试覆盖。
- UAT-DEP-03 与 UAT-TBL-01（`us-east-2`）没有复测，修复的效果要在下一个候选上验证；UAT-DEP-07 的修复也一样。
- 只有一个 AWS 账号：跨账号安装（从发布镜像仓库跨账号拉取）没有实测；跨区域复制在 `uate2` 上实测了。
- GitHub CI 因账单失败不能运行，按请求方的决定豁免；每个 PR 都在本地对确切 SHA 运行了全部作业。CI 结果不能替代 UAT。
- 性能不在 UAT 内；吞吐、延迟分位等结论见[性能验收](../../../performance/v1.0.0-rc.3.md)。

## 9. 范围外

见候选上的[用例文档](../../cases.md)第 2 节与第 7 节：`run-upgrade.sh`（开发阶段不做跨版本兼容）、上游 Apache Flink CDC 的复现、`PERF=1` 的性能检查、`NODE_LOSS=drain` 等其他节点故障方式与其他中断点、离线单元测试、第二朵云（#33）。

各区域范围外的用例（`us-east-2` 的 UAT-DEP-02、UAT-DEP-06、UAT-DEP-09，`us-west-2` 的 UAT-DEP-01），按用例文档第 7 节的“区域”一列，在第 5 节各区域的表下单列；落在本区域范围外的运行，列为“其他运行”。`uat` 在 `045d426c` 上的全新安装早于本候选，不判定本候选的任何用例。安全清点与拒绝探测、性能验收在 UAT 之后执行，不属于 UAT。

## 10. 证据

证据保存在执行机的 `<证据目录>/rc3/v1.0.0-rc.3/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。其中：
  - `uat.log` 记每个运行的起止与测试所在的提交；
  - `runs.tsv` 每个运行一行（含失败的）；
  - `summary.tsv` 每项检查一行；
  - `<标签>.log` 与 `<标签>/` 是各运行的输出与证据。

  `uat-us-west-2/` 与 `uate2-us-east-2/` 的 `summary.tsv` 在 UAT 结束时写出，不含卸载的运行；`plat-us-west-2/` 没有 `summary.tsv`，因为核对失败后，驱动脚本在写它之前就停下了。卸载各次运行的结果，在 `runs.tsv` 与各自的 `verdicts.tsv` 里。
- 各环境的安装证据（`install/`、`reinstall/`、`deploy-repeat/install/`）与卸载证据（`uninstall/`、`uninstall-before/`、`uninstall-2/`），以及离线重判 `uninstall-offline-rejudge.tsv`。
- `leftover-lambda-log-groups.txt`：手工删除的 16 个 Lambda 日志组。
- `security-uat/`、`security-uate2/`：UAT 之后的安全清点与拒绝探测（见[安全一节](../../../../release/v1.0.0-rc.3/security.md)）；`perf-rc3p/`：性能运行。
- 执行驱动脚本与时间线：
  - `../runbook-rc3.md`；
  - `../s*.sh`，输出在 `../s*.stdout`；
  - `uat` 在 `045d426c` 上的全新安装：`../f1-fresh-install.sh`、`../f1.stdout` 与 `../uat-us-west-2/`；
  - 发布仓库的部署与复制：`../deploy-registry.log`、`../registry-copy.log`；
  - 查询引擎的重建与比对：`../query-engine-rebuild/`；
  - 补丁标签的核对脚本与记录：`../patch-tag-watch.*`；
  - 各 PR 的本地 CI 记录：`../ci-*/`。
- 手工清理的记录：`uat-us-west-2/manual-purge-dev-kafka.log`。

## 11. 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #271）、提交（如 `0cb3e707`）和 tag（如 `v1.0.0-rc.3`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
