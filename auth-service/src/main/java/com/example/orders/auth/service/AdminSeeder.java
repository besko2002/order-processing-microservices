package com.example.orders.auth.service;

import com.example.orders.auth.config.AuthProperties;
import com.example.orders.auth.domain.Emails;
import com.example.orders.auth.domain.Role;
import com.example.orders.auth.domain.UserAccount;
import com.example.orders.auth.domain.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Makes sure an ADMIN account exists after startup. Idempotent: running it again on a database
 * that already has the admin is a no-op, and it never rewrites an existing password.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    AdminSeeder(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * Creates the configured admin unless it is already there.
     *
     * @return {@code true} when a row was inserted by this call
     */
    public boolean seed() {
        AuthProperties.Admin admin = properties.admin();
        String email = Emails.normalize(admin.email());
        if (users.existsByEmail(email)) {
            log.debug("Admin account {} already present, nothing to seed", email);
            return false;
        }

        UserAccount account = UserAccount.create(
                admin.name(), email, passwordEncoder.encode(admin.password()), Role.ADMIN);
        try {
            users.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // Another instance seeded it between the check and the insert.
            log.debug("Admin account {} was seeded concurrently", email, e);
            return false;
        }
        log.info("Seeded ADMIN account {} ({})", account.getId(), email);
        return true;
    }
}
