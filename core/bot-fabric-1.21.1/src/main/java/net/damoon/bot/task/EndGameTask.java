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
    private enum TowerSt { BASE, CLIMB, OPEN, SHOOT, DOWN }
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
            if (e instanceof EndCrystal c && c.isAlive() && c.getY() > 60 && Math.hypot(c.getX(), c.getZ()) > 20 && !caged(l, c)) crystals.add(c);
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
            // 크리스탈이 없어졌으면 내려옴
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
        for (int dist : new int[]{10, 9, 11, 8}) {
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
                tst = TowerSt.SHOOT;
                towerWait = 0;
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
                        // 화살이 안 되면 칼로 (폭발 감수)
                        tst = TowerSt.DOWN;
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
            if (r > 2.9) {
                detail = hp + " — 내려앉음! 머리로 달려감";
                archer.reset();
                // 머리는 계속 조금씩 움직이므로 블록 단위로 고정한 목표를 씀 (매 틱 길찾기 다시 하지 않게)
                AABB q = new AABB(BlockPos.containing(head.getCenter())).inflate(0.5);
                Nav.R nr = bot.nav.go(new Goal.Reach(q, 2.6));
                if (nr == Nav.R.FAILED) bot.nav.stop();
                return S.RUNNING;
            }
            bot.nav.stop();
            detail = hp + " — 머리 공격!";
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, head.getCenter());
            float charge = p.getAttackStrengthScale(0.5f);
            if (r > 2.4) bot.ctl.fwd = true;
            if (p.onGround() && charge > 0.85f) bot.ctl.jump = true;
            boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
            if ((falling || charge >= 1f && p.onGround() && r > 2.7) && bot.ctl.aimed(p, 25)) {
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

    private static AreaEffectCloud breathAt(ClientLevel l, LocalPlayer p) {
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
