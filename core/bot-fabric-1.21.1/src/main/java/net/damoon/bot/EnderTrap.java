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
    public enum R { RUNNING, NONE }

    private int targetId = -1, boatId = -1, placeWait;
    private final Set<Integer> ignore = new HashSet<>();
    private final java.util.Map<Integer, Integer> pickTime = new java.util.HashMap<>();
    private int targetTicks;
    public String info = "";

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
        double reach = Act.reach(p, em.getBoundingBox());
        if (em.isPassenger()) {
            info = "보트에 갇힌 엔더맨 공격!";
            Inv.selectWeapon(p);
            bot.ctl.lookAt(p, em.getBoundingBox().getCenter());
            if (reach > 2.8) {
                Nav.R r = bot.nav.go(new Goal.Near(em.blockPosition(), 1.6));
                if (r == Nav.R.FAILED) bot.nav.stop();
            } else {
                bot.nav.stop();
                Melee.fight(bot, em, false);
            }
            return R.RUNNING;
        }
        if (!em.isCreepy()) {
            if (d > 24 || !p.hasLineOfSight(em)) {
                info = "엔더맨에게 다가가는 중";
                Nav.R r = bot.nav.go(new Goal.Near(em.blockPosition(), 12));
                if (r == Nav.R.FAILED) { bot.nav.stop(); ignore.add(em.getId()); targetId = -1; }
                return R.RUNNING;
            }
            info = "엔더맨 눈을 바라봐서 화나게 하는 중";
            bot.nav.stop();
            bot.ctl.lookAt(p, em.getEyePosition());
            return R.RUNNING;
        }
        // 화남: 다가오는 길목에 보트를 놓음
        bot.nav.stop();
        if (placeWait > 0) placeWait--;
        if (boat == null && hasBoat(p) && p.onGround() && d < 14 && d > 3.2 && placeWait == 0) {
            Vec3 dir = new Vec3(em.getX() - p.getX(), 0, em.getZ() - p.getZ()).normalize();
            BlockPos floor = BlockPos.containing(p.position().add(dir.scale(1.9))).below();
            var fs = l.getBlockState(floor);
            if (BlockUtil.floor(l, floor.getX(), floor.getY(), floor.getZ(), fs) && l.getBlockState(floor.above()).isAir()) {
                info = "엔더맨이 오는 길에 보트 놓는 중";
                Inv.select(p, s -> s.is(ItemTags.BOATS));
                Vec3 aim = Vec3.atCenterOf(floor).add(0, 0.5, 0);
                bot.ctl.lookAt(p, aim);
                bot.ctl.speed = 60f;
                if (bot.ctl.aimed(p, 2f)) {
                    Minecraft.getInstance().gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    placeWait = 10;
                }
                return R.RUNNING;
            }
        }
        if (boat == null && boatId == -1) {
            for (Entity e : l.entitiesForRendering())
                if (e instanceof Boat b && b.distanceTo(p) < 5 && b.getPassengers().isEmpty()) { boatId = b.getId(); break; }
        }
        // 보트가 엔더맨과 나 사이에 오도록 자리를 옮김 → 다가오던 엔더맨이 보트에 탐
        if (boat != null && reach > 3.0 && boat.distanceTo(p) < 8) {
            Vec3 away = new Vec3(boat.getX() - em.getX(), 0, boat.getZ() - em.getZ()).normalize();
            Vec3 spot = boat.position().add(away.scale(1.4));
            double sd = Math.hypot(spot.x - p.getX(), spot.z - p.getZ());
            if (sd > 0.4) {
                Vec3 dir = new Vec3(spot.x - p.getX(), 0, spot.z - p.getZ()).normalize();
                // 엔더맨을 보면서 옆걸음/뒷걸음으로 자리 잡기
                bot.ctl.lookAt(p, em.getBoundingBox().getCenter());
                double yaw = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                double fwd = dir.x * fx + dir.z * fz, side = dir.x * fz - dir.z * fx;
                bot.ctl.fwd = fwd > 0.3;
                bot.ctl.back = fwd < -0.3;
                bot.ctl.left = side > 0.3;
                bot.ctl.right = side < -0.3;
                info = "보트를 사이에 두고 자리 잡는 중";
                return R.RUNNING;
            }
        }
        // 다가오길 기다림: 방패로 막다가 닿으면 치명타
        Melee.fight(bot, em, false);
        info = boat != null ? "보트 함정 앞에서 엔더맨을 기다리는 중" : "화난 엔더맨과 싸우는 중";
        return R.RUNNING;
    }
}
