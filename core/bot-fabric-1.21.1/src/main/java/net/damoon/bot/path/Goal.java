package net.damoon.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 길찾기 목표. h()는 남은 비용 추정(틱 단위). */
public interface Goal {
    boolean isIn(int x, int y, int z);

    double h(int x, int y, int z);

    default boolean allowPortal() { return false; }

    static double horiz(int dx, int dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        int mx = Math.max(dx, dz), mn = Math.min(dx, dz);
        return (mx + mn * 0.4142) * PathFinder.SPRINT;
    }

    static double vert(int dy) {
        return dy > 0 ? dy * (PathFinder.JUMP + PathFinder.SPRINT) : -dy * PathFinder.FALL;
    }

    /** 정확히 그 칸에 서기 */
    record Block(int x, int y, int z, boolean portal) implements Goal {
        public Block(BlockPos p) { this(p.getX(), p.getY(), p.getZ(), false); }
        public boolean isIn(int a, int b, int c) { return a == x && b == y && c == z; }
        public double h(int a, int b, int c) { return horiz(x - a, z - c) + vert(y - b); }
        public boolean allowPortal() { return portal; }
    }

    /** 반경 r 안 아무 데나 */
    record Near(int x, int y, int z, double r) implements Goal {
        public Near(BlockPos p, double r) { this(p.getX(), p.getY(), p.getZ(), r); }
        public boolean isIn(int a, int b, int c) {
            double dx = a - x, dy = b - y, dz = c - z;
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
        public double h(int a, int b, int c) {
            double d = Math.sqrt((double) (a - x) * (a - x) + (double) (c - z) * (c - z));
            double k = Math.max(0, d - r) / Math.max(1, d);
            return horiz((int) ((x - a) * k), (int) ((z - c) * k)) + vert((int) ((y - b) * (Math.abs(y - b) > r ? 1 : 0)));
        }
    }

    /** x, z 좌표만 맞추기 (멀리 갈 때) */
    record XZ(int x, int z) implements Goal {
        public boolean isIn(int a, int b, int c) { return a == x && c == z; }
        public double h(int a, int b, int c) { return horiz(x - a, z - c); }
    }

    /** 수평 반경 r 안 (높이 무관) */
    record NearXZ(int x, int z, double r) implements Goal {
        public boolean isIn(int a, int b, int c) {
            double dx = a - x, dz = c - z;
            return dx * dx + dz * dz <= r * r;
        }
        public double h(int a, int b, int c) {
            double d = Math.sqrt((double) (a - x) * (a - x) + (double) (c - z) * (c - z));
            return Math.max(0, d - r) * PathFinder.SPRINT;
        }
    }

    /** 이 블록(또는 상자)을 손이 닿는 거리에서 다룰 수 있는 곳 */
    record Reach(AABB box, double reach) implements Goal {
        public Reach(BlockPos p, double reach) { this(new AABB(p), reach); }
        public boolean isIn(int a, int b, int c) {
            AABB me = new AABB(a + 0.2, b, c + 0.2, a + 0.8, b + 1.8, c + 0.8);
            if (me.intersects(box)) return false;
            Vec3 eye = new Vec3(a + 0.5, b + 1.62, c + 0.5);
            double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
            double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
            double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }
        public double h(int a, int b, int c) {
            Vec3 ctr = box.getCenter();
            int dx = (int) Math.floor(ctr.x) - a, dz = (int) Math.floor(ctr.z) - c;
            int dy = (int) Math.floor(ctr.y) - 1 - b;
            double d = Math.sqrt((double) dx * dx + dz * dz);
            double k = Math.max(0, d - reach + 0.5) / Math.max(1, d);
            int ady = Math.abs(dy) > 2 ? dy - Integer.signum(dy) * 2 : 0;
            return horiz((int) (dx * k), (int) (dz * k)) + vert(ady);
        }
    }

    /** 이 높이 이하로 내려가기 (동굴을 따라 내려가기) */
    record Below(int y) implements Goal {
        public boolean isIn(int a, int b, int c) { return b <= y; }
        public double h(int a, int b, int c) { return Math.max(0, b - y) * 3.0; }
    }

    /** 이 높이 이상으로 올라가기 (땅 위로 나가기) */
    record Above(int y) implements Goal {
        public boolean isIn(int a, int b, int c) { return b >= y; }
        public double h(int a, int b, int c) { return Math.max(0, y - b) * 8.0; }
    }

    /** 이 지점에서 멀어지기 (반경 r 밖이면 성공) */
    record Away(double x, double z, double r) implements Goal {
        public boolean isIn(int a, int b, int c) {
            double dx = a + 0.5 - x, dz = c + 0.5 - z;
            return dx * dx + dz * dz >= r * r;
        }
        public double h(int a, int b, int c) {
            double d = Math.sqrt((a + 0.5 - x) * (a + 0.5 - x) + (c + 0.5 - z) * (c + 0.5 - z));
            return Math.max(0, r - d) * PathFinder.SPRINT;
        }
    }
}
