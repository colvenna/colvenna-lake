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

Since 2026-10-30, GitHub Actions is the CI of record for this repository. A pull request merges only when every job of [`.github/workflows/lint.yml`](.github/workflows/lint.yml) and of the [secret scan workflow](.github/workflows/ggshield.yml) has succeeded on its exact head commit; a new commit or a rebase needs a new run. The local checks below supplement it: run them before you push, but they do not replace a successful run on GitHub. A waiver, merging on local results, is allowed only when Actions cannot run at all (for example, the runners are unavailable), not when a job fails. The pull request then says why, and records a local run of every job on that exact commit.

A ruleset on `main` enforces this (#44). A change reaches `main` only through a pull request, on a branch up to date with `main`, whose required checks have passed: `pre-commit`, `operator`, `cli`, `console`, `bi`, `deploy`, `acceptance`, `go-lint` (the jobs of `lint.yml`) and `ggshield` (the secret scan). The ruleset also blocks force pushes to `main` and its deletion. It has no bypass, so a waiver also needs the repository owner to change the ruleset for that merge.

## Local validation

```bash
pip install pre-commit==4.6.0
pre-commit run --all-files
```

runs the file checks, markdownlint, actionlint, the two gates above and the ggshield hook, which scans the staged changes ([Secret scanning](#secret-scanning)). Without ggshield, skip that hook with `SKIP=ggshield`, as CI does. The CI workflow [`.github/workflows/lint.yml`](.github/workflows/lint.yml) defines these jobs; run the ones for what you changed:

- **pre-commit** (Python 3.12): `SKIP=ggshield pre-commit run --all-files --show-diff-on-failure`
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
  bash deploy/scripts/foundation/install-crane.sh   # the pinned crane, into deploy/.tools, for test_promote.py
  python3 -m unittest discover -s deploy/scripts/release -p 'test_*.py'
  python3 -m unittest discover -s deploy/scripts/redaction -p 'test_*.py'
  python3 -m unittest discover -s deploy/scripts/rename -p 'test_*.py'
  python3 -m unittest discover -s deploy/scripts/ggshield -p 'test_*.py'   # the publication scan's offline parts, no ggshield
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

The [secret scan workflow](.github/workflows/ggshield.yml) has one job:

- **ggshield** (the hash-locked ggshield and a GitGuardian API key, [Secret scanning](#secret-scanning)): `ggshield secret scan commit-range origin/main..HEAD` scans the commits of your branch, as the job does for a pull request.

The Java builds check formatting with Spotless (google-java-format, AOSP style) and compile with Error Prone; `mvn -f <module>/pom.xml spotless:apply` formats a module. Judge a build by its own result (`BUILD SUCCESS`), not by filtered quiet output.

These checks need no AWS account. Behavior on AWS needs a deployed environment: install one with the [install manual](docs/manual/install.md) (it creates billable resources; see the [cost estimate](docs/manual/cost.md)) and run the [UAT cases](docs/uat/cases.md) (`tests/acceptance/run-uat.sh`); performance is measured as [docs/uat/performance.md](docs/uat/performance.md) describes.

## Secret scanning

Nothing is made public before [ggshield](https://docs.gitguardian.com/ggshield-docs/getting-started), GitGuardian's command-line scanner, finds 0 incidents in it with this repository's [`.gitguardian.yaml`](.gitguardian.yaml) (#44). This holds for the repository's history, its published artifacts and its GitHub text. The repository is public, so a push publishes its commits. The local hooks below are a best-effort check before that, and CI scans each push again afterwards. The publication scan is the gate for a release and for making anything public. ggshield is installed from a hash-locked file, for CPython 3.12 on Linux (x86_64 or arm64):

```bash
python3 -m venv ~/.venvs/colvenna-ggshield
~/.venvs/colvenna-ggshield/bin/python -m pip install --require-hashes --only-binary=:all: \
  -r deploy/scripts/ggshield/requirements.lock
export PATH="$HOME/.venvs/colvenna-ggshield/bin:$PATH" GITGUARDIAN_API_KEY=...  # or: ggshield auth login
```

- **Before a release, or before the repository is made public**, a maintainer runs the publication scan. For a release, its summary goes into the candidate's release notes, in the secret scan row of section 5 ([template](release/notes-template.md)), before the verdict. Check the remaining API quota first with `ggshield quota`, because a full scan uses part of it.

  ```bash
  python3 deploy/scripts/ggshield/ggshield_scan.py --output <private directory> [--path <files to publish>]
  ```

  It scans everything the public can fetch:
  - an anonymous mirror clone (every branch, tag and `refs/pull/*`), plus each pull-request head that a force push replaced and GitHub still serves;
  - the GitHub Release assets and workflow artifacts as published, plus any `--path`, with every archive opened (zip, wheel, jar, tar, gzip);
  - the GitHub text: issues, pull requests, comments, reviews, release notes and commit comments, and the wiki when it is enabled. An enabled wiki that cannot be cloned makes the scan fail.

  The release images stay in the maintainers' private release registry, which the public cannot read, so the scan does not cover them. Colvenna Catalog scans the image and chart that it publishes on GitHub Container Registry.

  It uses the `.gitguardian.yaml` of `main`; `--branch` selects another branch. It refuses any ggshield version other than the one in the lock file, because its checks read that version's messages.

  Some files would be left out by ggshield without notice, so they are prepared first:
  - A text over ggshield's 1 MiB limit is scanned in overlapping parts.
  - ggshield drops files by extension (for example `.svg` and `.pdf`), so a text under such an extension is scanned under a `.txt` name.
  - ggshield's default exclusions (for example `vendor/`, `node_modules/`, `.venv/`, `*.mdx` and `*.sops`) apply to every scan, and its `--ignore-default-excludes` has no effect in 1.56.1. Unpacked files that they match are scanned under a neutral name. Any path in the history that they match makes the scan fail, and so does an output directory under such a path.

  It exits 0 only when every scan is complete and finds 0 incidents. ggshield 1.56.1 exits 0 even when part of a scan fails, so a "Scanning failed" message or an error in its report also makes the scan fail. The summary counts the binary files that ggshield cannot decode or drops by extension. Any other file that ggshield skips makes the scan fail.

  The reports in `--output` contain the matched values, so keep that directory private. The summary has no values; it records the date, the scanned commit, the ggshield version, the quota and each finding's location.
- **Each commit and each push (best effort):** `pre-commit install` installs the pre-commit, commit-msg and pre-push hooks, the [ggshield hooks](.pre-commit-config.yaml) among them. At a push, the file checks and the two gates also run on the pushed files. The ggshield hooks use the `ggshield` on `PATH` and refuse any version other than the one in the lock file.
  - The pre-commit hook scans the staged changes.
  - The pre-push hook scans the commits about to be published. It covers at most `max_commits_for_hook` commits of a push (1000, set in `.gitguardian.yaml`), and ggshield lets a push through with only a warning when it cannot work out the commit range. When one push updates several refs (`git push --all`, or several branches or tags), pre-commit hands ggshield only the first, so push them one at a time.
  - Without an API key, they stop with ggshield's error.
  - `SKIP=ggshield,ggshield-push` or `git push --no-verify` skips them. The CI job and the publication scan still apply.
- **Each push and pull request:** the [secret scan workflow](.github/workflows/ggshield.yml) scans the new commits with the `GITGUARDIAN_API_KEY` Actions secret, which the repository owner sets. A manual run scans the whole history. Pull requests from forks get no secrets, so for them the job only notes this. A maintainer scans such a change before merging it.
- **A finding:**
  - A real credential is revoked or rotated first, then the history is reviewed. It is never ignored.
  - Any other finding (a test value, a public example, a false positive) is added to `.gitguardian.yaml` by its ignore SHA, with a name that gives the file and the reason. The addition is reviewed in its pull request.
  - No path or detector is ignored.
  - A tracked file's name should avoid credential words such as "secret", "token" or "password". A build manifest that pairs a path with its SHA-256 reads to ggshield as a secret: Colvenna Isle's did ([colvenna/colvenna-isle#25](https://github.com/colvenna/colvenna-isle/issues/25)). An ignore entry would be needed again each time the file changed, so rename the file instead.
- **Deployment values:** ggshield does not look for AWS account IDs, ARNs, domains or internal addresses. The no-deployment-values gate checks files and commit messages as they are committed, but not the GitHub text or the artifacts. So a maintainer also runs the gate over those, as the scan fetched them, and reviews each hit:

  ```bash
  find <private directory>/unpacked -type f -exec python3 deploy/scripts/redaction/redaction.py {} +
  ```

  The maintainers' accounts must appear 0 times. Third-party packages inside workflow artifacts can hold example ARNs and addresses, which are expected.

## Architecture diagram

When the architecture changes, update the Mermaid source [`assets/architecture/colvenna-lake-architecture.mmd`](assets/architecture/colvenna-lake-architecture.mmd), regenerate the static SVG with [`render_architecture.go`](assets/architecture/render_architecture.go) and its PNG with `rsvg-convert` (the commands are at the top of the generator), and update the interactive [diagram](docs/design/diagrams/colvenna-lake-architecture.html) and the text version in the [High-Level Design](docs/design/hld-realtime-lakehouse.md), so that all of them describe the same system.

For confidential security concerns, follow [SECURITY.md](SECURITY.md).
