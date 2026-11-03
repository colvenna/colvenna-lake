"""A stub OCI registry for the offline tests of promote.sh, as Colvenna Catalog's: the distribution API's
endpoints that crane digest and crane tag use (/v2/, manifests read and written, blobs read), held in
memory and served over plain HTTP on 127.0.0.1, a loopback registry promote.sh accepts for a rehearsal.

  immutable  a tag is never moved to another digest (the release registry's ECR repositories are
             IMMUTABLE, deploy/aws/src/release-stack.ts); writing the digest it holds again is fine
  denied     every read but /v2/ answers 403 DENIED: a registry that will not let these credentials in

Every request is logged (requests: method, path), so a test can say what was read and written.
seed_image puts an image in without the API."""
import gzip
import hashlib
import io
import json
import re
import tarfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def digest_of(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


class Registry:
    def __init__(self, immutable=True, denied=False):
        self.immutable, self.denied = immutable, denied
        self.blobs = {}
        self.manifests = {}  # repository -> {digest: (media type, bytes)}
        self.tags = {}  # repository -> {tag: digest}
        self.requests = []
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def host(self):
        return f"127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def seed_blob(self, data):
        digest = digest_of(data)
        self.blobs[digest] = data
        return digest

    def seed_image(self, repository, tag, layer=b"layer"):
        """A small image (one layer, a gzipped tarball holding one file of these bytes) under the tag;
        its manifest's digest."""
        uncompressed = io.BytesIO()
        with tarfile.open(fileobj=uncompressed, mode="w") as archive:
            info = tarfile.TarInfo("layer")
            info.size = len(layer)
            archive.addfile(info, io.BytesIO(layer))
        compressed = gzip.compress(uncompressed.getvalue(), mtime=0)
        config = json.dumps({"architecture": "amd64", "os": "linux",
                             "rootfs": {"type": "layers", "diff_ids": [digest_of(uncompressed.getvalue())]}}).encode()
        manifest = json.dumps({
            "schemaVersion": 2, "mediaType": OCI_MANIFEST,
            "config": {"mediaType": OCI_CONFIG, "digest": self.seed_blob(config), "size": len(config)},
            "layers": [{"mediaType": OCI_LAYER, "digest": self.seed_blob(compressed), "size": len(compressed)}],
        }).encode()
        digest = digest_of(manifest)
        self.manifests.setdefault(repository, {})[digest] = (OCI_MANIFEST, manifest)
        if tag:
            self.tags.setdefault(repository, {})[tag] = digest
        return digest

    def tag(self, repository, tag):
        """The digest a tag holds, or None."""
        return self.tags.get(repository, {}).get(tag)

    def writes(self):
        return [r for r in self.requests if r[0] in ("POST", "PUT", "PATCH", "DELETE")]


def _handler(registry):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def body(self):
            return self.rfile.read(int(self.headers.get("Content-Length") or 0))

        def answer(self, status, body=b"", headers=None):
            self.send_response(status)
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def error(self, status, code, message):
            self.answer(status, json.dumps({"errors": [{"code": code, "message": message}]}).encode(),
                        {"Content-Type": "application/json"})

        def handle_one(self):
            path = urlsplit(self.path).path
            data = self.body() if self.command in ("POST", "PUT", "PATCH") else b""
            with registry.lock:
                registry.requests.append((self.command, path))
            if path in ("/v2", "/v2/"):
                return self.answer(200, b"{}")
            if registry.denied and self.command in ("GET", "HEAD"):
                return self.error(403, "DENIED", "requested access to the resource is denied")
            found = re.fullmatch(r"/v2/(.+)/blobs/(sha256:[0-9a-f]{64})", path)
            if found and self.command in ("GET", "HEAD"):
                blob = registry.blobs.get(found[2])
                if blob is None:
                    return self.error(404, "BLOB_UNKNOWN", found[2])
                return self.answer(200, blob, {"Content-Type": "application/octet-stream",
                                               "Docker-Content-Digest": found[2]})
            found = re.fullmatch(r"/v2/(.+)/manifests/([^/]+)", path)
            if found:
                return self.manifest(found[1], found[2], data)
            return self.error(404, "UNSUPPORTED", f"{self.command} {path}")

        def manifest(self, repository, reference, data):
            held = registry.manifests.get(repository, {})
            if self.command in ("GET", "HEAD"):
                digest = reference if DIGEST.fullmatch(reference) else registry.tags.get(repository, {}).get(reference)
                if digest not in held:
                    return self.error(404, "MANIFEST_UNKNOWN", f"{repository}:{reference}")
                media_type, body = held[digest]
                return self.answer(200, body, {"Content-Type": media_type, "Docker-Content-Digest": digest})
            if self.command != "PUT":
                return self.error(405, "UNSUPPORTED", self.command)
            digest = digest_of(data)
            if DIGEST.fullmatch(reference) and reference != digest:
                return self.error(400, "DIGEST_INVALID", reference)
            try:
                parsed = json.loads(data)
            except ValueError:
                return self.error(400, "MANIFEST_INVALID", "not JSON")
            for blob in [parsed.get("config", {})] + parsed.get("layers", []):
                if blob.get("digest") and blob["digest"] not in registry.blobs:
                    return self.error(400, "MANIFEST_BLOB_UNKNOWN", blob["digest"])
            tags = registry.tags.setdefault(repository, {})
            if not DIGEST.fullmatch(reference) and registry.immutable and tags.get(reference, digest) != digest:
                return self.error(400, "TAG_INVALID", f"{repository}:{reference} is immutable")
            media_type = self.headers.get("Content-Type") or parsed.get("mediaType") or OCI_MANIFEST
            with registry.lock:
                registry.manifests.setdefault(repository, {})[digest] = (media_type, data)
                if not DIGEST.fullmatch(reference):
                    tags[reference] = digest
            return self.answer(201, b"", {"Location": f"/v2/{repository}/manifests/{digest}",
                                          "Docker-Content-Digest": digest})

        do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = handle_one

    return Handler
