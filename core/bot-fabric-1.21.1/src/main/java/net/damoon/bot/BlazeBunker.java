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
    public String info = "";

    public BlazeBunker(BlockPos spawner) { this.spawner = spawner; }

    public BlockPos cell() { return cell; }

    /** 벙커 칸 후보: 스포너 2~3칸 옆, 단단한 바닥, 몸 들어갈 2칸, 스포너 쪽이 트여 있고 용암이 가깝지 않음 */
    public static BlockPos pick(ClientLevel l, LocalPlayer p, BlockPos sp, Direction[] outFront) {
        BlockPos best = null;
        double bs = 1e9;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    int ad = Math.max(Math.abs(dx), Math.abs(dz));
                    if (ad < 2 || ad > 3) continue;
                    if (dx != 0 && dz != 0 && Math.abs(dx) != Math.abs(dz) && Math.min(Math.abs(dx), Math.abs(dz)) > 1) continue;
                    BlockPos c = sp.offset(dx, dy, dz);
                    var fl = l.getBlockState(c.below());
                    if (!BlockUtil.floor(l, c.getX(), c.getY() - 1, c.getZ(), fl) || BlockUtil.isWater(fl)) continue;
                    if (!l.getBlockState(c).isAir() || !l.getBlockState(c.above()).isAir()) continue;
                    Direction f = Direction.getNearest(sp.getX() - c.getX(), 0, sp.getZ() - c.getZ());
                    BlockPos o = c.relative(f);
                    if (!l.getBlockState(o).isAir() || !l.getBlockState(o.above()).isAir()) continue;
                    if (lavaNear(l, c, 2)) continue;
                    double score = ad + Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(c))) * 0.05 + Math.abs(dy);
                    if (score < bs) { bs = score; best = c; outFront[0] = f; }
                }
        return best;
    }

    private static boolean lavaNear(ClientLevel l, BlockPos c, int r) {
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -1, -r), c.offset(r, 2, r)))
            if (BlockUtil.isLava(l.getBlockState(q))) return true;
        return false;
    }

    /** 막아야 할 칸들 (순서대로: 아래부터 쌓아 올리고 천장은 마지막) */
    private List<BlockPos> walls() {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y <= 2; y++)
            for (Direction h : Direction.Plane.HORIZONTAL) {
                if (h == front) continue;
                out.add(cell.relative(h).above(y));
            }
        out.add(cell.above(2));
        return out;
    }

    public R tick(Bot bot, int rods, int want) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (cell == null) {
            Direction[] f = new Direction[1];
            cell = pick(l, p, spawner, f);
            front = f[0];
            if (cell == null) return R.FAILED;
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
        if (!inCell) {
            info = phase == 0 ? "벙커 자리로 이동" : "벙커로 돌아가는 중";
            bot.reflex.camp = false;
            Nav.R r = bot.nav.go(new Goal.Block(cell));
            if (r == Nav.R.FAILED) { bot.nav.stop(); cell = null; phase = 0; return R.FAILED; }
            return R.RUNNING;
        }
        bot.nav.stop();
        if (phase == 0) phase = 1;
        double cx = cell.getX() + 0.5 - p.getX(), cz = cell.getZ() + 0.5 - p.getZ();
        if (phase == 1 && cx * cx + cz * cz > 0.02) {
            // 칸 가운데에 서야 벽 블록을 놓을 수 있음
            bot.ctl.lookYaw(p, new Vec3(cell.getX() + 0.5, p.getY(), cell.getZ() + 0.5), 60f);
            bot.ctl.fwd = true;
            bot.ctl.sneak = true;
            return R.RUNNING;
        }
        if (phase == 1) {
            if (++buildTicks > 20 * 60) { bot.log("벙커를 다 못 지음 → 그냥 싸움"); phase = 2; }
            for (BlockPos w : walls()) {
                if (!l.getBlockState(w).canBeReplaced()) continue;
                info = "벙커 짓는 중 (스포너 주변·천장 막기)";
                int r = Placer.placeAt(bot, w, Inv.throwaway());
                if (r == -1) continue;
                return R.RUNNING;
            }
            phase = 2;
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
