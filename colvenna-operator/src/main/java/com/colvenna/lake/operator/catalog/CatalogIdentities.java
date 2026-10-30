package com.colvenna.lake.operator.catalog;

import com.colvenna.lake.operator.crd.CatalogIdentity;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One colvenna-catalog identity per declared resource: a principal named after the resource and its
 * uid (a disabled principal's name can never be reused), a bearer token in a Secret the resource's
 * jobs mount, and exactly the grants the resource needs.
 *
 * <p>Tokens and grants have expiries, so they are renewed ahead of time. Paimon clients never
 * refresh a bearer token, so a new token reaches the jobs through a restart: the Secret and the
 * pod-template annotation change together, and the old token is revoked once the jobs run with the
 * new one ({@link #revokePrevious}).
 */
public final class CatalogIdentities {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogIdentities.class);
    static final Duration TOKEN_TTL = Duration.ofDays(30);
    static final Duration TOKEN_RENEW_BEFORE = Duration.ofDays(10);
    static final Duration GRANT_TTL = Duration.ofDays(90);
    static final Duration GRANT_RENEW_BEFORE = Duration.ofDays(30);
    public static final String TOKEN_KEY = "token";

    private final CatalogClient catalog;
    private final KubernetesClient kube;

    public CatalogIdentities(CatalogClient catalog, KubernetesClient kube) {
        this.catalog = catalog;
        this.kube = kube;
    }

    /** {@code <kind>.<namespace>.<name>.<uid prefix>}, hashed if it would not fit. */
    public static String principalName(String kind, HasMetadata owner) {
        String uid = owner.getMetadata().getUid().replace("-", "").substring(0, 8);
        String name =
                kind
                        + "."
                        + owner.getMetadata().getNamespace()
                        + "."
                        + owner.getMetadata().getName()
                        + "."
                        + uid;
        if (name.length() <= 128) {
            return name;
        }
        return kind + "." + sha256(name).substring(0, 32) + "." + uid;
    }

    public static String tokenSecretName(HasMetadata owner) {
        return owner.getMetadata().getName() + "-catalog-token";
    }

    /**
     * Makes the principal, its token Secret and its grants match. Returns the identity to record; a
     * changed {@code tokenId} means the jobs must restart onto the new token.
     */
    public CatalogIdentity ensure(
            HasMetadata owner,
            String kind,
            String purpose,
            List<Grant> wanted,
            CatalogIdentity current,
            Instant now) {
        CatalogIdentity next = copy(current);
        next.principal = principalName(kind, owner);
        catalog.ensurePrincipal(
                next.principal,
                kind
                        + " "
                        + owner.getMetadata().getNamespace()
                        + "/"
                        + owner.getMetadata().getName());

        Secret secret =
                kube.secrets()
                        .inNamespace(owner.getMetadata().getNamespace())
                        .withName(tokenSecretName(owner))
                        .get();
        boolean expiring =
                next.tokenExpiresAt == null
                        || Instant.parse(next.tokenExpiresAt)
                                .isBefore(now.plus(TOKEN_RENEW_BEFORE));
        boolean present =
                secret != null
                        && secret.getData() != null
                        && secret.getData().containsKey(TOKEN_KEY)
                        && next.tokenId != null
                        && secret.getMetadata().getAnnotations() != null
                        && next.tokenId.equals(
                                secret.getMetadata()
                                        .getAnnotations()
                                        .get("colvenna-lake.io/token-id"));
        if (!present || expiring) {
            CatalogClient.IssuedToken token =
                    catalog.issueToken(next.principal, TOKEN_TTL, tokenSecretName(owner));
            writeSecret(owner, token);
            if (next.tokenId != null && !next.tokenId.equals(next.previousTokenId)) {
                if (next.previousTokenId != null) {
                    // A second rotation before the first finished: the older one is unused.
                    catalog.revokeToken(next.previousTokenId);
                }
                next.previousTokenId = next.tokenId;
            }
            next.tokenId = token.id();
            next.tokenExpiresAt = token.expiresAt().toString();
            LOG.info("Issued token {} for {}", token.id(), next.principal);
        }

        // A token issued in a round that then failed (before its Secret or the status was written)
        // is in use nowhere: revoke every active token but the current and the replaced one.
        for (String id : catalog.activeTokenIds(next.principal)) {
            if (!id.equals(next.tokenId) && !id.equals(next.previousTokenId)) {
                catalog.revokeToken(id);
            }
        }

        List<Grant> existing = catalog.grants(next.principal);
        List<String> ids = new ArrayList<>();
        Instant earliest = null;
        for (Grant want : wanted) {
            Grant kept = null;
            for (Grant have : existing) {
                if (have.sameAccess(want)) {
                    if (have.expiresAt().isAfter(now.plus(GRANT_RENEW_BEFORE))) {
                        kept = have;
                    } else {
                        // No update in the admin API: the duplicate key ignores expiry.
                        catalog.revokeGrant(have.id());
                    }
                }
            }
            if (kept == null) {
                kept =
                        catalog.grant(
                                new Grant(
                                        null,
                                        next.principal,
                                        want.scope(),
                                        want.database(),
                                        want.object(),
                                        want.privilege(),
                                        "ALLOW",
                                        purpose,
                                        "colvenna-operator",
                                        want.approval(),
                                        now.plus(GRANT_TTL)));
            }
            ids.add(kept.id());
            if (earliest == null || kept.expiresAt().isBefore(earliest)) {
                earliest = kept.expiresAt();
            }
        }
        for (Grant have : existing) {
            if (wanted.stream().noneMatch(have::sameAccess)) {
                catalog.revokeGrant(have.id());
            }
        }
        next.grantIds = ids;
        next.grantsExpireAt = earliest == null ? null : earliest.toString();
        return next;
    }

    /** Revokes the token a rotation replaced, once every job runs with the new one. */
    public void revokePrevious(CatalogIdentity identity) {
        if (identity.previousTokenId != null) {
            catalog.revokeToken(identity.previousTokenId);
            identity.previousTokenId = null;
        }
    }

    /** Revokes everything and disables the principal; the resource is being deleted. */
    public void remove(HasMetadata owner, String kind) {
        String principal = principalName(kind, owner);
        if (catalog.principal(principal).isEmpty()) {
            return;
        }
        for (Grant grant : catalog.grants(principal)) {
            catalog.revokeGrant(grant.id());
        }
        for (String token : catalog.activeTokenIds(principal)) {
            catalog.revokeToken(token);
        }
        catalog.disablePrincipal(principal);
    }

    private void writeSecret(HasMetadata owner, CatalogClient.IssuedToken token) {
        Secret secret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName(tokenSecretName(owner))
                        .withNamespace(owner.getMetadata().getNamespace())
                        .withLabels(Map.of("app.kubernetes.io/managed-by", "colvenna-operator"))
                        .withAnnotations(
                                Map.of(
                                        "colvenna-lake.io/token-id",
                                        token.id(),
                                        "colvenna-lake.io/token-expires-at",
                                        token.expiresAt().toString()))
                        .withOwnerReferences(
                                new OwnerReferenceBuilder()
                                        .withApiVersion(owner.getApiVersion())
                                        .withKind(owner.getKind())
                                        .withName(owner.getMetadata().getName())
                                        .withUid(owner.getMetadata().getUid())
                                        .withController(true)
                                        .withBlockOwnerDeletion(true)
                                        .build())
                        .endMetadata()
                        .withType("Opaque")
                        .withData(
                                Map.of(
                                        TOKEN_KEY,
                                        java.util.Base64.getEncoder()
                                                .encodeToString(
                                                        token.token()
                                                                .getBytes(StandardCharsets.UTF_8))))
                        .build();
        kube.secrets()
                .inNamespace(owner.getMetadata().getNamespace())
                .resource(secret)
                .createOr(NonDeletingOperation::update);
    }

    private static CatalogIdentity copy(CatalogIdentity current) {
        CatalogIdentity next = new CatalogIdentity();
        if (current != null) {
            next.principal = current.principal;
            next.tokenId = current.tokenId;
            next.tokenExpiresAt = current.tokenExpiresAt;
            next.previousTokenId = current.previousTokenId;
            next.grantIds = new ArrayList<>(current.grantIds);
            next.grantsExpireAt = current.grantsExpireAt;
        }
        return next;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
