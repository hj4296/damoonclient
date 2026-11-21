package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.Archer;
import net.damoon.bot.Ballistics;
import net.damoon.bot.BlockUtil;
import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Miner;
import net.minecraft.world.item.Items;
import net.damoon.bot.Nav;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엔드 포털로 들어가서 엔더 드래곤을 잡고 출구 포털로 나가 엔딩까지.
 * 1) 기둥 위 크리스탈을 활로 저격 (철창 안은 옆에 블록 기둥을 쌓아 철창을 깨고 쏨)
 * 2) 드래곤이 포털에 내려앉으면 머리를 점프 치명타로 공격, 날 때는 예측 사격
 * 3) 드래곤 브레스(보라색 구름)는 피함
 * 4) 죽으면 출구 포털로 들어감
 */
public class EndGameTask extends Task {
    private String detail = "";
    private final Archer archer = new Archer();

    // 크리스탈 저격
    private int crystalId = -1;
    private BlockPos spot;
    private int spotShots, crystalShots, waitShot;
    private final Set<Long> badSpots = new HashSet<>();
    private final Set<Integer> needTower = new HashSet<>();
    private final Map<Integer, Integer> skipUntil = new HashMap<>();
    private final Map<Integer, Integer> shotsAt = new HashMap<>();

    // 철창 크리스탈: 블록 기둥
    private enum TowerSt { BASE, CLIMB, OPEN, RETREAT, SHOOT, BACK, DOWN }
    private boolean towerCaged;
    private BlockPos retreatTo;
    private int retreatTicks;
    private TowerSt tst;
    private int towerCrystal = -1;
    private BlockPos towerBase;
    private int towerH, towerDx, towerDz, towerWait, towerShots;
    private final Set<Long> towerTried = new HashSet<>();

    private boolean wasInEnd;
    private boolean reachedCenter;
    private Miner gather;
    private int equipTick;

    @Override public String name() { return "엔더 드래곤 잡기"; }

    @Override public String detail() { return detail; }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        bot.reflex.holdEating = false;

