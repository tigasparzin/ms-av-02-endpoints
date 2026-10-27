package br.edu.microservices.av02;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:blocking;DB_CLOSE_DELAY=-1", "management.server.port=0",
        "av02.test-users.admin-password=", "av02.test-users.maintenance-password=" })
class LoginBlockingIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthenticationManager authenticationManager;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private String username;
    private String password;

    @BeforeEach
    void createUserInIsolatedDatabase() {
        username = "test-" + UUID.randomUUID();
        password = UUID.randomUUID().toString();
        users.createUser(User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }

    @AfterEach
    void cleanUpTestUser() {
        jdbc.update("DELETE FROM login_attempts WHERE username = ?", username);
        users.deleteUser(username);
    }

    @Test
    void firstFailureCommitsCounterAndTimestampsDespite401() throws Exception {
        assertThat(login(username, "wrong")).isEqualTo(401);
        Attempts state = attempts();
        assertThat(state.count()).isEqualTo(1);
        assertThat(state.locked()).isFalse();
        assertThat(state.lastFailed()).isNotNull();
        assertThat(state.lockedAt()).isNull();
        assertThat(state.updatedAt()).isNotNull();
    }

    @Test
    void secondFailureLocksBothLoginAndCentralAuthentication() throws Exception {
        assertThat(login(username, "wrong")).isEqualTo(401);
        assertThat(login(username, "wrong")).isEqualTo(401);
        Attempts state = attempts();
        assertThat(state.count()).isEqualTo(2);
        assertThat(state.locked()).isTrue();
        assertThat(state.lastFailed()).isNotNull();
        assertThat(state.lockedAt()).isNotNull();
        assertThat(login(username, password)).isEqualTo(401);
        assertThat(basic(username, password)).isEqualTo(401);
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password)))
                .isInstanceOf(LockedException.class);
        assertThat(attempts()).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT enabled FROM usuarios WHERE username = ?", Boolean.class, username)).isTrue();
    }

    @Test
    void successfulLoginResetsCounterAndNextFailureStartsAtOne() throws Exception {
        assertThat(login(username, "wrong")).isEqualTo(401);
        assertThat(login(username, password)).isEqualTo(200);
        Attempts reset = attempts();
        assertThat(reset.count()).isZero();
        assertThat(reset.locked()).isFalse();
        assertThat(reset.lastFailed()).isNull();
        assertThat(reset.lockedAt()).isNull();
        assertThat(reset.updatedAt()).isNotNull();
        assertThat(login(username, "wrong")).isEqualTo(401);
        assertThat(attempts().count()).isEqualTo(1);
        assertThat(attempts().locked()).isFalse();
    }

    @Test
    void unknownUsernameNeverCreatesUserOrAttempts() throws Exception {
        String unknown = "unknown-" + UUID.randomUUID();
        assertThat(login(unknown, "wrong")).isEqualTo(401);
        assertThat(users.userExists(unknown)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE username = ?", Integer.class, unknown)).isZero();
    }

    @Test
    void attemptsOnLockedAccountDoNotChangeCounterOrDates() throws Exception {
        login(username, "wrong");
        login(username, "wrong");
        Attempts locked = attempts();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(login(username, "wrong")).isEqualTo(401);
            assertThat(login(username, password)).isEqualTo(401);
        }
        assertThat(attempts()).isEqualTo(locked);
    }

    @Test
    void httpBasicNeverCountsOrResetsLoginFailures() throws Exception {
        assertThat(basic(username, "wrong")).isEqualTo(401);
        assertThat(basic(username, password)).isEqualTo(403); // Authenticated ADMIN without MANUTENCAO.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE username = ?", Integer.class, username)).isZero();
        login(username, "wrong");
        Attempts firstFailure = attempts();
        assertThat(basic(username, "wrong")).isEqualTo(401);
        assertThat(basic(username, password)).isEqualTo(403);
        assertThat(attempts()).isEqualTo(firstFailure);
        assertThat(authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password)).isAuthenticated()).isTrue();
    }

    @RepeatedTest(5)
    void twoSimultaneousFirstFailuresProduceExactlyTwoAndLocked() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> failure = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                return login(username, "wrong");
            };
            var first = executor.submit(failure);
            var second = executor.submit(failure);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(401);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(401);
            assertThat(attempts().count()).isEqualTo(2);
            assertThat(attempts().locked()).isTrue();
            assertThat(login(username, password)).isEqualTo(401);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int login(String user, String secret) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"senha\":\"" + secret + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        return response.statusCode();
    }

    private int basic(String user, String secret) throws Exception {
        String credentials = Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/"))
                .header("Authorization", "Basic " + credentials).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Attempts attempts() {
        return jdbc.queryForObject("SELECT * FROM login_attempts WHERE username = ?",
                (rs, row) -> new Attempts(rs.getInt("failed_attempts"), rs.getBoolean("locked"),
                        rs.getTimestamp("last_failed_at"), rs.getTimestamp("locked_at"), rs.getTimestamp("updated_at")), username);
    }

    private String baseUrl() { return "http://127.0.0.1:" + port; }

    private record Attempts(int count, boolean locked, Timestamp lastFailed, Timestamp lockedAt, Timestamp updatedAt) { }
}
