package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 작업과 상관없이 먼저 반응하는 행동: 먹기, 회복, 가까이 온 몹과 싸우기, 크리퍼 피하기 */
public final class Reflex {
    private final Bot bot;
    private boolean eating;
    private int eatTicks;
    public String doing = "";
    /** 작업이 지금은 먹지 말라고 할 때 (예: 드래곤 머리 때리는 중) */
    public boolean holdEating;
    public boolean fightEnabled = true;

    Reflex(Bot bot) { this.bot = bot; }

    public boolean tick() {
        LocalPlayer p = bot.p();
        doing = "";
        if (p.containerMenu != p.inventoryMenu) return false;

        // 먹는 중이면 계속
        if (eating) {
            eatTicks++;
            if ((!p.isUsingItem() && eatTicks > 6) || eatTicks > 70) {
                eating = false;
            } else {
                bot.ctl.use = true;
                doing = "먹는 중";
                return true;
            }
        }

        // 크리퍼가 터지려 하면 도망
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof Creeper c && c.isAlive() && c.getSwelling(1f) > 0.15f && c.distanceTo(p) < 5) {
                Vec3 away = p.position().subtract(c.position()).normalize();
                bot.ctl.lookYaw(p, p.position().add(away.scale(5)), 0f);
                bot.ctl.fwd = true;
                bot.ctl.sprint = true;
                if (p.horizontalCollision) bot.ctl.jump = true;
                doing = "크리퍼 피하는 중";
                return true;
            }
        }

        // 가까운 적과 싸우기
        LivingEntity threat = fightEnabled ? threat(p) : null;
        if (threat != null) {
            Inv.selectWeapon(p);
            Vec3 aim = threat.position().add(0, threat.getBbHeight() * 0.7, 0);
            bot.ctl.lookAt(p, aim);
            double r = Act.reach(p, threat.getBoundingBox());
            if (r > 2.6) {
                bot.ctl.fwd = true;
                if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
            }
            if (r <= 3.0 && bot.ctl.aimed(p, 25)) Act.attack(bot, threat, 0.92f);
            doing = "싸우는 중: " + threat.getName().getString();
            return true;
        }

        // 먹기 / 황금사과
        if (!holdEating) {
            int food = p.getFoodData().getFoodLevel();
            float hp = p.getHealth();
            boolean gap = hp <= 10 && Inv.has(p, Items.GOLDEN_APPLE) && !p.hasEffect(MobEffects.REGENERATION);
            boolean need = food <= 14 || (hp < 14 && food < 20);
            if (gap || need) {
                boolean ok = gap ? Inv.select(p, Inv.is(Items.GOLDEN_APPLE)) : selectBestFood(p);
                if (ok) {
                    eating = true;
                    eatTicks = 0;
                    bot.ctl.use = true;
                    bot.ctl.look(p.getYRot(), -30f);
                    doing = gap ? "황금사과 먹는 중" : "먹는 중";
                    return true;
                }
            }
        }
        return false;
    }

    private boolean selectBestFood(LocalPlayer p) {
        int best = -1, bestVal = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().items.get(i);
            if (!Inv.food().test(s) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;
            int v = Inv.foodValue(s);
            if (v > bestVal) { bestVal = v; best = i; }
        }
        if (best < 0) return false;
        ItemStack want = p.getInventory().items.get(best);
        return Inv.select(p, s -> s == want);
    }

    public static boolean hostile(Entity e) {
        if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (e instanceof EnderMan em) return em.isCreepy();
        if (e instanceof ZombifiedPiglin) return false;
        if (e instanceof AbstractPiglin) return false;
        return !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon);
    }

    private LivingEntity threat(LocalPlayer p) {
        LivingEntity best = null;
        double bd = 1e9;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!hostile(e)) continue;
            double d = e.distanceTo(p);
            double lim = e instanceof EnderMan ? 8 : 4.5;
            if (d > lim || Math.abs(e.getY() - p.getY()) > 3) continue;
            if (!p.hasLineOfSight(e)) continue;
            if (d < bd) { bd = d; best = (LivingEntity) e; }
        }
        return best;
    }
}
