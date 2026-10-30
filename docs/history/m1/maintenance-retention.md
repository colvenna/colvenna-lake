# M1 独立维护与数据保留验证

本页对应 Issue #12，依据
[HLD 8.3、11、12.4 节](../../design/hld-realtime-lakehouse.md)。在 AWS
`work` 账号 `<ACCOUNT_ID>` 的 `us-west-2` 私有 PoC 环境，使用
Paimon 2.0.0、Flink 1.20.4、JDBC catalog 共享锁和真实 S3。
所有表与数据均为隔离的合成输入，不引用 #8 历史前镜像不合格的 CDC 表。

## 实验契约

`issue12/run-probe.sh` 从干净提交构建 Java jar，核对挂载文件的 SHA-256，
并只创建一张 `ods.issue12_maint_<run>` 表。表采用 2 个 UTC 日期分区、
`bucket=-1` 和 `write-only=true`。固定的 Paimon 2.0.0
`CoreOptions` 没有 HLD 原先写的 `manifest.merge.skip-on-write-only`；
写入端仍采用默认 `manifest.merge-min-count=30`。
独立 `compact_database` 流作业以 combined 模式只发现本次表；
作业把 `manifest.merge-min-count` 覆写为 4，
要求维护前后当前快照的 manifest 数下降；
隔离表的 `snapshot.time-retained` 设为 1 分钟，首批提交
超过 65 秒后再启动维护，以观察该作业自身的快照过期。
12 行初始写入、停机追加 6 行、恢复运行期间追加 6 行，各阶段固定快照
通过真实 S3 文件及 Paimon 原生读者逐行核对 ID、值和分区。
恢复阶段的每次追加在准备好数据文件后保持 15 秒，
要求至少一次压实快照在尚未提交的写入区间内完成。
压实前打开固定在快照 12 的全部 reader split，跨两次压实和快照过期
后继续逐行读取完整 12 行；
另设 tag 与 consumer 指针保护所需历史。

此 24 行合成实验的门槛是：阶段性数据文件最多 16 个，压实后最多 4 个；
当前快照的数据 manifest 最多 32 个、总计最多 128 KiB；
物理保留的 manifest 对象最多 128 个、总计最多 512 KiB，
五类元数据（manifest、snapshot、tag、consumer、schema）
合计最多 256 个对象、1 MiB；未过期的 snapshot 最多 32 个；
物理保留的数据文件最多 64 个、总计最多 16 MiB，
过期清理后最多 8 个，过期测试分区的 S3 前缀须为零；
每批提交到原生读取完成最多 30 秒。
它们是可重复的测试上界，不是生产容量或 Doris 查询延迟目标。
停机时的文件积压、重启到运行及重启到压实的时间单独记录。
快照、分区与孤儿清理只在隔离表上执行；为了在短时 PoC 观察效果，
释放活跃 reader 后，以加速阈值调用 Paimon 的 consumer TTL 清理，
确认过时指针删除而新指针保留；随后手动快照过期使用 1 秒测试阈值；
默认的一天孤儿年龄保护先行核对。

## 安全与运行

AWS 的专用 IRSA 角色由
`maintenance-role-stack.ts` (`infra/m1/src/maintenance-role-stack.ts` at tag `history/m1-m5` in the early private repository)
的 TypeScript CDK 创建，OIDC 只信任
`cove-lake-ods:cove-issue12-maintenance` 服务账号。
角色仅读取 catalog 所需的精确父目录元数据，读写本次
`warehouse/ods.db/issue12_maint_*` 与独立 Flink checkpoint/savepoint
前缀。入口脚本核对账号、区域、CDK 输出源码 SHA、私有镜像 digest，
以及 admission 策略：维护身份创建的 Pod 必须使用自身服务账号，
所有使用该账号的 Pod 均受策略约束，包括控制器创建的 JobManager；
服务器端 dry-run 必须拒绝改用较宽的 ODS 身份。
策略还限定 Pod 的镜像、唯一可引用的 catalog Secret，拒绝其他 Secret
引用、hostPath/CSI 挂载和 host 网络；通过专用账号或控制器身份提交
的服务器端 dry-run 都必须明确拒绝挂载其他 Secret。
Flink 服务为 `ClusterIP`，无外部 IP 或 Ingress；对已确认存在的
合成 S3 对象执行匿名 GET，必须返回 403。任何未来公开的页面或资源
须先加入身份验证，并验证匿名请求被拒。

