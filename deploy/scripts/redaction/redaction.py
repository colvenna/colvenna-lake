#!/usr/bin/env python3
"""The repository carries none of the maintainers' deployment values (#248): no AWS account ID,
hosted zone ID or domain (nor any name under it), executor role or instance, ARN of a real account,
internal or public address. Documents, tests and commit messages use placeholders and synthetic
values; the values themselves live only in uncommitted local settings, which this check reads to know
what to look for, so that the repository never has to name them.

What it looks for:

- The values of every local environment's settings (deploy/.state/<env>/: account, hosted-zone-id,
  hosted-zone-name, entry-domain) and of deploy/.state/redaction-values (one value per line, for those
  the settings do not hold, such as the executor's role), in every worktree of this clone.
- The values whose SHA-256 is in known-values.sha256, so that a clone without the settings, and CI,
  look for the maintainers' values too. An account keeps only 6 hex digits of its hash: a full hash of
  a 12-digit number is reversed by trying them all, while about 60,000 numbers share 6 digits.
- Whatever the values: an ARN or an ECR registry of an account that is neither synthetic nor AWS's
  own, and an internal IPv4 address (10/8, 172.16/12, 192.168/16, or an ip-10-... host name) that is
  not a CIDR. A line with "redaction: allow" is spared these, never the values.
- A public IPv4 address, with or without a prefix length (#300): a globally reachable unicast
  address (Python's ipaddress: is_global, not is_multicast), so neither private, shared, loopback,
  link-local nor of the documentation ranges 192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24
  (RFC 5737), which documents and tests use instead. A four-part number with a letter or a dot
  next to it (v1.2.3.4) is not read as an address. Only public-addresses.tsv allows one, by file and
  address, each with its reason; "redaction: allow" does not.

A value is matched as a word: an account as 12 digits with no digit next to them, anything else as a
run of letters, digits, "_", "." and "-", or a part of one between "." or "-" (so the hosted zone's
domain is found in every name under it). Case does not matter.

Usage: redaction.py FILE...      (pre-commit passes the staged files, or the commit message)
       redaction.py --hash < values   (the known-values.sha256 lines of the values, one per line)
"""
import fnmatch
import hashlib
import ipaddress
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
KNOWN = os.path.join(HERE, "known-values.sha256")
PUBLIC = os.path.join(HERE, "public-addresses.tsv")
SALT = "colvenna-lake-redaction:"
# How many hex digits of a hash known-values.sha256 keeps: an account few, so the line does not give
# it away (another 12-digit number matches by chance once in 16.7 million); other values more, since
# every word of a file is compared with them.
DIGITS = {"account": 6, "text": 16}
# The settings install.sh records per environment (deploy/env.sh's COLVENNA_SETTINGS) that are the
# deployment's own; the region is not.
SETTINGS = ("account", "hosted-zone-id", "hosted-zone-name", "entry-domain")
EXTRA = "redaction-values"
ALLOW = "redaction: allow"

ACCOUNT = re.compile(r"(?<!\d)\d{12}(?!\d)")
WORD = re.compile(r"[a-z0-9_](?:[a-z0-9_.-]*[a-z0-9_])?")
PARTS = re.compile(r"([.-])")
# A word's parts that are compared together at most: a value is found inside a longer name only up to
# this many parts (colvenna-uat.aws.example.com has five).
MAX_PARTS = 8
# What a split or quoted literal in code is joined by (tests/acceptance/test_portability.py).
JOINERS = re.compile(r"""[\s'"`+\\,(){}]|\$\{?|\}""")

# Accounts that are not anybody's: AWS's documentation examples and test data.
SYNTHETIC = {"123456789012", "210987654321", "111122223333", "444455556666"}
# Accounts that are AWS's own, which public images and policies name.
AWS_OWNED = {"602401143452"}  # Amazon EKS's image registry (add-ons such as the VPC CNI)
ARN = re.compile(r"\barn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:(\d{12})(?!\d)")
ECR = re.compile(r"(?<!\d)(\d{12})\.dkr\.ecr(?:-fips)?\.[a-z0-9-]+\.amazonaws\.com")
IPV4 = re.compile(r"(?<![\d.])((?:10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])|192\.168)\.\d{1,3}\.\d{1,3})(?![\d.]*\d)(?!/\d)")
INTERNAL_HOST = re.compile(r"\bip-(?:10(?:-\d{1,3}){3}|172-(?:1[6-9]|2\d|3[01])(?:-\d{1,3}){2}|192-168(?:-\d{1,3}){2})\b")
# Any IPv4 literal, a CIDR's too; not one with an ASCII letter, digit, "_" or "." next to it (a
# version, v1.2.3.4). Chinese text next to it does not hide it.
ANY_IPV4 = re.compile(r"(?<![A-Za-z0-9_.])(\d{1,3}(?:\.\d{1,3}){3})(?:/\d{1,2})?(?![A-Za-z0-9_]|\.\d)")


