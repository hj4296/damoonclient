package net.damoon.bot;

import net.damoon.bot.path.PathExec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 터틀링: 위험할 때 그 자리에서 사방 벽(발·머리 높이)과 천장을 블록으로 막아 완전히 갇힌 채
 * 먹고 회복한 뒤, 벽 하나를 부수고 나옴. 화염구·근접 공격 모두 닿지 않음.
 */
public final class Turtle {
    private boolean active;
    private BlockPos cell;
    private int phase, ticks;

    public boolean active() { return active; }
    public void abort() { active = false; }
    /** 구멍 모드: 발밑 3칸을 파고 들어가 위를 덮음 (적이 이미 붙었을 때) */
    private boolean hole;

    /** 발밑 3칸을 파도 안전한지: 캘 수 있고, 사방이 막혀 있고, 그 아래가 단단하고, 용암·물이 없음 */
    public static boolean holeOk(ClientLevel l, LocalPlayer p, BlockPos f) {
        for (int d = 1; d <= 3; d++) {
            BlockPos b = f.below(d);
            var s = l.getBlockState(b);
            if (s.isAir() || !s.getFluidState().isEmpty() || !BlockUtil.breakable(l, b.getX(), b.getY(), b.getZ(), s)) return false;
            if (BlockUtil.breakTicks(p, l, b, s, false, null) > 30) return false;
            for (Direction h : Direction.Plane.HORIZONTAL) {
                BlockPos n = b.relative(h);
                var ns = l.getBlockState(n);
                if (ns.getCollisionShape(l, n).isEmpty() || !ns.getFluidState().isEmpty()) return false;
            }
        }
        BlockPos fl = f.below(4);
        var fs = l.getBlockState(fl);
        return !fs.getCollisionShape(l, fl).isEmpty() && !BlockUtil.isLava(fs);
    }
    /** 벽·천장을 다 막고 안에서 회복 중 */
    public boolean sealed() { return active && phase == 1 && openWalls == 0; }
    private int openWalls, fixTicks, cooldownUntil;

    private int countOpen(ClientLevel l) {
        if (hole) return l.getBlockState(cell.below()).canBeReplaced() ? 1 : 0;
        int n = 0;
        for (BlockPos w : walls()) if (l.getBlockState(w).canBeReplaced() || BlockUtil.vine(l.getBlockState(w))) n++;
        return n;
    }

    private List<BlockPos> walls() {
        List<BlockPos> out = new ArrayList<>();
        // 벽 칸 아래가 비어 있으면(낭떠러지 옆) 받침부터 — 내 발밑 블록 옆면에 붙음
        for (Direction h : Direction.Plane.HORIZONTAL) {
            BlockPos under = cell.relative(h).below();
            var us = Minecraft.getInstance().level.getBlockState(under);
            if (us.canBeReplaced() && us.getFluidState().isEmpty()) out.add(under);
        }
        for (int y = 0; y <= 1; y++) for (Direction h : Direction.Plane.HORIZONTAL) out.add(cell.relative(h).above(y));
        // 지붕은 옆면에 붙일 곳이 있어야 놓을 수 있음 → 머리 위 옆칸에 받침 하나 (이미 막혀 있으면 그대로)
        BlockPos roof = cell.above(2);
        boolean support = false;
        for (Direction h : Direction.Plane.HORIZONTAL)
            if (!Minecraft.getInstance().level.getBlockState(roof.relative(h)).canBeReplaced()) support = true;
        if (!support && Minecraft.getInstance().level.getBlockState(cell.above(3)).canBeReplaced()) out.add(cell.relative(Direction.NORTH).above(2));
        out.add(roof);
        return out;
    }