在专用 #12 worktree 的干净提交运行：

```bash
mvn -B -f infra/m1/issue12/pom.xml clean verify
cd infra/m1 && npm run check && npm test
cd ../..
pre-commit run --all-files
bash infra/m1/issue12/deploy-role.sh
bash infra/m1/issue12/run-probe.sh
```

`run-probe.sh` 删除本次 FlinkDeployment、Pod、ConfigMap、
RoleBinding、Role、服务账号与 admission 策略，定点删除测试表及
两次 Job 的 checkpoint 前缀；对 S3 及 Kubernetes 均核对无剩余后
才输出 `PASS`。证据留在忽略的 `infra/m1/evidence/issue12-*`。
CDK IAM 角色及底座 PoC 资源是单独管理的资源，实验结束不会随测试
Pod 一起删除。

## 实测与限制

提交 `815784e81bc5698c32b1f520fd0da8a643f0ff02` 的完整私有运行位于
`infra/m1/evidence/issue12-maintenance-20260929070021-3155520/`，
脚本输出 `Private independent maintenance PASS`。本轮核对了恢复后的压实
与未提交追加写入交叠、reader 跨压实读取、consumer 指针保护及过期、
旧分区逻辑过期与物理回收：

| 阶段 | 行数 / ID 合计 | 当前数据文件 | 当前 manifest 数 / 字节 | 物理数据文件 | 物理 manifest 对象 / 字节 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 初始停机 | 12 / 78 | 12 | 12 / 25,446 | 12 | 36 / 53,910 |
| 首次压实 | 12 / 78 | 2 | 2 / 5,272 | 14 | 40 / 61,443 |
| 自动快照过期 | 12 / 78 | 2 | 2 / 5,272 | 14 | 20 / 37,631 |
| 维护停止后追加 | 18 / 171 | 8 | 8 / 18,015 | 20 | 38 / 64,446 |
| 恢复并发追加后压实 | 24 / 300 | 4 | 2 / 4,687 | 30 | 74 / 122,184 |
| 快照、分区与孤儿清理后 | 24 / 300 | 4 | 4 / 8,937 | 5 | 9 / 15,494 |

停止时长为 23.749 秒；触发重启到 RUNNING 为 379.771 秒，
到压实为 498.488 秒。停机追加与并发追加的原生读可见时间分别为
1.816、1.449 秒。恢复阶段出现 3 个真实 `COMPACT` 快照，
其中压实提交落在准备中的写入区间。保留中的 consumer 将最早快照固定
在 12；释放 reader 与 consumer 后推进到 28，陈旧指针删除而新指针保留，
tag 仍可读。旧分区由 25 行回到 24 行，第一轮孤儿清理在默认年龄下删除
0 个对象，定点加速 dry-run 与实际删除均为 7 个；快照进一步过期到 30
后的第二轮 dry-run 与实际删除均为 0。旧分区 S3 前缀为 0 对象，
最终当前 24 行、ID 合计 300 未变，最终物理数据文件为 5 个。
已存在的合成 S3 对象匿名 GET 返回 403；临时表、两次 checkpoint
前缀和 Kubernetes 资源均清零。该结果仅是短时私有合成验证，
不替代 CI、生产保留期或长时间容量验证。

提交 `b544c87b1af72ed9fba798d3286fb57e0a3c5a60` 的完整私有复测位于
`infra/m1/evidence/issue12-maintenance-20260929063449-3058904/`。
并发写入期间产生真实 `COMPACT` 快照，consumer TTL 删除旧指针并保留新指针；
孤儿清理 dry-run 与实际删除均为 4 个，当前 24 行未变。
旧日期分区由 25 行过期为 24 行，后续快照过期将最早快照从 27 推进到 29。
但旧分区 S3 前缀仍有 1 个对象，脚本仅记录数量，未记录键及大小，
因此无法判定是否仍有物理数据文件，本轮为 `RED`。
隔离表、checkpoint 与临时 Kubernetes 资源在失败后均已清理。
后续运行将保存完整 S3 对象清单，只在唯一对象确认为零字节目录标记时
定点删除并复核前缀为零。Paimon 2.0.0 的快照过期之后还会对本隔离表
执行一次 dry-run 对账的孤儿清理，以回收旧分区历史数据文件；
若仍有数据文件或其他对象则继续失败。

