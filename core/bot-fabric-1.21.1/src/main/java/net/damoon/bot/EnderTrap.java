package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * 엔더맨 잡기 (보트 함정): 눈을 바라봐 화나게 하고, 다가오는 길목에 보트를 놓아 태운 뒤
 * (보트에 탄 엔더맨은 순간이동을 못 함) 벰. 끝나면 보트를 다시 챙김.
 */
public final class EnderTrap {
    private BlockPos boatGoal;
    private int boatGoTicks, angryTicks;
    public enum R { RUNNING, NONE }

    private int targetId = -1, boatId = -1, placeWait, pushTicks;
    private final Set<Integer> ignore = new HashSet<>();
    private final java.util.Map<Integer, Integer> pickTime = new java.util.HashMap<>();
    private int targetTicks;
    public String info = "";
    /** 쳐다보기 방식: 엔더맨 한 마리의 눈을 쳐다봐 화나게 해서 불러온 뒤 방패 반격으로 잡음 (보트 없이) */
    public boolean stare;
    private int stareTicks;

    public static Item boatFor(LocalPlayer p) {
        Item[][] m = {{Items.OAK_PLANKS, Items.OAK_BOAT}, {Items.SPRUCE_PLANKS, Items.SPRUCE_BOAT}, {Items.BIRCH_PLANKS, Items.BIRCH_BOAT},
                {Items.DARK_OAK_PLANKS, Items.DARK_OAK_BOAT}, {Items.JUNGLE_PLANKS, Items.JUNGLE_BOAT}, {Items.ACACIA_PLANKS, Items.ACACIA_BOAT},
                {Items.MANGROVE_PLANKS, Items.MANGROVE_BOAT}, {Items.CHERRY_PLANKS, Items.CHERRY_BOAT}};
        for (Item[] x : m) if (Inv.count(p, x[0]) >= 5) return x[1];
        return null;
    }

    public static boolean hasBoat(LocalPlayer p) {
        return Inv.count(p, s -> s.is(ItemTags.BOATS)) > 0;
    }

    private int stareTarget = -1, placeTarget = -1, placeTicks;

    public R tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        // 진주·보트 줍기
        for (Entity e : l.entitiesForRendering()) {
            if (e instanceof ItemEntity ie && (ie.getItem().is(Items.ENDER_PEARL) || ie.getItem().is(ItemTags.BOATS)) && ie.distanceTo(p) < 12 && !ignore.contains(ie.getId())) {
                if (pickTime.merge(ie.getId(), 1, Integer::sum) > 20 * 8) { ignore.add(ie.getId()); continue; }
                info = "엔더 진주 줍는 중";
                if (ie.distanceTo(p) < 2.5) { bot.nav.stop(); bot.ctl.lookYaw(p, ie.position(), 20f); bot.ctl.fwd = true; }
                else { Nav.R r = bot.nav.go(new Goal.Near(ie.blockPosition(), 1.0)); if (r == Nav.R.FAILED) { bot.nav.stop(); ignore.add(ie.getId()); } }
                return R.RUNNING;
            }
        }
        Entity boat = boatId >= 0 ? l.getEntity(boatId) : null;
        if (boat != null && !boat.isAlive()) { boat = null; boatId = -1; }

