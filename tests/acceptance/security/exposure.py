"""The release check's exposure inventory and denial matrix (#208).

inventory.sh writes what AWS and the cluster answer to <evidence>/raw/; this module classifies it:
which resources of the account, in the environment's region, can be reached from the internet,
whether they are the environment's, and how they authenticate. run-denials.sh then probes every
internet-reachable resource of the environment, and the matrix pairs each with the evidence of the
three refusals the release check asks for: unauthenticated, unauthorized, and around the intended
entry.

usage:
  exposure.py classify <evidence>             raw/ -> inventory.tsv and others.tsv; prints the checks
  exposure.py preflight <evidence> <env> <scripts-sha>
                                              why a denial run may not write there (exit 1), else nothing
  exposure.py underprivileged <probe-log> <role> <check>...
                                              the under-privileged probe's log -> its checks
  exposure.py matrix <evidence> <run-id> [<uat-root>]
                                              inventory.tsv, this inventory's and run's verdicts and the
                                              UAT's evidence -> matrix.tsv and matrix.md; prints the check
  exposure.py scrub <evidence>                prints the check that nothing credential-like is in the evidence
  exposure.py exact-scan <evidence> <state-dir> <made types>
                                              prints the check that none of the run's own credentials is
  exposure.py section <evidence> [<run-id>]   the release check's Markdown section of this environment,
                                              the deployment's values replaced by placeholders, one number
                                              per address in the whole section
  exposure.py redact <evidence> <file.md>     the same placeholders, in place, with this environment's values,
                                              one number per address in the whole file

The checks print as "<name>\t<PASS|FAIL|N/A>\t<detail>" lines; the scripts time-stamp them into
verdicts.tsv, with the id of the run that made them (the inventory's, or the denial run's). N/A is an
outcome of its own, with its reason as the detail: neither a pass nor a failure.
"""

import glob
import json
import os
import re
import sys

PUBLIC_CIDRS = ("0.0.0.0/0", "::/0")


def is_nat_or_spare(note):
    """Whether an address's note marks it as a NAT gateway or unassociated Elastic IP (nothing listens)."""
    return note.startswith(("NAT", "unassociated"))


def load(path, default=None):
    """A raw file's JSON; the default when inventory.sh recorded nothing (absent or null)."""
    if not os.path.exists(path):
        return default
    with open(path) as f:
        text = f.read().strip()
    if not text or text == "null":
        return default
    return json.loads(text)


def policy_statements(policy):
    """The statements of a resource policy, given as a JSON string or a parsed document."""
    if not policy:
        return []
    if isinstance(policy, str):
        policy = json.loads(policy)
    statements = policy.get("Statement", [])
    return statements if isinstance(statements, list) else [statements]


def public_principal(statement):
    """Whether an Allow statement names everyone: "*" or {"AWS": "*"} (with or without a condition)."""
    if statement.get("Effect") != "Allow":
        return False
    principal = statement.get("Principal")
    if principal == "*":
        return True
    if isinstance(principal, dict):
        aws = principal.get("AWS")
        return aws == "*" or (isinstance(aws, list) and "*" in aws)
    return False


def denies_plain_http(policy):
    """Whether a bucket policy denies every request made without TLS (aws:SecureTransport false)."""
    for s in policy_statements(policy):
        bool_condition = s.get("Condition", {}).get("Bool", {})
        if s.get("Effect") == "Deny" and str(bool_condition.get("aws:SecureTransport")).lower() == "false":
            return True
    return False


def public_grants(acl):
    """The ACL grants to all users or to all authenticated AWS users."""
    groups = ("http://acs.amazonaws.com/groups/global/AllUsers",
              "http://acs.amazonaws.com/groups/global/AuthenticatedUsers")
    return [g for g in (acl or {}).get("Grants", []) if g.get("Grantee", {}).get("URI") in groups]


def blocks_all(bpa):
    """Whether a Block Public Access configuration has all four settings on."""
    keys = ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")
    config = (bpa or {}).get("PublicAccessBlockConfiguration", bpa or {})
    return all(config.get(k) is True for k in keys)


def open_ingress(group):
    """An open security group's rules that admit any IPv4 or IPv6 address: (protocol, from, to)."""
    rules = []
    for p in group.get("IpPermissions", []):
        cidrs = [r.get("CidrIp") for r in p.get("IpRanges", [])] + [r.get("CidrIpv6") for r in p.get("Ipv6Ranges", [])]
        if any(c in PUBLIC_CIDRS for c in cidrs):
            rules.append((p.get("IpProtocol"), p.get("FromPort"), p.get("ToPort")))
    return rules


def action_kinds(actions):
    return [a["Type"] for a in sorted(actions, key=lambda a: a.get("Order", 0))]


def entry_listeners_ok(listeners, rules_by_port, protocols_by_port):
    """The entry's listeners as designed: 80 only redirects to HTTPS; 443 allows TLS 1.2 and 1.3 only,
    answers what no rule matches with a fixed response, and every rule signs in at Cognito before it
    forwards. Returns (ok, detail)."""
    by_port = {l["Port"]: l for l in listeners}
    problems = []
    if sorted(by_port) != [80, 443]:
        problems.append("ports %s" % sorted(by_port))
    if 80 in by_port:
        actions = by_port[80].get("DefaultActions", [])
        if action_kinds(actions) != ["redirect"] or actions[0].get("RedirectConfig", {}).get("Protocol") != "HTTPS":
            problems.append("80 does not only redirect to HTTPS")
    if 443 in by_port:
        if by_port[443].get("Protocol") != "HTTPS":
            problems.append("443 is %s" % by_port[443].get("Protocol"))
        if sorted(protocols_by_port.get(443, [])) != ["TLSv1.2", "TLSv1.3"]:
            problems.append("443 allows %s" % protocols_by_port.get(443))
        for r in rules_by_port.get(443, []):
            want = ["fixed-response"] if r.get("IsDefault") else ["authenticate-cognito", "forward"]
            if action_kinds(r.get("Actions", [])) != want:
                problems.append("rule %s: %s" % (r.get("Priority"), action_kinds(r.get("Actions", []))))
    return not problems, "; ".join(problems) or "80 redirects to HTTPS; 443 TLS 1.2/1.3, every rule signs in first"


