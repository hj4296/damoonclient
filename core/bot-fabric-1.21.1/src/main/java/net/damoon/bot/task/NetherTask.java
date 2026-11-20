package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Hunter;
import net.damoon.bot.Inv;
import net.damoon.bot.Miner;
import net.damoon.bot.Nav;
import net.damoon.bot.Scan;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/**
 * 네더: 요새를 찾아 블레이즈 막대를 모으고, 엔더맨을 잡아 엔더 진주를 모은 뒤 들어온 포털로 돌아감.
 */
public class NetherTask extends Task {
    public static final int RODS = 7, PEARLS = 13;
    private BlockPos portal;
    private Hunter blazes, endermen;
    private Miner rack;
    private BlockPos exploreTo;
    private int exploreLeg, exploreTicks;
    private String detail = "";
    private BlockPos fortWalk;
    private int fortTicks;
    private final java.util.Set<Long> visited = new java.util.HashSet<>();

    @Override public String name() { return "네더"; }
    @Override public String detail() { return detail; }

    public static int rods(LocalPlayer p) {
        return Inv.count(p, Items.BLAZE_ROD) + (Inv.count(p, Items.BLAZE_POWDER) + Inv.count(p, Items.ENDER_EYE)) / 2;
    }

    public static int pearls(LocalPlayer p) {
        return Inv.count(p, Items.ENDER_PEARL) + Inv.count(p, Items.ENDER_EYE);
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (l.dimension() != Level.NETHER) return S.DONE;
        if (portal == null) {
            BlockPos f = PathExec.feet(p);
            portal = Scan.nearest(l, f, 1, f.getY() - 4, f.getY() + 4, s -> s.is(Blocks.NETHER_PORTAL), null);
            if (portal == null) portal = f;
            bot.log("네더 도착! 돌아갈 포털: " + portal.toShortString());
        }
        // 다리 놓을 블록이 모자라면 네더랙 캐기
        if (Inv.count(p, Inv.throwaway()) < 24 || rack != null) {
            if (rack == null) rack = new Miner(s -> s.is(Blocks.NETHERRACK), Inv.is(Items.NETHERRACK), Inv.count(p, Items.NETHERRACK) + 64, true);
            Miner.R r = rack.tick(bot);
            detail = "다리용 네더랙 캐는 중 " + rack.info;
            if (r != Miner.R.RUNNING) rack = null;
            return S.RUNNING;
        }
        int rods = rods(p), pearls = pearls(p);
        // 1) 블레이즈 막대
        if (rods < RODS) {
            BlockPos f = PathExec.feet(p);
            BlockPos spawner = Scan.nearest(l, f, 6, l.getMinBuildHeight(), l.getMaxBuildHeight(), s -> s.is(Blocks.SPAWNER), b -> isBlazeSpawner(l, b));
            if (blazes == null) blazes = new Hunter(e -> e instanceof Blaze, Inv.is(Items.BLAZE_ROD));
            blazes.range = 24;
            if (spawner != null) {
                Hunter.R r = blazes.tick(bot);
                detail = "블레이즈 막대 " + rods + "/" + RODS + " — " + blazes.info;
                if (r == Hunter.R.NONE) {
                    detail = "블레이즈 스포너 옆에서 기다리는 중 (" + rods + "/" + RODS + ")";
                    Nav.R nr = bot.nav.go(new Goal.Near(spawner, 4));
                    if (nr == Nav.R.FAILED) bot.nav.stop();
                }
                return S.RUNNING;
            }
            // 요새 벽돌 위(걸을 수 있는 곳)를 따라 요새 안을 돌아다니며 스포너를 찾음
            if (fortWalk == null || ++fortTicks > 20 * 50) {
                fortTicks = 0;
                java.util.List<BlockPos> bricks = Scan.find(l, f, 5, 20, 110, s -> s.is(Blocks.NETHER_BRICKS),
                        b -> l.getBlockState(b.above()).isAir() && l.getBlockState(b.above(2)).isAir() && !visited.contains(b.asLong() >> 6), 400);
                BlockPos pick = null;
                double best = -1;
                for (BlockPos b : bricks) {
                    double d = Math.sqrt(b.distSqr(f));
                    if (d < 12) continue;
                    double score = Math.min(d, 60) - Math.abs(b.getY() - f.getY()) * 2 + p.getRandom().nextDouble() * 15;
                    if (score > best) { best = score; pick = b; }
                }
                fortWalk = pick;
                if (pick != null) bot.log("요새 안을 돌아다니며 스포너 찾기 → " + pick.toShortString());
            }
            if (fortWalk != null) {
                detail = "요새 안에서 블레이즈 스포너 찾는 중";
                Nav.R nr = bot.nav.go(new Goal.Near(fortWalk.above(), 2));
                if (nr != Nav.R.RUNNING) {
                    visited.add(fortWalk.asLong() >> 6);
                    fortWalk = null;
                    bot.nav.stop();
                }
                return S.RUNNING;
            }
            return explore(bot, "요새 찾기");
        }
        // 2) 엔더 진주
        if (pearls < PEARLS) {
            if (endermen == null) endermen = new Hunter(e -> e instanceof EnderMan, Inv.is(Items.ENDER_PEARL));
            endermen.range = 40;
            Hunter.R r = endermen.tick(bot);
            detail = "엔더 진주 " + pearls + "/" + PEARLS + " — " + endermen.info;
            if (r == Hunter.R.NONE) return explore(bot, "엔더맨 찾기");
            return S.RUNNING;
        }
        // 3) 포털로 돌아가기
        detail = "포털로 돌아가는 중";
        Nav.R nr = bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
        if (nr == Nav.R.FAILED) bot.nav.stop();
        return S.RUNNING;
    }

    private static boolean isBlazeSpawner(ClientLevel l, BlockPos b) {
        BlockEntity be = l.getBlockEntity(b);
        if (!(be instanceof SpawnerBlockEntity sp)) return false;
        var e = sp.getSpawner().getOrCreateDisplayEntity(l, b);
        return e instanceof Blaze;
    }

    private S explore(Bot bot, String why) {
        LocalPlayer p = bot.p();
        if (exploreTo == null || ++exploreTicks > 20 * 90) {
            // 요새는 남북·동서 띠 모양으로 많이 나옴 → 한 방향으로 길게 감
            int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
            int[] d = dirs[exploreLeg++ % 4];
            exploreTo = new BlockPos((int) p.getX() + d[0] * 160, 0, (int) p.getZ() + d[1] * 160);
            exploreTicks = 0;
            bot.log("네더 탐험 (" + why + ") → " + exploreTo.getX() + ", " + exploreTo.getZ());
        }
        detail = "탐험 중: " + why;
        Nav.R r = bot.nav.go(new Goal.NearXZ(exploreTo.getX(), exploreTo.getZ(), 8));
        if (r != Nav.R.RUNNING) { exploreTo = null; bot.nav.stop(); }
        return S.RUNNING;
    }
}
