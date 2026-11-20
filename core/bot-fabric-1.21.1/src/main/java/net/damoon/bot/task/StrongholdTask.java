package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.Bot;
import net.damoon.bot.Digger;
import net.damoon.bot.Inv;
import net.damoon.bot.Nav;
import net.damoon.bot.Scan;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 엔더의 눈을 던져 방향을 보고 두 번 던져 삼각측량 → 요새 위치로 가서 파 내려감
 * → 엔드 포털 틀에 눈을 다 끼움 (그 다음은 EndGameTask가 포털로 들어감).
 */
public class StrongholdTask extends Task {
    private Vec3 line1o, line1d, line2o, line2d;
    private Vec3 guess;
    private int throwState, throwTicks, wait;
    private Vec3 eyeStart;
    private int eyeId = -1;
    private BlockPos travelTo;
    private Digger digger;
    private BlockPos room;
    private String detail = "";

    @Override public String name() { return "요새 찾기"; }
    @Override public String detail() { return detail; }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (wait > 0) { wait--; return S.RUNNING; }
        BlockPos f = PathExec.feet(p);

        // 포털이 이미 열렸으면 끝 (EndGameTask가 들어감)
        if (Scan.nearest(l, f, 3, l.getMinBuildHeight(), 80, s -> s.is(Blocks.END_PORTAL), null) != null) return S.DONE;

        // 포털 방이 보이면 → 거기로
        if (room == null) {
            room = Scan.nearest(l, f, 5, l.getMinBuildHeight(), 70, s -> s.is(Blocks.END_PORTAL_FRAME), null);
            if (room != null) bot.log("엔드 포털 틀 발견: " + room.toShortString());
        }
        if (room != null) return gotoRoom(bot, p, l, f);

        // 엔더의 눈 던지기 중이면 지켜보기
        if (throwState == 1) return watchEye(bot, p, l);

