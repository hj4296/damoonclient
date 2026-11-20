package net.damoon.bot;

import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 사람이 하듯 계단식으로 파 내려가거나(STAIRS) 2칸 높이 굴을 파며 앞으로(TUNNEL).
 * 용암이 옆에 있으면 방향을 바꾸고, 발밑이 비면 블록을 깔고 감.
 */
public final class Digger {
    public enum Mode { STAIRS, TUNNEL }

    private Direction dir;
    /** 최근에 방향을 바꾼 횟수 (많으면 막힌 것) */
    public int recentTurns;
    private int turns, stuck;
    private BlockPos lastFeet;
    public String info = "";

    public Digger(Direction start) { this.dir = start; }

    public Direction dir() { return dir; }

    private void turn(Bot bot, String why) {
        dir = dir.getClockWise();
        turns++;
        recentTurns++;
        bot.log("방향 바꿈: " + why);
    }

    public boolean tick(Bot bot, Mode mode) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        BlockPos f = PathExec.feet(p);
        if (!p.onGround() && !p.isInWater()) return true;
        if (f.equals(lastFeet)) {
            if (++stuck > 20 * 20) { turn(bot, "막힘"); stuck = 0; }
        } else { stuck = 0; lastFeet = f; }

        BlockPos front = f.relative(dir);
        BlockPos[] clear;
        BlockPos floor;
        if (mode == Mode.STAIRS) {
            clear = new BlockPos[]{front.above(), front, front.below()};
            floor = front.below(2);
            info = "계단식으로 파 내려가는 중 (y=" + f.getY() + ")";
        } else {
            clear = new BlockPos[]{front.above(), front};
            floor = front.below();
            info = "굴 파며 앞으로 (" + dir.getName() + ")";
        }
        // 위험 확인: 캘 블록 옆이나 밟을 곳에 용암
        for (BlockPos c : clear) {
            if (BlockUtil.isLava(l.getBlockState(c)) || BlockUtil.fluidNear(l, c.getX(), c.getY(), c.getZ(), true)) {
                turn(bot, "용암 발견");
                return true;
            }
        }
        BlockState fs = l.getBlockState(floor);
        if (BlockUtil.isLava(fs)) { turn(bot, "아래 용암"); return true; }

        for (BlockPos c : clear) {
            BlockState s = l.getBlockState(c);
            if (!BlockUtil.passable(l, c.getX(), c.getY(), c.getZ(), s)) {
                if (!BlockUtil.breakable(l, c.getX(), c.getY(), c.getZ(), s)) { turn(bot, "못 캐는 블록"); return true; }
                Act.dig(bot, c);
                return true;
            }
        }
        Act.stopDig(bot);
        if (mode == Mode.STAIRS && !BlockUtil.floor(l, floor.getX(), floor.getY(), floor.getZ(), fs) && !BlockUtil.isWater(fs)) {
            // 아래가 비었음: 3칸 안에 땅이 있으면 그냥 내려감, 깊으면 발판을 붙여 놓음
            int drop = 0;
            for (int k = 1; k <= 4; k++) {
                BlockState q = l.getBlockState(floor.below(k));
                if (BlockUtil.isLava(q)) { drop = 99; break; }
                if (BlockUtil.floor(l, floor.getX(), floor.getY() - k, floor.getZ(), q)) { drop = k; break; }
            }
            if (drop > 0 && drop <= 2) {
                Vec3 t = new Vec3(front.getX() + 0.5, p.getY(), front.getZ() + 0.5);
                bot.ctl.lookYaw(p, t, 40f);
                bot.ctl.fwd = true;
                return true;
            }
            if (drop != 99 && Inv.count(p, Inv.throwaway()) > 0) {
                int r = Placer.placeAt(bot, floor, Inv.throwaway());
                if (r != -1) return true;
            }
            turn(bot, "아래가 깊음");
            return true;
        }
        if (!BlockUtil.floor(l, floor.getX(), floor.getY(), floor.getZ(), fs) || BlockUtil.isWater(fs)) {
            // 발밑이 비었으면 블록 깔기 (2칸 이상 낭떠러지면 방향 바꿈)
            BlockState below2 = l.getBlockState(floor.below());
            if (Inv.count(p, Inv.throwaway()) > 0) {
                BlockPos against = mode == Mode.STAIRS ? f.below() : f.below();
                Vec3 hit = Vec3.atCenterOf(against).add(dir.getStepX() * 0.5, mode == Mode.STAIRS ? -0.5 : 0, dir.getStepZ() * 0.5);
                bot.ctl.lookAt(p, hit);
                bot.ctl.sneak = true;
                if (mode == Mode.STAIRS) {
                    // 계단: 앞 아래칸 바닥에 블록 — 앞칸(floor.above) 옆면 대신 floor 아래 블록 윗면 사용
                    if (BlockUtil.floor(l, floor.getX(), floor.getY() - 1, floor.getZ(), below2)) Act.place(bot, floor.below(), Direction.UP);
                    else turn(bot, "아래가 깊음");
                } else {
                    Act.place(bot, against, dir);
                }
                return true;
            }
            turn(bot, "발판 없음");
            return true;
        }
        // 앞으로 이동
        Vec3 target = new Vec3(front.getX() + 0.5, p.getY(), front.getZ() + 0.5);
        float yaw = (float) (Mth.atan2(target.z - p.getZ(), target.x - p.getX()) * (180.0 / Math.PI)) - 90f;
        bot.ctl.look(yaw, mode == Mode.STAIRS ? 35f : 15f);
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 30) bot.ctl.fwd = true;
        return true;
    }
}
