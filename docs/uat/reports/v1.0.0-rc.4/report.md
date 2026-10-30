---
title: "Colvenna Lake v1.0.0-rc.4 UAT 报告"
date: 2026-10-27
status: final
issue: 181
---

# v1.0.0-rc.4 UAT 报告

本文是发布候选 `v1.0.0-rc.4` 的 UAT 报告，按 Release 归档。这个候选在验收中途停止：改名门禁（#271）在它的 catalog 镜像里发现了旧名（#286），修复要换 catalog 镜像，维护者 2026-10-27 决定停止本候选，带着修复出 `v1.0.0-rc.5`。所以本报告只记下停止之前的结果；性能验收与安全检查都没有运行。用例与判定规则是候选 tag 上的 [UAT 测试用例](../../cases.md)，两个标准区域 `us-west-2` 与 `us-east-2` 分别执行、分别统计。

## 1. 结论

- 停止之前：`us-west-2` 必需的 43 条中 33 条 PASS、1 条 FAIL（UAT-MON-01）、9 条 NOT_RUN；`us-east-2` 必需的 41 条中 32 条 PASS、9 条 NOT_RUN，没有 FAIL。两个区域都没有 BLOCKED。
- 两个区域 NOT_RUN 的是同样的 9 条：UAT-GOV-02、UAT-ICE-04、UAT-CON-01、UAT-DSH-01、UAT-BI-01、UAT-CON-02、UAT-ENT-01、UAT-NODE-01、UAT-NODE-02。
  - 停止时，两套 UAT 都在 `solo` 阶段的 `governance`（UAT-GOV-02）里。这次运行被中止，没有退出码，也没有写进 `runs.tsv`，所以工具把 UAT-GOV-02 判为 NOT_RUN；中止之前已有的检查见第 6 节。
  - 其余 8 条没有开始。
- 唯一的 FAIL 是测试缺陷：`uat` 的 UAT-MON-01 里，`source-latency-agrees` 的标记与心跳两个中位数差 16 秒，超过允许的 15 秒（#287）。
  - PR #289 在本候选之后修复。
  - 修复后的测试用本候选的镜像，在 `uat` 上单独运行，16/16 通过。这次运行不在 tag 上，不改变本候选的判定（第 6 节）。
- `v1.0.0-rc.3` 的三个产品缺陷，修复都在本候选里得到验证（第 7 节）：
  - #278：`uat` 在 tag 上按清单重装（UAT-DEP-03），11/11，`doris-image` 步骤 5 秒通过；
  - #279：UAT-TBL-01 两个区域都 45/45，包括新加的“升级落在旧作业第一个 checkpoint 之前”的路径；
  - #282：UAT-DEP-07 两个区域都 4/4。卸载删除了 CloudFormation 截短了栈名的 Lambda 日志组：`plat` 7 个，另有 2 个带完整栈名的；`uate2` 9 个，另有 2 个带完整栈名的，共 11 个。
- 本报告只给 UAT 的结果。本候选能否发布，由发布检查（#181）决定：改名门禁未通过，必需用例有 FAIL 与 NOT_RUN，性能与安全没有运行，本候选不能发布。

## 2. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.4`，annotated tag，指向 `6c916d00`（清单 PR #285 的合并提交） |
| 镜像与安装所用的源码 | `f79d9a49`（PR #283 的合并提交，下称 B4）。tag 的提交与它只差 `release/v1.0.0-rc.4/` |
| 发布清单 | [`release/v1.0.0-rc.4/manifest.json`](../../../../release/v1.0.0-rc.4/manifest.json)，schema 2，内容的种类与 `v1.0.0-rc.3` 的相同；生成时 `check-release.sh` 核对了其中 34 个镜像与 colvenna CLI 的二进制 |
| 环境 | 与 `v1.0.0-rc.3` 的三套相同（[rc.3 的报告](../v1.0.0-rc.3/report.md)第 3 节），在同一个账号里。`uat`（`us-west-2`，带 `--fixtures`）保留下来，从 rc.3 的 tag 原地升级；`plat`（`us-west-2`，只装平台）与 `uate2`（`us-east-2`，带 `--fixtures`）在 tag 上按清单从零安装。部署值只在执行机未提交的设置里，这里用设置的键名 |
| 用例与测试 | UAT-DEP-06 的升级在 tag 之前、在 B4 上从源码运行，清单由升级后的 `uat` 核对生成。其余运行都在 tag 上，两次卸载与它们的核对也在内。#287 的单独复测用的是 tag 之后的测试提交（第 6 节），不判定本候选的用例 |
| `RUN_TAG` | `us-west-2`：`rc4w`；`us-east-2`：`rc4e` |
| 补丁窗口 | 执行跨过 10-27 00:00 UTC 的补丁窗口。执行机与三套环境节点的 `Patch Group` 标签事先去掉：`plat`、`uate2` 的节点在 10-26 17:18，执行机与 `uat` 的节点沿用 rc.3 时的处理。之后脚本每 10 分钟核对一次。窗口之后标签被自动补回过，都在下一次核对时去掉：执行机 03:35，`uat` 的节点 03:45，`uate2` 的节点 04:06（`patch-tag-watch.log`） |

