package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * 사람처럼 싸우는 근접전 (PvP 기술):
 * - 거리 유지: 칼 끝 거리(2.6~3칸)에서 때리고, 너무 붙으면 방패 들고 뒤로 물러남
 * - 쿨다운 동안엔 방패로 막음
 * - 상대가 하나면 점프 치명타(1.5배), 여럿이면 달리며 때려 넉백으로 거리를 벌림
 * - 기다리는 동안 좌우로 움직여 투사체를 피함
 */
public final class Melee {
    private Melee() {}

    private static int strafeTick;
    private static boolean counter;
    private static final java.util.Map<Integer, Integer> lastDist = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Double> bestDist = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> engaged = new java.util.HashMap<>();

    /** 이 상대와 붙어 있은 틱 수 (마지막 공격 이후) */
    private static int engagedTicks(Bot bot, LivingEntity t) {
        if (engaged.size() > 64) engaged.clear();
        return bot.ticks - engaged.computeIfAbsent(t.getId(), k -> bot.ticks);
    }
    private static final java.util.Map<Integer, Integer> lastSwing = new java.util.HashMap<>();

    /** 상대가 휘두르는 순간을 기억 (클라이언트도 휘두르기 애니메이션을 받음) */
    private static void trackSwing(Bot bot, LivingEntity t) {
        if (lastSwing.size() > 64) lastSwing.clear();
        for (Entity e : bot.level().entitiesForRendering())
            if (e instanceof LivingEntity le && le.swinging && le.swingTime <= 1 && le.distanceTo(bot.p()) < 10) lastSwing.put(le.getId(), bot.ticks);
    }

