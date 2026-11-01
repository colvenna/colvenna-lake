---
title: "Colvenna Lake UAT 测试用例"
date: 2026-10-20
status: draft
issue: 163
---

# UAT 测试用例

本文是 Colvenna Lake 用户验收测试（UAT）的用例。用例覆盖 M1–M5 已交付的能力，以及部署运维与节点故障。每条用例对应一个验收测试脚本或一个运维步骤，写明对应的需求与验收报告、适用配置、前置条件、输入（用到的合成数据与参数）、步骤、期望结果、它对共享环境的影响与清理方式。第 5.1–5.9 节按 `tests/acceptance/run-uat.sh` 的执行顺序排列；部署运维（5.10 节）的用例分别在 `prep`、`deploy` 阶段运行或由运维在 UAT 前后执行，节点故障（5.11 节）在最后的 `nodes` 阶段运行。本次 Release 必须执行哪些用例，见第 7 节。

执行结果按发布候选归档在 `reports/<候选>/report.md`（最新的是 [`v1.0.0-rc.5`](reports/v1.0.0-rc.5/report.md)）：每条必需用例、每个区域记 PASS、FAIL、BLOCKED 或 NOT_RUN 之一与证据位置（第 7 节），并记录执行环境与提交 SHA；每个 FAIL 关联一个 issue 或修复。更早的一次执行见 [UAT 执行报告](report.md)。

## 1. 范围

UAT 验证功能，覆盖：

- 数据源接入：MySQL、PostgreSQL、MongoDB、Kafka 事件，以及 Kafka 承载的 CDC；
- 分层表 DWD、DWS；维护任务与回刷任务；质量门禁与豁免；治理与合规删除；
- 看板与 BI；Iceberg 发布；血缘；入口：控制台与 CLI；
- 控制平面 colvenna-catalog：Flink、Spark、Doris 经 REST 读写，按表数据凭证，授权与审计，提交故障，恢复，令牌续期；
- 监控与告警；第二个受管命名空间；
- 认证与权限的负向用例：未认证的请求得到 401、403 或连不上，越权访问被拒绝。它们分布在各用例的期望结果里，标为“（负向）”，第 6 节按用例汇总；
- 部署运维：从零安装（带 `--fixtures` 与只装平台）、重复执行、中断后续装、配置拒绝、原地升级、卸载、按发布清单安装，以及元数据从 RDS 备份恢复；
- 故障恢复：除 catalog 的提交故障与实例故障（UAT-CAT-10、UAT-CAT-11）之外，一台节点丢失之后作业、表、看板与入口的恢复。

边界：

- 性能与容量不在 UAT 里重测，沿用开发阶段的结论，见[性能测试](performance.md)。`run-dashboards.sh` 的负载、突发下的看板时延、各路径新鲜度与稳态只在 `PERF=1` 时运行，`run-uat.sh` 不设这个变量。
- 留在 UAT 里的时间界限验证的是功能：某个行为在设计给定的时间内发生。例如 Iceberg append 表的提交 90 s 内发布，看板的 BE 或 FE 被删后 1800 s 内恢复，告警在规则窗口内触发与解除，会话在超时前后结束。
- 只用合成数据。测试源库是 `--fixtures` 部署的 RDS MySQL 与 PostgreSQL；开发用 Kafka 与 MongoDB 由测试自己安装；每个测试建自己的库表、写自己的数据。
- 第二朵云（#33）不在范围内。
- 执行中发现的问题另开 issue，执行报告引用它们。

## 2. 不在 UAT 范围

| 内容 | 原因 |
| --- | --- |
| colvenna-catalog 自身的升级、回滚，以及旧版本服务端拒绝新 schema（原 `tests/acceptance/catalog/run-upgrade.sh`，#295 删除） | colvenna-catalog 现在是独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog)，本仓库不再构建它的镜像，这些归它自己的验收；本仓库验收的是装上它固定的发布版、原地接管已有环境（UAT-DEP-06）。维护者也决定开发阶段的升级不保持版本兼容，不做跨版本回退演练 |
| `tests/upstream/flink-cdc/`：`run-pipeline.sh`（Flink CDC 3.7 YAML Pipeline）与 `run-repro.sh`（FLINK-40741 的本机复现） | YAML Pipeline 是评估过、1.0 不采用的接入路径，见 [M3 Flink CDC 3.7 兼容性评估](../history/m3/flink-cdc-3.7.md)的“选择”一节；两个脚本都在执行机本地的 Docker 里运行，不在 EKS 环境上 |
| `run-dashboards.sh` 在 `PERF=1` 下的 8 项检查 | 性能目标，见[性能测试](performance.md) |
| `tests/acceptance/test_*.py` | 判定脚本的离线单元测试，由 CI 运行 |
| `catalog/drop-database.sh`、`catalog/run-catalog-tool.sh` 等辅助脚本 | 被用例调用，或在 UAT 之后清理用，本身不是用例 |

## 3. 环境与执行

### 3.1 环境

- 按[安装部署手册](../manual/install.md)从零新建一套环境，带测试夹具：`COLVENNA_ENV=<env> COLVENNA_REGION=<区域> deploy/install.sh --fixtures`。写入节点与查询节点各 2 台，即默认规模。环境的区域记在它的本地状态里，测试从那里读，不必再给 `COLVENNA_REGION`。
- 执行用例与运维步骤的 shell 里先导出 `AWS_PROFILE=work`（执行机的 shell 默认导出的 profile 属于别的账号，脚本的第一项检查会拒绝它）与 `COLVENNA_ENV`。环境的区域、账号、托管区与入口域名（[安装部署手册](../manual/install.md)第 1 节"账号、托管区与入口域名"）在第一次安装时记进本地状态，测试从那里读，不必再给；给了不同的值会被拒绝。
- 在保存这套环境本地状态的工作区里执行（`deploy/.state/<env>/`：kubeconfig 与各镜像的 digest 记录），工作区不能有未提交的改动。测试拒绝在有改动的工作区上运行，并按提交查找镜像记录：`prep` 阶段把它所在的提交写进 `<证据目录>/installed-sha`，之后同一证据目录下的运行都经 `IMAGES_SHA` 读这个提交的镜像。所以新提交只改了测试时，不必重装就能在新提交上重跑；改了产品代码则要重跑 `prep`。
- 执行机除手册第 2 节的条件外，还要有：
  - mvn 与 JDK：UAT-CAT 各用例在本机打包流程 jar、生成信任库；
  - Docker buildx：`prep` 阶段构建镜像；
  - python3、curl、openssl；
  - kubeconfig 的身份能以 `kubectl --as` 扮演用户：UAT-QUA-01、UAT-CON-01；
  - 测试的 port-forward 都由 kubectl 选空闲的本机端口，不占固定端口；
  - 能访问 Docker Hub、quay.io 与 Maven Central。
- 两套环境的 UAT 可以在同一台执行机上同时运行，各在保存自己本地状态的工作区里执行：测试的 port-forward 不占固定的本机端口。
- 按发布清单装的环境（UAT-DEP-08），设 `UAT_INSTALL_ARGS='--release <候选>'`，并 `export COLVENNA_RELEASE_REGISTRY=<发布仓库>`：`prep` 与 `deploy` 阶段的重装与续装都带上它们。
- 测试用的第三方镜像（MongoDB、AWS CLI、Airflow、Debezium Server、Trino、Kafka 客户端等，`deploy/images/third-party.tsv`）从本环境的 `colvenna-lake-<env>/third-party` 拉，由安装的 `third-party` 步骤复制：适用于只装平台的用例用到的（MongoDB、AWS CLI 等，范围 `platform`）每次安装都复制，其余带 `--fixtures` 时复制；只改测试的提交改了其中某个 digest 时，先运行 `deploy/scripts/foundation/copy-third-party.sh --fixtures`。
- 每条用例的“适用配置”写明它能在哪种安装上运行：只装平台、`--fixtures`，或两者都可以；区域写“任一标准区域”的，在哪个标准区域都可以运行。UAT 在带 `--fixtures` 的环境上执行。
- UAT 会跨过 00:00 UTC 时，先去掉执行机与节点的 `Patch Group` 标签，执行期间定时复查（[安装部署手册](../manual/install.md)第 9 节）。`nodes` 阶段补回的新节点可能在几分钟后被重新打上，复查要覆盖它们。

### 3.2 执行

```bash
COLVENNA_ENV=<env> tests/acceptance/run-uat.sh <证据目录>                 # 全部阶段
COLVENNA_ENV=<env> tests/acceptance/run-uat.sh <证据目录> kafka summary   # 只跑指定阶段
COLVENNA_ENV=<env> tests/acceptance/rerun-uat.sh <证据目录> cdc-2 \
  bash tests/acceptance/cdc/run-cdc.sh cdcb<tag> <证据目录>/cdc-2           # 修好后重跑一个测试
```

- 不指定阶段时按下表顺序执行全部阶段。
- 每个测试的运行 id 是“代码 + `RUN_TAG`”，例如 `cdc` 加 `10201530`。`RUN_TAG` 默认取 UTC 的月、日、时、分，最多 8 个小写字母或数字。测试会留下以运行 id 命名的对象（读权限、Polaris principal 等），所以同一个运行 id 不能用两次。
- 一个测试失败不中断执行，下一个测试照常开始。
- 同一个证据目录里，每个阶段只跑一次。修好一个测试后重跑它，用 `rerun-uat.sh`：标签加后缀（`-2`、`-3`），运行 id 在代码后换一个字母（例如 `cdcb<tag>`），证据目录也用新的。不要在同一个证据目录里以原标签重跑：日志会被覆盖，测试的 `verdicts.tsv` 是追加写的，新旧结果会混在一起。`summary` 阶段列出新旧所有运行。
- 按 2026-10-20 至 21 日那次执行里各用例最后一次运行的用时（[执行报告](report.md)），当时的阶段约 13.4 小时，其中 `renewal` 约 1 小时 45 分钟、`governance` 约 1 小时 26 分钟；后来加的 `deploy` 阶段约 50 分钟、`nodes` 阶段约 30 分钟。

| 阶段 | 运行（标签） | 用例 | 说明 |
| --- | --- | --- | --- |
| `prep` | `reinstall`、`spark-image` | UAT-DEP-06（环境装在更早的提交上时） | 以本提交再跑一次 `install.sh`（`tests/acceptance/deploy/run-reinstall.sh`：有测试源栈时带 `--fixtures`，另加 `UAT_INSTALL_ARGS`；每步收敛，镜像按提交记录），按升级或重复判定（环境装在只在 `release/` 下与本提交不同的提交上时，产品相同，是重复）；再构建 catalog 流程用的 Spark 镜像（`catalog/build-spark-image.sh`，不是安装步骤） |
| `deploy` | `deploy-refusals`、`deploy-release-refusals`、`deploy-repeat`、`deploy-resume` | UAT-DEP-05、UAT-DEP-08、UAT-DEP-03、UAT-DEP-04 | 逐个运行；`deploy-release-refusals` 只在按发布清单安装、`UAT_INSTALL_ARGS` 带 `--release <候选>` 时运行。`deploy-repeat` 约 35 分钟，`deploy-resume` 约 10 分钟 |
| `catalog` | `catalog-flink`、`catalog-spark`、`catalog-doris`、`catalog-session`、`catalog-tokens`、`catalog-authorization`、`catalog-access`、`catalog-health`、`catalog-logs` | UAT-CAT-01–09 | 逐个运行；Flink、Spark、Doris 依次用同一个库 `flow_cf<tag>` |
| `faults` | `catalog-commit-faults`、`catalog-recovery` | UAT-CAT-10、UAT-CAT-11 | 逐个运行，各自独占环境 |
| `renewal` | `renewal-flink`、`renewal-spark`、`renewal-catalog`、`renewal`、`renewal-catalog-back` | UAT-CAT-12 | 逐个运行；`renewal` 约 1 小时 45 分钟，独占环境；之后 catalog 恢复默认的令牌有效期 |
| `ingest` | `cdc`；`tables` 与 `tasks`；`mongo` 与 `enforcement`；`monitoring` | UAT-SRC-01、UAT-TBL-01、UAT-TSK-01、UAT-SRC-02、UAT-GOV-01、UAT-MON-01 | 四组依次运行，同组的两个同时运行。这是 2026-10-20 在默认规模上实际跑通的分组 |
| `kafka` | `kafka`、`kafka-cdc`、`quality`、`trino`、`iceberg`、`iceberg-layered` | UAT-SRC-03、UAT-SRC-04、UAT-QUA-01、UAT-ICE-01–03 | 逐个运行：它们共用一套开发用 Kafka，每个测试的安装都替换它的 topic 与用户 |
| `namespaces` | `namespaces-stack`、`namespaces-operator`、`namespaces`、`namespaces-operator-back`、`namespaces-stack-back` | UAT-NS-01 | 之后删除命名空间 `colvenna-second` 与仓库桶里的 `flink-state/colvenna-second/` |
| `solo` | `lineage`、`governance`、`iceberg-erasure`、`operations`、`dashboards` | UAT-LIN-01、UAT-GOV-02、UAT-ICE-04、UAT-CON-01、UAT-DSH-01 | 逐个运行：每个都观察或删除整个受管命名空间里的声明 |
| `console` | `bi` 与 `pipeline` | UAT-BI-01、UAT-CON-02 | 同时运行 |
| `entry` | `entry-stack`、`entry`、`entry-stack-back` | UAT-ENT-01 | 运行期间会话时长临时改为 600 s |
| `nodes` | `node-job`、`node-query` | UAT-NODE-01、UAT-NODE-02 | 逐个运行，各让整套环境丢一台节点 |
| `summary` | — | — | 生成 `summary.tsv` |

**运维执行的用例。** UAT-DEP-01、UAT-DEP-02、UAT-DEP-07、UAT-DEP-09 不在 `run-uat.sh` 里：从零安装在 UAT 之前，卸载在 UAT 与其他检查都完成之后；元数据恢复替换整个 `colvenna_catalog` 库，在环境里没有别的测试运行时单独执行。它们的脚本把自己记成这个证据目录里的一次运行，与 `rerun-uat.sh` 一样：输出在 `<root>/<标签>.log`，判定在 `<root>/<标签>/verdicts.tsv`，`runs.tsv` 与 `uat.log` 各多一行，`summary` 阶段与报告据此统计。标签默认为下表的 `install`、`install-platform`、`metadata-restore`、`uninstall`；重跑时以 `--label`（例如 `install-2`）另给一个：

| 用例 | 什么时候 | 命令 | 证据目录 |
| --- | --- | --- | --- |
| UAT-DEP-01 | 从零新建环境之后、`prep` 之前 | `tests/acceptance/deploy/check-install.sh <root> --fresh <安装证据目录>...` | `<root>/install/`，另复制安装证据 |
| UAT-DEP-02 | 只装平台的环境装好之后 | `tests/acceptance/deploy/check-install.sh <root> --fresh --platform <安装证据目录>...` | `<root>/install-platform/` |
| UAT-DEP-09 | 环境里没有别的测试在运行时 | `tests/acceptance/deploy/run-metadata-restore.sh <root>`：从快照与按时间点各演练一次 | `<root>/metadata-restore/`（两次演练的证据在其下的 `snapshot/`、`time/`） |
| UAT-DEP-07 | 卸载之前与之后 | 卸载前 `tests/acceptance/deploy/inventory.sh <root>/uninstall-before --account`，卸载后 `tests/acceptance/deploy/check-uninstall.sh <root> <root>/uninstall-before <卸载证据目录>` | `<root>/uninstall/` |

### 3.3 结果与判定

结果都在证据目录下：

| 文件 | 内容 |
| --- | --- |
| `uat.log` | `run-uat.sh` 每次运行先记一行环境名、提交 SHA、`RUN_TAG` 与所跑阶段，之后是每个运行的起止；运维执行的判定脚本（第 3.2 节）与 `rerun-uat.sh` 也记自己的起止。UAT-DEP-01 在 `prep` 之前判定时，文件的第一行是它的 |
| `runs.tsv` | 每个运行一行：标签、退出状态、开始时间、秒数 |
| `<标签>.log` | 该运行的标准输出与标准错误 |
| `<标签>/` | 该测试的证据目录。M3–M5 的测试在其中写 `verdicts.tsv`：时间、检查名、PASS 或 FAIL、细节 |
| `summary.tsv` | 每项检查一行：标签、检查名、PASS 或 FAIL、细节 |

`summary.tsv` 由 `tests/acceptance/uat_report.py summary` 生成，执行报告的逐例结果由同一个脚本的 `report` 生成，两者按同样的方式读取每个运行的检查结果：

- 有 `verdicts.tsv` 的测试，取其中每一行。
- M2 的检查不写 `verdicts.tsv`。取 `uat-cases.tsv` 为该用例写明的证据文件里的结果行（`STEP <检查> OK`、`CHECK <检查> expected=… actual=… OK`、`<检查> expected=… actual=… [OK]`，没有 OK 或 FAIL 时按两值是否相等判定，以及 `<检查> OK <细节>`）；证据文件里没有时，取日志里的 `STEP`、`CHECK` 与 `expected=… actual=…` 行；都取不到时记一行 `(exit status)`。
- 运行非零退出而没有 FAIL 行时，也记一行 `(exit status)` 为 FAIL。
- 准备类的运行（`spark-image`、`renewal-catalog`、`namespaces-stack`、`entry-stack` 等）同样以退出状态记一行。它们不是用例，但失败时依赖它们的用例不可信，判为 `BLOCKED`：`uat-cases.tsv` 把它们列在用例的其余标签里。
- `reinstall`（`prep` 阶段）与 `deploy` 阶段的运行写自己的 `verdicts.tsv`。环境装在更早的提交上时，`reinstall` 是 UAT-DEP-06 的判定运行（`reinstall/kind` 为 `upgrade`）；装在本提交上、或装在只在 `release/` 下不同的提交上时，它是一次重复执行（`repeat`），按 UAT-DEP-03 的检查判定，不计入 UAT-DEP-06；UAT-DEP-03 本身由 `deploy-repeat` 判定。
- 运维执行的 `install`、`install-platform`、`metadata-restore` 与 `uninstall` 由各自的脚本记进 `runs.tsv`，同样有 `verdicts.tsv`（第 3.2 节）。

用例的判定：运行退出 0，期望结果表里的每项检查都出现并为 PASS，期望结果写明的项数、“最后输出”与“最后一行”都符合，即为通过。注明“只在失败时出现”的检查可以不出现；注明“仅在 `Y` 通过后判定”的检查在 `Y` 通过时必须出现。表头是 `STEP`、`CHECK` 或“检查”的表列的是检查；其余的表（例如 UAT-CAT-10 的“`faults.txt` 中的行”、UAT-CAT-11 的“`FAIL:` 行”）说明的是证据文件里的行，按“最后一行”判定。期望结果的表列了检查而一项结果也读不到，判为 FAIL。

用例与运行的对应在 `tests/acceptance/uat-cases.tsv`：每条用例一行，列出它的阶段（运维执行的写 `operator`）、运行的标签（第一个是判定它的运行，其余是它依赖的准备运行）、适用配置与证据。重跑的运行以标签加后缀（`-2`、`-3`）识别，不另占一行。`tests/acceptance/test_uat_cases.py` 核对这张表与本文的用例标题、第 7 节的必需集合、`run-uat.sh` 的标签一致，由 CI 运行。

下文的写法：`<tag>` 是 `RUN_TAG`，`<root>` 是证据目录，`<run>` 是该测试的运行 id。

## 4. 覆盖对照

各项能力怎么用，见[用户手册](../manual/user.md)；手册附录 A 按流程列出对应的用例，与下表互相对照。

| 能力 | 用例 |
| --- | --- |
| 数据源接入：MySQL | UAT-SRC-01 的 MySQL 部分；UAT-TBL-01、UAT-TSK-01、UAT-GOV-02 等以 MySQL 源为输入 |
| 数据源接入：PostgreSQL | UAT-SRC-01 的 PostgreSQL 部分 |
| 数据源接入：MongoDB | UAT-SRC-02 |
| 数据源接入：Kafka 事件 | UAT-SRC-03；UAT-QUA-01、UAT-ICE-01、UAT-ICE-02、UAT-NS-01 以 Kafka 事件源为输入 |
| 数据源接入：Kafka 承载的 CDC | UAT-SRC-04 |
| 分层表 DWD、DWS | UAT-TBL-01；UAT-TSK-01、UAT-MON-01、UAT-LIN-01、UAT-CON-02 也建 DWD 与 DWS |
| 维护任务 | UAT-TSK-01（tag、compact、expire、removeOrphanFiles、串行、重试）；UAT-CON-01（控制台与 CLI 发起） |
| 回刷任务 | UAT-TSK-01；UAT-LIN-01（回刷作为批运行上报血缘）；UAT-CON-01 |
| 质量门禁与豁免 | UAT-QUA-01；UAT-MON-01（不合格行隔离）；UAT-CON-01（check 与 waive） |
| 治理：分级、保留、按分级发布 | UAT-GOV-02 |
| 治理：列标签、脱敏、行过滤 | UAT-GOV-01；UAT-BI-01 |
| 合规删除 | UAT-GOV-02；UAT-ICE-04（含 Iceberg 元数据）；UAT-CON-01（forget） |
| 看板 | UAT-DSH-01；UAT-GOV-01 |
| BI | UAT-BI-01 |
| Iceberg 发布 | UAT-ICE-02、UAT-ICE-03、UAT-ICE-04；已有 Trino 经 Iceberg 读取：UAT-ICE-01 |
| 血缘 | UAT-LIN-01；UAT-CON-02（控制台展示的血缘与 Marquez 一致） |
| 入口：控制台 | UAT-ENT-01（登录、会话、暴露面）；UAT-CON-01（受控操作）；UAT-CON-02（流水线全景）；UAT-BI-01（与 BI 同一次登录） |
| 入口：CLI | UAT-CON-01（`colvenna` 与控制台做同一操作的结果相同）；UAT-QUA-01（`colvenna check`、`colvenna waive`） |
| catalog：Flink、Spark、Doris 经 REST | UAT-CAT-01、UAT-CAT-02、UAT-CAT-03、UAT-CAT-04 |
| catalog：按表数据凭证 | UAT-CAT-05；续期：UAT-CAT-12 |
| catalog：授权与审计 | UAT-CAT-06 |
| catalog：令牌与网络访问检查 | UAT-CAT-07 |
| catalog：健康与端点 | UAT-CAT-08、UAT-CAT-09 |
| catalog：提交故障 | UAT-CAT-10 |
| catalog：恢复（节点排空、元数据恢复） | UAT-CAT-11 |
| catalog：令牌续期 | UAT-CAT-12 |
| 监控与告警 | UAT-MON-01；UAT-CON-02（告警定位） |
| 第二个受管命名空间 | UAT-NS-01 |
| 认证与权限的负向用例 | 见第 6 节 |
| 部署运维：从零安装（`--fixtures`、只装平台） | UAT-DEP-01、UAT-DEP-02 |
| 部署运维：重复执行、中断后续装、原地升级 | UAT-DEP-03、UAT-DEP-04、UAT-DEP-06 |
| 部署运维：配置拒绝（不支持的区域、中国区与 GovCloud、名字被别的区域占用、错误区域卸载等） | UAT-DEP-05 |
| 部署运维：卸载，以及只影响目标环境 | UAT-DEP-07 |
| 部署运维：按发布清单安装固定发行物 | UAT-DEP-08 |
| 部署运维：元数据从 RDS 备份恢复，表目录挂回 | UAT-DEP-09 |
| 故障恢复：节点丢失 | UAT-NODE-01（CDC 作业 TaskManager 所在的节点，不论角色）、UAT-NODE-02（Doris FE 所在的查询节点）；catalog 的实例故障见 UAT-CAT-10、UAT-CAT-11 |

## 5. 用例

所有用例的共同前置条件：环境按第 3.1 节装好，`prep` 阶段已以本提交执行成功。下文的前置条件只列各自额外的条件。

### 5.1 `catalog` 阶段

#### UAT-CAT-01 Flink 经 colvenna-catalog 读写

**对应需求**：#18、#20；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“引擎流程”。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：Flink 基础镜像（安装步骤 `flink-image`）；catalog 栈输出 `FlinkEngineRoleArn`；执行机有 mvn。

**输入**：合成数据：库 `flow_cf<tag>`，`orders` 先批量写入 100 行，再 upsert、更新与删除几行；`events` 3 个分区，带 checkpoint 的流式追加 3000 行，写入并行度 1。

**步骤**：

1. `run-uat.sh` 以标签 `catalog-flink` 运行 `tests/acceptance/catalog/run-flink-flow.sh cf<tag> <root>/catalog-flink`。数据库为 `flow_cf<tag>`。
2. 本机打包 `tests/acceptance/catalog/flink` 的流程 jar，放进 ConfigMap。
3. 准备引擎命名空间 `colvenna-test-engines`：Pod Security restricted、标签 `colvenna-lake.io/catalog-client=true`、带 IRSA 角色的服务账号、catalog 的 CA。给身份 `test-flink` 签发 2 天的 token，授予该库 MANAGE。
4. 以 Job 运行 `FlinkCatalogFlow`（单 JVM 的 Flink mini cluster）：经 colvenna-catalog 建库、建表、读写与维护，数据文件用按表数据凭证访问。
5. 日志须有 `FLOW PASS flow_cf<tag>`。

**期望结果**：退出 0，18 行 `STEP` 都为 `OK`，最后输出 `Flink flow PASS (flow_cf<tag>)`。逐项结果在 `<root>/catalog-flink/steps.txt`；`summary.tsv` 每个 `STEP` 一行。

| STEP | 期望 |
| --- | --- |
| `create-database` | 建库 |
| `create-primary-key-table` | 建主键表 `orders` |
| `create-append-table` | 建 append 表 `events`，分区由 catalog 管理 |
| `batch-insert` | 写入 100 行 |
| `upsert-update-delete` | upsert 2 行、更新 1 行、删除 5 行之后结果正确 |
| `alter-add-column` | 经 `alterTable` 加列 |
| `streaming-append-commits` | 带 checkpoint 的流式写入（写入并行度 1）逐次提交：3000 行都可读，`APPEND` 提交至少 2 次 |
| `catalog-partitions` | catalog 登记了 3 个分区 |
| `consumer-id-stream-read` | 带 consumer id 的分层流式读 |
| `tag-create-and-read` | tag `flow_tag` 固定写入前的快照，可读 |
| `time-travel-by-snapshot` | 快照 1 是第一次写入的内容 |
| `branch-write-and-read` | 从 tag 建 branch `flow_branch`，branch 上的写入与主干隔离 |
| `compact-procedure` | compact 产生独立的压缩提交 |
| `expire-snapshots` | 过期快照，保留 2 个 |
| `rollback-on-scratch-table` | 在临时表上回滚到快照 1 |
| `view` | catalog 视图 |
| `catalog-function` | catalog 函数的建、调用、列出与删除 |
| `rename-and-drop` | 改名只改元数据，删表清除数据 |

**副作用与注意**：

- `streaming-append-commits` 的写入并行度固定为 1（#297）：Paimon 2.0.0 在流模式下写有界输入、写入并行度大于 1 时，可能静默丢掉后结束的写入子任务的收尾数据（[用户手册](../manual/user.md#17-限制与未验证项)第 17 节）。这一步要验证的是带 checkpoint 的写入经 catalog 多次提交且不丢行。代价是它不再覆盖多个写入子任务的数据进同一个提交，UAT-CAT-04 也一样。
- 留下库 `flow_cf<tag>`：UAT-CAT-02、03、05、06 在其上继续，UAT-TBL-01 与 UAT-TSK-01 以其中的 `orders` 作 `OUTSIDE_TABLE`（一张不归受管命名空间所有的表）。
- 留下 Job、ConfigMap `flink-flow-cf<tag>` 与 `test-flink` 的 MANAGE 授权（7 天）。
- 同一工作区里不能同时运行另一个打包同一 pom 的测试，mvn 的 `target/` 会被互相删掉。
- 以往约 4–6 分钟。

**清理方式**：测试不删自己的对象。库 `flow_cf<tag>` 留给本阶段后面的用例与 UAT-TBL-01、UAT-TSK-01，UAT 之后可用 `tests/acceptance/catalog/drop-database.sh flow_cf<tag>` 删除；Job、ConfigMap `flink-flow-cf<tag>` 与 `test-flink` 的授权（7 天后到期）随环境卸载删除。

#### UAT-CAT-02 Spark 经 colvenna-catalog 读写

**对应需求**：#18；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“引擎流程”。

**适用配置**：只装平台或 `--fixtures`（Spark 镜像由 `prep` 阶段构建）；任一标准区域。

**前置条件**：UAT-CAT-01 已在 `flow_cf<tag>` 上运行过一次；`prep` 构建的 Spark 镜像；catalog 栈输出 `SparkEngineRoleArn`。

**输入**：UAT-CAT-01 写的 `flow_cf<tag>`；Spark 自己建的 `spark_sales`（4 行）与它写进 `orders` 的 1 行。

**步骤**：

1. 标签 `catalog-spark`：`tests/acceptance/catalog/run-spark-flow.sh cs<tag> flow_cf<tag> <root>/catalog-spark`。
2. 本机打包 `tests/acceptance/catalog/spark` 的流程 jar；给身份 `test-spark` 签发 2 天的 token，授予该库 MANAGE。
3. 以 Job 运行 `spark-submit`（`local[4]`），执行 `SparkCatalogFlow`：读 Flink 写的表，再建表、写入并写回 Flink 的表。
4. 日志须有 `FLOW PASS flow_cf<tag>`。

**期望结果**：退出 0，9 行 `STEP` 都为 `OK`，最后输出 `Spark flow PASS (flow_cf<tag>)`。逐项结果在 `<root>/catalog-spark/steps.txt`；`summary.tsv` 每个 `STEP` 一行。

