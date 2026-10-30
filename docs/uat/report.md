---
title: "cove-lake UAT 执行报告"
date: 2026-10-21
status: draft
issue: 163
---

# UAT 执行报告

> 本文记录的是当时的情况：产品当时名为 cove-lake，技术标识以 cove 开头；2026-10-24/25 改为 Colvenna Lake 与 colvenna（#258、#271）。

本文记录按 [UAT 测试用例](cases.md) 在新建环境 `uat` 上的一次完整执行：每条用例的结果与证据位置、执行环境与提交，以及执行中出现的失败与处理。

这次执行在 2026-10-20 至 21 日、提交 `aacb39cc` 上，早于发布候选。发布候选的 UAT 报告按 Release 归档在 [`reports/<候选>/`](reports/)，最新的是 [`v1.0.0-rc.5`](reports/v1.0.0-rc.5/report.md)（两个区域）。

## 1. 结论

- 33 条用例全部通过。各用例最后一次运行里，按检查行计数的 23 个测试共 825 项 PASS、0 项 FAIL，其余 10 个 UAT-CAT 用例以 0 退出。`summary.tsv` 里有 13 行 FAIL，都属于后来修好测试、重跑通过的运行。
- 执行中失败的运行，直接原因都在测试脚本、测试夹具或执行方式上：测试有过时的预期，执行机的 `env` 被别的程序遮住，测试的资源请求超出默认规模，一个上游镜像被删。修好测试后重跑都通过，见第 4 节。
- 其中三处的根源在产品或它依赖的组件上，测试里绕开了，另开 issue 跟踪：Doris Operator 建好 FE 后有时不再协调（#172），Doris Operator 的默认请求过大（#170），`createTag` 在没有快照的表上运行时才失败（#171）。除此之外没有发现产品的功能缺陷。
- 观察到的容量与运维事项见第 5 节。

## 2. 环境与提交

| 项 | 内容 |
| --- | --- |
| 环境 | `COVE_ENV=uat`，维护者的工作账号 `<ACCOUNT_ID>`，`us-west-2`。2026-10-20 按[安装部署手册](../manual/install.md)从零新建（第 10 节） |
| 规模 | 默认规模：EKS 1.35（`v1.35.8-eks`），写入节点 2 台 t3.xlarge，查询节点 2 台 r6i.xlarge，每台可分配 3.92 CPU |
| 执行身份与执行机 | 执行机的 EC2 实例角色；aarch64 的 Ubuntu 主机，镜像以 linux/amd64 构建 |
| 产品镜像 | 提交 `aacb39cc`。`prep` 阶段在这个提交上对已装好的环境重跑 `deploy/install.sh --fixtures`：26 步全部 `ok`，用时 2085 秒 |
| 测试 | 从 `aacb39cc` 起，按第 4 节修复测试，先后在 8 个提交上运行；每个运行用的测试提交见第 3 节，每次更换都记在 `uat.log` 里。这期间的提交改了测试与 UAT 文档；部署脚本只改了查找镜像的方式（`IMAGES_SHA`）与 catalog Pod 的一个注解（`image-sha`），`8be58408` 曾给 `install-doris.sh` 加过对 Doris 的催促，`55bbf3b4` 又撤回了。产品镜像没有重建，各次运行用的都是 `aacb39cc` 的镜像 |
| `RUN_TAG` | `a1020`。重跑的运行另取一个没用过的 id，例如 `ckb1020`、`cya1020`、`tbb1020`、`rnf1020` |
| 时间 | 2026-10-20T13:28Z 开始 `prep`，2026-10-21T07:02Z 结束最后一次检查。按各用例最后一次运行的用时、照 `run-uat.sh` 的分组算，完整执行一遍约 13.4 小时 |
| 补丁窗口 | 账号的维护窗口每天 00:00 UTC 给带 `Patch Group=DEV` 标签的实例打补丁并按需重启。执行期间去掉了执行机与 4 个节点的这个标签，并每 10 分钟核对一次，防止被自动补回；UAT 结束后给节点恢复 |

