"""The ggshield version requirements.lock pins, and the version installed (#44)."""
import re
import subprocess
from pathlib import Path

LOCK = Path(__file__).resolve().parent / "requirements.lock"


def pinned_version():
    return re.search(r"^ggshield==(\S+) ", LOCK.read_text(encoding="utf-8"), re.M)[1]


def installed_version(binary):
    """The version of the ggshield at binary (a path or a name on PATH), or "none"."""
    try:
        done = subprocess.run([binary, "--version"], capture_output=True, text=True) if binary else None
    except OSError:
        return "none"
    return (done.stdout.strip().rpartition(" ")[2] if done and done.returncode == 0 else "") or "none"
