#!/usr/bin/env python3
"""Compares what an operation made through the console with what the same operation made
through `colvenna`, for the operations acceptance run. Each check prints what it saw and exits 0 when it
holds.

  judge.py same <cli.json> <console.json> <cli-name>=<console-name>...
      the two objects are the same declaration and the operator judged them the same: spec,
      phase, conditions (type, status, reason), a check's verdict and failed rules, a waiver's
      phase; each pair of names (and anything else that only differs by them) taken as equal
  judge.py owned <secret.json> <erasure.json>
      the Secret is owned by the erasure and holds the subject under the erasure's key
  judge.py audit <events.json> <who> <action> <resource> <outcome>
      the console's audit has the action on the resource by who: requested and then the
      outcome (ok, refused or previewed: previewed only, nothing requested)
  judge.py cluster-audit <events.json> <user> <person> <verb> <resource> <name>
      the cluster's audit has the request, made by the console as user with the person
"""
import json
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def judged(o, names):
    """What an object is and what the operator made of it, with the run's names made equal."""
    st = o.get("status") or {}
    seen = {
        "kind": o.get("kind"),
        "spec": o.get("spec"),
        "phase": st.get("phase"),
        "conditions": sorted((c.get("type"), c.get("status"), c.get("reason")) for c in st.get("conditions") or []),
        "verdict": (st.get("check") or {}).get("verdict"),
        "failed": (st.get("check") or {}).get("failed"),
    }
    text = json.dumps(seen, sort_keys=True)
    for a, b in names:
        text = text.replace(b, a)
    return json.loads(text)


def same(cli_path, console_path, *pairs):
    names = [p.split("=", 1) for p in pairs]
    a, b = judged(load(cli_path), names), judged(load(console_path), names)
    differ = {k: (a[k], b[k]) for k in a if a[k] != b[k]}
    print(f"{a['kind']} phase {a['phase']}/{b['phase']}, verdict {a['verdict']}/{b['verdict']}; differ: {differ}")
    return not differ and a["phase"] is not None


def owned(secret_path, erasure_path):
    secret, erasure = load(secret_path), load(erasure_path)
    owners = secret["metadata"].get("ownerReferences") or []
    key = erasure["spec"]["subjectSecret"].get("key", "values")
    ok = (len(owners) == 1 and owners[0]["uid"] == erasure["metadata"]["uid"] and owners[0]["kind"] == "LakeErasure"
          and bool((secret.get("data") or {}).get(key)))
    print(f"owners {[(o['kind'], o['name']) for o in owners]}; key {key} {'present' if (secret.get('data') or {}).get(key) else 'missing'}")
    return ok


def audit(events_path, who, action, resource, outcome):
    events = [json.loads(m) for m in load(events_path)]
    mine = [e for e in events if e.get("kind") == "operation" and e.get("username") == who
            and e.get("action") == action and e.get("resource") == resource]
    reasons = [(e.get("reason"), e.get("status")) for e in mine]
    requested = any(r == "requested" for r, _ in reasons)
    results = [(r, s) for r, s in reasons if r not in ("requested", "previewed")]
    if outcome == "previewed":
        ok = any(r == "previewed" for r, _ in reasons) and not requested and not results
    elif outcome == "ok":
        ok = requested and any(s == 200 for _, s in results)
    else:
        ok = any(s and s >= 400 for _, s in results)
    print(f"{who} {action} {resource}: {reasons}")
    return ok


def cluster_audit(events_path, user, person, verb, resource, name):
    events = [json.loads(m) for m in load(events_path)]
    found = [e for e in events if e.get("verb") == verb and (e.get("objectRef") or {}).get("resource") == resource
             and (e.get("objectRef") or {}).get("name") == name and (e.get("impersonatedUser") or {}).get("username") == user]
    shown = [(e["user"].get("username"), e["impersonatedUser"].get("extra"), (e.get("responseStatus") or {}).get("code"))
             for e in found]
    print(f"{verb} {resource}/{name} as {user}: {shown}")
    return any(e["user"].get("username", "").startswith("system:serviceaccount:")
               and (e["impersonatedUser"].get("extra") or {}).get("colvenna-lake.io/person") == [person] for e in found)


def main():
    command, *args = sys.argv[1:]
    checks = {"same": same, "owned": owned, "audit": audit, "cluster-audit": cluster_audit}
    sys.exit(0 if checks[command](*args) else 1)


if __name__ == "__main__":
    main()