        // 따라오는 작은 몹(좀벌레 등)은 먼저 다 잡음 — 포털 들어가기 전, 그리고 엔드 시작 발판 위에서 (밀려 떨어지지 않게)
        Entity pest = null;
        for (Entity e : l.entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.monster.Silverfish || e instanceof net.minecraft.world.entity.monster.Endermite)
                if (((LivingEntity) e).isAlive() && e.distanceTo(p) < 7 && Math.abs(e.getY() - p.getY()) < 3) { pest = e; break; }
        if (pest != null) {
            bot.nav.stop();
            bot.ctl.sneak = true;
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, pest.getBoundingBox().getCenter());
            if (Act.reach(p, pest.getBoundingBox()) <= 3.0 && bot.ctl.aimed(p, 30)) Act.attack(bot, pest, 0.85f);
            detail = "따라온 " + pest.getName().getString() + " 먼저 잡는 중 (웅크린 채)";
            if (l.dimension() == Level.END && !wasInEnd) { wasInEnd = true; bot.log("엔드 도착!"); }
            return S.RUNNING;
        }
        if (l.dimension() != Level.END) {
            if (wasInEnd) return S.DONE;
            return enterPortal(bot, p, l);
        }
        if (!wasInEnd) {
            wasInEnd = true;
            bot.nav.stop();
            bot.log("엔드 도착!");
        }
        if (++equipTick % 10 == 0 && Inv.equipArmor(p)) return S.RUNNING;

        EnderDragon dragon = null;
        List<EndCrystal> crystals = new ArrayList<>();
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof EnderDragon d && d.isAlive()) dragon = d;
            // 철창 안 크리스탈 2개는 그냥 둠: 옆에서 부수면 폭발에 죽고, 드래곤이 가운데 앉아 있을 땐 멀어서 회복도 못 함
            if (e instanceof EndCrystal c && c.isAlive() && c.getY() > 60 && Math.hypot(c.getX(), c.getZ()) > 20) crystals.add(c);
        }

        // 브레스 피하기
        AreaEffectCloud breath = breathAt(l, p);
        boolean hitting = dragon != null && dragon.getPhaseManager().getCurrentPhase().isSitting()
                && Act.reach(p, dragon.head.getBoundingBox()) < 3.2 && p.getHealth() > 14;
        if (breath != null && !hitting) {
            // 구름 중심 반대 방향으로 달려서 빠져나감 (길찾기 없이 바로)
            detail = "드래곤 브레스 피하는 중";
            archer.reset();
            bot.nav.stop();
            Vec3 away = new Vec3(p.getX() - breath.getX(), 0, p.getZ() - breath.getZ());
            if (away.lengthSqr() < 0.01) away = new Vec3(-p.getX(), 0, -p.getZ());
            // 섬 밖으로 나가지 않게 가운데 쪽으로 살짝 꺾음
            Vec3 toCenter = new Vec3(-p.getX(), 0, -p.getZ()).normalize().scale(0.4);
            Vec3 dir = away.normalize().add(toCenter).normalize();
            // 발밑이 있는 방향으로만 (다리 위·섬 가장자리에서 공허로 달려가지 않게): 원래 방향에서 가까운 안전한 방향을 고름
            Vec3 pick = null;
            double bestDot = -2;
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8;
                Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                boolean ok = true;
                for (double t = 1.0; t <= 3.0 && ok; t += 1.0)
                    if (!Bot.safeAheadToward(p, p.getX() + d.x * t, p.getZ() + d.z * t)) ok = false;
                if (ok && d.dot(dir) > bestDot) { bestDot = d.dot(dir); pick = d; }
            }
            bot.reflex.holdEating = true; // 구름 안에서 멈춰서 먹지 않기
            if (pick == null) { detail = "브레스 — 피할 땅이 없어 웅크리고 버팀"; bot.ctl.sneak = true; return S.RUNNING; }
            bot.ctl.lookYaw(p, p.position().add(pick.scale(5)), 5f);
            bot.ctl.fwd = true;
            bot.ctl.sprint = bestDot > 0;
            if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
            return S.RUNNING;
        }

        // 드래곤이 없고 출구 포털이 열렸으면 → 엔딩
        if (dragon == null || dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING) {
            BlockPos portal = exitPortal(l, p);
            if (portal != null && dragon == null) {
                detail = "출구 포털로 들어가는 중";
                bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
                return S.RUNNING;
            }
            detail = dragon != null ? "드래곤이 죽는 중…" : "드래곤을 찾는 중";
            if (dragon == null && crystals.isEmpty()) {
                bot.nav.go(new Goal.NearXZ(0, 0, 14));
                return S.RUNNING;
            }
            if (dragon != null) {
                bot.nav.go(new Goal.NearXZ(0, 0, 14));
                return S.RUNNING;
            }
        }

        if (!crystals.isEmpty()) {
            return crystals(bot, p, l, crystals);
        }
        // 크리스탈을 다 부순 뒤: 엔드 돌 대피소(드래곤이 못 부숨)에서 창문으로 머리를 쏘고, 화살이 없으면 앉았을 때만 나가서 벰
        S sh = hole(bot, p, l, dragon);
        if (sh != null) return sh;
        return fight(bot, p, l, dragon);
    }

    // ---------------- 엔드 포털 들어가기 ----------------
    private S enterPortal(Bot bot, LocalPlayer p, ClientLevel l) {
        BlockPos portal = findBlock(l, p, Blocks.END_PORTAL, 24);
        if (portal == null) {
            detail = "근처에 엔드 포털이 없음";
            return S.RUNNING;
        }
        detail = "엔드 포털로 들어가는 중";
        Nav.R r = bot.nav.go(new Goal.Block(portal.getX(), portal.getY(), portal.getZ(), true));
        if (r == Nav.R.FAILED) {
            bot.nav.stop();
            // 길찾기가 안 되면 그냥 걸어 들어감
            bot.ctl.lookYaw(p, Vec3.atCenterOf(portal), 30f);
            bot.ctl.fwd = true;
        }
        return S.RUNNING;
    }

    // ---------------- 크리스탈 ----------------
    private S crystals(Bot bot, LocalPlayer p, ClientLevel l, List<EndCrystal> crystals) {
        if (towerCrystal != -1) {
            Entity e = l.getEntity(towerCrystal);
            if (e instanceof EndCrystal c && c.isAlive()) return tower(bot, p, l, c);
            // 크리스탈이 없어졌으면: 다리 위에 있으면 기둥으로 돌아간 뒤 내려옴
            if (towerCaged && (tst == TowerSt.SHOOT || tst == TowerSt.RETREAT || tst == TowerSt.BACK)) {
                detail = "크리스탈 파괴! 기둥으로 돌아가는 중";
                BlockPos top = new BlockPos(towerBase.getX(), towerH + 1, towerBase.getZ());
                Nav.R nr = bot.nav.go(new Goal.Block(top.getX(), top.getY(), top.getZ(), false));
                tst = TowerSt.BACK;
                if (nr != Nav.R.RUNNING || ++retreatTicks > 20 * 40) { bot.nav.stop(); tst = TowerSt.DOWN; }
                return S.RUNNING;
            }
            if (tst != null && tst != TowerSt.BASE && PathExec.feet(p).getY() > towerBase.getY() + 1) {
                tst = TowerSt.DOWN;
                return towerDown(bot, p, l);
            }
            towerCrystal = -1;
            tst = null;
        }

        // 먼저 섬 가운데 쪽으로 (도착 지점은 섬 가장자리 안쪽 굴 속일 수 있음)
        double cd = Math.hypot(p.getX(), p.getZ());
        if (cd < 40) reachedCenter = true;
        if ((!reachedCenter && cd > 40 || cd > 85) && towerCrystal == -1) {
            detail = "섬 가운데로 이동 (크리스탈 " + crystals.size() + "개)";
            archer.reset();
            Nav.R r = bot.nav.go(new Goal.NearXZ(0, 0, 30));
            if (r == Nav.R.FAILED) bot.nav.stop();
            return S.RUNNING;
        }

        EndCrystal target = null;
        double bd = 1e9;
        for (EndCrystal c : crystals) {
            if (needTower.contains(c.getId()) || caged(l, c)) continue;
            double d = c.distanceTo(p);
            if (d < bd) { bd = d; target = c; }
        }
        if (target == null) {
            // 남은 건 철창 안 크리스탈
            EndCrystal c = null;
            for (EndCrystal x : crystals) {
                if (bot.ticks < skipUntil.getOrDefault(x.getId(), 0)) continue;
                double d = x.distanceTo(p);
                if (c == null || d < c.distanceTo(p)) c = x;
            }
            if (c == null) {
                detail = "다음 방법을 생각하는 중";
                return S.RUNNING;
            }
            // 기둥 쌓을 블록이 모자라면 엔드 돌을 캐서 모음
            int need = (int) Math.floor(c.getY()) - (int) Math.floor(p.getY()) + 12;
            if (gather != null || Inv.count(p, Inv.throwaway()) < need) {
                if (gather == null) {
                    gather = new Miner(s -> s.is(Blocks.END_STONE), Inv.is(Items.END_STONE), Inv.count(p, Items.END_STONE) + need + 16, false);
                    gather.minY = (int) Math.floor(p.getY()) - 1;
                    bot.log("블록이 모자람 → 엔드 돌 캐서 모으기");
                }
                Miner.R mr = gather.tick(bot);
                detail = "기둥용 블록 모으는 중: " + gather.info;
                if (mr != Miner.R.RUNNING) gather = null;
                return S.RUNNING;
            }
            startTower(bot, l, c);
            if (towerCrystal == -1) skipUntil.put(c.getId(), bot.ticks + 200);
            return S.RUNNING;
        }
        if (target.getId() != crystalId) {
            crystalId = target.getId();
            spot = null;
            spotShots = 0;
            crystalShots = shotsAt.getOrDefault(crystalId, 0);
            archer.reset();
        }
        Vec3 ctr = target.position().add(0, 1.0, 0);
        int left = crystals.size();
        if (waitShot > 0) {
            waitShot--;
            detail = "화살 날아가는 중… (남은 크리스탈 " + left + ")";
            bot.ctl.lookAt(p, ctr);
            return S.RUNNING;
        }
        Vec3 from = p.getEyePosition().subtract(0, 0.1, 0);
        boolean lowOk = p.onGround() && from.distanceTo(ctr) < 48 && Ballistics.clearShot(l, p, from, ctr, Ballistics.FULL);
        boolean highOk = !lowOk && p.onGround() && from.distanceTo(ctr) < 60 && Ballistics.clearShot(l, p, from, ctr, Ballistics.FULL, true);
        boolean here = lowOk || highOk;
        if (here) {
            archer.high = highOk;
            bot.nav.stop();
            detail = "크리스탈 조준 (남은 " + left + ", " + (crystalShots + 1) + "번째 화살)";
            if (archer.tick(bot, ctr, null)) {
                spotShots++;
                crystalShots++;
                shotsAt.put(crystalId, crystalShots);
                double[] s = highOk ? Ballistics.solveHigh(from, ctr, Ballistics.FULL) : Ballistics.solve(from, ctr, Ballistics.FULL);
                waitShot = (int) (s == null ? 30 : s[1]) + 12;
                bot.log("크리스탈에 화살 발사 (" + crystalShots + ")");
                if (spotShots >= 3) {
                    badSpots.add(PathExec.feet(p).asLong());
                    spot = null;
                    spotShots = 0;
                }
                if (crystalShots >= 9) {
                    needTower.add(crystalId);
                    bot.log("이 크리스탈은 못 맞힘 → 기둥 쌓아서 접근");
                }
            }
            return S.RUNNING;
        }
        archer.reset();
        if (spot == null || badSpots.contains(spot.asLong())) {
            spot = findSpot(l, p, ctr);
            if (spot == null) {
                double hd = Math.hypot(ctr.x - p.getX(), ctr.z - p.getZ());
                if (hd > 26) {
                    detail = "크리스탈 쪽으로 다가가는 중 (남은 " + left + ")";
                    Nav.R r = bot.nav.go(new Goal.NearXZ((int) Math.floor(ctr.x), (int) Math.floor(ctr.z), 18));
                    if (r == Nav.R.FAILED) { bot.nav.stop(); needTower.add(crystalId); }
                    return S.RUNNING;
                }
                needTower.add(crystalId);
                bot.log("쏠 자리가 없음 → 기둥 쌓아서 접근");
                return S.RUNNING;
            }
            spotShots = 0;
        }
        detail = "크리스탈 쏠 자리로 이동 (남은 " + left + ")";
        Nav.R r = bot.nav.go(new Goal.Block(spot.getX(), spot.getY(), spot.getZ(), false));
        if (r == Nav.R.FAILED) {
            badSpots.add(spot.asLong());
            spot = null;
            bot.nav.stop();
        } else if (r == Nav.R.ARRIVED) {
            // 도착했는데 못 쏘면 다른 자리
            Vec3 e2 = p.getEyePosition().subtract(0, 0.1, 0);
            if (!Ballistics.clearShot(l, p, e2, ctr, Ballistics.FULL) && !Ballistics.clearShot(l, p, e2, ctr, Ballistics.FULL, true)) {
                badSpots.add(spot.asLong());
                spot = null;
            }
        }
        return S.RUNNING;
    }

    private BlockPos findSpot(ClientLevel l, LocalPlayer p, Vec3 ctr) {
        BlockPos best = null;
        double bs = 1e9;
        for (int r : new int[]{12, 16, 20, 24, 28, 9, 34, 40}) {
            for (int a = 0; a < 32; a++) {
                double ang = a * Math.PI * 2 / 32;
                int x = (int) Math.floor(ctr.x + Math.cos(ang) * r), z = (int) Math.floor(ctr.z + Math.sin(ang) * r);
                if (!l.hasChunk(x >> 4, z >> 4)) continue;
                int y = groundY(l, x, z);
                if (y < 20 || Math.abs(y - p.getY()) > 14) continue;
                if (!l.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.END_STONE)) continue;
                if (badSpots.contains(BlockPos.asLong(x, y, z))) continue;
                if (!BlockUtil.floor(l, x, y - 1, z, l.getBlockState(new BlockPos(x, y - 1, z)))) continue;
                if (BlockUtil.isWater(l.getBlockState(new BlockPos(x, y - 1, z)))) continue;
                Vec3 eye = new Vec3(x + 0.5, y + 1.62 - 0.1, z + 0.5);
                if (!Ballistics.clearShot(l, p, eye, ctr, Ballistics.FULL) && !Ballistics.clearShot(l, p, eye, ctr, Ballistics.FULL, true)) continue;
                double score = Math.sqrt(p.distanceToSqr(x + 0.5, y, z + 0.5)) + Math.abs(r - 16) * 0.7;
                if (score < bs) { bs = score; best = new BlockPos(x, y, z); }
            }
        }
        return best;
    }

    private static boolean caged(ClientLevel l, EndCrystal c) {
        BlockPos b = c.blockPosition();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 2; dy++)
                    if (l.getBlockState(b.offset(dx, dy, dz)).is(Blocks.IRON_BARS)) return true;
        return false;
    }

    private static int groundY(ClientLevel l, int x, int z) {
        // 클라이언트에는 MOTION_BLOCKING / WORLD_SURFACE 높이맵만 있음
        return l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    // ---------------- 철창 크리스탈: 옆에 기둥 쌓기 ----------------
    private void startTower(Bot bot, ClientLevel l, EndCrystal c) {
        int cx = (int) Math.floor(c.getX()), cz = (int) Math.floor(c.getZ());
        towerH = (int) Math.floor(c.getY()) - 1;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        LocalPlayer p = bot.p();
        BlockPos best = null;
        double bd = 1e9;
        // 크리스탈 폭발(9칸 안은 철 갑옷으로도 위험)을 피해 10칸쯤 떨어진 곳에 기둥
        boolean cage = caged(l, c);
        towerCaged = cage;
        // 철창: 바로 옆(5~6칸)에 쌓아 철창을 뜯은 뒤 다리로 8칸 물러나서 쏨 / 철창 없음: 10칸 떨어져 쌓고 바로 쏨
        for (int dist : cage ? new int[]{6, 5} : new int[]{10, 9, 11, 8}) {
            for (int[] d : dirs) {
                int x = cx + d[0] * dist, z = cz + d[1] * dist;
                int y = groundY(l, x, z);
                if (y < 30 || y > towerH) continue;
                if (towerTried.contains((long) BlockPos.asLong(x, y, z) ^ c.getId())) continue;
                double s = p.distanceToSqr(x, y, z);
                if (s < bd) { bd = s; best = new BlockPos(x, y, z); towerDx = d[0]; towerDz = d[1]; }
            }
            if (best != null) break;
        }
        if (best == null) {
            bot.log("기둥 쌓을 자리가 없음");
            needTower.remove(c.getId());
            return;
        }
        towerBase = best;
        towerCrystal = c.getId();
        tst = TowerSt.BASE;
        towerShots = 0;
        bot.log("철창 크리스탈: 옆에 기둥을 쌓아 올라감 (높이 " + towerH + ")");
    }

    private S tower(Bot bot, LocalPlayer p, ClientLevel l, EndCrystal c) {
        BlockPos f = PathExec.feet(p);
        int cx = (int) Math.floor(c.getX()), cz = (int) Math.floor(c.getZ());
        switch (tst) {
            case BASE -> {
                detail = "기둥 쌓을 자리로 이동";
                Nav.R r = bot.nav.go(new Goal.Block(towerBase.getX(), towerBase.getY(), towerBase.getZ(), false));
                if (r == Nav.R.ARRIVED) { tst = TowerSt.CLIMB; bot.nav.stop(); }
                if (r == Nav.R.FAILED) {
                    towerTried.add((long) towerBase.asLong() ^ c.getId());
                    towerCrystal = -1;
                    bot.nav.stop();
                }
            }
            case CLIMB -> {
                detail = "블록 기둥 쌓는 중 (" + f.getY() + "/" + (towerH + 1) + ")";
                if (f.getY() >= towerH + 1 && p.onGround()) { tst = TowerSt.OPEN; return S.RUNNING; }
                if (Inv.count(p, Inv.throwaway()) == 0) { bot.log("블록이 모자람"); towerCrystal = -1; return S.RUNNING; }
                BlockPos above = f.above(2);
                if (!BlockUtil.passable(l, above.getX(), above.getY(), above.getZ(), l.getBlockState(above))) {
                    Act.dig(bot, above);
                    return S.RUNNING;
                }
                Act.stopDig(bot);
                double dx = f.getX() + 0.5 - p.getX(), dz = f.getZ() + 0.5 - p.getZ();
                if (p.onGround() && dx * dx + dz * dz > 0.0064) {
                    bot.ctl.lookYaw(p, new Vec3(f.getX() + 0.5, p.getY(), f.getZ() + 0.5), 60f);
                    bot.ctl.fwd = true;
                    bot.ctl.sneak = true;
                    return S.RUNNING;
                }
                bot.ctl.look(p.getYRot(), 90f);
                bot.ctl.sneak = true;
                if (p.getXRot() < 70) return S.RUNNING;
                if (p.onGround()) bot.ctl.jump = true;
                int baseY = (int) Math.floor(p.getY() - 0.2);
                BlockPos at = new BlockPos(f.getX(), baseY, f.getZ());
                if (!p.onGround() && p.getY() > at.getY() + 1.0 && l.getBlockState(at).isAir()
                        && !l.getBlockState(at.below()).isAir()) {
                    Act.place(bot, at.below(), Direction.UP);
                }
            }
            case OPEN -> {
                BlockPos b1 = new BlockPos(cx + 2 * towerDx, towerH + 1, cz + 2 * towerDz);
                BlockPos b2 = b1.above();
                for (BlockPos b : new BlockPos[]{b2, b1}) {
                    if (l.getBlockState(b).is(Blocks.IRON_BARS)) {
                        detail = "철창 부수는 중";
                        if (!Act.dig(bot, b)) { bot.log("철창에 손이 안 닿음"); tst = TowerSt.SHOOT; }
                        return S.RUNNING;
                    }
                }
                Act.stopDig(bot);
                towerWait = 0;
                if (towerCaged) {
                    // 크리스탈 폭발(9칸 안 위험)을 피해 같은 높이로 다리를 놓아 물러남
                    retreatTo = new BlockPos(towerBase.getX() + towerDx * 8, towerH + 1, towerBase.getZ() + towerDz * 8);
                    retreatTicks = 0;
                    tst = TowerSt.RETREAT;
                    bot.log("철창을 뜯음 → 다리를 놓아 8칸 물러나서 쏨");
                } else tst = TowerSt.SHOOT;
            }
            case RETREAT -> {
                detail = "다리 놓으며 물러나는 중 (크리스탈 폭발 피하기)";
                Nav.R nr = bot.nav.go(new Goal.Block(retreatTo.getX(), retreatTo.getY(), retreatTo.getZ(), false));
                if (nr == Nav.R.ARRIVED || ++retreatTicks > 20 * 40) { bot.nav.stop(); tst = TowerSt.SHOOT; towerWait = 0; }
                if (nr == Nav.R.FAILED) { bot.nav.stop(); tst = TowerSt.SHOOT; }
            }
            case BACK -> {
                detail = "기둥으로 돌아가는 중";
                BlockPos top = new BlockPos(towerBase.getX(), towerH + 1, towerBase.getZ());
                Nav.R nr = bot.nav.go(new Goal.Block(top.getX(), top.getY(), top.getZ(), false));
                if (nr != Nav.R.RUNNING || ++retreatTicks > 20 * 40) { bot.nav.stop(); tst = TowerSt.DOWN; }
            }
            case SHOOT -> {
                detail = "철창 안 크리스탈 쏘는 중";
                Vec3 ctr = c.position().add(0, 1.0, 0);
                if (towerWait > 0) { towerWait--; bot.ctl.lookAt(p, ctr); return S.RUNNING; }
                archer.high = false;
                if (archer.tick(bot, ctr, null)) {
                    towerShots++;
                    towerWait = 25;
                    bot.log("철창 안 크리스탈에 발사 (" + towerShots + ")");
                    if (towerShots > 6) {
                        retreatTicks = 0;
                        tst = towerCaged ? TowerSt.BACK : TowerSt.DOWN;
                    }
                }
            }
            case DOWN -> { return towerDown(bot, p, l); }
        }
        return S.RUNNING;
    }

    private S towerDown(Bot bot, LocalPlayer p, ClientLevel l) {
        BlockPos f = PathExec.feet(p);
        int gy = groundY(l, towerBase.getX(), towerBase.getZ());
        detail = "기둥에서 내려가는 중";
        // 내가 쌓은 기둥이므로 땅 높이 = 기둥 꼭대기. 원래 땅 높이로 비교
        if (f.getY() <= towerBase.getY() || !p.onGround() && f.getY() <= towerBase.getY() + 1) {
            Act.stopDig(bot);
            towerCrystal = -1;
            tst = null;
            return S.RUNNING;
        }
        if (p.onGround()) {
            BlockPos below = f.below();
            if (!l.getBlockState(below).isAir()) Act.dig(bot, below);
        }
        return S.RUNNING;
    }

    // ---------------- 드래곤과 싸우기 ----------------
    private Vec3 lastDragonPos;

    // ---------------- 드래곤 구멍: 3칸 파고 들어가 덮음 (브레스·돌진·화염구 모두 안 닿음), 앉았을 때만 나가서 벰 ----------------
    private BlockPos hTop;      // 구멍 입구 칸 (땅 높이의 발 위치)
    private int hFails, hTicks;
    private boolean hOut;       // 지금 밖에 나가 싸우는 중

    private boolean healing;

    private S hole(Bot bot, LocalPlayer p, ClientLevel l, EnderDragon d) {
        if (d == null || hFails > 4) return null;
        // 평소엔 열린 곳에서 싸우고(fight), 체력이 10 아래면 덮은 구멍에 숨어 먹고 18까지 회복
        if (!healing && p.getHealth() < 10) { healing = true; bot.log("체력 " + (int) p.getHealth() + " → 구멍을 파고 숨어서 회복"); }
        if (healing && p.getHealth() >= 18 && p.getFoodData().getFoodLevel() >= 17) { healing = false; hOut = false; bot.log("회복 완료 → 다시 싸우러"); }
        if (!healing) {
            if (hOut || hTop == null) return null;
            // 구멍 안에 있으면 나옴
            BlockPos f0 = PathExec.feet(p);
            if (f0.getY() < hTop.getY() && Math.abs(f0.getX() - hTop.getX()) + Math.abs(f0.getZ() - hTop.getZ()) == 0) {
                detail = "구멍에서 나가는 중";
                BlockPos cover = hTop.below();
                if (!l.getBlockState(cover).isAir() && !l.getBlockState(cover).canBeReplaced() && hTop.getY() - f0.getY() >= 2) { Act.dig(bot, cover); return S.RUNNING; }
                Act.stopDig(bot);
                bot.ctl.look(p.getYRot(), 90f);
                bot.ctl.sneak = true;
                if (p.getXRot() > 70f) {
                    if (p.onGround()) bot.ctl.jump = true;
                    BlockPos at = BlockPos.containing(p.getX(), Math.floor(p.getY() - 0.01), p.getZ());
                    if (!p.onGround() && l.getBlockState(at).isAir() && !l.getBlockState(at.below()).isAir() && p.getY() > at.getY() + 1.05)
                        Act.place(bot, at.below(), Direction.UP);
                }
                return S.RUNNING;
            }
            hOut = true;
            return null;
        }
        hOut = false;
        DragonPhaseInstance ph = d.getPhaseManager().getCurrentPhase();
        boolean perched = ph.isSitting() || ph.getPhase() == EnderDragonPhase.LANDING;
        if (bot.ticks % 100 == 0) bot.dbg(String.format("드래곤 상태 %s 체력 %.0f 거리 %.0f 위치 %.0f,%.0f,%.0f", ph.getPhase(), d.getHealth(), d.distanceTo(p), d.getX(), d.getY(), d.getZ()));
        double cdist = Math.hypot(p.getX(), p.getZ());
        if (cdist > 28 && !hOut) {
            detail = "섬 가운데로 이동 (구멍 파러)";
            Nav.R r = bot.nav.go(new Goal.NearXZ(0, 0, 18));
            if (r == Nav.R.FAILED) bot.nav.stop();
            return S.RUNNING;
        }
        if (hTop == null) {
            BlockPos best = null;
            double bd = 1e9;
            for (int r : new int[]{9, 10, 8, 11}) {
                for (int a = 0; a < 24; a++) {
                    double ang = a * Math.PI * 2 / 24;
                    int x = (int) Math.floor(Math.cos(ang) * r), z = (int) Math.floor(Math.sin(ang) * r);
                    int y = groundY(l, x, z);
                    BlockPos c = new BlockPos(x, y, z);
                    boolean ok = l.getBlockState(c).isAir() && l.getBlockState(c.above()).isAir();
                    for (int k = 1; k <= 4 && ok; k++) if (!l.getBlockState(c.below(k)).is(Blocks.END_STONE)) ok = false;
                    for (Direction h : Direction.Plane.HORIZONTAL)
                        for (int k = 1; k <= 3 && ok; k++) if (!l.getBlockState(c.below(k).relative(h)).is(Blocks.END_STONE)) ok = false;
                    if (!ok) continue;
                    double dd = p.distanceToSqr(Vec3.atCenterOf(c));
                    if (dd < bd) { bd = dd; best = c; }
                }
                if (best != null) break;
            }
            if (best == null) { hFails = 9; bot.log("구멍 팔 자리를 못 찾음"); return null; }
            hTop = best;
            bot.log("숨을 구멍 자리: " + hTop.toShortString());
        }
        // 앉았고 체력이 넉넉하면 나가서 싸움 (fight가 머리 옆으로 감)
        if (false) {
            if (!hOut) {
                BlockPos f = PathExec.feet(p);
                int depth = hTop.getY() - f.getY();
                if (depth > 0 && Math.abs(f.getX() - hTop.getX()) + Math.abs(f.getZ() - hTop.getZ()) == 0) {
                    // 덮개 부수고 블록을 쌓아 올라감
                    detail = "드래곤이 앉음! 구멍에서 나가는 중";
                    Act.stopDig(bot);
                    bot.ctl.look(p.getYRot(), 90f);
                    bot.ctl.sneak = true;
                    if (p.getXRot() > 70f) {
                        if (p.onGround()) bot.ctl.jump = true;
                        BlockPos at = BlockPos.containing(p.getX(), Math.floor(p.getY() - 0.01), p.getZ());
                        if (!p.onGround() && l.getBlockState(at).isAir() && !l.getBlockState(at.below()).isAir() && p.getY() > at.getY() + 1.05)
                            Act.place(bot, at.below(), Direction.UP);
                    }
                    return S.RUNNING;
                }
                hOut = true;
            }
            bot.reflex.forceEat = false;
            return null; // fight()가 앉은 드래곤 머리 옆에서 공격
        }
        // 날고 있음 (또는 체력이 낮음): 구멍으로
        BlockPos f = PathExec.feet(p);
        boolean onTop = Math.abs(f.getX() - hTop.getX()) + Math.abs(f.getZ() - hTop.getZ()) == 0;
        if (!onTop && f.getY() >= hTop.getY() - 1) {
            hOut = false;
            detail = "구멍으로 돌아가는 중";
            Nav.R r = bot.nav.go(new Goal.Block(hTop.getX(), hTop.getY(), hTop.getZ(), false));
            if (r == Nav.R.FAILED) { bot.nav.stop(); if (++hFails > 4) return null; hTop = null; }
            return S.RUNNING;
        }
        bot.nav.stop();
        hOut = false;
        int depth = hTop.getY() - f.getY();
        if (depth < 3) {
            detail = "숨을 구멍 파는 중 (" + Math.max(0, depth) + "/3)";
            if (!p.onGround()) return S.RUNNING;
            double dx = hTop.getX() + 0.5 - p.getX(), dz = hTop.getZ() + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 0.03 && depth == 0) {
                bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(hTop), 60f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                return S.RUNNING;
            }
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 60f;
            Act.dig(bot, f.below());
            if (++hTicks > 20 * 30) { hTicks = 0; hFails++; hTop = null; }
            return S.RUNNING;
        }
        BlockPos cover = hTop.below();
        if (l.getBlockState(cover).canBeReplaced()) {
            detail = "구멍 위를 덮는 중";
            net.damoon.bot.Placer.placeAt(bot, cover, st -> st.is(Items.END_STONE) || Inv.throwaway().test(st));
            return S.RUNNING;
        }
        hTicks = 0;
        String hp = String.format("드래곤 %.0f/200", d.getHealth());
        bot.reflex.holdEating = false;
        bot.reflex.forceEat = p.getHealth() < 20;
        boolean arrows = Inv.has(p, Items.ARROW) && Inv.has(p, Items.BOW);
        Vec3 headC = d.head.getBoundingBox().getCenter();
        Vec3 eye = p.getEyePosition().subtract(0, 0.1, 0);
        if (false && arrows && !perched && eye.distanceTo(headC) < 45 && Ballistics.clearShot(l, p, eye, headC, Ballistics.FULL)) {
            archer.high = false;
            Vec3 vel = d.position().subtract(d.xo, d.yo, d.zo);
            if (archer.tick(bot, headC, vel)) bot.log("참호에서 드래곤 머리에 화살 (" + hp + ")");
            detail = hp + " — 참호에서 날아다니는 드래곤 머리 저격";
            return S.RUNNING;
        }
        archer.reset();
        detail = hp + " — 구멍 속에서 먹으며 회복 중 (체력 " + (int) p.getHealth() + ")";
        return S.RUNNING;
    }

    private BlockPos shCell;
    private Direction shFront;
    private int shPhase, shFails, shBuild;
    private Miner shGather;

    private List<BlockPos> shWalls() {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y <= 2; y++)
            for (Direction h : Direction.Plane.HORIZONTAL) {
                if (h == shFront && y == 1) continue; // 머리 높이 창문
                out.add(shCell.relative(h).above(y));
            }
        out.add(shCell.above(2));
        return out;
    }

    private static boolean dragonProof(net.minecraft.world.level.block.state.BlockState st) {
        return st.is(Blocks.END_STONE) || st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK) || st.is(Blocks.END_STONE_BRICKS);
    }

    /** null이면 대피소 전술을 안 씀 (그냥 fight) */
    private S shelter(Bot bot, LocalPlayer p, ClientLevel l, EnderDragon d) {
        if (d == null || shFails > 3) { if (bot.ticks % 100 == 0) bot.dbg("대피소 안 씀: d=" + (d != null) + " fails=" + shFails); return null; }
        boolean arrows = Inv.has(p, Items.ARROW) && Inv.has(p, Items.BOW);
        DragonPhaseInstance ph = d.getPhaseManager().getCurrentPhase();
        boolean perched = ph.isSitting() || ph.getPhase() == EnderDragonPhase.LANDING;
        // 앉은 드래곤은 화살이 안 통함 → 체력이 넉넉하면 나가서 머리 옆에서 칼로 (fight), 아니면 대피소에서 기다림
        if (perched && p.getHealth() >= 12) return null;
        // 섬에서 멀면 (시작 발판 등) 먼저 섬 가운데 쪽으로
        double cdist = Math.hypot(p.getX(), p.getZ());
        if (cdist > 28) {
            detail = "섬 가운데로 이동 (대피소 지으러)";
            Nav.R r = bot.nav.go(new Goal.NearXZ(0, 0, 18));
            if (r == Nav.R.FAILED) bot.nav.stop();
            return S.RUNNING;
        }
        if (shCell == null) {
            BlockPos best = null;
            double bd = 1e9;
            for (int r : new int[]{10, 11, 9, 12}) {
                for (int a = 0; a < 24; a++) {
                    double ang = a * Math.PI * 2 / 24;
                    int x = (int) Math.floor(Math.cos(ang) * r), z = (int) Math.floor(Math.sin(ang) * r);
                    int y = groundY(l, x, z);
                    BlockPos c = new BlockPos(x, y, z);
                    if (!l.getBlockState(c.below()).is(Blocks.END_STONE)) continue;
                    if (!l.getBlockState(c).isAir() || !l.getBlockState(c.above()).isAir() || !l.getBlockState(c.above(2)).isAir()) continue;
                    Direction f = Direction.getNearest(-x, 0, -z);
                    if (!l.getBlockState(c.relative(f).above()).isAir()) continue;
                    double dd = p.distanceToSqr(Vec3.atCenterOf(c));
                    if (dd < bd) { bd = dd; best = c; shFront = f; }
                }
                if (best != null) break;
            }
            if (best == null) { shFails = 9; bot.log("대피소 자리를 못 찾음"); return null; }
            shCell = best;
            shPhase = 0;
            bot.log("크리스탈 끝 → 포털 옆에 엔드 돌 대피소 (드래곤 돌진·화염을 피하고 창문으로 머리 저격)");
        }
        // 엔드 돌이 모자라면 캠
        int need = 0;
        for (BlockPos w : shWalls()) if (!dragonProof(l.getBlockState(w))) need++;
        if (shPhase == 0 && (shGather != null || Inv.count(p, Items.END_STONE) + Inv.count(p, Items.OBSIDIAN) < need)) {
            if (shGather == null) {
                shGather = new Miner(st -> st.is(Blocks.END_STONE), Inv.is(Items.END_STONE), Inv.count(p, Items.END_STONE) + need + 4, false);
                shGather.minY = shCell.getY() - 1;
            }
            Miner.R mr = shGather.tick(bot);
            detail = "대피소용 엔드 돌 모으는 중: " + shGather.info;
            if (mr != Miner.R.RUNNING) { shGather = null; if (mr == Miner.R.FAILED) shFails++; }
            return S.RUNNING;
        }
        BlockPos f = PathExec.feet(p);
        if (!f.equals(shCell)) {
            shPhase = 0; // 나갔다 오면 부서진 벽을 다시 막음
            shBuild = 0;
            detail = "대피소 자리로 이동";
            Nav.R r = bot.nav.go(new Goal.Block(shCell.getX(), shCell.getY(), shCell.getZ(), false));
            if (r == Nav.R.FAILED) { bot.nav.stop(); shFails++; shCell = null; }
            return S.RUNNING;
        }
        bot.nav.stop();
        bot.reflex.holdEating = false;
        if (shPhase == 0) {
            double dx = shCell.getX() + 0.5 - p.getX(), dz = shCell.getZ() + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 0.03 && ++shBuild < 60) {
                bot.ctl.lookYaw(p, Vec3.atBottomCenterOf(shCell), 60f);
                bot.ctl.fwd = true;
                bot.ctl.sneak = true;
                return S.RUNNING;
            }
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> proof = st -> st.is(Items.END_STONE) || st.is(Items.OBSIDIAN);
            for (BlockPos w : shWalls()) {
                if (dragonProof(l.getBlockState(w))) continue;
                if (!l.getBlockState(w).canBeReplaced()) { Act.dig(bot, w); return S.RUNNING; }
                int r = net.damoon.bot.Placer.placeAt(bot, w, proof);
                if (r == -1) continue;
                detail = "엔드 돌 대피소 짓는 중";
                if (++shBuild > 20 * 40) { shFails++; shCell = null; }
                return S.RUNNING;
            }
            shPhase = 1;
            bot.log("대피소 완성 → 창문으로 드래곤 머리 저격");
        }
        // 안에서: 앉은 드래곤 머리 (거의 가만히 있음) 또는 날아다니는 머리를 창문으로 쏨
        bot.ctl.sneak = true;
        Vec3 headC = d.head.getBoundingBox().getCenter();
        Vec3 eye = p.getEyePosition().subtract(0, 0.1, 0);
        String hp = String.format("드래곤 %.0f/200", d.getHealth());
        boolean see = !perched && eye.distanceTo(headC) < 50 && Ballistics.clearShot(l, p, eye, headC, Ballistics.FULL);
        if (arrows && see) {
            archer.high = false;
            Vec3 vel = d.position().subtract(d.xo, d.yo, d.zo);
            if (archer.tick(bot, headC, vel)) bot.log("대피소에서 드래곤 머리에 화살 (" + hp + ")");
            detail = hp + " — 대피소 창문으로 " + (perched ? "앉은" : "날아다니는") + " 드래곤 머리 저격";
            return S.RUNNING;
        }
        archer.reset();
        bot.ctl.look(shFront.toYRot(), -10f);
        detail = hp + " — 대피소 안에서 기다리는 중";
        return S.RUNNING;
    }

    private S fight(Bot bot, LocalPlayer p, ClientLevel l, EnderDragon d) {
        if (d == null) return S.RUNNING;
        DragonPhaseInstance ph = d.getPhaseManager().getCurrentPhase();
        boolean perched = ph.isSitting() || ph.getPhase() == EnderDragonPhase.LANDING;
        String hp = String.format("드래곤 %.0f/200", d.getHealth());
        Vec3 dpos = d.position();
        Vec3 vel = lastDragonPos == null ? Vec3.ZERO : dpos.subtract(lastDragonPos);
        lastDragonPos = dpos;

        if (perched) {
            AABB head = d.head.getBoundingBox();
            double r = Act.reach(p, head);
            bot.reflex.holdEating = p.getHealth() > 8;
            // 브레스는 머리 앞쪽으로 나옴 → 머리 옆(정면에서 비켜선 곳)에서 침
            Vec3 hc = head.getCenter(), bc = d.position();
            Vec3 fwdDir = new Vec3(hc.x - bc.x, 0, hc.z - bc.z);
            fwdDir = fwdDir.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : fwdDir.normalize();
            Vec3 toMe = new Vec3(p.getX() - hc.x, 0, p.getZ() - hc.z);
            boolean inFront = toMe.lengthSqr() > 1e-3 && toMe.normalize().dot(fwdDir) > 0.6;
            if (r > 2.9 || inFront) {
                detail = hp + " — 내려앉음! 머리 옆으로 감" + (inFront ? " (정면은 브레스)" : "");
                archer.reset();
                Vec3 side = new Vec3(-fwdDir.z, 0, fwdDir.x);
                Vec3 a = hc.add(side.scale(2.7)), b2 = hc.subtract(side.scale(2.7));
                Vec3 tgt = p.position().distanceToSqr(a) < p.position().distanceToSqr(b2) ? a : b2;
                BlockPos tp = BlockPos.containing(tgt.x, p.getY(), tgt.z);
                Nav.R nr = bot.nav.go(new Goal.Near(tp, 1.2));
                if (nr == Nav.R.FAILED) {
                    bot.nav.stop();
                    AABB q = new AABB(BlockPos.containing(head.getCenter())).inflate(0.5);
                    bot.nav.go(new Goal.Reach(q, 2.6));
                }
                return S.RUNNING;
            }
            bot.nav.stop();
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, head.getCenter());
            // 머리·목 둘레 1칸 안에 들어가면 드래곤이 10씩 때림 → 칼끝 거리(2.6~3.0)만 유지
            if (p.getHealth() < 10) {
                // 체력이 낮으면 머리에서 물러나 먹음
                detail = hp + " — 체력이 낮아 물러나서 먹는 중";
                bot.reflex.holdEating = false;
                if (r < 5.5 && Bot.safeAheadToward(p, p.getX() - (head.getCenter().x - p.getX()), p.getZ() - (head.getCenter().z - p.getZ()))) bot.ctl.back = true;
                return S.RUNNING;
            }
            detail = hp + " — 머리 공격!";
            float charge = p.getAttackStrengthScale(0.5f);
            if (r < 2.5 && net.damoon.bot.Reflex.safeBehindPublic(p)) bot.ctl.back = true;
            else if (r > 2.95 && p.onGround()) bot.ctl.fwd = true;
            // 점프 치명타 (앞으로 가며 뛰지 않게: 제자리 점프)
            if (p.onGround() && charge > 0.85f && r >= 2.5 && r <= 2.95) { bot.ctl.jump = true; bot.ctl.fwd = false; }
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
            if ((falling || charge >= 1f && p.onGround() && r > 2.6) && bot.ctl.aimed(p, 25) && r <= 3.0) {
                if (Act.attack(bot, d.head, 0.95f) && falling) bot.log("치명타!");
            }
            return S.RUNNING;
        }

        // 날고 있음: 포털 근처에서 대기하며 활로 머리 저격
        bot.reflex.holdEating = false;
        double cdist = Math.hypot(p.getX(), p.getZ());
        if (cdist > 16) {
            detail = hp + " — 포털 근처로 이동";
            archer.reset();
            bot.nav.go(new Goal.NearXZ(0, 0, 10));
            return S.RUNNING;
        }
        bot.nav.stop();
        Vec3 headC = d.head.getBoundingBox().getCenter();
        double dist = p.getEyePosition().distanceTo(headC);
        if (dist < 60 && dist > 6 && p.hasLineOfSight(d)) {
            detail = hp + " — 날아다니는 드래곤 저격";
            archer.high = false;
            if (archer.tick(bot, headC, vel)) bot.log("드래곤에 화살 발사");
        } else {
            detail = hp + " — 내려앉기를 기다림";
            archer.reset();
            bot.ctl.lookAt(p, headC);
        }
        return S.RUNNING;
    }

    // ---------------- 도우미 ----------------
    /** 출구 포털 (0,0 근처) 중 나와 가장 가까운 포털 블록 */
    private static BlockPos exitPortal(ClientLevel l, LocalPlayer p) {
        BlockPos best = null;
        double bd = 1e9;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                for (int y = 30; y <= 100; y++) {
                    m.set(x, y, z);
                    if (l.getBlockState(m).is(Blocks.END_PORTAL)) {
                        double d = p.distanceToSqr(Vec3.atCenterOf(m));
                        if (d < bd) { bd = d; best = m.immutable(); }
                    }
                }
        return best;
    }

    public static AreaEffectCloud breathAt(ClientLevel l, LocalPlayer p) {
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof AreaEffectCloud c && c.getParticle().getType() == ParticleTypes.DRAGON_BREATH) {
                double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                double r = c.getRadius() + 1.2;
                if (dx * dx + dz * dz < r * r && Math.abs(p.getY() - c.getY()) < 3) return c;
            }
        }
        return null;
    }

    private static BlockPos findBlock(ClientLevel l, LocalPlayer p, net.minecraft.world.level.block.Block block, int r) {
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++)
                for (int y = -12; y <= 12; y++) {
                    m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                    if (l.getBlockState(m).is(block)) {
                        double d = m.distSqr(c);
                        if (d < bd) { bd = d; best = m.immutable(); }
                    }
                }
        return best;
    }
}
