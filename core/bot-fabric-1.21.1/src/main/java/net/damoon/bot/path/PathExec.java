package net.damoon.bot.path;

import net.damoon.bot.Act;
import net.damoon.bot.BlockUtil;
import net.damoon.bot.Bot;
import net.damoon.bot.Ctl;
import net.damoon.bot.Inv;
import net.damoon.bot.path.PathFinder.Move;
import net.damoon.bot.path.PathFinder.Node;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 찾은 길을 실제 키 입력으로 따라감: 걷기, 점프, 블록 캐기, 다리 놓기, 기둥 쌓기 */
public final class PathExec {
    public enum R { RUNNING, DONE, FAILED }

    public final List<Node> path;
    private int i = 0;
    private int moveTicks, stuckChecks;
    private Vec3 lastPos;
    public String why = "";

    public PathExec(List<Node> path) {
        this.path = path;
    }

    public int index() { return i; }
    public Node target() { return i + 1 < path.size() ? path.get(i + 1) : path.get(path.size() - 1); }

    public static BlockPos feet(LocalPlayer p) {
        BlockPos b = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
        Level l = p.level();
        if (!BlockUtil.passable(l, b.getX(), b.getY(), b.getZ(), l.getBlockState(b)) && !BlockUtil.isPortal(l.getBlockState(b))) b = b.above();
        return b;
    }

    private static boolean settled(LocalPlayer p) {
        return p.onGround() || p.isInWater() || p.onClimbable();
    }

    private static boolean at(BlockPos f, Node n) {
        return f.getX() == n.x && f.getY() == n.y && f.getZ() == n.z;
    }

    private static double hdist(LocalPlayer p, Node n) {
        double dx = n.x + 0.5 - p.getX(), dz = n.z + 0.5 - p.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public R tick(Bot bot) {
        bot.trace += " exec";
        bot.pathDriving = true;
        LocalPlayer p = bot.p();
        Level l = p.level();
        Ctl c = bot.ctl;
        BlockPos f = feet(p);

        // 도착 확인 (앞의 몇 칸까지 건너뛰기 허용)
        for (int j = Math.min(path.size() - 1, i + 4); j > i; j--) {
            Node n = path.get(j);
            if (at(f, n) && settled(p)) {
                boolean last = j == path.size() - 1;
                boolean turn = j + 1 < path.size() && dirChanges(n, path.get(j + 1));
                if (last || !turn || hdist(p, n) < 0.45 || n.move == Move.DESCEND) {
                    i = j;
                    moveTicks = 0;
                    stuckChecks = 0;
                }
                break;
            }
        }
        if (i >= path.size() - 1) {
            Act.stopDig(bot);
            return R.DONE;
        }
        Node from = path.get(i), to = path.get(i + 1);

        double df = Math.sqrt(p.distanceToSqr(from.x + 0.5, from.y, from.z + 0.5));
        double dt = Math.sqrt(p.distanceToSqr(to.x + 0.5, to.y, to.z + 0.5));
        if (df > 3.2 && dt > 3.2) { why = "길에서 벗어남"; return R.FAILED; }
        if (++moveTicks > 20 * 20) { why = "한 칸에 너무 오래 걸림"; return R.FAILED; }

        // 1) 막힌 블록 캐기
        for (long k : to.breaks) {
            BlockPos b = BlockPos.of(k);
            BlockState s = l.getBlockState(b);
            if (!BlockUtil.passable(l, b.getX(), b.getY(), b.getZ(), s) && !(BlockUtil.isPortal(s))) {
                if (BlockUtil.isLava(s) || !BlockUtil.breakable(l, b.getX(), b.getY(), b.getZ(), s)) { why = "못 캐는 블록"; return R.FAILED; }
                if (!Act.dig(bot, b)) { why = "손이 안 닿음"; return R.FAILED; }
                return R.RUNNING;
            }
        }
        if (to.move != Move.PILLAR && to.move != Move.DIG_DOWN) {
            for (BlockPos b : new BlockPos[]{to.pos().above(), to.pos()}) {
                BlockState s = l.getBlockState(b);
                if (!BlockUtil.passable(l, b.getX(), b.getY(), b.getZ(), s) && !BlockUtil.isPortal(s)) {
                    if (BlockUtil.isLava(s) || !BlockUtil.breakable(l, b.getX(), b.getY(), b.getZ(), s)) { why = "막힘"; return R.FAILED; }
                    if (!Act.dig(bot, b)) { why = "막힌 블록에 손이 안 닿음"; return R.FAILED; }
                    return R.RUNNING;
                }
            }
        }
        // 한 칸 오르기가 계속 안 되면 (점프할 때 머리가 위 블록에 걸림) 그 블록도 캠
        if (to.move == Move.ASCEND && moveTicks > 40) {
            for (BlockPos b : new BlockPos[]{to.pos().above(2), from.pos().above(3)}) {
                BlockState s = l.getBlockState(b);
                if (!BlockUtil.passable(l, b.getX(), b.getY(), b.getZ(), s) && BlockUtil.breakable(l, b.getX(), b.getY(), b.getZ(), s)
                        && !BlockUtil.fluidNear(l, b.getX(), b.getY(), b.getZ(), false)) {
                    if (Act.dig(bot, b)) return R.RUNNING;
                }
            }
        }
        Act.stopDig(bot);

        // 2) 움직이기
        switch (to.move) {
            case PILLAR -> {
                if (hdist(p, from) > 0.08 && p.onGround()) {
                    center(bot, from);
                    return R.RUNNING;
                }
                c.look(p.getYRot(), 90f);
                c.sneak = true;
                if (p.getXRot() < 70f) return R.RUNNING;
                if (p.onGround() && at(f, from)) c.jump = true;
                BlockPos at = from.pos();
                if (!p.onGround() && p.getY() > from.y + 1.08 && BlockUtil.passable(l, at.getX(), at.getY(), at.getZ(), l.getBlockState(at))) {
                    if (!Act.place(bot, at.below(), Direction.UP)) { why = "쌓을 블록 없음"; return R.FAILED; }
                }
            }
            case DIG_DOWN -> {
                // 바닥은 위에서 캤음 — 떨어지는 중
                c.look(p.getYRot(), 90f);
            }
            case BRIDGE -> {
                BlockPos fl = BlockPos.of(to.place);
                BlockState fs = l.getBlockState(fl);
                if (BlockUtil.floor(l, fl.getX(), fl.getY(), fl.getZ(), fs) && !BlockUtil.isWater(fs)) {
                    walkTo(bot, to, false);
                } else {
                    c.sneak = true;
                    if (!at(f, from) || hdist(p, from) > 0.35) {
                        walkTo(bot, from, false);
                        c.sneak = true;
                        return R.RUNNING;
                    }
                    Direction dir = Direction.getNearest(to.x - from.x, 0, to.z - from.z);
                    BlockPos against = new BlockPos(from.x, from.y - 1, from.z);
                    Vec3 hit = Vec3.atCenterOf(against).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
                    c.lookAt(p, hit);
                    if (c.aimed(p, 10) && !Act.place(bot, against, dir)) { why = "다리 놓을 블록 없음"; return R.FAILED; }
                }
            }
            default -> walkTo(bot, to, true);
        }

        // 3) 끼임 감지
        if (moveTicks % 30 == 0) {
            Vec3 now = p.position();
            if (lastPos != null && now.distanceTo(lastPos) < 0.2) {
                if (++stuckChecks >= 3) { why = "끼임"; return R.FAILED; }
                c.jump = true;
            } else stuckChecks = 0;
            lastPos = now;
        }
        return R.RUNNING;
    }

    private static boolean dirChanges(Node a, Node b) {
        Node pa = a.parent;
        if (pa == null) return true;
        return Integer.signum(b.x - a.x) != Integer.signum(a.x - pa.x) || Integer.signum(b.z - a.z) != Integer.signum(a.z - pa.z);
    }

    /** 웅크리고 천천히 걸어서 블록 정중앙에 서기 (기둥 쌓기 전, 옆 블록 모서리에 머리가 걸리지 않게) */
    public static void center(Bot bot, Node n) {
        LocalPlayer p = bot.p();
        double dx = n.x + 0.5 - p.getX(), dz = n.z + 0.5 - p.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        bot.ctl.look(yaw, 60f);
        bot.ctl.sneak = true;
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 25) bot.ctl.fwd = true;
    }

