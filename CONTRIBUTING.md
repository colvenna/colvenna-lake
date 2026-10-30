# Contributing

This repository contains Colvenna Lake: the declarative `colvenna-operator` (Java) and the Flink jobs it runs, `colvenna-jobs` (Java 11); the `colvenna` CLI `colvenna-cli` and the management console `colvenna-console` (Go); the BI extension for Apache Superset, `colvenna-bi` (Python); the deployment in `deploy/` (the install and uninstall scripts, the AWS CDK stacks in TypeScript, the Helm charts, the image recipes and the per-component scripts); the acceptance, performance and upstream tests in `tests/`; the release records in `release/`; the manuals, designs, UAT cases and research in `docs/`; and the brand, architecture diagram and overview video in `assets/`. The [README](README.md) is the overview; the documentation is written in Chinese. The control plane, colvenna-catalog, is [Colvenna Catalog](https://github.com/colvenna/colvenna-catalog), developed in its own repository: a change to the server or its chart goes there, and Colvenna Lake takes it by pinning a new release in [`deploy/images/colvenna-catalog.json`](deploy/images/colvenna-catalog.json).

1. Open an issue that names the part of the design (HLD or LLD section) it concerns. Make the change on its own branch and open a focused pull request that links the issue and says how you validated it.
2. Keep the README, the [install manual](docs/manual/install.md), the [user manual](docs/manual/user.md), the designs in `docs/design/`, the [UAT cases](docs/uat/cases.md) and the release records aligned with the code. The CRDs in `deploy/helm/colvenna-operator/crds/` are generated from the Java types, and a test fails when the checked-in copies differ. Third-party images are pinned in one place, [`deploy/images/third-party.tsv`](deploy/images/third-party.tsv); a new or re-pinned one also updates [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
3. Cite an official source (documentation, source code at a fixed commit, AWS documentation) for any new fact about an upstream system, or state it as a design decision. Separate implemented behavior, proposed behavior and validation results, and do not describe an unverified interface as an existing capability.
4. Do not include credentials, tokens, AWS account IDs, hosted zone IDs, domains of a deployment, ARNs, internal addresses, or the role or instance that ran an installation, in files, issues, pull requests or commit messages. Use placeholders (`<ACCOUNT_ID>`, `<ARN>`, `example.com`) and synthetic values (`123456789012`); an environment's own values live only in its uncommitted local settings (`deploy/.state/<env>/`). Two pre-commit gates enforce this, and `pre-commit install` installs both with the commit-message hook:
   - **no deployment values** ([`deploy/scripts/redaction/`](deploy/scripts/redaction/redaction.py)) looks for the values of your local settings, the values you list in `deploy/.state/redaction-values`, and the maintainers' values stored as hashes, and refuses any ARN or ECR registry of a non-synthetic account, any internal IPv4 address, and any public IPv4 address (use the documentation ranges 192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24, or a placeholder) except where [`public-addresses.tsv`](deploy/scripts/redaction/public-addresses.tsv) allows it, with its reason, in files and in commit messages;
   - **no old name** ([`deploy/scripts/rename/`](deploy/scripts/rename/old_name.py)) refuses the product's former name and identifiers anywhere except where `deploy/scripts/rename/exceptions.tsv` allows them, each with its reason.

Releases make no backward-compatibility commitment: a new version does not guarantee compatibility with, or rollback to, an older one; the [install manual (section 7)](docs/manual/install.md#7-升级) describes how each upgrade is done.

## Continuous integration

Since 2026-10-30, GitHub Actions is the CI of record for this repository. A pull request merges only when every job of [`.github/workflows/lint.yml`](.github/workflows/lint.yml) has succeeded on its exact head commit; a new commit or a rebase needs a new run. The local checks below supplement it: run them before you push, but they do not replace a successful run on GitHub. A waiver, merging on local results, is allowed only when Actions cannot run at all (for example, the runners are unavailable), not when a job fails. The pull request then says why, and records a local run of every job on that exact commit.

## Local validation

```bash
pip install pre-commit==4.6.0
pre-commit run --all-files
```

runs the file checks, markdownlint, actionlint and the two gates above. The CI workflow [`.github/workflows/lint.yml`](.github/workflows/lint.yml) defines these jobs; run the ones for what you changed:

- **pre-commit** (Python 3.12): `pre-commit run --all-files --show-diff-on-failure`
- **operator** (JDK 21, Maven):

  ```bash
  mvn -B -f colvenna-operator/pom.xml clean verify
  mvn -B -f colvenna-jobs/pom.xml clean verify
  ```

- **cli**, **console** and **bi** (Docker; the Go checks run in the pinned Go and golangci-lint images, the BI check builds the image and runs its tests in it):

  ```bash
  bash colvenna-cli/check.sh
  bash colvenna-console/check.sh
  bash colvenna-bi/check.sh
  ```

- **deploy** (Node.js 22, Python 3, ShellCheck, Helm, Docker):

  ```bash
  (cd deploy/aws && npm ci && npm run check && npm test)
  shellcheck -x deploy/*.sh deploy/scripts/*/*.sh
  python3 -m unittest discover -s deploy/scripts/release -p 'test_*.py'
  python3 -m unittest discover -s deploy/scripts/redaction -p 'test_*.py'
  python3 -m unittest discover -s deploy/scripts/rename -p 'test_*.py'
  bash deploy/scripts/monitoring/check-rules.sh
  ```

  and `helm lint` of each chart in `deploy/helm/` with the placeholder values the workflow gives.
- **acceptance** (JDK 21, Maven, Python 3, ShellCheck, Helm): the tests' own code, fixtures and upstream checks, not the tests themselves:

  ```bash
  mvn -B -f tests/acceptance/catalog/flink/pom.xml clean verify
  mvn -B -f tests/acceptance/catalog/spark/pom.xml clean verify
  mvn -B -f tests/acceptance/catalog/migration/pom.xml clean verify
  mvn -B -f tests/performance/tools/pom.xml clean verify
  python3 -m unittest discover -s tests/acceptance -p 'test_*.py'
  python3 -m unittest discover -s tests/performance -p 'test_*.py'
  shellcheck -x tests/acceptance/*.sh tests/acceptance/*/*.sh tests/fixtures/*/*.sh tests/upstream/*/*.sh tests/performance/*.sh
  helm lint tests/fixtures/kafka/colvenna-dev-kafka
  helm lint tests/fixtures/kafka/colvenna-dev-kafka --set storage.type=ephemeral
  ```

- **go-lint** (Docker): `bash assets/architecture/check-go.sh`

The Java builds check formatting with Spotless (google-java-format, AOSP style) and compile with Error Prone; `mvn -f <module>/pom.xml spotless:apply` formats a module. Judge a build by its own result (`BUILD SUCCESS`), not by filtered quiet output.

These checks need no AWS account. Behavior on AWS needs a deployed environment: install one with the [install manual](docs/manual/install.md) (it creates billable resources; see the [cost estimate](docs/manual/cost.md)) and run the [UAT cases](docs/uat/cases.md) (`tests/acceptance/run-uat.sh`); performance is measured as [docs/uat/performance.md](docs/uat/performance.md) describes.

## Architecture diagram

When the architecture changes, update the Mermaid source [`assets/architecture/colvenna-lake-architecture.mmd`](assets/architecture/colvenna-lake-architecture.mmd), regenerate the static SVG with [`render_architecture.go`](assets/architecture/render_architecture.go) and its PNG with `rsvg-convert` (the commands are at the top of the generator), and update the interactive [diagram](docs/design/diagrams/colvenna-lake-architecture.html) and the text version in the [High-Level Design](docs/design/hld-realtime-lakehouse.md), so that all of them describe the same system.

For confidential security concerns, follow [SECURITY.md](SECURITY.md).
