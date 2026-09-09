package sh.germain.keycloak.email.cc;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jboss.logging.Logger;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.freemarker.FreeMarkerEmailTemplateProvider;
import org.keycloak.models.KeycloakSession;

/**
 * Behaves exactly like the built-in FreeMarker provider, except that a password reset email is
 * also delivered to whatever addresses the user holds in a configurable attribute.
 */
public class PasswordResetCcEmailTemplateProvider extends FreeMarkerEmailTemplateProvider {

    private static final Logger log = Logger.getLogger(PasswordResetCcEmailTemplateProvider.class);

    /** Per-realm override of the attribute name, set through the admin REST API. */
    static final String REALM_ATTRIBUTE = "passwordResetCcAttribute";

    private final String defaultAttributeName;

    /** True only while a password reset email is being rendered and sent. */
    private boolean sendingPasswordReset;

    public PasswordResetCcEmailTemplateProvider(KeycloakSession session, String defaultAttributeName) {
        super(session);
        this.defaultAttributeName = defaultAttributeName;
    }

    @Override
    public void sendPasswordReset(String link, long expirationInMinutes) throws EmailException {
        sendingPasswordReset = true;
        try {
            super.sendPasswordReset(link, expirationInMinutes);
        } finally {
            sendingPasswordReset = false;
        }
    }

    /**
     * Single choke point through which every templated email leaves the provider. A non-null
     * address means the mail is already aimed at someone other than the user, so it is left alone.
     */
    @Override
    protected void send(Map<String, String> config, String subject, String textBody, String htmlBody, String address)
            throws EmailException {
        super.send(config, subject, textBody, htmlBody, address);

        if (!sendingPasswordReset || address != null) {
            return;
        }

        EmailSenderProvider sender = session.getProvider(EmailSenderProvider.class);
        for (String recipient : copyRecipients()) {
            try {
                sender.send(config, recipient, subject, textBody, htmlBody);
                log.debugf("Sent password reset copy for %s to %s", user.getUsername(), recipient);
            } catch (Exception e) {
                // The user already got their email; a failing copy must not break the reset flow.
                log.warnf(e, "Failed to send password reset copy for %s to %s", user.getUsername(), recipient);
            }
        }
    }

    List<String> copyRecipients() {
        if (user == null) {
            return List.of();
        }
        String attributeName = attributeName();
        String userEmail = user.getEmail();

        return user.getAttributeStream(attributeName)
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split("[,;\\s]+")))
                .map(String::trim)
                .filter(candidate -> candidate.indexOf('@') > 0)
                .filter(candidate -> !candidate.equalsIgnoreCase(userEmail))
                .distinct()
                .toList();
    }

    String attributeName() {
        String override = realm == null ? null : realm.getAttribute(REALM_ATTRIBUTE);
        return override == null || override.isBlank() ? defaultAttributeName : override.trim();
    }
}
