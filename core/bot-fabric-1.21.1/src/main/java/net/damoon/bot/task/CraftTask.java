package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Clicks;
import net.damoon.bot.Inv;
import net.damoon.bot.Nav;
import net.damoon.bot.Placer;
import net.damoon.bot.path.Goal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 아이템 조합: 조합법을 찾아 재료를 칸에 직접 클릭해서 넣고 결과를 꺼냄.
 * 2x2로 되면 인벤토리에서, 아니면 작업대를 놓고(없으면 만들고) 열어서.
 */
public class CraftTask extends Task {
    private final Item item;
    private final int count;
    private int step, wait, tries;
    private CraftTask sub;
    private BlockPos table, roomDig;
    private String detail = "";
    private BlockPos placed;
    private int pickTicks;

    public CraftTask(Item item, int count) {
        this.item = item;
        this.count = count;
    }

    @Override public String name() { return "조합"; }
    @Override public String detail() { return sub != null ? sub.detail() : detail; }

    public static boolean has(LocalPlayer p, Item item, int n) { return Inv.count(p, item) >= n; }

    /** 가진 수 (만들자마자 입은 갑옷도 셈 — 입은 걸 못 세서 갑옷을 두 벌씩 만들던 문제) */
    private int have(LocalPlayer p) {
        int n = Inv.count(p, item);
        for (net.minecraft.world.item.ItemStack st : p.getInventory().armor) if (st.is(item)) n += st.getCount();
        return n;
    }

    /** 재료가 있는 조합법 (없으면 아무거나) */
    public static RecipeHolder<CraftingRecipe> recipe(ClientLevel l, LocalPlayer p, Item item) {
        List<RecipeHolder<CraftingRecipe>> all = l.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        RecipeHolder<CraftingRecipe> any = null;
        for (RecipeHolder<CraftingRecipe> h : all) {
            ItemStack res = h.value().getResultItem(l.registryAccess());
            if (!res.is(item)) continue;
            if (h.value().getIngredients().isEmpty()) continue;
            if (any == null) any = h;
            if (missing(p, h.value(), 1) == null) return h;
        }
        return any;
    }