class Inventory:
    """The raw answers of one inventory run, and what of them is the environment's."""

    def __init__(self, evidence):
        self.raw = os.path.join(evidence, "raw")
        r = lambda name, default=None: load(os.path.join(self.raw, name), default)  # noqa: E731
        self.r = r
        self.env = r("env.json", {})
        self.resources = r("stack-resources.json", [])
        self.physical = {x["physical"] for x in self.resources if x.get("physical")}
        self.vpc = self.env.get("vpc")
        self.cluster = r("eks.json", {})
        self.rows = []
        self.others = []
        self.checks = []

    def of_type(self, kind):
        return [x["physical"] for x in self.resources if x["type"] == kind and x.get("physical")]

    def row(self, kind, ident, owner, reachable, anonymous, auth, note=""):
        (self.rows if owner in ("env", "release") else self.others).append(
            (kind, ident, owner, reachable, anonymous, auth, note))

    def check(self, name, ok, detail):
        self.checks.append((name, "PASS" if ok else "FAIL", detail))

    # --- addresses and load balancers --------------------------------------------------------------
    def addresses(self):
        # The entry's network interfaces are described "ELB app/<name>/<id>", the end of its ARN.
        alb_id = self.env.get("alb_arn", "").split(":loadbalancer/")[-1]
        seen = {}
        for e in self.r("enis-public.json", {}).get("NetworkInterfaces", []):
            ip = e.get("Association", {}).get("PublicIp")
            mine = e.get("VpcId") == self.vpc
            if e.get("InterfaceType") == "nat_gateway":
                what = "NAT gateway (egress only)"
            elif e.get("Description", "").startswith("ELB "):
                what = "load balancer %s" % e["Description"][4:]
            else:
                what = "%s %s" % (e.get("InterfaceType"), e.get("Description", "") or e.get("Attachment", {}).get("InstanceId", ""))
            seen[ip] = (mine, what.strip())
        for a in self.r("addresses.json", {}).get("Addresses", []):
            ip = a.get("PublicIp")
            if ip in seen:
                continue
            mine = ip in self.physical or a.get("AllocationId") in self.physical
            seen[ip] = (mine, "unassociated Elastic IP" if not a.get("AssociationId") else "Elastic IP %s" % a.get("AssociationId"))
        allowed = []
        for ip, (mine, what) in sorted(seen.items()):
            self.row("address", ip, "env" if mine else "other", "internet", "no",
                     "none listens" if is_nat_or_spare(what) else "the load balancer's listeners", what)
            if mine:
                ok = is_nat_or_spare(what) or (alb_id and what == "load balancer %s" % alb_id)
                allowed.append((ip, what, ok))
        self.check("public-addresses-only-nat-and-entry", allowed and all(ok for _, _, ok in allowed),
                   "; ".join("%s %s" % (ip, what) for ip, what, _ in allowed))

    def load_balancers(self):
        entry_arn = self.env.get("alb_arn")
        public_env = []
        for lb in self.r("load-balancers.json", {}).get("LoadBalancers", []):
            mine = lb.get("VpcId") == self.vpc
            scheme = lb.get("Scheme")
            name = lb["LoadBalancerName"]
            if mine:
                listeners = load(os.path.join(self.raw, "lb", name, "listeners.json"), {}).get("Listeners", [])
                rules = {}
                protocols = {}
                for l in listeners:
                    rules[l["Port"]] = load(os.path.join(self.raw, "lb", name, "rules-%s.json" % l["Port"]), {}).get("Rules", [])
                    if l.get("SslPolicy"):
                        policy = load(os.path.join(self.raw, "lb", name, "ssl-%s.json" % l["Port"]), {})
                        protocols[l["Port"]] = (policy.get("SslPolicies") or [{}])[0].get("SslProtocols", [])
                note = "%s %s; listeners %s" % (lb.get("Type"), lb.get("DNSName"), sorted(rules))
                if scheme == "internet-facing":
                    public_env.append(lb["LoadBalancerArn"])
                    if lb["LoadBalancerArn"] == entry_arn:
                        ok, detail = entry_listeners_ok(listeners, rules, protocols)
                        self.check("entry-listeners-sign-in-first", ok, detail)
                self.row("load-balancer", name, "env", "internet" if scheme == "internet-facing" else "vpc", "no",
                         "Cognito sign-in (password + TOTP) before any forward" if lb["LoadBalancerArn"] == entry_arn else "?",
                         note)
            elif scheme == "internet-facing":
                self.row("load-balancer", name, "other", "internet", "?", "?", "%s in %s" % (lb.get("Type"), lb.get("VpcId")))
        for lb in self.r("classic-load-balancers.json", {}).get("LoadBalancerDescriptions", []):
            mine = lb.get("VPCId") == self.vpc
            if mine:
                public_env.append(lb["LoadBalancerName"])
            self.row("load-balancer", lb["LoadBalancerName"], "env" if mine else "other",
                     "internet" if lb.get("Scheme") == "internet-facing" else "vpc", "?", "?", "classic")
        self.check("load-balancers-only-the-entry", public_env == [entry_arn],
                   "internet-facing load balancers of the environment: %s" % (public_env or "none"))

    # --- the cluster ---------------------------------------------------------------------------------
    def host_addresses(self):
        """The address of the host the release check ran on, when the evidence shows it: the one /32
        the EKS public endpoint admits. deploy.sh narrows the endpoint to the installer's address, and
        inventory.sh and run-denials.sh reach the cluster through it from this host. Nothing when the
        endpoint is private or admits more: which one is the host's is not known."""
        vpc_config = self.cluster.get("resourcesVpcConfig", {})
        cidrs = vpc_config.get("publicAccessCidrs") or []
        one = vpc_config.get("endpointPublicAccess") is True and len(cidrs) == 1 and cidrs[0].endswith("/32")
        return [cidrs[0][:-len("/32")]] if one else []

    def eks(self):
        cluster = self.cluster
        vpc_config = cluster.get("resourcesVpcConfig", {})
        cidrs = vpc_config.get("publicAccessCidrs", [])
        mode = cluster.get("accessConfig", {}).get("authenticationMode")
        public = vpc_config.get("endpointPublicAccess")
        self.row("eks-endpoint", cluster.get("name", "?"), "env", "internet" if public else "vpc", "no",
                 "IAM (access entries, %s), from %s only" % (mode, ",".join(cidrs)),
                 "private access %s" % vpc_config.get("endpointPrivateAccess"))
        ok = (not public or (cidrs and all(c.endswith("/32") and c not in PUBLIC_CIDRS for c in cidrs))) \
            and mode in ("API", "API_AND_CONFIG_MAP", "CONFIG_MAP") and vpc_config.get("endpointPrivateAccess") is True
        self.check("eks-endpoint-iam-and-narrow", ok, "public %s from %s; authentication %s; private %s"
                   % (public, cidrs, mode, vpc_config.get("endpointPrivateAccess")))
        for c in self.r("eks-others.json", []):
            self.row("eks-endpoint", c["name"], "other", "internet" if c.get("public") else "vpc", "no",
                     "IAM", "public from %s" % c.get("cidrs"))

    def kubernetes(self):
        services = [s for s in self.r("k8s-services.json", []) if s["type"] in ("LoadBalancer", "NodePort")]
        ingresses = self.r("k8s-ingresses.json", [])
        for s in services:
            self.row("k8s-service", "%s/%s" % (s["namespace"], s["name"]), "env", "vpc", "?", "?", s["type"])
        for i in ingresses:
            self.row("k8s-ingress", "%s/%s" % (i["namespace"], i["name"]), "env", "?", "?", "?", "")
        for p in self.r("k8s-host-ports.json", []):
            self.row("k8s-host-port", "%s/%s" % (p["namespace"], p["name"]), "env", "vpc (node address)", "-",
                     "nodes in private subnets", "hostNetwork %s, ports %s" % (p.get("hostNetwork"), p.get("hostPorts")))
        self.check("kubernetes-no-load-balancer-node-port-ingress", not services and not ingresses,
                   "LoadBalancer/NodePort services %s; ingresses %s"
                   % (["%s/%s" % (s["namespace"], s["name"]) for s in services] or "none",
                      ["%s/%s" % (i["namespace"], i["name"]) for i in ingresses] or "none"))

    # --- storage, identities, images -----------------------------------------------------------------
    def buckets(self):
        account = self.r("s3-account.json", {})
        self.row("s3-account", self.env.get("account", "?"), "env", "-", "no", "Block Public Access",
                 json.dumps((account or {}).get("PublicAccessBlockConfiguration", account)))
        self.check("s3-account-blocks-public-access", blocks_all(account), json.dumps(account))
        problems, names = [], []
        for bucket in sorted(self.of_type("AWS::S3::Bucket")):
            base = os.path.join(self.raw, "s3", bucket)
            bpa = load(os.path.join(base, "bpa.json"))
            policy = (load(os.path.join(base, "policy.json")) or {}).get("Policy")
            status = (load(os.path.join(base, "policy-status.json")) or {}).get("PolicyStatus", {})
            acl = load(os.path.join(base, "acl.json"), {})
            encryption = load(os.path.join(base, "encryption.json"))
            names.append(bucket)
            mine = []
            if not blocks_all(bpa):
                mine.append("Block Public Access not all on")
            if status.get("IsPublic"):
                mine.append("policy is public")
            if any(public_principal(s) for s in policy_statements(policy)):
                mine.append("a statement allows everyone")
            if public_grants(acl):
                mine.append("ACL grants %s" % public_grants(acl))
            if not denies_plain_http(policy):
                mine.append("no deny of plain HTTP")
            if not encryption:
                mine.append("no default encryption")
            problems += ["%s: %s" % (bucket, p) for p in mine]
            self.row("s3-bucket", bucket, "env", "internet (S3 API)", "no",
                     "IAM and the bucket policy, TLS only", "; ".join(mine) or "private, TLS only, encrypted")
        self.check("s3-buckets-private-and-tls-only", names and not problems,
                   "; ".join(problems) or "%d buckets: %s" % (len(names), ", ".join(names)))

    def cognito(self):
        pools = self.r("cognito-pools.json", {}).get("UserPools", [])
        env_pools = set(self.of_type("AWS::Cognito::UserPool"))
        domain_of = self.env.get("entry_domain", "")
        problems = []
        for p in pools:
            base = os.path.join(self.raw, "cognito", p["Id"])
            pool = load(os.path.join(base, "pool.json"), {})
            if p["Id"] not in env_pools:
                if pool.get("Domain") or pool.get("CustomDomain"):
                    self.row("cognito-user-pool", p["Id"], "other", "internet", "?", "?",
                             "domain %s" % (pool.get("CustomDomain") or pool.get("Domain")))
                continue
            if pool.get("MfaConfiguration") != "ON":
                problems.append("MFA %s" % pool.get("MfaConfiguration"))
            if pool.get("AllowAdminCreateUserOnly") is not True:
                problems.append("self sign-up allowed")
            for f in sorted(glob.glob(os.path.join(base, "client-*.json"))):
                c = load(f, {})
                if not c.get("HasSecret"):
                    problems.append("client %s without a secret" % c.get("ClientId"))
                if c.get("AllowedOAuthFlows") != ["code"]:
                    problems.append("OAuth flows %s" % c.get("AllowedOAuthFlows"))
                extra = set(c.get("ExplicitAuthFlows") or []) - {"ALLOW_REFRESH_TOKEN_AUTH"}
                if extra:
                    problems.append("direct auth flows %s" % sorted(extra))
                if c.get("SupportedIdentityProviders") != ["COGNITO"]:
                    problems.append("identity providers %s" % c.get("SupportedIdentityProviders"))
                bad = [u for u in (c.get("CallbackURLs") or []) + (c.get("LogoutURLs") or [])
                       if not re.match(r"^https://[a-z0-9-]+\.%s/" % re.escape(domain_of), u)]
                if bad:
                    problems.append("URLs outside %s: %s" % (domain_of, bad))
            self.row("cognito-user-pool", p["Id"], "env", "internet", "sign-in page only",
                     "password + TOTP; no self sign-up; client secret; authorization code only",
                     "domain %s" % (pool.get("CustomDomain") or pool.get("Domain")))
        self.check("cognito-admin-only-mfa-code-flow", env_pools and not problems,
                   "; ".join(problems) or "%s: MFA on, administrators create users, code flow with a secret"
                   % ", ".join(sorted(env_pools)))

    def lambdas(self):
        env_functions = set(self.of_type("AWS::Lambda::Function"))
        total = urls = mine = 0
        found, public = [], []
        for region_dir in sorted(glob.glob(os.path.join(self.raw, "lambda", "*"))):
            region = os.path.basename(region_dir)
            for name in load(os.path.join(region_dir, "functions.json"), []):
                total += 1
                configs = load(os.path.join(region_dir, name + ".urls.json"), {}).get("FunctionUrlConfigs", [])
                urls += len(configs)
                owner = "env" if name in env_functions and region == self.env.get("region") else "other"
                if configs:
                    found.append("%s %s (%s, auth %s)" % (region, name, owner, ",".join(c.get("AuthType", "?") for c in configs)))
                if owner == "env":
                    mine += 1
                    policy = (load(os.path.join(region_dir, name + ".policy.json")) or {}).get("Policy")
                    if any(public_principal(s) for s in policy_statements(policy)):
                        public.append(name)
                    self.row("lambda-function", name, "env", "AWS API (IAM)", "no", "IAM; no function URL",
                             "%d URLs; policy %s" % (len(configs), "public" if name in public else "not public"))
        self.check("lambda-function-urls-none", total > 0 and urls == 0,
                   "%d functions in %s (%d of the environment), %d function URLs%s"
                   % (total, ", ".join(os.path.basename(d) for d in sorted(glob.glob(os.path.join(self.raw, "lambda", "*")))),
                      mine, urls, (": " + "; ".join(found)) if found else ""))
        self.check("lambda-no-public-policy", not public, "public resource policies: %s" % (public or "none"))

    def security_groups(self):
        entry_groups = {x["physical"] for x in self.resources
                        if x["type"] == "AWS::EC2::SecurityGroup" and x["stack"] == self.env.get("entry_stack")}
        groups = {}
        for name in ("security-groups-open-ipv4.json", "security-groups-open-ipv6.json"):
            for g in self.r(name, {}).get("SecurityGroups", []):
                groups[g["GroupId"]] = g
        problems = []
        for gid, g in sorted(groups.items()):
            rules = open_ingress(g)
            if not rules:
                continue
            mine = g.get("VpcId") == self.vpc
            self.row("security-group", gid, "env" if mine else "other", "internet", "-", "-",
                     "%s%s: %s" % ("the entry's load balancer, " if gid in entry_groups else "", g.get("GroupName"), rules))
            if mine and not (gid in entry_groups and sorted(rules) == [("tcp", 80, 80), ("tcp", 443, 443)]):
                problems.append("%s %s %s" % (gid, g.get("GroupName"), rules))
        self.check("security-groups-open-only-the-entry", not problems,
                   "; ".join(problems) or "only the entry's group admits any address, on 80 and 443")

    def apis(self):
        stacks = set(self.env.get("stacks", []))
        found = []
        for api in self.r("apigateway-rest.json", {}).get("items", []):
            mine = (api.get("tags") or {}).get("aws:cloudformation:stack-name") in stacks
            self.row("api-gateway", "rest %s %s" % (api.get("id"), api.get("name")), "env" if mine else "other",
                     "internet", "?", "?", str(api.get("endpointConfiguration", {}).get("types")))
            found += [api.get("id")] if mine else []
        for api in self.r("apigateway-v2.json", {}).get("Items", []):
            mine = (api.get("Tags") or {}).get("aws:cloudformation:stack-name") in stacks
            self.row("api-gateway", "%s %s %s" % (api.get("ProtocolType"), api.get("ApiId"), api.get("Name")),
                     "env" if mine else "other", "internet", "?", "?", api.get("ApiEndpoint", ""))
            found += [api.get("ApiId")] if mine else []
        self.check("api-gateway-none-of-the-environment", not found, "APIs of the environment: %s" % (found or "none"))
        marks = [m for m in (self.env.get("alb_dns", "").lower(), self.env.get("entry_domain", "")) if m]
        marks += [b.lower() for b in self.of_type("AWS::S3::Bucket")]
        distributions = (self.r("cloudfront.json", {}).get("DistributionList") or {}).get("Items") or []
        mine = []
        for d in distributions:
            origins = [o.get("DomainName", "").lower() for o in (d.get("Origins") or {}).get("Items", [])]
            aliases = (d.get("Aliases") or {}).get("Items") or []
            is_mine = any(m in o for m in marks for o in origins) or any(a.endswith(self.env.get("entry_domain", "-")) for a in aliases)
            mine += [d["Id"]] if is_mine else []
            self.row("cloudfront", d["Id"], "env" if is_mine else "other", "internet", "?", "?",
                     "%s -> %s" % (d.get("DomainName"), origins))
        self.check("cloudfront-none-of-the-environment", not mine, "distributions of the environment: %s" % (mine or "none"))

    def databases(self):
        public = []
        n = 0
        for db in self.r("rds-instances.json", []):
            mine = db.get("vpc") == self.vpc
            if mine:
                n += 1
                public += [db["id"]] if db.get("public") else []
                self.row("rds", db["id"], "env", "vpc", "no", "database login, TLS",
                         "publicly accessible %s; %s:%s" % (db.get("public"), db.get("address"), db.get("port")))
            elif db.get("public"):
                self.row("rds", db["id"], "other", "internet", "?", "database login", "publicly accessible")
        self.check("rds-none-public", n > 0 and not public, "%d instances of the environment; public: %s" % (n, public or "none"))

    def repositories(self):
        env = self.env.get("env", "")
        env_repos = set(self.of_type("AWS::ECR::Repository"))
        problems, mine = [], 0
        for repo in self.r("ecr-repositories.json", []):
            name = repo["repositoryName"]
            owner = ("env" if name in env_repos or name.startswith("colvenna-lake-%s/" % env)
                     else "release" if name.startswith("colvenna-lake-release/") else "other")
            if owner == "other":
                continue
            mine += 1
            policy = (load(os.path.join(self.raw, "ecr", name.replace("/", "__") + ".policy.json")) or {}).get("policyText")
            public = any(public_principal(s) for s in policy_statements(policy))
            problems += [name] if public else []
            self.row("ecr-repository", name, owner, "internet (registry API)", "no",
                     "IAM of the account" + ("; a repository policy" if policy else ""),
                     "public policy" if public else ("policy, not public" if policy else "no repository policy"))
        self.check("ecr-no-public-policy", mine > 0 and not problems,
                   "%d repositories of the environment and the release; public: %s" % (mine, problems or "none"))

    def secrets(self):
        public, n = [], 0
        for arn in sorted(self.of_type("AWS::SecretsManager::Secret")):
            n += 1
            name = arn.split(":")[-1]
            policy = (load(os.path.join(self.raw, "secrets", name.replace("/", "__") + ".policy.json")) or {}).get("ResourcePolicy")
            if any(public_principal(s) for s in policy_statements(policy)):
                public.append(name)
            self.row("secret", name, "env", "AWS API (IAM)", "no", "IAM" + ("; resource policy" if policy else ""),
                     "public policy" if name in public else ("policy, not public" if policy else "no resource policy"))
        self.check("secrets-no-public-policy", not public, "%d secrets; public: %s" % (n, public or "none"))

    def names(self):
        records = self.r("route53.json", [])
        alb = self.env.get("alb_dns", "").lower()
        problems = []
        for rec in records:
            target = (rec.get("AliasTarget") or {}).get("DNSName", "").lower().rstrip(".")
            values = [v["Value"] for v in rec.get("ResourceRecords", [])]
            name = rec["Name"].rstrip(".")
            if rec["Type"] in ("A", "AAAA"):
                ok = target.endswith(alb) and alb != ""
                self.row("dns-name", name, "env", "internet", "no", "-> the entry", target or str(values))
                problems += [] if ok else ["%s -> %s" % (name, target or values)]
            elif rec["Type"] == "CNAME" and name.startswith("_") and all(v.endswith(".acm-validations.aws.") for v in values):
                self.row("dns-name", name, "env", "-", "-", "-", "certificate validation")
            else:
                problems.append("%s %s" % (name, rec["Type"]))
        self.check("dns-names-only-the-entry", records and not problems,
                   "; ".join(problems) or "%d names, every address one the entry's" % len(records))

    def classify(self):
        for step in (self.addresses, self.load_balancers, self.eks, self.kubernetes, self.buckets, self.cognito,
                     self.lambdas, self.security_groups, self.apis, self.databases, self.repositories,
                     self.secrets, self.names):
            step()
        return self


