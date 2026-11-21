package net.damoon.bot.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.damoon.bot.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* 길찾기. 걷기·대각선·한 칸 오르기·떨어지기·블록 캐기·다리 놓기·기둥 쌓기·아래로 파기를 비용(틱)으로 비교합니다.
 * 한 틱에 시간 예산만큼만 계산하고 다음 틱에 이어서 합니다.
 */
public final class PathFinder {
    public static final double WALK = 4.63, SPRINT = 3.56, JUMP = 2.5, FALL = 1.0, PLACE = 6.0, DIAG = 1.4142;
    public static final int MAX_FALL = 3;

    public enum Move { START, WALK, DIAG, ASCEND, DESCEND, BRIDGE, PILLAR, DIG_DOWN }

    public static final class Node {
        public final int x, y, z;
        double g, h, f;
        Node parent;
        public Move move = Move.START;
        public long[] breaks = NONE;
        public long place = Long.MIN_VALUE;
        boolean closed;

        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        public BlockPos pos() { return new BlockPos(x, y, z); }
        public long key() { return BlockPos.asLong(x, y, z); }
        @Override public String toString() { return move + "(" + x + "," + y + "," + z + ")"; }
    }

    static final long[] NONE = new long[0];

    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    private final Level level;
    private final Player player;
    private final Goal goal;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private final Long2ObjectOpenHashMap<BlockState> cache = new Long2ObjectOpenHashMap<>();
    private final Map<Block, Double> breakCache = BlockUtil.newCache();
    private final Node start;
    private Node best;
    private Node found;
    private int expanded;
    public int maxNodes = 60_000;
    public boolean canBreak = true;
    public int placeBlocks;
    public final java.util.Set<Long> avoid = new java.util.HashSet<>();
    /** 실제로 해 보니 못 가는 이동 (끼임) — 같은 길을 다시 고르지 않게 */
    public final java.util.Set<Long> badEdges = new java.util.HashSet<>();

    public static long edge(int fx, int fy, int fz, int tx, int ty, int tz) {
        return BlockPos.asLong(fx, fy, fz) * 1000003L ^ BlockPos.asLong(tx, ty, tz);
    }
    private Status status = Status.RUNNING;