**证据。** 证据保存在执行机的 `<证据目录>/issue-163/uat-aacb39c/`，不入库：

- `uat.log`：每个运行的起止，以及每次更换测试提交的记录；
- `runs.tsv`：每个运行一行，含标签、退出状态、开始时间与秒数；
- `summary.tsv`：每项检查一行；
- `<标签>.log` 与 `<标签>/`：每个运行的输出与证据目录。

重跑的运行以 `-2`、`-3` 等结尾，与第一次的运行并存。重跑是用执行机上的脚本逐个发起的，做法与 [`tests/acceptance/rerun-uat.sh`](../../tests/acceptance/rerun-uat.sh) 相同；这个脚本是执行之后才收进仓库的，用于以后的重跑。

## 3. 用例结果

"判定"一列：M3–M5 的测试是它 `verdicts.tsv` 里的检查数；UAT-CAT-03、UAT-CAT-06 是日志里带 `OK`、`PASS` 的检查行数；其余 UAT-CAT 用例按退出状态判定，逐项结果在[用例文档](cases.md)写明的证据文件里。"测试提交"是这次运行时测试所在的提交。"之前的运行"列出同一用例更早的运行：失败的，被中止的，或者在测试修改之后又重跑一次的，原因见第 4 节。

| 用例 | 结果 | 运行 | 测试提交 | 判定 | 用时 | 之前的运行 |
| --- | --- | --- | --- | --- | --- | --- |
| UAT-CAT-01 | 通过 | `catalog-flink` | `aacb39cc` | 按退出状态 | 3 分 56 秒 | — |
| UAT-CAT-02 | 通过 | `catalog-spark` | `aacb39cc` | 按退出状态 | 1 分 53 秒 | — |
| UAT-CAT-03 | 通过 | `catalog-doris-2` | `8be58408` | 16 PASS | 3 分 47 秒 | `catalog-doris` |
| UAT-CAT-04 | 通过 | `catalog-session-2` | `8be58408` | 按退出状态 | 5 分 24 秒 | `catalog-session` |
| UAT-CAT-05 | 通过 | `catalog-tokens` | `aacb39cc` | 按退出状态 | 1 分 40 秒 | — |
| UAT-CAT-06 | 通过 | `catalog-authorization` | `aacb39cc` | 52 PASS | 32 秒 | — |
| UAT-CAT-07 | 通过 | `catalog-access` | `aacb39cc` | 按退出状态 | 2 分 24 秒 | — |
| UAT-CAT-08 | 通过 | `catalog-health` | `aacb39cc` | 按退出状态 | 27 秒 | — |
| UAT-CAT-09 | 通过 | `catalog-logs` | `aacb39cc` | 按退出状态 | 8 秒 | — |
| UAT-CAT-10 | 通过 | `catalog-commit-faults-2` | `e51a9792` | 按退出状态 | 28 分 13 秒 | `catalog-commit-faults` |
| UAT-CAT-11 | 通过 | `catalog-recovery` | `8be58408` | 按退出状态 | 29 分 39 秒 | — |
| UAT-CAT-12 | 通过 | `renewal-6` | `7491a2c3` | 按退出状态 | 102 分 52 秒 | `renewal`、`renewal-2`、`renewal-3`、`renewal-4`、`renewal-5` |
| UAT-SRC-01 | 通过 | `cdc` | `8be58408` | 50 PASS | 62 分 51 秒 | — |
| UAT-TBL-01 | 通过 | `tables-2` | `e51a9792` | 37 PASS | 50 分 43 秒 | `tables` |
| UAT-SRC-02 | 通过 | `mongo` | `8be58408` | 21 PASS | 20 分 29 秒 | — |
| UAT-GOV-01 | 通过 | `enforcement` | `8be58408` | 42 PASS | 9 分 12 秒 | — |
| UAT-MON-01 | 通过 | `monitoring` | `8be58408` | 16 PASS | 37 分 36 秒 | — |
| UAT-TSK-01 | 通过 | `tasks-2` | `e51a9792` | 42 PASS | 43 分 10 秒 | `tasks` |
| UAT-SRC-03 | 通过 | `kafka` | `e51a9792` | 32 PASS | 21 分 26 秒 | — |
| UAT-SRC-04 | 通过 | `kafka-cdc-2` | `dbcb1b1b` | 24 PASS | 43 分 6 秒 | `kafka-cdc` |
| UAT-QUA-01 | 通过 | `quality` | `e51a9792` | 56 PASS | 24 分 39 秒 | — |
| UAT-ICE-01 | 通过 | `trino` | `e51a9792` | 40 PASS | 17 分 20 秒 | — |
| UAT-ICE-02 | 通过 | `iceberg` | `e51a9792` | 64 PASS | 26 分 6 秒 | — |
| UAT-ICE-03 | 通过 | `iceberg-layered` | `e51a9792` | 10 PASS | 18 分 44 秒 | — |
| UAT-NS-01 | 通过 | `namespaces` | `e51a9792` | 36 PASS | 2 分 51 秒 | — |
| UAT-LIN-01 | 通过 | `lineage` | `e51a9792` | 31 PASS | 24 分 12 秒 | — |
| UAT-GOV-02 | 通过 | `governance` | `e51a9792` | 28 PASS | 86 分 8 秒 | — |
| UAT-ICE-04 | 通过 | `iceberg-erasure` | `e51a9792` | 7 PASS | 10 分 52 秒 | — |
| UAT-CON-01 | 通过 | `operations` | `e51a9792` | 59 PASS | 24 分 0 秒 | — |
| UAT-DSH-01 | 通过 | `dashboards` | `e51a9792` | 19 PASS | 37 分 36 秒 | — |
| UAT-BI-01 | 通过 | `bi` | `e51a9792` | 54 PASS | 10 分 56 秒 | — |
| UAT-CON-02 | 通过 | `pipeline-3` | `827e8e89` | 39 PASS | 48 分 23 秒 | `pipeline`、`pipeline-2` |
| UAT-ENT-01 | 通过 | `entry` | `e51a9792` | 50 PASS | 14 分 47 秒 | — |

