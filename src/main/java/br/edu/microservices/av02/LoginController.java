package br.edu.microservices.av02;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
    private final LoginService loginService;
    private final LoginMetrics metrics;

    public LoginController(LoginService loginService, LoginMetrics metrics) {
        this.loginService = loginService;
        this.metrics = metrics;
    }

    @PostMapping("/login")
    @Operation(summary = "Autentica um usuário", tags = "Login",
            description = "Público. Valida username e senha. Não cria sessão ou token. "
                    + "Credenciais inválidas e contas bloqueadas retornam a mesma resposta 401.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Autenticação válida", content = @Content),
            @ApiResponse(responseCode = "401", description = "Credenciais inválidas ou usuário bloqueado", content = @Content),
            @ApiResponse(responseCode = "400", description = "JSON ou campos obrigatórios inválidos", content = @Content)
    })
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request) {
        boolean authenticated = loginService.login(request.username(), request.senha());
        // The transactional service has committed; validation and HTTP Basic never reach this point.
        metrics.recordLogin(authenticated);
        return authenticated
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
    public ResponseEntity<Void> invalidRequest() {
        return ResponseEntity.badRequest().build();
    }

    public record LoginRequest(
            @Schema(description = "Username da conta", example = "usuario-exemplo", minLength = 1,
                    requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String username,
            @Schema(description = "Senha; exemplo exclusivamente fictício", example = "senha-ficticia-exemplo",
                    format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String senha) { }
}
