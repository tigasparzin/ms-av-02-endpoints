package br.edu.microservices.av02;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoginMetrics {
    private final Counter success;
    private final Counter failure;

    public LoginMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        success = Counter.builder("av02.login.success")
                .description("Logins validos processados por POST /login desde a inicializacao")
                .register(registry);
        failure = Counter.builder("av02.login.failure")
                .description("Logins invalidos processados por POST /login desde a inicializacao")
                .register(registry);
        Gauge.builder("av02.users.locked", jdbc,
                source -> source.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE locked = TRUE", Long.class))
                .description("Quantidade atual de usuarios bloqueados no banco")
                .register(registry);
    }

    public void recordLogin(boolean authenticated) {
        if (authenticated) { success.increment(); }
        else { failure.increment(); }
    }
}