# --- Whether a denial run may use an evidence directory ---------------------------------------------
def preflight(evidence, env_name, scripts_sha):
    """Why a denial run of the scripts at scripts_sha may not write into this evidence directory (an
    empty list: it may). It needs this environment's inventory, made by the same commit's scripts, and
    nothing of any other run: no earlier denial run (its log or probes), no verdict row but this
    inventory's own (the rows carry their run's id)."""
    env = load(os.path.join(evidence, "raw", "env.json"), {})
    inventory_tsv = os.path.join(evidence, "inventory.tsv")
    if not os.path.exists(inventory_tsv) or not os.path.getsize(inventory_tsv) or env.get("env") != env_name:
        return ["no inventory of %s in %s: run inventory.sh first" % (env_name, evidence)]
    problems = []
    inventory_id = env.get("inventory_id")
    if not inventory_id:
        problems.append("the inventory recorded no id (raw/env.json inventory_id): run inventory.sh into a new directory")
    sha_path = os.path.join(evidence, "scripts-sha.txt")
    sha = read_text(sha_path).strip() if os.path.exists(sha_path) else ""
    if sha != scripts_sha:
        problems.append("the inventory ran the scripts at %s, these are at %s: run both from one commit"
                        % (sha or "an unrecorded commit", scripts_sha))
    for name in ("denials.log", "denials"):
        if os.path.exists(os.path.join(evidence, name)):
            problems.append("%s already holds a denial run (%s): use a new directory and inventory" % (evidence, name))
            break
    others = sorted({row[4] or "(no run id)" for row in read_verdict_rows(os.path.join(evidence, "verdicts.tsv"))
                     if row[4] != inventory_id})
    if others:
        problems.append("verdicts.tsv holds rows of other runs (%s): use a new directory and inventory" % ", ".join(others))
    return problems