    private void walkTo(Bot bot, Node to, boolean allowSprint) {
        LocalPlayer p = bot.p();
        Ctl c = bot.ctl;
        double dx = to.x + 0.5 - p.getX(), dz = to.z + 0.5 - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        c.look(yaw, to.y < p.getY() - 0.5 ? 25f : 10f);
        float diff = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
        if (hd > 0.12 && (diff < 50 || hd < 0.5)) c.fwd = true;
        boolean straight = to.move == Move.WALK || to.move == Move.DIAG;
        if (allowSprint && straight && diff < 10 && p.getFoodData().getFoodLevel() > 6 && i + 4 < path.size()) {
            boolean ahead = true;
            // 앞에 떨어지는 칸이 있으면 달리지 않음 (탄력으로 착지 지점을 지나쳐 낭떠러지로 떨어지지 않게)
            for (int j = i + 2; j < Math.min(path.size(), i + 5); j++) {
                Move m = path.get(j).move;
                if (m != Move.WALK && m != Move.DIAG) { ahead = false; break; }
            }
            if (ahead) c.sprint = true;
        }
        if (to.move == Move.DESCEND) {
            // 착지 칸 안에 들어왔으면 앞으로 그만 감
            if (hd < 0.35) c.fwd = false;
            c.sprint = false;
            if (p.isSprinting()) c.fwd = hd > 0.6;
        }
        if (to.move == Move.ASCEND && (p.onGround() || p.isInWater()) && (hd < 1.25 || p.horizontalCollision)) c.jump = true;
        if (p.isInWater() && to.y >= Math.floor(p.getY())) c.jump = true;
        if (p.horizontalCollision && p.onGround() && to.y > p.getY() - 0.5) c.jump = true;
        if (p.isInLava()) { c.jump = true; c.fwd = true; }
    }
}
