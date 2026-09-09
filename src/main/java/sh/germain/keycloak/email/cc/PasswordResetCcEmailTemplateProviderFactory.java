package sh.germain.keycloak.email.cc;

import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.email.EmailTemplateProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Registers under the built-in "freemarker" id with a higher order, so it transparently replaces
 * the default email template provider without any provider-selection configuration.
 */
public class PasswordResetCcEmailTemplateProviderFactory implements EmailTemplateProviderFactory {

    private static final Logger log = Logger.getLogger(PasswordResetCcEmailTemplateProviderFactory.class);

    static final String CONFIG_ATTRIBUTE = "ccAttribute";
    static final String DEFAULT_ATTRIBUTE = "cc_email";

    private String attributeName = DEFAULT_ATTRIBUTE;

    @Override
    public EmailTemplateProvider create(KeycloakSession session) {
        return new PasswordResetCcEmailTemplateProvider(session, attributeName);
    }

    @Override
    public void init(Config.Scope config) {
        attributeName = config.get(CONFIG_ATTRIBUTE, DEFAULT_ATTRIBUTE);
        log.infof("Password reset copies will be sent to the address(es) in user attribute '%s'"
                + " (per-realm override: realm attribute '%s')", attributeName,
                PasswordResetCcEmailTemplateProvider.REALM_ATTRIBUTE);
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(CONFIG_ATTRIBUTE)
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(DEFAULT_ATTRIBUTE)
                .helpText("Name of the user attribute holding the address(es) that receive a copy"
                        + " of the password reset email. Several addresses may be separated by commas.")
                .add()
                .build();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "freemarker";
    }

    @Override
    public int order() {
        return 100;
    }
}
