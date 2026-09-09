package sh.germain.keycloak.email.cc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A Keycloak with the freshly built provider jar dropped in, wired to a Mailpit that captures
 * everything it sends. Started once for the whole integration test run.
 */
final class KeycloakTestEnvironment {

    static final String ADMIN = "admin";
    static final String CONFIGURED_ATTRIBUTE = "copyTo";

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.7.3";

    private static GenericContainer<?> keycloak;
    private static GenericContainer<?> mailpit;

    private KeycloakTestEnvironment() {
    }

    static synchronized void start() {
        if (keycloak != null) {
            return;
        }
        Network network = Network.newNetwork();

        mailpit = new GenericContainer<>("axllent/mailpit:latest")
                .withNetwork(network)
                .withNetworkAliases("mailpit")
                .withExposedPorts(8025)
                .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025).forStatusCode(200));
        mailpit.start();

        keycloak = new GenericContainer<>(image())
                .withNetwork(network)
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN)
                // Both SPI option spellings, same value: the single-dash form is what Keycloak
                // 26.0/26.1 understand, the double-underscore form what 26.2+ expect. Whichever
                // the runtime picks, the configured attribute is the same.
                .withEnv("KC_SPI_EMAIL_TEMPLATE_FREEMARKER_CC_ATTRIBUTE", CONFIGURED_ATTRIBUTE)
                .withEnv("KC_SPI_EMAIL_TEMPLATE__FREEMARKER__CC_ATTRIBUTE", CONFIGURED_ATTRIBUTE)
                .withCopyFileToContainer(MountableFile.forHostPath(providerJar()),
                        "/opt/keycloak/providers/keycloak-password-reset-cc.jar")
                .withCommand("start-dev")
                .waitingFor(Wait.forHttp("/realms/master").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(4));
        keycloak.start();
    }

    static String baseUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    static String mailpitUrl() {
        return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
    }

    /** Image under test; the CI matrix overrides this to check several Keycloak releases. */
    static String image() {
        return System.getProperty("keycloak.test.image", DEFAULT_IMAGE);
    }

    static String keycloakLogs() {
        return keycloak.getLogs();
    }

    private static Path providerJar() {
        String configured = System.getProperty("provider.jar");
        if (configured == null) {
            throw new IllegalStateException(
                    "provider.jar system property is not set; run the tests through 'mvn verify'");
        }
        Path jar = Path.of(configured);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Provider jar not found at " + jar
                    + "; run 'mvn package' before the integration tests");
        }
        return jar;
    }
}