各用例的逐项结果在 `<标签>/verdicts.tsv`；UAT-CAT 各用例在 `<标签>/` 的 `steps.txt`、`*-checks.txt` 等文件里，[用例文档](cases.md)写明每个用例的证据文件。

准备与重装类的运行：除了第 4 节第 5 条的 `renewal-catalog-back`，都以 0 退出，即 `reinstall`、`spark-image`、UAT-CAT-12 的前置运行 `renewal-flink` 与 `renewal-spark`、`renewal-catalog-2` 至 `-6`、`renewal-catalog-back-2` 至 `-6`、`namespaces-stack`、`namespaces-operator`、`namespaces-operator-back`、`namespaces-stack-back`、`entry-stack` 与 `entry-stack-back`。

最后，在测试的最终提交 `55bbf3b4` 上以 `rerun-uat.sh` 重装了一次 cove-catalog（`catalog-install-final`），并做了一次健康检查（`catalog-health-final`），都以 0 退出。评审之后改过的 `install-catalog.sh` 与新收进的 `rerun-uat.sh`，由这两步实际跑过。

`runs.tsv` 里有三行 0 秒、退出 0 的运行：`renewal-catalog`、`tables`、`tasks`。它们其实没有执行，原因见第 4 节第 4 条，不算结果。

## 4. 执行中的失败与处理

