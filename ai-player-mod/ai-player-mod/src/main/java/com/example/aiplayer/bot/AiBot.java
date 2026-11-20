package com.example.aiplayer.bot;

import com.example.aiplayer.AiPlayerMod;
import com.example.aiplayer.Config;
import com.example.aiplayer.llm.Llm;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The autonomous player: a fake ServerPlayerEntity + a task queue + an LLM "brain" that fills the queue. */
public class AiBot {
    public ServerPlayerEntity player;
    private MinecraftServer server;

    private final Deque<Task> queue = new ArrayDeque<>();
    private final List<Llm.Msg> history = new ArrayList<>();
    final List<String> events = new ArrayList<>();   // results of finished tasks, shown to the AI next turn
    final List<String> heard = new ArrayList<>();    // chat from humans, shown to the AI next turn

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aiplayer-llm");
        t.setDaemon(true);
        return t;
    });
    private boolean thinking = false;
    private boolean urgent = false;
    private int generation = 0;
    private int idleTicks = 0;
    private int backoff = 0;
    private int deadTicks = 0;
    private int tickCount = 0;
    private String lastError = "";
    private String lastThought = "";

    // ---------------------------------------------------------------- lifecycle

    public boolean isActive() {
        return player != null;
    }

    public void spawn(MinecraftServer server, ServerWorld world, Vec3d pos) {
        if (player != null) despawn();
        this.server = server;
        Config cfg = AiPlayerMod.config;
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("AiPlayer:" + cfg.botName).getBytes(StandardCharsets.UTF_8)), cfg.botName);
        ServerPlayerEntity p = new ServerPlayerEntity(server, world, profile);
        server.getPlayerManager().onPlayerConnect(new FakeConnection(), p);
        p.teleport(world, pos.x, pos.y, pos.z, 0f, 0f);
        p.setHealth(p.getMaxHealth());
        this.player = p;
        queue.clear();
        history.clear();
        events.clear();
        heard.clear();
        thinking = false;
        generation++;
        idleTicks = 1000; // think right away
        events.add("You just spawned into the world.");
    }

    public void despawn() {
        generation++;
        queue.clear();
        thinking = false;
        if (player != null && server != null) {
            try {
                server.getPlayerManager().remove(player);
            } catch (Exception e) {
                AiPlayerMod.LOGGER.warn("despawn failed", e);
            }
        }
        player = null;
    }

    public String status() {
        if (player == null) return "Bot is not spawned. Use /aiplayer spawn";
        Task t = queue.peek();
        return AiPlayerMod.config.provider + " | " + (thinking ? "thinking..." : t == null ? "idle" : t.describe())
                + " | queue=" + queue.size()
                + (lastError.isEmpty() ? "" : " | last error: " + lastError)
                + (lastThought.isEmpty() ? "" : " | thought: " + lastThought);
    }

    // ---------------------------------------------------------------- per tick

    public void tick(MinecraftServer server) {
        if (player == null) return;
        tickCount++;
        try {
            player.playerTick(); // normally done by the network handler, which a fake connection does not have
        } catch (Exception e) {
            AiPlayerMod.LOGGER.warn("playerTick failed", e);
        }

        if (player.isDead()) {
            if (++deadTicks == 1) {
                queue.clear();
                events.add("You DIED at " + player.getBlockPos().toShortString() + ". Your items dropped there. You are respawning.");
            }
            if (deadTicks > 30) respawn();
            return;
        }
        deadTicks = 0;

        if (tickCount % 20 == 0) reflexes();

        Task cur = queue.peek();
        if (cur != null) {
            boolean done;
            try {
                done = cur.tick(this);
            } catch (Exception e) {
                AiPlayerMod.LOGGER.warn("task failed: " + cur.describe(), e);
                note("ERROR in '" + cur.describe() + "': " + e);
                done = true;
            }
            if (done) queue.poll();
            idleTicks = 0;
        } else if (!thinking) {
            if (backoff > 0) backoff--;
            else if (urgent || ++idleTicks >= AiPlayerMod.config.thinkIntervalTicks) think();
        }
    }

    private void respawn() {
        try {
            ServerPlayerEntity np = server.getPlayerManager().respawnPlayer(player, false);
            this.player = np;
            events.add("You respawned at " + np.getBlockPos().toShortString() + " with an empty inventory (unless keepInventory is on).");
        } catch (Exception e) {
            AiPlayerMod.LOGGER.error("respawn failed", e);
            lastError = "respawn failed: " + e;
        }
        deadTicks = 0;
    }

    /** Automatic survival habits that don't need the LLM. */
    private void reflexes() {
        // eat when hungry
        if (player.getHungerManager().getFoodLevel() <= 14 || (player.getHealth() < 14 && player.getHungerManager().getFoodLevel() < 20)) {
            eat();
        }
        // wear the best armor
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            ItemStack s = player.getInventory().main.get(i);
            if (s.getItem() instanceof ArmorItem a) {
                EquipmentSlot slot = a.getSlotType();
                ItemStack worn = player.getEquippedStack(slot);
                int cur = worn.getItem() instanceof ArmorItem w ? w.getProtection() : -1;
                if (a.getProtection() > cur) {
                    player.getInventory().main.set(i, worn.copy());
                    player.equipStack(slot, s.copy());
                }
            }
        }
        // swat a hostile mob that is right next to us while idle
        if (queue.isEmpty()) {
            Vec3d pos = player.getPos();
            var mobs = player.getServerWorld().getEntitiesByClass(HostileEntity.class, new Box(pos, pos).expand(3.5), e -> e.isAlive());
            if (!mobs.isEmpty()) queue.addFirst(new CombatTask("hostile", 1));
        }
    }

    // ---------------------------------------------------------------- brain

    /** A human spoke to the bot. */
    public void hear(String who, String text) {
        if (player == null) return;
        String name = AiPlayerMod.config.botName;
        String t = text.trim();
        if (t.regionMatches(true, 0, name, 0, name.length())) t = t.substring(name.length());
        else if (t.startsWith("@ai")) t = t.substring(3);
        t = t.replaceFirst("^[\\s,:、：]+", "");
        heard.add(who + ": " + t);
        queue.clear();
        generation++;     // a reply to an older request is now stale
        thinking = false;
        urgent = true;
        backoff = 0;
    }

    private void think() {
        thinking = true;
        urgent = false;
        idleTicks = 0;
        final int gen = generation;
        final Config cfg = AiPlayerMod.config;
        history.add(new Llm.Msg("user", Brain.observe(this)));
        while (history.size() > 14) history.remove(0);
        while (!history.isEmpty() && !history.get(0).role().equals("user")) history.remove(0);
        final List<Llm.Msg> snapshot = new ArrayList<>(history);
        final String system = Brain.systemPrompt(cfg);
        pool.submit(() -> {
            try {
                String reply = Llm.chat(cfg, system, snapshot);
                server.execute(() -> onReply(gen, reply));
            } catch (Throwable t) {
                server.execute(() -> onError(gen, t));
            }
        });
    }

    private void onReply(int gen, String reply) {
        if (gen != generation || player == null) { // stale: a human interrupted or the bot was removed
            dropLastUser();
            return;
        }
        thinking = false;
        history.add(new Llm.Msg("assistant", reply));
        lastError = "";
        Brain.Plan plan = Brain.parse(this, reply);
        lastThought = plan.thought();
        AiPlayerMod.LOGGER.info("[{}] {}", AiPlayerMod.config.provider, plan.thought());
        if (!plan.say().isBlank()) chat(plan.say());
        queue.addAll(plan.tasks());
        if (plan.tasks().isEmpty()) queue.add(new Tasks.Wait(40));
        if (!plan.error().isEmpty()) note(plan.error());
    }

    private void onError(int gen, Throwable t) {
        if (gen != generation) { dropLastUser(); return; }
        thinking = false;
        dropLastUser();
        lastError = String.valueOf(t.getMessage());
        AiPlayerMod.LOGGER.warn("LLM call failed: {}", lastError);
        backoff = 200; // retry in 10 s
    }

    private void dropLastUser() {
        if (!history.isEmpty() && history.get(history.size() - 1).role().equals("user")) history.remove(history.size() - 1);
    }

    // ---------------------------------------------------------------- helpers used by tasks

    public ServerWorld world() {
        return player.getServerWorld();
    }

    public void note(String s) {
        events.add(s);
        while (events.size() > 25) events.remove(0);
    }

    public void chat(String msg) {
        server.getPlayerManager().broadcast(Text.literal("<" + AiPlayerMod.config.botName + "> " + msg), false);
    }

    public Task currentTask() {
        return queue.peek();
    }

    /** Runs {@code t} before the rest of the queue (used by "follow"). */
    void insertFront(Task t) {
        // the task that called this is still at the head and gets polled when it returns true,
        // so put the new one right behind it
        Task head = queue.poll();
        queue.addFirst(t);
        if (head != null) queue.addFirst(head);
    }

    public void moveTo(Vec3d v) {
        player.refreshPositionAndAngles(v.x, v.y, v.z, player.getYaw(), player.getPitch());
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0;
        world().getChunkManager().threadedAnvilChunkStorage.updatePosition(player);
    }

    public void lookAt(Vec3d target) {
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target);
        player.setHeadYaw(player.getYaw());
    }

    private void toHand(int slot) {
        var inv = player.getInventory();
        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            ItemStack a = inv.getStack(slot).copy();
            ItemStack b = inv.getStack(inv.selectedSlot).copy();
            inv.setStack(slot, b);
            inv.setStack(inv.selectedSlot, a);
        }
    }

    public void equipBestTool(BlockState st) {
        var inv = player.getInventory();
        int best = inv.selectedSlot;
        float bestSpeed = inv.getStack(best).getMiningSpeedMultiplier(st);
        for (int i = 0; i < inv.main.size(); i++) {
            float s = inv.getStack(i).getMiningSpeedMultiplier(st);
            if (s > bestSpeed) { bestSpeed = s; best = i; }
        }
        if (best != inv.selectedSlot) toHand(best);
    }

    public void equipBestWeapon() {
        var inv = player.getInventory();
        int best = -1;
        float bestDmg = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            Item it = inv.getStack(i).getItem();
            float d = it instanceof SwordItem sw ? sw.getAttackDamage() : it instanceof MiningToolItem mt ? mt.getAttackDamage() : 0;
            if (d > bestDmg) { bestDmg = d; best = i; }
        }
        if (best >= 0 && best != inv.selectedSlot) toHand(best);
    }

    public boolean hasItem(Item it) {
        for (ItemStack s : player.getInventory().main) if (s.isOf(it)) return true;
        return player.getOffHandStack().isOf(it);
    }

    public boolean equipItem(Item it) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.main.size(); i++) {
            if (inv.getStack(i).isOf(it)) { toHand(i); return true; }
        }
        return false;
    }

    public boolean eat() {
        var inv = player.getInventory();
        int best = -1, bestFood = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack s = inv.getStack(i);
            var fc = s.getItem().getFoodComponent();
            if (fc == null || s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO)
                    || s.isOf(Items.PUFFERFISH) || s.isOf(Items.CHORUS_FRUIT) || s.isOf(Items.SUSPICIOUS_STEW)) continue;
            if (fc.getHunger() > bestFood) { bestFood = fc.getHunger(); best = i; }
        }
        if (best < 0) return false;
        player.eatFood(world(), inv.getStack(best));
        return true;
    }
}
