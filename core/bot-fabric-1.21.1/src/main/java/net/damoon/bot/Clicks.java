package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** 열린 창(인벤토리·작업대·화로)에서 슬롯 클릭 */
public final class Clicks {
    private Clicks() {}

    public static void click(LocalPlayer p, int slot, int button, ClickType type) {
        AbstractContainerMenu m = p.containerMenu;
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(m.containerId, slot, button, type, p);
    }

    /** 창 안에서 플레이어 인벤토리 칸에 해당하는 슬롯 번호 (조건에 맞는 아이템, 핫바는 뒤로) */
    public static int findInvSlot(LocalPlayer p, Predicate<ItemStack> f) {
        AbstractContainerMenu m = p.containerMenu;
        Inventory inv = p.getInventory();
        int best = -1, bestCount = -1;
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (s.container != inv || s.getContainerSlot() >= 36) continue;
            ItemStack st = s.getItem();
            if (f.test(st) && st.getCount() > bestCount) { best = i; bestCount = st.getCount(); }
        }
        return best;
    }

    public static int emptyInvSlot(LocalPlayer p) {
        AbstractContainerMenu m = p.containerMenu;
        Inventory inv = p.getInventory();
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (s.container == inv && s.getContainerSlot() < 36 && s.getItem().isEmpty()) return i;
        }
        return -1;
    }

    /** 손(커서)에 든 게 있으면 빈 칸에 내려놓음 */
    public static void dropCarried(LocalPlayer p) {
        if (p.containerMenu.getCarried().isEmpty()) return;
        int e = emptyInvSlot(p);
        if (e >= 0) click(p, e, 0, ClickType.PICKUP);
        else click(p, -999, 0, ClickType.PICKUP); // 바깥에 버림
    }
}
