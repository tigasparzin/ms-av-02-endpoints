package br.edu.microservices.av02;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TestUsersInitializer implements ApplicationRunner {
    private final JdbcUserDetailsManager users;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String maintenancePassword;

    public TestUsersInitializer(JdbcUserDetailsManager users, PasswordEncoder encoder,
            @Value("${av02.test-users.admin-password}") String adminPassword,
            @Value("${av02.test-users.maintenance-password}") String maintenancePassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.maintenancePassword = maintenancePassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfMissing("admin", adminPassword, "ADMIN");
        createIfMissing("manutencao", maintenancePassword, "MANUTENCAO");
    }

    private void createIfMissing(String username, String password, String role) {
        if (!password.isBlank() && !users.userExists(username)) {
            users.createUser(User.withUsername(username)
                    .password(encoder.encode(password)).roles(role).build());
        }
    }
}