def digest(value):
    return hashlib.sha256((SALT + value.lower()).encode()).hexdigest()


def kind(value):
    """account or text: what a value is compared with. None when it can never be found as a word."""
    value = value.lower()
    if ACCOUNT.fullmatch(value):
        return "account"
    if WORD.fullmatch(value) and len(PARTS.split(value)) <= 2 * MAX_PARTS - 1:
        return "text"
    return None


def words(line):
    """The words of a line and their parts between "." or "-", in all of their runs up to MAX_PARTS."""
    for word in WORD.findall(line):
        parts = PARTS.split(word)  # the parts at even indexes, their separators between
        n = (len(parts) + 1) // 2
        for i in range(n):
            for j in range(i, min(n, i + MAX_PARTS)):
                yield "".join(parts[2 * i:2 * j + 1])


def accounts(line, joined=False):
    """12-digit numbers of a line; of a joined one, every 12 digits of a longer number too."""
    if not joined:
        return ACCOUNT.findall(line)
    return [run[i:i + 12] for run in re.findall(r"\d{12,}", line) for i in range(len(run) - 11)]


def synthetic(account):
    return account in SYNTHETIC or account in AWS_OWNED or len(set(account)) == 1


class Values:
    """The values to look for, as hashes (full ones for the local values) with what each one is."""

    def __init__(self):
        self.hashes = {"account": {}, "text": {}}  # kind -> hash or prefix -> what
        self.lengths = {"account": set(), "text": set()}
        self.cache = {}

    def add_hash(self, kind_, prefix, what):
        self.hashes[kind_][prefix.lower()] = what
        self.lengths[kind_].add(len(prefix))
        self.cache.clear()

    def add_value(self, value, what):
        value = value.strip()
        kind_ = kind(value)
        if kind_ is None:
            raise ValueError("%s: a value to look for must be one word of letters, digits, '_', '.' and '-'" % what)
        self.add_hash(kind_, digest(value), what)

    def load_known(self, path=KNOWN):
        with open(path) as f:
            for number, line in enumerate(f, 1):
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                fields = line.split(None, 2)
                if len(fields) < 2 or fields[0] not in DIGITS or not re.fullmatch(r"[0-9a-f]{6,64}", fields[1]):
                    raise ValueError("%s:%d: not '<account|text> <hex prefix> <what it is>'" % (path, number))
                self.add_hash(fields[0], fields[1], fields[2] if len(fields) > 2 else "a maintainers' value")
        return self

    def load_local(self, roots=None):
        """The values of the local settings in every worktree of this clone (or of the roots given)."""
        for root in worktrees() if roots is None else roots:
            state = os.path.join(root, "deploy", ".state")
            if not os.path.isdir(state):
                continue
            for env in sorted(os.listdir(state)):
                for setting in SETTINGS:
                    path = os.path.join(state, env, setting)
                    if os.path.isfile(path):
                        value = read(path).strip()
                        if value:
                            self.add_value(value, "the %s of local environment %s" % (setting, env))
            extra = os.path.join(state, EXTRA)
            if os.path.isfile(extra):
                for number, line in enumerate(read(extra).splitlines(), 1):
                    if line.strip() and not line.lstrip().startswith("#"):
                        self.add_value(line, "line %d of deploy/.state/%s" % (number, EXTRA))
        return self

    def _match(self, kind_, candidate):
        key = (kind_, candidate)
        if key not in self.cache:
            full, found = digest(candidate), None
            for length in self.lengths[kind_]:
                found = self.hashes[kind_].get(full[:length])
                if found:
                    break
            self.cache[key] = found
        return self.cache[key]

    def find(self, line, joined=False):
        """What values a line holds: the description of each, once. joined: also when a literal is
        split and quoted (see JOINERS)."""
        line = line.lower()
        found = []
        for text, split in [(line, False)] + ([(JOINERS.sub("", line), True)] if joined else []):
            for what in [self._match("account", a) for a in accounts(text, split)] + \
                    [self._match("text", w) for w in words(text)]:
                if what and what not in found:
                    found.append(what)
        return found


