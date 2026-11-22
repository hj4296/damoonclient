package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** 동물(또는 지정한 몹)을 쫓아가 잡고 떨어진 아이템을 주움 */
public final class Hunter {
    public enum R { RUNNING, NONE }

    private final Predicate<Entity> prey;
    private final Predicate<ItemStack> loot;
    private int targetId = -1;
    private BlockPos goalPos;
    private int chaseTicks, noReach;
    private final java.util.Map<Integer, Integer> ignoreUntil = new java.util.HashMap<>();
    public int range = 48;
    public String info = "";

    public Hunter(Predicate<Entity> prey, Predicate<ItemStack> loot) {
        this.prey = prey;
        this.loot = loot;
    }

    public static Predicate<Entity> foodAnimals() {
        return e -> (e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken || e instanceof Rabbit)
                && !((LivingEntity) e).isBaby();
    }

    /** 전리품마다 쫓은 시간 (못 줍는 자리에 있으면 10초 뒤 포기) */
    private final java.util.Map<Integer, Integer> lootTime = new java.util.HashMap<>();

    public R tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        // 떨어진 전리품
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof ItemEntity ie && loot.test(ie.getItem()) && ie.distanceTo(p) < 10 && ie.distanceTo(p) > 1.0 && Math.abs(ie.getY() - p.getY()) < 3
                    && lootTime.merge(ie.getId(), 1, Integer::sum) < 20 * 10) {
                info = "전리품 줍는 중";
                Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 0.9));
                if (r != Nav.R.FAILED) return R.RUNNING;
                bot.nav.stop();
            }
        }
        Entity t = targetId >= 0 ? l.getEntity(targetId) : null;
        // 40초 동안 못 잡았거나, 도착했는데 벽 너머라 못 때리면 그 녀석은 2분 동안 무시 (같은 걸 계속 다시 고르던 문제)
        if (t != null && t.isAlive() && (chaseTicks > 20 * 40 || noReach > 20 * 6)) ignoreUntil.put(targetId, bot.ticks + 20 * 120);
        if (t == null || !t.isAlive() || ++chaseTicks > 20 * 40 || noReach > 20 * 6) {
            noReach = 0;
            targetId = -1;
            chaseTicks = 0;
            Entity best = null;
            double bd = 1e9;
            for (Entity e : l.entitiesForRendering()) {
                if (!e.isAlive() || !(e instanceof LivingEntity) || !prey.test(e)) continue;
                if (bot.ticks < ignoreUntil.getOrDefault(e.getId(), 0)) continue;
                double d = e.distanceTo(p) + Math.abs(e.getY() - p.getY()) * 2;
                if (d < range && d < bd) { bd = d; best = e; }
            }
            if (best == null) { info = "사냥감이 안 보임"; return R.NONE; }
            targetId = best.getId();
            t = best;
            goalPos = null;
        }
        LivingEntity le = (LivingEntity) t;
        double r = Act.reach(p, le.getBoundingBox());
        info = le.getName().getString() + " 잡는 중";
        if (r <= 3.0 && p.hasLineOfSight(le)) {
            bot.nav.stop();
            Melee.fight(bot, le, true);
            return R.RUNNING;
        }
        BlockPos at = le.blockPosition();
        if (goalPos == null || goalPos.distSqr(at) > 9) goalPos = at;
        Nav.R nr = bot.nav.go(new Goal.Near(goalPos, 1.5));
        if (nr == Nav.R.FAILED) { bot.nav.stop(); targetId = -1; }
        if (nr == Nav.R.ARRIVED) { goalPos = null; noReach++; } else if (nr == Nav.R.RUNNING) noReach = Math.max(0, noReach - 1);
        return R.RUNNING;
    }
}
