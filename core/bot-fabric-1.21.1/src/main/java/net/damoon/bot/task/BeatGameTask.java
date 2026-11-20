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
    private final Map<String, Integer> cooldown = new HashMap<>();
    private final EndGameTask end = new EndGameTask();
    private NetherTask nether;
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

    /** 하위 작업 실행 (같은 key면 이어서) */
    private S run(Bot bot, String key, java.util.function.Supplier<Task> make) {
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
            miner = new Miner(block, item, want, tool);
            miner.below = below;
            miner.above = above;
            minerKey = key;
            bot.nav.stop();
        }
        Miner.R r = miner.tick(bot);
        detail = miner.info;
        if (r == Miner.R.FAILED) {
            miner = null;
            minerKey = "";
            return explore(bot, key);
        }
        if (r == Miner.R.DONE) { miner = null; minerKey = ""; }
        return S.RUNNING;
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
        if (sub != null) { bot.trace = "sub " + subKey; return run(bot, subKey, () -> sub); }
        if (l.dimension() == Level.NETHER) {
            stage = "8. 네더";
            if (nether == null) nether = new NetherTask();
            nether.tick(bot);
            detail = nether.detail();
            return S.RUNNING;
        }
        return overworld(bot, p, l);
    }

    private S overworld(Bot bot, LocalPlayer p, ClientLevel l) {
        bot.trace = "overworld";
        int tier = tier(p);
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        Predicate<ItemStack> planks = s -> s.is(ItemTags.PLANKS);
        int cobble = Inv.count(p, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE));

        // ---- 0. 급할 때: 먹을 게 떨어짐 → 땅 위로 올라가 사냥 ----
        int foodNow = foodCount(p);
        if (foodNow < 2 && (p.getFoodData().getFoodLevel() < 17 || p.getHealth() < 12) && tier >= 1) {
            stage = "먹을 게 없음 → 사냥하러";
            BlockPos fp = PathExec.feet(p);
            int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, fp.getX(), fp.getZ());
            if (fp.getY() < top - 4 && l.getBrightness(net.minecraft.world.level.LightLayer.SKY, fp) < 8) {
                detail = "땅 위로 올라가는 중";
                Nav.R r = bot.nav.go(new Goal.Above(top - 1));
                if (r == Nav.R.FAILED) bot.nav.stop();
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
        int needPlanks = (Inv.has(p, Items.CRAFTING_TABLE) ? 0 : 4) + (tier == 0 ? 3 : 0) + (int) Math.ceil(needSticks / 4.0) * 2;
        if (tier < 3 && planksEq(p) < needPlanks) {
            stage = "1. 나무 모으기";
            int wantLogs = Inv.count(p, logs) + (int) Math.ceil((needPlanks - planksEq(p)) / 4.0) + 1;
            // 땅속이면 먼저 땅 위로
            BlockPos f = PathExec.feet(p);
            int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
            if (f.getY() < top - 4 && (miner == null || !minerKey.equals("logs") || !miner.busy())) {
                detail = "땅 위로 올라가는 중";
                Nav.R r = bot.nav.go(new Goal.Above(top - 1));
                if (r == Nav.R.FAILED) bot.nav.stop();
                return S.RUNNING;
            }
            return mine(bot, "logs", s -> s.is(BlockTags.LOGS), logs, wantLogs, false, 6, 20);
        }
        if (Inv.count(p, planks) < needPlanks + 2 && Inv.count(p, logs) > 0 && !cool(bot, "planks")) {
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
        if (Inv.count(p, Items.COAL) + Inv.count(p, Items.CHARCOAL) < 5 && !cool(bot, "coal")) {
            stage = "4. 석탄 캐기";
            S s = mine(bot, "coal", st -> st.is(Blocks.COAL_ORE) || st.is(Blocks.DEEPSLATE_COAL_ORE), is(Items.COAL), 10, true, 24, 24);
            if (miner == null && exploreTo != null) { fail(bot, "coal", 20 * 120); exploreTo = null; bot.nav.stop(); }
            return s;
        }
        // ---- 5. 철 ----
        int ironHave = Inv.count(p, Items.IRON_INGOT) + Inv.count(p, Items.RAW_IRON) + ironInGear(p);
        if (ironHave < 30) {
            stage = "5. 철 캐기 (" + ironHave + "/30)";
            return mineDeep(bot, "iron", st -> st.is(Blocks.IRON_ORE) || st.is(Blocks.DEEPSLATE_IRON_ORE), is(Items.RAW_IRON),
                    Inv.count(p, Items.RAW_IRON) + (30 - ironHave), 16);
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
        // 줍기: 근처에 떨어진 쓸 만한 것 (실, 깃털, 화살, 활, 진주 등)
        S loot = loot(bot, p, l);
        if (loot != null) return loot;

        boolean afterNether = NetherTask.rods(p) >= NetherTask.RODS && NetherTask.pearls(p) >= NetherTask.PEARLS;
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
            if (Inv.count(p, Items.OBSIDIAN) < 10 && !builtPortal) {
                stage = "7. 흑요석 만들기 (" + Inv.count(p, Items.OBSIDIAN) + "/10)";
                S r = run(bot, "obsidian", () -> new ObsidianTask(10));
                if (cool(bot, "obsidian")) {
                    // 근처에 용암이 없으면 더 깊이 내려가서 찾음
                    if (digger == null) digger = new Digger(Direction.fromYRot(p.getYRot()));
                    digger.tick(bot, PathExec.feet(p).getY() > -50 ? Digger.Mode.STAIRS : Digger.Mode.TUNNEL);
                    detail = "용암을 찾아 이동 — " + digger.info;
                }
                return r;
            }
            stage = "7. 네더 포털 짓기";
            builtPortal = true;
            return run(bot, "portal", PortalTask::new);
        }

        // ---- 9. 엔드 준비: 엔더의 눈, 활과 화살 ----
        int eyes = Inv.count(p, Items.ENDER_EYE);
        if (Inv.count(p, Items.ENDER_PEARL) > 0) {
            if (Inv.count(p, Items.BLAZE_POWDER) < 1 && Inv.has(p, Items.BLAZE_ROD)) {
                stage = "9. 블레이즈 가루";
                int want = Inv.count(p, Items.BLAZE_POWDER) + Inv.count(p, Items.BLAZE_ROD) * 2;
                return run(bot, "powder", () -> new CraftTask(Items.BLAZE_POWDER, want));
            }
            if (Inv.count(p, Items.BLAZE_POWDER) > 0) {
                stage = "9. 엔더의 눈";
                int want = eyes + Math.min(Inv.count(p, Items.ENDER_PEARL), Inv.count(p, Items.BLAZE_POWDER));
                return run(bot, "eyes", () -> new CraftTask(Items.ENDER_EYE, want));
            }
        }
        if (!Inv.has(p, Items.BOW) && !cool(bot, "bow")) {
            if (Inv.count(p, Items.STRING) >= 3) { stage = "9. 활 만들기"; return run(bot, "bow", () -> new CraftTask(Items.BOW, 1)); }
            stage = "9. 활용 실 모으기 (" + Inv.count(p, Items.STRING) + "/3)";
            BlockPos web = net.damoon.bot.Scan.nearest(l, PathExec.feet(p), 3, PathExec.feet(p).getY() - 30, PathExec.feet(p).getY() + 20, st -> st.is(Blocks.COBWEB), null);
            if (web != null) return mine(bot, "web", st -> st.is(Blocks.COBWEB), is(Items.STRING), 3, true, 30, 20);
            if (hunter == null || !"spider".equals(hunterKey)) { hunter = new Hunter(e -> e instanceof net.minecraft.world.entity.monster.Spider, is(Items.STRING)); hunterKey = "spider"; }
            Hunter.R r = hunter.tick(bot);
            detail = hunter.info;
            if (r == Hunter.R.NONE) { fail(bot, "bow", 20 * 60 * 3); bot.log("거미가 안 보임 → 활 없이 먼저 요새로"); }
            return S.RUNNING;
        }
        int arrows = Inv.count(p, Items.ARROW);
        if (Inv.has(p, Items.BOW) && arrows < 16 && !cool(bot, "arrows")) {
            if (Inv.count(p, Items.FLINT) > 0 && Inv.count(p, Items.FEATHER) > 0 && Inv.count(p, Items.STICK) > 0) {
                stage = "9. 화살 만들기";
                return run(bot, "arrow", () -> new CraftTask(Items.ARROW, arrows + 4));
            }
            if (Inv.count(p, Items.FEATHER) == 0) {
                stage = "9. 화살용 깃털 (닭 사냥)";
                if (hunter == null || !"chicken".equals(hunterKey)) { hunter = new Hunter(e -> e instanceof net.minecraft.world.entity.animal.Chicken, is(Items.FEATHER)); hunterKey = "chicken"; }
                Hunter.R r = hunter.tick(bot);
                detail = hunter.info;
                if (r == Hunter.R.NONE) fail(bot, "arrows", 20 * 60 * 3);
                return S.RUNNING;
            }
            stage = "9. 화살용 부싯돌 (자갈 캐기)";
            S r = mineDeep(bot, "flint2", st -> st.is(Blocks.GRAVEL), is(Items.FLINT), Inv.count(p, Items.FLINT) + 1, PathExec.feet(p).getY());
            if (++flintTicks > 20 * 60 * 4) { flintTicks = 0; fail(bot, "arrows", 20 * 60 * 5); }
            return r;
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

    private String hunterKey = "";
    private Hunter hunterFood;
    private final java.util.Set<Integer> lootIgnore = new java.util.HashSet<>();
    private int lootId = -1, lootTicks;
    private int goldTicks, flintTicks;
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
            if (ie.getId() != lootId) { lootId = ie.getId(); lootTicks = 0; }
            if (++lootTicks > 20 * 10) { lootIgnore.add(ie.getId()); bot.nav.stop(); continue; }
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
        Object[][] cost = {{Items.IRON_PICKAXE, 3}, {Items.IRON_SWORD, 2}, {Items.SHIELD, 1}, {Items.BUCKET, 3}, {Items.WATER_BUCKET, 3}, {Items.LAVA_BUCKET, 3},
                {Items.IRON_CHESTPLATE, 8}, {Items.IRON_LEGGINGS, 7}, {Items.IRON_BOOTS, 4}, {Items.IRON_HELMET, 5}};
        for (Object[] c : cost) {
            Item it = (Item) c[0];
            if (Inv.has(p, it) || worn(p, it)) n += (int) c[1];
        }
        return n;
    }
}
