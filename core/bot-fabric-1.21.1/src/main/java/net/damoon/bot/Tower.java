package net.damoon.bot;

import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 기둥 방어 (사람들이 쓰는 "기둥 쌓기"): 근접 몹이 여럿 몰려오면 발밑에 블록을 3칸 쌓고 올라가
 * 위에서 내려침. 몹의 팔은 옆으로만 닿고 위로는 안 닿으므로 (위더 스켈레톤 키 2.4칸) 3칸 위에선 맞지 않음.
 * 잠잠해지면 기둥을 캐면서 내려옴.
 */
public final class Tower {
    private boolean active;
    private BlockPos base;
    private int height, phase, ticks, calm, hits, lastDescLog = -10000;
    public String info = "";

    private boolean forcedDown;
    private int cooldownUntil;
    public boolean active() { return active; }

    /** 기둥 꼭대기에 서 있음 (근접 몹이 못 때림) */
    public boolean onTop(LocalPlayer p) {
        return active && phase == 1 && p.getY() >= base.getY() + height - 0.2;
    }

    /** 땅 위의 근접 몹 (기어오르는 거미, 뛰는 슬라임, 원거리 몹 제외) */
    static boolean groundMelee(Entity e) {
        return Reflex.hostile(e) && !Reflex.ranged(e) && !(e instanceof Creeper) && !(e instanceof Spider)
                && !(e instanceof Slime) && !(e instanceof Ghast) && !(e instanceof net.minecraft.world.entity.monster.Phantom)
                && !(e instanceof net.minecraft.world.entity.monster.Vex);
    }

    private static boolean tower(LocalPlayer p) {
        return p.onGround() && !p.isInWater() && !p.isInLava() && Inv.count(p, Inv.throwaway()) >= 4;
    }

    private static boolean clear(ClientLevel l, BlockPos b) {
        var s = l.getBlockState(b);
        return s.getCollisionShape(l, b).isEmpty() && s.getFluidState().isEmpty() && !BlockUtil.hurts(s);
    }

