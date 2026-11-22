package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Digger;
import net.damoon.bot.Hunter;
import net.damoon.bot.Inv;
import net.damoon.bot.Miner;
import net.damoon.bot.Nav;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 빈손으로 시작해서 엔딩까지: 매 틱 "지금 가장 먼저 필요한 것"을 고르는 계획기.
 * 죽어서 물건을 잃어도 인벤토리를 다시 보고 필요한 단계부터 다시 함.
 */
public class BeatGameTask extends Task {
    private Task sub;
    private String subKey = "";
    private Miner miner;
    private String minerKey = "";
    private Hunter hunter;
    private Digger digger;
    private BlockPos exploreTo;
    private int exploreTicks;
    /** 캘 블록을 못 찾아 탐험하는 동안엔 Miner를 새로 만들지 않음 (만들 때마다 길찾기가 끊기던 문제) */
    private String exploreFor = "";
    private int exploreUntil;
    private net.damoon.bot.PitEscape pit;
    private int netherFoodSince, bowSearch;
    private final Map<String, Integer> cooldown = new HashMap<>();
    private final EndGameTask end = new EndGameTask();
    private NetherTask nether;
    private PortalTask portalTask;
    private Hunter sheep;
    private net.damoon.bot.EnderTrap trap;
    private int portalFails;
    private StrongholdTask stronghold;
    private String stage = "준비";
    private String detail = "";

    @Override public String name() { return "마인크래프트 클리어"; }

    @Override public String detail() {
        String d = sub != null ? sub.detail() : detail;
        return stage + (d.isEmpty() ? "" : " — " + d);
    }

    private boolean cool(Bot bot, String key) { return bot.ticks < cooldown.getOrDefault(key, 0); }

    private void fail(Bot bot, String key, int ticks) { cooldown.put(key, bot.ticks + ticks); }

    /** 나무 모으기 (땅속이면 먼저 땅 위로) — 판자가 모자라 방패 등을 못 만들 때 */
    private S gatherWood(Bot bot, int moreLogs) {
        LocalPlayer p = bot.p();
        BlockPos f = PathExec.feet(p);
        int top = bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
        if (surfaceTrip(bot, f, top, f.getY() < top - 4 && (miner == null || !minerKey.equals("logs") || !miner.busy()))) { detail = "땅 위로 올라가는 중"; return S.RUNNING; }
        Predicate<ItemStack> lg = s -> s.is(ItemTags.LOGS);
        return mine(bot, "logs", s -> s.is(BlockTags.LOGS), lg, Inv.count(p, lg) + moreLogs, false, 6, 20);
    }

    /** 하위 작업 실행 (같은 key면 이어서) */
    private S run(Bot bot, String key, java.util.function.Supplier<Task> make) {
        // 방금 실패한 작업(재료 부족 등)을 틱마다 다시 시도하지 않음:
        // 그 사이 가장 흔한 부족 재료인 나무를 구해 둠 (오버월드), 아니면 잠깐 둘러봄
        if (cool(bot, key) && (sub == null || !subKey.equals(key))) {
            LocalPlayer p = bot.p();
            if (bot.ticks % 200 == 0) bot.log(key + " 대기 중 (재료 부족) → 그동안 " + (bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && planksEq(p) < 24 ? "나무 모으기" : "둘러보기"));
            if (bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && planksEq(p) < 24) {
                detail = "재료 부족 → 나무 모으는 중";
                BlockPos f = PathExec.feet(p);
                int top = bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
                if (surfaceTrip(bot, f, top, f.getY() < top - 4 && (miner == null || !minerKey.equals("logs") || !miner.busy()))) return S.RUNNING;
                Predicate<ItemStack> lg = s -> s.is(ItemTags.LOGS);
                return mine(bot, "logs", s -> s.is(BlockTags.LOGS), lg, Inv.count(p, lg) + 6, false, 6, 20);
            }
            // 네더·엔드에선 아무 데나 돌아다니면 위험 → 그 자리에서 잠깐 기다림 (반사 행동은 계속)
            if (bot.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) { bot.nav.stop(); detail = "잠깐 기다리는 중 (" + key + ")"; return S.RUNNING; }
            return explore(bot, "재료");
        }
        if (sub == null || !subKey.equals(key)) {
            sub = make.get();
            subKey = key;
            bot.nav.stop();
        }
        S s = sub.tick(bot);
        if (s != S.RUNNING) {
            if (s == S.FAILED) { bot.log(key + " 실패 → 잠시 뒤 다시"); fail(bot, key, 20 * 20); }
            sub = null;
            subKey = "";
        }
        return S.RUNNING;
    }

    /** 블록 캐서 모으기 (없으면 탐험) */
    private S mine(Bot bot, String key, Predicate<BlockState> block, Predicate<ItemStack> item, int want, boolean tool, int below, int above) {
        if (miner == null || !minerKey.equals(key)) {
            if (key.equals(exploreFor) && bot.ticks < exploreUntil) return explore(bot, key);
            miner = new Miner(block, item, want, tool);
            miner.below = below;
            miner.above = above;
            if (key.equals(exploreFor)) miner.failAfter = 1;
            minerKey = key;
            bot.nav.stop();
        }
        Miner.R r = miner.tick(bot);
        detail = miner.info;
        if (r == Miner.R.FAILED) {
            miner = null;
            minerKey = "";
            exploreFor = key;
            exploreUntil = bot.ticks + 20 * 15;
            return explore(bot, key);
        }
        if (r == Miner.R.DONE) { miner = null; minerKey = ""; exploreFor = ""; }
        else if (miner.busy()) exploreFor = "";
        return S.RUNNING;
    }

    /** 땅 위로 올라가기: 한 번 시작하면 다 올라갈 때까지 계속 (높이가 경계에서 오르내리며 목표가 매 틱 바뀌던 문제) */
    private int surfaceY = Integer.MIN_VALUE, surfaceUntil;
    private int shieldWoodSince;
    private boolean coalLatch, huntLatch;
    private int huntSince;
    private int waterSeekStart;
    private final java.util.Set<Long> badWaterZone = new java.util.HashSet<>();
    /** 오버월드 쪽 네더 포털 자리 (기억 파일에도 적어 둠) — 멀리 나갔다가 포털을 새로 짓지 않고 돌아오게 */
    private BlockPos owPortal;
    private boolean owPortalLoaded;

    private BlockPos knownOverworldPortal(ClientLevel l) {
        if (!owPortalLoaded) { owPortal = net.damoon.bot.Memory.getPos("overworldPortal"); owPortalLoaded = true; }
        if (owPortal != null && l.isLoaded(owPortal) && !l.getBlockState(owPortal).is(Blocks.NETHER_PORTAL)) {
            BlockPos found = null;
            for (BlockPos q : BlockPos.betweenClosed(owPortal.offset(-2, -2, -2), owPortal.offset(2, 2, 2)))
                if (l.getBlockState(q).is(Blocks.NETHER_PORTAL)) { found = q.immutable(); break; }
            if (found == null) { owPortal = null; net.damoon.bot.Memory.put("overworldPortal", null); }
            else owPortal = found;
        }
        return owPortal;
    }

    private void rememberOverworldPortal(BlockPos b) {
        if (b.equals(owPortal)) return;
        owPortal = b;
        owPortalLoaded = true;
        net.damoon.bot.Memory.putPos("overworldPortal", b);
    }
    private net.damoon.bot.Climb surfClimb;

