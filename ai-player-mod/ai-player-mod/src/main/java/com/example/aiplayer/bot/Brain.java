package com.example.aiplayer.bot;

import com.example.aiplayer.AiPlayerMod;
import com.example.aiplayer.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.StringReader;
import java.util.*;

/** Builds what the AI sees (observation) and turns what it answers (JSON) into tasks. */
public final class Brain {
    private Brain() {}

    public record Plan(String thought, String say, List<Task> tasks, String error) {}

    private static final Set<String> BORING = Set.of("stone", "dirt", "grass_block", "deepslate", "netherrack",
            "short_grass", "tall_grass", "grass", "fern", "bedrock", "sand", "gravel", "andesite", "diorite", "granite");

    // ------------------------------------------------------------------ prompt

    public static String systemPrompt(Config cfg) {
        return """
                You are "%s", an autonomous player in a Minecraft Java Edition 1.20.1 survival world. You control a real player body.
                Goal: survive and make progress like a good human player (wood -> crafting table -> wooden pickaxe -> stone tools -> coal -> iron -> furnace -> iron gear -> diamonds ...), build a small safe base, keep food and health up.
                If a human tells you something in chat, that instruction has priority over your own plans. Follow it as well as you can. When there is no instruction, decide yourself.
                Talk in chat in this language: %s. Keep chat short.

                Each turn you get an OBSERVATION. Reply with ONLY one JSON object (no markdown, no extra text):
                {"thought":"short reasoning","say":"optional chat message or empty","actions":[ ... 1 to 6 actions ... ]}

                Available actions (ids are Minecraft ids without "minecraft:", coordinates are integers):
                {"do":"mine","block":"oak_log","count":5}          nearest matching blocks within the scan radius: walks there, breaks them, picks up the drops. "log", "ore", "planks" also work as groups.
                {"do":"move","x":0,"y":64,"z":0}                   walk next to a position
                {"do":"follow","player":"Name"}                    walk to a player
                {"do":"explore","direction":"north"}               walk ~25 blocks (north/south/east/west/any) to find new things
                {"do":"craft","item":"wooden_pickaxe","count":1}   uses real recipes from your inventory. 3x3 recipes need a crafting table within 5 blocks.
                {"do":"smelt","item":"iron_ingot","count":3}       give the OUTPUT item. Needs a furnace within 5 blocks, the raw item and fuel in the inventory.
                {"do":"place","block":"crafting_table","x":1,"y":64,"z":2}   place a block you carry (the spot must be empty and next to a solid block)
                {"do":"attack","target":"zombie","count":1}        a mob id, or "hostile" / "animal". Auto-picks up drops.
                {"do":"equip","item":"iron_sword"}
                {"do":"eat"}
                {"do":"give","item":"cobblestone","count":8,"player":"Name"}
                {"do":"wait","ticks":40}

                Rules:
                - You only know blocks listed in the observation (visible ones within the radius). If the thing you need is not listed, explore.
                - Check your inventory before planning. Crafting needs the ingredients (4 planks -> 1 crafting_table, 2 planks -> 4 stick, wooden_pickaxe = 3 planks + 2 sticks).
                - Stone and ores need the right pickaxe tier, otherwise nothing drops. Cobblestone comes from mining "stone".
                - Read the "Events" list: FAILED / PARTIAL means the plan did not work, so change it instead of repeating it.
                - At night (time of day 13000-23000) or when hostile mobs are close and your health is low, fight carefully or build a shelter; never dig straight down; stay away from lava.
                - Prefer a few well-chosen actions per turn. Do not output anything except the JSON object.
                """.formatted(cfg.botName, cfg.language);
    }

    // ------------------------------------------------------------------ observation

