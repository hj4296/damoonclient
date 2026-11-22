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
    public static final int RODS = 7, PEARLS = 14;
    private BlockPos portal;
    private Hunter blazes, endermen;
    private Miner rack;
    private BlockPos exploreTo;
    private int exploreLeg, exploreTicks;
    private String detail = "";
    private BlockPos fortWalk;
    private net.damoon.bot.EnderTrap trap;
    private CraftTask boatCraft;
    private CraftTask shieldCraft;
    private boolean foodTrip, shieldTrip, spareTrip;
    private static int nextSpareTrip;
    private int shieldRetry;
    private boolean boatTried;
    private BlockPos safeSpot;
    private net.damoon.bot.BlazeBunker bunker, quick;
    private int quickAge;
    private boolean bunkerFailed;
    private boolean healing;
    private int fortTicks, rackGiveUp;
    private EnderHutTask hut;
    private boolean pearlGiveUp;
    private int pearlStart, hutFails, warpedTicks, warpedFails, waitEnders;
    private BlockPos warped;
    private boolean warpedHere;
    private int hutRetry;
    private final java.util.Set<Long> visited = new java.util.HashSet<>();

    @Override public String name() { return "네더"; }
    @Override public String detail() { return detail; }

    public static int rods(LocalPlayer p) {
        // 막대 1개 = 가루 2개, 엔더의 눈 1개에 가루 1개 (올림)
        return Inv.count(p, Items.BLAZE_ROD) + (Inv.count(p, Items.BLAZE_POWDER) + Inv.count(p, Items.ENDER_EYE) + 1) / 2;
    }

    public static int pearls(LocalPlayer p) {
        return Inv.count(p, Items.ENDER_PEARL) + Inv.count(p, Items.ENDER_EYE);
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (l.dimension() != Level.NETHER) return S.DONE;
        bot.reflex.retreat = false;
        if (portal == null) {
            BlockPos f = PathExec.feet(p);
            portal = Scan.nearest(l, f, 1, f.getY() - 4, f.getY() + 4, s -> s.is(Blocks.NETHER_PORTAL), b -> !l.getBlockState(b.below()).is(Blocks.NETHER_PORTAL));
            if (portal != null) {
                net.damoon.bot.Memory.putPos("netherPortal", portal);
                bot.log("네더 도착! 돌아갈 포털: " + portal.toShortString());
            } else {
                // 포털 옆이 아님 (게임을 다시 켬) → 적어 둔 포털 자리
                portal = net.damoon.bot.Memory.getPos("netherPortal");
                if (portal == null) portal = f;
                bot.log("돌아갈 포털 (기억): " + portal.toShortString());
            }
        }
        // 먹을 게 하나도 없고 체력이 저절로 안 차면 (배고픔 17 이하) 오버월드로 돌아가 사냥해 옴
        // 음식이 3개 이하로 줄면 다 떨어지기 전에 (싸우는 동안 금방 줄어듦) 돌아가서 채워 옴
        if (BeatGameTask.foodCount(p) <= 3 || foodTrip && BeatGameTask.foodCount(p) < 8) {
            if (!foodTrip) { foodTrip = true; bot.log("먹을 게 없음 → 포털로 돌아가 오버월드에서 음식을 구해 옴"); }
            bot.reflex.camp = false;
            bot.reflex.building = false;
            detail = "먹을 게 없음 → 포털로 돌아가는 중";
            bot.reflex.retreat = true;
            Nav.R nr = bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
            if (nr == Nav.R.FAILED) bot.nav.stop();
            return S.RUNNING;
        }
        foodTrip = false;
        // 방패가 부서졌으면 (화염구·화살을 막다 닳음) 판자와 철로 바로 새로 만듦 — 방패 없이 블레이즈와 싸우면 불에 탐
        boolean hasShield = p.getOffhandItem().is(Items.SHIELD) || Inv.has(p, Items.SHIELD);
        if (shieldCraft != null || (!hasShield && bot.ticks > shieldRetry && Inv.count(p, Items.IRON_INGOT) >= 1
                && Inv.count(p, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 6 && !bot.reflex.turtle.sealed() && calmAround(l, p))) {
            if (shieldCraft == null) { shieldCraft = new CraftTask(Items.SHIELD, 1); bot.log("방패가 부서짐 → 새로 만듦"); }
            S r = shieldCraft.tick(bot);
            detail = "방패 새로 만드는 중";
            if (r != S.RUNNING) {
                if (r == S.FAILED) shieldRetry = bot.ticks + 20 * 30;
                shieldCraft = null;
            }
            return S.RUNNING;
        }
        // 방패가 없고 새로 만들 재료(판자 6·철 1)도 없으면: 방패 없이 블레이즈와 싸우지 않고 오버월드로 돌아가 만들어 옴
        boolean canCraftShield = Inv.count(p, Items.IRON_INGOT) >= 1 && Inv.count(p, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 6;
        // 예비 방패가 없는데 만들 재료도 없으면 (블레이즈 싸움 중 부서지면 끝): 아직 막대를 다 못 모았으면 한 번 돌아가서 예비를 만들어 옴 (20분에 한 번까지)
        int shieldsAll = Inv.count(p, Items.SHIELD); // 왼손 포함
        if (!shieldTrip && hasShield && shieldsAll < 2 && !canCraftShield && bot.ticks > nextSpareTrip && Inv.count(p, Items.BLAZE_ROD) < RODS
                && !bot.reflex.turtle.sealed()) {
            nextSpareTrip = bot.ticks + 20 * 60 * 10;
            spareTrip = true;
            bot.log("예비 방패가 없고 만들 재료도 없음 → 오버월드로 돌아가 예비 방패를 만들어 옴");
        }
        if ((!hasShield && !canCraftShield || spareTrip) && !bot.reflex.turtle.sealed()) {
            if (!shieldTrip && !spareTrip) { shieldTrip = true; bot.log("방패가 부서졌고 만들 재료가 없음 → 포털로 돌아가 오버월드에서 만들어 옴"); }
            bot.reflex.camp = false;
            bot.reflex.building = false;
            bot.reflex.retreat = true;
            detail = "방패 재료 없음 → 포털로 돌아가는 중";
            Nav.R nr = bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
            if (nr == Nav.R.FAILED) bot.nav.stop();
            return S.RUNNING;
        }
        shieldTrip = false;
        if (!hasShield && Inv.count(p, Items.IRON_INGOT) >= 1 && Inv.count(p, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 6
                && !bot.reflex.turtle.sealed() && !calmAround(l, p)) {
            // 방패 없이 싸우지 않고, 조용한 곳(처음 스포너를 본 자리나 포털 쪽)으로 물러나서 만듦
            bot.reflex.camp = false;
            bot.reflex.building = false;
            BlockPos to = safeSpot != null ? safeSpot : portal;
            detail = "방패가 없음 → 조용한 곳으로 물러나 방패 만들기";
            bot.reflex.retreat = true;
            Nav.R nr = bot.nav.go(new Goal.Near(to, 3));
            if (nr != Nav.R.RUNNING) { bot.nav.stop(); safeSpot = portal; }
            return S.RUNNING;
        }
        // 다리 놓을 블록이 모자라면 네더랙 캐기
        if ((Inv.count(p, Inv.throwaway()) < 24 && bot.ticks > rackGiveUp) || rack != null) {
            if (rack == null) {
                // 네더랙(나일리움도 캐면 네더랙), 블랙스톤, 현무암, 영혼 흙 — 무엇이든 발판용
                rack = new Miner(s -> s.is(Blocks.NETHERRACK) || s.is(Blocks.WARPED_NYLIUM) || s.is(Blocks.CRIMSON_NYLIUM)
                        || s.is(Blocks.BLACKSTONE) || s.is(Blocks.BASALT) || s.is(Blocks.SOUL_SOIL),
                        Inv.throwaway(), Inv.count(p, Inv.throwaway()) + 64, true);
                rack.radius = 40;
                rack.chunks = 3;
                rack.below = 12;
                rack.above = 12;
            }
            Miner.R r = rack.tick(bot);
            detail = "다리용 네더랙 캐는 중 " + rack.info;
            if (r == Miner.R.FAILED) {
                rack = null;
                // 블록이 조금이라도 있으면 일단 진행, 없으면 20초 돌아다닌 뒤 다시 찾음
                rackGiveUp = bot.ticks + (Inv.count(p, Inv.throwaway()) >= 8 ? 20 * 120 : 20 * 20);
            } else if (r != Miner.R.RUNNING) rack = null;
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
            // 이동 중에 블레이즈가 쏘아 대면 그 자리에 바로 피난처를 지음 (블레이즈 쪽만 열어 둠)
            if (quick == null && Inv.count(p, Inv.throwaway()) >= 12) {
                net.minecraft.world.entity.monster.Blaze shooter = null;
                double sd = 1e9;
                for (var e : l.entitiesForRendering())
                    if (e instanceof net.minecraft.world.entity.monster.Blaze b && b.isAlive() && b.distanceTo(p) < 20 && p.hasLineOfSight(b) && b.distanceTo(p) < sd) { sd = b.distanceTo(p); shooter = b; }
                if (shooter != null && false) { // 피난처 대신 위급할 땐 터틀링, 평소엔 이동하며 활로 저격
                    var here = net.damoon.bot.BlazeBunker.here(l, p, shooter.blockPosition());
                    if (here != null && (bunker == null || !here.cell().equals(bunker.cell()))) {
                        quick = here;
                        bot.log("블레이즈 공격 → 이 자리에 피난처를 지음");
                    }
                }
            }
            if (quick != null) {
                quickAge++;
                if ((quick.calmFor(bot, 20 * 10) && p.getHealth() >= 18) || (quickAge > 20 * 60 && p.getHealth() >= 16)) {
                    quickAge = 0;
                    bot.log("블레이즈가 없음 → 피난처에서 나와 전진");
                    quick = null;
                    bot.reflex.camp = false;
                    bot.reflex.building = false;
                } else {
                    net.damoon.bot.BlazeBunker.R qr = quick.tick(bot, rods, RODS);
                    detail = "[피난처] " + quick.info;
                    if (qr == net.damoon.bot.BlazeBunker.R.FAILED) quick = null;
                    return S.RUNNING;
                }
            }
            // 판단: 블록이 충분하고 벙커 자리가 있으면 벙커 전술, 아니면 돌격 전술
            if (spawner != null && bunker == null && !bunkerFailed && Inv.count(p, Inv.throwaway()) >= 14) {
                bunker = new net.damoon.bot.BlazeBunker(spawner);
                bot.log("판단: 벙커 전술 (블록 " + Inv.count(p, Inv.throwaway()) + "개)");
            }
            if (bunker != null) {
                // 피난처 안에서는 그 자리 피난처가 블레이즈를 상대 (스포너 근처 벙커면 계속 머묾)
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
        bot.reflex.building = false;
        bot.reflex.forceEat = false;
        // 2) 엔더 진주: 근처에 뒤틀린 숲(엔더맨이 많고 가스트가 없음)이 있으면 거기에 엔더맨 굴을 짓고 모음
        if (pearls < PEARLS && !pearlGiveUp) {
            if (pearlStart == 0) pearlStart = bot.ticks;
            if (bot.ticks - pearlStart > 20 * 60 * 15) { pearlGiveUp = true; bot.log("네더에서 진주를 충분히 못 모음 → 오버월드에서 마저"); }
            if (hut != null) {
                S r = hut.tick(bot);
                detail = "[엔더맨 굴] " + hut.detail();
                if (r == S.RUNNING) return S.RUNNING;
                hut = null;
                if (r == S.FAILED) hutFails++;
                return S.RUNNING;
            }
            int enders = 0;
            for (var e : l.entitiesForRendering()) if (e instanceof EnderMan && e.distanceTo(p) < 40) enders++;
            BlockPos f0 = PathExec.feet(p);
            boolean onWarped = l.getBlockState(f0.below()).is(Blocks.WARPED_NYLIUM);
            // 보트가 없으면 판자로 만듦 (보트에 태운 엔더맨은 순간이동·이동을 못 함)
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
            if (false && enders >= 2 && (onWarped || warpedHere) && Inv.count(p, Inv.throwaway()) >= 12 && bot.ticks > hutRetry) {
                // 머리 위 3x3 지붕을 얹고 그 아래에서 엔더맨을 불러 잡음 (엔더맨은 지붕 밑 옆 칸에 못 섬)
                warpedHere = true;
                hut = new EnderHutTask(PEARLS);
                hutRetry = bot.ticks + 20 * 20;
                return S.RUNNING;
            }
            if (enders >= 1 && (onWarped || warpedHere)) {
                // 쳐다봐서 한 마리씩 불러 방패 반격으로 잡음
                if (!warpedHere) bot.log("뒤틀린 숲에 엔더맨 " + enders + "마리 → 한 마리씩 쳐다봐서 불러 잡기");
                warpedHere = true;
                if (trap == null) trap = new net.damoon.bot.EnderTrap();
                trap.stare = !net.damoon.bot.EnderTrap.hasBoat(p) && trapBoatLost();
                net.damoon.bot.EnderTrap.R tr = trap.tick(bot);
                detail = "엔더 진주 " + pearls + "/" + PEARLS + " — " + trap.info;
                if (tr == net.damoon.bot.EnderTrap.R.RUNNING) return S.RUNNING;
            }
            if (warped == null || warpedTicks++ > 20 * 90) {
                warpedTicks = 0;
                warped = Scan.nearest(l, f0, 6, 32, 120, st -> st.is(Blocks.WARPED_NYLIUM),
                        b -> l.getBlockState(b.above()).isAir() && l.getBlockState(b.above(2)).isAir() && b.distSqr(f0) > 9);
                if (warped == null) { pearlGiveUp = true; bot.log("근처에 뒤틀린 숲이 없음 → 오버월드에서 진주"); return S.RUNNING; }
                bot.log("뒤틀린 숲으로 (엔더맨 사냥) → " + warped.toShortString());
            }
            detail = "뒤틀린 숲으로 가는 중 (진주 " + pearls + "/" + PEARLS + ")";
            Nav.R nr = bot.nav.go(new Goal.Near(warped.above(), 2));
            if (nr == Nav.R.FAILED) { bot.nav.stop(); warped = null; if (++warpedFails > 4) pearlGiveUp = true; }
            if (nr == Nav.R.ARRIVED) {
                bot.nav.stop();
                if (enders < 2) { detail = "뒤틀린 숲에서 엔더맨 기다리는 중"; if (++waitEnders > 20 * 60) { warped = null; waitEnders = 0; } }
                else if (Inv.count(p, Inv.throwaway()) >= 16) hut = new EnderHutTask(PEARLS);
            }
            return S.RUNNING;
        }
        if (false && pearls < PEARLS) {
            // (진주는 오버월드에서 구함) 보트가 없으면 판자 5개로 만듦 (엔더맨을 보트에 태워 순간이동을 막는 함정)
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
        bot.reflex.retreat = true;
        bot.reflex.camp = false;
        bot.reflex.building = false;
        Nav.R nr = bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
        if (nr == Nav.R.FAILED) bot.nav.stop();
        return S.RUNNING;
    }

    private boolean trapBoatLost() { return true; }

    /** 블레이즈 20칸·다른 적 12칸 안에 없음 (작업대를 펴고 만들 수 있을 만큼 안전) */
    private static boolean calmAround(ClientLevel l, LocalPlayer p) {
        for (var e : l.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) continue;
            if (e instanceof Blaze && e.distanceTo(p) < 20) return false;
            if (net.damoon.bot.Reflex.hostile(e) && e.distanceTo(p) < 12) return false;
        }
        return true;
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
