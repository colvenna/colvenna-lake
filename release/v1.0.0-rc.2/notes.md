---
title: "Colvenna Lake v1.0.0-rc.2 发布说明"
date: 2026-10-24
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

# Colvenna Lake v1.0.0-rc.2 发布说明

## 1. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.2`（annotated tag） |
| 镜像与安装所用的源码 | 提交 `9ab8b2a7`（`9ab8b2a7dd67e61f17fdedce24aab4d63558bed3`）。tag `v1.0.0-rc.2` 指向的提交与它相比，只多了 `release/v1.0.0-rc.2/` |
| 发布清单 | [`release/v1.0.0-rc.2/manifest.json`](manifest.json)：全部镜像（含第三方镜像）在发布仓库里的仓库名、tag 与 digest，cove CLI 的 SHA-256、Helm chart、安装脚本、UAT 用例文档的版本、支持的配置、必需用例集合与性能验收口径。清单不写发布仓库的地址与账号：安装时由 `COVE_RELEASE_REGISTRY` 给出 |
| Helm chart | 各 chart 的版本号都还是 `0.1.0`，不随候选变化；固定它们的是清单里各 chart 目录的 git tree（`charts.<chart>.tree`） |
| 生成日期 | 2026-10-24 |

按清单安装，不在安装时构建镜像。新环境要给全部设置，之后同一环境只需 `COVE_ENV` 与 `COVE_RELEASE_REGISTRY`（[部署指南](../../docs/manual/install.md)）：

```bash
git checkout v1.0.0-rc.2
export AWS_PROFILE=<目标账号的 profile>                    # 安装者自己的凭证，属于 COVE_ACCOUNT
export COVE_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com   # 保存本候选镜像的发布仓库
COVE_ENV=<环境> COVE_REGION=<区域> COVE_ACCOUNT=<账号> \
  COVE_HOSTED_ZONE_ID=<托管区 ID> COVE_HOSTED_ZONE_NAME=<托管区名> [COVE_ENTRY_DOMAIN=<入口域名>] \
  deploy/install.sh --release v1.0.0-rc.2 [--fixtures]      # 不加 --fixtures 只装平台；加上则带测试夹具
```

## 2. 交付物

| 交付物 | 固定版本 | 状态 |
| --- | --- | --- |
| 用户手册 | [`docs/manual/user.md`](../../docs/manual/user.md) | 完成 |
| 部署指南 | [`docs/manual/install.md`](../../docs/manual/install.md) | 完成 |
| 安装脚本 | [`deploy/install.sh`](../../deploy/install.sh)，清单的 `installer` 记有它的 blob | 完成 |
| 成本估算 | [`docs/manual/cost.md`](../../docs/manual/cost.md) | 完成；资源按 `v1.0.0-rc.1` 核对，本候选新增的第三方镜像仓库按上游镜像的大小估算计入（文档第 1 节写明了口径），没有按本候选重新取价与核对 |
| UAT 用例 | [`docs/uat/cases.md`](../../docs/uat/cases.md)，清单的 `uat_cases` 记有它的提交与 blob | 完成 |
| 完整 UAT 报告 | [`docs/uat/reports/v1.0.0-rc.2/report.md`](../../docs/uat/reports/v1.0.0-rc.2/report.md) | 完成（结果见第 5 节） |
| 独立性能测试文档 | [`docs/performance/v1.0.0-rc.2.md`](../../docs/performance/v1.0.0-rc.2.md) | 完成（结果见第 5 节） |

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
| UAT：`us-west-2`（`uat`、`plat`） | PASS 43、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 43 条） | UAT 报告的第 5.1 节 |
| UAT：`us-east-2`（`uate2`） | PASS 41、FAIL 0、BLOCKED 0、NOT_RUN 0（必需用例 41 条） | UAT 报告的第 5.2 节 |
| 性能验收 | 未达到第 4 节的口径：结论 FAIL。各链路的延迟都在目标之内且余量很大，正确性、恰好一次、负载速率都 PASS；两个终点层（`external`、`dws_dashboard`）的稳态斜率超过 0.05 分钟/小时（0.0746、0.0647），以它们为终点的 9 行路径判定因此为 FAIL；积压没有增长，原因是 10 秒采样格点上的台阶（#268，是否改测法由维护者决定） | 性能测试文档的第 1、5.3 节 |
| 安全：暴露面与拒绝证据 | 通过：两个区域各 64 项检查全部 PASS；公网可达的资源三类请求的每一类都 PASS 或 N/A（每个区域 6 项 N/A，理由见安全一节）；账号所有已启用区域没有 Lambda 函数 URL；证据里没有凭证 | [安全一节](security.md) |
| CI | `WAIVED_BY_REQUESTER_NOT_PASS`：GitHub Actions 因账单拒绝启动，请求方豁免；本候选的每个 PR（#251、#252、#253、#254、#255、#256、#257、#259、#260、#262、#263）都在确切 SHA 上本地执行了 `.github/workflows` 的全部 9 个作业，结果在各 PR 的评论里 | 各 PR 的评论 |
| 发布结论 | **NO-GO**：性能验收未达到固定的口径。UAT 与安全都通过 | 发布检查 #181 |