        if (guess != null) {
            double dist = Math.hypot(guess.x - p.getX(), guess.z - p.getZ());
            if (dist > 24) {
                detail = String.format("요새 예상 위치로 이동 (%.0f, %.0f) 남은 %.0f", guess.x, guess.z, dist);
                Nav.R r = bot.nav.go(new Goal.NearXZ((int) guess.x, (int) guess.z, 16));
                if (r == Nav.R.FAILED) bot.nav.stop();
                return S.RUNNING;
            }
            // 도착했는데 틀이 안 보이면 계단식으로 파 내려가며 찾음
            detail = "요새를 찾아 파 내려가는 중";
            if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot()));
            bot.nav.stop();
            if (f.getY() > 10) digger.tick(bot, Digger.Mode.STAIRS);
            else { guess = null; line1o = null; line2o = null; }
            return S.RUNNING;
        }
        if (line1o != null && line2o == null && travelTo != null) {
            double dist = Math.hypot(travelTo.getX() - p.getX(), travelTo.getZ() - p.getZ());
            if (dist > 12) {
                detail = "눈이 날아간 쪽으로 이동 (남은 " + (int) dist + ")";
                Nav.R r = bot.nav.go(new Goal.NearXZ(travelTo.getX(), travelTo.getZ(), 8));
                if (r == Nav.R.FAILED) { bot.nav.stop(); travelTo = null; }
                return S.RUNNING;
            }
        }
        return throwEye(bot, p);
    }

    private S throwEye(Bot bot, LocalPlayer p) {
        detail = "엔더의 눈 던지는 중";
        if (!Inv.select(p, Inv.is(Items.ENDER_EYE))) return S.FAILED;
        bot.nav.stop();
        if (!p.onGround()) return S.RUNNING;
        bot.ctl.look(p.getYRot(), -15f);
        if (!bot.ctl.aimed(p, 3)) return S.RUNNING;
        Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
        throwState = 1;
        throwTicks = 0;
        eyeId = -1;
        eyeStart = p.position();
        return S.RUNNING;
    }

    private S watchEye(Bot bot, LocalPlayer p, ClientLevel l) {
        throwTicks++;
        detail = "엔더의 눈이 날아가는 방향을 보는 중";
        EyeOfEnder eye = null;
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof EyeOfEnder ee && (eyeId == -1 ? ee.distanceTo(p) < 4 : ee.getId() == eyeId)) { eye = ee; break; }
        }
        if (eye != null) {
            if (eyeId == -1) { eyeId = eye.getId(); eyeStart = eye.position(); }
            bot.ctl.lookAt(p, eye.position());
            if (throwTicks == 25) {
                Vec3 d = eye.position().subtract(eyeStart);
                Vec3 dir = new Vec3(d.x, 0, d.z).normalize();
                if (dir.lengthSqr() > 0.5) recordLine(bot, p.position(), dir);
            }
            return S.RUNNING;
        }
        if (throwTicks < 30) return S.RUNNING;
        // 눈이 떨어졌으면 주우러
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof ItemEntity ie && ie.getItem().is(Items.ENDER_EYE) && ie.distanceTo(p) < 20) {
                detail = "엔더의 눈 줍는 중";
                Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0));
                if (r == Nav.R.RUNNING) return S.RUNNING;
                if (r == Nav.R.ARRIVED) { bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; if (throwTicks < 200) return S.RUNNING; }
                bot.nav.stop();
            }
        }
        throwState = 0;
        wait = 10;
        return S.RUNNING;
    }

    private void recordLine(Bot bot, Vec3 o, Vec3 d) {
        if (line1o == null) {
            line1o = o;
            line1d = d;
            // 옆으로 비켜서 두 번째로 던짐 (진행 방향에 수직으로 120칸)
            Vec3 side = new Vec3(-d.z, 0, d.x).scale(120).add(d.scale(150));
            travelTo = BlockPos.containing(o.add(side));
            bot.log(String.format("첫 번째 눈: 방향 (%.2f, %.2f)", d.x, d.z));
        } else if (line2o == null) {
            line2o = o;
            line2d = d;
            // 두 직선의 교점
            double den = line1d.x * line2d.z - line1d.z * line2d.x;
            if (Math.abs(den) < 0.05) {
                bot.log("두 방향이 거의 평행 → 다시 던짐");
                line2o = null;
                travelTo = BlockPos.containing(o.add(new Vec3(-d.z, 0, d.x).scale(150)));
                return;
            }
            double t = ((line2o.x - line1o.x) * line2d.z - (line2o.z - line1o.z) * line2d.x) / den;
            guess = line1o.add(line1d.scale(t));
            bot.log(String.format("요새 예상 위치: %.0f, %.0f", guess.x, guess.z));
        }
    }

    private S gotoRoom(Bot bot, LocalPlayer p, ClientLevel l, BlockPos f) {
        List<BlockPos> frames = Scan.find(l, room, 1, room.getY() - 2, room.getY() + 2, s -> s.is(Blocks.END_PORTAL_FRAME), null, 16);
        BlockPos empty = null;
        double bd = 1e9;
        for (BlockPos b : frames) {
            BlockState s = l.getBlockState(b);
            if (s.getValue(EndPortalFrameBlock.HAS_EYE)) continue;
            double d = p.distanceToSqr(Vec3.atCenterOf(b));
            if (d < bd) { bd = d; empty = b; }
        }
        if (empty == null) {
            detail = "포털 완성!";
            return S.DONE;
        }
        int eyes = Inv.count(p, Items.ENDER_EYE);
        int missing = 0;
        for (BlockPos b : frames) if (!l.getBlockState(b).getValue(EndPortalFrameBlock.HAS_EYE)) missing++;
        detail = "엔드 포털 틀에 눈 끼우는 중 (빈 칸 " + missing + ", 눈 " + eyes + ")";
        if (eyes == 0) return S.FAILED;
        // 멀면 내려가기: 위에서 계단식으로 파 내려감, 가까우면 길찾기
        if (f.getY() > empty.getY() + 6 && Math.hypot(empty.getX() - p.getX(), empty.getZ() - p.getZ()) < 12) {
            if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot()));
            bot.nav.stop();
            digger.tick(bot, Digger.Mode.STAIRS);
            detail = "포털 방으로 파 내려가는 중 (y=" + f.getY() + ")";
            return S.RUNNING;
        }
        if (Act.reach(p, new net.minecraft.world.phys.AABB(empty)) > 4.2) {
            Nav.R r = bot.nav.go(new Goal.Reach(empty, 3.5));
            if (r == Nav.R.FAILED) {
                bot.nav.stop();
                if (f.getY() > empty.getY() + 2) { if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot())); digger.tick(bot, Digger.Mode.STAIRS); }
            }
            return S.RUNNING;
        }
        bot.nav.stop();
        if (!Inv.select(p, Inv.is(Items.ENDER_EYE))) return S.FAILED;
        bot.ctl.sneak = false;
        if (p.isShiftKeyDown()) return S.RUNNING;
        Vec3 hit = Vec3.atCenterOf(empty).add(0, 0.5, 0);
        bot.ctl.lookAt(p, hit);
        if (!bot.ctl.aimed(p, 10)) return S.RUNNING;
        Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, empty, false));
        p.swing(InteractionHand.MAIN_HAND);
        wait = 4;
        return S.RUNNING;
    }
}
