package com.templeregistry.service.impl.notification;

import com.templeregistry.entity.notification.EmailDeliveryLog;
import com.templeregistry.event.base.BaseNotificationEvent;
import com.templeregistry.repository.notification.EmailDeliveryLogRepository;
import com.templeregistry.service.notification.EmailService;
import com.templeregistry.service.notification.EmailTemplateResolver;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Implementation of {@link EmailService}.
 * Sends email notifications using JavaMailSender and Thymeleaf templates.
 *
 * <h3>Template resolution</h3>
 * All template keys (short form from {@code notification_rules.template_key}) are resolved
 * to their full Thymeleaf path via {@link EmailTemplateResolver} which prepends {@code "email/"}
 * when needed.  This fixes the critical bug where bare keys like {@code "submission-notification"}
 * resolved to {@code classpath:/templates/submission-notification.html} (does not exist) instead of
 * the correct {@code classpath:/templates/email/submission-notification.html}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final EmailDeliveryLogRepository deliveryLogRepository;
    private final com.templeregistry.repository.auth.UserRepository userRepository;
    private final EmailTemplateResolver templateResolver;

    @Value("${spring.mail.from:noreply@templeregistry.gov.in}")
    private String fromEmail;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${spring.mail.enabled:false}")
    private boolean emailEnabled;

    // ─── Legacy path ──────────────────────────────────────────────────────────

    @Override
    @Async("taskExecutor")
    public void sendNotificationEmail(String recipientEmail, BaseNotificationEvent event, Long notificationEventId) {
        if (!emailEnabled) {
            log.debug("Email sending is disabled. Skipping email to {}", recipientEmail);
            return;
        }

        String templateName = determineTemplateName(event);
        String subject = event.getNotificationTitle();

        try {
            Context context = new Context();
            context.setVariable("title", event.getNotificationTitle());
            context.setVariable("body", event.getNotificationBody());
            context.setVariable("actionUrl", baseUrl + event.getActionUrl());
            context.setVariable("priority", event.getPriority().name());
            context.setVariable("category", event.getCategory().name());
            context.setVariable("year", LocalDateTime.now().getYear());

            String htmlContent = templateEngine.process(templateName, context);

            send(recipientEmail, subject, htmlContent);
            logEmailDelivery(notificationEventId, recipientEmail, subject, templateName, "SENT", null);
            log.info("Email sent successfully: recipient=[{}] subject=[{}]", recipientEmail, subject);

        } catch (MessagingException ex) {
            log.error("Failed to send email: recipient=[{}] subject=[{}]", recipientEmail, subject, ex);
            logEmailDelivery(notificationEventId, recipientEmail, subject, templateName, "FAILED", ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error sending email: recipient=[{}]", recipientEmail, ex);
            logEmailDelivery(notificationEventId, recipientEmail, subject, templateName, "FAILED", ex.getMessage());
        }
    }

    // ─── V2 pipeline ─────────────────────────────────────────────────────────

    /**
     * V2 pipeline entry-point used by {@link com.templeregistry.service.notification.impl.EmailDeliveryService}.
     *
     * <p>Template key resolution: {@code "submission-notification"} is automatically prefixed
     * to {@code "email/submission-notification"} by {@link EmailTemplateResolver}.
     */
    @Override
    @Async("taskExecutor")
    public void sendNotification(Long recipientId, String subject, String templateKey,
                                  Map<String, Object> metadata) {
        if (!emailEnabled) {
            log.debug("[EmailService v2] Email disabled — skip userId={}", recipientId);
            return;
        }
        if (recipientId == null) {
            log.warn("[EmailService v2] recipientId is null — skipping");
            return;
        }

        String recipientEmail = userRepository.findById(recipientId)
            .map(com.templeregistry.entity.auth.User::getEmail)
            .orElse(null);
        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("[EmailService v2] No email for userId={} — skipping", recipientId);
            return;
        }

        // ── CRITICAL FIX: always resolve through EmailTemplateResolver ──
        String resolvedTemplate = templateResolver.resolve(templateKey);

        try {
            Context context = new Context();
            context.setVariable("title", subject);
            context.setVariable("year", LocalDateTime.now().getYear());
            context.setVariable("actionUrl", baseUrl);
            if (metadata != null) metadata.forEach(context::setVariable);

            String htmlContent = templateEngine.process(resolvedTemplate, context);
            send(recipientEmail, subject, htmlContent);

            logEmailDelivery(null, recipientEmail, subject, resolvedTemplate, "SENT", null);
            log.info("[EmailService v2] Sent userId={} email=[{}] template=[{}]",
                recipientId, recipientEmail, resolvedTemplate);

        } catch (MessagingException ex) {
            log.error("[EmailService v2] MessagingException userId={} template=[{}]", recipientId, resolvedTemplate, ex);
            logEmailDelivery(null, recipientEmail, subject, resolvedTemplate, "FAILED", ex.getMessage());
            throw new RuntimeException("Email send failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("[EmailService v2] Unexpected error userId={} template=[{}]", recipientId, resolvedTemplate, ex);
            logEmailDelivery(null, recipientEmail, subject, resolvedTemplate, "FAILED", ex.getMessage());
            throw ex;
        }
    }

    // ─── Password reset ───────────────────────────────────────────────────────

    @Override
    @Async("taskExecutor")
    public void sendPasswordResetEmail(String recipientEmail, String resetLink) {
        if (!emailEnabled) {
            log.info("[PasswordReset] Email disabled — reset link generated for [{}]", recipientEmail);
            return;
        }
        String template = "email/password-reset";
        try {
            Context context = new Context();
            context.setVariable("title", "Password Reset Request");
            context.setVariable("resetLink", resetLink);
            context.setVariable("expiryMinutes", 30);
            context.setVariable("year", LocalDateTime.now().getYear());
            context.setVariable("actionUrl", resetLink);

            String htmlContent = templateEngine.process(template, context);
            send(recipientEmail, "Temple Registry — Password Reset", htmlContent);

            logEmailDelivery(null, recipientEmail, "Temple Registry — Password Reset",
                template, "SENT", null);
            log.info("[PasswordReset] Reset email sent to [{}]", recipientEmail);

        } catch (MessagingException ex) {
            log.error("[PasswordReset] Failed to send reset email to [{}]", recipientEmail, ex);
            logEmailDelivery(null, recipientEmail, "Temple Registry — Password Reset",
                template, "FAILED", ex.getMessage());
        }
    }

    // ─── User account creation ────────────────────────────────────────────────

    /**
     * Sends a welcome / credential email when a SUPER_ADMIN creates a new user account.
     *
     * <p><strong>Security</strong>: the {@code temporaryPassword} is passed only to the
     * Thymeleaf template renderer and is never logged.
     */
    @Override
    @Async("taskExecutor")
    public void sendUserAccountCreatedEmail(String recipientEmail, String username,
                                             String temporaryPassword, String role,
                                             String loginUrl) {
        if (!emailEnabled) {
            log.info("[AccountCreated] Email disabled — account created for [{}] username=[{}]",
                recipientEmail, username);
            return;
        }
        String template = "email/account-created";
        String subject  = "Welcome to Temple Registry — Your Account Is Ready";
        try {
            Context context = new Context();
            context.setVariable("title",             "Your Account Is Ready");
            context.setVariable("username",          username);
            context.setVariable("temporaryPassword", temporaryPassword);  // never logged below
            context.setVariable("role",              formatRole(role));
            context.setVariable("loginUrl",          loginUrl);
            context.setVariable("year",              LocalDateTime.now().getYear());
            context.setVariable("actionUrl",         loginUrl);

            String htmlContent = templateEngine.process(template, context);
            send(recipientEmail, subject, htmlContent);

            logEmailDelivery(null, recipientEmail, subject, template, "SENT", null);
            // Log only username — NEVER the password
            log.info("[AccountCreated] Credentials email sent to [{}] username=[{}]",
                recipientEmail, username);

        } catch (MessagingException ex) {
            log.error("[AccountCreated] Failed to send credentials email to [{}] username=[{}]",
                recipientEmail, username, ex);
            logEmailDelivery(null, recipientEmail, subject, template, "FAILED", ex.getMessage());
        }
    }

    // ─── Admin-issued temporary password ─────────────────────────────────────

    /**
     * Sends the temporary password produced by the Super Admin "reset user password" action.
     *
     * <p><strong>Security</strong>: the {@code temporaryPassword} is passed only to the
     * Thymeleaf template renderer and is never logged.
     */
    @Override
    @Async("taskExecutor")
    public void sendTemporaryPasswordEmail(String recipientEmail, String fullName, String username,
                                            String temporaryPassword, String loginUrl) {
        if (!emailEnabled) {
            log.info("[TempPassword] Email disabled — temporary password issued for username=[{}]", username);
            return;
        }
        String template = "email/temporary-password";
        String subject  = "Temple Registry — Your Password Has Been Reset";
        try {
            Context context = new Context();
            context.setVariable("title",             "Your Password Has Been Reset");
            context.setVariable("fullName",          fullName);
            context.setVariable("username",          username);
            context.setVariable("temporaryPassword", temporaryPassword);  // never logged below
            context.setVariable("loginUrl",          loginUrl);
            context.setVariable("year",              LocalDateTime.now().getYear());
            context.setVariable("actionUrl",         loginUrl);

            String htmlContent = templateEngine.process(template, context);
            send(recipientEmail, subject, htmlContent);

            logEmailDelivery(null, recipientEmail, subject, template, "SENT", null);
            // Log only the username — NEVER the password
            log.info("[TempPassword] Temporary password email sent to [{}] username=[{}]",
                recipientEmail, username);

        } catch (MessagingException ex) {
            log.error("[TempPassword] Failed to send temporary password email to [{}] username=[{}]",
                recipientEmail, username, ex);
            logEmailDelivery(null, recipientEmail, subject, template, "FAILED", ex.getMessage());
        }
    }

    // ─── Test email ───────────────────────────────────────────────────────────

    @Override
    public void sendTestEmail(String recipientEmail) {
        if (!emailEnabled) {
            log.warn("Email sending is disabled. Cannot send test email.");
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("title",     "Test Email");
            context.setVariable("body",      "This is a test email from Temple Registry System.");
            context.setVariable("actionUrl", baseUrl);
            context.setVariable("priority",  "LOW");
            context.setVariable("category",  "SYSTEM");
            context.setVariable("year",      LocalDateTime.now().getYear());

            String htmlContent = templateEngine.process("email/notification", context);
            send(recipientEmail, "Test Email - Temple Registry System", htmlContent);
            log.info("Test email sent successfully to: {}", recipientEmail);

        } catch (Exception ex) {
            log.error("Failed to send test email to: {}", recipientEmail, ex);
            throw new RuntimeException("Failed to send test email", ex);
        }
    }

    // ─── Deprecated legacy retry path ────────────────────────────────────────

    /**
     * @deprecated Use {@link com.templeregistry.service.notification.impl.EmailDeliveryService#processRetries()}
     *             which reads the full context from {@link com.templeregistry.entity.notification.EmailOutbox}.
     */
    @Override
    @Deprecated(forRemoval = true)
    public void resendByLog(String recipientEmail, String subject, String templateName)
            throws MessagingException {
        log.warn("[EmailService] resendByLog() called — this method is deprecated. "
            + "Retries should go through EmailDeliveryService.processRetries().");
        String resolved = templateResolver.resolve(templateName);
        Context context = new Context();
        context.setVariable("title",     subject);
        context.setVariable("year",      LocalDateTime.now().getYear());
        context.setVariable("actionUrl", baseUrl);

        String htmlContent = templateEngine.process(resolved, context);
        send(recipientEmail, subject, htmlContent);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void send(String recipientEmail, String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(recipientEmail);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);
        mailSender.send(message);
    }

    private void logEmailDelivery(Long notificationEventId, String recipientEmail, String subject,
                                   String templateName, String status, String failureReason) {
        try {
            EmailDeliveryLog entry = EmailDeliveryLog.builder()
                .notificationEventId(notificationEventId)
                .recipientEmail(recipientEmail)
                .subject(subject)
                .templateName(templateName)
                .status(status)
                .sentAt("SENT".equals(status) ? LocalDateTime.now() : null)
                .failureReason(failureReason)
                .retryCount(0)
                .build();
            deliveryLogRepository.save(entry);
        } catch (Exception ex) {
            log.error("Failed to log email delivery: recipient=[{}]", recipientEmail, ex);
        }
    }

    private String determineTemplateName(BaseNotificationEvent event) {
        return switch (event.getCategory()) {
            case APPROVAL      -> "email/approval-notification";
            case REJECTION     -> "email/rejection-notification";
            case CLARIFICATION -> "email/clarification-notification";
            case SITE_VISIT    -> "email/site-visit-notification";
            case SUBMISSION    -> "email/submission-notification";
            default            -> "email/notification";
        };
    }

    private String formatRole(String rawRole) {
        if (rawRole == null) return "User";
        return switch (rawRole.toUpperCase()) {
            case "SUPER_ADMIN"        -> "Super Administrator";
            case "DISTRICT_COLLECTOR" -> "District Collector";
            case "DC_STAFF"           -> "DC Staff";
            case "TEMPLE_AUTHORITY"   -> "Temple Authority";
            case "AUDITOR"            -> "Auditor";
            case "VIEWER"             -> "Viewer";
            default                   -> rawRole.replace("_", " ");
        };
    }
}
