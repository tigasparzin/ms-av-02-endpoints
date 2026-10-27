package br.edu.microservices.av02;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "spring.datasource.url=jdbc:h2:mem:login;DB_CLOSE_DELAY=-1", "management.server.port=0" })
class LoginIntegrationTest {
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String MAINTENANCE_PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("av02.test-users.admin-password", () -> ADMIN_PASSWORD);
        registry.add("av02.test-users.maintenance-password", () -> MAINTENANCE_PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetAttemptsInIsolatedTestDatabase() {
        jdbc.update("DELETE FROM login_attempts");
    }

    @Test
    void acceptsBcryptCredentialsWithoutAuthorizationAndDoesNotCreateSession(CapturedOutput output) throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        for (String username : new String[] { "admin", "manutencao" }) {
            String password = username.equals("admin") ? ADMIN_PASSWORD : MAINTENANCE_PASSWORD;
            String hash = jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = ?", String.class, username);
            assertThat(hash).startsWith("{bcrypt}").isNotEqualTo(password);
            HttpResponse<String> response = login(client, "{\"username\":\"" + username + "\",\"senha\":\"" + password + "\"}");
            assertEmptyResponse(response, 200);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(output.getAll()).doesNotContain(password, hash);
        }
        assertThat(cookies.getCookieStore().getCookies()).isEmpty();
        HttpResponse<String> protectedResponse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedResponse.statusCode()).isEqualTo(401);
    }

    @Test
    void returnsIdentical401ForUnknownUsernameAndWrongPassword(CapturedOutput output) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String wrongPassword = UUID.randomUUID().toString();
        HttpResponse<String> wrong = login(client, "{\"username\":\"admin\",\"senha\":\"" + wrongPassword + "\"}");
        HttpResponse<String> unknown = login(client, "{\"username\":\"unknown-user\",\"senha\":\"" + wrongPassword + "\"}");
        assertEmptyResponse(wrong, 401);
        assertEmptyResponse(unknown, 401);
        assertThat(unknown.body()).isEqualTo(wrong.body());
        assertThat(output.getAll()).doesNotContain(wrongPassword, ADMIN_PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "", "null",
            "{\"senha\":\"secret\"}",
            "{\"username\":\"\",\"senha\":\"secret\"}",
            "{\"username\":\"   \",\"senha\":\"secret\"}",
            "{\"username\":null,\"senha\":\"secret\"}",
            "{\"username\":\"admin\"}",
            "{\"username\":\"admin\",\"senha\":\"\"}",
            "{\"username\":\"admin\",\"senha\":\"   \"}",
            "{\"username\":\"admin\",\"senha\":null}",
            "{\"username\":[],\"senha\":\"secret\"}"
    })
    void returns400ForMalformedJsonAndMissingOrBlankFields(String json, CapturedOutput output) throws Exception {
        assertEmptyResponse(login(HttpClient.newHttpClient(), json), 400);
        assertThat(output.getAll()).doesNotContain("secret");
    }

    @Test
    void leavesHttpBasicAndRoleChecksWorking() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest withoutCredentials = HttpRequest.newBuilder(URI.create(baseUrl() + "/")).GET().build();
        assertThat(client.send(withoutCredentials, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        String basic = Base64.getEncoder().encodeToString(("admin:" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest insufficientRole = HttpRequest.newBuilder(URI.create(baseUrl() + "/"))
                .header("Authorization", "Basic " + basic).GET().build();
        assertThat(client.send(insufficientRole, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }

    @Test
    void successfulLoginResetsFailureWithoutChangingCredentialsOrEnabled() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String hashBefore = jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = 'admin'", String.class);
        assertEmptyResponse(login(client, "{\"username\":\"admin\",\"senha\":\"incorrect\"}"), 401);
        assertEmptyResponse(login(client, "{\"username\":\"admin\",\"senha\":\"" + ADMIN_PASSWORD + "\"}"), 200);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM login_attempts WHERE username = 'admin'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT enabled FROM usuarios WHERE username = 'admin'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = 'admin'", String.class)).isEqualTo(hashBefore);
    }

    private HttpResponse<String> login(HttpClient client, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertEmptyResponse(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