| STEP | 期望 |
| --- | --- |
| `read-flink-tables` | 跨引擎读 Flink 流程写的表 |
| `time-travel-version-and-tag` | 按快照 id（100 行）与 tag（96 行）回溯 |
| `read-branch` | 读 Flink 写的 branch（97 行） |
| `system-tables` | `$snapshots`、`$schemas`、`$tags`、`$files` 可读 |
| `create-and-insert` | Spark 建主键表 `spark_sales` 并写入 |
| `update-delete-merge` | 行级更新、删除与 MERGE |
| `alter-table` | 结构变更 |
| `procedures` | `create_tag` 与 compact |
| `write-to-flink-table` | 向 Flink 的 `orders` 写入：两个引擎共用一个提交权威 |

**副作用与注意**：每个库只能运行一次，UAT-CAT-03 的检查值按“Flink 一次、Spark 一次”写定（`orders` 98 行、`spark_sales` 4 行）。留下 Job 与 ConfigMap `spark-flow-cs<tag>`。以往约 2 分钟。

**清理方式**：测试不删自己的对象。它建的 `spark_sales` 在库 `flow_cf<tag>` 里，随这个库删除；Job 与 ConfigMap `spark-flow-cs<tag>` 随环境卸载删除。

#### UAT-CAT-03 Doris 经 colvenna-catalog 只读

**对应需求**：#18、#20；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“引擎流程”。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：UAT-CAT-01、UAT-CAT-02 各在 `flow_cf<tag>` 上运行过一次；Doris Operator 与本环境的查询引擎镜像（基于 Apache Doris 4.1.4，仓库 `colvenna-lake-<env>/query-engine`；安装步骤 `engines`、`doris-image`）；catalog 栈输出 `DorisEngineRoleArn`；查询节点有约 4 CPU、22 GiB 空闲。

**输入**：UAT-CAT-01、UAT-CAT-02 写的 `flow_cf<tag>`；`catalog/doris-checks.sql` 的查询与期望值。

**步骤**：

1. 标签 `catalog-doris`：`tests/acceptance/catalog/run-doris-flow.sh cd<tag> flow_cf<tag> <root>/catalog-doris`。
2. 在 `colvenna-doris` 里另建一个 DorisCluster `doris-cd<tag>`（1 FE、1 BE，查询节点），与平台 Doris 并存。
3. 给身份 `doris` 签发 2 天的 token，授予该库 READ；在 Doris 里 `CREATE CATALOG`，经 REST、bearer 认证与数据凭证访问。
4. 从 mysql 客户端 Pod 运行 `catalog/doris-checks.sql` 与 `EXPLAIN`，逐项比对期望值。
5. 删除这个 DorisCluster、它的卷与 Secret。

**期望结果**：退出 0，16 行 `CHECK` 都为 `OK`，最后输出 `Doris flow PASS (flow_cf<tag>)`。逐项结果在 `<root>/catalog-doris/checks.txt`，`summary.tsv` 每个 `CHECK` 一行。另外记录、不作判定：`<root>/catalog-doris/doris-touches.txt` 一行，等 BE 时 `touch_doris_without_be` 给 DorisCluster 打注解的次数，以及 BE 的 StatefulSet 在等待开始多少秒后出现。Doris Operator 25.8.0 有时在 FE 就绪后不再协调、BE 建不出来（#172，上游 [apache/doris-operator#495](https://github.com/apache/doris-operator/issues/495) 未修），产品的 `install-doris.sh` 与本测试用同一个办法绕开；这一行说明这次是否碰上：次数为 0 或 StatefulSet 几秒内出现是没有碰上，等了一分钟以上、打了多次注解才出现是碰上了、被绕开。平台 Doris 的安装在 `install-*/doris.log` 里有同样的一行。

| CHECK | 期望 |
| --- | --- |
| `native-reader-splits` | `events` 的每个 split 都由原生读取器读取 |
| `orders-count` | 98 行 |
| `orders-evolved-column` | 加列后的值可读：1 行 |
| `orders-spark-write` | Spark 写入的行可读：1 行 |
| `orders-snapshot-1`、`orders-tag`、`orders-branch` | 快照 1、tag、branch 分别为 100、96、97 行 |
| `orders-snapshots`、`orders-tags` | 快照数大于 0；tag 1 个 |
| `events-count`、`events-partitions` | 3000 行；3 个分区 |
| `spark-sales-count`、`spark-sales-amount`、`spark-sales-evolved` | Spark 建的表：4 行、金额合计 401、加列后的值 1 行 |
| `jni-orders-count`、`jni-events-count` | 经 JNI 读取器读同两张表：98 行、3000 行 |

**副作用与注意**：查询节点上同时有两套 Doris，期间不要运行其他使用查询节点的测试；runner 在 `catalog` 阶段里逐个运行，满足这一点。留下 `colvenna-doris` 里的 Secret `doris-token` 与身份 `doris` 的 READ 授权。以往约 3–4 分钟。

**清理方式**：测试退出时（成败都一样）删除自己的 DorisCluster `doris-cd<tag>`、它的卷与 Secret；留下的 Secret `doris-token` 与 `doris` 的 READ 授权随环境卸载删除。

#### UAT-CAT-04 Flink session 集群经 colvenna-catalog 读写

**对应需求**：#18、#20（TaskManager 自己取数据凭证）；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：Flink Kubernetes Operator；Flink 基础镜像；`FlinkEngineRoleArn`；集群里有约 4 CPU 空闲。

**输入**：合成数据：库 `flow_cx<tag>`，内容同 UAT-CAT-01，并行度 2；`streaming-append-commits` 的写入并行度仍是 1（见 UAT-CAT-01 的副作用与注意）。

**步骤**：

1. 标签 `catalog-session`：`tests/acceptance/catalog/run-flink-deployment.sh cx<tag> <root>/catalog-session com.colvenna.lake.acceptance.flow.FlinkCatalogFlow flow_cx<tag>`。
2. 本机打包流程 jar；建 Role 与 RoleBinding `test-flink-runtime`；给 `test-flink` 签发 token，授予 `flow_cx<tag>` MANAGE。
3. 建 FlinkDeployment `test-flink-cx<tag>`（session 集群，不重启），等它 READY。
4. 客户端 Job 以并行度 2 提交 `FlinkCatalogFlow`，TaskManager 用自己取得的数据凭证读写文件。
5. 收集日志，删除 FlinkDeployment、Job 与 ConfigMap。

**期望结果**：退出 0，最后输出 `Flink session run PASS (flow_cx<tag>)`。`STEP` 与 UAT-CAT-01 的 18 项相同，都为 `OK`；逐项结果在 `<root>/catalog-session/steps.txt`，`summary.tsv` 每个 `STEP` 一行。

**副作用与注意**：库 `flow_cx<tag>` 与 UAT-CAT-01 的库分开。2026-10-28 #295 在 `uat` 上验证时，这个用例第一次运行的 `streaming-append-commits` 以写入并行度 2 只提交了 3000 行里的 1506 行，原因是上游 Paimon 的缺陷（本仓库的 #297）；之后这一步的写入并行度固定为 1，见 UAT-CAT-01 的副作用与注意。留下 Role、RoleBinding `test-flink-runtime` 与 MANAGE 授权。以往约 4 分钟。

**清理方式**：测试退出时删除 FlinkDeployment、提交作业的客户端 Job 与 jar 的 ConfigMap；库 `flow_cx<tag>` 可用 `drop-database.sh` 删除；Role、RoleBinding `test-flink-runtime` 与授权随环境卸载删除。

#### UAT-CAT-05 按表数据凭证的范围

**对应需求**：#20；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“按表凭证”。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：UAT-CAT-01–04 已运行：`flow_cf<tag>` 有 `orders` 与 `events`，`colvenna-test-engines` 里有服务账号 `test-flink`、`test-spark`，`colvenna-doris` 里有 `doris`；证据目录下有这些流程的清单（`*.yaml`、`*.sql`）；执行机的 AWS 身份能在本机调用 `aws s3api`。

**输入**：UAT-CAT-01–04 的表与它们的流程清单；另建的库 `flow_cf<tag>_other` 与表 `secrets`；三个探测身份与它们的授权。

**步骤**：

1. 标签 `catalog-tokens`：`tests/acceptance/catalog/verify-data-tokens.sh flow_cf<tag> <root> <root>/catalog-tokens`。
2. 建库 `flow_cf<tag>_other` 与表 `secrets`；建身份 `token-probe`（`orders` 的 WRITE）、`token-reader`（READ）与 `token-outsider`（无授权），分别取 `orders` 的数据凭证。
3. 只用取到的凭证在本机调用 S3：本表、同库的另一张表、不带斜杠的表前缀、库目录、另一个库、整个桶、仓库之外。
4. 以 `test-flink`、`test-spark`、`doris` 与一个没有 IRSA 的服务账号 `no-irsa` 起探测 Pod，确认它们自己的身份读不到表数据。
5. 扫描证据目录下的流程清单、两个引擎命名空间里的 ConfigMap、Job、Pod、DorisCluster，以及流程源码，确认没有 S3 密钥。

**期望结果**：退出 0，最后输出 `PASS`。每项检查一行 `<检查> expected=… actual=…`，第一个不符就输出 `FAIL: <检查>` 并退出。逐项结果在 `<root>/catalog-tokens/data-token-checks.txt`，`summary.tsv` 每项检查一行。

| 检查 | 期望 |
| --- | --- |
| `writer identity gets credentials`、`read-only identity gets credentials` | 200，取得凭证 |
| `identity without a grant gets none` | （负向）没有授权的身份得到 403 |
| `own table: list`、`own table: read schema`、`own table: write`、`own table: delete` | allow |
| `own table: missing key is 404, not 403` | 本表下不存在的键得到 404 |
| `read-only identity: read` | allow |
| `read-only identity: write data`、`read-only identity: delete data` | （负向）deny |
| `read-only identity: write consumer progress`、`read-only identity: remove its consumer progress` | allow：只读身份可以记录自己的消费进度 |
| `sibling table: list`、`sibling table: read schema`、`sibling table: write` | （负向）同库另一张表 deny |
| `bare table prefix: list (would show orders_*)` | （负向）deny |
| `database directory: list` | （负向）deny |
| `other database: read schema`、`other database: list` | （负向）deny |
| `whole bucket: list` | （负向）deny |
| `outside the warehouse: write` | （负向）deny |
| `test-flink pod identity is its IRSA role`、`test-spark pod identity is its IRSA role`、`doris pod identity is its IRSA role` | Pod 的身份是本环境的角色 `colvenna-lake-<env>-<服务账号>` |
| `test-flink pod identity: read table data`、`test-flink pod identity: list table data`、`test-flink pod identity: warehouse table data`、`test-spark pod identity: read table data`、`test-spark pod identity: list table data`、`test-spark pod identity: warehouse table data`、`doris pod identity: read table data`、`doris pod identity: list table data`、`doris pod identity: warehouse table data`、`no-irsa pod identity: read table data`、`no-irsa pod identity: list table data`、`no-irsa pod identity: warehouse table data` | （负向）deny：引擎 Pod 自己的身份，以及没有 IRSA 时退回的节点身份，都读不到、列不出表数据 |
| `flow configurations without S3 keys` | （负向）none：清单、运行中的对象与源码里都没有 S3 密钥 |
| `probe target exists: <键>`、`flow manifests found under <root>` | 只在失败时出现：探测目标不存在，或证据目录下找不到流程清单 |

**副作用与注意**：留下身份 `token-probe`、`token-reader`、`token-outsider`（不停用，授权 7 天）、库 `flow_cf<tag>_other` 与服务账号 `no-irsa`。可与其他测试同时运行。以往约 2–3 分钟。

**清理方式**：测试不删自己的对象。库 `flow_cf<tag>_other` 可用 `drop-database.sh` 删除；身份 `token-probe`、`token-reader`、`token-outsider`（授权 7 天后到期）与服务账号 `no-irsa` 随环境卸载删除。

#### UAT-CAT-06 目录授权与审计

**对应需求**：#21；验收报告 [M2 目录授权与审计在 EKS 上的验收](../history/m2/authorization-on-eks.md)。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：`flow_cf<tag>.orders` 存在（UAT-CAT-01）；`colvenna-test-engines` 里有 catalog CA 的 ConfigMap。

**输入**：表 `flow_cf<tag>.orders`；6 个身份与它们的授权；每个请求自己的 `X-Request-Id`，commit 与 alter 的请求体是 `{}`。

**步骤**：

1. 标签 `catalog-authorization`：`tests/acceptance/catalog/verify-authorization.sh flow_cf<tag> <root>/catalog-authorization`。
2. 建 6 个身份 `authz-{owner,writer,reader,columns,denied,outsider}-<时间戳>`，各有 2 小时的 token。授权：owner 对库 MANAGE；writer 对 `orders` WRITE；reader 对 `orders` READ；columns 只对列 `id` READ；denied 对库 READ 且对 `orders` DENY；outsider 无授权。
3. 一个 curl Pod 依次以各身份发请求，每个请求带自己的 `X-Request-Id`。commit 与 alter 的请求体是 `{}`，得到 400 说明已过授权。
4. 按请求 id 在审计里逐个找到对应事件，核对动作、资源、结果、状态码与时间。
5. 退出时停用这 6 个身份。

**期望结果**：退出 0，最后输出 `Authorization checks PASS: N checks`；每项一行 `<检查> expected=… actual=… OK`。逐项结果在 `<root>/catalog-authorization/authorization-checks.txt`，`summary.tsv` 每项一行。

| 检查 | 期望 |
| --- | --- |
| `list-databases/owner`、`list-databases/reader` | 200，列出该库 |
| `list-databases/outsider` | （负向）200，但不列出该库 |
| `get-table/owner`、`get-table/writer`、`get-table/reader` | 200 |
| `get-table/denied`、`get-table/outsider` | （负向）403 |
| `list-tables/owner` | 200，含 `orders` |
| `list-tables/reader` | 200，只列出 `orders` |
| `list-tables/outsider` | （负向）403 |
| `data-token/writer`、`data-token/reader` | 200 |
| `data-token/denied`、`data-token/outsider` | （负向）403 |
| `column-auth/allowed` | 只查 `id`：200 |
| `column-auth/denied` | （负向）查 `id`、`amount`：403，消息指明 `amount` |
| `commit/owner`、`commit/writer` | 400：已过授权 |
| `commit/reader`、`commit/outsider` | （负向）403 |
| `alter-table/owner` | 400：已过授权 |
| `alter-table/writer` | （负向）403：WRITE 不含结构变更 |
| `create-table/writer` | （负向）403 |
| `create-table/owner`、`drop-table/owner` | 200（临时表 `authz_probe_<时间戳>`） |
| `admin-grants/admin` | 200 |
| `admin-grants/owner`、`admin-grants/reader` | （负向）403：服务身份不能用管理面 |
| `drop-table/writer` | （负向）对 `orders` 403：WRITE 不含删表 |
| `audit <调用>`：上面除 `list-databases/*`、`get-table/writer`、`get-table/reader`、`list-tables/owner`、`list-tables/reader`、`data-token/writer`、`admin-grants/admin` 之外的 21 个调用各一行 | 按请求 id 找到审计事件，动作、资源、结果（SUCCEEDED、DENIED 或 FAILED）、状态码与时间都对 |
| `audit failures-recorded-as-success` | 0：没有状态码 400 以上却记为 SUCCEEDED 的事件 |

**副作用与注意**：留下 6 个已停用的身份（授权 1 天）；建了又删 Secret `authz-tokens`。可与其他测试同时运行。以往约 0.5 分钟。

**清理方式**：测试退出时停用 6 个身份、删除 Secret `authz-tokens`；停用的身份留在 catalog 里（授权 1 天后到期），随环境卸载删除。

#### UAT-CAT-07 catalog 的令牌与网络访问检查

**对应需求**：#20；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“访问检查”；也是安装步骤 `verify` 的一部分（[安装部署手册](../manual/install.md)第 4 节）。

**适用配置**：只装平台或 `--fixtures`（安装步骤 `verify` 也运行它）；任一标准区域。

**前置条件**：colvenna-catalog 已安装；集群执行 NetworkPolicy。

**输入**：一个服务身份的三个 token（1 天的、1 分钟的、1 天但随即撤销的），一个伪造的 token，一个停用主体的 token；两个命名空间（打标签与不打）。

**步骤**：

1. 标签 `catalog-access`：`deploy/scripts/catalog/verify-catalog.sh <root>/catalog-access`。
2. 建命名空间 `colvenna-catalog-client-check`（带 `colvenna-lake.io/catalog-client=true`）与 `colvenna-catalog-outsider`（不带）。
3. 建服务身份 `verify-service-<时间戳>`，签发三个 token：1 天的、1 分钟的（等它过期）、1 天的（随即撤销）；再建 `verify-disabled-<时间戳>` 并停用。
4. 等 70 s，让 1 分钟的 token 过期并离开认证缓存；然后从客户端命名空间的 curl Pod、从 catalog Pod 内部、从未打标签的命名空间分别探测。
5. 删除两个命名空间，停用服务身份。

**期望结果**：退出 0，14 项都符合，最后输出 `PASS`。逐项结果在 `<root>/catalog-access/access-checks.txt`，`summary.tsv` 每项检查一行。

| 检查 | 期望 |
| --- | --- |
| `client namespace, admin token`、`client namespace, service token` | 200 |
| `client namespace, no token` | （负向）401 |
| `client namespace, forged token` | （负向）401 |
| `client namespace, expired token` | （负向）401 |
| `client namespace, revoked token` | （负向）401 |
| `client namespace, token of a disabled principal` | （负向）401 |
| `service identity on the admin API` | （负向）403 |
| `admin API without token` | （负向）401 |
| `plain HTTP to the TLS port gets no HTTP response` | （负向）000：TLS 端口不回应明文 HTTP |
| `health port from another pod` | （负向）000：健康端口只在 Pod 内的回环地址上 |
| `health port on 127.0.0.1 inside the pod` | 200 |
| `health port on the pod address inside the pod` | （负向）000 |
| `unlabelled namespace is blocked by NetworkPolicy` | （负向）000：未打标签的命名空间连不上 |

**副作用与注意**：命名空间名固定，不能两个同时运行。留下已停用的身份。以往约 2.5 分钟。

**清理方式**：测试退出时删除两个命名空间、停用服务身份。命名空间名固定：运行被强行中止时，先 `kubectl delete namespace colvenna-catalog-client-check colvenna-catalog-outsider` 再重跑。

#### UAT-CAT-08 catalog 与平台服务的健康

**对应需求**：#23；验收报告 [M2 控制平面的安装、检查与恢复](../history/m2/control-plane-operations.md)；也是安装步骤 `verify` 的一部分。

**适用配置**：只装平台或 `--fixtures`（安装步骤 `verify` 也运行它）；任一标准区域。

**前置条件**：在安装所用的提交上运行（`catalog-schema` 与本提交的迁移数比较）。

**输入**：运行中的环境；本提交的 schema 迁移数。

**步骤**：

1. 标签 `catalog-health`：`deploy/scripts/catalog/check-health.sh <root>/catalog-health`（不带 `--dev-kafka`）。
2. 检查 colvenna-catalog 的 Deployment：就绪副本数、副本所在节点、PodDisruptionBudget；在每个副本里请求回环地址上的 `/readyz`。
3. 查 RDS 实例状态；起一个 psql Pod 读 schema 版本。
4. 检查 Flink Kubernetes Operator 与 Doris Operator，以及 Doris Operator 的资源请求与上限。

**期望结果**：退出 0，每项一行 `<检查> OK <细节>`。逐项结果在 `<root>/catalog-health/health.txt`，`summary.tsv` 每项一行。

