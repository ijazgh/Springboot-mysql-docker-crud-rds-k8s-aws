package com.javatechie.crud;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void createThenReadOrderThroughHttp() throws Exception {
        var created = client.send(HttpRequest.newBuilder(uri("/orders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"RDS lesson\",\"qty\":3,\"price\":25.5}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        var json = mapper.readTree(created.body());
        int id = json.get("id").asInt();
        assertThat(id).isPositive();
        var found = client.send(HttpRequest.newBuilder(uri("/orders/" + id)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(found.body()).get("name").asString()).isEqualTo("RDS lesson");
        var list = client.send(HttpRequest.newBuilder(uri("/orders")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("RDS lesson");
    }

    @Test
    void kubernetesHealthEndpointsAreAvailable() throws Exception {
        for (String group : new String[]{"liveness", "readiness"}) {
            var response = client.send(HttpRequest.newBuilder(uri("/actuator/health/" + group))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).get("status").asString()).isEqualTo("UP");
        }
    }

    URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
