# M1 Doris 读取 Paimon 验证

本页对应 Issue #14。
目标是用真实私有 S3、共享 PostgreSQL JDBC catalog 和隔离合成表，
判断 Doris 4.1.4 是否能可靠查询 Paimon 2.0.0。Doris 4.1.4
内置的 Paimon reader 为 1.4.2；发布文档有 JDBC catalog 与
Deletion Vector 读取能力，但这个跨版本组合须以实测行集判定。
Doris 写入 Paimon 不在本实验范围。

按 Issue #14 的五条接收条件，当前状态为 **COMPLETE**，判定依据见
[结项判定](#结项判定)；M1 的四个 issue（#8、#14、#15、#16）均已按接收条件结项。下文其余各节按时间保留各轮记录，
其中的 `RED`、`NOT_COMPLETE` 等是当时的状态。

## 安全边界

AWS 资源在 `work` 账号 `<ACCOUNT_ID>`、`us-west-2`
用 TypeScript CDK 创建。`CoveLakeM1Issue14Doris`
只授予 Doris 服务账号读取 `warehouse/ods.db/issue14_*`，
合成数据写入账号只能变更同一隔离前缀；两者均使用精确的 EKS IRSA
信任主体。Paimon 的 JDBC catalog 初始化还需要列举 `warehouse/`
等根路径，因此角色可看到其他表的对象名称，但不能读取其数据文件；
这不构成 M2 严格按表凭证的验证。`CoveLakeM1Issue14Images` 是私有、不可变、
开启镜像扫描的 ECR 仓库。Doris FE 与 BE 镜像固定到
4.1.4 官方 x86 digest，只加入经 SHA-256 校验的 PostgreSQL
42.7.8 JDBC 驱动。镜像在 ECR 扫描完成后才计作可用于 PoC。

DorisCluster 必须使用独立的 `kubernetes.io/basic-auth`
Secret 和 FE 启动时的非空 root 密码；Secret 与 FE 校验值必须一致。
FE 的 `skip_localhost_auth_check=false` 关闭对来自 `127.0.0.1`
的 root/admin 默认认证旁路，端口转发也必须执行密码校验。
内置 `admin@%` 的默认密码为空；私有初始化阶段用独立的随机凭据
设置该账号密码，随后分别验证 root、admin 空密码均被拒绝，
且新 admin 凭据可登录。在 DorisCluster 创建前先给本次 FE Pod
施加临时 NetworkPolicy。先核对 FE Pod IP 的 MySQL 端口
确已监听，再要求独立 Seed Pod 实测无法连接 FE；
在 FE Pod 内通过 stdin 设置 admin 二阶段散列口令，
不把明文放进 SQL 或进程参数。认证核对后才撤销临时隔离，
由 Seed Pod 确认连接恢复，再允许 BE 注册和本机 port-forward。
全程无公开 Service/Ingress。此集群原有 VPC CNI policy agent
参数为 `--enable-network-policy=false`，控制器 ConfigMap 也为
`enable-network-policy-controller=false`；两个开关都须启用，
并核对本次 FE 的 PolicyEndpoint 已生成。启动期用不依赖 BE 的
`SHOW FRONTENDS` 验证 FE Pod IP 上的认证监听。
[AWS 文档](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html)
说明 `standard` 模式允许新 Pod 在策略装载前短时放行；
本次保留 `standard`，运行结束后恢复两处开关并删除本次生成的
PolicyEndpoint 对象。
Pod 启动到策略生效间的内部访问窗口无法由本实验排除，
因此只准用于私有、隔离的合成数据 PoC；
所有可从外部访问的入口均不得在认证核对前开放。
改用 `strict` 模式还须为集群内 CoreDNS 和各业务 Pod
补齐放行策略，本次不修改共享网络基线。
FE 的配置以固定 4.1.4 私有镜像 digest 内的 Apache 原版 `fe.conf`
为基底，仅去掉两处行尾空格，文件 SHA-256 为
`0fbc96276ce85b54ae5e273a9551c3ef33fe83d562b6b46b39de7c917caa5ac0`；
渲染器核对该值后才追加与 Secret 对应的校验值，以及
`enable_fqdn_mode=true`。固定版本 Doris Operator 25.8.0
要求自定义 FE 配置启用 FQDN 模式，以便 Pod 重建后保持 FE 身份；
缺少此项会使 Operator 选择 Pod IP 模式。原版配置中的
`JAVA_OPTS_FOR_JDK_17` 等启动必需设置保持不变。
BE 清单设置 `skipDefaultSystemInit=true`，跳过 Operator 会生成的
特权 `default-init` 容器，以满足命名空间的 PodSecurity `baseline`；
本 PoC 不据此声称生产节点的系统参数调优或查询性能已验收。
FE 与 BE 的 Service 只能为 `ClusterIP`，
均使用受限服务账号，不创建 Ingress、
NodePort、LoadBalancer 或公开页面。查询客户端仅通过绑定
`127.0.0.1` 的、需 EKS 身份授权的 port-forward
连接 FE；空密码登录须明确拒绝。对已存在的合成 S3
对象进行匿名 GET，须返回 403。
现有 EKS 节点 CPU 请求量不足以运行 FE/BE，因此
`CoveLakeM1Issue14Capacity` 通过 TypeScript CDK 在已核实的
私有 NAT 子网各增加一台专用节点，EBS 加密、IMDSv2 必选且 Pod
不可访问实例元数据；taint 与 nodeSelector 将容量留给本次 Doris。
FE 放在专用查询节点；BE 为分散有限的 PoC CPU 和内存请求，放在
复用写节点角色的第二台专用、隔离节点，但仍使用只读 Doris 服务账号。
这一短期布置不代表 HLD 中 Doris 正式落在查询节点组的部署目标已验证。
脚本验证节点无公网 IP，实验清理完成后由 CDK 删除这两个节点组。

私有 ECR 构建证据位于
`infra/m1/evidence/issue14-images-20260929063342-3055120/`。
FE、BE 各自的完整扫描均为 129 个 Critical、801 个 High；
扫描完成不表示漏洞已修复。共享 JDBC catalog
没有面向终端用户的授权控制。因此本环境只能运行短期、
无公开入口、仅合成数据的兼容性 PoC；不能作为生产发布、
真实用户数据或行列权限验收。

## 结项判定

2026-10-12 按 Issue #14 的五条接收条件结项。需求方确认：在官方 Doris 4.1.4
镜像上自建的 Paimon 2.0.0 读取器（下称 SDK2 读取器，见
[实验产物](paimon2-reader-candidate.md)）即 issue 边界中的“经确认的替代只读路径”；
Doris 4.1.4 自带的 Paimon 1.4.2 读取器读 DV 表为 RED，判为不可用。据此 **Doris
成立为 M1 的主查询引擎**，前提是使用 SDK2 读取器并把 catalog 的
`meta.cache.paimon.table.ttl-second` 设为 `0`；不启动 StarRocks 4.1.3 对照
（其 4.1.3 源码固定 Paimon 1.3.1，文档也未列出 JDBC catalog，见下文替代候选预检）。
M1 范围内的安全边界（私有网络、FE 认证、每次运行专用的只读读端与 IAM 读权限）已在各轮核对；
生产发布仍受镜像漏洞扫描门禁约束：ECR 对下述 FE、BE 镜像的扫描均为 COMPLETE，各有 148 项
Critical、804 项 High，只准内部合成数据 PoC。

**版本组合**：Doris `4.1.4-rc04-ad35a140c7f`（`SHOW FRONTENDS`），SDK2 读取器回执
`2d4412f9…`，基础镜像 FE `m1-doris@sha256:4608a0cb51305f43c68dfe64c215657ea62f47cbec985d4000061a55e6b173d9`、
BE `m1-doris@sha256:956817fda0eb7bf929dbb17608d071b70173bf99e82cadd6976a408c471d9d98`；
加入受限 JDBC 工厂（`infra/m1/issue15` `95f6f01`）后为 FE
`m1-doris@sha256:ae19e6d65ec43656b85462580253cc968ddbf4ba668a65963bbf02fa723a7020`、
BE `m1-doris@sha256:ecaf6f89ffd01d19c0160b85dc787219c25c7efb7d2b2325ea9d73c495929cfa`。
写入侧为 Flink 1.20.4、Paimon 2.0.0、PostgreSQL JDBC catalog。

| 接收条件 | 证据 | 判定 |
| --- | --- | --- |
| 与 Paimon 原生读逐项核对，更新和删除后无遗漏或旧值 | SDK2 第五轮有界验收：MOR/DV/append 在更新、删除后与原生行集逐行一致；#16 的 24 小时基线中，DV ODS 外表每 5 秒一次的查询都恰好等于确定性负载的某个前缀：键连续、版本计数与键界一致、逐行按公式核对金额/客户/版本的不符行为 0，等价于与原生行集相同，DWS 外表与 MV 同样逐次核对；终态与 MySQL、原生 Paimon 一致（详见下表） | 通过 |
| 记录读路径、快照可见延迟、schema 变更可见延迟与代表性查询耗时 | 读路径与缓存见下文；24 小时基线的 Paimon→Doris 外表与 MV 新鲜度、10+1 QPS 查询耗时见下表；schema 变更可见延迟只有 append 增列的单个观察值 9,073 ms | 通过（schema 延迟为观察值） |
| 确切版本组合和不可用特性有验收记录 | 上文版本组合；下文不可用特性清单 | 通过 |
| time travel、增量读及系统表逐项标记 | 下文能力表 | 已逐项标记 |
| 依据实测确认 Doris 是否成立为主查询引擎 | 本节结论 | 成立（SDK2 读取器） |

24 小时基线 `20261011062753_1289437`（2026-10-11 12:31 至 10-12 12:31 UTC，8,640 万次 CDC
变更、8.64 亿条 Kafka 事件，方法与按 insert/update/delete 分列的结果见
[#16 报告](capacity-baseline.md)）的 Doris 指标。三类操作的 p95 相差不到 0.03 分钟，下表给合计；
全程时钟测量 49,805 次，最大偏差 42.9 毫秒：

| 指标 | 样本数 | p95 | p99 | 目标 |
| --- | ---: | ---: | ---: | --- |
| Paimon→Doris 外表（DV ODS），全程 | 846,277 | 0.76 分钟 | 1.28 分钟 | 3 / 6 分钟 |
| Paimon→Doris 外表，探针修正后的 04:40–12:31 | 282,073 | 0.10 分钟 | 0.10 分钟 | 3 / 6 分钟 |
| Paimon→Doris MV（DWS 上每分钟刷新），全程 | 846,277 | 1.54 分钟 | 2.12 分钟 | 5 / 10 分钟 |
| Paimon→Doris MV，探针修正后 | 282,073 | 1.03 分钟 | 1.04 分钟 | 5 / 10 分钟 |
| 源库→Doris ODS 外表，全程 | 846,277 | 1.70 分钟 | 2.29 分钟 | 5 / 10 分钟 |
| 源库→Doris ODS 外表，探针修正后 | 282,073 | 1.40 分钟 | 1.49 分钟 | 5 / 10 分钟 |
| 固定看板查询 10 QPS（承载基线负载的 Doris，10-12 03:45–12:31） | 315,700 | 2.5 毫秒 | 2.6 毫秒 | 3 / 10 秒 |
| 交互点查 1 QPS（同上） | 31,570 | 1,112 毫秒 | 1,402 毫秒 | 不作秒级承诺 |
| 固定看板查询 10 QPS（#14 保留现场，24 小时） | 864,000 | 4.2 毫秒 | 5.8 毫秒 | 3 / 10 秒 |
| 交互点查 1 QPS（#14 保留现场，24 小时） | 86,400 | 1,077 毫秒 | 1,348 毫秒 | 不作秒级承诺 |

查询负载为[静态千万行查询](static-query-10m.md)的同一份 1,000 万行、7 天窗口 DV 数据集：看板查
Doris 内部 MV `summary`（4 行），交互点查按主键读外表。“#14 保留现场”是 #14 会话保留的 Doris
集群 `issue14-doris-20261002011424-165973`；“承载基线负载的 Doris”是同时服务上表新鲜度探针的
`issue15-doris-20261011062753-1289437`，两路都在集群内运行（Pod 内转发到 FE Service），较早的
30 分钟静态轮次经主机 port-forward，耗时含往返，因此更高。全部查询结果正确；外表与 MV 的每次
读取都恰好是确定性负载的某个前缀，终态与 MySQL 一致。前 6 小时 Doris 探针每轮查询一次
`$snapshots`，之后每 60 轮一次，其耗时随保留快照增长到约 100 秒，期间新鲜度探测停顿，全程分位数
因此偏大；04:34 UTC 停用该查询后的数值反映 Doris 本身。

**SDK2 读取器能力**（各项所用的表见说明列）：

| 能力 | 判定 | 说明 |
| --- | --- | --- |
| 当前快照读 | 通过 | 第五轮有界验收（MOR、DV、append）与 24 小时持续核对（DV ODS、DWS、MV） |
| time travel（快照号） | 通过 | 第五轮维护后的历史快照（MOR、DV、append）；维护前 DV L0 的显式版本读为 RED，见下文 |
| tag | 通过 | `FOR VERSION AS OF '<tag>'` 与 `@tag('name'='<tag>')` 读出的 DV ODS、非 DV DWD 状态的八项聚合与原生 tag 读相同，并恰好等于确定性前缀，不符合公式的行数为 0；append 表未测 |
| branch | 不支持 | DV ODS、DWD 上 Paimon 2.0.0 `JdbcCatalog` 拒绝加载 branch，Doris 与原生 catalog 报同一错误；须待 M2 REST catalog |
| 增量读 | 受限 | DV ODS、DWD 上 `@incr` 返回的行数与五项校验和与原生 changelog 一致，但不返回 RowKind |
| 系统表 | 受限 | `$tags`、`$branches` 内容与原生一致；`$snapshots` 可读但未与原生逐项对照，且耗时随保留快照数增长；`$files`、`$schemas`、`$audit_log` 仅确认可查询；`$indexes` 不支持 |
| append 增列 | 通过 | 新旧列可见性与原生一致；可见延迟只有第五轮的单个观察值 9,073 毫秒，样本不足、只报告观察值；主键表的 schema 变更可见延迟未测 |

**读路径与缓存**：持续 CDC 加增量 compaction 时，DV ODS、DWD、DWS 外表查询全部走
JNI 分片（`paimonNativeReadSplits=0`，ODS 一个 split 读 966,100 行合并为
606,400 行，2026-10-12 采集）；第五轮全量 compaction 后的 DV 表为 native 2/2。Doris 默认的表元数据
缓存会让外表停留在旧快照：同一 Doris 上的对照 catalog 24 小时内 12,518 次读取，
落后 Paimon 最新状态 p50 4.6、p95 9.2、最长 11.3 分钟（读到的始终是一致的旧前缀），
不满足 3 / 6 分钟目标，因此 `ttl=0` 是必需配置。

**不可用或受限特性**：Doris 4.1.4 自带 Paimon 1.4.2 读取器的 DV/L0 读（RED）；
JDBC catalog 下的 branch；`$indexes`；增量读的 RowKind；默认元数据缓存下的新鲜度；
Doris 4.1.4 会把 `CREATE CATALOG` 中的 JDBC 读端口令明文写入 FE 审计日志（#15 实测，见
[回刷收敛](backfill-convergence.md)），读端须为
每次部署专用的只读角色。

## 对账与判定

`issue14/DorisSeedProbe` 分别创建不带 DV 的主键表、带 DV 的主键表、
append 表。每次提交后固定 Paimon snapshot，
由原生 reader 从真实 S3 数据文件生成逐行基线；DV 表在更新删除后
还必须在原生 split 中查到非空 deletion file，才算真正覆盖 DV。
原生基线还须逐项确认 id 2 改为新版本、id 3 消失、id 4 新增，
以及 append 事件 103 和增列后的事件 104，不能只靠两种读端彼此相同。
写入账号以 `write-only=true` 提交；先让 Doris 核对首次 L0 行集，
再用独立的 Flink 全量维护将 1027 行 DV 基线提升出 L0；
随后提交四条更新/删除记录，在 minor 维护前再次核对 L0 行集，
并记录已提交的写入快照到 Doris 可见的时长。即使 Doris 的 L0 核对失败，
仍继续执行维护后的 DV 核对，但最终结果为 RED。
维护后同时核对 Doris 默认读与显式固定 snapshot 的读。行集未变化时
默认读只报告 `DEFAULT_ROW_MATCH`，不推断所读 snapshot；
固定读取能核实维护后的 DV snapshot 可查。初次建集与建 catalog
的耗时单列为
启动上界，更新后的 L0 查询才用于缓存新鲜度判断。
另对行集不同的初始快照作历史固定读取，确保 `@options` 选择器
确实生效，而非忽略选择器后碰巧返回最新行。
若 L0 查询超时后才启动维护，写入到维护后的耗时包含人为等待，
只作为本次流程的上界，不代表自动维护的稳态延迟。
两次维护必须各自生成新快照，最终 DV split 不得残留 L0，
且至少有一个实际 deletion file。1027 行只是让这次 minor 维护能对
已有较大文件执行 lookup 并生成 DV，不代表设计目标中的千万行负载档。
Doris 另用短期只读 PostgreSQL 身份访问 JDBC catalog，
实际拒绝 catalog 元数据写权限后才创建 Doris 外表 catalog。
Doris 必须在同一 snapshot 上与基线逐项核对，
包含首次写入、主键更新/删除和追加表 schema 增列。
分别按各表 snapshot 的提交时刻记录 Doris 可见延迟和本合成样本的
查询耗时，并在运行前后测量主机与 Paimon Pod 的时钟偏差；
当前每个阶段仅有一个提交样本，查询也没有按目标负载施压，
不能据此判定设计目标的 p95/p99 或查询性能通过；
新列在追加新行前单独查一次，记录从 schema DDL 完成到首次查询成功
的上界耗时（包含探针调用开销）；
`EXPLAIN VERBOSE` 核对实际 native/JNI 分流。

在继承 #13 的干净提交、私有 FE/BE 镜像和两套 TypeScript CDK 栈均已
核对源码 SHA 后，运行：

```bash
mvn -B -f infra/m1/issue14/pom.xml clean verify
cd infra/m1 && npm run check && npm test
cd ../..
pre-commit run --all-files
cd infra/m1
bash issue14/run-persistent-session.sh
```

会话脚本按源码 SHA 部署身份和容量、启用网络策略，随后运行探针；
退出时先核对本轮资源，再恢复共享 CNI 和删除剩余专用容量。
探针先验证服务端 admission 拒绝改挂其他 Secret，
再创建合成表与私有 DorisCluster；出现错误时仍定点清理本次资源，
清理不完整将明确报错并保留现场供排查。
若 Operator 在 FE endpoint 就绪后仍未生成 BE StatefulSet，
脚本保存 endpoint 后对本次 DorisCluster 注解一次以触发 reconcile，
并核对生成的 BE Pod 没有特权初始化容器。
需要手动回收专用节点时使用
`bash infra/m1/issue14/destroy-capacity.sh`；
它会拒绝删除承载非 DaemonSet Pod 的节点。
若远端 Flink 状态未知，运行脚本会保留 Seed Pod 与合成表。
此时先审阅本次证据，再用
`bash infra/m1/issue14/recover-query-network.sh <run-id>`
仅撤销本次 Doris 查询集群、等待其 Pod/StatefulSet/Service 消失，
撤销本次 NetworkPolicy 并恢复共享 CNI agent、控制器及
自动生成的 Kafka PolicyEndpoint。
`network-policy-session.sh` 同时管理两处开关；如果控制器启用失败，
会核对并恢复已开启的 agent，不把半开状态当作就绪。
若恢复命令的响应中断，可以重复执行 `restore`；
脚本会核对已恢复的 DaemonSet、滚动状态及 Kafka/Flink 就绪状态。
该恢复动作不删除待审阅的 Seed Pod、只读角色或 S3 表；
未确认远端状态前不能把本次运行标为清理完成。
新增的 FE 元数据 PVC 使用按运行 ID 命名的加密 EBS 卷。
`recover-query-network.sh` 在中断恢复中记录其 PVC、PV 和
StorageClass 的实际状态，保留这些资源供 catalog 与查询状态核对；
该命令的成功只表示 Doris 查询入口和共享网络策略已回收，
不能表示 FE 卷、Seed Pod、只读角色或表已清理。
常规探针退出时会在 FE Pod 消失后删除确切 PVC，
并核对对应 PV 和 EBS 卷确已删除，最后删除 StorageClass。
FE Pod 重建后仍能通过原 catalog 读取原生 Paimon 行集的实验
首次现场运行 **RED**：新 Pod 虽复用了原 PVC/PV，却未恢复 Ready。
在新卷上追加 FQDN 配置的复测中，新 Pod 复用原 PVC/PV 后
恢复 Ready，并通过原 catalog 的两张表逐行核对，单次
**PASS**；单有 PVC 或 API Server dry run 不构成恢复通过。
每个阶段的原生行集、Doris 对账、
执行计划与访问控制证据保存在忽略的 `infra/m1/evidence/issue14-doris-*`。
若 `kubectl exec` 传输中断且缺少远端进程退出回执，
脚本保留隔离资源并报告 `CLEANUP_INCOMPLETE`，避免与仍可能进行的
Flink 维护并发删除。

下表是 Doris 4.1.4 自带读端（Paimon 1.4.2）的结果；SDK2 读取器的判定见[结项判定](#结项判定)。

| 能力 | 接收条件 | 当前状态 |
| --- | --- | --- |
| 非 DV 主键表与 append 行集 | 首次写入及更新/追加后与原生读逐行相同 | PASS；仅合成数据单次样本 |
| DV 主键表 L0 行集 | 首次写入及更新、删除后与原生读逐行相同 | RED；首次漏行，更新后读到旧行 |
| 维护后的 DV 与历史快照 | 默认、固定最新快照及不同的历史快照与原生读一致 | PASS；只覆盖本次合成样本 |
| append schema 增列 | 新旧列可见性与原生读相同 | PASS；第八轮旧行空值与新行已逐项核对 |
| 快照新鲜度和耗时 | 记录可见延迟、查询样本与执行计划 | PARTIAL；MOR 有 1027 行、30 次串行聚合筛查，目标负载的 p95/p99 及外表/内表对比 NOT_RUN |
| time travel | 固定快照与原生行集相同 | PARTIAL；维护前 DV L0 显式版本读为 RED，维护后历史快照通过；branch/tag 指定读 NOT_RUN |
| 增量读 | 增量行集与原生 changelog 相同 | LIMITED；查询可执行，原生 changelog 对账 NOT_RUN |
| 系统表 | 系统表内容可核对 | LIMITED；`$snapshots/$files/$schemas/$branches/$tags/$audit_log` 只核对数量，`$indexes` 不支持 |
| 匿名/空密码访问 | S3 403，Doris 空密码失败，无公开 Service/Ingress | PASS；仅短期私有 PoC，CNI `standard` 的内部启动窗口仍在 |
| FE 重建后的 catalog 与行集 | 新 Pod 复用原卷后通过认证读取原 catalog 中的 Paimon 行集 | PASS；FQDN 配置下的单次隔离复测，先前 IP 模式运行仍为 RED |
| 资源清理 | 合成表与临时 Kubernetes 资源无剩余 | PASS；本轮已定点核对后清理 |
| 主查询路径选型 | 正确性、性能与安全准入共同满足 M0 | NOT_COMPLETE；当前 DV 实时路径 RED，MOR/JNI 候选尚待千万行、目标 QPS 与长稳态验收 |

第一次实测 `infra/m1/evidence/issue14-doris-20260929104313-2533788/`
在建表前被旧时钟探针拒绝：一次 Java 启动导致主机请求与回执间隔
约 1.64 秒，推得的偏差区间包含零，不能据此说两端时钟失步。
该运行保持 RED；三个表前缀的对象数为 0，临时 Kubernetes 资源
及两台专用节点均已清理。新的持久 exec 时钟测法在现有私有 Pod
得到约 63–65 毫秒的往返时间，仍须随最终 Doris 实测复核。

第二次实测 `infra/m1/evidence/issue14-doris-20260929110339-2774455/`
在提交 `7df11cf` 上确认主机与 Seed Pod 时钟偏差上界 33 毫秒、
原生首次快照的主键与 append 行集、DV 表真实 L0 文件及匿名 S3 403；
Doris FE 因旧 ConfigMap 覆盖镜像默认 `fe.conf` 而缺少
`JAVA_OPTS_FOR_JDK_17`，启动失败，Doris 行集仍为 NOT_RUN。
自动清理另因管理员无权执行 `DROP OWNED BY` 留下只读角色及临时身份，
保留原始 `CLEANUP_INCOMPLETE`；修复后由同一隔离 Pod 使用字节哈希
核对的新 JAR 撤销三项明确授权并删除角色，三表 S3 对象数为 0，
最终 Kubernetes 临时资源清单为空。两台专用节点仍留作下一次实测，
最终运行结束时继续核对 CDK 清理。

第三次实测 `infra/m1/evidence/issue14-doris-20260929111714-2988790/`
在提交 `7a7c46b` 上确认 29 毫秒的时钟偏差上界、原生首次快照、
只读 JDBC 角色验证、匿名 S3 403。FE 以完整配置实际启动，
Pod `Ready=true`、重启数为 0；BE 尚未创建，因为 Operator 默认生成
`privileged=true` 的 `default-init` 容器，被命名空间 PodSecurity
`baseline` 拒绝。此轮在 Doris catalog 和任何 Doris 行集核对前结束，
保持 RED；不能将 FE 就绪当作查询通过。定点清理后只读角色已撤销，
三表 S3 对象数为 0，临时 Kubernetes 资源清单为空，
专用节点栈已由 CDK 删除。终止信号的进程退出码为 143，
旧脚本却打印了误导性的完成提示；新的信号处理将非零终态明确传给清理。

第四次实测 `infra/m1/evidence/issue14-doris-20260929113227-3143682/`
在提交 `90bb207` 上确认 FE Ready、BE Pod 无初始化容器且 Ready，
两者均零重启。FE endpoint 出现后 Operator 未再次创建 BE；
保存 CR 与 endpoint 后，手动注解本次 DorisCluster 才触发 BE 创建。
私有端口转发下的空密码 root 实际登录成功，因此本轮安全门禁 RED，
没有创建 Doris catalog，也没有执行任何 Doris 行集核对。
Doris 4.1.4 源码的 `skip_localhost_auth_check` 默认值为 `true`，
即使设置 `initial_root_password` 也会跳过来自 `127.0.0.1`
的 root/admin 密码校验；内置 `admin@%` 的初始密码另为空。
本轮清理已撤销只读角色、删除三个表前缀的所有 S3 对象与临时
Kubernetes 资源，并由 CDK 删除专用节点栈。后续运行须验证
localhost 旁路关闭、admin 独立密码设置及空密码拒绝，
才能进入行集接收。

第五次实测 `infra/m1/evidence/issue14-doris-20260929121708-3555985/`
在提交 `2767109` 上确认原生合成表和只读角色创建、匿名 S3 GET 403；
FE Pod 就绪，但 BE 尚未注册时，新增的 Pod IP 正向检查用
`SELECT 1` 收到 Doris `No backend available as scan node`，
所以安全检查在 admin 密码设置之前停止。该错误证明 SQL
在当前启动阶段不可用，不能推断 root/admin 密码验收或
NetworkPolicy 已生效。事后发现 VPC CNI 控制器开关仍为 `false`；
临时开启时已晚于本次 FE 策略删除，仅生成了 Kafka 的
PolicyEndpoint，故本轮不得计入 FE 隔离通过，也未做 Doris 行集核对。
运行以 RED 退出，自动清理核对三个表前缀对象数均为 0，
Doris/Seed/准入资源均无剩余，专用节点栈已删除。
临时控制器、agent 及自动生成的 Kafka PolicyEndpoint
随后均恢复至运行前状态；Kafka 和四个长驻 Flink 作业保持就绪。

第六次实测 `infra/m1/evidence/issue14-doris-20260929124700-4070372/`
在提交 `2b0da89` 上通过了 FE 临时策略编译、Pod IP 监听与 Seed
连接拒绝、root/admin 独立凭据及空密码拒绝。BE 就绪后经私有
port-forward 创建了只读 JDBC catalog。非 DV 主键表及 append 表
首次和更新/追加后的行集均与原生快照逐行一致；更新后两表的单次
可见耗时分别约 15.7 秒和 18.2 秒。DV 初始快照 1 含一个 L0 文件，
原生读有 1027 行，而 Doris 在 120 秒内一直返回空行；全量维护后的
快照 2 则可读。更新/删除快照 3 再次含 L0，原生读已显示 id 2
的新版本、无 id 3、并新增 id 4；Doris 在 120 秒内仍给出旧行。
minor 维护生成快照 4 和真实 deletion file 后，Doris 默认读与
固定快照 4 均与原生行集一致，执行计划报告两个 native split
和 `hasDeletionVector=true`。本轮选择器对照误选已知不可读的
L0 快照 1，因此历史选择器判定仍为 NOT_RUN，不能拿该错误推断
选择器失效。维护后距离快照 3 提交约 167.8 秒，其中包含刻意等待的
120 秒，不能当作自动维护稳态延迟。原生/Doris schema 和其他能力
分类尚未执行；本轮保持 RED。合成 S3 前缀、临时 Kubernetes 对象
与专用节点栈清理完成，共享 CNI 两处开关及 PolicyEndpoint 已恢复。

第七次实测 `infra/m1/evidence/issue14-doris-20260929131152-4192659/`
在提交 `f072559` 上再次通过私有认证、网络隔离和非 DV/append
行集核对；更新后单次可见耗时约 16.4 秒和 18.3 秒。DV 的两个
L0 阶段再次分别漏读和读旧行，保持 RED；minor 维护后的最新与
固定快照 4 均与原生行集一致，历史固定快照 2 则返回不同的正确旧
行集，`HISTORY_SELECTOR dv 2 PASS`，说明该维护后历史选择器生效。
维护后距离快照 3 提交约 170.5 秒，仍含人为 120 秒等待。
尝试给 Doris 查询显式加入
`deletion-vectors.merge-on-read=true` 时收到 SQL 1105
`Unsupported Paimon query option(s)`；该选项虽已在 Paimon 表属性中，
不能借查询选项修复本次 L0 错读。append 增列 DDL 成功后，原生探针
把旧的二字段物理行当作三字段行取值，抛出
`ArrayIndexOutOfBoundsException`；Doris 增列核对、time travel、
增量和系统表分类因此均为 NOT_RUN，不能据此判定 Doris 的增列行为。
本轮同样核对 S3/Kubernetes/专用节点清理与 CNI 恢复。
原生探针已改用实际物理字段数解释旧行，新一轮将继续核对这些能力。

第八次实测 `infra/m1/evidence/issue14-doris-20260929133703-297004/`
在提交 `51401b6` 上重现上述认证、网络隔离与 DV 两次 L0 错读：
初始原生 1027 行、Doris 0 行；更新/删除后原生行集已变，
Doris 在 120 秒内仍返回旧行。非 DV 主键表和 append 表初始、
更新/追加后均逐行匹配；后两者更新后的单次可见耗时约
15.8 秒和 18.0 秒。minor 维护后的 DV 快照 4 有一个真实
deletion file、两条删除记录，Doris 默认和固定最新读匹配；
固定的旧快照 2 也返回与当前不同的正确旧行集。
从快照 3 提交到维护后首次匹配约 169.3 秒，包含刻意等待
120 秒，仍不是稳态延迟分位数。

append 增列后的旧行 `note` 均为 NULL，新行 `note=new`，
两阶段 Doris 行集均与原生读一致；首次新列匹配发生在 DDL
后约 9.2 秒，属于单次上界样本。`FOR VERSION AS OF`
对三张表的初始快照均返回预期行集，但执行时 DV 表已经维护，
不能推断其能读取未维护的 L0。`$snapshots/$files/$schemas`
查询可执行，只核对了数量；`$indexes` 报
`Unknown sys table`，增量查询可执行但返回的 4 行尚未与原生
changelog 对账。前后时钟偏差上界均为 29 毫秒。
本轮以 DV L0 RED 退出，三表 S3 对象数、临时 Kubernetes 对象均为
0，专用节点栈和共享 CNI 策略开关均已恢复。

源码解释与本轮错误方向一致：Doris 4.1.4 的
[Paimon 依赖](https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/pom.xml)
固定为 1.4.2；该版
[CoreOptions.batchScanSkipLevel0](https://github.com/apache/paimon/blob/ac223f47d916f4c8e9c07fd1745db448e4e7d0d8/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java)
对启用 DV 的表直接返回跳过 L0，Paimon 2.0.0 的
[同一判断](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-api/src/main/java/org/apache/paimon/CoreOptions.java)
才考虑 `deletion-vectors.merge-on-read`。2026-09-29 发布的
[Doris 4.1.4.1](https://github.com/apache/doris/blob/4.1.4.1/fe/pom.xml)
仍依赖 Paimon 1.4.2，不能只凭升级这个补丁版消除本次风险。
第九次实测 `infra/m1/evidence/issue14-doris-20260929140737-592649/`
在提交 `32adcc5` 上继续重现 DV L0 的结果错误。初始快照 1
含 L0，原生为 1027 行，Doris 默认读取在 120 秒内为 0 行；
维护前的 `FOR VERSION AS OF 1` 也为 0 行。该次 EXPLAIN
未报告 Paimon 分片路径，探针因此提前抛出断言，
初始 `@options` 为 NOT_RUN，`initial-versioned-l0-status` 保持
RED。提交后的探针修复仅使这类已失败的选择器继续记录后续结果，
没有改变第九轮实测状态。

更新/删除后的快照 3 再含 L0。原生读已有 id 2 的新值、
无 id 3 并新增 id 4；Doris 默认读取在 120 秒内仍保留
id 2 的旧值与已删除的 id 3，且漏掉 id 4。
维护前 `FOR VERSION AS OF 3` 与
`@options('scan.snapshot-id'='3')` 两个选择器均与原生行集
逐项不符；另两次独立的已认证查询也分别复现 FAIL。
它们的计划均为 `native-splits=1/1`、
`has-deletion-vector=false`。因此固定快照不能修复此版本组合的
未维护 DV L0 错读。

非 DV 主键表和 append 表在首次及更新/追加后的行集仍逐行通过；
更新后的单次可见耗时分别约 15.0 秒、17.4 秒。非 DV 更新
计划为 `native-splits=0/1` 的 JNI 读取，append 为 `2/2`
的 native 读取。minor 维护后 DV 快照 4 的默认与固定最新读取、
以及不同的历史快照 2，均与原生行集一致；最新计划为
`native-splits=2/2`、`has-deletion-vector=true`。
距离快照 3 写入首次匹配约 175 秒，包含有意等待的 120 秒，
不是自动维护新鲜度或 p95/p99 结论。

append 增列后旧行的 `note` 为 NULL，新行取到新增值，均与原生
结果一致；DDL 后首次新列匹配约 9.2 秒。维护之后对三表初始
快照的 time travel 均通过，但不能抵消维护前 DV 版本读的 RED。
`$snapshots/$files/$schemas/$branches/$tags/$audit_log` 只验证查询
及行数；`$indexes` 返回 `Unknown sys table`；增量查询返回 4 行，
与原生 changelog 的内容对账仍为 NOT_RUN。未创建非空 branch/tag
并按名称读取其数据，外表/内表响应对比也为 NOT_RUN。
前后时钟偏差上界分别为
34 毫秒、29 毫秒。运行脚本首先因初始版本探针未完成分类报错，
其后的 DV L0 门禁未再次执行；两阶段行集 RED 仍有独立证据，
不能把这一技术退出误记为 capability 失败。三表 S3 对象数均为 0，
临时 Kubernetes 对象为空，专用节点栈已删除，共享 CNI 控制器、
agent 与 PolicyEndpoint 均已恢复。

第十次实测 `infra/m1/evidence/issue14-doris-20260929145200-781604/`
固定源码及私有角色、容量栈 SHA 为
`fc0451144603a899d83e483e41646ecbc1b839ce`。
原生 Paimon 2.0.0 的初始 DV 快照有 1027 行和 L0 文件，
Doris 4.1.4 默认读为 0 行；维护前的
`FOR VERSION AS OF 1` 与 `@options('scan.snapshot-id'='1')`
也均为 FAIL。更新/删除后的 DV 快照 3 原生仍有 1027 行，
但 Doris 默认读在 120 秒内仍与原生行集不符；
两种显式快照选择器均保留 id 2 的旧版本、已删除的 id 3，
漏掉新增的 id 4，结果为 FAIL。
这一次补齐了第九轮未执行的初始 `@options` 对账，
两次 L0 行集仍为 **RED**。

非 DV 主键表与 append 表首次和更新后的行集继续逐行通过。
更新后 MOR 走 JNI `native-splits=0/1`，从提交到首次可见约
14.8 秒；append 走 native `2/2`，约 17.4 秒，均只是单次样本。
针对更新/删除后的 MOR 原生基线另运行了 5 次预热、
30 次串行分组聚合查询，全部核对了分组计数和 ID 求和；
JNI `native-splits=0/1` 的最近秩筛查结果为
p95 1,582.430 毫秒、p99 1,616.889 毫秒。
这张表只有 1027 行，且没有 10 QPS 并发、千万行和七天窗口，
**不构成 M1 查询性能通过**。DV minor 维护后快照 4 有真实
deletion file、没有 L0；默认、固定最新和不同的历史快照 2
均与原生行集一致。从快照 3 写入到维护后首次匹配约
227.9 秒，包含故意等待的 120 秒 L0 观察窗和约一分钟的 MOR
串行筛查，不代表自动维护的新鲜度。append 增列后旧行空值与新行
均核对通过，DDL 后首次新列匹配约 9.8 秒。

time travel 的维护后固定快照通过，`$snapshots`、`$files`、
`$schemas`、`$branches`、`$tags`、`$audit_log` 仍只记录数量，
`$indexes` 仍报 `Unknown sys table`。增量查询返回 4 行，
但原生 changelog 内容对账
仍为 NOT_RUN。前后时钟偏差上界分别为 34、29 毫秒。
FE 启动隔离、root/admin 空密码拒绝、匿名 S3 GET 403 和
私有端口转发均有本轮证据；
清理后 MOR、DV、append 三表 S3 对象数各为 0，临时 Kubernetes
对象与专用节点组均无剩余。共享 CNI agent 恢复为 `false` 且
2/2 Ready，控制器为 `false`、PolicyEndpoint 为 0，
四个常驻 Flink 作业仍为 RUNNING。本轮脚本因 DV L0 RED 以非零退出，
不能把已记录的 MOR 筛查当作整项验收。
共享状态的独立恢复回执分别在
`infra/m1/evidence/issue14-network-policy-20260929151119-1017497/`
和 `infra/m1/evidence/issue14-policy-controller-20260929151140-1018254/`，
包含 agent、控制器、PolicyEndpoint、Flink 与 Kafka 的恢复后状态。

第十一次实测
`infra/m1/evidence/issue14-doris-20260929152553-1133397/`
使用提交 `d02bf0ebf437b127208cd36606c8a0c0cdd8aa5e`，
私有角色和容量栈均核对同一 SourceSha。新脚本把 MOR 计时放在
DV L0 观察与 minor 维护对账**之后**。原生初始 DV 快照 1
仍有 1027 行，Doris 默认读为 0 行；维护前的
`FOR VERSION AS OF` 和 `@options` 均为 FAIL。更新/删除后
原生快照 3 与 Doris 默认读虽都是 1027 行，内容仍不匹配；
两种固定快照读保留 id 2 的旧版本和已删除的 id 3、
漏掉 id 4，结果为 FAIL。因此两段 DV L0 的 **RED** 与第十轮一致，
不受 MOR 计时顺序影响。

非 DV 主键表与 append 表的首次及更新后行集继续逐行通过。
更新后 MOR 为 JNI `native-splits=0/1`，从提交到首次匹配约
15.6 秒；append 为 native `2/2`，约 18.2 秒。
DV minor 维护后的快照 4 有真实 deletion file、没有 L0；
默认、固定最新和不同的历史快照 2 均与原生行集相同。
从快照 3 写入到维护后首次匹配约 176.8 秒，
仍包含故意等待的 120 秒 L0 观察窗，
但已不包含 MOR 筛查。之后才执行 MOR 原生基线分组聚合：
5 次预热和 30 次串行查询全部核对通过，
JNI `native-splits=0/1` 的最近秩 p95 为 1,349.327 毫秒、
p99 为 1,389.087 毫秒。1027 行的串行结果依然只是筛查；
千万行、七天窗口和 10 QPS 目标负载仍为 **NOT_RUN**。
append DDL 后首次新列匹配约 9.5 秒，
前后时钟偏差上界均为 32 毫秒。time travel 的维护后快照通过，
其余系统表与增量读的受限/未对账状态未改变。

脚本以 DV L0 RED 和已验证的定点清理状态退出。
三张合成表的 S3 对象数、临时 Kubernetes 资源和专用节点组
均为 0。本轮 evidence 目录中的 `final-verification.json`
保存了清理后的独立核对；
`infra/m1/evidence/issue14-network-policy-20260929154353-1335067/`
与 `infra/m1/evidence/issue14-policy-controller-20260929154416-1338163/`
分别保存 CNI agent、控制器、PolicyEndpoint 和 Flink/Kafka
恢复后回执。共享 CNI agent 为 `false` 且 2/2 Ready，
控制器为 `false`、PolicyEndpoint 为 0，
四个常驻 Flink 作业为 RUNNING/STABLE。

当前 Doris 4.1.4 内置 Paimon 1.4.2 读取
Paimon 2.0.0 `write-only` DV 主键表的实时路径不满足更新/删除
正确性，不能据此批准为主查询路径。替代选型先评估已在本次合成
数据中逐行正确的 MOR/JNI 路径，并与支持 Paimon 2.0.0
语义的其他只读引擎路径比较；MOR 性能、#16 的分位延迟与长稳态、
历史回刷 #15 的结果收敛以及替代引擎的独立安全准入均尚未验收。
Issue #14 因此保持 OPEN。（本段写于 2026-09-29，针对自带读端；2026-10-12 的[结项判定](#结项判定)采用 SDK2 读取器。）

替代候选的源码与文档预检（2026-09-29）：
[StarRocks 4.1.3 源码](https://github.com/StarRocks/starrocks/blob/4.1.3/fe/pom.xml)
固定的 Paimon 依赖为 **1.3.1**；
[Paimon catalog 文档](https://docs.starrocks.io/docs/data_source/catalog/paimon_catalog/)
列出的 metastore 类型为 `filesystem` 和 `hive`，未列出本环境使用的
JDBC catalog。StarRocks 对此处 JDBC 权威目录和 Paimon 2.0.0
DV/L0 的实际可读性均为 **NOT_RUN**，不能把版本号或文档中的
“支持 Paimon”当成替代引擎通过。即使另用 filesystem 路径发现
同一 S3 表，也须逐快照核对目录一致性、DV 更新删除、查询延迟
和私有只读身份；不能绕开权威目录后直接宣称兼容。

对 Doris 直接替换 Paimon JAR 也不能先验视为修复。
[Doris 主线源码 `0a3595e`](https://github.com/apache/doris/blob/0a3595e61870ed302eb8b732f968e48823bc0cc4/fe/pom.xml#L437-L445)
明确要求 FE connector 与 BE Paimon scanner 使用**同一**
Paimon 依赖版本：FE 序列化 `Table`/`Split`，BE 再反序列化。
该固定源码使用 1.3.1，已测试的 Doris 4.1.4 则使用 1.4.2，
两者均早于本轮写入端的 Paimon 2.0.0。
若要评估自构建 Doris，必须成套固定 FE/BE 依赖、镜像 digest，
并重新跑 DV/L0、压实后读取、历史快照及查询负载；
只替换一侧依赖或看到版本号变大都不构成兼容性验收。

另一条受限读法是在 DV L0 更新未完成维护时，
让查询固定在上一已核对的 compacted snapshot；
维护后的新快照与原生行集一致才切换发布指针。
第十二次实测已在快照 3 尚有 L0 时，
用 `FOR VERSION AS OF` 和 `@options('scan.snapshot-id')`
分别请求旧 compacted 快照 2，并核对返回行集与 EXPLAIN。
两条查询的**旧行集对账 PASS**；但默认读当时也保留关键旧值，
所以 L0 窗口内的选择器是否生效仍为 **UNKNOWN**。
还须构造两个行集不同、都已压实的快照作为选择器对照，
并实测自动维护与发布延迟、指针切换、
故障和查询性能，并防止默认读端越过发布指针；
不能用旧版本可读来抵消默认 DV/L0 路径的 RED。

第十二次私有实测 `20260929194550-2281149`
固定在干净源码 `0d7eb46dc8784b2fb316b06f83d3183263849946`；
独立身份和容量栈的 SourceSha、FE/BE 镜像 digest、
原生 Paimon 读、只读 JDBC 身份、匿名 S3 GET 403、
FE 启动隔离及 root/admin 空密码拒绝均在同轮核对。
初始 DV 快照 1 的原生读有 1027 行、Doris 默认及两种固定读均为
0 行；更新/删除后的快照 3，Doris 默认及两种固定读均保留旧值和
已删除键，漏掉新键。两阶段 DV/L0 仍为 **RED**。
与此不同，快照 3 的 L0 尚未维护时，旧 compacted 快照 2
由两种显式请求形式各自返回 1027 行，均与**旧**原生快照逐行相同；
EXPLAIN 均为 `native-splits=1/1`、`has-deletion-vector=false`。
该时点默认读也保留关键旧值，故这里的 PASS 只对应
**返回行集**，不能证明两种选择器被执行；
维护后不同历史行集的对照也不能外推到维护前的 L0 窗口。
minor 维护后的快照 4 无 L0、有真实 deletion file，
Doris 默认、固定最新和不同的历史快照 2 都与原生读一致。

非 DV 主键表和 append 表更新后的单次首次可见耗时分别约
17.2 秒和 19.0 秒；MOR 走 JNI `native-splits=0/1`，
append 走 native `2/2`。1027 行、5 次预热和 30 次串行
MOR 聚合筛查逐次核对正确，最近秩 p95 为 1,340.115 毫秒、
p99 为 1,409.146 毫秒；千万行和目标 QPS 仍 **NOT_RUN**。
append 增列后的旧行 NULL 与新行均核对通过；
DDL 后首次查到新列约 90.9 秒，包含 DDL 后人工启动查询的间隔，
只能作为本轮恢复流程的上界，不能代表正常 schema 新鲜度。
维护后查询距离快照 3 提交约 175 秒，包含刻意等待的
120 秒 L0 观察窗，不是自动维护的稳态延迟。
三个表的旧快照 time travel 均通过；
`$indexes` 不支持，其他系统表仅核对行数，
增量读的原生 changelog 对账仍 **NOT_RUN**。

主脚本在执行 schema 增列前因本机 `kubectl` 符号链接所指的
Issue #15 worktree 被清理而退出，保留了
`CLEANUP_INCOMPLETE` 原始回执；这不是 Doris 或 Paimon 的错误。
独立恢复先确认远端没有残留探针进程，原生三表的 schema 与快照
逐项等于退出前读数，说明失败的变更没有送达 Pod。
本机恢复独立的 `kubectl`、kubeconfig 和 `node_modules` 后，
同一 Seed Pod 使用原 JAR 继续完成 schema 增列及查询能力分类，
远端变更均有退出码 0 的回执，Doris schema 对账均 **PASS**。
随后按本次运行 ID 撤销 Doris catalog、三张 Paimon 表、
只读角色、Doris/Seed Pod 与身份及准入对象；
三个 S3 表前缀对象数均为 0。专用容量栈由 CDK 删除，
共享 CNI agent/controller 恢复为 `false/false`、
PolicyEndpoint 为 0，四条原有 Flink 作业和 Kafka 就绪。
`recovery-final-verification.json` 的 11 项独立检查均通过；
原脚本的非零退出记录仍保留，本轮业务读路径仍为 **RED**。
249 个本次工作目录证据文件已逐字节归档至
`<证据目录>/issue14-live-20260929194550-published-old/`。

第十三次私有实测 `20260929203404-2460316` 固定在干净源码
`6657af3ca028d107bb8677a6cde0912a972dc637`。本轮工具、
kubeconfig 和 `node_modules` 均为 Issue #14 新工作树的独立文件；
运行入口拒绝三者的符号链接。TypeScript CDK 身份和两组加密、
无公网 IP、带专用污点的 EKS 节点的 SourceSha 均为该提交；
FE/BE 私有镜像 digest、Paimon 种子镜像、只读 JDBC 身份、
匿名 S3 GET 403、FE 隔离启动和管理员空密码拒绝也经同轮核对。

原生快照 1 的 DV 表有 1027 行和未维护 L0，Doris 默认及
`FOR VERSION AS OF`、`@options('scan.snapshot-id')` 两种
固定读均返回 0 行。更新/删除后的原生快照 3 应有
`(2, Lin v2, 2)`、没有键 3、并新增 `(4, Maya, 1)`；
Doris 默认和两种固定读仍返回旧 `(2, Lin, 1)`、
`(3, Tao, 1)`，因此两阶段 DV/L0 继续为 **RED**。
另在快照 3 的 L0 窗口，依据
[Doris 4.1.4 扫描源码](https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/datasource/paimon/source/PaimonScanNode.java#L483)
用独立 SQL 连接执行
`SET force_jni_scanner = true`，再分别运行默认查询和两种
固定快照查询。EXPLAIN 均显示 `native-splits=0/1`，
但三条查询仍与原生 1027 行集不一致；对键 2、3、4 的
单独查询同样返回旧键 2、3 而漏掉新键 4。
强制 JNI 在此版本组合不能修复 DV/L0 读错。

选择器对照则证明了更窄的一项能力：原生 DV 快照 2、
快照 4 的 L0 文件数均为 0，两个 1027 行集合不同；
随后仅对 DV 提交更新，得到有 1 个 L0 文件、行集再次变化的
快照 5。在快照 5 存在时，两种 SQL 语法分别请求快照 2 和 4，
四次完整行集对账均 **PASS**；请求快照 2 的 EXPLAIN 为
`native-splits=1/1`、无 DV，请求快照 4 为
`native-splits=2/2`、有 DV。默认查询此时匹配快照 4。
由于两个请求的已压实行集确实不同，这证明了本轮 L0 窗口内
两种显式快照选择器生效；默认查询未读到快照 5，
不能被当作新提交已可见。

本轮 schema 增列首次查到约 9.3 秒、随后新增列值首次查到
约 9.2 秒；MOR 的 1027 行、30 次串行聚合筛查 p95 为
1,239.452 毫秒、p99 为 1,247.875 毫秒。这些只是小样本观察，
千万行及目标 QPS **NOT_RUN**。三个表的旧快照 time travel
逐行对账 **PASS**；增量读仅执行查询、未与原生 changelog
逐项核对，仍为 **LIMITED**；部分系统表只核对行数，
`$indexes` 在此 Doris 版本返回错误。

主探针因既有 DV/L0 **RED** 门禁以退出码 1 结束，
而非选择器或资源清理失败。作用域内 Doris catalog、
三张 Paimon 表、临时角色和 Kubernetes 资源已清除，
三个 S3 表前缀对象数均为 0，专用容量栈和节点组已删除。
共享 CNI agent/controller 恢复为 `false/false`，
PolicyEndpoint 为 0，四条原有 Flink 作业及 Kafka 健康。
`issue14-selector-final-20260929205545/final-checks.json`
保存 15 项独立终态检查，全部 **PASS**。本轮 275 个私有证据文件
已逐字节归档至
`<证据目录>/issue14-selector-20260929203404/`。

后续还须验证自动维护、已核对快照的发布指针与访问围栏、
新版本发布时延、故障恢复、代表性负载及查询耗时。
快照 2/4 的显式选择器 **PASS** 不能消除 DV/L0 实时读的
**RED**，也不能据此确认 Doris 为 M1 主查询引擎。

下一轮受限视图探针须在新的隔离合成表上重现两个行集不同、
均无 L0 的 DV 快照，以及一个更新但尚有 L0 的新快照。
在 Doris 内部 catalog 创建固定第一个已核对快照的视图，
只给本轮随机身份授予该视图的 `SELECT_PRIV`；
受限身份必须逐行读对该视图，同时直接读取 Paimon 外表
必须收到明确的授权拒绝。随后由管理员将视图定义改为第二个
已核对快照，同一受限身份须逐行读到新行集，且仍无权直读外表。
若 Doris 不支持固定快照视图、视图授权不能形成访问围栏、
或视图切换后返回旧值，本方案应记为 **RED**。
单次视图切换结果见下文。即使探针通过，自动维护、
并发查询时切换的原子性、版本发布延迟、故障恢复以及
千万行和目标 QPS 均须另行实测。

首次受限视图运行 `20260929211905-2623760` 固定于
`54fb84d01a40ef53d69adec18d2ddaa342447b3a`。
两个无 L0 且行集不同的压实快照 2、4 和更新后有 L0 的
快照 5 已由原生读确认；两种显式快照选择器的对照均 **PASS**。
视图探针在 `CREATE USER` 的 PyMySQL 本地参数插值阶段因
未转义 SQL 中的 `%` 失败，创建语句没有发往 Doris；
本次 `view-control-status` 为 **RED（探针实现错误）**，
**Doris 视图及权限围栏能力仍为 NOT_RUN**。
原有 DV/L0 两阶段行集仍 **RED**：初始 1027 行被 Doris
读成 0 行，更新/删除后返回旧键和值。运行以退出码 1 结束，
作用域内 Doris、Paimon、Kubernetes 与三个 S3 前缀均已清理；
专用容量栈删除，共享 CNI 恢复为 `false/false`、
PolicyEndpoint 为 0，四条常驻 Flink 作业为 RUNNING。
250 个私有证据文件逐字节归档于
`<证据目录>/issue14-view-first-20260929211905/`。
新探针用实际 PyMySQL `mogrify` 检查密码参数与主机通配符的插值，
并在下轮固定源码重跑 Doris 视图能力。

第二次受限视图运行 `20260929214516-2713207` 固定于
`d0a2222714f4a20693a6c02d4a748712e2c2e044`，
私有身份与两台加密、带专用污点的节点的 TypeScript CDK
SourceSha 均匹配该提交。原生 DV 快照 2、4 各有 1027 行、
L0 文件数均为 0，且两个行集不同；较新的快照 5 有 1 个
L0 文件，行集再次变化。快照 5 存在时，
`FOR VERSION AS OF` 与 `@options('scan.snapshot-id')`
分别读取快照 2、4，四次逐行对账 **PASS**。

本次随机创建的 Doris 用户只被授予内部 catalog 视图的
`SELECT_PRIV`。视图先固定快照 2，受限用户返回与原生
快照 2 完全相同的 1027 行；管理员以 `ALTER VIEW`
固定快照 4 后，同一用户返回与原生快照 4 完全相同的 1027 行。
两个阶段该用户直接查询原 Paimon 外表都得到 Doris
`Permission denied` 错误，而不是超时或空结果。
视图的 `EXPLAIN VERBOSE` 分别报告 native splits `1/1`
（无 DV）和 `2/2`（有 DV）。因此**单次受限视图发布切换
与本轮表级直读拒绝 PASS**。尚未核对并发查询在切换时
只看到完整旧版或新版，也未测试系统表、导出等旁路，
不能据此批准终端用户的敏感数据访问。

同轮初始 DV/L0 的原生 1027 行在 Doris 仍返回 0 行；
更新/删除后 Doris 仍返回旧键和值，两阶段继续 **RED**。
主脚本因此以退出码 1 结束，不把视图 PASS 当作
DV 实时路径通过。append 增列约 9.0 秒首次可见；
MOR 的 1027 行、30 次串行查询 p95 为 1,190.836 毫秒、
p99 为 1,288.860 毫秒，只是筛查观察值；千万行、
10 QPS 和自动发布的 3/6 分钟新鲜度均 **NOT_RUN**。
作用域内 Doris、Paimon 表、身份与 Kubernetes 资源均清理，
三个 S3 前缀对象数为 0，专用节点组和容量栈已删除，
共享 CNI 为 `false/false`、PolicyEndpoint 为 0，
四条常驻 Flink 作业为 RUNNING。
`issue14-view-second-final/final-checks.json` 的 16 项独立核查
均 **PASS**；253 个私有证据文件逐字节归档于
`<证据目录>/issue14-view-second-20260929214516/`。
下一关是让独立维护及时产生已核对快照，再以可审计的发布指针
切换并测量并发正确性、积压、故障和目标负载。

### 独立连续维护与受限视图发布探针

本轮新增 #14 专用 Flink `CompactAction`：仅针对当前隔离运行的
`issue14_dv_*` 表，使用独立 IRSA，按 15 秒间隔发现数据并强制
压实 L0（首轮源码为 5 秒间隔）；维护身份的 S3 数据写权限限于
Issue #14 的 DV 表前缀。checkpoint 和 savepoint 的作业路径按运行 ID
配置并定点清理；IAM 角色授权覆盖 `issue14/*`，不构成逐运行
权限隔离。运行脚本先产生并核对较新的
L0 快照，再启动维护作业，等待**行集不变、快照递增、L0 文件归零**
的原生证据。随后将仅有视图 `SELECT_PRIV` 的 Doris 读者从旧
压实快照切到新压实快照，记录从待发布快照提交到受限查询完成
的时间，并核对原表直读被授权规则拒绝。探针失败时先停止维护
作业并删除其运行前缀，再删除源表；若无法确认维护已停止，
保留表和身份供定点核查。

2026-09-29 的首轮隔离运行 `20260929222832-2867533` 固定于
`f3c57e0c268ffbbe51342d3539ba291ac18c81a6`。
独立 Flink 维护作业 `RUNNING/STABLE` 后，原生 DV 快照
5→6 的 1027 行逐项不变，L0 文件数 1→0；
受限 Doris 视图先固定快照 4，再切到自动压实快照 6，
两个阶段各有 1027 行与原生相符，直接查询原 Paimon 表
两次均被明确拒绝。这次**单次自动压实与视图发布 PASS**。
待发布快照 5 提交到新视图查询完成共 501,629 毫秒，
其中包含 Flink 冷启动和探针开销；快照 6 提交到查询完成
为 28,845 毫秒。这些是单次观察值，不能用于稳态分位数
判定。初始和更新/删除后 DV/L0 直读继续 **RED**，
因此主探针以退出码 1 结束。作用域内表、身份、Kubernetes
对象和五个 S3 前缀清零，专用节点组已删，共享 CNI 恢复
`false/false`，22 项独立终态检查 PASS；305 个证据文件
逐字节归档于
`<证据目录>/issue14-maint-first-20260929222832/`。

第二轮 `20260929230624-3200183` 固定于
`4d78ad992086b60cd9487c78c74c5261c1f87bb2`，
把 JobManager/TaskManager 的 CPU 上限设为 500m。
首次待发布 DV 快照 5→自动压实快照 6 的 1027 行保持一致、
L0 1→0；受限视图快照 4→6 两次读数均与原生一致，
原表直读均被拒绝，单次写入提交到新视图查询完成为
**227,235 毫秒**，其中包括 Flink 冷启动与探针时间。
JobManager 约 23 秒通过 Pod 就绪检查，但 Pod 就绪
本身不等于作业已恢复或结果可查。

同一维护作业保持 `RUNNING/STABLE` 后，再写入 DV 更新；
原生快照 7 确认 1 个 L0 文件与变化后的 1027 行。
作业自动生成快照 8，行集不变、L0 为 0。
另建一组受限 Doris 用户与视图，按快照 6→8 再切换，
两次返回的 1027 行逐项匹配原生快照，
两次原表直读得到明确的 `Permission denied`。
这次**暖态连续维护与单次视图发布 PASS**：
快照 7 提交到新视图查询完成 **73,260 毫秒**，
快照 8 提交到查询完成 **26,297 毫秒**。
Doris `EXPLAIN` 显示新视图快照 8 的 native splits `2/2`，
包含 deletion vector。append 增列首次可见约 9.3 秒；
MOR 的 1027 行、30 次串行聚合查询 p95/p99 为
1,316.638 / 1,334.977 毫秒，仅为低负载筛查。

同轮初始与更新/删除后的 DV/L0 默认直读仍为 **RED**，
因此主探针退出码为 1，不把发布视图的 PASS 覆盖该失败。
三张表、专用 Flink/Doris 资源、两个维护 state 前缀及节点组
已清理，共享 CNI 恢复 `false/false`，22 项独立终态核查
均 PASS。322 个私有证据文件逐字节归档于
`<证据目录>/issue14-maint-warm-20260929230624/`。
同一用户与视图跨写入持续发布、3/6 分钟分位延迟、
并发视图切换、千万行 10 QPS、积压和故障目标仍为
**NOT_RUN**；不能由两次单点测量关闭 #14 或把 M1
标记完成。

### FE 重建与元数据持久性

2026-09-30 的隔离运行 `20260930020329-458616` 固定于
`d0ec8a51f68c7d0c0d5c4cb3ff62ae775ef638c0`。
FE 元数据挂载在本轮独立的 10 GiB 加密 gp3 EBS 卷；
首次 FE、BE 均 Ready，临时网络隔离、root/admin 认证、
只读 JDBC catalog 建立以及非 DV 主键表、append 表初次行集核对
均通过。删除 FE Pod 后，StatefulSet 创建了不同 UID 的新 Pod，
PVC UID、PV 和挂载卷保持不变，但 FE 在 12 分钟内未恢复 Ready，
日志显示新 Pod IP 下先进入 FOLLOWER、随后处于 UNKNOWN，
原 catalog 的重启后读取因此 **NOT_RUN**。本轮
**FE 恢复 RED**，后续 DV、视图和性能阶段也未运行；
此前各轮已记录的 DV/L0 错读仍为 RED。

固定 Doris Operator 25.8.0 源码明确要求自定义 FE 配置设置
`enable_fqdn_mode=true`；其启动模式选择逻辑在 ConfigMap 缺少
该项时采用 IP。失败轮配置确实缺少此项，而 FE Pod IP 在重建时
变化，提示 FE 启动身份与 Pod IP 有关。复测仅对新建 FE
追加 FQDN 配置，没有改写失败轮的 ConfigMap 或元数据卷。
失败轮三个合成 S3 表前缀对象数均为 0，
Doris/Seed/准入资源、FE PVC/PV 及对应 EBS 卷、
专用容量栈和节点已定点清理。由于 FE 未恢复，无法执行
catalog SQL 删除；已独立核对承载元数据的 EBS 卷不存在。
共享 CNI 两处开关恢复 `false/false`、PolicyEndpoint 为 0，
常驻 Flink 作业保持就绪。123 个私有证据文件及终态记录保存在
`<证据目录>/issue14-live-20260930020329-fe-recovery-red/`。

复测 `20260930023511-645732` 固定于
`2cc944c4a0813c35112a2fd8391433de48d38b8b`，
使用全新的 10 GiB 加密 gp3 元数据卷与 FQDN 配置。
首次 FE、BE 就绪且原生/Doris 的非 DV 主键表、append 表行集一致。
删除 FE Pod 后，新 Pod 的 UID 与 IP 均改变，却复用原 PVC UID
及 PV，零重启恢复 Ready；第一次经认证的原 catalog 查询
即对两张表逐行匹配。因此 **FE 重建和 catalog 恢复单次 PASS**，
也支持前轮的 IP 模式诊断方向，但不能据此推断所有 FE 故障模式
已验收。

同轮初始与更新/删除后的 DV/L0 直读再次 **RED**；
压实后的 DV 行集、两个不同历史压实快照的选择器、
append schema 增列及受限视图对照继续通过。独立 Flink
维护作业使待发布快照 5→6、7→8 均保持 1027 行且 L0 从 1
变为 0；受限视图分别读到正确的新快照，原表直读被拒绝。
待发布提交至视图可读分别为 **201,346 毫秒**（含冷启动）
和 **74,526 毫秒**（暖态）；30 次串行 MOR 聚合筛查的
p95/p99 为 **1,537.757 / 1,553.868 毫秒**，
append 新列首次可见约 **9.3 秒**。它们均为小样本观察值，
不能证明目标 QPS、持续分位延迟或 24 小时容量达标。

主探针因 DV/L0 错读以退出码 1 结束。Doris catalog、
受限视图的数据库与用户、临时 Flink/Doris 工作负载和身份、
三张合成表及两个维护 state 前缀均定点清理；FE PVC/PV
与实际 EBS 卷不存在，专用节点组及容量栈已删除。
共享 CNI 两处开关独立复核为 `false/false`，
PolicyEndpoint 为 0，四条常驻 Flink 作业
保持 READY/RUNNING。253 个源证据文件逐字节归档于
`<证据目录>/issue14-live-20260930023511-fe-recovered-dv-red/`。
FE 恢复 PASS 不覆盖 DV/L0 的 RED，也不能关闭 #14 或 M1。

### 同一受限视图持续发布方案

上述受限视图对照在每次调用后都会删除用户与视图，
尚不能证明同一查询身份跨写入和 FE 重建持续可用。
后续隔离探针据此增加持续发布路径：先在未维护的 L0
存在时创建受限视图，固定到上一已核对的压实快照；
同一用户在两轮更新期间都须读到旧发布行集，且原表直读
明确被拒绝。每轮独立维护作业产出行集不变、L0 归零的
新快照后才执行 `ALTER VIEW`，再以同一用户核对新行集。
切换前先由 Doris 管理员直读候选固定快照，与原生行集逐行比较；
若候选错误，保持旧视图定义，不对读者发布。第二轮旧视图读取
前后分别重新检查原生 DV 当前快照，两次都须仍是同一个 L0
待发布快照，否则该轮旧值证明失败。独立维护作业的发现间隔
已设为 60 秒，为这段读取留出验证窗口；具体窗口仍以现场
快照证据判定。
最后在保留该用户和视图的情况下再次重建 FE，
确认新 Pod 复用原 PVC/PV、在 FE 元数据目录实际挂载该卷，
且视图及授权仍有效。FE 重建结果不明或 SQL 清理失败时
保留现场资源供定点核查。
探针凭据仅写入私有回执文件，权限为 `0600`，
仅在用户与数据库经 SQL 清理，或 FE 元数据卷销毁且
资源清场核对通过后删除该凭据。
这一路径的实际执行与边界见下方两次隔离运行；
单点延迟和串行查询仍不足以判定稳态 p95/p99、
并发切换及 #16 的目标负载。

若 JDBC catalog 无法连通、2.0.0 表或 DV 读取错误、或新鲜度与查询性能
无法满足 [M0 验收契约](../../design/acceptance-contract.md)，
Doris 不能被确认是主查询引擎；须触发替代选型并保留 RED 证据。
本实验结果不能代替 #16 的 24 小时负载和故障验收。

### 同一视图探针的 FE 挂载校验中断

干净源码 `8e86d25bfbfd5199a3e5d39844e115b07630076e` 的隔离运行
`20260930042948-2815060`，在首批 MOR 1027 行与 append 2 行
和原生快照对账后重建 FE Pod。Pod 已替换、同一 PVC/PV 与加密 EBS
卷继续绑定；但探针内嵌 `awk` 在 `&&` 前断行，解析时报语法错误，
因此挂载校验没有产生有效判定。原探针按 `fe_restart_unknown` 保留
catalog、工作负载、卷、共享网络策略与专用节点，并返回
`CLEANUP_INCOMPLETE`。**该原运行不是 FE 恢复通过或视图通过。**

在未重放写入的情况下，独立核查保存于
`infra/m1/evidence/issue14-reconcile-20260930042948-2815060-20260930043800/`：
新 FE Pod UID 不同，PVC UID 与 PV 卷号一致，EBS 加密，
容器内 `/opt/apache-doris/fe/doris-meta` 为实际读写挂载。
经重建后的 FE 认证查询，MOR 1027 行与 append 2 行仍与该轮
原生快照相同。这是**中断后的定点核查**，不能倒填原探针完成状态。
随后确认隔离 catalog 已删除、三张表和 JDBC 读角色均取得远端
成功退出回执；三个 S3 前缀为空，DorisCluster、Pod、PVC、PV、
EBS 卷、StorageClass、身份和准入对象全部清除。
共享 CNI 独立复核为 `false/false`、PolicyEndpoint 0，
专用容量栈和节点组不存在，两台基线节点 Ready，
四条常驻 Flink 作业 RUNNING。

挂载校验命令已修正为可执行的单行条件，待新源码独立复跑。
同一受限视图跨两轮写入、compaction、切换和 FE 重建的验证仍
**NOT_RUN**；既有 DV/L0 直读 **RED**，#14 与 M1 **NOT_COMPLETE**。
按 HLD §17 触发的
[StarRocks 4.1.3 / Paimon 2.0.0 候选评估](starrocks-paimon-fallback.md)
只有发布文档与源码证据，JDBC 同目录读取和 DV 正确性均未现场运行，
不能作为 Doris 的回退通过。

### 同一受限视图两轮发布与 FE 恢复实跑

隔离运行 `20260930050045-3718904` 固定于干净源码
`877a0c3280240866b7a0bebffd870c21fba61bc6`。
首次 FE/BE 就绪后，原生 Paimon 与 Doris 的非 DV 主键表
1027 行和 append 表 2 行逐项匹配；首次删除 FE Pod，
新 Pod 复用原 PVC/PV 和加密 EBS 卷、实际挂载元数据目录，
经认证的两表恢复查询在第 1 次尝试匹配。
此前被 `awk` 语法阻断的挂载与恢复路径在**本轮**得到有效回执，
不回填到前轮中断运行。

直接 DV/L0 仍独立 **RED**：初始 Doris 返回 0 行，
原生为 1027 行；更新与删除后双方虽均为 1027 行，
Doris 返回旧值。旧发布快照、schema 增列与最终 schema
核对为 PASS；历史压实快照选择器和临时受限视图对照为 PASS。
这说明错误不能用计数相等或另一条表路径的正确性掩盖。

持续发布探针在同一受限用户和视图上完成两轮：
待发布快照 5、7 各保留 1027 行且 L0=1，
独立维护作业分别产出快照 6、8，行集不变且 L0=0；
每次先将 Doris 管理员读取的固定候选快照与原生结果逐行对账，
再切换视图并由同一用户核对 1027 行。
第二轮待发布 L0 在切换前旧视图读取的前后保持不变，
原表直读均被权限拒绝。两次待发布提交到视图正确可读分别为
**243,782 毫秒**（含冷启动）和 **119,438 毫秒**
（单次暖态观察）。保留用户与视图再次删除 FE Pod 后，
同一卷与元数据挂载得到核对，第 2 次经认证的受限视图查询恢复
并保持原表拒绝。持续发布与这次 FE 恢复是本轮的
**有限 PASS**；两次时延和 30 次串行 MOR 筛查
（p95/p99 **1,301.195 / 1,418.547 毫秒**）
都不足以证明目标负载、24 小时稳态或分位新鲜度达标。
本轮发布与校验是串行探针，没有在 `ALTER VIEW` 前后持续施加并发
查询；即使候选先验对账、失败后尝试回退，也不能证明其他读者
不会在切换窗口看到错误行。并发切换正确性继续 **NOT_RUN**，
须随 #16 的查询档独立验收。

主探针按 DV/L0 硬门禁退出码 1，外层 wrapper 也退出码 1，
未把部分通过改写为整轮通过。独立终态核对确认
Issue #14 Kubernetes 资源零、共享 CNI `false/false`、
PolicyEndpoint 零、两台基线节点 Ready、四条常驻 Flink 作业
RUNNING、三张合成表 S3 前缀为空、FE EBS 卷及专用容量栈不存在。
315 份源回执与独立核查逐字节复制到私有归档
`<证据目录>/issue14-live-20260930050045-persistent-view-dv-red/`，
其中 `SHA256SUMS` 固定每份内容。后续发布失败回退、
FE 未知结果的维护状态保留、以及发布后查询计划记录的代码加固
须在新的源码 SHA 上重跑；本轮 877a0c3 的通过项不能转写为
加固后源码的 AWS PASS。直接 DV/L0、主查询引擎选型和 M1
继续 **RED / NOT_COMPLETE**。

### 发布失败围栏加固后的同源实跑

隔离运行 `20260930064937-7323` 固定于干净源码
`41029793b24d8b95a1df1f9ef86ad92eb7d24267`。
该源码在前一轮基础上增加：`ALTER VIEW` 结果失败或不明时
核对并回退旧定义、FE 重建状态不明时保留维护状态供核查、
以及每次发布后保存 `EXPLAIN` 和实际读路径。
这些失败分支的注入验收尚未执行，不能仅凭正常路径通过
推断回退与未知状态恢复已现场通过。

本轮初始非 DV 主键表 1027 行、append 表 2 行以及
后续更新、追加和 schema 增列均与原生 Paimon 快照逐行匹配。
FE 第一次重建复用原 PVC/PV 和加密卷，原 catalog 的
认证读取第 1 次尝试恢复。直接 DV/L0 读取重复出现
**RED**：首次 Doris 返回 0 行，原生为 1027 行；
更新、删除后 Doris 与原生计数同为 1027，
但 Doris 仍返回旧值。历史压实快照选择器、
受限视图访问控制和旧发布快照的行集核对为 PASS；
不能用这些正确路径抵消直接读取的错行。

同一受限用户和视图跨两轮发布的有限探针为 PASS。
待发布快照 5、7 各为 1027 行、L0=1；
独立维护作业分别产出快照 6、8，行集保持 1027 行且 L0=0。
每次先由管理员将固定候选快照与原生行集核对，
再切换视图，并由原用户核对新行集及原表直读拒绝。
第二轮待发布 L0 期间，旧视图仍读取快照 6；
发布后两轮 `EXPLAIN` 均保存了 Paimon native split
及 deletion vector 路径。本次从待发布提交到发布后
读者行集、直读拒绝及 `EXPLAIN` 校验完成的保守上界
分别为 **239,082 毫秒**（含首次维护启动）
和 **100,951 毫秒**（一次暖态观察）；
探针没有记录首次正确读取时刻，不能将这两个数值
作为 M0 分位新鲜度样本。
视图和用户保留时再次重建 FE，同一卷及元数据挂载得到核对；
受限视图在第 2 次认证尝试恢复，原表仍被拒绝。
本轮后段主机与 Paimon Pod 的时钟偏差上界为 29 毫秒。

直接 DV/L0 硬门禁使主探针及外层会话均以退出码 1 结束。
外层完成定点清理后，独立核查确认 Issue #14 Kubernetes 资源零、
共享 CNI 为 `false/false`、PolicyEndpoint 零、两台基线节点 Ready、
四条常驻 Flink 作业 RUNNING、Kafka broker Ready、
三个合成 S3 前缀为空、FE EBS 卷及专用容量栈不存在。
本轮八个关联证据目录共 369 份文件逐字节归档于
`<证据目录>/issue14-live-20260930064937-exact-sha/`，
并保存 `SHA256SUMS` 与独立清理回执。

这两轮发布是串行、小规模合成探针。`ALTER VIEW` 前后并发读者、
千万行与 10 QPS 看板、24 小时稳态、目标分位新鲜度、
branch/tag 与完整增量/系统表对账仍为 **NOT_RUN**。
Doris 4.1.4 / Paimon 2.0.0 的直接 DV/L0 路径保持 **RED**，
受限视图只能列为待容量与并发验收的候选；
Issue #14 主查询引擎选型和 M1 继续 **NOT_COMPLETE**。

### 四会话并发切换探针

后续源码在受限视图每次发布前启动四个持续查询会话，
要求每个会话在 `ALTER VIEW` 前读到完整旧行集、
在切换后新发起的查询读到完整新行集，
且每个会话都至少有一次客户端查询执行区间
与切换命令重叠。
切换期间的单次行集只能与旧快照或新快照逐行相同；
错行、暂时不可查、切换后继续读旧值、缺少实际重叠或
任一会话缺少前后读数都会拒绝；尚未尝试切换时保持旧视图，
已尝试切换时核对并尝试恢复旧视图。
记录每次查询的单调时钟执行区间、行数、完整行集
SHA-256 与分类，
便于分辨读数失败与没有覆盖切换窗口。
摘要输入为按 `id` 排序的整份结果，以 UTF-8 紧凑
JSON 数组编码，不转义非 ASCII 字符，也不接受非标准
浮点值；同轮旧、新原生基线的摘要一并记录。
本探针只约束这四个会话的完整行集，不证明任意用户并发数、
查询 p95/p99 或发布过程在服务器内部原子完成。
Python 脚本仅作为私有测试探针运行，不作为平台作业、
服务或用户 CLI 交付；产品实现语言边界仍按 HLD §6.6。
先前 `4102979` 实跑的有限 PASS 不计作并发切换通过；
下面单独记录新增探针在精确源码上的 AWS 结果。

### 精确源码并发切换复验

固定干净源码 `3d3b7ab0f2d82ee73dfac086423744493a2c3eba`，
外层会话 `20260930082447-3885942` 部署了本轮角色和专用容量，
隔离探针 `20260930083109-209423` 在真实私有 S3 上运行。
FE 重建后复用原 PVC UID、PV 和加密 EBS 卷，
经认证的原 catalog 主键与 append 行集在第 1 次尝试恢复。
初始 DV/L0 直读仍返回 0 行，而 Paimon 原生快照有 1027 行；
更新删除后直读返回旧值。这两项均为 **RED**，
后续视图结果不能消除它们。

持续的独立 Flink 维护作业把行集不变、L0 文件数从 1
降至 0 的待发布快照分别由 5→6、7→8。
受限视图的第一次发布报告四个读者共 28 次旧行集、
14 次新行集和 7 次查询与 `ALTER VIEW` 重叠；
第二次报告 28 次旧行集、12 次新行集和 8 次重叠。
每个读者在每轮都有切换前后的完整行集，
并至少有一次客户端查询执行区间与切换命令重叠；
没有错误、暂时不可查、混合行集或切换后旧值。
两轮完整行集 SHA-256、每次查询的单调时钟区间、
旧新分类及 `CONCURRENT_VIEW_PASS` 均保存在原始回执。
本轮四会话的受限视图并发切换为**有限 PASS**，
不证明服务端内部原子性或更多用户的负载表现。

从待发布 Paimon 提交到最终发布核验的保守上界为
244,067 毫秒和 115,571 毫秒；它们包含压实、
候选行集检查和读者验证耗时，
**不是首次正确可见时刻或 p95/p99**。
第二次发布后 FE 再次重建，原视图和受限读者
在第 2 次认证尝试恢复通过。

子探针与外层会话因既有 DV/L0 直读硬门禁均退出 1；
外层仍恢复共享 CNI、删除专用容量。
独立现场复核确认 #14 Kubernetes 资源为零、
CNI 两处开关为 `false/false`、PolicyEndpoint 为零，
两台原有节点 Ready、四条常驻 Flink 作业 RUNNING、
Kafka broker Ready，三张合成表 S3 前缀为空，
FE EBS 卷与专用容量栈不存在。
探针、外层及独立复核等 12 个证据目录、417 个文件
已逐字节核对并私有归档于
`<证据目录>/issue14-live-20260930083109-exact-sha/`。

千万行、目标 QPS、连续 24 小时、目标分位延迟、
branch/tag 及完整增量读和系统表核对仍 **NOT_RUN**。
Doris 4.1.4 / Paimon 2.0.0 直接 DV/L0 路径继续 **RED**；
受限视图仍只是待容量、长稳态和安全准入验收的候选。
Issue #14 与 M1 继续 **NOT_COMPLETE**。

## 七天查询数据集组件：三模式原生有限验收

2026-10-01，运行 `20261001022147-3402554` 在 AWS `work / us-west-2`
固定源码 `0231d53034a2ea4b2f6ae6df105d2278b29ee734`，
使用该源码的已核对 JAR 与固定 `3fb4d31` Paimon/Flink 运行时。
MOR、DV 配置、append 各生成 28 行，原生快照均为 1；
独立 `inspect` 全量核对五字段、唯一 ID 和完整行集，
四区域各 7 行、金额总和 `84, 91, 98, 105`，全部匹配。

原驱动退出 0，三张表的真实 S3 前缀已空，六个临时 Kubernetes 对象按 UID
清理，专用节点组和 EBS 已删除。独立复核原 Flink、Kafka broker、
CNI 身份与状态，并逐字节归档 383 文件、2,462,232 字节；
详情见 查询数据集验收记录（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/query-load-dataset.md`）。

本轮是静态数据集的生成、原生读回和定向清理验收。
千万行读回、10 QPS 看板 / 1 QPS 交互压力仍 **NOT_RUN**，
新建 DV 配置表的物理 DV 文件为 **NOT_VERIFIED**。
Issue #14、#16 与 M1 保持 **NOT_COMPLETE**。

> 注：本文中的 issue 与 PR 编号（如 #14）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
