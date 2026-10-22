package com.javatechie.crud;

import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("public")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "management.endpoint.health.probes.enabled=true"})
class PublicTlsTests {
    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    static Path store;

    @DynamicPropertySource
    static void certificate(DynamicPropertyRegistry registry) throws Exception {
        Path folder = Files.createTempDirectory("learning-tls-test-");
        store = folder.resolve("server.p12");
        store.toFile().deleteOnExit();
        folder.toFile().deleteOnExit();
        String tool = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(tool, "-genkeypair", "-alias", "app", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "1", "-storetype", "PKCS12", "-keystore", store.toString(),
            "-storepass", "test-store-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-noprompt")
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("Test certificate failed: " + output);
        registry.add("server.ssl.key-store", () -> store.toUri().toString());
        registry.add("server.ssl.key-store-password", () -> "test-store-only");
    }

    @Test void httpsProtectsOrdersAndUsesSecureSessionCookie() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { trust.load(input, "test-store-only".toCharArray()); }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        var client = HttpClient.newBuilder().sslContext(context).build();
        var denied = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/orders"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        var login = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/login"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("Secure").contains("HttpOnly");
        var health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + managementPort + "/actuator/health/liveness"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }
}
