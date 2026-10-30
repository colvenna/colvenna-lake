# Issue #14：配套 Paimon 2.0 reader 实验产物

## 状态与范围

2026-10-12 起，本读取器经需求方确认为 #14 的主查询读路径，并在 #16 的 24 小时基线中持续使用；结论见 [Doris 结项判定](doris-paimon.md#结项判定)。以下为实验阶段的记录。

2026-09-30 已实现本地产物准备与独立的候选镜像构建入口。
准备产物的分类为 `PAIRED_SDK2_ARTIFACT_PREPARED_LIVE_NOT_RUN`。
第五轮源码 `e455e0f` 的 AWS 有界读取门禁已通过并独立清理、归档；
具体结果见后文，完整 Issue #14 和 M1 仍未完成。
源码 `65c5fc96b5c8848cccee61cde4be53cb529c486a` 已实际构建镜像并完成扫描，
FE/BE 启动且现场产物回执一致；创建 catalog 时因 AWS 凭证加载失败中止。
该轮所有 DV/L0 查询为 **NOT_RUN**，FE 完整 ABI 兼容性仍未验证。
Issue #14 的旧 reader 现场 RED 和 M1 `NOT_COMPLETE` 保留。#14 已按接收条件结项，见
[结项判定](doris-paimon.md#结项判定)；下文保留当时各轮的状态。
这是官方 Doris `4.1.4` 基础镜像上的自构建读取器组合；
不能称为官方已发布的 Paimon 2.x 升级。

此前[正式版本核验](doris-paimon-reader-upgrade.md)已发现
FE 与 BE 的 Paimon `1.4.2` 依赖。
本次又只读检查当前独立 #15 Doris Pod 中的 JAR：
FE 与 BE 的 `CoreOptions.batchScanSkipLevel0()` 字节码仍在 DV 启用时直接跳过 L0。
SDK `2.0.0` 则读取 `deletion-vectors.merge-on-read`。
这些是实际构件语义，不是新一轮查询正确性结论。

## 固定输入与替换方式

- Doris JNI 的 15 个 Java 源文件固定到
  `ad35a140c7fd0b842f18c23300bac581f7d04326`，逐文件校验 SHA-256。
- Paimon bundle、Arrow、S3 均固定 `2.0.0`。
  下载 URL、校验和、原始 BE connector、preload、UDF 的校验和保存于
  输入锁文件（早期私有仓库 tag `history/m1-m5` 下的 `infra/m1/issue14/paimon2-reader-lock.json`）。
- 使用 `javac --release 11 -proc:none`，新 bundle/Arrow/S3 位于编译类路径最前；
  原始 preload/UDF 提供 Doris JNI 公共类型。
- BE connector 中的旧 Paimon 类、元数据、代码生成资源和 S3 插件被移除，
  再加入固定 SDK 2 字节及重新编译的 Doris connector。
  服务发现文件移除旧 Paimon provider 后合并新 provider。
  同时保留 `paimon-codegen/` 和 `paimon-plugin-s3/` 目录入口：
  Paimon 的 `PluginLoader` 使用目录 URL，只有类文件不足以加载插件。
  其他依赖保留原始 connector 的字节；这部分运行时兼容性仍须现场验收。
- BE 共享 UDF JAR 也含旧的 `paimon-codegen/`，会参与父加载器资源查找。
  从固定 UDF 输入移除 Paimon SDK 命名空间，输出到独立的 `reader/be-udf/`，
  并复制到实际 `java_extensions/java-udf/` 目录。
  非 SDK 入口清单及每个入口的内容必须与原始 UDF 逐字节一致；
  独立产物核验拒绝残留 SDK 资源及 Paimon service provider。
  原始 UDF 哈希和转换后哈希均写入准备回执。
- FE 的十个旧 SDK/相关 shade JAR 原路径被替换为空的标记 JAR，
  三个固定的新 SDK JAR 加入同一目录。
  文件名含 `1.4.2` 的标记包没有类，回执和 Maven 元数据才是版本依据。
- 本候选只覆盖 AWS S3/JDBC；旧 Jindo reader 被移除。
  Jindo 及其他未包含存储后端不属于本次候选的已验证能力。
- 两个 Dockerfile 仅用 `COPY`，基础 FE/BE 镜像 digest 与现有 `4.1.4` 探针一致。
  准备过程无需在 ARM 主机执行 amd64 容器。

## 本地验收

已对固定输入运行实际准备 CLI，再运行 `--verify-output`：
15 个源文件编译通过；产物包含 14 个 JAR；
66,437 个 SDK 类入口逐字节与原 SDK 构件一致；
Paimon Maven 元数据均为 `2.0.0`；
旧 Jindo 类不存在，旧 FE 标记 JAR 均不含类。
模块描述符不参与合并后的 classpath JAR。
原始失败尝试因核验器将已排除的插件模块描述符计入预期而拒绝产物；
修正后第二轮准备通过；独立复核又拒绝了包含多余目录前缀的回执路径。
修正路径后第三轮重新准备并独立复核通过，保留两项原始失败回执。

第三轮实际部署的探针为 `20260930161500-2467765`。
镜像中的准备回执 SHA-256 为
`416c855134d2fc0aa37fa14160e3f26b8bc3f1d30ea4986d0182d7b642270da4`，
现场 FE/BE 读取的回执哈希相同。
认证拒绝检查通过后，catalog 初始化报 `SdkClientException`：
无法从系统设置加载 AWS 凭证。查询尚未开始，外层于
2026-09-30 16:27:50 UTC 退出 1。
独立清理核查确认本轮 Kubernetes 资源、三个 S3 表前缀、FE EBS 卷和专用容量不存在，
CNI false/false、PolicyEndpoint 为零，原有节点、四条 Flink 作业和 Kafka 身份不变。
335 份终态与清理证据逐字节归档，SHA-256 再次核查通过。
原始 RESULT 中的独立清理 `NOT_RUN` 保留，由单独验证器提供清理 PASS。

只读源码诊断发现，Doris 将 S3 设置放入 `CatalogContext` 的 Hadoop 配置，
而 SDK 2 的 S3 插件通过 options 创建新 context。
候选修正使用 `paimon.fs.s3a.*` 显式传递插件的 Web Identity provider、端点与区域；
Doris 自身的 native reader 继续使用 `s3.credentials_provider_type = WEB_IDENTITY`。
凭证仍来自 IRSA，没有静态密钥。
[Doris JDBC 初始化](https://github.com/apache/doris/blob/ad35a140c7fd0b842f18c23300bac581f7d04326/fe/fe-core/src/main/java/org/apache/doris/datasource/property/metastore/PaimonJdbcMetaStoreProperties.java)、
[SDK 2 S3 loader](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-filesystems/paimon-s3/src/main/java/org/apache/paimon/s3/S3Loader.java)
是此差异的固定源码依据；修正后的实际凭证加载与查询尚未验证。

本地手动加载原第三轮 BE 产物还暴露了缺少插件目录入口导致的
`PluginLoader` 空指针。该诊断没有访问 AWS，也不是本轮现场报错。
新增目录入口核验用于拒绝同类不完整产物；
[PluginLoader](https://github.com/apache/paimon/blob/604e6d5e131c74a8d127333a2a3ad6d0319732bf/paimon-common/src/main/java/org/apache/paimon/plugin/PluginLoader.java)
直接调用 `ClassLoader.getResource(dirName)`，解释了必须保留目录入口的原因。

第四轮准备及独立产物复核通过，含两个插件目录入口。
本地无凭证的手动加载检查中，旧配置复现现场同类系统设置凭证错误；
显式配置则进入 Web Identity provider，并因本机缺少 IRSA 角色身份而拒绝访问。
这只证明目录加载及 provider 选择，不证明真实 STS/S3 授权或查询通过。

第四轮随后以源码 `5321afacfa9c51f2022f67a0a77040119f31ef1f` 实际部署，
探针为 `20260930164559-2520932`，现场 FE/BE 回执哈希均为
`49dbee3208f77201d69a739b67ae888ccce0baacaf6722fcf7094fe1c66eb017`。
catalog 创建、初始 MOR/append/DV、两个版本选择器及 FE 重建后 MOR 读取通过。
初始 DV/L0 行集为 1,027 行，但该阶段读路径尚无物理 deletion vector。
实际原生更新、删除后，MOR JNI 查询报 `AbstractMethodError`；
后续更新/删除 DV、schema 及其他门禁未运行。
外层于 2026-09-30 17:01:18 UTC 退出 1。
独立清理再次确认本轮资源、三个表前缀、FE EBS 与专用容量不存在，
CNI false/false、PolicyEndpoint 为零，原有节点、作业及 Kafka 身份不变。
374 份终态证据逐字节归档并再次核查 SHA-256。

离线本地诊断使用同一 BE connector 和固定 stock UDF：
UDF 优先的类路径复现 `CodeGeneratorImpl` 缺少 SDK 2 的
`generateRecordComparator(List, int[], boolean[])` 实现；
stock UDF 中 1,737 个 `paimon-codegen/` 入口的元数据版本为 `1.4.2`。
SDK 2 优先时越过该错误，但独立诊断类路径缺少 Hadoop MapReduce 类而停止。
这些诊断不是 AWS 行集验收；共享 UDF 的资源移除修复仍须实际部署验收。

第五轮准备及独立产物复核通过，产物增至 15 个 JAR。
共享 UDF 移除 1,737 个旧 SDK 入口，其余 106,952 个入口逐字节核对一致。
补齐离线诊断的 Hadoop MapReduce 依赖后，原始 UDF 优先仍复现相同 ABI 错误，
SDK 2 优先得到更新后的唯一行 `1 / value-2 / 2`。
替换为第五轮 UDF 后，UDF 优先也读到该唯一行，命令退出 0。
这证明固定本地类路径下的代码生成冲突已消除；当时 AWS/S3、Doris JNI 及 DV 尚未运行。
既有 36 项 Python 测试通过，没有新增测试。

## 第五轮实际 AWS 有界验收

实际源码为 `e455e0f5fba0a6b97da4e8bd87af052bd4529fd8`，
探针 `20260930172729-2593904`，外层会话 `20260930172116-2586816`，
使用 `work / us-west-2`。驱动于 2026-09-30 17:51:35 UTC 退出 0。
FE/BE 中的产物回执 SHA-256 均匹配
`2d4412f9032540b4df73c95f00d59fa7d54d5432fb3c1ad17095e52203fab86c`。
分类为 `SDK2_NATIVE_JNI_DV_BOUNDED_PASS`；完整 Issue #14 和 M1 仍未完成。

| 已运行阶段 | 结果与实际路径 |
| --- | --- |
| 初始 MOR、DV/L0、append | 原生与 Doris 行集一致；MOR/DV 各 1,027 行，初始 DV 尚无物理 deletion vector |
| 原生更新、删除后 MOR/DV | 各 1,027 行一致；新值 `Lin v2 / 2`、删除键不再出现、新键 Maya 正确；均为 JNI `native-splits=0/1` |
| 版本选择器 | 初始及更新后的 `FOR VERSION`、`@options` 行集通过 |
| 实际物理 DV 压实后 | 默认与固定快照行集一致；`native-splits=2/2`、`has-deletion-vector=true`；旧快照仍正确 |
| append schema 变更与加列 | 行集对照通过；加列轮次观测延迟 9,073 ms、该轮查询 1,064.089 ms |
| 认证、FE 重建、受限发布视图 | 探针门禁通过，原生与候选快照行集对照后发布 |

更新 MOR 和 DV 的该次正确行集观测分别为 16,132 ms、25,776 ms；
初始 MOR 冷启动观测为 269,861 ms，包含启动过程。
这些是单次、有界观测，既不是 p95/p99，也不是持续目标档的延迟结论。
host 与 seed Pod 的实测时钟偏差上界在前后两次分别为 31 ms、33 ms；
这不覆盖所有读端或 24 小时的时钟稳定性。

三种表的旧快照读取通过。系统表 `$snapshots`、`$files`、`$schemas`、
`$branches`、`$tags`、`$audit_log` 仅查询执行，内容尚未逐项核对，均为 **LIMITED**；
`$indexes` 报未知系统表。增量查询执行得到四行，但未与原生 changelog 对照，
同样为 **LIMITED**。不能把能执行 SQL 当作该项语义通过。

2026-09-30 23:37 UTC 的独立核查确认本轮 Kubernetes 资源、三个 S3 表前缀、
FE EBS 和专用容量不存在，CNI false/false、PolicyEndpoint 为零；
原有节点、四条 Flink 作业及 Kafka 身份不变。
499 份终态与清理证据逐字节归档并重新核对 SHA-256。
原始驱动 RESULT 中的独立清理 `NOT_RUN` 保留，单独回执提供清理 PASS。
前面 stock reader 的 RED、第三轮凭证失败及第四轮 JNI 失败也全部保留。

本轮不提供千万行、10 QPS 看板、1 QPS 交互、持续目标负载、24 小时、
全路径样本或主引擎最终选型通过结论。此自构建组合的有界功能已验证；
实际性能与其余能力仍是门禁，不能称为官方已发布的 Paimon 2.x reader。

这次没有在未确认的 #14 负载测试接口上新增测试，也不作为 TDD 结论。
编译和字节存在性无法证明 FE 已编译代码的 ABI、split/table 序列化、
运行时共享依赖及真实 DV/L0 行集正确。

## 使用入口

原始输入须从固定基础镜像读取，或从已核对相同基础镜像的独立 Pod 只读复制。
准备 CLI 对三个原始 BE JAR 强制校验，输入不匹配即拒绝。
从仓库根目录执行，参数中的路径指向本机私有构件：

```bash
python3 infra/m1/issue14/prepare-paimon2-reader.py \
  --stock-be-connector /private/stock-be-paimon-connector.jar \
  --stock-preload /private/preload-extensions-jar-with-dependencies.jar \
  --stock-udf /private/java-udf-jar-with-dependencies.jar \
  --cache /private/sdk2-cache \
  --output /private/new-paired-reader

python3 infra/m1/issue14/prepare-paimon2-reader.py \
  --verify-output /private/new-paired-reader
```

提交并审查源码后，依现有 `work/us-west-2` 流程部署镜像仓库来源，
才可执行候选镜像构建与串行验收：

```bash
bash infra/m1/issue14/deploy-image-repo.sh
bash infra/m1/issue14/build-images.sh --paimon2-reader /private/new-paired-reader
# 部署本 SHA 的角色及独立容量，核对原有任务/CNI 基线后执行：
bash infra/m1/issue14/run-persistent-session.sh --paimon2
```

候选 ECR 标签为 `<image-source-sha>-paimon2-<receipt-sha256>-fe/be`，stock 标签保持
`<image-source-sha>-fe/be`。运行入口以所选标签解析实际 ECR digest、
校验本地引用和完整扫描状态；两个变体不得混用。
准备回执随镜像保留，并保存为本工作树的私有文件。
回执包含全部产物校验和及准备源码校验和；
不同编译器产生不同字节时，回执和镜像标签也随之变化。
构建临时上下文退出时清除。

## 24 小时基线中的版本读复核

2026-10-11 在 #16 的 24 小时基线运行 `20261011062753_1289437` 上复核
time travel 之外的版本读能力。读端为 SDK2 基础镜像加受限 JDBC 读端：
FE `m1-doris@sha256:ae19e6d65ec43656b85462580253cc968ddbf4ba668a65963bbf02fa723a7020`，
BE `m1-doris@sha256:ecaf6f89ffd01d19c0160b85dc787219c25c7efb7d2b2325ea9d73c495929cfa`，
catalog 为 `paimon.catalog.type=jdbc`、`paimon.metastore=cove-readonly-jdbc`，
`meta.cache.paimon.table.ttl-second=0`。被测表在持续写入中：
`ods.issue15_orders_*` 为 DV 主键表（write-only，独立 compaction），
`dwd.issue15_orders_*` 为非 DV 主键表。在 ODS 快照 320、DWD 快照 358 上各建
一个 tag，并由 tag 建 branch。原生侧用同一 JDBC catalog 和 Paimon 2.0.0 API
读取，比较行数、金额和、版本 2 行数与键和。

| 能力 | Doris 读法 | 结果 |
| --- | --- | --- |
| tag | `FOR VERSION AS OF '<tag>'`、`@tag('name'='<tag>')` | **通过**：两种写法在 DV ODS 与 DWD 上的聚合与原生 tag 读相同（如 ODS 700,000 行、金额和 424,588,000）；八项聚合判定两个 tag 状态恰好等于确定性前缀 5,393,800 与 5,423,900，不符合公式的行数为 0 |
| branch | `@branch('name'='<branch>')` | **不支持**：Doris 经 Paimon 2.0.0 `JdbcCatalog` 加载 branch，报 `currently does not support table branches`；原生 `Catalog.getTable(branch)` 报同一错误。直接在表对象上 `switchToBranch` 读出的 branch 数据与 tag 一致，限制在 JDBC catalog，须待 M2 的 REST catalog 复核 |
| 增量读 | `@incr('startSnapshotId'='310','endSnapshotId'='320')` | **受限**：DV ODS 返回 360,800 行，与原生 `incremental-between`（changelog 模式）的 180,400 条撤回加 180,400 条新增相同；行数、键和、键×版本和、金额和、版本和五项校验和在 ODS 与 DWD 上均一致，但 Doris 不返回 RowKind |
| 系统表 | `$tags`、`$branches`、`$snapshots` | `$tags`、`$branches` 内容与创建结果一致；`$snapshots` 在基线探针中前 6 小时每轮、之后每 60 轮查询一次最新快照，耗时随保留快照增长到约 100 秒，04:34 UTC 停用。`$indexes` 仍不支持，整体为**受限** |

执行工具为 #16 交付的 `infra/m1/issue16-baseline/TagBranchProbe.java` 与
`doris_versions.py`，随 #16 的 PR 合入 main；运行时的 jar SHA256 记在私有证据的
`ARTIFACTS-jars-v*.json`。私有证据：
`<证据目录>/m1-baseline-20261011062753-1289437/`
下的 `TAG_BRANCH_CREATE.json`、`TAG_BRANCH_NATIVE.json`、`TAG_BRANCH_DORIS*.json`
与 `INCREMENTAL_DORIS.json`。

## 下一道验收门禁（历史）

本节是 24 小时基线前的计划，已由上节与[结项判定](doris-paimon.md#结项判定)取代。

上一轮 #15 已完成独立清理与归档。
源码 `65c5fc9`、`5321afa` 的失败轮次及 `e455e0f` 的有界通过轮次
均已取得终态、独立清理核验和证据归档。
下一轮仍须核对共用镜像仓库来源、CNI 和原有作业基线，再部署独立容量。
下一阶段扩展到 10M 行、持续目标负载、24h、查询性能及其余能力语义对照，
再决定主引擎选择；每项仍需独立回执。

> 注：本文中的 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