    /** 이번 틱을 썼으면 true */
    public boolean tick(Bot bot, Reflex reflex) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        int count = 0;
        boolean wither = false;
        LivingEntity closest = null;
        double cd = 1e9;
        for (Entity e : l.entitiesForRendering()) {
            if (!groundMelee(e) || e instanceof net.minecraft.world.entity.monster.EnderMan || ((LivingEntity) e).getMaxHealth() <= 10) continue;
            double d = e.distanceTo(p);
            if (d > 10 || Math.abs(e.getY() - p.getY()) > 4) continue;
            if (d > 5 && !p.hasLineOfSight(e)) continue;
            count++;
            if (e instanceof WitherSkeleton || e instanceof net.minecraft.world.entity.monster.EnderMan) wither = true;
            if (d < cd) { cd = d; closest = (LivingEntity) e; }
        }
        boolean ranged = false;
        // 원거리 몹(스켈레톤) 때문에 기둥을 쌓는 건 활이 있어 위에서 쏠 수 있을 때만 (없으면 기둥 위는 과녁일 뿐)
        if (!active && Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW)) {
            for (Entity e : l.entitiesForRendering())
                if (Reflex.hostile(e) && Reflex.ranged(e) && e.distanceTo(p) < 20 && p.hasLineOfSight(e)) { ranged = true; break; }
        }
        if (!active) {
            // 근접 몹이 여럿이거나, 위더 스켈레톤이 오거나, 원거리 몹과 같이 오거나, 체력이 낮으면 (아직 3칸 넘게 떨어져 있을 때 미리)
            // 방패가 있으면 위더 스켈레톤 하나는 방패로 막으며 칼로 상대 (기둥은 쌓는 사이 맞고 밀려 떨어짐)
            boolean shield = p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD);
            boolean want = count >= 2 || (count >= 1 && ((wither && !shield) || ranged || p.getHealth() < 12) && cd < 9);
            if (want && cd < 3.8) return false; // 이미 붙었으면 먼저 방패로 밀어냄
            // 화난 엔더맨이 있으면 기둥 대신 방패 싸움 (순간이동해서 옆으로 옴)
            for (Entity e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.EnderMan em && em.isCreepy() && em.distanceTo(p) < 16) return false;
            if (want && !tower(p) && bot.ticks % 40 == 0) bot.dbg("기둥 조건: 블록 " + Inv.count(p, Inv.throwaway()) + " 땅 " + p.onGround());
            if (!want || bot.ticks < cooldownUntil || !p.onGround() || p.isInWater() || p.isInLava() || Inv.count(p, Inv.throwaway()) < 4) return false;
            BlockPos f = Placer.standCell(p);
            int h = clear(l, f.above(2)) && clear(l, f.above(3)) && clear(l, f.above(4)) ? 3
                    : (!wither && clear(l, f.above(2)) && clear(l, f.above(3))) ? 2 : 0;
            if (h == 0) return false;
            if (!BlockUtil.floor(l, f.getX(), f.getY() - 1, f.getZ(), l.getBlockState(f.below()))) return false;
            // 주변 2칸 안에 낭떠러지(아래 3칸 안에 바닥 없음)가 있으면 밀려 떨어질 수 있으니 안 쌓음
            for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
                if (ox == 0 && oz == 0) continue;
                boolean ground = false;
                for (int dy = 1; dy <= 3 && !ground; dy++) {
                    BlockPos q = f.offset(ox, -dy, oz);
                    var qs = l.getBlockState(q);
                    if (BlockUtil.isLava(qs)) break;
                    if (!qs.getCollisionShape(l, q).isEmpty()) ground = true;
                }
                if (!ground && !(l.getBlockState(f.offset(ox, 0, oz)).getCollisionShape(l, f.offset(ox, 0, oz)).isEmpty() == false)) {
                    if (bot.ticks % 40 == 0) bot.dbg("기둥 안 쌓음: 가장자리 " + f.offset(ox, 0, oz).toShortString());
                    return false;
                }
            }
            active = true;
            base = f;
            height = h;
            phase = 0;
            ticks = 0;
            calm = 0;
            hits = 0;
            bot.nav.stop();
            Act.stopDig(bot);
            bot.log("근접 몹 " + count + "마리 → 블록 " + h + "칸 쌓고 올라가 위에서 싸움");
        }
        ticks++;
        BlockPos feet = PathExec.feet(p);
        int top = base.getY() + height;
        if (p.getY() < base.getY() - 0.5) { active = false; bot.log("기둥 밑으로 떨어짐 → 다시 판단"); return false; }
        if (Math.abs(feet.getX() - base.getX()) > 0 || Math.abs(feet.getZ() - base.getZ()) > 0) {
            // 밀려 떨어짐
            if (p.onGround()) { active = false; cooldownUntil = bot.ticks + 20 * 15; bot.log("기둥에서 밀려남 → 15초 동안 방패로 싸움"); return false; }
        }
        if (phase == 0) {
            info = "기둥 쌓는 중 (" + (feet.getY() - base.getY()) + "/" + height + ")";
            reflex.doing = info;
            if (ticks > 20 * 8) { active = false; bot.log("기둥을 못 쌓음"); return false; }
            if (closest != null && cd < 2.3 && feet.getY() - base.getY() < 2 && p.onGround()) {
                active = false;
                bot.log("쌓는 중에 붙음 → 방패부터");
                return false;
            }
            if (p.onGround() && feet.getY() >= top) {
                phase = 1;
                ticks = 0;
                return true;
            }
            double dx = base.getX() + 0.5 - p.getX(), dz = base.getZ() + 0.5 - p.getZ();
            if (p.onGround() && dx * dx + dz * dz > 0.04 && ticks < 25) {
                float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
                bot.ctl.look(yaw, 60f);
                bot.ctl.sneak = true;
                if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 25) bot.ctl.fwd = true;
                return true;
            }
            Inv.select(p, Inv.throwaway());
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 60f;
            bot.ctl.sneak = true;
            if (p.getXRot() < 70f) return true;
            if (p.onGround()) bot.ctl.jump = true;
            BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
            // 뛰어오른 뒤 원래 서 있던 칸이 비면 그 아래 블록 윗면에 놓음
            BlockPos stand = BlockPos.containing(p.getX(), Math.floor(p.getY() - 0.01) , p.getZ());
            if (!p.onGround() && clear(l, stand) && !clear(l, stand.below()) && p.getY() > stand.getY() + 1.05) {
                Act.place(bot, stand.below(), Direction.UP);
            } else if (!p.onGround() && clear(l, under) && !clear(l, under.below()) && p.getY() > under.getY() + 1.05) {
                Act.place(bot, under.below(), Direction.UP);
            }
            return true;
        }
        if (phase == 1) {
            // 위에서 싸우기
            if (feet.getY() < top - 1 && p.onGround()) { phase = 0; ticks = 0; return true; }
            bot.ctl.sneak = true;
            if (closest == null || cd > 10) calm++; else calm = 0;
            // 방패도 활도 없이 기둥 위에서 화살을 맞고 있으면 버티지 말고 바로 내려감 (내려가서 칼로 잡거나 피함)
            boolean bowReady = Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW);
            if (!bowReady && !p.getOffhandItem().is(Items.SHIELD) && bot.lastAttacker != null && Reflex.ranged(bot.lastAttacker)
                    && bot.ticks - bot.lastAttackTick < 20 * 2 && ticks > 20) {
                forcedDown = true;
                phase = 2;
                ticks = 0;
                bot.log("기둥 위에서 화살을 맞음 (방패·활 없음) → 내려감");
                return true;
            }
            // 몹이 밑에서 서성이기만 하고 안 다가오면 끝이 없음 → 1분 넘게 위에 있었으면 내려가 방패·칼로 상대
            if (calm > 60 || ticks > 20 * 60) {
                forcedDown = calm <= 60;
                phase = 2;
                ticks = 0;
                if (bot.ticks - lastDescLog > 400) { bot.log("잠잠해짐 → 기둥 캐며 내려감"); lastDescLog = bot.ticks; }
                return true;
            }
            // 사거리 안의 몹 중 가장 가까운 것
            LivingEntity tgt = null;
            double best = 1e9;
            for (Entity e : l.entitiesForRendering()) {
                if (!(e instanceof LivingEntity le) || !Reflex.hostile(e)) continue;
                double r = Act.reach(p, le.getBoundingBox());
                if (r <= 3.0 && r < best && p.hasLineOfSight(le)) { best = r; tgt = le; }
            }
            // 화염구·화살을 쏘려는 몹이 보이면 방패 (근접 몹은 못 때리니 공격 기회가 없을 때만)
            Entity shooter = null;
            for (Entity e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.Blaze b && reflex.blazeSoon(p, b, 0) && p.hasLineOfSight(b)) { shooter = e; break; }
            float charge = p.getAttackStrengthScale(0.5f);
            if (tgt != null && (charge >= 0.9f || shooter == null)) {
                Inv.selectWeapon(p);
                Vec3 aim = new Vec3(tgt.getX(), tgt.getBoundingBox().maxY - 0.2, tgt.getZ());
                bot.ctl.lookAt(p, aim);
                if (p.isUsingItem() && charge >= 0.9f) { bot.ctl.use = false; info = "방패 내림"; return true; }
                if (charge >= 0.95f && bot.ctl.aimed(p, 15) && Act.attack(bot, tgt, 0.95f)) hits++;
                info = "기둥 위에서 " + tgt.getName().getString() + " 내려치는 중";
                reflex.doing = info;
                return true;
            }
            if (shooter != null && p.getOffhandItem().is(Items.SHIELD)) {
                Inv.selectWeapon(p);
                bot.ctl.lookAt(p, shooter.getBoundingBox().getCenter());
                bot.ctl.use = true;
                reflex.doing = "기둥 위에서 화염구 막는 중";
                return true;
            }
            // 기다리는 동안 먹기 (근접 몹이 못 닿으니 안전)
            if (p.getFoodData().getFoodLevel() < 20 && (p.getHealth() < 18 || p.getFoodData().getFoodLevel() <= 14)) {
                if (reflex.startEatIfNeeded()) { reflex.doing = "기둥 위에서 먹는 중"; return true; }
            }
            // 칼이 안 닿는 몹은 활로 쏨 (기둥 위라 안전하게 저격)
            LivingEntity snipe = null;
            double sdist = 1e9;
            for (Entity e : l.entitiesForRendering()) {
                if (!(e instanceof LivingEntity le) || !Reflex.hostile(e) || e instanceof net.minecraft.world.entity.monster.EnderMan) continue;
                double d = e.distanceTo(p);
                if (d < 26 && d < sdist && p.hasLineOfSight(e)) { sdist = d; snipe = le; }
            }
            if (snipe != null && Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW)) {
                Vec3 vel = snipe.position().subtract(snipe.xo, snipe.yo, snipe.zo);
                if (reflex.archer.tick(bot, snipe.getBoundingBox().getCenter(), vel)) bot.log("기둥 위에서 " + snipe.getName().getString() + "에게 화살");
                reflex.doing = "기둥 위에서 " + snipe.getName().getString() + " 활로 저격";
                return true;
            }
            if (closest != null) bot.ctl.lookAt(p, closest.getEyePosition());
            reflex.doing = "기둥 위에서 기다리는 중";
            return true;
        }
        // phase 2: 발밑 블록을 캐며 내려감
        if (closest != null && cd < 5 && count >= 1 && !forcedDown) {
            // 다시 몰려오면: 아직 꼭대기면 그대로 싸움, 조금 내려왔으면 다시 쌓음
            phase = feet.getY() >= top ? 1 : 0;
            calm = 0;
            ticks = 0;
            return true;
        }
        if (feet.getY() <= base.getY() || ticks > 20 * 20) {
            Act.stopDig(bot);
            active = false;
            // 시간이 다 돼 내려왔으면 잠시 다시 쌓지 않음 (내려가자마자 다시 올라가던 반복)
            // 내려온 뒤엔 잠시 다시 쌓지 않음 (벽 너머 몹 때문에 쌓고 내려오기를 반복하던 문제)
            cooldownUntil = bot.ticks + 20 * (forcedDown ? 30 : 20);
            forcedDown = false;
            bot.log("기둥에서 내려옴");
            return false;
        }
        reflex.doing = "기둥 캐며 내려가는 중";
        if (!p.onGround()) return true;
        BlockPos below = feet.below();
        var s = l.getBlockState(below);
        if (s.isAir() || !BlockUtil.breakable(l, below.getX(), below.getY(), below.getZ(), s)) {
            active = false;
            return false;
        }
        bot.ctl.look(p.getYRot(), 90f);
        bot.ctl.sneak = true;
        Act.dig(bot, below);
        return true;
    }
}
