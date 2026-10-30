---
title: "M1 Kafka 事件流入湖 PoC"
date: 2026-09-28
status: implementation-in-progress
issue: 10
---

# M1 Kafka 事件流入湖 PoC

本页记录 Issue #10 的合成数据 PoC。AWS 资源由 `infra/m1/src/kafka-ingest-stack.ts` 的 TypeScript CDK 创建；部署脚本固定 profile `work`、账号 `<ACCOUNT_ID>`、区域 `us-west-2`，并核对干净提交、CloudFormation `SourceSha` 与 ECR 镜像 digest。生产 Kafka 由用户提供，本 PoC 的 Strimzi 单节点 Kafka 只供开发验证。

## 数据契约

本轮恢复使用 Kafka topic `cove-issue10-events-r2`；首次验证的 `cove-issue10-events` 已在开发 broker 重建后丢失消息。每条消息是 JSON 对象，必须有字符串 `event_id`（最多 128 字符）、`event_type`（最多 64 字符）、含时区的 RFC 3339 `event_time`。可选 `attributes` 必须为对象。新增顶层字段并入 `attributes_json`；若新增顶层字段与 `attributes` 内字段同名，该键保存 `{"attributes":原值,"top_level":新增值}`，避免静默覆盖。事件时间转 UTC 日期 `dt`。输入早于处理时刻 30 天或晚于 1 天会被拒绝，避免不断写入已过期分区。

`ods.kafka_events` 是无主键 Paimon append 表，保存 `dt, event_id, event_time_ms, event_type, attributes_json, source_topic, source_partition, source_offset`。`bucket=-1`、`write-only=true`，元数据与快照提交使用现有 JDBC catalog 和锁；独立维护作业属于 #12。分区按 UTC 日组织，`partition.idle-time-to-done=1 m`、`partition.mark-done-action=success-file` 给下游提供 `_SUCCESS` 调度信号。完成信号表示空闲，不保证此后绝无迟到数据。`partition.expiration-time=31 d` 定义逻辑过期；Paimon 2.0.0 的分区值策略在当前时间严格晚于分区日期零点加 31 天时才将其选作过期候选，实际移除依赖写入与过期检查，物理对象清理由快照过期治理负责。探针读取真实 `_SUCCESS` 对象并输出理论候选边界；JDBC catalog 的 `Partition.done()` 不能替代这个对象检查。

解析失败的消息和水位线迟到消息进入 `ods.kafka_event_rejections`，仅保存日期、固定原因码、topic、partition、offset 与检测时刻，不保存原始载荷。水位线容忍 5 分钟乱序；`event_time_ms <= currentWatermark` 视为迟到。常规 Flink 作业重启使用 checkpoint 绑定 Kafka 位点与 Paimon 提交；同一消费组不手工重置位点。开发 broker 或 topic 一旦重建并从 0 重新编号，必须暂停旧作业、保留原 checkpoint 与表证据，换用新 topic 名称、消费组及 checkpoint 前缀启动作业，避免旧位点跳过新消息或与旧湖表行撞号。生产 Kafka 必须由用户提供持久化与恢复保障。手工回放使用独立 SCRAM 用户、消费组、`ods.kafka_events_replay` 和 `ods.kafka_event_rejections_replay`，通过 `(source_topic, source_partition, source_offset)` 与主表对账，不向主表追加第二份相同位点。部署脚本在回放表已有 S3 对象时要求同名运行中作业、相同 topic／消费组／目标表和可读取的 S3 checkpoint；状态丢失时拒绝无状态回放，重新回放须换用空表。

## 私有部署与凭证

CDK 只创建私有 ECR 仓库 `cove-lake/m1-kafka-ingest`，开启不可变标签与 push 扫描。Strimzi Helm chart `1.2.0` 的 SHA-256 为 `0f8a50b2f19bd99482f9fd6e17cf42902f72f9e594a136813ac3f0b7af422efd`；`issue10/dev-kafka.yaml` 创建单节点、2 GiB 临时存储、内部 TLS listener、SCRAM-SHA-512 和按 topic/消费组限权的用户。单节点或 Pod 重建会丢数据，必须保持开发用途。Kafka 和 Flink 服务都是 `ClusterIP`，没有外部 listener、Ingress、NodePort 或负载均衡器。EKS 公开 API 的来源限部署主机 `/32`，API 仍要求 IAM/Kubernetes 身份验证。

Kafka 与 catalog 密码经 Kubernetes Secret 注入。Flink 作业以程序化 KafkaSource 加 Paimon sink 写表，不把 SASL JAAS 密码写入 SQL、FlinkDeployment 或容器参数。Kafka 服务端 CA 作为 Secret 文件挂载。Flink REST 操作入口留在集群内并禁用 Web 提交、取消与扩缩容，部署后检查实际 Pod 日志是否含密码。清单门禁拒绝公开路由、公开 Service、主机端口及明文密码环境变量。任何未来公开页面或资源在开放前必须实现身份验证，并实测匿名和越权拒绝。

现有写入节点几乎占满 CPU request，此合成数据作业临时落在查询节点，使用独立 `cove-flinkods` IRSA 写 ODS 前缀。为容纳主作业和独立回放作业，这个低流量 PoC 的 Flink JobManager 与 TaskManager 各请求 100m CPU，不代表生产容量配置。当前节点共享安全组，不能以这次 PoC 证明生产写入/查询网络隔离。catalog 使用现有 PoC 管理凭证，生产需专用最小权限身份。镜像含上游基础镜像漏洞，必须依据 ECR 扫描结果修复后才能成为生产基线。