# --- The under-privileged probe ---------------------------------------------------------------------
def underprivileged(log_text, role, checks):
    """The checks of run-denials.sh's under-privileged probe. Its pod runs each request as the console's
    service account and prints one line "<check>\t<exit status>\t<answer on one line>" for it. A check
    passes on exactly one line of its own whose request failed (a non-zero exit status) with an
    AccessDenied answer naming the service account's role; no other check's line counts for it."""
    lines = {}
    for line in log_text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            lines.setdefault(parts[0], []).append(parts)
    out = []
    for check in checks:
        mine = lines.get(check, [])
        if len(mine) != 1:
            out.append((check, "FAIL", "%d result lines of this request in the probe's log, not 1" % len(mine)))
            continue
        _, status, answer = mine[0]
        denied = "AccessDenied" in answer and bool(role) and ("assumed-role/%s/" % role) in answer
        ok = status.isdigit() and status != "0" and denied
        out.append((check, "PASS" if ok else "FAIL", "as %s: exit %s, %s" % (role or "no role", status, answer[:240])))
    return out


# --- The denial matrix ------------------------------------------------------------------------------
CLASSES = ("unauthenticated", "unauthorized", "bypass")
# Why the user pool's unauthorized class has no probe of its own (release/security-template.md carries
# the same words).
COGNITO_UNAUTHORIZED_NA = ("the app-level unauthorized class is covered by the console and BI rows; the hosted UI "
                           "returns no access token, and enabling a password flow would bypass TOTP")


def live(check):
    return ("live", check)


def uat(label, check):
    """A verdict of a UAT test in the UAT's summary.tsv: its label (the latest rerun counts) and check."""
    return ("uat", label, check)


def uat_line(label, path, line):
    """A line "<line> expected=<x> actual=<y>" of a file in a UAT test's evidence (the M2 checks)."""
    return ("uat-line", label, path, line)


def inventory(check):
    return ("inventory", check)


def na(reason):
    return ("na", reason)


