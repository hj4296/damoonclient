package net.damoon.bot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 불러온 청크에서 블록 찾기 (청크 구역 팔레트로 빠르게 건너뜀).
 * Baritone/AltoClef 처럼 불러온 청크의 블록 정보를 읽습니다.
 */
public final class Scan {
    private Scan() {}

    public static List<BlockPos> find(ClientLevel l, BlockPos from, int chunkRadius, int minY, int maxY,
                                      Predicate<BlockState> pred, Predicate<BlockPos> ok, int limit) {
        List<BlockPos> out = new ArrayList<>();
        int fcx = from.getX() >> 4, fcz = from.getZ() >> 4;
        for (int cx = fcx - chunkRadius; cx <= fcx + chunkRadius; cx++) {
            for (int cz = fcz - chunkRadius; cz <= fcz + chunkRadius; cz++) {
                if (!l.hasChunk(cx, cz)) continue;
                LevelChunk ch = l.getChunk(cx, cz);
                LevelChunkSection[] secs = ch.getSections();
                for (int i = 0; i < secs.length; i++) {
                    LevelChunkSection sec = secs[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    int by = l.getSectionYFromSectionIndex(i) << 4;
                    if (by + 15 < minY || by > maxY) continue;
                    if (!sec.maybeHas(pred)) continue;
                    for (int y = 0; y < 16; y++) {
                        int wy = by + y;
                        if (wy < minY || wy > maxY) continue;
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState s = sec.getBlockState(x, y, z);
                                if (!pred.test(s)) continue;
                                BlockPos p = new BlockPos((cx << 4) + x, wy, (cz << 4) + z);
                                if (ok == null || ok.test(p)) out.add(p);
                            }
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public static BlockPos nearest(ClientLevel l, BlockPos from, int chunkRadius, int minY, int maxY,
                                   Predicate<BlockState> pred, Predicate<BlockPos> ok) {
        List<BlockPos> r = find(l, from, chunkRadius, minY, maxY, pred, ok, 1);
        return r.isEmpty() ? null : r.get(0);
    }
}
