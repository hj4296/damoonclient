package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Clicks;
import net.damoon.bot.Inv;
import net.damoon.bot.Nav;
import net.damoon.bot.Placer;
import net.damoon.bot.Act;
import net.damoon.bot.path.Goal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

/** 화로에 재료와 연료를 넣고 다 구워질 때까지 기다렸다가 꺼냄 */
public class SmeltTask extends Task {
    private final Predicate<ItemStack> input;
    private final Item output;
    private final int count;
    private BlockPos furnace;
    private int wait, tries, idle;
    private CraftTask sub;
    private String detail = "";
    private BlockPos placed;
    private boolean done;
    private int pickTicks;

    public SmeltTask(Predicate<ItemStack> input, Item output, int count) {
        this.input = input;
        this.output = output;
        this.count = count;
    }

    @Override public String name() { return "굽기"; }
    @Override public String detail() { return sub != null ? sub.detail() : detail; }

    public static Predicate<ItemStack> fuel() {
        return s -> !s.isEmpty() && (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS_THAT_BURN)
                || s.is(Items.COAL_BLOCK) || s.is(Items.STICK) || s.is(Items.LAVA_BUCKET));
    }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (sub != null) {
            S s = sub.tick(bot);
            if (s == S.RUNNING) return s;
            sub = null;
            if (s == S.FAILED) return S.FAILED;
        }
        int have = Inv.count(p, output);
        if (wait > 0) { wait--; return S.RUNNING; }
        if (!(p.containerMenu instanceof AbstractFurnaceMenu fm)) {
            if (have >= count || done) {
                if (placed != null && ++pickTicks < 100) {
                    detail = "화로 다시 챙기는 중";
                    if (!Placer.pickUp(bot, placed, Blocks.FURNACE, Items.FURNACE)) return S.RUNNING;
                }
                return have > 0 ? S.DONE : S.FAILED;
            }
            if (Inv.count(p, input) == 0) return have > 0 ? S.DONE : S.FAILED;
            if (p.containerMenu != p.inventoryMenu) { p.closeContainer(); return S.RUNNING; }
            if (furnace == null || !l.getBlockState(furnace).is(Blocks.FURNACE)) furnace = Placer.near(l, p, Blocks.FURNACE, 4.4);
            if (furnace == null) {
                BlockPos far = Placer.near(l, p, Blocks.FURNACE, 14);
                if (far != null) {
                    detail = "화로로 가는 중";
                    Nav.R nr = bot.nav.go(new Goal.Reach(far, 3.5));
                    if (nr != Nav.R.FAILED) return S.RUNNING;
                    bot.nav.stop();
                }
                if (!Inv.has(p, Items.FURNACE)) {
                    sub = new CraftTask(Items.FURNACE, 1);
                    return S.RUNNING;
                }
                detail = "화로 놓는 중";
                furnace = Placer.placeNear(bot, Items.FURNACE);
                if (furnace != null) placed = furnace;
                if (furnace != null) wait = 3;
                if (furnace == null && ++tries > 80) return S.FAILED;
                return S.RUNNING;
            }
            detail = "화로 여는 중";
            Placer.open(bot, furnace);
            wait = 4;
            if (++tries > 80) return S.FAILED;
            return S.RUNNING;
        }
        // 화로 안: 0 재료, 1 연료, 2 결과
        ItemStack res = fm.getSlot(2).getItem();
        if (!res.isEmpty()) Clicks.click(p, 2, 0, ClickType.QUICK_MOVE);
        have = Inv.count(p, output);
        int need = count - have;
        ItemStack in = fm.getSlot(0).getItem();
        detail = output.getDescription().getString() + " 굽는 중 (" + have + "/" + count + ")";
        if (need <= 0) {
            if (!in.isEmpty()) Clicks.click(p, 0, 0, ClickType.QUICK_MOVE);
            Clicks.dropCarried(p);
            p.closeContainer();
            done = true;
            return S.RUNNING;
        }
        if (in.isEmpty()) {
            int src = Clicks.findInvSlot(p, input);
            if (src < 0) {
                if (fm.isLit()) return S.RUNNING;
                p.closeContainer();
                done = true;
                return S.RUNNING;
            }
            Clicks.click(p, src, 0, ClickType.PICKUP);
            int n = Math.min(need, p.containerMenu.getCarried().getCount());
            for (int i = 0; i < n; i++) Clicks.click(p, 0, 1, ClickType.PICKUP);
            Clicks.click(p, src, 0, ClickType.PICKUP);
            Clicks.dropCarried(p);
            wait = 2;
            return S.RUNNING;
        }
        ItemStack fuelSlot = fm.getSlot(1).getItem();
        if (fuelSlot.isEmpty() && !fm.isLit()) {
            // 석탄 1개 = 8개, 판자 1개 = 1.5개
            int src = Clicks.findInvSlot(p, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
            int n;
            if (src >= 0) n = (int) Math.ceil(in.getCount() / 8.0);
            else {
                src = Clicks.findInvSlot(p, fuel());
                n = (int) Math.ceil(in.getCount() / 1.5);
            }
            if (src < 0) { bot.log("연료 없음"); p.closeContainer(); return S.FAILED; }
            Clicks.click(p, src, 0, ClickType.PICKUP);
            n = Math.min(n, p.containerMenu.getCarried().getCount());
            for (int i = 0; i < n; i++) Clicks.click(p, 1, 1, ClickType.PICKUP);
            Clicks.click(p, src, 0, ClickType.PICKUP);
            Clicks.dropCarried(p);
            wait = 2;
            return S.RUNNING;
        }
        // 굽는 중 — 기다림
        if (!fm.isLit() && fuelSlot.isEmpty()) idle++;
        wait = 10;
        return S.RUNNING;
    }
}
