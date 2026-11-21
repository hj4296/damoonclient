package net.damoon.bot;

import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 좁은 수직 굴(예: 죽은 뒤 되살아난 자리가 예전에 판 굴 바닥)에 갇혔을 때 빠져나가기.
 * 블록이 충분하면 발밑에 쌓아 올라가고, 모자라면 계단을 파서 올라감 (곡괭이가 없으면 맨손으로).
 */
public final class PitEscape {
    private Direction dir;
    private int ticks, stepTicks, turns;
    private BlockPos lastFeet, stepTo;
    public String info = "";
    /** 구덩이가 아니어도 바로 계단을 파서 땅 위까지 올라감 (땅속 깊은 곳에서 길찾기가 너무 오래 걸릴 때) */
    public boolean force;

    private static boolean pass(ClientLevel l, BlockPos b) {
        return BlockUtil.passable(l, b.getX(), b.getY(), b.getZ(), l.getBlockState(b));
    }

    /** 옆 칸 기둥에서 걸어 나갈 수 있는 높이 (발 높이 기준). 0이면 바로 걸어 나감 */
    static int exitHeight(ClientLevel l, BlockPos c) {
        for (int k = 0; k < 48; k++)
            if (pass(l, c.above(k)) && pass(l, c.above(k + 1)) && (k == 0 || !pass(l, c.above(k - 1)))) return k;
        return 48;
    }

    /** 사방이 막힌 구덩이 깊이 (가장 낮은 옆 출구 높이) */
    public static int depth(ClientLevel l, BlockPos f) {
        int m = 99;
        for (Direction d : Direction.Plane.HORIZONTAL) m = Math.min(m, exitHeight(l, f.relative(d)));
        return m;
    }

    /** 하늘이 보이는 좁은 굴 바닥에 있고, 걸어서(한 칸 오르기 포함) 못 나가는지 */
    public static boolean trapped(Bot bot) {
        LocalPlayer p = bot.p();
        if (!p.onGround() || p.isInWater()) return false;
        ClientLevel l = bot.level();
        BlockPos f = PathExec.feet(p);
        if (!l.canSeeSky(f.above())) return false;
        return depth(l, f) >= 2;
    }