提交 `b465fb9d3db5d39577555bf6ecd2d2b16503202f` 的运行位于
`infra/m1/evidence/issue12-maintenance-20260929061055-2969024/`。
写入期间产生的两次真实 `COMPACT` 快照、consumer TTL 清理和
25→24 行的旧分区逻辑过期已核对；孤儿清理的测试仍假定只有
1 个待清理文件，在新增快照和物理文件过期后该假定不再成立，
因此本次运行以 `Orphan dry-run changed the isolated table` 失败，
未执行最终物理文件上界与旧分区 S3 前缀断言。失败后的隔离表、
checkpoint 和 Kubernetes 资源均已清理。本次不能计作 #12 完成，
后续修订须完整重跑。

提交 `64f16d76059cb2329cf19579e045bcb55a96cc9a` 的完整私有验证位于
`infra/m1/evidence/issue12-maintenance-20260929054453-2496794/`。
服务器端 dry-run 已确认维护账号只能创建使用自身服务账号的 Pod，
控制器身份创建的同服务账号 Pod 同样受约束；两个身份挂载其他
Secret 均被 admission 策略拒绝。已存在合成 S3 对象的匿名 GET
返回 403。维护和恢复期间的原生 S3 读结果如下：

| 阶段 | 行数 / ID 合计 | 当前文件 | 当前 manifest 数 / 字节 | 物理 manifest 对象 / 字节 |
| --- | ---: | ---: | ---: | ---: |
| 停机初始状态 | 12 / 78 | 12 | 12 / 25,439 | 36 / 53,787 |
| 首次压实后 | 12 / 78 | 2 | 2 / 5,265 | 40 / 61,312 |
| 作业自动过期后 | 12 / 78 | 2 | 2 / 5,265 | 20 / 37,605 |
| 停机追加 | 18 / 171 | 8 | 8 / 18,003 | 38 / 64,410 |
| 恢复并发追加后压实 | 24 / 300 | 2 | 2 / 5,486 | 60 / 99,918 |
| 快照、旧分区与孤儿清理后 | 24 / 300 | 2 | 4 / 9,732 | 13 / 20,833 |

独立作业把最早快照从 1 自动推进到活跃 consumer 需要的 12，
当时保留快照数从 14 降为 4；跨压实持有的两个 reader split
最终读出原有全部 12 行。停机 24.111 秒后触发重启，
从重启到 RUNNING 为 361.375 秒、到压实为 427.869 秒；
停机追加与运行期间追加的原生读可见时间分别为 1.844、2.592 秒。
释放 consumer 后最早快照从 12 前进到 26，
tag 仍可读取原有数据。旧分区清理从 25 行回到 24 行，
孤儿清理依次为默认年龄保护 0、dry-run 识别 1、删除 1。
本次运行结束后，隔离测试表与两次 checkpoint 前缀均无
S3 对象，Flink、Pod、Service、Deployment/ReplicaSet、
测试身份和 admission 策略无剩余。
该次运行没有给历史物理数据文件设上界，旧测试分区在整表删除前
仍留有文件；也没有单独验证 consumer TTL 和压实与未提交写入交错。
这些缺口不以本次 `PASS` 输出抵消，后续修订运行须补齐。

提交 `78c8a29bbcb6f76d19f181a659ac557c03372ed3`
的完整数据行为验证位于
`infra/m1/evidence/issue12-maintenance-20260929051516-2184032/`。
当前快照的文件与 manifest 数和 S3 上仍保留的历史元数据分别采集，
不会将当前快照变小误报为全部物理对象已删除：

