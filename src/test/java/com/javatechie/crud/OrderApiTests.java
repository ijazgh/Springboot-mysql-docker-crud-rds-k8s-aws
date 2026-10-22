package com.javatechie.crud;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.endpoint.health.probes.enabled=true",
                  "management.endpoint.health.group.readiness.include=readinessState,db"})
class OrderApiTests {
    @Value("${local.server.port}") int port;
    final JsonMapper mapper = JsonMapper.builder().build();
    final String authorization = "Basic " + Base64.getEncoder().encodeToString(
        "learner:test-login-only-password".getBytes(StandardCharsets.UTF_8));
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(
        new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    HttpResponse<String> get(HttpClient c, String path, String auth) throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).GET();
        if (auth != null) b.header("Authorization", auth);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousAndWrongPasswordCannotReadOrders() throws Exception {
        assertThat(get(client(), "/orders", null).statusCode()).isEqualTo(401);
        var browser = client().send(HttpRequest.newBuilder(uri("/orders"))
            .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(browser.statusCode()).isEqualTo(302);
        assertThat(browser.headers().firstValue("location").orElseThrow()).endsWith("/login");
        assertThat(get(client(), "/orders", "Basic " + Base64.getEncoder()
            .encodeToString("learner:wrong".getBytes(StandardCharsets.UTF_8))).statusCode()).isEqualTo(401);
    }
    @Test void authenticatedCreateNeedsCsrfThenPersistsFields() throws Exception {
        var c = client();
        String body = "{\"name\":\"Protected order\",\"qty\":3,\"price\":25.5}";
        var denied = c.send(HttpRequest.newBuilder(uri("/orders"))
            .header("Authorization", authorization).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        var csrfResponse = get(c, "/api/csrf", authorization);
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        var csrf = mapper.readTree(csrfResponse.body());
        var created = c.send(HttpRequest.newBuilder(uri("/orders"))
            .header("Authorization", authorization).header("Content-Type", "application/json")
            .header(csrf.get("headerName").asString(), csrf.get("token").asString())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        int id = mapper.readTree(created.body()).get("id").asInt();
        assertThat(id).isPositive();
        assertThat(get(c, "/orders/" + id, authorization).body()).contains("Protected order");
    }
    @Test void browserFormLoginCreatesSessionAndLogoutEndsIt() throws Exception {
        var c = client();
        var loginPage = get(c, "/login", null);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        var tokenMatch = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage.body());
        assertThat(tokenMatch.find()).isTrue();
        String form = "username=learner&password=test-login-only-password&_csrf=" +
            URLEncoder.encode(tokenMatch.group(1), StandardCharsets.UTF_8);
        var loggedIn = c.send(HttpRequest.newBuilder(uri("/login"))
            .header("Accept", "text/html")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(loggedIn.statusCode()).withFailMessage("Login failed: %s; cookies: %s", loggedIn.body(),
            ((CookieManager)c.cookieHandler().orElseThrow()).getCookieStore().getCookies()).isEqualTo(302);
        assertThat(get(c, "/orders", null).statusCode()).isEqualTo(200);
        var csrf = mapper.readTree(get(c, "/api/csrf", null).body());
        var logout = c.send(HttpRequest.newBuilder(uri("/logout"))
            .header("Accept", "text/html")
            .header(csrf.get("headerName").asString(), csrf.get("token").asString())
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(get(c, "/orders", null).statusCode()).isEqualTo(401);
    }
    @Test void healthProbesRemainAnonymous() throws Exception {
        for (String group : new String[]{"liveness", "readiness"}) {
            var response = get(client(), "/actuator/health/" + group, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        }
    }
}
