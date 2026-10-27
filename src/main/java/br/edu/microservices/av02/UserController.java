package br.edu.microservices.av02;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    public ResponseEntity<Void> create(@Valid @RequestBody CreateRequest request) {
        return users.create(request.username(), request.senha())
                ? ResponseEntity.status(HttpStatus.CREATED).build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PutMapping("/trocarsenha/{userid}")
    public ResponseEntity<Void> changePassword(@PathVariable String userid,
            @Valid @RequestBody PasswordChange request) {
        return users.changePassword(userid, request.atual(), request.nova())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PutMapping("/unlock/{userid}")
    public ResponseEntity<Void> unlock(@PathVariable String userid) {
        return users.unlock(userid) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/")
    public List<UserService.UserSummary> listUsers() { return users.listUsers(); }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class })
    public ResponseEntity<Void> invalidRequest() { return ResponseEntity.badRequest().build(); }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Void> duplicateUsername() { return ResponseEntity.status(HttpStatus.CONFLICT).build(); }

    // Client-supplied roles/authorities/enabled are never bound or applied.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateRequest(@NotBlank @Size(max = 100) String username, @NotBlank String senha) { }
    public record PasswordChange(@NotBlank String atual, @NotBlank String nova) { }
}
