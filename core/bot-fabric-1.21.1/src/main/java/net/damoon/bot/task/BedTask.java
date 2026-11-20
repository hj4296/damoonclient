package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Placer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;

/** 밤이 되면 침대를 놓고 자서 밤을 건너뜀 (아침에 다시 챙김) */
public class BedTask extends Task {
    private BlockPos bed;
    private Item bedItem;
    private int tries, wait, sleptTicks;
    private String detail = "";

    @Override public String name() { return "잠자기"; }
    @Override public String detail() { return detail; }

    public static boolean night(ClientLevel l) {
        long t = l.getDayTime() % 24000;
        return t >= 12600 && t <= 23300;
    }

    public static boolean hasBed(LocalPlayer p) {
        return Inv.count(p, s -> s.is(ItemTags.BEDS)) > 0;
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (wait > 0) { wait--; return S.RUNNING; }
        if (p.isSleeping()) {
            detail = "자는 중 (밤 건너뛰기)";
            sleptTicks++;
            return S.RUNNING;
        }
        if (!night(l)) {
            // 아침: 침대 다시 챙기기
            if (bed != null && l.getBlockState(bed).getBlock() instanceof BedBlock && bedItem != null) {
                detail = "침대 다시 챙기는 중";
                if (!Placer.pickUp(bot, bed, l.getBlockState(bed).getBlock(), bedItem)) return S.RUNNING;
            }
            return S.DONE;
        }
        if (bed == null || !(l.getBlockState(bed).getBlock() instanceof BedBlock)) {
            int i = Inv.find(p, s -> s.is(ItemTags.BEDS));
            if (i < 0) return S.FAILED;
            ItemStack st = p.getInventory().items.get(i);
            bedItem = st.getItem();
            detail = "침대 놓는 중";
            BlockPos at = Placer.placeNear(bot, bedItem);
            if (at != null) { bed = at; wait = 4; return S.RUNNING; }
            // 놓을 방향을 바꿔 봄
            if (++tries % 20 == 0) bot.ctl.look(p.getYRot() + 90, 30f);
            return tries > 200 ? S.FAILED : S.RUNNING;
        }
        detail = "침대에 눕는 중";
        Placer.open(bot, bed);
        wait = 10;
        if (++tries > 300) return S.FAILED;
        return S.RUNNING;
    }

    /** 가진 양털 중 같은 색 3개로 만들 수 있는 침대 */
    public static Item bedFor(LocalPlayer p) {
        Item[][] m = {{Items.WHITE_WOOL, Items.WHITE_BED}, {Items.BLACK_WOOL, Items.BLACK_BED}, {Items.GRAY_WOOL, Items.GRAY_BED},
                {Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_BED}, {Items.BROWN_WOOL, Items.BROWN_BED}, {Items.PINK_WOOL, Items.PINK_BED}};
        for (Item[] x : m) if (Inv.count(p, x[0]) >= 3) return x[1];
        return null;
    }
}
