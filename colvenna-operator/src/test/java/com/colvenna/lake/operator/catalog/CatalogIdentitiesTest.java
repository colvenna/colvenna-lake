package com.colvenna.lake.operator.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.CatalogIdentity;
import com.colvenna.lake.operator.crd.LakeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Identity management against a fake colvenna-catalog admin API and a Kubernetes mock: tokens land
 * in an owned Secret, rotate ahead of expiry, and grants are renewed and pruned.
 */
@EnableKubernetesMockClient(crud = true, https = false)
class CatalogIdentitiesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    KubernetesClient kube;
    private MockWebServer catalog;
    private final List<String> calls = new ArrayList<>();
    private final List<JsonNode> grants = new ArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        catalog = new MockWebServer();
        catalog.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String call = r.getMethod() + " " + r.getPath();
                        calls.add(call);
                        try {
                            if (call.equals("POST /colvenna/admin/v1/principals")) {
                                return new MockResponse().setBody("{\"name\":\"x\"}");
                            }
                            if (call.matches("POST /colvenna/admin/v1/principals/.*/tokens")) {
                                int n = tokens.incrementAndGet();
                                return new MockResponse()
                                        .setBody(
                                                "{\"id\":\"t"
                                                        + n
                                                        + "\",\"token\":\"colvenna_secret"
                                                        + n
                                                        + "\",\"expiresAt\":\"2026-11-14T00:00:00Z\"}");
                            }
                            if (call.matches("GET /colvenna/admin/v1/principals/.*/tokens")) {
                                var list = JSON.createArrayNode();
                                for (int i = 1; i <= tokens.get(); i++) {
                                    var t = list.addObject().put("id", "t" + i);
                                    if (calls.contains("DELETE /colvenna/admin/v1/tokens/t" + i)) {
                                        t.put("revokedAt", "2026-10-15T00:00:00Z");
                                    }
                                }
                                return new MockResponse()
                                        .setBody(
                                                JSON.createObjectNode()
                                                        .set("tokens", list)
                                                        .toString());
                            }
                            if (call.startsWith("GET /colvenna/admin/v1/grants")) {
                                return new MockResponse()
                                        .setBody(
                                                JSON.createObjectNode()
                                                        .set("grants", JSON.valueToTree(grants))
                                                        .toString());
                            }
                            if (call.equals("POST /colvenna/admin/v1/grants")) {
                                var g =
                                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                                JSON.readTree(
                                                        r.getBody()
                                                                .readString(
                                                                        StandardCharsets.UTF_8));
                                g.put("id", "g" + (grants.size() + 1));
                                grants.add(g);
                                return new MockResponse().setBody(g.toString());
                            }
                            if (call.startsWith("DELETE ")) {
                                return new MockResponse();
                            }
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        return new MockResponse().setResponseCode(404).setBody("{}");
                    }
                });
        catalog.start();
    }

    @AfterEach
    void stop() throws IOException {
        catalog.shutdown();
    }

    private LakeSource owner() {
        LakeSource source = new LakeSource();
        source.setMetadata(
                new ObjectMetaBuilder()
                        .withName("shop")
                        .withNamespace("colvenna-data")
                        .withUid("2b111fc6-1234-5678-9abc-def012345678")
                        .build());
        return source;
    }

    private CatalogIdentities identities() throws Exception {
        return new CatalogIdentities(
                new CatalogClient(
                        URI.create(catalog.url("/").toString()),
                        () -> "admin",
                        SSLContext.getDefault()),
                kube);
    }

    @Test
    void issuesATokenIntoAnOwnedSecretAndGrantsOnce() throws Exception {
        LakeSource owner = owner();
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        String principal = CatalogIdentities.principalName("lakesource", owner);
        assertThat(principal).isEqualTo("lakesource.colvenna-data.shop.2b111fc6");
        List<Grant> wanted =
                List.of(Grant.database(principal, "ods_shop", "MANAGE", null, "uid", now));

        CatalogIdentity first =
                identities()
                        .ensure(
                                owner,
                                "lakesource",
                                "LakeSource colvenna-data/shop",
                                wanted,
                                null,
                                now);
        assertThat(first.tokenId).isEqualTo("t1");
        assertThat(first.grantIds).containsExactly("g1");
        Secret secret =
                kube.secrets().inNamespace("colvenna-data").withName("shop-catalog-token").get();
        assertThat(
                        new String(
                                Base64.getDecoder().decode(secret.getData().get("token")),
                                StandardCharsets.UTF_8))
                .isEqualTo("colvenna_secret1");
        assertThat(secret.getMetadata().getOwnerReferences().get(0).getName()).isEqualTo("shop");
        assertThat(grants.get(0).path("approvedBy").asText()).isEqualTo("colvenna-operator");
        assertThat(grants.get(0).path("expiresAt").asText()).isEqualTo("2027-01-13T00:00:00Z");

        // Nothing changes on the next round.
        CatalogIdentity second =
                identities().ensure(owner, "lakesource", "p", wanted, first, now.plusSeconds(60));
        assertThat(second.tokenId).isEqualTo("t1");
        assertThat(tokens.get()).isEqualTo(1);
        assertThat(grants).hasSize(1);
    }

    @Test
    void revokesTokensIssuedInARoundThatNeverRecordedThem() throws Exception {
        LakeSource owner = owner();
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        CatalogIdentity first = identities().ensure(owner, "lakesource", "p", List.of(), null, now);
        // A round that issued t2 and failed before writing its Secret or status.
        tokens.incrementAndGet();
        identities().ensure(owner, "lakesource", "p", List.of(), first, now.plusSeconds(30));
        assertThat(calls)
                .contains("DELETE /colvenna/admin/v1/tokens/t2")
                .doesNotContain("DELETE /colvenna/admin/v1/tokens/t1");
    }

    @Test
    void rotatesTheTokenAheadOfExpiryAndKeepsTheOldOneUntilJobsMove() throws Exception {
        LakeSource owner = owner();
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        CatalogIdentity first = identities().ensure(owner, "lakesource", "p", List.of(), null, now);
        // Within ten days of expiry: a new token, the old one remembered for revocation.
        CatalogIdentity rotated =
                identities()
                        .ensure(
                                owner,
                                "lakesource",
                                "p",
                                List.of(),
                                first,
                                Instant.parse("2026-11-06T00:00:00Z"));
        assertThat(rotated.tokenId).isEqualTo("t2");
        assertThat(rotated.previousTokenId).isEqualTo("t1");
        assertThat(calls).noneMatch(c -> c.startsWith("DELETE /colvenna/admin/v1/tokens/"));
        identities().revokePrevious(rotated);
        assertThat(calls).contains("DELETE /colvenna/admin/v1/tokens/t1");
        assertThat(rotated.previousTokenId).isNull();
    }
}
