package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 블레이즈 벙커: 스포너 2~3칸 옆에 1칸짜리 방을 만들고 스포너 쪽만 열어 둠 (세 면 + 천장을 막음).
 * 블레이즈는 열린 쪽으로만 다가오고 화염구도 그쪽에서만 오므로 방패로 정면만 막고 칼로 벰.
 * 블레이즈가 없을 때 밖에 떨어진 막대를 주워 오고 다시 들어감.
 */
public final class BlazeBunker {
    public enum R { RUNNING, FAILED }

    private final BlockPos spawner;
    private BlockPos cell;          // 내가 설 칸 (발 위치)
    private Direction front;        // 스포너 쪽 (열린 면)
    private int phase;              // 0 자리로, 1 짓기, 2 싸우기
    private int buildTicks;
    private boolean sally;
    /** 벙커 없이 스포너 위·옆에서 방패+칼로 싸움 (블레이즈가 스폰되자마자 칼이 닿음) */
    private boolean open = false;
    private int sallyStart, lastGain, lastRods = -1, sallyRods = -1, rodTick, returnStart;
    public String info = "";

    public BlazeBunker(BlockPos spawner) { this.spawner = spawner; }

    /** 공격받는 그 자리에 바로 짓는 피난처 (front = 블레이즈 쪽) */
    public static BlazeBunker here(ClientLevel l, LocalPlayer p, BlockPos lookAt) {
        BlockPos c = PathExec.feet(p);
        var fl = l.getBlockState(c.below());
        if (!BlockUtil.floor(l, c.getX(), c.getY() - 1, c.getZ(), fl) || BlockUtil.isWater(fl)) return null;
        if (!l.getBlockState(c.above()).isAir() && !l.getBlockState(c.above()).canBeReplaced()) return null;
        if (lavaNear(l, c, 1)) return null;
        Direction f = Direction.getNearest(lookAt.getX() - c.getX(), 0, lookAt.getZ() - c.getZ());
        BlazeBunker b = new BlazeBunker(lookAt);
        b.cell = c;
        b.front = f;
        b.quick = true;
        return b;
    }

    /** 그 자리 피난처인지 (블레이즈가 없으면 나와서 다시 전진) */
    public boolean quick;
    private int calm;

    /** 블레이즈가 한동안 없으면 true (피난처를 떠나도 됨) */
    public boolean calmFor(Bot bot, int ticks) {
        boolean any = false;
        for (Entity e : bot.level().entitiesForRendering()) if (e instanceof Blaze b && b.isAlive() && b.distanceTo(bot.p()) < 20) { any = true; break; }
        calm = any ? 0 : calm + 1;
        return calm > ticks;
    }

    public BlockPos cell() { return cell; }

    /** 벙커 칸 후보: 스포너 10칸 안, 단단한 바닥, 몸 들어갈 2칸, 스포너 쪽이 트여 있고 용암이 가깝지 않음. 내 위치에서 가까운 곳 우선 */
    public static BlockPos pick(ClientLevel l, LocalPlayer p, BlockPos sp, Direction[] outFront) {
        BlockPos best = null;
        double bs = 1e9;
        BlockPos me = PathExec.feet(p);
        for (BlockPos q : BlockPos.betweenClosed(sp.offset(-10, -3, -10), sp.offset(10, 3, 10))) {
            BlockPos c = q.immutable();
            double ds = Math.sqrt(c.distSqr(sp));
            if (ds < 2 || ds > 10) continue;
            var fl = l.getBlockState(c.below());
            if (!BlockUtil.floor(l, c.getX(), c.getY() - 1, c.getZ(), fl) || BlockUtil.isWater(fl)) continue;
            if (!l.getBlockState(c).isAir() || !l.getBlockState(c.above()).isAir()) continue;
            Direction f = Direction.getNearest(sp.getX() - c.getX(), 0, sp.getZ() - c.getZ());
            BlockPos o = c.relative(f);
            if (!l.getBlockState(o).isAir() || !l.getBlockState(o.above()).isAir()) continue;
            if (lavaNear(l, c, 2)) continue;
            // 창문(앞쪽 머리 높이 한 칸)으로 스포너가 실제로 보여야 함 — 안 보이면 벽에 갇혀 아무것도 못 잡음
            if (!windowSees(l, p, c, f, sp)) continue;
            // 스포너 가까이(블레이즈가 생기는 곳 바로 옆) 우선: 생기자마자 칼이 닿음
            double score = Math.sqrt(c.distSqr(me)) * 0.25 + ds * 2.0 + Math.abs(c.getY() - sp.getY()) * 1.5;
            if (score < bs) { bs = score; best = c; outFront[0] = f; }
        }
        return best;
    }

