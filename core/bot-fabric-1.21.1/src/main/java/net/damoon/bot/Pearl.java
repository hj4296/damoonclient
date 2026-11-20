package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 엔더 진주로 위기 탈출: 공허로 떨어질 때, 용암에 빠졌을 때, 둘러싸였을 때 안전한 땅으로 던짐 */
public final class Pearl {
    private Pearl() {}

    private static BlockPos target;
    private static int aimTicks;

    /** 위기면 진주를 던지는 중이므로 true (반사 행동이 이번 틱을 씀) */
    public static boolean tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (!Inv.has(p, Items.ENDER_PEARL) || p.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) { target = null; return false; }
        String why = null;
        int minR = 4, maxR = 14;
        Vec3 prefer = null;
        if (!p.onGround() && !p.isInWater() && p.getDeltaMovement().y < -0.2 && noGroundBelow(l, p)) {
            why = "공허로 떨어지는 중";
            minR = 2;
            maxR = 40;
            if (l.dimension() == net.minecraft.world.level.Level.END) prefer = new Vec3(-p.getX(), 0, -p.getZ());
        } else if (p.isInLava()) {
            why = "용암에 빠짐";
            minR = 3;
            maxR = 14;
        } else if (p.getHealth() < 8 && p.getHealth() > 5.5 && hostilesNear(l, p) >= 2) {
            why = "체력이 낮은데 둘러싸임";
            minR = 12;
            maxR = 20;
        }
        if (why == null) { target = null; return false; }
        if (target == null || ++aimTicks > 30) {
            target = safeSpot(l, p, minR, maxR, prefer);
            aimTicks = 0;
            if (target == null) return false;
            bot.log("엔더 진주 탈출 (" + why + ") → " + target.toShortString());
        }
        Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
        Vec3 to = Vec3.atBottomCenterOf(target).add(0, 1.0, 0);
        Vec3 vel = p.getDeltaMovement();
        Vec3 hd = new Vec3(to.x - from.x, 0, to.z - from.z).normalize();
        double along = vel.x * hd.x + vel.z * hd.z;
        double[] s = Ballistics.solvePearl(from, to, along, p.onGround() ? 0 : vel.y);
        if (s == null) { target = null; return false; }
        Inv.select(p, Inv.is(Items.ENDER_PEARL));
        bot.ctl.look(Ctl.angles(from, to)[0], (float) s[0]);
        bot.ctl.speed = 90f;
        bot.reflex.doing = "엔더 진주 탈출! (" + why + ")";
        if (bot.ctl.aimed(p, 2.5f) && p.getMainHandItem().is(Items.ENDER_PEARL)) {
            Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
            target = null;
        }
        return true;
    }

    private static boolean noGroundBelow(ClientLevel l, LocalPlayer p) {
        BlockPos b = p.blockPosition();
        for (int dy = 1; dy <= 64; dy++) {
            var s = l.getBlockState(b.below(dy));
            if (!s.getCollisionShape(l, b.below(dy)).isEmpty() || !s.getFluidState().isEmpty()) return BlockUtil.isLava(s);
            if (b.getY() - dy < l.getMinBuildHeight()) return true;
        }
        return true;
    }

    private static int hostilesNear(ClientLevel l, LocalPlayer p) {
        int n = 0;
        for (Entity e : l.entitiesForRendering()) if (Reflex.hostile(e) && e.distanceTo(p) < 5) n++;
        return n;
    }

    /** 착지할 안전한 땅 (단단한 바닥 + 위 2칸 빈칸, 용암 아님) */
    public static BlockPos safeSpot(ClientLevel l, LocalPlayer p, int minR, int maxR, Vec3 prefer) {
        BlockPos best = null;
        double bs = 1e9;
        for (int r = minR; r <= maxR; r += 2) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI * 2 / 24;
                int x = (int) Math.floor(p.getX() + Math.cos(ang) * r), z = (int) Math.floor(p.getZ() + Math.sin(ang) * r);
                if (!l.hasChunk(x >> 4, z >> 4)) continue;
                int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (y < l.getMinBuildHeight() + 2 || y > p.getY() + 6) continue;
                BlockPos fl = new BlockPos(x, y, z);
                var fs = l.getBlockState(fl);
                if (!BlockUtil.floor(l, x, y, z, fs) || BlockUtil.isWater(fs) || BlockUtil.hurts(fs)) continue;
                if (!l.getBlockState(fl.above()).isAir() || !l.getBlockState(fl.above(2)).isAir()) continue;
                double score = r + Math.max(0, y - p.getY()) * 0.5;
                if (prefer != null && prefer.lengthSqr() > 0) score -= 6 * (Math.cos(ang) * prefer.normalize().x + Math.sin(ang) * prefer.normalize().z);
                if (score < bs) { bs = score; best = fl; }
            }
        }
        return best;
    }
}
