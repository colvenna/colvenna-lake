---
title: "Colvenna Lake v1.0.0-rc.5 UAT 报告"
date: 2026-10-28
status: final
issue: 181
---

# v1.0.0-rc.5 UAT 报告

本文是发布候选 `v1.0.0-rc.5` 的 UAT 报告，按 Release 归档。`v1.0.0-rc.4` 在验收中途停止：改名门禁（#271）在它的 catalog 镜像里发现了旧名（#286）。本候选带着这个修复（PR #288），以及 rc.4 的测试缺陷 #287 的修复（PR #289）。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)：本次 Release 的必需用例集合共 44 条（第 7 节），两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。性能不在 UAT 内，另见[性能验收](../../../performance/v1.0.0-rc.5.md)；改名门禁另见[改名门禁的记录](../../../../release/v1.0.0-rc.5/rename.md)。

## 1. 结论

- 两个区域的必需用例全部 PASS：`us-west-2` 43 条中 43 条 PASS，`us-east-2` 41 条中 41 条 PASS。两个区域都没有 FAIL、BLOCKED 或 NOT_RUN。必需集合里只要求在另一个区域执行的用例，在第 4 节各区域的表下单列。
- 测试运行都是一次通过：`uat` 51 次、`plat` 3 次、`uate2` 53 次，退出码都是 0，没有重跑，也没有人工判定（没有用 `--overrides`）。执行中没有改动测试。
- `v1.0.0-rc.4` 的两个问题，修复在本候选里得到验证（第 6 节）：
  - #286：改名门禁的镜像扫描里，catalog 镜像的 3 处命中都是第三方原文，rc.4 的那一处第一方命中（迁移锁的键）不在了；在 tag 上的源码扫描退出 0。门禁的结论是 PASS，见[改名门禁的记录](../../../../release/v1.0.0-rc.5/rename.md)。
  - #287：UAT-MON-01 两个区域都 16/16。`source-latency-agrees` 的 10 笔标记中位数与心跳中位数：`uat` 18 秒与 18 秒，`uate2` 19 秒与 27 秒，差都在允许的 15 秒之内。
  - rc.4 因停止而 NOT_RUN 的 9 条（UAT-GOV-02、UAT-ICE-04、UAT-CON-01、UAT-DSH-01、UAT-BI-01、UAT-CON-02、UAT-ENT-01、UAT-NODE-01、UAT-NODE-02），两个区域都 PASS。
