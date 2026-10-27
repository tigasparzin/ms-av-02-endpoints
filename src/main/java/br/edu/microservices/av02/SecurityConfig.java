package br.edu.microservices.av02;

import javax.sql.DataSource;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(JdbcUserDetailsManager users,
            PasswordEncoder encoder, JdbcTemplate jdbc) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        AccountStatusUserDetailsChecker accountChecks = new AccountStatusUserDetailsChecker();
        provider.setPreAuthenticationChecks(user -> {
            accountChecks.check(user);
            Integer locked = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM login_attempts WHERE username = ? AND locked = TRUE",
                    Integer.class, user.getUsername());
            if (locked != null && locked > 0) {
                throw new LockedException("Invalid credentials");
            }
        });
        return provider;
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/login").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/trocarsenha/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/").hasRole("MANUTENCAO")
                        .requestMatchers(HttpMethod.GET, "/").hasRole("MANUTENCAO")
                        .requestMatchers(HttpMethod.PUT, "/unlock/*").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    JdbcUserDetailsManager userDetailsService(DataSource dataSource) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        manager.setUsersByUsernameQuery("SELECT username, senha, enabled FROM usuarios WHERE username = ?");
        manager.setAuthoritiesByUsernameQuery("SELECT username, authority FROM authorities WHERE username = ?");
        manager.setCreateUserSql("INSERT INTO usuarios (username, senha, enabled) VALUES (?, ?, ?)");
        manager.setUpdateUserSql("UPDATE usuarios SET senha = ?, enabled = ? WHERE username = ?");
        manager.setDeleteUserSql("DELETE FROM usuarios WHERE username = ?");
        manager.setUserExistsSql("SELECT COUNT(*) FROM usuarios WHERE username = ?");
        manager.setChangePasswordSql("UPDATE usuarios SET senha = ? WHERE username = ?");
        manager.setCreateAuthoritySql("INSERT INTO authorities (username, authority) VALUES (?, ?)");
        manager.setDeleteUserAuthoritiesSql("DELETE FROM authorities WHERE username = ?");
        return manager;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