    /** 다른 근접 몹이 곧 나를 칠 수 있는 자리에 있는지 (그러면 방패를 내리면 안 됨) */
    private static boolean othersThreat(Bot bot, LocalPlayer p, LivingEntity t) {
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e == t || !Tower.groundMelee(e)) continue;
            double ox = e.getX() - p.getX(), oz = e.getZ() - p.getZ();
            double arm = e.getBbWidth() / 2 + 0.83 + p.getBbWidth() / 2;
            if (Math.max(Math.abs(ox), Math.abs(oz)) > arm + 1.2 || Math.abs(e.getY() - p.getY()) > 2.5) continue;
            Integer s = lastSwing.get(e.getId());
            if (s == null || bot.ticks - s > 10) return true;
        }
        return false;
    }

    public static String fight(Bot bot, LivingEntity t, boolean approach) {
        LocalPlayer p = bot.p();
        Inv.selectWeapon(p);
        bot.ctl.lookAt(p, t.getBoundingBox().getCenter());
        double r = Act.reach(p, t.getBoundingBox());
        float charge = p.getAttackStrengthScale(0.5f);
        boolean shield = p.getOffhandItem().is(Items.SHIELD);
        boolean blocking = p.isUsingItem() && p.getUseItem().is(Items.SHIELD);
        int crowd = 0;
        for (Entity e : bot.level().entitiesForRendering()) if (e != t && Reflex.hostile(e) && e.distanceTo(p) < 6) crowd++;

        // 방패 근접전: 상대 팔이 안 닿는 거리(약 2칸)이거나 방금 휘둘러 막은 직후에만 방패를 내리고 벰.
        // 그 밖엔 방패를 상대 쪽으로 들고 있음 (정면 공격은 모두 막고, 막으면 상대가 밀려남)
        trackSwing(bot, t);
        boolean meleeMob = !(t instanceof net.minecraft.world.entity.monster.Creeper) && !Reflex.ranged(t)
                && !(t instanceof net.minecraft.world.entity.monster.Blaze) && Reflex.hostile(t);
        if (shield && meleeMob) {
            Integer sw = lastSwing.get(t.getId());
            int since = sw == null ? 9999 : bot.ticks - sw;
            // 방패로 막은 직후(상대 다음 공격까지 20틱)에만 방패를 내리고 벰. 방패를 다시 들어 막히기까지 6틱이 걸리므로 10틱 안에 끝냄
            boolean window = since <= 10;
            // 상대가 3초 넘게 안 휘두르면(높이 차이 등으로 못 때리는 중) 그냥 벰
            boolean idle = since > 60 && engagedTicks(bot, t) > 60;
            double hx = t.getX() - p.getX(), hz = t.getZ() - p.getZ();
            double arm = t.getBbWidth() / 2 + 0.83 + p.getBbWidth() / 2;
            if (r <= 3.0 && charge >= 0.9f && (window || idle) && !othersThreat(bot, p, t)) {
                if (p.isUsingItem()) { bot.ctl.use = false; counter = true; return "방패 내리고 반격"; }
                counter = false;
                if (bot.ctl.aimed(p, 25) && Act.attack(bot, t, 0.9f)) { engaged.remove(t.getId()); return window ? "막고 반격!" : "공격"; }
                return "조준";
            }
            counter = false;
            bot.ctl.use = true;
            if (Math.max(Math.abs(hx), Math.abs(hz)) < arm + 0.1 && Reflex.safeBehind(p)) bot.ctl.back = true;
            else if (r > 3.2 && approach) {
                Integer last = lastDist.get(t.getId());
                // 앞이 낭떠러지라 못 걷거나 오래 안 가까워지면 길찾기로 돌아서 감
                if (!Bot.safeAhead(p) || (last != null && bot.ticks - last > 60)) {
                    bot.nav.go(new net.damoon.bot.path.Goal.Near(t.blockPosition(), 2.0));
                    bot.ctl.use = true;
                } else {
                    bot.ctl.fwd = true;
                    if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                }
                double dnow = t.distanceTo(p);
                Double best = bestDist.get(t.getId());
                if (best == null || dnow < best - 0.5) { bestDist.put(t.getId(), dnow); lastDist.put(t.getId(), bot.ticks); }
                return "방패 들고 다가가는 중";
            }
            return "방패로 막으며 틈 보는 중";
        }
        // 쿨다운 중: 방패 들고 거리 조절 (너무 붙으면 뒤로, 멀면 그대로 기다림)
        if (charge < 0.85f) {
            if (shield && r < 4.5) bot.ctl.use = true;
            if (r < 2.2 && Reflex.safeBehind(p)) bot.ctl.back = true;
            // 좌우로 조금씩 움직여 화살·화염구를 피함
            if (r > 2.5 && ++strafeTick % 40 < 20) bot.ctl.left = true; else if (r > 2.5) bot.ctl.right = true;
            return blocking ? "방패로 막으며 거리 유지" : "쿨다운 기다리는 중";
        }
        if (blocking) { bot.ctl.use = false; return "방패 내림"; }
        // 다가가기 (여럿이면 달려서 넉백 공격 준비)
        if (r > 2.9 && approach) {
            bot.ctl.fwd = true;
            bot.ctl.sprint = (r > 5 || crowd > 0) && p.getFoodData().getFoodLevel() > 6;
            if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
        }
        if (r > 3.0) return "다가가는 중";
        boolean headroom = p.level().getBlockState(p.blockPosition().above(2)).getCollisionShape(p.level(), p.blockPosition().above(2)).isEmpty();
        if (crowd > 0 && p.isSprinting()) {
            // 여럿: 달리며 때려 넉백
            if (bot.ctl.aimed(p, 25) && Act.attack(bot, t, 0.9f)) return "달리며 넉백 공격";
            return "달리는 중";
        }
        // 하나: 점프 치명타
        if (p.onGround() && headroom && !p.isInWater() && !p.onClimbable()) {
            bot.ctl.jump = true;
            bot.ctl.sprint = false;
            return "점프!";
        }
        boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
        if (bot.ctl.aimed(p, 25) && (falling || !headroom || p.isInWater())) {
            if (Act.attack(bot, t, 0.9f)) return falling ? "치명타!" : "공격";
        }
        return "공격 준비";
    }
}
