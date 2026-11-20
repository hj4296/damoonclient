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
        if (why == null) { target = null; aimTicks = 0; return false; }
        Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
        Vec3 vel = p.getDeltaMovement();
        // 닿을 수 있는 착지 지점 고르기 (높은 곳 우선)
        double[] s = null;
        Vec3 to = null;
        double bestErr = 1e9;
        for (BlockPos c : safeSpots(l, p, minR, maxR, prefer)) {
            Vec3 t = Vec3.atBottomCenterOf(c).add(0, 1.0, 0);
            Vec3 hd = new Vec3(t.x - from.x, 0, t.z - from.z).normalize();
            double along = vel.x * hd.x + vel.z * hd.z;
            double up = p.onGround() ? 0 : vel.y;
            double[] r = Ballistics.solvePearl(from, t, along, up);
            if (r != null) { s = r; to = t; target = c; break; }
            double[] cl = Ballistics.closestPearl(from, t, along, up);
            if (cl != null && cl[1] < bestErr && cl[1] < 3) { bestErr = cl[1]; s = new double[]{cl[0], 0}; to = t; target = c; }
        }
        if (s == null) return false;
        if (++aimTicks == 1) bot.log("엔더 진주 탈출 (" + why + ") → " + target.toShortString());
        Inv.select(p, Inv.is(Items.ENDER_PEARL));
        float yaw = Ctl.angles(from, to)[0], pitch = (float) s[0];
        bot.ctl.look(yaw, pitch);
        bot.reflex.doing = "엔더 진주 탈출! (" + why + ")";
        // 떨어지는 동안엔 몇 틱만 늦어도 못 닿으므로 시점을 한 번에 돌려 바로 던짐
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        if (p.getMainHandItem().is(Items.ENDER_PEARL)) {
            Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
            bot.log("진주 던짐!");
            target = null;
            aimTicks = 0;
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

    /** 착지 후보들 (점수 좋은 순) */
    public static java.util.List<BlockPos> safeSpots(ClientLevel l, LocalPlayer p, int minR, int maxR, Vec3 prefer) {
        java.util.List<double[]> sc = new java.util.ArrayList<>();
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
                double score = r * 0.5 + Math.max(0, p.getY() - y) * 0.8;
                if (prefer != null && prefer.lengthSqr() > 0) score -= 6 * (Math.cos(ang) * prefer.normalize().x + Math.sin(ang) * prefer.normalize().z);
                sc.add(new double[]{score, x, y, z});
            }
        }
        sc.sort(java.util.Comparator.comparingDouble(v -> v[0]));
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(12, sc.size()); i++) out.add(new BlockPos((int) sc.get(i)[1], (int) sc.get(i)[2], (int) sc.get(i)[3]));
        return out;
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
