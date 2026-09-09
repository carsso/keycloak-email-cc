package sh.germain.keycloak.email.cc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.email.freemarker.FreeMarkerEmailTemplateProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

class PasswordResetCcEmailTemplateProviderFactoryTest {

    private final PasswordResetCcEmailTemplateProviderFactory factory =
            new PasswordResetCcEmailTemplateProviderFactory();

    @Test
    @DisplayName("takes over the built-in provider id so no provider selection is needed")
    void shadowsTheBuiltInProvider() {
        assertEquals(new FreeMarkerEmailTemplateProviderFactory().getId(), factory.getId());
        assertTrue(factory.order() > new FreeMarkerEmailTemplateProviderFactory().order(),
                "order must beat the built-in factory for Keycloak to pick this one");
    }

    @Test
    @DisplayName("falls back to the documented default attribute")
    void defaultAttribute() {
        factory.init(scope(null));

        assertEquals(PasswordResetCcEmailTemplateProviderFactory.DEFAULT_ATTRIBUTE,
                providerAttribute());
    }

    @Test
    @DisplayName("honours the SPI-configured attribute name")
    void configuredAttribute() {
        factory.init(scope("copyTo"));

        assertEquals("copyTo", providerAttribute());
    }

    @Test
    @DisplayName("advertises the option so it shows up in show-config")
    void exposesConfigMetadata() {
        List<ProviderConfigProperty> metadata = factory.getConfigMetadata();

        assertEquals(1, metadata.size());
        ProviderConfigProperty property = metadata.get(0);
        assertEquals(PasswordResetCcEmailTemplateProviderFactory.CONFIG_ATTRIBUTE, property.getName());
        assertEquals(PasswordResetCcEmailTemplateProviderFactory.DEFAULT_ATTRIBUTE,
                property.getDefaultValue());
    }

    @Test
    @DisplayName("creates the copying provider")
    void createsProvider() {
        factory.init(scope("copyTo"));
        EmailTemplateProvider provider = factory.create(session());

        assertInstanceOf(PasswordResetCcEmailTemplateProvider.class, provider);
    }

    private String providerAttribute() {
        PasswordResetCcEmailTemplateProvider provider =
                (PasswordResetCcEmailTemplateProvider) factory.create(session());
        return provider.attributeName();
    }

    private static KeycloakSession session() {
        return mock(KeycloakSession.class);
    }

    /** A Config.Scope where only the cc-attribute key is set. */
    private static Config.Scope scope(String attribute) {
        Config.Scope scope = mock(Config.Scope.class);
        when(scope.get(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(scope.get(eqKey(), any())).thenAnswer(
                i -> attribute == null ? i.getArgument(1) : attribute);
        return scope;
    }

    private static String eqKey() {
        return org.mockito.ArgumentMatchers.eq(
                PasswordResetCcEmailTemplateProviderFactory.CONFIG_ATTRIBUTE);
    }
}
