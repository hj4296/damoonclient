package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.Bot;
import net.damoon.bot.BlockUtil;
import net.damoon.bot.Inv;
import net.damoon.bot.Placer;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔더맨 굴 (사람들이 쓰는 방법): 높이 2칸·길이 2칸짜리 굴을 짓고 안쪽 칸에 섬.
 * 엔더맨(키 2.9칸)은 굴에 못 들어오고 팔도 안 닿지만 나는 굴 입구 밖 엔더맨 다리를 칠 수 있음.
 * 멀리 있는 엔더맨의 눈을 쳐다봐서 화나게 하면 순간이동해 굴 앞으로 옴. 벽·지붕이 화살도 막음.
 */
public class EnderHutTask extends Task {
    private int noEnder;
    private BlockPos in, out;      // 안쪽 칸(내가 섬), 바깥 칸(입구)
    private Direction front;
    private int phase, ticks, lookTicks, buildTicks, peekTicks;
    private final java.util.Set<Integer> ignoreFar = new java.util.HashSet<>();
    private boolean peeking;
    private final int want;
    private String detail = "";

    public EnderHutTask(int want) { this.want = want; }

    @Override public String name() { return "엔더맨 굴"; }
    @Override public String detail() { return detail; }

    private static boolean air2(ClientLevel l, BlockPos b) {
        return l.getBlockState(b).getCollisionShape(l, b).isEmpty() && l.getBlockState(b.above()).getCollisionShape(l, b.above()).isEmpty()
                && l.getBlockState(b).getFluidState().isEmpty();
    }

    private static boolean solid(ClientLevel l, BlockPos b) {
        var s = l.getBlockState(b);
        return !s.getCollisionShape(l, b).isEmpty() && !BlockUtil.hurts(s);
    }