    /** 1번 조합에 모자란 재료 (다 있으면 null) */
    public static Ingredient missing(LocalPlayer p, CraftingRecipe r, int times) {
        Map<Integer, Integer> used = new HashMap<>();
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            boolean ok = false;
            for (int i = 0; i < 36; i++) {
                ItemStack s = p.getInventory().items.get(i);
                if (s.isEmpty() || !ing.test(s)) continue;
                int u = used.getOrDefault(i, 0);
                if (s.getCount() - u >= times) { used.put(i, u + times); ok = true; break; }
            }
            if (!ok) return ing;
        }
        return null;
    }

    public static boolean small(CraftingRecipe r) {
        if (r instanceof ShapedRecipe s) return s.getWidth() <= 2 && s.getHeight() <= 2;
        int n = 0;
        for (Ingredient i : r.getIngredients()) if (!i.isEmpty()) n++;
        return n <= 4;
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
        if (have(p) >= count) {
            if (p.containerMenu != p.inventoryMenu) { Clicks.dropCarried(p); p.closeContainer(); return S.RUNNING; }
            if (placed != null && item != Items.CRAFTING_TABLE && ++pickTicks < 100) {
                detail = "작업대 다시 챙기는 중";
                if (!Placer.pickUp(bot, placed, Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE)) return S.RUNNING;
            }
            return S.DONE;
        }
        if (wait > 0) { wait--; return S.RUNNING; }
        // 가방이 꽉 차면 만든 것을 꺼낼 자리가 없음 → 창을 닫고 잡동사니를 버림
        if (Inv.emptySlots(p) == 0) {
            if (p.containerMenu != p.inventoryMenu) { p.closeContainer(); wait = 2; return S.RUNNING; }
            if (Inv.dropJunk(p)) { wait = 3; return S.RUNNING; }
        }
        RecipeHolder<CraftingRecipe> h = recipe(l, p, item);
        if (h == null) { bot.log("조합법 없음: " + item); return S.FAILED; }
        CraftingRecipe r = h.value();
        if (missing(p, r, 1) != null) {
            bot.log("재료 부족: " + item.getDescription().getString());
            return S.FAILED;
        }
        detail = item.getDescription().getString() + " 만드는 중";
        boolean small = small(r);
        int gridW;
        if (small) {
            if (p.containerMenu != p.inventoryMenu && !(p.containerMenu instanceof CraftingMenu)) { p.closeContainer(); return S.RUNNING; }
            gridW = p.containerMenu instanceof CraftingMenu ? 3 : 2;
        } else {
            if (!(p.containerMenu instanceof CraftingMenu)) {
                if (p.containerMenu != p.inventoryMenu) { p.closeContainer(); return S.RUNNING; }
                if (table == null || !l.getBlockState(table).is(Blocks.CRAFTING_TABLE)) table = Placer.near(l, p, Blocks.CRAFTING_TABLE, 4.4);
                if (table == null) {
                    BlockPos far = net.damoon.bot.Placer.near(l, p, Blocks.CRAFTING_TABLE, 12);
                    if (far != null) {
                        detail = "작업대로 가는 중";
                        Nav.R nr = bot.nav.go(new Goal.Reach(far, 3.5));
                        if (nr == Nav.R.FAILED) bot.nav.stop(); else return S.RUNNING;
                    }
                    if (!Inv.has(p, Items.CRAFTING_TABLE)) {
                        sub = new CraftTask(Items.CRAFTING_TABLE, 1);
                        return S.RUNNING;
                    }
                    detail = "작업대 놓는 중";
                    // 좁은 굴이라 놓을 칸이 없으면 옆 벽을 한 칸 파서 자리를 만듦
                    if (roomDig != null) {
                        if (!l.getBlockState(roomDig).getCollisionShape(l, roomDig).isEmpty()) { detail = "작업대 놓을 자리 파는 중"; net.damoon.bot.Act.dig(bot, roomDig); return S.RUNNING; }
                        net.damoon.bot.Act.stopDig(bot);
                        roomDig = null;
                    }
                    if (tries % 20 == 19) {
                        BlockPos ft = net.damoon.bot.path.PathExec.feet(p);
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                            BlockPos c = ft.relative(d);
                            var cs = l.getBlockState(c);
                            if (cs.getCollisionShape(l, c).isEmpty() || !net.damoon.bot.BlockUtil.breakable(l, c.getX(), c.getY(), c.getZ(), cs)
                                    || net.damoon.bot.BlockUtil.fluidNear(l, c.getX(), c.getY(), c.getZ(), false)) continue;
                            roomDig = c;
                            break;
                        }
                    }
                    table = Placer.placeNear(bot, Items.CRAFTING_TABLE);
                if (table != null) placed = table;
                    if (table != null) wait = 3;
                    if (table == null && ++tries > 200) { bot.log("작업대 놓을 자리가 없음"); return S.FAILED; }
                    return S.RUNNING;
                }
                detail = "작업대 여는 중";
                Placer.open(bot, table);
                wait = 4;
                if (++tries > 60) return S.FAILED;
                return S.RUNNING;
            }
            gridW = 3;
        }
        // 재료 넣기
        int resultCount = r.getResultItem(l.registryAccess()).getCount();
        int times = Math.max(1, (int) Math.ceil((count - have(p)) / (double) resultCount));
        times = Math.min(times, 64);
        while (times > 1 && missing(p, r, times) != null) times--;
        if (!(p.containerMenu instanceof CraftingMenu || p.containerMenu instanceof InventoryMenu)) return S.RUNNING;
        int w = r instanceof ShapedRecipe s ? s.getWidth() : gridW;
        NonNullList<Ingredient> ings = r.getIngredients();
        clearGrid(p, gridW);
        for (int i = 0; i < ings.size(); i++) {
            Ingredient ing = ings.get(i);
            if (ing.isEmpty()) continue;
            int row = r instanceof ShapedRecipe ? i / w : i / gridW;
            int col = r instanceof ShapedRecipe ? i % w : i % gridW;
            int cell = 1 + row * gridW + col;
            int src = Clicks.findInvSlot(p, ing::test);
            if (src < 0) return S.FAILED;
            Clicks.click(p, src, 0, ClickType.PICKUP);
            for (int t = 0; t < times; t++) Clicks.click(p, cell, 1, ClickType.PICKUP);
            Clicks.click(p, src, 0, ClickType.PICKUP);
            Clicks.dropCarried(p);
        }
        // 결과 꺼내기 (쉬프트 클릭)
        Clicks.click(p, 0, 0, ClickType.QUICK_MOVE);
        clearGrid(p, gridW);
        wait = 3;
        if (++tries > 80) return S.FAILED;
        return S.RUNNING;
    }

    private static void clearGrid(LocalPlayer p, int gridW) {
        for (int c = 1; c <= gridW * gridW; c++) {
            if (!p.containerMenu.getSlot(c).getItem().isEmpty()) Clicks.click(p, c, 0, ClickType.QUICK_MOVE);
        }
    }
}
