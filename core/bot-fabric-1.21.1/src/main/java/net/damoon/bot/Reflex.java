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
    private final Archer archer = new Archer();
    /** 자리 지키기 (스포너 옆): 쫓아가지 않고 다가오는 적만 벰 */
    public boolean camp;
    /** 벙커 짓는 중: 체력이 넉넉하면 방패보다 짓기를 먼저 */
    public boolean building;
    public final Turtle turtle = new Turtle();

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
        if ((p.hasEffect(MobEffects.WITHER) || p.hasEffect(MobEffects.POISON)) && Inv.has(p, Items.MILK_BUCKET)) {
            if (p.isUsingItem() && !p.getUseItem().is(Items.MILK_BUCKET)) { bot.ctl.use = false; return true; }
            Inv.select(p, Inv.is(Items.MILK_BUCKET));
            bot.ctl.use = true;
            doing = "우유 마시는 중 (위더/독 해제)";
            return true;
        }

        // 위험하면 블록으로 몸을 감싸고 회복 (터틀링)
        if (turtle.tick(bot, this)) return true;

        // 엔더 진주로 위기 탈출 (공허·용암·포위)
        if (Pearl.tick(bot)) return true;

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
            if (fire != null && Act.reach(p, new net.minecraft.world.phys.AABB(fire)) < 4.0) {
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
        LivingEntity threat = fightEnabled ? threat(p) : null;
        if (building && p.getHealth() > 9 && threat != null && Act.reach(p, threat.getBoundingBox()) > 3.0) threat = null;
        boolean shieldOn = p.getOffhandItem().is(Items.SHIELD);

        // 블레이즈 공격 타이밍 읽기: 불꽃을 모으기 시작한 뒤 약 3초(60틱)부터 1초 동안 화염구 3발 (trackBlazes는 위에서)
        // 화염구 방어: 실제로 화염구가 날아오는 때에만 그 블레이즈 쪽으로 방패를 듦 (나머지 시간엔 공격)
        net.minecraft.world.entity.monster.Blaze shooter = null;
        double sd = 1e9;
        for (Entity e : bot.level().entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.monster.Blaze b && b.isAlive() && inShotWindow(b, 0) && b.distanceTo(p) < 24 && p.hasLineOfSight(b) && b.distanceTo(p) < sd) { sd = b.distanceTo(p); shooter = b; }
        boolean meleeTarget = threat instanceof net.minecraft.world.entity.monster.Blaze bt && Act.reach(p, bt.getBoundingBox()) <= 3.0 && bt.getHealth() <= 7;
        if (shooter != null && shieldOn && !meleeTarget && !(building && p.getHealth() > 9)) {
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
        // 블레이즈: 불꽃을 모으면(발사 직전) 방패로 막고, 칼이 닿을 때만 벰
        if (threat instanceof net.minecraft.world.entity.monster.Blaze bz && p.getOffhandItem().is(Items.SHIELD)) {
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, bz.getBoundingBox().getCenter());
            double r = Act.reach(p, bz.getBoundingBox());
            boolean danger = false;
            // 곧 화염구가 날아올 블레이즈가 있으면 위험 (활은 당기는 데 1초 걸리므로 여유를 둠)
            boolean soon = false;
            for (Entity e : bot.level().entitiesForRendering())
                if (e instanceof net.minecraft.world.entity.monster.Blaze o && o.distanceTo(p) < 24 && p.hasLineOfSight(o)) {
                    if (inShotWindow(o, 0)) danger = true;
                    if (inShotWindow(o, 22)) soon = true;
                }
            if (bz.getHealth() <= 7 && Act.reach(p, bz.getBoundingBox()) <= 3.0) danger = false;
            // 체력이 낮으면 바라본 채 방패 들고 뒷걸음질
            if (p.getHealth() < 12 && !camp) {
                bot.ctl.use = true;
                if (safeBehind(p)) bot.ctl.back = true;
                doing = "체력이 낮아 블레이즈를 보며 뒷걸음질";
                return true;
            }
            // 활이 있으면: 쏘기 직전이 아닐 때 활로 저격 (가까우면 칼)
            boolean hasBow = Inv.has(p, Items.BOW) && (Inv.has(p, Items.ARROW) || Inv.has(p, Items.SPECTRAL_ARROW));
            if (hasBow && !danger && (!soon || p.isUsingItem() && p.getUseItem().is(Items.BOW)) && r > 3.0 && bz.distanceTo(p) < 28 && p.hasLineOfSight(bz)) {
                if (p.isUsingItem() && p.getMainHandItem().is(Items.SHIELD) == false && !p.getUseItem().is(Items.BOW)) { bot.ctl.use = false; return true; }
                Vec3 vel = bz.position().subtract(bz.xo, bz.yo, bz.zo);
                if (archer.tick(bot, bz.getBoundingBox().getCenter(), vel)) bot.log("블레이즈에 화살 발사");
                doing = "블레이즈 활로 저격";
                return true;
            }
            if (danger && p.isUsingItem() && p.getUseItem().is(Items.BOW)) { bot.ctl.use = false; archer.reset(); return true; }
            if (r <= 3.0 && !danger) {
                if (p.isUsingItem()) { bot.ctl.use = false; doing = "방패 내리고 블레이즈 공격"; return true; }
                if (bot.ctl.aimed(p, 20)) Act.attack(bot, bz, 0.9f);
                doing = "블레이즈 공격!";
            } else {
                Inv.selectWeapon(p);
                bot.ctl.use = true;
                if (!danger && r > 3.0 && !camp) bot.ctl.fwd = true;
                doing = danger ? "방패로 화염구 막는 중" : "방패 들고 블레이즈에게 다가가는 중";
            }
            return true;
        }
        if (threat != null) {
            // 활 쏘는 몹에게 다가갈 때는 방패를 들고 감
            double r = Act.reach(p, threat.getBoundingBox());
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
            if (gap || need) {
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

    /** 이 블레이즈가 (지금 + ahead 틱 뒤에) 화염구를 쏘는 구간인지. 모으기 시작 후 약 55~85틱 */
    private boolean inShotWindow(net.minecraft.world.entity.monster.Blaze b, int ahead) {
        Integer st = chargeStart.get(b.getId());
        if (st == null) return false;
        int age = bot.ticks - st;
        for (int a = age; a <= age + ahead; a++) if (a >= 52 && a <= 88) return true;
        return age > 88 && b.isOnFire();
    }

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
                && bot.level().getGameTime() - lastHurtStamp(p) < 60 && atk.distanceTo(p) < 6 && p.hasLineOfSight(atk)) {
            return atk;
        }
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!hostile(e)) continue;
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
            if (++chaseTicks > 100 && d > 3.5) { ignoreUntil.put(best.getId(), bot.ticks + 20 * 30); return null; }
        }
        return best;
    }
}
