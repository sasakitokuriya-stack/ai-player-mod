package com.example.aiplayer;

import com.example.aiplayer.bot.AiBot;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class AiPlayerMod implements ModInitializer {
    public static final String ID = "aiplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    public static Config config;
    public static AiBot bot;

    @Override
    public void onInitialize() {
        config = Config.load();
        bot = new AiBot();

        ServerTickEvents.END_SERVER_TICK.register(server -> bot.tick(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> bot.despawn());

        // Talk to the AI by writing its name in chat, e.g. "AI_Player 家を建てて"
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            String name = config.botName.toLowerCase(Locale.ROOT);
            if (text.toLowerCase(Locale.ROOT).startsWith(name) || text.startsWith("@ai")) {
                bot.hear(sender.getName().getString(), text);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            dispatcher.register(CommandManager.literal("aiplayer")
                    .requires(s -> s.hasPermissionLevel(2))
                    .then(CommandManager.literal("spawn").executes(c -> {
                        ServerCommandSource s = c.getSource();
                        bot.spawn(s.getServer(), s.getWorld(), s.getPosition());
                        reply(s, "Spawned " + config.botName + " (" + config.provider + ")");
                        return 1;
                    }))
                    .then(CommandManager.literal("despawn").executes(c -> {
                        bot.despawn();
                        reply(c.getSource(), "Despawned.");
                        return 1;
                    }))
                    .then(CommandManager.literal("provider")
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                    .suggests((ctx, b) -> {
                                        for (String n : new String[]{"claude", "openai", "gemini", "kimi", "grok"}) b.suggest(n);
                                        return b.buildFuture();
                                    })
                                    .executes(c -> {
                                        String n = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
                                        if (!config.providers.containsKey(n)) {
                                            reply(c.getSource(), "Unknown provider: " + n);
                                            return 0;
                                        }
                                        config.provider = n;
                                        config.save();
                                        String key = config.apiKey(n).isBlank() ? " (API key is NOT set!)" : "";
                                        reply(c.getSource(), "Provider = " + n + " / " + config.providers.get(n).model + key);
                                        return 1;
                                    })))
                    .then(CommandManager.literal("say")
                            .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                    .executes(c -> {
                                        bot.hear(c.getSource().getName(), StringArgumentType.getString(c, "text"));
                                        return 1;
                                    })))
                    .then(CommandManager.literal("reload").executes(c -> {
                        config = Config.load();
                        reply(c.getSource(), "Config reloaded.");
                        return 1;
                    }))
                    .then(CommandManager.literal("status").executes(c -> {
                        reply(c.getSource(), bot.status());
                        return 1;
                    })));
        });
        LOGGER.info("AI Player loaded. Edit config/aiplayer.json and set an API key.");
    }

    private static void reply(ServerCommandSource s, String msg) {
        s.sendFeedback(() -> Text.literal(msg), false);
    }
}
