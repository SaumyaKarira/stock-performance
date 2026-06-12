package com.project.service;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Sends the generated CSV reports as email attachments via SMTP.
 *
 * <h2>Configuration</h2>
 * The service is configured through a {@link EmailConfig} value object, which
 * can be populated from environment variables, a properties file, or direct
 * construction in tests.
 *
 * <h2>MailHog support</h2>
 * MailHog is a development SMTP server that listens on port 1025 and captures
 * all outgoing mail.  Set {@code smtpPort=1025}, {@code auth=false},
 * and {@code tls=false} to use it.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li>Jakarta Mail (formerly JavaMail) is used — the standard for Java
 *       email, now under Eclipse Angus for the implementation.</li>
 *   <li>Multipart/mixed is used so the CSV files are proper attachments, not
 *       inline content.</li>
 *   <li>A plain-text body is included so the message is readable even without
 *       an attachment viewer.</li>
 *   <li>Each attachment is read into a {@link jakarta.mail.util.ByteArrayDataSource}
 *       to avoid file-handle leaks on failure.</li>
 * </ul>
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailConfig config;

    public EmailService(EmailConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Sends an email with all {@code attachments} as CSV attachments.
     *
     * @param attachments list of CSV files to attach; must not be null or empty
     * @throws MessagingException           if the SMTP interaction fails
     * @throws UnsupportedEncodingException if the sender display name cannot be encoded
     */
    public void send(List<Path> attachments) throws MessagingException, UnsupportedEncodingException {
        Objects.requireNonNull(attachments, "attachments must not be null");
        if (attachments.isEmpty()) {
            log.warn("No attachments provided — skipping email.");
            return;
        }

        Session session = buildSession();
        Message message = buildMessage(session, attachments);

        log.info("Sending email to {} via {}:{} …",
                config.toAddress(), config.smtpHost(), config.smtpPort());
        Transport.send(message);
        log.info("Email sent successfully.");
    }

    // ── builder helpers ──────────────────────────────────────────────────────

    private Session buildSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        props.put("mail.smtp.auth", String.valueOf(config.auth()));
        if (config.tls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        if (config.auth()) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.username(), config.password());
                }
            });
        }
        return Session.getInstance(props);
    }

    private Message buildMessage(Session session, List<Path> attachments)
            throws MessagingException, UnsupportedEncodingException {

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(config.fromAddress(), config.fromName()));
        msg.setRecipients(Message.RecipientType.TO,
                InternetAddress.parse(config.toAddress()));
        msg.setSubject("Stock Performance Reports");

        // ── body ────────────────────────────────────────────────────────────
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(buildBody(attachments));

        // ── attachments ─────────────────────────────────────────────────────
        Multipart multipart = new MimeMultipart("mixed");
        multipart.addBodyPart(textPart);

        for (Path file : attachments) {
            if (!Files.exists(file)) {
                log.warn("Attachment not found, skipping: {}", file);
                continue;
            }
            MimeBodyPart attachPart = new MimeBodyPart();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (Exception e) {
                log.warn("Cannot read attachment {}: {}", file, e.getMessage());
                continue;
            }
            attachPart.setDataHandler(new jakarta.activation.DataHandler(
                    new jakarta.mail.util.ByteArrayDataSource(bytes, "text/csv")));
            attachPart.setFileName(file.getFileName().toString());
            multipart.addBodyPart(attachPart);
        }

        msg.setContent(multipart);
        return msg;
    }

    private String buildBody(List<Path> attachments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please find attached the latest stock performance reports.\n\n");
        sb.append("Attachments:\n");
        for (Path p : attachments) {
            sb.append("  - ").append(p.getFileName()).append("\n");
        }
        sb.append("\nThis email was generated automatically by the Stock Performance Calculator.");
        return sb.toString();
    }

    // ── configuration value object ───────────────────────────────────────────

    /**
     * Immutable configuration bundle for the email service.
     *
     * <p>Defaults are set for MailHog (no auth, port 1025) so the application
     * works out-of-the-box in the Docker Compose environment.</p>
     */
    public record EmailConfig(
            String smtpHost,
            int    smtpPort,
            boolean auth,
            boolean tls,
            String username,
            String password,
            String fromAddress,
            String fromName,
            String toAddress
    ) {
        /** Convenience factory: reads well-known environment variables. */
        public static EmailConfig fromEnv() {
            return new EmailConfig(
                    getEnvOrDefault("SMTP_HOST",         "localhost"),
                    Integer.parseInt(getEnvOrDefault("SMTP_PORT",  "1025")),
                    Boolean.parseBoolean(getEnvOrDefault("SMTP_AUTH",  "false")),
                    Boolean.parseBoolean(getEnvOrDefault("SMTP_TLS",   "false")),
                    getEnvOrDefault("SMTP_USER",         ""),
                    getEnvOrDefault("SMTP_PASS",         ""),
                    getEnvOrDefault("EMAIL_FROM",        "reports@stock-performance.local"),
                    getEnvOrDefault("EMAIL_FROM_NAME",   "Stock Performance Bot"),
                    getEnvOrDefault("EMAIL_TO",          "analyst@example.com")
            );
        }

        private static String getEnvOrDefault(String key, String defaultValue) {
            String val = System.getenv(key);
            return (val != null && !val.isBlank()) ? val : defaultValue;
        }
    }
}