def entry_app(app):
    """The evidence for one application behind the entry's load balancer."""
    refs = {
        "console": {
            "unauthenticated": [uat("entry", "no-session"), uat("entry", "no-session-api-me"),
                                uat("entry", "forged-session-cookie"), uat("entry", "forged-authorization-code")],
            "unauthorized": [uat("entry", "roles-none"), uat("entry", "no-role-refused")],
            "bypass": [uat("entry", "unknown-host-gets-nothing"), uat("entry", "http-redirects-to-https"),
                       uat("entry", "legacy-tls-refused"), uat("entry", "client-identity-headers-not-trusted"),
                       uat("entry", "pods-unreachable-in-cluster"), uat("entry", "pod-refuses-forged-identity")],
        },
        "bi": {
            "unauthenticated": [uat("bi", "no-session"), uat("bi", "no-session-api-v1-database")],
            "unauthorized": [uat("bi", "roles-erin-refused"), uat("bi", "dave-reaches-nothing"),
                             uat("bi", "lake-table-refused")],
            "bypass": [uat("bi", "pod-unreachable-in-cluster"), uat("bi", "pod-refuses-forged-identity")],
        },
    }[app]
    return {
        "unauthenticated": [live("deny-%s-no-session" % app), live("deny-%s-forged-session-cookie" % app),
                            live("deny-%s-forged-identity-headers" % app)] + refs["unauthenticated"],
        "unauthorized": [live("deny-%s-without-the-role" % app), live("deny-%s-forged-headers-with-a-session" % app)]
        + refs["unauthorized"],
        "bypass": [live("deny-entry-load-balancer-name"), live("deny-entry-unknown-host"), live("deny-entry-plain-http"),
                   live("deny-entry-legacy-tls"), inventory("entry-listeners-sign-in-first")] + refs["bypass"],
    }


def matrix_rows(rows, env):
    """One row per internet-reachable resource of the environment: (resource, what, evidence by class)."""
    out = []
    # The entry's load balancer ID, to recognize its network interfaces' addresses.
    alb_id = env.get("alb_arn", "").split(":loadbalancer/")[-1] if env.get("alb_arn") else ""
    for kind, ident, owner, reachable, _, _, note in rows:
        if owner not in ("env", "release") or not reachable.startswith("internet"):
            continue
        if kind == "load-balancer" and ident == env.get("alb_name"):
            for app in env.get("apps", []):
                out.append(("https://%s.%s" % (app, env.get("entry_domain")), "%s behind the entry" % app,
                            entry_app(app)))
        elif kind == "address" and is_nat_or_spare(note):
            out.append((ident, note, {
                # The control: the probing host's own connections on the probed ports get out.
                "unauthenticated": [live("deny-address-%s-no-inbound" % ident), live("deny-address-positive-control")],
                "unauthorized": [na("nothing listens on it")],
                "bypass": [na("nothing listens on it")]}))
        elif kind == "address" and alb_id and note == "load balancer %s" % alb_id:
            continue  # the load balancer's own network interface addresses: the entry's app rows above
        elif kind == "address":
            out.append((ident, "public address: %s" % note, {c: [] for c in CLASSES}))
        elif kind == "eks-endpoint":
            out.append((ident, "the EKS API's public endpoint", {
                "unauthenticated": [live("deny-eks-anonymous"), live("deny-eks-forged-token")],
                "unauthorized": [live("deny-eks-without-rbac")],
                "bypass": [live("deny-eks-from-another-address"), inventory("eks-endpoint-iam-and-narrow")]}))
        elif kind == "s3-bucket":
            # The console's service account, a role of the environment without S3 permissions, is refused.
            unauthorized = [live("deny-s3-%s-underprivileged" % ident), inventory("s3-buckets-private-and-tls-only"),
                            inventory("s3-account-blocks-public-access")]
            if ident == env.get("warehouse_bucket"):
                path = "catalog-tokens/data-token-checks.txt"
                unauthorized += [uat_line("catalog-tokens", path, "identity without a grant gets none"),
                                 uat_line("catalog-tokens", path, "sibling table: write"),
                                 uat_line("catalog-tokens", path, "other database: list"),
                                 uat_line("catalog-tokens", path, "whole bucket: list"),
                                 uat_line("catalog-tokens", path, "outside the warehouse: write")]
            out.append((ident, "S3 bucket", {
                "unauthenticated": [live("deny-s3-%s-anonymous-list" % ident), live("deny-s3-%s-anonymous-put" % ident)],
                "unauthorized": unauthorized,
                "bypass": [live("deny-s3-%s-plain-http" % ident), live("deny-s3-%s-website" % ident)]}))
        elif kind == "cognito-user-pool":
            out.append((ident, "Cognito user pool: hosted sign-in and API (%s)" % note, {
                "unauthenticated": [live("deny-cognito-userinfo-without-token"), live("deny-cognito-token-without-secret")],
                "unauthorized": [live("deny-cognito-wrong-password"), uat("entry", "wrong-mfa-code-refused"),
                                 uat("entry", "disabled-user-cannot-sign-in"), na(COGNITO_UNAUTHORIZED_NA)],
                "bypass": [live("deny-cognito-sign-up-page"), live("deny-cognito-api-sign-up"),
                           live("deny-cognito-api-password-flow"), uat("entry", "no-self-sign-up")]}))
        elif kind == "ecr-repository":
            if any(r[0] == "image registry" for r in out):
                continue
            out.append(("image registry", "ECR repositories of the environment and the release", {
                "unauthenticated": [live("deny-ecr-anonymous")],
                "unauthorized": [live("deny-ecr-underprivileged"), inventory("ecr-no-public-policy")],
                "bypass": [na("the registry API is the only way to an image; it takes IAM credentials only")]}))
        elif kind == "dns-name" or (kind == "security-group" and note.startswith("the entry's load balancer")):
            continue  # the entry's names and its load balancer's group: its rows
        else:
            out.append((ident, "%s (no denial evidence defined)" % kind, {c: [] for c in CLASSES}))
    return out


def read_verdict_rows(path, ids=None):
    """The rows of a verdicts.tsv, in order: (time, check, result, detail, run id); only those of the
    given run ids when ids is given."""
    rows = []
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    row = tuple(parts[:5] + [""] * (5 - len(parts)))
                    if ids is None or row[4] in ids:
                        rows.append(row)
    return rows


def read_verdicts(path, ids=None):
    """The latest verdict of each check in a verdicts.tsv, of the given run ids: check -> (result, detail)."""
    return {name: (result, detail) for _, name, result, detail, _ in read_verdict_rows(path, ids)}


def run_ids(evidence, run=None):
    """The run ids whose verdict rows count in this evidence directory: its inventory's, and the denial
    run's when given. Rows of any other run (a copied file, an older run) are not read."""
    env = load(os.path.join(evidence, "raw", "env.json"), {})
    return {i for i in (env.get("inventory_id"), run) if i}


def _rerun_order(label, base):
    """0 for the label itself, n for its rerun "<label>-<n>", None for another label."""
    if label == base:
        return 0
    m = re.match(re.escape(base) + r"-(\d+)$", label)
    return int(m.group(1)) if m else None


def read_uat(root):
    """The UAT's summary.tsv: for each test label, its latest run's verdicts (rerun-uat.sh adds -2, -3)."""
    runs = {}
    path = os.path.join(root, "summary.tsv") if root else ""
    if root and os.path.exists(path):
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    runs.setdefault(parts[0], {})[parts[1]] = (parts[2], parts[3] if len(parts) > 3 else "")
    return runs


def evaluate(item, verdicts, uat_root, uat_runs):
    """One piece of evidence: (result, where). result is PASS, FAIL, MISSING or N/A."""
    kind = item[0]
    if kind == "na":
        return "N/A", item[1]
    if kind in ("live", "inventory"):
        result, detail = verdicts.get(item[1], ("MISSING", "not run"))
        return result, "%s: %s" % (item[1], detail[:160])
    if kind == "uat":
        _, base, check = item
        if not uat_root:
            return "NOT_GIVEN", "UAT %s/%s: no UAT evidence given" % (base, check)
        candidates = sorted(((_rerun_order(l, base), l) for l in uat_runs if _rerun_order(l, base) is not None
                             and check in uat_runs[l]), reverse=True)
        if not candidates:
            return "MISSING", "UAT %s/%s: not in summary.tsv" % (base, check)
        label = candidates[0][1]
        result, detail = uat_runs[label][check]
        return result, "UAT %s/%s: %s" % (label, check, detail[:120])
    if kind == "uat-line":
        _, base, path, line = item
        if not uat_root:
            return "NOT_GIVEN", "UAT %s: no UAT evidence given" % path
        rel = path.split("/", 1)[1]
        labels = sorted(((_rerun_order(d, base), d) for d in os.listdir(uat_root)
                         if _rerun_order(d, base) is not None and os.path.exists(os.path.join(uat_root, d, rel))),
                        reverse=True)
        if not labels:
            return "MISSING", "UAT %s: no such file" % path
        label = labels[0][1]
        with open(os.path.join(uat_root, label, rel)) as f:
            for text in f:
                m = re.match(r"^%s expected=(\S+) actual=(\S+)$" % re.escape(line), text.strip())
                if m:
                    ok = m.group(1) == m.group(2)
                    return ("PASS" if ok else "FAIL"), "UAT %s/%s: %s" % (label, rel, text.strip())
        return "MISSING", "UAT %s/%s: no line %r" % (label, rel, line)
    raise ValueError(item)


