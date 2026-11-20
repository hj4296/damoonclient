package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 블록 캐서 모으기: 원하는 블록을 찾아가서 캐고, 떨어진 아이템을 주움.
 * 나무·돌·광석·엔드 돌 모으기에 공통으로 씀.
 */
public final class Miner {
    public enum R { RUNNING, DONE, FAILED }

    private final Predicate<BlockState> block;
    private final Predicate<ItemStack> item;
    private final int want;
    private final boolean needTool;
    private BlockPos target;
    private int scanTick, noFind, targetTicks;
    private final Set<Long> bad = new HashSet<>();
    public int radius = 24;
    /** 이 높이보다 아래는 안 캠 (예: 발밑 엔드 돌만 캐서 섬에 구멍 뚫지 않게) */
    public int minY = Integer.MIN_VALUE;
    public String info = "";
    private final Set<Integer> ignoreDrops = new HashSet<>();
    private final java.util.Map<Integer, Integer> dropTime = new java.util.HashMap<>();

    public Miner(Predicate<BlockState> block, Predicate<ItemStack> item, int want, boolean needTool) {
        this.block = block;
        this.item = item;
        this.want = want;
        this.needTool = needTool;
    }

    public R tick(Bot bot) {
        bot.trace = "miner";
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        int have = Inv.count(p, item);
        if (have >= want) {
            Act.stopDig(bot);
            return R.DONE;
        }
        // 1) 떨어진 아이템 줍기
        ItemEntity drop = null;
        double dd = 1e9;
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof ItemEntity ie && item.test(ie.getItem()) && !ignoreDrops.contains(ie.getId())) {
                double d = ie.distanceTo(p);
                if (d < 12 && d < dd && Math.abs(ie.getY() - p.getY()) < 4) { dd = d; drop = ie; }
            }
        }
        if (drop != null) {
            bot.trace = "miner-drop";
            if (dropTime.merge(drop.getId(), 1, Integer::sum) > 20 * 8) {
                ignoreDrops.add(drop.getId());
            } else {
                info = "아이템 줍는 중 (" + have + "/" + want + ")";
                Act.stopDig(bot);
                Nav.R r = dd < 2.5 ? Nav.R.ARRIVED : bot.nav.go(new Goal.Near(drop.blockPosition(), 1.0));
                if (r == Nav.R.FAILED) { bot.nav.stop(); ignoreDrops.add(drop.getId()); }
                if (r == Nav.R.ARRIVED) {
                    // 가까우면 아이템 쪽으로 직접 걸어감
                    bot.nav.stop();
                    bot.ctl.lookYaw(p, drop.position(), 30f);
                    bot.ctl.fwd = dd > 0.3;
                    if (drop.getY() > p.getY() + 0.6 && p.onGround()) bot.ctl.jump = true;
                }
                return R.RUNNING;
            }
        }
        // 2) 캘 블록 찾기
        if (target != null && !block.test(l.getBlockState(target))) {
            target = null;
        }
        if (target == null || ++targetTicks > 20 * 30) {
            if (target != null) bad.add(target.asLong());
            targetTicks = 0;
            bot.trace = "miner-scan";
            if (scanTick-- > 0) return R.RUNNING;
            scanTick = 10;
            target = find(p, l);
            if (target == null) {
                info = "캘 블록을 못 찾음";
                return ++noFind > 6 ? R.FAILED : R.RUNNING;
            }
            noFind = 0;
        }
        info = "캐는 중 (" + have + "/" + want + ")";
        double reach = Act.reach(p, new net.minecraft.world.phys.AABB(target));
        info = "캐는 중 (" + have + "/" + want + ") 거리 " + String.format("%.1f", reach);
        bot.trace = "miner-reach " + target.toShortString();
        if (reach <= 4.8) {
            bot.trace = "miner-dig";
            bot.nav.stop();
            // 시야를 가리는 블록(잎 등)이 있으면 그것부터 부숨
            BlockPos dig = target;
            net.minecraft.world.phys.BlockHitResult hr = l.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hr.getBlockPos().equals(target)) {
                BlockPos h = hr.getBlockPos();
                BlockState hs = l.getBlockState(h);
                if (BlockUtil.breakable(l, h.getX(), h.getY(), h.getZ(), hs) && !BlockUtil.fluidNear(l, h.getX(), h.getY(), h.getZ(), true)) dig = h;
            }
            if (!Act.dig(bot, dig)) bad.add(target.asLong());
            return R.RUNNING;
        }
        Act.stopDig(bot);
        Nav.R r = bot.nav.go(new Goal.Reach(target, 3.6));
        if (r == Nav.R.FAILED) {
            bad.add(target.asLong());
            target = null;
            bot.nav.stop();
        }
        return R.RUNNING;
    }

    private boolean canSee(ClientLevel l, LocalPlayer p, BlockPos b) {
        for (Direction d : Direction.values()) {
            BlockPos n = b.relative(d);
            BlockState s = l.getBlockState(n);
            if (BlockUtil.passable(l, n.getX(), n.getY(), n.getZ(), s) || s.isAir()) return true;
        }
        return false;
    }

    /** 위아래로 몇 칸까지 찾을지 */
    public int below = 8, above = 10;
    public int chunks = 2;

    private BlockPos find(LocalPlayer p, ClientLevel l) {
        BlockPos c = PathExec.feet(p);
        int lo = Math.max(minY, c.getY() - below), hi = c.getY() + above;
        java.util.List<BlockPos> cands = Scan.find(l, c, chunks, lo, hi, block, q -> !bad.contains(q.asLong()), 40);
        BlockPos best = null;
        double bs = 1e9;
        for (BlockPos m : cands) {
            BlockState s = l.getBlockState(m);
            if (needTool && BlockUtil.breakTicks(p, l, m, s, true, null) >= BlockUtil.INF) continue;
            if (BlockUtil.fluidNear(l, m.getX(), m.getY(), m.getZ(), true)) continue;
            boolean exposed = false;
            for (Direction d : Direction.values()) if (l.getBlockState(m.relative(d)).isAir()) { exposed = true; break; }
            double dx = m.getX() - c.getX(), dy = m.getY() - c.getY(), dz = m.getZ() - c.getZ();
            double score = Math.sqrt(dx * dx + dz * dz + dy * dy * 3.0) + (exposed ? 0 : 5) + (dy < -2 ? 4 : 0);
            if (score < bs) { bs = score; best = m; }
        }
        return best;
    }

    public boolean busy() { return target != null; }

    /** 이 근처에 캘 게 있는지 (계획용) */
    public boolean available(Bot bot) {
        return find(bot.p(), bot.level()) != null;
    }
}
