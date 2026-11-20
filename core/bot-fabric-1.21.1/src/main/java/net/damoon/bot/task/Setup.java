package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** 테스트 준비: 새 월드 만들기, 엔드 전투용 장비 지급 */
public final class Setup {
    private Setup() {}

    public static void run(Bot bot, String what) {
        switch (what) {
            case "end" -> {
                bot.server(
                        "clear @s",
                        "effect clear @s",
                        "item replace entity @s armor.head with netherite_helmet[enchantments={levels:{protection:4,unbreaking:3}}]",
                        "item replace entity @s armor.chest with netherite_chestplate[enchantments={levels:{protection:4,unbreaking:3}}]",
                        "item replace entity @s armor.legs with netherite_leggings[enchantments={levels:{protection:4,unbreaking:3}}]",
                        "item replace entity @s armor.feet with netherite_boots[enchantments={levels:{protection:4,feather_falling:4,unbreaking:3}}]",
                        "give @s netherite_sword[enchantments={levels:{sharpness:5,unbreaking:3}}]",
                        "give @s bow[enchantments={levels:{power:5,infinity:1,unbreaking:3}}]",
                        "give @s netherite_pickaxe[enchantments={levels:{efficiency:5,unbreaking:3}}]",
                        "give @s cooked_beef 64",
                        "give @s golden_apple 16",
                        "give @s water_bucket",
                        "give @s cobblestone 64",
                        "give @s cobblestone 64",
                        "give @s cobblestone 64",
                        "give @s cobblestone 64",
                        "give @s arrow 64",
                        "time set day",
                        "difficulty normal",
                        "fill ~3 ~-1 ~-1 ~5 ~-1 ~1 minecraft:end_portal");
                bot.log("엔드 전투 장비 지급 + 앞에 엔드 포털 설치");
            }
            case "s6" -> {
                // 시험용: 철 장비까지 끝난 상태 (다이아몬드 단계부터 시험)
                bot.server("clear @s",
                        "give @s iron_pickaxe", "give @s iron_sword", "give @s shield", "give @s bucket",
                        "item replace entity @s armor.chest with iron_chestplate", "item replace entity @s armor.legs with iron_leggings",
                        "item replace entity @s armor.feet with iron_boots", "give @s cooked_beef 24", "give @s cobblestone 64",
                        "give @s oak_planks 32", "give @s stick 16", "give @s crafting_table", "give @s furnace", "give @s coal 16",
                        "give @s iron_ingot 3", "give @s stone_axe", "time set day");
                bot.log("시험용: 철 장비 상태로 지급");
            }
            case "s8" -> {
                // 시험용: 네더 포털 직전 상태
                run(bot, "s6");
                bot.server("give @s diamond_pickaxe", "give @s golden_helmet", "give @s flint_and_steel", "give @s obsidian 10");
                bot.log("시험용: 포털 직전 상태로 지급");
            }
            case "s9" -> {
                // 시험용: 네더를 마친 상태 (요새 찾기부터)
                run(bot, "s6");
                bot.server("give @s diamond_pickaxe", "give @s blaze_rod 7", "give @s ender_pearl 13", "give @s bow", "give @s arrow 32");
                bot.log("시험용: 네더를 마친 상태로 지급");
            }
            default -> bot.log("모르는 준비: " + what);
        }
    }

    public static void newWorld(Bot bot, long seed, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            bot.log("월드 안에서는 새 월드를 못 만듦 (title 먼저)");
            return;
        }
        LevelSettings ls = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        bot.log("새 월드 생성: " + name + " (시드 " + seed + ")");
        mc.createWorldOpenFlows().createFreshLevel(name, ls, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions, mc.screen);
    }

    public static void loadWorld(Bot bot, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            bot.log("이미 월드 안");
            return;
        }
        mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
    }
}
