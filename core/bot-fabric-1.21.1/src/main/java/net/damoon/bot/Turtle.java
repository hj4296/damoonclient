package net.damoon.bot;

import net.damoon.bot.path.PathExec;
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

    private List<BlockPos> walls() {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y <= 1; y++) for (Direction h : Direction.Plane.HORIZONTAL) out.add(cell.relative(h).above(y));
        out.add(cell.above(2));
        return out;
    }

    private static boolean danger(Bot bot, LocalPlayer p) {
        if (p.getHealth() >= 9) return false;
        if (p.isOnFire()) return true;
        for (Entity e : bot.level().entitiesForRendering()) if (Reflex.hostile(e) && e.distanceTo(p) < 14) return true;
        return false;
    }

    /** 이번 틱을 썼으면 true. heal 단계에서는 먹기를 위해 Reflex가 이어서 처리 */
    public boolean tick(Bot bot, Reflex reflex) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (!active) {
            if (!danger(bot, p) || Inv.count(p, Inv.throwaway()) < 9 || !p.onGround()) return false;
            active = true;
            cell = PathExec.feet(p);
            phase = 0;
            ticks = 0;
            bot.nav.stop();
            Act.stopDig(bot);
            bot.log("위험 (체력 " + (int) p.getHealth() + ") → 블록으로 몸을 감싸고 회복");
        }
        ticks++;
        if (!PathExec.feet(p).equals(cell) && phase < 2) {
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
            if (dx * dx + dz * dz > 0.03) {
                bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(cell), 60f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                return true;
            }
            for (BlockPos w : walls()) {
                if (!l.getBlockState(w).canBeReplaced()) continue;
                int r = Placer.placeAt(bot, w, Inv.throwaway());
                if (r == -1) continue;
                return true;
            }
            phase = 1;
            reflex.doing = "갇힌 채 회복 중";
            return true;
        }
        if (phase == 1) {
            reflex.forceEat = true;
            if ((p.getHealth() >= 18 && p.getFoodData().getFoodLevel() >= 17) || ticks > 20 * 90) {
                phase = 2;
                reflex.forceEat = false;
                bot.log("회복 완료 (체력 " + (int) p.getHealth() + ") → 벽을 부수고 나감");
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
