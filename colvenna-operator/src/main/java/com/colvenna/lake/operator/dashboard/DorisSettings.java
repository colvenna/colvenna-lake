package com.colvenna.lake.operator.dashboard;

import java.util.Optional;
import java.util.function.Function;

/**
 * Where the platform's Doris is (deploy/scripts/doris/install-doris.sh), from the environment the
 * Helm chart sets: its FE's MySQL endpoint and the Secret with root's password; Doris reads the
 * lake through the colvenna-catalog the operator uses. Without a host there is no Doris, and
 * dashboards wait for one.
 */
public record DorisSettings(
        String host, int port, String adminNamespace, String adminSecret, String catalogUri) {

    public static Optional<DorisSettings> fromEnvironment(
            Function<String, String> env, String catalogUri) {
        String host = env.apply("COLVENNA_DORIS_HOST");
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String secret = env.apply("COLVENNA_DORIS_ADMIN_SECRET");
        if (secret == null || !secret.matches("[a-z0-9-]{1,63}/[a-z0-9.-]{1,253}")) {
            throw new IllegalArgumentException(
                    "COLVENNA_DORIS_ADMIN_SECRET must be <namespace>/<name>, not " + secret);
        }
        String port = env.apply("COLVENNA_DORIS_PORT");
        return Optional.of(
                new DorisSettings(
                        host,
                        port == null || port.isBlank() ? 9030 : Integer.parseInt(port),
                        secret.split("/", 2)[0],
                        secret.split("/", 2)[1],
                        catalogUri));
    }
}
