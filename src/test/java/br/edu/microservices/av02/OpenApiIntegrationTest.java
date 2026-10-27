package br.edu.microservices.av02;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:openapi;DB_CLOSE_DELAY=-1", "management.server.port=0",
        "av02.test-users.admin-password=", "av02.test-users.maintenance-password=" })
class OpenApiIntegrationTest {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcUserDetailsManager users;
    @Autowired PasswordEncoder encoder;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String password = UUID.randomUUID().toString();

    @BeforeEach
    void isolatedAccount() {
        jdbc.update("DELETE FROM login_attempts");
        jdbc.update("DELETE FROM authorities");
        jdbc.update("DELETE FROM usuarios");
        users.createUser(User.withUsername("openapi-test").password(encoder.encode(password)).roles("USER").build());
    }

    private HttpResponse<String> get(int listener, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode document() throws Exception {
        var response = get(port, "/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        return json.readTree(response.body());
    }

    @Test
    void generatesMetadataAndExactlyFiveBusinessOperations() throws Exception {
        var doc = document();
        assertThat(doc.path("openapi").asText()).startsWith("3.");
        assertThat(doc.at("/info/title").asText()).isEqualTo("AV-02 — Segurança e Observabilidade");
        assertThat(doc.at("/info/version").asText()).isEqualTo("1.0.0");
        assertThat(doc.at("/info/description").asText()).isEqualTo(
                "API REST de gerenciamento de usuários, autenticação e controle de bloqueios");
        var paths = doc.path("paths");
        assertThat(paths.size()).isEqualTo(4);
        assertThat(paths.path("/").size()).isEqualTo(2);
        assertThat(paths.path("/").has("get")).isTrue();
        assertThat(paths.path("/").has("post")).isTrue();
        for (String route : List.of("/login", "/trocarsenha/{userid}", "/unlock/{userid}")) {
            assertThat(paths.path(route).size()).isEqualTo(1);
            assertThat(paths.path(route).has(route.equals("/login") ? "post" : "put")).isTrue();
        }
    }

    @Test
    void documentsEveryHttpResponseWithEmptyBodiesWhereApplicable() throws Exception {
        var paths = document().path("paths");
        assertResponses(paths.path("/login").path("post"), "200", "400", "401");
        assertResponses(paths.path("/trocarsenha/{userid}").path("put"), "204", "400", "401");
        assertResponses(paths.path("/unlock/{userid}").path("put"), "204", "404", "401", "403");
        assertResponses(paths.path("/").path("post"), "201", "409", "400", "401", "403");
        assertResponses(paths.path("/").path("get"), "200", "401", "403");
    }

    private void assertResponses(JsonNode operation, String... codes) {
        assertThat(operation.path("responses").size()).isEqualTo(codes.length);
        for (String code : codes) {
            var response = operation.path("responses").path(code);
            assertThat(response.path("description").asText()).isNotBlank();
            if (!operation.path("operationId").asText().equals("listUsers") || !code.equals("200")) {
                assertThat(response.has("content")).isFalse();
            }
        }
    }

    @Test
    void separatesPublicOperationsFromBasicAndDocumentsRolesWithoutScopes() throws Exception {
        var doc = document();
        assertThat(doc.has("security")).isFalse();
        assertThat(doc.at("/components/securitySchemes").size()).isEqualTo(1);
        var scheme = doc.at("/components/securitySchemes/basicAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("basic");
        assertThat(scheme.has("flows")).isFalse();
        var paths = doc.path("paths");
        assertThat(paths.path("/login").path("post").path("security").size()).isZero();
        assertThat(paths.path("/trocarsenha/{userid}").path("put").path("security").size()).isZero();
        for (var op : List.of(paths.path("/").path("post"), paths.path("/").path("get"),
                paths.path("/unlock/{userid}").path("put"))) {
            assertThat(op.path("security").size()).isEqualTo(1);
            assertThat(op.at("/security/0").has("basicAuth")).isTrue();
            assertThat(op.at("/security/0/basicAuth").size()).isZero();
            assertThat(op.path("description").asText()).contains(op.path("operationId").asText().equals("unlock")
                    ? "ROLE_ADMIN" : "ROLE_MANUTENCAO");
        }
    }

    @Test
    void documentsRequiredInputSchemasAndUsernameRouteParameters() throws Exception {
        var doc = document();
        var schemas = doc.at("/components/schemas");
        for (String name : List.of("LoginRequest", "CreateRequest", "PasswordChange")) {
            var schema = schemas.path(name);
            var fields = name.equals("PasswordChange") ? List.of("atual", "nova") : List.of("username", "senha");
            assertThat(schema.path("required").size()).isEqualTo(2);
            assertThat(schema.path("properties").size()).isEqualTo(2);
            for (String field : fields) {
                assertThat(schema.path("required").toString()).contains("\"" + field + "\"");
                var property = schema.path("properties").path(field);
                assertThat(property.path("type").asText()).isEqualTo("string");
                if (!field.equals("username")) {
                    assertThat(property.path("writeOnly").asBoolean()).isTrue();
                    assertThat(property.path("format").asText()).isEqualTo("password");
                    assertThat(property.path("description").asText()).contains("fictício");
                }
            }
        }
        assertThat(schemas.at("/CreateRequest/properties/username/maxLength").asInt()).isEqualTo(100);
        for (String route : List.of("/trocarsenha/{userid}", "/unlock/{userid}")) {
            var op = doc.path("paths").path(route).path("put");
            var parameter = op.path("parameters").get(0);
            assertThat(parameter.path("name").asText()).isEqualTo("userid");
            assertThat(parameter.path("in").asText()).isEqualTo("path");
            assertThat(parameter.path("required").asBoolean()).isTrue();
            assertThat(parameter.at("/schema/type").asText()).isEqualTo("string");
            assertThat(parameter.path("description").asText()).contains("Username");
        }
        for (var op : List.of(doc.path("paths").path("/login").path("post"),
                doc.path("paths").path("/").path("post"),
                doc.path("paths").path("/trocarsenha/{userid}").path("put"))) {
            assertThat(op.at("/requestBody/required").asBoolean()).isTrue();
            assertThat(op.at("/requestBody/content/application~1json/schema/$ref").asText()).startsWith("#/components/schemas/");
        }
    }

    @Test
    void listingSchemaOnlyContainsUsernameAndDocumentDoesNotExposeStoredCredentials() throws Exception {
        var doc = document();
        var response = doc.path("paths").path("/").path("get").at("/responses/200/content/application~1json/schema");
        assertThat(response.path("type").asText()).isEqualTo("array");
        String ref = response.at("/items/$ref").asText();
        assertThat(ref).isEqualTo("#/components/schemas/UserSummary");
        var properties = doc.at("/components/schemas/UserSummary/properties");
        assertThat(properties.size()).isEqualTo(1);
        assertThat(properties.path("username").path("type").asText()).isEqualTo("string");
        String hash = jdbc.queryForObject("SELECT senha FROM usuarios WHERE username = 'openapi-test'", String.class);
        assertThat(doc.toString()).doesNotContain(password, hash, "{bcrypt}", "$2a$", "authorities", "UserDetails");
    }

    @Test
    void servesYamlSwaggerConfigAndActualUiResourcesPublicly() throws Exception {
        var yaml = get(port, "/v3/api-docs.yaml");
        assertThat(yaml.statusCode()).isEqualTo(200);
        assertThat(yaml.body()).contains("openapi:", "basicAuth:", "username:");
        var config = get(port, "/v3/api-docs/swagger-config");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(json.readTree(config.body()).path("persistAuthorization").asBoolean()).isFalse();
        for (String path : List.of("/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
                "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js")) {
            var response = get(port, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).isNotBlank();
        }
        var redirect = get(port, "/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location").orElse("")).endsWith("/swagger-ui/index.html");
    }

    @Test
    void onlyDocumentationGetsPublicAccessAndManagementListenerStaysRestricted() throws Exception {
        for (String path : List.of("/", "/v3/api-docs/not-allowed", "/actuator/env")) {
            assertThat(get(port, path).statusCode()).as(path).isEqualTo(401);
        }
        var post = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/v3/api-docs").statusCode()).isNotEqualTo(200);
    }
}
