# M1 ODS 到 DWD/DWS 持续加工验证

本页对应 Issue #13，依据
[HLD 9、9.1、16 节](../../design/hld-realtime-lakehouse.md)。实验只在 AWS
`work` 账号 `<ACCOUNT_ID>` 的 `us-west-2` 私有 PoC 环境运行，固定
Paimon 2.0.0、Flink 1.20.4、共享 JDBC catalog 与真实 S3。
所有表名带本次运行 ID，全部业务数据为合成样例。

## 业务口径与作业

`ods.issue13_orders_<run>` 保存订单主键变更，
`ods.issue13_dim_<run>` 保存客户到地区的维表。
两个独立的常驻 `FlinkDeployment` 运行 Flink SQL：
DWD 使用带 `consumer-id` 的 ODS changelog 做 Paimon 处理时间 lookup join，
DWS 使用另一个 `consumer-id` 消费 DWD changelog，按地区统计订单数和金额。
ODS、DWD 的主键表设置 `changelog-producer=input` 与
`consumer.expiration-time=1 h`；三个业务层的每个结果都由
Paimon 原生 reader 从真实 S3 文件核对。作业通过 Java API 注册 catalog，
不把 JDBC 密码拼入 SQL 文本。

| 阶段 | ODS 变更 | 期望 DWD | 期望 DWS |
| --- | --- | --- | --- |
| 初始 | 订单 1、2、3 插入 | 1/3 属于 north，2 属于 south | north：2 单、40；south：1 单、20 |
| DWD 停机积压与重启 | 1 金额 10→15；删除 2；插入 4 | 1/3 属于 north，4 属于 south | north：2 单、45；south：1 单、40 |
| DWS 独立 savepoint 重启 | 3 金额 30→35；删除 4；插入 5 | 1/3/5 属于 north | north：3 单、55；south 行删除 |
| 维表更新后新事件 | 客户 1 的地区 north→west，随后插入订单 6 | 原有订单仍为 north，新订单 6 为 west | north：3 单、55；west：1 单、7 |

每次源端写入是单独进程，停机期间不写入；DWD 停机时制造已提交但未加工
的 ODS 积压，随后以相同 consumer 恢复。DWS 使用 savepoint
升级模式独立重启，必须留下本次运行前缀下的升级 savepoint。
每阶段要求三层的 ID、版本、金额、地区与聚合结果完全一致，
并要求 consumer 的下一个快照编号越过当前上游快照。
处理时间 lookup join 中的维表更正只影响之后到达的订单事件；
追溯修正已有订单属于 Issue #15
的历史回刷和下游重算范围。

完成正常业务阶段后，探针在隔离源表加速过期快照并删除已停止作业的
consumer，重新提交真实 DWD `FlinkDeployment`。
已有 DWD 表缺少必需指针时，作业入口必须报 `REBUILD_REQUIRED`
且不得进入 RUNNING。另用隔离表验证指针存在但落后于最早可恢复快照
时同样要求重建。加速后的测试窗口不代表生产一小时保留期。

## 安全和清理

AWS 专用 IRSA 角色由
`layered-role-stack.ts` (`infra/m1/src/layered-role-stack.ts` at tag `history/m1-m5` in the early private repository)
通过 TypeScript CDK 创建，仅信任
`cove-lake-ods:cove-issue13-layered` 服务账号，数据权限限定在
`warehouse/{ods,dwd,dws}.db/issue13_*` 和本次 Flink 状态前缀。
Kubernetes admission 同时检查由该账号创建、或以该账号运行的 Pod，
只允许固定镜像与指定 catalog Secret，并拒绝其他 Secret、
hostPath/CSI 挂载和 host 网络；负向 dry-run 要由准入策略明确拒绝。
Flink Service 必须为 `ClusterIP`，没有外部 IP 或 Ingress；
匿名 GET 已确认存在的合成 S3 对象必须返回 403。
现有 PoC 节点剩余 CPU 请求额度不足以保证同时运行两个分层 Job；
测试 Pod 与 JobManager/TaskManager 可以调度到 #14 临时 CDK 栈
创建的私有、加密且带 taint 的验证节点。此容忍配置只供 M1
合成实验使用，不赋予额外 IAM 或 Kubernetes 权限。
任何未来可公开访问的页面或资源须先加入身份验证，
并实测匿名请求被拒。

在专用 #13 worktree 的干净提交运行：

```bash
mvn -B -f infra/m1/issue13/pom.xml clean verify
cd infra/m1 && npm run check && npm test
cd ../..
pre-commit run --all-files
bash infra/m1/issue13/deploy-role.sh
bash infra/m1/issue13/run-probe.sh
```

成功输出前，脚本核对测试表、checkpoint/savepoint 前缀、
FlinkDeployment、Deployment/ReplicaSet、Pod、Service、
临时身份和准入策略均已删除；证据位于忽略的
`infra/m1/evidence/issue13-*`。CDK IAM 角色与底座私有 PoC
资源单独管理。

