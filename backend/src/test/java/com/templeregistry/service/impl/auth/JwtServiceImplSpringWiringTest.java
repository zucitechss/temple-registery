package com.templeregistry.service.impl.auth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves Spring can actually build {@link JwtServiceImpl}.
 *
 * <p>Every other test in this package constructs the service directly with the
 * direct-key constructor, so none of them exercises container wiring. That gap let a
 * startup-breaking defect ship: the class has two public constructors and, with neither
 * marked {@code @Autowired}, Spring could not choose between them and fell back to looking
 * for a no-arg constructor that does not exist:</p>
 *
 * <pre>
 * BeanInstantiationException: Failed to instantiate [JwtServiceImpl]:
 *   No default constructor found
 * Caused by: NoSuchMethodException: JwtServiceImpl.&lt;init&gt;()
 * </pre>
 *
 * <p>The application aborted startup on a clean database <em>after</em> Flyway had applied
 * every migration, so no existing test caught it: {@code ApplicationContextIT} fails earlier
 * on the known schema drift and never reaches this bean.</p>
 *
 * <p>A plain {@code AnnotationConfigApplicationContext} is used rather than
 * {@code @SpringBootTest} so this stays a fast unit-tier test with no database.</p>
 */
class JwtServiceImplSpringWiringTest {

    @Configuration
    static class KeyProviderConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            // Lets the @Value defaults on both beans resolve without a property file.
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        JwtKeyProvider jwtKeyProvider() {
            RsaTestKeys keys = RsaTestKeys.generate();
            return new JwtKeyProvider(
                    keys.privateKeyPem(), keys.publicKeyPem(),
                    "",   // app.jwt.private-key-path — blank, as in production
                    "",   // app.jwt.public-key-path  — blank, as in production
                    new DefaultResourceLoader());
        }
    }

    @Test
    void should_beInstantiableByTheSpringContainer_when_onlyAJwtKeyProviderIsAvailable() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext()) {
                context.register(KeyProviderConfig.class, JwtServiceImpl.class);
                context.refresh();

                assertThat(context.getBean(JwtServiceImpl.class))
                        .as("Spring must be able to construct the production JWT service")
                        .isNotNull();
            }
        })
                .as("startup must not fail on an ambiguous JwtServiceImpl constructor")
                .doesNotThrowAnyException();
    }

    @Test
    void should_signAWorkingToken_when_builtByTheContainer() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(KeyProviderConfig.class, JwtServiceImpl.class);
            context.refresh();

            JwtServiceImpl service = context.getBean(JwtServiceImpl.class);

            // The container-built instance must use the injected keypair, not nulls.
            assertThat(service.generateRegistrationToken(
                    Map.of("purpose", "REGISTRATION"), Duration.ofMinutes(15)))
                    .as("a container-built service must produce a usable token")
                    .isNotBlank();
        }
    }
}
