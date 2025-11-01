package com.pathvariable.smartgarden.summary;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client for Google Generative Language (Gemini) API.
 */
public class GeminiClient {

    private static final Gson gson = new Gson();

    private final Config config;

    public GeminiClient(Config config) {
        this.config = config;
    }


    public String generateSummary(String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + config.model() + ":generateContent?key=" + config.googleApiKey();

        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", prompt);
        JsonObject content = new JsonObject();
        content.add("parts", arrayOf(userPart));

        JsonObject req = new JsonObject();
        req.add("contents", arrayOf(content));
        if (config.systemInstruction() != null && !config.systemInstruction().isBlank()) {
            JsonObject sys = new JsonObject();
            sys.add("parts", arrayOf(textPart(config.systemInstruction())));
            req.add("system_instruction", sys);
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Gemini API error: HTTP " + resp.statusCode() + " - " + resp.body());
        }
        JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
        try {
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return "";
            JsonObject first = candidates.get(0).getAsJsonObject();
            JsonObject c = first.getAsJsonObject("content");
            JsonArray parts = c.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) return "";
            JsonObject p0 = parts.get(0).getAsJsonObject();
            return p0.has("text") ? p0.get("text").getAsString() : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response: " + e.getMessage() + " Body: " + resp.body());
        }
    }

    private static JsonArray arrayOf(JsonObject obj) {
        JsonArray arr = new JsonArray();
        arr.add(obj);
        return arr;
    }

    private static JsonObject textPart(String text) {
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        return p;
    }
}
