package com.example.aiplayer.llm;

import com.example.aiplayer.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * One tiny client for all five providers.
 *  - claude : Anthropic Messages API
 *  - gemini : Google generateContent API
 *  - openai / kimi / grok : OpenAI-compatible /chat/completions
 * Blocking - call it from a background thread.
 */
public final class Llm {
    public record Msg(String role, String text) {} // role: "user" or "assistant"

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private Llm() {}

    public static String chat(Config cfg, String system, List<Msg> msgs) throws Exception {
        String name = cfg.provider;
        Config.Provider p = cfg.providers.get(name);
        String key = cfg.apiKey(name);
        if (p == null) throw new IllegalStateException("Unknown provider: " + name);
        if (key.isBlank()) throw new IllegalStateException("No API key for '" + name + "' (config/aiplayer.json)");
        return switch (name) {
            case "claude" -> claude(p, key, system, msgs);
            case "gemini" -> gemini(p, key, system, msgs);
            default -> openAiCompatible(p, key, system, msgs);
        };
    }

    private static String claude(Config.Provider p, String key, String system, List<Msg> msgs) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", p.model);
        body.addProperty("max_tokens", 2048);
        body.addProperty("system", system);
        JsonArray arr = new JsonArray();
        for (Msg m : msgs) arr.add(obj("role", m.role(), "content", m.text()));
        body.add("messages", arr);
        JsonObject res = post(p.baseUrl + "/messages", body,
                "x-api-key", key, "anthropic-version", "2023-06-01");
        StringBuilder sb = new StringBuilder();
        for (var e : res.getAsJsonArray("content")) {
            JsonObject o = e.getAsJsonObject();
            if ("text".equals(o.get("type").getAsString())) sb.append(o.get("text").getAsString());
        }
        return sb.toString();
    }

    private static String openAiCompatible(Config.Provider p, String key, String system, List<Msg> msgs) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", p.model);
        JsonArray arr = new JsonArray();
        arr.add(obj("role", "system", "content", system));
        for (Msg m : msgs) arr.add(obj("role", m.role(), "content", m.text()));
        body.add("messages", arr);
        JsonObject res = post(p.baseUrl + "/chat/completions", body, "Authorization", "Bearer " + key);
        return res.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    private static String gemini(Config.Provider p, String key, String system, List<Msg> msgs) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        sysParts.add(obj("text", system));
        sys.add("parts", sysParts);
        body.add("systemInstruction", sys);
        JsonArray contents = new JsonArray();
        for (Msg m : msgs) {
            JsonObject c = new JsonObject();
            c.addProperty("role", m.role().equals("assistant") ? "model" : "user");
            JsonArray parts = new JsonArray();
            parts.add(obj("text", m.text()));
            c.add("parts", parts);
            contents.add(c);
        }
        body.add("contents", contents);
        JsonObject res = post(p.baseUrl + "/models/" + p.model + ":generateContent", body, "x-goog-api-key", key);
        StringBuilder sb = new StringBuilder();
        for (var e : res.getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
        }
        return sb.toString();
    }

    private static JsonObject post(String url, JsonObject body, String... headers) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) rb.header(headers[i], headers[i + 1]);
        HttpResponse<String> r = HTTP.send(rb.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            String b = r.body();
            throw new RuntimeException("HTTP " + r.statusCode() + ": " + (b.length() > 300 ? b.substring(0, 300) : b));
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static JsonObject obj(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }
}
