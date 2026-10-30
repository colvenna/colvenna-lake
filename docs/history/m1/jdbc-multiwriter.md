---
title: "M1 S3 多写者与 JDBC 锁验证"
date: 2026-09-28
status: implementation-in-progress
issue: 7
---

# M1 S3 多写者与 JDBC 锁验证

本验证对应 Issue #7。两个独立 JVM 进程通过同一个 Paimon 2.0.0 JDBC catalog、PostgreSQL 锁和真实 Amazon S3 写入一张合成主键表；每个进程使用独立提交身份。所有测试 Pod 只在 `cove-lake-ods` 私有命名空间运行，不创建 Service、Ingress、hostPort 或公网地址。Kubernetes Secret 从项目 RDS 的 Secrets Manager 记录注入，脚本不打印口令。

## 前提和运行

先部署 [#6 的私有 PoC](poc-environment.md)，确认 CloudFormation 栈为 `UPDATE_COMPLETE`、Flink 镜像在私有 ECR 中与栈的 `SourceSha` 对应。运行机需有 `javac`、`jar`、AWS CLI、`jq`、Python 3、Node.js、`shellcheck`；在 `infra/m1` 完成 `npm ci` 和 `scripts/install-tools.sh`，并准备当前集群的忽略文件 `evidence/kubeconfig`。脚本固定 AWS profile `work`、区域 `us-west-2`、账号 `<ACCOUNT_ID>`，只接受包含当前栈源码提交的 #7 工作树。

```bash
cd infra/m1
bash issue7/run-multiwriter.sh
```

开发期间可传 `--diagnostic`，它在证据目录写入 `PROBE_ONLY.txt`，**不算正式验收**。正式运行要求干净、已提交的源码。脚本下载并核对 Paimon Flink 1.20/2.0.0 jar 的 SHA-256，以 Java 11 目标编译探针，并将 jar 通过已认证的 Kubernetes exec 通道复制到两个私有 Pod。只保留合成表及本地忽略的 `evidence/issue7-<UTC 时间>/`；成功或失败均定点清理临时 Pod 与数据库口令 Secret，失败时先保存 Pod 状态。

## 核对内容

1. 两个进程在同一张表、同一 bucket 和同一 JDBC 锁配置下都完成 `prepareCommit` 后，由一个私有 S3 屏障同时放行提交。验证每个提交身份只产生一次 APPEND、快照 ID 连续、各快照引用的数据文件都位于预期表前缀且实际大小与 manifest 一致、各历史快照的全部行与预期相等，且 COMPACT 快照没有改动业务结果。
2. 在 `prepareCommit` 后、提交前强制退出进程，确认没有可见快照或行，再用同一提交身份与 ID 经 `filterAndCommit` 重试。记录故障到最终正确结果的时间。
3. 提交返回后、向调用方确认前强制退出进程，先按提交身份、快照与行集查证；已确认提交时不重复调用提交接口。
4. 两个进程写入同一主键范围，利用 `sequence_no` 决定最终版本。遇到明确的文件删除冲突时，仍先查证报错进程的 APPEND 快照；已经发布则跳过重试，确实未发布才用 `filterAndCommit` 从最新表状态重试。核对冲突前后每个历史快照与当前结果。
5. 前四步通过后，核对 RDS 实例属于当前 CDK 栈且 `PubliclyAccessible=false`，让新写者先完成 `prepareCommit`，随后重启该私有实例。只有从另一个 Pod 观察到实际 JDBC 连接失败，且放行屏障前连接仍失败，才允许新写者尝试提交；写者本身还须报出 PostgreSQL 连接异常，避免数据库抢先恢复造成误判。实例恢复后先查快照；已发布则不重试，未发布才使用 `filterAndCommit`。分别记录重启请求、首次观测失败、恢复可连接和全部行查询正确的时间。

脚本用 AWS S3 `HEAD` 核对表的 schema 确实落在 `warehouse/ods.db/<table>/`；探针逐快照枚举 DataSplit 的数据文件引用，对其执行 S3 文件状态核对并读取每个历史版本；独立的 `verify.py` 再对输出做文件引用、行集合与快照对账。作业成功或 Pod Ready 不能替代这些核对。未确认的提交不盲目重放覆盖写。

**目录访问边界。** Paimon 标准 `CatalogFactory.createCatalog` 会先对共享 `warehouse/` 执行 S3A 目录探测。细粒度 ODS IRSA 对这个无尾斜杠的根 key 返回 403；直接增加带前缀条件的权限和零字节目录标记也不能使标准探测通过。本探针使用同一发布版的 `JdbcCatalogFactory` 和配置后的 `FileIO` 构造 JDBC catalog，跳过根目录预探测，随后通过真实表创建、S3 写读和 PostgreSQL 锁结果验证可用性。这不证明 Flink SQL 标准 catalog 工厂已可在同样权限下运行；后续 Flink 接入前须实现并验证等效的受限初始化路径，或重新审定目录与 IAM 边界。不得为求通过而给数据身份无条件放开整个桶的列表或对象读取权限。

2026-09-28 的诊断运行中，提交后丢失回执并用原始 `commit` 重试同一提交 ID，实际生成了额外的 APPEND 和 COMPACT 快照；最终行集未变，但该操作不能视为幂等。这条失败证据保留在本地忽略的 `evidence/issue7-20260928163531`。随后同键双写者在 `evidence/issue7-20260928163902` 中出现一个 APPEND 后明确的文件删除冲突；在 `evidence/issue7-20260928164149` 中，报错的写者其实已经发布 APPEND，另一个写者随后发布 APPEND 与 COMPACT。两种情况均不得仅凭异常决定重试。恢复逻辑因此改为先查证快照，已提交则跳过，确实未提交才用 `filterAndCommit`。这些目录均是带 `PROBE_ONLY.txt` 的诊断失败，不能记为正式通过。

`evidence/issue7-20260928164519` 的 S3/JDBC 并发与进程退出轮次通过；`evidence/issue7-20260928164846` 又在同一流程中重启了 CDK 栈的私有 RDS：放行时 JDBC 返回 SQLSTATE `08001`，写者未发布 APPEND，恢复后经 `filterAndCommit` 提交。最终 8 个快照（含一次 COMPACT）及 60 行的各历史版本都与预期一致；从重启请求到 JDBC 恢复可连接为 79 秒，恢复可连接后到最终正确结果为 45 秒。这两个目录都带 `PROBE_ONLY.txt`，仅为未提交源码的诊断，**不算正式验收**。`evidence/issue7-20260928165602` 在干净源码 `daedd58` 上也通过同一流程，重启请求到 JDBC 恢复 75 秒、首次观测连接失败到恢复 67 秒、恢复后到最终结果正确 45 秒；但该版本尚未逐一记录数据文件引用，不能替代后续增强版的最终验收。`evidence/issue7-20260928170230` 使用增强版校验得到 8 个快照、60 行与 8 个数据文件引用全部相符，RDS 恢复可连接后 47 秒得到完整结果；该目录仍带 `PROBE_ONLY.txt`，不算正式通过。

进程在提交后、回执前退出只覆盖调用方无法确认提交结果这一情形，尚未注入服务端响应超时。独立的 write-only 写入与专职维护作业属于 Issue #12；本测试的两个角色都是同一表的写者。完整 [M0 故障矩阵](../../design/acceptance-contract.md#2-延迟正确性和故障目标)和 Flink 实际作业仍须分别验收。Paimon 锁、catalog 与 S3 路径依据固定发布版 [并发文档](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/docs/docs/concepts/concurrency-control.md)及 [JDBC 工厂源码](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-core/src/main/java/org/apache/paimon/jdbc/JdbcCatalogFactory.java)。

> 注：本文中的 issue 与 PR 编号（如 #7）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
