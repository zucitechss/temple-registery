package com.templeregistry.service.notification;

import com.templeregistry.entity.notification.NotificationRule;
import com.templeregistry.repository.notification.NotificationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup validator for the email notification system.
 *
 * <p>Runs after the application context is fully started (at {@link ApplicationReadyEvent})
 * and verifies that every active email-capable notification rule has a corresponding Thymeleaf
 * template file on the classpath.
 *
 * <p>If {@code email.startup-validation.fail-fast=true} (default: {@code false}), the
 * application will refuse to start when any template is missing — useful in production to
 * prevent silent email failures at runtime.  In development the validator logs errors but
 * allows the application to continue.
 *
 * <p>Example log output when a template is missing:
 * <pre>
 *   [EmailStartupValidator] MISSING TEMPLATE: rule.id=5 action=RESPOND_CLARIFICATION
 *       templateKey=clarification-response  resolved=email/clarification-response
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

    private final NotificationRuleRepository notificationRuleRepository;
    private final EmailTemplateResolver templateResolver;
    private final TemplateEngine templateEngine;

    @Value("${email.startup-validation.fail-fast:false}")
    private boolean failFast;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("[EmailStartupValidator] Validating email templates against notification rules ...");

        List<NotificationRule> rules =
            notificationRuleRepository.findByEnabledTrueAndDeletedFalse();

        List<String> missing = new ArrayList<>();

        for (var rule : rules) {
            String channel = rule.getChannel();
            if (!"EMAIL".equals(channel) && !"BOTH".equals(channel)) continue;

            String resolved = templateResolver.resolve(rule.getTemplateKey());
            if (!templateExists(resolved)) {
                log.error("[EmailStartupValidator] MISSING TEMPLATE: rule.id={} action={} "
                    + "templateKey={} resolved={}",
                    rule.getId(), rule.getAction(), rule.getTemplateKey(), resolved);
                missing.add(resolved);
            }
        }

        // Always validate fixed templates
        for (String fixedTemplate : List.of("email/password-reset", "email/account-created",
                "email/temporary-password", "email/notification")) {
            if (!templateExists(fixedTemplate)) {
                log.error("[EmailStartupValidator] MISSING FIXED TEMPLATE: {}", fixedTemplate);
                missing.add(fixedTemplate);
            }
        }

        if (missing.isEmpty()) {
            log.info("[EmailStartupValidator] ✓ All {} email templates validated successfully.",
                rules.size());
        } else {
            String msg = "[EmailStartupValidator] Validation FAILED — " + missing.size()
                + " missing template(s): " + missing;
            if (failFast) {
                throw new IllegalStateException(msg + "\nEnsure all templates exist under "
                    + "src/main/resources/templates/email/ before starting the application.");
            }
            log.error(msg);
            log.error("[EmailStartupValidator] Emails for the above templates will fail at delivery "
                + "time. Set 'email.startup-validation.fail-fast=true' to make this a hard failure.");
        }
    }

    /**
     * Attempts to process the template with a minimal context.
     * Returns {@code true} if Thymeleaf can locate and parse the template without errors.
     */
    private boolean templateExists(String templatePath) {
        try {
            Context ctx = new Context();
            ctx.setVariable("title",         "Validation Test");
            ctx.setVariable("year",          2025);
            ctx.setVariable("actionUrl",     "http://localhost");
            ctx.setVariable("body",          "Test body");
            ctx.setVariable("reason",        "");
            ctx.setVariable("templeName",    "Test Temple");
            ctx.setVariable("actorLabel",    "Test Actor");
            ctx.setVariable("actorName",     "Test User");
            ctx.setVariable("redirectUrl",   "http://localhost");
            ctx.setVariable("entityType",    "TEMPLE_PROFILE");
            ctx.setVariable("workflowStatus","SUBMITTED");
            ctx.setVariable("priority",      "MEDIUM");
            ctx.setVariable("comment",       "");
            ctx.setVariable("username",      "testuser");
            ctx.setVariable("temporaryPassword", "test");
            ctx.setVariable("role",          "Temple Authority");
            ctx.setVariable("loginUrl",      "http://localhost/login");
            ctx.setVariable("resetLink",     "http://localhost/reset");
            ctx.setVariable("expiryMinutes", 30);
            ctx.setVariable("fullName",      "Test User");
            templateEngine.process(templatePath, ctx);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
