package com.templeregistry.config;

import com.templeregistry.entity.auth.MfaType;
import com.templeregistry.entity.auth.User;
import com.templeregistry.entity.auth.UserRole;
import com.templeregistry.repository.auth.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the very first {@code SUPER_ADMIN} from environment variables,
 * replacing the seeded administrator that {@code V2__master_seed_data.sql} used
 * to create in every environment (C-5).
 *
 * <p>The old seed shipped a fixed bcrypt hash whose plaintext is published in
 * this repository, so a fresh production database self-provisioned a
 * full-privilege account with a publicly known password. That seed now lives in
 * {@code classpath:db/seed} and is loaded by the dev/test profiles only.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Disabled unless {@code app.bootstrap.admin.enabled=true}.</li>
 *   <li>A no-op when any {@code SUPER_ADMIN} already exists, so it is safe to
 *       leave enabled across a restart and safe to re-run.</li>
 *   <li>Aborts startup when enabled with incomplete or weak input, rather than
 *       creating a half-configured administrator.</li>
 *   <li>Never logs the password.</li>
 * </ul>
 *
 * <h2>Intended use</h2>
 * Set the four variables for a single boot against a brand-new database, sign
 * in, change the password, then set {@code APP_BOOTSTRAP_ADMIN_ENABLED=false}
 * and redeploy so the credentials leave the environment.
 */
@Component
@Slf4j
public class BootstrapAdminInitializer implements ApplicationRunner {

    /** Floor only — deliberately not a full policy engine. */
    private static final int MIN_PASSWORD_LENGTH = 16;

    /** Rejected outright: these are public in this repository's history. */
    private static final java.util.Set<String> BANNED_PASSWORDS =
            java.util.Set.of("password123", "admin", "changeme", "password");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String email;
    private final String password;

    public BootstrapAdminInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin.enabled:false}") boolean enabled,
            @Value("${app.bootstrap.admin.username:}") String username,
            @Value("${app.bootstrap.admin.email:}") String email,
            @Value("${app.bootstrap.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        if (userRepository.countByRole(UserRole.SUPER_ADMIN) > 0) {
            log.info("Bootstrap admin is enabled but a SUPER_ADMIN already exists — skipping. "
                    + "Set app.bootstrap.admin.enabled=false and redeploy.");
            return;
        }

        validateInputs();

        User admin = new User();
        admin.setUsername(username.trim());
        admin.setEmail(email.trim());
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Super Administrator");
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setActive(true);
        admin.setMfaType(MfaType.NONE);
        admin.setFailedLoginCount(0);
        admin.setCreatedBy(0L);
        admin.setUpdatedBy(0L);

        User saved = userRepository.save(admin);

        // Username only — never the password or its hash.
        log.warn("Bootstrap SUPER_ADMIN created (id={}, username={}). "
                        + "Change this password immediately, then set "
                        + "app.bootstrap.admin.enabled=false and remove "
                        + "APP_BOOTSTRAP_ADMIN_PASSWORD from the environment.",
                saved.getId(), saved.getUsername());
    }

    private void validateInputs() {
        requireText(username, "app.bootstrap.admin.username (APP_BOOTSTRAP_ADMIN_USERNAME)");
        requireText(email, "app.bootstrap.admin.email (APP_BOOTSTRAP_ADMIN_EMAIL)");
        requireText(password, "app.bootstrap.admin.password (APP_BOOTSTRAP_ADMIN_PASSWORD)");

        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "app.bootstrap.admin.password must be at least "
                            + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (BANNED_PASSWORDS.contains(password.toLowerCase())) {
            throw new IllegalStateException(
                    "app.bootstrap.admin.password is a well-known value published in "
                            + "this repository's history. Choose a new secret.");
        }
        if (userRepository.existsByUsername(username.trim())) {
            throw new IllegalStateException(
                    "Cannot bootstrap SUPER_ADMIN: username already exists.");
        }
        if (userRepository.existsByEmail(email.trim())) {
            throw new IllegalStateException(
                    "Cannot bootstrap SUPER_ADMIN: email already exists.");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "app.bootstrap.admin.enabled=true but " + propertyName + " is not set.");
        }
    }
}
