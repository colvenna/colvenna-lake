---
title: "Colvenna Lake v1.0.0-rc.2 UAT 报告"
date: 2026-10-25
status: final
issue: 181
---

# v1.0.0-rc.2 UAT 报告

> 本文记录的是当时的情况：产品当时名为 cove-lake，技术标识以 cove 开头；2026-10-24/25 改为 Colvenna Lake 与 colvenna（#258、#271）。

本文是发布候选 `v1.0.0-rc.2` 的 UAT 报告，按 Release 归档。产品在 2026-10-24 改名为 Colvenna Lake（#258），技术标识（`cove`、`cove-lake`、`cove-lake-<env>` 等）不变。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)：本次 Release 的必需用例集合共 44 条（第 7 节），两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。性能不在 UAT 内，另见[性能验收](../../../performance/v1.0.0-rc.2.md)。

## 1. 结论

- 两个标准区域执行了本 Release 的必需用例：`us-west-2` 43 条全部 PASS；`us-east-2` 41 条全部 PASS（UAT-DEP-07 卸载 `uate2`，排在性能验收之后）。两个区域都没有 FAIL 或 BLOCKED。必需集合里只要求在另一个区域执行的用例，在第 5 节各区域的表下单列。

- 在 tag 上的 UAT 测试运行（两个区域的 `deploy` 阶段与之后的各阶段，以及运维执行的 `install`、`install-platform`、`metadata-restore` 与 `uninstall`）都没有失败，没有复测；执行中也没有改动测试。UAT-DEP-06 在 tag 之前、`uat` 的 `prep` 里判定，运行了三次，前两次失败，原始记录都保留（第 6 节）：
  - 第一次在 `f4fad85b` 上，`doris` 步骤失败，是产品缺陷 #261：#258 改了查询引擎镜像的仓库名，`install-doris.sh` 没等 [Apache Doris](https://doris.apache.org/) Operator 换上新规格就连 FE，报错又被丢弃。PR #262 修复，镜像与安装所用的提交（B）顺延到修复之后。
  - 第二次在 B 上，`catalog` 步骤失败，是执行者的错误：重跑时把上一次失败的提交当成了镜像的提交。不是产品或测试的缺陷。
  - 第三次在 B 上 10/10 通过，UAT-DEP-06 按它判定。它升级的是第一次运行已部分升级过的环境，所以从 `v1.0.0-rc.1` 的升级是由第一次与第三次运行合起来走完的（第 6 节）。
- `v1.0.0-rc.1` 报告里计划在本候选修复的 #233、#237、#238，以及之后记下的 #234、#227、#244 与 #248 的清单部分都已修进本候选，受影响的用例两个区域都通过；#245 根因未明，只加了诊断，本次没有复现，issue 保持打开。各项在本次执行中的表现见第 7 节。其中 #233 的重试、#237 的 `backing-up`、#238 的缺行告警、#261 在已有 Doris 集群换镜像时的等待这几条路径，本次在真实环境里没有走到，只有离线测试覆盖。
- #172 的上游缺陷仍在，产品的规避本次实际生效一次（`uat` 的 UAT-CAT-03 催促 Operator 1 次）。
- 本报告只给 UAT 的结果。性能见[性能验收](../../../performance/v1.0.0-rc.2.md)，安全见[安全一节](../../../../release/v1.0.0-rc.2/security.md)。本候选能否发布，由发布检查（#181）综合 UAT、性能、安全与已知缺陷决定。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.2`，annotated tag，指向 `83c03c76`（PR #263 的合并提交） |
| 镜像与安装所用的源码 | `9ab8b2a7`（PR #262 的合并提交，下称 B）。tag 的提交与它只差 `release/v1.0.0-rc.2/` |
| 发布清单 | [`release/v1.0.0-rc.2/manifest.json`](../../../../release/v1.0.0-rc.2/manifest.json)，schema 2：只记各镜像在发布仓库里的仓库名、tag 与 digest，不写发布仓库的账号与区域，也不写执行身份（#248）；发布仓库在安装时由 `COVE_RELEASE_REGISTRY` 给出。内容：12 个产品镜像（查询引擎的 FE、BE 改名为 `query-engine-fe`、`query-engine-be`，仓库 `cove-lake-release/query-engine`，#258；查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，带用 Apache Doris 源码对 [Apache Paimon](https://paimon.apache.org/) 2.0.0 编译的读取器，不是 ASF 的发布，也没有得到 ASF 的认可，见[商标与命名](../../../../release/trademarks.md)），14 个第三方镜像（`third_party`，#234），夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像与夹具用的 6 个第三方镜像（`fixtures`），CLI 二进制的 SHA-256，5 个 Helm chart 的 git tree，`install.sh` 与 `uninstall.sh` 的 blob，UAT 用例文档的提交与 blob，必需用例集合（44 条）与性能口径 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B 上从源码运行（清单由升级后的 `uat` 核对生成）；其余全部在 tag 上运行（凡是调用 `install.sh` 的阶段都必须如此：按清单安装只接受与 B 只在 `release/` 下不同的检出）。执行中没有改动测试 |
| `RUN_TAG` | `us-west-2`：`rc2w`；`us-east-2`：`rc2e`。重跑另取运行 id |

