package net.damoon.bot.task;

import net.damoon.bot.Act;
import net.damoon.bot.Bot;
import net.damoon.bot.Inv;
import net.damoon.bot.Nav;
import net.damoon.bot.Scan;
import net.damoon.bot.path.Goal;
import net.damoon.bot.path.PathExec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** 빈 양동이에 물 담기 (엔드에서 떨어질 때 물 낙법용) */
public class WaterTask extends Task {
    private final Set<Long> bad = new HashSet<>();
    private int tries, wait, ticks;
    private String detail = "";

    @Override public String name() { return "물 담기"; }
    @Override public String detail() { return detail; }

    @Override
    public S tick(Bot bot) {
        LocalPlayer p = bot.p();
        ClientLevel l = bot.level();
        if (Inv.has(p, Items.WATER_BUCKET)) return S.DONE;
        if (!Inv.has(p, Items.BUCKET) || ++ticks > 20 * 60 * 3) return S.FAILED;
        if (wait > 0) { wait--; return S.RUNNING; }
        detail = "물 뜨러 가는 중";
        BlockPos src = Scan.nearest(l, PathExec.feet(p), 4, p.getBlockY() - 24, p.getBlockY() + 40,
                s -> s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource(),
                b -> l.getBlockState(b.above()).isAir() && !bad.contains(b.asLong()));
        if (src == null) return S.FAILED;
        if (Act.reach(p, new net.minecraft.world.phys.AABB(src)) > 4.0) {
            Nav.R r = bot.nav.go(new Goal.Reach(src, 3.0));
            if (r == Nav.R.FAILED) { bot.nav.stop(); bad.add(src.asLong()); }
            return S.RUNNING;
        }
        bot.nav.stop();
        Vec3 aim = Vec3.atCenterOf(src).add(0, 0.4, 0);
        if (ObsidianTask.useBucket(bot, Items.BUCKET, aim)) { wait = 5; if (++tries > 12) { bad.add(src.asLong()); tries = 0; } }
        return S.RUNNING;
    }
}
