package br.edu.microservices.av02;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @PostMapping("/")
    @Operation(summary = "Cadastra um usuário", tags = "Usuários",
            description = "Exige ROLE_MANUTENCAO via HTTP Basic. A nova conta recebe ROLE_USER.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cadastro realizado", content = @Content),
            @ApiResponse(responseCode = "409", description = "Username já cadastrado", content = @Content),
            @ApiResponse(responseCode = "400", description = "Dados inválidos; senha limitada a 72 bytes UTF-8 pelo BCrypt", content = @Content),
            @ApiResponse(responseCode = "401", description = "Não autenticado", content = @Content),
            @ApiResponse(responseCode = "403", description = "Sem ROLE_MANUTENCAO", content = @Content)
    })
    public ResponseEntity<Void> create(@Valid @RequestBody CreateRequest request) {
        return users.create(request.username(), request.senha())
                ? ResponseEntity.status(HttpStatus.CREATED).build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PutMapping("/trocarsenha/{userid}")
    @Operation(summary = "Troca a senha", tags = "Usuários",
            description = "Público. Valida a senha atual no banco. userid corresponde ao username. "
                    + "A nova senha aceita até 72 bytes UTF-8 pelo BCrypt.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Senha alterada", content = @Content),
            @ApiResponse(responseCode = "401", description = "Credencial inválida", content = @Content),
            @ApiResponse(responseCode = "400", description = "Requisição inválida", content = @Content)
    })
    public ResponseEntity<Void> changePassword(
            @Parameter(description = "Username da conta", example = "usuario-exemplo", required = true)
            @PathVariable String userid,
            @Valid @RequestBody PasswordChange request) {
        return users.changePassword(userid, request.atual(), request.nova())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PutMapping("/unlock/{userid}")
    @Operation(summary = "Desbloqueia um usuário", tags = "Usuários",
            description = "Exige ROLE_ADMIN via HTTP Basic. userid corresponde ao username.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Desbloqueio realizado", content = @Content),
            @ApiResponse(responseCode = "404", description = "Usuário inexistente", content = @Content),
            @ApiResponse(responseCode = "401", description = "Não autenticado", content = @Content),
            @ApiResponse(responseCode = "403", description = "Sem ROLE_ADMIN", content = @Content)
    })
    public ResponseEntity<Void> unlock(
            @Parameter(description = "Username da conta", example = "usuario-exemplo", required = true)
            @PathVariable String userid) {
        return users.unlock(userid) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/")
    @Operation(summary = "Lista usernames", tags = "Usuários",
            description = "Exige ROLE_MANUTENCAO via HTTP Basic. Retorna array de objetos contendo "
                    + "somente username, ordenados por username. Não retorna senhas ou hashes.",
            security = @SecurityRequirement(name = "basicAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de usernames",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserService.UserSummary.class)))),
            @ApiResponse(responseCode = "401", description = "Não autenticado", content = @Content),
            @ApiResponse(responseCode = "403", description = "Sem ROLE_MANUTENCAO", content = @Content)
    })
    public List<UserService.UserSummary> listUsers() { return users.listUsers(); }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class })
    public ResponseEntity<Void> invalidRequest() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Void> duplicateUsername() { return ResponseEntity.status(HttpStatus.CONFLICT).build(); }

    // Client-supplied roles/authorities/enabled are never bound or applied.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateRequest(
            @Schema(description = "Username único, com até 100 caracteres", example = "usuario-exemplo",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 100) String username,
            @Schema(description = "Senha; exemplo exclusivamente fictício", example = "senha-ficticia-exemplo",
                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String senha) { }
    public record PasswordChange(
            @Schema(description = "Senha atual; exemplo exclusivamente fictício", example = "atual-ficticia-exemplo",
                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String atual,
            @Schema(description = "Nova senha; exemplo exclusivamente fictício", example = "nova-ficticia-exemplo",
                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String nova) { }
}