def worktrees():
    """This clone's worktrees (the main one first), or just this one outside git."""
    try:
        out = subprocess.run(["git", "-C", REPO, "worktree", "list", "--porcelain"],
                             check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return [REPO]
    roots = [line[len("worktree "):] for line in out.splitlines() if line.startswith("worktree ")]
    return roots or [REPO]


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def patterns(line):
    """The value-independent findings of a line."""
    if ALLOW in line:
        return []
    found = []
    for m in ARN.finditer(line):
        if not synthetic(m.group(1)):
            found.append("an ARN of a real account: use a synthetic one (123456789012) or <ARN>")
    for m in ECR.finditer(line):
        if not synthetic(m.group(1)):
            found.append("an ECR registry of a real account: use a synthetic one or <ACCOUNT_ID>")
    for m in IPV4.finditer(line):
        if octets(m.group(1)):
            found.append("an internal IPv4 address: use a placeholder")
    if INTERNAL_HOST.search(line):
        found.append("an internal host name (ip-10-...): use a placeholder")
    return found


def octets(value):
    """Whether a dotted quad's four numbers are octets (10.1.2.300 is no address)."""
    return all(int(octet) <= 255 for octet in value.split("."))


def public_addresses(line):
    """The public IPv4 addresses of a line (without their prefix lengths), in order."""
    found = []
    for m in ANY_IPV4.finditer(line):
        value = m.group(1)
        if octets(value):
            address = ipaddress.ip_address(value)
            if address.is_global and not address.is_multicast:
                found.append(value)
    return found


def load_public(path=PUBLIC):
    """The public addresses allowed: (path glob, address, reason) of each line of public-addresses.tsv."""
    allowed = []
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 3 or not all(field.strip() for field in fields) or \
                    public_addresses(fields[1]) != [fields[1]]:
                raise ValueError("%s:%d: not '<path glob> TAB <public IPv4 address> TAB <reason>'" % (path, number))
            allowed.append(tuple(fields))
    return allowed


def repo_path(path):
    """A path as the exceptions name it: relative to the repository, with "/"."""
    full = os.path.abspath(path)
    if full == REPO or full.startswith(REPO + os.sep):
        path = os.path.relpath(full, REPO)
    return path.replace(os.sep, "/")


def check(path, values, joined=False, allowed=()):
    """path:line: finding, for each finding of a text file (a binary one has none). allowed: the
    public addresses allowed, as load_public gives them."""
    with open(path, "rb") as f:
        data = f.read()
    if b"\0" in data[:8192]:
        return []
    name = repo_path(path)
    # public-addresses.tsv itself holds every address it allows.
    here = {address for glob, address, _ in allowed if name == repo_path(PUBLIC) or fnmatch.fnmatchcase(name, glob)}
    out = []
    for number, line in enumerate(data.decode("utf-8", errors="replace").splitlines(), 1):
        for what in values.find(line, joined):
            out.append("%s:%d: %s" % (path, number, what))
        for what in patterns(line):
            out.append("%s:%d: %s" % (path, number, what))
        if any(address not in here for address in public_addresses(line)):
            out.append("%s:%d: a public IPv4 address: use a placeholder or a documentation address"
                       " (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24)" % (path, number))
    return out


def hash_lines(lines):
    """The known-values.sha256 lines of values, for the maintainers to say what each one is."""
    out = []
    for value in (v.strip() for v in lines):
        if not value:
            continue
        kind_ = kind(value)
        if kind_ is None:
            raise ValueError("a value to look for must be one word of letters, digits, '_', '.' and '-'")
        out.append("%s %s <what it is>" % (kind_, digest(value)[:DIGITS[kind_]]))
    return out


def main(argv, roots=None, known=KNOWN, public=PUBLIC):
    """roots: where the local settings are (by default every worktree of this clone)."""
    if argv[1:] == ["--hash"]:
        print("\n".join(hash_lines(sys.stdin)))
        return 0
    if not argv[1:] or argv[1].startswith("-"):
        print(__doc__.strip().rsplit("\n\n", 1)[-1], file=sys.stderr)
        return 2
    try:
        values = Values().load_known(known).load_local(roots)
        allowed = load_public(public)
    except ValueError as e:  # a setting or a stored line it cannot look for: say which, not its value
        print(e, file=sys.stderr)
        return 2
    found = [f for path in argv[1:] if os.path.isfile(path) for f in check(path, values, allowed=allowed)]
    if found:
        print("Deployment values in the repository (#248; the values are not shown):")
        print("\n".join(found))
        print("Write placeholders (<ACCOUNT_ID>, <COLVENNA_HOSTED_ZONE_NAME>, <ARN>) or synthetic values "
              "(123456789012, example.com) instead; see the README's Contributing. A public address that is "
              "not one (a version) goes in deploy/scripts/redaction/public-addresses.tsv, with its reason.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
