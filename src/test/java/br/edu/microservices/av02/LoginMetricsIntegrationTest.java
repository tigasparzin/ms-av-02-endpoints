package br.edu.microservices.av02;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:metrics;DB_CLOSE_DELAY=-1", "management.server.port=0",
        "av02.test-users.admin-password=", "av02.test-users.maintenance-password=" })
class LoginMetricsIntegrationTest {
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    @Autowired Environment environment;
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String password = UUID.randomUUID().toString();
    private Values before;

    @BeforeEach
    void prepareIsolatedDataAndCounterBaseline() {
        jdbc.update("DELETE FROM login_attempts");
        jdbc.update("DELETE FROM authorities");
        jdbc.update("DELETE FROM usuarios");
        for (String username : List.of("admin", "maintenance", "target", "other")) {
            String role = username.equals("admin") ? "ADMIN" : username.equals("maintenance") ? "MANUTENCAO" : "USER";
            users.createUser(User.withUsername(username).password(encoder.encode(password)).roles(role).build());
        }
        // Counters are cumulative: compare deltas instead of relying on test order.
        before = values();
        assertThat(before.locked()).isZero();
    }

    @Test
    void successfulLoginIncrementsOnlySuccessOnce() throws Exception {
        assertStatus(login("target", password), 200);
        assertDelta(1, 0, 0);
    }