## 实测与边界

提交 `7add25e9bf3d086253a77d426dc2a9e516c47af1` 的完整私有运行位于
`infra/m1/evidence/issue13-layered-20260929080634-4072471/`，结果为 `PASS`。
初始 ODS/DWD 的 3 条订单与 DWS north 2 单 40、south 1 单 20
逐行相符，源提交到三层收敛用时 67.453 秒。DWD 停机后，
ODS 提交更新 1、删除 2、新增 4；下游快照和旧行集保持冻结。
新 DWD Job ID 与旧 ID 不同，恢复后 DWD 明细与 DWS north 2 单 45、
south 1 单 40 完全一致；源提交到收敛用时 507.167 秒，
其中停机到新 DWD `RUNNING` 为 459.278 秒，
停机到三层收敛为 515.397 秒。

DWS 独立重启确实生成了本次专属的升级 savepoint；
新 Job 的 Flink REST 报 `restored=1`、`is_savepoint=true`，
恢复路径与 Operator `jobStatus.upgradeSavepointPath` 一致，
S3 `_metadata` 非空且为 8,914 字节。
后续更新 3、删除 4、新增 5 的三层收敛用时 73.285 秒，
终点只有 north 3 单 55。维表 north→west 更正后新订单 6
进入 west，旧订单保持 north；DWS 为 north 3 单 55、
west 1 单 7，源提交到收敛用时 75.482 秒。
已有合成 S3 对象的匿名 GET 返回 403，两个 Job 均完成 checkpoint。

删除已停止 DWD 的 consumer 后，真实 `FlinkDeployment` 以
`REBUILD_REQUIRED` 失败而未进入 `RUNNING`，业务行集保持不变；
另一隔离表的指针落后最早可恢复快照时同样返回
`LAG_REBUILD_REQUIRED`。完成后 5 个隔离表 S3 前缀、
checkpoint/savepoint 前缀各为 0 个对象，
FlinkDeployment、Pod、Service、临时身份及准入策略均无剩余。
本轮只证明合成 PoC 的分层加工与指定故障场景；
历史追溯回刷、Doris 查询及 24 小时容量仍由 #15、#14、#16 验证。

提交 `32528026de4fc2dd270099324f2fe36f82c21a3d` 的私有运行位于
`infra/m1/evidence/issue13-layered-20260929072914-3607219/`。
初始 3 单的 ODS/DWD/DWS 与 north 2 单 40、south 1 单 20 的聚合完全一致，
源提交到三层对账用时 66.921 秒，DWD/DWS 均完成 checkpoint；
匿名读取已存在的合成 S3 对象返回 403。DWD 停机期间提交更新、删除与新增，
下游保持旧快照；重新启动后，DWD 和 DWS 分别得到订单 1 金额 15、
订单 2 删除、订单 4 新增以及 north 2 单 45、south 1 单 40，
从源提交到收敛用时 495.558 秒（含重新启动）。
DWS 随后确实创建了升级 savepoint，新 Job 的 Flink REST 报告
`restored=1`、`is_savepoint=true`，恢复路径与 Operator 的
`jobStatus.upgradeSavepointPath` 及 S3 中非空 `_metadata` 对象一致。
但脚本错误地检查了空的 `lastSavepoint.location`，因此本轮输出
`DWS restarted without its scoped stateful upgrade savepoint`，记为 `RED`。
隔离表、checkpoint/savepoint 前缀和临时 Kubernetes 资源在失败后均已清理。
恢复后的后续变更、维表更新和过期 consumer 失败场景尚未运行，
修订脚本须完整重跑。

提交 `2f243aa05e1e8c2d5789c6b1b23902edc39bf4af` 的首次私有运行位于
`infra/m1/evidence/issue13-layered-20260929072457-3597148/`。
DWD `FlinkDeployment` 在创建时被 API 拒绝：纯数字与下划线组成的运行 ID
作为未加引号的 YAML 参数被解析成数字，而 `job.args` 要求字符串。
该次尚未开始分层写入，记为 `RED`。错误路径的资源核对还发现
`awk` 表达式换行错误，故脚本原始输出为 `CLEANUP_INCOMPLETE`；
随后人工核对 5 个隔离表前缀和 2 个状态前缀均为 0 个 S3 对象，
测试 Pod、Service、Deployment 等 Kubernetes 资源均无剩余，
定点删除了保留下来的 admission 策略及 binding。
后续修订仍需完整重跑；本地 Maven 或 CDK 测试不能代替三层业务接收。

Doris 原生/JNI 查询由 #14 验证，追溯性维表更正与回刷由 #15 验证，
24 小时负载和延迟基线由 #16 验证。
现有私有 ECR 基础镜像扫描仍有 Critical/High 漏洞，
不能据此批准生产发布。

> 注：本文中的 issue 与 PR 编号（如 #13）和 tag（如 `history/m1-m5`）都来自本项目早期的私有仓库，本仓库里没有对应的页面。
