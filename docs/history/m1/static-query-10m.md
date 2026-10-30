# 静态千万行 Doris 查询观察

Refs #14 / #16。本页记录时 Issue #14 / M1 为 **NOT_COMPLETE**；#14 已按接收条件结项，见
[结项判定](doris-paimon.md#结项判定)，同一数据集上的 24 小时查询结果也记在那里。
本轮核对静态数据的查询正确性与请求账本，性能门禁为 **NOT_ASSESSED**。

## 实际运行身份

| 项目 | 已核对身份 |
| --- | --- |
| 数据集、JDBC 权限组件源码 | `b1db2a19736993426216718dce31d61b12efc10e` |
| 查询与看板组件源码 | `092890393f518c365ca99d1cc4f99b6427edff90` |
| 数据集 JAR SHA256 | `2a94d027e7c47f0d235f32807f03245fcf9e447f04f7c27099d3a309220c9670` |
| Doris 镜像构建源码 | `e455e0f5fba0a6b97da4e8bd87af052bd4529fd8` |
| FE 镜像 digest | `sha256:4608a0cb51305f43c68dfe64c215657ea62f47cbec985d4000061a55e6b173d9` |
| BE 镜像 digest | `sha256:956817fda0eb7bf929dbb17608d071b70173bf99e82cadd6976a408c471d9d98` |
| 实际 SDK2 reader 回执文件 SHA256 | `2d4412f9032540b4df73c95f00d59fa7d54d5432fb3c1ad17095e52203fab86c` |
| 原始运行 ID | `20261001024623-3932505` |
| 固定已提交快照 | `100` |

部署与集成使用 AWS profile `work`、区域 `us-west-2`，实际核对账户、
EKS 服务端、FE/BE Pod UID、容器 ID、镜像与 reader 文件。
Native Seed 的实际 Java 为 11.0.31；项目 Java 21 的产品边界仍按 M0 契约说明。

## 数据与 Native 复核

数据覆盖 2026-09-25 至 2026-10-01，DV 配置主键表共 10,000,000 行，
100 次提交，每次 100,000 行。Native 核对全部 ID `0..9,999,999`、唯一性及
`dt/id/region/amount/version_no` 五字段。

| 区域 | 行数 | 金额汇总 |
| --- | ---: | ---: |
| r0 | 2,500,000 | 1,245,000,000 |
| r1 | 2,500,000 | 1,247,500,000 |
| r2 | 2,500,000 | 1,250,000,000 |
| r3 | 2,500,000 | 1,252,500,000 |

创建内置校验、独立 Native 读回、生成 JDBC 读者全量读回，以及查询前后
全量读回均以原始远程退出码 0 完成，快照保持 100。查询后复核完成于
2026-10-01 04:38:32 UTC；原 Doris 协调器退出码 0。

实际生成 JDBC 读者可访问 5 张 public catalog 元数据表，未发现 catalog 写权限。
Seed 使用写入用 IRSA，因此这条证据不证明 Doris S3 写入拒绝。
新建静态数据没有更新/删除后的物理 DV 文件证据：**physical_dv=NOT_VERIFIED**。
此前 SDK2 更新删除与 Native JNI 有界观察见 [原报告](doris-paimon.md)，保持其原源码和规模边界。

## Doris 与看板

认证 bootstrap 的原始命令成功；空密码被拒绝；实际 CNI 策略下 Seed 到
FE TCP 9030 的连接被拒绝，策略按登记 UID 删除后才开放私有 SQL 路径。
服务为私有 ClusterIP，调用者核对 loopback 转发的实际 FE targetRef。
FE 的实际挂载 PVC、PV 与加密 EBS 已核对。

Paimon catalog 的实际 ID 为 `1790826204911`。
手动完整刷新 MV 的实际 ID 为 `1790826204914`，任务 `3413953838368`，
`COMPLETE/MANUAL/SUCCESS`，03:46:50 至 03:47:29 UTC，错误信息为空；
实际四区域汇总与上表一致。查询用户仅授予选定表与看板 MV 的 SELECT。

## 30 分钟固定请求窗口

查询子进程原始退出码 0。独立核对原始账本全部请求序号、计划槽位、
run/source、结果及单调时间戳；没有补发或重复序号。

| 查询 | 计划 QPS | 正确请求 | 错误/拒绝/漏发 | 服务 p95/p99 (ms) | 含队列 p95/p99 (ms) |
| --- | ---: | ---: | --- | --- | --- |
| 看板四区域汇总 | 10 | 18,000 | 0 / 0 / 0 | 64.852602 / 76.770511 | 69.452949 / 116.243313 |
| 末行五字段读取 | 1 | 1,800 | 0 / 0 / 0 | 1348.529175 / 1553.890491 | 1359.817971 / 1599.484217 |

请求窗口 1,800 秒，含排空总时长 1800.199638591 秒。
账本 SHA256 为 `de9266d59deaab597bb0dca61f74b7387f562186cc1e5b53c5dd4f874b9b15ac`。
独立判定为 `STATIC_QUERY_SLOTS_COUNTS_AND_RAW_TIMERS_RECOUNTED`。
查询组件判定 `FIXED_QUERY_RESULTS_OBSERVED` 只表达固定结果观察。

交互查询仅 1,800 个样本，尚未满足 M0 契约中每路径/操作至少 10,000 个
有效样本的要求。本轮没有持续写入，没有源到 ODS/DWD/DWS/MV 的全链路
新鲜度样本，也没有完整运行时钟、24 小时吞吐与积压稳定性或故障恢复验收。
参见 [M0 验收契约](../../design/acceptance-contract.md)。

## 清理与证据状态

Doris SQL、按 UID 登记的 Kubernetes 资源、PVC/PV/EBS 与 CNI 恢复清理
原始退出码 0；Native 静态表、S3 表前缀及本轮容量清理原始退出码 0。
独立归档进程也以原始退出码 0 完成，实际重新核对 AWS 账户、EKS ARN/服务端、
登记资源与存储缺席、5 个原有 Flink 作业及原有 Kafka/CNI 身份。

独立判定：`INDEPENDENT_STATIC_QUERY_TEARDOWN_AND_EXACT_BYTE_ARCHIVE_PASS`。
归档共 1,529 个文件、13,499,269 字节，逐文件复制、哈希重读与目录 fsync 完成；
四个原运行/清理目录均发布相同的源码冻结解除回执，`source_freeze=RELEASED`。
归档完成于 `2026-10-01T04:48:17.239664+00:00`。

原始私有证据保存在验收主机的
`<证据目录>/issue14-query10m-20261001024623-exact-sha/`。
`MANIFEST.json` SHA256 为 `c3a9354c17e3064264170deee009085a17ba086eb8654d37b4b5967c7a20da6c`；原始请求账本、Native 远程退出、
SQL 注册/清理与实际资源身份等回执均在该归档内。仓库报告不复制私有凭据。

## 交付边界

本轮数据集源码的 CI `36807405343` 实际执行全部 11 jobs，逐个核对
check-run SHA、步骤与终态为 PASS，未使用 CI 豁免。文档后续提交需等待其
自己的 CI，旧源码 CI 与运行证据分别保留。

Doris 镜像已有安全扫描门禁仍阻断生产使用；本轮为已授权 PoC。
完整查询引擎选型、Issue #14 与 M1 仍为 **NOT_COMPLETE**。
接口见 静态数据集 CLI（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/query-load-dataset.md`）。

> 注：本文中的 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