def cell(items, verdicts, uat_root, uat_runs):
    """A class's result: FAIL if any evidence failed; MISSING if any is missing (a probe not run, a UAT
    check the given UAT evidence lacks) or none passed; N/A if there are only reasons; else PASS. UAT
    references without UAT evidence (NOT_GIVEN) neither pass nor fail a class."""
    results = [evaluate(i, verdicts, uat_root, uat_runs) for i in items]
    kinds = {r for r, _ in results}
    if "FAIL" in kinds:
        outcome = "FAIL"
    elif "MISSING" in kinds:
        outcome = "MISSING"
    elif "PASS" in kinds:
        outcome = "PASS"
    elif kinds == {"N/A"}:
        outcome = "N/A"
    else:
        outcome = "MISSING"
    return outcome, results


def read_rows(path):
    rows = []
    with open(path) as f:
        for line in f:
            if line.startswith("kind\t"):
                continue
            parts = line.rstrip("\n").split("\t")
            rows.append(tuple(parts + [""] * (7 - len(parts)))[:7])
    return rows


def matrix(evidence, run, uat_root=None):
    """The denial matrix of this evidence directory, from its inventory's and the denial run's verdicts
    only; writes matrix.tsv and matrix.md and returns its check."""
    env = load(os.path.join(evidence, "raw", "env.json"), {})
    rows = read_rows(os.path.join(evidence, "inventory.tsv"))
    verdicts = read_verdicts(os.path.join(evidence, "verdicts.tsv"), run_ids(evidence, run))
    uat_runs = read_uat(uat_root)
    table = []
    for resource, what, evidence_by_class in matrix_rows(rows, env):
        cells = {c: cell(evidence_by_class.get(c, []), verdicts, uat_root, uat_runs) for c in CLASSES}
        table.append((resource, what, cells))
    with open(os.path.join(evidence, "matrix.tsv"), "w") as f:
        f.write("resource\tclass\toutcome\tevidence result\tevidence\n")
        for resource, _, cells in table:
            for c in CLASSES:
                outcome, results = cells[c]
                for result, where in results or [("MISSING", "no evidence defined")]:
                    f.write("%s\t%s\t%s\t%s\t%s\n" % (resource, c, outcome, result, where.replace("\t", " ")))
    with open(os.path.join(evidence, "matrix.md"), "w") as f:
        f.write("| 资源 | 说明 | 未认证 | 越权 | 绕过入口 |\n| --- | --- | --- | --- | --- |\n")
        for resource, what, cells in table:
            def show(c):
                # N/A items count neither way: they are shown with their reasons.
                outcome, results = cells[c]
                reasons = [where for r, where in results if r == "N/A"]
                if outcome == "N/A":
                    return "N/A：" + "；".join(reasons)
                counted = [r for r, _ in results if r not in ("NOT_GIVEN", "N/A")]
                passed = sum(1 for r in counted if r == "PASS")
                skipped = sum(1 for r, _ in results if r == "NOT_GIVEN")
                notes = (["另 %d 项 UAT 未提供" % skipped] if skipped else []) + ["另 1 项 N/A：" + w for w in reasons]
                return "%s（%d/%d 项%s）" % (outcome, passed, len(counted), "".join("，" + n for n in notes))
            f.write("| `%s` | %s | %s | %s | %s |\n" % (resource, what, show("unauthenticated"), show("unauthorized"), show("bypass")))
    bad = ["%s/%s %s" % (r, c, cells[c][0]) for r, _, cells in table for c in CLASSES if cells[c][0] not in ("PASS", "N/A")]
    reachable = [r for r in rows if r[2] in ("env", "release") and r[3].startswith("internet")]
    return [("denial-matrix-complete", "PASS" if table and not bad else "FAIL",
             "; ".join(bad) or "%d internet-reachable resources of the environment, %d matrix rows, every class refused"
             % (len(reachable), len(table)))]


# --- Secrets in the evidence ------------------------------------------------------------------------
SECRET_PATTERNS = [
    ("AWS access key id", re.compile(r"\b(AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("AWS secret key", re.compile(r"(?i)(aws_secret_access_key|SecretAccessKey|fs\.s3a\.secret\.key)[\"'=: ]+[A-Za-z0-9/+]{30,}")),
    ("session token", re.compile(r"(?i)(SessionToken|aws_session_token)[\"'=: ]+[A-Za-z0-9/+=]{40,}")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("signed JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}")),
    ("client secret", re.compile(r"(?i)\"ClientSecret\"\s*:\s*\"[^\"]+\"")),
    ("bearer token", re.compile(r"(?i)authorization:\s*bearer\s+[A-Za-z0-9._-]{20,}")),
    ("colvenna token", re.compile(r"\bcolvenna_[A-Za-z0-9_-]{24,}")),
]


def scrub(evidence):
    """Files and lines of the evidence that look like a credential (never the match itself)."""
    hits = []
    for dirpath, _, files in os.walk(evidence):
        for name in files:
            path = os.path.join(dirpath, name)
            try:
                with open(path, errors="replace") as f:
                    for n, line in enumerate(f, 1):
                        for what, pattern in SECRET_PATTERNS:
                            if pattern.search(line):
                                hits.append("%s:%d %s" % (os.path.relpath(path, evidence), n, what))
            except (OSError, UnicodeDecodeError):
                continue
    return hits


# The run's own credentials, by type, as run-denials.sh and entry.py keep them in the run's private
# state directory: <user>.pw (the users' passwords, the refused sign-up's), <user>.totp (the TOTP
# secret entry.py saves at enrolment), <user>.cookies (entry.py's Mozilla cookie jar), client-secret
# (the user pool client's), sa-token (the service account's).
SECRET_TYPES = ("password", "totp", "cookie", "client-secret", "sa-token")
# Shorter values (a cookie's flag) are no credential and would match by chance.
MIN_SECRET = 8


def cookie_values(text):
    """The values of a Mozilla cookies.txt: the seventh tab-separated field of each cookie line. An
    HttpOnly cookie's line starts "#HttpOnly_"; other lines starting "#" are comments."""
    values = []
    for line in text.splitlines():
        if line.startswith("#HttpOnly_"):
            line = line[len("#HttpOnly_"):]
        elif line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) >= 7 and fields[6]:
            values.append(fields[6])
    return values


def secret_values(state):
    """The run's own credentials in its state directory: type -> values."""
    found = lambda pattern: sorted(glob.glob(os.path.join(state, pattern)))  # noqa: E731
    values = {t: [] for t in SECRET_TYPES}
    values["password"] = [read_text(p).strip() for p in found("*.pw")]
    values["totp"] = [read_text(p).strip() for p in found("*.totp")]
    for p in found("*.cookies"):
        values["cookie"] += cookie_values(read_text(p))
    for kind in ("client-secret", "sa-token"):
        values[kind] = [read_text(p).strip() for p in found(kind)]
    return values


