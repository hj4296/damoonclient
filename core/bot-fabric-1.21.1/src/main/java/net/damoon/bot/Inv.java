package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Predicate;

/** 인벤토리 다루기: 찾기·세기·손에 들기·갑옷 입기 */
public final class Inv {
    private Inv() {}

    public static final Set<Item> THROWAWAY = Set.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.END_STONE, Items.STONE,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.BLACKSTONE, Items.TUFF, Items.DEEPSLATE, Items.BASALT,
            Items.SOUL_SOIL, Items.SANDSTONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS);

    public static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);

    public static Predicate<ItemStack> is(Item... items) {
        Set<Item> set = Set.of(items);
        return s -> !s.isEmpty() && set.contains(s.getItem());
    }

    public static Predicate<ItemStack> throwaway() {
        return s -> !s.isEmpty() && THROWAWAY.contains(s.getItem());
    }

    /** 폭발에 강한 벽 블록 (가스트 화염구에 안 부서짐) 우선, 없으면 아무 발판 블록 */
    public static final Set<Item> STRONG = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.BLACKSTONE,
            Items.DEEPSLATE, Items.END_STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BASALT);

    public static Predicate<ItemStack> wall(net.minecraft.client.player.LocalPlayer p) {
        Predicate<ItemStack> strong = s -> !s.isEmpty() && STRONG.contains(s.getItem());
        return count(p, strong) > 0 ? strong : throwaway();
    }

    public static Predicate<ItemStack> food() {
        return s -> !s.isEmpty() && s.has(DataComponents.FOOD) && !BAD_FOOD.contains(s.getItem());
    }

    /** 인벤토리 번호(0~35, 0~8 핫바) — 없으면 -1 */
    public static int find(LocalPlayer p, Predicate<ItemStack> f) {
        Inventory inv = p.getInventory();
        if (f.test(inv.items.get(inv.selected))) return inv.selected;
        for (int i = 0; i < 36; i++) if (f.test(inv.items.get(i))) return i;
        return -1;
    }

    public static int count(LocalPlayer p, Predicate<ItemStack> f) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (f.test(s)) n += s.getCount();
        }
        if (f.test(inv.offhand.get(0))) n += inv.offhand.get(0).getCount();
        return n;
    }

    public static int count(LocalPlayer p, Item item) {
        return count(p, is(item));
    }

    public static boolean has(LocalPlayer p, Item item) {
        return count(p, item) > 0;
    }

    public static int menuSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    /** 조건에 맞는 아이템을 손에 듦 (핫바에 없으면 가방에서 꺼내 옴) */
    public static boolean select(LocalPlayer p, Predicate<ItemStack> f) {
        Inventory inv = p.getInventory();
        int i = find(p, f);
        if (i < 0) return false;
        if (i < 9) {
            inv.selected = i;
            return true;
        }
        if (p.containerMenu != p.inventoryMenu) return false;
        int hot = -1;
        for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { hot = h; break; }
        if (hot < 0) hot = 8;
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, hot, ClickType.SWAP, p);
        inv.selected = hot;
        return true;
    }

    /** 이 블록을 가장 빨리 캐는 도구 들기 */
    public static void selectTool(LocalPlayer p, BlockState s) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty()) continue;
            float sp = st.getDestroySpeed(s);
            boolean correct = !s.requiresCorrectToolForDrops() || st.isCorrectToolForDrops(s);
            double score = sp * (correct ? 3 : 1) - (i < 9 ? 0 : 0.01);
            if (sp > 1.0f && score > bestScore) { bestScore = score; best = i; }
        }
        if (best >= 0) {
            final int b = best;
            final ItemStack want = inv.items.get(b);
            select(p, st -> st == want);
        } else {
            // 도구가 도움이 안 되면 칼·도구 말고 다른 걸 듦 (내구도 아끼기)
            ItemStack cur = inv.getSelected();
            if (cur.isDamageableItem()) {
                for (int h = 0; h < 9; h++) if (!inv.items.get(h).isDamageableItem()) { inv.selected = h; break; }
            }
        }
    }

    public static boolean selectWeapon(LocalPlayer p) {
        return select(p, s -> s.getItem() instanceof SwordItem) || select(p, s -> s.getItem() instanceof AxeItem);
    }

    /** 가방에 있는 더 좋은 갑옷을 입음. 한 번에 하나씩 (클릭) — 입었으면 true */
    public static boolean equipArmor(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) return false;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.items.get(i);
            if (!(st.getItem() instanceof ArmorItem ai)) continue;
            EquipmentSlot slot = ai.getEquipmentSlot();
            ItemStack worn = p.getItemBySlot(slot);
            if (worn.isEmpty()) {
                Minecraft.getInstance().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(i), 0, ClickType.QUICK_MOVE, p);
                return true;
            }
            if (worn.getItem() instanceof ArmorItem wa && ai.getDefense() > wa.getDefense()) {
                int armorMenuSlot = switch (slot) { case HEAD -> 5; case CHEST -> 6; case LEGS -> 7; default -> 8; };
                var gm = Minecraft.getInstance().gameMode;
                int id = p.inventoryMenu.containerId;
                gm.handleInventoryMouseClick(id, menuSlot(i), 0, ClickType.PICKUP, p);
                gm.handleInventoryMouseClick(id, armorMenuSlot, 0, ClickType.PICKUP, p);
                gm.handleInventoryMouseClick(id, menuSlot(i), 0, ClickType.PICKUP, p);
                return true;
            }
        }
        return false;
    }

    public static int foodValue(ItemStack s) {
        FoodProperties fp = s.get(DataComponents.FOOD);
        return fp == null ? 0 : fp.nutrition();
    }

    /** 방패를 왼손에 듦 (이미 들었으면 true) */
    public static boolean equipShield(LocalPlayer p) {
        if (p.getOffhandItem().is(Items.SHIELD)) return true;
        if (p.containerMenu != p.inventoryMenu) return false;
        int i = find(p, is(Items.SHIELD));
        if (i < 0) return false;
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(i), 40, ClickType.SWAP, p);
        return true;
    }

    public static final Set<Item> JUNK = Set.of(Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF, Items.CALCITE, Items.DIRT,
            Items.WHEAT_SEEDS, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.BONE, Items.MOSS_BLOCK, Items.CLAY_BALL,
            Items.OAK_SAPLING, Items.DARK_OAK_SAPLING, Items.SPRUCE_SAPLING, Items.BIRCH_SAPLING, Items.DRIPSTONE_BLOCK, Items.POINTED_DRIPSTONE,
            Items.SAND, Items.RED_SAND, Items.SANDSTONE, Items.AZALEA, Items.FLOWERING_AZALEA, Items.MOSS_CARPET, Items.GLOW_BERRIES,
            Items.SMOOTH_BASALT, Items.AMETHYST_SHARD);

    /** 빈 칸이 적으면 잡동사니를 버림 (블록용 조약돌은 2묶음까지만). 버렸으면 true */
    public static boolean dropJunk(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) return false;
        Inventory inv = p.getInventory();
        int empty = 0;
        for (int i = 0; i < 36; i++) if (inv.items.get(i).isEmpty()) empty++;
        if (empty >= 4) return false;
        int keepBlocks = 128;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || i == inv.selected) continue;
            boolean junk = JUNK.contains(s.getItem());
            if (!junk && THROWAWAY.contains(s.getItem())) {
                if (keepBlocks > 0) { keepBlocks -= s.getCount(); continue; }
                junk = true;
            }
            if (junk) {
                Minecraft.getInstance().gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(i), 1, ClickType.THROW, p);
                return true;
            }
        }
        return false;
    }
}