| # | 运行 | 现象 | 原因 | 处理 |
| --- | --- | --- | --- | --- |
| 1 | `catalog-doris` | 1484 秒后失败：`pods "doris-cda1020-be-0" not found` | Doris Operator 25.8 建好 FE 之后没有再协调这个 DorisCluster，BE 一直没有建出来（#172） | 测试等 Pod 期间每分钟给 DorisCluster 打一个注解，促它重新协调（`8be58408` 的 `run-doris-flow.sh`）。`catalog-doris-2` 通过。平台的 `install-doris.sh` 由 #172 加了同样的处理 |
| 2 | `catalog-session` | Flink session 的第二个 TaskManager 排不上 | 测试把 Pod 固定在写入节点上，而写入节点已经放满：Doris Operator 一个 Pod 就请求 2 CPU、4 GiB（#170） | 去掉 catalog 各流程的节点固定（`8be58408`）。`catalog-session-2` 通过 |
| 3 | `catalog-commit-faults` | 读维护作业的日志时文件不存在 | 测试在维护 Job 建出来之前就开始注入故障 | 等维护 Job 出现再注入（`fbcd4864`）。`catalog-commit-faults-2` 通过 |
| 4 | `renewal-catalog`、`tables`、`tasks`、`renewal` | 前三个 0 秒退出 0；`renewal` 报 catalog 的令牌有效期仍是 `PT3H` | 执行机的 `PATH` 里，`~/.local/bin/env`（另一个程序的安装脚本）排在 `/usr/bin/env` 前面，`env VAR=… bash …` 什么也没做 | runner 改用 `/usr/bin/env`（`fbcd4864`）。`tables-2`、`tasks-2`、`renewal-catalog-2` 通过；`renewal` 见第 6 条 |
| 5 | `renewal-catalog-back` | `install-catalog.sh` 拒绝：没有本提交的镜像 | 测试已换到新提交，镜像却是 `prep` 时那个提交的 | `prep` 记下所在的提交，之后的运行与 `install-catalog.sh` 都按它查找镜像（`IMAGES_SHA`，`8be58408`、`e51a9792`）。`renewal-catalog-back-2` 通过 |
| 6 | `renewal-2`–`renewal-5` | session 集群的第二个 TaskManager 或测试用的 Doris BE 排不上：Flink 的 slot 请求 5 分钟后超时，作业不允许重启，于是失败 | 测试的资源请求超出默认规模。`renewal-2` 按原设计与 `ingest` 阶段同时运行，与 `tables-2`、`tasks-2` 一起时 CPU 请求到了 89–98%。`renewal-3` 单独运行仍放不下：它的 Flink session、客户端、Spark 与自带的一套 Doris 按各自默认的请求合计约 10 CPU，加上平台约 7.2 CPU，超过 4 个节点的约 15.7 CPU。`renewal-4` 调小了请求，但 session 集群全落在一台查询节点上，占了 Doris BE 要的 CPU，另一台查询节点内存不够。`renewal-5` 把 Flink 与 Spark 都放到写入节点，写入节点的内存又不够。后两次看到排不上就中止了 | `renewal` 改为单独运行，`ingest` 改为默认规模上跑通的分组（`4a78e1b3`）；三个引擎的 CPU 请求可调，`renewal` 里调小（`827e8e89`）；Flink 固定在写入节点，Spark 与 Doris 放在查询节点（`a166d202`、`7491a2c3`）。`renewal-6` 通过：Flink 连续写入 100 分钟，越过第一张 90 分钟的令牌，`test-flink` 在过期前续期了 `renew_rnf1020.renewal` |
| 7 | `kafka-cdc` | Debezium Server 的 Pod 拉不到镜像，部署超时 | 固定的 digest `306ed983…` 已从 quay.io 删除：上游 2026-10-20 在新的基础镜像上重建了 `3.6.3.Final`。开发环境的节点上还缓存着旧镜像，所以以前没有暴露 | 改为这个 tag 现在的 digest `a91f6e06…`（`5602a061`）；同时核对了仓库里其余 22 个按 digest 固定的外部镜像，都还能解析。`kafka-cdc-2` 通过 |
| 8 | `pipeline`、`pipeline-2` | `pipeline` 有 3 项判定失败；`pipeline-2` 通过，但打 tag 的任务第一次尝试失败 | `kubernetes-identity-only-reads` 与 `console-holds-no-catalog-identity` 写于 M5 #137，早于 #138 给控制台加的扮演权限与确认密钥 Secret，预期过时了。`nothing-marked-once-every-alert-resolves` 等了 30 分钟仍有两条告警：测试从不压缩它的 ODS 表，运行久了 `CoveCompactionBacklog` 按设计触发；DWS 上打 tag 的任务在 DWS 还没有快照时就运行（#171）（"Cannot create tag because latest snapshot doesn't exist"），重试紧接着就来，3 次都落在这段时间里，`CoveTaskFailed` 一直挂到最后 | 两项预期按控制台的现状改写；等"全部恢复"之前先压缩 ODS 表，与用户应做的一样；任务失败时先保存各次尝试的日志（`dbcb1b1b`），`pipeline-2` 通过。再让打 tag 的任务等到 DWS 有了第一个快照（`4bc66c9f`），`pipeline-3` 通过，任务第一次尝试就成功 |

