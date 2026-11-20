package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Placer;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 밤 숨기 (초반, 침대·방패·갑옷이 없을 때): 발밑을 3칸 파고 들어가 위를 블록으로 덮고 아침까지 기다림.
 * 몹이 없을 때 시작하므로 구멍에 따라 들어올 몹이 없음. 기다리는 동안 배고프면 먹음.
 */
public class HideTask extends Task {
    private BlockPos top;
    private int phase, ticks;
    private String detail = "";

    @Override public String name() { return "밤 숨기"; }
    @Override public String detail() { return detail; }

    /** 지금 자리에서 숨을 수 있는지 */
    public static boolean canHide(Bot bot) {
        LocalPlayer p = bot.p();
        // 덮개 블록은 파면서 나온 흙·돌로 충분 (맨손이면 흙만 빨리 파짐 — holeOk가 캐는 시간을 봄)
        return p.onGround() && !p.isInWater() && net.damoon.bot.Turtle.holeOk(bot.level(), p, Placer.standCell(p));
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        ticks++;
        if (top == null) {
            top = Placer.standCell(p);
            bot.nav.stop();
            bot.log("밤이 됐는데 장비가 약함 → 땅을 3칸 파고 들어가 위를 막고 아침까지 기다림");
        }
        BlockPos ft = PathExec.feet(p);
        int depth = top.getY() - ft.getY();
        if (phase == 0) {
            detail = "땅 파고 들어가는 중 (" + Math.max(0, depth) + "/3)";
            if (ticks > 20 * 30) return S.FAILED;
            if (!p.onGround()) return S.RUNNING;
            if (depth >= 3) {
                int r = Placer.placeAt(bot, top.below(), Inv.throwaway());
                if (r == 1) { phase = 1; Act.stopDig(bot); }
                else if (r == -1 && ticks > 20 * 20) return S.FAILED;
                return S.RUNNING;
            }
            double dx = top.getX() + 0.5 - p.getX(), dz = top.getZ() + 0.5 - p.getZ();
            if (depth == 0 && dx * dx + dz * dz > 0.03 && ticks < 60) {
                bot.ctl.lookYaw(p, net.minecraft.world.phys.Vec3.atBottomCenterOf(top), 60f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                return S.RUNNING;
            }
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 60f;
            Act.dig(bot, ft.below());
            return S.RUNNING;
        }
        if (phase == 1) {
            bot.reflex.forceEat = p.getHealth() < 20;
            if (BedTask.night(l)) {
                detail = "구멍 속에서 아침 기다리는 중";
                if (l.getBlockState(top.below()).canBeReplaced()) { phase = 0; return S.RUNNING; }
                return S.RUNNING;
            }
            bot.reflex.forceEat = false;
            phase = 2;
            bot.log("아침이 됨 → 덮개를 부수고 나감");
        }
        BlockPos cv = top.below();
        if (!l.getBlockState(cv).isAir() && !l.getBlockState(cv).canBeReplaced()) {
            detail = "덮개 부수는 중";
            Act.dig(bot, cv);
            return ticks > 20 * 60 * 15 ? S.FAILED : S.RUNNING;
        }
        Act.stopDig(bot);
        return S.DONE;
    }
}
