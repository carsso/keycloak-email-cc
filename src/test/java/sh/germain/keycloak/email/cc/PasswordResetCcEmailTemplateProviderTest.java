package sh.germain.keycloak.email.cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

class PasswordResetCcEmailTemplateProviderTest {

    private static final String ATTRIBUTE = "cc_email";
    private static final String USER_EMAIL = "alice@example.com";
    private static final String SUBJECT = "Reset password";
    private static final String TEXT = "text body";
    private static final String HTML = "<html>body</html>";

    private KeycloakSession session;
    private EmailSenderProvider sender;
    private RealmModel realm;
    private UserModel user;
    private Map<String, String> smtpConfig;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        sender = mock(EmailSenderProvider.class);
        realm = mock(RealmModel.class);
        user = mock(UserModel.class);
        smtpConfig = Map.of("host", "smtp.example.com");

        when(session.getProvider(EmailSenderProvider.class)).thenReturn(sender);
        when(realm.getSmtpConfig()).thenReturn(smtpConfig);
        when(user.getEmail()).thenReturn(USER_EMAIL);
        when(user.getUsername()).thenReturn("alice");
        // Any attribute is empty unless a test says otherwise.
        when(user.getAttributeStream(anyString())).thenAnswer(i -> Stream.empty());
    }

    /** Stubs the user attribute the provider is configured to read. */
    private void attribute(String name, String... values) {
        when(user.getAttributeStream(name)).thenAnswer(i -> Stream.of(values));
    }

    private TestableProvider provider() {
        TestableProvider p = new TestableProvider(session, ATTRIBUTE);
        p.setRealm(realm);
        p.setUser(user);
        return p;
    }

    @Nested
    @DisplayName("password reset")
    class PasswordReset {

        @Test
        @DisplayName("the user gets their mail and the copy address gets an identical one")
        void copiesToAttributeAddress() throws Exception {
            attribute(ATTRIBUTE, "manager@example.com");

            provider().sendPasswordReset("http://link", 30);

            verify(sender).send(smtpConfig, user, SUBJECT, TEXT, HTML);
            verify(sender).send(smtpConfig, "manager@example.com", SUBJECT, TEXT, HTML);
        }

        @Test
        @DisplayName("no attribute means no copy, and the user's mail still goes out")
        void noAttributeNoCopy() throws Exception {
            provider().sendPasswordReset("http://link", 30);

            verify(sender).send(smtpConfig, user, SUBJECT, TEXT, HTML);
            verify(sender, never()).send(any(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("a multivalued attribute copies to every address")
        void multivalued() throws Exception {
            attribute(ATTRIBUTE, "one@example.com", "two@example.com");

            provider().sendPasswordReset("http://link", 30);

            verify(sender).send(smtpConfig, "one@example.com", SUBJECT, TEXT, HTML);
            verify(sender).send(smtpConfig, "two@example.com", SUBJECT, TEXT, HTML);
        }

        @Test
        @DisplayName("a failing copy is swallowed so the reset flow still succeeds")
        void copyFailureDoesNotPropagate() throws Exception {
            attribute(ATTRIBUTE, "boom@example.com", "ok@example.com");
            doThrow(new EmailException("smtp down"))
                    .when(sender).send(any(), eq("boom@example.com"), any(), any(), any());

            provider().sendPasswordReset("http://link", 30);

            verify(sender).send(smtpConfig, user, SUBJECT, TEXT, HTML);
            // The failure must not stop the remaining copies either.
            verify(sender).send(smtpConfig, "ok@example.com", SUBJECT, TEXT, HTML);
        }

        @Test
        @DisplayName("a failing mail to the user itself still propagates")
        void primaryFailurePropagates() throws Exception {
            attribute(ATTRIBUTE, "manager@example.com");
            doThrow(new EmailException("smtp down"))
                    .when(sender).send(any(), eq(user), any(), any(), any());

            TestableProvider p = provider();
            org.junit.jupiter.api.Assertions.assertThrows(EmailException.class,
                    () -> p.sendPasswordReset("http://link", 30));
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("other email types are never copied")
        void verifyEmailIsNotCopied() throws Exception {
            attribute(ATTRIBUTE, "manager@example.com");

            provider().sendVerifyEmail("http://link", 30);

            verify(sender).send(smtpConfig, user, SUBJECT, TEXT, HTML);
            verify(sender, never()).send(any(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("the copy flag is cleared once the reset mail has been sent")
        void flagIsClearedAfterReset() throws Exception {
            attribute(ATTRIBUTE, "manager@example.com");
            TestableProvider p = provider();

            p.sendPasswordReset("http://link", 30);
            p.sendVerifyEmail("http://link", 30);

            // Exactly one copy overall: the one from the password reset.
            verify(sender).send(smtpConfig, "manager@example.com", SUBJECT, TEXT, HTML);
        }

        @Test
        @DisplayName("a mail already aimed at a specific address is left alone")
        void explicitAddressIsNotCopied() throws Exception {
            attribute(ATTRIBUTE, "manager@example.com");
            TestableProvider p = provider();
            p.forcedAddress = "someone@example.com";

            p.sendPasswordReset("http://link", 30);

            verify(sender).send(smtpConfig, "someone@example.com", SUBJECT, TEXT, HTML);
            verify(sender, never()).send(any(), eq("manager@example.com"), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("recipient parsing")
    class RecipientParsing {

        @ParameterizedTest(name = "\"{0}\" splits into two addresses")
        @ValueSource(strings = {
                "one@example.com,two@example.com",
                "one@example.com, two@example.com",
                "one@example.com;two@example.com",
                "one@example.com two@example.com",
                "  one@example.com ,; two@example.com  ",
        })
        void separators(String raw) {
            attribute(ATTRIBUTE, raw);

            assertIterableEquals(List.of("one@example.com", "two@example.com"),
                    provider().copyRecipients());
        }

        @Test
        @DisplayName("the user's own address is dropped, whatever its case")
        void selfIsExcluded() {
            attribute(ATTRIBUTE, "ALICE@EXAMPLE.COM, manager@example.com");

            assertIterableEquals(List.of("manager@example.com"), provider().copyRecipients());
        }

        @Test
        @DisplayName("duplicates are collapsed")
        void duplicatesCollapsed() {
            attribute(ATTRIBUTE, "manager@example.com", "manager@example.com");

            assertIterableEquals(List.of("manager@example.com"), provider().copyRecipients());
        }

        @ParameterizedTest(name = "\"{0}\" is not an address")
        @ValueSource(strings = {"", "   ", "not-an-address", "@example.com", ",;"})
        void rejectsGarbage(String raw) {
            attribute(ATTRIBUTE, raw);

            assertTrue(provider().copyRecipients().isEmpty());
        }

        @Test
        @DisplayName("a null value in the attribute is skipped")
        void nullValueSkipped() {
            when(user.getAttributeStream(ATTRIBUTE))
                    .thenAnswer(i -> Stream.of(null, "manager@example.com"));

            assertIterableEquals(List.of("manager@example.com"), provider().copyRecipients());
        }

        @Test
        @DisplayName("no user means no recipients rather than a crash")
        void noUser() {
            TestableProvider p = new TestableProvider(session, ATTRIBUTE);
            p.setRealm(realm);

            assertTrue(p.copyRecipients().isEmpty());
        }
    }

    @Nested
    @DisplayName("attribute name resolution")
    class AttributeName {

        @Test
        @DisplayName("falls back to the globally configured name")
        void usesGlobalDefault() {
            assertEquals(ATTRIBUTE, provider().attributeName());
        }

        @Test
        @DisplayName("a realm attribute overrides the global name")
        void realmOverride() {
            when(realm.getAttribute(PasswordResetCcEmailTemplateProvider.REALM_ATTRIBUTE))
                    .thenReturn("boss");
            attribute("boss", "boss@example.com");

            assertEquals("boss", provider().attributeName());
            assertIterableEquals(List.of("boss@example.com"), provider().copyRecipients());
        }

        @Test
        @DisplayName("the override is trimmed")
        void overrideIsTrimmed() {
            when(realm.getAttribute(PasswordResetCcEmailTemplateProvider.REALM_ATTRIBUTE))
                    .thenReturn("  boss  ");

            assertEquals("boss", provider().attributeName());
        }

        @ParameterizedTest(name = "a [{0}] override falls back to the global name")
        @ValueSource(strings = {"", "   "})
        void blankOverrideFallsBack(String override) {
            when(realm.getAttribute(PasswordResetCcEmailTemplateProvider.REALM_ATTRIBUTE))
                    .thenReturn(override);

            assertEquals(ATTRIBUTE, provider().attributeName());
        }

        @Test
        @DisplayName("no realm means the global name")
        void noRealm() {
            assertEquals(ATTRIBUTE, new TestableProvider(session, ATTRIBUTE).attributeName());
        }
    }

    /**
     * Short-circuits FreeMarker so the send chain can be exercised without a theme: the two
     * overridden methods are exactly the ones the real provider uses to render a template.
     */
    private static class TestableProvider extends PasswordResetCcEmailTemplateProvider {

        /** When set, templated mail is aimed at this address instead of the user. */
        String forcedAddress;

        TestableProvider(KeycloakSession session, String attributeName) {
            super(session, attributeName);
        }

        @Override
        protected void addLinkInfoIntoAttributes(String link, long expirationInMinutes,
                Map<String, Object> attributes) {
            attributes.put("link", link);
        }

        @Override
        public void send(String subjectFormatKey, String bodyTemplate,
                Map<String, Object> bodyAttributes) throws EmailException {
            send(realm.getSmtpConfig(), SUBJECT, TEXT, HTML, forcedAddress);
        }

        @Override
        public void send(String subjectFormatKey, List<Object> subjectAttributes,
                String bodyTemplate, Map<String, Object> bodyAttributes) throws EmailException {
            send(subjectFormatKey, bodyTemplate, new HashMap<>(bodyAttributes));
        }
    }
}
