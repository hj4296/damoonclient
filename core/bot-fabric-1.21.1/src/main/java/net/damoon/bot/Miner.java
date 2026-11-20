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

    public Miner(Predicate<BlockState> block, Predicate<ItemStack> item, int want, boolean needTool) {
        this.block = block;
        this.item = item;
        this.want = want;
        this.needTool = needTool;
    }

    public R tick(Bot bot) {
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
            if (e instanceof ItemEntity ie && item.test(ie.getItem()) && ie.onGround()) {
                double d = ie.distanceTo(p);
                if (d < 12 && d < dd && Math.abs(ie.getY() - p.getY()) < 4) { dd = d; drop = ie; }
            }
        }
        if (drop != null && dd > 1.0) {
            info = "아이템 줍는 중 (" + have + "/" + want + ")";
            Act.stopDig(bot);
            BlockPos ip = drop.blockPosition();
            Nav.R r = bot.nav.go(new Goal.Near(ip, 1.0));
            if (r == Nav.R.FAILED) bot.nav.stop();
            if (r != Nav.R.ARRIVED) return R.RUNNING;
        }
        // 2) 캘 블록 찾기
        if (target != null && !block.test(l.getBlockState(target))) {
            target = null;
        }
        if (target == null || ++targetTicks > 20 * 30) {
            if (target != null) bad.add(target.asLong());
            targetTicks = 0;
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
        double reach = p.getEyePosition().distanceTo(Vec3.atCenterOf(target));
        if (reach <= 4.2 && canSee(l, p, target)) {
            bot.nav.stop();
            if (!Act.dig(bot, target)) bad.add(target.asLong());
            return R.RUNNING;
        }
        Act.stopDig(bot);
        Nav.R r = bot.nav.go(new Goal.Reach(target, 3.8));
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

    private BlockPos find(LocalPlayer p, ClientLevel l) {
        BlockPos c = PathExec.feet(p);
        BlockPos best = null;
        double bs = 1e9;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int r = radius;
        for (int dy = -8; dy <= 10; dy++) {
            int y = c.getY() + dy;
            if (y < minY) continue;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, y, c.getZ() + dz);
                    BlockState s = l.getBlockState(m);
                    if (!block.test(s) || bad.contains(m.asLong())) continue;
                    if (needTool && BlockUtil.breakTicks(p, l, m, s, true, null) >= BlockUtil.INF) continue;
                    if (BlockUtil.fluidNear(l, m.getX(), m.getY(), m.getZ(), true)) continue;
                    // 드러난 블록 우선
                    boolean exposed = false;
                    for (Direction d : Direction.values()) if (l.getBlockState(m.relative(d)).isAir()) { exposed = true; break; }
                    double score = Math.sqrt(dx * dx + dz * dz + dy * dy * 2.0) + (exposed ? 0 : 6) + (dy < -1 ? 3 : 0);
                    if (score < bs) { bs = score; best = m.immutable(); }
                }
        }
        return best;
    }
}
