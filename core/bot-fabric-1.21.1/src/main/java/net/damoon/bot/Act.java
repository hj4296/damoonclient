package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 손 동작: 블록 캐기, 블록 놓기, 때리기 */
public final class Act {
    private Act() {}

    private static BlockPos digging;
    private static int placeCooldown;

    public static boolean isDigging() { return digging != null; }

    public static void tick() {
        if (placeCooldown > 0) placeCooldown--;
    }

    /** 블록을 바라보고 캠. 손이 안 닿으면 false */
    public static boolean dig(Bot bot, BlockPos b) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bot.p();
        BlockState s = p.level().getBlockState(b);
        Vec3 eye = p.getEyePosition();
        Vec3 ctr = Vec3.atCenterOf(b);
        // 서버 허용 거리: 블록 상자까지 4.5 + 1.0
        if (reach(p, new AABB(b)) > 5.3) return false;
        if (p.containerMenu == p.inventoryMenu) Inv.selectTool(p, s);
        bot.ctl.lookAt(p, ctr);
        if (!bot.ctl.aimed(p, 30)) return true;
        Direction face = Direction.getNearest(eye.x - ctr.x, eye.y - ctr.y, eye.z - ctr.z);
        if (digging != null && !digging.equals(b)) mc.gameMode.stopDestroyBlock();
        digging = b.immutable();
        if (mc.gameMode.continueDestroyBlock(b, face)) {
            mc.particleEngine.crack(b, face);
            p.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    public static void stopDig(Bot bot) {
        if (digging != null) {
            Minecraft.getInstance().gameMode.stopDestroyBlock();
            digging = null;
        }
    }

    /** against 블록의 face 면에 쓰레기 블록을 붙여 놓음 */
    public static boolean place(Bot bot, BlockPos against, Direction face) {
        LocalPlayer p = bot.p();
        if (placeCooldown > 0) return true;
        if (!Inv.select(p, Inv.throwaway())) return false;
        // 작업대·화로 위에 놓을 때 열리지 않게 웅크린 상태에서만 놓음 (서버가 웅크림을 알도록 한 틱 먼저)
        bot.ctl.sneak = true;
        if (!p.isShiftKeyDown()) return true;
        return use(bot, against, face);
    }

    /** 손에 든 아이템으로 블록 면을 우클릭 */
    public static boolean use(Bot bot, BlockPos against, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bot.p();
        if (placeCooldown > 0) return true;
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            placeCooldown = 2;
        }
        return true;
    }

    /** 공격 쿨다운이 찼으면 때림 */
    public static boolean attack(Bot bot, Entity e, float minCharge) {
        LocalPlayer p = bot.p();
        if (p.getAttackStrengthScale(0.5f) < minCharge) return false;
        Minecraft.getInstance().gameMode.attack(p, e);
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 눈에서 상자까지 가장 가까운 거리 */
    public static double reach(LocalPlayer p, AABB box) {
        Vec3 e = p.getEyePosition();
        double dx = Math.max(0, Math.max(box.minX - e.x, e.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - e.y, e.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - e.z, e.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