    /** 칸 c에 섰을 때 눈에서 스포너(블레이즈가 뜨는 높이)까지의 선이 앞쪽 창문 칸으로 나가고, 그 뒤로 막힌 블록이 없는지 */
    static boolean windowSees(ClientLevel l, LocalPlayer p, BlockPos c, Direction f, BlockPos sp) {
        // 지을 벽(앞쪽 머리 높이 창문만 열림 + 천장)을 미리 셈해서, 눈에서 스포너 위(블레이즈가 뜨는 곳)까지 선이 벽·땅에 안 걸리는지
        java.util.Set<BlockPos> wall = new java.util.HashSet<>();
        for (int y = 0; y <= 2; y++)
            for (Direction h : Direction.Plane.HORIZONTAL) {
                if (h == f && y == 1) continue;
                wall.add(c.relative(h).above(y));
            }
        wall.add(c.above(2));
        Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
        int ok = 0;
        for (double ty : new double[]{1.0, 1.8}) {
            Vec3 to = new Vec3(sp.getX() + 0.5, sp.getY() + ty, sp.getZ() + 0.5);
            Vec3 d = to.subtract(eye);
            double len = d.length();
            boolean clear = true;
            for (double s = 0.1; s < len - 0.3; s += 0.1) {
                Vec3 q = eye.add(d.scale(s / len));
                BlockPos b = BlockPos.containing(q);
                if (b.equals(c) || b.equals(c.above())) continue;
                if (wall.contains(b)) { clear = false; break; }
                var st = l.getBlockState(b);
                if (!b.equals(sp) && !st.getCollisionShape(l, b).isEmpty()) { clear = false; break; }
            }
            if (clear) ok++;
        }
        return ok == 2;
    }