## 6. 变更

相对 `v1.0.0-rc.1`（各项已进入用户手册、部署指南、UAT 用例与成本估算的相应位置）：

- 健康检查不再把 RDS 的备份等状态判为不健康（#237，#251）。
- 经 Apache Kafka 承载的 CDC 有了进度信号：带心跳的 MySQL 源不靠新提交就能判定，缺行时发 `RowsMissing`（#238，#252）。
- CDK CLI 解析不到账号时，CDK 步骤重跑，最多 3 次（#233，#253）。
- 大输出经 Pod 内的文件取回，不受 kubelet 日志轮转截断（#244，#255）。
- 文档与测试里不再有部署值，并加了门禁（#248，#256）。
- 数据源停滞时的诊断：`message` 写明最后一次提交，`TargetNotCommitting`/`WaitingForCommit` 事件，测试超时前保存诊断（#245，#257；根因未明）。
- 第三方镜像按 digest 复制进环境自己的 ECR，集群不再从上游拉镜像；清单不写账号与区域，发布仓库由 `COVE_RELEASE_REGISTRY` 给出（#234，#259）。
- 部署脚本的拒绝、可执行位与 `grep -q` 等一组可移植性修复（#227，#254）。
- 产品改名 Colvenna Lake，仓库改名 `amliuyong/colvenna-lake`；查询引擎镜像按 ASF 的分发政策改名 `query-engine`；Apache 全称与商标署名（#258，#260）。
- `doris` 步骤等 Operator 换上新的镜像后再连 FE，SQL 失败时的报错进 stderr（#261，#262）。

## 7. 已知限制

- #233 的重跑、#237 的 `backing-up`、#238 的 `RowsMissing` 与 #261 的等待（本次没有哪次运行改变已有 Doris 集群的镜像）这几条修复的路径，本次在真实环境里没有走到，只有离线测试覆盖（UAT 报告第 7 节）。
- `doris` 步骤只等镜像的变化；只改配置、不换镜像的 DorisCluster 规格变化仍要等 Operator 更新 StatefulSet 之后才看得出来（#261，代码注释写明）。
- 稳态判定对采样格点上的台阶敏感，同一产品的结论可能随运行而变（#268）。
- 发布仓库里保留的 `cove-lake-release/doris` 以旧名存着 `v1.0.0-rc.1` 的查询引擎镜像，保留、删除或撤销拉取由维护者决定（#258）。
- 只有一个 AWS 账号：跨账号安装没有实测。

## 8. 未关闭的问题

| issue | 影响 | 是否阻塞发布 |
| --- | --- | --- |
| #268 | 性能验收按固定的规则判 FAIL | 是：维护者决定测法之前，本候选只能 NO-GO |
| #245 | MySQL 源一张表久不 Ready 的根因未明；本候选只有诊断，本次没有复现 | 否 |
| #248 | git 历史与已关闭的 issue/PR 里的旧部署值怎样处理，待维护者决定 | 否 |
| #258 | 宣传视频与 GIF 仍是旧名、旧标志，已从 README 撤下，待重新渲染 | 否 |
| #267 | 安全一节的脱敏工具给地址编号不一致（本候选的文档已手工改正） | 否 |
| #33 | 第二朵云未验证（不在本次范围） | 否 |

## 9. 升级与兼容

开发阶段不保证版本之间的升级兼容，不做跨版本回退演练。本候选声明支持的升级、重建与恢复路径见部署指南，并在 UAT 中验证。

## 10. 组件与商标

本候选建立在 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（含 Flink CDC 与 Flink Kubernetes Operator 两个子项目）与 [Apache Doris](https://doris.apache.org/) 之上，另用到 [Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/) 与 [Apache Kafka](https://kafka.apache.org/)：

- 查询引擎镜像（清单的 `query-engine-fe`、`query-engine-be`，仓库 `cove-lake-release/query-engine`）是基于 Apache Doris 4.1.4 的修改构建：自带的 Paimon 1.4.2 读取器换成了用 Apache Doris 源码对 Apache Paimon 2.0.0 编译的读取器，BE 的 `java-udf-jar-with-dependencies.jar` 去掉了其中的 Paimon SDK 类，另加 PostgreSQL JDBC 驱动。它不是 ASF 的发布，也没有得到 ASF 的认可。
- Flink 基础镜像（`flink`，仓库 `cove-lake-release/flink-base`）是官方 Apache Flink 1.20.4 镜像加 Apache Paimon 2.0.0 的 jar 与 JDBC 驱动。
- 第三方镜像（`third_party`、`fixtures.third_party`）是上游镜像按 digest 原样复制的。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset and Superset are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目。其他各方的商标与各镜像的命名依据，见本候选的[商标与命名](../trademarks.md)。

> 注：本文中的 issue 与 PR 编号（如 #268）、提交（如 `9ab8b2a7`）和 tag（如 `v1.0.0-rc.2`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。原先指向早期私有仓库某个 tag 或提交的文件链接，现在指向本仓库里的同一文件。