    public PathFinder(Level level, Player player, BlockPos from, Goal goal, int throwaway) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.placeBlocks = throwaway;
        start = new Node(from.getX(), from.getY(), from.getZ());
        start.h = goal.h(start.x, start.y, start.z);
        start.f = start.h;
        nodes.put(start.key(), start);
        open.add(start);
        best = start;
    }

    public Status status() { return status; }
    public int expanded() { return expanded; }

    public Status step(long budgetNanos) {
        if (status != Status.RUNNING) return status;
        long end = System.nanoTime() + budgetNanos;
        int n = 0;
        while (!open.isEmpty()) {
            if ((++n & 63) == 0 && System.nanoTime() > end) return status;
            Node cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;
            if (goal.isIn(cur.x, cur.y, cur.z)) {
                found = cur;
                return status = Status.FOUND;
            }
            if (cur.h < best.h - 0.01 || (Math.abs(cur.h - best.h) < 0.01 && cur.g < best.g)) best = cur;
            if (++expanded > maxNodes) break;
            expand(cur);
        }
        return status = (best != start && best.h < start.h - 8) ? Status.PARTIAL : Status.FAILED;
    }

    /** 찾은 길 (못 찾았으면 목표에 가장 가까운 곳까지) */
    public List<Node> path() {
        Node end = found != null ? found : (best != start ? best : null);
        if (end == null) return null;
        List<Node> out = new ArrayList<>();
        for (Node c = end; c != null; c = c.parent) out.add(c);
        Collections.reverse(out);
        return out;
    }

    // ---------- 월드 조회 (캐시) ----------
    private BlockState st(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        BlockState s = cache.get(k);
        if (s == null && !cache.containsKey(k)) {
            s = BlockUtil.get(level, x, y, z);
            cache.put(k, s);
        }
        return s;
    }

    private boolean passable(int x, int y, int z) {
        BlockState s = st(x, y, z);
        if (s != null && goal.allowPortal() && BlockUtil.isPortal(s)) return true;
        return BlockUtil.passable(level, x, y, z, s);
    }

    private boolean floor(int x, int y, int z) {
        return BlockUtil.floor(level, x, y, z, st(x, y, z));
    }

    /** 이 칸을 비우는 비용 (이미 비었으면 0, 못 비우면 INF) */
    private double clear(int x, int y, int z) {
        if (passable(x, y, z)) return 0;
        if (!canBreak || avoid.contains(BlockPos.asLong(x, y, z))) return BlockUtil.INF;
        BlockState s = st(x, y, z);
        if (!BlockUtil.breakable(level, x, y, z, s)) return BlockUtil.INF;
        if (BlockUtil.fluidNear(level, x, y, z, true)) return BlockUtil.INF;
        double t = BlockUtil.breakTicks(player, level, new BlockPos(x, y, z), s, false, breakCache);
        if (t >= BlockUtil.INF) return t;
        double extra = 3;
        if (BlockUtil.fluidNear(level, x, y, z, false)) extra += 25;
        if (BlockUtil.falls(st(x, y + 1, z))) extra += 15;
        return t + extra;
    }

    /** 이 위치에 서 있는 게 위험한 정도 */
    private double danger(int x, int y, int z) {
        double d = 0;
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : side) {
            if (BlockUtil.isLava(st(x + o[0], y, z + o[1])) || BlockUtil.isLava(st(x + o[0], y - 1, z + o[1]))) d += 40;
        }
        BlockState below = st(x, y - 1, z);
        if (BlockUtil.isWater(below) || BlockUtil.isWater(st(x, y, z))) d += 6;
        // 낭떠러지 가장자리는 피함 (맞고 밀려 떨어지지 않게 다리 가운데로)
        for (int[] o : side) {
            int nx = x + o[0], nz = z + o[1];
            if (!floor(nx, y - 1, nz) && passable(nx, y - 1, nz) && passable(nx, y - 2, nz) && passable(nx, y - 3, nz) && !floor(nx, y - 4, nz)) d += 5;
        }
        return d;
    }

    private void add(Node from, int x, int y, int z, double cost, Move m, long[] br, long place) {
        if (cost >= BlockUtil.INF || y < level.getMinBuildHeight() || y > level.getMaxBuildHeight()) return;
        if (!badEdges.isEmpty() && badEdges.contains(edge(from.x, from.y, from.z, x, y, z))) return;
        double g = from.g + cost + danger(x, y, z);
        long k = BlockPos.asLong(x, y, z);
        Node n = nodes.get(k);
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.h(x, y, z);
            nodes.put(k, n);
        } else if (n.closed || g >= n.g) return;
        n.g = g;
        n.f = g + n.h * 1.8;
        n.parent = from;
        n.move = m;
        n.breaks = br;
        n.place = place;
        open.add(n);
    }

    private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private int placesUsed(Node n) {
        int c = 0;
        for (Node p = n; p != null; p = p.parent) if (p.place != Long.MIN_VALUE) c++;
        return c;
    }

    /** 이 경로에서 앞서 놓기로 한 블록인지 (공중에 다리를 계속 이어 놓기 위해) */
    private static boolean placedBy(Node n, int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        for (Node p = n; p != null; p = p.parent) if (p.place == k) return true;
        return false;
    }

    private void expand(Node n) {
        int x = n.x, y = n.y, z = n.z;
        boolean standing = floor(x, y - 1, z) || placedBy(n, x, y - 1, z);
        boolean onWater = BlockUtil.isWater(st(x, y - 1, z)) || BlockUtil.isWater(st(x, y, z));
        boolean canPlace = placeBlocks > 0 && placesUsed(n) < placeBlocks;

        for (int[] d : CARD) {
            int nx = x + d[0], nz = z + d[1];
            // 같은 높이로 가기 / 떨어지기 / 다리 놓기
            double c1 = clear(nx, y, nz), c2 = clear(nx, y + 1, nz);
            if (c1 < BlockUtil.INF && c2 < BlockUtil.INF) {
                long[] br = breaks(nx, y + 1, nz, c2, nx, y, nz, c1);
                if (floor(nx, y - 1, nz)) {
                    add(n, nx, y, nz, WALK * 0.85 + c1 + c2, Move.WALK, br, Long.MIN_VALUE);
                } else if (c1 == 0 && c2 == 0 && passable(nx, y - 1, nz)) {
                    boolean landed = false;
                    for (int k = 1; k <= MAX_FALL + 20; k++) {
                        int fy = y - 1 - k;
                        BlockState s = st(nx, fy, nz);
                        if (s == null) break;
                        if (BlockUtil.isWater(s)) { add(n, nx, fy + 1, nz, WALK + FALL * k, Move.DESCEND, NONE, Long.MIN_VALUE); landed = true; break; }
                        if (floor(nx, fy, nz)) {
                            if (k <= MAX_FALL) add(n, nx, fy + 1, nz, WALK + FALL * k + (k == MAX_FALL ? 4 : 0), Move.DESCEND, NONE, Long.MIN_VALUE);
                            landed = true;
                            break;
                        }
                        if (!passable(nx, fy, nz)) break;
                    }
                    if (canPlace && !onWater && standing) {
                        add(n, nx, y, nz, WALK * 2 + PLACE + (landed ? 4 : 0), Move.BRIDGE, NONE, BlockPos.asLong(nx, y - 1, nz));
                    }
                } else if (canPlace && !onWater && c1 == 0 && c2 == 0 && standing && BlockUtil.isWater(st(nx, y - 1, nz))) {
                    add(n, nx, y, nz, WALK * 2 + PLACE, Move.BRIDGE, NONE, BlockPos.asLong(nx, y - 1, nz));
                }
            }
            // 한 칸 오르기
            if (floor(nx, y, nz) && !BlockUtil.isWater(st(nx, y, nz))) {
                double a = clear(x, y + 2, z), b1 = clear(nx, y + 1, nz), b2 = clear(nx, y + 2, nz);
                if (a < BlockUtil.INF && b1 < BlockUtil.INF && b2 < BlockUtil.INF) {
                    long[] br = breaks3(x, y + 2, z, a, nx, y + 2, nz, b2, nx, y + 1, nz, b1);
                    add(n, nx, y + 1, nz, WALK + JUMP + a + b1 + b2, Move.ASCEND, br, Long.MIN_VALUE);
                }
            }
        }
        // 대각선 (캐지 않고 지나갈 수 있을 때만)
        for (int[] d : DIAGS) {
            int nx = x + d[0], nz = z + d[1];
            if (passable(nx, y, z) && passable(nx, y + 1, z) && passable(x, y, nz) && passable(x, y + 1, nz)
                    && passable(nx, y, nz) && passable(nx, y + 1, nz) && floor(nx, y - 1, nz)
                    && !BlockUtil.hurts(st(nx, y - 1, z)) && !BlockUtil.hurts(st(x, y - 1, nz))) {
                add(n, nx, y, nz, WALK * 0.85 * DIAG, Move.DIAG, NONE, Long.MIN_VALUE);
            }
        }
        // 기둥 쌓기 (점프하며 발밑에 블록)
        if (canPlace && standing && !onWater) {
            double a = clear(x, y + 2, z);
            if (a < BlockUtil.INF) {
                add(n, x, y + 1, z, JUMP + PLACE + 8 + a, Move.PILLAR, a > 0 ? new long[]{BlockPos.asLong(x, y + 2, z)} : NONE, BlockPos.asLong(x, y, z));
            }
        }
        // 아래로 파기
        if (canBreak && !onWater) {
            BlockState under = st(x, y - 1, z);
            if (BlockUtil.breakable(level, x, y - 1, z, under) && !BlockUtil.fluidNear(level, x, y - 1, z, false)) {
                BlockState under2 = st(x, y - 2, z);
                if (floor(x, y - 2, z) && !BlockUtil.isLava(under2) && !BlockUtil.isWater(under2) && !avoid.contains(BlockPos.asLong(x, y - 1, z))) {
                    double t = BlockUtil.breakTicks(player, level, new BlockPos(x, y - 1, z), under, false, breakCache);
                    if (t < BlockUtil.INF) add(n, x, y - 1, z, t + 6, Move.DIG_DOWN, new long[]{BlockPos.asLong(x, y - 1, z)}, Long.MIN_VALUE);
                }
            }
        }
    }

    private static long[] breaks(int ax, int ay, int az, double ca, int bx, int by, int bz, double cb) {
        if (ca == 0 && cb == 0) return NONE;
        if (ca > 0 && cb > 0) return new long[]{BlockPos.asLong(ax, ay, az), BlockPos.asLong(bx, by, bz)};
        return ca > 0 ? new long[]{BlockPos.asLong(ax, ay, az)} : new long[]{BlockPos.asLong(bx, by, bz)};
    }

    private static long[] breaks3(int ax, int ay, int az, double ca, int bx, int by, int bz, double cb, int cx, int cy, int cz, double cc) {
        List<Long> l = new ArrayList<>(3);
        if (ca > 0) l.add(BlockPos.asLong(ax, ay, az));
        if (cb > 0) l.add(BlockPos.asLong(bx, by, bz));
        if (cc > 0) l.add(BlockPos.asLong(cx, cy, cz));
        long[] r = new long[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }
}
