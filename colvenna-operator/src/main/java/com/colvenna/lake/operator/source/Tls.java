package com.colvenna.lake.operator.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Source CA bundles as files the JDBC drivers read, written once per distinct content. */
public final class Tls {
    static final String STORE_PASSWORD = "changeit";
    private static final Path DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "colvenna-source-ca");

    private Tls() {}

    /** The PEM bundle as a file, for PostgreSQL's sslrootcert. */
    public static Path pem(String bundle) {
        return write(bundle, ".pem", bundle.getBytes(StandardCharsets.UTF_8));
    }

    /** The bundle as a trust store in memory. */
    static KeyStore store(Path pem) throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(pem)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("source-ca-" + i++, cert);
            }
            return store;
        }
    }

    /** A TLS context trusting only the bundle, for drivers that take one (MongoDB's). */
    static SSLContext sslContext(Path pem) throws IOException, GeneralSecurityException {
        TrustManagerFactory trust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store(pem));
        // The driver uses this context's defaults: TLS 1.3 and 1.2 (the JDK disables older).
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    /** The bundle as a PKCS12 trust store, for MySQL Connector/J's trustCertificateKeyStoreUrl. */
    static Path pkcs12(Path pem) {
        try {
            KeyStore store = store(pem);
            var bytes = new java.io.ByteArrayOutputStream();
            store.store(bytes, STORE_PASSWORD.toCharArray());
            return write(Files.readString(pem), ".p12", bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid CA bundle " + pem, e);
        }
    }

    private static Path write(String key, String suffix, byte[] content) {
        try {
            Files.createDirectories(DIR);
            String name =
                    HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(key.getBytes(StandardCharsets.UTF_8)))
                                    .substring(0, 16)
                            + suffix;
            Path file = DIR.resolve(name);
            if (!Files.exists(file)) {
                Path part = Files.createTempFile(DIR, name, ".part");
                try (OutputStream out = Files.newOutputStream(part)) {
                    out.write(content);
                }
                Files.move(part, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