| 检查 | 期望 |
| --- | --- |
| `colvenna-catalog/colvenna-catalog` | 就绪副本数等于期望数 |
| `catalog-spread` | 副本分布在 min(副本数, 写入节点组的大小) 台节点上：默认规模是两台；写入节点组只有一台时，两个副本都在它上面也通过 |
| `catalog-disruption-budget` | 有 PodDisruptionBudget |
| `<pod>-readyz`，每个副本一行 | 就绪端点应答，即连得上 PostgreSQL。已经结束的 Pod（`Succeeded`、`Failed`）不是副本，不列：节点优雅关闭时 kubelet 停掉的 Pod 标为 `Terminated`（[Node Shutdowns](https://kubernetes.io/docs/concepts/cluster-administration/node-shutdown/)），2026-10-29 节点补丁重启后在 uat 上看到它们处于 `Failed`，与 ReplicaSet 补起的新 Pod 并存，直到被删掉（[Pod 的垃圾回收](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-garbage-collection)；#15） |
| `metadata-database` | RDS 实例处在照常接受连接的状态：`available`，或备份中的 `backing-up` 等（逐个列在 `check-health.sh` 的 `metadata_database` 里，#237）；细节依次记状态、版本、多可用区与备份天数 |
| `catalog-schema` | 库的 schema 版本等于本提交固定的 Colvenna Catalog 发布版的 `schema_version`（`deploy/images/colvenna-catalog.json`，即它的服务端带的迁移数） |
| `flink-operator/flink-kubernetes-operator`、`doris-operator/doris-operator` | 可用 |
| `doris-operator-resources` | Doris Operator 请求 100m CPU、128Mi 内存，上限 500m、512Mi，即 `deploy/scripts/lib/common.sh` 的 `DORIS_OPERATOR_RESOURCES`（#170 的产品修复；chart 的默认是 2 CPU、4 GiB） |

**副作用与注意**：只读，另有一个短暂的 psql Pod。可与任何测试同时运行。不到 1 分钟。

**清理方式**：只读；psql Pod 用完即删，不需要清理。

#### UAT-CAT-09 引擎没有调用 catalog 未提供的端点

**对应需求**：#18；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“引擎流程”。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：本阶段的引擎流程（UAT-CAT-01–04）已运行，其间 catalog 没有重启。

**输入**：本阶段开始以来每个 catalog Pod 的日志。

**步骤**：

1. 标签 `catalog-logs`：`tests/acceptance/catalog/check-catalog-logs.sh <本阶段开始时间> <root>/catalog-logs`。
2. 读当前每个 catalog Pod 自本阶段开始以来的日志，数 `no such endpoint` 与 `not implemented (<动作>)` 的行。

**期望结果**：退出 0，最后输出 `PASS`；`no such endpoint: 0`，501 只出现在 `LIST_PARTITIONS` 上（分区在文件里的表，客户端退回文件系统列分区，这是设计上的拒绝）。逐项结果在 `<root>/catalog-logs/endpoint-check.txt`，`summary.tsv` 只记退出状态。失败时输出 `FAIL: a client called an endpoint the catalog does not serve`。

**副作用与注意**：只读。它扫描的是环境里所有客户端的调用，也包括 Operator 管理的作业与 Doris。`kubectl logs` 只覆盖当前的 Pod，所以必须在本阶段末尾、任何 catalog 重启之前运行；否则会漏看重启前的日志。约 10 s。

**清理方式**：只读，不需要清理。

### 5.2 `faults` 阶段

#### UAT-CAT-10 故障下的原子提交

**对应需求**：#19；验收报告 [M2 REST 模式的原子快照提交与故障恢复](../history/m2/commit-recovery.md)。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：colvenna-catalog 至少 2 个副本；Secret `colvenna-catalog-db` 与 ConfigMap `colvenna-catalog-db-ca`；基础栈输出 `CatalogEndpoint`；没有其他测试在运行。

**输入**：合成数据：库 `flow_ck<tag>_commits` 的表 `events`，6 个写入方各 120 次提交、每次 500 行；四个注入的故障。

**步骤**：

1. 标签 `catalog-commit-faults`：`tests/acceptance/catalog/run-commit-faults.sh ck<tag> <root>/catalog-commit-faults`。库为 `flow_ck<tag>_commits`，表 `events`。
2. 启动 `CommitStress`（6 个写入方，各 120 次提交、每次 500 行、间隔 3 s）与 `MaintenanceLoop`，都经 run-flink-flow 运行；等到至少 5 个快照，并确认表此时一致。
3. 注入四个故障：强杀一个副本，30 s 后强杀另一个；30 s 内每 3 s 终止 catalog 的全部 PostgreSQL 会话；元数据库拒绝一切连接 40 s；同时强杀两个副本。每个故障发生时写入方都必须还在提交。
4. 核对写入方的自检、故障之后的维护轮次、表的一致性报告、审计里被拒的过期基线提交，以及已发布的提交意图编号。

**期望结果**：退出 0，`faults.txt` 最后一行为 `PASS`。逐项结果在 `<root>/catalog-commit-faults/faults.txt`，`summary.tsv` 只记退出状态。下列任何一条不满足即失败。

| 检查（`faults.txt` 中的行） | 期望 |
| --- | --- |
| `the writers did not start`、`the writers finished before fault N` | 不出现：每个故障都打在正在提交的写入方上 |
| `the table was not consistent before the faults` | 不出现：故障前的基线一致 |
| `fault 2: done, N sessions terminated` | N 大于 0：故障 2 确实终止了会话 |
| `fault 3: … fewest ready replicas while closed: N` | N 为 0：故障 3 期间 catalog 确实失去了数据库 |
| `fault 3: serving again Ns after the database reopened` | 记录恢复服务的秒数，不设门槛 |
| `writers finished (exit 0)` | 写入方自检通过：每个确认的提交恰好一次、每个结果已知、只有完整快照、快照 id 连续 |
| `maintenance failed` | 不出现：`MaintenanceLoop` 本身成功结束 |
| `commits that failed at first and were then resolved: N` | N 大于 0：有故障打在进行中的提交上 |
| `maintenance rounds after the faults: n, all OK: True` | 故障结束 60 s 后的每一轮维护都成功 |
| `consistency report: consistent=true` | 表的一致性报告无问题 |
| `commits the catalog refused on a stale base, then retried by their writers: N` | N 大于 0：catalog 拒绝了基于过期快照的提交，写入方重试 |
| `published snapshot ids 1..N each exactly once: True` | 已发布的提交意图把快照编号为 1..N，无缺无重 |

**副作用与注意**：整个环境的 catalog 停服两次，元数据库关闭 40 s，Operator 管理的作业会提交失败或重启，所以必须独占环境。退出时的清理会重新打开数据库。以往约 28 分钟。

**清理方式**：测试退出时（失败也一样）重新打开元数据库；库 `flow_ck<tag>_commits` 留下，可用 `drop-database.sh` 删除。

#### UAT-CAT-11 实例故障与元数据恢复

**对应需求**：#23；验收报告 [M2 控制平面的安装、检查与恢复](../history/m2/control-plane-operations.md)。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：catalog 2 个副本在不同的写入节点上，有 PodDisruptionBudget；数据库 Secret 与 CA；没有其他测试在写 catalog，也没有 LakeSource、LakeTable 作业在写。

**输入**：合成数据：库 `flow_cr<tag>_recovery` 的 `t1`、`t2`（`CommitStress` 写入）与备份之后建的 `t3`；元数据库的 `pg_dump` 备份。

**步骤**：

1. 标签 `catalog-recovery`：`tests/acceptance/catalog/run-recovery.sh cr<tag> <root>/catalog-recovery`。库为 `flow_cr<tag>_recovery`。
2. A 段：`CommitStress` 写 `t1` 期间，排空不在服务的副本所在节点（只驱逐 catalog Pod），被驱逐的副本只能等待或去别的节点，不能与另一副本同节点；恢复节点调度后，强杀一直在服务的副本。写入方须通过自检，`t1` 一致。
3. B 段：`CommitStress` 写 `t2` 期间 `pg_dump` 元数据库，备份后再建 `t3`，记录历史与表清单。
4. 把 catalog 缩到 0，`pg_restore --clean --single-transaction` 恢复，再扩回来。
5. 恢复后：表清单只比之前少 `t3`；`t1` 一致、`t2` 不一致（文件领先于备份）；reconcile `t2`、重新登记 `t3` 之后，清单与历史都与恢复前相同，`t2` 能再写入。

**期望结果**：退出 0，`timeline.txt` 有 `A: PASS, writers' checks passed and t1 is consistent`，最后一行为 `PASS`。逐项结果在 `<root>/catalog-recovery/timeline.txt`，`summary.tsv` 只记退出状态。下列 `FAIL:` 行都不出现：

| `FAIL:` 行 | 含义 |
| --- | --- |
| `the catalog replicas share a node` | 开始时副本同节点 |
| `A: the writers published no five snapshots` | A 段写入没有进展 |
| `A: no replica runs on another node than …` | 找不到另一节点上的副本 |
| `A: two replicas share a node during the drain`、`A: the replicas share a node after the drain` | 排空期间或之后副本同节点 |
| `A: the evicted replica neither waits for a node nor runs` | 被驱逐的副本既不等待也不运行 |
| `A: the replica … that kept serving is gone` | 一直服务的副本在强杀前已不在 |
| `A: the writers' checks failed`、`A: t1 inconsistent` | 实例故障后提交不完整或表不一致 |
| `B: the writers published no 20 snapshots`、`B: the writers' checks failed`、`B: the writer of t3 failed` | B 段写入失败 |
| `B: the catalog should list what it did before but t3` | 恢复后的清单不是“恢复前减去 `t3`” |
| `B: expected t2, and only t2, behind its files after the restore` | 恢复后不一致的不只是 `t2` |
| `B: t2 still inconsistent after reconcile` | reconcile 没有修好 `t2` |
| `B: t3 inconsistent after it was registered again` | 重新登记的 `t3` 不一致 |
| `B: the catalog lists other tables than before` | 重新登记后清单与恢复前不同 |
| `B: the history differs after the restore` | 某个快照读出的内容与恢复前不同 |

**副作用与注意**：

- 排空期间一个写入节点不可调度；catalog 缩到 0 一段时间。
- 整个 `colvenna_catalog` 库恢复到备份时刻：这段时间里的授权、token、身份、审计与提交都回到备份时的状态，期间签发的 token 失效。所以必须独占环境，并排在声明式接入之前。
- 别的测试在这段时间里建表或删表，会让清单比对失败。
- 以往约 30 分钟。

**清理方式**：测试结束时节点已恢复调度、catalog 已扩回原来的副本数；中途失败时先核对这两点（`kubectl get nodes` 没有 `SchedulingDisabled`，colvenna-catalog 的 Deployment 副本数与 `install-catalog.sh` 装的相同）。库 `flow_cr<tag>_recovery` 可用 `drop-database.sh` 删除。

### 5.3 `renewal` 阶段

#### UAT-CAT-12 数据凭证续期

**对应需求**：#20；验收报告 [M2 colvenna-catalog 在 EKS 上的部署与引擎验收](../history/m2/catalog-on-eks.md)的“续期”。

**适用配置**：只装平台或 `--fixtures`（Spark 镜像由 `prep` 阶段构建）；任一标准区域。

**前置条件**（由 runner 依次准备）：

- `renewal-flink`：`run-flink-flow.sh rf<tag> <root>/renewal-flink`，建库 `flow_rf<tag>`；
- `renewal-spark`：`run-spark-flow.sh rs<tag> flow_rf<tag> <root>/renewal-spark`。这两个运行的判定同 UAT-CAT-01、UAT-CAT-02；
- `renewal-catalog`：`DATA_TOKEN_DURATION=PT1H30M deploy/scripts/catalog/install-catalog.sh <root>/renewal-catalog`，catalog 改发 90 分钟的数据凭证（catalog 允许的最短有效期；默认 `PT3H`）；
- Spark 镜像、查询引擎镜像（基于 Apache Doris）与 Flink Kubernetes Operator。

**输入**：UAT-CAT-01、UAT-CAT-02 在 `flow_rf<tag>` 写的表；90 分钟的数据凭证；Flink 100 分钟的流式写入，Spark 与 Doris 等 95 分钟后的第二轮读写。

**步骤**：

1. 标签 `renewal`，单独运行：`tests/acceptance/catalog/run-renewal.sh rn<tag> flow_rf<tag> <root>/renewal`。脚本先确认 catalog 的有效期是 `PT1H30M`。
2. 三个引擎同时运行，都越过第一张凭证的有效期：
   - Flink：`FlinkRenewalFlow` 在 session 集群上带 checkpoint 流式写入 100 分钟，不允许重启，TaskManager 用自己的凭证写文件；
   - Spark：`SparkRenewalFlow` 在一个会话里读写，等 95 分钟，再读写一次，也经过等待前就持有的表对象；
   - Doris：对 `flow_rf<tag>` 跑 UAT-CAT-03 的只读检查，95 分钟后在同一个 catalog 上再跑一次。
3. 等三个引擎结束，从审计读出这段时间里新签发（非缓存）的 `ISSUE_DATA_TOKEN` 事件。
4. 续期门槛：持续使用凭证的 `test-flink` 至少有一张表在某次签发 15 分钟之后、过期之前取得了新凭证。Spark 与 Doris 等待期间不用凭证，以第二轮读写通过为准。
5. `renewal` 结束后，runner 接着以 `renewal-catalog-back` 运行 `install-catalog.sh <root>/renewal-catalog-back`，恢复默认有效期。

**期望结果**：退出 0，`run.txt` 最后一行为 `Renewal PASS`。逐项结果在 `<root>/renewal/run.txt`，引擎各自的结果在 `flink/`、`spark/`、`doris/` 子目录；`summary.tsv` 有 `flink`、`spark`、`doris` 与 `Renewal` 各一行。

| 检查（`run.txt` 中的行） | 期望 |
| --- | --- |
| `flink PASS` | Flink 写满 100 分钟，没有因凭证过期失败 |
| `spark PASS` | Spark 等待 95 分钟之后的读写成功 |
| `doris PASS` | Doris 95 分钟之后的复查与第一次结果相同 |
| `test-flink: renewed before expiry for <表>` | 有表在过期前续期；失败时为 `test-flink: no table renewed before its credentials expired` |

**副作用与注意**：

- 运行期间整个环境的数据凭证都是 90 分钟。
- 两次 catalog 重装各重启一次 catalog Pod；runner 把它们放在 `catalog` 与 `faults` 阶段之后，不影响 UAT-CAT-09 与 UAT-QUA-01 的 port-forward。
- 三个引擎同时运行，`run-renewal.sh` 把它们的 CPU 请求调小：Flink session 的 JobManager 与两个 TaskManager 各 0.5 CPU，提交作业的客户端 0.5 CPU，Spark 0.5 CPU，自带的一套 Doris 的 FE、BE 各 1 CPU（上限不变，节点空闲时可以多用）；Flink 放在写入节点，Spark 与 Doris 放在查询节点。按各流程默认的请求，三者合计约 10 CPU，加上平台组件约 5.1 CPU，几乎占满默认规模 4 个节点可分配的约 15.7 CPU，而且各自要落在指定的节点上：2026-10-21 单独运行时（那时 Doris Operator 还请求 2 CPU，平台组件约 7.2 CPU），第二个 TaskManager 也因 CPU 不足调度不上，5 分钟后 Flink 的 slot 请求超时，作业不允许重启，于是失败；调小请求、不固定节点时，session 集群占了 Doris BE 要的查询节点；Spark 也放在写入节点时，写入节点的内存不够。它不能与其他测试同时运行。
- 留下库 `renew_rn<tag>`。约 1 小时 45 分钟。

**清理方式**：runner 随后以 `renewal-catalog-back` 恢复默认的凭证有效期；这一步失败时手动运行 `deploy/scripts/catalog/install-catalog.sh <证据目录>`，否则之后的用例都拿 90 分钟的凭证。库 `flow_rf<tag>` 与 `renew_rn<tag>` 可用 `drop-database.sh` 删除。

### 5.4 `ingest` 阶段

#### UAT-SRC-01 MySQL 与 PostgreSQL 的声明式 CDC

**对应需求**：#24；验收报告 [M3 声明式 CDC 数据源接入](../history/m3/declarative-cdc.md)。MySQL 作业用 Flink CDC 3.7.0-1.20，见 [M3 Flink CDC 3.7 兼容性评估](../history/m3/flink-cdc-3.7.md)（#113）。

**适用配置**：`--fixtures`（测试源 MySQL 与 PostgreSQL）；任一标准区域。

**前置条件**：测试源与源登录（安装步骤 `sources`、`source-logins`：Secret `mysql-cdc`、`mysql-inspect`、`postgres-cdc`）；测试 PostgreSQL 开启逻辑复制；测试源 PostgreSQL 上没有上一次中断的运行留下的复制槽。

**输入**：合成数据：MySQL 库 `cdc_<run>`（`orders` 5000 行、`customers` 200 行、覆盖每种接受类型的 `typed`，以及应被拒绝的 `no_key`、`no_guard`、`blobs`、`tokens`）与 PostgreSQL 库 `pg_<run>`；约每秒 2 个事务的写入方；运行中新增的表与结构变化。

**步骤**：

1. 标签 `cdc`（单独运行）：`tests/acceptance/cdc/run-cdc.sh cdc<tag> <root>/cdc`。
2. MySQL：建库 `cdc_<run>`（`orders` 5000 行、`customers` 200 行、覆盖每种接受类型的 `typed`，以及应被拒绝的 `no_key`、`no_guard`、`blobs`、`tokens`），启动约每秒 2 个事务的写入方，声明 LakeSource `cdc-<run>`（目标 `ods_cdc_<run>`，删除策略 Retain）。
3. MySQL 上依次：准入与拒绝；写入中新增表 `invoices`；支持的结构变化；`ledger` 上不支持的变化（只让这张表失败）；取消选择 `ledger` 再选回（被拒）；TaskManager 内存改为 4096m；声明并行度 2（被保持为 1）；删除 TaskManager 与 JobManager Pod；暂停与恢复。停写后逐表比对行与完整 changelog。在 Operator 背后删除 FlinkDeployment，再删除 LakeSource。
4. PostgreSQL：建库 `pg_<run>` 与 publication，启动写入方，声明 LakeSource `pg-<run>`（删除策略 Delete，每表一个 Flink 作业）；准入与拒绝；新增表 `refunds`；加列；`ledger` 上不支持的变化（它的作业与复制槽被删）；内存变更；JobManager 丢失；暂停与恢复。
5. 停写后逐表比对；删除 LakeSource，复制槽与目标表都不留。

**期望结果**：退出 0，`<root>/cdc/verdicts.tsv` 的 50 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `mysql-admission` | 合规表 Ready；`no_key` 为 `NoPrimaryKey`、`no_guard` 为 `VersionGuardMissing`、`blobs` 与 `tokens` 为 `UnsupportedBinaryColumn`；`SourceVerified`（按 RDS CA 校验服务端）与 `TargetIsolated` 为 True；作业运行 |
| `mysql-new-table-enrolled`、`mysql-new-table-restored` | 写入中新建的 `invoices` Ready；作业从 `chk-` checkpoint 恢复 |
| `mysql-ledger-enrolled` | `ledger` Ready |
| `mysql-unsupported-change-isolated`、`mysql-unsupported-change-alerted` | `ledger` 为 Failed/`JobFailed`，`orders`、`invoices` 仍 Ready；有 Warning `TableFailed` |
| `mysql-deselected` | 取消选择后 `ledger` 离开表清单 |
| `mysql-reselected-refused` | 再选回被拒：Rejected/`TargetNotEmpty`，不向已有数据的目标重新快照 |
| `mysql-declaration-change`、`mysql-declaration-applied`、`mysql-upgrade-restored` | 内存变更 `SpecApplied` 为 True；FlinkDeployment 的 TaskManager 内存为 4096m；升级从 checkpoint 恢复 |
| `mysql-parallelism-pinned`、`mysql-parallelism-kept`、`mysql-parallelism-restored` | 声明并行度 2：`SpecApplied` 为 False，作业仍运行；部署保持并行度 1，有 Warning `ParallelismPinned`；改回 1 后 `SpecApplied` 为 True |
| `mysql-taskmanager-loss`、`mysql-jobmanager-loss`、`mysql-failover-restored` | TaskManager、JobManager Pod 被删后作业恢复运行，从 checkpoint 恢复 |
| `mysql-suspended`、`mysql-resumed`、`mysql-resume-restored` | 暂停；恢复运行；从 checkpoint 恢复 |
| `rows-ods_cdc_<run>.orders`、`rows-ods_cdc_<run>.customers`、`rows-ods_cdc_<run>.invoices`、`rows-ods_cdc_<run>.typed` | 最新快照与源表逐行相同 |
| `changelog-ods_cdc_<run>.orders`、`changelog-ods_cdc_<run>.customers`、`changelog-ods_cdc_<run>.invoices`、`changelog-ods_cdc_<run>.typed` | 完整 changelog 回放正确 |
| `mysql-job-lost` | 在 Operator 背后删除部署后，表为 Failed/`JobStateLost`，没有作业 |
| `mysql-job-not-recreated` | 60 s 后仍没有新建 FlinkDeployment（不重新快照），并有 Warning `JobLost` |
| `mysql-deleted` | （负向）删除后没有作业，旧 token 得到 401；目标表 `ods_cdc_<run>.orders` 保留（Retain） |
| `postgres-admission` | `accounts`、`payments`、`typed`、`ledger` Ready；`partial_identity` 为 `ReplicaIdentityNotFull`，`unpublished` 为 `NotInPublication` |
| `postgres-new-table-enrolled` | `refunds` Ready |
| `postgres-unsupported-change-isolated` | `ledger` Failed，它的作业已删，没有待处理的复制槽，`accounts` 仍 Ready |
| `postgres-failed-table-slot-dropped` | `ledger` 的复制槽已删，并有 Warning `TableFailed` |
| `postgres-declaration-change`、`postgres-upgrade-restored` | 内存变更已应用；从 checkpoint 恢复 |
| `postgres-jobmanager-loss`、`postgres-failover-restored` | 恢复运行；从 checkpoint 恢复 |
| `postgres-suspended`、`postgres-resumed` | 暂停；恢复运行 |
| `rows-ods_pg_<run>.accounts`、`rows-ods_pg_<run>.payments`、`rows-ods_pg_<run>.refunds`、`rows-ods_pg_<run>.typed` | 与源表逐行相同（`payments` 的键是 `id,region`） |
| `changelog-ods_pg_<run>.accounts`、`changelog-ods_pg_<run>.payments`、`changelog-ods_pg_<run>.refunds`、`changelog-ods_pg_<run>.typed` | 完整 changelog 回放正确 |
| `postgres-deleted` | 删除策略 Delete：没有作业，本源的复制槽都已删，目标表 404 |

**副作用与注意**：

- 自己删除自己的 TaskManager、JobManager Pod 与 FlinkDeployment；写测试 MySQL 与 PostgreSQL；在 PostgreSQL 上建复制槽。复制槽的检查只看本源的槽（`colvenna_<uid 前 8 位>_`）。
- 留下 MySQL 库 `cdc_<run>`、PostgreSQL 库 `pg_<run>` 与它的 publication、保留的 `ods_cdc_<run>`，以及 `lake.sh` 给核对身份的读授权。
- PostgreSQL 段同时有约 5 个 FlinkDeployment。不能与另一个 `run-cdc.sh` 同时运行。
- 以往约 62 分钟。

**清理方式**：测试删除自己的两个数据源与写入 Pod；PostgreSQL 段的删除策略是 Delete，目标表与本源的复制槽随之删除。留下的 MySQL 库 `cdc_<run>`、PostgreSQL 库 `pg_<run>` 与 publication、按 Retain 保留的 `ods_cdc_<run>` 与读授权不妨碍重跑（运行 id 不同），随环境卸载删除。中途失败时先删除 `pg-<run>` 数据源，再确认测试 PostgreSQL 上没有本源（`colvenna_<uid 前 8 位>_`）的复制槽：留下的槽会占住 WAL。

#### UAT-TBL-01 声明式分层表 DWD 与 DWS

**对应需求**：#26；验收报告 [M3 声明式分层表与常驻流作业](../history/m3/layered-tables.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；UAT-CAT-01 留下的 `flow_cf<tag>.orders`，runner 以 `OUTSIDE_TABLE` 传入。

**输入**：合成数据：MySQL 库 `tbl_<run>`（`customers`、`segments` 各 200 行，`orders` 5000 行），写入方；DWD、DWS 与 9 个无效的 LakeTable 声明。

**步骤**：

1. 标签 `tables`（与 `tasks` 同时）：`OUTSIDE_TABLE=flow_cf<tag>.orders tests/acceptance/tables/run-tables.sh tbl<tag> <root>/tables`。
2. 建 MySQL 库 `tbl_<run>`（`customers`、`segments` 200 行、`orders` 5000 行），启动写入方，声明 LakeSource `lts-<run>`（`orders` 与 `segments` 进 `ods_tbl_<run>`，删除策略 Delete）。
3. 先声明 DWS `dws-<run>`（聚合），再声明 DWD `dwd-<run>`（lookup join）：DWS 等待上游；之后改维表。
4. 声明 9 个无效的 LakeTable，各自因其原因被拒，且什么都不建。
5. 升级：输出 schema 变更被拒后撤回；兼容升级（DWD checkpoint 间隔 20 s、DWS TaskManager 内存）从 savepoint 恢复；不兼容升级（聚合前加过滤）自动回滚，撤回后恢复：一次在旧作业完成自己的第一个 checkpoint 之后（DWS 先改为每 5 分钟一次 checkpoint，下一个 checkpoint 远在升级读取旧作业的 checkpoint 之后），一次在它之前（#279：DWS 改为每天一次 checkpoint，从 savepoint 恢复运行后立即施加），之后 DWS 改回每 30 s 一次；暂停 DWD 时 DWS 停在原快照；恢复；丢失 DWS 的 TaskManager。
6. 停写后比对 DWD、DWS 的行与 changelog；暂停 DWD 时删掉它的 consumer，恢复时它拒绝启动（`RebuildRequired`）且行不变；删除 DWD（DWS 仍在读，目标保留）、DWS（目标删除）与数据源。

**期望结果**：退出 0，`<root>/tables/verdicts.tsv` 的 45 项都为 PASS（`dws-restored-from-its-upgrade-savepoint` 仅在 `dws-upgrade-restored` 通过后判定）。

| 检查 | 期望 |
| --- | --- |
| `source-ready` | `orders`、`segments` Ready，作业运行 |
| `dws-waits-for-upstream` | DWS 为 Pending，`DependenciesReady` 为 `UpstreamMissing` |
| `dwd-running`、`dws-running` | Running；校验得到的流式上游与 lookup 上游与声明一致 |
| `refused-invalid-sql` | `QueryValid` 为 `InvalidQuery` |
| `refused-undeclared-upstream` | 读了未声明的表：`UpstreamNotDeclared` |
| `refused-unread-upstream` | 声明了却不读的上游：`UpstreamMismatch` |
| `refused-unordered-changelog` | 更新可能乱序到达的 regular join：`UnorderedChangelog` |
| `refused-outside-upstream` | （负向）上游是命名空间之外的 `OUTSIDE_TABLE`：`DependenciesReady` 为 `UpstreamNotAllowed` |
| `refused-cycle-a`、`refused-cycle-b` | 互相读取的两张表：`DependencyCycle` |
| `refused-self-read` | 读自己：`SpecValid` 为 `InvalidSpec` |
| `refused-target-conflict` | 目标归另一张表所有：`TargetConflict` |
| `refused-created-nothing` | 被拒的声明都没有目标表（404），也没有作业 |
| `schema-change-refused`、`schema-change-reverted` | 输出 schema 变更：Degraded、`TargetReady` 为 `SchemaChanged`，作业继续运行；撤回后回到 Running |
| `compatible-upgrade-restored`、`compatible-upgrade-applied` | DWD 从 savepoint 恢复；checkpoint 间隔为 20s |
| `dws-upgrade-restored`、`dws-restored-from-its-upgrade-savepoint` | DWS 从 savepoint 恢复；用的正是升级时取的 savepoint |
| `dws-checkpointed` | DWS 改为每 5 分钟一次 checkpoint，从 savepoint 恢复运行，并已完成自己的 checkpoint |
| `incompatible-upgrade-upgrading` | 不兼容升级进行中，`status.upgrade.fromCheckpointsCompleted` 大于 0：升级落在旧作业完成 checkpoint 之后 |
| `incompatible-upgrade-rolled-back`、`incompatible-upgrade-alerted` | 自动回滚：`SpecApplied` 为 `RolledBack`，从 savepoint 运行原来的规格；有 Warning `UpgradeRolledBack` |
| `rolled-back-change-reverted` | 恢复原查询后 Running |
| `dws-no-checkpoint-yet` | DWS 改为每天一次 checkpoint，从升级时的 savepoint 恢复运行，还没有完成自己的 checkpoint |
| `before-checkpoint-upgrading` | 紧接着施加的同一不兼容升级进行中，`status.upgrade.fromCheckpointsCompleted` 为 0：升级落在旧作业第一个 checkpoint 之前，没有落在这条路径上就判 FAIL。升级记下的依据在 `status/before-checkpoint-upgrading.json` |
| `before-checkpoint-rolled-back`、`before-checkpoint-alerted` | 它同样自动回滚：`SpecApplied` 为 `RolledBack`，从 savepoint 运行原来的规格；又多一条 Warning `UpgradeRolledBack` |
| `before-checkpoint-change-reverted` | 恢复原查询后 Running |
| `dws-checkpoints-again` | checkpoint 间隔改回 30 s：从 savepoint 恢复，并完成 checkpoint |
| `dwd-suspended`、`dwd-frozen-while-suspended`、`dwd-resumed-from-savepoint` | DWD 暂停期间（60 s）DWS 读到的上游快照不变；恢复时从 savepoint 启动 |
| `dws-taskmanager-loss` | 丢失 TaskManager 后从 checkpoint 恢复运行 |
| `dwd-caught-up`、`dws-caught-up` | 上游积压为 0 |
| `rows-dwd`、`changelog-dwd`、`rows-dws`、`changelog-dws` | DWD 等于源表 join，DWS 等于源表聚合；changelog 没有重复，也没有过时的 before 镜像 |
| `dwd-suspended-again`、`dwd-rebuild-required`、`dwd-rows-untouched` | consumer 被删后恢复：`RebuildRequired` 并有同名 Warning；源表继续变化时 DWD 的行不变 |
| `tables-deleted` | （负向）有 Warning `TargetRetained`；`dwd_tbl_<run>.orders` 保留（200），`dws_tbl_<run>.segments` 删除（404），两张表的 token 都得到 401，DWS 的 consumer 已删，没有作业 |

**副作用与注意**：自己删除 DWS 的 TaskManager Pod、删除一个 Paimon consumer。留下按设计保留的 `dwd_tbl_<run>.orders` 与核对身份的读授权。3 个 FlinkDeployment，可与其他测试同时运行。两次不兼容升级的回滚各约需 10 分钟，改为每 5 分钟一次 checkpoint 后平均再等约 2.5 分钟，整个用例约 60 分钟（加入第二次之前约 45 分钟）。

**清理方式**：测试删除 DWD、DWS 与数据源（删除策略 Delete），按设计保留 `dwd_tbl_<run>.orders`；它、MySQL 库 `tbl_<run>` 与读授权随环境卸载删除。

#### UAT-SRC-02 MongoDB CDC

**对应需求**：#30；验收报告 [M3 MongoDB CDC](../history/m3/mongodb-cdc.md)。

**适用配置**：只装平台或 `--fixtures`（测试自己装开发用 MongoDB）；任一标准区域。

**前置条件**：本环境的仓库里有 MongoDB 的副本（`third-party.tsv` 的 `mongo`）。测试自己安装开发用 MongoDB（`tests/fixtures/mongo/dev-mongo.sh install mg-<run>`：单成员副本集、TLS、5 GiB 卷，NetworkPolicy 只放行 `colvenna-data` 与 `colvenna-system`），结束时删除。

**输入**：合成数据：开发用 MongoDB 的库 `shop_<run>`：`orders` 5000 条、`customers` 200 条、`ledger` 20 条、`catalog` 100 条、`events` 500 条；写入方写 300 轮后加字段 `channel`。

**步骤**：

1. 标签 `mongo`（与 `enforcement` 同时）：`tests/acceptance/mongo/run-mongo.sh mgo<tag> <root>/mongo`。
2. 清掉 `ods_mongo_<run>`、`ods_mongo1_<run>` 上残留的核对授权；装 MongoDB，库 `shop_<run>`：`orders` 5000 条（ObjectId 键）、`customers` 200、`ledger` 20（整数 `_id`）、`catalog` 100、`events` 500。
3. 写入方 `mw-<run>` 写 300 轮后加字段 `channel`；声明 LakeSource `lms-<run>`（列表加 `prod.*`）：`ledger` 被拒，报告 oplog 窗口过短。
4. 作业启动后新建的 `products` 被拒（`AddedAfterStart`）；另一个只读 `products` 的源 `lmo-<run>` 接入、比对后删除。
5. 删除 MongoDB Pod（它在同一卷上回来并重新成为 primary）；丢失 TaskManager；升级 TaskManager 内存；把另一个集合改名覆盖 `catalog`（`SourceReplaced`），删除 `customers`（`TableGone`）。
6. 停写，比对 `orders`，检查新列；删除数据源，清除 MongoDB。

**期望结果**：退出 0，`<root>/mongo/verdicts.tsv` 的 21 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready` | `orders`、`customers`、`catalog` Ready |
| `ledger-refused` | Rejected/`UnsupportedKeyType` |
| `oplog-window-reported`、`oplog-window-alerted` | `RetentionWindow` 为 False；有 Warning `RetentionWindowShort` |
| `products-refused`、`products-refused-alerted` | Rejected/`AddedAfterStart`；有 Warning `TableRejected` |
| `one-collection-ready` | 第二个源的 `products` Ready |
| `rows-products`、`changelog-products` | `ods_mongo1_<run>.products` 与集合一致，changelog 回放正确 |
| `one-collection-deleted` | （负向）第二个源删除后库 404，token 得到 401 |
| `member-back-primary` | 新的成员 Pod 在 300 s 内成为可写的 primary |
| `recovered-from-member-loss`、`recovered-from-taskmanager-loss` | 作业运行，`JobRunning`，`orders` Ready |
| `upgraded` | TaskManager 内存 2560m，从 checkpoint 恢复 |
| `catalog-replaced` | `catalog` 为 Failed/`SourceReplaced`，`orders` 仍 Ready |
| `customers-isolated`、`customers-isolated-alerted` | `customers` 被删后单独隔离；有 Warning `TableGone` |
| `rows-orders`、`changelog-orders` | 逐文档一致，changelog 回放正确 |
| `new-field-column` | `orders` 有列 `channel` |
| `mongo-deleted` | （负向）作业已删，表与库 404，token 得到 401 |

**副作用与注意**：自带的 StatefulSet `mg-<run>`、卷、Secret 与 ConfigMap 在结束时清除；中途失败时用 `dev-mongo.sh purge mg-<run>` 清理。数据源不是个人数据，命名空间范围的删除流程不会因它受阻。2 个 FlinkDeployment，可与其他测试同时运行。以往约 19 分钟。

**清理方式**：测试结束时删除数据源，并清除 MongoDB（StatefulSet、卷、Secret 与 ConfigMap）；中途失败时用 `tests/fixtures/mongo/dev-mongo.sh purge mg-<run>`。

#### UAT-GOV-01 列标签、脱敏、行过滤与 Doris 上的拒绝

**对应需求**：#35；验收报告 [M4 列标签、脱敏与行过滤](../history/m4/governance.md)（报告里的命名空间 `cove-m3-bi` 现为 `colvenna-bi-client`）。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；平台 Doris（安装步骤 `doris`：BE 的 `user_files_secure_path`，NetworkPolicy 放行带 `colvenna-lake.io/doris-client` 标签的命名空间）；开放层栈输出 `IcebergReadRoleArn`；节点不向 Pod 提供实例元数据。测试自己建并标记命名空间 `colvenna-bi-client`。

**输入**：合成数据：MySQL 库 `enf_<run>` 的 30 笔订单（`note` 等列带标签）；看板用户 analyst（行过滤 `state='created'`）与 viewer；17 条应被拒的语句。

**步骤**：

1. 标签 `enforcement`（与 `mongo` 同时）：`tests/acceptance/governance/run-enforcement.sh enf<tag> <root>/enforcement`。
2. MySQL 库 `enf_<run>`（30 笔订单）；LakeSource `ge-<run>`：L3、个人数据、带列标签。
3. LakeDashboard `gd-<run>`（访问级别 L2；查询 `live`、`everything`、`by_state`），用户 analyst 带行过滤 `state='created'`，另有 viewer。
4. 从 `colvenna-bi-client` 以这些用户查询 Doris：脱敏、行、登录、17 条应被拒的语句、没有凭证的 Pod、行过滤探测、`EXPLAIN`、FE 端口。
5. 源表加列 `phone`：它被遮蔽。给一列打上高于表级别的标签（L4）：冲突使表与看板停用、用户被收回；改正后恢复。
6. 清理。

**期望结果**：退出 0，`<root>/enforcement/verdicts.tsv` 的 42 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dashboard-ready` | `orders` Ready 且已治理；看板 Ready，4 条行策略、1 个暴露 |
| `masks-as-labelled` | 脱敏按标签：`customer_id:redact,note:mask,version_no:redact` |
| `analyst-sees-masked-columns-of-its-rows` | analyst 看到 15 行 `created`；`customer_id`、`version_no` 为 NULL，`note` 被遮蔽，`amount` 明文 |
| `analyst-copy-holds-its-rows-only` | 物化副本 `by_state` 只有 `created 15` |
| `user-without-a-condition-sees-no-rows` | （负向）没有行条件的 viewer 看到 0 行 |
| `undeclared-user-cannot-log-in`、`wrong-password-cannot-log-in`、`empty-password-cannot-log-in` | （负向）`access denied for user` |
| `analyst-logged-in-for-the-denials` | 下面这些拒绝确实以 analyst 身份执行 |
| `denied-lake-table`、`denied-system-table`、`denied-masking-view`、`denied-view-definition`、`denied-create-view`、`denied-write`、`denied-own-row-policy` | （负向）直接读湖表、读系统表、读脱敏视图本身、看视图定义、建视图、写入、给自己建行策略：都被权限拒绝 |
| `denied-s3-default-chain`、`denied-s3-instance-profile`、`denied-s3-another-role`、`denied-outfile-to-the-warehouse` | （负向）经 `s3()` 读仓库（默认凭证链、实例角色、扮演另一个角色）与 `INTO OUTFILE` 写仓库：被 AWS 拒绝 |
| `denied-local-files`、`denied-local-absolute-path`、`denied-local-parent-path` | （负向）经 `local()` 读 BE 本地文件、绝对路径、上级路径：被拒 |
| `denied-paimon-meta` | （负向）`paimon_meta()` 被拒 |
| `denied-http-instance-metadata`、`denied-http-colvenna-catalog` | （负向）经 `http()` 访问实例元数据与 colvenna-catalog：401 |
| `no-direct-storage` | （负向）没有 AWS 身份的 Pod：`Unable to locate credentials` |
| `row-filter-probe-fires-on-a-seen-row` | 对看得到的行，`assert_true` 探测会报错（探测有效） |
| `row-filter-probe-sees-no-hidden-row-view`、`row-filter-probe-sees-no-hidden-row-copy`、`row-filter-probe-sees-no-hidden-row-case` | （负向）对视图、副本与 CASE 写法，探测都碰不到隐藏的行：行策略先于用户的谓词 |
| `explain-shows-the-plan-not-the-rows` | （负向）`EXPLAIN` 显示 `mask(` 与策略谓词，不含任何行值 |
| `only-the-mysql-protocol` | （负向）从 `colvenna-bi-client` 只连得上 9030，8030 与 8070 连不上 |
| `new-column-redacted`、`new-column-redacted-in-doris`、`new-column-redacted-for-the-analyst` | 新列 `phone` 标为 `redact`；脱敏视图以 `NULLIF` 遮蔽；analyst 的 `COUNT(phone)` 为 0 |
| `conflict-stops-the-table`、`conflict-stops-the-dashboard` | 列标签高于表的级别：表为 `LabelInvalid`；看板 Failed（“above its table”） |
| `conflict-withdraws-the-users` | （负向）冲突期间 analyst 登录被拒 |
| `fixed-label-serves-again`、`analyst-reads-again` | 改正后看板 Ready；analyst 又能读到行 |

**副作用与注意**：留下命名空间 `colvenna-bi-client`；Doris 用户随看板删除。它的 L3 个人数据源会被同时运行的删除流程当作副本，所以不与 UAT-GOV-02、UAT-ICE-04、UAT-CON-01 同时运行；也不与删除 Doris Pod 的 UAT-DSH-01 同时运行。以往估计 35–50 分钟。

**清理方式**：测试结束时删除看板（Doris 用户随之删除）与数据源；命名空间 `colvenna-bi-client` 留下，后面的用例也用它，随环境卸载删除。

#### UAT-MON-01 监控、告警与不合格行隔离

**对应需求**：#29；验收报告 [M3 监控、告警与数据质量](../history/m3/monitoring.md)（报告里的 `m0-source-latency-agrees`、`m0-layer-latency-agrees` 现名 `source-latency-agrees`、`layer-latency-agrees`）。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：安装步骤 `monitoring`（Prometheus 与告警规则 `ColvennaRowsQuarantined`、`ColvennaLayerLagP99`、`ColvennaSourceStale`，Grafana 与看板 uid `colvenna-lake`，Secret `colvenna-grafana-admin`）；Operator 与 colvenna-catalog 的指标端口 9100；测试源。

**输入**：合成数据：MySQL 库 `mon_<run>`，带心跳表；10 笔标记订单，在一个会话里每 3 s 一笔，覆盖 30 s 检查点周期的各个相位；4 行状态不合格的数据。

**步骤**：

1. 标签 `monitoring`（单独运行）：`tests/acceptance/monitoring/run-monitoring.sh mon<tag> <root>/monitoring`。
2. port-forward Prometheus 与 Grafana（空闲的本机端口）；Grafana 须登录；从 `colvenna-data` 与 `colvenna-monitoring` 做隔离探测。
3. MySQL 库 `mon_<run>` 带心跳表；LakeSource `mon-<run>`，带质量检查的 DWD `mon-dwd-<run>`，DWS `mon-dws-<run>`。
4. 在一个会话里每 3 s 写一笔标记订单，共 10 笔；等 16 个指标序列出现；用标记行在 ODS 与 DWD changelog 里的时间，对照心跳与各层延迟的中位数。
5. 写 4 行状态不合格的数据：被隔离、告警、可独立查到。暂停 DWD 超过 5 分钟的 p99 阈值：告警触发，新鲜度沿链路增长，恢复后告警解除；对数据源与 `ColvennaSourceStale` 做同样的检查。
6. 清理。

**期望结果**：退出 0，`<root>/monitoring/verdicts.tsv` 的 16 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `grafana-signin-required` | （负向）匿名请求 Grafana 看板得到 401；管理员登录后看到看板“Colvenna Lake 运行”的面板 |
| `metrics-isolated` | （负向）从 `colvenna-data` 访问 Prometheus、Operator 与 colvenna-catalog 的指标端口都超时（curl 28）；从 `colvenna-monitoring` 两个指标端口返回 200，Prometheus 仍超时 |
| `source-ready`、`dwd-running`、`dws-running` | 声明都在运行 |
| `paths-measured` | 16 个序列都已采集（600 s 内） |
| `source-latency-agrees` | 标记行测得的源延迟中位数与心跳中位数之差不超过 max(15 s, 25%)：指标的含义与契约一致 |
| `layer-latency-agrees` | ODS→DWD 的标记行中位数与 Operator 导出的层延迟一致，容差同上 |
| `quarantined` | `status.quality` 里 `allowed(state)` 隔离数至少 4 |
| `rows-quarantined-alerted` | `ColvennaRowsQuarantined` 触发 |
| `bad-rows-traceable` | id 10 与 9100001–9100003 在隔离表里带着各自的规则，不在 DWD 里 |
| `layer-lag-alerted`、`freshness-grows-down-the-path`、`layer-lag-resolved` | `ColvennaLayerLagP99` 触发；DWD 与 DWS 的新鲜度都超过 300 s；恢复后告警变为 inactive |
| `source-stale-alerted`、`source-stale-resolved` | `ColvennaSourceStale` 触发，之后解除 |

**副作用与注意**：只暂停自己的声明，不重启任何组件；告警按自己的标签过滤，可与其他测试同时运行。以往约 50–60 分钟。

**清理方式**：测试结束时删除自己的声明与 MySQL 库，停掉自己的 port-forward；告警历史留在 Prometheus 里，按它的保留期过期。

#### UAT-TSK-01 维护任务、回刷与 Airflow

**对应需求**：#27；验收报告 [M3 批任务与维护任务](../history/m3/lake-tasks.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；本环境的仓库里有 Airflow 的副本（`airflow`）；UAT-CAT-01 留下的 `flow_cf<tag>.orders`，runner 以 `OUTSIDE_TABLE` 传入。

**输入**：合成数据：MySQL 库 `task_<run>`（`orders` 5000 行、`segments` 200 行与从不写入的 `empties`），写入方；Airflow 的三次 DAG 运行；各种维护与回刷任务。

**步骤**：

1. 标签 `tasks`（与 `tables` 同时）：`OUTSIDE_TABLE=flow_cf<tag>.orders tests/acceptance/tasks/run-tasks.sh tsk<tag> <root>/tasks`。
2. MySQL 库 `task_<run>`，写入方；LakeSource `ltk-<run>`（`orders`、`segments` 与从不写入的 `empties` 进 `ods_task_<run>`，治理 L2、保留 7 天）；LakeTable `ltk-dwd-<run>` 与 `ltk-dws-<run>`。
3. 起 Airflow 2.10.5（scheduler 与 DAG `colvenna_lake_task`），以服务账号 `airflow-<run>` 运行，它只能创建和读取 LakeTask。三次 DAG 运行：日终 `createTag`、同一个再来一次、一个会被拒的回刷；另发一个匿名 POST。
4. 维护：全量 compact；原样再 apply；修改规格被拒；再次 `createTag`；`removeOrphanFiles` 与 compact 串行；`retries: 0` 时杀掉运行 Pod。DWD 暂停时过期快照（`retainMin 1`、`olderThan 1m`）：consumer 的位置保留。
5. 改维表后回刷：DWS 回刷被拒（`StatefulQuery`）；DWS 暂停；DWD 回刷期间 DWD 作业被挂起（fence），第一次运行在 upsert 提交后被杀，第二次在 DWS 上等待（`Converging`）；DWS 恢复后成功，此时 DWS 已等于源表聚合。
6. 比对行与 changelog；对 `OUTSIDE_TABLE` 的任务被拒（`NotManaged`）；给还没有快照的 `ods_task_<run>.empties` 打 tag 的任务 `notag-<run>`：立即以 `NoSnapshot` 结束、不运行也不重试，删除它之后 Operator 不再导出它的 `Failed`（#171 的产品修复）；结束任务的 token 被拒；清理。

**期望结果**：退出 0，`<root>/tasks/verdicts.tsv` 的 47 项都为 PASS（`compact-committed` 仅在 `compact-succeeded` 通过后判定，`tag-again-idempotent` 仅在 `tag-again` 通过后，`dws-converged-at-success` 与 `backfill-retried` 仅在 `backfill-succeeded` 通过后，`notag-` 开头的另外 4 项仅在 `notag-failed` 通过后）。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dwd-running`、`dws-running` | 声明都在运行；`empties` 已接入，因为从没写过、没有快照，停在 `Snapshotting` |
| `airflow-task-succeeded` | DAG 运行 0 成功，日志有 `COLVENNA_TASK created`，`eod-<run>` Succeeded |
| `airflow-repeat-reused` | DAG 运行 1 成功，日志有 `COLVENNA_TASK exists`，仍只有 1 个 Job |
| `airflow-refused-failed` | DAG 运行 2 以 LakeTask 的失败原因失败 |
| `anonymous-refused` | （负向）未认证地向 API server 创建 LakeTask：401 或 403 |
| `tag-exists` | ODS `orders` 上有 tag `eod-<日期>-<run>` |
| `compact-succeeded`、`compact-committed` | 全量 compact Succeeded；`snapshotAfter` 大于 `snapshotBefore` |
| `reapply-unchanged` | 再次 `kubectl apply`：unchanged，`finishedAt` 不变，仍 1 个 Job |
| `changed-spec-refused` | API server 拒绝修改规格（任务按创建时的规格只运行一次） |
| `tag-again`、`tag-again-idempotent` | 第二次 `createTag` Succeeded，细节为 `tag … exists` |
| `orphans-succeeded`、`second-succeeded`、`tasks-serialized` | 两个任务都 Succeeded，第二个等第一个结束才开始 |
| `retries-exhausted`、`retries-exhausted-reported` | Failed，消息为 `attempt 1 of 1`，有 Warning `TaskRunFailed` |
| `dwd-suspended`、`expire-succeeded`、`expire-kept-reader-position`、`dwd-resumed-after-expire` | 过期后最早的快照不晚于 DWD consumer 的 `nextSnapshot`；DWD 从原位置恢复 |
| `dwd-caught-up`、`dws-caught-up` | 积压为 0 |
| `backfill-has-work` | 回刷前 DWD 与源表 join 不同 |
| `refused-stateful`、`refused-stateful-backfill` | DWS 回刷 Failed，原因 `StatefulQuery` |
| `dws-suspended`、`backfill-fenced`、`backfill-waits-for-dws` | 回刷期间 DWD 被 fence；回刷在 DWS 上等待（`Converging`，`waitingFor` DWS） |
| `backfill-succeeded`、`dws-converged-at-success`、`backfill-retried` | 回刷 Succeeded（3000 s 内）；成功时 DWS 已等于源表聚合；尝试 2 次，历史为 `1 Failed`、`2 Succeeded` |
| `rows-dwd`、`changelog-dwd`、`rows-dws`、`changelog-dws` | 最终的行与 changelog 正确 |
| `refused-outside`、`refused-outside-ran-nothing` | （负向）对命名空间之外的表的任务 Failed，原因 `NotManaged`，没有 Job |
| `notag-failed`、`notag-no-snapshot` | 给没有快照的表打 tag：300 s 内 `Failed`，`Finished` 的原因为 `NoSnapshot`；没有开始任何尝试（`attempts` 为空、没有历史），也没有 Job |
| `notag-not-retried` | 90 s 之后仍是同一次 `Failed`（`finishedAt` 不变），仍没有尝试、没有 Job：不在 `Pending` 里等，也不重试 |
| `notag-alerting-metric` | Operator 导出 `colvenna_task_phase{laketask="notag-<run>",phase="Failed"} 1`，即 `ColvennaTaskFailed` 告警依据的序列 |
| `notag-cleared-on-delete` | 删除任务后 120 s 内这个任务的序列都不再导出，`ColvennaTaskFailed` 随之撤掉 |
| `finished-tokens-refused` | （负向）每个已结束任务的 catalog token 都得到非 2xx |
| `tasks-deleted` | 本运行的任务都没有 Job 留下，DWD 上没有 fence 注解 |

**副作用与注意**：自己杀掉自己的任务 Pod；建了又删 Airflow 的 RBAC；留下核对身份的读授权。`tasks-serialized` 依赖约 15 s 的时间窗。Airflow 段最长可等 3600 s，以往整个用例约 35 分钟。

**清理方式**：测试结束时删除本运行的任务、DWD、DWS 与数据源（删除策略 Delete）、Airflow Pod 与它的 RBAC，再删除 MySQL 库；核对身份的读授权留下，随环境卸载删除。

### 5.5 `kafka` 阶段

本阶段的测试共用开发用 Kafka `colvenna-dev`（命名空间 `colvenna-lake-kafka`）。每个测试以 `helm upgrade --install` 装它，topic 与用户换成这个测试自己的，所以只能逐个运行。前两个测试以 `KEEP_KAFKA=1` 运行，结束时不删除 Kafka；`iceberg` 之后 Kafka 保留，供 UAT-NS-01 使用。

#### UAT-SRC-03 Kafka 事件源

**对应需求**：#25；验收报告 [M3 声明式 Kafka 事件源](../history/m3/kafka-events.md)。

**适用配置**：`--fixtures`（Strimzi）；任一标准区域。

**前置条件**：Strimzi（安装步骤 `strimzi`）；查询节点可放 broker。

**输入**：合成数据：开发用 Kafka 的 topic `ev-<run>`、`ev-<run>-b`：批次 b1（300 条有效事件加每种拒绝原因各一条）到 b7，一条迟到事件。

**步骤**：

1. 标签 `kafka`：`KEEP_KAFKA=1 tests/acceptance/kafka/run-kafka.sh kfk<tag> <root>/kafka`。
2. 装开发用 Kafka（topic `dev-events`、`ev-<run>`、`ev-<run>-b`，用户 `dev-producer`、`dev-consumer`、`producer`、`ingest`、`peek`）；给 `colvenna-data` 与 `colvenna-system` 打 `colvenna-lake.io/dev-kafka-client=true`；声明 LakeSource `ev-<run>`（4 个字段、事件时间、最多乱序 1 分钟），进 `ods_kafka_<run>.events`。
3. 两个暂停的声明：用户 `peek` 无权读（`NotAuthorized`），topic 不存在（`TopicMissing`）。
4. 批次 b1（300 条有效事件，加每种拒绝原因各一条），再一条迟到事件。删除 `ingest` 的 Secret，Strimzi 轮换口令（认证失败，再恢复）；去掉两个命名空间的客户端标签（连不上，再恢复）。
5. 升级；加第二个 topic；在重置消费组 offset 的同时删除 FlinkDeployment（从表重建）；加字段 `campaign`；暂停时写入再恢复。批次 b2–b7。
6. 判定每个分区的每个 offset 恰好落在 `events` 或 `events_rejections` 之一，逐字段一致，状态计数相符；删除数据源。

**期望结果**：退出 0，`<root>/kafka/verdicts.tsv` 的 32 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `kafka-running` | Running；`SourceReady`、`SourceVerified`、`TargetReady` 为 True |
| `kafka-refused-peek`、`kafka-refused-peek-alerted` | （负向）无读权限的用户：`NotAuthorized`；有 Warning `SourceNotAuthorized` |
| `kafka-refused-gone`、`kafka-refused-gone-alerted` | topic 不存在：`TopicMissing`；有 Warning `SourceTopicMissing` |
| `kafka-b1-consumed`、`kafka-b2-consumed`、`kafka-b3-consumed`、`kafka-b4-consumed`、`kafka-b4b-consumed`、`kafka-b5-consumed`、`kafka-b6-consumed` | 每批写完后积压为 0，作业运行 |
| `kafka-b1-rejections` | `LATE`、`NOT_JSON_OBJECT`、`TOO_OLD` 各 1 条，接受 300 条 |
| `kafka-credentials-refused`、`kafka-credentials-alerted` | （负向）口令轮换后旧口令被拒：`AuthenticationFailed`；有 Warning `SourceAuthenticationFailed` |
| `kafka-credentials-renewed` | 取得新口令后 `SourceReady` 恢复 |
| `kafka-unreachable`、`kafka-unreachable-alerted` | （负向）去掉客户端标签后连不上：`Unreachable`；有 Warning `SourceUnreachable` |
| `kafka-reachable-again` | 加回标签后恢复 |
| `kafka-upgraded` | TaskManager 内存已应用，从 checkpoint 恢复 |
| `kafka-topic-added`、`kafka-topics-change-restarts-from-tables` | 2 个 topic；有 Normal `JobDeleted`，新作业从表中的位置开始 |
| `kafka-job-recreated`、`kafka-recreated-from-tables` | 作业运行；消费组被重置也从表重建（Normal `JobRecreated`） |
| `kafka-field-added`、`kafka-field-evolved` | `SpecApplied` 为 True；有 Normal `FieldAdded` |
| `kafka-suspended`、`kafka-lag-while-suspended`、`kafka-resumed-consumed` | 暂停时积压大于 0；恢复后消费完 |
| `kafka-every-record-once` | 每个 offset 恰好一次；有效事件逐字段一致；拒绝记录不带原始内容 |
| `kafka-status-counts` | 状态里的接受数与各拒绝原因计数等于判定的期望 |
| `kafka-deleted` | （负向）作业已删，两张表 404，token 得到 401 |

**副作用与注意**：替换 Kafka 的 topic 与用户；删除 Secret `ingest`；最长约 10 分钟内去掉 `colvenna-data` 与 `colvenna-system` 的客户端标签，这期间所有 Kafka 数据源与 Operator 的探测都连不上 Kafka，所以与其他 Kafka 用户不能同时运行。留下标签与核对身份的读授权。以往约 19 分钟。

**清理方式**：测试删除数据源，加回两个命名空间的客户端标签；开发用 Kafka 以 `KEEP_KAFKA=1` 保留给本阶段后面的测试与 UAT-NS-01，UAT 之后随环境卸载删除，或用 `tests/fixtures/kafka/dev-kafka.sh purge <证据目录> --yes` 删除。

#### UAT-SRC-04 Kafka 承载的 CDC

**对应需求**：#31；验收报告 [M3 Kafka 承载的 CDC](../history/m3/kafka-cdc.md)。

**适用配置**：`--fixtures`（测试源与 Strimzi）；任一标准区域。

**前置条件**：测试源与源登录（测试会重跑 `sources.sh users`）；Strimzi；本环境的仓库里有 Debezium Server 的副本（`debezium-server`）。

**输入**：合成数据：MySQL 库 `kcdc<run>`（`orders` 5000 行、`segments` 200 行、`customers`），写入方；Debezium Server 写进 Kafka 的变更，以及重发的 200 条与 6 条乱码。

**步骤**：

1. 标签 `kafka-cdc`：`KEEP_KAFKA=1 tests/acceptance/kafka/run-kafka-cdc.sh kcd<tag> <root>/kafka-cdc`。
2. MySQL 库 `kcdc<run>`（`orders` 5000、`segments` 200、`customers`），写入方 `tk-<run>`；LakeSource `lkc-<run>` 先直接读 binlog，进 `ods_kcdc<run>`；LakeTable `dwk-<run>` 进 `dwd_kcdc<run>`。
3. Debezium Server `dbz-<run>` 先快照、再持续写进 Kafka；写入不停的情况下给数据源加 `spec.transport.kafka`：直连作业删除，Kafka 作业创建，changelog 切换，起点早于最后一次写入。
4. 故障：重发 200 条已读的记录（过时）；6 条乱码（其中 3 条无法解析）；Debezium 缩到 0 期间删 5 行，再扩回（重新快照被吸收，`DeletesLost` 后 `DeletesRepaired`）。写入方在 Debezium 缩到 0 之前停下，等它重新快照完、开始读 binlog 后再启动：Debezium 以 `snapshot.locking.mode=none` 快照（RDS 不给全局读锁），快照视图与它随后读 binlog 的起点之间提交的行不会出现在任何记录里，这是采集端自己的缺陷，湖无法修复；本步测的是采集端停机期间、没有任何记录带来的删除；删除 Kafka 作业（从重叠位置重建）。
5. 等积压为 0：ODS 的 `orders`、`segments` 等于源表，DWD 等于 join，changelog 回放正确。
6. 删除全部。

**期望结果**：退出 0，`<root>/kafka-cdc/verdicts.tsv` 的 24 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `direct-ready`、`dwd-running` | 直连 binlog 的数据源与 DWD 运行 |
| `debezium-streaming` | `orders` topic 至少 5000 条，Debezium 日志进入 streaming |
| `kafka-running` | 只有作业 `lkc-<run>-kafka`，`TransportReady` 为 True |
| `switch-ordered` | 事件顺序：`JobDeleted`（直连）先于 `JobCreated`（Kafka），再 `ChangelogSwitched` |
| `switch-starts-before-last-write` | `KAFKA_CDC_START` 记录的起点早于最后一次写入；两张目标表都切换 |
| `kafka-caught-up` | 积压小于 200 |
| `replay-dropped-as-stale` | 过时计数恰好增加重发的非删除记录数 |
| `unparsable-counted`、`unparsable-alerted` | 无法解析计数加 3；有 Warning `UnparsableEvents` |
| `resnapshot-absorbed`、`snapshot-again-noticed` | 重新快照后作业运行、积压小于 500；有事件 `SnapshotAgain` |
| `deletes-lost-repaired`、`resnapshot-reconciled` | `DeletesLost` 至少 5 行，随后 `DeletesRepaired`；没有表还在复核 |
| `kafka-recreated`、`kafka-recreated-from-tables` | 作业运行；`JobRecreated`，从重叠位置读 |
| `kafka-drained` | 积压为 0 |
| `rows-ods-orders`、`changelog-ods-orders`、`rows-ods-segments`、`changelog-ods-segments`、`rows-dwd`、`changelog-dwd` | 跨越切换后的最终结果正确 |
| `kafka-cdc-deleted` | （负向）ODS 与 DWD 404，token 得到 401，没有本运行的 FlinkDeployment |

**副作用与注意**：替换 Kafka 的 topic 与用户；给两个命名空间打客户端标签；自己缩放 Debezium；以同样的口令重设测试源的登录。Debezium 的 `server.id` 固定为 7100，不能两个同时运行。以往约 39 分钟。

**清理方式**：测试删除数据源、DWD 与 Debezium Server；Kafka 保留（同上）；测试源的登录以原来的口令重设，不需要恢复。

#### UAT-QUA-01 批量质量校验、发布门禁与豁免

**对应需求**：#38；验收报告 [M4 批量数据质量校验与发布门禁](../history/m4/batch-quality.md)。

**适用配置**：`--fixtures`（Strimzi）；任一标准区域。

**前置条件**：Strimzi；平台 Doris；本提交的 `colvenna` CLI（安装步骤 `cli`）；kubeconfig 的身份能以 `kubectl --as` 扮演用户；期间 catalog 不重启（测试从开始到结束持有一个 catalog 的 port-forward）。测试自己装开发用 Kafka（topic `qual-<run>`），自己建并标记 `colvenna-bi-client`。

**输入**：合成数据：Kafka topic `qual-<run>` 的事件：昨天与今天两个干净的分区，5 条无用户、3 条重复 id 的坏事件；质量规则与豁免的声明。

**步骤**：

1. 标签 `quality`：`tests/acceptance/quality/run-quality.sh qal<tag> <root>/quality`。
2. Kafka 事件源带一个读者；LakeQuality（`notNull`、`unique`、`rowCount`、`freshness`，门禁发布）；一个带用户 analyst 的看板。任何检查之前门禁是关的。
3. 昨天与今天两个干净的分区：`colvenna check` 退出 0，门禁打开，读者与看板得到服务。
4. 坏事件（5 条无用户、3 条重复 id）：退出 3，门禁关闭；失败的快照被打 tag，定位语句列出坏事件；同样条件重跑结果相同；删除任务后门禁仍关。
5. 豁免：伪造审批人、无权限的用户、豁免一条已通过的规则，都被拒；`colvenna waive` 打开门禁。被杀掉的检查什么都不写。
6. 改规则使门禁关闭；新的检查通过；`maxAge: 6m` 使门禁过期。核对决定记录、审批人与 tag 的清除。

**期望结果**：退出 0，`<root>/quality/verdicts.tsv` 的 56 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `events-d1`、`events-d2`、`events-bad`、`events-dups` | 各批事件的接受数到达 |
| `source-running` | 数据源运行 |
| `gate-closed-before-any-check`、`source-withheld-before-any-check` | 没有检查时门禁为关（`NoCheck`），数据源不发布 |
| `reader-refused-before-any-check`、`dashboard-refused-before-any-check` | （负向）读者得到 403；看板不提供 |
| `check-c1-exit`、`check-c2-exit`、`check-c3-exit`、`check-c4-exit`、`check-c6-exit` | `colvenna check` 的退出码：c1、c2、c6 为 0，c3、c4 为 3（规则未通过） |
| `results-c1`、`results-c2`、`results-c3`、`results-c6` | 结果库里每条规则的结果符合期望 |
| `gate-open-after-c1`、`source-published`、`reader-served-after-passing-checks`、`dashboard-ready`、`dashboard-served-after-passing-checks` | 检查通过后门禁打开（`Passed`），数据源发布，读者得到 200，看板 Ready 并应答 |
| `c3-verdict` | 任务状态为 Failed，失败规则为 `event-unique`、`user-present` |
| `gate-closed-after-c3`、`source-withheld-after-c3` | 检查失败后门禁关闭，数据源撤回 |
| `reader-refused-after-failed-check`、`dashboard-refused-after-failed-check`、`dashboard-user-refused-after-failed-check` | （负向）读者 403；看板不提供；看板用户的查询被拒 |
| `gate-closed-warned` | LakeQuality 上有 Warning `GateClosed` |
| `failed-snapshot-tagged`、`locate-user-present`、`locate-event-unique` | 失败的快照有 tag；经这个 tag 运行定位语句，列出的正是坏事件 |
| `rerun-same-results` | 同一快照、同一时间重跑，结果相同 |
| `gate-closed-after-tasks-deleted`、`reader-refused-after-tasks-deleted` | （负向）删除检查任务后门禁仍关，读者仍 403：门禁以结果库为准 |
| `waiver-forged-approver-refused`、`waiver-without-right-refused` | （负向）伪造审批人的豁免被 API server 拒绝；没有 `lakewaivers` 权限的用户建不了豁免 |
| `waiver-of-a-passed-rule-rejected` | 豁免一条已通过的规则：被拒 |
| `waiver-accepted`、`gate-waived`、`reader-served-after-waiver`、`dashboard-ready-after-waiver` | 豁免被接受，门禁以 `Waived` 打开，读者 200，看板 Ready |
| `interrupted-check-failed`、`interrupted-check-wrote-nothing`、`gate-unchanged-by-interrupted-check` | 被杀掉的检查 Failed，没有写入结果，门禁不变 |
| `gate-closed-on-new-rules`、`waiver-superseded-by-new-rules` | 规则改变后门禁关闭，豁免变为 Superseded |
| `reader-refused-on-new-rules` | （负向）读者 403 |
| `gate-open-under-new-rules`、`reader-served-under-new-rules` | 新规则下的检查通过，门禁打开，读者 200 |
| `gate-expired` | 最新检查超过 `maxAge` 后门禁过期（`Expired`） |
| `reader-refused-once-expired` | （负向）门禁过期后读者 403 |
| `decisions-recorded`、`waiver-decision-names-approver`、`tags-dropped-with-lakequality` | 门禁的每次变化都记入决定；`Waived` 的决定记有豁免、审批人与理由；删除 LakeQuality 后不留 `colvenna-check-` tag |

**副作用与注意**：替换 Kafka 的 topic；留下 `colvenna-bi-client` 与结果库 `quality_colvenna_data` 里的行。LakeTask 名固定为 `c1`–`c6`，不能两个同时运行。昨天与今天在开始时算定，跨越 UTC 0 点的运行可能使 c3 失败。`gate-expired` 最多等 600 s。以往约 22 分钟。

**清理方式**：测试删除任务、LakeQuality、看板与数据源；结果库 `quality_colvenna_data` 里的行与 `colvenna-bi-client` 留下，随环境卸载删除。

#### UAT-ICE-01 已有 Trino 经 Iceberg 读取

**对应需求**：#36；验收报告 [M4 已有 Trino 的接入边界](../history/m4/trino.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；Strimzi；Polaris 与指向它的 Operator（安装步骤 `polaris`、`operator-again`）；Helm；能访问 Docker Hub（`trinodb/trino:484`）与 Maven Central。测试自己装开发用 Kafka（topic `trino-<run>`）。

**输入**：合成数据：两批 Kafka 事件与一张 MySQL 主键表（再改几行）；两个 Polaris principal。

**步骤**：

1. 标签 `trino`：`tests/acceptance/trino/run-trino.sh trn<tag> <root>/trino`。
2. Kafka append 数据源与 MySQL 主键数据源；Polaris 上一个有授权、一个没有授权的 principal；两个 LakeIcebergPublication。
3. 在 `colvenna-trino` 装一个代替用户 Trino 的 Trino 484：5 个 catalog，普通清单，没有 Service，拒绝一切的 NetworkPolicy，没有 IRSA。
4. append 表：读到的与 Paimon 在已发布快照上的相同，跟上新提交，时间回溯与 `$files` 可用。主键表：压缩前不提供，压缩后是压缩的行，第二次压缩后是变化后的行。
5. 边界：匿名、错误的 secret、没有下发凭证、没有授权的 principal；写入、删表、建表被拒；Trino 的用户共用一个 principal；网络隔离；paimon-trino 未发布；平台安装里没有 Trino。
6. 清理，删除 `colvenna-trino`。

**期望结果**：退出 0，`<root>/trino/verdicts.tsv` 的 40 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-running`、`cdc-source-ready`、`published` | 两个数据源运行，append 表已发布 |
| `events-b1`、`events-b2` | 两批事件被接受 |
| `published-at-latest-b1`、`published-at-latest-b2` | catalog 指向 Paimon 的最新快照 |
| `trino-reads-what-paimon-reads`、`trino-sees-the-published-snapshot`、`trino-follows-new-commits`、`time-travel-reads-the-earlier-snapshot`、`metadata-tables-readable` | Trino 读到的等于 Paimon，快照一致，跟上新提交，回溯读到早先的快照，`$files` 可读 |
| `orders-withheld-until-compacted`、`orders-not-served-before-compaction` | 主键表在首次全量压缩前不发布，Trino 读不到 |
| `compacted-c1-<run>`、`orders-published-after-compaction`、`trino-reads-the-compacted-orders` | 压缩 Succeeded 后发布，Trino 读到压缩后的行 |
| `changes-reached-the-lake`、`orders-published-after-changes`、`trino-reads-the-compacted-orders-until-the-next-compaction` | 源端变化进湖后，下次压缩前 Trino 仍读到上次压缩的行 |
| `compacted-c2-<run>`、`orders-published-after-c2`、`trino-reads-the-changed-orders`、`updated-orders-visible`、`deleted-orders-gone` | 第二次压缩后读到变化后的行：更新可见，删除的行消失 |
| `anonymous-gets-nothing` | （负向）匿名访问 Polaris 被拒 |
| `wrong-secret-gets-no-token` | （负向）错误的 client secret 取不到 token |
| `no-vended-credentials-no-data` | （负向）不用下发凭证就读不到数据 |
| `ungranted-principal-reads-nothing`、`ungranted-principal-reads-no-metadata-table` | （负向）没有授权的 principal 读不到表，也读不到元数据表 |
| `insert-refused`、`drop-refused`、`create-refused`、`nothing-written` | （负向）写入、删表、建表被拒，行数不变 |
| `trino-users-share-the-principal` | Trino 的不同用户读到相同的行：没有按用户的授权，能读什么由 catalog 的 principal 决定 |
| `trino-has-no-service` | （负向）`colvenna-trino` 里没有 Service 或 Ingress |
| `trino-listens-on-its-address` | 在 Trino 自己的 Pod 里连得上 8080 |
| `trino-unreachable-from-other-pods` | （负向）`colvenna-data` 的 Pod 连不上 Trino |
| `paimon-trino-unreleased` | Maven Central 上没有 `paimon-trino-476`（404） |
| `trino-not-in-the-platform-install` | Operator 的 chart 渲染不出任何 Trino 的对象 |

**副作用与注意**：替换 Kafka 的 topic；建了又删 `colvenna-trino`；留下它的 Polaris principal 与角色。不与 UAT-ICE-02 同时运行（那个测试让 Polaris 停服一段时间）。`paimon-trino-unreleased` 依赖外网与时间：上游发布该构件后会失败。以往约 16 分钟，另加第一次拉取 Trino 镜像。

**清理方式**：测试删除 `colvenna-trino`、两个发布与数据源；它的 Polaris principal 与角色留下（运行 id 不同，不妨碍重跑），随环境卸载删除。

#### UAT-ICE-02 Iceberg 兼容发布

**对应需求**：#34；验收报告 [M4 开放层：Iceberg 兼容发布](../history/m4/iceberg.md)的“结果”。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；Strimzi；Spark Iceberg 读端镜像（安装步骤 `readers`）；开放层栈；Polaris（catalog `colvenna_open`、Secret `polaris-root`）与指向它的 Operator；查询节点可放 Spark Pod。测试自己装开发用 Kafka（topic `dev-events`、`ice-<run>`），自己建并标记 `colvenna-readers`。

**输入**：合成数据：MySQL 库 `ice_<run>` 的 100 笔订单（之后更新与删除几笔），Kafka 的 clicks 事件 b1–b4（中途加字段）；Polaris 的读者与 outsider。

**步骤**：

1. 标签 `iceberg`：`tests/acceptance/iceberg/run-iceberg.sh ice<tag> <root>/iceberg`。
2. MySQL 库 `ice_<run>`（100 笔订单）的主键数据源，与 Kafka 的 append 数据源（clicks），都带 Iceberg 元数据；Polaris 上的 catalog 角色、principal reader 与 outsider；一个原生 Paimon principal 作对照。
3. 主键表：首次全量压缩前不发布；压缩后等于 Paimon；更新与删除后，Iceberg 的视图等于 `$ro`；第二次压缩后再次相等。
4. append 表：提交后 90 s 内发布，快照与 Paimon 相同；新增字段在审核前撤下；tag 到达读者。
5. 门禁：表定为 L3 时撤下，改回 L2 时重新发布。Polaris 缩到 0 期间写 20 条事件，扩回 1 后 catalog 追上。
6. 边界：撤销读者角色、写入与 DDL 被拒、outsider 被拒、匿名 401、管理端口 8182 对读者关闭、下发凭证的范围与有效期、读端 Pod 没有别的 AWS 身份。删除发布。

**期望结果**：退出 0，`<root>/iceberg/verdicts.tsv` 的 64 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `cdc-source-ready`、`kafka-source-running` | 两个数据源运行 |
| `orders-withheld-until-compacted`、`orders-not-served-before-compaction` | 主键表 Pending/`MetadataIncomplete`；Spark 读得到 `NOT_FOUND` |
| `compacted-c1-<run>`、`compacted-c2-<run>` | 两次全量压缩的 LakeTask Succeeded |
| `compaction-keeps-the-metadata-chain` | 压缩任务的细节表明 manifest 没有被压缩，每个快照的 Iceberg 元数据都保留 |
| `orders-published-after-compaction`、`orders-published-after-changes`、`orders-published-after-c2` | 每次 catalog 都指向 Paimon 的最新快照 |
| `iceberg-equals-paimon-after-compaction`、`iceberg-has-all-orders` | 100 行与 Paimon 相同 |
| `iceberg-is-ro-after-changes` | 下次压缩前 Iceberg 视图等于 `$ro` |
| `iceberg-equals-paimon-after-changes`、`deleted-orders-gone`、`updated-orders-visible` | 第二次压缩后与 Paimon 相同：删除的行消失，更新的行可见 |
| `events-b1`、`events-b2`、`events-b3`、`events-b4` | 各批事件累计被接受 |
| `clicks-published` | append 表已发布 |
| `visibility-within-window` | 从提交到发布不超过 90 s |
| `append-snapshot-equals-paimon`、`iceberg-snapshot-is-paimon-snapshot` | 行相同，快照 id 相同 |
| `field-added`、`unreviewed-column-withdrawn`、`unreviewed-column-not-served`、`reviewed-column-republished` | 新增字段在发布审核前表被撤下、读不到；列入发布后重新发布 |
| `tagged`、`clicks-published-after-field`、`added-field-visible`、`rows-with-added-field-equal`、`tag-visible` | tag 与新字段都到达读者，行相同 |
| `withdrawn-when-restricted`、`withdrawn-table-not-found` | （负向）表定为 L3 时撤下，读者读不到 |
| `republished-when-l2` | 改回 L2 时重新发布 |
| `publication-unavailable`、`writers-unaffected`、`caught-up-after-outage`、`catalog-caught-up` | Polaris 停服时发布不可用，写入方不受影响；恢复后追上，发布的快照前进 |
| `reader-revoked`、`revoked-reader-refused` | （负向）撤销 catalog 角色后读者被拒（Forbidden） |
| `reader-granted-again` | 再授予后恢复 |
| `reader-cannot-insert`、`reader-cannot-drop`、`reader-cannot-create`、`reader-cannot-alter`、`reader-cannot-rename` | （负向）读者不能写入、删表、建表、改表、改名 |
| `reader-still-reads` | 这些尝试之后读者仍能读 |
| `outsider-cannot-read` | （负向）没有授权的 principal 得到 Forbidden |
| `anonymous-refused` | （负向）不带 token 的 `loadTable` 得到 401 |
| `anonymous-config-refused`、`anonymous-namespaces-refused`、`anonymous-catalogs-refused`、`anonymous-principals-refused` | （负向）匿名访问 catalog API 与管理 API 都得到 401 |
| `management-port-closed-to-readers` | （负向）从 `colvenna-readers` 连不上 8182 |
| `vended-credentials-expire-within-15m` | 下发凭证在 15 分钟内到期 |
| `vended-reads-own-table` | 下发凭证能读本表的文件 |
| `vended-cannot-read-other-table`、`vended-cannot-write` | （负向）读不了别的表（403），什么都写不了 |
| `reader-pod-has-no-other-way` | （负向）读端 Pod 没有别的 AWS 身份可用 |
| `withdrawn-orders`、`withdrawn-clicks` | 删除发布后 `loadTable` 得到 404 |
| `vended-credentials-outlive-withdrawal-until-expiry` | 撤下之前下发的凭证在到期前仍能读（记录剩余秒数） |

**副作用与注意**：

- 替换 Kafka 的 topic 与用户，Kafka 保留。给 `colvenna-data`、`colvenna-system` 打客户端标签。
- 把 `deploy/polaris` 缩到 0 再扩回；无论运行如何结束，退出时都把它扩回 1 个副本。期间不能运行其他用 Polaris 的测试。
- 留下 `colvenna-readers` 以及它建的全部 Polaris principal 与角色，同一运行 id 重跑会得到 409。
- 凭证有效期的检查要求撤下发生在下发后 900 s 内。以往约 26 分钟。

**清理方式**：测试退出时（成败都一样）把 Polaris 扩回 1 个副本；删除发布与数据源。`colvenna-readers` 与它建的 Polaris principal、角色留下，随环境卸载删除。

#### UAT-ICE-03 写入端自己压缩的分层表发布

**对应需求**：#34；验收报告 [M4 开放层：Iceberg 兼容发布](../history/m4/iceberg.md)的“写入端自己压缩的分层表（补验）”。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；Spark Iceberg 读端镜像；Polaris 与指向它的 Operator。不用 Kafka。测试自己建 `colvenna-readers`（若不存在）。

**输入**：合成数据：MySQL 库 `icel_<run>` 的 60 笔订单，后台每 240 s 更新一行，之后把 10 笔改为 paid。

**步骤**：

1. 标签 `iceberg-layered`：`tests/acceptance/iceberg/run-iceberg-layered.sh icl<tag> <root>/iceberg-layered`。
2. MySQL 库 `icel_<run>`（60 笔订单），LakeSource 到 ODS，LakeTable 到 DWD（带 Iceberg 元数据），以及一个发布；后台每 240 s 更新一行。
3. DWD 的选项 `compaction.optimization-interval = 1 h`；原生 principal 与 Polaris 读者。
4. 等写入端自己完成的压缩带来发布；Iceberg 等于 `$ro`。
5. 把 10 笔订单改为 paid，每 300 s 用 Spark 查一次，直到读者看到；期间没有 compact 任务运行。
6. 清理，包括 Polaris 上的对象。

**期望结果**：退出 0，`<root>/iceberg-layered/verdicts.tsv` 的 10 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`table-running` | 声明都在运行 |
| `writer-gets-the-interval` | DWD 的 `compaction.optimization-interval` 为 `1 h` |
| `published-by-the-writer` | 没有压缩任务，发布由写入端的压缩带来（5400 s 内），读端的授权也已就位（`status.readers`） |
| `iceberg-is-the-writers-compacted-rows` | Iceberg 等于 `$ro` |
| `changes-visible-by-the-writers-compaction` | 10 笔 paid 订单在变化后 4500 s 内到达读者 |
| `iceberg-is-the-writers-compacted-rows-after-changes`、`iceberg-has-the-paid-orders`、`ro-has-the-paid-orders` | 变化之后 Iceberg 仍等于 `$ro`，两边都有 10 笔 paid |
| `no-compaction-task-needed` | DWD 上 0 个 compact 任务，发布的快照前进 |

**副作用与注意**：Polaris 上的对象在结束时清理。不在 UAT-ICE-02 让 Polaris 停服时运行。以往约 20 分钟（看到变化用了约 11 分钟），最坏约 3 小时。

**清理方式**：测试结束时删除声明与它在 Polaris 上建的对象。

### 5.6 `namespaces` 阶段

#### UAT-NS-01 第二个受管命名空间的作业身份

**对应需求**：#148（PR #160）。没有单独的验收报告；设计见 [colvenna-operator LLD](../design/colvenna-operator-lld.md) 中受管命名空间与作业角色的说明。

**适用配置**：`--fixtures`（Strimzi；本阶段要重新部署 Operator 栈）；任一标准区域。

**前置条件**（由 runner 依次准备）：

- `namespaces-stack`：`MANAGED_NAMESPACES=colvenna-data,colvenna-second deploy/scripts/operator/deploy-stack.sh`，为 `colvenna-second` 加作业角色 `colvenna-lake-<env>-jobs-colvenna-second`；
- `namespaces-operator`：同样的变量运行 `install-operator.sh`，建 `colvenna-second` 并升级 Operator（Operator 重启）；
- 开发用 Kafka 已装（`kafka` 阶段留下）；
- `flink-state/colvenna-data/` 下有对象（前面阶段的作业留下），仓库的 `warehouse/` 下有表对象。命名空间范围的删除流程会清掉孤立的 Flink 状态，所以本阶段排在 `solo` 之前。

**输入**：合成数据：`colvenna-second` 里 Kafka 数据源 `ns-<run>` 的事件；两个命名空间的作业身份各发 13 个 S3、STS 请求。

**步骤**：

1. 标签 `namespaces`：`MANAGED_NAMESPACES=colvenna-data,colvenna-second tests/acceptance/namespaces/run-namespaces.sh ns<tag> <root>/namespaces`。
2. 核对每个受管命名空间的 `colvenna-flink` 服务账号带各自的角色。
3. 建 KafkaTopic 与 KafkaUser `ns-<run>`，给 `colvenna-second` 与 `colvenna-system` 打客户端标签；在 `colvenna-second` 声明 Kafka 数据源 `ns-<run>`，等它运行并在 `flink-state/colvenna-second/` 下完成 checkpoint。
4. 以每个命名空间的作业身份起探测 Pod，各发 13 个 S3、STS 请求：自己的状态、对方的状态、表数据、扮演对方的角色。
5. 删除数据源；退出时删除 topic、用户、Secret、CA ConfigMap 与 `colvenna-second` 的标签。
6. 之后 runner 以 `namespaces-operator-back`、`namespaces-stack-back` 用默认的 `MANAGED_NAMESPACES` 恢复 Operator 与栈，再删除 `colvenna-second` 与 `s3://<仓库桶>/flink-state/colvenna-second/`。

**期望结果**：退出 0，`<root>/namespaces/verdicts.tsv` 的 36 项都为 PASS。下表的 `<ns>` 依次为 `colvenna-data` 与 `colvenna-second`，每个名字两项。

| 检查 | 期望 |
| --- | --- |
| `role-colvenna-data`、`role-colvenna-second` | 服务账号注解为 `ColvennaLakeOperator` 栈记录的本命名空间角色，两个命名空间不同 |
| `second-source-running` | Running，`SourceReady`、`JobRunning` |
| `second-checkpoint-completed` | `flink-state/colvenna-second/` 下有完成的 `chk-N/_metadata` |
| `existing-state-colvenna-data`、`existing-state-colvenna-second`、`existing-table-object` | 探测目标确实存在（否则 403 证明不了什么） |
| `colvenna-data-identity`、`colvenna-second-identity` | 探测 Pod 的身份是 `assumed-role/colvenna-lake-<env>-jobs-<ns>/` |
| `<ns>-own-list`、`<ns>-own-put`、`<ns>-own-get`、`<ns>-own-delete` | 自己的状态可列、可写、可读、可删 |
| `<ns>-own-root-head` | 状态根路径本身 HEAD 得到 404（Hadoop S3A 需要 404 而不是 403） |
| `<ns>-other-list`、`<ns>-other-list-bare`、`<ns>-other-root-head`、`<ns>-other-get`、`<ns>-other-put` | （负向）对方的状态列不了、读不了、写不了：403 |
| `<ns>-tables-list`、`<ns>-tables-get` | （负向）表数据列不了、读不了：403 |
| `<ns>-other-assume` | （负向）扮演对方的角色：403 |
| `second-source-deleted` | 没有留下 LakeSource 与 FlinkDeployment |

**副作用与注意**：Operator 在本阶段重启两次（升级与恢复），CDK 栈部署两次，所以本阶段单独占一段时间。测试本身不恢复任何东西，恢复由 runner 完成。`colvenna-system` 上的 Kafka 客户端标签会留下。测试约 20–30 分钟，前后两次重新部署各约 10 分钟。

**清理方式**：测试删除数据源、topic、用户、Secret、CA ConfigMap 与 `colvenna-second` 的标签；runner 随后恢复 Operator 与栈，再删除 `colvenna-second` 与 `s3://<仓库桶>/flink-state/colvenna-second/`。`namespaces-operator-back` 或 `namespaces-stack-back` 失败时，以默认的 `MANAGED_NAMESPACES` 手动重跑 `deploy/scripts/operator/install-operator.sh` 与 `deploy-stack.sh`。

### 5.7 `solo` 阶段

本阶段的测试逐个运行。血缘测试比对 Marquez 里命名空间的全部作业，删除流程作用于命名空间里的全部声明，看板测试会删除平台 Doris 的 Pod，它们与其他测试同时运行会互相干扰。

#### UAT-LIN-01 血缘上报到 Marquez

**对应需求**：#37；验收报告 [M4 血缘与目录发现](../history/m4/lineage.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；平台 Doris；Marquez 与指向它的 Operator（安装步骤 `marquez`、`operator-again`，`COLVENNA_LINEAGE_URL` 已设）；`colvenna-data` 没有 `colvenna-lake.io/lineage-client` 标签；执行机的出口地址在 EKS 公网端点的允许范围内（经 API server 代理的检查）。

**输入**：合成数据：MySQL 库 `lin_<run>`（`orders`、`segments`）与由它们而来的 ODS、DWD、DWS、看板视图；源表加列 `channel`；一次回刷、一次压缩与一次即席查询。

**步骤**：

1. 标签 `lineage`：`tests/acceptance/lineage/run-lineage.sh lin<tag> <root>/lineage`。
2. MySQL 库 `lin_<run>`（`orders`、`segments`）→ ODS → DWD（join）→ DWS（聚合）→ LakeDashboard 视图。
3. Marquez 里的路径、版本（规格哈希与 Flink 作业）与 schema 与 colvenna-catalog 一致，源表加列之后也一致。修改 `checkpointInterval` 产生新版本；回刷 LakeTask 作为批运行上报。
4. 一次压缩与一次即席查询不上报（150 s 后比对作业清单）；删除 Operator Pod，再检查血缘指标。
5. 暴露面：Marquez 只有 ClusterIP，未打标签的命名空间连不上，Web 与管理端口被拒，Operator 的命名空间连得上，未认证的 API server 代理被拒。
6. 删除声明；所有运行都已结束。

**期望结果**：退出 0，`<root>/lineage/verdicts.tsv` 的 31 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `operator-reports-to-marquez` | Operator 设置了 `COLVENNA_LINEAGE_URL` |
| `source-ready`、`dwd-running`、`dws-running`、`dashboard-ready` | 流水线运行 |
| `path-orders-to-doris-view`、`path-segments-to-dwd` | Marquez 里有从 `orders` 到 Doris 视图、从 `segments` 到 DWD 的每一跳 |
| `versions-are-the-deployments` | 作业版本是规格哈希，运行是 Flink 作业 |
| `schema-ods_lin_<run>.orders`、`schema-ods_lin_<run>.segments`、`schema-dwd_lin_<run>.orders`、`schema-dws_lin_<run>.segments` | Marquez 记录的字段等于 colvenna-catalog 里的列 |
| `schema-follows-the-catalog-after-a-new-column` | 源表加的列 `channel` 出现在 Marquez 里 |
| `dwd-upgraded`、`new-version-runs`、`old-version-run-completed` | 改规格后新版本运行，旧版本的运行已结束 |
| `backfill-succeeded`、`backfill-is-a-batch-run`、`backfill-run-completed`、`dwd-resumed`、`resumed-job-is-its-running-run` | 回刷成功并作为批运行上报、已结束；DWD 恢复后，运行中的作业就是它当前的运行 |
| `compaction-succeeded`、`maintenance-and-queries-not-reported` | 压缩与即席查询不产生新的作业或数据集 |
| `restart-starts-no-run-twice`、`lineage-reported-without-failures` | Operator 重启后不重复开始运行；上报失败计数为 0 |
| `marquez-cluster-ip-only` | （负向）Marquez 的 Service 都是 ClusterIP，没有 Ingress |
| `unlabelled-namespace-cannot-connect` | （负向）从 `colvenna-data` 连不上 Marquez API |
| `web-and-admin-port-refused` | （负向）从 `colvenna-data` 连不上 Web 界面与管理端口 |
| `operator-namespace-connects` | 从 Operator 的命名空间连得上 |
| `unauthenticated-proxy-refused` | （负向）未认证地经 API server 代理访问 Marquez 被拒 |
| `runs-ended-with-their-declarations` | 删除声明后，四个作业都没有 RUNNING 的运行 |

**副作用与注意**：删除 Operator Pod，期间所有声明停止调谐 1–2 分钟；比对 Marquez 命名空间 `colvenna-lake.colvenna-data` 的全部作业，期间有别的声明或 LakeTask 变化就会误判失败。Marquez 保留它记录过的全部作业；作业清单按 200 条查询，UAT 后期前面的测试已留下许多作业，结果可疑时核对清单是否被截断。以往约 25 分钟。

**清理方式**：测试删除自己的声明；Marquez 保留它记录过的作业与数据集，不能删除，随环境卸载删除。

#### UAT-GOV-02 分级、保留与跨层个人数据删除

**对应需求**：#28；验收报告 [M3 保留策略与跨层个人数据删除](../history/m3/governance-erasure.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录；安装步骤 `operator` 记录了存储证据（`storage-evidence` 读它）；`colvenna-data` 里没有别的个人数据或 L3 的声明。

**输入**：合成数据：MySQL 库 `gov_<run>`（200 个客户、5000 笔订单），写入方；删除的主体客户 42（放在 Secret 里）。

**步骤**：

1. 标签 `governance`：`tests/acceptance/governance/run-governance.sh gov<tag> <root>/governance`。
2. catalog 身份 `reader-<run>`；MySQL 库 `gov_<run>`（200 个客户、5000 笔订单），写入方。LakeSource `gov-<run>`：L3、个人数据、保留 24 小时、主体键，发布给 reader；LakeTable `gov-dwd-<run>`（lookup join，L3）与 `gov-low-<run>`（L2，低于上游）。
3. 核对 reader 能读什么（200 或 403）；降低、删去再恢复 DWD 的治理信息，授权随之变化。
4. 保留：选项落到表上；2 天的 tag 被拒；Operator 每小时的过期任务成功。
5. 删除：删掉低级别表、停写；Secret 加 LakeErasure `erase-<run>`（逻辑删除期限 60 分钟、物理删除期限 75 分钟）；在源端删除客户 42，接受后立即重启 Operator；等到 `LogicallyErased`；独立读取、fence、回刷与 Flink 状态的检查；越过物理期限再等 10 分钟，等 `SlaBreach`。
6. 清理：删除流程、表与数据源；撤销并停用 reader；删除 MySQL 库。

**期望结果**：退出 0，`<root>/governance/verdicts.tsv` 的 28 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready` | `orders`、`customers` Ready，作业运行（900 s 内） |
| `source-governed`、`source-published` | `Governed`、`Published` 为 True |
| `dwd-running`、`dwd-published` | DWD 运行并已发布 |
| `low-not-governed`、`low-not-published` | L2 表：`Governed` 为 False/`BelowUpstream`，`Published` 为 False/`NotGoverned` |
| `published-only-governed` | （负向）reader 读 ODS `orders`、`customers` 与 DWD 得到 200，读低级别表得到 403 |
| `dwd-lowered-unpublished` | （负向）DWD 降为 L2 时撤销发布（`NotGoverned`） |
| `dwd-republished` | 恢复后重新发布 |
| `grant-follows-governance` | （负向）降级期间 reader 读 DWD 得到 403，恢复后 200 |
| `dwd-retention-missing`、`dwd-unclassified` | （负向）去掉保留期时 `Governed` 为 False/`RetentionMissing`，去掉分级时为 False/`Unclassified` |
| `dwd-declared-republished` | 补回后重新发布 |
| `undeclared-unpublished` | （负向）缺保留期与缺分级时 reader 都得到 403，补回后 200 |
| `bounded-retention` | ODS 与 DWD 上有三个 24 小时的保留选项；DWD 的 `compaction.optimization-interval` 为 1 h |
| `long-tag-refused` | 保留 2 天的 `createTag`：`Finished` 为 False/`RetentionTooLong` |
| `retention-enforced` | Operator 每小时的过期 LakeTask 成功（900 s 内） |
| `dwd-caught-up` | 停写后 DWD 积压为 0 |
| `erasure-counted-before` | 每个不处于 Blocked 的 SourceDatabase 与 Rows 副本都统计到大于 0 行 |
| `erasure-logical` | 60 分钟内 `LogicallyErased` |
| `erasure-independent-read` | 独立读取：没有主体 42 的行；最早的快照不早于干净的那个；3 张表都干净 |
| `dwd-compacted-behind-fence` | 删除流程对 DWD 的压缩任务成功，fence 已释放 |
| `dwd-backfill-accounted` | 不需要回刷，或回刷成功并有 `ErasureBackfill` 事件 |
| `flink-state-checked` | 每个 FlinkState 副本都 Done，包括孤立的状态 |
| `erasure-overdue-reported` | 越过物理期限：`SlaBreach`，1 条违约指明 Files，`PhysicallyErased` 与 `WithinDeadlines` 为 False，有 Warning `ErasureOverdue` |
| `storage-evidence` | Storage 副本为 `Done: bucket …` |
| `unpublished-on-delete` | （负向）删除后 reader 不持有任何授权 |

**副作用与注意**：

- 重启 Operator，影响同时运行的所有测试。
- LakeErasure 作用于命名空间里的全部 LakeSource、LakeTable、LakeDashboard 与 LakeIcebergPublication：别的测试的个人数据表会成为统计到 0 行的副本，使 `erasure-counted-before` 失败；Kafka、MongoDB 的个人数据源或未声明的表会使它 Blocked。
- 删除检查会删掉命名空间里其他作业的旧 checkpoint、savepoint 以及已删除作业的状态；运行期间删掉的作业会留下 `left` 条目，使 `flink-state-checked` 失败。
- 留下已停用的 `reader-<run>` 与核对身份的读授权。物理期限 75 分钟写在声明里，以往约 1 小时 45 分钟到 2 小时。

**清理方式**：测试删除删除流程、表与数据源，撤销并停用 `reader-<run>`，删除 MySQL 库；停用的身份与读授权随环境卸载删除。

#### UAT-ICE-04 带 Iceberg 元数据的删除

**对应需求**：#34；验收报告 [M4 开放层：Iceberg 兼容发布](../history/m4/iceberg.md)的“删除流程（补验）”。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：测试源与源登录。不用 Polaris、读端与 Kafka。执行机的身份能列仓库桶；`colvenna-data` 里没有别的个人数据声明。

**输入**：合成数据：MySQL 库 `icee_<run>` 的 40 笔订单，其中 3 笔属于删除的主体客户 42。

**步骤**：

1. 标签 `iceberg-erasure`：`tests/acceptance/iceberg/run-iceberg-erasure.sh ier<tag> <root>/iceberg-erasure`。
2. MySQL 库 `icee_<run>`：40 笔订单，其中 3 笔属于客户 42；数据源为 L3、个人数据、带主体键与 Iceberg 元数据。
3. 确认 S3 上已有 Iceberg 的 manifest 与 `metadata.json`。
4. LakeErasure 加主体 Secret；在 MySQL 删除这些行。
5. 每 20 s 查一次 Files 副本，直到干净（3600 s 内）；核对压缩任务的细节，确认没有早于这次压缩的 manifest 留下。
6. 等 `LogicallyErased`（1800 s 内）；清理。

**期望结果**：退出 0，`<root>/iceberg-erasure/verdicts.tsv` 的 7 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready` | `orders` Ready |
| `iceberg-metadata-written` | 表的 `metadata/` 下有 manifest 与 `metadata.json` |
| `erasure-counts-the-iceberg-manifests` | Files 副本的消息指明“in its Iceberg metadata” |
| `files-clean-iceberg-metadata-included` | Files 副本干净：`compactedSnapshot` 已设，不在压缩中 |
| `erasure-compaction-rewrites-the-iceberg-metadata` | 压缩任务的细节表明 manifest 已压缩、Iceberg 元数据重写 |
| `no-iceberg-manifest-from-before-the-erasure` | S3 上每个 manifest 都写于这次压缩之后 |
| `erasure-logical` | `LogicallyErased` |

**副作用与注意**：LakeErasure 没有范围字段，会从命名空间里的每个声明删除这个主体：压缩别的测试的个人数据表，删除其中客户 42 的行。所以不与 UAT-GOV-01、UAT-GOV-02、UAT-BI-01、UAT-CON-01 或其他删除流程同时运行。以往约 13 分钟，最坏约 1 小时 45 分钟。

**清理方式**：测试删除删除流程与数据源。

#### UAT-CON-01 控制台的受控操作与 CLI

**对应需求**：#138；验收报告 [M5 管理控制台：受控操作](../history/m5/operations.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：入口栈、Load Balancer Controller 与本提交的控制台（安装步骤 `entry-stack`、`lbc`、`console`）；本提交的 Operator chart（`colvenna-operator-waiver-approver` 校验含 `colvenna-lake.io/person`）；本提交的 `colvenna` CLI；测试源与源登录；EKS 审计日志开启；kubeconfig 的身份能以 `kubectl --as` 扮演用户；会话时长为默认的 28800 s。

**输入**：合成数据：MySQL 库 `ops_<run>`（50 个客户、500 笔订单）；4 个 Cognito 用户；每个操作以 `colvenna` 与控制台各做一次。

**步骤**：

1. 标签 `operations`：`tests/acceptance/operations/run-operations.sh ops<tag> <root>/operations`。
2. 建 Cognito 用户 `ops-<run>-{admin,op,bi,none}`；MySQL 库 `ops_<run>`（50 个客户、500 笔订单）；经 `colvenna apply` 声明 LakeSource `os-<run>`（`ods_ops_<run>`）与 LakeTable `owd-<run>`（`dwd_ops_<run>.orders`）。
3. 拒绝：没有角色、BI 角色、跨站请求、没有 Origin、另一个命名空间；核对 RBAC 与扮演范围。
4. 每个操作用 `colvenna` 做一次、在控制台做一次，以 `judge same` 比对：apply（及被拒的 apply）、check 加 waive（`rowCount` 下限 1e6，故意失败）、tag、compact、expire、orphans、暂停与恢复、回刷、删除与 forget（删除流程），后三者先取消一次再确认。
5. 控制台审计里有 21 个预期事件；集群审计记录被扮演的人；主体不出现在任何审计或证据里。
6. 删除全部：DWD 表在控制台删，数据源用 `colvenna` 删。

**期望结果**：退出 0，`<root>/operations/verdicts.tsv` 的 59 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dwd-running` | 流水线运行 |
| `refused-without-the-role` | （负向）没有会话：302 或 401；BI 角色、没有角色、operator 做 delete、forget、waive、跨站、没有 Origin、目标为 `kube-system`：都是 403 |
| `the-page-offers-each-role-its-own` | （负向）operator 的页面没有 delete、forget、waive；admin 的页面有 forget |
| `nothing-made-by-the-refused`、`nothing-deleted-by-the-refused` | （负向）被拒的请求没有建出任何 `-refused-<run>` 对象，DWD 仍在 |
| `the-console-impersonates-only-its-users` | （负向）控制台的服务账号只能扮演 `colvenna-console:admin` 与 `colvenna-console:operator`，不能扮演 `system:admin`、`kubernetes-admin`、`system:masters` 或服务账号 |
| `kubernetes-holds-the-roles` | （负向）Kubernetes 的授权与角色一致：operator 不能删表、建删除流程、建豁免、建 Secret，能 patch 表；admin 能删表、建 Secret，不能读 Secret、建 Pod |
| `apply-is-the-same`、`apply-refused-the-same` | `colvenna` 与控制台 apply 的 LakeQuality 相同；被拒时 API server 给出相同的拒绝 |
| `apply-leaves-confirmed-and-other-namespaces-out`、`nothing-applied-around-the-rules` | （负向）经 apply 提交回刷、以别名提交 expire、提交到另一个命名空间：都是 400，什么都没建 |
| `check-is-the-same`、`check-failed-the-rule` | 两种方式的检查相同；规则失败（`colvenna` 退出 3，控制台 200 200） |
| `waive-is-the-same`、`the-waivers-approver-is-the-person` | 两种方式的豁免相同，均被接受；审批人为 `colvenna-console:admin/ops-<run>-admin`，即具体的人 |
| `maintenance-submitted` | tag、compact、expire、orphans 都得到 200 |
| `tag-is-the-same`、`compact-is-the-same`、`expire-is-the-same`、`orphans-is-the-same` | CLI 与控制台建出的任务相同 |
| `expire-and-orphans-showed-their-effect` | 提交前的预览提到时间回溯受影响与“不能恢复” |
| `suspended-in-the-console`、`resumed-with-colvenna`、`suspended-with-colvenna`、`resumed-in-the-console`、`suspend-and-resume-answered` | 暂停与恢复两种方式都可用 |
| `backfill-shows-its-effect-and-a-cancel-changes-nothing`、`backfill-is-the-same` | 预览列出 DWD，取消后什么都没建；确认后两种方式相同 |
| `delete-shows-its-effect-and-a-cancel-changes-nothing`、`delete-is-the-same` | 取消后 generation 不变；确认后两种方式删除同一声明 |
| `forget-shows-its-effect-and-a-cancel-changes-nothing`、`forget-is-the-same`、`the-erasure-owns-its-subject`、`the-subject-is-the-same` | 删除流程的预览；取消后什么都不留；两种方式建出的 LakeErasure 相同，主体 Secret 归它所有，主体相同 |
| `audit-apply-ok-oq`、`audit-check-ok-ockc`、`audit-waive-ok-owc`、`audit-tag-ok-otgc`、`audit-compact-ok-ocpc`、`audit-expire-ok-oexc`、`audit-orphans-ok-oorc`、`audit-suspend-ok-owd`、`audit-resume-ok-owd`、`audit-backfill-ok-obfc`、`audit-delete-ok-oq`、`audit-forget-ok-oefc` | 控制台审计按人与资源记下每个成功的操作 |
| `audit-backfill-previewed-obfx`、`audit-forget-previewed-oefx` | 审计记下取消前的预览 |
| `audit-delete-refused-owd`、`audit-forget-refused-oe-refused`、`audit-waive-refused-ow-refused`、`audit-apply-refused-oq-cm`、`audit-apply-refused-obfa`、`audit-apply-refused-oexa`、`audit-apply-refused-ona` | （负向）审计记下每个被拒的操作 |
| `cluster-audit-names-the-person` | 集群审计里，控制台的服务账号扮演 `colvenna-console:admin` patch `lakewaivers/owc-<run>`，并带着这个人作为附加信息 |
| `the-subject-is-in-no-audit` | 主体（明文或 base64）不出现在控制台审计、集群审计与证据里 |
| `deleting-a-pipeline-declaration-each-way` | DWD 在控制台删除、数据源用 `colvenna` 删除，都不留 FlinkDeployment |

**副作用与注意**：清理时删除 `colvenna-data` 里名字以 `-<run>` 结尾的全部 lakewaiver、lakeerasure、laketask 与 lakequality。两个 LakeErasure 作用于整个命名空间（未分级的声明按个人数据处理），`forget-is-the-same` 比较相隔几分钟的两个删除流程，命名空间里的声明在其间变化就会失败。所以单独运行，也不与 UAT-ENT-01 同时运行。以往约 27 分钟，最坏超过 2 小时。

**清理方式**：测试退出时（成败都一样）删除 `colvenna-data` 里名字以 `-<run>` 结尾的 lakewaiver、lakeerasure、laketask 与 lakequality、它的 DWD 与数据源，以及 Cognito 用户 `ops-<run>-*`。

#### UAT-DSH-01 Doris 看板服务与查询隔离

**对应需求**：#32；验收报告 [M3 Doris 看板服务与查询负载隔离](../history/m3/dashboards.md)（报告里的 `load-within-m0` 现名 `load-within-targets`，只在 `PERF=1` 时运行）。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：平台 Doris（FE 的审计日志保留 5 天、catalog 回收站 86400 s）；测试源（测试会重跑 `sources.sh users`）；集群执行 NetworkPolicy；Doris Pod 名为 `colvenna-doris-be-0` 与 `colvenna-doris-fe-0`。测试自己建并标记 `colvenna-bi-client`。不设 `PERF`。

**输入**：合成数据：MySQL 库 `dash_<run>`，带心跳表，标记 Pod 每 0.1 s 写一笔订单；两个看板的 7 个查询与它们的用户；4 个即席用户的重查询。

**步骤**：

1. 标签 `dashboards`：`tests/acceptance/dashboards/run-dashboards.sh dsh<tag> <root>/dashboards`。
2. MySQL 库 `dash_<run>` 带心跳表与用户 `mark_<run>`；LakeSource `dash-<run>`、DWD、DWS；每 10 分钟对每张 ODS 表请求一次全量压缩。
3. 看板 `board-<run>`（7 个查询；用户 analyst、viewer、ops）与 `adhoc-<run>`（`maxConcurrency` 2，4 个用户）；检查访问、口令与 Doris 的网络隔离。
4. 标记 Pod 每 0.1 s 向 MySQL 写一笔订单，并轮询各路径；3 分钟后，4 个即席用户各开三个连接跑重查询，持续 3 分钟，同时采样工作组；之后等 2 分钟。
5. 恢复：删除 `colvenna-doris-be-0`，再删除 `colvenna-doris-fe-0`，按标记日志判定每条路径重新应答。
6. 停写；以 LakeDump 逐行比对副本；审计日志、Doris 的保留、压缩；删除看板，确认登录被拒、库、catalog 与工作组都已删除、token 被拒。

**期望结果**：退出 0，`<root>/dashboards/verdicts.tsv` 的 19 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dwd-running`、`dws-running` | 湖内声明运行 |
| `dashboard-ready` | 两个看板 Ready，5 个副本已刷新（900 s 内；失败时清理并退出） |
| `users-read-what-they-are-given` | analyst 的 5 个查询都成功；（负向）读 catalog、审计日志与另一个看板被拒，viewer、ops 与即席用户同样只读得到给自己的 |
| `system-schemas-withdrawn` | （负向）`information_schema.active_queries` 与 `mysql.user` 报错 |
| `passwords-required` | （负向）root、admin 空口令与 analyst 错误口令都登录不了 |
| `doris-isolated` | （负向）从 `colvenna-data` 两个 FE 端口都连不上；从 `colvenna-bi-client` 只连得上 9030 |
| `adhoc-bounded` | 采样到每个工作组同时运行的查询不超过 2，并出现排队 |
| `connections-bounded` | 每个即席用户的第三个连接被拒（`Reach_limit_of_connections`） |
| `recovered-be`、`recovered-fe` | BE、FE 被删后，每条路径重新以注入之后写入的数据应答、落后在稳态范围内，内表重写，都在 1800 s 内 |
| `consistent-region_totals`、`consistent-customers_hot`、`consistent-order_lookup` | Doris 的副本与 Paimon 的行逐行相同 |
| `queries-audited` | 审计日志记有每个用户在每条路径上的成功与被拒查询 |
| `doris-retention` | `dynamic_partition.start` 为 -6，`audit_log_delete_age` 5 天，`catalog_trash_expire_second` 86400，`trash_file_expire_time_sec` 0 |
| `ods-compacted` | 本运行的压缩任务有成功的，没有失败的 |
| `dashboard-deleted` | （负向）删除后用户登录被拒，库与工作组为 0，catalog 已删，两个看板的 token 都得到 401 |

**副作用与注意**：删除平台 Doris 的 BE 与 FE Pod，同时用 Doris 的测试都会中断，所以单独运行。期间 MySQL 持续写入，Doris 有标记与突发的查询。留下 `colvenna-bi-client`；中途失败时可能留下 LakeTask、MySQL 用户 `mark_<run>` 与 `colvenna-bi-client` 里的 Pod 和 Secret。不带性能部分时以往估计约 45–60 分钟。

**清理方式**：测试删除看板与数据源；`colvenna-bi-client` 留下。中途失败时可能留下本运行的 LakeTask、MySQL 用户 `mark_<run>` 与 `colvenna-bi-client` 里的 Pod、Secret，手动删除。

### 5.8 `console` 阶段

#### UAT-BI-01 BI 查询界面

**对应需求**：#136；验收报告 [M5 BI 查询界面](../history/m5/bi.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：入口栈、Load Balancer Controller、本提交的控制台与 BI（安装步骤 `console`、`bi`）；平台 Doris（审计插件开启）；指向 BI 的 Operator（`operator-again`，它发布 `colvenna-bi-doris-users`）；测试源与源登录；会话时长为默认的 28800 s。测试自己建 `colvenna-bi-client`。

**输入**：合成数据：MySQL 库 `bi_<run>` 的 30 笔订单（`note` 脱敏，带行过滤）；6 个 Cognito 用户。

**步骤**：

1. 标签 `bi`（与 `pipeline` 同时）：`tests/acceptance/bi/run-bi.sh bi<tag> <root>/bi`。
2. MySQL 库 `bi_<run>`（30 笔订单）；LakeSource `bs-<run>`（L3、个人数据、主体键、列标签，`note` 脱敏）进 `ods_bi_<run>`。LakeDashboard `bd-<run>`：用户 analyst、viewer、carol，以 `sso` 对应登录名 alice、bob、carol，带行过滤。Cognito 用户 `bi-<run>-{alice,bob,carol,dave}`（`colvenna-bi`）、`-erin`（`colvenna-operator`）、`-adm`（`colvenna-admin`）。
3. 没有会话访问 Superset；登录、角色、可见的库；其他登录途径被拒。
4. 读取与导出：SQL Lab 的读取经脱敏与过滤，与 carol 直接读 Doris 的结果比对；拒绝；浏览；数据集、图表与导出；保存的看板；没有结果后端。
5. 绕过：port-forward 到 BI 的 Pod；按人核对 Doris 审计。去掉 bob 的 `sso`，登出，删除看板，清理。

**期望结果**：退出 0，`<root>/bi/verdicts.tsv` 的 54 项都为 PASS（`signed-in-<用户>` 只在登录失败时出现）。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dashboard-ready` | `orders` Ready 且已治理；看板 Ready，`BiPublished`，1 个暴露 |
| `published-to-the-bi-interface` | BI 的 Secret 把 3 个 Doris 用户对应到 3 个登录名 |
| `no-session`、`no-session-sqllab`、`no-session-api-v1-database`、`no-session-api-v1-security-csrf_token`、`no-session-health`、`no-session-signed-out` | （负向）没有会话：302 到 Cognito |
| `one-sign-in-for-both` | 在控制台登录之后打开 BI，不再出现登录页 |
| `roles-dave`、`roles-adm` | 角色分别为 `ColvennaBI`、`Admin` |
| `roles-erin-refused` | （负向）`colvenna-operator` 组的用户在 BI 得到 403 |
| `databases-appear-for-alice`、`databases-bob`、`databases-carol` | 每人只看到库 `colvenna-data/bd-<run>` |
| `roles-alice`、`roles-bob`、`roles-carol` | 角色为 `ColvennaBI,Dashboard colvenna-data/bd-<run>` |
| `databases-dave-none` | （负向）看板没有给 dave 的用户：他看不到任何库 |
| `refused-api-v1-security-login`、`refused-api-v1-security-refresh`、`refused-login`、`refused-api-v1-security-guest_token` | （负向）Superset 自己的登录、刷新与 guest token 接口：404 |
| `api-token-refused` | （负向）带 Bearer token 的 API 请求：401 |
| `alice-reads-her-rows-masked`、`bob-reads-his-rows-masked` | 各 15 行，经过行过滤与脱敏 |
| `carol-reads-as-directly` | SQL Lab 读到的与 mysql 客户端直接读的相同（10 行） |
| `lake-table-refused`、`information-schema-refused`、`query-not-given-refused`、`writes-refused` | （负向）Doris 拒绝读湖表、读 `information_schema`、读没给自己的查询、写入 |
| `alice-copy-holds-her-rows` | `by_state` 为 `[['created', 15]]` |
| `browse-schemas`、`browse-tables`、`browse-columns` | 不经 `information_schema` 也能浏览库、表与列 |
| `dave-reaches-nothing` | （负向）dave 按 id 对 alice 的库执行 SQL 被拒 |
| `chart-shows-each-viewer-their-rows`、`export-is-the-viewers-rows`、`saved-dashboard-shows-each-viewer-their-rows` | 图表、导出与保存的看板对每个查看者只给他自己的行 |
| `chart-refuses-dave` | （负向）dave 打开图表被拒 |
| `sqllab-export-is-the-exporters` | （负向）bob 导出 alice 的查询，得不到 alice 的行 |
| `no-results-backend`、`reports-off` | `RESULTS_BACKEND_NOT_CONFIGURED`；报表接口 403 或 404 |
| `pod-unreachable-in-cluster` | （负向）集群内的 Pod 连不上 BI 的 Pod（curl 28） |
| `pod-refuses-forged-identity` | （负向）绕过负载均衡器直连 Pod：没有身份、伪造的身份都得到 401；健康检查 200 |
| `doris-audit-names-each-person` | Doris 审计里每个人用的是自己的 Doris 用户 |
| `no-other-doris-user-from-the-bi-interface` | （负向）从 Superset Pod 的地址来的没有别的 Doris 用户 |
| `bob-loses-the-database`、`bob-queries-refused-after`、`bob-sees-no-rows-of-the-dashboard-after` | （负向）去掉 bob 的 `sso` 后他看不到库，查询被拒，看板上没有他的行 |
| `logout-returns-to-sign-in` | 两步登出后回到登录页 |
| `credentials-withdrawn`、`alice-loses-the-database` | （负向）删除看板后 Secret 里不留条目，alice 也看不到库 |
| `signed-in-<用户>` | 只在失败时出现：该用户登录失败 |

**副作用与注意**：留下 `colvenna-bi-client`、Superset 元数据（alice 的数据集、图表与看板 `orders <run>`、自动创建的 Superset 用户）与审计行。它的 L3 个人数据源会被同时运行的删除流程覆盖。审计检查按 Superset Pod 的地址过滤，期间别人用 BI 会使它失败。不与 UAT-ENT-01（600 s 会话）或 UAT-DSH-01 同时运行。以往约 11 分钟。

**清理方式**：测试退出时删除看板、数据源与它的 Cognito 用户；Superset 元数据（数据集、图表、看板与自动建的 Superset 用户）与审计行留下，随环境卸载删除。

#### UAT-CON-02 控制台的流水线全景

**对应需求**：#137；验收报告 [M5 管理控制台：流水线全景](../history/m5/pipeline.md)。

**适用配置**：`--fixtures`；任一标准区域。

**前置条件**：入口栈、Load Balancer Controller 与本提交的控制台；本提交的监控（告警规则 `ColvennaRowsQuarantined`、`ColvennaLayerLagP99`、`ColvennaSourceStale`）；Marquez 与指向它的 Operator；平台 Doris；测试源；集群执行 NetworkPolicy；执行机的 AWS 身份能读 IAM 角色策略；会话时长为默认的 28800 s。

**输入**：合成数据：MySQL 库 `pipe_<run>`，带心跳表、写入 Pod 与一行标记；3 行 `lost` 状态的数据；4 个 Cognito 用户。

**步骤**：

1. 标签 `pipeline`（与 `bi` 同时）：`tests/acceptance/pipeline/run-pipeline.sh pip<tag> <root>/pipeline`。
2. Cognito 用户 `pipe-<run>-{admin,op,bi,none}`；没有会话与按角色的访问检查。
3. 流水线：MySQL 库 `pipe_<run>` 带心跳、写入 Pod 与一行标记；LakeSource `ps-<run>`；LakeTable `pwd-<run>`（DWD，带质量检查与隔离）与 `pws-<run>`（DWS）；LakeDashboard `pd-<run>`；LakeTask `pt-<run>`（`createTag`），等 DWS 有了第一个快照之后才建：没有快照的表上的 `createTag` 按设计以 `NoSnapshot` 结束（#171，UAT-TSK-01 的 `notag-*`），先等表提交过一次正是用户应有的做法（`4bc66c9f`），不是测试侧的绕行。
4. 把控制台的 `/api/pipeline` 与 kubectl、Prometheus（port-forward）、告警和 Marquez 比对；检查不泄漏、只读、控制台自己的身份、Prometheus 的网络策略。
5. 故障：3 行 `lost` 状态的数据进隔离；暂停 DWD；暂停数据源。每个故障在告警触发时被定位，告警解除时清除。
6. 审计；删除全部，控制台不再显示这些节点。

**期望结果**：退出 0，`<root>/pipeline/verdicts.tsv` 的 39 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `no-session-pipeline`、`no-session-api`、`no-session-node` | （负向）没有会话：302 到 Cognito |
| `sees-the-pipeline-admin`、`sees-the-pipeline-op` | 200，页面有 SVG 全景 |
| `refused-the-pipeline-bi`、`refused-the-pipeline-none` | （负向）BI 角色与没有角色：三个页面都是 403 |
| `source-ready`、`dwd-running`、`dws-running`、`dashboard-ready`、`task-ended` | 声明都在运行，任务已结束 |
| `path-source-to-dashboard` | 全景里有预期的 9 条边 |
| `state-is-kubectls` | 阶段、作业、积压、看板查询与任务都与 kubectl 一致 |
| `measures-are-prometheus` | 节点上的度量等于同一时刻的 PromQL 结果 |
| `alerts-are-prometheus` | 节点上的告警等于 Prometheus 的 `/alerts` |
| `lineage-is-marquezs` | 边等于 Marquez 命名空间 `colvenna-lake.colvenna-data` 里的作业 |
| `nothing-secret-or-of-the-data-is-served` | （负向）任何页面都不含 6 个命名空间里的 Secret 值、会话封印、cookie、源端身份或标记行 |
| `only-get` | （负向）POST、PUT、DELETE、PATCH 都得到 405 |
| `kubernetes-identity-only-reads`、`kubernetes-refuses-the-consoles-writes`、`the-write-did-not-happen` | （负向）控制台的服务账号只能在 `colvenna-data` 里 get、list 5 种资源；它的写入被拒，也确实没有发生 |
| `console-holds-no-catalog-identity`、`catalog-refuses-the-consoles-token` | （负向）控制台没有 catalog 的 Secret，所在命名空间不是 catalog 客户端；它的服务账号 token 在 catalog 得到 401 401 |
| `aws-identity-only-its-own-audit-and-sign-out` | （负向）控制台的 IRSA 角色只能写自己的审计日志组与调用 `AdminUserGlobalSignOut` |
| `prometheus-reads-only-from-the-consoles-namespace`、`prometheus-unreachable-without-the-labels` | （负向）从控制台的命名空间只能查询（200），管理接口 500、生命周期 403、远程写入 404，连不上 catalog；其他地方连不上 Prometheus（curl 28） |
| `quarantine-located`、`alerts-are-prometheus-during-a-fault` | `ColvennaRowsQuarantined` 触发（600 s 内）：DWD 标为有问题，下游受影响，上游正常；告警与 Prometheus 一致 |
| `layer-lag-located`、`alerts-are-prometheus-while-dwd-lags`、`layer-lag-cleared` | `ColvennaLayerLagP99` 在 900 s 内被定位；解除后 900 s 内清除 |
| `source-stale-located`、`alerts-are-prometheus-while-the-source-is-stale`、`source-stale-cleared` | `ColvennaSourceStale` 同上 |
| `nothing-marked-once-every-alert-resolves`、`alerts-are-prometheus-once-resolved` | 1800 s 内没有触发的告警，也没有被标记的节点 |
| `audit-has-who-looked-and-who-was-refused` | 审计记有 op 与 admin 的请求、bi 的拒绝与被拒的写入 |
| `gone-from-the-console` | 删除后控制台不再显示它的任何节点 |

**副作用与注意**：建了又删自己的对象、写入 Pod 与探测 Pod；在内存里读 6 个命名空间的全部 Secret 作泄漏比对。留下 Marquez 的作业与数据集、审计条目与告警历史。泄漏检查比对的是 `colvenna-data` 的全部 Secret 与展示整个 `colvenna-data` 流水线的页面，同时运行的测试可能造成误判。不与 UAT-ENT-01 同时运行。以往约 49 分钟，最坏约 3 小时。

**清理方式**：测试退出时删除全部声明、写入 Pod、探测 Pod 与 Cognito 用户；Marquez 的作业与数据集、控制台审计与告警历史留下。

### 5.9 `entry` 阶段

#### UAT-ENT-01 Web 入口、统一登录与会话

**对应需求**：#135；验收报告 [M5 Web 入口与统一登录](../history/m5/entry.md)。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：

- runner 先以 `entry-stack` 运行 `SESSION_TIMEOUT_SECONDS=600 deploy/scripts/entry/deploy-stack.sh`，把会话时长临时改为 600 s（测试在超过 900 s 时拒绝运行）；
- 本提交的控制台，`colvenna-data` 是 catalog 客户端（探测 Pod 期望 colvenna-catalog 回 401）；
- 执行机的 AWS 身份能读 CloudTrail、CloudWatch Logs、elbv2、eks、rds 与 s3control。

**输入**：10 个 Cognito 用户与它们的口令、TOTP；伪造的会话 cookie、授权码、ALB JWT 与身份头。

**步骤**：

1. 标签 `entry`：`tests/acceptance/entry/run-entry.sh ent<tag> <root>/entry`。
2. 建 10 个 Cognito 用户 `entry-<run>-{admin,op,bi,multi,none,out,off,idle,promo,late}`。
3. 未认证探测：每条路径都跳到 Cognito；HTTP 跳到 HTTPS；TLS 1.1；未知主机名；伪造的 cookie 与授权码；不能自助注册。
4. 每人以口令与 TOTP 登录：角色、错误口令与验证码、没有角色得到 403、客户端自带的身份头。绕过：集群内的探测 Pod；port-forward 到控制台的 Pod，带伪造的 ALB JWT。
5. 会话结束：登出、复制的 cookie、停用的用户、改组、token 过期后登出（`late`）、空闲超时（`idle`，每 20 s 查一次，最多到超时加 300 s）。
6. 审计：控制台日志组、Cognito 用户活动、CloudTrail、ALB 日志。暴露面：负载均衡器、监听器与规则、LoadBalancer/NodePort/Ingress、EKS 公网 CIDR、RDS 公网标志、S3 公共访问阻止。之后 runner 以 `entry-stack-back` 恢复默认会话时长。

**期望结果**：退出 0，`<root>/entry/verdicts.tsv` 的 50 项都为 PASS（`signed-in-<用户>` 只在登录失败时出现）。

| 检查 | 期望 |
| --- | --- |
| `no-session`、`no-session-api-me`、`no-session-healthz`、`no-session-static-console.css`、`no-session-logout`、`no-session-signed-out`、`no-session-nowhere` | （负向）没有会话：302 到 Cognito 的 `/oauth2/authorize`，不返回控制台内容 |
| `no-session-post-logout` | （负向）没有会话的 `POST /logout`：302 到登录 |
| `http-redirects-to-https` | （负向）HTTP 得到 `301` 跳到 443 |
| `legacy-tls-refused` | （负向）允许 TLS 1.1 的客户端（OpenSSL `SECLEVEL=0`）收到服务端的 `protocol version` 告警；443 监听器的策略只允许 TLSv1.2 与 TLSv1.3 |
| `unknown-host-gets-nothing` | （负向）别的主机名与负载均衡器的原始域名得到 `Not found` 404 |
| `forged-session-cookie` | （负向）随机的会话 cookie 被送去登录 |
| `forged-authorization-code` | （负向）伪造的授权码访问 `/oauth2/idpresponse`：401 |
| `no-self-sign-up` | （负向）注册页跳到 `/error` |
| `wrong-password-refused` | （负向）错误口令停在登录页 |
| `roles-admin`、`roles-op`、`roles-bi`、`roles-multi`、`roles-out`、`roles-off`、`roles-idle`、`roles-promo`、`roles-late` | 每人得到所在组对应的角色（`multi` 为 `admin,bi`） |
| `roles-none` | （负向）没有组的用户：`/api/me` 得到 403 |
| `wrong-mfa-code-refused` | （负向）错误的验证码停在 MFA 页 |
| `no-role-refused` | （负向）没有角色的用户得到 403“没有分配角色” |
| `client-identity-headers-not-trusted` | （负向）客户端自带的 `X-Amzn-Oidc-*` 头被 ALB 的替换，伪造的身份无效 |
| `pods-unreachable-in-cluster` | （负向）从 `colvenna-data` 连不上控制台的 Pod 与 Service（curl 28）；同一 Pod 访问 colvenna-catalog 得到 401 |
| `pod-refuses-forged-identity` | （负向）直连 Pod：没有 JWT、`alg none`、未知密钥签名都得到 401；健康检查 200 |
| `logout-returns-to-sign-in` | 经 `/signed-out` 与 Cognito `/logout` 回到登录页 |
| `logout-drops-the-session` | （负向）登出后原会话得到 302 |
| `copied-session-ends-after-logout`、`disabled-user-session-ends` | （负向）登出后复制的 cookie、停用用户的会话分别在 330 s 内失效 |
| `group-change-applies` | 改组在 330 s 内生效 |
| `session-times-out` | （负向）空闲会话在超时前 60 s 到超时后 120 s 之间结束 |
| `logout-after-the-token-expired` | 登录 300 s 以后（token 已过期）登出，仍回到登录页 |
| `logout-after-the-token-expired-drops-the-session` | （负向）这次登出也结束了会话 |
| `disabled-user-cannot-sign-in` | （负向）停用的用户登录被拒（disabled） |
| `audit-sign-ins-by-user`、`audit-requests-by-user`、`audit-no-role-refusal`、`audit-forged-refusals`、`audit-logout` | 控制台审计日志按人记下登录、请求、没有角色的拒绝、伪造的拒绝与登出 |
| `user-activity-sign-ins`、`user-activity-failed-sign-ins`、`disabled-sign-in-traced` | Cognito 用户活动记有 10 次 `Password:Success`、`Password:Failure` 与 `Mfa:Failure`；停用用户的登录尝试与 CloudTrail 对得上 |
| `alb-logs-refusals` | ALB 日志里有未转发给目标的 302 与 401 |
| `exposure-only-the-entry` | （负向）只有一个面向公网的负载均衡器：80 跳转，443 的规则先 `authenticate-cognito` 再转发；集群里没有 LoadBalancer、NodePort 或 Ingress |
| `exposure-other-rows` | （负向）EKS 公网 CIDR 都是 /32；RDS 都不公开；账号、仓库与日志桶都阻止公共访问 |
| `signed-in-<用户>` | 只在失败时出现：该用户登录失败 |

**副作用与注意**：退出时删除自己的 Cognito 用户（被 SIGKILL 时会留下）；建了又删 `colvenna-data` 里的探测 Pod。600 s 的会话时长使环境里所有控制台与 BI 会话 10 分钟后结束，所以本阶段单独运行，前后各重新部署一次入口栈。暴露面检查覆盖整个集群与 VPC。测试约 14 分钟，另加两次入口栈部署。

**清理方式**：测试退出时删除自己的 Cognito 用户（被 SIGKILL 时会留下，用 `deploy/scripts/entry/users.sh remove <用户名>` 删除）与探测 Pod；runner 随后以 `entry-stack-back` 恢复默认会话时长，这一步失败时手动运行 `deploy/scripts/entry/deploy-stack.sh`。

### 5.10 部署运维

部署运维的用例检查 `deploy/install.sh` 与 `deploy/uninstall.sh` 本身：从零安装、只装平台、重复执行、中断后续装、配置拒绝、原地升级、卸载，以及按发布清单安装；另有元数据从 RDS 备份恢复。其中 UAT-DEP-03、04、05、08 在 `deploy` 阶段运行，UAT-DEP-06 是 `prep` 阶段的重装；UAT-DEP-01、02、07、09 由运维在 UAT 前后执行（第 3.2 节"运维执行的用例"），证据放进同一个证据目录。判定脚本在 `tests/acceptance/deploy/`：`inventory.sh` 记下一套环境（或整个账号）的组成，`deploy_checks.py` 比较两次记录与安装证据。

#### UAT-DEP-01 从零安装

**对应需求**：#162（安装部署手册，从零新建环境）、#168（任一标准区域）、#203；[安装部署手册](../manual/install.md)第 3、4 节。

**适用配置**：`--fixtures`（只装平台的从零安装见 UAT-DEP-02）；一套从零新建的环境，任一标准区域。每个区域都要有 UAT-DEP-01 或 UAT-DEP-02 之一（第 7 节）。

**前置条件**：账号里没有这个环境名的任何资源（`install.sh` 自己核对，见 UAT-DEP-05）；执行机满足安装部署手册第 2 节；安装会跨过 00:00 UTC 时，先去掉执行机的 `Patch Group` 标签。

**输入**：本提交的源码，或 `--release` 时候选的清单与发布镜像仓库里的镜像；环境名、区域与 `--fixtures`。不用业务数据。

**步骤**（运维执行，不在 `run-uat.sh` 里）：

1. 运行 `COLVENNA_ENV=<env> COLVENNA_REGION=<区域> deploy/install.sh --fixtures`；按发布清单安装时在候选的 tag 上另加 `--release <候选>`（UAT-DEP-08）。证据在 `deploy/.state/<env>/install-<UTC 时间>/`。中途失败时修好原因，用 `--from <步骤>` 接着装，几段证据合起来算一次安装。
2. 装完运行 `COLVENNA_ENV=<env> tests/acceptance/deploy/check-install.sh <root> --fresh <安装证据目录>...`（标签 `install`），再把安装证据目录复制到 `<root>/install/`。

**期望结果**：`install.sh` 以 0 退出；`check-install.sh` 以 0 退出，`<root>/install/verdicts.tsv` 的 5 项都为 PASS。判定脚本自身出错（读不到证据、记录不了环境的组成）时另有 `judge-<检查>`、`inventory` 或 `steps-listed` 一行 FAIL，不会默默通过。

| 检查 | 期望 |
| --- | --- |
| `every-step-done` | 本提交 `install.sh --list` 的每一步都是 `ok` |
| `verify-passed` | `verify` 一步 `ok`：健康检查没有 FAIL，14 项访问检查都符合预期，console 与 bi 都把没有会话的请求 302 到本环境的 Cognito 登录 |
| `install-finished` | `install.log` 最后一行是 `Installed <env> at <提交>` |
| `installed-from-nothing` | 本环境的每个栈都创建于这次安装开始之后 |
| `no-upstream-images` | 每个 Deployment、StatefulSet、DaemonSet 与每个没有结束的 Pod（`Succeeded`、`Failed` 的不算：它们不再运行）的每个容器，镜像都在本环境自己的仓库 `colvenna-lake-<env>/…` 里，第三方镜像也是 `colvenna-lake-<env>/third-party` 的副本；同一注册表里的别的仓库（直接拉发布仓库的 `colvenna-lake-release/…`、别的环境的）也算上游。只有 `kube-system`、`amazon-guardduty` 里 AWS 自己的镜像（托管插件、GuardDuty 的 agent）例外。判定的说明只写仓库路径，不写注册表地址（地址里有账号 ID）。`inventory.sh` 少记了工作负载或 Pod，这项就是 FAIL |

另外记录、不作判定：每一步的秒数（`steps.tsv`）；`doris.log` 里 `Doris colvenna-doris BE:` 一行（等 BE 时给 DorisCluster 打注解的次数，见 UAT-CAT-03）；节点上的 `Patch Group` 标签。

**副作用与注意**：新建一整套环境，费用见[成本估算](../manual/cost.md)。以往约 77–105 分钟（[安装部署手册](../manual/install.md)第 10 节）。

**清理方式**：环境留给后面的用例；UAT 之后按 UAT-DEP-07 卸载。

#### UAT-DEP-02 只装平台

**对应需求**：#205、#203；[安装部署手册](../manual/install.md)第 1、3 节（不带 `--fixtures` 是默认的安装方式）。

**适用配置**：只装平台；一套从零新建的环境，任一标准区域。每个区域都要有 UAT-DEP-01 或 UAT-DEP-02 之一（第 7 节）。

**前置条件**：同 UAT-DEP-01。

**输入**：同 UAT-DEP-01，不带 `--fixtures`。

**步骤**（运维执行）：

1. 运行 `COLVENNA_ENV=<env> COLVENNA_REGION=<区域> deploy/install.sh`（不带 `--fixtures`；按发布清单安装时另加 `--release <候选>`）。
2. 运行 `COLVENNA_ENV=<env> tests/acceptance/deploy/check-install.sh <root> --fresh --platform <安装证据目录>...`（标签 `install-platform`）。

**期望结果**：两者都以 0 退出，`<root>/install-platform/verdicts.tsv` 的 7 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `every-step-done` | `sources`、`strimzi`、`source-logins`、`readers` 四步为 `skipped (no --fixtures)`，其余都是 `ok` |
| `fixture-steps-skipped` | 上面四步确实跳过 |
| `verify-passed`、`install-finished`、`installed-from-nothing`、`no-upstream-images` | 同 UAT-DEP-01 |
| `no-fixture-resources` | 没有测试源栈（`ColvennaLake<Env>TestMysql`、`TestPostgres`），也没有它们建的 Secrets Manager 密钥；受管命名空间里没有测试源的登录（`mysql-cdc`、`postgres-cdc`、`mysql-inspect`、`mysql-beat`）；没有 Strimzi 的 release、它的 CRD 与命名空间 `colvenna-lake-kafka`，那里也没有工作负载；Spark 读端的仓库里没有镜像，本地状态里也没有读端镜像的记录（`readers-*.json`）。`inventory.sh` 少记了其中任何一种，这项就是 FAIL |

**副作用与注意**：只装平台的环境里只能运行适用配置含"只装平台"的用例。

**清理方式**：按 UAT-DEP-07 卸载。

#### UAT-DEP-03 重复执行

**对应需求**：#162（每一步可重复执行）、#203；[安装部署手册](../manual/install.md)第 3、7 节。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：环境已在本提交，或在只在 `release/` 下与本提交不同的提交上装好（`prep` 阶段之后；例如以源码装在候选的镜像构建所在的提交上，再在候选的 tag 上按清单重装）；没有其他测试在运行。

**输入**：已装好的环境；同一提交（或同一产品）与同样的配置（有测试源栈时带 `--fixtures`，以及 `UAT_INSTALL_ARGS`）。

**步骤**：

1. 标签 `deploy-repeat`：`tests/acceptance/deploy/run-reinstall.sh rep<tag> <root>/deploy-repeat`。
2. 记下环境的组成：栈与每个资源的物理 id、节点、Helm release、工作负载的镜像与提交注解、卷、本环境各 ECR 仓库的镜像数。
3. 按环境原来的配置（有测试源栈时带 `--fixtures`）在同一提交上再运行一次 `install.sh`，再记一次组成，比较两次。

**期望结果**：退出 0，`<root>/deploy-repeat/kind` 为 `repeat`，`verdicts.tsv` 的 11 项都为 PASS（只装平台的环境另有 `fixture-steps-skipped`，共 12 项）。判定脚本自身出错时另有 `judge-<检查>` 或 `inventory-<before|after>` 一行 FAIL。

| 检查 | 期望 |
| --- | --- |
| `install-exit` | 这次 `install.sh` 以 0 退出 |
| `every-step-done`、`verify-passed`、`install-finished` | 同 UAT-DEP-01 |
| `no-upstream-images` | 同 UAT-DEP-01，看重装之后的组成 |
| `nothing-made-or-replaced` | 栈相同，每个栈的资源与物理 id 都不变：没有新建，也没有替换 |
| `same-nodes` | 节点还是那几台实例 |
| `releases-converged` | Helm release 相同，chart 与版本不变，都是 `deployed` |
| `same-workloads` | 每个 Deployment、StatefulSet、DaemonSet 的镜像与提交注解不变；环境原先装在只在 `release/` 下不同的提交上时，提交注解从那个提交换成本提交，算不变 |
| `same-volumes` | 卷相同 |
| `no-image-built` | 本环境各 ECR 仓库的镜像数不变：没有重新构建 |

**副作用与注意**：每一步都重新收敛一遍，约 35 分钟（2026-10-20 的同类重装 2085 秒）。期间不要运行其他测试。

**清理方式**：不需要清理。

#### UAT-DEP-04 中断后续装

**对应需求**：#162（`--from` 续装）、#203；[安装部署手册](../manual/install.md)第 3 节。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：环境已在本提交装好；没有其他测试在运行。

**输入**：已装好的环境；中断的步骤（默认 `operator-again`）与信号（默认 `SIGHUP`）。

**步骤**：

1. 标签 `deploy-resume`：`tests/acceptance/deploy/run-resume.sh res<tag> <root>/deploy-resume`。第三个参数可以换一个要中断的步骤：`operator-again`（默认）、`operator`、`catalog`、`monitoring`、`console` 或 `bi`。
2. 在一个独立的会话里运行 `install.sh [--fixtures] --from operator-again`。等它的 `helm upgrade --install colvenna-operator` 把 release 记成 `pending-*`、Helm 仍在运行时，给整个会话发 `SIGHUP`，模拟执行机的 SSH 连接断开（`RESUME_SIGNAL=INT` 是 Ctrl-C，`KILL` 是执行机宕机）。升级在被看到 `pending-*` 之前就结束时，什么也没有被打断，`interrupted` 判为 FAIL，要重跑本用例，不能算通过。
3. 记下 release 的状态与历史，再运行一次 `install.sh [--fixtures] --from operator-again`。

**期望结果**：退出 0，`verdicts.tsv` 的 3 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `interrupted` | 被中断的运行以非零退出，那一步没有记为 `ok`；细节记下 release 此时的状态 |
| `resumed` | 续装以 0 退出，从那一步到 `verify` 的每一步都完成 |
| `release-deployed` | release 为 `deployed`，版本号比中断之前的大 |

**副作用与注意**：Operator 重启一到两次。约 10 分钟。2026-10-22 在 `uat` 上实测：`SIGHUP` 让 Helm 来不及收尾，release 停在 `pending-upgrade`，续装的 `helm upgrade` 报 `another operation (install/upgrade/rollback) is in progress`，`resumed` 与 `release-deployed` 失败。这是安装脚本的缺陷（各步骤的 `helm upgrade --install` 不处理上次中断留下的 `pending-*` 状态，#214），修好之前本用例失败。

**清理方式**：续装本身就是恢复。`release-deployed` 失败、release 停在 `pending-upgrade` 时，用 `helm -n colvenna-system rollback colvenna-operator <最后一个 deployed 的版本>` 放开它，再运行 `install.sh --from operator-again`。

#### UAT-DEP-05 配置拒绝

**对应需求**：#164（环境名）、#168（区域）、#203；[安装部署手册](../manual/install.md)第 1、8、9 节。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：被测环境已装好、它的区域已记下（它的名字作"被占用的名字"）；执行机能访问 Git 仓库对象（测试在临时克隆里运行一部分拒绝）。

**输入**：被测环境的名字、区域与记下的设置（账号、托管区、入口域名）；测试自己的名字 `zr<运行 id 前 6 位>`；不合规则或保留的名字（`Uat`、`1abc`、`abcdefghi`、`m1`、`poc`）与不支持的区域（不给、`cn-north-1`、`cn-northwest-1`、`us-gov-west-1`、`uswest2`、没有端点的 `us-fake-1`、一个账号没有开通的区域）；不合格的设置（不给或格式不对的账号、另一个账号 `111122223333`、不给或格式不对的托管区、账号里没有的托管区、名字不符的托管区、托管区之外或就是托管区或含大写的入口域名、被测环境的入口域名或其下的域名、没有 Doris 镜像或不是 ECR 仓库的 `DORIS_IMAGE_SOURCE`）；替身 `aws` 对一次只读调用给出的假答复（托管区是私有区、区域没有基础栈的 Kubernetes 版本、没有可用区提供两种节点类型、只有一个可用区提供、VPC 配额为 0、弹性 IP 配额为 0）；一套可以丢掉的真实环境（`UNINSTALL_PROBE_ENV`）。

**步骤**：

1. 标签 `deploy-refusals`：`tests/acceptance/deploy/run-refusals.sh ref<tag> <root>/deploy-refusals`。
2. 逐个运行会被拒绝的命令，每个都要以非零退出、给出它的提示，并且不记下任何设置（区域、账号、托管区、入口域名）、不开始安装证据。用的名字是测试自己的 `zr<运行 id 前 6 位>`，它在账号里什么都没有；只有第 4 步用一套真实但可以丢掉的环境的名字。
3. 为了不让一个失效的拒绝真的建出东西：能用不存在的区域 `us-fake-1` 的都用它；`install.sh` 只给 `--only tools`（只装本机的工具）；`npm`、`npx`、`cdk`、`helm`、`kubectl` 换成一定失败的替身；`uninstall.sh` 只给测试自己的名字，不给被测环境的名字。
4. 再以一套真实存在、本来就要拆掉的环境（`UNINSTALL_PROBE_ENV`，默认 `plat` 与 `uate2` 中先找到的那个，不能是被测环境；不要给要保留的环境）在它没有栈的那个区域运行 `uninstall.sh`：在没有它区域记录的临时克隆里运行，拒绝的原因必须是“这个区域里没有它的栈”，而不是名字没见过。这样的环境不存在时这一项为 FAIL。
5. 设置与能力（#212）：以测试自己的名字、在被测环境的区域里，逐项给不合格的账号、托管区与入口域名；再在被测环境自己的工作区里给与记下的不同的账号、托管区与入口域名。区域放不下一套环境的几种情形（`deploy/scripts/lib/preflight.sh`），由排在 `PATH` 最前的替身 `aws` 对那一次只读调用给出假答复，其余调用照常转给真的 AWS CLI。各次运行沿用被测环境的账号与托管区，使测试的名字能走到后面的检查；入口域名不沿用。
6. 最后核对账号：测试的名字在被测环境的区域与另一个区域都没有栈、没有 IAM 角色；被测环境记下的设置不变，它的栈没有被更新；用来试的那套环境的栈也没有变。

**期望结果**：退出 0，`verdicts.tsv` 的 49 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `name-uppercase`、`name-digit-first`、`name-too-long` | 环境名不合规则（大写、数字开头、超过 8 个字符）：`COLVENNA_ENV=<env>: a lowercase letter, …` |
| `name-reserved-m1`、`name-reserved-poc` | 保留名：`COLVENNA_ENV … is reserved` |
| `region-missing`、`region-china`、`region-china-northwest`、`region-govcloud`、`region-malformed` | 没给区域、中国区（`cn-north-1`、`cn-northwest-1`）、GovCloud（`us-gov-west-1`）、不是区域名：`COLVENNA_REGION=<region>: … standard AWS region` |
| `region-unreachable` | 形式合法、没有端点的区域：第一项检查（凭证属于哪个账号）就读不到，`Cannot read the caller's account in us-fake-1` |
| `region-not-enabled` | 账号没有开通的区域（取 `describe-regions` 里第一个 `not-opted-in` 的）：`Cannot read the caller's account in <区域>` |
| `name-held-by-another-region` | 在另一个区域用被测环境的名字（临时克隆里，没有区域记录）：`Environment <env> is not in <区域>, yet N IAM roles of its name exist` |
| `region-not-the-recorded-one` | 被测环境的工作区里给另一个区域：`Environment <env> is in <区域>, not <另一个>` |
| `step-unknown-only`、`step-unknown-from`、`option-unknown` | 不存在的步骤与选项：`No step nosuch`；打印用法 |
| `tree-dirty` | 工作区有未提交的改动：`Commit or remove local changes first` |
| `foundation-without-region` | 区域没记下时单独运行 `foundation/deploy.sh`：`No region recorded for …` |
| `uninstall-unconfirmed`、`uninstall-other-name` | `uninstall.sh` 不带 `--yes <env>` 或名字不对：打印用法 |
| `uninstall-region-holds-nothing` | 所给区域里没有这个名字的栈、也没有开始过的卸载：`No stack of … in …, and no uninstall begun` |
| `account-missing`、`account-malformed` | 新名字没有给账号，或不是 12 位数字：`COLVENNA_ACCOUNT=<account>: the 12-digit AWS account …` |
| `account-not-the-callers`、`uninstall-account-not-the-callers` | `install.sh`、`uninstall.sh` 给的账号不是当前凭证的：`The credentials are of AWS account <账号>, not <另一个>` |
| `zone-missing`、`zone-malformed` | 没有给托管区，或 ID 不是 `Z…`：`COLVENNA_HOSTED_ZONE_ID=<Z...> COLVENNA_HOSTED_ZONE_NAME=<name>: …` |
| `zone-not-in-account` | 账号里没有这个托管区：`Cannot read hosted zone Z00000000NOSUCHZONE0 in account …` |
| `zone-name-mismatch` | 托管区的名字不符：`Hosted zone <ID> is <名字>, not COLVENNA_HOSTED_ZONE_NAME other-<名字>` |
| `zone-private` | 替身答复被测环境的托管区是私有区：`Hosted zone <ID> is private` |
| `entry-domain-outside-the-zone`、`entry-domain-is-the-zone`、`entry-domain-upper-case` | 入口域名在托管区之外、就是托管区本身、含大写：`COLVENNA_ENTRY_DOMAIN=…: a lowercase domain within the zone …` |
| `entry-domain-not-free`、`entry-domain-under-another-entry` | 新名字用被测环境的入口域名（托管区里已有它的记录）、或其下的域名：`COLVENNA_ENTRY_DOMAIN … is not free: hosted zone … has N records at or under it`、`… is under <域名>, another environment's entry` |
| `doris-source-without-the-images`、`doris-source-malformed` | 从源码安装（只跑 `doris-image`），`DORIS_IMAGE_SOURCE` 是没有这两个 digest 的仓库（被测环境的 `colvenna-catalog`），或不是 ECR 仓库：`… does not hold the query engine's FE …`、`DORIS_IMAGE_SOURCE must be an ECR repository in a standard region` |
| `recorded-account-changed`、`recorded-zone-changed`、`recorded-entry-domain-changed` | 被测环境的工作区里给与记下的不同的账号、托管区、入口域名：`Environment <env> has COLVENNA_…=<记下的>, not <给的>` |
| `kubernetes-version-missing` | 替身答复区域里没有 `foundation-spec.json` 的 Kubernetes 版本：`EKS in <区域> does not offer Kubernetes <版本>` |
| `node-types-missing`、`node-types-in-one-zone` | 替身答复没有可用区、或只有一个可用区同时提供两种节点类型：`No two availability zones of <区域> offer both node types, …: 0 of N do`（或 `1 of N`） |
| `vpc-quota-full`、`address-quota-full` | 替身答复 VPC、弹性 IP 的配额为 0：`<区域> has N VPCs of its quota of 0 (vpc L-F678F1CE …`、`… Elastic IPs of its quota of 0 (ec2 L-0263D0A3 …` |
| `uninstall-real-env-wrong-region` | 一套真实环境在它没有栈的区域卸载：同样被拒；它在自己区域的栈不变（变了另记 `uninstall-real-env-untouched` 为 FAIL） |
| `nothing-made` | 测试的名字在两个区域都没有栈、没有 IAM 角色，临时克隆里没有记下任何设置 |
| `environment-untouched` | 被测环境记下的设置（区域、账号、托管区、入口域名）不变，两个区域里它的栈都没有更新 |

**副作用与注意**：只读；约 1 分钟。`uninstall.sh` 的"给错区域"先用测试自己的名字测，再用一套本来就要拆掉的真实环境测，从不用被测环境：检查一旦失效，`uninstall.sh` 会删掉所给环境的全局 DNS 记录与别的资源，所以只在 `UNINSTALL_PROBE_ENV` 给的、可以丢掉的环境上测。

**清理方式**：临时克隆在退出时删除；不需要别的清理。

#### UAT-DEP-06 原地升级

**对应需求**：#162、#203；[安装部署手册](../manual/install.md)第 7 节。开发阶段不保证跨版本兼容，本用例只验证在新提交上重跑 `install.sh` 能就地升级、保留数据。

**适用配置**：只装平台或 `--fixtures`；被升级的那套环境所在的区域（本次 Release 是 `us-west-2` 的 `uat`）。

**前置条件**：环境装在更早的提交上，且在技术标识改名（#271）之后：之前装的环境是产品旧名字的，不能升级过来（[安装部署手册](../manual/install.md)第 7 节）。`v1.0.0-rc.2` 的这次运行跨过了查询引擎镜像改名（#258），给了 `DORIS_IMAGE_SOURCE`，见 rc.2 的 UAT 报告。

**输入**：装在更早提交上的环境；本提交；跨过改名时另有 `DORIS_IMAGE_SOURCE`。

**步骤**：

1. 标签 `reinstall`（`prep` 阶段）：`tests/acceptance/deploy/run-reinstall.sh rei<tag> <root>/reinstall`。colvenna-catalog 的 Pod 注解的提交与本提交不同，判为升级。
2. 记下环境的组成，在本提交上运行 `install.sh [--fixtures]`，再记一次，比较。

**期望结果**：退出 0，`<root>/reinstall/kind` 为 `upgrade`，`verdicts.tsv` 的 10 项都为 PASS（只装平台的环境另有 `fixture-steps-skipped`，共 11 项；出错时的 `judge-*` 行同 UAT-DEP-03）。同一阶段里环境若已装在本提交上（例如刚从零装好），或装在只在 `release/` 下与本提交不同的提交上（发布候选的 tag 与它的镜像构建所在的提交，产品相同），`kind` 为 `repeat`，按 UAT-DEP-03 判定，不计入本用例。

| 检查 | 期望 |
| --- | --- |
| `install-exit` | 这次 `install.sh` 以 0 退出 |
| `every-step-done`、`verify-passed`、`install-finished` | 同 UAT-DEP-01 |
| `no-upstream-images` | 同 UAT-DEP-01，看升级之后的组成：更早的提交装的开发用 Kafka，`strimzi` 一步已把它换到本环境的副本 |
| `same-stacks` | 栈相同：就地更新，没有新建或删除栈 |
| `data-kept` | 有状态的资源（RDS 实例、S3 桶、Cognito 用户池、ECR 仓库、密钥、VPC、EKS 集群）物理 id 都不变 |
| `volumes-kept` | 升级前的卷都还在 |
| `components-at-the-commit` | 带提交注解的组件（colvenna-catalog、Operator、Polaris、Marquez）都注解为本提交 |
| `images-changed` | 至少有组件换上了新镜像 |

**副作用与注意**：组件按新提交逐个重启，Kubernetes 小版本变化时节点组逐台替换（安装部署手册第 7 节）。以往约 35 分钟。

**清理方式**：不需要清理；升级不能回退（开发阶段不保证兼容）。

#### UAT-DEP-07 卸载

**对应需求**：#162、#168、#203；[安装部署手册](../manual/install.md)第 8 节。

**适用配置**：只装平台或 `--fixtures`；每个区域一次，卸载本区域从零新建、验收完的那套环境（第 7 节）。

**前置条件**：这套环境的证据都已保存；卸载期间不部署、不测试别的环境（`others-untouched` 比较别的环境的栈的最后更新时间与它们的资源）。

**输入**：验收完、要拆掉的环境；卸载之前的账号清单。

**步骤**（运维执行）：

1. 卸载之前：`COLVENNA_ENV=<env> tests/acceptance/deploy/inventory.sh <root>/uninstall-before --account`，记下账号里 `uninstall.sh` 按名字删除的一切：环境所在的区域与默认的 `us-west-2`、`us-east-2` 各自的 `ColvennaLake` 栈、`colvenna-lake` 开头的 ECR 仓库（发布镜像仓库也在内）、数据库与它们的手动快照、用户池、密钥、日志组与各集群的 EBS 卷；整个账号的 IAM 角色、OIDC provider、桶与入口的 DNS 记录；以及本环境的集群名与它的 OIDC issuer。
2. `COLVENNA_ENV=<env> deploy/uninstall.sh --yes <env>`。
3. `COLVENNA_ENV=<env> tests/acceptance/deploy/check-uninstall.sh <root> <root>/uninstall-before <卸载证据目录>`（标签 `uninstall`）。

**期望结果**：`uninstall.sh` 与 `check-uninstall.sh` 都以 0 退出，`<root>/uninstall/verdicts.tsv` 的 4 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `uninstall-finished` | `uninstall.log` 最后一行是 `nothing of <env> is left in the account`，`leftovers.txt` 为空 |
| `settings-record-gone` | 本地状态里记下的设置（`region`、`account`、`hosted-zone-id`、`hosted-zone-name`、`entry-domain`）都已删除，这个名字可以在任何区域再用 |
| `nothing-of-it-left` | 账号里没有这个环境的上面任何一种资源：按它的 7 个栈名（与 CloudFormation 由它们生成的名字）、`colvenna-lake-<env>-`、`colvenna-lake-<env>/`、`.colvenna-<env>.`、它的集群名与 OIDC issuer 识别，名字相近的别的环境（例如 `uat` 与 `uate2`）不会混淆 |
| `others-untouched` | 别的环境的这些资源（栈的状态与最后更新时间在内）与发布镜像仓库 `colvenna-lake-release/*` 都与卸载之前相同 |

**副作用与注意**：卸载删除这套环境的一切，数据也在内，包括 RDS 的最终快照（安装部署手册第 8 节）；要保留数据，先按手册备份再卸载。以往约 49 分钟。

**清理方式**：卸载就是清理；`uninstall.sh` 中途失败时直接重跑。

#### UAT-DEP-08 按发布清单安装

**对应需求**：#201（发布清单）、#202（按清单安装固定发行物）、#203。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。

**前置条件**：发布候选的 tag 与它的清单 `release/<候选>/manifest.json`，清单里的镜像（含第三方镜像）在发布镜像仓库里；`COLVENNA_RELEASE_REGISTRY` 指向这个仓库（清单不写仓库地址与账号）。

**输入**：候选的 tag、`release/<候选>/manifest.json` 与发布镜像仓库里的镜像；不全、digest 不存在或不符、提交不符的清单（由拒绝测试自己生成）。

**步骤**：

1. 在候选的 tag 上运行 `deploy/install.sh [--fixtures] --release <候选>`：从零新建的环境就是 UAT-DEP-01（或 UAT-DEP-02）那次安装；升级的环境（本次 `us-west-2` 的 `uat`，先以源码装在候选的镜像构建所在的提交上，即 UAT-DEP-06，并由它生成清单）是 `deploy` 阶段 `deploy-repeat` 那次按清单的重装（`UAT_INSTALL_ARGS='--release <候选>'`，产品相同，按 UAT-DEP-03 判定）。
2. 标签 `deploy-release-refusals`：`tests/acceptance/deploy/run-release-refusals.sh rrf<tag> <root>/deploy-release-refusals`。候选取自 `UAT_INSTALL_ARGS` 的 `--release <候选>`。每种情形在本提交的一个临时工作区里只改一处并提交，对一个不存在的环境（名字取自运行 id）以 `--only verify` 运行 `install.sh --release`。

**期望结果**：安装以 0 退出，开始时打印目标账号、区域、集群与要装的版本，不在安装时构建镜像，运行中的产品镜像与清单的 digest 相同，第三方镜像都从本环境的 `colvenna-lake-<env>/third-party` 运行（那次安装的 `check-install.sh` 或 `deploy-repeat` 的 `no-upstream-images`）；`deploy-release-refusals/verdicts.tsv` 的 14 项都为 PASS：

| 检查 | 期望 |
| --- | --- |
| `control` | 对照：`check-release.sh` 接受原样的候选（带 `--fixtures`） |
| `no-registry` | 没有给 `COLVENNA_RELEASE_REGISTRY`：拒绝 |
| `no-manifest` | 检出里没有这个候选的清单：拒绝 |
| `other-candidate` | 清单是另一个候选的：拒绝 |
| `incomplete` | 清单少一个镜像（`images.bi`）：拒绝 |
| `unknown-digest` | 某个镜像的 digest 在发布仓库里不在它的 tag 下：拒绝 |
| `swapped-digests` | 两个镜像的 digest 对调（各自都在仓库里，但不在清单固定的位置）：拒绝 |
| `doris-not-pinned` | Doris 的 digest 与 `lib/common.sh` 固定的不同：拒绝 |
| `third-party-not-pinned` | 某个第三方镜像的 digest 与 `deploy/images/third-party.tsv` 固定的不同：拒绝 |
| `cli-binaries` | 某个 CLI 二进制的 SHA-256 与发布的 `colvenna-cli` 镜像里的不同：拒绝 |
| `tree-differs` | 检出与镜像构建所在的提交在 `release/` 之外有不同：拒绝 |
| `uncommitted` | 有未提交的改动：拒绝 |
| `other-mode` | 同一提交上已经从源码装过的环境（`install-mode` 记录）：拒绝 |
| `nothing-made` | 前后账号里都没有这个环境的栈，临时工作区里也没有记下区域 |

每项拒绝都发生在记下区域、改动任何资源之前；拒绝失效时，`--only verify` 会因为环境不存在而失败，不会建出东西。

**副作用与注意**：只在 tag 的检出上安装；主干上更晚的提交被拒绝。

**清理方式**：同 UAT-DEP-01（安装）与 UAT-DEP-05（拒绝）。

#### UAT-DEP-09 元数据恢复

**对应需求**：#205（维护与恢复）；[安装部署手册](../manual/install.md)第 11.2、11.3、11.6 节。

**适用配置**：只装平台或 `--fixtures`；任一标准区域。不需要测试源：表由 catalog 的 Flink 流程自己建。

**前置条件**：环境里没有别的测试在运行，也没有别的写入方：恢复替换整个 `colvenna_catalog` 库。按时间点恢复要求项目 RDS 的自动备份已覆盖恢复点（实例建好之后约 2 分钟有第一次备份；脚本等到 RDS 最近能恢复到的时刻越过恢复点）。执行机有 mvn 与 JDK。

**输入**：三张由 `CommitStress` 写入的追加表（t1 10 次提交、t2 5 次、gone 3 次）、恢复点之后的 1 次 `WriteProbe` 提交与一张新表 t3（5 次）；项目 RDS 的一个手动快照（`snapshot`）或恢复点那一秒（`time`）。不用业务数据。

**步骤**（运维执行，不在 `run-uat.sh` 里）：

`COLVENNA_ENV=<env> tests/acceptance/deploy/run-metadata-restore.sh <root>`（标签 `metadata-restore`，一次运行）。它先以 `tests/acceptance/catalog/run-rds-restore.sh mr<tag> <root>/metadata-restore/snapshot snapshot` 从快照演练，再以 `… mt<tag> <root>/metadata-restore/time time` 按时间点演练；前一次失败，后一次照样运行。每次演练：

1. 恢复点之前写 t1、t2 与 gone，记下库里列出的表、每张表的状态（`CatalogStateDump`）与每个快照的读取结果（`SnapshotHistory`）。
2. 定恢复点：`snapshot` 以 `deploy/scripts/catalog/restore-metadata.sh snapshot` 给项目 RDS 做手动快照；`time` 记下当时的时刻。
3. 之后 t1 再提交一次，新建 t3，删掉 gone。
4. `restore-metadata.sh restore --snapshot <恢复点快照> --yes <env>`，或 `restore --time <恢复点> --yes <env>`（先等 RDS 最近能恢复到的时刻越过恢复点）；再 `check`。
5. `restore-metadata.sh reconcile --unregister-missing`。
6. 把 t2 的目录复制到另一个库的路径下，以复制开始的时间为恢复点再 `reconcile`；删掉原来的 t2，读复制出来的表。

**期望结果**：运行以 0 退出，`<root>/metadata-restore/verdicts.tsv` 的 36 项都为 PASS：下表 17 项检查，两次演练各一份，名字分别加 `snapshot-` 与 `time-` 前缀（例如 `snapshot-lists-as-at-point`、`time-lists-as-at-point`）；再加 `snapshot-exit` 与 `time-exit`，各次演练以 0 退出（中途停下的演练写的检查不全，这一项就是 FAIL）。

| 检查（加 `snapshot-` 或 `time-` 前缀） | 期望 |
| --- | --- |
| `gone-dropped-with-files` | 删表之后，gone 的目录里没有文件 |
| `lists-as-at-point` | 恢复之后，库里列出的表与恢复点相同：gone、t1、t2，没有 t3 |
| `reads-as-at-point` | t1、t2 的状态（行数、行校验和、各数值列之和、最新快照、schema、tag 与分支）与恢复点相同 |
| `history-as-at-point` | t1、t2 每个快照的读取结果与恢复点相同 |
| `check-t1-behind`、`check-t2-consistent`、`check-gone-missing`、`check-t3-new` | `check` 把 t1 判为 `behind`（快照文件超过了指针），t2 为 `consistent`，gone 为 `missing`，t3 为 `new` |
| `all-in-line` | `reconcile` 之后，这个库的每张表都是 `consistent` |
| `lists-as-before-restore` | 列出的表与恢复之前相同：t1、t2、t3 |
| `reads-as-before-restore`、`history-as-before-restore` | 三张表的状态与每个快照的读取结果都与恢复之前相同：t1 恢复点之后的提交还在，t3 的历史完整 |
| `takes-writes` | t1 与 t3 都能再写入 |
| `copy-registered` | 复制出来的目录按目录名登记进新建的库，`consistent` |
| `original-dropped-with-files` | 删掉原来的 t2 之后，它的目录里没有文件 |
| `moved-reads-as-t2`、`moved-history-as-t2` | 复制出来的表的状态与每个快照的读取结果都与原来的 t2 相同 |

另外记录、不作判定：各次 `restore` 的用时与 colvenna-catalog 停止的秒数（`metadata-restore/<snapshot|time>/restore/catalog-stopped-seconds.txt`），恢复点上被围栏的表（同一目录的 `restore-fenced.txt`）。

**副作用与注意**：

- 每次恢复期间 colvenna-catalog 停止约 3 分钟，作业与引擎对它的请求失败后重试。以往每次演练约 35–40 分钟，整个运行约 75 分钟；其中 `restore` 约 12 分钟（快照）或 14 分钟（按时间点）。
- 每次演练留下库 `flow_<运行 id>_rds`（t1、t3）与 `flow_<运行 id>_moved`（t2）。
- 设 `KEEP_INSTANCE=1` 时保留 `restore` 的临时实例，供 `uninstall.sh` 删除（UAT-DEP-07）；它照常计费。

**清理方式**：演练只删除它自己做的、属于本环境项目实例的快照：恢复点快照随即删除；`before-restore` 快照只在全部检查通过时删除，否则保留，作为回到恢复之前的退路，核对之后手工删除。临时实例除非 `KEEP_INSTANCE=1`，都在恢复后删除。留下的两个库随环境卸载（UAT-DEP-07）。

### 5.11 `nodes` 阶段

本阶段的两个测试各让整套环境丢一台节点，所以排在最后、逐个运行。节点组以同一期望规模补回一台新节点；被终止的实例不需要恢复。

#### UAT-NODE-01 CDC 作业所在节点丢失

**对应需求**：#203（节点故障）；[验收契约](../design/acceptance-contract.md)第 2.2 节：已确认的提交零丢失，作业从 checkpoint 恢复。

**适用配置**：`--fixtures`（测试源 MySQL）；任一标准区域。

**前置条件**：作业 TaskManager 所在节点的节点组至少 2 台（默认规模）；没有其他测试在运行。

**输入**：合成数据：MySQL 库 `node_<run>`（`orders` 5000 行、`customers` 200 行），约每秒 2 个事务的写入方；要丢的那台节点。

**步骤**：

1. 标签 `node-job`：`tests/acceptance/faults/run-node-loss.sh nlj<tag> <root>/node-job job`。
2. MySQL 库 `node_<run>`（`orders` 5000 行、`customers` 200 行），LakeSource `nl-<run>`（目标 `ods_node_<run>`，删除策略 Delete），等它 Ready。
3. 要丢的节点：作业的 TaskManager 所在的那台，不论角色（作业的 Pod 不固定在写入节点上，可能是查询节点）。JobManager 也在这台上时一起丢；在别的节点上时它留下，由它重启作业的任务。写入 Pod `nw-<run>` 避开这台节点，约每秒 2 个事务，先写 60 s。
4. 记下节点组的期望规模；确认这台实例的标签 `eks:cluster-name` 是本环境的集群、`aws:autoscaling:groupName` 是这个节点组的 Auto Scaling 组，不是就不终止；再以 EC2 终止它：不排空，像宿主机故障一样（`NODE_LOSS=abrupt`，默认；`NODE_LOSS=drain` 改经 Auto Scaling 组终止，EKS 先排空节点）。
5. 等节点组补回一台同角色的节点；作业重新运行并从一个新的 checkpoint 恢复；再写 2 分钟；平台的工作负载全部就绪；catalog 健康检查，包括它的两个副本仍在两台节点上。
6. 停写，两张表逐行比对源表；删除数据源与 MySQL 库。

**期望结果**：退出 0，`<root>/node-job/verdicts.tsv` 的 8 项都为 PASS。

| 检查 | 期望 |
| --- | --- |
| `source-ready` | `orders`、`customers` Ready，作业运行 |
| `node-lost` | 实例已终止 |
| `node-replaced` | 1800 s 内有一台同角色的新节点 Ready，节点组的期望规模不变；细节记下新实例、可用区与秒数 |
| `job-recovered` | 作业重新运行，两张表 Ready，从一个新的 `chk-` checkpoint 恢复 |
| `platform-workloads-back` | 平台各命名空间的 Deployment 与 StatefulSet 在 1200 s 内全部就绪 |
| `catalog-healthy` | `check-health.sh` 全部 OK，包括副本又分布在两台节点上（`catalog-spread`） |
| `rows-orders`、`rows-customers` | ODS 与源表逐行相同：丢失之前、期间与之后写的行都在，没有多余的行 |

另外记录在 `timeline.tsv`、不作判定：丢的节点上原来有哪些 Pod（`pods-on-the-lost-node.txt`），节点不再就绪、新节点就绪、作业恢复各用的秒数，新实例就绪时与测试结束时的标签。

**副作用与注意**：

- 整套环境少一台节点约 2–3 分钟：这台节点上的作业、catalog 副本、Operator、Doris 等都会迁走或重启。2026-10-22 在 `uat` 上丢写入节点的两次与丢查询节点的一次：新节点在丢失后 92–157 s 就绪，作业在新节点就绪后约 1 分钟从 checkpoint 恢复，平台的工作负载在 292–510 s 内全部就绪；整个用例约 14 分钟。
- 作业的 Pod 不固定在写入节点上：2026-10-22 在 `uat` 上，丢了 JobManager 与 TaskManager 所在的写入节点之后，新的 TaskManager 落在了查询节点上；另两次新建的作业一开始就在查询节点上。
- 2026-10-22 在 `uat` 上，被丢的写入节点上那个 colvenna-catalog 副本重新调度到了剩下的唯一一台写入节点，新节点加入以后也没有分开：两个副本留在同一台节点上，`catalog-spread` 失败。原因是 chart 的分布约束只按调度时存在的写入节点计算；#213 给它加了 `minDomains`，之后被调走的副本等补回的节点再起（同日在 `uat` 上实测：删掉丢失节点的 Node、只剩一台写入节点的约 54 s 里，副本一直 Pending，新节点就绪后起在它上面）。
- 新实例可能在启动几分钟后被账号的自动化打上 `Patch Group=DEV`：UAT 期间复查标签的脚本要覆盖新节点（[安装部署手册](../manual/install.md)第 9 节）。

**清理方式**：测试删除数据源（目标表随之删除）与 MySQL 库；写入 Pod 在退出时停掉，成败都一样。中途失败时删除 LakeSource `nl-<run>`，再用 `tests/acceptance/cdc/mysql_contract.py reset node_<run>` 生成的语句删除 MySQL 库。装的是 #213 之前的 chart、`catalog-spread` 失败、两个 catalog 副本在同一台节点上时，删除其中一个 Pod（`kubectl -n colvenna-catalog delete pod <名字>`），它会调度到另一台写入节点。

#### UAT-NODE-02 查询节点丢失

**对应需求**：#203（节点故障）；看板服务的恢复见 [M3 Doris 看板服务](../history/m3/dashboards.md)。

**适用配置**：`--fixtures`（测试源 MySQL）；任一标准区域。

**前置条件**：至少 2 台查询节点，分布在两个可用区（默认规模）；平台 Doris 的 FE 与 BE 各一个，卷是 EBS，绑定在可用区上；没有其他测试在运行。

**输入**：同 UAT-NODE-01，另有看板 `nq-<run>` 的两个查询与用户 analyst。

**步骤**：

1. 标签 `node-query`：`tests/acceptance/faults/run-node-loss.sh nlq<tag> <root>/node-query query`。
2. 同 UAT-NODE-01 的数据源；另声明看板 `nq-<run>`：外部查询 `live` 与每分钟刷新的物化副本 `by_state`，用户 analyst；命名空间 `colvenna-bi-client` 作 Doris 客户端。
3. 要丢的节点是平台 Doris 的 FE（`colvenna-doris-fe-0`）所在的查询节点；先以 analyst 查一次两条查询，记下看到的最大订单 id。
4. 同 UAT-NODE-01 核对实例的集群与 Auto Scaling 组，再以 EC2 终止它（`NODE_LOSS` 同上）。
5. 等节点组在同一可用区补回一台查询节点；Doris 在原来的卷上回来；每 30 s 以 analyst 查一次，直到两条查询都看到丢失之后写的行；入口的目标组（console、BI）都有健康的目标；平台工作负载全部就绪；catalog 健康检查。
6. 停写，逐行比对；删除看板、数据源与 MySQL 库。

**期望结果**：退出 0，`<root>/node-query/verdicts.tsv` 的 10 项都为 PASS（`dashboard-answers-before` 只在失败时出现）。

| 检查 | 期望 |
| --- | --- |
| `source-ready`、`dashboard-ready` | 数据源与看板 Ready |
| `node-lost`、`node-replaced` | 同 UAT-NODE-01，对查询节点组 |
| `dashboard-answers-again` | 1800 s 内，analyst 的外部查询与物化副本都答出比丢失之前更大的订单 id：Doris 回来了，副本也重新刷新 |
| `entry-targets-healthy` | 每个 TargetGroupBinding 的目标组都有 `healthy` 的目标：console 与 BI 在负载均衡器后面应答 |
| `platform-workloads-back`、`catalog-healthy` | 同 UAT-NODE-01 |
| `rows-orders`、`rows-customers` | 同 UAT-NODE-01 |

另外记录：Doris 的 FE、BE 最后所在的节点与可用区，`doris/pods-after.txt` 的 Pod 与卷声明。

**副作用与注意**：Doris 在恢复之前不可用，看板与 BI 的查询都失败（2026-10-22 在 `uat` 上，丢了 FE 所在的查询节点之后 284 s，两条查询都答出了丢失之后写的行；平台的工作负载 318 s 内全部就绪；整个用例约 14 分钟）；同一台节点上的其他 Pod（例如 Prometheus、开发用 Kafka 的 broker）也随之迁走或等待原可用区的新节点。新节点不在原可用区时，带 EBS 卷的 Pod 起不来，`dashboard-answers-again` 会失败。新实例的标签同 UAT-NODE-01。

**清理方式**：测试删除看板（Doris 用户随之删除）、数据源与 MySQL 库；`colvenna-bi-client` 留下。中途失败时另删看板 `nq-<run>` 与 Secret `nq-<run>-analyst`。

## 6. 负向安全用例

下表按用例汇总期望结果里标为“（负向）”的检查。

### 6.1 未认证的请求得到 401、403、跳转登录或连不上

| 用例 | 检查 |
| --- | --- |
| UAT-CAT-07 | `client namespace, no token`、`client namespace, forged token`、`client namespace, expired token`、`client namespace, revoked token`、`client namespace, token of a disabled principal`、`admin API without token`、`plain HTTP to the TLS port gets no HTTP response` |
| UAT-TSK-01 | `anonymous-refused` |
| UAT-MON-01 | `grafana-signin-required` |
| UAT-SRC-03 | `kafka-credentials-refused`、`kafka-credentials-alerted`（轮换后的旧口令） |
| UAT-ICE-01 | `anonymous-gets-nothing`、`wrong-secret-gets-no-token`、`no-vended-credentials-no-data` |
| UAT-ICE-02 | `anonymous-refused`、`anonymous-config-refused`、`anonymous-namespaces-refused`、`anonymous-catalogs-refused`、`anonymous-principals-refused` |
| UAT-LIN-01 | `unauthenticated-proxy-refused` |
| UAT-GOV-01 | `undeclared-user-cannot-log-in`、`wrong-password-cannot-log-in`、`empty-password-cannot-log-in` |
| UAT-DSH-01 | `passwords-required` |
| UAT-BI-01 | `no-session`、`no-session-sqllab`、`no-session-api-v1-database`、`no-session-api-v1-security-csrf_token`、`no-session-health`、`no-session-signed-out`、`refused-api-v1-security-login`、`refused-api-v1-security-refresh`、`refused-login`、`refused-api-v1-security-guest_token`、`api-token-refused`、`pod-refuses-forged-identity` |
| UAT-CON-01 | `refused-without-the-role`（没有会话的部分） |
| UAT-CON-02 | `no-session-pipeline`、`no-session-api`、`no-session-node`、`catalog-refuses-the-consoles-token` |
| UAT-ENT-01 | `no-session`、`no-session-api-me`、`no-session-healthz`、`no-session-static-console.css`、`no-session-logout`、`no-session-signed-out`、`no-session-nowhere`、`no-session-post-logout`、`http-redirects-to-https`、`legacy-tls-refused`、`unknown-host-gets-nothing`、`forged-session-cookie`、`forged-authorization-code`、`no-self-sign-up`、`wrong-password-refused`、`wrong-mfa-code-refused`、`client-identity-headers-not-trusted`、`pod-refuses-forged-identity`、`disabled-user-cannot-sign-in` |

### 6.2 有身份但越权，被拒绝

| 用例 | 检查 |
| --- | --- |
| UAT-CAT-05 | `identity without a grant gets none`、`read-only identity: write data`、`read-only identity: delete data`、`sibling table: list`、`sibling table: read schema`、`sibling table: write`、`bare table prefix: list (would show orders_*)`、`database directory: list`、`other database: read schema`、`other database: list`、`whole bucket: list`、`outside the warehouse: write`；`test-flink`、`test-spark`、`doris`、`no-irsa` 各自的 `pod identity: read table data`、`pod identity: list table data`、`pod identity: warehouse table data`，共 12 项；`flow configurations without S3 keys` |
| UAT-CAT-06 | `list-databases/outsider`、`get-table/denied`、`get-table/outsider`、`list-tables/outsider`、`data-token/denied`、`data-token/outsider`、`column-auth/denied`、`commit/reader`、`commit/outsider`、`alter-table/writer`、`create-table/writer`、`admin-grants/owner`、`admin-grants/reader`、`drop-table/writer` |
| UAT-CAT-07 | `service identity on the admin API` |
| UAT-TBL-01 | `refused-outside-upstream` |
| UAT-TSK-01 | `refused-outside`、`refused-outside-ran-nothing` |
| UAT-GOV-01 | `user-without-a-condition-sees-no-rows`、17 项 `denied-*`、`no-direct-storage`、`row-filter-probe-sees-no-hidden-row-view`、`row-filter-probe-sees-no-hidden-row-copy`、`row-filter-probe-sees-no-hidden-row-case`、`explain-shows-the-plan-not-the-rows`、`conflict-withdraws-the-users` |
| UAT-SRC-03 | `kafka-refused-peek`、`kafka-refused-peek-alerted` |
| UAT-QUA-01 | `reader-refused-before-any-check`、`dashboard-refused-before-any-check`、`reader-refused-after-failed-check`、`dashboard-refused-after-failed-check`、`dashboard-user-refused-after-failed-check`、`reader-refused-after-tasks-deleted`、`gate-closed-after-tasks-deleted`、`waiver-forged-approver-refused`、`waiver-without-right-refused`、`reader-refused-on-new-rules`、`reader-refused-once-expired` |
| UAT-ICE-01 | `ungranted-principal-reads-nothing`、`ungranted-principal-reads-no-metadata-table`、`insert-refused`、`drop-refused`、`create-refused`、`nothing-written` |
| UAT-ICE-02 | `withdrawn-when-restricted`、`withdrawn-table-not-found`、`reader-revoked`、`revoked-reader-refused`、`reader-cannot-insert`、`reader-cannot-drop`、`reader-cannot-create`、`reader-cannot-alter`、`reader-cannot-rename`、`outsider-cannot-read`、`vended-cannot-read-other-table`、`vended-cannot-write`、`reader-pod-has-no-other-way` |
| UAT-NS-01 | 每个命名空间的 `-other-list`、`-other-list-bare`、`-other-root-head`、`-other-get`、`-other-put`、`-tables-list`、`-tables-get`、`-other-assume`，共 16 项 |
| UAT-GOV-02 | `published-only-governed`、`dwd-lowered-unpublished`、`grant-follows-governance`、`dwd-retention-missing`、`dwd-unclassified`、`undeclared-unpublished`、`unpublished-on-delete` |
| UAT-CON-01 | `refused-without-the-role`、`the-page-offers-each-role-its-own`、`nothing-made-by-the-refused`、`nothing-deleted-by-the-refused`、`the-console-impersonates-only-its-users`、`kubernetes-holds-the-roles`、`apply-leaves-confirmed-and-other-namespaces-out`、`nothing-applied-around-the-rules`、`audit-delete-refused-owd`、`audit-forget-refused-oe-refused`、`audit-waive-refused-ow-refused`、`audit-apply-refused-oq-cm`、`audit-apply-refused-obfa`、`audit-apply-refused-oexa`、`audit-apply-refused-ona` |
| UAT-DSH-01 | `users-read-what-they-are-given`（拒绝的部分）、`system-schemas-withdrawn` |
| UAT-BI-01 | `roles-erin-refused`、`databases-dave-none`、`lake-table-refused`、`information-schema-refused`、`query-not-given-refused`、`writes-refused`、`dave-reaches-nothing`、`chart-refuses-dave`、`sqllab-export-is-the-exporters`、`no-other-doris-user-from-the-bi-interface` |
| UAT-CON-02 | `refused-the-pipeline-bi`、`refused-the-pipeline-none`、`nothing-secret-or-of-the-data-is-served`、`only-get`、`kubernetes-identity-only-reads`、`kubernetes-refuses-the-consoles-writes`、`the-write-did-not-happen`、`console-holds-no-catalog-identity`、`aws-identity-only-its-own-audit-and-sign-out`、`prometheus-reads-only-from-the-consoles-namespace` |
| UAT-ENT-01 | `roles-none`、`no-role-refused` |

### 6.3 网络隔离与暴露面

| 用例 | 检查 |
| --- | --- |
| UAT-CAT-07 | `health port from another pod`、`health port on the pod address inside the pod`、`unlabelled namespace is blocked by NetworkPolicy` |
| UAT-GOV-01 | `only-the-mysql-protocol` |
| UAT-MON-01 | `metrics-isolated` |
| UAT-SRC-03 | `kafka-unreachable`、`kafka-unreachable-alerted` |
| UAT-ICE-01 | `trino-has-no-service`、`trino-unreachable-from-other-pods` |
| UAT-ICE-02 | `management-port-closed-to-readers` |
| UAT-LIN-01 | `marquez-cluster-ip-only`、`unlabelled-namespace-cannot-connect`、`web-and-admin-port-refused` |
| UAT-DSH-01 | `doris-isolated` |
| UAT-BI-01 | `pod-unreachable-in-cluster` |
| UAT-CON-02 | `prometheus-unreachable-without-the-labels` |
| UAT-ENT-01 | `pods-unreachable-in-cluster`、`exposure-only-the-entry`、`exposure-other-rows` |

### 6.4 身份与访问随声明或会话收回

| 用例 | 检查 |
| --- | --- |
| UAT-SRC-01 | `mysql-deleted` |
| UAT-TBL-01 | `tables-deleted` |
| UAT-SRC-02 | `one-collection-deleted`、`mongo-deleted` |
| UAT-TSK-01 | `finished-tokens-refused` |
| UAT-SRC-03 | `kafka-deleted` |
| UAT-SRC-04 | `kafka-cdc-deleted` |
| UAT-DSH-01 | `dashboard-deleted` |
| UAT-BI-01 | `bob-loses-the-database`、`bob-queries-refused-after`、`bob-sees-no-rows-of-the-dashboard-after`、`credentials-withdrawn`、`alice-loses-the-database` |
| UAT-ENT-01 | `logout-drops-the-session`、`copied-session-ends-after-logout`、`disabled-user-session-ends`、`session-times-out`、`logout-after-the-token-expired-drops-the-session` |

## 7. 本次 Release 的必需用例集合

本节在执行之前固定发布候选（从 `v1.0.0-rc.1` 起）必须执行的用例，共 44 条。

- 每条用例按“区域”一列执行，两个标准区域（本次是 `us-west-2` 与 `us-east-2`）分别给出结果。UAT 在带 `--fixtures` 的环境上执行；只装平台的用例在另一套只装平台的环境上执行。
- 每个区域都要有一次从零安装与一次卸载，两者的配置不必相同：从零安装是 UAT-DEP-01（`--fixtures`）或 UAT-DEP-02（只装平台）之一，卸载是 UAT-DEP-07，卸载的是本区域从零新建的那套环境。本次 `us-east-2` 从零新建 `uate2`（UAT-DEP-01）并在验收之后卸载；`us-west-2` 的 `uat` 是升级的（UAT-DEP-06），另以发布清单从零新建只装平台的 `plat`（UAT-DEP-02）并卸载。
- 执行报告对每条必需用例、每个区域只给 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN` 之一：判定它的运行（`uat-cases.tsv` 的第一个标签）的检查全部为 PASS 是 `PASS`；有 FAIL 是 `FAIL`；它依赖的准备运行（其余标签）失败、使判定运行没有运行或不可信，是 `BLOCKED`；没有运行是 `NOT_RUN`。必需用例不能从分母里去掉，也不能改写成“不适用”。
- 修好之后重跑的结果另记一行，原来的失败、中止与复测都保留在报告里。

<!-- required-cases:start -->

| 用例 | 名称 | 配置 | 区域 | 执行 |
| --- | --- | --- | --- | --- |
| UAT-DEP-01 | 从零安装 | `--fixtures` | 至少一个区域（本次 `us-east-2` 的 `uate2`） | 运维 |
| UAT-DEP-02 | 只装平台 | 只装平台 | 至少一个区域（本次 `us-west-2` 的 `plat`，按发布清单安装） | 运维 |
| UAT-DEP-03 | 重复执行 | `--fixtures` | 两个区域各一次 | `deploy` |
| UAT-DEP-04 | 中断后续装 | `--fixtures` | 两个区域各一次 | `deploy` |
| UAT-DEP-05 | 配置拒绝 | `--fixtures` | 两个区域各一次 | `deploy` |
| UAT-DEP-06 | 原地升级 | `--fixtures` | 升级的那套环境所在的区域（`us-west-2` 的 `uat`） | `prep` |
| UAT-DEP-07 | 卸载 | `--fixtures` 或只装平台 | 两个区域各一次，卸载本区域从零新建的那套（本次 `uate2` 与 `plat`） | 运维 |
| UAT-DEP-08 | 按发布清单安装 | `--fixtures` | 两个区域各一次 | 运维与 `deploy` |
| UAT-DEP-09 | 元数据恢复 | 只装平台 | 至少一个区域（本次 `us-west-2` 的 `plat`，快照与按时间点各一次） | 运维 |
| UAT-CAT-01 | Flink 经 colvenna-catalog 读写 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-02 | Spark 经 colvenna-catalog 读写 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-03 | Doris 经 colvenna-catalog 只读 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-04 | Flink session 集群经 colvenna-catalog 读写 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-05 | 按表数据凭证的范围 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-06 | 目录授权与审计 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-07 | catalog 的令牌与网络访问检查 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-08 | catalog 与平台服务的健康 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-09 | 引擎没有调用 catalog 未提供的端点 | `--fixtures` | 两个区域各一次 | `catalog` |
| UAT-CAT-10 | 故障下的原子提交 | `--fixtures` | 两个区域各一次 | `faults` |
| UAT-CAT-11 | 实例故障与元数据恢复 | `--fixtures` | 两个区域各一次 | `faults` |
| UAT-CAT-12 | 数据凭证续期 | `--fixtures` | 两个区域各一次 | `renewal` |
| UAT-SRC-01 | MySQL 与 PostgreSQL 的声明式 CDC | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-TBL-01 | 声明式分层表 DWD 与 DWS | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-SRC-02 | MongoDB CDC | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-GOV-01 | 列标签、脱敏、行过滤与 Doris 上的拒绝 | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-MON-01 | 监控、告警与不合格行隔离 | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-TSK-01 | 维护任务、回刷与 Airflow | `--fixtures` | 两个区域各一次 | `ingest` |
| UAT-SRC-03 | Kafka 事件源 | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-SRC-04 | Kafka 承载的 CDC | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-QUA-01 | 批量质量校验、发布门禁与豁免 | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-ICE-01 | 已有 Trino 经 Iceberg 读取 | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-ICE-02 | Iceberg 兼容发布 | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-ICE-03 | 写入端自己压缩的分层表发布 | `--fixtures` | 两个区域各一次 | `kafka` |
| UAT-NS-01 | 第二个受管命名空间的作业身份 | `--fixtures` | 两个区域各一次 | `namespaces` |
| UAT-LIN-01 | 血缘上报到 Marquez | `--fixtures` | 两个区域各一次 | `solo` |
| UAT-GOV-02 | 分级、保留与跨层个人数据删除 | `--fixtures` | 两个区域各一次 | `solo` |
| UAT-ICE-04 | 带 Iceberg 元数据的删除 | `--fixtures` | 两个区域各一次 | `solo` |
| UAT-CON-01 | 控制台的受控操作与 CLI | `--fixtures` | 两个区域各一次 | `solo` |
| UAT-DSH-01 | Doris 看板服务与查询隔离 | `--fixtures` | 两个区域各一次 | `solo` |
| UAT-BI-01 | BI 查询界面 | `--fixtures` | 两个区域各一次 | `console` |
| UAT-CON-02 | 控制台的流水线全景 | `--fixtures` | 两个区域各一次 | `console` |
| UAT-ENT-01 | Web 入口、统一登录与会话 | `--fixtures` | 两个区域各一次 | `entry` |
| UAT-NODE-01 | CDC 作业所在节点丢失 | `--fixtures` | 两个区域各一次 | `nodes` |
| UAT-NODE-02 | 查询节点丢失 | `--fixtures` | 两个区域各一次 | `nodes` |

<!-- required-cases:end -->

不在必需集合里的：

| 内容 | 原因 |
| --- | --- |
| 第 2 节的各项：colvenna-catalog 自身的升级、`tests/upstream/`、`PERF=1` 下的检查、`test_*.py`、辅助脚本 | 见第 2 节 |
| UAT-NODE-01、UAT-NODE-02 以 `NODE_LOSS=drain` 运行 | 经 Auto Scaling 组终止、EKS 先排空节点，是计划内的节点退役，不是故障；可以另外运行，不计入 |
| UAT-DEP-04 换一个步骤中断，或以别的 `RESUME_SIGNAL` 中断 | 可以另外运行，不计入；必需的是默认的 `operator-again` 与 `SIGHUP` |
| 吞吐、QPS、延迟分位、容量与压测 | 性能验收单独成文，见[性能测试](performance.md)，不计入 UAT 的统计 |
| 第二朵云（#33） | 不在本次 Release 的范围内 |

> 注：本文中的 issue 与 PR 编号（如 #33）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
