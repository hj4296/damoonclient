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
    private net.damoon.bot.EnderTrap trap;
    private CraftTask boatCraft;
    private boolean boatTried;
    private BlockPos safeSpot;
    private net.damoon.bot.BlazeBunker bunker;
    private boolean bunkerFailed;
    private boolean healing;
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
            portal = Scan.nearest(l, f, 1, f.getY() - 4, f.getY() + 4, s -> s.is(Blocks.NETHER_PORTAL), b -> !l.getBlockState(b.below()).is(Blocks.NETHER_PORTAL));
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
        // 네더에선 배고픔을 가득 채워 두어 체력이 계속 회복되게
        bot.reflex.forceEat = p.getHealth() < 18;
        // 1) 블레이즈 막대
        if (rods < RODS) {
            BlockPos f = PathExec.feet(p);
            BlockPos spawner = Scan.nearest(l, f, 6, l.getMinBuildHeight(), l.getMaxBuildHeight(), s -> s.is(Blocks.SPAWNER), b -> isBlazeSpawner(l, b));
            if (blazes == null) blazes = new Hunter(e -> e instanceof Blaze, Inv.is(Items.BLAZE_ROD));
            blazes.range = 24;
            if (spawner != null && safeSpot == null) {
                // 처음 스포너를 본 자리(아직 멀리 있을 때)를 안전한 후퇴 지점으로 기억
                safeSpot = PathExec.feet(p);
                if (safeSpot.distSqr(spawner) < 18 * 18) safeSpot = portal;
            }
            // 판단: 블록이 충분하고 벙커 자리가 있으면 벙커 전술, 아니면 돌격 전술
            if (spawner != null && bunker == null && !bunkerFailed && Inv.count(p, Inv.throwaway()) >= 14) {
                bunker = new net.damoon.bot.BlazeBunker(spawner);
                bot.log("판단: 벙커 전술 (블록 " + Inv.count(p, Inv.throwaway()) + "개)");
            }
            if (bunker != null) {
                net.damoon.bot.BlazeBunker.R br = bunker.tick(bot, rods, RODS);
                detail = "[벙커] " + bunker.info;
                if (br == net.damoon.bot.BlazeBunker.R.FAILED) {
                    bunker = null;
                    bunkerFailed = true;
                    bot.reflex.camp = false;
                    bot.log("판단: 벙커 자리가 없음 → 돌격 전술 (방패 들고 칼)");
                }
                return S.RUNNING;
            }
            // 체력이 낮으면 후퇴해서 먹고 회복한 뒤 돌아옴
            if (healing || (spawner != null && p.getHealth() < 13)) {
                if (!healing) bot.log("체력 " + (int) p.getHealth() + " → 물러나서 회복");
                healing = p.getHealth() < 19;
                bot.reflex.forceEat = healing;
                bot.reflex.camp = false;
                if (healing) {
                    detail = "물러나서 회복하는 중 (체력 " + (int) p.getHealth() + ")";
                    BlockPos to = safeSpot != null ? safeSpot : portal;
                    Nav.R nr = bot.nav.go(new Goal.Near(to, 3));
                    if (nr == Nav.R.FAILED) bot.nav.stop();
                    if (p.getFoodData().getFoodLevel() >= 20 && nr == Nav.R.ARRIVED) bot.ctl.sneak = true;
                    return S.RUNNING;
                }
                bot.log("회복 완료 → 다시 블레이즈 잡으러");
            }
            if (spawner != null) {
                // 블레이즈는 날아다니므로 쫓아가지 않고 스포너 근처에 서서 다가오는 블레이즈를 상대 (반사 행동이 싸움)
                for (var e : l.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.getItem().is(Items.BLAZE_ROD) && ie.distanceTo(p) < 12) {
                        detail = "블레이즈 막대 줍는 중 (" + rods + "/" + RODS + ")";
                        Nav.R nr = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0));
                        if (nr == Nav.R.ARRIVED) { bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; }
                        if (nr == Nav.R.FAILED) bot.nav.stop();
                        return S.RUNNING;
                    }
                }
                // 스포너 바로 옆에 붙어 서서 생기는 블레이즈를 바로 벰
                detail = "블레이즈 스포너 옆에서 기다리는 중 (" + rods + "/" + RODS + ")";
                // 활이 있으면 스포너에서 조금 떨어져(약 8칸) 쏘고, 없으면 바로 옆에 붙음
                boolean bow = Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW);
                double want = bow ? 9 : 2.6;
                if (bow && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(spawner)) <= want * want) {
                    bot.reflex.camp = true;
                    bot.nav.stop();
                    bot.ctl.lookAt(p, net.minecraft.world.phys.Vec3.atCenterOf(spawner).add(0, 1, 0));
                    return S.RUNNING;
                }
                if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(spawner)) > want * want) {
                    bot.reflex.camp = false;
                    Nav.R nr = bot.nav.go(bow ? new Goal.Near(spawner, 7) : new Goal.Reach(spawner, 1.5));
                    if (nr == Nav.R.FAILED) { bot.nav.stop(); bot.nav.go(new Goal.Near(spawner, 3)); }
                } else {
                    bot.reflex.camp = true;
                    bot.nav.stop();
                    bot.ctl.lookAt(p, net.minecraft.world.phys.Vec3.atCenterOf(spawner).add(0, 1, 0));
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
        bot.reflex.camp = false;
        bot.reflex.forceEat = false;
        // 2) 엔더 진주
        if (pearls < PEARLS) {
            // 보트가 없으면 판자 5개로 만듦 (엔더맨을 보트에 태워 순간이동을 막는 함정)
            if (!net.damoon.bot.EnderTrap.hasBoat(p) && boatCraft == null && net.damoon.bot.EnderTrap.boatFor(p) != null && !boatTried) {
                boatCraft = new CraftTask(net.damoon.bot.EnderTrap.boatFor(p), 1);
                boatTried = true;
            }
            if (boatCraft != null) {
                S r = boatCraft.tick(bot);
                detail = "엔더맨 함정용 보트 만드는 중";
                if (r != S.RUNNING) boatCraft = null;
                return S.RUNNING;
            }
            if (trap == null) trap = new net.damoon.bot.EnderTrap();
            net.damoon.bot.EnderTrap.R r = trap.tick(bot);
            detail = "엔더 진주 " + pearls + "/" + PEARLS + " — " + trap.info;
            if (r == net.damoon.bot.EnderTrap.R.NONE) return explore(bot, "엔더맨 찾기");
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
