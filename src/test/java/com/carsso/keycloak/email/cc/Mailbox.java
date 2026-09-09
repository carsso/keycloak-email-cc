package com.carsso.keycloak.email.cc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads what Keycloak actually put on the wire, through Mailpit's REST API. */
final class Mailbox {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ACTION_TOKEN = Pattern.compile("https?://\\S*action-token\\S*");

    private final String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    Mailbox(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    void clear() {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/messages")).DELETE().build());
    }

    /**
     * Waits for the mail count to settle: the copy leaves right after the original, so a bare
     * read could otherwise catch only the first one.
     */
    List<Message> awaitMessages(int expected) {
        List<Message> messages = List.of();
        for (int attempt = 0; attempt < 60; attempt++) {
            messages = messages();
            if (messages.size() >= expected) {
                break;
            }
            sleep(250);
        }
        // Give any unexpected extra mail a chance to show up, so over-sending fails the test.
        sleep(1500);
        return messages();
    }

    private List<Message> messages() {
        JsonNode response = json(send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/v1/messages?limit=50")).GET().build()));
        List<Message> messages = new ArrayList<>();
        for (JsonNode summary : response.get("messages")) {
            JsonNode full = json(send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/api/v1/message/" + summary.get("ID").asText())).GET().build()));
            String text = full.get("Text").asText();
            Matcher matcher = ACTION_TOKEN.matcher(text);
            messages.add(new Message(
                    summary.get("To").get(0).get("Address").asText(),
                    summary.get("Subject").asText(),
                    text,
                    matcher.find() ? matcher.group() : null));
        }
        return messages;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    record Message(String to, String subject, String text, String actionTokenLink) {
    }
}