        Entity t = targetId >= 0 ? l.getEntity(targetId) : null;
        if (t == null || !t.isAlive() || ++targetTicks > 20 * 60) {
            if (t != null && t.isAlive()) ignore.add(t.getId());
            targetId = -1;
            targetTicks = 0;
            double bd = 1e9;
            for (Entity e : l.entitiesForRendering()) {
                if (e instanceof EnderMan em && em.isAlive() && !ignore.contains(em.getId())) {
                    double d = em.distanceTo(p);
                    // 혼자 있는 엔더맨만 (여러 마리를 한꺼번에 화나게 하지 않게)
                    boolean alone = true;
                    for (Entity o : l.entitiesForRendering())
                        if (o != em && o instanceof EnderMan && o.distanceTo(em) < (stare ? 5 : 14)) { alone = false; break; }
                    if (!alone && !em.isCreepy()) continue;
                    if (d < 48 && d < bd) { bd = d; t = em; }
                }
            }
            if (t == null) {
                // 사냥감이 없으면 보트 회수
                if (boat != null && boat.distanceTo(p) < 8) {
                    info = "보트 다시 챙기는 중";
                    Inv.selectWeapon(p);
                    bot.ctl.lookAt(p, boat.position());
                    if (boat.distanceTo(p) > 2.5) bot.ctl.fwd = true;
                    else Act.attack(bot, boat, 0.5f);
                    return R.RUNNING;
                }
                info = "엔더맨이 안 보임";
                return R.NONE;
            }
            targetId = t.getId();
        }
        EnderMan em = (EnderMan) t;
        double d = em.distanceTo(p);
        if (em.isPassenger()) {
            pushTicks = 0;
            // 멀면 먼저 길찾기로 다가감 (보트에 탄 채 둥둥 떠서 목표가 매 틱 바뀌지 않게 칸을 고정). 1분 넘게 못 가면 포기
            if (Act.reach(p, em.getBoundingBox()) > 3.5) {
                if (boatGoal == null || boatGoal.distSqr(em.blockPosition()) > 4) { boatGoal = em.blockPosition(); boatGoTicks = 0; }
                if (++boatGoTicks > 20 * 60) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; boatGoal = null; return R.RUNNING; }
                info = "보트에 태운 엔더맨에게 다가가는 중";
                Nav.R r = bot.nav.go(new Goal.Near(boatGoal, 2.0));
                if (r == Nav.R.FAILED) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; boatGoal = null; }
                return R.RUNNING;
            }
            boatGoal = null;
            // 보트에 탄 엔더맨: 순간이동·이동 불가 → 팔이 안 닿는 끝 거리에서 벰
            bot.nav.stop();
            info = "보트에 태운 엔더맨 공격! (" + Melee.fight(bot, em, false) + ")";
            return R.RUNNING;
        }
        if (em.isCreepy()) {
            // 이미 화남: 방패·거리 유지·치명타로 싸움. 칼이 안 닿으면 길찾기를 멈추지 않음 (매 틱 멈춰 제자리이던 문제)
            if (Act.reach(p, em.getBoundingBox()) <= 3.5) { bot.nav.stop(); angryTicks = 0; }
            else if (++angryTicks > 20 * 60) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; angryTicks = 0; return R.RUNNING; }
            info = "화난 엔더맨과 싸우는 중 (" + Melee.fight(bot, em, true) + ")";
            return R.RUNNING;
        }
        if (stare || em.getId() == stareTarget || (!hasBoat(p) && boat == null)) {
            // 쳐다보기: 보이는 거리까지 다가간 뒤 눈을 쳐다봄 → 화나서 순간이동해 오면 반사 행동(방패 반격)이 싸움
            boolean los = p.hasLineOfSight(em);
            if (d > 20 || !los) {
                info = "엔더맨 쪽으로 가는 중";
                // 안 보이면 바로 옆까지 (화나지 않은 엔더맨은 공격하지 않음)
                Nav.R r = bot.nav.go(new Goal.Near(em.blockPosition(), los ? 12 : 2.5));
                if (r == Nav.R.ARRIVED && !los) { ignore.add(em.getId()); targetId = -1; bot.nav.stop(); }
                if (r == Nav.R.FAILED) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; }
                return R.RUNNING;
            }
            bot.nav.stop();
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, em.getEyePosition());
            bot.ctl.allowEnderGaze = true;
            info = "엔더맨 눈을 쳐다봐서 부르는 중";
            if (++stareTicks > 20 * 12) { stareTicks = 0; ignore.add(em.getId()); targetId = -1; }
            return R.RUNNING;
        }
        stareTicks = 0;
        // 화나게 하지 않도록 눈이 아니라 다리를 봄
        Vec3 legs = em.position().add(0, 0.4, 0);
        if (!hasBoat(p) && boat == null) {
            info = "보트가 없음";
            return R.NONE;
        }
        if (boat == null) {
            if (d > 5.0 || !p.hasLineOfSight(em)) {
                info = "엔더맨에게 다가가는 중 (다리만 봄)";
                Nav.R r = bot.nav.go(new Goal.Near(em.blockPosition(), 3.5));
                if (r == Nav.R.FAILED) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; }
                return R.RUNNING;
            }
            bot.nav.stop();
            if (placeWait > 0) { placeWait--; }
            // 엔더맨 쪽 1.6칸 앞 바닥에 보트를 놓음
            Vec3 dir = new Vec3(em.getX() - p.getX(), 0, em.getZ() - p.getZ()).normalize();
            BlockPos floor = BlockPos.containing(p.position().add(dir.scale(1.6))).below();
            var fs = l.getBlockState(floor);
            info = "엔더맨 앞에 보트 놓는 중";
            // 10초 안에 못 놓으면 (엔더맨이 턱 위·좁은 곳) 이 엔더맨은 쳐다봐서 불러 방패·칼로 잡음 (몇 분씩 보트만 들고 서 있던 문제)
            if (placeTarget != em.getId()) { placeTarget = em.getId(); placeTicks = 0; }
            if (++placeTicks > 20 * 10) { stareTarget = em.getId(); placeTicks = 0; return R.RUNNING; }
            if (BlockUtil.floor(l, floor.getX(), floor.getY(), floor.getZ(), fs) && l.getBlockState(floor.above()).isAir() && placeWait == 0) {
                Inv.select(p, s -> s.is(ItemTags.BOATS));
                Vec3 aim = Vec3.atCenterOf(floor).add(0, 0.5, 0);
                bot.ctl.lookAt(p, aim);
                bot.ctl.speed = 60f;
                if (bot.ctl.aimed(p, 2f) && p.getMainHandItem().is(ItemTags.BOATS)) {
                    Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    placeWait = 8;
                }
            } else {
                bot.ctl.lookAt(p, legs);
            }
            for (Entity e : l.entitiesForRendering())
                if (e instanceof Boat b && b.distanceTo(p) < 4 && b.getPassengers().isEmpty()) { boatId = b.getId(); pushTicks = 0; break; }
            return R.RUNNING;
        }
        // 보트를 밀어서 엔더맨에게 부딪침 → 빈 보트에 닿은 몹은 자동으로 탐
        if (++pushTicks > 20 * 15) {
            info = "보트가 빗나감 → 보트 회수 후 다시";
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, boat.position());
            if (boat.distanceTo(p) > 2.5) bot.ctl.fwd = true; else Act.attack(bot, boat, 0.5f);
            if (pushTicks > 20 * 25) pushTicks = 0;
            return R.RUNNING;
        }
        Vec3 toEm = new Vec3(em.getX() - boat.getX(), 0, em.getZ() - boat.getZ());
        Vec3 behind = boat.position().subtract(toEm.normalize().scale(1.1));
        double off = Math.hypot(behind.x - p.getX(), behind.z - p.getZ());
        bot.ctl.sprint = false;
        if (off > 0.9 && boat.distanceTo(p) > 1.4) {
            // 보트 뒤쪽(엔더맨 반대편)으로 돌아감
            info = "보트 뒤로 돌아가는 중";
            bot.ctl.lookYaw(p, behind, 20f);
            bot.ctl.fwd = true;
        } else {
            info = "보트를 밀며 달리는 중 → 엔더맨 태우기";
            bot.ctl.lookYaw(p, em.position(), 25f);
            bot.ctl.fwd = true;
            bot.ctl.sprint = p.getFoodData().getFoodLevel() > 6;
        }
        return R.RUNNING;
    }
}