- `v1.0.0-rc.3` 的三个产品缺陷，修复在两个区域再次得到验证（第 6 节）：#278（UAT-DEP-03 两个区域都 11/11），#279（UAT-TBL-01 两个区域都 45/45），#282（UAT-DEP-07 两个区域都 4/4，卸载删除了 `plat` 的 9 个、`uate2` 的 11 个 Lambda 日志组）。
- UAT-DEP-07 的前置条件（卸载期间不部署、不测试别的环境）这次两个区域都满足（第 5 节）；rc.4 在 `us-east-2` 有一处不满足。
- 本报告只给 UAT 的结果。性能见[性能验收](../../../performance/v1.0.0-rc.5.md)，安全见[安全一节](../../../../release/v1.0.0-rc.5/security.md)。本候选能否发布，由发布检查（#181）综合 UAT、性能、安全、改名门禁与已知缺陷决定：性能验收 FAIL（看板运行的一项功能检查，测试缺陷 #293），本候选不能发布。另外，维护者 2026-10-28 决定 v1.0 改用独立的 Colvenna Catalog（#295），下一个候选在这次切换之后。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.5`，annotated tag，指向 `546d5157`（清单 PR #290 的合并提交） |
| 镜像与安装所用的源码 | `b6b884b0`（PR #289 的合并提交，下称 B5）。tag 的提交与它只差 `release/v1.0.0-rc.5/` |
| 发布清单 | [`release/v1.0.0-rc.5/manifest.json`](../../../../release/v1.0.0-rc.5/manifest.json)，schema 2，内容的种类与 `v1.0.0-rc.3` 的相同；生成时 `check-release.sh` 核对了其中 34 个镜像与 colvenna CLI 的二进制。与 rc.4 的清单相比，从 B5 构建的镜像都是新的 digest（Flink 基础镜像、colvenna-catalog、colvenna-operator、5 个作业镜像、夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像与 CLI 镜像）；控制台、BI 与查询引擎 FE、BE 的 digest 不变，第三方镜像（`third_party` 与 `fixtures.third_party`）也不变 |
| 环境 | 与 `v1.0.0-rc.3` 的三套相同（[rc.3 的报告](../v1.0.0-rc.3/report.md)第 3 节），在同一个账号里，都是改名之后按新名字安装的。`uat`（`us-west-2`，带 `--fixtures`）保留下来，从 rc.4 的 tag（`6c916d00`）原地升级；`plat`（`us-west-2`，只装平台）与 `uate2`（`us-east-2`，带 `--fixtures`）在 rc.4 结束时已卸载，本候选在 tag 上按清单从零安装。版本取自证据：EKS 1.36（`uat` 的平台版本 `eks.15`，性能运行记下的），节点的 kubelet `v1.36.5`、系统 Amazon Linux 2023.12.20261021，项目元数据库 RDS PostgreSQL 16.13（`verify` 读到的），测试源 MySQL 8.4.10。部署值只在执行机未提交的设置里，这里用设置的键名 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B5 上从源码运行，清单由升级后的 `uat` 核对生成。其余运行都在 tag 上，两次卸载与它们的核对也在内 |
| `RUN_TAG` | `us-west-2`：`rc5w`；`us-east-2`：`rc5e` |
| 补丁窗口 | 执行跨过 10-28 00:00 UTC 的补丁窗口。`Patch Group` 标签事先去掉：`plat`、`uate2` 的节点在 10-27 08:49，执行机与 `uat` 的节点沿用 rc.4 时的处理；10-27 07:18 起由本候选的核对脚本每 10 分钟核对一次。窗口时执行机与各节点都没有这个标签。窗口之后，标签被自动补回过，都在下一次核对时去掉（下面是核对发现的时刻）：节点故障测试新起的节点，`uate2` 00:41、01:01，`uat` 01:52、02:12；执行机与 `uat` 的 4 个节点 03:43，`uate2` 的 4 个节点 04:03（`patch-tag-watch.log`）。10-28 06:16 停止核对，之后 `uat` 的节点恢复了标签（执行驱动的时间线 `runbook-rc5.md`） |

## 3. 执行顺序

两个区域的执行有先后约束：

- 凡是调用 `install.sh` 的阶段，都必须在 tag 上运行。UAT-DEP-06 的升级例外：它在 B5 上运行，清单由它生成，所以在 tag 之前。
- UAT-DEP-05 的“错误区域卸载”要探测另一个区域里可丢弃的环境：`uat` 探测 `uate2`，`uate2` 探测 `plat`，从不探测 `uat`。
- UAT-DEP-07 比对卸载前后其他环境的资源，卸载期间其他环境要保持静止。