## 3. 为什么停止

2026-10-25，#271 补充了改名门禁：对候选的源码与发布制品做旧名扫描，扫描 PASS 之后才能发布；这一项未完成、或结果为 FAIL、UNKNOWN、NOT_RUN 时，发布结论为 NO-GO，GitHub CI 的豁免不豁免它。

- **镜像扫描。** 10-26 23:25–23:51，按清单的 digest 从发布仓库逐个读取 14 个镜像：12 个产品镜像、夹具的 [Apache Spark](https://spark.apache.org/) 读端镜像与 CLI 镜像，jar、zip 里的条目也读（`rename-scan/hits.tsv`）。命中的几乎都是第三方原文，例如 JTS 的几何类名、JDK 里的地名，以及系统库里的字节。
- **源码扫描。** 在 tag 上，历史材料与 rc.1、rc.2 的不可变记录之外，命中的只有说明改名沿革的几句（见 #286）。仓库主页的地址还指向旧的仓库名，已改正。
- **我们自己的一处。** 在 `colvenna-catalog` 镜像的 `colvenna-catalog.jar` 里，`com/colvenna/lake/catalog/db/Migrations.class`。
  - 它是迁移用的 PostgreSQL advisory lock 的键 `ADVISORY_LOCK_KEY`，值是十六进制 `636f76656361744c`，即旧名的 ASCII。
  - 改名时（#272）的文本扫描看不到十六进制的写法。
- **决定。** 修复要换 catalog 镜像，在本候选里做不到。维护者 2026-10-27 决定停止 rc.4，带着修复出 `v1.0.0-rc.5`。
  - 10-27 05:55 左右，向两套 UAT 的驱动进程组发 TERM，两套都停在 `governance` 运行中。
  - 之后在 tag 上卸载了 `uate2`，留给 rc.5 从零安装；`uat` 没有卸载。
- **修复。** #286 由 PR #288 在本候选之后修复，10-27 06:23 合入 main（`62587ba3`）。
  - 锁的键改为新名字的 ASCII（`colvcatL`）。
  - 新加 pre-commit 检查 `deploy/scripts/rename/old_name.py`，十六进制字面量拼出的旧名也查。例外只能列在 `deploy/scripts/rename/exceptions.tsv` 里，逐条写明理由。

## 4. 执行经过

| 时间（UTC，2026-10-26/27） | 区域 | 做了什么 | 用例 |
| --- | --- | --- | --- |
| 10-26 14:16–15:46 | — | PR #280（#278）、#281（#279）、#283（#282）合入，B4 = `f79d9a49` | — |
| 15:47–16:26 | `us-west-2` | `uat` 检出 B4，`prep` 在其上从源码重装：从 rc.3 的 tag（`0cb3e707`）原地升级（`reinstall`，10/10，安装 27 步 2192 秒）；之后 `spark-image` | UAT-DEP-06 |
| 16:26–16:33 | `us-west-2` | 在 B4 上部署发布镜像仓库；`make-manifest.sh` 从 `uat` 核对生成清单，按 digest 复制镜像 | — |
| 16:34–16:48 | — | 清单 PR #285 合入（本地 CI 9/9），annotated tag 打在合并提交上 | — |
| 16:49–17:51 | `us-west-2` | `plat` 在 tag 上按清单从零只装平台（27 步，3641 秒），之后 `check-install` | UAT-DEP-02 |
| 16:49–18:08 | `us-east-2` | `uate2` 在 tag 上按清单从零安装（`--fixtures`，27 步，4676 秒），之后 `check-install --fresh` | UAT-DEP-01 |
| 17:51–19:06 | `us-west-2` | `plat` 演练元数据恢复：快照（恢复 799 秒，catalog 停 193 秒）与按时间点（恢复 940 秒，catalog 停 185 秒） | UAT-DEP-09 |
| 18:08–19:01 | `us-east-2` | `uate2` 的 `deploy` 阶段：配置拒绝、发布清单拒绝、重复执行、中断续装；之后 `prep`：在 tag 上重装（环境已装在本提交上，是重复执行，不判定任何必需用例）、`spark-image` | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 |
| 19:07–19:45 | `us-west-2` | `uat` 与 `uate2` 静止时，在 tag 上卸载 `plat`（19:07–19:44），之后核对 | UAT-DEP-07 |
| 19:45–10-27 05:55 | 两个区域 | 两套 UAT 在 tag 上同时运行：`uat` 从 `deploy` 阶段起，`uate2` 从 `catalog` 阶段起；都运行到 `solo` 阶段的 `governance` | 其余用例 |
| 10-26 23:25–23:51 | — | 改名门禁的镜像扫描（第 3 节）；23:53 开出 #286 | — |
| 10-27 05:55 | 两个区域 | 按维护者的决定停止两套 UAT | — |
| 06:03–06:56 | `us-east-2` | 在 tag 上卸载 `uate2`（06:03–06:55），之后核对 | UAT-DEP-07 |
| 06:15–06:58 | `us-west-2` | 在 `uat` 上单独运行修复后的 `monitoring`（#287，不判定本候选） | — |
| 06:23、07:17 | — | PR #288（#286）、PR #289（#287）合入 main | — |

## 5. 统计与逐例结果

两个区域分别统计。各自的分母是本区域必需的用例，按候选上[用例文档](../../cases.md)第 7 节的“区域”一列。

下面的表由 `tests/acceptance/uat_report.py report` 从证据生成，没有人工判定（没有用 `--overrides`）。`--doc` 指向候选 tag 上的用例文档：main 上的用例文档在 PR #289 之后改了 UAT-MON-01 的输入与步骤。表里的列与 rc.3 的报告相同：

- “检查”是该例最后一次运行的检查结果，“运行”列出这一例的全部运行；
- “证据”是相对证据根目录 `<证据目录>/rc4/v1.0.0-rc.4/` 的路径；
- UAT-DEP-01、UAT-DEP-02 的用时是安装之后那次检查的用时，安装本身的用时见第 4 节。

### 5.1 `us-west-2`（`uat`、`plat`）

| 结果 | 用例数 |
| --- | --- |
| PASS | 33 |
| FAIL | 1 |
| BLOCKED | 0 |
| NOT_RUN | 9 |
| 合计（本区域必需） | 43 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-02 | PASS | 7 PASS | 2026-10-26T17:50:28Z | 0 分 48 秒 | plat-us-west-2/install-platform (exit 0) | `plat-us-west-2/install-platform/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-26T19:53:03Z | 33 分 10 秒 | uat-us-west-2/deploy-repeat (exit 0) | `uat-us-west-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-26T20:26:13Z | 7 分 0 秒 | uat-us-west-2/deploy-resume (exit 0) | `uat-us-west-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-26T19:45:36Z | 3 分 1 秒 | uat-us-west-2/deploy-refusals (exit 0) | `uat-us-west-2/deploy-refusals/` | — |
| UAT-DEP-06 | PASS | 10 PASS | 2026-10-26T15:47:45Z | 38 分 32 秒 | uat-us-west-2/reinstall (exit 0) | `uat-us-west-2/reinstall/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-26T19:44:02Z | 1 分 2 秒 | plat-us-west-2/uninstall (exit 0) | `plat-us-west-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-26T19:48:37Z | 4 分 26 秒 | uat-us-west-2/deploy-release-refusals (exit 0) | `uat-us-west-2/deploy-release-refusals/` | — |
| UAT-DEP-09 | PASS | 36 PASS | 2026-10-26T17:51:35Z | 75 分 9 秒 | plat-us-west-2/metadata-restore (exit 0) | `plat-us-west-2/metadata-restore/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-26T20:33:13Z | 3 分 3 秒 | uat-us-west-2/catalog-flink (exit 0) | `uat-us-west-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-26T20:36:16Z | 1 分 45 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/catalog-spark (exit 0) | `uat-us-west-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-26T20:38:01Z | 4 分 33 秒 | uat-us-west-2/catalog-doris (exit 0) | `uat-us-west-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-26T20:42:34Z | 5 分 3 秒 | uat-us-west-2/catalog-session (exit 0) | `uat-us-west-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-26T20:47:37Z | 1 分 40 秒 | uat-us-west-2/catalog-tokens (exit 0) | `uat-us-west-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-26T20:49:17Z | 0 分 32 秒 | uat-us-west-2/catalog-authorization (exit 0) | `uat-us-west-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-26T20:49:49Z | 2 分 28 秒 | uat-us-west-2/catalog-access (exit 0) | `uat-us-west-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-26T20:52:17Z | 0 分 34 秒 | uat-us-west-2/catalog-health (exit 0) | `uat-us-west-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-26T20:52:51Z | 0 分 7 秒 | uat-us-west-2/catalog-logs (exit 0) | `uat-us-west-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-26T20:52:58Z | 28 分 3 秒 | uat-us-west-2/catalog-commit-faults (exit 0) | `uat-us-west-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-26T21:21:01Z | 30 分 24 秒 | uat-us-west-2/catalog-recovery (exit 0) | `uat-us-west-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-26T21:57:01Z | 102 分 51 秒 | uat-us-west-2/spark-image (exit 0)；uat-us-west-2/renewal-flink (exit 0)；uat-us-west-2/renewal-spark (exit 0)；uat-us-west-2/renewal-catalog (exit 0)；uat-us-west-2/renewal (exit 0)；uat-us-west-2/renewal-catalog-back (exit 0) | `uat-us-west-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-26T23:41:08Z | 63 分 43 秒 | uat-us-west-2/cdc (exit 0) | `uat-us-west-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-10-27T00:44:51Z | 63 分 22 秒 | uat-us-west-2/tables (exit 0) | `uat-us-west-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-27T01:48:14Z | 23 分 11 秒 | uat-us-west-2/mongo (exit 0) | `uat-us-west-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-27T01:48:14Z | 9 分 28 秒 | uat-us-west-2/enforcement (exit 0) | `uat-us-west-2/enforcement/` | — |
| UAT-MON-01 | FAIL | 15 PASS，1 FAIL：source-latency-agrees | 2026-10-27T02:11:25Z | 37 分 13 秒 | uat-us-west-2/monitoring (exit 1) | `uat-us-west-2/monitoring/` | 退出 1；1 项 FAIL |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-27T00:44:51Z | 39 分 36 秒 | uat-us-west-2/tasks (exit 0) | `uat-us-west-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-27T02:48:38Z | 20 分 20 秒 | uat-us-west-2/kafka (exit 0) | `uat-us-west-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-27T03:08:58Z | 42 分 17 秒 | uat-us-west-2/kafka-cdc (exit 0) | `uat-us-west-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-27T03:51:15Z | 23 分 15 秒 | uat-us-west-2/quality (exit 0) | `uat-us-west-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-27T04:14:30Z | 17 分 21 秒 | uat-us-west-2/trino (exit 0) | `uat-us-west-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-27T04:31:51Z | 26 分 18 秒 | uat-us-west-2/iceberg (exit 0) | `uat-us-west-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-27T04:58:09Z | 19 分 1 秒 | uat-us-west-2/iceberg-layered (exit 0) | `uat-us-west-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-27T05:19:40Z | 3 分 0 秒 | uat-us-west-2/namespaces-stack (exit 0)；uat-us-west-2/namespaces-operator (exit 0)；uat-us-west-2/namespaces (exit 0)；uat-us-west-2/namespaces-operator-back (exit 0)；uat-us-west-2/namespaces-stack-back (exit 0) | `uat-us-west-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-27T05:24:48Z | 23 分 28 秒 | uat-us-west-2/lineage (exit 0) | `uat-us-west-2/lineage/` | — |
| UAT-GOV-02 | NOT_RUN | — | — | — | — | — | — |
| UAT-ICE-04 | NOT_RUN | — | — | — | — | — | — |
| UAT-CON-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-DSH-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-BI-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-CON-02 | NOT_RUN | — | — | — | — | — | — |
| UAT-ENT-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-NODE-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-NODE-02 | NOT_RUN | — | — | — | — | — | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-01：至少一个区域（本次 `us-east-2` 的 `uate2`）

不在必需集合里的内容与理由见用例文档第 7 节。

NOT_RUN 的 9 条都因停止而没有判定：UAT-GOV-02 的 `governance` 已经开始，被中止（第 6 节）；其余 8 条没有开始。

### 5.2 `us-east-2`（`uate2`）

| 结果 | 用例数 |
| --- | --- |
| PASS | 32 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT_RUN | 9 |
| 合计（本区域必需） | 41 |

| 用例 | 结果 | 检查 | 开始（UTC） | 用时 | 运行 | 证据 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAT-DEP-01 | PASS | 5 PASS | 2026-10-26T18:07:49Z | 0 分 40 秒 | uate2-us-east-2/install (exit 0) | `uate2-us-east-2/install/` | — |
| UAT-DEP-03 | PASS | 11 PASS | 2026-10-26T18:15:40Z | 20 分 4 秒 | uate2-us-east-2/deploy-repeat (exit 0) | `uate2-us-east-2/deploy-repeat/` | — |
| UAT-DEP-04 | PASS | 3 PASS | 2026-10-26T18:35:44Z | 5 分 58 秒 | uate2-us-east-2/deploy-resume (exit 0) | `uate2-us-east-2/deploy-resume/` | — |
| UAT-DEP-05 | PASS | 49 PASS | 2026-10-26T18:08:48Z | 2 分 36 秒 | uate2-us-east-2/deploy-refusals (exit 0) | `uate2-us-east-2/deploy-refusals/` | — |
| UAT-DEP-07 | PASS | 4 PASS | 2026-10-27T06:55:12Z | 0 分 55 秒 | uate2-us-east-2/uninstall (exit 0) | `uate2-us-east-2/uninstall/` | — |
| UAT-DEP-08 | PASS | 14 PASS | 2026-10-26T18:11:24Z | 4 分 16 秒 | uate2-us-east-2/deploy-release-refusals (exit 0) | `uate2-us-east-2/deploy-release-refusals/` | — |
| UAT-CAT-01 | PASS | 18 PASS | 2026-10-26T19:45:38Z | 3 分 7 秒 | uate2-us-east-2/catalog-flink (exit 0) | `uate2-us-east-2/catalog-flink/` | — |
| UAT-CAT-02 | PASS | 9 PASS | 2026-10-26T19:48:45Z | 1 分 39 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/catalog-spark (exit 0) | `uate2-us-east-2/catalog-spark/` | — |
| UAT-CAT-03 | PASS | 16 PASS | 2026-10-26T19:50:24Z | 4 分 49 秒 | uate2-us-east-2/catalog-doris (exit 0) | `uate2-us-east-2/catalog-doris/` | — |
| UAT-CAT-04 | PASS | 19 PASS | 2026-10-26T19:55:13Z | 5 分 37 秒 | uate2-us-east-2/catalog-session (exit 0) | `uate2-us-east-2/catalog-session/` | — |
| UAT-CAT-05 | PASS | 38 PASS | 2026-10-26T20:00:50Z | 1 分 51 秒 | uate2-us-east-2/catalog-tokens (exit 0) | `uate2-us-east-2/catalog-tokens/` | — |
| UAT-CAT-06 | PASS | 52 PASS | 2026-10-26T20:02:41Z | 0 分 26 秒 | uate2-us-east-2/catalog-authorization (exit 0) | `uate2-us-east-2/catalog-authorization/` | — |
| UAT-CAT-07 | PASS | 14 PASS | 2026-10-26T20:03:07Z | 2 分 32 秒 | uate2-us-east-2/catalog-access (exit 0) | `uate2-us-east-2/catalog-access/` | — |
| UAT-CAT-08 | PASS | 10 PASS | 2026-10-26T20:05:39Z | 0 分 28 秒 | uate2-us-east-2/catalog-health (exit 0) | `uate2-us-east-2/catalog-health/` | — |
| UAT-CAT-09 | PASS | 退出 0 | 2026-10-26T20:06:07Z | 0 分 7 秒 | uate2-us-east-2/catalog-logs (exit 0) | `uate2-us-east-2/catalog-logs/` | — |
| UAT-CAT-10 | PASS | 退出 0 | 2026-10-26T20:06:14Z | 27 分 42 秒 | uate2-us-east-2/catalog-commit-faults (exit 0) | `uate2-us-east-2/catalog-commit-faults/` | — |
| UAT-CAT-11 | PASS | 退出 0 | 2026-10-26T20:33:56Z | 28 分 7 秒 | uate2-us-east-2/catalog-recovery (exit 0) | `uate2-us-east-2/catalog-recovery/` | — |
| UAT-CAT-12 | PASS | 4 PASS | 2026-10-26T21:07:21Z | 102 分 34 秒 | uate2-us-east-2/spark-image (exit 0)；uate2-us-east-2/renewal-flink (exit 0)；uate2-us-east-2/renewal-spark (exit 0)；uate2-us-east-2/renewal-catalog (exit 0)；uate2-us-east-2/renewal (exit 0)；uate2-us-east-2/renewal-catalog-back (exit 0) | `uate2-us-east-2/renewal/` | — |
| UAT-SRC-01 | PASS | 50 PASS | 2026-10-26T22:51:00Z | 66 分 11 秒 | uate2-us-east-2/cdc (exit 0) | `uate2-us-east-2/cdc/` | — |
| UAT-TBL-01 | PASS | 45 PASS | 2026-10-26T23:57:11Z | 63 分 14 秒 | uate2-us-east-2/tables (exit 0) | `uate2-us-east-2/tables/` | — |
| UAT-SRC-02 | PASS | 21 PASS | 2026-10-27T01:00:25Z | 21 分 50 秒 | uate2-us-east-2/mongo (exit 0) | `uate2-us-east-2/mongo/` | — |
| UAT-GOV-01 | PASS | 42 PASS | 2026-10-27T01:00:25Z | 8 分 46 秒 | uate2-us-east-2/enforcement (exit 0) | `uate2-us-east-2/enforcement/` | — |
| UAT-MON-01 | PASS | 16 PASS | 2026-10-27T01:22:15Z | 37 分 39 秒 | uate2-us-east-2/monitoring (exit 0) | `uate2-us-east-2/monitoring/` | — |
| UAT-TSK-01 | PASS | 47 PASS | 2026-10-26T23:57:11Z | 37 分 27 秒 | uate2-us-east-2/tasks (exit 0) | `uate2-us-east-2/tasks/` | — |
| UAT-SRC-03 | PASS | 32 PASS | 2026-10-27T01:59:54Z | 23 分 58 秒 | uate2-us-east-2/kafka (exit 0) | `uate2-us-east-2/kafka/` | — |
| UAT-SRC-04 | PASS | 24 PASS | 2026-10-27T02:23:52Z | 41 分 36 秒 | uate2-us-east-2/kafka-cdc (exit 0) | `uate2-us-east-2/kafka-cdc/` | — |
| UAT-QUA-01 | PASS | 56 PASS | 2026-10-27T03:05:28Z | 21 分 57 秒 | uate2-us-east-2/quality (exit 0) | `uate2-us-east-2/quality/` | — |
| UAT-ICE-01 | PASS | 40 PASS | 2026-10-27T03:27:25Z | 16 分 15 秒 | uate2-us-east-2/trino (exit 0) | `uate2-us-east-2/trino/` | — |
| UAT-ICE-02 | PASS | 64 PASS | 2026-10-27T03:43:40Z | 25 分 9 秒 | uate2-us-east-2/iceberg (exit 0) | `uate2-us-east-2/iceberg/` | — |
| UAT-ICE-03 | PASS | 10 PASS | 2026-10-27T04:08:49Z | 19 分 5 秒 | uate2-us-east-2/iceberg-layered (exit 0) | `uate2-us-east-2/iceberg-layered/` | — |
| UAT-NS-01 | PASS | 36 PASS | 2026-10-27T04:30:21Z | 2 分 44 秒 | uate2-us-east-2/namespaces-stack (exit 0)；uate2-us-east-2/namespaces-operator (exit 0)；uate2-us-east-2/namespaces (exit 0)；uate2-us-east-2/namespaces-operator-back (exit 0)；uate2-us-east-2/namespaces-stack-back (exit 0) | `uate2-us-east-2/namespaces/` | — |
| UAT-LIN-01 | PASS | 31 PASS | 2026-10-27T04:35:12Z | 23 分 7 秒 | uate2-us-east-2/lineage (exit 0) | `uate2-us-east-2/lineage/` | — |
| UAT-GOV-02 | NOT_RUN | — | — | — | — | — | — |
| UAT-ICE-04 | NOT_RUN | — | — | — | — | — | — |
| UAT-CON-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-DSH-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-BI-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-CON-02 | NOT_RUN | — | — | — | — | — | — |
| UAT-ENT-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-NODE-01 | NOT_RUN | — | — | — | — | — | — |
| UAT-NODE-02 | NOT_RUN | — | — | — | — | — | — |

本区域范围外的必需用例（按用例文档第 7 节的“区域”一列，在另一个区域执行）：

- UAT-DEP-02：至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装）
- UAT-DEP-06：升级的那套环境所在的区域（`us-west-2` 的 `uat`）
- UAT-DEP-09：至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次）

其他运行（不判定本区域的任何必需用例）：uate2-us-east-2/reinstall (exit 0)

不在必需集合里的内容与理由见用例文档第 7 节。

NOT_RUN 的 9 条与 `us-west-2` 的相同，也都因停止而没有判定。

## 6. 失败与中断

| 区域 | 运行 | 现象 | 原因 | 类别 | 处理 |
| --- | --- | --- | --- | --- | --- |
| `us-west-2` | `monitoring`（`ingest`，UAT-MON-01），tag | `source-latency-agrees` FAIL：标记的延迟 `[3, 4, 9, 12, 20, 28]` 秒，中位数 10 秒；心跳的中位数 26 秒，两者差 16 秒，允许的是 `max(15 秒, 标记中位数的 25%)`。其余 15 项 PASS，退出 1。`uate2` 上同一个测试 16/16 通过 | 每笔标记订单由一个新起的 Pod 写入，Pod 启动要 20–30 秒，标记落在 30 秒 checkpoint 周期的哪个位置由启动的快慢决定，于是 6 笔标记的中位数随机摆动（按 Pod 的启动时间模拟，p1–p99 为 7.6–28.2 秒）。心跳是 15 分钟里很多次测量的中位数，稳定得多。产品的心跳延迟没有测错：26 秒在以往的范围里 | 测试 | #287，PR #289 在本候选之后修复：10 笔标记在一个 MySQL 会话里每 3 秒一笔，覆盖周期的各个相位（模拟为 16.4–19.6 秒）；容差不变。只改测试，产品不变 |
| 两个区域 | `governance`（`solo`，UAT-GOV-02），tag | 停止时运行中，被中止：没有退出码，`runs.tsv` 没有这一行。`uat`：05:48 开始，28 项检查中 3 项已 PASS（`source-ready`、`source-governed`、`source-published`），刚建好两张表。`uate2`：04:58 开始，25 项已 PASS，没有 FAIL；最后一行是 05:36 的 `past the physical deadline`，之后没有输出 | 维护者停止本候选（第 3 节） | 中止 | 不判定，工具判为 NOT_RUN。被中止的运行没有走到它自己的清理：`uate2` 随后整套卸载；`uat` 上它留下的 LakeSource、两张 LakeTable 与它们的 LakeTask、Secret 和一个辅助 Pod，停止后由执行者按测试自己的清理步骤删除，之后 `uat` 上没有这次运行的任何对象（记在执行驱动的时间线 `rc4/runbook-rc4.md`） |

**UAT-MON-01 的单独复测。** 10-27 06:27–06:58，在 `uat` 上单独运行修复后的测试（`mon287b`，证据在 `<证据目录>/rc4/issue-287-check/monitoring-b/`），16/16 PASS。`source-latency-agrees` 的标记中位数 17 秒、心跳中位数 15 秒。

- 测试在 `9d9ca36a` 上运行，与 `b444004e` 是同一棵树：只改了提交说明。它们之后变基进 PR #289，成为 `7a21d544`，`tests/acceptance/monitoring/` 与这两个提交的相同。
- 镜像是本候选的：`images.txt` 里的 3 个 digest 与 tag 上 `monitoring` 运行的相同。
- 在它之前，06:15–06:24 有一次尝试（`mon287a`，`issue-287-check/monitoring/`）：它没有指定镜像，在写标记的那一步找不到测试提交的镜像，停下（此前 6 项检查 PASS），不算判定。
- 这次复测不在 tag 上，所以本候选的 UAT-MON-01 仍是 FAIL。下一个候选在两个区域重跑。

## 7. `v1.0.0-rc.3` 的缺陷在本候选上的验证

| 问题 | 本候选的变更 | 本次的表现 |
| --- | --- | --- |
| #278 查询引擎镜像在环境仓库里的 tag 不随 digest 变（rc.3 的 UAT-DEP-03 在 `uat` 上 FAIL） | PR #280：环境仓库里的 tag 带上 digest 的前 12 位，源码安装与按候选安装同一种写法；digest 已在、只挂着别的 tag 时补上 tag | `uat` 的 `deploy-repeat` 在 tag 上按清单重装，11/11：安装 27 步 1820 秒，`doris-image` 步骤 5 秒，按 digest 认出 FE `8ef38589…`、BE `d9835517…`；7 个栈的 161 项资源、4 个节点、22 个工作负载与 3 个卷都没有变，没有构建镜像。之前 UAT-DEP-06 在 B4 上的升级 10/10。`uate2` 的 UAT-DEP-03 也 11/11 |
| #279 不兼容升级落在旧作业（一个 [Apache Flink](https://flink.apache.org/) 作业）第一个 checkpoint 之前，回滚被拒，改回声明也不恢复（rc.3 的 UAT-TBL-01 在 `uate2` 上 FAIL） | PR #281：升级开始时记下旧作业用过的最大 checkpoint id 作为基准，被拒之后改回声明能回滚。`tables` 加了一步，确定会走到这条路径，期望从 37 项改为 45 项 | 两个区域都 45/45。新路径：`before-checkpoint-upgrading` 与 `before-checkpoint-rolled-back` 都 PASS，回滚用时 `uat` 671 秒、`uate2` 687 秒，改回之后恢复运行。原来那次不兼容升级的回滚也 PASS（699 秒、673 秒） |
| #282 卸载漏删栈名被截短的 Lambda 日志组，又报告没有遗留（rc.3 的 UAT-DEP-07 两个区域都 FAIL） | PR #283：卸载的清扫与遗留检查都认得 CloudFormation 截短的栈名；卸载的核对也认得所有被截短的名字 | 两个区域的 UAT-DEP-07 都 4/4（`s6-uninstall.stdout`、`s10.stdout`）。`plat`：删除 9 个 Lambda 日志组，其中 7 个是截短的 `/aws/lambda/ColvennaLakePlatFoundatio-…`，2 个带完整栈名；`nothing-of-it-left` PASS，`others-untouched` 185 项都与卸载前相同。`uate2`：删除 11 个，其中截短的 9 个（`ColvennaLakeUate2Foundati-…` 7 个，`ColvennaLakeUate2TestMysq-…` 与 `ColvennaLakeUate2TestPost-…` 各 1 个），2 个带完整栈名；`others-untouched` 99 项都与卸载前相同 |

UAT-DEP-07 在 `us-east-2` 的执行有一处不符合用例的前置条件（“卸载期间不部署、不测试别的环境”）：`uate2` 卸载期间（06:03–06:56），`uat` 上在运行 #287 的单独复测（06:15–06:58）。`others-untouched` 比较的 99 项，`uat` 的栈与资源在内，都与卸载之前相同，工具判 PASS，本报告没有改判。这是执行者的疏忽。那次复测只在 `uat` 的集群里建声明与 Pod、往测试源写行，在账号里不建任何资源（对 AWS 只读调用者身份），而 UAT-DEP-07 比对的正是账号里的资源，所以这次比对仍然有效。下一个候选卸载 `uate2` 时，`uat` 上不运行任何测试。

## 8. 证据

证据保存在执行机的 `<证据目录>/rc4/`，目录只有执行者本人可读，不入库，不含口令与密钥：

- `v1.0.0-rc.4/uat-us-west-2/`、`plat-us-west-2/`、`uate2-us-east-2/`：每套环境一个 UAT 证据目录。
  - `uat.log` 记每个运行的起止与测试所在的提交，`runs.tsv` 每个运行一行，`<标签>.log` 与 `<标签>/` 是各运行的输出与证据。
  - `plat-us-west-2/` 与 `uate2-us-east-2/` 的 `summary.tsv`，是运维执行的核对最后一次（卸载的核对）写出的。`uat-us-west-2/` 没有 `summary.tsv`：UAT 停在 `summary` 阶段之前。
  - 被中止的 `governance` 只有 `governance/` 与 `governance.log`，没有 `runs.tsv` 的一行。
- 安装证据（`install/`、`reinstall/`、`deploy-repeat/install/`）与卸载证据（`uninstall-before/`、`uninstall/`）。
- `issue-287-check/`：#287 的单独复测（`monitoring-b/`）与之前的那次尝试（`monitoring/`）；`NOTE.txt` 记下两个测试提交的关系。
- `rename-scan/`：改名门禁的镜像扫描（`images.tsv`、`hits.tsv`、`scan.log`）。
- 执行驱动脚本与时间线：`runbook-rc4.md`；`s*.sh`，输出在 `s*.stdout`（两次卸载是 `s6-uninstall.stdout` 与 `s10.stdout`）；清单 PR 的本地 CI 记录 `ci-181-b99deddb/`；补丁标签的核对脚本与记录 `patch-tag-watch.*`。

工具输出与日志里写的证据路径，用的是这个目录改名之前的名字。旧名字现在是指向它的符号链接，是同一个目录；本报告一律写现在的名字。

## 9. 商标

> Apache, Apache Flink, Flink, Apache Doris, Doris, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；查询引擎镜像是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建，不是 ASF 的发布。其他各方的商标与各镜像的命名依据，见候选版本的[商标与命名](../../../../release/trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #271）、提交（如 `6c916d00`）和 tag（如 `v1.0.0-rc.4`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
