# 发布候选

这个目录保存每个发布候选的清单与发布说明：

| 文件 | 内容 |
| --- | --- |
| `<候选>/manifest.json` | 发布清单：全部产品镜像、测试夹具镜像与[第三方镜像](#第三方镜像)在发布仓库里的仓库名、tag 与 digest（第三方镜像另记它固定的上游引用），colvenna CLI 二进制的 SHA-256，四个 Helm chart 的版本与目录的 git tree，候选固定的 [Colvenna Catalog](#colvenna-catalog) 发布版（版本、tag 的提交、`built_from`，以及它的镜像与 chart 在发布仓库里的副本），`install.sh` 与 `uninstall.sh` 的 blob，UAT 用例文档的提交与 blob，支持的配置（安装方式，以及验收的两个以上标准区域），本次的必需用例集合，以及性能验收口径。清单不写发布仓库的地址、账号，也不写生成它的执行身份（见下文“发布仓库”）。格式与校验见 [`deploy/scripts/release/manifest.py`](../deploy/scripts/release/manifest.py) |
| `<候选>/notes.md` | 发布说明，由 [`notes-template.md`](notes-template.md) 生成，链接七项交付物的固定版本 |

**组件与商标。** 候选的镜像建立在 Apache 软件基金会（ASF）的几个项目之上：Flink 基础镜像（`flink-base`）是官方 [Apache Flink](https://flink.apache.org/) 1.20.4 镜像加 [Apache Paimon](https://paimon.apache.org/) 2.0.0 的 jar 与 JDBC 驱动；查询引擎镜像（`query-engine`）是基于 [Apache Doris](https://doris.apache.org/) 4.1.4 的修改构建（自带的 Paimon 读取器换成了对 Paimon 2.0.0 编译的读取器），不是 ASF 的发布，所以不以 Doris 命名；BI 镜像（`colvenna-bi`）基于 [Apache Superset](https://superset.apache.org/)。第三方镜像里还有 Apache Flink Kubernetes Operator、Apache Doris 的 Doris Operator、[Apache Polaris](https://polaris.apache.org/) 与 [Apache Airflow](https://airflow.apache.org/) 等的上游镜像，原样复制。各镜像怎样命名与依据，见[商标与命名](trademarks.md)。

> Apache, Apache Paimon, Paimon, Apache Flink, Flink, Apache Doris, Doris, Apache Superset, Superset, Apache Polaris, Polaris, Apache Airflow, Airflow, Apache Kafka, Kafka, Apache Spark and Spark are either registered trademarks or trademarks of The Apache Software Foundation in the United States and/or other countries. No endorsement by The Apache Software Foundation is implied by the use of these marks.

Colvenna Lake 不是 ASF 的项目；其他各方的商标见[商标与命名](trademarks.md)第 1 节。

候选的名字是 `v<主>.<次>.<修订>-rc.<n>`，例如 `v1.0.0-rc.1`；正式版本去掉 `-rc.<n>`。`v0.0.0-dev.<n>` 只用来试验工具，不入库。

**Helm chart 的版本。** 本仓库四个 chart 的版本号都还是 `0.1.0`，不随候选变化。固定 chart 的是清单里各 chart 目录的 git tree（`charts.<chart>.tree`）：两个候选的 chart 是否相同，比较 tree，不看版本号。colvenna-catalog 的 chart 是 Colvenna Catalog 发布的，以它的版本与 digest 固定（`catalog.chart`）。

## 候选与 tag

- 镜像在提交 B 上构建：用 B 从源码装好一套带 `--fixtures` 的环境，`make-manifest.sh` 再从这套环境生成清单。
- 清单记下 B（`source.built_from`）。B 加上 `release/<候选>/` 这一个目录，就是提交 C；annotated tag `<候选>` 指向 C。
- `deploy/install.sh --release <候选>` 只在与 B 相比只有 `release/` 不同的工作区里运行，所以检出 tag 就能安装，之后的 main 不行。
- tag 所在提交里的 `notes.md` 只记录候选的身份（候选名、B、日期、验收的区域与性能验收口径）。交付物的状态、报告的链接与结论要等材料齐备，另行提交到 main 的 `release/<候选>/notes.md`；发布检查链接那个提交。

## 发布仓库

镜像保存在 ECR 仓库 `colvenna-lake-release/<名字>` 里，由 CDK 栈 `ColvennaLakeRelease` 管理（[`deploy/aws/src/release-stack.ts`](../deploy/aws/src/release-stack.ts)）。它建在部署者自己的账号里，区域由 `COLVENNA_RELEASE_REGION` 指定，默认 `us-west-2`。

**清单不写仓库地址。** 从 `v1.0.0-rc.2` 起，清单只记仓库名、tag 与 digest，不写发布仓库的账号与区域，也不写生成它的执行身份（#248）。安装时由设置 `COLVENNA_RELEASE_REGISTRY=<账号>.dkr.ecr.<区域>.amazonaws.com` 给出发布仓库：`install.sh --release` 必须给它（`check-release.sh`、`install-images.sh` 从它读取），与安装到哪个账号、哪个区域无关；从源码安装时可以给：从它复制查询引擎镜像与它已有的第三方镜像；Colvenna Catalog 的发布版不从它取，默认从 GitHub Container Registry 匿名取（见下文 [Colvenna Catalog](#colvenna-catalog)）。digest 决定内容，所以任何保存着同一批 digest 的仓库都可以作为来源。维护者发布本仓库候选的发布仓库地址不写进仓库，由维护者另行告知；别的账号要拉取，请维护者把它加进下面的拉取账号。`v1.0.0-rc.1` 的清单在它的 tag 上，仍写着发布仓库的账号与执行身份，不改；它只能用它自己 tag 上的工具安装。

- 名字与环境自己的仓库对应：`colvenna-lake-<环境>/colvenna-jobs` 对应 `colvenna-lake-release/colvenna-jobs`；第三方镜像都在 `colvenna-lake-release/third-party`，对应环境的 `colvenna-lake-<环境>/third-party`；
- 查询引擎的 FE 与 BE 在 `colvenna-lake-release/query-engine`，清单的键是 `query-engine-fe`、`query-engine-be`（#258）。`v1.0.0-rc.1` 与 `v1.0.0-rc.2` 的清单写的是产品旧名字的发布仓库（[商标与命名](trademarks.md)第 5、7 节），本仓库的工具不按它们安装，它们的 tag 与清单都不变。复制进一套环境自己的仓库时，FE 与 BE 的 tag 不是候选名，而是 `<DORIS_TAG>-fe-<digest 的前 12 位十六进制>`、`-be-<…>`（`lib/common.sh` 的 `doris_tag`），与从源码安装时相同：每个固定的 digest 有自己的 tag，跨越一次重新固定的升级不会撞上旧 digest 的 tag（#278）；
- Colvenna Catalog 发布版的副本：服务端镜像在 `colvenna-lake-release/colvenna-catalog`，Helm chart（OCI 制品）在 `colvenna-lake-release/charts/colvenna-catalog`，digest 与 Colvenna Catalog 自己发布的相同，tag 都是 `<候选>`（见下文 [Colvenna Catalog](#colvenna-catalog)）；
- tag 是 `<候选>` 或 `<候选>-<变体>`（例如 `v1.0.0-rc.1-kafka`、`v1.0.0-rc.1-fe`），第三方镜像是 `<候选>-<名字>`（例如 `<候选>-debezium-server`），不可改写；
- 推送时扫描；删栈时保留仓库，没有生命周期规则，任何环境的卸载都不碰它。环境名 `release` 因此是保留名；
- 默认没有仓库策略，只有仓库所在账号能经 IAM 读取。要让别的账号安装（#212），在 `COLVENNA_RELEASE_PULL_ACCOUNTS` 里列出它们的 12 位账号 ID（CDK 上下文 `-c pullAccounts=<id>,<id>`）：每个仓库加一条仓库策略，只允许这些账号的 IAM 身份（`arn:aws:iam::<id>:root`）拉取镜像——`ecr:BatchGetImage`、`ecr:GetDownloadUrlForLayer`、`ecr:BatchCheckLayerAvailability`，不能推送、删除或列出，也从不开放给 `*`。
- 拉取账号里，安装者的身份还要有自己账号的 IAM 权限：上面三项（资源可以限定为发布仓库的 ARN），以及 `ecr:GetAuthorizationToken`（取 registry 的登录凭证；这一项只能授予 `*`，它本身不给任何仓库的访问）。

部署或更新这个栈：

```bash
deploy/scripts/release/deploy-registry.sh                                        # 默认 us-west-2，不给别的账号
COLVENNA_RELEASE_PULL_ACCOUNTS=111122223333,444455556666 deploy/scripts/release/deploy-registry.sh  # 允许这两个账号拉取
```

**试验用的候选。** tag 不可改写，`v0.0.0-dev.<n>` 这类试验候选推进去以后一直留在仓库里，除非删掉。删除时先按 tag 删（`names` 也列出第三方镜像），再删 OCI index 留下的无 tag 子清单（Flink 基础镜像与大多数第三方镜像是 index）：

```bash
python3 deploy/scripts/release/manifest.py names v0.0.0-dev.1 | while IFS=$'\t' read -r _ repo tag; do
  aws ecr batch-delete-image --region us-west-2 --repository-name "colvenna-lake-release/$repo" --image-ids "imageTag=$tag"
done
aws ecr list-images --region us-west-2 --repository-name colvenna-lake-release/flink-base \
  --filter tagStatus=UNTAGGED --query 'imageIds[].imageDigest' --output text   # 逐个以 imageDigest=<digest> 删除
```

2026-10-22 为 #201、#202 试验推过 `v0.0.0-dev.1`、`v0.0.0-dev.2`，用上面的做法删掉了，九个仓库试验之后都是空的。

## 生成一个候选

1. 在 B 上，用干净的工作区从源码装好（或升级）一套环境：`COLVENNA_ENV=<环境> COLVENNA_RELEASE_REGISTRY=<发布仓库> [COLVENNA_CATALOG_REGISTRY=<仓库地址>/<前缀>] deploy/install.sh --fixtures`。查询引擎镜像从 `COLVENNA_RELEASE_REGISTRY` 取；Colvenna Catalog 的发布版默认从 GitHub Container Registry 匿名取，catalog 公开之前给 `COLVENNA_CATALOG_REGISTRY`，指向它的私有发布仓库（下文 [Colvenna Catalog](#colvenna-catalog)）。环境里若还留着更早提交装的开发用 [Apache Kafka](https://kafka.apache.org/)，`strimzi` 一步会把它换到本环境的第三方镜像副本（broker 滚动重启一次）。
2. 生成清单与发布说明的草稿：

   ```bash
   COLVENNA_ENV=<环境> [COLVENNA_CATALOG_REGISTRY=<仓库地址>/<前缀>] deploy/scripts/release/make-manifest.sh <候选>
   ```

   发布仓库在执行者的账号、`COLVENNA_RELEASE_REGION`（默认 `us-west-2`）里，要先在本提交上部署好（含 `colvenna-lake-release/third-party`）。#295 加了 `colvenna-lake-release/charts/colvenna-catalog`（Colvenna Catalog 的 chart），在这之前部署的发布仓库没有它，要先用 `deploy-registry.sh` 重新部署一次（给出上次部署时的 `COLVENNA_RELEASE_PULL_ACCOUNTS`，否则别的账号的拉取权限会随仓库策略一起被删掉）；脚本在核对环境之前先查发布仓库里有它要复制进去的每个仓库，缺了就列出来并停下。验收的区域由 `RELEASE_REGIONS` 给出，默认 `us-west-2,us-east-2`。脚本先核对这套环境确实是 B 的：七个栈的 `SourceSha`、本地状态记下的镜像、环境仓库里以 B 为 tag 的镜像、环境仓库里 B 固定的每个第三方镜像，以及各工作负载每个 Pod 实际运行的镜像，任何一处不符就停下；还要求没有容器从上游拉镜像：每个工作负载与每个没有结束的 Pod 的容器都运行本环境自己的仓库 `colvenna-lake-<环境>/…` 里的镜像（直接拉 `colvenna-lake-release/…` 也不行），只有 `kube-system`、`amazon-guardduty` 里 AWS 自己的镜像例外（记进证据）。已经结束的 Pod（`Succeeded`、`Failed`）不算：它们不再运行，也不会再启动；不符时脚本逐个列出 `<命名空间>/<类型>/<名字>`、容器与镜像，并说明怎样处理（在 B 上重装运行它的那一步，或删掉测试留下的 Pod）。还核对它运行的是 B 固定的 Colvenna Catalog 发布版：镜像是那个 digest、chart 是那个版本。然后按 digest 把镜像复制进发布仓库，第三方镜像从本环境的副本复制（上游可能已删掉那个 digest），Colvenna Catalog 的镜像从本环境的副本复制，chart 从安装这套环境时取它的同一个来源复制（默认 GitHub Container Registry，匿名；给了 `COLVENNA_CATALOG_REGISTRY` 时从那里，应与安装时给的相同），所以不需要 `COLVENNA_RELEASE_REGISTRY`，在固定 digest 的 Go 镜像里构建 colvenna CLI 并作为一个镜像推进 `colvenna-lake-release/colvenna-cli`，最后从 B 读出 chart、用例文档与发布说明的模板，写出 `release/<候选>/manifest.json` 与 `notes.md` 并校验清单（清单里出现 12 位账号 ID 也算不合格）。构建 CLI 之前与写出文件之前，都再核对一次工作区仍是干净的 B。中途停下可以重跑：发布仓库里已有的 tag 必须是同一个 digest，已有的 CLI 镜像必须是同样的二进制。证据在 `deploy/.state/<环境>/release-<候选>/`，执行身份只记在那里。

3. 只提交这两个文件，开一个 PR 合入 main，再给合入后的那个提交打 tag 并推送：

   ```bash
   git switch -c release-<候选> <B>
   git add release/<候选> && git commit -m "release: <候选>"
   # PR 合入后，C 是 main 上与 B 相比只多了 release/<候选>/ 的那个提交：合并提交，或 PR 的这个提交
   git diff --name-only <B> <C>                                      # 只应列出 release/<候选>/ 下的两个文件
   git tag -a <候选> -m "<候选>: images built from <B>" <C>
   git push origin <候选>
   ```

   **注意：** 从 B 到这个 PR 合入之间，main 上不能合入任何别的 PR，这个 PR 也不能 rebase 到更新的 main 上。否则 C 与 B 相比就不止 `release/` 不同，`install.sh --release` 在 tag 上也会拒绝安装，只能从新的 main 重新生成候选。

4. 材料齐备后，按模板的规则补全 `notes.md` 里尖括号的内容，另行提交到 main。

## Colvenna Catalog

colvenna-catalog 是同一系列的独立产品 [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog)：服务端与它的 Helm chart 在它自己的仓库开发、按它自己的流程发布（它的 [docs/development.md](https://github.com/colvenna/colvenna-catalog/blob/v1.0.0-rc.1/docs/development.md) 的“Releasing”一节），本仓库不构建它（#295）。

- **一处固定。** [`deploy/images/colvenna-catalog.json`](../deploy/images/colvenna-catalog.json) 记下用哪个发布版：它的仓库与候选名（现在是 `v1.0.0-rc.1`）、tag 指向的提交（`tag_commit`）、镜像与 chart 构建自的提交（`built_from`），Colvenna Catalog 发布它们的仓库地址（`registry`：GitHub Container Registry 的 `ghcr.io/colvenna`），镜像与 chart 在它下面的仓库名与 digest（`colvenna-catalog`、`charts/colvenna-catalog`，即 `ghcr.io/colvenna/colvenna-catalog`、`ghcr.io/colvenna/charts/colvenna-catalog`），chart 的版本，以及它的服务端用的 schema 版本（`check-health.sh` 核对）。`manifest.py catalog` 逐项校验：`registry` 与两个仓库名只能是这几个值，固定文件里也不能有 AWS 账号 ID。Colvenna Catalog 只发布到 GitHub Container Registry，不发布到 ECR Public。
- **来源。** 从源码安装默认从 `registry` 匿名取：GitHub Container Registry 上公开的包不需要凭证，安装器也不读执行机上的 Docker 或 Helm 登录（crane 与 Helm 只用这次运行自己的凭证文件）。设置 `COLVENNA_CATALOG_REGISTRY=<仓库地址>[/<前缀>]` 时改从那里取：前缀下要有同名的两个仓库与同样的 digest。它用于 catalog 公开之前的候选验收，指向维护者的私有发布仓库，例如 `<账号>.dkr.ecr.<区域>.amazonaws.com/colvenna-catalog-release`。ECR 仓库用当前的 AWS 凭证读，只要拉取权限；别的仓库也匿名读。`manifest.py catalog-source` 解析并校验这个设置：只写地址与前缀，不带 scheme、tag 或 digest；ECR 仓库要在标准区域。
- **从源码安装**：`install.sh` 在开始时就核对来源里有这两个 digest（匿名来源以 OCI 分发接口匿名读清单，`manifest.py catalog-held`；ECR 以 `ecr:BatchGetImage`），没有就什么都不建就停下。`catalog` 一步先运行 `deploy/scripts/catalog/fetch-catalog.sh`。它先按 digest 读镜像的配置，核对标签 `org.opencontainers.image.revision` 是 `built_from`、平台是 `linux/amd64`；再按 digest 拉取 chart（`helm pull oci://…@sha256:…`），核对它的名字、版本与注解 `colvenna-catalog/source-commit`（OCI 配置与包里的 `Chart.yaml` 都要是 `built_from`）。任何一项不符就停下，什么都不复制、不装。按 digest 取，所以来源里同名的 tag 指着别的 digest 时也取不到（`MANIFEST_UNKNOWN`）。都符合之后，用 crane 按 digest 把镜像复制进本环境的 `colvenna-lake-<环境>/colvenna-catalog`，tag 是 Colvenna Catalog 的候选名，并留下 chart 的包。然后 `install-catalog.sh` 以 Colvenna Lake 的取值安装这个 chart。
- **发布候选**：`make-manifest.sh` 把这两个 digest 原样复制进本仓库的发布仓库（`colvenna-lake-release/colvenna-catalog`、`colvenna-lake-release/charts/colvenna-catalog`，tag `<候选>`）：镜像从生成候选的那套环境的副本复制，chart 从上面的来源复制（默认 GitHub Container Registry，或 `COLVENNA_CATALOG_REGISTRY`）。所以按候选安装只要一个发布仓库；清单的 `catalog`（schema 3）记下固定文件里的各项与这两份副本，校验要求它们与 tag 上的固定文件一致。
- **按候选安装**：`check-release.sh` 核对两份副本按 tag 都在 `COLVENNA_RELEASE_REGISTRY` 里、是清单写的 digest，并按 digest 读出它们的配置，像上面一样核对 `built_from`、平台、名字与版本；`catalog` 一步改为 `install-images.sh <候选> catalog`，即 `fetch-catalog.sh --release <候选>`，从副本取，同样核对。
- **AWS 资源。** catalog 的服务端角色与数据角色仍在本仓库的 catalog 栈：Colvenna Catalog 的 CDK 应用不是可以按 digest 取用的发布物。`deploy/aws/test/catalog-roles.test.ts` 把这两个角色与 Colvenna Catalog 自己的 EKS 栈在固定版本上的授权相比（`deploy/aws/test/fixtures/colvenna-catalog-eks-roles.json`）：信任与会话时长相同，它的每项授权都有，多出的只有列出的一项（列举不带斜杠的 `warehouse` 前缀，本仓库 M2 以来的授权）。
- **换一个版本**：改固定文件（版本、两个提交、两个 digest、chart 版本、schema 版本），按新版本的 `deploy/aws/src/eks-stack.ts` 重新得出 `colvenna-catalog-eks-roles.json`（给 `COLVENNA_CATALOG_EKS_TEMPLATE` 时测试直接比对它的合成模板），把文档里指向 Colvenna Catalog 仓库的链接改到新版本（`test_manifest.py` 检查），再在环境上重跑 `install.sh`（或 `--only catalog`）。schema 版本变了时，先看新版本的升级说明。

## 第三方镜像

上游仓库会删掉按 digest 固定的镜像：quay.io 在新的基础镜像上重建同一个 tag 之后，旧的 index 就拉不到了。Debezium Server `3.6.3.Final` 已经这样失效四次（2026-10-20、10-21、10-23、10-24），节点上没有缓存旧镜像的新环境因此 `ImagePullBackOff`（#234）。所以集群里不直接从上游拉第三方镜像：

- **一份清单。** 平台与测试夹具在集群里运行的第三方镜像都按 digest 固定在 [`deploy/images/third-party.tsv`](../deploy/images/third-party.tsv)，每行是名字、范围、上游引用与用途。范围 `platform` 是每次安装都复制的：平台自己运行的，以及适用于只装平台的用例（`docs/uat/cases.md` 的“适用配置”）运行的；`fixtures` 只在 `--fixtures` 时复制。`tests/acceptance/test_third_party.py` 从 `uat-cases.tsv`、`run-uat.sh` 与各脚本推出适用于只装平台的用例与平台的安装步骤用到哪些镜像，核对它们都是 `platform`。脚本、chart 与测试都从这里取，不另写 digest（`test_manifest.py` 检查 `deploy/`、`tests/` 里没有别处按 digest 固定的镜像，短名如 `postgres:16-alpine@sha256:…` 也算，Dockerfile 的 `FROM` 除外）；三处读这份清单的代码（`manifest.py`、`lib/common.sh`、`check-rules.sh` 经 `manifest.py`）都逐行校验，格式不对就报出行号停下。
- **只取节点运行的。** 节点是 x86_64 的 Linux。镜像索引里带 Windows 镜像的（目前只有 MongoDB：两个 Windows 镜像共约 5.9 GB），固定它的 linux/amd64 清单的 digest：那是上游已有的一个清单，按 digest 复制时原样保留，只是不再带别的平台。其余镜像固定整个索引。
- **每套环境有自己的副本。** 安装的 `third-party` 步骤（在 `foundation` 之后、`engines` 之前）把它们按原 digest 复制进本环境的 `colvenna-lake-<环境>/third-party`（基础栈建的仓库，tag 是 `<名字>-<digest 的前 12 位十六进制>`），之后 chart、脚本与测试都运行这份副本（`lib/common.sh` 的 `third_party_image`）。所以一套环境装好以后，换节点、补丁重启都不再依赖上游。
- **从源码安装**（`deploy/scripts/foundation/copy-third-party.sh [--fixtures]`）：给了 `COLVENNA_RELEASE_REGISTRY` 并且发布仓库的 `colvenna-lake-release/third-party` 里有这个 digest 时从发布仓库复制，否则从上游按 digest 复制。上游已删掉、发布仓库里也没有的 digest 只能重新固定（见下）。
- **发布候选**：`make-manifest.sh` 从生成候选的那套环境的副本，把它们复制进 `colvenna-lake-release/third-party`，tag `<候选>-<名字>`；清单的 `third_party`（平台的）与 `fixtures.third_party`（测试夹具的）逐个记下上游引用、发布仓库的仓库名、tag 与 digest，校验要求它们与 tag 上的 `third-party.tsv` 一致。
- **按候选安装**：`check-release.sh` 核对它们都在 `COLVENNA_RELEASE_REGISTRY` 里；`third-party` 步骤改为 `install-images.sh <候选> third-party [--fixtures]`，从发布仓库复制进本环境的仓库，tag 与从源码安装时相同。
- **重新固定一个镜像**：改 `third-party.tsv` 的那一行，再在环境上重跑 `install.sh`（或 `--only third-party`，以及运行它的那一步）。只改测试的提交在一套更早装的环境上跑测试前，先运行 `COLVENNA_ENV=<环境> deploy/scripts/foundation/copy-third-party.sh --fixtures`。

清单里的镜像：

| 名字 | 范围 | 上游 | 谁运行 |
| --- | --- | --- | --- |
| `flink-kubernetes-operator` | platform | `ghcr.io/apache/flink-kubernetes-operator:1.16.1` | Flink Kubernetes Operator（`setup-operators.sh`）。原先只按 tag 固定，现在按 digest |
| `doris-operator` | platform | `docker.io/apache/doris:operator-25.8.0` | Doris Operator（`setup-operators.sh`）。原先只按 tag 固定，现在按 digest |
| `aws-load-balancer-controller` | platform | `public.ecr.aws/eks/aws-load-balancer-controller:v3.6.0` | AWS Load Balancer Controller（`install-lbc.sh`） |
| `prometheus` | platform | `docker.io/prom/prometheus`（v3.5.0） | `colvenna-monitoring` 的 Prometheus；`check-rules.sh` 在执行机上用它的 promtool（从上游拉，不进集群） |
| `grafana` | platform | `docker.io/grafana/grafana`（12.2.0） | `colvenna-monitoring` 的 Grafana |
| `marquez` | platform | `docker.io/marquezproject/marquez:0.51.1` | Marquez 的 API（`install-marquez.sh`） |
| `marquez-web` | platform | `docker.io/marquezproject/marquez-web:0.51.1` | Marquez 的界面 |
| `polaris` | platform | `docker.io/apache/polaris:1.8.0` | Polaris（`install-polaris.sh`） |
| `polaris-admin-tool` | platform | `docker.io/apache/polaris-admin-tool:1.8.0` | Polaris 的初始化 |
| `postgres` | platform | `docker.io/library/postgres:16-alpine` | 在项目 RDS 上执行 psql 的 Pod（catalog、BI、Marquez、Polaris 的库；测试源） |
| `mysql` | platform | `docker.io/library/mysql:8.4` | 连 Doris 与测试 MySQL 的 mysql 客户端 Pod |
| `curl` | platform | `docker.io/curlimages/curl:8.16.0` | 集群内的 HTTP 检查（`verify-catalog.sh`、测试） |
| `aws-cli` | platform | `docker.io/amazon/aws-cli:2.31.18` | 测试里的 S3、STS 探测；UAT-CAT-05 也适用于只装平台 |
| `mongo` | platform | `docker.io/library/mongo`（7.0.45，7.0 索引 `sha256:9650758f…` 里的 linux/amd64 清单） | 用户的 MongoDB（`dev-mongo.sh`）；UAT-SRC-02 也适用于只装平台 |
| `strimzi-operator` | fixtures | `quay.io/strimzi/operator:1.2.0` | Strimzi operator，以及开发用 Kafka 的 entity operator（`install-strimzi.sh`）。原先只按 tag 固定，现在按 digest |
| `strimzi-kafka` | fixtures | `quay.io/strimzi/kafka:1.2.0-kafka-4.3.1` | 开发用 Kafka 的 broker（`dev-kafka.sh` 写进 Kafka 资源的 `spec.kafka.image`）与测试里的 Kafka 客户端 |
| `airflow` | fixtures | `docker.io/apache/airflow`（2.10.5） | 用户的编排器（`run-tasks.sh`） |
| `debezium-server` | fixtures | `quay.io/debezium/server:3.6.3.Final` | 用户的采集器（`run-kafka-cdc.sh`）。2026-10-24 重新固定为当天的 index |
| `trino` | fixtures | `docker.io/trinodb/trino:484` | 用户的 Trino（`install-trino.sh`） |
| `busybox` | fixtures | `docker.io/library/busybox`（1.37.0） | 性能测试的时钟探针 |

不在清单里的，以及原因：

| 镜像 | 原因 |
| --- | --- |
| EKS 托管插件：VPC CNI（`aws-node` 及其 network policy agent）、kube-proxy、CoreDNS、EBS CSI 驱动与它的边车 | AWS 按集群版本从它自己的区域 ECR 安装与更新，不由本项目固定；`make-manifest.sh` 只把它们记进证据 |
| GuardDuty 运行时监控的 agent（`amazon-guardduty` 命名空间） | 账号开了 GuardDuty 时由 AWS 安装与更新，同上 |
| Dockerfile 的 `FROM` 基础镜像：`flink`、`eclipse-temurin`、`golang`、`gcr.io/distroless/static-debian12`、`apache/superset`、`apache/doris`（FE、BE 的官方镜像）、`apache/spark`（[Apache Spark](https://spark.apache.org/)） | 构建进本项目自己的镜像，集群不直接运行；发布候选保存的是构建好的镜像，按候选安装不需要它们。从源码构建时仍从上游拉，某个基础镜像的 digest 失效时，改 `FROM` 重新固定并重新构建 |
| 执行机上的构建与检查工具：`golang`、`golangci-lint`（`colvenna-cli`、`colvenna-console` 的 `check.sh`），`tests/upstream/` 在本机 Docker 里起的 MySQL 与 PostgreSQL | 只在执行机上运行，不进集群 |
| Strimzi operator 能用、开发用 Kafka 不用的镜像：Kafka Bridge、Kafka Connect、MirrorMaker 2、Kafka Exporter、Cruise Control、Kaniko、Buildah | 开发用 Kafka 不部署这些组件，留 chart 的默认值 |

> 注：本文中的 issue 与 PR 编号（如 #248）是本项目早期私有仓库的编号，本仓库里没有对应的页面。