## 5. 观察

**容量。** 默认规模的 4 个节点共可分配约 15.7 CPU，平台自己的组件请求约 7.2 CPU，留给作业与测试的约 8.5 CPU。其中：

- Doris Operator 一个 Pod 请求 2 CPU、4 GiB（chart 的默认值），占平台请求的近三成，比 cove-catalog 的两个副本合计还多（#170）；
- `renewal`（session 集群、Spark 与一套自己的 Doris）不能与其他测试同时运行；单独运行也要调小请求，并把 Flink 放在写入节点、Spark 与 Doris 放在查询节点，才放得下；
- `ingest` 阶段两两同时运行的分组是这次实际跑通的；
- 生产规模的容量结论见[性能测试](performance.md)。

**执行中发现、另开 issue 的问题。** 它们是第 4 节几次失败的根源，测试里已经绕开，用例按修好的测试判定：

- #172：Doris Operator 25.8 建好 FE 之后有时不再协调，BE 迟迟不建（第 4 节第 1 条）。平台的安装脚本已在 #172 中加了同样的处理。
- #170：Doris Operator 按 chart 默认值请求 2 CPU、4 GiB，占平台 CPU 请求的近三成（第 4 节第 2 条）。已在 #170 中按实测改为请求 100m、128 MiB。
- #171：`createTag` 在还没有快照的表上运行时才失败，重试紧接着就用完（第 4 节第 8 条）。`check` 任务则在准入时判为 `NoSnapshot`。

**在已有环境上升级。** `prep` 阶段在新提交上对已装好的环境重跑整个 `install.sh --fixtures`，26 步都收敛，用时 2085 秒（34.75 分钟），之后的用例都在升级后的环境上运行。安装部署手册第 7 节原先只验证了 `foundation` 与 `verify` 两步的重跑。

**外部镜像的 digest 会失效。** 上游重建同一版本的镜像并删掉旧的 index 后，按 digest 固定的引用就拉不到了。缓存了旧镜像的节点不会暴露这个问题，从零新建的环境会。

**补丁窗口。** 执行跨过了 2026-10-21 00:00 UTC 的维护窗口；去掉标签之后，执行机与节点都没有被重启。

## 6. 未执行与不在范围

见[用例文档](cases.md)第 2 节：cove-catalog 的跨版本升级与回退、Flink CDC 3.7 的 YAML Pipeline 与本机复现、`PERF=1` 下的看板性能检查、判定脚本的离线单元测试，以及第二朵云。

> 注：本文中的 issue 与 PR 编号（如 #258）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