    private static boolean danger(Bot bot, LocalPlayer p) {
        float hp = p.getHealth();
        if (hp >= 14) return false;
        if (p.isOnFire()) return true;
        if (hp >= 12) {
            // 위더 스켈레톤이 근처면 더 일찍
            for (Entity e : bot.level().entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.WitherSkeleton && e.distanceTo(p) < 6) return true;
            return false;
        }
        // 먹을 게 없고 배고프면 갇혀 있어도 체력이 안 참 → 적이 정말 가까울 때만 (아니면 사냥하러 가야 함)
        // 밤엔 밖이 더 위험하니 먹을 게 없어도 감싸고 아침까지 버팀
        boolean night = net.damoon.bot.task.BedTask.night(bot.level()) && bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
        double r = canHeal(p) || night ? 14 : 6;
        for (Entity e : bot.level().entitiesForRendering()) if (Reflex.hostile(e) && e.distanceTo(p) < r) return true;
        return false;
    }

    /** 체력이 저절로 차거나(배고픔 18 이상) 먹을 게 있음 */
    static boolean canHeal(LocalPlayer p) {
        return p.getFoodData().getFoodLevel() >= 18 || Inv.count(p, Inv.food()) > 0;
    }

    private boolean holeTick(Bot bot, Reflex reflex, LocalPlayer p, ClientLevel l) {
        BlockPos ft = PathExec.feet(p);
        int depth = cell.getY() - ft.getY();
        if (phase == 0) {
            reflex.doing = "발밑 파고 숨는 중 (" + Math.max(0, depth) + "/3)";
            if (ticks > 20 * 12 || Math.abs(ft.getX() - cell.getX()) + Math.abs(ft.getZ() - cell.getZ()) > 0) {
                Act.stopDig(bot);
                active = false;
                return false;
            }
            if (!p.onGround()) return true;
            if (depth >= 3) {
                BlockPos cv = cell.below();
                int r = Placer.placeAt(bot, cv, Inv.throwaway());
                if (r == 1) {
                    phase = 1;
                    Act.stopDig(bot);
                    reflex.doing = "구멍 속에서 회복 중";
                } else if (r == -1 && ticks > 20 * 10) active = false;
                return true;
            }
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 60f;
            Act.dig(bot, ft.below());
            return true;
        }
        if (phase == 1) {
            reflex.forceEat = true;
            boolean angry = false;
            for (Entity e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.EnderMan em && em.isCreepy() && em.distanceTo(p) < 20) angry = true;
            boolean night = net.damoon.bot.task.BedTask.night(l);
            boolean foeNear = false;
            for (Entity e : l.entitiesForRendering()) if (Reflex.hostile(e) && e.distanceTo(p) < 10) { foeNear = true; break; }
            // 먹을 게 없으면 체력이 안 차니 낮이고 주변이 조용하면 나가서 먹을 것을 구함 (밤엔 아침까지 안에서 버팀)
            boolean hopeless = !canHeal(p) && ticks > 20 * 8 && !night && !foeNear && !(angry && ticks < 20 * 60);
            boolean timeout = ticks > 20 * 90 && (canHeal(p) || !night || ticks > 20 * 60 * 8);
            if (((p.getHealth() >= 18 && p.getFoodData().getFoodLevel() >= 17) && !(angry && ticks < 20 * 60)) || timeout || hopeless) {
                phase = 2;
                reflex.forceEat = false;
                // 나오자마자 다시 갇히지 않게 잠시 쉼 (그 사이 먹을 것을 구함)
                cooldownUntil = bot.ticks + 20 * 60;
                bot.log("회복 완료 (체력 " + (int) p.getHealth() + ") → 덮개를 부수고 나감");
            } else {
                reflex.doing = "구멍 속에서 회복 중 (체력 " + (int) p.getHealth() + ")";
                return reflex.startEatIfNeeded() || true;
            }
        }
        // 덮개 부수기 → 나머지는 길찾기가 기둥 쌓기로 올라감
        BlockPos cv = cell.below();
        var cs = l.getBlockState(cv);
        if (!cs.isAir() && BlockUtil.breakable(l, cv.getX(), cv.getY(), cv.getZ(), cs) && ticks < 20 * 130) {
            Act.dig(bot, cv);
            reflex.doing = "덮개 부수는 중";
            return true;
        }
        Act.stopDig(bot);
        active = false;
        return false;
    }

