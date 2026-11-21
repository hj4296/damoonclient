package net.damoon.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 작업과 상관없이 먼저 반응하는 행동: 먹기, 회복, 가까이 온 몹과 싸우기, 크리퍼 피하기 */
public final class Reflex {
    private final Bot bot;
    private boolean eating;
    private int eatTicks;
    public String doing = "";
    /** 작업이 지금은 먹지 말라고 할 때 (예: 드래곤 머리 때리는 중) */
    public boolean holdEating;
    public boolean fightEnabled = true;
    /** 회복 중: 배고픔을 가득 채워 자연 회복이 되게 */
    public boolean forceEat;
    final Archer archer = new Archer();
    /** 자리 지키기 (스포너 옆): 쫓아가지 않고 다가오는 적만 벰 */
    public boolean camp;
    /** 벙커 짓는 중: 체력이 넉넉하면 방패보다 짓기를 먼저 */
    public boolean building;
    public final Turtle turtle = new Turtle();
    public final Tower tower = new Tower();
    /** 엔더맨 굴 안: 엔더맨은 굴이 상대하니 반사 행동은 무시 (이 틱까지) */
    public int ignoreEndermenUntil;

    /** 배고픔이 덜 찼으면 먹기 시작 (시작했으면 true) */
    public boolean startEatIfNeeded() {
        LocalPlayer p = bot.p();
        if (eating) return true;
        int food = p.getFoodData().getFoodLevel();
        boolean gap = p.getHealth() <= 10 && Inv.has(p, Items.GOLDEN_APPLE) && !p.hasEffect(MobEffects.REGENERATION);
        if (food >= 20 && !gap) return false;
        boolean ok = gap ? Inv.select(p, Inv.is(Items.GOLDEN_APPLE)) : selectBestFood(p);
        if (!ok) return false;
        eating = true;
        eatTicks = 0;
        bot.ctl.use = !(p.isUsingItem() && !p.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD));
        return true;
    }
    private net.minecraft.core.BlockPos mlgWater;
    private int mlgTicks;

    Reflex(Bot bot) { this.bot = bot; }

    public boolean tick() {
        LocalPlayer p = bot.p();
        doing = "";
        if (p.containerMenu != p.inventoryMenu) return false;

        trackBlazes(p);
        // 갑옷·방패는 생기면 바로 착용 (어떤 작업 중이든)
        if (bot.ticks % 20 == 7 && !p.isUsingItem()) { Inv.equipArmor(p); Inv.equipShield(p); }
        // 바로 앞의 근접 몹 (위더 스켈레톤 등): 이게 있으면 먹기·우유·불 끄기·화염구 방어보다 방패 싸움이 먼저
        // 날아오는 화염구·화살: 궤적을 계산해서 나를 맞힐 것만 방패로 막음 (방패는 들고 5틱 뒤부터 막힘)
        Entity proj = turtle.sealed() || (tower.active() && !tower.onTop(p)) ? null : incoming(p, 15);
        // 한 번 막기 시작한 투사체는 지나갈 때까지 계속 막음 (예측이 틱마다 흔들려도)
        if (proj == null && lastProj >= 0 && !turtle.sealed()) {
            Entity lp = bot.level().getEntity(lastProj);
            if (lp != null && lp.isAlive() && lp.distanceTo(p) < 20
                    && lp.getDeltaMovement().dot(p.getBoundingBox().getCenter().subtract(lp.position())) > 0) proj = lp;
            else lastProj = -1;
        }
        if (proj != null) lastProj = proj.getId();
        // 근접 몹이 붙어 있으면: 투사체와 근접 몹이 둘 다 방패 앞(180°)에 들어오게 가운데를 봄. 반대편이면 근접 몹 쪽 (더 아픔)
        LivingEntity melee0 = turtle.sealed() || tower.onTop(p) ? null : nearMelee(p, 4.0);
        float faceYaw = Float.NaN;
        if (proj != null && melee0 != null) {
            float a1 = Ctl.angles(p.getEyePosition(), melee0.getBoundingBox().getCenter())[0];
            float a2 = Ctl.angles(p.getEyePosition(), proj.position())[0];
            float diff = net.minecraft.util.Mth.wrapDegrees(a2 - a1);
            if (Math.abs(diff) <= 140) faceYaw = a1 + diff / 2;
            else proj = null;
        }
        if (proj != null && p.getOffhandItem().is(Items.SHIELD)) {
            if (eating) eating = false;
            Inv.selectWeapon(p);
            if (p.isUsingItem() && !p.getUseItem().is(Items.SHIELD)) { bot.ctl.use = false; doing = "투사체! 손에 든 것 내림"; return true; }
            float multi = bestShieldYaw(p, proj);
            if (!Float.isNaN(faceYaw)) bot.ctl.look(faceYaw, 10f);
            else if (!Float.isNaN(multi)) bot.ctl.look(multi, 0f);
            else bot.ctl.lookAt(p, proj.position());
            bot.ctl.speed = 45f;
            bot.ctl.use = true;
            // 쏜 몹(스켈레톤 등)이 16칸 안이면 방패를 든 채 그쪽으로 걸어가서 잡음 (가만히 막기만 하면 끝이 안 남)
            Entity shooterE = proj instanceof net.minecraft.world.entity.projectile.Projectile pj ? pj.getOwner() : null;
            if (shooterE instanceof LivingEntity sh && sh.isAlive() && hostile(sh) && sh.distanceTo(p) < 16 && Float.isNaN(faceYaw)
                    && Bot.safeAheadToward(p, sh.getX(), sh.getZ()) && !(sh instanceof net.minecraft.world.entity.monster.Blaze) && !(sh instanceof net.minecraft.world.entity.monster.Ghast)) {
                bot.ctl.moveToward(p, sh.position());
                if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                doing = "화살을 막으며 " + sh.getName().getString() + "에게 다가가는 중";
                return true;
            }
            // 막으면서도 가던 길은 계속 감 (걷는 길일 때만, 방패 쪽을 본 채 옆·뒷걸음)
            var ex = bot.nav.exec();
            if (ex != null) {
                var to = ex.target();
                if (to != null && to.move != null && (to.move.name().equals("WALK") || to.move.name().equals("DIAG"))
                        && Bot.safeAheadToward(p, to.x + 0.5, to.z + 0.5))
                    bot.ctl.moveToward(p, new Vec3(to.x + 0.5, p.getY(), to.z + 0.5));
            }
            doing = "날아오는 " + proj.getName().getString() + " 방패로 막는 중";
            return true;
        }
        boolean safe = turtle.sealed() || tower.active();
        LivingEntity near = safe ? null : nearMelee(p, 4.5);
        if (eating && !safe) {
            LivingEntity n6 = nearMelee(p, 6.0);
            if (n6 != null) {
                eating = false;
                if (p.isUsingItem()) bot.ctl.use = false;
                bot.log("먹다가 " + n6.getName().getString() + "이(가) 다가와서 멈춤");
            }
        }
        // 먹는 중이라도 곧 화염구가 날아오면 멈추고 방패부터
        if (eating) {
            for (Entity e : bot.level().entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.Blaze b && inShotWindow(b, 8) && b.distanceTo(p) < 24 && p.hasLineOfSight(b)) { eating = false; break; }
        }
        // 먹는 중이면 계속 (단, 방패 등 다른 걸 쓰고 있으면 먼저 손을 떼야 먹기 시작됨)
        if (eating && p.isUsingItem() && !p.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD)) {
            bot.ctl.use = false;
            doing = "먹으려고 방패 내리는 중";
            return true;
        }
        if (eating) {
            eatTicks++;
            if ((!p.isUsingItem() && eatTicks > 6) || eatTicks > 70) {
                eating = false;
            } else {
                bot.ctl.use = true;
                doing = "먹는 중";
                return true;
            }
        }

        // 위더·독에 걸리면 우유
        if ((p.hasEffect(MobEffects.WITHER) || p.hasEffect(MobEffects.POISON)) && Inv.has(p, Items.MILK_BUCKET)
                && near == null && !blazeSoon(p, 40) && (turtle.sealed() || !hostileNear(p, 8))) {
            if (p.isUsingItem() && !p.getUseItem().is(Items.MILK_BUCKET)) { bot.ctl.use = false; return true; }
            Inv.select(p, Inv.is(Items.MILK_BUCKET));
            bot.ctl.use = true;
            doing = "우유 마시는 중 (위더/독 해제)";
            return true;
        }

        // 위험하면 블록으로 몸을 감싸고 회복 (터틀링)
        // 물속에서 숨이 차면 가장 가까운 공기 쪽으로 헤엄쳐 올라감 (익사 방지) — 무엇보다 먼저
        if (p.isUnderWater() && p.getAirSupply() < 150) {
            net.minecraft.core.BlockPos f = p.blockPosition();
            net.minecraft.core.BlockPos air = null;
            double ad = 1e9;
            for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(f.offset(-6, -2, -6), f.offset(6, 8, 6))) {
                var st = bot.level().getBlockState(q);
                if (!st.isAir() || !bot.level().getBlockState(q.above()).isAir()) continue;
                double d = q.distSqr(f) + (q.getY() < f.getY() ? 20 : 0);
                if (d < ad) { ad = d; air = q.immutable(); }
            }
            if (eating) { eating = false; bot.ctl.use = false; }
            Act.stopDig(bot);
            bot.nav.stop();
            if (air != null) {
                bot.ctl.lookAt(p, Vec3.atCenterOf(air));
                bot.ctl.fwd = Math.abs(air.getX() + 0.5 - p.getX()) + Math.abs(air.getZ() + 0.5 - p.getZ()) > 0.6;
            } else bot.ctl.look(p.getYRot(), -70f);
            bot.ctl.jump = true;
            bot.ctl.sprint = true;
            doing = "숨이 참 → 물 위로 (공기 " + p.getAirSupply() / 15 + ")";
            return true;
        }

        // 드래곤 브레스 구름 안이면 (벽을 뚫고 들어옴) 무엇보다 먼저 빠져나옴 — 발밑이 있는 쪽으로만
        if (bot.level().dimension() == net.minecraft.world.level.Level.END) {
            var cloud = net.damoon.bot.task.EndGameTask.breathAt(bot.level(), p);
            if (cloud != null) {
                if (turtle.active()) turtle.abort();
                if (eating) { eating = false; bot.ctl.use = false; }
                Vec3 away = new Vec3(p.getX() - cloud.getX(), 0, p.getZ() - cloud.getZ());
                if (away.lengthSqr() < 0.01) away = new Vec3(-p.getX(), 0, -p.getZ());
                away = away.normalize();
                Vec3 pick = null;
                double bestDot = -2;
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8;
                    Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                    boolean ok = true;
                    for (double t = 1.0; t <= 4.0 && ok; t += 1.0) {
                        if (!Bot.safeAheadToward(p, p.getX() + d.x * t, p.getZ() + d.z * t)) ok = false;
                        // 벽에 막히지 않고 실제로 걸어갈 수 있는 쪽 (발·머리 높이가 비었거나 한 칸 오르면 되는 곳)
                        net.minecraft.core.BlockPos c = net.minecraft.core.BlockPos.containing(p.getX() + d.x * t, p.getY() + 0.1, p.getZ() + d.z * t);
                        var lv = bot.level();
                        boolean feetFree = lv.getBlockState(c).getCollisionShape(lv, c).isEmpty();
                        boolean headFree = lv.getBlockState(c.above()).getCollisionShape(lv, c.above()).isEmpty();
                        boolean stepUp = !feetFree && headFree && lv.getBlockState(c.above(2)).getCollisionShape(lv, c.above(2)).isEmpty();
                        if (!(feetFree && headFree) && !stepUp) ok = false;
                    }
                    if (ok && d.dot(away) > bestDot) { bestDot = d.dot(away); pick = d; }
                }
                bot.nav.stop();
                Act.stopDig(bot);
                if (pick != null) {
                    bot.ctl.lookYaw(p, p.position().add(pick.scale(5)), 5f);
                    bot.ctl.fwd = true;
                    bot.ctl.sprint = bestDot > 0;
                    // 벽에 막혔으면 부수거나 뛰어넘음
                    if (p.horizontalCollision) {
                        bot.ctl.jump = true;
                        net.minecraft.core.BlockPos w = p.blockPosition().offset((int) Math.round(pick.x), 0, (int) Math.round(pick.z));
                        for (net.minecraft.core.BlockPos b : new net.minecraft.core.BlockPos[]{w, w.above()})
                            if (!bot.level().getBlockState(b).getCollisionShape(bot.level(), b).isEmpty()) { Act.dig(bot, b); break; }
                    }
                } else bot.ctl.sneak = true;
                doing = "드래곤 브레스 구름에서 빠져나오는 중";
                return true;
            }
        }

        // 엔드: 드래곤 화염구가 내 근처에 떨어질 것 같으면 (구름이 생김) 날아오는 방향의 옆으로 피함
        if (bot.level().dimension() == net.minecraft.world.level.Level.END) {
            for (Entity e : bot.level().entitiesForRendering()) {
                if (!(e instanceof net.minecraft.world.entity.projectile.DragonFireball fb)) continue;
                Vec3 pos = fb.position(), v = fb.getDeltaMovement();
                if (v.lengthSqr() < 1e-4) v = fb.position().subtract(fb.xo, fb.yo, fb.zo);
                if (v.lengthSqr() < 1e-4) continue;
                Vec3 land = null;
                for (int t = 0; t < 60; t++) {
                    Vec3 np = pos.add(v);
                    var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(pos, np, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, fb));
                    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) { land = hit.getLocation(); break; }
                    if (p.getBoundingBox().inflate(0.5).clip(pos, np).isPresent()) { land = p.position(); break; }
                    pos = np;
                    v = v.add(v.normalize().scale(0.1)).scale(0.95);
                }
                if (land == null || land.distanceTo(p.position()) > 5.5) continue;
                Vec3 side = new Vec3(-v.z, 0, v.x).normalize();
                Vec3 away = p.position().subtract(land).multiply(1, 0, 1);
                if (away.lengthSqr() > 0.01 && side.dot(away) < 0) side = side.scale(-1);
                Vec3 target = p.position().add(side.scale(6));
                if (!Bot.safeAheadToward(p, target.x, target.z)) { side = side.scale(-1); target = p.position().add(side.scale(6)); }
                if (eating) { eating = false; bot.ctl.use = false; }
                bot.nav.stop();
                bot.ctl.lookYaw(p, target, 0f);
                bot.ctl.fwd = true;
                bot.ctl.sprint = true;
                if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                doing = "드래곤 화염구 피하는 중";
                return true;
            }
        }

        // 엔드: 날아오는 드래곤(돌진)은 방패로 막음 (몸통 공격은 방패에 막힘)
        if (bot.level().dimension() == net.minecraft.world.level.Level.END && p.getOffhandItem().is(Items.SHIELD) && !turtle.sealed()) {
            for (Entity e : bot.level().entitiesForRendering()) {
                if (!(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dr) || !dr.isAlive()) continue;
                var ph = dr.getPhaseManager().getCurrentPhase();
                if (ph.isSitting()) break;
                // 몸 부위 중 가장 가까운 것
                double best = 1e9;
                Vec3 at = null;
                for (var part : dr.getSubEntities()) {
                    double dd = part.getBoundingBox().distanceToSqr(p.getEyePosition());
                    if (dd < best) { best = dd; at = part.getBoundingBox().getCenter(); }
                }
                // 앉아 있지 않은 드래곤이 25칸 안이면 항상 몸통 쪽으로 방패 (다가올 때 들면 준비가 늦음)
                if (at != null && best < 25 * 25 && dr.distanceTo(p) < 30) {
                    if (eating) { eating = false; }
                    Inv.selectWeapon(p);
                    if (p.isUsingItem() && !p.getUseItem().is(Items.SHIELD)) { bot.ctl.use = false; return true; }
                    bot.ctl.lookAt(p, dr.position().add(0, 1, 0));
                    bot.ctl.speed = 45f;
                    bot.ctl.use = true;
                    doing = "돌진하는 드래곤을 방패로 막는 중";
                    return true;
                }
                break;
            }
        }

        // 엔더 진주로 위기 탈출 (공허·죽을 낙하·용암·포위) — 무엇보다 먼저
        if (Pearl.tick(bot)) return true;

        // 근접 몹이 여럿이면 기둥을 쌓고 올라가 위에서 싸움
        if (tower.tick(bot, this)) return true;
        if (!tower.active() && turtle.tick(bot, this, near)) return true;

        // 높은 곳에서 떨어지는 중이면 물 양동이로 착지 (MLG)
        if (!p.onGround() && !p.isInWater() && p.fallDistance > 4.5 && p.getDeltaMovement().y < -0.5
                && bot.level().dimension() != net.minecraft.world.level.Level.NETHER && Inv.has(p, Items.WATER_BUCKET)) {
            Inv.select(p, Inv.is(Items.WATER_BUCKET));
            bot.ctl.look(p.getYRot(), 90f);
            bot.ctl.speed = 90f;
            doing = "물 양동이 낙법!";
            net.minecraft.core.BlockPos f = p.blockPosition();
            for (int dy = 1; dy <= 4; dy++) {
                net.minecraft.core.BlockPos g = f.below(dy);
                var gs = bot.level().getBlockState(g);
                if (!gs.getCollisionShape(bot.level(), g).isEmpty()) {
                    if (p.getY() - (g.getY() + 1) < 2.6 && p.getXRot() > 80) {
                        net.minecraft.client.Minecraft.getInstance().gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                        if (mlgWater == null) bot.log(String.format("물 낙법! (%.0f칸 낙하)", p.fallDistance));
                        mlgWater = g.above();
                        mlgTicks = 0;
                    }
                    break;
                }
            }
            return true;
        }
        // 낙법에 쓴 물 다시 퍼 담기
        if (mlgWater != null) {
            if (++mlgTicks > 60 || !bot.level().getFluidState(mlgWater).isSource()) { mlgWater = null; }
            else if (p.onGround() || p.isInWater()) {
                Inv.select(p, Inv.is(Items.BUCKET));
                bot.ctl.lookAt(p, net.minecraft.world.phys.Vec3.atCenterOf(mlgWater));
                if (bot.ctl.aimed(p, 3) && mlgTicks > 6) {
                    net.minecraft.client.Minecraft.getInstance().gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    mlgWater = null;
                }
                doing = "물 다시 퍼 담는 중";
                return true;
            }
        }

        // 발밑·주변의 불은 주먹으로 꺼 버림 (블레이즈 화염구가 바닥에 붙인 불 속에 서 있지 않게)
        {
            net.minecraft.core.BlockPos f = p.blockPosition();
            net.minecraft.core.BlockPos fire = null;
            double fd = 1e9;
            for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(f.offset(-2, -1, -2), f.offset(2, 2, 2))) {
                var st = bot.level().getBlockState(q);
                if (st.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                    double d = q.distSqr(f);
                    if (d < fd) { fd = d; fire = q.immutable(); }
                }
            }
            if (fire != null && near == null && Act.reach(p, new net.minecraft.world.phys.AABB(fire)) < 4.0) {
                Act.dig(bot, fire);
                doing = "불 끄는 중";
                return true;
            }
        }

        // 크리퍼가 터지려 하면 도망
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof Creeper c && c.isAlive() && c.getSwelling(1f) > 0.15f && c.distanceTo(p) < 5) {
                Vec3 away = p.position().subtract(c.position()).normalize();
                bot.ctl.lookYaw(p, p.position().add(away.scale(5)), 0f);
                bot.ctl.fwd = true;
                bot.ctl.sprint = true;
                if (p.horizontalCollision) bot.ctl.jump = true;
                doing = "크리퍼 피하는 중";
                return true;
            }
        }

        // 가까운 적과 싸우기
        LivingEntity threat = fightEnabled ? (near != null ? near : threat(p)) : null;
        if (building && p.getHealth() > 9 && threat != null && Act.reach(p, threat.getBoundingBox()) > 3.0) threat = null;
        boolean shieldOn = p.getOffhandItem().is(Items.SHIELD);

        // 블레이즈 공격 타이밍 읽기: 불꽃을 모으기 시작한 뒤 약 3초(60틱)부터 1초 동안 화염구 3발 (trackBlazes는 위에서)
        // 화염구 방어: 실제로 화염구가 날아오는 때에만 그 블레이즈 쪽으로 방패를 듦 (나머지 시간엔 공격)
        net.minecraft.world.entity.monster.Blaze shooter = null;
        double sd = 1e9;
        for (Entity e : bot.level().entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.monster.Blaze b && b.isAlive() && pointBlank(b, 0) && b.distanceTo(p) < 6 && p.hasLineOfSight(b) && b.distanceTo(p) < sd) { sd = b.distanceTo(p); shooter = b; }
        boolean meleeTarget = threat instanceof net.minecraft.world.entity.monster.Blaze bt && Act.reach(p, bt.getBoundingBox()) <= 3.0 && bt.getHealth() <= 7;
        if (false && shooter != null && shieldOn && !meleeTarget && near == null && !(building && p.getHealth() > 9)) {
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, shooter.getBoundingBox().getCenter());
            bot.ctl.use = true;
            boolean weakNow = p.getHealth() < 10;
            if (weakNow && safeBehind(p)) bot.ctl.back = true; // 바라본 채 뒷걸음
            doing = weakNow ? "방패 들고 뒷걸음질로 물러나는 중" : "화염구를 방패로 막는 중";
            return true;
        }

        boolean weak = p.getHealth() < 7 || (p.isOnFire() && p.getHealth() < 10);
        if (threat != null && ranged(threat) && weak && threat.distanceTo(p) > 3) {
            // 상대를 바라보고 방패를 든 채 뒷걸음질로 물러남 (등을 보이지 않음)
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, threat.getBoundingBox().getCenter());
            if (shieldOn) bot.ctl.use = true;
            if (safeBehind(p)) bot.ctl.back = true;
            doing = "체력이 낮아 " + threat.getName().getString() + "을(를) 보며 뒷걸음질";
            return true;
        }
        // 블레이즈: 멀리서 날아오는 화염구는 위(incoming)에서 방패로 막음.
        // 여기선 칼이 닿으면 벰 — 단, 가까운 블레이즈가 막 쏘는 순간(모으기 시작 후 약 56~76틱)엔 방패
        if (threat instanceof net.minecraft.world.entity.monster.Blaze bz0 && p.getOffhandItem().is(Items.SHIELD)) {
            // (칼은 칼을 쓸 때만 쥠 — 활을 당기는 중에 칼로 바꾸면 당기기가 취소됨)
            // 칼이 닿는 블레이즈가 있으면 그중 체력이 가장 낮은 것
            net.minecraft.world.entity.monster.Blaze bz = bz0;
            float lowHp = 999;
            for (Entity e : bot.level().entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.Blaze o && o.isAlive() && Act.reach(p, o.getBoundingBox()) <= 3.0
                        && o.getHealth() < lowHp) { lowHp = o.getHealth(); bz = o; }
            double r = Act.reach(p, bz.getBoundingBox());
            boolean danger = false, soon = false;
            for (Entity e : bot.level().entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.Blaze o && o.isAlive() && o.distanceTo(p) < 6 && p.hasLineOfSight(o)) {
                    if (pointBlank(o, 0)) danger = true;
                    if (pointBlank(o, 20)) soon = true;
                }
            // 체력이 낮으면 바라본 채 방패 들고 뒷걸음질 (벙커·기둥 안이면 그 자리)
            if (p.getHealth() < 10 && !camp) {
                Inv.selectWeapon(p);
                bot.ctl.lookAt(p, bz.getBoundingBox().getCenter());
                bot.ctl.use = true;
                if (safeBehind(p)) bot.ctl.back = true;
                doing = "체력이 낮아 블레이즈를 보며 뒷걸음질";
                return true;
            }
            // 칼이 닿으면 벰 (실제로 날아오는 화염구는 맨 위 incoming에서 먼저 막음)
            if (r <= 3.0) {
                if (!(p.isUsingItem() && p.getUseItem().is(Items.BOW))) Inv.selectWeapon(p);
                else { bot.ctl.use = false; return true; }
                bot.ctl.lookAt(p, bz.getBoundingBox().getCenter());
                if (p.isUsingItem() && p.getAttackStrengthScale(0.5f) >= 0.9f) { bot.ctl.use = false; doing = "방패 내리고 블레이즈 공격"; return true; }
                if (!p.isUsingItem() && bot.ctl.aimed(p, 20) && Act.attack(bot, bz, 0.9f)) { doing = "블레이즈 공격!"; return true; }
                if (p.getAttackStrengthScale(0.5f) < 0.9f) bot.ctl.use = true;
                doing = "블레이즈 공격 준비";
                return true;
            }
            // 활: 곧 가까운 블레이즈가 쏘지 않고, 화살이 막히지 않을 때
            boolean hasBow = Inv.has(p, Items.BOW) && (Inv.has(p, Items.ARROW) || Inv.has(p, Items.SPECTRAL_ARROW));
            boolean drawing = p.isUsingItem() && p.getUseItem().is(Items.BOW);
            Vec3 aimAt = bz.getBoundingBox().getCenter();
            if (hasBow && !danger && (!soon || drawing) && r > 3.0 && bz.distanceTo(p) < 28 && p.hasLineOfSight(bz)
                    && (drawing || Ballistics.clearShot(bot.level(), p, p.getEyePosition().subtract(0, 0.1, 0), aimAt, 3.0))) {
                if (p.isUsingItem() && !drawing) { bot.ctl.use = false; return true; }
                Vec3 vel = bz.position().subtract(bz.xo, bz.yo, bz.zo);
                if (archer.tick(bot, aimAt, vel)) bot.log("블레이즈에 화살 발사");
                doing = "블레이즈 활로 저격";
                return true;
            }
            if (danger && drawing) { bot.ctl.use = false; archer.reset(); return true; }
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, bz.getBoundingBox().getCenter());
            bot.ctl.use = true;
            if (!danger && r > 3.0 && !camp) bot.ctl.fwd = true;
            doing = danger ? "블레이즈가 코앞에서 쏘는 중 → 방패" : camp ? "방패 들고 블레이즈 기다리는 중" : "방패 들고 블레이즈에게 다가가는 중";
            return true;
        }
        if (threat != null) {
            // 활 쏘는 몹에게 다가갈 때는 방패를 들고 감
            double r = Act.reach(p, threat.getBoundingBox());
            // 방패가 없으면 멀리서 쏘는 몹에게 다가가지 않음 (가다가 여러 대 맞음) → 반대쪽으로 달려 시야에서 벗어남
            if (ranged(threat) && !shieldOn && r > 5.0 && !(threat instanceof net.minecraft.world.entity.monster.Blaze)) {
                Vec3 away = p.position().subtract(threat.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
                Vec3 to = p.position().add(away.normalize().scale(6));
                if (Bot.safeAheadToward(p, to.x, to.z)) {
                    bot.ctl.lookYaw(p, to, 0f);
                    bot.ctl.fwd = true;
                    bot.ctl.sprint = p.getFoodData().getFoodLevel() > 6;
                    if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                    doing = "방패가 없어 " + threat.getName().getString() + "에게서 도망";
                    return true;
                }
            }
            if (ranged(threat) && r > 3.0 && shieldOn) {
                Inv.selectWeapon(p);
                bot.ctl.lookAt(p, threat.getBoundingBox().getCenter());
                bot.ctl.use = true;
                if (!camp) bot.ctl.fwd = true;
                if (p.horizontalCollision && p.onGround()) bot.ctl.jump = true;
                doing = "방패 들고 " + threat.getName().getString() + "에게 다가가는 중";
                return true;
            }
            // 활이 있으면 멀리 있는 근접 몹(위더 스켈레톤 등)은 먼저 활로 쏨
            if (r > 5.0 && Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW) && p.hasLineOfSight(threat) && !(p.isUsingItem() && p.getUseItem().is(Items.SHIELD))) {
                Vec3 vel = threat.position().subtract(threat.xo, threat.yo, threat.zo);
                if (archer.tick(bot, threat.getBoundingBox().getCenter(), vel)) bot.log(threat.getName().getString() + "에게 화살 발사");
                doing = threat.getName().getString() + " 활로 저격";
                return true;
            }
            // 약한 몹(좀벌레 등)은 칼이 준비됐을 때만 한 번 베고, 나머지 틱엔 하던 일을 계속함
            if (threat.getMaxHealth() <= 10 && !(threat instanceof Creeper) && p.getHealth() >= 8) {
                if (r <= 3.0 && p.getAttackStrengthScale(0.5f) >= 0.9f) {
                    Inv.selectWeapon(p);
                    bot.ctl.lookAt(p, threat.getBoundingBox().getCenter());
                    if (bot.ctl.aimed(p, 30)) Act.attack(bot, threat, 0.9f);
                    doing = threat.getName().getString() + " 베기";
                    return true;
                }
                return false;
            }
            String st = Melee.fight(bot, threat, !camp);
            doing = threat.getName().getString() + ": " + st;
            return true;
        }

        // 먹기 / 황금사과
        if (!holdEating) {
            int food = p.getFoodData().getFoodLevel();
            float hp = p.getHealth();
            boolean gap = hp <= 10 && Inv.has(p, Items.GOLDEN_APPLE) && !p.hasEffect(MobEffects.REGENERATION);
            boolean need = food <= 14 || (hp < 14 && food < 20) || (forceEat && food < 20);
            // 적이 가까이 있거나 곧 화염구가 오면 먹지 않음 (먹는 1.6초 동안 방패를 못 듦)
            boolean unsafe = !turtle.sealed() && (hostileNear(p, 7) || blazeSoon(p, 35));
            if ((gap || need) && (!unsafe || (hp <= 5 && !hostileNear(p, 4)))) {
                boolean ok = gap ? Inv.select(p, Inv.is(Items.GOLDEN_APPLE)) : selectBestFood(p);
                if (ok) {
                    eating = true;
                    eatTicks = 0;
                    // 방패를 들고 있었으면 이번 틱엔 떼기만 함
                    bot.ctl.use = !(p.isUsingItem() && !p.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD));
                    bot.ctl.look(p.getYRot(), -30f);
                    doing = gap ? "황금사과 먹는 중" : "먹는 중";
                    return true;
                }
            }
        }
        return false;
    }

    /** 가까이 있고 보이는 근접 몹 (활·화염구 쏘는 몹·크리퍼 제외) */
    private LivingEntity nearMelee(LocalPlayer p, double lim) {
        LivingEntity best = null;
        double bd = lim;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!hostile(e) || ranged(e) || e instanceof Creeper) continue;
            if (e instanceof EnderMan && bot.ticks < ignoreEndermenUntil) continue;
            if (e instanceof net.minecraft.world.entity.monster.Slime || e instanceof net.minecraft.world.entity.monster.Ghast) continue;
            double d = e.distanceTo(p);
            if (d >= bd || Math.abs(e.getY() - p.getY()) > 2.5 || !p.hasLineOfSight(e)) continue;
            bd = d;
            best = (LivingEntity) e;
        }
        return best;
    }

    private boolean hostileNear(LocalPlayer p, double lim) {
        for (Entity e : bot.level().entitiesForRendering())
            if (hostile(e) && !(e instanceof EnderMan) && e.distanceTo(p) < lim && p.hasLineOfSight(e)) return true;
        return false;
    }

    private final java.util.Set<Integer> seenProj = new java.util.HashSet<>();
    private int lastProj = -1;

    /** 지금 날아오는 투사체들 (incoming이 채움): 방패 방향을 고를 때 씀 */
    private final java.util.List<Entity> incomingAll = new java.util.ArrayList<>();

    /** 날아오는 투사체를 가장 많이(아픈 것 우선) 막을 수 있는 방향 (방패는 앞쪽 ±90°를 막음) */
    private float bestShieldYaw(LocalPlayer p, Entity first) {
        if (incomingAll.size() <= 1) return Float.NaN;
        java.util.List<float[]> dirs = new java.util.ArrayList<>();
        for (Entity e : incomingAll) {
            float w = e instanceof net.minecraft.world.entity.projectile.LargeFireball ? 4
                    : e instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile ? 3 : 1;
            dirs.add(new float[]{Ctl.angles(p.getEyePosition(), e.position())[0], w});
        }
        java.util.List<Float> cand = new java.util.ArrayList<>();
        for (float[] a : dirs) cand.add(a[0]);
        for (int i = 0; i < dirs.size(); i++) for (int j = i + 1; j < dirs.size(); j++)
            cand.add(dirs.get(i)[0] + net.minecraft.util.Mth.wrapDegrees(dirs.get(j)[0] - dirs.get(i)[0]) / 2);
        float best = Float.NaN, bw = -1;
        for (float c : cand) {
            float w = 0;
            for (float[] a : dirs) if (Math.abs(net.minecraft.util.Mth.wrapDegrees(a[0] - c)) < 78) w += a[1];
            if (w > bw) { bw = w; best = c; }
        }
        return best;
    }

    /** maxT 틱 안에 내 몸에 닿을 화염구·화살 (가장 먼저 닿는 것) */
    public Entity incoming(LocalPlayer p, int maxT) {
        incomingAll.clear();
        net.minecraft.world.phys.AABB box0 = p.getBoundingBox();
        Vec3 pv = p.getDeltaMovement().multiply(1, 0, 1);
        Entity best = null;
        int bt = maxT + 1;
        for (Entity e : bot.level().entitiesForRendering()) {
            boolean fb = e instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile
                    && !(e instanceof net.minecraft.world.entity.projectile.DragonFireball);
            boolean ar = e instanceof net.minecraft.world.entity.projectile.AbstractArrow a && a.getDeltaMovement().lengthSqr() > 0.01;
            if (!fb && !ar) continue;
            if (e instanceof net.minecraft.world.entity.projectile.Projectile pr && pr.getOwner() == p) continue;
            if (e.distanceToSqr(p) > 48 * 48) continue;
            Vec3 pos = e.position(), v = e.getDeltaMovement();
            if (v.lengthSqr() < 1e-6) v = e.position().subtract(e.xo, e.yo, e.zo);
            if (v.lengthSqr() < 1e-6) continue;
            // 나에게서 멀어지는 것(내가 쏜 화살 등)은 무시
            if (v.dot(p.getBoundingBox().getCenter().subtract(pos)) <= 0) continue;
            for (int t = 1; t <= maxT; t++) {
                Vec3 np = pos.add(v);
                // 벽·천장에 먼저 부딪히면 나한테 안 옴 (벙커·터틀링·기둥 안이면 대부분 여기서 걸러짐)
                var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(pos, np,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, e));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    net.minecraft.world.phys.AABB bx = box0.move(pv.scale(t)).inflate(0.3);
                    if (!bx.contains(hit.getLocation()) && hit.getLocation().distanceTo(p.getBoundingBox().getCenter()) > 1.6) break;
                }
                // 내가 움직이는 것도 예측, 멀리 볼수록 여유를 크게
                // 가스트 화염구는 근처 아무 데나 맞아도 터지니 넓게
                double grow = e instanceof net.minecraft.world.entity.projectile.LargeFireball ? 2.2 : 0.45;
                net.minecraft.world.phys.AABB box = box0.move(pv.scale(t)).inflate(grow + t * 0.04);
                if (box.contains(np) || box.clip(pos, np).isPresent()) {
                    incomingAll.add(e);
                    if (t < bt) { bt = t; best = e; }
                    if (seenProj.add(e.getId())) bot.dbg(String.format("투사체 감지 %s %.1f칸 %d틱 뒤 방패=%s", e.getType().toShortString(), e.distanceTo(p), t, p.isBlocking()));
                    break;
                }
                pos = np;
                if (fb) v = v.add(v.normalize().scale(0.1)).scale(0.95);
                else v = v.scale(0.99).add(0, -0.05, 0);
            }
        }
        return best;
    }

    public boolean blazeSoon(LocalPlayer p, net.minecraft.world.entity.monster.Blaze b, int ahead) {
        return b.isAlive() && b.distanceTo(p) < 24 && inShotWindow(b, ahead);
    }

    /** ahead 틱 안에 화염구를 쏠 블레이즈가 보이는지 */
    private boolean blazeSoon(LocalPlayer p, int ahead) {
        for (Entity e : bot.level().entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.monster.Blaze b && b.isAlive() && b.distanceTo(p) < 24 && inShotWindow(b, ahead) && p.hasLineOfSight(b)) return true;
        return false;
    }

    private boolean selectBestFood(LocalPlayer p) {
        int best = -1, bestVal = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().items.get(i);
            if (!Inv.food().test(s) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;
            int v = Inv.foodValue(s);
            if (v > bestVal) { bestVal = v; best = i; }
        }
        if (best < 0) return false;
        ItemStack want = p.getInventory().items.get(best);
        return Inv.select(p, s -> s == want);
    }

    public static boolean hostile(Entity e) {
        if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (e instanceof EnderMan em) return em.isCreepy();
        // 중립 몹은 화가 나서 공격하려 할 때만 적
        if (e instanceof ZombifiedPiglin zp) return zp.isAggressive();
        if (e instanceof AbstractPiglin pg) return pg.isAggressive();
        return !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon);
    }

    private final java.util.Map<Integer, Integer> ignoreUntil = new java.util.HashMap<>();
    private int chaseId = -1, chaseTicks;
    private double chaseBest;

    private static long lastHurtStamp(LocalPlayer p) {
        // LivingEntity.lastDamageStamp 는 비공개라 hurtTime으로 근사
        return p.hurtTime > 0 ? p.level().getGameTime() : 0;
    }

    private final java.util.Map<Integer, Integer> chargeStart = new java.util.HashMap<>();

    private void trackBlazes(LocalPlayer p) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.monster.Blaze b) || !b.isAlive()) continue;
            seen.add(b.getId());
            if (b.isOnFire()) chargeStart.putIfAbsent(b.getId(), bot.ticks);
            else chargeStart.remove(b.getId());
        }
        chargeStart.keySet().retainAll(seen);
    }

    /** 가까운 블레이즈가 지금~ahead틱 안에 실제로 쏘는 순간인지 (모으기 시작 후 60·66·72틱에 쏨, 여유 포함 56~76) */
    private boolean pointBlank(net.minecraft.world.entity.monster.Blaze b, int ahead) {
        Integer st = chargeStart.get(b.getId());
        if (st == null) return false;
        int age = bot.ticks - st;
        for (int a = age; a <= age + ahead; a++) if (a >= 56 && a <= 76) return true;
        return false;
    }

    /** 이 블레이즈가 (지금 + ahead 틱 뒤에) 화염구를 쏘는 구간인지. 모으기 시작 후 약 55~85틱 */
    private boolean inShotWindow(net.minecraft.world.entity.monster.Blaze b, int ahead) {
        Integer st = chargeStart.get(b.getId());
        if (st == null) return false;
        int age = bot.ticks - st;
        for (int a = age; a <= age + ahead; a++) if (a >= 52 && a <= 88) return true;
        return age > 88 && b.isOnFire();
    }

    public static boolean safeBehindPublic(LocalPlayer p) { return safeBehind(p); }

    /** 바라보는 반대쪽(뒤)으로 한 걸음이 안전한지 */
    static boolean safeBehind(LocalPlayer p) {
        float y = p.getYRot();
        p.setYRot(y + 180f);
        boolean ok = Bot.safeAhead(p);
        p.setYRot(y);
        return ok;
    }

    static boolean ranged(Entity e) {
        return (e instanceof net.minecraft.world.entity.monster.AbstractSkeleton && !(e instanceof net.minecraft.world.entity.monster.WitherSkeleton))
                || e instanceof net.minecraft.world.entity.monster.Pillager
                || e instanceof net.minecraft.world.entity.monster.Witch || e instanceof net.minecraft.world.entity.monster.Blaze;
    }

    private LivingEntity threat(LocalPlayer p) {
        LivingEntity best = null;
        double bd = 1e9;
        // 방금 나를 때린 상대에게는 종류와 상관없이 반격
        var src = p.getLastDamageSource();
        if (src != null && src.getEntity() instanceof LivingEntity atk && atk.isAlive() && atk != p
                && !(atk instanceof EnderMan && bot.ticks < ignoreEndermenUntil)
                && bot.level().getGameTime() - lastHurtStamp(p) < 60 && atk.distanceTo(p) < 6 && p.hasLineOfSight(atk)) {
            return atk;
        }
        boolean inEnd = bot.level().dimension() == net.minecraft.world.level.Level.END;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!hostile(e)) continue;
            if (e instanceof EnderMan && bot.ticks < ignoreEndermenUntil) continue;
            // 엔드에선 드래곤이 우선: 엔더맨은 바로 옆(3칸)에서 덤빌 때만
            if (inEnd && e instanceof EnderMan && e.distanceTo(p) > 3) continue;
            if (bot.ticks < ignoreUntil.getOrDefault(e.getId(), 0)) continue;
            double d = e.distanceTo(p);
            // 활 쏘는 몹은 멀리서도 보이면 달려가서 벰
            boolean bowReady = Inv.has(p, Items.BOW) && Inv.has(p, Items.ARROW);
            boolean blaze = e instanceof net.minecraft.world.entity.monster.Blaze;
            double lim = e instanceof EnderMan ? 8 : blaze && bowReady ? 28 : ranged(e) ? 14
                    : (bowReady && e instanceof net.minecraft.world.entity.monster.WitherSkeleton) ? 14 : 4.5;
            double dyLim = blaze ? 14 : ranged(e) ? 6 : 3;
            if (d > lim || Math.abs(e.getY() - p.getY()) > dyLim) continue;
            if (!p.hasLineOfSight(e)) continue;
            double score = d - (ranged(e) ? 6 : 0);
            if (score < bd) { bd = score; best = (LivingEntity) e; }
        }
        // 쫓아가도 가까워지지 않으면 (절벽 위 등) 잠시 무시
        if (best != null) {
            double d = best.distanceTo(p);
            if (best.getId() != chaseId) { chaseId = best.getId(); chaseTicks = 0; chaseBest = d; }
            if (d < chaseBest - 0.5) { chaseBest = d; chaseTicks = 0; }
            if (++chaseTicks > 100 && d > 3.5 && !(camp && best instanceof net.minecraft.world.entity.monster.Blaze)) { ignoreUntil.put(best.getId(), bot.ticks + 20 * 30); return null; }
        }
        return best;
    }
}