| 时间（UTC，2026-10-27/28） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 10-27 06:23、07:17 | — | PR #288（#286）、PR #289（#287）合入，B5 = `b6b884b0` | — |
| 07:18 | — | 私有证据目录改为现在的名字（第 9 节）；本候选的补丁标签核对脚本接替 rc.4 的 | — |
| 07:19–07:58 | `us-west-2` | `uat` 检出 B5，`prep` 在其上从源码重装：从 rc.4 的 tag 原地升级（`reinstall`，10/10，安装 27 步 2205 秒）；之后 `spark-image` | UAT-DEP-06 |
| 07:58–08:06 | `us-west-2` | 在 B5 上部署发布镜像仓库；`make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 08:07–08:26 | — | 清单 PR #290 合入（本地 CI 9/9），annotated tag 打在合并提交上（08:26:46 推送） | — |
| 08:27–08:43 | — | 改名门禁的镜像扫描：按清单的 digest 读取 14 个镜像（[改名门禁的记录](../../../../release/v1.0.0-rc.5/rename.md)） | — |
| 08:27–09:25 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（27 步，3386 秒），之后 `check-install` | UAT-DEP-02 |
| 08:27–09:47 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，27 步，4722 秒），之后 `check-install --fresh` | UAT-DEP-01 |
| 09:25–10:45 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 923 秒，catalog 停 190 秒）与按时间点（恢复 939 秒，catalog 停 194 秒） | UAT-DEP-09 |
| 09:47–10:44 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝、重复执行（1324 秒）、中断续装；之后 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 |
| 10:45–11:25 | `us-west-2` | `uat` 与 `uate2` 静止时，在 tag 上卸载 `plat`（10:45–11:24），之后核对 | UAT-DEP-07 |
| 11:26–10-28 02:17 | 两个区域 | 两套 UAT 在 tag 上同时运行：`uat` 从 `deploy` 阶段起（02:17 结束），`uate2` 从 `catalog` 阶段起（01:05 结束） | 其余用例 |
| 10-28 02:19 | `us-west-2` | 删除 UAT 的 `kafka` 阶段留下的开发用 [Apache Kafka](https://kafka.apache.org/)（`tests/fixtures/kafka/dev-kafka.sh purge`，`uat-us-west-2/manual-purge-dev-kafka.log`），让性能运行独占环境 | — |
| 02:19–02:26 | `us-west-2` | `uat` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 02:26–02:32 | `us-east-2` | `uate2` 的安全清点与拒绝探测，64/64（不属于 UAT） | — |
| 02:33–05:22 | `us-west-2` | `uat` 单独运行性能验收 `rc5p`（不属于 UAT，见[性能验收](../../../performance/v1.0.0-rc.5.md)） | — |
| 05:24–06:15 | `us-east-2` | `uat` 静止时，在 tag 上卸载 `uate2`（05:24–06:14），之后核对 | UAT-DEP-07 |
| 05:59 | — | PR #294（#293，性能运行的测试缺陷）合入 main | — |

## 4. 统计与逐例结果

两个区域分别统计。各自的分母是本区域必需的用例，按候选上[用例文档](../../cases.md)第 7 节的“区域”一列。

下面的表由 `tests/acceptance/uat_report.py report` 从证据生成，没有人工判定（没有用 `--overrides`）。`--doc` 指向候选 tag 上的用例文档；工具本身、`--cases` 用的 `tests/acceptance/uat-cases.tsv` 与用例文档，main 上的都与 tag 上的相同。表里的列与 rc.3 的报告相同：

- 每例的预期，是用例文档里它的期望结果：期望结果表里的每项检查都出现并为 PASS，项数、最后输出与最后一行都相符；按退出码判定的用例，退出 0。
- “检查”是该例最后一次运行的检查结果，“退出 0”表示按退出码判定的用例；“运行”列出这一例的全部运行。
- “证据”是相对证据根目录 `<证据目录>/rc5/v1.0.0-rc.5/` 的路径。
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
| UAT-DEP-02 | PASS | 7 PASS | 2026-10-27T09:24:33Z | 0 分 47 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-27T11:33:13Z | 33 分 19 秒 | uat-us-west-2/deploy-repeat (exit 0) | `uat-us-west-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-27T12:06:32Z | 7 分 9 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-27T11:26:11Z | 2 分 46 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-10-27T07:19:40Z | 38 分 55 秒 | uat-us-west-2/reinstall (exit 0) | `uat-us-west-2/reinstall/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-27T11:24:33Z | 0 分 58 秒 | plat-us-west-2/uninstall (exit 0) | `plat-us-west-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-27T11:28:57Z | 4 分 16 秒 | uat-us-west-2/deploy-release-refusals (exit 0) | `uat-us-west-2/deploy-release-refusals/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-10-27T09:25:51Z | 79 分 12 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-27T12:13:41Z | 3 分 34 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-27T12:17:15Z | 1 分 32 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-27T12:18:47Z | 3 分 17 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-27T12:22:04Z | 5 分 18 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-27T12:27:22Z | 1 分 54 秒 | uat-us-west-2/catalog-tokens (exit 0) | `uat-us-west-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-27T12:29:16Z | 0 分 32 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-27T12:29:48Z | 2 分 25 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-27T12:32:13Z | 0 分 33 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-27T12:32:46Z | 0 分 8 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-27T12:32:54Z | 28 分 15 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-27T13:01:09Z | 34 分 4 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-27T13:42:47Z | 102 分 49 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-27T15:26:56Z | 76 分 42 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-10-27T16:43:38Z | 62 分 53 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-27T17:46:31Z | 22 分 18 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-27T17:46:31Z | 10 分 47 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-27T18:08:49Z | 35 分 37 秒 | uat-us-west-2/monitoring (exit 0) | `uat-us-west-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-27T16:43:38Z | 40 分 3 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-27T18:44:26Z | 23 分 36 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-27T19:08:02Z | 44 分 22 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-27T19:52:24Z | 23 分 14 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-27T20:15:38Z | 16 分 52 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-27T20:32:30Z | 26 分 11 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-27T20:58:41Z | 19 分 3 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-27T21:20:15Z | 2 分 51 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-27T21:25:07Z | 24 分 16 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-27T21:49:23Z | 87 分 54 秒 | uat-us-west-2/governance (exit 0) | `uat-us-west-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-27T23:17:17Z | 11 分 23 秒 | uat-us-west-2/iceberg-erasure (exit 0) | `uat-us-west-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-27T23:28:40Z | 26 分 53 秒 | uat-us-west-2/operations (exit 0) | `uat-us-west-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-27T23:55:34Z | 38 分 14 秒 | uat-us-west-2/dashboards (exit 0) | `uat-us-west-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-28T00:33:48Z | 11 分 11 秒 | uat-us-west-2/bi (exit 0) | `uat-us-west-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-28T00:33:48Z | 51 分 28 秒 | uat-us-west-2/pipeline (exit 0) | `uat-us-west-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-28T01:26:33Z | 13 分 42 秒 | uat-us-west-2/entry-stack (exit 0)；uat-us-west-2/entry (exit 0)；uat-us-west-2/entry-stack-back (exit 0) | `uat-us-west-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-28T01:41:50Z | 17 分 10 秒 | uat-us-west-2/node-job (exit 0) | `uat-us-west-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-28T01:59:00Z | 18 分 24 秒 | uat-us-west-2/node-query (exit 0) | `uat-us-west-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

