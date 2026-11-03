---
title: "Colvenna Lake v1.0.0-rc.6 发布说明"
date: 2026-11-01
status: final
---

<!--
发布说明的模板。deploy/scripts/release/make-manifest.sh 生成候选时，从清单填好候选的身份（候选名、
镜像所用的提交、日期）、验收的区域与性能验收口径，写成 release/<候选>/notes.md，随清单进入 tag 所在的
提交。那时的这份说明只记录候选的身份；尖括号里的内容（交付物状态、报告链接、结论）等材料齐备后由
发布负责人补全，另行提交到 main 的 release/<候选>/notes.md，发布检查链接那个提交。规则：

- 每个链接都指向不可变的版本：tag，或完整的提交 SHA。不用分支名，不用 main。第 10 节的 ASF 项目官网
  例外：ASF 的商标政策要求链接项目官网。
- tag 之后才合入的材料（UAT 报告、性能文档等），链接写到它们合入的那个提交。
- 还没有的材料，状态写“未完成”并链接跟踪它的 issue，不留空。只要有一项未完成，或任一必需用例
  不是 PASS，或性能验收未达到下面固定的口径，这份说明就只能给出 NO-GO。
- 不写口令、密钥或其他秘密；原始证据只写它的位置。
-->

# Colvenna Lake v1.0.0-rc.6 发布说明

