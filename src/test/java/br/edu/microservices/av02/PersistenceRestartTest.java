package br.edu.microservices.av02;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "av02.persistence.phase", matches = "write|read")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "spring.datasource.url=jdbc:h2:file:./.local/persistence-check/av02;DB_CLOSE_ON_EXIT=FALSE", "management.server.port=0" })
class PersistenceRestartTest {
    private static final Path CHECKPOINT = Path.of(".local/persistence-check/checkpoint.txt");
    private static final Timestamp FIXTURE_TIME = Timestamp.valueOf("2026-10-26 12:00:00");

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        // Different passwords in each JVM: the restart must NOT replace existing hashes.
        registry.add("av02.test-users.admin-password", () -> UUID.randomUUID().toString());
        registry.add("av02.test-users.maintenance-password", () -> UUID.randomUUID().toString());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired UserService management;
    @Autowired MeterRegistry registry;
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    @Test
    @EnabledIfSystemProperty(named = "av02.persistence.phase", matches = "write")
    void writesDurableUserAndLoginAttemptFixture() throws Exception {
        var probe = User.withUsername("persistence-probe")
                .password(encoder.encode(UUID.randomUUID().toString())).roles("MANUTENCAO").disabled(true).build();
        if (users.userExists(probe.getUsername())) { users.updateUser(probe); }
        else { users.createUser(probe); }
        // Fixture only: no application login/blocking logic is implemented in this stage.
        jdbc.update("MERGE INTO login_attempts (username, failed_attempts, locked, last_failed_at, locked_at, updated_at) KEY(username) VALUES (?, ?, ?, ?, ?, ?)",
                probe.getUsername(), 2, true, FIXTURE_TIME, FIXTURE_TIME, FIXTURE_TIME);
        String secret = restartPassword();
        var restartUser = User.withUsername("restart-lock-probe").password(encoder.encode(secret)).roles("ADMIN").build();
        if (users.userExists(restartUser.getUsername())) { users.updateUser(restartUser); }
        else { users.createUser(restartUser); }
        jdbc.update("DELETE FROM login_attempts WHERE username = ?", restartUser.getUsername());
        assertThat(login("wrong")).isEqualTo(401);
        assertThat(login("wrong")).isEqualTo(401);
        assertBlockedAfterRestart(secret);
        // Exercise management writes against the same durable database.
        jdbc.update("DELETE FROM login_attempts WHERE username = 'management-restart-probe'");
        if (users.userExists("management-restart-probe")) { users.deleteUser("management-restart-probe"); }
        assertThat(management.create("management-restart-probe", secret)).isTrue();
        assertThat(management.changePassword("management-restart-probe", secret, secret + "-changed")).isTrue();
        assertThat(login("management-restart-probe", "wrong")).isEqualTo(401);
        assertThat(login("management-restart-probe", "wrong")).isEqualTo(401);
        assertThat(management.unlock("management-restart-probe")).isTrue();
        assertThat(registry.get("av02.login.success").counter().count()).isZero();
        assertThat(registry.get("av02.login.failure").counter().count()).isEqualTo(5);
        assertGaugeMatchesPersistentDatabase();
        Files.writeString(CHECKPOINT, snapshot());
        assertThat(Files.exists(Path.of(".local/persistence-check/av02.mv.db"))).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "av02.persistence.phase", matches = "read")
    void preservesCredentialsRolesAndAttemptRecordAfterJvmRestart() throws Exception {
        // Before any login in this new JVM: counters reset, persisted locks do not.
        assertThat(registry.get("av02.login.success").counter().count()).isZero();
        assertThat(registry.get("av02.login.failure").counter().count()).isZero();
        assertGaugeMatchesPersistentDatabase();
        var scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("av02_login_success_total 0.0", "av02_login_failure_total 0.0", "av02_users_locked 2.0");
        Files.writeString(Path.of(".local/etapa5-restart-prometheus-sample.prom"),
                String.join("\n", scrape.body().lines().filter(line -> line.startsWith("av02_")).toList()) + "\n");
        assertThat(Files.exists(CHECKPOINT)).as("Run persistence-write first").isTrue();
        assertThat(snapshot()).isEqualTo(Files.readString(CHECKPOINT));
        assertThat(users.loadUserByUsername("persistence-probe").isEnabled()).isFalse();
        assertThat(users.loadUserByUsername("persistence-probe").getAuthorities())
                .extracting("authority").containsExactly("ROLE_MANUTENCAO");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM login_attempts WHERE username = 'persistence-probe'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locked FROM login_attempts WHERE username = 'persistence-probe'", Boolean.class)).isTrue();
        for (String column : new String[] { "last_failed_at", "locked_at", "updated_at" }) {
            assertThat(jdbc.queryForObject("SELECT " + column + " FROM login_attempts WHERE username = 'persistence-probe'", Timestamp.class)).isEqualTo(FIXTURE_TIME);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class)).isEqualTo(5);
        assertBlockedAfterRestart(restartPassword());
        var managed = users.loadUserByUsername("management-restart-probe");
        assertThat(managed.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(encoder.matches(restartPassword() + "-changed", managed.getPassword())).isTrue();
        assertThat(encoder.matches(restartPassword(), managed.getPassword())).isFalse();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM login_attempts WHERE username = 'management-restart-probe'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked FROM login_attempts WHERE username = 'management-restart-probe'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT last_failed_at FROM login_attempts WHERE username = 'management-restart-probe'", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT locked_at FROM login_attempts WHERE username = 'management-restart-probe'", Timestamp.class)).isNull();
        assertThat(login("management-restart-probe", restartPassword() + "-changed")).isEqualTo(200);
    }

    private void assertGaugeMatchesPersistentDatabase() {
        Long locked = jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE locked = TRUE", Long.class);
        assertThat(locked).isEqualTo(2L);
        assertThat(registry.get("av02.users.locked").gauge().value()).isEqualTo(locked.doubleValue());
    }

    private String restartPassword() {
        String secret = System.getenv("AV02_RESTART_TEST_PASSWORD");
        assertThat(secret).as("Use dev.ps1 persistence-write/read in the same terminal").isNotBlank();
        return secret;
    }

    private void assertBlockedAfterRestart(String secret) throws Exception {
        assertThat(encoder.matches(secret, users.loadUserByUsername("restart-lock-probe").getPassword())).isTrue();
        assertThat(users.loadUserByUsername("restart-lock-probe").isEnabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM login_attempts WHERE username = 'restart-lock-probe'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locked FROM login_attempts WHERE username = 'restart-lock-probe'", Boolean.class)).isTrue();
        assertThatThrownBy(() -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("restart-lock-probe", secret)))
                .isInstanceOf(LockedException.class);
        assertThat(login(secret)).isEqualTo(401);
        String basic = Base64.getEncoder().encodeToString(("restart-lock-probe:" + secret).getBytes(StandardCharsets.UTF_8));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private int login(String secret) throws Exception {
        return login("restart-lock-probe", secret);
    }

    private int login(String username, String secret) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"senha\":\"" + secret + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEmpty();
        return response.statusCode();
    }

    private String snapshot() {
        return String.join("\n", jdbc.query("SELECT username, senha, enabled FROM usuarios ORDER BY username",
                (rs, row) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3)));
    }
}
