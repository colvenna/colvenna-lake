"""The Doris identity of each person on each dashboard, as the operator publishes them.

The operator writes one entry per dashboard user with a sign-in name into a Secret of this
namespace (colvenna-operator's BiCredentials); the pod mounts it read-only, one file per Doris user,
each a JSON object: sso, dorisUser, password, database, dashboard. Kubernetes swaps the files in
when the Secret changes, so they are read again when the mount's data link moves.
"""

import json
import os
import threading
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class Identity:
    sso: str
    doris_user: str
    password: str
    database: str
    dashboard: str


class Credentials:
    def __init__(self, directory, now=time.time, every=5):
        self.directory, self.now, self.every = directory, now, every
        self.lock = threading.Lock()
        self.version, self.checked = None, None
        self.by_database, self.by_person = {}, {}

    def _load(self):
        with self.lock:
            if self.checked is not None and self.now() - self.checked < self.every:
                return
            self.checked = self.now()
            # A Secret volume points ..data at the current version: it moves on every change.
            link = os.path.join(self.directory, "..data")
            version = os.readlink(link) if os.path.islink(link) else self._mtimes()
            if version == self.version:
                return
            by_database, by_person = {}, {}
            for name in sorted(os.listdir(self.directory)):
                path = os.path.join(self.directory, name)
                if name.startswith(".") or not os.path.isfile(path):
                    continue
                try:
                    with open(path) as f:
                        e = json.load(f)
                    identity = Identity(e["sso"], e["dorisUser"], e["password"], e["database"],
                                        e["dashboard"])
                except (OSError, ValueError, KeyError, TypeError):
                    continue
                person = identity.sso.lower()
                by_database[(identity.database, person)] = identity
                by_person.setdefault(person, []).append(identity)
            self.by_database, self.by_person, self.version = by_database, by_person, version

    def _mtimes(self):
        return tuple(sorted((n, os.stat(os.path.join(self.directory, n)).st_mtime_ns)
                            for n in os.listdir(self.directory)))

    def lookup(self, database, username):
        """The person's identity on the dashboard whose Doris database this is, or None."""
        self._load()
        return self.by_database.get((database, username.lower()))

    def dashboards(self, username):
        """The dashboards the person is a user of, as <namespace>/<name>."""
        self._load()
        return sorted({i.dashboard for i in self.by_person.get(username.lower(), [])})

    def all(self):
        """Every identity: what the Superset databases follow."""
        self._load()
        return sorted(self.by_database.values(), key=lambda i: (i.dashboard, i.doris_user))
