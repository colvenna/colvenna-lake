package com.colvenna.lake.operator.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * A colvenna-catalog grant (colvenna-catalog LLD, 授权). {@code id} and {@code expiresAt} come back
 * from the server; a request leaves {@code id} null.
 */
public record Grant(
        String id,
        String principal,
        String scope,
        String database,
        String object,
        String privilege,
        String effect,
        String purpose,
        String approvedBy,
        String approval,
        Instant expiresAt) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Grant database(
            String principal,
            String database,
            String privilege,
            String purpose,
            String approval,
            Instant expiresAt) {
        return new Grant(
                null,
                principal,
                "DATABASE",
                database,
                null,
                privilege,
                "ALLOW",
                purpose,
                "colvenna-operator",
                approval,
                expiresAt);
    }

    /** A table-scoped request; the purpose and expiry are the identity's (CatalogIdentities). */
    public static Grant table(
            String principal, String database, String table, String privilege, String approval) {
        return new Grant(
                null,
                principal,
                "TABLE",
                database,
                table,
                privilege,
                "ALLOW",
                null,
                "colvenna-operator",
                approval,
                null);
    }

    /** Whether this grant gives the same access as {@code other}, ignoring id and expiry. */
    public boolean sameAccess(Grant other) {
        return principal.equals(other.principal)
                && scope.equals(other.scope)
                && java.util.Objects.equals(database, other.database)
                && java.util.Objects.equals(object, other.object)
                && privilege.equals(other.privilege);
    }

    /** Whether this grant lets its principal read the database, or any table in it. */
    public boolean readsDatabase(String db, Instant now) {
        boolean covers =
                scope.equals("CATALOG") || (scope.equals("DATABASE") && db.equals(database));
        return covers && expiresAt.isAfter(now) && !"DENY".equals(effect);
    }

    ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("principal", principal);
        node.put("scope", scope);
        if (database != null) {
            node.put("database", database);
        }
        if (object != null) {
            node.put("object", object);
        }
        node.put("privilege", privilege);
        node.put("effect", effect == null ? "ALLOW" : effect);
        node.put("purpose", purpose);
        node.put("approvedBy", approvedBy);
        node.put("approval", approval);
        node.put("expiresAt", expiresAt.toString());
        return node;
    }

    static Grant from(JsonNode node) {
        return new Grant(
                node.path("id").asText(),
                node.path("principal").asText(),
                node.path("scope").asText(),
                node.path("database").isTextual() ? node.path("database").asText() : null,
                node.path("object").isTextual() ? node.path("object").asText() : null,
                node.path("privilege").asText(),
                node.path("effect").asText("ALLOW"),
                node.path("purpose").asText(),
                node.path("approvedBy").asText(),
                node.path("approval").asText(),
                Instant.parse(node.path("expiresAt").asText()));
    }
}
