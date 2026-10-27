package br.edu.microservices.av02;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:management;DB_CLOSE_DELAY=-1", "management.server.port=0",
        "av02.test-users.admin-password=", "av02.test-users.maintenance-password=" })
class UserManagementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    @Autowired UserService service;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String password = UUID.randomUUID().toString();

    @BeforeEach
    void prepareIsolatedDatabase() {
        jdbc.update("DELETE FROM login_attempts");
        jdbc.update("DELETE FROM authorities");
        jdbc.update("DELETE FROM usuarios");
        fixture("admin", "ADMIN");
        fixture("maintenance", "MANUTENCAO");
        fixture("common", "USER");
        fixture("target", "USER");
        fixture("other", "USER");
    }

    private void fixture(String username, String role) {
        users.createUser(User.withUsername(username).password(encoder.encode(password)).roles(role).build());
    }

    static Stream<Arguments> permissionMatrix() {
        return Stream.of(
                Arguments.of("GET", "/", "maintenance", 200),
                Arguments.of("GET", "/", "admin", 403),
                Arguments.of("GET", "/", "common", 403),
                Arguments.of("GET", "/", "", 401),
                Arguments.of("POST", "/", "maintenance", 201),
                Arguments.of("POST", "/", "admin", 403),
                Arguments.of("POST", "/", "common", 403),
                Arguments.of("POST", "/", "", 401),
                Arguments.of("PUT", "/unlock/target", "admin", 204),
                Arguments.of("PUT", "/unlock/target", "maintenance", 403),
                Arguments.of("PUT", "/unlock/target", "common", 403),
                Arguments.of("PUT", "/unlock/target", "", 401));
    }

    @ParameterizedTest
    @MethodSource("permissionMatrix")
    void enforcesRolesWithoutAdminInheritance(String method, String path, String actor, int status) throws Exception {
        String body = method.equals("POST") ? registration("new-user", password) : null;
        var response = request(method, path, body, actor.isEmpty() ? null : actor);
        assertThat(response.statusCode()).isEqualTo(status);
        if (!method.equals("GET") || status != 200) { assertThat(response.body()).isEmpty(); }
    }

    @Test
    void creationHashesPasswordSupportsLoginAndNeverGrantsPrivileges(CapturedOutput output) throws Exception {
        String createdPassword = UUID.randomUUID().toString();
        assertEmpty(request("POST", "/", registration("new-user", createdPassword), "maintenance"), 201);
        String stored = hash("new-user");
        assertThat(stored).startsWith("{bcrypt}").isNotEqualTo(createdPassword);
        assertThat(encoder.matches(createdPassword, stored)).isTrue();
        assertThat(users.loadUserByUsername("new-user").getAuthorities())
                .extracting("authority").containsExactly("ROLE_USER");
        assertEmpty(login("new-user", createdPassword), 200);
        assertThat(requestWithPassword("GET", "/", null, "new-user", createdPassword).statusCode()).isEqualTo(403);
        assertThat(requestWithPassword("POST", "/", registration("attack", password), "new-user", createdPassword).statusCode()).isEqualTo(403);
        assertThat(requestWithPassword("PUT", "/unlock/target", null, "new-user", createdPassword).statusCode()).isEqualTo(403);
        assertThat(users.userExists("attack")).isFalse();
        assertThat(output.getAll()).doesNotContain(createdPassword, stored);
    }

    @Test
    void injectedRolesAuthoritiesAndEnabledAreIgnored() throws Exception {
        String injected = "{\"username\":\"injected\",\"senha\":\"" + password
                + "\",\"roles\":[\"ROLE_ADMIN\",\"ROLE_MANUTENCAO\"],\"authorities\":[\"ROLE_ADMIN\"],\"enabled\":false}";
        assertEmpty(request("POST", "/", injected, "maintenance"), 201);
        var created = users.loadUserByUsername("injected");
        assertThat(created.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(created.isEnabled()).isTrue();
        assertThat(request("PUT", "/unlock/target", null, "injected").statusCode()).isEqualTo(403);
        assertThat(request("GET", "/", null, "injected").statusCode()).isEqualTo(403);
    }

    @Test
    void duplicateUsernameDoesNotOverwriteCredentialsOrRoles() throws Exception {
        String before = hash("admin");
        var roles = users.loadUserByUsername("admin").getAuthorities();
        assertEmpty(request("POST", "/", registration("admin", UUID.randomUUID().toString()), "maintenance"), 409);
        assertThat(hash("admin")).isEqualTo(before);
        assertThat(users.loadUserByUsername("admin").getAuthorities()).isEqualTo(roles);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{", "null", "{}", "{\"username\":\"x\"}", "{\"senha\":\"x\"}",
            "{\"username\":\"\",\"senha\":\"x\"}", "{\"username\":\"  \",\"senha\":\"x\"}",
            "{\"username\":\"x\",\"senha\":\"\"}", "{\"username\":\"x\",\"senha\":null}" })
    void invalidRegistrationReturns400WithoutPartialWrites(String body) throws Exception {
        assertEmpty(request("POST", "/", body, "maintenance"), 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorities", Integer.class)).isEqualTo(5);
    }

    @Test
    void rejectsUsernameBeyondSchemaAndPasswordsBeyondBcryptByteLimit() throws Exception {
        assertEmpty(request("POST", "/", registration("x".repeat(101), password), "maintenance"), 400);
        assertEmpty(request("POST", "/", registration("too-long", "a".repeat(73)), "maintenance"), 400);
        assertEmpty(request("POST", "/", registration("too-long", "á".repeat(37)), "maintenance"), 400);
        assertEmpty(changePassword("target", password, "á".repeat(37)), 400);
        assertThat(encoder.matches(password, hash("target"))).isTrue();
    }

    @Test
    void publicPasswordChangeMakesOldPasswordInvalidAndPreservesRoles(CapturedOutput output) throws Exception {
        String replacement = UUID.randomUUID().toString();
        String oldHash = hash("target");
        var oldRoles = users.loadUserByUsername("target").getAuthorities();
        assertEmpty(changePassword("target", password, replacement), 204);
        String newHash = hash("target");
        assertThat(newHash).startsWith("{bcrypt}").isNotEqualTo(oldHash);
        assertThat(encoder.matches(replacement, newHash)).isTrue();
        assertThat(encoder.matches(password, newHash)).isFalse();
        assertThat(users.loadUserByUsername("target").getAuthorities()).isEqualTo(oldRoles);
        assertEmpty(login("target", password), 401);
        assertEmpty(login("target", replacement), 200);
        assertThat(output.getAll()).doesNotContain(password, replacement, oldHash, newHash);
    }

    @Test
    void incorrectCurrentPasswordAndUnknownUsernameHaveSame401WithoutChanges() throws Exception {
        String before = hash("target");
        var wrong = changePassword("target", "wrong", "replacement");
        var unknown = changePassword("unknown", "wrong", "replacement");
        assertEmpty(wrong, 401);
        assertEmpty(unknown, 401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
        assertThat(hash("target")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{", "null", "{}", "{\"atual\":\"x\"}", "{\"nova\":\"x\"}",
            "{\"atual\":\"\",\"nova\":\"x\"}", "{\"atual\":\"x\",\"nova\":\"\"}",
            "{\"atual\":null,\"nova\":\"x\"}", "{\"atual\":\"x\",\"nova\":null}" })
    void invalidPasswordChangeReturns400(String body) throws Exception {
        assertEmpty(request("PUT", "/trocarsenha/target", body, null), 400);
        assertThat(encoder.matches(password, hash("target"))).isTrue();
    }

    @Test
    void passwordChangeDoesNotResetOneFailureOrUnlockBlockedUser() throws Exception {
        assertEmpty(login("target", "wrong"), 401);
        Map<String, Object> oneFailure = attempts("target");
        String replacement = UUID.randomUUID().toString();
        assertEmpty(changePassword("target", password, replacement), 204);
        assertThat(attempts("target")).isEqualTo(oneFailure);
        assertEmpty(login("target", "wrong"), 401);
        Map<String, Object> locked = attempts("target");
        String next = UUID.randomUUID().toString();
        assertEmpty(changePassword("target", replacement, next), 204);
        assertThat(attempts("target")).isEqualTo(locked);
        assertEmpty(login("target", next), 401);
        assertThat(requestWithPassword("GET", "/", null, "target", next).statusCode()).isEqualTo(401);
        assertThat(attempts("target")).isEqualTo(locked);
    }

    @Test
    void adminUnlockResetsOnlyTargetAndPreservesCredentialsAndRoles() throws Exception {
        login("target", "wrong"); login("target", "wrong");
        login("other", "wrong"); login("other", "wrong");
        String before = hash("target");
        var roles = users.loadUserByUsername("target").getAuthorities();
        Map<String, Object> other = attempts("other");
        assertEmpty(request("PUT", "/unlock/target", null, "admin"), 204);
        Map<String, Object> state = attempts("target");
        assertThat(state.get("failed_attempts")).isEqualTo(0);
        assertThat(state.get("locked")).isEqualTo(false);
        assertThat(state.get("last_failed_at")).isNull();
        assertThat(state.get("locked_at")).isNull();
        assertThat(state.get("updated_at")).isNotNull();
        assertThat(hash("target")).isEqualTo(before);
        assertThat(users.loadUserByUsername("target").getAuthorities()).isEqualTo(roles);
        assertThat(attempts("other")).isEqualTo(other);
        assertEmpty(login("target", password), 200);
        assertEmpty(request("PUT", "/unlock/target", null, "admin"), 204);
        assertEmpty(request("PUT", "/unlock/unknown", null, "admin"), 404);
    }

    @Test
    void lockedActorsCannotUseAnyProtectedOperation() throws Exception {
        login("maintenance", "wrong"); login("maintenance", "wrong");
        login("admin", "wrong"); login("admin", "wrong");
        assertEmpty(request("GET", "/", null, "maintenance"), 401);
        assertEmpty(request("POST", "/", registration("no-create", password), "maintenance"), 401);
        assertEmpty(request("PUT", "/unlock/target", null, "admin"), 401);
        assertThat(users.userExists("no-create")).isFalse();
    }

    @Test
    void listingContainsOnlyUsernamesWithoutPasswordsOrHashes(CapturedOutput output) throws Exception {
        var response = request("GET", "/", null, "maintenance");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        var body = json.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(5);
        for (var item : body) {
            assertThat(item.size()).isEqualTo(1);
            assertThat(item.has("username")).isTrue();
        }
        assertThat(response.body()).doesNotContain("senha", "password", "bcrypt", password);
        for (String user : List.of("admin", "maintenance", "common", "target", "other")) {
            assertThat(response.body()).doesNotContain(hash(user));
            assertThat(output.getAll()).doesNotContain(hash(user));
        }
    }

    @Test
    void emptyDatabaseProducesEmptyJsonArray() throws Exception {
        jdbc.update("DELETE FROM authorities");
        jdbc.update("DELETE FROM usuarios");
        assertThat(service.listUsers()).isEmpty();
        assertThat(json.writeValueAsString(service.listUsers())).isEqualTo("[]");
    }

    @Test
    void simultaneousDuplicateRegistrationsLeaveOneCompleteAccount() throws Exception {
        List<Integer> statuses = concurrent(
                () -> request("POST", "/", registration("concurrent", password), "maintenance").statusCode(),
                () -> request("POST", "/", registration("concurrent", password), "maintenance").statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuarios WHERE username = 'concurrent'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authorities WHERE username = 'concurrent'", Integer.class)).isEqualTo(1);
        assertThat(users.loadUserByUsername("concurrent").getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertEmpty(login("concurrent", password), 200);
    }

    @Test
    void simultaneousPasswordChangesWithSameOldPasswordHaveOnlyOneWinner() throws Exception {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        List<Integer> statuses = concurrent(
                () -> changePassword("target", password, first).statusCode(),
                () -> changePassword("target", password, second).statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(204, 401);
        String stored = hash("target");
        assertThat(encoder.matches(first, stored) ^ encoder.matches(second, stored)).isTrue();
        assertThat(encoder.matches(password, stored)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts", Integer.class)).isZero();
    }

    @Test
    void concurrentUnlockAndLoginFailureProduceAConsistentSerialResult() throws Exception {
        login("target", "wrong"); login("target", "wrong");
        List<Integer> statuses = concurrent(
                () -> request("PUT", "/unlock/target", null, "admin").statusCode(),
                () -> login("target", "wrong").statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(204, 401);
        Map<String, Object> state = attempts("target");
        assertThat(state.get("locked")).isEqualTo(false);
        assertThat((Integer) state.get("failed_attempts")).isBetween(0, 1);
        assertThat(state.get("locked_at")).isNull();
        assertThat(state.get("last_failed_at") == null).isEqualTo(state.get("failed_attempts").equals(0));
    }

    private List<Integer> concurrent(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private String hash(String username) {
        return jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = ?", String.class, username);
    }
    private Map<String, Object> attempts(String username) {
        return jdbc.queryForMap("SELECT * FROM login_attempts WHERE username = ?", username);
    }
    private String registration(String username, String secret) {
        return "{\"username\":\"" + username + "\",\"senha\":\"" + secret + "\"}";
    }
    private HttpResponse<String> login(String username, String secret) throws Exception {
        return request("POST", "/login", registration(username, secret), null);
    }
    private HttpResponse<String> changePassword(String username, String current, String next) throws Exception {
        return request("PUT", "/trocarsenha/" + username,
                "{\"atual\":\"" + current + "\",\"nova\":\"" + next + "\"}", null);
    }
    private HttpResponse<String> request(String method, String path, String body, String actor) throws Exception {
        return requestWithPassword(method, path, body, actor, password);
    }
    private HttpResponse<String> requestWithPassword(String method, String path, String body, String actor, String secret) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (actor != null) {
            String basic = Base64.getEncoder().encodeToString((actor + ":" + secret).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertEmpty(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }
}
