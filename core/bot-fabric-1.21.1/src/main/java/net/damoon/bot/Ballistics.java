package net.damoon.bot;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 화살 탄도 계산: 중력 0.05, 공기 저항 0.99 를 그대로 시뮬레이션해서 맞는 각도를 찾음 */
public final class Ballistics {
    private Ballistics() {}

    public static final double FULL = 3.0;

    /** 수평 거리 d에 도달했을 때 높이 (도달 못 하면 NaN). deg는 위쪽이 +. */
    private static double gravity = 0.05;

    private static double addVh, addVy;

    /** 엔더 진주(중력 0.03, 속도 1.5)용. 던지는 사람의 속도(수평 진행 방향 성분, 수직)가 더해짐 */
    public static double[] solvePearl(Vec3 from, Vec3 to, double alongVel, double upVel) {
        gravity = 0.03;
        addVh = alongVel;
        addVy = upVel;
        try { return solve(from, to, 1.5); } finally { gravity = 0.05; addVh = 0; addVy = 0; }
    }

    private static double[] simulate(double deg, double d, double v) {
        double a = Math.toRadians(deg);
        double vh = v * Math.cos(a) + addVh, vy = v * Math.sin(a) + addVy;
        double h = 0, y = 0;
        for (int t = 1; t < 400; t++) {
            double nh = h + vh, ny = y + vy;
            if (nh >= d) {
                double k = (d - h) / (nh - h);
                return new double[]{y + (ny - y) * k, t - 1 + k};
            }
            h = nh;
            y = ny;
            vh *= 0.99;
            vy = vy * 0.99 - gravity;
            if (vh < 1e-3) break;
        }
        return null;
    }

    /** 마인크래프트 pitch(위가 -)와 비행 틱 수. 못 맞추면 null */
    public static double[] solve(Vec3 from, Vec3 to, double v) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double dy = to.y - from.y;
        if (d < 0.5) return new double[]{dy > 0 ? -89 : 89, 1};
        double prevDeg = Double.NaN, prevErr = Double.NaN;
        for (double deg = -70; deg <= 60; deg += 0.5) {
            double[] r = simulate(deg, d, v);
            double err = r == null ? -1e9 : r[0] - dy;
            if (!Double.isNaN(prevErr) && prevErr < 0 && err >= 0) {
                double lo = prevDeg, hi = deg;
                double[] best = r;
                for (int i = 0; i < 30; i++) {
                    double mid = (lo + hi) / 2;
                    double[] m = simulate(mid, d, v);
                    double e = m == null ? -1e9 : m[0] - dy;
                    if (e < 0) lo = mid; else { hi = mid; best = m; }
                }
                return new double[]{-hi, best == null ? d / v : best[1]};
            }
            prevDeg = deg;
            prevErr = err;
        }
        return null;
    }

    /** 이 지점에서 쏘면 화살이 블록에 안 막히고 target 근처까지 가는지 */
    public static boolean clearShot(Level l, Player p, Vec3 from, Vec3 target, double v) {
        double[] s = solve(from, target, v);
        if (s == null) return false;
        float[] ang = Ctl.angles(from, target);
        double yaw = Math.toRadians(ang[0]);
        double pitch = Math.toRadians(s[0]);
        double vx = -Math.sin(yaw) * Math.cos(pitch) * v, vy = -Math.sin(pitch) * v, vz = Math.cos(yaw) * Math.cos(pitch) * v;
        Vec3 pos = from;
        for (int t = 0; t < 200; t++) {
            Vec3 next = pos.add(vx, vy, vz);
            HitResult hr = l.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hr.getType() == HitResult.Type.BLOCK) return hr.getLocation().distanceTo(target) < 1.4;
            if (next.distanceTo(target) < 1.2 || pos.distanceTo(target) < 1.2) return true;
            pos = next;
            vx *= 0.99;
            vz *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return false;
    }
}
