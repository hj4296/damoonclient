package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.BlockUtil;
import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Nav;
import net.damoon.bot.Placer;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 네더 포털 짓기: 눈앞 2칸 떨어진 곳에 흑요석 10개(모서리는 아무 블록) 틀을 세우고
 * 부싯돌과 부시로 불을 붙인 뒤 들어감.
 */
public class PortalTask extends Task {
    private BlockPos base;     // 틀 왼쪽 아래 모서리
    private Direction along;   // 틀이 뻗는 방향
    private int phase, wait, ticks;
    private String detail = "";
    public BlockPos portalAt;

    @Override public String name() { return "네더 포털"; }
    @Override public String detail() { return detail; }

    private BlockPos at(int i, int j) { return base.relative(along, i).above(j); }

    private static boolean frame(int i, int j) {
        boolean edgeX = i == 0 || i == 3, edgeY = j == 0 || j == 4;
        return (edgeX || edgeY) && !(edgeX && edgeY);
    }

    private static boolean corner(int i, int j) {
        return (i == 0 || i == 3) && (j == 0 || j == 4);
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (l.dimension() != Level.OVERWORLD) return S.DONE;
        if (wait > 0) { wait--; return S.RUNNING; }
        if (++ticks > 20 * 60 * 6) return S.FAILED;
        if (base == null) {
            BlockPos f = PathExec.feet(p);
            Direction d = Direction.fromYRot(p.getYRot());
            along = d.getClockWise();
            base = f.relative(d, 2).relative(along.getOpposite(), 1);
            bot.log("포털 자리: " + base.toShortString());
        }
        bot.nav.stop();
        if (phase == 0) {
            // 바닥 받침, 틀 자리 비우기, 블록 놓기
            for (int i = 0; i < 4; i++) {
                BlockPos g = at(i, -1);
                BlockState gs = l.getBlockState(g);
                if (gs.canBeReplaced()) {
                    detail = "포털 바닥 받침 놓는 중";
                    int r = Placer.placeAt(bot, g, Inv.throwaway());
                    if (r == -1) { bot.log("받침을 못 놓음"); return S.FAILED; }
                    if (r == 0) return S.RUNNING;
                }
            }
            for (int j = 0; j <= 4; j++) {
                for (int i = 0; i < 4; i++) {
                    BlockPos q = at(i, j);
                    BlockState s = l.getBlockState(q);
                    boolean isFrame = frame(i, j), isCorner = corner(i, j);
                    if (isCorner) {
                        if (!s.canBeReplaced()) continue;
                        detail = "모서리 블록 놓는 중";
                        int r = Placer.placeAt(bot, q, Inv.throwaway());
                        if (r == -1) return S.FAILED;
                        if (r == 0) return S.RUNNING;
                    } else if (isFrame) {
                        if (s.is(Blocks.OBSIDIAN)) continue;
                        if (!s.canBeReplaced()) {
                            detail = "틀 자리 비우는 중";
                            if (!Act.dig(bot, q)) return S.FAILED;
                            return S.RUNNING;
                        }
                        Act.stopDig(bot);
                        detail = "흑요석 놓는 중";
                        int r = Placer.placeAt(bot, q, Inv.is(Items.OBSIDIAN));
                        if (r == -1) { bot.log("흑요석을 못 놓음 " + q.toShortString()); return S.FAILED; }
                        if (r == 0) return S.RUNNING;
                    } else {
                        if (!BlockUtil.passable(l, q.getX(), q.getY(), q.getZ(), s) && !s.isAir()) {
                            detail = "포털 안쪽 비우는 중";
                            if (!Act.dig(bot, q)) return S.FAILED;
                            return S.RUNNING;
                        }
                    }
                }
            }
            Act.stopDig(bot);
            phase = 1;
            bot.log("포털 틀 완성");
        }
        if (phase == 1) {
            if (l.getBlockState(at(1, 1)).is(Blocks.NETHER_PORTAL)) {
                phase = 2;
                portalAt = at(1, 1);
                bot.log("포털에 불 붙음!");
                return S.RUNNING;
            }
            detail = "부싯돌과 부시로 불 붙이는 중";
            if (!Inv.select(p, Inv.is(Items.FLINT_AND_STEEL))) return S.FAILED;
            bot.ctl.sneak = false;
            if (p.isShiftKeyDown()) return S.RUNNING;
            BlockPos against = at(1, 0);
            Vec3 hit = Vec3.atCenterOf(against).add(0, 0.5, 0);
            bot.ctl.lookAt(p, hit);
            if (!bot.ctl.aimed(p, 8)) return S.RUNNING;
            Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, against, false));
            p.swing(InteractionHand.MAIN_HAND);
            wait = 10;
            return S.RUNNING;
        }
        // 들어가기
        detail = "포털로 들어가는 중";
        BlockPos in = at(1, 1);
        BlockPos f = PathExec.feet(p);
        if (Math.abs(f.getX() - in.getX()) + Math.abs(f.getZ() - in.getZ()) <= 1 && Math.abs(f.getY() - in.getY()) <= 1
                && l.getBlockState(f).is(Blocks.NETHER_PORTAL)) {
            detail = "포털 안에서 기다리는 중";
            return S.RUNNING;
        }
        Nav.R r = bot.nav.go(new Goal.Block(in.getX(), in.getY(), in.getZ(), true));
        if (r == Nav.R.FAILED) {
            bot.nav.stop();
            bot.ctl.lookYaw(p, Vec3.atCenterOf(in), 10f);
            bot.ctl.fwd = true;
        }
        return S.RUNNING;
    }
}
