package br.edu.microservices.av02;

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

    public record LoginRequest(@NotBlank String username, @NotBlank String senha) { }
}