    @Test
    void incorrectAndUnknownCredentialsEachIncrementFailureOnce() throws Exception {
        assertStatus(login("target", "wrong"), 401);
        assertDelta(0, 1, 0);
        assertStatus(login("unknown", "wrong"), 401);
        assertDelta(0, 2, 0);
        assertThat(users.userExists("unknown")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE username = 'unknown'", Integer.class)).isZero();
    }

    @Test
    void secondFailureLocksAndBlockedRequestsStillCountAsFailures() throws Exception {
        assertStatus(login("target", "wrong"), 401);
        assertDelta(0, 1, 0);
        assertStatus(login("target", "wrong"), 401);
        assertDelta(0, 2, 1);
        assertStatus(login("target", password), 401);
        assertDelta(0, 3, 1);
        assertStatus(login("target", "wrong"), 401);
        assertDelta(0, 4, 1);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM login_attempts WHERE username = 'target'", Integer.class)).isEqualTo(2);
    }

    @Test
    void httpBasicAndOtherOperationsNeverChangeLoginCounters() throws Exception {
        assertThat(request(port, "GET", "/", null, "maintenance", password).statusCode()).isEqualTo(200);
        assertThat(request(port, "GET", "/", null, "maintenance", "wrong").statusCode()).isEqualTo(401);
        assertThat(request(port, "GET", "/", null, "admin", password).statusCode()).isEqualTo(403);
        assertThat(request(port, "GET", "/", null, null, null).statusCode()).isEqualTo(401);
        assertStatus(request(port, "POST", "/", credentials("created", password), "maintenance", password), 201);
        assertStatus(request(port, "PUT", "/trocarsenha/target",
                "{\"atual\":\"" + password + "\",\"nova\":\"" + UUID.randomUUID() + "\"}", null, null), 204);
        assertStatus(request(port, "PUT", "/unlock/target", null, "admin", password), 204);
        assertDelta(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{", "{}", "null", "{\"username\":\"target\"}",
            "{\"username\":\"\",\"senha\":\"wrong\"}", "{\"username\":\"target\",\"senha\":\"\"}" })
    void badRequestsDoNotIncrementCounters(String body) throws Exception {
        assertStatus(request(port, "POST", "/login", body, null, null), 400);
        assertDelta(0, 0, 0);
    }

    @Test
    void administrativeUnlockReducesGaugeWithoutChangingCounters() throws Exception {
        login("target", "wrong"); login("target", "wrong");
        login("other", "wrong"); login("other", "wrong");
        assertDelta(0, 4, 2);
        assertStatus(request(port, "PUT", "/unlock/target", null, "admin", password), 204);
        assertDelta(0, 4, 1);
        assertStatus(request(port, "PUT", "/unlock/other", null, "admin", password), 204);
        assertDelta(0, 4, 0);
    }

    @Test
    void gaugeReadsActualDatabaseInsteadOfAccumulatingEvents() throws Exception {
        jdbc.update("INSERT INTO login_attempts (username, failed_attempts, locked) VALUES ('target', 2, TRUE)");
        assertDelta(0, 0, 1);
        jdbc.update("DELETE FROM login_attempts WHERE username = 'target'");
        assertDelta(0, 0, 0);
    }

    @Test
    void concurrentSuccessfulLoginsIncrementExactlyTwice() throws Exception {
        assertThat(concurrent(() -> login("target", password).statusCode(),
                () -> login("target", password).statusCode())).containsExactly(200, 200);
        assertDelta(2, 0, 0);
    }

    @Test
    void concurrentFailuresIncrementExactlyTwiceAndGaugeReflectsOneBlockedUser() throws Exception {
        assertThat(concurrent(() -> login("target", "wrong").statusCode(),
                () -> login("target", "wrong").statusCode())).containsExactly(401, 401);
        assertDelta(0, 2, 1);
    }

    @Test
    void exportsRealPrometheusNamesAndOnlyExposesLocalManagementEndpoint() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertStatus(login("maintenance", password), 200);
        assertStatus(login("target", "wrong"), 401);
        assertStatus(login("target", "wrong"), 401);
        assertDelta(1, 2, 1);
        String scrape = scrape();
        assertThat(scrape).contains("# TYPE av02_login_success_total counter", "# TYPE av02_login_failure_total counter",
                "# TYPE av02_users_locked gauge");
        List<String> customLines = scrape.lines().filter(line -> line.startsWith("av02_")).toList();
        assertThat(customLines).hasSize(3);
        for (String line : customLines) { assertThat(line).doesNotContain("{", "username", "senha", password, "bcrypt"); }
        Files.writeString(Path.of(".local/etapa5-prometheus-sample.prom"), String.join("\n", customLines) + "\n");
        assertThat(request(port, "GET", "/actuator/prometheus", null, null, null).statusCode()).isEqualTo(401);
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/actuator/metrics")) {
            assertThat(request(managementPort, "GET", path, null, null, null).statusCode()).isNotEqualTo(200);
        }
        assertThat(request(managementPort, "GET", "/", null, "maintenance", password).statusCode()).isNotEqualTo(200);
        assertThat(request(managementPort, "POST", "/login", credentials("maintenance", password), null, null).statusCode()).isNotEqualTo(200);
        assertDelta(1, 2, 1);
    }

    private Values values() {
        return new Values(registry.get("av02.login.success").counter().count(),
                registry.get("av02.login.failure").counter().count(), registry.get("av02.users.locked").gauge().value());
    }

    private void assertDelta(double success, double failure, double locked) throws Exception {
        Values actual = values();
        assertThat(actual.success() - before.success()).isEqualTo(success);
        assertThat(actual.failure() - before.failure()).isEqualTo(failure);
        assertThat(actual.locked()).isEqualTo(locked);
        String export = scrape();
        assertThat(sample(export, "av02_login_success_total")).isEqualTo(actual.success());
        assertThat(sample(export, "av02_login_failure_total")).isEqualTo(actual.failure());
        assertThat(sample(export, "av02_users_locked")).isEqualTo(locked);
    }

    private String scrape() throws Exception {
        var response = request(managementPort, "GET", "/actuator/prometheus", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private double sample(String text, String name) {
        var match = Pattern.compile("(?m)^" + Pattern.quote(name) + " ([0-9.E+\\-]+)$").matcher(text);
        assertThat(match.find()).as("Exported metric %s", name).isTrue();
        return Double.parseDouble(match.group(1));
    }

    private List<Integer> concurrent(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); } return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); } return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private HttpResponse<String> login(String username, String secret) throws Exception {
        return request(port, "POST", "/login", credentials(username, secret), null, null);
    }
    private String credentials(String username, String secret) {
        return "{\"username\":\"" + username + "\",\"senha\":\"" + secret + "\"}";
    }
    private HttpResponse<String> request(int targetPort, String method, String path, String body, String actor, String secret) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path));
        if (actor != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((actor + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private void assertStatus(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
    }
    private record Values(double success, double failure, double locked) { }
}
