package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.BlockUtil;
import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Miner;
import net.damoon.bot.Nav;
import net.damoon.bot.Scan;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 흑요석 모으기: 용암 원천 옆에 물을 부어 흑요석으로 바꾸고, 물은 다시 퍼 담고, 다이아 곡괭이로 캠.
 * 떨어진 흑요석이 용암에 타지 않게 아래가 단단한 것만 캠.
 */
public class ObsidianTask extends Task {
    private final int want;
    private Miner miner;
    private BlockPos lava, water;
    private int phase, wait, tries;
    private final Set<Long> badLava = new HashSet<>();
    private String detail = "";

    public ObsidianTask(int want) { this.want = want; }

    @Override public String name() { return "흑요석"; }
    @Override public String detail() { return detail; }

    /** 양동이를 들고 그 지점을 바라본 뒤 사용 (바라보는 방향으로 물/용암을 퍼거나 부음) */
    public static boolean useBucket(Bot bot, Item bucket, Vec3 at) {
        LocalPlayer p = bot.p();
        if (!p.isUsingItem() && !Inv.select(p, Inv.is(bucket))) return false;
        bot.ctl.lookAt(p, at);
        bot.ctl.speed = 25f;
        if (!bot.ctl.aimed(p, 1.0f)) return false;
        Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean safeObsidian(ClientLevel l, BlockPos b) {
        BlockState below = l.getBlockState(b.below());
        return below.getFluidState().isEmpty() && !below.isAir() && !BlockUtil.fluidNear(l, b.getX(), b.getY(), b.getZ(), true);
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        int have = Inv.count(p, Items.OBSIDIAN);
        if (have >= want && water == null) return S.DONE;
        if (wait > 0) { wait--; return S.RUNNING; }

        // 물을 부어 놨으면 다시 퍼 담기
        if (water != null) {
            if (!l.getFluidState(water).is(FluidTags.WATER) || !l.getFluidState(water).isSource()) { water = null; return S.RUNNING; }
            detail = "물 다시 퍼 담는 중";
            if (Act.reach(p, new net.minecraft.world.phys.AABB(water)) > 4.0) {
                bot.nav.go(new Goal.Reach(water, 3.0));
                return S.RUNNING;
            }
            bot.nav.stop();
            if (useBucket(bot, Items.BUCKET, Vec3.atCenterOf(water).add(0, 0.3, 0))) { wait = 5; if (++tries > 30) water = null; }
            return S.RUNNING;
        }

        // 캘 수 있는 흑요석이 있으면 캠
        int needMore = want - have;
        List<BlockPos> obs = Scan.find(l, PathExec.feet(p), 2, p.getBlockY() - 16, p.getBlockY() + 16, s -> s.is(Blocks.OBSIDIAN), b -> safeObsidian(l, b), 32);
        if (!obs.isEmpty()) {
            if (miner == null) miner = new Miner(s -> s.is(Blocks.OBSIDIAN), Inv.is(Items.OBSIDIAN), want, true);
            miner.below = 16;
            miner.above = 16;
            Miner.R r = miner.tick(bot);
            detail = "흑요석 캐는 중 " + miner.info;
            if (r == Miner.R.FAILED) miner = null;
            return S.RUNNING;
        }
        // 물 양동이 필요
        if (!Inv.has(p, Items.WATER_BUCKET)) {
            detail = "물 뜨러 가는 중";
            BlockPos src = Scan.nearest(l, PathExec.feet(p), 3, p.getBlockY() - 24, p.getBlockY() + 40,
                    s -> s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource(), b -> l.getBlockState(b.above()).isAir());
            if (src == null) return S.FAILED;
            if (Act.reach(p, new net.minecraft.world.phys.AABB(src)) > 4.0) {
                Nav.R r = bot.nav.go(new Goal.Reach(src, 3.0));
                if (r == Nav.R.FAILED) { bot.nav.stop(); return S.FAILED; }
                return S.RUNNING;
            }
            bot.nav.stop();
            if (useBucket(bot, Items.BUCKET, Vec3.atCenterOf(src).add(0, 0.4, 0))) wait = 5;
            return S.RUNNING;
        }
        // 용암 원천 찾기: 아래가 단단하고 위가 비어 있는 것
        if (lava == null || !isLavaSource(l, lava)) {
            lava = Scan.nearest(l, PathExec.feet(p), 3, p.getBlockY() - 20, p.getBlockY() + 20,
                    s -> s.getFluidState().is(FluidTags.LAVA) && s.getFluidState().isSource(),
                    b -> !badLava.contains(b.asLong()) && l.getBlockState(b.above()).isAir()
                            && l.getBlockState(b.below()).getFluidState().isEmpty() && !l.getBlockState(b.below()).isAir()
                            && lavaNeighbors(l, b) >= 1);
            if (lava == null) { detail = "용암 웅덩이를 못 찾음"; return S.FAILED; }
            tries = 0;
        }
        detail = "용암에 물 붓기 (흑요석 " + have + "/" + want + ")";
        if (Act.reach(p, new net.minecraft.world.phys.AABB(lava)) > 3.8) {
            Nav.R r = bot.nav.go(new Goal.Reach(lava, 3.2));
            if (r == Nav.R.FAILED) { bot.nav.stop(); badLava.add(lava.asLong()); lava = null; }
            return S.RUNNING;
        }
        bot.nav.stop();
        // 용암 아래 블록의 윗면을 겨냥 → 물이 그 용암 자리에 놓이고 옆 용암 원천들이 흑요석이 됨
        Vec3 aim = Vec3.atCenterOf(lava.below()).add(0, 0.5, 0);
        if (useBucket(bot, Items.WATER_BUCKET, aim)) {
            water = lava;
            lava = null;
            wait = 12;
            tries = 0;
        } else if (++tries > 80) {
            badLava.add(lava.asLong());
            lava = null;
        }
        return S.RUNNING;
    }

    private static boolean isLavaSource(ClientLevel l, BlockPos b) {
        return l.getFluidState(b).is(FluidTags.LAVA) && l.getFluidState(b).isSource();
    }

    private static int lavaNeighbors(ClientLevel l, BlockPos b) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = b.relative(d);
            if (isLavaSource(l, q) && !l.getBlockState(q.below()).isAir() && l.getBlockState(q.below()).getFluidState().isEmpty()) n++;
        }
        return n;
    }
}
