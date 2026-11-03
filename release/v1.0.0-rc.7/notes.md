---
title: "Colvenna Lake v1.0.0-rc.7 发布说明"
date: 2026-11-03
status: draft
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

# Colvenna Lake v1.0.0-rc.7 发布说明

## 1. 候选

| 项 | 内容 |
| --- | --- |
| 候选 | `v1.0.0-rc.7`（annotated tag） |
| 镜像与安装所用的源码 | 提交 [`3e7344f1`](https://github.com/colvenna/colvenna-lake/commit/3e7344f1ba7e62ab7ed48d2574656a8647a42e4b)（`3e7344f1ba7e62ab7ed48d2574656a8647a42e4b`）。tag `v1.0.0-rc.7` 指向的提交与它相比，只多了 `release/v1.0.0-rc.7/` |
| 发布清单 | [`release/v1.0.0-rc.7/manifest.json`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/release/v1.0.0-rc.7/manifest.json)：全部镜像（含第三方镜像）在发布仓库里的仓库名、tag 与 digest，colvenna CLI 的 SHA-256、Helm chart、安装脚本、UAT 用例文档的版本、支持的配置、必需用例集合与性能验收口径。清单不写发布仓库的地址与账号：安装时由 `COLVENNA_RELEASE_REGISTRY` 给出 |
| Helm chart | 各 chart 的版本号都还是 `0.1.0`，不随候选变化；固定它们的是清单里各 chart 目录的 git tree（`charts.<chart>.tree`） |
| 生成日期 | 2026-11-03 |

按清单安装，不在安装时构建镜像。新环境要给全部设置，之后同一环境只需 `COLVENNA_ENV` 与 `COLVENNA_RELEASE_REGISTRY`（[部署指南](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/install.md)）：

```bash
git checkout v1.0.0-rc.7
export AWS_PROFILE=<目标账号的 profile>                    # 安装者自己的凭证，属于 COLVENNA_ACCOUNT
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com   # 保存本候选镜像的发布仓库
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> [COLVENNA_ENTRY_DOMAIN=<入口域名>] \
  deploy/install.sh --release v1.0.0-rc.7 [--fixtures]      # 不加 --fixtures 只装平台；加上则带测试夹具
```

## 2. 交付物

| 交付物 | 固定版本 | 状态 |
| --- | --- | --- |
| 用户手册 | [`docs/manual/user.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/user.md) | <完成 / 未完成，链接 issue> |
| 部署指南 | [`docs/manual/install.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/manual/install.md) | <完成 / 未完成，链接 issue> |
| 安装脚本 | [`deploy/install.sh`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/deploy/install.sh)，清单的 `installer` 记有它的 blob | <完成 / 未完成，链接 issue> |
| 成本估算 | [`docs/manual/cost.md`](https://github.com/colvenna/colvenna-lake/blob/<提交>/docs/manual/cost.md) | <完成 / 未完成，链接 issue> |
| UAT 用例 | [`docs/uat/cases.md`](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/docs/uat/cases.md)，清单的 `uat_cases` 记有它的提交与 blob | <完成 / 未完成，链接 issue> |
| 完整 UAT 报告 | [`docs/uat/reports/v1.0.0-rc.7/report.md`](https://github.com/colvenna/colvenna-lake/blob/<提交>/docs/uat/reports/v1.0.0-rc.7/report.md) | <完成 / 未完成，链接 issue> |
| 独立性能测试文档 | [`docs/performance/v1.0.0-rc.7.md`](https://github.com/colvenna/colvenna-lake/blob/<提交>/docs/performance/v1.0.0-rc.7.md) | <完成 / 未完成，链接 issue> |

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
| UAT：<区域 1> | PASS <数>、FAIL <数>、BLOCKED <数>、NOT_RUN <数>（必需用例 <数> 条） | UAT 报告的 <节> |
| UAT：<区域 2> | PASS <数>、FAIL <数>、BLOCKED <数>、NOT_RUN <数>（必需用例 <数> 条） | UAT 报告的 <节> |
| 性能验收 | <达到 / 未达到第 4 节的口径> | 性能测试文档的 <节> |
| 安全：暴露面与拒绝证据 | <结论> | <安全一节的链接> |
| CI | <状态：`PASS`，本候选的每个 PR 在确切的 head 提交上 GitHub Actions 的全部作业都成功（2026-10-30 起的口径，见 CONTRIBUTING.md）；只有 Actions 无法运行时才豁免，记为 `WAIVED_BY_REQUESTER_NOT_PASS` 并写明原因与每个 PR 在确切 SHA 上本地执行全部作业的记录> | <各 PR 的 GitHub Actions 运行；豁免时另附本地 CI 记录> |
| 发布结论 | <GO / NO-GO> | <发布检查报告的链接> |

## 6. 变更

<相对上一次发布的变更；第一次发布写“首个候选”。列出新增的能力与限制，并确认它们已进入用户手册、UAT 用例、成本与性能的范围。>

## 7. 已知限制

<用户手册“限制与未验证项”一节之外，本候选新发现的限制；每项链接 issue。>

## 8. 未关闭的问题

<影响本候选的未关闭 issue：编号、影响、是否阻塞发布。>

## 9. 升级与兼容

开发阶段不保证版本之间的升级兼容，不做跨版本回退演练。本候选声明支持的升级、重建与恢复路径见部署指南，并在 UAT 中验证。

## 10. 组件与商标

本候选建立在 [Apache Paimon](https://paimon.apache.org/)、[Apache Flink](https://flink.apache.org/)（含 Flink CDC 与 Flink Kubernetes Operator 两个子项目）与 [Apache Doris](https://doris.apache.org/) 之上，另用到 [Apache Iceberg](https://iceberg.apache.org/)、[Apache Polaris](https://polaris.apache.org/)、[Apache Spark](https://spark.apache.org/)、[Apache Superset](https://superset.apache.org/) 与 [Apache Kafka](https://kafka.apache.org/)：

- 查询引擎镜像（清单的 `query-engine-fe`、`query-engine-be`，仓库 `colvenna-lake-release/query-engine`）是基于 Apache Doris 4.1.4 的修改构建：自带的 Paimon 1.4.2 读取器换成了用 Apache Doris 源码对 Apache Paimon 2.0.0 编译的读取器，BE 的 `java-udf-jar-with-dependencies.jar` 去掉了其中的 Paimon SDK 类，另加 PostgreSQL JDBC 驱动。它不是 ASF 的发布，也没有得到 ASF 的认可。
- Flink 基础镜像（`flink`，仓库 `colvenna-lake-release/flink-base`）是官方 Apache Flink 1.20.4 镜像加 Apache Paimon 2.0.0 的 jar 与 JDBC 驱动。
- 第三方镜像（`third_party`、`fixtures.third_party`）是上游镜像按 digest 原样复制的。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Kafka, Kafka, Apache Iceberg, Iceberg, Apache Polaris, Polaris, Apache Spark, Spark, Apache Superset and Superset are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目。其他各方的商标与各镜像的命名依据，见本候选的[商标与命名](https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.7/release/trademarks.md)。
