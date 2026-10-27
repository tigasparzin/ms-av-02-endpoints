package br.edu.microservices.av02;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "spring.datasource.url=jdbc:h2:mem:structure;DB_CLOSE_DELAY=-1", "management.server.port=0" })
class StructureIntegrationTest {
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String MAINTENANCE_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("av02.test-users.admin-password", () -> ADMIN_PASSWORD);
        registry.add("av02.test-users.maintenance-password", () -> MAINTENANCE_PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    @LocalServerPort int port;

    @Test
    void createsOfficialTablesAndSeedsOnlyBcryptCredentials() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorities", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'USERS'", Integer.class)).isZero();
        assertPassword("admin", ADMIN_PASSWORD);
        assertPassword("manutencao", MAINTENANCE_PASSWORD);
        assertThat(users.loadUserByUsername("admin").getAuthorities())
                .extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(users.loadUserByUsername("manutencao").getAuthorities())
                .extracting("authority").containsExactly("ROLE_MANUTENCAO");
    }

    @Test
    void jdbcManagerCreatesUpdatesChangesPasswordAndDeletesInUsuarios() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String third = UUID.randomUUID().toString();
        try {
            users.createUser(User.withUsername("crud-probe").password(encoder.encode(first))
                    .roles("MANUTENCAO").build());
            assertThat(users.userExists("crud-probe")).isTrue();
            assertPassword("crud-probe", first);
            users.updateUser(User.withUsername("crud-probe").password(encoder.encode(second))
                    .roles("ADMIN").disabled(true).build());
            assertPassword("crud-probe", second);
            assertThat(users.loadUserByUsername("crud-probe").isEnabled()).isFalse();
            assertThat(users.loadUserByUsername("crud-probe").getAuthorities())
                    .extracting("authority").containsExactly("ROLE_ADMIN");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("crud-probe", second));
            users.changePassword(second, encoder.encode(third));
            assertPassword("crud-probe", third);
            assertThat(encoder.matches(second, users.loadUserByUsername("crud-probe").getPassword())).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
            if (users.userExists("crud-probe")) { users.deleteUser("crud-probe"); }
        }
        assertThat(users.userExists("crud-probe")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorities WHERE username = 'crud-probe'", Integer.class)).isZero();
    }

    @Test
    void httpBasicReturns401ForMissingOrInvalidCredentialsAnd403ForWrongRole() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        assertThat(get(client, null)).isEqualTo(401);
        assertThat(get(client, basic("admin", "invalid"))).isEqualTo(401);
        assertThat(get(client, basic("admin", ADMIN_PASSWORD))).isEqualTo(403);
    }

    private int get(HttpClient client, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET();
        if (authorization != null) { request.header("Authorization", authorization); }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void assertPassword(String username, String rawPassword) {
        String stored = jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = ?", String.class, username);
        assertThat(stored).startsWith("{bcrypt}").hasSize(68).isNotEqualTo(rawPassword);
        assertThat(encoder.matches(rawPassword, stored)).isTrue();
    }
}
