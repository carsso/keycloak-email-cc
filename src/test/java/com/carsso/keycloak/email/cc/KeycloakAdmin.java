package com.carsso.keycloak.email.cc;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal Keycloak admin REST client, plus the browser-side forgot-password flow. */
final class KeycloakAdmin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESET_LINK = Pattern.compile("href=\"([^\"]*reset-credentials[^\"]*)\"");
    private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]*)\"");

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final String token;

    KeycloakAdmin(String baseUrl) {
        this.baseUrl = baseUrl;
        this.token = fetchToken();
    }

    private String fetchToken() {
        String form = "client_id=admin-cli&grant_type=password"
                + "&username=" + KeycloakTestEnvironment.ADMIN
                + "&password=" + KeycloakTestEnvironment.ADMIN;
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
        expect(response, 200);
        return json(response.body()).get("access_token").asText();
    }

    /**
     * Creates a self-contained realm: SMTP pointing at Mailpit, password reset enabled, a public
     * client to start the login flow from, and the given user profile attributes declared so the
     * admin API is allowed to set them.
     */
    void createRealm(String realm, List<String> declaredAttributes) {
        post("/admin/realms", Map.of(
                "realm", realm,
                "enabled", true,
                "resetPasswordAllowed", true,
                "smtpServer", Map.of(
                        "host", "mailpit",
                        "port", "1025",
                        "from", "keycloak@example.com")), 201);

        post("/admin/realms/" + realm + "/clients", Map.of(
                "clientId", "testclient",
                "publicClient", true,
                "standardFlowEnabled", true,
                "redirectUris", List.of("http://localhost/*")), 201);

        JsonNode profile = get("/admin/realms/" + realm + "/users/profile");
        var attributes = (com.fasterxml.jackson.databind.node.ArrayNode) profile.get("attributes");
        for (String name : declaredAttributes) {
            attributes.addObject()
                    .put("name", name)
                    .put("displayName", name)
                    .put("multivalued", true)
                    .putPOJO("permissions", Map.of("view", List.of("admin"), "edit", List.of("admin")));
        }
        put("/admin/realms/" + realm + "/users/profile", profile, 200);
    }

    void setRealmAttribute(String realm, String name, String value) {
        JsonNode representation = get("/admin/realms/" + realm);
        ((com.fasterxml.jackson.databind.node.ObjectNode) representation)
                .withObject("/attributes").put(name, value);
        put("/admin/realms/" + realm, representation, 204);
    }

    void createUser(String realm, String username, String email, Map<String, List<String>> attributes) {
        post("/admin/realms/" + realm + "/users", Map.of(
                "username", username,
                "email", email,
                "enabled", true,
                "emailVerified", false,
                "attributes", attributes), 201);
    }

    void sendVerifyEmail(String realm, String username) {
        String id = get("/admin/realms/" + realm + "/users?username=" + username).get(0).get("id").asText();
        HttpResponse<String> response = send(authenticated(
                "/admin/realms/" + realm + "/users/" + id + "/send-verify-email")
                .PUT(HttpRequest.BodyPublishers.noBody()).build());
        expect(response, 204);
    }

    /**
     * Drives the real "Forgot password?" pages, which is what triggers sendPasswordReset.
     *
     * <p>Cookies are tracked by hand rather than through a {@link java.net.CookieManager}:
     * Keycloak marks KC_RESTART as {@code Secure;SameSite=None}, and the JDK cookie manager
     * refuses to send a secure cookie back over the plain-HTTP test listener.
     */
    void requestPasswordReset(String realm, String username) {
        // A fresh jar per attempt, so realms cannot leak login state into each other.
        Map<String, String> cookies = new LinkedHashMap<>();
        String authUrl = baseUrl + "/realms/" + realm + "/protocol/openid-connect/auth"
                + "?client_id=testclient&response_type=code&scope=openid"
                + "&redirect_uri=" + URLEncoder.encode("http://localhost/", StandardCharsets.UTF_8);
        String loginPage = body(browse(cookies, HttpRequest.newBuilder()
                .uri(URI.create(authUrl)).GET()));

        String resetPage = body(browse(cookies, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + extract(RESET_LINK, loginPage, "forgot-password link")))
                .GET()));

        expect(browse(cookies, HttpRequest.newBuilder()
                .uri(URI.create(extract(FORM_ACTION, resetPage, "reset form action")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)))), 200);
    }

    /** Sends the request with the jar's cookies, then folds any Set-Cookie back into the jar. */
    private HttpResponse<String> browse(Map<String, String> cookies, HttpRequest.Builder builder) {
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response = send(builder.build());
        for (String header : response.headers().allValues("set-cookie")) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return response;
    }

    private static String extract(Pattern pattern, String html, String what) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not find the " + what + " in the Keycloak page");
        }
        return matcher.group(1).replace("&amp;", "&");
    }

    private JsonNode get(String path) {
        HttpResponse<String> response = send(authenticated(path).GET().build());
        expect(response, 200);
        return json(response.body());
    }

    private void post(String path, Object payload, int expected) {
        expect(send(authenticated(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(payload)))
                .build()), expected);
    }

    private void put(String path, Object payload, int expected) {
        expect(send(authenticated(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(write(payload)))
                .build()), expected);
    }

    private HttpRequest.Builder authenticated(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String body(HttpResponse<String> response) {
        expect(response, 200);
        return response.body();
    }

    private static void expect(HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new IllegalStateException("Expected HTTP " + status + " from " + response.uri()
                    + " but got " + response.statusCode() + ": " + response.body());
        }
    }

    private static String write(Object payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Not valid JSON: " + raw, e);
        }
    }
}