    /** 이번 틱을 썼으면 true. heal 단계에서는 먹기를 위해 Reflex가 이어서 처리 */
    public boolean tick(Bot bot, Reflex reflex, Entity near) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        // 근접 몹이 바로 붙어 있으면 (그 칸엔 벽을 못 놓고, 짓는 동안 맞음) 먼저 방패로 밀어내고 나서 지음
        boolean engaged = near != null && near.distanceTo(p) < 3.8;
        // 블레이즈가 바로 옆이면 벽 놓는 사이 칼 맞듯 근접 공격을 연달아 맞음 → 감싸지 않음 (방패 들고 물러남)
        for (Entity e : l.entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.monster.Blaze b && b.isAlive() && b.distanceTo(p) < 3.5) { engaged = true; break; }
        if (!active) {
            if (bot.ticks < cooldownUntil || !danger(bot, p) || !p.onGround() || p.isInLava()) return false;
            // 용암 옆에선 감싸지 않음 (벽 놓는 사이 불에 타 죽음) — 먼저 벗어남
            if (BlockUtil.fluidNear(l, Placer.standCell(p).getX(), Placer.standCell(p).getY(), Placer.standCell(p).getZ(), true)
                    || BlockUtil.isLava(l.getBlockState(Placer.standCell(p).below()))) return false;
            // 엔드에서 드래곤이 근처에 있으면 감싸지 않음 (브레스는 벽을 뚫고 들어옴)
            if (l.dimension() == net.minecraft.world.level.Level.END) {
                for (Entity e : l.entitiesForRendering())
                    if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dr && dr.isAlive() && dr.distanceTo(p) < 40) return false;
            }
            BlockPos f0 = Placer.standCell(p);
            // 적이 이미 붙어 있으면 벽은 못 지으니 발밑을 파고 들어감 (2칸만 내려가도 몹 팔이 안 닿음)
            boolean useHole = false; // 구멍 숨기는 몹이 같이 떨어져 들어와서 끔 (holeOk 참고)
            if (engaged && !useHole) return false;
            if (!useHole && Inv.count(p, Inv.throwaway()) < 9) return false;
            active = true;
            hole = useHole;
            cell = f0;
            phase = 0;
            ticks = 0;
            bot.nav.stop();
            Act.stopDig(bot);
            bot.log("위험 (체력 " + (int) p.getHealth() + ") → " + (hole ? "적이 붙어 있어 발밑 3칸 파고 들어가 덮고 회복" : "블록으로 몸을 감싸고 회복"));
        }
        ticks++;
        openWalls = countOpen(l);
        if (hole) return holeTick(bot, reflex, p, l);
        // 회복 중인데 벽에 빈틈이 생겼으면 (못 놓았거나 부서짐) 다시 막음, 2초 안에 못 막으면 포기
        if (phase == 1 && openWalls > 0) {
            if (++fixTicks > 40) {
                StringBuilder sb = new StringBuilder();
                for (BlockPos w : walls()) if (l.getBlockState(w).canBeReplaced()) sb.append(w.toShortString()).append(' ');
                bot.dbg("벽 빈틈: " + sb + " 내 위치 " + String.format("%.2f %.2f", p.getX(), p.getZ()) + " 칸 " + cell.toShortString());
                bot.log("벽 빈틈을 못 막음 → 감싸기 포기 (10초 동안 방패로)");
                active = false;
                cooldownUntil = bot.ticks + 200;
                reflex.forceEat = false;
                return false;
            }
            for (BlockPos w : walls()) {
                if (!l.getBlockState(w).canBeReplaced()) continue;
                int r = Placer.placeAt(bot, w, Inv.wall(p));
                if (r == -1) continue;
                reflex.doing = "벽 빈틈 막는 중";
                return true;
            }
            return false;
        }
        fixTicks = 0;
        if (engaged && phase == 0) { if (ticks > 20 * 30) active = false; return false; }
        if (!PathExec.feet(p).equals(cell) && !Placer.standCell(p).equals(cell) && phase < 2) {
            // 밀려났으면 다시 그 자리로
            Vec3 c = Vec3.atBottomCenterOf(cell);
            bot.ctl.lookYaw(p, c, 30f);
            bot.ctl.fwd = true;
            if (ticks > 200) { active = false; }
            return true;
        }
        if (phase == 0) {
            reflex.doing = "블록으로 몸 감싸는 중";
            double dx = cell.getX() + 0.5 - p.getX(), dz = cell.getZ() + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 0.03 && ticks < 60) {
                bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(cell), 60f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                return true;
            }
            for (BlockPos w : walls()) {
                if (!l.getBlockState(w).canBeReplaced() && !BlockUtil.vine(l.getBlockState(w))) continue;
                int r = Placer.placeAt(bot, w, Inv.wall(p));
                if (r == -1) continue;
                return true;
            }
            phase = 1;
            reflex.doing = "갇힌 채 회복 중";
            return true;
        }
        if (phase == 1) {
            reflex.forceEat = true;
            boolean angry = false;
            for (Entity e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.EnderMan em && em.isCreepy() && em.distanceTo(p) < 20) angry = true;
            // 화난 엔더맨이 밖에 있으면 화가 풀릴 때까지 안에서 기다림
            boolean night = net.damoon.bot.task.BedTask.night(l) && l.dimension() == net.minecraft.world.level.Level.OVERWORLD;
            boolean foeNear = false;
            for (Entity e : l.entitiesForRendering()) if (Reflex.hostile(e) && e.distanceTo(p) < 10) { foeNear = true; break; }
            // 먹을 게 없으면 갇혀 있어도 체력이 안 참: 오버월드 밤이 아니면 나가서 먹을 것을 구함 (밤엔 아침까지 버팀)
            boolean hopeless = !canHeal(p) && ticks > 20 * 8 && !night && !(angry && ticks < 20 * 60);
            boolean timeout = ticks > 20 * 90 && (canHeal(p) || !night || ticks > 20 * 60 * 8);
            if (((p.getHealth() >= 18 && p.getFoodData().getFoodLevel() >= 17) && !(angry && ticks < 20 * 60)) || timeout || hopeless) {
                phase = 2;
                reflex.forceEat = false;
                // 나오자마자 다시 갇히지 않게 잠시 쉼 (그 사이 먹을 것을 구하거나 할 일을 함)
                cooldownUntil = bot.ticks + 20 * (canHeal(p) ? 20 : 90);
                bot.log("회복 " + (hopeless ? "불가 (먹을 게 없음)" : "완료") + " (체력 " + (int) p.getHealth() + ") → 벽을 부수고 나감");
            } else {
                reflex.doing = "갇힌 채 회복 중 (체력 " + (int) p.getHealth() + ")";
                return reflex.startEatIfNeeded() || true;
            }
        }
        // 나가기: 용암 쪽이 아닌 벽 하나를 부숨
        for (Direction h : Direction.Plane.HORIZONTAL) {
            BlockPos head = cell.relative(h).above(), feet = cell.relative(h);
            BlockPos beyond = feet.relative(h);
            if (BlockUtil.isLava(l.getBlockState(beyond)) || BlockUtil.isLava(l.getBlockState(beyond.below()))) continue;
            for (BlockPos b : new BlockPos[]{head, feet}) {
                var s = l.getBlockState(b);
                if (!s.isAir() && BlockUtil.breakable(l, b.getX(), b.getY(), b.getZ(), s)) {
                    Act.dig(bot, b);
                    reflex.doing = "벽 부수고 나가는 중";
                    if (ticks > 20 * 120) active = false;
                    return true;
                }
            }
            Act.stopDig(bot);
            active = false;
            return false;
        }
        active = false;
        return false;
    }
}