    /** 지을 블록 순서: 옆 기둥(받침) → 머리 위 → 3x3 지붕. 지붕 아래 옆 칸엔 엔더맨(키 2.9)이 설 수 없음 */
    private List<BlockPos> walls() {
        List<BlockPos> outL = new ArrayList<>();
        BlockPos sup = in.relative(front.getOpposite());
        outL.add(sup);
        outL.add(sup.above());
        outL.add(sup.above(2));
        outL.add(in.above(2));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos r = in.offset(dx, 2, dz);
            if (!outL.contains(r)) outL.add(r);
        }
        // 가장자리 지붕은 이미 놓인 지붕 옆에 붙도록: 받침에 가까운 것부터
        BlockPos s2 = sup.above(2);
        outL.subList(4, outL.size()).sort(java.util.Comparator.comparingDouble(q -> q.distSqr(s2)));
        return outL;
    }

    private int pearls(LocalPlayer p) { return Inv.count(p, Items.ENDER_PEARL) + Inv.count(p, Items.ENDER_EYE); }

    private net.damoon.bot.Climb stairs;

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        ticks++;
        if (pearls(p) >= want) return S.DONE;
        if (in == null) {
            BlockPos f = Placer.standCell(p);
            // 굴은 땅 위(하늘이 보이는 곳)에 지어야 멀리 있는 엔더맨이 보임 → 땅속이면 먼저 올라감
            if (l.dimension() == net.minecraft.world.level.Level.OVERWORLD && !l.canSeeSky(f.above())) {
                int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
                detail = "엔더맨 굴 지을 땅 위로 올라가는 중";
                // 깊으면 길찾기 대신 계단을 파서 곧장 올라감 (동굴 속 길찾기는 너무 오래 걸려 계속 처음부터 다시 함)
                if (stairs != null || f.getY() < top - 12) {
                    if (stairs == null) stairs = new net.damoon.bot.Climb();
                    if (stairs.tick(bot, top - 1)) { detail = "엔더맨 굴 지을 땅 위로 — " + stairs.info; ticks = 0; return S.RUNNING; }
                    stairs = null;
                    return S.RUNNING;
                }
                net.damoon.bot.Nav.R r = bot.nav.go(new net.damoon.bot.path.Goal.Above(top - 1));
                if (r == net.damoon.bot.Nav.R.FAILED) { bot.nav.stop(); return S.FAILED; }
                return ticks > 20 * 90 ? S.FAILED : S.RUNNING;
            }
            bot.nav.stop();
            // 입구는 가장 가까운 엔더맨 쪽을 먼저 (안에서 쳐다볼 수 있게)
            java.util.List<Direction> dirs = new java.util.ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
            EnderMan ne = null;
            double ned = 1e9;
            for (Entity e : l.entitiesForRendering()) if (e instanceof EnderMan em && em.distanceTo(p) < ned) { ned = em.distanceTo(p); ne = em; }
            if (ne != null) {
                final EnderMan target = ne;
                dirs.sort(java.util.Comparator.comparingDouble(d -> {
                    double ax = target.getX() - p.getX(), az = target.getZ() - p.getZ();
                    return -(ax * d.getStepX() + az * d.getStepZ());
                }));
            }
            for (Direction d : dirs) {
                BlockPos o = f.relative(d);
                // 입구 앞 눈높이가 6칸 넘게 트여 있어야 함
                boolean open = true;
                for (int k = 2; k <= 7 && open; k++) {
                    BlockPos q = f.relative(d, k).above();
                    if (!l.getBlockState(q).getCollisionShape(l, q).isEmpty()) open = false;
                }
                boolean roofOk = true;
                for (int dx = -1; dx <= 1 && roofOk; dx++) for (int dz = -1; dz <= 1; dz++)
                    if (!l.getBlockState(f.offset(dx, 2, dz)).canBeReplaced()) { roofOk = false; break; }
                if (open && roofOk && air2(l, f) && solid(l, f.below()) && l.getBlockState(f.relative(d.getOpposite())).canBeReplaced()) {
                    in = f; out = o; front = d;
                    break;
                }
            }
            if (in == null) return ticks > 40 ? S.FAILED : S.RUNNING;
            bot.nav.stop();
            bot.log("엔더맨 굴 짓기 (높이 2칸: 엔더맨은 못 들어오고 나는 다리를 칠 수 있음)");
        }
        // 0) 짓기
        if (phase == 0) {
            detail = "엔더맨 굴 짓는 중";
            bot.reflex.building = true;
            BlockPos ft = PathExec.feet(p);
            if (!ft.equals(in)) {
                Vec3 c = Vec3.atBottomCenterOf(in);
                bot.ctl.lookYaw(p, c, 30f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                if (++buildTicks > 200) { bot.reflex.building = false; return S.FAILED; }
                return S.RUNNING;
            }
            for (BlockPos w : walls()) {
                var s = l.getBlockState(w);
                if (!s.canBeReplaced() && !BlockUtil.vine(s)) continue;
                int r = Placer.placeAt(bot, w, Inv.wall(p));
                if (r == -1) continue;
                if (++buildTicks > 20 * 40) { bot.reflex.building = false; return S.FAILED; }
                return S.RUNNING;
            }
            bot.reflex.building = false;
            phase = 1;
            bot.log("엔더맨 굴 완성 → 엔더맨을 쳐다봐서 불러옴");
        }
        // 1) 굴 안에서: 화난 엔더맨이 입구 앞에 오면 다리를 벰, 아니면 멀리 있는 엔더맨의 눈을 쳐다봄
        BlockPos ft = PathExec.feet(p);
        boolean angryNear = false;
        for (Entity e : l.entitiesForRendering()) if (e instanceof EnderMan em && em.isCreepy() && em.distanceTo(p) < 24) angryNear = true;
        if (angryNear) peeking = false;
        if (!ft.equals(in) && !peeking) {
            // 진주 줍고 돌아오는 중이거나 밀려남, 또는 엔더맨을 화나게 했으니 안으로
            Vec3 c = Vec3.atBottomCenterOf(in);
            bot.ctl.lookYaw(p, c, 20f);
            bot.ctl.fwd = true;
            return S.RUNNING;
        }
        bot.ctl.sneak = true;
        bot.reflex.camp = true;
        bot.reflex.ignoreEndermenUntil = bot.ticks + 3;
        // 굴 입구 앞에 떨어진 진주 (입구 칸까지만 나가서 주움)
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof ItemEntity ie && ie.getItem().is(Items.ENDER_PEARL) && ie.distanceTo(p) < 4) {
                boolean angry = false;
                for (Entity o : l.entitiesForRendering()) if (o instanceof EnderMan em && em.isCreepy() && em.distanceTo(p) < 8) angry = true;
                if (!angry) {
                    detail = "입구 앞 진주 줍는 중";
                    bot.ctl.lookYaw(p, ie.position(), 20f);
                    bot.ctl.fwd = true;
                    bot.ctl.sneak = false;
                    return S.RUNNING;
                }
            }
        }
        EnderMan near = null, far = null;
        double nd = 1e9, fd = 1e9;
        for (Entity e : l.entitiesForRendering()) {
            if (!(e instanceof EnderMan em) || !em.isAlive()) continue;
            double d = em.distanceTo(p);
            if (em.isCreepy() && d < nd) { nd = d; near = em; }
            if (!em.isCreepy() && d < 40 && d < fd && p.hasLineOfSight(em) && !ignoreFar.contains(em.getId())) { fd = d; far = em; }
        }
        detail = "굴 안에서 엔더맨 기다리는 중 (진주 " + pearls(p) + "/" + want + ")";
        if (near != null) {
            Inv.selectWeapon(p);
            // 다리를 봄 (몸통 아래쪽)
            Vec3 legs = near.position().add(0, 0.5, 0);
            bot.ctl.lookAt(p, legs);
            double r = Act.reach(p, near.getBoundingBox());
            if (r <= 3.0 && bot.ctl.aimed(p, 20) && p.getAttackStrengthScale(0.5f) >= 0.95f) Act.attack(bot, near, 0.95f);
            detail = "굴 앞 화난 엔더맨 다리 치는 중 (진주 " + pearls(p) + "/" + want + ")";
            return S.RUNNING;
        }
        if (far != null) {
            // 눈을 쳐다봐서 화나게 함 (시선 피하기를 잠시 끔)
            bot.ctl.lookAt(p, far.getEyePosition());
            bot.ctl.allowEnderGaze = true;
            detail = "멀리 있는 엔더맨을 쳐다봐서 부르는 중 (진주 " + pearls(p) + "/" + want + ")";
            if (++lookTicks > 20 * 10) { lookTicks = 0; ignoreFar.add(far.getId()); }
            return S.RUNNING;
        }
        if (ignoreFar.size() > 30) ignoreFar.clear();
        // 안에서 엔더맨이 안 보이면: 입구 칸까지 나가서 둘러보고, 보이는 엔더맨을 쳐다봐 화나게 한 뒤 다시 들어옴
        EnderMan any = null;
        double ad = 1e9;
        for (Entity e : l.entitiesForRendering()) if (e instanceof EnderMan em && !em.isCreepy() && !ignoreFar.contains(em.getId()) && em.distanceTo(p) < 48 && em.distanceTo(p) < ad) { ad = em.distanceTo(p); any = em; }
        if (false && any != null) {
            peekTicks++;
            peeking = true;
            detail = "입구로 나가 엔더맨 쳐다보기 (진주 " + pearls(p) + "/" + want + ")";
            bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(out), 0f);
            bot.ctl.fwd = true;
            bot.ctl.sneak = true;
            if (PathExec.feet(p).equals(out) || p.distanceToSqr(Vec3.atBottomCenterOf(out)) < 0.5) {
                bot.ctl.fwd = false;
                bot.ctl.lookAt(p, any.getEyePosition());
                bot.ctl.allowEnderGaze = true;
            }
            if (peekTicks > 20 * 20) { peekTicks = 0; peeking = false; ignoreFar.add(any.getId()); }
            return S.RUNNING;
        }
        peeking = false;
        // 엔더맨이 1분 넘게 하나도 안 보이면 이 자리는 그만 (다른 곳을 찾아감)
        if (any == null) { if (++noEnder > 20 * 60) return S.FAILED; } else noEnder = 0;
        // 엔더맨이 아예 없으면: 아침이 됐거나 근처에 없음
        if (l.dimension() == net.minecraft.world.level.Level.OVERWORLD && !BedTask.night(l) && ticks > 20 * 30) return S.FAILED;
        bot.ctl.look(front.toYRot(), 0f);
        return ticks > 20 * 60 * 6 ? S.FAILED : S.RUNNING;
    }
}
