package net.damoon.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/** 블록 판정: 지나갈 수 있는지, 밟을 수 있는지, 캘 수 있는지, 위험한지 */
public final class BlockUtil {
    private BlockUtil() {}

    public static final double INF = 1e9;

    /** 청크가 안 불러와졌으면 null (모르는 곳 = 못 감) */
    public static BlockState get(Level l, int x, int y, int z) {
        if (y < l.getMinBuildHeight()) return null;
        if (y >= l.getMaxBuildHeight()) return Blocks.AIR.defaultBlockState();
        if (!l.hasChunk(x >> 4, z >> 4)) return null;
        return l.getBlockState(new BlockPos(x, y, z));
    }

    public static boolean isLava(BlockState s) {
        return s != null && s.getFluidState().is(FluidTags.LAVA);
    }

    public static boolean isWater(BlockState s) {
        return s != null && s.getFluidState().is(FluidTags.WATER);
    }

    /** 닿으면 아프거나 갇히는 블록 */
    public static boolean hurts(BlockState s) {
        if (s == null) return false;
        Block b = s.getBlock();
        return isLava(s) || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW || b == Blocks.WITHER_ROSE
                || b instanceof CampfireBlock || b == Blocks.POINTED_DRIPSTONE;
    }

    public static boolean isPortal(BlockState s) {
        if (s == null) return false;
        Block b = s.getBlock();
        return b == Blocks.END_PORTAL || b == Blocks.NETHER_PORTAL || b == Blocks.END_GATEWAY;
    }

    /** 몸이 들어갈 수 있는 칸 (물은 헤엄쳐서 지나감) */
    public static boolean passable(Level l, int x, int y, int z, BlockState s) {
        if (s == null) return false;
        if (s.isAir()) return true;
        if (hurts(s) || isPortal(s)) return false;
        if (!s.getFluidState().isEmpty()) return isWater(s) && s.getCollisionShape(l, new BlockPos(x, y, z)).isEmpty();
        return s.getCollisionShape(l, new BlockPos(x, y, z)).isEmpty();
    }

    /** 위에 설 수 있는 블록 (물 위는 헤엄) */
    public static boolean floor(Level l, int x, int y, int z, BlockState s) {
        if (s == null) return false;
        if (isWater(s)) return true;
        if (hurts(s) || s.isAir()) return false;
        VoxelShape sh = s.getCollisionShape(l, new BlockPos(x, y, z));
        if (sh.isEmpty()) return false;
        double top = sh.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    /** 캐도 되는 블록 */
    public static boolean breakable(Level l, int x, int y, int z, BlockState s) {
        if (s == null || s.isAir() || !s.getFluidState().isEmpty()) return false;
        Block b = s.getBlock();
        if (b == Blocks.BEDROCK || b == Blocks.END_PORTAL_FRAME || b == Blocks.BARRIER || isPortal(s)
                || b == Blocks.SPAWNER || b == Blocks.REINFORCED_DEEPSLATE || b == Blocks.DRAGON_EGG) return false;
        return s.getDestroySpeed(l, new BlockPos(x, y, z)) >= 0;
    }

    public static boolean falls(BlockState s) {
        return s != null && s.getBlock() instanceof FallingBlock;
    }

    /** 이 블록을 캤을 때 용암·물이 흘러들어올 수 있는지 */
    public static boolean fluidNear(Level l, int x, int y, int z, boolean lavaOnly) {
        int[][] d = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            BlockState s = get(l, x + o[0], y + o[1], z + o[2]);
            if (s == null) continue;
            if (isLava(s)) return true;
            if (!lavaOnly && isWater(s)) return true;
        }
        return false;
    }

    /** 인벤토리 최고 도구로 캐는 데 걸리는 틱 수 (needDrop이면 드랍이 나오는 도구만) */
    public static double breakTicks(Player p, Level l, BlockPos pos, BlockState s, boolean needDrop, Map<Block, Double> cache) {
        Block b = s.getBlock();
        if (cache != null) {
            Double c = cache.get(b);
            if (c != null) return c;
        }
        float h = s.getDestroySpeed(l, pos);
        double r;
        if (h < 0) r = INF;
        else if (h == 0) r = 1;
        else {
            double best = 0;
            var inv = p.getInventory();
            for (int i = 0; i <= 36; i++) {
                ItemStack st = i < 36 ? inv.items.get(i) : ItemStack.EMPTY;
                float sp = st.isEmpty() ? 1f : st.getDestroySpeed(s);
                boolean correct = !s.requiresCorrectToolForDrops() || (!st.isEmpty() && st.isCorrectToolForDrops(s));
                if (needDrop && !correct) continue;
                double prog = sp / h / (correct ? 30.0 : 100.0);
                if (prog > best) best = prog;
            }
            r = best <= 0 ? INF : Math.ceil(1.0 / Math.min(1.0, best)) + 1;
        }
        if (cache != null) cache.put(b, r);
        return r;
    }

    public static Map<Block, Double> newCache() {
        return new HashMap<>();
    }
}
