package br.edu.microservices.av02;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {
    private final AuthenticationManager authenticationManager;
    private final JdbcTemplate jdbc;

    public LoginService(AuthenticationManager authenticationManager, JdbcTemplate jdbc) {
        this.authenticationManager = authenticationManager;
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean login(String username, String password) {
        // Lock the existing user even before the first login_attempts row exists.
        boolean exists = !jdbc.queryForList(
                "SELECT username FROM usuarios WHERE username = ? FOR UPDATE", String.class, username).isEmpty();
        if (exists) {
            jdbc.update("""
                    INSERT INTO login_attempts (username)
                    SELECT ? WHERE NOT EXISTS (SELECT 1 FROM login_attempts WHERE username = ?)
                    """, username, username);
        }
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            jdbc.update("""
                    UPDATE login_attempts SET failed_attempts = 0, last_failed_at = NULL,
                    locked_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE username = ? AND locked = FALSE
                    """, username);
            return true;
        } catch (BadCredentialsException exception) {
            if (exists) {
                jdbc.update("""
                        UPDATE login_attempts SET
                        failed_attempts = failed_attempts + 1,
                        locked = (failed_attempts + 1 >= 2),
                        last_failed_at = CURRENT_TIMESTAMP,
                        locked_at = CASE WHEN failed_attempts + 1 >= 2 THEN CURRENT_TIMESTAMP ELSE NULL END,
                        updated_at = CURRENT_TIMESTAMP
                        WHERE username = ? AND locked = FALSE
                        """, username);
            }
            return false;
        } catch (AuthenticationException exception) {
            // Already locked/disabled accounts do not increase the failure counter.
            return false;
        }
        // Authentication failures are handled here so their updates commit before HTTP 401.
    }
}
