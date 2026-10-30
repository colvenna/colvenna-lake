---
title: "M2 控制平面的安装、检查与恢复"
date: 2026-10-13
status: complete
issue: 23
---

# M2 控制平面的安装、检查与恢复

本页记录 Issue #23 的验收，内容包括：

- 在定义好的全新起点上完成首次安装，并计时；
- 组件健康检查；
- 单实例故障与元数据后端恢复；
- 升级与回退；
- 开发环境的可选 Kafka。

所有资源都在 AWS profile `work`（账号 `<ACCOUNT_ID>`、区域 `us-west-2`），部署在 M1 PoC 集群上，只使用合成数据。部署形态见 [EKS 部署与引擎验收](catalog-on-eks.md)，服务端设计见 [cove-catalog LLD](https://github.com/colvenna/colvenna-catalog/blob/main/docs/design/colvenna-catalog-lld.md)。

**证据的来源版本**：全部结果来自提交 `b6f8be568b8b9b593f93948185969bd6e6131df3`，镜像以该 SHA 构建并安装，原始日志在私有证据目录 `m2-23/run-b6f8be5/`。之后的提交只改了本页。

早先的四次尝试也保留在证据目录中：

- `run-2ec6ade-slow-history/`；
- `run-d5e2a2f/`，其中拆除失败的部分在 `teardown-failed-owner/`；
- `run-67e28b6/fresh-doris-skipped/`；
- `run-9bd6d54/`。

它们暴露的问题见 [过程中的发现](#过程中的发现)。

**部署身份**：这些运行都以 AWS profile `work` 部署，它解析到实例角色 `arn:aws:sts::<ACCOUNT_ID>:assumed-role/<执行身份>/...`。升级与回退的每一步，以及全新安装，都把部署者写进证据；其余运行不改动 catalog 的部署。

## 结论

| 接收条件 | 结果 | 依据 |
| --- | --- | --- |
| 在已定义的全新集群起点完成首次安装并达到可写可查状态；耗时按产品目标测量 | **通过** | `teardown.sh` 回到起点，其间删除了一个运行中的开发环境 Kafka 及其卷。`fresh-install.sh` 逐项核对起点后开始计时，到 Flink 经 REST 写入、Doris 查询通过共用 677 秒（11 分 17 秒），目标 30 分钟。镜像构建用到了本机缓存，见下文 |
| 单实例故障及元数据后端恢复后，目录与提交历史一致 | **通过** | 节点排空、副本被强制杀掉时，960 次提交全部找到，读端没有看到不完整的提交。从备份恢复元数据库后，按流程 reconcile 并重新登记：目录与恢复前相同，三张表共 1090 个快照的读取结果都与恢复前相同 |
| 升级或回退不导致已发布快照不可读，部署身份和证据可追溯 | **通过** | 依次升级、回滚、部署旧模式版本（被拒绝）、再升级，写入方期间提交 800 次。每一步之后此前的快照都还在且内容不变，最终 800 个都可读。每一步都记录部署者、Helm 修订号、声明的镜像，以及各 Pod 实际运行的镜像。跨模式版本的升级见下文的说明 |
| 开发环境可按声明启动可用的测试 Kafka，卸载时数据保留或删除行为明确可见 | **通过** | 安装后写入 100 条；卸载保留卷，并记录集群 id；重装接回原数据，100 条全部读回；`purge` 后没有卷剩下；临时存储卸载后什么都不剩。每次检查都附带拒绝的对照 |

## 首次安装

### 起点

M2 安装到一个受支持的环境中，这个环境就是 M1 的基础设施：

- VPC；
- EKS 集群及其节点组；
- 项目的 RDS PostgreSQL 实例与仓库桶；
- Flink Kubernetes Operator 与 Doris operator；
- Strimzi operator（命名空间 `cove-lake-kafka`），开发环境 Kafka 由它运行。

创建这些基础设施不算在 M2 的安装里。M2 的产品目标是"全新 EKS 集群 30 分钟内完成部署并可写可查"（[新闻稿](../../product/press-release.md)），其中的部署指的就是在这样的集群上装入平台本身。

起点上没有任何 M2 的东西：

- 没有 `CoveLakeM2Catalog` 栈，也就没有 ECR 仓库、IRSA 角色和数据库 Secret；
- 没有 M2 命名空间；
- RDS 实例上没有 `cove_catalog` 数据库，也没有同名角色；
- 没有开发环境 Kafka，也没有它保留下来的卷。

`teardown.sh` 把集群带回这个起点，开发环境 Kafka 连同保留的数据一起删除。`fresh-install.sh` 开始前会逐项核对，数据库与角色直接向 RDS 查询。

### 计时的阶段

| 阶段 | 内容 |
| --- | --- |
| deploy-stack | CDK 部署 `CoveLakeM2Catalog` |
| build-image | 构建 catalog 镜像并推送，tag 为源码 SHA |
| install | 建库建角色、私有 CA 与服务端证书、Helm 安装、引导管理员 |
| health | 下文的健康检查 |
| verify | 访问检查：有效、缺失、伪造、过期、撤销、已停用的身份，服务身份访问管理面，明文 HTTP，健康端口，NetworkPolicy |
| flink-write | Flink 经 REST 与按表凭证写表、建 tag 与分支 |
| doris-query | Doris 经 REST 查询这些表，包括 native 与 JNI 两种读取器、快照、tag 与分支 |

任一阶段失败，或总时长超过 30 分钟，运行都判为失败。

## 健康检查

`check-health.sh` 每项输出一行，任一项失败就返回非零：

- **cove-catalog**：
  - 每个副本都就绪，且分布在多个节点上；
  - 有 PodDisruptionBudget；
  - 每个副本的本地就绪探针返回成功（探针会检查 PostgreSQL 连接）。
- **元数据库**：
  - RDS 实例状态为 `available`，同时报告版本、多可用区与备份保留天数；
  - catalog 模式版本等于当前提交的迁移数。
- **平台服务**：Flink Kubernetes Operator 与 Doris operator 可用；加 `--dev-kafka` 时，还检查 Strimzi operator 和开发环境 Kafka。

## 单实例故障与元数据后端恢复

### 高可用

- **副本与分布**：cove-catalog 默认两个副本，用 `topologySpreadConstraints`（`whenUnsatisfiable: DoNotSchedule`）分到不同节点：
  - 只要某个可用的节点（节点选择器允许的写节点）上还没有副本，新副本就不会落到已有副本的节点上；
  - 即使那个节点暂时不接收 Pod（例如为排空而停止调度），新副本也宁可等待；
  - 节点数少于副本数时，副本在这些节点上尽量均分，不会因此卡住；
  - 约束按 ReplicaSet 计算，滚动更新时只把新副本彼此分开，不受旧副本影响；
  - 设 `spreadAcrossNodes=false` 时只是尽量打散：副本宁可与另一个副本同节点，也不等待。
- **中断预算**：PodDisruptionBudget 要求至少一个副本可用。
- **滚动更新**：`maxUnavailable: 0`，新副本就绪之前不会撤下旧副本。
- **无状态**：副本之间不共享内存状态，提交在 PostgreSQL 的行锁下串行，见 LLD 的提交协议。

`run-recovery.sh` 的 A 部分在写入进行中依次：

1. 排空一个副本所在节点上的 catalog Pod，模拟计划内的节点维护。PodDisruptionBudget 保证始终有一个副本在服务。被驱逐的副本不能和另一个副本挤在同一节点上：没有空闲的写节点时必须等待，有空闲的写节点时就在那里启动。节点恢复调度后，两个副本重新分布在不同节点；
2. 不带宽限期地杀掉一直在服务的那个副本，模拟实例故障。

每一步都要求写入方仍在提交。结束后，写入方的检查与一致性报告必须都通过。更多故障组合（数据库连接中断、数据库不可达、全部副本失效）见 [#19 的提交与恢复](commit-recovery.md)。

### 备份与恢复

**生产环境的机制**：RDS 自动备份与按时间点恢复。

- M1 的 PoC 实例备份保留 1 天；生产应按恢复要求设置保留期，并开启多可用区。
- 按时间点恢复会得到一个新实例，也就有新的端点。恢复后，用新端点更新 `database.url` 并重新部署。

**恢复后的状态与处置**：元数据库回到时间点 T，表目录中的文件则一直写到故障时刻。

- **T 之后的提交**：
  - 快照文件在，指针落后；
  - 一致性报告会列出这些表，管理员逐表 reconcile，指针追上文件，提交历史与故障前一致；
  - reconcile 之前，这些表的新提交会被围栏，因为槽位已被占用。所以恢复后应先 reconcile，再放开写入。
- **T 之后新建的表**：不在恢复后的 catalog 中，用管理员的 register 按原路径重新登记。
- **只在 catalog 里的信息**：T 之后的授权、token、审计与提交意图都会丢失。这些就是元数据库自身的 RPO，以备份为准；需要的话，在恢复后重新签发 token、重新授权。

**演练**：`run-recovery.sh` 的 B 部分为了速度，用 `pg_dump` / `pg_restore` 代替按时间点恢复，留下的状态与按时间点恢复相同。

1. 写入进行中做备份，写入方随后继续提交；备份之后再新建一张表 `t3`，提交 10 次；
2. 停止 catalog，从备份恢复，再启动；
3. 核对恢复后的状态：
   - catalog 列出的库表与恢复前相比，只少了 `t3`；
   - 只有写入中的表 `t2` 落后于文件，`t1` 一致；
4. reconcile `t2`，并把 `t3` 按原路径重新登记。之后 catalog 列出的库表与恢复前相同，三张表每个快照的读取结果都与恢复前相同：计划行数逐个快照比对，实际读出行数在首尾和每第 25 个快照上比对；
5. `t2` 恢复写入。

## 升级与回退

### 同一模式版本内

`helm upgrade` 与 `helm rollback`，滚动进行，不停服务。

`run-upgrade.sh` 在写入进行中依次：

1. 部署上一版本（它自己的 chart 与镜像）；
2. 升级到本版本；
3. `helm rollback` 回到上一版本；
4. 部署模式版本更早的服务端，必须被拒绝，见 [跨模式版本](#跨模式版本)；
5. 再升级到本版本。

每一步之后，表的快照历史都必须保留此前的每个快照，内容不变。部署本版本的两步之后，副本还必须分布在不同节点上。

### 跨模式版本

服务端启动时按版本迁移模式，迁移只增不改。遇到比自身更新的模式版本时，旧服务端拒绝启动，日志为 `newer than this server`。原因是旧服务端不认识新模式里的数据，例如 #21 的授权：如果它照常运行，就会绕过这些授权。

所以，回退到模式版本更早的服务端不会成功，但也不会造成中断：

- 新副本起不来，`maxUnavailable: 0` 让旧副本继续服务；
- `helm upgrade --atomic` 在超时后自动回到上一个版本。

`run-upgrade.sh` 的第 4 步就是这样一次被拒绝的回退，并核对旧服务端的拒绝日志。要真正回到更早的模式版本，按"备份与恢复"一节，从升级前的备份恢复元数据库，再 reconcile。

### 部署身份与证据

每个版本都可以追溯到一个提交：

- 镜像 tag 是源码 SHA，ECR 仓库的 tag 不可变；
- Helm 按 digest 部署镜像，Pod 带 `cove-lake/source-sha` 注解；
- 安装脚本拒绝没有以当前 SHA 打 tag 的镜像。

`run-upgrade.sh` 每一步都记录以下内容：

- 部署者（`aws sts get-caller-identity`）；
- Helm 修订号；
- Deployment 声明的镜像，以及各 Pod 实际运行的镜像 ID。

ECR 仓库只保留最近 10 个镜像，`teardown.sh` 销毁栈时也会清空仓库，所以升级测试所需的旧版本镜像要事先构建好。

## 开发环境 Kafka

`deploy/helm/cove-dev-kafka` 只用于开发与 PoC，生产 Kafka 不由平台安装。

**部署方式**：通过集群里已有的 Strimzi operator 声明：

- 一个 KRaft 节点；
- 集群内 TLS 加 SCRAM-SHA-512 的 listener，没有外部 listener；
- 只有带 `cove-lake.io/dev-kafka-client=true` 标签的命名空间能连接，由 Strimzi 生成的 NetworkPolicy 执行；
- 声明式的 topic（默认保留 1 天）与用户。

Strimzi operator 只监听 `cove-lake-kafka` 命名空间，所以 release 装在那里。

**数据的保留与删除**，由 `dev-kafka.sh` 执行，结果都写在证据里：

| 操作 | 数据 | 如何看到 |
| --- | --- | --- |
| `uninstall`（默认 `deleteClaim=false`） | 保留卷 | PVC 留下；ConfigMap `cove-dev-retained-data` 记录它属于哪个 Kafka 集群 |
| 再次 `install` | 接回原数据 | 按记录的集群 id 恢复：资源先暂停，写入 id，再恢复调和。先前的消息仍能读到 |
| `purge --yes` | 删除 | 先置 `deleteClaim=true`，等 operator 把卷标记为待删，再卸载；最后确认没有 PVC 剩下 |
| `storage.type=ephemeral` | 不保留 | 卸载后什么都不剩，Pod 重启也会丢数据 |

保留下来的消息仍会按 topic 的保留期过期。要用 `dev-kafka.sh` 卸载：直接 `helm uninstall` 也会留下 PVC，但不会记录集群 id，之后只能清除，不能接回。

**检查**：每次检查都有正向对照：

- 带凭证的客户端能列出 topic，同一个客户端去掉凭证后被拒绝；
- 有标签的命名空间能建立 TCP 连接，没有标签的命名空间能解析域名，但连接被丢弃。

## 结果

### 首次安装的结果

2026-10-14 09:57–10:14（UTC）。

**拆除**：`teardown.sh` 用时约 5 分钟，依次：

- 删除 3 个 flow 库及其文件；
- 卸载 catalog；
- 发现开发环境 Kafka 仍在运行，连同卷一起删除；
- 删除 M2 命名空间、`cove_catalog` 库与角色；
- 销毁 `CoveLakeM2Catalog` 栈。

**全新安装**：`fresh-install.sh` 先核对起点：栈、库与角色、命名空间、开发环境 Kafka 及其卷都不存在；集群有 2 个写节点、2 个查询节点、7 个加载节点。之后计时：

| 阶段 | 秒 |
| --- | --- |
| deploy-stack | 85 |
| build-image | 31 |
| install | 69 |
| health | 26 |
| verify | 144 |
| flink-write | 169 |
| doris-query | 153 |
| **合计** | **677**，目标 1800 |

- Flink 的 18 步全部通过，包括：
  - 建库建表，upsert 与删除，加列；
  - 流式提交，consumer；
  - tag，按快照回读，分支；
  - compaction，过期快照，回滚；
  - 视图，函数，改名与删除。
- Doris 的 10 项检查全部通过：
  - 行数与新加的列；
  - 快照、tag 与分支；
  - 分区；
  - 原生读取与 JNI 读取；
  - append 表的 23 个 split 全部走原生读取。
- 判定"可写可查"看的是 Flink 与 Doris 的结果文件，不只是退出码。
- **镜像构建用到了缓存**：build-image 的 31 秒用到了本机的 Maven 与 Docker 缓存。冷缓存时这一步会更长，但总时长离目标还有 18 分钟余量。

### 健康检查的结果

共运行三次，每项都是 OK：

- **升级之后**（`health/`）：
  - 2 个副本就绪，分布在 2 个节点；
  - PodDisruptionBudget 为 `minAvailable 1`；
  - 每个副本的就绪探针报告 PostgreSQL 可达；
  - RDS 为 `available`、PostgreSQL 16.13，不是多可用区，备份保留 1 天；
  - 模式版本 2，等于当前提交的迁移数；
  - Flink 与 Doris operator 就绪。
- **开发环境 Kafka 运行时**（`kafka/2b-health/`）：以上各项，外加 Strimzi operator 与 Kafka `cove-dev` 就绪。
- **全新安装中**（`fresh/health/`）：与升级之后相同。

### 实例故障的结果

2026-10-14 09:14–09:29（UTC），4 个写入方共提交 960 次，每次 200 行：

| 时间 | 事件 |
| --- | --- |
| 09:14:52 | 排空另一个副本所在的节点，此时已发布到快照 13 |
| 09:15:04 | 留下的副本继续服务；被驱逐的副本在等待自己的节点 |
| 09:15:14 | 节点恢复调度，两个副本重新分布在两个节点 |
| 09:15:47 | 不带宽限期杀掉一直在服务的那个副本 |
| 09:28:41 | 写入方结束 |

写入方的检查全部通过：

- 960 次提交各出现一次；
- 955 次直接确认，5 次首次失败后重试成功，没有结果不明的；
- 26 次读取都没有看到不完整的提交；
- 快照 id 连续。

之后 `t1` 的一致性报告干净。

### 元数据后端恢复的结果

| 时间 | 事件 |
| --- | --- |
| 09:29:52 | `t2` 写入中做 `pg_dump`；写入方随后提交到 120 次 |
| 09:31:34 | 备份之后新建 `t3`，提交 10 次 |
| 09:36:57 | 恢复前：3 张表共 1090 个快照，catalog 列出 7 张表 |
| 09:36:57–09:37:12 | 停止 catalog，从备份恢复，再启动 |
| 09:38:14 | catalog 少列了 `t3`；`t1` 一致；`t2` 不一致，停在快照 30 |
| 09:38:32 | reconcile `t2`：指针 30 → 120 |
| 09:38:39 | `t3` 按原路径重新登记，一致；catalog 列出的表与恢复前相同 |
| 09:43:01 | 三张表 1090 个快照的读取结果与恢复前完全相同 |
| 09:43:55 | `t2` 再提交一次成功 |

### 升级与回退的结果

2026-10-14 08:35–09:13（UTC），4 个写入方共提交 800 次：799 次直接确认，1 次首次失败后重试成功；87 次读取都没有看到不完整的提交。

| 步骤 | 运行的版本 | Helm 修订号 | 之后可读的快照 |
| --- | --- | --- | --- |
| 1. 部署上一版本 | `230e8db` | 2 | 24 |
| 2. 升级到本版本 | `b6f8be5` | 3 | 59 |
| 3. `helm rollback` | `230e8db` | 4（回到 2） | 99 |
| 4. 部署旧模式的 `be3a644` | 拒绝；`--atomic` 回到 `230e8db` | 5 失败，6（回到 4） | 135（期间）、246 |
| 5. 再升级到本版本 | `b6f8be5` | 7 | 800 |

- 第 4 步中，旧服务端的日志为 `Catalog schema version 2 is newer than this server (1); refusing to start an older server on a newer schema.`；
- 每一步的历史都保留了此前的全部快照，没有丢失或变化；
- 第 2 步与第 5 步之后，本版本的两个副本分别在两个写节点上，节点名记在各步的记录里；
- 结束时表的一致性报告干净。

**没有覆盖的部分**：目前仍在开发阶段，按需求方的决定，升级不必保持版本之间的兼容。所以跨模式版本的升级与回退只做到下面这些，不再另行演练：

- 这里的升级不跨模式版本：上一版本与本版本都在模式版本 2。
- 跨模式升级（V1 → V2）由 [#21 的运行](authorization-on-eks.md#模式迁移) 验证：在已有 31 张表的 catalog 上升级，前后的表清单完全相同。那次没有写入方同时提交，因为升级到 V2 后，已有身份在授权之前一律被拒绝。
- 回到更早模式版本的完整流程（恢复升级前的备份，再部署旧版本）没有整体演练。其中从备份恢复和 reconcile 两步由上面的元数据后端恢复覆盖。

### 开发环境 Kafka 的结果

2026-10-14 09:44–09:57（UTC）：

| 步骤 | 结果 |
| --- | --- |
| 安装（持久卷） | 卷 `data-cove-dev-cove-dev-node-0` 已绑定 |
| 写入 100 条 | OK；不带凭证被拒绝，没有标签的命名空间连接被丢弃 |
| 卸载 | 卷保留；ConfigMap `cove-dev-retained-data` 记下集群 `v04ZMC-lRCCK2HcURCjnbQ` |
| 重装 | 按记录的集群 id 接回同一个卷 |
| 从头读取 | 100 条全部读回；两项拒绝同上 |
| `purge --yes` | 没有卷剩下 |
| 临时存储安装、写入 10 条、卸载 | OK；卸载后什么都不剩 |
| 再装一次（持久卷） | 留给拆除，拆除时连同卷一起删除 |

### 过程中的发现

- **逐个快照核对太慢**：`2ec6ade` 的升级运行中，快照历史逐个快照规划，621 个快照用了 16 分钟。写入方在第 5 步之前就写完了，运行判为失败。之后改为 16 个线程并行，写入方延长到约半小时。
- **主用户不继承它创建的角色**：RDS 主用户创建了 `cove_catalog` 角色，却不是能删除其数据库的成员，`d5e2a2f` 的拆除报 `must be owner of database cove_catalog`。现在删库前先把该角色授予主用户。
- **`env` 被遮蔽**：这台主机的 `~/.local/bin/env` 是某个安装器留下的脚本，什么都不执行，却排在 `/usr/bin/env` 之前。`67e28b6` 的全新安装因此 0 秒就"通过"了 Doris 阶段，并没有查询。现在不再经过 `env`，判定也改为看引擎的结果文件。
- **评审之后的修改**：`9bd6d54` 的拆除与全新安装都通过了。之后评审指出几处可能误报或证据不足的检查，修改后在 `b6f8be5` 上把全部步骤重跑了一遍：
  - 分散检查改为只取一次 Pod 列表，并等 rollout 完成；
  - 杀掉的是事先记下的那个服务中的副本；
  - 拆除前先装一个开发环境 Kafka，拆除会记下是否发现了它。

## 复现

```bash
bash infra/m2/scripts/check-health.sh <evidence> [--dev-kafka]
bash infra/m2/scripts/run-recovery.sh <run-id> <evidence>
bash infra/m2/scripts/run-upgrade.sh <run-id> <evidence> <from-sha> <older-schema-sha>
bash infra/m2/scripts/dev-kafka.sh install|check|uninstall|purge <evidence> ...
bash infra/m2/scripts/teardown.sh <evidence> --yes && bash infra/m2/scripts/fresh-install.sh <run-id> <evidence>
```

> 注：本文中的 issue 与 PR 编号（如 #23）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
