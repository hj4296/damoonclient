package net.damoon.bot;

import net.damoon.bot.path.PathExec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 작업대·화로 같은 블록을 옆에 놓고, 블록을 우클릭해서 열기 */
public final class Placer {
    private Placer() {}

    /** 가까이에 이 블록이 있으면 위치 반환 */
    public static BlockPos near(ClientLevel l, LocalPlayer p, Block b, double reach) {
        BlockPos f = PathExec.feet(p);
        BlockPos best = null;
        double bd = 1e9;
        int r = (int) Math.ceil(reach) + 1;
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-r, -2, -r), f.offset(r, 3, r))) {
            if (!l.getBlockState(q).is(b)) continue;
            double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(q));
            if (d <= reach && d < bd) { bd = d; best = q.immutable(); }
        }
        return best;
    }

    /** 손에 든 블록 아이템을 가까운 빈자리에 놓음 (바닥·벽 아무 단단한 면에 붙여서). 놓은 위치 또는 null */
    public static BlockPos placeNear(Bot bot, Item item) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (!Inv.select(p, Inv.is(item))) return null;
        bot.ctl.sneak = true;
        if (!p.isShiftKeyDown()) return null;
        BlockPos f = PathExec.feet(p);
        AABB me = p.getBoundingBox().inflate(0.1);
        BlockPos bestAt = null, bestAgainst = null;
        Direction bestFace = null;
        double bs = 1e9;
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-3, -1, -3), f.offset(3, 2, 3))) {
            BlockPos at = q.immutable();
            var st = l.getBlockState(at);
            if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) continue;
            if (new AABB(at).intersects(me)) continue;
            if (at.getX() == f.getX() && at.getZ() == f.getZ()) continue;
            for (Direction d : Direction.values()) {
                BlockPos against = at.relative(d);
                var as = l.getBlockState(against);
                if (as.isAir() || !as.getFluidState().isEmpty() || as.getCollisionShape(l, against).isEmpty()) continue;
                Direction face = d.getOpposite();
                Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                double dist = p.getEyePosition().distanceTo(hit);
                if (dist > 4.3) continue;
                // 바닥에 놓는 게 제일 좋고, 내가 다니는 발 높이 바로 앞은 피함
                double score = dist + (d == Direction.DOWN ? 0 : 1.5) + (at.getY() == f.getY() && Math.abs(at.getX() - f.getX()) + Math.abs(at.getZ() - f.getZ()) == 1 ? 1 : 0);
                if (score < bs) { bs = score; bestAt = at; bestAgainst = against; bestFace = face; }
            }
        }
        if (bestAt == null) return null;
        Vec3 hit = Vec3.atCenterOf(bestAgainst).add(bestFace.getStepX() * 0.5, bestFace.getStepY() * 0.5, bestFace.getStepZ() * 0.5);
        bot.ctl.lookAt(p, hit);
        InteractionResult r = Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, bestFace, bestAgainst, false));
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            return bestAt;
        }
        return null;
    }

    /** 다 쓴 작업대·화로를 다시 캐서 주움. 끝났으면 true */
    public static boolean pickUp(Bot bot, BlockPos pos, Block block, Item item) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (p.containerMenu != p.inventoryMenu) { p.closeContainer(); return false; }
        if (l.getBlockState(pos).is(block)) {
            if (!Act.dig(bot, pos)) return true;
            return false;
        }
        Act.stopDig(bot);
        for (var e : l.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.distanceTo(p) < 6 && ie.position().distanceTo(Vec3.atCenterOf(pos)) < 2.5) {
                bot.ctl.lookYaw(p, ie.position(), 30f);
                bot.ctl.fwd = ie.distanceTo(p) > 0.5;
                return false;
            }
        }
        return true;
    }

    /** 블록 우클릭 (작업대·화로 열기). 웅크리지 않은 상태여야 함 */
    public static void open(Bot bot, BlockPos b) {
        LocalPlayer p = bot.p();
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(b);
        Direction face = Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3 hit = c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        bot.ctl.lookAt(p, hit);
        bot.ctl.sneak = false;
        if (p.isShiftKeyDown()) return; // 웅크린 채로 누르면 열리지 않고 블록이 놓임
        Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, b, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * pos 자리에 조건에 맞는 블록을 놓음 (주변 단단한 면에 붙여서).
     * 1 = 이미 막혀 있음/놓음, 0 = 진행 중, -1 = 불가능
     */
    public static int placeAt(Bot bot, BlockPos pos, java.util.function.Predicate<net.minecraft.world.item.ItemStack> item) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        var st = l.getBlockState(pos);
        if (!st.canBeReplaced()) return 1;
        if (new AABB(pos).intersects(p.getBoundingBox())) return -1;
        if (!Inv.select(p, item)) return -1;
        bot.ctl.sneak = true;
        if (!p.isShiftKeyDown()) return 0;
        Direction best = null;
        double bd = 1e9;
        for (Direction d : Direction.values()) {
            BlockPos against = pos.relative(d);
            var as = l.getBlockState(against);
            if (as.isAir() || !as.getFluidState().isEmpty() || as.getCollisionShape(l, against).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = p.getEyePosition().distanceTo(hit);
            if (dist < bd && dist <= 4.6) { bd = dist; best = d; }
        }
        if (best == null) return -1;
        BlockPos against = pos.relative(best);
        Direction face = best.getOpposite();
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        bot.ctl.lookAt(p, hit);
        InteractionResult r = Minecraft.getInstance().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
        return 0;
    }

    /** 실제로 밟고 서 있는 칸 (가장자리에 걸쳐 서 있으면 발 중심 칸 아래가 빌 수 있음): 몸이 걸친 칸 중 바닥이 있는 가장 가까운 칸 */
    public static BlockPos standCell(LocalPlayer p) {
        ClientLevel l = (ClientLevel) p.level();
        BlockPos f = net.damoon.bot.path.PathExec.feet(p);
        BlockPos best = null;
        double bd = 1e9;
        AABB box = p.getBoundingBox();
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            BlockPos c = f.offset(ox, 0, oz);
            if (box.maxX <= c.getX() || box.minX >= c.getX() + 1 || box.maxZ <= c.getZ() || box.minZ >= c.getZ() + 1) continue;
            var fl = l.getBlockState(c.below());
            if (fl.getCollisionShape(l, c.below()).isEmpty() || BlockUtil.hurts(fl)) continue;
            if (!l.getBlockState(c).getCollisionShape(l, c).isEmpty() || !l.getBlockState(c.above()).getCollisionShape(l, c.above()).isEmpty()) continue;
            double d = Math.pow(c.getX() + 0.5 - p.getX(), 2) + Math.pow(c.getZ() + 0.5 - p.getZ(), 2);
            if (d < bd) { bd = d; best = c; }
        }
        return best != null ? best : f;
    }
}
