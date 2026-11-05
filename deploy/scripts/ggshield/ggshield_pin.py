"""The ggshield version requirements.lock pins, and the version installed (#44)."""
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCK = HERE / "requirements.lock"
LOCK_NAME = LOCK.relative_to(HERE.parents[2]).as_posix()  # as the repository names it


def pinned_version():
    return re.search(r"^ggshield==(\S+) ", LOCK.read_text(encoding="utf-8"), re.M)[1]


def installed_version(binary):
    """The version of the ggshield at binary (a path or a name on PATH), or "none"."""
    try:
        done = subprocess.run([binary, "--version"], capture_output=True, text=True) if binary else None
    except OSError:
        return "none"
    return (done.stdout.strip().rpartition(" ")[2] if done and done.returncode == 0 else "") or "none"


def require_pinned(binary, user, hint=""):
    """The pinned version, when the ggshield at binary is that version; else exit, saying which user needs it,
    what was found, and the hint."""
    want, have = pinned_version(), installed_version(binary)
    if have != want:
        sys.exit(f"{user} needs ggshield {want}, installed from {LOCK_NAME} (found: {have}){hint}")
    return have
