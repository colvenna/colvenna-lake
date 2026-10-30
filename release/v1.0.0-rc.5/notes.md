---
title: "Colvenna Lake v1.0.0-rc.5 发布说明"
date: 2026-10-27
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

# Colvenna Lake v1.0.0-rc.5 发布说明

## 1. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.5`（annotated tag） |
| 镜像与安装所用的源码 | 提交 `b6b884b0`（`b6b884b0a87b24ca318e9140a1b1ebfff9310bdd`）。tag `v1.0.0-rc.5` 指向的提交与它相比，只多了 `release/v1.0.0-rc.5/` |
| 发布清单 | [`release/v1.0.0-rc.5/manifest.json`](manifest.json)：全部镜像（含第三方镜像）在发布仓库里的仓库名、tag 与 digest，colvenna CLI 的 SHA-256、Helm chart、安装脚本、UAT 用例文档的版本、支持的配置、必需用例集合与性能验收口径。清单不写发布仓库的地址与账号：安装时由 `COLVENNA_RELEASE_REGISTRY` 给出 |
| Helm chart | 各 chart 的版本号都还是 `0.1.0`，不随候选变化；固定它们的是清单里各 chart 目录的 git tree（`charts.<chart>.tree`） |
| 生成日期 | 2026-10-27 |

按清单安装，不在安装时构建镜像。新环境要给全部设置，之后同一环境只需 `COLVENNA_ENV` 与 `COLVENNA_RELEASE_REGISTRY`（[部署指南](../../docs/manual/install.md)）：

```bash
git checkout v1.0.0-rc.5
export AWS_PROFILE=<目标账号的 profile>                    # 安装者自己的凭证，属于 COLVENNA_ACCOUNT
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com   # 保存本候选镜像的发布仓库
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> [COLVENNA_ENTRY_DOMAIN=<入口域名>] \
  deploy/install.sh --release v1.0.0-rc.5 [--fixtures]      # 不加 --fixtures 只装平台；加上则带测试夹具
```

## 2. 交付物

