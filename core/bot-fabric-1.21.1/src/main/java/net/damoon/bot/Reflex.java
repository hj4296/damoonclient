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
    private net.minecraft.core.BlockPos mlgWater;
    private int mlgTicks;

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

        // 높은 곳에서 떨어지는 중이면 물 양동이로 착지 (MLG)
        if (!p.onGround() && !p.isInWater() && p.fallDistance > 4.5 && p.getDeltaMovement().y < -0.5
                && bot.level().dimension() != net.minecraft.world.level.Level.NETHER && Inv.has(p, Items.WATER_BUCKET)) {
            Inv.select(p, Inv.is(Items.WATER_BUCKET));
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 90f;
            doing = "물 양동이 낙법!";
            net.minecraft.core.BlockPos f = p.blockPosition();
            for (int dy = 1; dy <= 4; dy++) {
                net.minecraft.core.BlockPos g = f.below(dy);
                var gs = bot.level().getBlockState(g);
                if (!gs.getCollisionShape(bot.level(), g).isEmpty()) {
                    if (p.getY() - (g.getY() + 1) < 2.6 && p.getXRot() > 80) {
                        net.minecraft.client.Minecraft.getInstance().gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        mlgWater = g.above();
                        mlgTicks = 0;
                    }
                    break;
                }
            }
            return true;
        }
        // 낙법에 쓴 물 다시 퍼 담기
        if (mlgWater != null) {
            if (++mlgTicks > 60 || !bot.level().getFluidState(mlgWater).isSource()) { mlgWater = null; }
            else if (p.onGround() || p.isInWater()) {
                Inv.select(p, Inv.is(Items.BUCKET));
                bot.ctl.lookAt(p, net.minecraft.world.phys.Vec3.atCenterOf(mlgWater));
                if (bot.ctl.aimed(p, 3) && mlgTicks > 6) {
                    net.minecraft.client.Minecraft.getInstance().gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    mlgWater = null;
                }
                doing = "물 다시 퍼 담는 중";
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
        if (threat != null && ranged(threat) && p.getHealth() < 7 && threat.distanceTo(p) > 4) {
            Vec3 away = p.position().subtract(threat.position()).normalize();
            bot.ctl.lookYaw(p, p.position().add(away.scale(5)), 0f);
            bot.ctl.fwd = true;
            bot.ctl.sprint = p.getFoodData().getFoodLevel() > 6;
            if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
            doing = "체력이 낮아 " + threat.getName().getString() + " 피하는 중";
            return true;
        }
        if (threat != null) {
            Inv.selectWeapon(p);
            Vec3 aim = threat.position().add(0, threat.getBbHeight() * 0.7, 0);
            bot.ctl.lookAt(p, aim);
            double r = Act.reach(p, threat.getBoundingBox());
            // 활 쏘는 몹에게 다가갈 때는 방패를 들고 감
            if (ranged(threat) && r > 3.0 && p.getOffhandItem().is(Items.SHIELD)) {
                bot.ctl.use = true;
                bot.ctl.fwd = true;
                if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                doing = "방패 들고 " + threat.getName().getString() + "에게 다가가는 중";
                return true;
            }
            if (r > 2.6) {
                bot.ctl.fwd = true;
                bot.ctl.sprint = r > 5 && p.getFoodData().getFoodLevel() > 6;
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

    private final java.util.Map<Integer, Integer> ignoreUntil = new java.util.HashMap<>();
    private int chaseId = -1, chaseTicks;
    private double chaseBest;

    static boolean ranged(Entity e) {
        return e instanceof net.minecraft.world.entity.monster.AbstractSkeleton || e instanceof net.minecraft.world.entity.monster.Pillager
                || e instanceof net.minecraft.world.entity.monster.Witch || e instanceof net.minecraft.world.entity.monster.Blaze;
    }

    private LivingEntity threat(LocalPlayer p) {
        LivingEntity best = null;
        double bd = 1e9;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!hostile(e)) continue;
            if (bot.ticks < ignoreUntil.getOrDefault(e.getId(), 0)) continue;
            double d = e.distanceTo(p);
            // 활 쏘는 몹은 멀리서도 보이면 달려가서 벰
            double lim = e instanceof EnderMan ? 8 : ranged(e) ? 14 : 4.5;
            if (d > lim || Math.abs(e.getY() - p.getY()) > (ranged(e) ? 6 : 3)) continue;
            if (!p.hasLineOfSight(e)) continue;
            double score = d - (ranged(e) ? 6 : 0);
            if (score < bd) { bd = score; best = (LivingEntity) e; }
        }
        // 쫓아가도 가까워지지 않으면 (절벽 위 등) 잠시 무시
        if (best != null) {
            double d = best.distanceTo(p);
            if (best.getId() != chaseId) { chaseId = best.getId(); chaseTicks = 0; chaseBest = d; }
            if (d < chaseBest - 0.5) { chaseBest = d; chaseTicks = 0; }
            if (++chaseTicks > 100 && d > 3.5) { ignoreUntil.put(best.getId(), bot.ticks + 20 * 30); return null; }
        }
        return best;
    }
}