## 执行顺序

在干净提交的 `infra/m1` 目录执行，私有 kubeconfig 保存在忽略的 `evidence/`：

```bash
bash issue10/deploy-image-repo.sh
bash issue10/deploy-dev-kafka.sh
bash issue10/build-image.sh
bash issue10/deploy-ingest-job.sh
bash issue10/produce-synthetic.sh
bash issue10/check-kafka-offsets.sh
bash issue10/inspect-ods.sh kafka_events
bash issue10/inspect-ods.sh kafka_event_rejections
bash issue10/deploy-ingest-job.sh replay
bash issue10/inspect-ods.sh kafka_events_replay
bash issue10/inspect-ods.sh kafka_event_rejections_replay
```

`produce-synthetic.sh` 以约 50 条/秒送入 1000 条有效事件（含前一天分区和新增字段），随后送入 1 条无效属性和 1 条迟到事件。事件 ID 含每次执行的批次号；不同执行和重建前后的 topic 名称可区分，按 `source_topic, source_partition, source_offset` 对账。验收须从 Kafka 实际位点、Paimon S3 文件和快照核对有效行数、关键字段、两个拒绝原因、重启后的无重复、独立回放、前一天分区完成标记及过期规则。Doris 查询属于 #11，不能用本表可读冒充 Doris 验收。

`inspect-ods.sh kafka_events` 的 `PARTITION_BOUNDARY` 行给出日期、S3 `_SUCCESS` 是否存在、UTC 过期候选时刻、当前是否超过该时刻。增量流读需先运行 `inspect-ods.sh kafka_events follow <当前 Kafka 下一位点>`，待输出文件出现 `STREAM_START` 再执行一次 `produce-synthetic.sh`；探针通过 Paimon `scan.mode=latest` 在私有 Pod 中等候新快照，并拒绝旧位点。第二批次后用 `check-kafka-offsets.sh 2004` 核对总位点。持续运行的正式下游还需设置独立 `consumer-id` 和 `consumer.expiration-time`；本探针是限时一次性读者。

## 本阶段证据

本地构建与校验：`mvn -B -f infra/m1/issue10/pom.xml clean verify`、`npm run check && npm test`、`pre-commit run --all-files`。真实部署和对账结果记录在忽略的 `infra/m1/evidence/issue10-*`。第一次 Kafka 清单中的 listener 名为 `authenticated`，违反 Strimzi `^[a-z0-9]{1,11}$` 限制，创建 Kafka CR 失败；改为 `auth` 后 Kafka、topic 和三个用户 Ready。首次脚本的失败证据保留，不当作通过。

提交 `bf83b1b3d19d416029e0c0faf6550fddc59b2ac7` 的首轮运行镜像 digest 为 `sha256:cc66754af44702b55debc968823a689c2d991b72e7e90542503655c3b8249933`。私有 Flink 作业 RUNNING，两个 Pod 的日志凭证扫描通过；ECR 扫描完成，27 Critical、83 High，生产发布阻断。生产者提交 1002 条消息，Kafka 实际起止位点在重启前核对为 0→1002；真实 S3 主表快照有 1000 个不同 ID 和 1000 个不同 Kafka 位点，其中 100 个保存新增 `campaign` 字段、1 个属于前一天分区。拒绝表有 `INVALID_ATTRIBUTES` 和 `LATE_BY_WATERMARK` 各 1 行。前一天 `dt=2026-09-27/_SUCCESS` 于 2026-09-28 23:49:01 UTC 写入 SSE-S3 桶，当前日尚无该信号；表 `schema-0` 确认 31 天分区过期配置。首轮 Flink REST 曾计 1 次 checkpoint 失败，后续连续 6 次完成；失败根因仍需确认，不能直接视为稳定性通过。

2026-09-29 00:00 UTC 的 SSM 节点补丁命令重启两台 EKS 节点。开发 Kafka broker 的临时存储被清空，同名 topic 的实际起止位点变为 0→0；S3 上主表 1000 条有效行与拒绝表 2 条仍在。恢复中的旧 Flink 作业 checkpoint 保留 partition 0 位点 1002，重建后的同名 topic 末位点却是 0，继续向其发送消息会有静默跳过风险。旧作业已在 `suspended/CANCELED` 状态退出；恢复改用 `r2` topic、独立认证账号和全新 checkpoint 前缀。新账号 Ready 后撤销旧账号及旧空 topic，避免遗留可用凭证。原始位点、Flink REST 与暂停证据存于忽略的 `infra/m1/evidence/issue10-broker-restart-*`；新批次独立回放和重启无重复须按真实运行结果判定。

提交 `a282fcf05bb1301055af21bfd7c4ee7ce703fc27` 的 `r2` 主作业从实际 S3 checkpoint 恢复后再次完成 checkpoint，Kafka 实际位点为 0→1002，消费和提交位点均到 1002。主表现在有 2000 条不同事件 ID、2000 个不同 `(topic,partition,offset)`，其中新 topic 的 1000 条与生产者清单逐字段一致；拒绝表为 4 条。独立回放表有新 topic 的 1000 条有效记录与 2 条拒绝记录，和主表新 topic 子集逐行一致；回放消费组提交位点 1002，失败提交数为 0。前一日与当前日的 `_SUCCESS` 均已在开启 SSE-S3 的私有桶中观察到；31 天实际过期尚未发生，不能视为清理时长通过。镜像仍有 ECR 扫描的 27 个 Critical 与 83 个 High，生产发布阻断。

> 注：本文中的 issue 与 PR 编号（如 #10）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