def exact_scan(evidence, state, made):
    """The check that no exact copy of the run's own credentials (secret_values) is in any file of the
    evidence. It fails too when a type the run made (made: SECRET_TYPES keys) had no value to look
    for: the scan would have looked in the wrong place. It names files and types, never a value."""
    values = secret_values(state)
    problems = ["%s: the run made some, but none was found to scan for" % t for t in sorted(set(made)) if not values.get(t)]
    needles = [(t, v.encode()) for t in SECRET_TYPES for v in values[t] if len(v) >= MIN_SECRET]
    short = sum(1 for t in SECRET_TYPES for v in values[t] if len(v) < MIN_SECRET)
    files = 0
    for dirpath, _, names in os.walk(evidence):
        for name in sorted(names):
            path = os.path.join(dirpath, name)
            with open(path, "rb") as f:
                data = f.read()
            files += 1
            hit = sorted({t for t, v in needles if v in data})
            problems += ["a %s value in %s" % (t, os.path.relpath(path, evidence)) for t in hit]
    scanned = "%d files for %s values%s" % (files, ", ".join("%d %s" % (len(values[t]), t) for t in SECRET_TYPES),
                                         " (%d shorter than %d characters not looked for)" % (short, MIN_SECRET) if short else "")
    return ("evidence-has-no-actual-credentials", "FAIL" if problems else "PASS",
            "; ".join(problems[:20]) + "; scanned " + scanned if problems else "none found; scanned " + scanned)


def read_text(path):
    with open(path) as f:
        return f.read()


