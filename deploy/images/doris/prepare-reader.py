#!/usr/bin/env python3
"""Prepare a paired, experimental SDK 2 overlay without running amd64 images."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


HERE = Path(__file__).resolve().parent
SDK_PREFIXES = (
    "org/apache/paimon/",
    "paimon-codegen/",
    "paimon-plugin-s3/",
    "META-INF/maven/org.apache.paimon/",
)
PLUGIN_ROOTS = ("paimon-codegen/", "paimon-plugin-s3/")
OLD_FE_JARS = (
    "paimon-api-1.4.2.jar",
    "paimon-codegen-loader-1.4.2.jar",
    "paimon-common-1.4.2.jar",
    "paimon-core-1.4.2.jar",
    "paimon-format-1.4.2.jar",
    "paimon-jindo-1.4.2.jar",
    "paimon-s3-1.4.2.jar",
    "paimon-shade-caffeine-2-2.9.3-0.8.0.jar",
    "paimon-shade-guava-30-30.1.1-jre-0.8.0.jar",
    "paimon-shade-jackson-2-2.14.2-0.8.0.jar",
)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked(path, expected):
    if sha256(path) != expected:
        raise ValueError(f"Artifact checksum mismatch: {path.name}")


def fetch(record, directory):
    path = directory / record["name"]
    if not path.exists():
        # Over HTTPS only: never file:, ftp: or plain http:, whatever the lock file says.
        if not record["url"].startswith("https://"):
            raise ValueError(f"Artifact URL is not https: {record['url']}")
        partial = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(record["url"], timeout=120) as response:  # nosec B310
            with partial.open("wb") as stream:
                shutil.copyfileobj(response, stream)
        checked(partial, record["sha256"])
        partial.replace(path)
    checked(path, record["sha256"])
    return path


def write_entry(archive, name, data):
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


def excluded(name):
    upper = name.upper()
    return (
        name.endswith("/")
        or name.endswith("module-info.class")
        or upper == "META-INF/MANIFEST.MF"
        or (upper.startswith("META-INF/") and upper.endswith((".SF", ".RSA", ".DSA")))
    )


def merge_connector(stock, sdk_jars, classes, output):
    """Replace the SDK and Doris connector; retain stock non-SDK dependencies."""
    archives = [zipfile.ZipFile(path) for path in [stock, *sdk_jars]]
    entries = {}
    services = {}
    try:
        for index, archive in enumerate(archives):
            for name in archive.namelist():
                if excluded(name) or (index == 0 and name.startswith(SDK_PREFIXES)):
                    continue
                if name.startswith("META-INF/services/"):
                    providers = {
                        line.split("#", 1)[0].strip()
                        for line in archive.read(name).decode().splitlines()
                    } - {""}
                    if index == 0:
                        providers = {
                            value for value in providers
                            if not value.startswith("org.apache.paimon.")
                        }
                    services.setdefault(name, set()).update(providers)
                    continue
                if index == 0 and name.startswith("org/apache/doris/paimon/"):
                    continue
                if index > 0 and name.startswith(("META-INF/LICENSE", "META-INF/NOTICE")):
                    name_in_output = f"META-INF/sdk2-licenses/{sdk_jars[index - 1].stem}/{name}"
                else:
                    name_in_output = name
                entries[name_in_output] = (archive, name)
        with zipfile.ZipFile(output, "w") as result:
            write_entry(result, "META-INF/MANIFEST.MF",
                        b"Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n")
            # PluginLoader resolves these directory URLs with getResource().
            # Class entries alone do not make a ZIP directory discoverable.
            for name in PLUGIN_ROOTS:
                if any(entry.startswith(name) for entry in entries):
                    write_entry(result, name, b"")
            for name, (archive, original) in sorted(entries.items()):
                write_entry(result, name, archive.read(original))
            for name, providers in sorted(services.items()):
                if providers:
                    write_entry(result, name, ("\n".join(sorted(providers)) + "\n").encode())
            for path in sorted(classes.rglob("*.class")):
                write_entry(result, path.relative_to(classes).as_posix(), path.read_bytes())
    finally:
        for archive in archives:
            archive.close()


def audit_connector(path, sdk_jars):
    with zipfile.ZipFile(path) as result:
        names = set(result.namelist())
        for root in PLUGIN_ROOTS:
            if root not in names:
                raise ValueError(f"SDK plugin directory missing: {root}")
        sdk_classes = 0
        for sdk in sdk_jars:
            with zipfile.ZipFile(sdk) as source:
                for name in source.namelist():
                    if (
                        name.startswith(SDK_PREFIXES)
                        and name.endswith(".class") and not excluded(name)
                    ):
                        if name not in names or result.read(name) != source.read(name):
                            raise ValueError(f"SDK class missing or changed: {name}")
                        sdk_classes += 1
        for name in names:
            if name.startswith("META-INF/maven/org.apache.paimon/") and name.endswith("pom.properties"):
                versions = [
                    line.split("=", 1)[1].strip()
                    for line in result.read(name).decode().splitlines()
                    if line.startswith("version=")
                ]
                if versions != ["2.0.0"]:
                    raise ValueError(f"Unexpected embedded Paimon version: {name}")
        if "org/apache/paimon/jindo/JindoLoader.class" in names:
            raise ValueError("Old Jindo reader survived replacement")
        return {
            "sdk_class_entries_checked": sdk_classes,
            "old_jindo_removed": True,
            "plugin_directories_present": list(PLUGIN_ROOTS),
        }


def audit_shared_udf(path, stock=None):
    """Reject SDK resources in the parent loader and preserve other UDF bytes."""
    with zipfile.ZipFile(path) as result:
        names = set(result.namelist())
        if any(name.startswith(SDK_PREFIXES) for name in names):
            raise ValueError("Shared UDF still contains Paimon SDK resources")
        for name in names:
            if name.startswith("META-INF/services/") and not name.endswith("/"):
                providers = {
                    line.split("#", 1)[0].strip()
                    for line in result.read(name).decode().splitlines()
                }
                if any(value.startswith("org.apache.paimon.") for value in providers):
                    raise ValueError("Shared UDF still declares a Paimon provider")
        if stock is None:
            return
        with zipfile.ZipFile(stock) as source:
            expected = {
                name for name in source.namelist()
                if not name.startswith(SDK_PREFIXES)
            }
            if names != expected:
                raise ValueError("Shared UDF non-SDK inventory changed")
            for name in expected:
                if result.read(name) != source.read(name):
                    raise ValueError(f"Shared UDF non-SDK entry changed: {name}")
            return {
                "non_sdk_entries_byte_checked": len(expected),
                "sdk_entries_removed": len(set(source.namelist()) - expected),
                "parent_loader_paimon_resources_absent": True,
            }


def prepare_shared_udf(stock, output):
    """Remove the pinned stock UDF's old codegen without changing its UDF API."""
    with zipfile.ZipFile(stock) as source, zipfile.ZipFile(output, "w") as result:
        for name in sorted(source.namelist()):
            if not name.startswith(SDK_PREFIXES):
                write_entry(result, name, source.read(name))
    return audit_shared_udf(output, stock)