    /** 계속 진행 중이면 true, 빠져나왔거나 포기하면 false */
    public boolean tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        BlockPos f = p.onGround() ? Placer.standCell(p) : PathExec.feet(p);
        ticks++;
        if (ticks > 20 * 60 * 15) { info = "포기"; return false; }
        // 뛰면서 위아래로 출렁여도 진전으로 치지 않음: 옆으로 옮겼거나 더 높이 올라갔을 때만
        if (lastFeet == null || f.getX() != lastFeet.getX() || f.getZ() != lastFeet.getZ() || f.getY() > lastFeet.getY()) { lastFeet = f; stepTicks = 0; } else stepTicks++;
        // nav.stop()은 캐던 블록도 멈추게 하므로 (진행이 초기화됨) 길찾기가 돌고 있을 때만
        if (bot.nav.goal() != null) bot.nav.stop();
        int blocks = Inv.count(p, Inv.throwaway());
        if (dir == null) {
            int d0 = force ? 99 : depth(l, f);
            if (d0 <= 1) { info = "빠져나옴"; return false; }
            if (!force && d0 - 1 <= blocks && headroom(l, f, d0 + 1)) {
                return pillar(bot, p, l, f, d0);
            }
            dir = pickDir(l, f);
            bot.log("구덩이 깊이 " + d0 + "칸, 블록 " + blocks + "개 → " + dirName(dir) + " 쪽으로 계단을 파서 올라감");
        }
        // 앞쪽 땅 높이까지 올라왔으면 끝
        BlockPos ahead = f.relative(dir);
        int surf = l.getHeight(Heightmap.Types.MOTION_BLOCKING, ahead.getX(), ahead.getZ());
        if (surf <= f.getY() + 1 && p.onGround() && l.canSeeSky(f.above())) { info = "땅 위로 나옴"; Act.stopDig(bot); return false; }
        if (stepTicks > 20 * 50) { turn(bot, "한 계단이 너무 오래 걸림"); stepTicks = 0; if (turns > 8) return false; return true; }
        if (!p.onGround() && !p.isInWater()) {
            // 뛰는 중에도 계속 앞으로 (손을 떼면 제자리에서 뛰기만 함)
            if (stepTo != null) { bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(stepTo), 30f); bot.ctl.fwd = true; }
            return true;
        }
        stepTo = null;
        // 계단 칸 가운데에 먼저 섬 (걸쳐 있으면 다음 발판 칸에 몸이 겹쳐 '발판 없음'으로 착각함)
        double cx = f.getX() + 0.5 - p.getX(), cz = f.getZ() + 0.5 - p.getZ();
        if (cx * cx + cz * cz > 0.22 * 0.22) {
            info = "계단 가운데로";
            Act.stopDig(bot);
            bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(f), 40f);
            bot.ctl.fwd = true;
            bot.ctl.sneak = true;
            return true;
        }
        BlockPos floor = f.relative(dir);
        BlockPos next = floor.above();
        BlockPos[] dig = {f.above(2), next.above(), next};
        for (BlockPos c : dig) {
            if (pass(l, c)) continue;
            var s = l.getBlockState(c);
            // 용암뿐 아니라 물도 피함 (물이 굴로 쏟아지면 익사)
            if (!BlockUtil.breakable(l, c.getX(), c.getY(), c.getZ(), s) || BlockUtil.fluidNear(l, c.getX(), c.getY(), c.getZ(), false)) {
                turn(bot, "못 캐는 블록/용암·물");
                return turns <= 8;
            }
            info = "계단 파는 중 (y " + f.getY() + " → " + surf + ")";
            if (!Act.dig(bot, c)) { turn(bot, "손이 안 닿음"); return turns <= 8; }
            return true;
        }
        Act.stopDig(bot);
        if (pass(l, floor)) {
            // 앞이 동굴로 뚫려 발판이 없음 → 블록을 놓거나 방향 바꿈
            int r = Placer.placeAt(bot, floor, Inv.throwaway());
            if (r == -1) {
                // 뚫린 동굴이라 앞에 디딜 곳을 못 만들면 발밑에 쌓아 한 칸 올라감
                if (force && blocks > 0 && pass(l, f.above(2))) return pillar(bot, p, l, f, 2);
                turn(bot, "발판이 없음");
                return turns <= 8;
            }
            return true;
        }
        info = "한 계단 오르는 중 (y " + f.getY() + " → " + surf + ")";
        bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(next), 30f);
        bot.ctl.fwd = true;
        // 앞을 보고 난 뒤에 뜀
        float want = Ctl.angles(p.getEyePosition(), Vec3.atBottomCenterOf(next))[0];
        if (p.onGround() && Math.abs(net.minecraft.util.Mth.wrapDegrees(want - p.getYRot())) < 20) { bot.ctl.jump = true; stepTo = next; }
        return true;
    }

    private static boolean headroom(ClientLevel l, BlockPos f, int h) {
        for (int k = 1; k <= h; k++) if (!pass(l, f.above(k))) return false;
        return true;
    }

    /** 발밑에 블록을 쌓아 올라감 (기둥 쌓기) */
    private boolean pillar(Bot bot, LocalPlayer p, ClientLevel l, BlockPos f, int d0) {
        info = "블록을 쌓아 올라가는 중 (" + d0 + "칸)";
        if (stepTicks > 20 * 10) { dir = pickDir(l, f); bot.log("쌓아 올라가기가 막힘 → 계단 파기"); return true; }
        Inv.select(p, Inv.throwaway());
        bot.ctl.look(p.getYRot(), 90f);
        bot.ctl.speed = 60f;
        if (p.getXRot() < 70f) return true;
        if (p.onGround()) bot.ctl.jump = true;
        // 뛰어오른 뒤 원래 서 있던 칸이 비면 그 아래 블록 윗면에 놓음 (기둥 쌓기와 같은 방식)
        BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
        if (!p.onGround() && pass(l, under) && !pass(l, under.below()) && p.getY() > under.getY() + 1.05)
            Act.place(bot, under.below(), Direction.UP);
        return true;
    }

    /** 땅이 가장 낮고 용암·물이 없는 쪽 */
    private static Direction pickDir(ClientLevel l, BlockPos f) {
        Direction best = Direction.NORTH;
        double bs = 1e9;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            double s = 0;
            for (int k = 1; k <= 6; k++) {
                BlockPos c = f.relative(d, k);
                s += l.getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
            }
            for (int k = 1; k <= 3; k++) {
                BlockPos c = f.relative(d, k).above(k);
                if (BlockUtil.fluidNear(l, c.getX(), c.getY(), c.getZ(), true)) s += 500;
                else if (BlockUtil.fluidNear(l, c.getX(), c.getY(), c.getZ(), false)) s += 60;
            }
            if (s < bs) { bs = s; best = d; }
        }
        return best;
    }

    private void turn(Bot bot, String why) {
        dir = dir.getClockWise();
        turns++;
        bot.log("계단 방향 바꿈 (" + why + ") → " + dirName(dir));
    }

    private static String dirName(Direction d) {
        return switch (d) { case NORTH -> "북"; case SOUTH -> "남"; case EAST -> "동"; default -> "서"; };
    }
}
