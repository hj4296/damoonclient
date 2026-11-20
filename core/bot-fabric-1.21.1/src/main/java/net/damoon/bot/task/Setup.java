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
