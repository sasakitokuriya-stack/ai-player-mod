package com.example.aiplayer.bot;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Hunt a kind of mob ("zombie", "cow", or the groups "hostile" / "animal"), then collect the drops. */
public class CombatTask implements Task {
    private final String type;
    private final int count;
    private int kills = 0;
    private int ticks = 0;
    private int repath = 0;
    private LivingEntity target;
    private Task move;
    private Task pickup;

    public CombatTask(String type, int count) {
        this.type = type.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        this.count = Math.max(1, Math.min(count, 32));
    }

    @Override
    public String describe() {
        return "attack " + type + " (" + kills + "/" + count + ")";
    }

    private boolean matches(LivingEntity e) {
        if (!e.isAlive() || e instanceof ServerPlayerEntity) return false;
        return switch (type) {
            case "hostile", "monster" -> e instanceof HostileEntity;
            case "animal", "animals" -> e instanceof AnimalEntity;
            default -> Registries.ENTITY_TYPE.getId(e.getType()).getPath().equals(type);
        };
    }

    @Override
    public boolean tick(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        ServerWorld w = p.getServerWorld();
        if (pickup != null) {
            if (pickup.tick(bot)) pickup = MineTask.nextPickup(bot, w);
            return false;
        }
        if (++ticks > 1200) {
            bot.note("STOPPED attack " + type + ": timeout, killed " + kills);
            return true;
        }
        if (target != null && !target.isAlive()) {
            kills++;
            target = null;
            move = null;
            if (kills >= count) {
                bot.note("DONE attack " + type + " x" + kills);
                pickup = MineTask.nextPickup(bot, w);
                return pickup == null;
            }
        }
        if (target == null) {
            Vec3d pos = p.getPos();
            List<LivingEntity> found = w.getEntitiesByClass(LivingEntity.class, new Box(pos, pos).expand(24), this::matches);
            if (found.isEmpty()) {
                bot.note((kills > 0 ? "PARTIAL " : "FAILED ") + "attack " + type + ": none within 24 blocks, killed " + kills);
                pickup = MineTask.nextPickup(bot, w);
                return pickup == null;
            }
            found.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
            target = found.get(0);
        }
        double dist = p.distanceTo(target);
        if (dist <= 3.2) {
            bot.equipBestWeapon();
            bot.lookAt(target.getEyePos());
            if (p.getAttackCooldownProgress(0.5f) >= 1.0f) {
                p.attack(target);
                p.swingHand(Hand.MAIN_HAND, true);
            }
        } else if (move == null || repath++ % 10 == 0) {
            move = new MoveTask(target.getBlockPos(), 2.4, type);
        }
        if (dist > 3.2 && move != null && move.tick(bot)) move = null;
        return false;
    }
}
