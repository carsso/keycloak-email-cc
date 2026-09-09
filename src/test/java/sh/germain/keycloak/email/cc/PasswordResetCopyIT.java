package sh.germain.keycloak.email.cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the built jar inside a real Keycloak and drives the actual "Forgot password?" pages,
 * asserting on the mail that genuinely reached the SMTP server.
 */
class PasswordResetCopyIT {

    private static final String USER = "alice";
    private static final String USER_EMAIL = "alice@example.com";
    private static final String COPY_EMAIL = "manager@example.com";
    private static final String SUBJECT = "Reset password";

    private static KeycloakAdmin keycloak;
    private static Mailbox mailbox;

    private String realm;

    @BeforeAll
    static void startKeycloak() {
        KeycloakTestEnvironment.start();
        keycloak = new KeycloakAdmin(KeycloakTestEnvironment.baseUrl());
        mailbox = new Mailbox(KeycloakTestEnvironment.mailpitUrl());
    }

    @BeforeEach
    void freshRealm() {
        realm = "it-" + UUID.randomUUID().toString().substring(0, 8);
        keycloak.createRealm(realm, List.of(KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE, "boss"));
        mailbox.clear();
    }

    @Test
    @DisplayName("the provider replaces the built-in one without any provider selection")
    void providerIsActive() {
        assertTrue(KeycloakTestEnvironment.keycloakLogs()
                        .contains("Password reset copies will be sent to the address(es) in user attribute '"
                                + KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE + "'"),
                "the SPI-configured attribute name should appear in the startup log");
    }

    @Test
    @DisplayName("password reset reaches both the user and the copy address, with the same link")
    void copiesPasswordReset() {
        createUser(Map.of(KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE, List.of(COPY_EMAIL)));

        keycloak.requestPasswordReset(realm, USER);
        List<Mailbox.Message> messages = mailbox.awaitMessages(2);

        assertEquals(Set.of(USER_EMAIL, COPY_EMAIL), recipients(messages));
        assertTrue(messages.stream().allMatch(m -> SUBJECT.equals(m.subject())), "same subject");
        assertNotNull(messages.get(0).actionTokenLink(), "the mail should carry a reset link");
        assertEquals(1, messages.stream().map(Mailbox.Message::actionTokenLink).distinct().count(),
                "the copy must carry the very same reset link");
        assertEquals(1, messages.stream().map(Mailbox.Message::text).distinct().count(),
                "the copy must be the same body");
    }

    @Test
    @DisplayName("several addresses in one attribute value each get a copy")
    void copiesToEveryAddress() {
        createUser(Map.of(KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE,
                List.of("one@example.com, two@example.com")));

        keycloak.requestPasswordReset(realm, USER);

        assertEquals(Set.of(USER_EMAIL, "one@example.com", "two@example.com"),
                recipients(mailbox.awaitMessages(3)));
    }

    @Test
    @DisplayName("a user without the attribute just gets their own mail")
    void noAttributeMeansNoCopy() {
        createUser(Map.of());

        keycloak.requestPasswordReset(realm, USER);

        assertEquals(Set.of(USER_EMAIL), recipients(mailbox.awaitMessages(1)));
    }

    @Test
    @DisplayName("a realm attribute redirects the lookup to another user attribute")
    void realmAttributeOverride() {
        createUser(Map.of(
                KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE, List.of(COPY_EMAIL),
                "boss", List.of("boss@example.com")));
        keycloak.setRealmAttribute(realm,
                PasswordResetCcEmailTemplateProvider.REALM_ATTRIBUTE, "boss");

        keycloak.requestPasswordReset(realm, USER);

        assertEquals(Set.of(USER_EMAIL, "boss@example.com"), recipients(mailbox.awaitMessages(2)));
    }

    @Test
    @DisplayName("only password resets are copied, not other Keycloak emails")
    void verifyEmailIsNotCopied() {
        createUser(Map.of(KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE, List.of(COPY_EMAIL)));

        keycloak.sendVerifyEmail(realm, USER);

        assertEquals(Set.of(USER_EMAIL), recipients(mailbox.awaitMessages(1)));
    }

    @Test
    @DisplayName("a copy address equal to the user's own email does not duplicate the mail")
    void selfAddressIsNotDuplicated() {
        createUser(Map.of(KeycloakTestEnvironment.CONFIGURED_ATTRIBUTE, List.of(USER_EMAIL)));

        keycloak.requestPasswordReset(realm, USER);
        List<Mailbox.Message> messages = mailbox.awaitMessages(1);

        assertEquals(1, messages.size(), "exactly one mail, not two copies of the same one");
        assertEquals(Set.of(USER_EMAIL), recipients(messages));
    }

    private void createUser(Map<String, List<String>> attributes) {
        keycloak.createUser(realm, USER, USER_EMAIL, attributes);
    }

    private static Set<String> recipients(List<Mailbox.Message> messages) {
        return messages.stream().map(Mailbox.Message::to).collect(Collectors.toSet());
    }
}