    /** 땅속인지: 높이 지도보다 4칸 넘게 아래이고, 하늘이 안 보이거나(나뭇잎 아래는 보임) 12칸 넘게 아래 (곧게 판 1칸 굴은 하늘이 보여도 땅속) */
    static boolean underground(ClientLevel l, BlockPos f, int top) {
        if (f.getY() >= top - 4) return false;
        // 머리 위에 나뭇잎·나무·풀만 있으면 숲속일 뿐 땅 위 (빽빽한 숲에선 하늘빛이 어두워 땅속으로 착각하던 문제)
        boolean onlyTrees = true;
        for (int y = f.getY() + 2; y <= top && onlyTrees; y++) {
            var st = l.getBlockState(new BlockPos(f.getX(), y, f.getZ()));
            if (st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)
                    || st.canBeReplaced() || !st.getFluidState().isEmpty() && false) continue;
            onlyTrees = false;
        }
        if (onlyTrees) return false;
        return l.getBrightness(net.minecraft.world.level.LightLayer.SKY, f) < 12 || f.getY() < top - 12;
    }

    private boolean surfaceTrip(Bot bot, BlockPos f, int top, boolean need) {
        // 하늘이 보이면(하늘빛 12 이상) 이미 땅 위 — 나뭇잎·나무 아래라 높이 지도가 위로 잡힌 것 (나무 꼭대기로 올라가려다 멈추던 문제)
        // (공중에 떠 있을 때(점프 중)도 땅속이 아니면 새로 올라가기를 시작하지 않음 — 점프할 때마다 목표가 바뀌며 제자리 반복)
        if (!underground(bot.level(), f, top)) {
            if (surfaceY != Integer.MIN_VALUE && bot.p().onGround()) { surfaceY = Integer.MIN_VALUE; surfClimb = null; bot.nav.stop(); }
            if (surfaceY == Integer.MIN_VALUE) return false;
        }
        if (surfaceY == Integer.MIN_VALUE || bot.ticks > surfaceUntil) {
            surfaceY = Integer.MIN_VALUE;
            if (!need) return false;
            surfaceY = top - 1;
            surfaceUntil = bot.ticks + 20 * 90;
        }
        if (f.getY() >= surfaceY && bot.p().onGround()) { surfaceY = Integer.MIN_VALUE; surfClimb = null; return false; }
        // 깊은 땅속이면 10칸씩 끊어서 올라감 (한 번에 길을 찾으면 너무 오래 걸림), 안 되면 계단 파기
        if (surfClimb != null || f.getY() < surfaceY - 14) {
            if (surfClimb == null) surfClimb = new net.damoon.bot.Climb();
            if (surfClimb.tick(bot, surfaceY)) { surfaceUntil = bot.ticks + 20 * 60; return true; }
            surfClimb = null;
            surfaceY = Integer.MIN_VALUE;
            return false;
        }
        Nav.R r = bot.nav.go(new Goal.Above(surfaceY));
        if (r == Nav.R.FAILED) { bot.nav.stop(); surfaceY = Integer.MIN_VALUE; surfaceUntil = bot.ticks + 20 * 20; }
        if (r == Nav.R.ARRIVED) surfaceY = Integer.MIN_VALUE;
        return r != Nav.R.FAILED;
    }

    /** 멀리 걸어가서 새 지역 보기 */
    private S explore(Bot bot, String why) {
        LocalPlayer p = bot.p();
        if (exploreTo == null || ++exploreTicks > 20 * 60) {
            double a = bot.p().getRandom().nextDouble() * Math.PI * 2;
            exploreTo = new BlockPos((int) (p.getX() + Math.cos(a) * 90), 0, (int) (p.getZ() + Math.sin(a) * 90));
            exploreTicks = 0;
            bot.log("주변에 없음(" + why + ") → 탐험");
        }
        detail = "탐험 중 (" + why + ")";
        Nav.R r = bot.nav.go(new Goal.NearXZ(exploreTo.getX(), exploreTo.getZ(), 6));
        if (r != Nav.R.RUNNING) { exploreTo = null; bot.nav.stop(); }
        return S.RUNNING;
    }

    static int tier(LocalPlayer p) {
        if (Inv.has(p, Items.NETHERITE_PICKAXE) || Inv.has(p, Items.DIAMOND_PICKAXE)) return 4;
        if (Inv.has(p, Items.IRON_PICKAXE)) return 3;
        if (Inv.has(p, Items.STONE_PICKAXE)) return 2;
        if (Inv.has(p, Items.WOODEN_PICKAXE)) return 1;
        return 0;
    }

    private static int planksEq(LocalPlayer p) {
        return Inv.count(p, s -> s.is(ItemTags.PLANKS)) + Inv.count(p, s -> s.is(ItemTags.LOGS)) * 4;
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (l.dimension() == Level.END) {
            stage = "엔드";
            S s = end.tick(bot);
            return s == S.DONE ? S.DONE : S.RUNNING;
        }
        if (bot.ticks % 20 == 0) { Inv.equipArmor(p); Inv.equipShield(p); }
        if (bot.ticks % 20 == 10) Inv.dropJunk(p);
        // 포털 근처에 오면(지었거나 네더에서 돌아옴) 자리를 기억해 둠 — 멀리 나갔다가도 새로 짓지 않고 돌아오게
        // (하위 작업 중에도: 포털을 짓자마자 그 안에 서서 넘어가 버리므로)
        if (l.dimension() == Level.OVERWORLD && bot.ticks % 20 == 3) {
            BlockPos ft = PathExec.feet(p);
            BlockPos np = net.damoon.bot.Scan.nearest(l, ft, 1, ft.getY() - 8, ft.getY() + 8, st -> st.is(Blocks.NETHER_PORTAL), b -> !l.getBlockState(b.below()).is(Blocks.NETHER_PORTAL));
            if (np != null) rememberOverworldPortal(np);
        }
        // 흑요석·포털 짓기 중인데 이미 지은 포털이 있으면 그만둠 (멀리 있을 때 시작했던 경우)
        if ((sub instanceof ObsidianTask || sub instanceof PortalTask) && l.dimension() == Level.OVERWORLD && knownOverworldPortal(l) != null) {
            bot.log("이미 지은 포털이 있음 " + owPortal.toShortString() + " → " + subKey + " 그만두고 그리로");
            sub = null; subKey = ""; bot.nav.stop();
        }
        if (sub != null) { bot.trace = "sub " + subKey; return run(bot, subKey, () -> sub); }
        if (l.dimension() == Level.NETHER) {
            // 네더에서 죽었으면 다시 왔을 때 먼저 떨어뜨린 물건을 찾음 (멀리 있던 동안은 물건 시계가 멈춰 있음)
            S rec = recover(bot, p, l);
            if (rec != null) { detail = stage; return rec; }
            stage = "8. 네더";
            if (nether == null) nether = new NetherTask();
            nether.tick(bot);
            detail = nether.detail();
            return S.RUNNING;
        }
        return overworld(bot, p, l);
    }

    /** 죽은 자리로 돌아가 떨어뜨린 물건 줍기 (아이템은 5분 뒤 사라지므로 4분 안에) */
    private int recoverNear, recoverDeathTick = -1, recoverFails;
    private boolean recoverLoaded, recoverFromMemory;

    private void forgetDeath(Bot bot) {
        bot.deathPos = null;
        net.damoon.bot.Memory.put("deathPos", null);
    }

    private S recover(Bot bot, LocalPlayer p, ClientLevel l) {
        // 게임을 다시 켰으면 적어 둔 죽은 자리를 다시 읽음
        // 처음 한 번 + 그 뒤로도 10초마다 (다른 데서 죽은 자리를 적어 두면 이어서 찾으러 감)
        if (!recoverLoaded || (bot.deathPos == null && bot.ticks % 200 == 0)) {
            recoverLoaded = true;
            if (bot.deathPos == null) {
                BlockPos mp = net.damoon.bot.Memory.getPos("deathPos");
                String dd = net.damoon.bot.Memory.get("deathDim");
                if (mp != null && dd != null) {
                    bot.deathPos = mp;
                    bot.deathDim = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(dd));
                    bot.deathTick = bot.ticks;
                    recoverNear = 20 * 30; // 죽자마자 멀리서 다시 태어나면 거의 안 지났음 (30초로 잡음)
                    recoverFromMemory = true;
                    bot.log("적어 둔 죽은 자리 " + mp.toShortString() + " → 물건 찾으러 감");
                }
            }
        }
        if (bot.deathPos == null || bot.deathDim != l.dimension()) return null;
        if (recoverDeathTick != bot.deathTick) { recoverDeathTick = bot.deathTick; if (!recoverFromMemory) recoverNear = 0; recoverFromMemory = false; recoverFails = 0; }
        BlockPos d = bot.deathPos;
        double dist = Math.sqrt(p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(d)));
        // 떨어진 물건은 그 자리가 플레이어 근처(약 8청크 안)라 움직일 때만 5분 시계가 감 → 멀리서 다시 태어났으면 시간은 멈춰 있음
        if (dist < 130) recoverNear++;
        if (recoverNear > 20 * 60 * 4 + 20 * 30) { bot.log("물건이 사라졌을 시간 → 포기"); forgetDeath(bot); return null; }
        // 용암에 빠져 죽었으면 (죽은 칸·발밑이 용암) 물건은 이미 탔고, 가면 또 빠짐 → 안 감
        if (l.isLoaded(d) && (net.damoon.bot.BlockUtil.isLava(l.getBlockState(d)) || net.damoon.bot.BlockUtil.isLava(l.getBlockState(d.below())))) {
            bot.log("죽은 자리가 용암 → 물건은 포기"); forgetDeath(bot); return null;
        }
        if (dist > 1500) { forgetDeath(bot); return null; }
        // 멀리서 다시 태어났고 맨손이면: 물건은 안 사라지니 먼저 돌 곡괭이·칼만 만들고 감 (맨손으로 땅속 먼 길은 위험)
        // 먼 길이면 돌 곡괭이·칼과 음식 3개는 챙긴 뒤에 감 (빈손·굶주린 채 가다 죽음). 체력이 낮고 먹을 게 없으면 먼저 사냥
        boolean sword = Inv.has(p, Items.STONE_SWORD) || Inv.has(p, Items.IRON_SWORD) || Inv.has(p, Items.DIAMOND_SWORD);
        if (dist > 140 && (tier(p) < 2 || !sword || foodCount(p) < 3)) return null;
        if (foodCount(p) < 1 && (p.getHealth() < 12 || p.getFoodData().getFoodLevel() < 10)) return null;
        // 멀리 가야 하는데 방패·갑옷이 없으면 밤엔 가지 않음 (가다가 스켈레톤·좀비에게 죽음) → 밤엔 숨고 낮에 감
        boolean weakNow = !p.getOffhandItem().is(Items.SHIELD) || !worn(p, Items.IRON_CHESTPLATE);
        if (dist > 40 && weakNow && l.dimension() == Level.OVERWORLD && BedTask.night(l)) return null;
        stage = "죽은 자리로 물건 되찾으러 (" + (int) dist + "칸)";
        if (dist > 6) {
            Nav.R r = bot.nav.go(new Goal.Near(d, 3));
            if (r == Nav.R.FAILED) { bot.nav.stop(); if (++recoverFails >= 5) forgetDeath(bot); }
            return S.RUNNING;
        }
        for (var e : l.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(d)) < 10
                    && lootTime.merge(ie.getId(), 1, Integer::sum) < 20 * 12) {
                detail = "떨어뜨린 " + ie.getItem().getHoverName().getString() + " 줍는 중";
                boolean wet = ie.isInWater() || p.isInWater();
                if (ie.distanceTo(p) < 2.5 || (wet && ie.distanceTo(p) < 10)) {
                    // 가깝거나 물속이면 길찾기 없이 바로 그쪽으로 헤엄침 (물속 길찾기가 자주 막힘)
                    bot.nav.stop();
                    bot.ctl.lookAt(p, ie.position());
                    bot.ctl.fwd = true;
                    if (p.isInWater()) {
                        if (ie.getY() < p.getY() - 0.4 && p.getAirSupply() > 120) bot.ctl.sneak = true; // 가라앉기
                        else bot.ctl.jump = true;
                    } else if (ie.getY() > p.getY() + 0.6 && p.onGround() || p.horizontalCollision) bot.ctl.jump = true;
                }
                else { Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0)); if (r == Nav.R.FAILED) bot.nav.stop(); }
                return S.RUNNING;
            }
        }
        bot.log("떨어뜨린 물건 되찾기 끝");
        forgetDeath(bot);
        recoverFails = 0;
        return null;
    }

    private S overworld(Bot bot, LocalPlayer p, ClientLevel l) {
        S rec = recover(bot, p, l);
        if (rec != null) return rec;
        bot.trace = "overworld";
        int tier = tier(p);
        // 좁은 굴 바닥에 갇힘 (되살아난 자리가 예전에 판 굴이거나, 길찾기가 계속 실패) → 쌓거나 계단을 파서 올라감
        if (pit != null || (bot.ticks % 20 == 7 && (tier == 0 || bot.nav.fails() >= 3) && net.damoon.bot.PitEscape.trapped(bot))) {
            if (pit == null) { pit = new net.damoon.bot.PitEscape(); bot.log("좁은 굴에 갇힘 → 빠져나가기"); }
            stage = "굴에서 빠져나가기";
            if (pit.tick(bot)) { detail = pit.info; return S.RUNNING; }
            bot.log("굴 빠져나가기 끝: " + pit.info);
            pit = null;
            miner = null;
            minerKey = "";
            exploreTo = null;
        }
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        Predicate<ItemStack> planks = s -> s.is(ItemTags.PLANKS);
        int cobble = Inv.count(p, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE));

        // ---- 밤: 침대가 있으면 자서 밤을 건너뜀 ----
        // 진주를 모을 때(막대는 다 모았고 진주가 모자람)는 밤에 자지 않음 — 엔더맨은 밤에 나옴
        boolean pearlTime = NetherTask.rods(p) >= NetherTask.RODS && NetherTask.pearls(p) < NetherTask.PEARLS;
        if ((BedTask.night(l) && BedTask.hasBed(p) && !pearlTime && !cool(bot, "bed") && bot.ticks > BedTask.nextTry) || sub instanceof BedTask) {
            stage = "밤 — 침대에서 자기";
            return run(bot, "bed", BedTask::new);
        }
        // 밤인데 침대가 없고 장비가 약하면 (방패나 갑옷 없음) 땅 속에 숨어서 아침까지 (오버월드 땅 위에서만)
        boolean weak = !p.getOffhandItem().is(Items.SHIELD) || !worn(p, Items.IRON_CHESTPLATE);
        if (sub instanceof HideTask || (BedTask.night(l) && !BedTask.hasBed(p) && weak && l.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && l.canSeeSky(PathExec.feet(p)) && !cool(bot, "hide") && HideTask.canHide(bot))) {
            stage = "밤 — 땅 속에 숨기";
            return run(bot, "hide", HideTask::new);
        }
        // ---- 0. 급할 때: 먹을 게 떨어짐 → 땅 위로 올라가 사냥 ----
        int foodNow = foodCount(p);
        // 한 번 사냥을 시작하면 6개 모을 때까지 (1~2개 얻으면 철 캐러 갔다가 먹고 다시 오던 반복)
        if (foodNow < 2 && (p.getFoodData().getFoodLevel() < 17 || p.getHealth() < 12) && tier >= 1) huntLatch = true;
        if (foodNow >= 6 || cool(bot, "emhunt")) huntLatch = false;
        if (!huntLatch) huntSince = 0;
        if (huntLatch && tier >= 1) {
            if (huntSince == 0) huntSince = bot.ticks;
            // 10분 넘게 못 채우면 잠시 다른 일 (그 사이 배고프면 다시 시작)
            if (bot.ticks - huntSince > 20 * 60 * 10 && foodNow >= 2) { huntLatch = false; huntSince = 0; fail(bot, "emhunt", 20 * 60 * 3); }
            stage = "먹을 게 없음 → 사냥하러";
            BlockPos fp = PathExec.feet(p);
            int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, fp.getX(), fp.getZ());
            // 사냥감이 보이면 땅 위로 올라가지 않고 바로 잡으러 감 (경계에서 올라가기↔사냥을 매 틱 오가던 문제)
            boolean preyVisible = false;
            for (var e : l.entitiesForRendering())
                if (Hunter.foodAnimals().test(e) && e.distanceTo(p) < 32 && p.hasLineOfSight(e)) { preyVisible = true; break; }
            if (!preyVisible && surfaceTrip(bot, fp, top, fp.getY() < top - 4 && underground(l, fp, top))) {
                detail = "땅 위로 올라가는 중";
                return S.RUNNING;
            }
            if (hunterFood == null) hunterFood = new Hunter(Hunter.foodAnimals(), st -> st.has(net.minecraft.core.component.DataComponents.FOOD) || st.is(Items.FEATHER));
            Hunter.R r = hunterFood.tick(bot);
            detail = hunterFood.info;
            if (r == Hunter.R.NONE) return explore(bot, "동물");
            return S.RUNNING;
        }
        // ---- 1. 나무 ----
        int sticks = Inv.count(p, Items.STICK);
        int needSticks = Math.max(0, 8 - sticks);
        // 처음(땅 위에 있을 때) 넉넉히 모아 두고, 나중엔 막대기가 정말 없을 때만 나무를 구하러 감
        int needPlanks = tier < 2 ? 40
                : (sticks < 3 ? 2 : 0) + (Inv.has(p, Items.CRAFTING_TABLE) || Inv.count(p, planks) >= 4 ? 0 : 4);
        // (다이아 곡괭이가 있어도 작업대를 잃어버렸고 판자도 없으면 오버월드에선 나무를 구함 — 양동이·방패 등을 못 만듦)
        boolean noTable = !Inv.has(p, Items.CRAFTING_TABLE) && l.dimension() == net.minecraft.world.level.Level.OVERWORLD;
        // 이왕 나무하러 가면 작업대 + 예비 방패 3개 + 여유만큼 (한 번에)
        if (noTable && tier >= 3 && planksEq(p) < needPlanks) needPlanks = 32;
        if ((tier < 3 || noTable) && planksEq(p) < needPlanks) {
            stage = "1. 나무 모으기";
            int wantLogs = Inv.count(p, logs) + (int) Math.ceil((needPlanks - planksEq(p)) / 4.0) + 1;
            // 땅속이면 먼저 땅 위로
            BlockPos f = PathExec.feet(p);
            int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
            if (surfaceTrip(bot, f, top, f.getY() < top - 4 && (miner == null || !minerKey.equals("logs") || !miner.busy()))) {
                detail = "땅 위로 올라가는 중";
                return S.RUNNING;
            }
            return mine(bot, "logs", s -> s.is(BlockTags.LOGS), logs, wantLogs, false, 6, 20);
        }
        // 원목이 있으면 판자를 늘 6개 이상 (침대·방패·작업대를 만들 때 원목만 있고 판자가 없어 '재료 부족'으로 멈추던 문제)
        if (Inv.count(p, planks) < Math.max(needPlanks + 2, 6) && Inv.count(p, logs) > 0 && !cool(bot, "planks")) {
            stage = "1. 판자 만들기";
            int want = Inv.count(p, planks) + Math.min(Inv.count(p, logs), 4) * 4;
            Item pl = plankFor(p);
            return run(bot, "planks", () -> new CraftTask(pl, want));
        }
        if (Inv.count(p, Items.STICK) < 4 && Inv.count(p, planks) >= 2 && !cool(bot, "sticks")) {
            stage = "1. 막대기 만들기";
            return run(bot, "sticks", () -> new CraftTask(Items.STICK, 8));
        }
        if (tier == 0) {
            stage = "1. 나무 곡괭이";
            return run(bot, "wpick", () -> new CraftTask(Items.WOODEN_PICKAXE, 1));
        }
        // ---- 2. 돌 ----
        if (tier < 2 || (!Inv.has(p, Items.STONE_SWORD) && !Inv.has(p, Items.IRON_SWORD) && !Inv.has(p, Items.DIAMOND_SWORD)) || !Inv.has(p, Items.FURNACE) && cobble < 8 && !furnaceNear(l, p)) {
            int needCobble = (tier < 2 ? 3 : 0) + 2 + 8 + 3;
            if (cobble < needCobble) {
                stage = "2. 돌 캐기";
                return mine(bot, "stone", s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE),
                        s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE), needCobble + 6, true, 10, 6);
            }
            if (tier < 2) { stage = "2. 돌 곡괭이"; return run(bot, "spick", () -> new CraftTask(Items.STONE_PICKAXE, 1)); }
            if (!Inv.has(p, Items.STONE_SWORD) && !Inv.has(p, Items.IRON_SWORD)) { stage = "2. 돌 칼"; return run(bot, "ssword", () -> new CraftTask(Items.STONE_SWORD, 1)); }
        }
        if (!Inv.has(p, Items.STONE_AXE) && !Inv.has(p, Items.IRON_AXE) && cobble >= 3 && !cool(bot, "saxe")) {
            stage = "2. 돌 도끼";
            return run(bot, "saxe", () -> new CraftTask(Items.STONE_AXE, 1));
        }
        if (!Inv.has(p, Items.FURNACE) && cobble >= 8 && !cool(bot, "furnace")) {
            stage = "2. 화로";
            return run(bot, "furnace", () -> new CraftTask(Items.FURNACE, 1));
        }
        // ---- 2-1. 침대 (밤을 건너뛰어 밤 몹을 피함): 양털 3개 ----
        if (tier >= 2 && !BedTask.hasBed(p) && !cool(bot, "bed-make")) {
            Item bedItem = BedTask.bedFor(p);
            if (bedItem != null) {
                stage = "2. 침대 만들기";
                return run(bot, "bedcraft", () -> new CraftTask(bedItem, 1));
            }
            stage = "2. 침대용 양털 (양 잡기)";
            if (sheep == null) sheep = new Hunter(e -> e instanceof net.minecraft.world.entity.animal.Sheep sh && !sh.isBaby() && !sh.isSheared(),
                    st -> st.is(ItemTags.WOOL) || st.has(net.minecraft.core.component.DataComponents.FOOD));
            Hunter.R r = sheep.tick(bot);
            detail = sheep.info;
            if (r == Hunter.R.NONE) { fail(bot, "bed-make", 20 * 60 * 3); bot.nav.stop(); }
            return S.RUNNING;
        }
        // 방패가 부서졌는데 철이 있으면 무엇보다 먼저 새로 만듦 (방패 없이 스켈레톤·좀비를 만나면 위험)
        if (!p.getOffhandItem().is(Items.SHIELD) && !Inv.has(p, Items.SHIELD) && Inv.count(p, Items.IRON_INGOT) >= 1 && !cool(bot, "shieldnow")) {
            if (Inv.count(p, s2 -> s2.is(ItemTags.PLANKS)) >= 6) { stage = "방패가 부서짐 → 새 방패"; return run(bot, "shieldnow", () -> new CraftTask(Items.SHIELD, 1)); }
            if (Inv.count(p, logs) >= 2) { Item pl = plankFor(p); int want = Inv.count(p, planks) + 8; stage = "새 방패용 판자"; return run(bot, "shieldnowplanks", () -> new CraftTask(pl, want)); }
            if (l.dimension() == Level.OVERWORLD) { stage = "새 방패용 나무 모으기"; return gatherWood(bot, 8); }
        }
        // ---- 3. 음식 ----
        int food = foodCount(p);
        int raw = Inv.count(p, rawMeat());
        if (food < 6 && !cool(bot, "hunt")) {
            stage = "3. 사냥";
            if (hunterFood == null) hunterFood = new Hunter(Hunter.foodAnimals(), s -> s.has(net.minecraft.core.component.DataComponents.FOOD) || s.is(Items.LEATHER) || s.is(Items.FEATHER) || s.is(Items.WHITE_WOOL));
            Hunter.R r = hunterFood.tick(bot);
            detail = hunterFood.info;
            if (r == Hunter.R.NONE) { fail(bot, "hunt", 20 * 60); bot.nav.stop(); }
            return S.RUNNING;
        }
        if (raw >= 4 && hasFuel(p) && !cool(bot, "cook")) {
            stage = "3. 고기 굽기";
            Item out = cookedFor(p);
            int want = Inv.count(p, out) + Math.min(raw, 16);
            return run(bot, "cook", () -> new SmeltTask(rawMeat(), out, want));
        }
        // ---- 4. 석탄 ----
        // 한 번 캐기 시작하면 10개까지 (5개 밑으로 떨어질 때마다 1개씩만 캐고 오가던 문제)
        int coalNow = Inv.count(p, Items.COAL) + Inv.count(p, Items.CHARCOAL);
        if (coalNow < 5) coalLatch = true;
        if (coalNow >= 10) coalLatch = false;
        if (coalLatch && !cool(bot, "coal")) {
            stage = "4. 석탄 캐기";
            S s = mine(bot, "coal", st -> st.is(Blocks.COAL_ORE) || st.is(Blocks.DEEPSLATE_COAL_ORE), is(Items.COAL), 10, true, 24, 24);
            if (miner == null && exploreTo != null) { fail(bot, "coal", 20 * 120); exploreTo = null; bot.nav.stop(); }
            return s;
        }
        // ---- 5. 철 ----
        int ironHave = Inv.count(p, Items.IRON_INGOT) + Inv.count(p, Items.RAW_IRON) + ironInGear(p);
        if (ironHave < 35) {
            stage = "5. 철 캐기 (" + ironHave + "/35)";
            return mineDeep(bot, "iron", st -> st.is(Blocks.IRON_ORE) || st.is(Blocks.DEEPSLATE_IRON_ORE), is(Items.RAW_IRON),
                    Inv.count(p, Items.RAW_IRON) + (35 - ironHave), 16);
        }
        if (Inv.count(p, Items.RAW_IRON) > 0 && !cool(bot, "smeltiron")) {
            stage = "5. 철 굽기";
            int want = Inv.count(p, Items.IRON_INGOT) + Inv.count(p, Items.RAW_IRON);
            return run(bot, "smeltiron", () -> new SmeltTask(is(Items.RAW_IRON), Items.IRON_INGOT, want));
        }
        Item[] gear = {Items.IRON_PICKAXE, Items.IRON_SWORD, Items.SHIELD, Items.BUCKET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
        for (Item g : gear) {
            if (g == Items.SHIELD && p.getOffhandItem().is(Items.SHIELD)) continue;
            if (g == Items.SHIELD && Inv.count(p, s2 -> s2.is(ItemTags.PLANKS)) < 6) {
                if (Inv.count(p, logs) >= 2 && !cool(bot, "shieldplanks")) {
                    Item pl = plankFor(p);
                    int want = Inv.count(p, planks) + 8;
                    stage = "5. 방패용 판자";
                    return run(bot, "shieldplanks", () -> new CraftTask(pl, want));
                }
                continue;
            }
            if (!Inv.has(p, g) && !worn(p, g) && !cool(bot, "g" + g)) {
                if (g == Items.BUCKET && (Inv.has(p, Items.WATER_BUCKET) || Inv.has(p, Items.LAVA_BUCKET))) continue;
                stage = "5. 철 장비: " + g.getDescription().getString();
                return run(bot, "g" + g, () -> new CraftTask(g, 1));
            }
        }
        // 예비 방패 하나 (화살을 막다 보면 닳아 부서짐 — 부서지는 순간 바로 바꿔 듦)
        // (Inv.count는 왼손도 셈)
        if (p.getOffhandItem().is(Items.SHIELD) && Inv.count(p, Items.SHIELD) < 3 && Inv.count(p, Items.IRON_INGOT) >= 1
                && Inv.count(p, s2 -> s2.is(ItemTags.PLANKS)) >= 6 && !cool(bot, "spareshield")) {
            stage = "5. 예비 방패";
            int wantSh = Inv.count(p, Items.SHIELD) + 1;
            return run(bot, "spareshield", () -> new CraftTask(Items.SHIELD, wantSh));
        }
        // 줍기: 근처에 떨어진 쓸 만한 것 (실, 깃털, 화살, 활, 진주 등)
        S loot = loot(bot, p, l);
        if (loot != null) return loot;

        // 네더에서는 블레이즈 막대만 (진주는 오버월드에서 혼자 있는 엔더맨을 잡아 구함)
        // 포털 틀에 이미 끼운 눈 (작업이 다시 시작돼도 알 수 있게 주변을 봄, 5초마다)
        if (bot.ticks - frameScanTick > 100 && l.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            frameScanTick = bot.ticks;
            int fwe = net.damoon.bot.Scan.find(l, PathExec.feet(p), 4, l.getMinBuildHeight(), 70,
                    st -> st.is(Blocks.END_PORTAL_FRAME) && st.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE), null, 12).size();
            if (fwe > 0 || bot.framesWithEyes == 0) bot.framesWithEyes = Math.max(bot.framesWithEyes, fwe);
        }
        int placedEyes = bot.framesWithEyes;
        int eyesAll = Inv.count(p, Items.ENDER_EYE) + placedEyes;
        if (NetherTask.rods(p) >= NetherTask.RODS || eyesAll >= 10) bot.pastNether = true;
        // 네더 뒤에 죽어서 막대·눈을 다 잃었으면 네더부터 다시
        if (bot.pastNether && NetherTask.rods(p) == 0 && Inv.count(p, Items.BLAZE_POWDER) == 0 && eyesAll + Inv.count(p, Items.ENDER_PEARL) < 12) bot.pastNether = false;
        boolean afterNether = bot.pastNether;
        if (!afterNether) {
            // ---- 6. 다이아몬드 ----
            if (tier < 4) {
                int dia = Inv.count(p, Items.DIAMOND);
                if (dia < 3) {
                    stage = "6. 다이아몬드 캐기 (" + dia + "/3)";
                    return mineDeep(bot, "diamond", st -> st.is(Blocks.DIAMOND_ORE) || st.is(Blocks.DEEPSLATE_DIAMOND_ORE), is(Items.DIAMOND), 3, -54);
                }
                stage = "6. 다이아몬드 곡괭이";
                return run(bot, "dpick", () -> new CraftTask(Items.DIAMOND_PICKAXE, 1));
            }
            // ---- 7. 활과 화살 (블레이즈를 멀리서 쏘기 위해, 못 구하면 몇 분 뒤 그냥 감) ----
            S ba = bowAndArrows(bot, p, l, "7");
            if (ba != null) return ba;
            // ---- 7. 네더 준비: 금 투구(피글린), 부싯돌, 흑요석, 포털 ----
            if (!Inv.has(p, Items.GOLDEN_HELMET) && !worn(p, Items.GOLDEN_HELMET) && !cool(bot, "gold")) {
                int gold = Inv.count(p, Items.GOLD_INGOT) + Inv.count(p, Items.RAW_GOLD);
                if (gold < 5) {
                    stage = "7. 금 캐기 (" + gold + "/5) — 피글린이 공격하지 않게";
                    S r = mineDeep(bot, "gold", st -> st.is(Blocks.GOLD_ORE) || st.is(Blocks.DEEPSLATE_GOLD_ORE), is(Items.RAW_GOLD), Inv.count(p, Items.RAW_GOLD) + 5 - gold, -16);
                    if (++goldTicks > 20 * 60 * 8) { fail(bot, "gold", 20 * 60 * 30); bot.log("금을 못 찾음 → 금 투구 없이 감"); }
                    return r;
                }
                if (Inv.count(p, Items.RAW_GOLD) > 0) {
                    stage = "7. 금 굽기";
                    int want = Inv.count(p, Items.GOLD_INGOT) + Inv.count(p, Items.RAW_GOLD);
                    return run(bot, "smeltgold", () -> new SmeltTask(is(Items.RAW_GOLD), Items.GOLD_INGOT, want));
                }
                stage = "7. 금 투구";
                return run(bot, "ghelm", () -> new CraftTask(Items.GOLDEN_HELMET, 1));
            }
            // 우유 (위더 스켈레톤의 위더 효과를 풀기 위해) — 빈 양동이가 없으면 하나 더 만듦
            if (!Inv.has(p, Items.MILK_BUCKET) && !cool(bot, "milk")) {
                if (!Inv.has(p, Items.BUCKET)) {
                    if (Inv.count(p, Items.IRON_INGOT) >= 3 && !cool(bot, "bucket2")) { stage = "7. 우유용 양동이"; return run(bot, "bucket2", () -> new CraftTask(Items.BUCKET, 1)); }
                } else {
                    stage = "7. 소에게서 우유 받기";
                    S r = milk(bot, p, l);
                    if (r != null) return r;
                }
            }
            // 예비 방패 (화염구를 막다 보면 방패가 닳아 부서짐)
            int shields = Inv.count(p, Items.SHIELD); // 왼손 포함 — 블레이즈 여럿의 화염구를 막다 보면 금방 닳음
            if (shields < 4 && Inv.count(p, Items.IRON_INGOT) >= 1 && !cool(bot, "shield2")) {
                if (Inv.count(p, s2 -> s2.is(ItemTags.PLANKS)) >= 6) { stage = "7. 예비 방패"; return run(bot, "shield2", () -> new CraftTask(Items.SHIELD, Inv.count(p, Items.SHIELD) + 1)); }
                if (Inv.count(p, logs) >= 2) { Item pl = plankFor(p); int want = Inv.count(p, planks) + 8; stage = "7. 예비 방패용 판자"; return run(bot, "shield2planks", () -> new CraftTask(pl, want)); }
                // 판자도 나무도 없으면 나무를 구함 (네더에서 방패가 부서지면 새로 못 만듦). 못 찾으면 5분 뒤 다시
                if (l.dimension() == Level.OVERWORLD) {
                    stage = "7. 예비 방패용 나무 모으기";
                    if (shieldWoodSince == 0) shieldWoodSince = bot.ticks;
                    if (bot.ticks - shieldWoodSince > 20 * 60 * 4) { fail(bot, "shield2", 20 * 60 * 5); shieldWoodSince = 0; }
                    return gatherWood(bot, 4);
                }
            }
            shieldWoodSince = 0;
            if (!Inv.has(p, Items.FLINT_AND_STEEL)) {
                if (Inv.count(p, Items.FLINT) < 1) {
                    stage = "7. 부싯돌 (자갈 캐기)";
                    return mineDeep(bot, "flint", st -> st.is(Blocks.GRAVEL), is(Items.FLINT), 1, PathExec.feet(p).getY());
                }
                if (Inv.count(p, Items.IRON_INGOT) < 1 && Inv.count(p, Items.RAW_IRON) > 0) {
                    stage = "7. 철 굽기";
                    return run(bot, "smeltiron2", () -> new SmeltTask(is(Items.RAW_IRON), Items.IRON_INGOT, 1));
                }
                stage = "7. 부싯돌과 부시";
                return run(bot, "fns", () -> new CraftTask(Items.FLINT_AND_STEEL, 1));
            }
            // 이미 불 붙은 포털이 근처에 있으면 바로 들어감
            // 맨 아래 칸(아래가 포털이 아닌 칸)만 — 그래야 그 안에 설 수 있음
            BlockPos lit = net.damoon.bot.Scan.nearest(l, PathExec.feet(p), 2, PathExec.feet(p).getY() - 20, PathExec.feet(p).getY() + 20,
                    st -> st.is(Blocks.NETHER_PORTAL), b -> !l.getBlockState(b.below()).is(Blocks.NETHER_PORTAL));
            // 근처에 안 보여도 전에 지은(나온) 포털이 그대로 있으면 거기로 돌아감 (새로 짓지 않음)
            if (lit != null) rememberOverworldPortal(lit);
            else lit = knownOverworldPortal(l);
            // 네더에 들어가기 전 음식을 넉넉히 (네더엔 먹을 게 거의 없음) — 포털을 짓기 전에 (짓자마자 들어가 버리므로)
            // (못 채워 쉬는 중이어도 8개 밑이면 무조건 더 구함 — 3개로 들어가면 네더에서 바로 '먹을 게 없음'으로 돌아나와 포털을 오가던 문제)
            if ((foodCount(p) < 20 && !cool(bot, "netherfood") || foodCount(p) < 8) && !(sub instanceof PortalTask)) {
                stage = "7. 네더 가기 전 음식 챙기기 (" + foodCount(p) + "/20)";
                if (Inv.count(p, rawMeat()) >= 3 && hasFuel(p) && !cool(bot, "cook")) {
                    Item out = cookedFor(p);
                    int want = Inv.count(p, out) + Inv.count(p, rawMeat());
                    return run(bot, "cook", () -> new SmeltTask(rawMeat(), out, want));
                }
                // 동물은 땅 위에 있음 → 땅속이면 먼저 올라감
                BlockPos ff = PathExec.feet(p);
                int ftop = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, ff.getX(), ff.getZ());
                if (surfaceTrip(bot, ff, ftop, ff.getY() < ftop - 4 && underground(l, ff, ftop))) {
                    detail = "땅 위로 올라가는 중";
                    return S.RUNNING;
                }
                if (hunterFood == null) hunterFood = new Hunter(Hunter.foodAnimals(), st -> st.has(net.minecraft.core.component.DataComponents.FOOD) || st.is(Items.LEATHER) || st.is(Items.FEATHER));
                Hunter.R hr = hunterFood.tick(bot);
                detail = hunterFood.info;
                if (hr == Hunter.R.NONE) {
                    if (netherFoodSince == 0) netherFoodSince = bot.ticks;
                    // 5분 넘게 찾아도 없으면 있는 것만 들고 감
                    if (bot.ticks - netherFoodSince > 20 * 60 * 5) { fail(bot, "netherfood", 20 * 60 * 10); netherFoodSince = 0; }
                    return explore(bot, "동물");
                }
                return S.RUNNING;
            }
            if (lit != null) {
                stage = "7. 네더 포털로 들어가는 중";
                BlockPos fp = PathExec.feet(p);
                if (l.getBlockState(fp).is(Blocks.NETHER_PORTAL)) { bot.nav.stop(); detail = "포털 안에서 기다리는 중"; return S.RUNNING; }
                Nav.R r = bot.nav.go(new Goal.Block(lit.getX(), lit.getY(), lit.getZ(), true));
                if (r == Nav.R.FAILED) bot.nav.stop();
                return S.RUNNING;
            }
            int placedObs = portalTask != null ? portalTask.placedObsidian(l) : 0;
            if (Inv.count(p, Items.OBSIDIAN) + placedObs < 10 && !(sub instanceof PortalTask)) {
                stage = "7. 흑요석 만들기 (" + Inv.count(p, Items.OBSIDIAN) + "/10)";
                // 실패한 뒤 쉬는 동안엔 다시 만들지 않음 (매 틱 새로 만들고 실패하던 문제)
                S r = cool(bot, "obsidian") && !(sub instanceof ObsidianTask) ? S.RUNNING : run(bot, "obsidian", () -> new ObsidianTask(10));
                if (cool(bot, "obsidian") && !Inv.has(p, Items.WATER_BUCKET) && Inv.has(p, Items.BUCKET)) {
                    // 물이 없음 (근처에 물 원천이 없어 실패) → 땅 위로 올라가 물(강·호수·바다)을 찾아 돌아다님
                    BlockPos wf = PathExec.feet(p);
                    int wtop = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, wf.getX(), wf.getZ());
                    if (surfaceTrip(bot, wf, wtop, underground(l, wf, wtop))) { detail = "물을 찾아 땅 위로 올라가는 중"; return S.RUNNING; }
                    BlockPos ws = net.damoon.bot.Scan.nearest(l, wf, 4, wf.getY() - 16, wf.getY() + 24,
                            st -> st.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && st.getFluidState().isSource(),
                            b -> l.getBlockState(b.above()).isAir() && !badWaterZone.contains(new BlockPos(b.getX() >> 4, 0, b.getZ() >> 4).asLong()));
                    if (ws != null) {
                        detail = "물을 찾음 → 양동이로 뜨러 가는 중";
                        if (net.damoon.bot.Act.reach(p, new net.minecraft.world.phys.AABB(ws)) > 4.0) {
                            // 30초 넘게 못 가면 그 청크의 물은 포기
                            if (bot.ticks - waterSeekStart > 20 * 30 && waterSeekStart > 0) {
                                badWaterZone.add(new BlockPos(ws.getX() >> 4, 0, ws.getZ() >> 4).asLong());
                                waterSeekStart = 0; bot.nav.stop(); return S.RUNNING;
                            }
                            if (waterSeekStart == 0) waterSeekStart = bot.ticks;
                            Nav.R wr = bot.nav.go(new Goal.Reach(ws, 3.0));
                            if (wr == Nav.R.FAILED) { bot.nav.stop(); badWaterZone.add(new BlockPos(ws.getX() >> 4, 0, ws.getZ() >> 4).asLong()); waterSeekStart = 0; }
                            return S.RUNNING;
                        }
                        waterSeekStart = 0;
                        bot.nav.stop();
                        ObsidianTask.useBucket(bot, Items.BUCKET, net.minecraft.world.phys.Vec3.atCenterOf(ws).add(0, 0.4, 0));
                        return S.RUNNING;
                    }
                    return explore(bot, "물");
                }
                if (cool(bot, "obsidian")) {
                    // 근처에 용암이 없으면 더 깊이 내려가서 찾음
                    if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot()));
                    BlockPos df = PathExec.feet(p);
                    if (bot.ticks % 400 == 0 && digger.recentTurns < 4) digger.recentTurns = 0;
                    if (digger.recentTurns >= 4) {
                        // 사방이 물(지하 대수층)이라 계단을 못 팜 → 길찾기로 동굴을 따라 다른 곳으로
                        Nav.R nr = bot.nav.go(new Goal.Below(Math.max(-50, df.getY() - 10)));
                        if (nr != Nav.R.RUNNING) { digger.recentTurns = 0; bot.nav.stop(); if (nr == Nav.R.FAILED) digger = new Digger(Direction.fromYRot(p.getYRot() + 90)); }
                        detail = "용암을 찾아 이동 — 물을 피해 동굴로";
                    } else {
                        digger.tick(bot, df.getY() > -50 ? Digger.Mode.STAIRS : Digger.Mode.TUNNEL);
                        detail = "용암을 찾아 이동 — " + digger.info;
                    }
                }
                return r;
            }
            stage = "7. 네더 포털 짓기";
            // 포털은 땅 위 평지에 지음 (땅속 좁은 동굴에선 틀 블록을 놓을 받침이 없어 자주 실패)
            if (!(sub instanceof PortalTask) && (portalTask == null || portalTask.placedObsidian(l) == 0)) {
                BlockPos pf = PathExec.feet(p);
                int ptop = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pf.getX(), pf.getZ());
                if (surfaceTrip(bot, pf, ptop, pf.getY() < ptop - 6 && underground(l, pf, ptop))) {
                    detail = "포털 지을 땅 위로 올라가는 중";
                    return S.RUNNING;
                }
            }
            if (portalTask == null || portalFails > 3) { portalTask = new PortalTask(); portalFails = 0; }
            final PortalTask pt = portalTask;
            S r = run(bot, "portal", () -> pt);
            if (sub == null && cool(bot, "portal")) { portalFails++; cooldown.remove("portal"); }
            return r;
        }

        // ---- 9. 엔더 진주: 오버월드의 혼자 있는 엔더맨 (밤·동굴) ----
        // 요새 찾기를 시작했으면 (눈을 던져서 줄어도) 진주 사냥으로 돌아가지 않음 — 눈이 6개 아래로 떨어질 때만
        if (stronghold != null && eyesAll < 6 && Inv.count(p, Items.ENDER_PEARL) == 0) stronghold = null;
        if (NetherTask.pearls(p) + placedEyes < NetherTask.PEARLS && stronghold == null) {
            stage = "9. 엔더 진주 (" + NetherTask.pearls(p) + "/" + NetherTask.PEARLS + ")";
            if (!net.damoon.bot.EnderTrap.hasBoat(p) && net.damoon.bot.EnderTrap.boatFor(p) != null && !cool(bot, "boat")) {
                Item boat = net.damoon.bot.EnderTrap.boatFor(p);
                return run(bot, "boat", () -> new CraftTask(boat, 1));
            }
            // 밤엔 엔더맨 굴 (높이 2칸 굴 안에서 엔더맨을 불러 다리를 침) — 가장 안전
            boolean seeEnder = false;
            for (var e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.EnderMan && e.distanceTo(p) < 48) { seeEnder = true; break; }
            // (낮엔 굴이 30초 만에 끝나므로(엔더맨이 동굴에만 있음) 굴 대신 바로 다가가 다리를 침)
            if (sub instanceof EnderHutTask || (l.dimension() == net.minecraft.world.level.Level.OVERWORLD && seeEnder && BedTask.night(l)
                    && Inv.count(p, Inv.throwaway()) >= 16 && !cool(bot, "hut"))) {
                detail = "엔더맨 굴";
                return run(bot, "hut", () -> new EnderHutTask(NetherTask.PEARLS));
            }
            if (trap == null) trap = new net.damoon.bot.EnderTrap();
            net.damoon.bot.EnderTrap.R r = trap.tick(bot);
            detail = trap.info;
            if (r == net.damoon.bot.EnderTrap.R.NONE) {
                // 낮이면 동굴(어두운 곳)에도 엔더맨이 있음 → 탐험
                return explore(bot, "엔더맨");
            }
            return S.RUNNING;
        }
        // ---- 9. 엔드 준비: 엔더의 눈, 활과 화살 ----
        int eyes = Inv.count(p, Items.ENDER_EYE);
        // 눈은 (틀에 끼운 것 포함) 12개까지만 만들고 나머지 진주는 공허·낙하 탈출용으로 남김
        if (Inv.count(p, Items.ENDER_PEARL) > 0 && eyes + placedEyes < 12) {
            if (Inv.count(p, Items.BLAZE_POWDER) < 1 && Inv.has(p, Items.BLAZE_ROD)) {
                stage = "9. 블레이즈 가루";
                int want = Inv.count(p, Items.BLAZE_POWDER) + Inv.count(p, Items.BLAZE_ROD) * 2;
                return run(bot, "powder", () -> new CraftTask(Items.BLAZE_POWDER, want));
            }
            if (Inv.count(p, Items.BLAZE_POWDER) > 0) {
                stage = "9. 엔더의 눈";
                int want = eyes + Math.min(12 - eyes - placedEyes, Math.min(Inv.count(p, Items.ENDER_PEARL), Inv.count(p, Items.BLAZE_POWDER)));
                return run(bot, "eyes", () -> new CraftTask(Items.ENDER_EYE, want));
            }
        }
        S ba = bowAndArrows(bot, p, l, "9");
        if (ba != null) return ba;
        // ---- 10. 엔드 준비: 물 양동이 (높은 곳에서 떨어지면 물 낙법) ----
        if (Inv.has(p, Items.BUCKET) && !Inv.has(p, Items.WATER_BUCKET) && l.dimension() == net.minecraft.world.level.Level.OVERWORLD && !cool(bot, "water")) {
            stage = "10. 엔드 준비: 물 양동이";
            return run(bot, "water", WaterTask::new);
        }
        // ---- 10. 요새 ----
        stage = "10. 요새 찾기 (엔더의 눈 " + eyes + "개)";
        if (stronghold == null) stronghold = new StrongholdTask();
        S s = stronghold.tick(bot);
        detail = stronghold.detail();
        if (s == S.DONE) {
            // 포털이 열림 → EndGameTask가 들어감
            return end.tick(bot);
        }
        return S.RUNNING;
    }

    private int cowId = -1, milkTicks;

    /** 빈 양동이로 소 우유 받기 (소가 안 보이면 null → 잠시 뒤 다시) */
    private S milk(Bot bot, LocalPlayer p, ClientLevel l) {
        net.minecraft.world.entity.Entity cow = cowId >= 0 ? l.getEntity(cowId) : null;
        if (cow == null || !cow.isAlive()) {
            cow = null;
            double bd = 1e9;
            for (var e : l.entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.animal.Cow c && !c.isBaby() && c.distanceTo(p) < 40 && c.distanceTo(p) < bd) { bd = c.distanceTo(p); cow = c; }
            if (cow == null) { fail(bot, "milk", 20 * 60 * 2); return null; }
            cowId = cow.getId();
            milkTicks = 0;
        }
        if (++milkTicks > 20 * 40) { cowId = -1; fail(bot, "milk", 20 * 60 * 2); return null; }
        double r = net.damoon.bot.Act.reach(p, cow.getBoundingBox());
        detail = "소에게 다가가는 중";
        if (r > 2.8) {
            Nav.R nr = bot.nav.go(new Goal.Near(cow.blockPosition(), 1.5));
            if (nr == Nav.R.FAILED) { bot.nav.stop(); cowId = -1; }
            return S.RUNNING;
        }
        bot.nav.stop();
        Inv.select(p, Inv.is(Items.BUCKET));
        bot.ctl.lookAt(p, cow.getBoundingBox().getCenter());
        if (bot.ctl.aimed(p, 15) && p.getMainHandItem().is(Items.BUCKET)) {
            net.minecraft.client.Minecraft.getInstance().gameMode.interact(p, cow, net.minecraft.world.InteractionHand.MAIN_HAND);
            detail = "우유 받는 중";
        }
        return S.RUNNING;
    }

    private String hunterKey = "";
    private Hunter hunterFood;
    private final java.util.Set<Integer> lootIgnore = new java.util.HashSet<>();
    private final java.util.Map<Integer, Integer> lootTime = new java.util.HashMap<>();

    /** 활과 화살 (블레이즈·엔드 수정 저격용). 없으면 null (다 했거나 못 구해서 미룸) */
    private S bowAndArrows(Bot bot, LocalPlayer p, ClientLevel l, String no) {
        if (!Inv.has(p, Items.BOW) && !cool(bot, "bow")) {
            if (Inv.count(p, Items.STRING) >= 3) { stage = no + ". 활 만들기"; return run(bot, "bow", () -> new CraftTask(Items.BOW, 1)); }
            stage = no + ". 활용 실 모으기 (" + Inv.count(p, Items.STRING) + "/3)";
            BlockPos web = net.damoon.bot.Scan.nearest(l, PathExec.feet(p), 3, PathExec.feet(p).getY() - 30, PathExec.feet(p).getY() + 20, st -> st.is(Blocks.COBWEB), null);
            if (web != null) return mine(bot, "web", st -> st.is(Blocks.COBWEB), is(Items.STRING), 3, true, 30, 20);
            if (hunter == null || !"spider".equals(hunterKey)) { hunter = new Hunter(e -> e instanceof net.minecraft.world.entity.monster.Spider, is(Items.STRING)); hunterKey = "spider"; }
            Hunter.R r = hunter.tick(bot);
            detail = hunter.info;
            if (r == Hunter.R.NONE) {
                // 활은 블레이즈 잡기에 꼭 필요함 (칼은 떠 있는 블레이즈에 안 닿음) → 15분까지는 거미·거미줄을 찾아 돌아다님
                if (bowSearch == 0) bowSearch = bot.ticks;
                if (bot.ticks - bowSearch > 20 * 60 * 15) { fail(bot, "bow", 20 * 60 * 30); bot.log("15분 동안 실을 못 구함 → 활 없이 감"); return S.RUNNING; }
                // 거미는 어두운 곳(동굴·밤)에 많음
                return explore(bot, "거미");
            }
            return S.RUNNING;
        }
        int arrows = Inv.count(p, Items.ARROW);
        if (Inv.has(p, Items.BOW) && arrows < 48 && !cool(bot, "arrows")) {
            if (Inv.count(p, Items.FLINT) > 0 && Inv.count(p, Items.FEATHER) > 0 && Inv.count(p, Items.STICK) > 0) {
                stage = no + ". 화살 만들기";
                return run(bot, "arrow", () -> new CraftTask(Items.ARROW, arrows + 4));
            }
            if (Inv.count(p, Items.FEATHER) == 0) {
                stage = no + ". 화살용 깃털 (닭 사냥)";
                if (hunter == null || !"chicken".equals(hunterKey)) { hunter = new Hunter(e -> e instanceof net.minecraft.world.entity.animal.Chicken, is(Items.FEATHER)); hunterKey = "chicken"; }
                Hunter.R r = hunter.tick(bot);
                detail = hunter.info;
                if (r == Hunter.R.NONE) fail(bot, "arrows", 20 * 60 * 3);
                return S.RUNNING;
            }
            stage = no + ". 화살용 부싯돌 (자갈 캐기)";
            S r = mineDeep(bot, "flint2", st -> st.is(Blocks.GRAVEL), is(Items.FLINT), Inv.count(p, Items.FLINT) + 1, PathExec.feet(p).getY());
            if (++flintTicks > 20 * 60 * 4) { flintTicks = 0; fail(bot, "arrows", 20 * 60 * 5); }
            return r;
        }
        return null;
    }

    private int goldTicks, flintTicks, frameScanTick = -1000;
    private boolean builtPortal;

    /** 근처에 떨어진 쓸 만한 아이템 줍기 */
    private S loot(Bot bot, LocalPlayer p, ClientLevel l) {
        for (var e : l.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie)) continue;
            ItemStack st = ie.getItem();
            if (!(st.is(Items.STRING) || st.is(Items.FEATHER) || st.is(Items.ARROW) || st.is(Items.BOW) || st.is(Items.ENDER_PEARL)
                    || st.is(Items.BLAZE_ROD) || st.is(Items.DIAMOND) || st.is(Items.OBSIDIAN) || st.is(Items.FLINT))) continue;
            double d = ie.distanceTo(p);
            if (d > 10 || d < 0.8 || Math.abs(ie.getY() - p.getY()) > 3 || !ie.onGround() || lootIgnore.contains(ie.getId())) continue;
            // 처음 본 때부터 10초 (거미줄에 걸려 떠 있는 실처럼 가끔만 조건에 맞아도 끝이 나도록 실제 시간으로 셈)
            int first = lootTime.computeIfAbsent(ie.getId(), k -> bot.ticks);
            if (bot.ticks - first > 20 * 10) { lootIgnore.add(ie.getId()); continue; }
            stage = "줍기: " + st.getHoverName().getString();
            Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0));
            if (r == Nav.R.ARRIVED) { bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; }
            if (r == Nav.R.FAILED) { bot.nav.stop(); continue; }
            return S.RUNNING;
        }
        return null;
    }

    /** 깊이 있는 광석: 근처에서 찾고, 없으면 계단식으로 내려가 굴을 파며 찾음 */
    private S mineDeep(Bot bot, String key, Predicate<BlockState> ore, Predicate<ItemStack> item, int want, int targetY) {
        LocalPlayer p = bot.p();
        if (miner == null || !minerKey.equals(key)) {
            miner = new Miner(ore, item, want, true);
            miner.below = 24;
            miner.above = 16;
            miner.chunks = 2;
            minerKey = key;
            bot.nav.stop();
        }
        boolean found = miner.available(bot) || miner.busy();
        if (found) {
            Miner.R r = miner.tick(bot);
            detail = miner.info;
            if (r == Miner.R.DONE) { miner = null; minerKey = ""; }
            return S.RUNNING;
        }
        BlockPos f = PathExec.feet(p);
        if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot()));
        // 계단 파기가 막혔으면 (방향을 계속 바꿈) 길찾기로 동굴을 따라 내려감
        if (digger.recentTurns >= 4 && f.getY() > targetY) {
            detail = "동굴을 따라 내려가는 중 (y=" + f.getY() + ")";
            Nav.R r = bot.nav.go(new Goal.Below(Math.max(targetY, f.getY() - 12)));
            if (r != Nav.R.RUNNING) { digger.recentTurns = 0; bot.nav.stop(); if (r == Nav.R.FAILED) digger = new Digger(Direction.fromYRot(p.getYRot() + 90)); }
            return S.RUNNING;
        }
        if (bot.ticks % 400 == 0) digger.recentTurns = 0;
        bot.nav.stop();
        digger.tick(bot, f.getY() > targetY ? Digger.Mode.STAIRS : Digger.Mode.TUNNEL);
        detail = digger.info;
        return S.RUNNING;
    }

    private static boolean furnaceNear(ClientLevel l, LocalPlayer p) {
        return net.damoon.bot.Placer.near(l, p, Blocks.FURNACE, 12) != null;
    }

    static Predicate<ItemStack> is(Item i) { return Inv.is(i); }

    static Predicate<ItemStack> rawMeat() {
        return s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT);
    }

    private static Item cookedFor(LocalPlayer p) {
        if (Inv.has(p, Items.BEEF)) return Items.COOKED_BEEF;
        if (Inv.has(p, Items.PORKCHOP)) return Items.COOKED_PORKCHOP;
        if (Inv.has(p, Items.MUTTON)) return Items.COOKED_MUTTON;
        if (Inv.has(p, Items.CHICKEN)) return Items.COOKED_CHICKEN;
        return Items.COOKED_RABBIT;
    }

    private static Item plankFor(LocalPlayer p) {
        Item[][] map = {
                {Items.OAK_LOG, Items.OAK_PLANKS}, {Items.SPRUCE_LOG, Items.SPRUCE_PLANKS}, {Items.BIRCH_LOG, Items.BIRCH_PLANKS},
                {Items.JUNGLE_LOG, Items.JUNGLE_PLANKS}, {Items.ACACIA_LOG, Items.ACACIA_PLANKS}, {Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS},
                {Items.MANGROVE_LOG, Items.MANGROVE_PLANKS}, {Items.CHERRY_LOG, Items.CHERRY_PLANKS}};
        for (Item[] m : map) if (Inv.has(p, m[0])) return m[1];
        return Items.OAK_PLANKS;
    }

    private static boolean hasFuel(LocalPlayer p) {
        return Inv.count(p, SmeltTask.fuel()) > 0;
    }

    static int foodCount(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().items.get(i);
            if (Inv.food().test(s) && !rawMeat().test(s)) n += s.getCount();
        }
        return n + Inv.count(p, rawMeat());
    }

    private static boolean worn(LocalPlayer p, Item g) {
        for (ItemStack s : p.getArmorSlots()) if (s.is(g)) return true;
        return false;
    }

    private static int ironInGear(LocalPlayer p) {
        int n = 0;
        Object[][] cost = {{Items.IRON_PICKAXE, 3}, {Items.IRON_SWORD, 2}, {Items.SHIELD, 1}, {Items.BUCKET, 3}, {Items.WATER_BUCKET, 3}, {Items.LAVA_BUCKET, 3}, {Items.MILK_BUCKET, 3},
                {Items.IRON_CHESTPLATE, 8}, {Items.IRON_LEGGINGS, 7}, {Items.IRON_BOOTS, 4}, {Items.IRON_HELMET, 5}};
        for (Object[] c : cost) {
            Item it = (Item) c[0];
            if (Inv.has(p, it) || worn(p, it)) n += (int) c[1];
        }
        return n;
    }
}