| 交付物 | 固定版本 | 状态 |
| --- | --- | --- |
| 用户手册 | [`docs/manual/user.md`](../../docs/manual/user.md) | 完成 |
| 部署指南 | [`docs/manual/install.md`](../../docs/manual/install.md) | 完成 |
| 安装脚本 | [`deploy/install.sh`](../../deploy/install.sh)，清单的 `installer` 记有它的 blob | 完成 |
| 成本估算 | [`docs/manual/cost.md`](../../docs/manual/cost.md) | 完成；与 `v1.0.0-rc.4` 的相同，口径与 `v1.0.0-rc.3` 相同：资源按 `v1.0.0-rc.1` 核对，`v1.0.0-rc.2` 起新增的第三方镜像仓库按上游镜像的大小估算计入（文档第 1、2 节写明了口径）。没有按本候选重新取价与核对 |
| UAT 用例 | [`docs/uat/cases.md`](../../docs/uat/cases.md)，清单的 `uat_cases` 记有它的提交与 blob | 完成 |
| 完整 UAT 报告 | [`docs/uat/reports/v1.0.0-rc.5/report.md`](../../docs/uat/reports/v1.0.0-rc.5/report.md) | 完成（结果见第 5 节） |
| 独立性能测试文档 | [`docs/performance/v1.0.0-rc.5.md`](../../docs/performance/v1.0.0-rc.5.md) | 完成（结论 FAIL，见第 5 节） |

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
| 目标 | 各链路的 p95/p99，见 [`docs/design/acceptance-contract.md`](../../docs/design/acceptance-contract.md#21-测量协议) |
| 方法 | [`docs/uat/performance.md`](../../docs/uat/performance.md) |

## 5. 验收结论

| 项 | 结果 | 依据 |
| --- | --- | --- |
| UAT：`us-west-2`（`uat`、`plat`） | PASS 43、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 43 条）；没有失败或重跑的运行，没有人工判定 | UAT 报告的第 4.1、5 节 |
| UAT：`us-east-2`（`uate2`） | PASS 41、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 41 条）；没有失败或重跑的运行，没有人工判定 | UAT 报告的第 4.2、5 节 |
| 性能验收 | 未达到第 4 节的口径：结论 FAIL。判定的 43 行中 42 行 PASS（另有 2 行只观测）。唯一的 FAIL 是看板运行的功能检查（19 项中 18 项 PASS）：`adhoc-bounded` 要求 FE 的采样覆盖整个突发窗口，采样 Pod 的第一个样本晚于窗口起点 5 秒以上，判不了同时运行数；FE 采到的同时运行数没有超过上限 2。这是测试缺陷 #293，由 #294 在本候选之后修复。各链路、各种变更的 p95/p99 都在目标之内，而且余量很大；7 层都稳态；正确性、恰好一次、负载速率、时钟与清理都 PASS。按执行之前固定的规则，本候选不改判 | 性能测试文档的第 1、5 节 |
| 安全：暴露面与拒绝证据 | 通过：两个区域各 64 项检查全部 PASS；公网可达的资源三类请求的每一类都 PASS 或 N/A（每个区域 6 项 N/A，理由见安全一节）；账号所有已启用区域没有 Lambda 函数 URL；证据里没有凭证 | [安全一节](security.md) |
| 改名门禁（#271） | PASS：在 tag 上源码扫描（`deploy/scripts/rename/old_name.py`）退出 0，旧名只在例外清单的 27 条里；清单的 14 个镜像按 digest 扫描，357 处命中全部是第三方原文，第一方 0 处（`v1.0.0-rc.4` 的 1 处已由 #288 修复）；按类别核对了映射；不需要迁移；UAT 与安全在新名字下复测通过 | [改名门禁的记录](rename.md) |
| CI | `WAIVED_BY_REQUESTER_NOT_PASS`：GitHub Actions 因账单拒绝启动，请求方豁免。`v1.0.0-rc.4` 与 `v1.0.0-rc.5` 两个 tag 之间合入的 PR（#284（`v1.0.0-rc.3` 的记录）、#288、#289、#290）都在确切 SHA 上本地执行了 `.github/workflows` 的全部 9 个作业，结果在各 PR 的评论里；tag 之后合入的 #291（`v1.0.0-rc.4` 的记录）、#292（#271 的公开仓库文件）与 #294（#293）也一样。改名门禁不受这项豁免影响 | 各 PR 的评论 |
| 发布结论 | **NO-GO**：性能验收 FAIL（测试缺陷 #293，已在 main 上修复）。此外，维护者 2026-10-28 决定 v1.0 使用独立的 Colvenna Catalog（`colvenna/colvenna-catalog`）替代本仓库自带的 colvenna-catalog（#295），所以下一个候选在这次切换之后；两个仓库都准备好之后一起公开，不做法律审查。UAT、安全与改名门禁都通过 | 发布检查 #181 |

## 6. 变更

相对 `v1.0.0-rc.4`，是它的两个问题的修复。#287 的改动已进入 UAT 用例（UAT-MON-01 的输入与步骤）；#286 只改 catalog 内部的一个常量并加了仓库的检查，用户手册、部署指南与成本估算不需要改：

- catalog 迁移用的 PostgreSQL advisory lock 的键，从旧名的 ASCII 改为新名字的 ASCII（`colvcatL`）；新加 pre-commit 检查 `deploy/scripts/rename/old_name.py`，词、驼峰标识的中段、文件路径与十六进制字面量里的旧名都查，例外只能逐条列在 `deploy/scripts/rename/exceptions.tsv` 里并写明理由；CI 的 deploy 作业也运行它的单元测试（#286，#288）。
- UAT-MON-01 的延迟标记改为在一个 MySQL 会话里每 3 秒一笔、共 10 笔，覆盖 checkpoint 周期的各个相位；容差不变。只改测试，产品不变（#287，#289）。

## 7. 已知限制

- 性能运行里，看板运行的 `adhoc-bounded` 在 FE 的采样 Pod 晚出第一个样本时误判，本候选的性能验收因此 FAIL；只影响测试，产品不变（#293，已由 #294 在 main 上修复，不在本候选里）。
- 本候选的 catalog 是本仓库自带的 colvenna-catalog。v1.0 改用独立的 Colvenna Catalog（#295），本候选的 catalog 部分不是 v1.0 的形态。
- #233 的重跑、#237 的 `backing-up` 与 #238 的 `RowsMissing` 这几条修复的路径，本次在真实环境里仍没有走到，只有离线测试覆盖（UAT 报告第 6 节）。
- `doris` 步骤只等镜像的变化；只改配置、不换镜像的 DorisCluster 规格变化，仍要等 Operator 更新 StatefulSet 之后才看得出来（#261，代码注释写明）。
- 改名扫描不读 PNG、GIF 与 MP4 的内容，只按路径判；宣传视频与 GIF 在 `v1.0.0-rc.3` 之前已按新名字重新渲染，本次没有逐帧复查（改名门禁的记录第 6 节）。
- 只有一个 AWS 账号：跨账号安装没有实测。

## 8. 未关闭的问题

| issue | 影响 | 是否阻塞发布 |
| --- | --- | --- |
| #295 | v1.0 改用独立的 Colvenna Catalog：删掉本仓库的 catalog 服务端与 chart，按 digest 用 Colvenna Catalog 的发布物；切换之后的候选要重新通过 UAT、性能、安全与改名门禁 | 是：v1.0 要在切换之后的候选上判定 |
| #271 | 改名：本候选的改名门禁 PASS（[改名门禁的记录](rename.md)）。尚余 v1.0 正式版 GO 之后公开到 `colvenna/` 并 archive 本仓库（第 4 节）；按 #295，两个仓库都准备好之后一起公开 | 否：本候选的改名门禁 PASS |
| #245 | MySQL 源一张表久不 Ready 的根因未明；只有诊断，本次没有复现 | 否 |
| #248 | git 历史与已关闭的 issue/PR 里的旧部署值：维护者 2026-10-25 决定不改写历史，本仓库保持 private，公开时只推一个 init 提交 | 否 |
| #33 | 第二朵云未验证（不在本次范围） | 否 |
| #265 | 按积压自动调度只写不压的 ODS 表的压缩与过期（改进，不在本候选的范围） | 否 |

Issue #286 与 #287 已关闭，修复（#288、#289）在本候选里，并在本次验收中得到验证（UAT 报告第 6 节）。#293 已由 #294 在 main 上修复并关闭，修复不在本候选里，所以本候选的性能验收仍是 FAIL；修复后的测试要在下一个候选的性能运行里实际走到。

## 9. 升级与兼容

开发阶段不保证版本之间的升级兼容，不做跨版本回退演练。本候选声明支持的升级、重建与恢复路径见部署指南，并在 UAT 中验证。

## 10. 组件与商标

本候选建立在 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（含 Flink CDC 与 Flink Kubernetes Operator 两个子项目）与 [Apache Doris](https://doris.apache.org/) 之上，另用到 [Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/) 与 [Apache Kafka](https://kafka.apache.org/)：

- 查询引擎镜像（清单的 `query-engine-fe`、`query-engine-be`，仓库 `colvenna-lake-release/query-engine`）是基于 Apache Doris 4.1.4 的修改构建：自带的 Paimon 1.4.2 读取器换成了用 Apache Doris 源码对 Apache Paimon 2.0.0 编译的读取器，BE 的 `java-udf-jar-with-dependencies.jar` 去掉了其中的 Paimon SDK 类，另加 PostgreSQL JDBC 驱动。它不是 ASF 的发布，也没有得到 ASF 的认可。
- Flink 基础镜像（`flink`，仓库 `colvenna-lake-release/flink-base`）是官方 Apache Flink 1.20.4 镜像加 Apache Paimon 2.0.0 的 jar 与 JDBC 驱动。
- 第三方镜像（`third_party`、`fixtures.third_party`）是上游镜像按 digest 原样复制的。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset and Superset are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目。其他各方的商标与各镜像的命名依据，见本候选的[商标与命名](../trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #293）、提交（如 `b6b884b0`）和 tag（如 `v1.0.0-rc.5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
