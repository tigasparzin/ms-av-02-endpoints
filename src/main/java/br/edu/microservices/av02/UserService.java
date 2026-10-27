package br.edu.microservices.av02;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final JdbcTemplate jdbc;
    private final JdbcUserDetailsManager users;
    private final PasswordEncoder encoder;

    public UserService(JdbcTemplate jdbc, JdbcUserDetailsManager users, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public boolean create(String username, String password) {
        if (users.userExists(username)) { return false; }
        users.createUser(User.withUsername(username).password(encode(password)).roles("USER").build());
        return true;
    }

    @Transactional
    public boolean changePassword(String username, String current, String replacement) {
        List<String> hashes = jdbc.queryForList(
                "SELECT senha FROM usuarios WHERE username = ? FOR UPDATE", String.class, username);
        if (hashes.isEmpty() || !encoder.matches(current, hashes.get(0))) { return false; }
        // Only the password changes; enabled, roles and login_attempts are preserved.
        jdbc.update("UPDATE usuarios SET senha = ? WHERE username = ?", encode(replacement), username);
        return true;
    }

    @Transactional
    public boolean unlock(String username) {
        if (jdbc.queryForList("SELECT username FROM usuarios WHERE username = ? FOR UPDATE",
                String.class, username).isEmpty()) { return false; }
        jdbc.update("""
                UPDATE login_attempts SET failed_attempts = 0, locked = FALSE,
                last_failed_at = NULL, locked_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE username = ?
                """, username);
        return true;
    }

    public List<UserSummary> listUsers() {
        return jdbc.query("SELECT username FROM usuarios ORDER BY username",
                (rs, row) -> new UserSummary(rs.getString("username")));
    }

    private String encode(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password exceeds BCrypt limit");
        }
        return encoder.encode(password);
    }

    public record UserSummary(String username) { }
}
