package com.colvenna.lake.operator.source;

import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeSourceSpec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Where a LakeSource's database is and who reads it, from its declaration and Secrets. */
public final class SourceTargets {
    private SourceTargets() {}

    /** The declared CA's ConfigMap does not exist or has no ca.pem. */
    static final class CaMissing extends IllegalStateException {
        CaMissing(String message) {
            super(message);
        }
    }

    public static SourceProbe.Target target(KubernetesClient kube, LakeSource source) {
        LakeSourceSpec.Connection c = source.getSpec().connection;
        int port = c.port != null ? c.port : SourceJobs.defaultPort(source.getSpec());
        Path ca = null;
        if (c.caConfigMap != null) {
            ConfigMap map =
                    kube.configMaps()
                            .inNamespace(source.getMetadata().getNamespace())
                            .withName(c.caConfigMap)
                            .get();
            if (map == null || map.getData() == null || map.getData().get("ca.pem") == null) {
                throw new CaMissing(
                        "ConfigMap " + c.caConfigMap + " does not exist or lacks ca.pem");
            }
            ca = Tls.pem(map.getData().get("ca.pem"));
        }
        return new SourceProbe.Target(
                c.host,
                port,
                c.database,
                c.schema,
                c.publication,
                login(kube, source, c.credentialsSecret),
                c.inspectorSecret == null ? null : login(kube, source, c.inspectorSecret),
                ca,
                SourceJobs.isMongo(source.getSpec())
                        ? SourceJobs.authSource(source.getSpec())
                        : null);
    }

    public static SourceProbe.Login login(
            KubernetesClient kube, LakeSource source, LakeSourceSpec.SecretRef ref) {
        Secret secret = secret(kube, source, ref.name);
        String user = value(secret, ref.usernameKey == null ? "username" : ref.usernameKey);
        String password = value(secret, ref.passwordKey == null ? "password" : ref.passwordKey);
        if (user == null || password == null) {
            throw new IllegalStateException(
                    "Secret " + ref.name + " lacks the user name or password");
        }
        return new SourceProbe.Login(user, password);
    }

    public static Secret secret(KubernetesClient kube, LakeSource source, String name) {
        Secret secret =
                kube.secrets()
                        .inNamespace(source.getMetadata().getNamespace())
                        .withName(name)
                        .get();
        if (secret == null) {
            throw new IllegalStateException("Secret " + name + " does not exist");
        }
        return secret;
    }

    public static String value(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().get(key) != null) {
            return new String(
                    Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        }
        return secret.getStringData() == null ? null : secret.getStringData().get(key);
    }
}