def _md(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


# The repository's rule: no account id, real domain or URL, ARN or internal address in documents;
# the deployment's values stay in the evidence and in the uncommitted settings. A Redaction replaces
# first the environment's own values (raw/env.json, exactly), then these patterns, in this order. A
# placeholder never matches a later pattern, so redacting twice changes nothing.
ENV_PLACEHOLDERS = (("alb_arn", "<ARN>"), ("signin", "<Cognito 托管登录地址>"), ("alb_dns", "<入口负载均衡器的 DNS 名>"),
                    ("entry_domain", "<入口域名>"), ("cluster", "<EKS 集群>"), ("vpc", "<VPC>"),
                    ("pool", "<用户池>"), ("client", "<应用客户端>"), ("warehouse_bucket", "<仓库桶>"),
                    ("alb_name", "<入口负载均衡器>"))
REDACTIONS = [
    # Not a trailing dot or the backslash of an escaped "\|": the ARN ends before them.
    (re.compile(r"arn:aws[a-z-]*:[^\s`'\"|,;\])\\]*[^\s`'\"|,;\])\\.]"), "<ARN>"),
    # A Cognito host, also cut short ("….auth.us-east-2.amaz" in a probe's 60 characters).
    (re.compile(r"[\w-]+\.auth\.[a-z]{2}(?:-[a-z]+)+-\d(?:\.[a-z]+)*"), "<Cognito 托管登录域名>"),
    # Application and network load balancers (….elb.amazonaws.com, ….elb.<region>.amazonaws.com).
    (re.compile(r"[\w.-]+\.elb(?:\.[a-z0-9-]+)?\.amazonaws\.com", re.I), "<负载均衡器的 DNS 名>"),
    (re.compile(r"[\w.-]+\.eks\.amazonaws\.com", re.I), "<EKS 端点>"),
    (re.compile(r"\bCluster[0-9A-F]{8}-[0-9a-f]{32}\b"), "<EKS 集群>"),
    (re.compile(r"\b[a-z]{2}(?:-[a-z]+)+-\d_[A-Za-z0-9]{9}\b"), "<用户池>"),
    (re.compile(r"\b(vpc|vpce|sg|subnet|eni|eipalloc|eipassoc|nat|igw|tgw|pcx|i|vol|snap|ami|rtb|acl|lt)"
                r"-[0-9a-f]{8,17}\b"), r"<\1-…>"),
    # A load balancer or target group as the end of its ARN has it, "<type>/<name>/<ID>" (an ELB
    # network interface's description): the ID, and the name's generated suffix (CloudFormation's 12
    # characters). The entry's name is already <入口负载均衡器>.
    (re.compile(r"\b(app|net|gwy|targetgroup)/([^\s/]+?)(-[A-Za-z0-9]{12})?/[0-9a-f]{16}\b"),
     lambda m: "%s/%s%s/<…>" % (m.group(1), m.group(2), "-<…>" if m.group(3) else "")),
    # A bucket's generated name: <stack><logical id><8 hex>-<12 random>.
    (re.compile(r"\b([a-z0-9-]*[a-z])[0-9a-f]{8}-[0-9a-z]{12}\b"), r"\1<…>"),
    # A CloudFormation physical name's random suffix: 12 characters with a digit in them or a lower
    # case letter right before an upper case one (a word such as "Certificates" has neither), after
    # the stack's name and the logical id, or a nested construct's prefix and the logical id (CDK's
    # provider functions: <stack>-aws-OnEventHandler42BEBAE0-<12>).
    (re.compile(r"\b([A-Z][A-Za-z0-9]*(?:-[A-Za-z0-9]+){1,2})-(?=[A-Za-z0-9]{12}\b)"
                r"(?=[A-Za-z0-9]*[0-9]|[A-Za-z0-9]*[a-z][A-Z])[A-Za-z0-9]{12}\b"), r"\1-<…>"),
]
PRIVATE_IPV4 = re.compile(r"^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|"
                          r"100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.)")
# No letter or dot around it (v1.2.3.4 is a version); a hyphen is fine, a check's name holds the
# address (deny-address-<address>-no-inbound). A four-part version alone reads as an address: when in
# doubt, the value is hidden.
IPV4 = re.compile(r"(?<![\w.])(\d{1,3}(?:\.\d{1,3}){3})(?!\w|\.\d)")
IPV6 = re.compile(r"(?<![\w:])[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}(?![\w:])")
# Outside a code span, these read as HTML tags (markdownlint MD033); <ACCOUNT_ID> does not.
TAG_LIKE = re.compile(r"(<(?:ARN|VPC)>)")
# An address's placeholder, as a document redacted before holds it.
NUMBERED = re.compile(r"<(公网地址|内网地址|IPv6 地址) (\d+)>")
HOST_ADDRESS = "<执行机的出口地址>"


class Redaction:
    """Markdown for the repository, one document at a time (a section, a file): the environment's
    values and the patterns above become placeholders; 0.0.0.0/0 and ::/0 stay, they are what a
    check is about. Each address gets one number for the whole document, however many texts of it
    are redacted (a section's cells, each before it is cut short): the same address always the same
    number, two addresses never the same one, so that rows and check names stay apart. A number the
    text already holds (a document redacted before) is not given again. The host's own address
    (Inventory.host_addresses) is named instead. A text that goes inside a code span (code: a
    resource, a check's name) keeps <ARN> and <VPC> as they are; elsewhere they become code."""

    def __init__(self, env, host=()):
        self.env = env
        self.host = set(host)
        self.numbers = {}
        self.highest = {}

    def address(self, kind, value):
        if value in self.host:
            return HOST_ADDRESS
        if (kind, value) not in self.numbers:
            self.numbers[(kind, value)] = self.highest[kind] = self.highest.get(kind, 0) + 1
        return "<%s %d>" % (kind, self.numbers[(kind, value)])

    def __call__(self, text, code=False):
        env = self.env
        for kind, n in NUMBERED.findall(text):
            self.highest[kind] = max(self.highest.get(kind, 0), int(n))
        for key, placeholder in ENV_PLACEHOLDERS:
            if isinstance(env.get(key), str) and env[key]:
                text = text.replace(env[key], placeholder)
        for pattern, placeholder in REDACTIONS:
            text = pattern.sub(placeholder, text)

        def ipv4(m):
            value = m.group(1)
            # 0.0.0.0 is what an open-CIDR check is about, so it stays; nothing binds to it here.
            if value == "0.0.0.0" or any(int(octet) > 255 for octet in value.split(".")):  # nosec B104
                return value
            return self.address("内网地址" if PRIVATE_IPV4.match(value) else "公网地址", value)

        text = IPV4.sub(ipv4, text)
        text = IPV6.sub(lambda m: self.address("IPv6 地址", m.group(0))
                        if "::" in m.group(0) or m.group(0).count(":") >= 3 else m.group(0), text)
        if env.get("account"):
            text = re.sub(r"(?<!\d)%s(?!\d)" % re.escape(str(env["account"])), "<ACCOUNT_ID>", text)
        out, fenced = [], False
        for line in text.split("\n"):
            if line.lstrip().startswith("```"):
                fenced = not fenced
            elif not fenced and not code:
                line = "`".join(TAG_LIKE.sub(r"`\1`", part) if i % 2 == 0 else part
                                for i, part in enumerate(line.split("`")))
            out.append(line)
        return "\n".join(out)


def redact(text, env, host=()):
    """One text as a document of its own: Redaction."""
    return Redaction(env, host)(text)


def outcome_counts(results):
    """"<n> 项 PASS，<n> 项 N/A，<n> 项 FAIL" of a list of results: PASS and FAIL always, any other
    outcome (N/A first) when there is one."""
    counts = {}
    for r in results:
        counts[r] = counts.get(r, 0) + 1
    order = ["PASS", "N/A", "FAIL"] + sorted(set(counts) - {"PASS", "N/A", "FAIL"})
    return "，".join("%d 项 %s" % (counts.get(k, 0), k) for k in order if k in counts or k in ("PASS", "FAIL"))


def section(evidence, run=None):
    """The release check's section of one environment, in Markdown: identity, the inventory's checks,
    what is reachable from the internet, the denial matrix and the probes, and the rest of the account.
    Only the verdicts of this directory's inventory and of the denial run given count. One Redaction
    numbers the addresses of the whole section: each cell is redacted where it comes, before it is cut
    short, so the numbers follow the section's order; a last pass over the whole replaces the
    environment's own values in the header."""
    inv = Inventory(evidence)
    env = inv.env
    hide = Redaction(env, inv.host_addresses())
    rows = read_rows(os.path.join(evidence, "inventory.tsv"))
    others = read_rows(os.path.join(evidence, "others.tsv"))
    verdict_rows = read_verdict_rows(os.path.join(evidence, "verdicts.tsv"), run_ids(evidence, run))
    latest = {}
    for t, name, result, detail, _ in verdict_rows:
        latest[name] = (t, result, detail)
    first_line = lambda name: (read_text(os.path.join(evidence, name)).split("\n", 1)[0].strip()  # noqa: E731
                               if os.path.exists(os.path.join(evidence, name)) else "")
    out = ["### `%s`（%s）" % (env.get("env"), env.get("region")), "",
           "| 项 | 值 |", "| --- | --- |",
           "| 账号 | `%s` |" % env.get("account"),
           "| VPC、集群 | `%s`、`%s` |" % (env.get("vpc"), env.get("cluster")),
           "| 入口域名 | `%s` |" % env.get("entry_domain"),
           "| 时间（UTC） | %s 至 %s |" % (verdict_rows[0][0] if verdict_rows else "-", verdict_rows[-1][0] if verdict_rows else "-"),
           "| 清点 | %s |" % _md(hide(first_line("inventory.log"))),
           "| 拒绝检查 | %s |" % _md(hide(first_line("denials.log")) or "未运行"),
           "| 原始证据 | `%s` |" % evidence, ""]
    out += ["检查共 %d 项：%s。N/A 不算通过也不算失败，理由见细节。" % (len(latest), outcome_counts([r for _, r, _ in latest.values()])), ""]
    out += ["#### 清点的检查", "", "| 检查 | 结果 | 细节 |", "| --- | --- | --- |"]
    for name, (_, result, detail) in latest.items():
        if not name.startswith(("deny-", "denial-matrix", "evidence-has")):
            out.append("| `%s` | %s | %s |" % (hide(name, code=True), result, _md(hide(detail)[:300])))
    out += ["", "#### 公网可达的资源（本环境与发布镜像仓库）", "", "| 类型 | 资源 | 归属 | 认证 | 说明 |",
            "| --- | --- | --- | --- | --- |"]
    for kind, ident, owner, reachable, _, auth, note in rows:
        if reachable.startswith("internet"):
            out.append("| %s | `%s` | %s | %s | %s |" % (kind, _md(hide(ident, code=True)), owner, _md(hide(auth)),
                                                          _md(hide(note)[:160])))
    matrix_md = os.path.join(evidence, "matrix.md")
    out += ["", "#### 三类拒绝", ""]
    out += [hide(read_text(matrix_md).rstrip("\n")) if os.path.exists(matrix_md) else "未生成（先运行 run-denials.sh）"]
    out += ["", "逐项证据见 `matrix.tsv`。", "", "#### 拒绝检查", "", "| 检查 | 结果 | 细节 |", "| --- | --- | --- |"]
    for name, (_, result, detail) in latest.items():
        if name.startswith(("deny-", "denial-matrix", "evidence-has")):
            out.append("| `%s` | %s | %s |" % (hide(name, code=True), result, _md(hide(detail)[:300])))
    counts = {}
    for kind, *_ in others:
        counts[kind] = counts.get(kind, 0) + 1
    out += ["", "#### 同一区域里账号的其他资源（不属于本环境，不计入结论）", "",
            "| 类型 | 公网可达的数量 |", "| --- | --- |"]
    out += ["| %s | %d |" % (k, v) for k, v in sorted(counts.items())] or ["| - | 0 |"]
    out += ["", "明细见 `others.tsv`。Lambda 函数 URL 的检查覆盖账号所有已启用区域的所有函数，不分归属。", ""]
    return hide("\n".join(out))


def write_inventory(evidence, inv):
    header = "kind\tid\towner\treachable\tanonymous\tauth\tnote\n"
    with open(os.path.join(evidence, "inventory.tsv"), "w") as f:
        f.write(header)
        for r in inv.rows:
            f.write("\t".join(str(x).replace("\t", " ") for x in r) + "\n")
    with open(os.path.join(evidence, "others.tsv"), "w") as f:
        f.write(header)
        for r in inv.others:
            f.write("\t".join(str(x).replace("\t", " ") for x in r) + "\n")


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)
    cmd, evidence = argv[1], argv[2]
    if cmd == "classify":
        inv = Inventory(evidence).classify()
        write_inventory(evidence, inv)
        checks = inv.checks
    elif cmd == "preflight" and len(argv) == 5:
        problems = preflight(evidence, argv[3], argv[4])
        if problems:
            sys.exit("Refusing %s: %s" % (evidence, "; ".join(problems)))
        return
    elif cmd == "underprivileged" and len(argv) >= 5:
        # The first argument is the probe's log here, not an evidence directory.
        checks = underprivileged(read_text(evidence) if os.path.exists(evidence) else "", argv[3], argv[4:])
    elif cmd == "matrix" and len(argv) >= 4:
        checks = matrix(evidence, argv[3], argv[4] if len(argv) > 4 and argv[4] else None)
    elif cmd == "section":
        print(section(evidence, argv[3] if len(argv) > 3 else None))
        return
    elif cmd == "redact" and len(argv) == 4:
        # A document written before section() redacted, with this environment's values.
        inv = Inventory(evidence)
        env = inv.env
        if not env.get("account"):
            sys.exit("Refusing to redact %s: no account in %s/raw/env.json" % (argv[3], evidence))
        with open(argv[3], encoding="utf-8") as f:
            text = redact(f.read(), env, inv.host_addresses())
        with open(argv[3], "w", encoding="utf-8") as f:
            f.write(text)
        return
    elif cmd == "scrub":
        hits = scrub(evidence)
        checks = [("evidence-has-no-secrets", "PASS" if not hits else "FAIL",
                   "; ".join(hits[:20]) or "no credential-like string in %s" % evidence)]
    elif cmd == "exact-scan" and len(argv) == 5:
        checks = [exact_scan(evidence, argv[3], argv[4].split())]
    else:
        sys.exit(__doc__)
    for name, result, detail in checks:
        print("%s\t%s\t%s" % (name, result, detail.replace("\t", " ").replace("\n", " ")))


if __name__ == "__main__":
    main(sys.argv)