    private static boolean lavaNear(ClientLevel l, BlockPos c, int r) {
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -1, -r), c.offset(r, 2, r)))
            if (BlockUtil.isLava(l.getBlockState(q))) return true;
        return false;
    }

    /** 막아야 할 칸들 (순서대로: 아래부터 쌓아 올리고 천장은 마지막) */
    private List<BlockPos> walls() {
        List<BlockPos> out = new ArrayList<>();
        // 앞쪽은 머리 높이 창문 하나만 남김 (발 높이·윗칸은 막음) → 다른 방향 화염구는 벽이 막고 블레이즈는 못 들어옴
        for (int y = 0; y <= 2; y++)
            for (Direction h : Direction.Plane.HORIZONTAL) {
                if (h == front && y == 1) continue;
                out.add(cell.relative(h).above(y));
            }
        out.add(cell.above(2));
        return out;
    }

    private int openFails;
    private boolean announced;

    /** 스포너 위에 서서 싸움: 스폰되는 블레이즈는 바로 칼이 닿고, 멀리 뜬 건 반사 행동이 활·접근으로 처리 */
    private R openFight(Bot bot, LocalPlayer p, ClientLevel l, List<Entity> blazes, int rods, int want) {
        bot.reflex.camp = false;
        bot.reflex.building = false;
        bot.reflex.forceEat = p.getHealth() < 16;
        // 떨어진 막대 줍기 (가까운 블레이즈가 없을 때)
        boolean close = false;
        for (Entity e : blazes) if (e.distanceTo(p) < 5) { close = true; break; }
        if (!close) {
            for (Entity e : l.entitiesForRendering()) {
                if (e instanceof ItemEntity ie && ie.getItem().is(Items.BLAZE_ROD) && ie.distanceTo(p) < 12) {
                    info = "막대 줍는 중 (" + rods + "/" + want + ")";
                    if (ie.distanceTo(p) < 2.5) { bot.nav.stop(); bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; }
                    else { Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0)); if (r == Nav.R.FAILED) bot.nav.stop(); }
                    return R.RUNNING;
                }
                if (e instanceof net.minecraft.world.entity.projectile.AbstractArrow a && a.getOwner() == p && a.getDeltaMovement().lengthSqr() < 0.001
                        && a.distanceTo(p) < 8 && a.pickup == net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED) {
                    info = "땅에 꽂힌 화살 줍는 중";
                    Nav.R r = bot.nav.go(new Goal.Near(a.blockPosition(), 0.8));
                    if (r == Nav.R.FAILED) bot.nav.stop();
                    return R.RUNNING;
                }
            }
        }
        info = "스포너 위에서 방패+칼로 블레이즈 잡는 중 (" + rods + "/" + want + ")";
        BlockPos top = spawner.above();
        boolean onTop = PathExec.feet(p).equals(top);
        if (!onTop && openFails < 3) {
            Nav.R r = bot.nav.go(new Goal.Block(top));
            if (r == Nav.R.FAILED) { bot.nav.stop(); openFails++; }
            return R.RUNNING;
        }
        if (!onTop && p.distanceToSqr(Vec3.atCenterOf(spawner)) > 3.0 * 3.0) {
            Nav.R r = bot.nav.go(new Goal.Near(spawner, 2.5));
            if (r == Nav.R.FAILED) bot.nav.stop();
            return R.RUNNING;
        }
        bot.nav.stop();
        // 블레이즈가 없으면 스포너 쪽을 보고 방패를 든 채 기다림
        bot.ctl.use = p.getOffhandItem().is(Items.SHIELD);
        if (onTop) bot.ctl.sneak = true; // 스포너 위에서 떨어지지 않게
        return R.RUNNING;
    }

    public R tick(Bot bot, int rods, int want) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (open && !quick) {
            List<Entity> bl = new ArrayList<>();
            for (Entity e : l.entitiesForRendering()) if (e instanceof Blaze b && b.isAlive() && b.distanceTo(p) < 16) bl.add(e);
            if (!announced) { announced = true; bot.log("블레이즈 스포너 " + spawner.toShortString() + " → 스포너 위에서 방패+칼로 싸움"); }
            return openFight(bot, p, l, bl, rods, want);
        }
        if (cell == null) {
            Direction[] f = new Direction[1];
            cell = pick(l, p, spawner, f);
            front = f[0];
            if (cell == null) {
                // 창문으로 스포너가 보이는 벙커 자리가 없음 → 스포너 위에서 방패+칼
                open = true;
                return R.RUNNING;
            }
            bot.log("블레이즈 벙커 자리: " + cell.toShortString() + " (스포너 쪽 열어 둠)");
        }
        BlockPos feet = PathExec.feet(p);
        boolean inCell = feet.equals(cell);
        List<Entity> blazes = new ArrayList<>();
        for (Entity e : l.entitiesForRendering()) if (e instanceof Blaze b && b.isAlive() && b.distanceTo(p) < 16) blazes.add(e);

        // 블레이즈가 없을 때 밖에 떨어진 막대 줍기
        if (phase == 2 && blazes.isEmpty()) {
            for (Entity e : l.entitiesForRendering()) {
                if (e instanceof ItemEntity ie && ie.getItem().is(Items.BLAZE_ROD) && ie.distanceTo(p) < 10) {
                    info = "블레이즈가 없는 틈에 막대 줍는 중 (" + rods + "/" + want + ")";
                    bot.reflex.camp = false;
                    if (ie.distanceTo(p) < 2.5) { bot.nav.stop(); bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; }
                    else { Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0)); if (r == Nav.R.FAILED) bot.nav.stop(); }
                    return R.RUNNING;
                }
            }
        }
        // 오래 막대가 안 늘거나 화살이 떨어지면: 잠깐 밖으로 나가 스포너 옆에서 방패 들고 칼로 싸우고, 땅에 꽂힌 화살도 주움
        if (rods != lastRods) { lastRods = rods; lastGain = bot.ticks; rodTick = bot.ticks; }
        if (rodTick == 0) rodTick = bot.ticks;
        // 벙커에서 3분 넘게 막대가 하나도 안 늘면 (블레이즈가 멀리 떠서 쏘기만 함) → 스포너 위에 서서
        // 새로 생기는 블레이즈를 생기자마자 칼로 벰 (갓 생긴 블레이즈는 바로 옆이라 칼이 닿음)
        if (!quick && phase != 0 && !sally && bot.ticks - rodTick > 20 * 60 * 3 && p.getHealth() >= 14
                && Inv.count(p, Items.SHIELD) >= 1) {
            open = true;
            bot.reflex.camp = false;
            bot.reflex.building = false;
            bot.log("벙커에서 3분 동안 막대가 안 늘어남 → 스포너 위에 서서 생기는 블레이즈를 바로 벰");
            return R.RUNNING;
        }
        boolean noArrows = !Inv.has(p, Items.ARROW);
        if (!sally && phase == 2 && p.getHealth() >= 16 && (bot.ticks - lastGain > 20 * 45 || (noArrows && bot.ticks - lastGain > 20 * 15))) {
            sally = true;
            sallyStart = bot.ticks;
            bot.log("벙커에서 막대가 안 늘어남 → 밖으로 나가 스포너 옆에서 칼로 싸움");
        }
        if (sally) {
            if (p.getHealth() < 12 || bot.ticks - sallyStart > 20 * 40 || rods != sallyRods && rods >= want) {
                sally = false;
                lastGain = bot.ticks;
                phase = 1; // 나가면서 부순 벽을 다시 막음
                buildTicks = 0;
                bot.log("벙커로 돌아감 (체력 " + (int) p.getHealth() + ")");
            } else {
                if (sallyRods < 0) sallyRods = rods;
                bot.reflex.camp = false;
                // 땅에 꽂힌 내 화살 줍기
                for (Entity e : l.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.projectile.AbstractArrow a && a.getOwner() == p && a.getDeltaMovement().lengthSqr() < 0.001
                            && a.distanceTo(p) < 10 && a.pickup == net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED) {
                        info = "땅에 꽂힌 화살 줍는 중";
                        Nav.R r = bot.nav.go(new Goal.Near(a.blockPosition(), 0.8));
                        if (r == Nav.R.FAILED) bot.nav.stop();
                        return R.RUNNING;
                    }
                }
                // 블레이즈는 나를 볼 수 있으면 다가오지 않고 떠서 쏘기만 함 → 칼이 닿게 내가 그 아래로 감
                Blaze tgt = null;
                double best = 1e9;
                for (Entity e : blazes) {
                    if (!(e instanceof Blaze b) || e.getY() > p.getY() + 3.2 || e.getY() < p.getY() - 2.5 || !p.hasLineOfSight(e)) continue;
                    double s = e.distanceTo(p) + b.getHealth() * 0.3;
                    if (s < best) { best = s; tgt = b; }
                }
                if (tgt != null && tgt.distanceTo(p) > 2.6) {
                    info = "블레이즈 밑으로 다가가 칼로 벰 (" + rods + "/" + want + ")";
                    Nav.R r = bot.nav.go(new Goal.NearXZ(tgt.getBlockX(), tgt.getBlockZ(), 2));
                    if (r != Nav.R.RUNNING) { bot.nav.stop(); bot.ctl.lookAt(p, tgt.getEyePosition()); bot.ctl.use = p.getOffhandItem().is(Items.SHIELD); }
                    return R.RUNNING;
                }
                info = "스포너 옆에서 방패 들고 블레이즈와 싸우는 중 (" + rods + "/" + want + ")";
                Nav.R r = bot.nav.go(new Goal.Near(spawner, 2.5));
                if (r != Nav.R.RUNNING) { bot.nav.stop(); bot.ctl.lookAt(p, Vec3.atCenterOf(spawner).add(0, 1, 0)); bot.ctl.use = p.getOffhandItem().is(Items.SHIELD); }
                return R.RUNNING;
            }
        }
        sallyRods = -1;
        if (!inCell) {
            info = phase == 0 ? "벙커 자리로 이동" : "벙커로 돌아가는 중";
            bot.reflex.camp = false;
            // 블레이즈 화염구를 막느라 45초 넘게 벙커로 못 들어가면 → 스포너 위에서 싸움
            if (!quick) {
                if (returnStart == 0) returnStart = bot.ticks;
                if (bot.ticks - returnStart > 20 * (phase == 0 ? 60 : 45)) {
                    open = true;
                    returnStart = 0;
                    bot.log("45초 동안 벙커로 못 돌아감 → 스포너 위에 서서 생기는 블레이즈를 바로 벰");
                    return R.RUNNING;
                }
            }
            Nav.R r = bot.nav.go(new Goal.Block(cell));
            if (r == Nav.R.FAILED) { bot.nav.stop(); cell = null; phase = 0; return R.FAILED; }
            return R.RUNNING;
        }
        bot.nav.stop();
        returnStart = 0;
        if (phase == 0) phase = 1;
        double cx = cell.getX() + 0.5 - p.getX(), cz = cell.getZ() + 0.5 - p.getZ();
        if (phase == 1 && cx * cx + cz * cz > 0.02) {
            // 칸 가운데에 서야 벽 블록을 놓을 수 있음
            bot.ctl.lookYaw(p, new Vec3(cell.getX() + 0.5, p.getY(), cell.getZ() + 0.5), 60f);
            bot.ctl.fwd = true;
            bot.ctl.sneak = true;
            return R.RUNNING;
        }
        bot.reflex.building = phase == 1;
        if (phase == 1) {
            if (++buildTicks > 20 * 60) { bot.log("벙커를 다 못 지음 → 그냥 싸움"); phase = 2; }
            for (BlockPos w : walls()) {
                if (!l.getBlockState(w).canBeReplaced()) continue;
                info = "벙커 짓는 중 (스포너 주변·천장 막기)";
                int r = Placer.placeAt(bot, w, Inv.wall(p));
                if (r == -1) continue;
                return R.RUNNING;
            }
            phase = 2;
            bot.reflex.building = false;
            bot.log("벙커 완성 → 열린 쪽에서 오는 블레이즈만 상대");
        }
        // 싸우기: 칸 가운데에 서서 스포너 쪽을 봄. 실제 공격·방패는 반사 행동(camp)이 함
        bot.reflex.camp = true;
        bot.reflex.forceEat = p.getHealth() < 16;
        double dx = cell.getX() + 0.5 - p.getX(), dz = cell.getZ() + 0.5 - p.getZ();
        if (dx * dx + dz * dz > 0.04) {
            bot.ctl.lookYaw(p, new Vec3(cell.getX() + 0.5, p.getY(), cell.getZ() + 0.5), 0f);
            bot.ctl.fwd = true;
            bot.ctl.sneak = true;
        } else {
            bot.ctl.lookAt(p, Vec3.atCenterOf(spawner).add(0, 0.5, 0));
        }
        info = "벙커 안에서 블레이즈 기다리는 중 (" + rods + "/" + want + ")";
        return R.RUNNING;
    }
}