    public static String observe(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        ServerWorld w = p.getServerWorld();
        BlockPos bp = p.getBlockPos();
        StringBuilder sb = new StringBuilder("OBSERVATION\n");

        sb.append("Position: ").append(bp.getX()).append(' ').append(bp.getY()).append(' ').append(bp.getZ())
                .append(" | dimension ").append(w.getRegistryKey().getValue().getPath())
                .append(" | biome ").append(w.getBiome(bp).getKey().map(k -> k.getValue().getPath()).orElse("?"))
                .append(" | time of day ").append(w.getTimeOfDay() % 24000)
                .append(w.isRaining() ? " | raining" : "").append('\n');
        sb.append("Health ").append(Math.round(p.getHealth())).append("/20 | Food ")
                .append(p.getHungerManager().getFoodLevel()).append("/20 | Armor ").append(p.getArmor()).append('\n');
        ItemStack hand = p.getMainHandStack();
        sb.append("Holding: ").append(hand.isEmpty() ? "nothing" : idOf(hand)).append('\n');
        sb.append("Inventory: ").append(inventory(p)).append('\n');

        // entities
        Vec3d pos = p.getPos();
        List<LivingEntity> ents = w.getEntitiesByClass(LivingEntity.class, new Box(pos, pos).expand(20), e -> e != p && e.isAlive());
        ents.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        sb.append("Nearby entities: ");
        if (ents.isEmpty()) sb.append("none");
        int n = 0;
        for (LivingEntity e : ents) {
            if (n++ >= 12) break;
            String name = e instanceof ServerPlayerEntity sp ? "PLAYER " + sp.getName().getString()
                    : Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            sb.append(name).append(" ").append(Math.round(e.distanceTo(p))).append("m at ")
                    .append(e.getBlockX()).append(',').append(e.getBlockY()).append(',').append(e.getBlockZ()).append("; ");
        }
        sb.append('\n');

        sb.append("Visible blocks within ").append(AiPlayerMod.config.scanRadius).append(" (type xCount nearest(x,y,z) distance): ")
                .append(scan(w, bp)).append('\n');

        BlockState under = w.getBlockState(bp.down());
        sb.append("Standing on: ").append(Registries.BLOCK.getId(under.getBlock()).getPath()).append('\n');
        Task t = bot.currentTask();
        sb.append("Current task: ").append(t == null ? "none" : t.describe()).append('\n');

        sb.append("\nEvents since your last turn:\n");
        if (bot.events.isEmpty()) sb.append("- (nothing)\n");
        for (String e : bot.events) sb.append("- ").append(e).append('\n');
        bot.events.clear();
        if (!bot.heard.isEmpty()) {
            sb.append("\nHumans said (follow this!):\n");
            for (String h : bot.heard) sb.append("- ").append(h).append('\n');
            bot.heard.clear();
        }
        sb.append("\nPlan your next actions. JSON only.");
        return sb.toString();
    }