## 3. 环境身份

两个区域在同一个账号里，各自独立。账号、托管区等部署值只记在执行机未提交的设置里（`deploy/.state/<环境>/`），这里用占位符与设置的键名。版本取自安装证据（基础栈的输出、`verify` 读到的元数据库、节点上报的信息）；证据里没有的，照实写明：

| 项 | `us-west-2` | `us-east-2` |
| --- | --- | --- |
| 账号 | `<ACCOUNT_ID>`（`COVE_ACCOUNT`） | 同左 |
| 环境 | `uat`：已有的环境（`v1.0.0-rc.1` 的那套，装在 rc.1 的 tag 上），在 B 上原地升级（UAT-DEP-06），之后在 tag 上按清单重装（`deploy` 阶段的 `deploy-repeat`，即 UAT-DEP-03）；`plat`：只装平台，在 tag 上按清单从零安装，演练元数据恢复后卸载 | `uate2`：带 `--fixtures`，在 tag 上按清单从零安装，UAT 之后卸载 |
| 可用区 | `us-west-2a`、`us-west-2b` | `us-east-2a`、`us-east-2b` |
| 节点 | 写入 t3.xlarge ×2（根卷 80 GiB）、查询 r6i.xlarge ×2（100 GiB），两个可用区各一台；AL2023 x86_64 | 同左 |
| EKS | 1.36，升级策略 `EXTENDED`（基础栈的 `KubernetesVersion`、`UpgradeSupportType`）。节点上报 kubelet：`uat` `v1.36.4`、系统 Amazon Linux 2023.12.20261012；`plat` `v1.36.5`、2023.12.20261016。集群的平台版本只有 `uat` 的有记录（`eks.15`，性能运行记下的），节点镜像的发布版本证据里没有 | 1.36，`EXTENDED`；kubelet `v1.36.5`、系统 Amazon Linux 2023.12.20261016。平台版本与节点镜像的发布版本证据里没有 |
| 项目元数据库 | RDS PostgreSQL 16.13（`verify` 读到的），db.t4g.small，20 GiB，单可用区，自动备份保留 1 天，存储加密 | 同左 |
| 测试源（`--fixtures`） | RDS MySQL 8.4.10（测试源栈的输出）与 PostgreSQL 16.13（tag 上 CDK 固定的版本，安装证据没有输出），db.t4g.small | 同左 |
| 存储 | 仓库桶（S3，私有、只许 TLS、加密），Doris 与开发用的 [Apache Kafka](https://kafka.apache.org/) 的卷为 EBS | 同左 |
| 镜像 | 每套环境运行自己 ECR 里的副本（`cove-lake-<env>/...`，第三方镜像在 `cove-lake-<env>/third-party`）；按清单安装时从 `us-west-2` 的发布仓库按 digest 复制 | 同左，从 `us-west-2` 的发布仓库跨区域复制 |
| 入口 | `cove-uat.<COVE_HOSTED_ZONE_NAME>`、`cove-plat.<COVE_HOSTED_ZONE_NAME>`（Cognito、ALB、ACM） | `cove-uate2.<COVE_HOSTED_ZONE_NAME>` |
| 数据 | 只用合成数据：每个测试建自己的库表、写自己的数据 | 同左 |
| 执行身份与执行机 | 执行机的 EC2 实例角色（`AWS_PROFILE=work`）；aarch64 的 Ubuntu 主机（us-east-1），两个区域的 UAT 在同一台执行机上同时运行 | 同左 |
| 补丁窗口 | 执行跨过 10-25 00:00 UTC 的补丁窗口。执行机的 `Patch Group` 标签此前已长期去掉；10-24 05:40（`uat` 的 4 个节点）与 11:34（`plat`、`uate2` 的节点）起去掉了三个集群全部节点的标签，由脚本每 10 分钟核对一次，防止被自动补回。窗口时执行机与各节点都没有这个标签。10-25 03:06–04:27 之间，标签被自动补回到节点故障测试新起的节点、两套环境的全部节点（`uat` 03:47、`uate2` 04:07）与执行机（03:36）上，都在下一次核对时去掉。性能验收结束后，10-25 07:37 给保留的 `uat` 的 4 个节点恢复了标签；`plat` 已卸载 | 同左；`uate2` 在性能验收之后卸载 |

## 4. 执行顺序

两个区域的执行有先后约束：凡是调用 `install.sh` 的阶段必须在 tag 上运行（UAT-DEP-06 的升级在 B 上，清单由它生成，所以在 tag 之前）；UAT-DEP-05 的"错误区域卸载"要探测另一个区域里可丢弃的环境（`uat` 探测 `uate2`，`uate2` 探测 `plat`，从不探测 `uat`）；UAT-DEP-07 比对卸载前后其他环境的资源，卸载期间其他环境要保持静止。

| 时间（UTC，2026-10-24/25） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 10-24 08:31–08:58 | `us-west-2` | `uat` 检出 `f4fad85b`（PR #260 合入后的 main，#261 修复之前），`prep` 在其上从源码重装：从 `v1.0.0-rc.1` 的环境（`d5acb09e`）原地升级，`DORIS_IMAGE_SOURCE=<发布仓库>/cove-lake-release/doris`。在 `doris` 步骤失败（第 6 节）；之后 `spark-image` | UAT-DEP-06（失败） |
| 08:58–09:00 | `us-west-2` | 在 `f4fad85b` 上部署发布镜像仓库：此后栈里是新的 `cove-lake-release/query-engine` 与 `cove-lake-release/third-party`，旧的 `cove-lake-release/doris` 不在栈里但保留，仍存着 rc.1 的 FE 与 BE。`make-manifest.sh` 拒绝生成清单：`uat` 的开放层栈还是 `d5acb09e` 装的 | — |
| 09:35 | — | #261 的修复 PR #262 合入，B = `9ab8b2a7` | — |
| 09:37–09:51 | `us-west-2` | `uat` 检出 B，`reinstall-2`：在 `catalog` 步骤失败（执行者的错误，第 6 节） | UAT-DEP-06（失败） |
| 09:52–10:34 | `us-west-2` | `reinstall-3`：在 B 上重装，10/10（安装 27 步 2391 秒）；`spark-image-2` | UAT-DEP-06 |
| 10:34–10:47 | `us-west-2` | 在 B 上再部署一次发布镜像仓库；`make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 10:49–11:08 | — | 清单 PR #263 合入（本地 CI 9/9），annotated tag 打在合并提交上 | — |
| 11:09–12:08 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（3491 秒） | UAT-DEP-02、UAT-DEP-08 |
| 11:09–12:29 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，4692 秒） | UAT-DEP-01、UAT-DEP-08 |
| 12:08–13:21 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 751 秒，catalog 停 185 秒）与按时间点（恢复 923 秒，catalog 停 191 秒） | UAT-DEP-09 |
| 12:29–13:03 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝、重复执行、中断续装 | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 |
| 13:03–13:22 | `us-east-2` | `uate2` 的 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | — |
| 13:23–14:05 | `us-west-2` | `uat` 与 `uate2` 静止时卸载 `plat` 并核对 | UAT-DEP-07 |
| 14:06–10-25 04:36 | 两个区域 | `uat` 从 `deploy` 阶段起（`deploy-repeat` 在 tag 上按清单重装）、`uate2` 从 `catalog` 阶段起，两套 UAT 同时运行到 `summary`（`uate2` 10-25 03:27 结束） | 其余用例 |
| 10-25 03:28–03:34 | `us-east-2` | `uate2` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 04:36–04:43 | `us-west-2` | `uat` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 04:44–04:47 | `us-west-2` | 性能运行 `rc2p` 拒绝开始：UAT 的 `kafka` 阶段留下的开发用 Kafka 还装着，环境不是独占的。按 `tests/fixtures/kafka/dev-kafka.sh purge` 删除（`uat-us-west-2/manual-purge-dev-kafka.log`） | — |
| 04:47–07:34 | `us-west-2` | `uat` 单独运行性能验收 `rc2q`（不属于 UAT，见[性能验收](../../../performance/v1.0.0-rc.2.md)） | — |
| 10-25 07:36–08:30 | `us-east-2` | `uat` 静止时卸载 `uate2` 并核对 | UAT-DEP-07 |

## 5. 统计与逐例结果

两个区域分别统计，各自的分母是本区域必需的用例（按候选上[用例文档](../../cases.md)第 7 节的“区域”一列）；下面的表由 `tests/acceptance/uat_report.py report` 从证据生成。每例的预期是用例文档里它的期望结果：期望结果表里的每项检查都出现并为 PASS，项数、最后输出与最后一行都相符（按退出码判定的用例，退出 0）。“检查”一列是实际：该例最后一次运行的检查结果，“退出 0”表示按退出码判定的用例。“运行”一列列出这一例的全部运行（含失败的），“证据”一列是相对证据根目录 `<证据目录>/rc2/v1.0.0-rc.2/` 的路径。UAT-DEP-01、UAT-DEP-02 的用时是安装之后那次检查的用时，安装本身的用时见第 4 节。

### 5.1 `us-west-2`（`uat`、`plat`）

这张表没有人工判定（没有用 `--overrides`）。

| 结果 | 用例数 |
| --- | --- |
| PASS | 43 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 43 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-02 | PASS | 7 PASS | 2026-10-24T12:07:40Z | 0 分 47 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-24T14:13:52Z | 43 分 38 秒 | uat-us-west-2/deploy-repeat (exit 0) | `uat-us-west-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-24T14:57:30Z | 7 分 14 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-24T14:06:20Z | 3 分 1 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-10-24T09:52:34Z | 42 分 5 秒 | uat-us-west-2/reinstall (exit 1)；uat-us-west-2/reinstall-2 (exit 1)；uat-us-west-2/reinstall-3 (exit 0) | `uat-us-west-2/reinstall-3/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-24T14:04:52Z | 0 分 58 秒 | plat-us-west-2/uninstall (exit 0) | `plat-us-west-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-24T14:09:21Z | 4 分 31 秒 | uat-us-west-2/deploy-release-refusals (exit 0) | `uat-us-west-2/deploy-release-refusals/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-10-24T12:08:51Z | 72 分 53 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-24T15:04:44Z | 3 分 10 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-24T15:07:54Z | 1 分 51 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/spark-image-2 (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-24T15:09:45Z | 4 分 43 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-24T15:14:28Z | 4 分 45 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-24T15:19:13Z | 1 分 48 秒 | uat-us-west-2/catalog-tokens (exit 0) | `uat-us-west-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-24T15:21:01Z | 0 分 36 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-24T15:21:37Z | 2 分 27 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-24T15:24:04Z | 0 分 34 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-24T15:24:38Z | 0 分 8 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-24T15:24:46Z | 28 分 4 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-24T15:52:50Z | 31 分 35 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-24T16:30:38Z | 102 分 47 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/spark-image-2 (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-24T18:14:43Z | 63 分 15 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 37 PASS | 2026-10-24T19:17:58Z | 45 分 26 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-24T20:03:24Z | 21 分 41 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-24T20:03:24Z | 9 分 22 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-24T20:25:05Z | 39 分 20 秒 | uat-us-west-2/monitoring (exit 0) | `uat-us-west-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-24T19:17:58Z | 39 分 1 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-24T21:04:25Z | 24 分 22 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-24T21:28:47Z | 41 分 30 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-24T22:10:17Z | 22 分 43 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-24T22:33:00Z | 18 分 26 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-24T22:51:26Z | 27 分 26 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-24T23:18:52Z | 19 分 7 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-24T23:41:18Z | 3 分 12 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-24T23:46:55Z | 22 分 53 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-25T00:09:48Z | 86 分 10 秒 | uat-us-west-2/governance (exit 0) | `uat-us-west-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-25T01:35:58Z | 10 分 48 秒 | uat-us-west-2/iceberg-erasure (exit 0) | `uat-us-west-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-25T01:46:46Z | 27 分 8 秒 | uat-us-west-2/operations (exit 0) | `uat-us-west-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-25T02:13:54Z | 39 分 23 秒 | uat-us-west-2/dashboards (exit 0) | `uat-us-west-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-25T02:53:17Z | 10 分 46 秒 | uat-us-west-2/bi (exit 0) | `uat-us-west-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-25T02:53:17Z | 52 分 6 秒 | uat-us-west-2/pipeline (exit 0) | `uat-us-west-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-25T03:46:39Z | 14 分 7 秒 | uat-us-west-2/entry-stack (exit 0)；uat-us-west-2/entry (exit 0)；uat-us-west-2/entry-stack-back (exit 0) | `uat-us-west-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-25T04:02:00Z | 16 分 7 秒 | uat-us-west-2/node-job (exit 0) | `uat-us-west-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-25T04:18:07Z | 18 分 12 秒 | uat-us-west-2/node-query (exit 0) | `uat-us-west-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

### 5.2 `us-east-2`（`uate2`）

这张表没有人工判定（没有用 `--overrides`）。

| 结果 | 用例数 |
| --- | --- |
| PASS | 41 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-10-24T12:28:09Z | 0 分 56 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-24T12:36:18Z | 20 分 39 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-24T12:56:57Z | 6 分 15 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-24T12:29:29Z | 2 分 36 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-25T08:28:54Z | 0 分 56 秒 | uate2-us-east-2/uninstall (exit 0) | `uate2-us-east-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-24T12:32:05Z | 4 分 13 秒 | uate2-us-east-2/deploy-release-refusals (exit 0) | `uate2-us-east-2/deploy-release-refusals/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-24T14:06:51Z | 3 分 12 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-24T14:10:03Z | 1 分 43 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-24T14:11:46Z | 4 分 41 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-24T14:16:27Z | 5 分 25 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-24T14:21:52Z | 2 分 14 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-24T14:24:06Z | 0 分 28 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-24T14:24:34Z | 2 分 31 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-24T14:27:05Z | 0 分 30 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-24T14:27:35Z | 0 分 7 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-24T14:27:42Z | 27 分 51 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-24T14:55:33Z | 28 分 0 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-24T15:28:53Z | 102 分 44 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-24T17:12:47Z | 69 分 54 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | PASS | 37 PASS | 2026-10-24T18:22:41Z | 43 分 25 秒 | uate2-us-east-2/tables (exit 0) | `uate2-us-east-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-24T19:06:06Z | 21 分 1 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-24T19:06:06Z | 9 分 21 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-24T19:27:07Z | 38 分 20 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-24T18:22:41Z | 38 分 45 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-24T20:05:27Z | 24 分 22 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-24T20:29:49Z | 43 分 1 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-24T21:12:50Z | 21 分 19 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-24T21:34:09Z | 16 分 45 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-24T21:50:54Z | 24 分 10 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-24T22:15:04Z | 19 分 22 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-24T22:37:08Z | 3 分 3 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-24T22:42:21Z | 23 分 40 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-24T23:06:01Z | 86 分 44 秒 | uate2-us-east-2/governance (exit 0) | `uate2-us-east-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-25T00:32:45Z | 10 分 56 秒 | uate2-us-east-2/iceberg-erasure (exit 0) | `uate2-us-east-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-25T00:43:41Z | 26 分 31 秒 | uate2-us-east-2/operations (exit 0) | `uate2-us-east-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-25T01:10:12Z | 38 分 35 秒 | uate2-us-east-2/dashboards (exit 0) | `uate2-us-east-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-25T01:48:47Z | 11 分 11 秒 | uate2-us-east-2/bi (exit 0) | `uate2-us-east-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-25T01:48:47Z | 47 分 39 秒 | uate2-us-east-2/pipeline (exit 0) | `uate2-us-east-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-25T02:37:34Z | 14 分 15 秒 | uate2-us-east-2/entry-stack (exit 0)；uate2-us-east-2/entry (exit 0)；uate2-us-east-2/entry-stack-back (exit 0) | `uate2-us-east-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-25T02:53:33Z | 16 分 1 秒 | uate2-us-east-2/node-job (exit 0) | `uate2-us-east-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-25T03:09:34Z | 17 分 51 秒 | uate2-us-east-2/node-query (exit 0) | `uate2-us-east-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

## 6. 失败、中止与复测

在 tag 上的 UAT 测试运行（两个区域的 `deploy` 阶段与之后的各阶段，以及运维执行的 `install`、`install-platform`、`metadata-restore` 与 `uninstall`）都没有失败，没有复测。UAT-DEP-06 在 tag 之前、`uat` 的 `prep` 里判定，运行了三次（第 5.1 节的表）：前两次非零退出，第三次通过。两次失败都保留在 `uat-us-west-2/runs.tsv` 里（它们的 13 项 FAIL 也在 `summary.tsv` 里），各自的证据在 `reinstall/`、`reinstall-2/`；它们的复测见下表最后一列。

| 区域 | 运行 | 现象 | 原因 | 类别 | 处理 | 复测 |
| --- | --- | --- | --- | --- | --- | --- |
| `us-west-2` | `reinstall`（`prep`，UAT-DEP-06），`f4fad85b` | 从 `v1.0.0-rc.1` 升级，`doris` 步骤 50 秒后失败，它的日志里只有 `ready at the first look, nothing to touch`，没有报错 | #258 改了查询引擎镜像的仓库名，DorisCluster 里 FE、BE 的镜像地址随之改变（digest 不变）。`install-doris.sh` 应用新的 DorisCluster 后，看到旧规格的 FE、BE Pod 还 ready，就用 admin 客户端连 FE，恰好遇上 Doris Operator 按新规格重建 FE（事件里 `Killing pod/cove-doris-fe-0` 与 admin 客户端的 Pod 同一时刻），SQL 连不上。`doris_sql` 把客户端的报错写在 stdout，调用方丢弃了 stdout，所以日志是空的。这次运行里 `doris` 之前的 12 步都成功：`foundation` 把 `cove-lake-uat/doris` 换成了 `cove-lake-uat/query-engine`，`doris-image` 从发布仓库的 `cove-lake-release/doris` 按 digest 复制了 FE 与 BE | 产品 | #261，PR #262 修复：新增 `doris_component_current`，FE、BE 的 StatefulSet 都滚动完、Pod 运行的就是应用的镜像且 ready，才连 FE；`doris_sql` 失败时把报错写到 stderr，口令打码。B 顺延到修复之后（`9ab8b2a7`） | `reinstall-3`（B）：10/10 |
| `us-west-2` | `reinstall-2`（`prep`，UAT-DEP-06），B | `catalog` 步骤 47 秒后失败：`No image tagged f4fad85b… with digest …` | `rerun-uat.sh` 把证据根里的 `installed-sha`（还是失败那次 `prep` 的 `f4fad85b`）导出为 `IMAGES_SHA`，`catalog` 步骤就去找那个提交的镜像。是执行者的错误（同一个证据根跨了提交），不是产品或测试的缺陷 | 执行 | 执行步骤改为先把 B 记为 `installed-sha`（与 `phase_prep` 的做法一样），再重跑 | `reinstall-3`（B）：10/10 |

UAT-DEP-06 按 `reinstall-3` 判定（10/10，用时 42 分 5 秒）。它升级的是一套第一次运行已部分升级过的环境：第一次运行在 `f4fad85b` 上做完了 `doris` 之前的 12 步（`tools` 到 `doris-image`：`foundation`、`third-party`、`engines`、`flink-image`、`sources`、`catalog-stack`、`catalog`、`strimzi`、`operator-stack`、`operator-image` 等），其余的 Doris、Operator、监控、开放层、Marquez、[Apache Polaris](https://polaris.apache.org/)、入口、控制台、BI 与 CLI 仍是 `v1.0.0-rc.1`（`d5acb09e`）装的；`f4fad85b` 与 B 只差 #261 的修复（`install-doris.sh` 与 `lib/common.sh`），前 12 步的产物相同。第二次运行在 B 上又重做了 `catalog` 之前的 7 步。所以从 `v1.0.0-rc.1` 的升级是由第一次与第三次运行合起来走完的。

`data-kept` 只接受一处改名：基础栈的 `DorisImageRepository…`（`cove-lake-uat/doris`）换成 `QueryEngineImageRepository…`（`cove-lake-uat/query-engine`）（#258）。这次改名发生在第一次运行里，所以 `reinstall-3` 的 `data-kept` 读到的是 25 项有状态资源原样保留、没有改名。第一次运行自己的判定（`uat-us-west-2/reinstall/verdicts.tsv`）10 项中 4 项 PASS：`same-stacks`（7 个栈就地更新）、`data-kept`（23 项有状态资源保留，另列这一处有意的改名）、`volumes-kept`（3 个卷）、`images-changed`（cove-catalog 换上了新镜像）；6 项 FAIL 都是停在 `doris` 的结果：`install-exit`、`every-step-done`、`verify-passed`、`install-finished`，`no-upstream-images`（Marquez 那一步没有运行，还在跑上游镜像），`components-at-the-commit`（Marquez、Polaris 与 cove-operator 还注解为 `d5acb09e`）。

## 7. `v1.0.0-rc.1` 的缺陷：本候选的修复与本次的表现

| 问题 | 在 `v1.0.0-rc.1` 里怎样出现 | 本候选的修复 | 本次执行中的表现 |
| --- | --- | --- | --- |
| #233 CDK 偶尔解析不到账号 | `us-east-2` 在 tag 上的一次重装里，`open-stack` 步骤的 CDK 没解析出账号，安装停下 | PR #253：`cdk_deploy` 只在 CDK CLI 没解析出账号时重跑 `cdk deploy`（最多 3 次，间隔 30 秒），其他失败立即返回 | 这条路径本次没有走到：三套环境的安装与重装、两个区域的 `deploy-repeat` 与中断续装、入口与命名空间用例的栈部署、发布仓库的两次部署，都没有遇上没解析出账号，证据里没有一行 `cdk_deploy` 的重试。重试只由离线测试（`tests/acceptance/test_cdk_deploy.py`）覆盖 |
| #237 健康检查把 RDS 的 `backing-up` 判为不健康 | `us-west-2` 的 UAT-NODE-02 失败，判为 FAIL | PR #251：`metadata-database` 按 RDS 文档列出照常接受连接的状态（含 `backing-up`），其余（含 `modifying`）都判为失败 | UAT-NODE-02 的 `catalog-healthy` 两个区域都 PASS。证据里 14 次健康检查读到的状态都是 `available`，没有一次遇上 `backing-up`；备份窗口是否落在执行期间、落在何时，证据里没有记录。`backing-up` 只由离线测试（`tests/acceptance/test_catalog_health.py`，RDS 的全部 34 种状态）覆盖 |
| #238 Kafka 承载的 CDC：缺行不告警，空闲表的复核不结束 | `us-east-2` 的 `kafka-cdc-2`：采集端重新快照时漏了一行，湖里少一行没有告警，复核一直不结束 | PR #252：带心跳的 MySQL 源不靠新提交也能判定已追上（经 Kafka 承载时，还要表的话题已被消费到末端）；同一批键连续两次复核都不一致时发 Warning `RowsMissing` | UAT-SRC-04 两个区域都 24/24、第一次运行就通过。采集端重新快照之后，两张表都完成了复核（`resnapshot-reconciled`：`us-west-2` 218 秒，`us-east-2` 370 秒），其中 `segments` 只在开始时写入，没有新提交也结束了复核，这正是 rc.1 里不结束的情形；`SnapshotAgain`、`DeletesLost`/`DeletesRepaired`（两个区域各 5 行）如期出现。本次没有缺行，`RowsMissing` 没有出现，只由 cove-operator 的单元测试覆盖。UAT-SRC-01（MySQL 与 PostgreSQL CDC）两个区域都 50/50 |
| #244 大输出经 `kubectl logs` 取回被截断 | rc.1 的第三次性能运行：DWD 约 97 MB 的导出丢了开头，一致性检查报出假的差异 | PR #255：Pod 把输出写进文件，用 `kubectl exec` 取回并核对字节数（`run_pod_out`）；LakeDump 最后写一行 `END`，验收按首尾标记与行数核对。cove-jobs 镜像在 B 上重建 | 两个区域证据里的 140 份 LakeDump 导出都以 `TABLE` 行开头、`END` 行结尾，没有留下 `.incomplete`：CDC、表、任务、Kafka、质量、监控、治理、看板、节点故障等用例 `dumps/` 下的 130 份，UAT-ICE-01 的 `trino/status/` 下的 10 份（每个区域 5 份）；读它们的检查都 PASS。`doris_sql` 也改用这种取回方式，UAT-DSH-01 两个区域都通过 |
| #245 MySQL 数据源的一张表 900 秒内没有 Ready | rc.1 的第三次性能运行；之后在 `uat` 上复现 6 次都在 258–320 秒内 Ready，没有复现 | PR #257，只加诊断：等待新提交时写明在等什么，停滞 10 分钟发 Warning `TargetNotCommitting`；验收测试等 LakeSource 超时时先保存诊断 | 两个区域没有一次等待 LakeSource 超时，没有保存诊断；各测试收集的事件里没有 `TargetNotCommitting`。根因仍未查明，issue 保持打开（第 8 节） |
| #234 第三方镜像按 digest 固定会被上游删掉 | Debezium Server 的固定 digest 被上游删掉，两个区域的 `kafka-cdc` 都 `ImagePullBackOff` | PR #259：全部第三方镜像按 digest 固定在 `deploy/images/third-party.tsv`，新的安装步骤 `third-party` 把它们复制进环境自己的 `cove-lake-<env>/third-party`；`make-manifest.sh` 复制进 `cove-lake-release/third-party`，清单的 `third_party` 可直接安装 | `no-upstream-images` 在 UAT-DEP-01（60 个工作负载与 Pod）、UAT-DEP-02（58）、UAT-DEP-06（60）与两个区域的 UAT-DEP-03（各 60）都 PASS：除 AWS 自己的插件外，每个镜像都来自本环境的 `cove-lake-<env>/...`。`third-party` 步骤第一次实跑是 `uat` 第一次 `prep`（222 秒），`plat`、`uate2` 从零安装各 155、196 秒。UAT-DEP-08 新增的 `third-party-not-pinned` 两个区域都 PASS |
| #227 #212 复审的后续 | 卸载对没有记下设置的旧环境不核对托管区与入口域名、预检吞掉读取错误、CDK bootstrap 未检查、可执行位、`grep -q` 等 | PR #254（第 4 项随 #248、第 5 项随 #234） | 各次安装都通过了预检（含新的 CDK bootstrap 版本检查）；UAT-DEP-05 两个区域都 49/49；`plat` 的卸载（UAT-DEP-07）4/4；`uat` 升级时 `DORIS_IMAGE_SOURCE` 通过了格式校验并照常复制 |
| #248 仓库里的部署值 | `v1.0.0-rc.1` 的清单写着发布仓库的账号与执行身份；文档里有维护者的账号与域名 | PR #256（文档与提交前检查）；清单的部分随 PR #259：schema 2 不写账号与区域，安装时要给 `COVE_RELEASE_REGISTRY` | 本候选的清单里没有账号、ARN 或发布仓库的地址；每次 `--release` 安装都由设置给出发布仓库，UAT-DEP-08 的 `no-registry` 两个区域都 PASS。git 历史与已关闭的 issue/PR 待维护者决定，issue 保持打开（第 8 节） |
| #258 查询引擎镜像改名、产品改名 Colvenna Lake | （本候选新增） | PR #260：查询引擎镜像的仓库改为 `query-engine`，产品名改为 Colvenna Lake（GitHub 仓库同时改名为 `amliuyong/colvenna-lake`），技术标识不变 | 清单、发布仓库与三套环境都用新仓库名；`uat` 的升级跨过改名（第 6 节），`data-kept` 只接受这一处改名；FE 与 BE 的 digest 不变，从保留的 `cove-lake-release/doris` 复制。这次改名暴露了 #261 |
| #261 Doris 步骤不等 Operator 换上新规格 | （本次 `prep` 中发现，第 6 节） | PR #262 | 各次 `doris` 步骤都通过，但面对的都是镜像没有变的集群或新建的集群：`reinstall-3` 开始时 FE、BE 已经在运行 `cove-lake-uat/query-engine` 的镜像（`uat-us-west-2/reinstall-3/before/pods.json`：第一次运行已应用新的 DorisCluster，Operator 已换上新规格），它的 `doris` 步骤 129 秒通过；两个区域的 `deploy-repeat` 与 `uate2` 的重装前后镜像相同；`plat`、`uate2` 是从零安装。本次没有一次运行改变已有 Doris 集群的镜像，#261 新增的等待在真实环境里没有走到，只有离线测试（`tests/acceptance/test_doris_rollout.py`）覆盖 |
| #172 的上游竞态：Doris Operator 建好 FE 后有时不再协调 | rc.1 里竞态发生 4 次（两个区域的 UAT-CAT-03、`uate2` 的 UAT-CAT-12、`plat` 的安装），产品的规避都化解了 | 产品与测试共用 `touch_doris_without_be`（PR #189）；上游 [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 这次竞态发生 1 次：`uat` 的 UAT-CAT-03 催促 1 次（等待开始后 15 秒）。`uate2` 的 UAT-CAT-03 与 UAT-CAT-12、`plat` 与 `uate2` 的从零安装都在等待开始 1 秒内看到 BE 的 StatefulSet，不需要催促；`uat` 的 UAT-CAT-12 与各次重装第一眼就 ready。UAT-CAT-03 与 UAT-DSH-01（BE、FE 丢失后恢复）两个区域都通过 |

`v1.0.0-rc.1` 执行中修好的测试（PR #232、#235、#236、#239）都在本候选里；相应的 UAT-DEP-05（`us-east-2`）、UAT-SRC-04、UAT-ICE-03 本次第一次运行就通过。

## 8. 已知缺陷与限制

本候选已知、尚未解决的缺陷与限制：

| 项 | 内容 | 影响 |
| --- | --- | --- |
| #245 | rc.1 性能运行里一张表 900 秒内没有 Ready，根因未查明；本候选只加了诊断 | 再发生时能看出卡在哪（`message`、`TargetNotCommitting`、测试保存的诊断）；本次没有复现 |
| #248 | git 历史与已关闭的 issue/PR 正文里仍有旧的部署值，是否改写由维护者决定 | 当前的树与本候选的清单里没有；改写历史会改掉全部提交的 SHA，已推送的 tag 与证据里的引用都会失效 |
| #258 | 宣传视频与 GIF 还是旧名字，已从 README 撤下 | 计划在 `v1.0.0-rc.2` 之后重新渲染 |
| `cove-lake-release/doris` | 发布仓库里保留的旧仓库，存着 `v1.0.0-rc.1` 的 FE 与 BE（digest 与现在固定的相同），由维护者决定保留 | rc.1 的 tag 与清单不变；跨过改名的升级在 rc.2 之前用它作 `DORIS_IMAGE_SOURCE` |
| #261 的已知缺口 | 只改配置、不换镜像的 DorisCluster 规格变化，要等 Operator 更新了 StatefulSet 之后才看得出来，`doris` 步骤仍不等它（代码注释写明） | 本次的升级与重装不涉及这种变化 |
| #172 的上游 | [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 产品安装与测试会在需要时催促 Operator 协调，本次实际发生 1 次并被化解 |

其他限制：

- #233 的重试、#237 的 `backing-up`、#238 的 `RowsMissing`、#261 在已有 Doris 集群换镜像时的等待，这几条修复的路径本次在真实环境里都没有走到（第 7 节），只有离线测试覆盖。
- 只有一个 AWS 账号：跨账号安装（从发布镜像仓库跨账号拉取）没有实测；跨区域复制在 `uate2` 上实测了。
- GitHub CI 因账单失败不能运行，按请求方的决定豁免；每个 PR 都在本地对确切 SHA 运行了全部作业。CI 结果不能替代 UAT。
- 性能不在 UAT 内；吞吐、延迟分位等结论见[性能验收](../../../performance/v1.0.0-rc.2.md)。

## 9. 范围外

见候选上的[用例文档](../../cases.md)第 2 节与第 7 节：`run-upgrade.sh`（开发阶段不做跨版本兼容）、上游 [Apache Flink](https://flink.apache.org/) CDC 的复现、`PERF=1` 的性能检查、`NODE_LOSS=drain` 等其他节点故障方式与其他中断点、离线单元测试、第二朵云（#33）。各区域范围外的用例（`us-east-2` 的 UAT-DEP-02、UAT-DEP-06、UAT-DEP-09，`us-west-2` 的 UAT-DEP-01）在第 5 节各区域的表下单列（按用例文档第 7 节的“区域”一列）；落在本区域范围外的运行，列为“其他运行”。安全清点与拒绝探测、性能验收在 UAT 之后执行，不属于 UAT。

## 10. 证据

证据保存在执行机的 `<证据目录>/rc2/v1.0.0-rc.2/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。其中 `uat.log` 记每个运行的起止与测试所在的提交，`runs.tsv` 每个运行一行（含失败的），`summary.tsv` 每项检查一行，`<标签>.log` 与 `<标签>/` 是各运行的输出与证据；
- 各环境的安装证据（`install/`、`reinstall/`、`reinstall-2/`、`reinstall-3/`、`deploy-repeat/install/`）与卸载证据（`uninstall/`、`uninstall-before/`）；
- `security-uat/`、`security-uate2/`：UAT 之后的安全清点与拒绝探测（见[安全一节](../../../../release/v1.0.0-rc.2/security.md)）；`perf-rc2p/`、`perf-rc2q/`：性能运行；
- 执行驱动脚本与时间线：`../runbook-rc2.md`、`../s*.sh`（输出在 `../s*.stdout`），补丁标签的核对脚本与记录 `../patch-tag-watch.*`，各 PR 的本地 CI 记录 `../ci/`；
- 手工清理的记录：`uat-us-west-2/manual-purge-dev-kafka.log`。

## 11. 商标

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Polaris, Polaris, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 Apache Doris 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #258）、提交（如 `83c03c76`）和 tag（如 `v1.0.0-rc.2`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
