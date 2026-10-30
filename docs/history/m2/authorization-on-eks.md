---
title: "M2 目录授权与审计在 EKS 上的验收"
date: 2026-10-13
status: complete
issue: 21
---

# M2 目录授权与审计在 EKS 上的验收

本页记录 Issue #21 在真实 AWS 环境中的结果：

- 不同服务身份在目录查询、数据读取、写入和管理操作上的允许与拒绝；
- 这些请求留下的审计。

授权模型见 [cove-catalog LLD 的“授权”一节](https://github.com/colvenna/colvenna-catalog/blob/main/docs/design/colvenna-catalog-lld.md#授权)；各项权限的执行方，以及 cove-catalog 不能独立执行的部分，见产品文档 [授权与执行方](../../product/authorization.md)。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群上，只使用合成数据。部署方式见 [EKS 部署与引擎验收](catalog-on-eks.md)。

**证据的来源版本**：

- 引擎流程来自提交 `eda42e2e128c6c703354322f4b4e3d52631d1265`，镜像以该 SHA 构建并安装。
- 凭证、授权与端点检查来自 `5ae22764011b6e45e5e73d296b7936a11260cec0`。它只修了 `verify-data-tokens.sh`（只读探测身份要先建立，才能授权），服务端仍是上述镜像。
- 模式迁移的证据来自同一天较早的一次运行（`727b6d6`）。那次运行中，Flink 流程因下文的默认库问题失败。
- 之后的提交只改了本页。

原始日志在私有证据目录 `m2-21/run-eda42e2/`（检查在其中的 `checks-5ae2276/`）和 `m2-21/run-727b6d6-failed-default-db/`。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 不同身份的允许与拒绝行为覆盖目录查询、数据读取、写入和管理操作 | **通过** | 授权矩阵 30 项全部符合预期，覆盖列库、列表、读表、数据凭证、列级判定、提交、改表、建表与删表、管理面；按表凭证 38 项检查全部符合预期，其中只读身份不能写、不能删数据对象；Flink、Spark、Doris 在最小授权下流程全部通过 |
| 审计能关联身份、资源、操作、结果及发生时间，且失败操作不会被记成成功 | **通过** | 按请求 id 找到的 21 个请求，每条都带服务身份、动作、资源、结果、HTTP 状态和时间，并与客户端看到的状态一致；这些身份的审计中没有失败请求被记成成功。HTTP 200 但 compare-and-set 失败的提交记为 `FAILED`，由 [#19](commit-recovery.md) 在真实并发下核对 |
| 任何无法由目录独立强制的权限在产品文档中明确标注其执行方 | **通过** | [授权与执行方](../../product/authorization.md) 逐项列出执行方与边界：Doris 终端用户权限、列级判定不是隔离边界、已签发凭证的有效期、行过滤与列脱敏不提供 |

## 验证手段

### 最小授权下的引擎流程

Flink、Spark、Doris 三个流程与 [EKS 部署与引擎验收](catalog-on-eks.md#引擎流程) 相同，但每个服务身份只有完成任务所需的授权：

| 服务身份 | 授权 |
| --- | --- |
| `m2-flink`、`m2-spark` | 只对自己流程的库有 `MANAGE` |
| `m2-doris` | 对所读的库只有 `READ` |
| `admin` | 只能用管理面；访问目录对象同样要靠显式授权，安装时授予它目录级 `MANAGE`，用于平台运维 |

### 按表凭证的读写边界

`verify-data-tokens.sh` 在真实 S3 上检查以下几项：

- 只读身份拿到的凭证只能读本表对象，以及写本表 `consumer/` 下的消费进度；
- 写入和删除数据对象被拒绝；
- 没有授权的身份拿不到凭证。

### 授权矩阵与审计：`verify-authorization.sh`

每次运行都新建六个服务身份，对 Flink 流程写出的表 `orders` 授权如下，运行结束时停用这些身份，授权一天后到期：

| 身份 | 授权 |
| --- | --- |
| owner | 库上 `MANAGE` |
| writer | `orders` 上 `WRITE` |
| reader | `orders` 上 `READ` |
| columns | `orders` 上只对列 `id` 有 `READ` |
| denied | 库上 `READ`，`orders` 上 `DENY READ` |
| outsider | 无 |

所有请求都从集群内的客户端命名空间发出，并记下响应头中的 `X-Request-Id`。请求覆盖以下四类：

- **目录查询**：列库、列表、读表，用来检查只返回可见的对象。
- **数据读取**：
  - 签发数据凭证；
  - 列级判定 `/auth`：只能读 `id` 的身份请求读 `id` 被允许，请求同时读 `amount` 被拒。
- **写入与 DDL**：
  - 提交与改表请求带无效的请求体，所以既不会写入，也能分出两种结果：400 表示授权已通过、因请求体无效被拒，403 表示授权未通过；
  - owner 真实建立并删除一张临时表；
  - writer 在 `orders` 上只有 `WRITE`，它对 `orders` 的建表、改表、删表都必须被拒。
- **管理面**：只有管理员身份能调用。

之后按请求 id 在审计中逐条找到这些请求，核对服务身份、动作、资源、结果、HTTP 状态和时间：

- 被拒绝的记为 `DENIED` 和 403；
- 授权通过、但因请求体无效而失败的记为 `FAILED` 和 400；
- 成功的记为 `SUCCEEDED`。

还要核对：这些身份的审计中没有任何失败请求被记成成功。HTTP 200 但 compare-and-set 失败的提交记为 `FAILED`，这一项由 [#19 的故障注入](commit-recovery.md) 在真实并发下核对。

## 结果

### 模式迁移

在已有 31 张表的 catalog 上，从没有授权的版本升级到本版本，服务端启动时执行 `V2__grants.sql`。升级前后，管理员看到的表清单完全相同，长期存在的表 `m2_flow_final.orders` 的一致性报告也干净。

升级后，已有服务身份没有任何授权，要先授权再恢复作业，见 [授权与执行方](../../product/authorization.md)。

### 引擎流程（最小授权）

| 引擎 | 结果 |
| --- | --- |
| Flink | 18 步全部通过 |
| Spark | 9 步全部通过 |
| Doris | 16 项检查全部通过，native 读取器取全部分片 |

同一时段的 catalog 日志中没有缺失的端点，也没有 501。

### 授权矩阵

| 请求 | owner | writer | reader | columns | denied | outsider |
| --- | --- | --- | --- | --- | --- | --- |
| 列库 | 200，含该库 | — | 200，含该库 | — | — | 200，不含该库 |
| 读表 `orders` | 200 | 200 | 200 | — | 403 | 403 |
| 列表 | 200 | — | 200，只有 `orders` | — | — | 403 |
| 数据凭证 | — | 200 | 200 | — | 403 | 403 |
| 列级判定 `/auth` | — | — | — | 读 `id` 200；读 `amount` 403 | — | — |
| 提交（无效请求体） | 400 | 400 | 403 | — | — | 403 |
| 改表（无效请求体） | 400 | 403 | — | — | — | — |
| 建表 | 200 | 403 | — | — | — | — |
| 删表 | 临时表 200 | `orders` 403 | — | — | — | — |
| 管理面列授权 | 403 | — | 403 | — | — | — |

管理员身份列授权：200。

### 审计

按请求 id 找到 21 个请求，逐条核对动作、资源、结果和 HTTP 状态，全部一致：

- 拒绝记为 `DENIED` 和 403；
- 无效请求体的提交与改表记为 `FAILED` 和 400；
- 成功的读表、凭证签发、列级判定、建表与删表记为 `SUCCEEDED`。

在库级判定处被拒的建表记的是库名，成功建表记的是新表全名。

### 过程中的发现

- **默认库**：最小授权下的 Flink 流程最初连 catalog 都创建不了。Paimon 的 Flink 与 Spark catalog 启动时会查找库 `default`，403 会让它们失败。现在这个库对每个服务身份可见，库中对象仍按授权判定；安装时建好它。
- **无效请求体**：改表请求缺少变更时，原先返回 500，现在返回 400。
- **失败建表的审计资源**：授权已通过、之后失败的建表（例如名称已存在），原先只记表名，现在记库名加表名。

## 复现

```bash
# 前提：cove-catalog 已按 catalog-on-eks.md 安装；当前树已提交；先跑一次 Flink 流程。
bash infra/m2/flows/run-flink-deployment.sh <run-id> <evidence>/flink com.covelake.m2.flow.FlinkCatalogFlow <database>
bash infra/m2/scripts/verify-data-tokens.sh <database> <evidence> <evidence>/data-tokens
bash infra/m2/scripts/verify-authorization.sh <database> <evidence>/authorization
```

> 注：本文中的 issue 与 PR 编号（如 #21）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