| 阶段 | 行数 / ID 合计 | 当前文件 | 当前 manifest 数 / 字节 | 物理 manifest 对象 / 字节 |
| --- | ---: | ---: | ---: | ---: |
| 停机初始状态 | 12 / 78 | 12 | 12 / 25,427 | 36 / 53,977 |
| 首次压实后 | 12 / 78 | 2 | 2 / 5,270 | 40 / 61,508 |
| 作业自动过期后 | 12 / 78 | 2 | 2 / 5,270 | 20 / 37,629 |
| 停机追加 | 18 / 171 | 8 | 8 / 18,011 | 38 / 64,428 |
| 恢复并发追加后压实 | 24 / 300 | 2 | 3 / 7,548 | 63 / 104,352 |
| 快照、旧分区与孤儿清理后 | 24 / 300 | 2 | 5 / 11,794 | 14 / 22,920 |

独立维护作业把最早快照从 1 自动推进到活跃 consumer 需要的 12，
对应 snapshot 数从 13 降至 2。停机追加后从重启到
RUNNING 为 357.499 秒、到压实为 436.107 秒；
停机追加和运行期间追加的原生读可见时间分别为 1.887 和 2.667 秒。
压实前持有的两个 reader split 最终读出全部 12 行，
释放 consumer 后最早快照从 12 推进到 27，
tag 仍可读第一行；旧分区清理从 25 行回到 24 行，
孤儿清理依次为默认年龄保护 0、dry-run 1、删除 1。
本次运行结束后测试表及两次 checkpoint 前缀均为 0 个 S3 对象，
临时 Kubernetes 资源也已删除。

先前提交 `a475bcc028becefce7227cab6f26085d5f652add` 的原始
Paimon 验证保存在
`infra/m1/evidence/issue12-maintenance-20260929040801-1916114/`：

| 阶段 | 行数 / ID 合计 | 数据文件 | manifest 数 / 字节 |
| --- | ---: | ---: | ---: |
| 初始维护停止 | 12 / 78 | 12 | 12 / 25,449 |
| 首次压实 | 12 / 78 | 2 | 13 / 28,152 |
| 停机追加 | 18 / 171 | 8 | 19 / 40,893 |
| 重启并发追加后压实 | 24 / 300 | 2 | 26 / 56,405 |
| 快照、旧分区与孤儿清理后 | 24 / 300 | 2 | 28 / 60,648 |

该次从触发重启到积压压实为 449 秒。consumer 存在时最早快照从
1 过期到 12，固定在快照 12 的 12 行仍可读；释放 consumer 后
最早快照从 12 前进到 26，tag 仍可读第一行。
测试旧分区清理使 25 行回到 24 行；
孤儿清理依次得到默认年龄保护 0、dry-run 识别 1、定点删除 1。
测试表及两次 checkpoint 的 S3 前缀均为 0 对象，
Flink 与测试身份已删除。这次运行没有跨维护持有的活跃读者、
admission 越权拒绝或定量 manifest 上界，不能替代后续修订提交的接收。

较早的失败原样保留：
`issue12-maintenance-20260929033654-1781459/` 因节点 CPU
无法调度 JobManager；
`issue12-maintenance-20260929034031-1808426/` 受到 ODS
角色的 S3 warehouse 父目录 403；
`issue12-maintenance-20260929035015-1863599/` 中专用角色仍缺少
Paimon `user.sys` 固定目录的精确读取；
`issue12-maintenance-20260929035953-1891256/` 中 TaskManager
因缺少本次 Deployment 的 `get` 权限而失败。
对应修复均在后续提交中实施；这些运行从未计作 Issue #12 接收通过，
本次测试表与 checkpoint 前缀已清理。

本实验只证明原生 Paimon 读者与私有 S3 上的短时合成表行为。
Doris 外表读取与 native/JNI 路径由 #14 验证，
24 小时负载、生产延迟和故障基线由 #16 验证。
这里的加速过期不证明生产定时调度、真实保留期或个人数据的跨副本删除；
删除证明另由独立 issue 验收。
现有私有 ECR 基础镜像扫描仍有 Critical/High 漏洞，
不能据此批准生产发布。

> 注：本文中的 issue 与 PR 编号（如 #12）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
