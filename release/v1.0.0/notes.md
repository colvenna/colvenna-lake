---
title: "Colvenna Lake v1.0.0 发布说明"
date: 2026-11-04
status: final
---

<!--
deploy/scripts/release/promote.sh 把 v1.0.0-rc.7 晋升为 v1.0.0 时写下这份说明：第 1 节取自
release/v1.0.0/manifest.json；其余各节是 v1.0.0-rc.7 验收时的发布说明（提交
dc00cd16ed236a8c3beed53832360548695c8358 上的 release/v1.0.0-rc.7/notes.md），原样保留；只有第 6、7 节
说晋升没有在 AWS 上实测的两处，在晋升实跑之后改成了实跑的结果（tag v1.0.0 上是晋升时的原文）。
-->

# Colvenna Lake v1.0.0 发布说明

## 1. 正式版

| 项 | 内容 |
| --- | --- |
| 正式版 | `v1.0.0`（annotated tag） |
| 晋升自 | `v1.0.0-rc.7`，判为 GO：它验收时的发布说明，即提交 `dc00cd16ed236a8c3beed53832360548695c8358` 上的 [`release/v1.0.0-rc.7/notes.md`](https://github.com/colvenna/colvenna-lake/blob/dc00cd16ed236a8c3beed53832360548695c8358/release/v1.0.0-rc.7/notes.md)。没有重新构建任何东西；本节之后的各节就是那份说明，原样保留，只有第 6、7 节关于晋升的两处按晋升的实跑结果更正 |
| 镜像与安装所用的源码 | 提交 [`3e7344f1`](https://github.com/colvenna/colvenna-lake/commit/3e7344f1ba7e62ab7ed48d2574656a8647a42e4b)（`3e7344f1ba7e62ab7ed48d2574656a8647a42e4b`），与 `v1.0.0-rc.7` 的相同。tag `v1.0.0` 指向的提交在 `v1.0.0-rc.7` 的 tag 提交 `a01301c59a79fcb92aa7804359859bc34fae691d` 上只多了 `release/v1.0.0/` |
| 发布清单 | [`release/v1.0.0/manifest.json`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0/release/v1.0.0/manifest.json)：`v1.0.0-rc.7` 的清单换成正式版的名字，digest 全都不变；`promotion` 记下晋升自哪个候选、它的 tag 提交与清单，以及记下 GO 结论的提交与文件 |
| 镜像 | 发布仓库里 `v1.0.0-rc.7` 的 35 个镜像（产品、测试夹具与第三方镜像，colvenna CLI，以及 Colvenna Catalog 发布版的镜像与 chart 的副本），以同一个 digest 另打上正式版的 tag：`v1.0.0`、`v1.0.0-<变体>`，第三方镜像 `v1.0.0-<名字>`。候选的 tag 照旧 |
| Helm chart | 本仓库的四个 chart 没有改动，版本号与候选的相同（`colvenna-bi` 0.1.0、`colvenna-console` 0.1.0、`colvenna-monitoring` 0.1.0、`colvenna-operator` 0.1.0）：安装时取自 tag 的工作区，以清单里各 chart 目录的 git tree 固定（`charts.<chart>.tree`）。colvenna-catalog 的 chart 是 Colvenna Catalog `v1.0.0` 的发布物，按 digest 固定（`catalog.chart`，版本 `1.0.0`），本仓库不重新打包 |
| 晋升日期 | 2026-11-04 |

按正式版安装，同样不构建镜像（[部署指南](https://github.com/colvenna/colvenna-lake/blob/v1.0.0/docs/manual/install.md)）：

```bash
git fetch origin --tags                                    # 晋升记录里的提交都要在本地
git checkout v1.0.0
export AWS_PROFILE=<目标账号的 profile>
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> [COLVENNA_ENTRY_DOMAIN=<入口域名>] \
  deploy/install.sh --release v1.0.0 [--fixtures]
```

## 2. 交付物

| 交付物 | 固定版本 | 状态 |
| --- | --- | --- |
| 用户手册 | [`docs/manual/user.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/user.md) | 完成；第 17 节写明上游 Paimon 的限制（[#6](https://github.com/colvenna/colvenna-lake/issues/6)） |
| 部署指南 | [`docs/manual/install.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/install.md) | 完成；第 7 节写明两条平台 grant、两个 catalog token 与 catalog 的 CA 证书的续期规则（[#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24)）与声明了 `quality` 的分层表的升级（[#28](https://github.com/colvenna/colvenna-lake/issues/28)），第 3 节写明按正式版安装（[#30](https://github.com/colvenna/colvenna-lake/issues/30)） |
| 安装脚本 | [`deploy/install.sh`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/deploy/install.sh)，清单的 `installer` 记有它的 blob | 完成 |
| 成本估算 | [`docs/manual/cost.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/cost.md) | 完成；内容与 `v1.0.0-rc.6` 的相同，口径与 `v1.0.0-rc.3` 相同：资源按 `v1.0.0-rc.1` 核对，`v1.0.0-rc.2` 起新增的第三方镜像仓库按上游镜像的大小估算计入（文档第 1、2 节写明了口径）。没有按本候选重新取价与核对；发布镜像仓库里 rc.6 起多出的 `charts/colvenna-catalog`（Colvenna Catalog 的 chart，10 个文件）仍没有计入 |
| UAT 用例 | [`docs/uat/cases.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/uat/cases.md)，清单的 `uat_cases` 记有它的提交与 blob | 完成 |
| 完整 UAT 报告 | [`docs/uat/reports/v1.0.0-rc.7/report.md`](https://github.com/colvenna/colvenna-lake/blob/06b44f0f55c35db61af662394c5927f0f4389016/docs/uat/reports/v1.0.0-rc.7/report.md) | 完成（结果见第 5 节） |
| 独立性能测试文档 | [`docs/performance/v1.0.0-rc.7.md`](https://github.com/colvenna/colvenna-lake/blob/06b44f0f55c35db61af662394c5927f0f4389016/docs/performance/v1.0.0-rc.7.md) | 完成（结论 PASS，见第 5 节） |

## 3. 支持的配置

与清单的 `configurations` 一致：

- 安装方式：只装平台（`deploy/install.sh`），或带测试夹具（`--fixtures`）。
- 区域：本候选在 `us-west-2`、`us-east-2` 上验收。安装脚本接受任一标准区域（分区 `aws`），其余区域没有对本候选做验收；中国区与 GovCloud 不支持。

## 4. 性能验收口径

执行之前固定，与清单的 `performance` 一致：

| 项 | 值 |
| --- | --- |
| 规模 | default scale：写入节点 2 台、查询节点 2 台 |
| 负载 | CDC 200 变更/秒、Kafka 2,000 条/秒、看板 10 QPS |
| 持续 | 稳态 2 小时 |
| 目标 | 各链路的 p95/p99，见 [`docs/design/acceptance-contract.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/design/acceptance-contract.md#21-测量协议) |
| 方法 | [`docs/uat/performance.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/uat/performance.md) |

## 5. 验收结论

| 项 | 结果 | 依据 |
| --- | --- | --- |
| UAT：`us-west-2`（`uat`、`plat`） | PASS 43、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 43 条）；每个运行都是一次通过，没有重跑，没有人工判定 | UAT 报告的第 4.1、5 节 |
| UAT：`us-east-2`（`uate2`） | PASS 41、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 41 条）；每个运行都是一次通过，没有重跑，没有人工判定。UAT-DEP-07（卸载 `uate2`）在性能运行之后，4/4 | UAT 报告的第 4.2、5 节 |
| 性能验收 | 达到第 4 节的口径：结论 PASS。判定的 43 行全部 PASS（路径表 24 行、检查表 19 行），另有 2 行只观测。各链路、各种变更的 p95/p99 都在目标之内，而且余量很大：p95 最长的是源库 → DWS 看板，151 秒，目标 15 分钟；7 层都稳态；正确性、恰好一次、负载速率、时钟与清理都 PASS，没有因时钟作废的样本；看板运行的功能检查 19/19 | [性能测试文档](https://github.com/colvenna/colvenna-lake/blob/06b44f0f55c35db61af662394c5927f0f4389016/docs/performance/v1.0.0-rc.7.md)的第 1、5 节 |
| 安全：暴露面与拒绝证据 | 通过：两个区域各 64 项检查全部 PASS；公网可达的资源三类请求的每一类都 PASS 或 N/A（每个区域 6 项 N/A，理由见安全一节）；账号所有已启用区域没有 Lambda 函数 URL；证据里没有凭证。矩阵引用的 UAT 负向检查都取自第一次运行 | [安全一节](https://github.com/colvenna/colvenna-lake/blob/06b44f0f55c35db61af662394c5927f0f4389016/release/v1.0.0-rc.7/security.md) |
| 改名门禁（[#2](https://github.com/colvenna/colvenna-lake/issues/2)） | PASS：在 tag 上源码扫描（`deploy/scripts/rename/old_name.py`）退出 0，旧名只在例外清单的 21 条里；清单的 14 个镜像与 Colvenna Catalog 的 chart，共 15 个制品按 digest 扫描，357 处命中全部是第三方原文，与 rc.6 的逐处相同，第一方 0 处；按类别核对了映射；不需要迁移；UAT 与安全检查在新名字下复测 | [改名门禁的记录](https://github.com/colvenna/colvenna-lake/blob/06b44f0f55c35db61af662394c5927f0f4389016/release/v1.0.0-rc.7/rename.md) |
| CI | `PASS`：rc.6 与 rc.7 的 tag 之间合入的每个 PR，在确切的 head 提交上 GitHub Actions（`.github/workflows/lint.yml`）的全部 8 个作业都成功（2026-10-30 起的口径，见 CONTRIBUTING.md）：[#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)、[#25](https://github.com/colvenna/colvenna-lake/pull/25)、[#26](https://github.com/colvenna/colvenna-lake/pull/26)、[#27](https://github.com/colvenna/colvenna-lake/pull/27)、[#29](https://github.com/colvenna/colvenna-lake/pull/29)、[#31](https://github.com/colvenna/colvenna-lake/pull/31)、[#32](https://github.com/colvenna/colvenna-lake/pull/32)、[#33](https://github.com/colvenna/colvenna-lake/pull/33) 与清单 PR [#35](https://github.com/colvenna/colvenna-lake/pull/35)；tag 的提交在 main 上的运行也是 8/8 | 各 PR 的 GitHub Actions 运行 |
| 发布结论 | **GO**。两个区域的必需 UAT 全部 PASS（`us-west-2` 43/43、`us-east-2` 41/41），运维执行的用例（UAT-DEP-01、UAT-DEP-02、两个区域的 UAT-DEP-07、UAT-DEP-09）都在其中且都 PASS；性能验收 PASS；安全两个区域各 64/64；改名门禁 PASS；七项交付物齐全；CI `PASS`。已知限制见第 7 节，其中上游 Paimon 的限制（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）写在用户手册第 17 节，不阻塞发布 | 发布检查 [#2](https://github.com/colvenna/colvenna-lake/issues/2) |

## 6. 变更

相对 `v1.0.0-rc.6`，是它的阻塞项与同一类问题的修复、[#28](https://github.com/colvenna/colvenna-lake/issues/28) 的修复，以及正式版的晋升工具。变更已进入部署指南与运维 LLD；UAT 用例、必需用例集合与性能验收的口径都没有变；成本估算没有按本候选更新（第 2 节）：

- **平台身份的 catalog grant 续期（[#22](https://github.com/colvenna/colvenna-lake/issues/22)，PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）。** `install.sh` 每次都按条款核对平台的两条 catalog 级 `MANAGE` grant（平台身份 `admin` 7 天，`colvenna-operator` 90 天）：已过期、剩余不到有效期的一半、到期比条款允许的晚一天以上或列不同时，删掉重建。rc.6 的 UAT-CAT-05 因它 FAIL。
- **平台的两个 catalog token 续签（[#23](https://github.com/colvenna/colvenna-lake/issues/23)，PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)）。** 管理员 token 与 Operator 的 token（各 90 天）每次安装都核对：被 catalog 拒绝或剩余有效期不到 `TOKEN_RENEW_DAYS`（默认 30 天）时重签，token 的 id 记进 Secret。
- **catalog 的私有 CA 证书重签（[#24](https://github.com/colvenna/colvenna-lake/issues/24)，PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)）。** 已过期或剩余有效期不到 `CA_RENEW_DAYS`（默认 90 天）时，用同一个密钥与名称重签一张（825 天），catalog 的 Pod 随之滚动；旧 CA 证书仍能校验新签的服务端证书。
- **分层表的层延迟（[#28](https://github.com/colvenna/colvenna-lake/issues/28)，PR [#31](https://github.com/colvenna/colvenna-lake/pull/31)）。** 声明了 `quality` 的分层表，作业对每张流读上游只读一次（原来读两次，两个读端共用一个 Paimon consumer，层延迟指标因此与标记不一致）。在这之前部署的这类表，升级之后要按用户手册第 4.3 节逐个重建（部署指南第 7 节）。指标余下的小偏差由 [#34](https://github.com/colvenna/colvenna-lake/issues/34) 跟踪。
- **正式版的晋升工具（[#30](https://github.com/colvenna/colvenna-lake/issues/30)，PR [#32](https://github.com/colvenna/colvenna-lake/pull/32)）。** `deploy/scripts/release/promote.sh` 把判为 GO 的候选晋升为正式版，不重新构建：清单指向候选的制品，按 digest；`install.sh --release` 与 `check-release.sh` 接受正式版的名字与它的晋升记录。晋升 `v1.0.0` 时在 AWS 上实跑过（PR [#38](https://github.com/colvenna/colvenna-lake/pull/38)，见第 7 节）。
- **测试缺陷的修复（[#18](https://github.com/colvenna/colvenna-lake/issues/18)、[#20](https://github.com/colvenna/colvenna-lake/issues/20)，PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)）。** 发布清单拒绝（UAT-DEP-08）的临时提交不再执行 clone 里的钩子，并按 schema 3 的清单改写产品镜像；只改测试。
- **上游 [Apache Paimon](https://paimon.apache.org/) 的限制（[#6](https://github.com/colvenna/colvenna-lake/issues/6)，没有变）。** Paimon 2.0.0 的 Flink 写入端（`CommitterOperator`）以流模式写有界输入、写入并行度大于 1 时，后结束的那个写入子任务的收尾数据可能被静默丢弃，作业照常以 FINISHED 结束，没有报错。
  - 条件，同时成立时才会发生：流模式且开了 checkpoint；输入有界；写入并行度大于 1，且各写入子任务在不同的 checkpoint 之后结束；最后一个子任务的输入结束赶在提交器处理上一个“checkpoint 完成”之前到达。
  - 不受影响：批模式、写入并行度 1、无界输入；平台自己管理的作业不受影响。受影响的是用户自己经 catalog 以流模式写有界输入的 Flink 作业。
  - 绕法：有界写入用批模式（`execution.runtime-mode=batch`）；一定要用流模式时，把写入并行度设为 1（目标表上的 `sink.parallelism`，或整个作业的并行度）。
  - 用户手册第 17 节与 README 写明了这个限制。UAT-CAT-01/04 的流写步骤 `streaming-append-commits` 固定写入并行度为 1；验收覆盖因此缩小，见第 7 节与 UAT 报告第 6 节。
  - 上游的 [apache/paimon#9236](https://github.com/apache/paimon/issues/9236) 还没有修。[#6](https://github.com/colvenna/colvenna-lake/issues/6) 保持打开，直到上游发布修复、本项目升级到含修复的 Paimon。

## 7. 已知限制

用户手册第 17 节之外，本候选的：

- 分层表的层延迟指标按轮次配对提交：两轮之间落了两个提交时偏高一个 checkpoint 间隔，`lastDataCommit` 又先于 position 读取（[#34](https://github.com/colvenna/colvenna-lake/issues/34)）。看板与告警 `ColvennaLayerLatencyP95` 用这个指标，偏差离 120 秒的门槛还远；本次 UAT-MON-01 两个区域都通过。
- 声明了 `quality` 的分层表的升级（[#28](https://github.com/colvenna/colvenna-lake/issues/28) 的修复之后）：部署指南写明要逐个重建，Operator 在 savepoint 对不上时回到旧作业，这一点是按 Flink 恢复 savepoint 的规则推断的，没有实测；本次升级时 `uat` 上没有这类表，也没有用例走这条路径。
- [#22](https://github.com/colvenna/colvenna-lake/issues/22)、[#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24) 的修复在真实环境里走到的是落进续期余量的 grant 与调大阈值之后提前续期的 token、证书（UAT 报告第 5 节）；已经过期的情况由离线测试覆盖。
- 正式版的晋升（[#30](https://github.com/colvenna/colvenna-lake/issues/30)）2026-11-04 在 AWS 上实跑：`promote.sh` 把 `v1.0.0-rc.7` 晋升为 `v1.0.0`，发布仓库里候选的 35 个镜像以同一个 digest 另打上正式版的 tag（PR [#38](https://github.com/colvenna/colvenna-lake/pull/38)）。按正式版安装只实跑到安装之前：在 tag `v1.0.0` 的全新 checkout 上，`check-release.sh v1.0.0 --fixtures` 通过；`install.sh --release v1.0.0` 以一套新环境跑完了安装前的全部检查，只执行了本地工具一步，没有在 AWS 上建任何东西。按正式版的完整安装没有实测；它装的镜像与 `v1.0.0-rc.7` 的是同一组 digest，候选的完整安装在本次 UAT 中验证过。
- catalog 的默认来源 GitHub Container Registry 还没有实跑：catalog 公开之前只能经 `COLVENNA_CATALOG_REGISTRY` 取（[#10](https://github.com/colvenna/colvenna-lake/issues/10)，[#4](https://github.com/colvenna/colvenna-lake/issues/4) 的 M3）。
- 只有一个 AWS 账号：跨账号安装没有实测。
- 验收覆盖的缩小（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）：UAT-CAT-04（与 UAT-CAT-01）的流写步骤固定写入并行度 1，这一步仍验证带 checkpoint 的写入经 catalog 多次提交且不丢行，但不覆盖多个写入子任务的数据进同一个提交。上游修复发布、升级 Paimon 之后撤掉。

## 8. 未关闭的问题

| issue | 影响 | 是否阻塞发布 |
| --- | --- | --- |
| [#34](https://github.com/colvenna/colvenna-lake/issues/34) | 层延迟指标的小偏差（至多一个 checkpoint 间隔）；UAT-MON-01 本次两个区域都通过 | 否 |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) | 上游 Paimon 2.0.0：流模式写有界输入、写入并行度大于 1 时可能静默丢数据；用户手册写明绕法，UAT-CAT-01/04 的流写步骤固定并行度 1 | 否：上游的问题，平台自己的作业不受影响 |
| [#10](https://github.com/colvenna/colvenna-lake/issues/10) | 取 catalog 的发布物：改 pin 与经 `COLVENNA_CATALOG_REGISTRY` 的实跑都已完成；默认来源 GitHub Container Registry 的实跑，要等 catalog 公开之后的候选（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)） | 否 |
| [#5](https://github.com/colvenna/colvenna-lake/issues/5) | 切换到 Colvenna Catalog：两个区域都验收过，UAT-CAT-01 到 12 全部 PASS，本候选 GO。保持打开，等 M3 用默认来源 GitHub Container Registry 实跑之后再关闭（[#10](https://github.com/colvenna/colvenna-lake/issues/10)） | 否 |
| [#4](https://github.com/colvenna/colvenna-lake/issues/4) | v1.0：GO 候选（M2）、公开与 archive（M3）。本候选是 GO 的候选；公开与 archive 在后面 | 否：跟踪 issue |
| [#2](https://github.com/colvenna/colvenna-lake/issues/2) | 发布检查：本说明是它对本候选的结论 | — |
| [#1](https://github.com/colvenna/colvenna-lake/issues/1) | 第二朵云未开展（不在本次范围） | 否 |
| [#3](https://github.com/colvenna/colvenna-lake/issues/3) | 按积压自动调度只写不压的 ODS 表的压缩与过期（改进，不在本候选的范围） | 否 |

rc.6 的阻塞项 [#22](https://github.com/colvenna/colvenna-lake/issues/22) 与同一类的 [#23](https://github.com/colvenna/colvenna-lake/issues/23)、[#24](https://github.com/colvenna/colvenna-lake/issues/24)，以及 [#28](https://github.com/colvenna/colvenna-lake/issues/28)，都已关闭：修复在本候选里，并在本次的运维检查与 UAT 中得到验证（UAT 报告第 5、6 节）。[#30](https://github.com/colvenna/colvenna-lake/issues/30) 已关闭，晋升工具在本候选里。

## 9. 升级与兼容

开发阶段不保证版本之间的升级兼容，不做跨版本回退演练。本候选声明支持的升级、重建与恢复路径见部署指南，并在 UAT 中验证。

## 10. 组件与商标

本候选建立在 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（含 Flink CDC 与 Flink Kubernetes Operator 两个子项目）与 [Apache Doris](https://doris.apache.org/) 之上，另用到 [Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/) 与 [Apache Kafka](https://kafka.apache.org/)：

- 查询引擎镜像（清单的 `query-engine-fe`、`query-engine-be`，仓库 `colvenna-lake-release/query-engine`）是基于 Apache Doris 4.1.4 的修改构建：自带的 Paimon 1.4.2 读取器换成了用 Apache Doris 源码对 Apache Paimon 2.0.0 编译的读取器，BE 的 `java-udf-jar-with-dependencies.jar` 去掉了其中的 Paimon SDK 类，另加 PostgreSQL JDBC 驱动。它不是 ASF 的发布，也没有得到 ASF 的认可。
- Flink 基础镜像（`flink`，仓库 `colvenna-lake-release/flink-base`）是官方 Apache Flink 1.20.4 镜像加 Apache Paimon 2.0.0 的 jar 与 JDBC 驱动。
- 第三方镜像（`third_party`、`fixtures.third_party`）是上游镜像按 digest 原样复制的。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset and Superset are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目。其他各方的商标与各镜像的命名依据，见本候选的[商标与命名](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/release/trademarks.md)。

> 注：本文中带链接的 issue 与 PR 编号（如 [#22](https://github.com/colvenna/colvenna-lake/issues/22)、PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）是本仓库现在的；不带链接的编号来自本项目早期的私有仓库，本仓库里没有对应的页面。