def preparation_hashes():
    return {
        name: sha256(HERE / name)
        for name in ("prepare-reader.py", "reader-lock.json")
    }


def verify_output(directory):
    receipt = json.loads((directory / "receipt.json").read_text())
    if (
        receipt["classification"] != "PAIRED_SDK2_ARTIFACT_PREPARED_LIVE_NOT_RUN"
        or receipt["preparation_source_sha256"] != preparation_hashes()
    ):
        raise ValueError("Prepared overlay does not match current preparation source")
    expected = receipt["output_sha256"]
    actual = {
        path.relative_to(directory).as_posix(): sha256(path)
        for path in sorted(directory.rglob("*.jar"))
    }
    if actual != expected:
        raise ValueError("Prepared overlay files differ from their receipt")
    expected_fe = set(OLD_FE_JARS) | {
        record["name"] for record in json.loads(
            (HERE / "reader-lock.json").read_text()
        )["sdk"]
    }
    if {path.name for path in (directory / "fe").iterdir()} != expected_fe:
        raise ValueError("Prepared FE library inventory differs")
    for folder, name in (
        ("be", "paimon-connector-jar-with-dependencies.jar"),
        ("be-udf", "java-udf-jar-with-dependencies.jar"),
    ):
        if {path.name for path in (directory / folder).iterdir()} != {name}:
            raise ValueError(f"Prepared {folder} library inventory differs")
    audit_shared_udf(directory / "be-udf/java-udf-jar-with-dependencies.jar")
    for name in OLD_FE_JARS:
        with zipfile.ZipFile(directory / "fe" / name) as archive:
            if archive.namelist() != ["META-INF/MANIFEST.MF"]:
                raise ValueError("An old FE SDK file still contains classes")
    lock = json.loads((HERE / "reader-lock.json").read_text())
    sdk = []
    for record in lock["sdk"]:
        path = directory / "fe" / record["name"]
        checked(path, record["sha256"])
        sdk.append(path)
    audit_connector(directory / "be/paimon-connector-jar-with-dependencies.jar", sdk)
    print("PAIRED_SDK2_ARTIFACT_VERIFIED_LIVE_NOT_RUN")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-output", type=Path)
    parser.add_argument("--stock-be-connector", type=Path)
    parser.add_argument("--stock-preload", type=Path)
    parser.add_argument("--stock-udf", type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.verify_output:
        verify_output(args.verify_output)
        return
    if any(value is None for value in (
        args.stock_be_connector, args.stock_preload, args.stock_udf,
        args.cache, args.output,
    )):
        parser.error("Preparation requires all stock inputs, --cache and --output")
    lock = json.loads((HERE / "reader-lock.json").read_text())
    for key, path in (
        ("connector", args.stock_be_connector),
        ("preload", args.stock_preload),
        ("udf", args.stock_udf),
    ):
        checked(path, lock["stock_be_sha256"][key])
    if args.output.exists():
        raise ValueError("Output must be a new directory")
    args.cache.mkdir(parents=True, exist_ok=True)
    sdk_jars = [fetch(record, args.cache) for record in lock["sdk"]]
    sources = [fetch(record, args.cache) for record in lock["doris_sources"]]
    # Preparation is local only; no repository deployment or image reference changes.
    with tempfile.TemporaryDirectory(prefix="doris-reader-", dir=args.output.parent) as temp:
        stage = Path(temp)
        classes = stage / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(str(path) for path in [
            *sdk_jars, args.stock_be_connector, args.stock_preload, args.stock_udf,
        ])
        subprocess.run([
            "javac", "-proc:none", "--release", "11", "-cp", classpath,
            "-d", str(classes), *(str(path) for path in sources),
        ], check=True)
        fe = stage / "reader/fe"
        be = stage / "reader/be"
        udf = stage / "reader/be-udf"
        fe.mkdir(parents=True)
        be.mkdir(parents=True)
        udf.mkdir(parents=True)
        for name in OLD_FE_JARS:
            with zipfile.ZipFile(fe / name, "w") as empty:
                write_entry(empty, "META-INF/MANIFEST.MF",
                            b"Manifest-Version: 1.0\r\nColvenna-Lake-Removed-SDK: 1.4.2\r\n\r\n")
        for sdk in sdk_jars:
            shutil.copyfile(sdk, fe / sdk.name)
        connector = be / "paimon-connector-jar-with-dependencies.jar"
        merge_connector(args.stock_be_connector, sdk_jars, classes, connector)
        audit = audit_connector(connector, sdk_jars)
        udf_audit = prepare_shared_udf(
            args.stock_udf, udf / "java-udf-jar-with-dependencies.jar")
        receipt = {
            "classification": "PAIRED_SDK2_ARTIFACT_PREPARED_LIVE_NOT_RUN",
            "doris_commit": lock["doris_commit"],
            "sdk_version": "2.0.0",
            "preparation_source_sha256": preparation_hashes(),
            "stock_inputs_sha256": lock["stock_be_sha256"],
            "compile": "PASS",
            "compile_is_runtime_compatibility": False,
            "non_sdk_dependencies": "retained from pinned stock connector",
            "jindo": "removed; AWS S3/JDBC candidate only",
            "be_audit": audit,
            "be_shared_udf_audit": udf_audit,
            "output_sha256": {
                path.relative_to(stage / "reader").as_posix(): sha256(path)
                for path in sorted((stage / "reader").rglob("*.jar"))
            },
        }
        (stage / "reader/receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        shutil.move(str(stage / "reader"), args.output)
    print(json.dumps({"classification": receipt["classification"], "output": str(args.output)}))


if __name__ == "__main__":
    main()