    private static String idOf(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).getPath();
    }

    private static String inventory(ServerPlayerEntity p) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack s : p.getInventory().main) if (!s.isEmpty()) m.merge(idOf(s), s.getCount(), Integer::sum);
        for (ItemStack s : p.getInventory().armor) if (!s.isEmpty()) m.merge(idOf(s) + "(worn)", 1, Integer::sum);
        if (!p.getOffHandStack().isEmpty()) m.merge(idOf(p.getOffHandStack()) + "(offhand)", p.getOffHandStack().getCount(), Integer::sum);
        if (m.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append(" x").append(v).append(", "));
        return sb.toString();
    }

    private record Seen(int[] count, BlockPos[] nearest, double[] dist) {}

    private static String scan(ServerWorld w, BlockPos c) {
        int r = AiPlayerMod.config.scanRadius;
        boolean xray = AiPlayerMod.config.xray;
        Map<String, Integer> counts = new HashMap<>();
        Map<String, BlockPos> near = new HashMap<>();
        Map<String, Double> dist = new HashMap<>();
        Set<String> boringSeen = new TreeSet<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (!w.isChunkLoaded((c.getX() + dx) >> 4, (c.getZ() + dz) >> 4)) continue;
                for (int dy = -r; dy <= r; dy++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState s = w.getBlockState(m);
                    if (s.isAir()) continue;
                    String id = Registries.BLOCK.getId(s.getBlock()).getPath();
                    if (BORING.contains(id)) { boringSeen.add(id); continue; }
                    if (!xray && !exposed(w, m)) continue;
                    counts.merge(id, 1, Integer::sum);
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d < dist.getOrDefault(id, 1e9)) { dist.put(id, d); near.put(id, m.toImmutable()); }
                }
            }
        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort(Comparator.comparingDouble(dist::get));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : keys) {
            if (n++ >= 30) break;
            BlockPos b = near.get(k);
            sb.append(k).append(" x").append(counts.get(k)).append(" (").append(b.getX()).append(',').append(b.getY())
                    .append(',').append(b.getZ()).append(") ").append(Math.round(dist.get(k))).append("m; ");
        }
        if (keys.isEmpty()) sb.append("nothing notable; ");
        if (!boringSeen.isEmpty()) sb.append("[also plenty of: ").append(String.join(", ", boringSeen)).append("]");
        return sb.toString();
    }

    private static boolean exposed(ServerWorld w, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos q = p.offset(d);
            BlockState s = w.getBlockState(q);
            if (s.isAir() || s.getCollisionShape(w, q).isEmpty()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ parsing

    public static Plan parse(AiBot bot, String reply) {
        String thought = "", say = "", err = "";
        List<Task> tasks = new ArrayList<>();
        try {
            int a = reply.indexOf('{'), b = reply.lastIndexOf('}');
            if (a < 0 || b <= a) return new Plan("", "", tasks, "Your last reply was not a JSON object. Reply with JSON only.");
            JsonReader jr = new JsonReader(new StringReader(reply.substring(a, b + 1)));
            jr.setLenient(true);
            JsonObject root = JsonParser.parseReader(jr).getAsJsonObject();
            thought = str(root, "thought");
            say = str(root, "say");
            JsonElement acts = root.get("actions");
            if (acts != null && acts.isJsonArray()) {
                JsonArray arr = acts.getAsJsonArray();
                for (int i = 0; i < arr.size() && i < 8; i++) {
                    try {
                        add(bot, tasks, arr.get(i).getAsJsonObject());
                    } catch (Exception e) {
                        err += "Action #" + (i + 1) + " was invalid (" + e.getMessage() + "). ";
                    }
                }
            }
        } catch (Exception e) {
            err = "Could not parse your reply as JSON: " + e.getMessage();
        }
        return new Plan(thought, say, tasks, err);
    }

    private static void add(AiBot bot, List<Task> out, JsonObject o) {
        String act = str(o, "do").toLowerCase(Locale.ROOT);
        switch (act) {
            case "mine" -> out.add(new MineTask(req(o, "block"), num(o, "count", 1)));
            case "move" -> out.add(new MoveTask(new BlockPos(num(o, "x", 0), num(o, "y", 64), num(o, "z", 0)), 1.5, "target"));
            case "follow" -> {
                String name = req(o, "player");
                out.add(new Tasks.Instant("follow " + name, b -> {
                    ServerPlayerEntity t = b.player.getServer().getPlayerManager().getPlayer(name);
                    if (t == null) { b.note("FAILED follow: player '" + name + "' is not online"); return; }
                    b.insertFront(new MoveTask(t.getBlockPos(), 3.0, name));
                }));
            }
            case "explore" -> out.add(new Tasks.Explore(str(o, "direction")));
            case "craft" -> out.add(new CraftTask(req(o, "item"), num(o, "count", 1)));
            case "smelt" -> out.add(new SmeltTask(req(o, "item"), num(o, "count", 1)));
            case "place" -> out.add(new Tasks.Place(req(o, "block"), new BlockPos(num(o, "x", 0), num(o, "y", 0), num(o, "z", 0))));
            case "attack" -> out.add(new CombatTask(req(o, "target"), num(o, "count", 1)));
            case "equip" -> {
                String id = req(o, "item");
                out.add(new Tasks.Instant("equip " + id, b -> {
                    if (!b.equipItem(Tasks.item(id))) b.note("FAILED equip: you have no " + id);
                }));
            }
            case "eat" -> out.add(new Tasks.Instant("eat", b -> {
                if (!b.eat()) b.note("FAILED eat: no safe food in the inventory");
            }));
            case "give" -> {
                String id = req(o, "item"), name = req(o, "player");
                int cnt = num(o, "count", 1);
                ServerPlayerEntity t = bot.player.getServer().getPlayerManager().getPlayer(name);
                if (t == null) throw new IllegalArgumentException("player '" + name + "' is not online");
                out.add(new MoveTask(t.getBlockPos(), 2.5, name));
                out.add(new Tasks.Instant("give " + id + " x" + cnt + " to " + name, b -> {
                    var inv = b.player.getInventory();
                    int left = cnt;
                    for (int i = 0; i < inv.main.size() && left > 0; i++) {
                        ItemStack s = inv.getStack(i);
                        if (s.isOf(Tasks.item(id))) {
                            ItemStack drop = s.split(Math.min(left, s.getCount()));
                            left -= drop.getCount();
                            b.player.dropItem(drop, false);
                        }
                    }
                    b.note(left == 0 ? "DONE give " + id + " x" + cnt : "PARTIAL give: you were missing " + left + " " + id);
                }));
            }
            case "wait" -> out.add(new Tasks.Wait(num(o, "ticks", 40)));
            default -> throw new IllegalArgumentException("unknown action '" + act + "'");
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String req(JsonObject o, String k) {
        String s = str(o, k);
        if (s.isBlank()) throw new IllegalArgumentException("missing '" + k + "'");
        return s;
    }

    private static int num(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return def;
        return (int) Math.round(e.getAsDouble());
    }
}