### 4.2 `us-east-2`（`uate2`）

| 结果 | 用例数 |
| --- | --- |
| PASS | 41 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 0 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-10-27T09:46:57Z | 0 分 40 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-27T09:54:41Z | 22 分 4 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-27T10:16:45Z | 6 分 16 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-27T09:47:55Z | 2 分 35 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-28T06:14:27Z | 0 分 57 秒 | uate2-us-east-2/uninstall (exit 0) | `uate2-us-east-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-27T09:50:30Z | 4 分 11 秒 | uate2-us-east-2/deploy-release-refusals (exit 0) | `uate2-us-east-2/deploy-release-refusals/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-27T11:26:13Z | 3 分 3 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-27T11:29:16Z | 1 分 37 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-27T11:30:53Z | 4 分 35 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-27T11:35:28Z | 5 分 20 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-27T11:40:48Z | 1 分 48 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-27T11:42:36Z | 0 分 26 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-27T11:43:02Z | 2 分 16 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-27T11:45:18Z | 0 分 29 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-27T11:45:47Z | 0 分 6 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-27T11:45:53Z | 27 分 35 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-27T12:13:28Z | 28 分 25 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-27T12:47:10Z | 102 分 35 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-27T14:30:52Z | 69 分 59 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-10-27T15:40:51Z | 65 分 47 秒 | uate2-us-east-2/tables (exit 0) | `uate2-us-east-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-27T16:46:38Z | 20 分 46 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-27T16:46:38Z | 8 分 56 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-27T17:07:24Z | 34 分 59 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-27T15:40:51Z | 40 分 7 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-27T17:42:23Z | 23 分 59 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-27T18:06:22Z | 42 分 47 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-27T18:49:09Z | 21 分 9 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-27T19:10:18Z | 16 分 43 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-27T19:27:01Z | 26 分 32 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-27T19:53:33Z | 19 分 36 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-27T20:15:26Z | 3 分 0 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-27T20:20:19Z | 24 分 15 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | PASS | 28 PASS | 2026-10-27T20:44:34Z | 88 分 58 秒 | uate2-us-east-2/governance (exit 0) | `uate2-us-east-2/governance/` | — |
| UAT-ICE-04 | PASS | 7 PASS | 2026-10-27T22:13:32Z | 10 分 40 秒 | uate2-us-east-2/iceberg-erasure (exit 0) | `uate2-us-east-2/iceberg-erasure/` | — |
| UAT-CON-01 | PASS | 59 PASS | 2026-10-27T22:24:12Z | 26 分 55 秒 | uate2-us-east-2/operations (exit 0) | `uate2-us-east-2/operations/` | — |
| UAT-DSH-01 | PASS | 19 PASS | 2026-10-27T22:51:07Z | 38 分 20 秒 | uate2-us-east-2/dashboards (exit 0) | `uate2-us-east-2/dashboards/` | — |
| UAT-BI-01 | PASS | 54 PASS | 2026-10-27T23:29:27Z | 11 分 5 秒 | uate2-us-east-2/bi (exit 0) | `uate2-us-east-2/bi/` | — |
| UAT-CON-02 | PASS | 39 PASS | 2026-10-27T23:29:27Z | 47 分 59 秒 | uate2-us-east-2/pipeline (exit 0) | `uate2-us-east-2/pipeline/` | — |
| UAT-ENT-01 | PASS | 50 PASS | 2026-10-28T00:18:37Z | 14 分 14 秒 | uate2-us-east-2/entry-stack (exit 0)；uate2-us-east-2/entry (exit 0)；uate2-us-east-2/entry-stack-back (exit 0) | `uate2-us-east-2/entry/` | — |
| UAT-NODE-01 | PASS | 8 PASS | 2026-10-28T00:33:56Z | 16 分 34 秒 | uate2-us-east-2/node-job (exit 0) | `uate2-us-east-2/node-job/` | — |
| UAT-NODE-02 | PASS | 10 PASS | 2026-10-28T00:50:30Z | 14 分 52 秒 | uate2-us-east-2/node-query (exit 0) | `uate2-us-east-2/node-query/` | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

## 5. 失败、中止与复测

没有失败、中止或复测的运行。三个证据根的 `runs.tsv` 里，每一行的退出码都是 0：`uat-us-west-2/` 51 行（UAT-DEP-06 的 `reinstall` 与 `spark-image`，加上 tag 上的 49 次），`plat-us-west-2/` 3 行（`install-platform`、`metadata-restore`、`uninstall`），`uate2-us-east-2/` 53 行（含 `install` 与 `uninstall`）。两套 UAT 的 `summary` 阶段分别记下 1069 项（`uat`）与 1075 项（`uate2`）PASS 的检查。

**UAT-DEP-06 与 UAT-DEP-03。** `reinstall`（10/10，用时 38 分 55 秒）从 rc.4 的 tag（`6c916d00`）的安装原地升级到 B5：7 个栈就地更新，25 项有状态资源与 4 个卷保留，5 个组件注解为 B5，colvenna-catalog 与 colvenna-operator 换上了新镜像。之后 `uat` 的 `deploy-repeat` 在 tag 上按清单重装（UAT-DEP-03，11/11）：7 个栈的 161 项资源、4 个节点、23 个工作负载与 4 个卷都没有变，11 个 release 收敛，没有构建镜像。

**UAT-DEP-07 的前置条件。** 两次卸载期间，其他环境都保持静止：

- 卸载 `plat`（10-27 10:45–11:25）时，`uate2` 的 `deploy` 与 `prep` 已于 10:44 结束，两套 UAT 在 11:26 才开始；
- 卸载 `uate2`（10-28 05:24–06:15）时，`uat` 上的性能运行已于 05:22 结束，之后 `uat` 上没有运行任何测试。

`others-untouched` 比对的是其他环境与发布仓库的资源：`plat` 卸载时 186 项、`uate2` 卸载时 98 项，都与卸载之前相同。补丁标签的核对脚本在这两段时间里没有改动任何节点（`patch-tag-watch.log` 在这两段时间里没有记录）。

## 6. `v1.0.0-rc.4` 之后：本候选的变更与本次的表现

| 问题 | 本候选的变更 | 本次的表现 |
| --- | --- | --- |
| #286 catalog 镜像里，迁移锁的键是旧名的 ASCII（十六进制 `636f76656361744c`），rc.4 的改名门禁因此 FAIL | PR #288：锁的键改为新名字的 ASCII（`colvcatL`）；新加 pre-commit 检查 `deploy/scripts/rename/old_name.py`，十六进制字面量拼出的旧名也查，例外只能列在 `deploy/scripts/rename/exceptions.tsv` 里 | 改名门禁 PASS：catalog 镜像的 92,531 个文件里有 3 处命中，都是第三方原文（rc.4 是 4 处，多出的就是锁键）；在 tag 上源码扫描退出 0。换了 catalog 镜像之后，UAT-CAT-01 到 UAT-CAT-12 与 UAT-DEP-09 的两次元数据恢复都通过 |
| #287 UAT-MON-01 的 `source-latency-agrees`：6 笔标记的中位数随机摆动（rc.4 的 `uat` FAIL） | PR #289：10 笔标记在一个 MySQL 会话里每 3 秒一笔，覆盖 checkpoint 周期的各个相位；容差不变。只改测试 | 两个区域都 16/16。`uat`：标记 `[4, 7, 11, 14, 17, 20, 23, 26, 29, 32]` 秒，中位数 18 秒，心跳中位数 18 秒。`uate2`：标记 `[6, 9, 12, 15, 18, 21, 24, 27, 29, 32]` 秒，中位数 19 秒，心跳中位数 27 秒 |
| rc.4 因停止而 NOT_RUN 的 9 条 | （没有变） | 两个区域都 PASS，包括 rc.4 中止在其中的 UAT-GOV-02（28/28，`uat` 87 分 54 秒、`uate2` 88 分 58 秒） |
| #278 查询引擎镜像在环境仓库里的 tag | 修复（PR #280）从 rc.4 起在候选里 | UAT-DEP-03 两个区域都 11/11。`uat` 在 tag 上按清单重装：安装 27 步 1826 秒，`doris-image` 步骤 5 秒；`uate2`：1178 秒，`doris-image` 6 秒 |
| #279 不兼容升级落在旧作业（一个 [Apache Flink](https://flink.apache.org/) 作业）第一个 checkpoint 之前 | 修复（PR #281）从 rc.4 起在候选里 | UAT-TBL-01 两个区域都 45/45。“第一个 checkpoint 之前升级”的路径：`before-checkpoint-rolled-back` 回滚用时 `uat` 664 秒、`uate2` 689 秒，改回之后恢复运行；原来那次不兼容升级的回滚 688 秒、693 秒 |
| #282 卸载漏删栈名被截短的 Lambda 日志组 | 修复（PR #283）从 rc.4 起在候选里 | UAT-DEP-07 两个区域都 4/4（`s6-uninstall.stdout`、`s10.stdout`）。`plat`：删除 9 个 Lambda 日志组，其中 7 个是 CloudFormation 截短了栈名的（`/aws/lambda/ColvennaLakePlatFoundatio-…`），2 个带完整栈名。`uate2`：删除 11 个，其中截短的 9 个（`ColvennaLakeUate2Foundati-…` 7 个，`ColvennaLakeUate2TestMysq-…` 与 `ColvennaLakeUate2TestPost-…` 各 1 个），2 个带完整栈名 |
| #172 的上游竞态：Doris Operator 建好 FE 后有时不再协调 | （没有变）；上游 [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 发生 1 次，被产品的规避化解：`plat` 的从零安装催促 1 次（等待开始后 15 秒）。其余 9 次不需要催促：`uat` 的升级与 `deploy-repeat`、`uate2` 的 `deploy-repeat` 与重装、`uat` 的 UAT-CAT-03，第一眼就 ready；`uate2` 的从零安装与 UAT-CAT-03、两个区域的 UAT-CAT-12，都在等待开始 1 秒内看到 BE 的 StatefulSet |
| #245 MySQL 数据源的一张表久不 Ready | （没有变，只有诊断） | 没有复现：UAT 与性能运行里 35 次 `source-ready` 检查都 PASS，证据里没有 `TargetNotCommitting`，也没有保存的诊断。UAT-DSH-01 的 `source-ready`：`us-west-2` 284 秒，`us-east-2` 311 秒（限 900 秒） |
| #233 CDK 解析不到账号时重跑；#237 RDS 的 `backing-up`；#238 `RowsMissing` | （没有变） | 本次仍然没有走到：证据里没有一行 `cdk_deploy` 的重跑；24 次 `metadata-database` 的检查读到的状态都是 `available`；`RowsMissing` 没有出现。这几条仍只有离线测试覆盖 |

## 7. 已知缺陷与限制

本候选已知、尚未解决的缺陷与限制：

| 项 | 内容 | 影响 |
| --- | --- | --- |
| #293 | 测试缺陷，已在 main 上修复（PR #294），不在本候选里：性能运行里，看板运行的 `adhoc-bounded` 在 FE 的采样 Pod 晚出第一个样本时误判 | 不影响 UAT：UAT-DSH-01 两个区域都 19/19。本候选的性能验收因它 FAIL，见[性能验收](../../../performance/v1.0.0-rc.5.md) |
| #295 | 维护者 2026-10-28 决定 v1.0 改用独立的 Colvenna Catalog，不再用本仓库自带的 colvenna-catalog | 本候选的 catalog 是自带的那个；下一个候选在切换之后，要重新做完整的 UAT |
| #245 | rc.1 性能运行里一张表 900 秒内没有 Ready，根因未查明；只有诊断 | 再发生时能看出卡在哪；本次没有复现 |
| #248 | git 历史与已关闭的 issue/PR 正文里仍有旧的部署值。维护者 2026-10-25 决定不改写历史，本仓库保持 private；公开时只推一个 init 提交（#271 第 4 节） | 当前的树与本候选的清单里没有部署值 |
| #261 的已知缺口 | 只改配置、不换镜像的 DorisCluster 规格变化，要等 Operator 更新了 StatefulSet 之后才看得出来，`doris` 步骤仍不等它（代码注释写明） | 本次的升级与重装不涉及这种变化 |
| #172 的上游 | [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修 | 产品安装与测试会在需要时催促 Operator 协调，本次实际发生 1 次，被化解 |

其他限制：

- #233 的重跑、#237 的 `backing-up`、#238 的 `RowsMissing`，这几条修复的路径本次在真实环境里仍没有走到（第 6 节），只有离线测试覆盖。
- 只有一个 AWS 账号：跨账号安装（从发布镜像仓库跨账号拉取）没有实测；跨区域复制在 `uate2` 上实测了。
- GitHub CI 因账单失败不能运行，按请求方的决定豁免；每个 PR 都在本地对确切 SHA 运行了全部作业。CI 结果不能替代 UAT。
- 性能不在 UAT 内；吞吐、延迟分位等结论见[性能验收](../../../performance/v1.0.0-rc.5.md)。

## 8. 范围外

见候选上的[用例文档](../../cases.md)第 2 节与第 7 节：`run-upgrade.sh`（开发阶段不做跨版本兼容）、上游 Apache Flink CDC 的复现、`PERF=1` 的性能检查、`NODE_LOSS=drain` 等其他节点故障方式与其他中断点、离线单元测试、第二朵云（#33）。

各区域范围外的用例（`us-east-2` 的 UAT-DEP-02、UAT-DEP-06、UAT-DEP-09，`us-west-2` 的 UAT-DEP-01），按用例文档第 7 节的“区域”一列，在第 4 节各区域的表下单列；落在本区域范围外的运行，列为“其他运行”。改名门禁的扫描、安全清点与拒绝探测、性能验收不属于 UAT。

## 9. 证据

证据保存在执行机的 `<证据目录>/rc5/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `v1.0.0-rc.5/uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。
  - `uat.log` 记每个运行的起止与测试所在的提交，`runs.tsv` 每个运行一行，`summary.tsv` 每项检查一行，`<标签>.log` 与 `<标签>/` 是各运行的输出与证据。
  - `uat-us-west-2/` 的 `summary.tsv` 在 UAT 结束时写出；`plat-us-west-2/` 与 `uate2-us-east-2/` 的，在卸载的核对之后写出，含卸载的运行。
- 安装证据（`install/`、`reinstall/`、`deploy-repeat/install/`）与卸载证据（`uninstall-before/`、`uninstall/`）。
- `rename-scan/`：改名门禁的镜像扫描（`images.tsv`、`hits.tsv`、`scan.log`、`scan_image.py`、`run.sh`），见[改名门禁的记录](../../../../release/v1.0.0-rc.5/rename.md)。
- `v1.0.0-rc.5/security-uat/`、`security-uate2/`：UAT 之后的安全清点与拒绝探测（见[安全一节](../../../../release/v1.0.0-rc.5/security.md)）；`v1.0.0-rc.5/perf-rc5p/`：性能运行。
- 执行驱动脚本与时间线：`runbook-rc5.md`；`s*.sh`，输出在 `s*.stdout`（两次卸载是 `s6-uninstall.stdout` 与 `s10.stdout`）；清单 PR 的本地 CI 记录 `ci-181-22d898fd/`；补丁标签的核对脚本与记录 `patch-tag-watch.*`。
- 手工清理的记录：`v1.0.0-rc.5/uat-us-west-2/manual-purge-dev-kafka.log` 与同名目录。

证据目录在本候选开始之前（10-27 07:18）改为现在的名字，本候选的工具输出与日志写的都是现在的名字。日志里出现的执行机上的工作区路径，本报告写作 `<clone>/.worktrees/…`。

## 10. 商标

> Apache, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #271）、提交（如 `546d5157`）和 tag（如 `v1.0.0-rc.5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