## 1. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.6`（annotated tag） |
| 镜像与安装所用的源码 | 提交 [`803357a5`](https://github.com/colvenna/colvenna-lake/commit/803357a57160c24c45b7b58a743698e82ff09d0a)（`803357a57160c24c45b7b58a743698e82ff09d0a`）。tag `v1.0.0-rc.6` 指向的提交与它相比，只多了 `release/v1.0.0-rc.6/` |
| 发布清单 | [`release/v1.0.0-rc.6/manifest.json`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/release/v1.0.0-rc.6/manifest.json)：全部镜像（含第三方镜像）在发布仓库里的仓库名、tag 与 digest，colvenna CLI 的 SHA-256、Helm chart、安装脚本、UAT 用例文档的版本、支持的配置、必需用例集合与性能验收口径。清单不写发布仓库的地址与账号：安装时由 `COLVENNA_RELEASE_REGISTRY` 给出 |
| Helm chart | 各 chart 的版本号都还是 `0.1.0`，不随候选变化；固定它们的是清单里各 chart 目录的 git tree（`charts.<chart>.tree`） |
| 生成日期 | 2026-11-01 |

按清单安装，不在安装时构建镜像。新环境要给全部设置，之后同一环境只需 `COLVENNA_ENV` 与 `COLVENNA_RELEASE_REGISTRY`（[部署指南](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/manual/install.md)）：

```bash
git checkout v1.0.0-rc.6
export AWS_PROFILE=<目标账号的 profile>                    # 安装者自己的凭证，属于 COLVENNA_ACCOUNT
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com   # 保存本候选镜像的发布仓库
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> [COLVENNA_ENTRY_DOMAIN=<入口域名>] \
  deploy/install.sh --release v1.0.0-rc.6 [--fixtures]      # 不加 --fixtures 只装平台；加上则带测试夹具
```

## 2. 交付物

| 交付物 | 固定版本 | 状态 |
| --- | --- | --- |
| 用户手册 | [`docs/manual/user.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/manual/user.md) | 完成；第 17 节写明上游 Paimon 的限制（[#6](https://github.com/colvenna/colvenna-lake/issues/6)） |
| 部署指南 | [`docs/manual/install.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/manual/install.md) | 完成；第 2 节写明 catalog 发布物的来源与 `COLVENNA_CATALOG_REGISTRY`（[#10](https://github.com/colvenna/colvenna-lake/issues/10)） |
| 安装脚本 | [`deploy/install.sh`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/deploy/install.sh)，清单的 `installer` 记有它的 blob | 完成 |
| 成本估算 | [`docs/manual/cost.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/manual/cost.md) | 完成；内容与 `v1.0.0-rc.5` 的相同（迁移到本仓库时只改了链接），口径与 `v1.0.0-rc.3` 相同：资源按 `v1.0.0-rc.1` 核对，`v1.0.0-rc.2` 起新增的第三方镜像仓库按上游镜像的大小估算计入（文档第 1、2 节写明了口径）。没有按本候选重新取价与核对；本候选起发布镜像仓库多了 `charts/colvenna-catalog` 一个仓库（Colvenna Catalog 的 chart，10 个文件），文档没有计入 |
| UAT 用例 | [`docs/uat/cases.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/uat/cases.md)，清单的 `uat_cases` 记有它的提交与 blob | 完成 |
| 完整 UAT 报告 | [`docs/uat/reports/v1.0.0-rc.6/report.md`](../../docs/uat/reports/v1.0.0-rc.6/report.md) | 完成（结果见第 5 节） |
| 独立性能测试文档 | [`docs/performance/v1.0.0-rc.6.md`](../../docs/performance/v1.0.0-rc.6.md) | 完成（结论 PASS，见第 5 节） |

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
| 目标 | 各链路的 p95/p99，见 [`docs/design/acceptance-contract.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/design/acceptance-contract.md#21-测量协议) |
| 方法 | [`docs/uat/performance.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/docs/uat/performance.md) |

## 5. 验收结论

| 项 | 结果 | 依据 |
| --- | --- | --- |
| UAT：`us-west-2`（`uat`、`plat`） | PASS 41、FAIL 2、BLOCKED 0、NOT_RUN 0（必需用例 43 条）。FAIL：UAT-CAT-05，产品缺陷 [#22](https://github.com/colvenna/colvenna-lake/issues/22)（平台身份的 catalog grant 过期后不续期）；UAT-MON-01，原因未查明（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）。两处都是第一次运行失败、重跑通过，重跑不计入，以 `--overrides` 人工判为 FAIL | UAT 报告的第 1、4.1、5 节 |
| UAT：`us-east-2`（`uate2`） | PASS 40、FAIL 0、BLOCKED 0、NOT_RUN 1（必需用例 41 条）。NOT_RUN：UAT-DEP-07，协调人决定保留环境给下一个候选，没有卸载 `uate2` | UAT 报告的第 4.2、5 节 |
| 性能验收 | 达到第 4 节的口径：结论 PASS。判定的 43 行全部 PASS（路径表 24 行、检查表 19 行），另有 2 行只观测。各链路、各种变更的 p95/p99 都在目标之内，而且余量很大：p95 最长的是源库 → DWS 看板，134 秒，目标 15 分钟；7 层都稳态；正确性、恰好一次、负载速率、时钟与清理都 PASS；看板运行的功能检查 19/19，`v1.0.0-rc.5` 在这里 FAIL 的 `adhoc-bounded`（早期仓库的测试缺陷 #293）这次通过 | [性能测试文档](../../docs/performance/v1.0.0-rc.6.md)的第 1、5 节 |
| 安全：暴露面与拒绝证据 | 通过：两个区域各 64 项检查全部 PASS；公网可达的资源三类请求的每一类都 PASS 或 N/A（每个区域 6 项 N/A，理由见安全一节）；账号所有已启用区域没有 Lambda 函数 URL；证据里没有凭证。`uat` 的矩阵引用的 UAT-CAT-05 的 5 项检查取自绕行之后的重跑，安全一节写明了这一点；不算这 5 项，仓库桶越权一类也是 PASS | [安全一节](security.md) |
| 改名门禁（[#2](https://github.com/colvenna/colvenna-lake/issues/2)） | PASS：在 tag 上源码扫描（`deploy/scripts/rename/old_name.py`）退出 0，旧名只在例外清单的 21 条里；清单的 14 个镜像与 Colvenna Catalog 的 chart，共 15 个制品按 digest 扫描，357 处命中全部是第三方原文，第一方 0 处；按类别核对了映射；不需要迁移；完整的 UAT 与安全检查都在新名字下复测（结果见上） | [改名门禁的记录](rename.md) |
| CI | `PASS`：本候选的每个 PR 在确切的 head 提交上，GitHub Actions（`.github/workflows/lint.yml`）的全部 8 个作业都成功（2026-10-30 起的口径，见 CONTRIBUTING.md）。tag 里的：[#7](https://github.com/colvenna/colvenna-lake/pull/7)、[#9](https://github.com/colvenna/colvenna-lake/pull/9)、[#11](https://github.com/colvenna/colvenna-lake/pull/11)、[#12](https://github.com/colvenna/colvenna-lake/pull/12)、[#14](https://github.com/colvenna/colvenna-lake/pull/14)、[#16](https://github.com/colvenna/colvenna-lake/pull/16)、[#17](https://github.com/colvenna/colvenna-lake/pull/17)；tag 之后合入 main 的：[#19](https://github.com/colvenna/colvenna-lake/pull/19)、[#21](https://github.com/colvenna/colvenna-lake/pull/21)、[#25](https://github.com/colvenna/colvenna-lake/pull/25)、[#26](https://github.com/colvenna/colvenna-lake/pull/26)、[#27](https://github.com/colvenna/colvenna-lake/pull/27)。本仓库的初始提交没有 PR，它在 main 上的运行也是 8/8。另在 tag 上用 `ci_local.py` 做了一次本地补充，8/8，不是豁免 | 各 PR 的 GitHub Actions 运行 |
| 发布结论 | **NO-GO**。阻塞项是产品缺陷 [#22](https://github.com/colvenna/colvenna-lake/issues/22)：`us-west-2` 的 UAT-CAT-05 FAIL；修复（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）在 tag 之后合入 main，不在本候选里。此外 UAT-MON-01 在 `us-west-2` FAIL（[#28](https://github.com/colvenna/colvenna-lake/issues/28)），UAT-DEP-07 在 `us-east-2` NOT_RUN；同一类的缺陷 [#23](https://github.com/colvenna/colvenna-lake/issues/23)（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)）、[#24](https://github.com/colvenna/colvenna-lake/issues/24)（PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)）也已在 main 上修复，留给下一个候选。[#5](https://github.com/colvenna/colvenna-lake/issues/5) 的切换在两个区域验收过，但按它的接收条件，候选不是 GO，[#5](https://github.com/colvenna/colvenna-lake/issues/5) 不关闭。性能验收、安全与改名门禁都通过，不改变这个结论。环境保留给 `v1.0.0-rc.7` | 发布检查 [#2](https://github.com/colvenna/colvenna-lake/issues/2) |

## 6. 变更

相对 `v1.0.0-rc.5`。本候选是本仓库 `colvenna/colvenna-lake` 构建的第一个候选：早期私有仓库的 main（含 PR #301，即 #300 迁移之前的修正）以一个初始提交迁到本仓库，未关闭的 issue 迁过来，编号改为 [#1](https://github.com/colvenna/colvenna-lake/issues/1) 到 [#6](https://github.com/colvenna/colvenna-lake/issues/6)（PR [#7](https://github.com/colvenna/colvenna-lake/pull/7) 让文档引用新编号；PR [#9](https://github.com/colvenna/colvenna-lake/pull/9) 去掉了初始提交误带的字节码，[#8](https://github.com/colvenna/colvenna-lake/issues/8)）。变更如下，已进入用户手册、部署指南与 UAT 用例；成本估算没有按本候选更新（第 2 节），性能验收的口径不变（第 4 节）：

- **改用独立的 Colvenna Catalog（[#5](https://github.com/colvenna/colvenna-lake/issues/5)）。** 本仓库删掉 catalog 的服务端与 chart，不再构建它们，按 digest 使用 Colvenna Catalog 发布的服务端镜像与 Helm chart（早期仓库的 #295，PR #298）。发布清单升到 schema 3，`catalog` 一节记下发布版、tag 指向的提交、构建所自的提交、两个 digest、chart 的版本、schema 版本与晋升记录；`check-release.sh`、`install.sh --release` 与 `make-manifest.sh` 核对它们；从源码安装也用固定的发布版。现在固定的是 `v1.0.0`（由它的 GO 候选 `v1.0.0-rc.4` 晋升，镜像 digest 与 rc.4 的相同，chart 重新打包为 `1.0.0`，schema 版本 4，PR [#12](https://github.com/colvenna/colvenna-lake/pull/12)）。UAT-CAT-01 到 UAT-CAT-12 不变，UAT-DEP-06 覆盖在已有环境上接管。
- **取 catalog 的发布物（[#10](https://github.com/colvenna/colvenna-lake/issues/10)）。** 默认从 GitHub Container Registry 按 digest 匿名取；`COLVENNA_CATALOG_REGISTRY=<仓库地址>[/<前缀>]` 可改指别的仓库，用于 catalog 公开之前的候选验收（PR [#11](https://github.com/colvenna/colvenna-lake/pull/11)）。本候选的实跑经这个设置从维护者的私有副本（`<the catalog's private release registry>`）取；按清单安装时从本候选的发布仓库（`<发布仓库>`）里的副本取。默认来源的实跑要等 catalog 公开（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)）。
- **上游 [Apache Paimon](https://paimon.apache.org/) 的限制（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）。** Paimon 2.0.0 的 Flink 写入端（`CommitterOperator`）有一个上游缺陷：以流模式写有界输入、写入并行度大于 1 时，后结束的那个写入子任务的收尾数据可能被静默丢弃，作业照常以 FINISHED 结束，没有报错。
  - 条件，同时成立时才会发生：流模式且开了 checkpoint；输入有界；写入并行度大于 1，且各写入子任务在不同的 checkpoint 之后结束；最后一个子任务的输入结束赶在提交器处理上一个“checkpoint 完成”之前到达（提交慢或节点忙时更容易）。
  - 不受影响：批模式、写入并行度 1、无界输入。平台自己管理的作业不受影响：数据库 CDC、Kafka 事件与分层表的作业输入都是无界的，`LakeTask` 以批模式运行。受影响的是用户自己经 catalog 以流模式写有界输入的 Flink 作业（一次性回填、有界源的 `INSERT INTO ... SELECT`），与 catalog 无关。
  - 绕法：有界写入用批模式（`execution.runtime-mode=batch`）；一定要用流模式时，把写入并行度设为 1（目标表上的 `sink.parallelism`，或整个作业的并行度）。
  - 用户手册第 17 节与 README 写明了这个限制（早期仓库的 #297，PR #299）。UAT-CAT-01/04 的流写步骤 `streaming-append-commits` 固定写入并行度为 1；验收覆盖因此缩小，见第 7 节与 UAT 报告第 6 节。
  - 上游的 [apache/paimon#9236](https://github.com/apache/paimon/issues/9236) 还没有修。[#6](https://github.com/colvenna/colvenna-lake/issues/6) 保持打开，直到上游发布修复、本项目升级到含修复的 Paimon，再撤掉已知限制与测试里的并行度 1。
- **候选之前发现、修进 B 的两个产品缺陷。** [#13](https://github.com/colvenna/colvenna-lake/issues/13)（PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)）：ECR 的 `batch-get-image` 按 digest 读时同一个 digest 带几个 tag 就回几份（实测 2026-11-01，us-west-2），`install.sh` 的开始检查数个数，把带两个 tag 的 catalog 镜像当成没有；现在按 digest 核对。[#15](https://github.com/colvenna/colvenna-lake/issues/15)（PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)）：`verify`、`make-manifest.sh` 与元数据恢复把已经结束的 catalog Pod（节点关机留下的 `Failed`）当成副本；现在跳过它们。
- **性能测试的修正（早期仓库的 #293，PR #294）。** 看板运行的 `adhoc-bounded` 在 FE 的采样 Pod 晚出第一个样本时误判；只改性能测试，产品不变。本候选的性能运行走到了这个修复，`adhoc-bounded` PASS。

## 7. 已知限制

用户手册第 17 节之外，本候选新发现的：

- 平台身份 `admin` 的 catalog 级 `MANAGE` grant 7 天后过期，重跑 `install.sh` 也不续期（[#22](https://github.com/colvenna/colvenna-lake/issues/22)，产品缺陷，本候选的阻塞项）。从第一次安装起满 7 天，平台身份就进不了 catalog 的数据面，Operator 也不能再建库、建表。修复（PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）在 main 上，不在本候选里。本次在 `uat` 上的绕法：经 catalog 的管理面删掉过期的那条，建同样的一条。
- 平台的管理员 token 与 Operator 的 token 90 天后过期，重跑 `install.sh` 不续签（[#23](https://github.com/colvenna/colvenna-lake/issues/23)）；catalog 的私有 CA 证书 825 天后过期，安装只在 Secret 不存在时生成，它签发的服务端证书 397 天有效、只在安装时重签（[#24](https://github.com/colvenna/colvenna-lake/issues/24)）。修复（PR [#26](https://github.com/colvenna/colvenna-lake/pull/26)、PR [#27](https://github.com/colvenna/colvenna-lake/pull/27)）在 main 上，不在本候选里；本次都没有走到。
- UAT-MON-01 的 `layer-latency-agrees` 在 `uat` 上失败一次：标记看到的 ODS→DWD 延迟 41 秒，Operator 导出的层延迟只有 9 秒；原因未查明，指标本身有没有算错还没有排除（[#28](https://github.com/colvenna/colvenna-lake/issues/28)）。
- catalog 的默认来源 GitHub Container Registry 还没有实跑：catalog 公开之前只能经 `COLVENNA_CATALOG_REGISTRY` 取（[#10](https://github.com/colvenna/colvenna-lake/issues/10)，[#4](https://github.com/colvenna/colvenna-lake/issues/4) 的 M3）。
- 只有一个 AWS 账号：跨账号安装没有实测。
- 验收覆盖的缩小（[#6](https://github.com/colvenna/colvenna-lake/issues/6)）：UAT-CAT-04（与 UAT-CAT-01）的流写步骤固定写入并行度 1，这一步仍验证带 checkpoint 的写入经 catalog 多次提交且不丢行，但不再覆盖多个写入子任务的数据进同一个提交。上游修复发布、升级 Paimon 之后撤掉。

## 8. 未关闭的问题

| issue | 影响 | 是否阻塞发布 |
| --- | --- | --- |
| [#22](https://github.com/colvenna/colvenna-lake/issues/22) | 平台身份的 catalog grant 7 天后过期且不续期；`us-west-2` 的 UAT-CAT-05 FAIL。修复 PR [#25](https://github.com/colvenna/colvenna-lake/pull/25) 在 main 上，等在一套 grant 已过期的环境上实测 | 是：本候选的阻塞项 |
| [#28](https://github.com/colvenna/colvenna-lake/issues/28) | UAT-MON-01 的 `layer-latency-agrees` 在 `uat` 上失败一次，原因未查明；下一个候选要查明原因并复测通过 | 是：UAT-MON-01 判 FAIL，必需用例不是全部 PASS |
| [#23](https://github.com/colvenna/colvenna-lake/issues/23) | 平台的两个 catalog token 90 天后过期且不续签。修复 PR [#26](https://github.com/colvenna/colvenna-lake/pull/26) 在 main 上，不在本候选里 | 不是本候选判定的阻塞项（本次没有走到）；修复在下一个候选里实测 |
| [#24](https://github.com/colvenna/colvenna-lake/issues/24) | catalog 的私有 CA 证书 825 天后过期且不换。修复 PR [#27](https://github.com/colvenna/colvenna-lake/pull/27) 在 main 上，不在本候选里 | 不是本候选判定的阻塞项（本次没有走到）；修复在下一个候选里实测 |
| [#6](https://github.com/colvenna/colvenna-lake/issues/6) | 上游 Paimon 2.0.0：流模式写有界输入、写入并行度大于 1 时可能静默丢数据；用户手册写明绕法，UAT-CAT-01/04 的流写步骤固定并行度 1 | 否：上游的问题，平台自己的作业不受影响 |
| [#5](https://github.com/colvenna/colvenna-lake/issues/5) | 切换到 Colvenna Catalog：两个区域都验收过（UAT-CAT-01 到 12，`us-west-2` 除 UAT-CAT-05）；按它的接收条件，要在 GO 的候选上关闭 | 否：切换本身没有新的阻塞问题；等 GO 的候选 |
| [#2](https://github.com/colvenna/colvenna-lake/issues/2) | 发布检查：本说明是它对本候选的结论 | — |
| [#4](https://github.com/colvenna/colvenna-lake/issues/4) | v1.0：GO 候选（M2）、公开与 archive（M3）。本候选完成了完整的验收，结论 NO-GO；下一个候选是 `v1.0.0-rc.7` | 否：跟踪 issue |
| [#10](https://github.com/colvenna/colvenna-lake/issues/10) | 取 catalog 的发布物：改 pin（PR [#12](https://github.com/colvenna/colvenna-lake/pull/12)）与经 `COLVENNA_CATALOG_REGISTRY` 的实跑都已完成。PR #12 合入时它被关闭，2026-11-02 重新打开：默认来源 GitHub Container Registry 的实跑还没有做，要等 catalog 公开之后的候选（M3，[#4](https://github.com/colvenna/colvenna-lake/issues/4)） | 否 |
| [#1](https://github.com/colvenna/colvenna-lake/issues/1) | 第二朵云未开展（不在本次范围） | 否 |
| [#3](https://github.com/colvenna/colvenna-lake/issues/3) | 按积压自动调度只写不压的 ODS 表的压缩与过期（改进，不在本候选的范围） | 否 |

Issue [#13](https://github.com/colvenna/colvenna-lake/issues/13) 与 [#15](https://github.com/colvenna/colvenna-lake/issues/15) 已关闭，修复（PR [#14](https://github.com/colvenna/colvenna-lake/pull/14)、PR [#16](https://github.com/colvenna/colvenna-lake/pull/16)）在本候选里，并在本次验收中得到验证（UAT 报告第 5、6 节）。[#18](https://github.com/colvenna/colvenna-lake/issues/18) 与 [#20](https://github.com/colvenna/colvenna-lake/issues/20) 是测试缺陷，已由 PR [#19](https://github.com/colvenna/colvenna-lake/pull/19)、PR [#21](https://github.com/colvenna/colvenna-lake/pull/21) 在 main 上修复并关闭；以修好的测试、以 tag 为基准的重跑，两个区域都 14/14。

## 9. 升级与兼容

开发阶段不保证版本之间的升级兼容，不做跨版本回退演练。本候选声明支持的升级、重建与恢复路径见部署指南，并在 UAT 中验证。

## 10. 组件与商标

本候选建立在 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（含 Flink CDC 与 Flink Kubernetes Operator 两个子项目）与 [Apache Doris](https://doris.apache.org/) 之上，另用到 [Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/) 与 [Apache Kafka](https://kafka.apache.org/)：

- 查询引擎镜像（清单的 `query-engine-fe`、`query-engine-be`，仓库 `colvenna-lake-release/query-engine`）是基于 Apache Doris 4.1.4 的修改构建：自带的 Paimon 1.4.2 读取器换成了用 Apache Doris 源码对 Apache Paimon 2.0.0 编译的读取器，BE 的 `java-udf-jar-with-dependencies.jar` 去掉了其中的 Paimon SDK 类，另加 PostgreSQL JDBC 驱动。它不是 ASF 的发布，也没有得到 ASF 的认可。
- Flink 基础镜像（`flink`，仓库 `colvenna-lake-release/flink-base`）是官方 Apache Flink 1.20.4 镜像加 Apache Paimon 2.0.0 的 jar 与 JDBC 驱动。
- 第三方镜像（`third_party`、`fixtures.third_party`）是上游镜像按 digest 原样复制的。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset and Superset are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目。其他各方的商标与各镜像的命名依据，见本候选的[商标与命名](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.6/release/trademarks.md)。

> 注：本文中带链接的 issue 与 PR 编号（如 [#22](https://github.com/colvenna/colvenna-lake/issues/22)、PR [#25](https://github.com/colvenna/colvenna-lake/pull/25)）是本仓库现在的；不带链接的编号（如 #293、PR #294、#297、PR #298、PR #301）来自本项目早期的私有仓库，本仓库里没有对应的页面。
