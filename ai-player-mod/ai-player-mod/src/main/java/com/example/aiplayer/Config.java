package com.example.aiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** config/aiplayer.json */
public class Config {
    public String provider = "claude";          // claude | openai | gemini | kimi | grok
    public String botName = "AI_Player";
    public String language = "ja";              // language of the bot's chat messages
    public int thinkIntervalTicks = 60;         // minimum ticks between two LLM calls (20 ticks = 1 s)
    public int scanRadius = 16;                 // radius of the block scan given to the AI
    public boolean xray = false;                // false = AI only "sees" blocks that are exposed to air
    public Map<String, Provider> providers = new LinkedHashMap<>();

    public static class Provider {
        public String apiKey = "";
        public String model = "";
        public String baseUrl = "";

        public Provider() {}

        public Provider(String model, String baseUrl) {
            this.model = model;
            this.baseUrl = baseUrl;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> ENV = Map.of(
            "claude", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "gemini", "GEMINI_API_KEY",
            "kimi", "MOONSHOT_API_KEY",
            "grok", "XAI_API_KEY");

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiplayer.json");
    }

    public static Config load() {
        Config c = null;
        try {
            Path p = path();
            if (Files.exists(p)) c = GSON.fromJson(Files.readString(p), Config.class);
        } catch (Exception e) {
            AiPlayerMod.LOGGER.error("Failed to read aiplayer.json, using defaults", e);
        }
        if (c == null) c = new Config();
        c.fillDefaults();
        c.save();
        return c;
    }

    private void fillDefaults() {
        // Model names are only defaults - edit them in aiplayer.json whenever newer models come out.
        def("claude", "claude-sonnet-5-5", "https://api.anthropic.com/v1");
        def("openai", "gpt-5", "https://api.openai.com/v1");
        def("gemini", "gemini-2.5-pro", "https://generativelanguage.googleapis.com/v1beta");
        def("kimi", "kimi-k2-0905-preview", "https://api.moonshot.ai/v1");
        def("grok", "grok-4", "https://api.x.ai/v1");
        provider = provider.toLowerCase(Locale.ROOT);
    }

    private void def(String name, String model, String base) {
        Provider p = providers.computeIfAbsent(name, k -> new Provider(model, base));
        if (p.model == null || p.model.isBlank()) p.model = model;
        if (p.baseUrl == null || p.baseUrl.isBlank()) p.baseUrl = base;
        if (p.apiKey == null) p.apiKey = "";
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (Exception e) {
            AiPlayerMod.LOGGER.error("Failed to write aiplayer.json", e);
        }
    }

    /** API key from the config file, falling back to an environment variable. */
    public String apiKey(String name) {
        Provider p = providers.get(name);
        if (p != null && p.apiKey != null && !p.apiKey.isBlank()) return p.apiKey.trim();
        String env = ENV.get(name);
        String v = env == null ? null : System.getenv(env);
        return v == null ? "" : v.trim();
    }
}
