package net.damoon.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 키보드·마우스 대신 봇이 누르는 입력.
 * 매 틱 reset() 후 원하는 키만 켜고, apply()가 실제 키 상태와 시점을 바꿉니다.
 * 시점은 사람처럼 부드럽게 돌아갑니다 (한 틱에 최대 speed 도).
 */
public final class Ctl {
    public boolean fwd, back, left, right, jump, sneak, sprint, use;
    private boolean look;
    private float tYaw, tPitch;
    public float speed = 35f;

    public void reset() {
        fwd = back = left = right = jump = sneak = sprint = use = false;
        look = false;
        speed = 35f;
    }

    public void look(float yaw, float pitch) {
        look = true;
        tYaw = yaw;
        tPitch = Mth.clamp(pitch, -90f, 90f);
    }

    public void lookAt(LocalPlayer p, Vec3 target) {
        float[] a = angles(p.getEyePosition(), target);
        look(a[0], a[1]);
    }

    /** 수평 방향만 바라보기 (위아래는 pitch로 고정) */
    public void lookYaw(LocalPlayer p, Vec3 target, float pitch) {
        float[] a = angles(p.getEyePosition(), target);
        look(a[0], pitch);
    }

    public static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * (180.0 / Math.PI));
        return new float[]{yaw, pitch};
    }

    /** 바라보는 방향과 상관없이 target 쪽으로 걷기 (방패를 들고 옆·뒷걸음으로 이동) */
    public void moveToward(LocalPlayer p, Vec3 target) {
        double dx = target.x - p.getX(), dz = target.z - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.15) return;
        dx /= len;
        dz /= len;
        double yaw = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // 앞
        double lx = Math.cos(yaw), lz = Math.sin(yaw);    // 왼쪽
        double f = dx * fx + dz * fz, s = dx * lx + dz * lz;
        if (f > 0.38) fwd = true; else if (f < -0.38) back = true;
        if (s > 0.38) left = true; else if (s < -0.38) right = true;
    }

    public float targetYaw() { return tYaw; }
    public float targetPitch() { return tPitch; }

    /** 목표 시점과 실제 시점 차이가 tol 도 이내인지 */
    public boolean aimed(LocalPlayer p, float tol) {
        return Math.abs(Mth.wrapDegrees(tYaw - p.getYRot())) < tol && Math.abs(tPitch - p.getXRot()) < tol;
    }

    public void apply(LocalPlayer p) {
        Options o = Minecraft.getInstance().options;
        if (look && p != null) {
            float dy = Mth.wrapDegrees(tYaw - p.getYRot());
            float dp = tPitch - p.getXRot();
            p.setYRot(p.getYRot() + ease(dy));
            p.setXRot(Mth.clamp(p.getXRot() + ease(dp), -90f, 90f));
            p.setYHeadRot(p.getYRot());
        }
        if (p != null) avoidEndermanGaze(p);
        o.keyUp.setDown(fwd);
        o.keyDown.setDown(back);
        o.keyLeft.setDown(left);
        o.keyRight.setDown(right);
        o.keyJump.setDown(jump);
        o.keyShift.setDown(sneak);
        o.keySprint.setDown(sprint);
        o.keyUse.setDown(use);
    }

    /** 가만히 있는 엔더맨의 눈을 쳐다보면 화를 내므로, 시선이 눈 근처에 걸리면 살짝 아래를 봄 */
    private static void avoidEndermanGaze(LocalPlayer p) {
        if (p.level() == null) return;
        Vec3 view = p.getViewVector(1f).normalize();
        Vec3 eye = p.getEyePosition();
        for (var e : ((net.minecraft.client.multiplayer.ClientLevel) p.level()).entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.monster.EnderMan em) || em.isCreepy() || !em.isAlive()) continue;
            Vec3 to = em.getEyePosition().subtract(eye);
            double d = to.length();
            if (d > 64) continue;
            if (view.dot(to.normalize()) > 1.0 - 0.07 / d && p.hasLineOfSight(em)) {
                p.setXRot(Mth.clamp(p.getXRot() + (p.getXRot() > 60 ? -20f : 14f), -90f, 90f));
                return;
            }
        }
    }

    private float ease(float d) {
        if (Math.abs(d) < 1.5f) return d;
        float step = d * 0.55f;
        return Mth.clamp(step, -speed, speed);
    }

    public void releaseAll() {
        reset();
        Options o = Minecraft.getInstance().options;
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        o.keyShift.setDown(false);
        o.keySprint.setDown(false);
        o.keyUse.setDown(false);
        o.keyAttack.setDown(false);
    }
}
