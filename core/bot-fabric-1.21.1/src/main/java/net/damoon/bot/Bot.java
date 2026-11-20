package net.damoon.bot;

import net.damoon.bot.path.Goal;
import net.damoon.bot.task.EndGameTask;
import net.damoon.bot.task.GotoTask;
import net.damoon.bot.task.Setup;
import net.damoon.bot.task.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 봇의 중심: 작업 실행, 반사 행동, 화면 처리, 명령, 상태 기록 */
public final class Bot {
    public static final Bot I = new Bot();
    public static final Logger LOG = LoggerFactory.getLogger("DamoonBot");

    public final Ctl ctl = new Ctl();
    public final Nav nav = new Nav(this);
    public final Reflex reflex = new Reflex(this);

    public boolean running;
    public Task root;
    public int ticks, deaths;
    public boolean victory;
    /** 감마팩 (밝기 1500%) */
    public boolean gamma = true;
    /** 마지막으로 죽은 곳 (떨어뜨린 물건 되찾기) */
    public net.minecraft.core.BlockPos deathPos;
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> deathDim;
    public int deathTick;
    public long startedAt;
    public final Deque<String> events = new ArrayDeque<>();
    /** 진단용: 마지막으로 지나간 분기 */
    public String trace = "";
    public final Deque<String> traceHist = new ArrayDeque<>();
    private Supplier<Task> restart;
    private int statusTick;

    public Minecraft mc() { return Minecraft.getInstance(); }
    public LocalPlayer p() { return mc().player; }
    public ClientLevel level() { return mc().level; }

    public void log(String s) {
        LOG.info("[봇] " + s);
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        events.addFirst(t + " " + s);
        while (events.size() > 7) events.removeLast();
        try {
            Path f = dir().resolve("events.log");
            Files.writeString(f, t + " " + s + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public static Path dir() {
        Path d = Minecraft.getInstance().gameDirectory.toPath().resolve("bot");
        try { Files.createDirectories(d); } catch (IOException ignored) {}
        return d;
    }

    public void start(Supplier<Task> make) {
        restart = make;
        root = make.get();
        running = true;
        victory = false;
        startedAt = System.currentTimeMillis();
        ticks = 0;
        log("시작: " + root.name());
    }

    public void stop() {
        running = false;
        root = null;
        nav.stop();
        Act.stopDig(this);
        ctl.releaseAll();
        log("정지");
    }

    public void tick() {
        Minecraft mc = mc();
        Act.tick();
        // 감마(풀브라이트): 동굴·밤에도 화면이 보이게
        double g = gamma ? 15.0 : 1.0;
        if (mc.options.gamma().get() != g) ((net.damoon.bot.mixin.OptionInstanceAccessor) (Object) mc.options.gamma()).damoonbot$setValue(g);
        Control.poll(this);
        if (++statusTick % 20 == 0) Control.writeStatus(this);
        if (!running || mc.player == null || mc.level == null) return;
        mc.options.pauseOnLostFocus = false;
        ticks++;

        if (mc.screen instanceof PauseScreen) mc.setScreen(null);
        if (mc.screen instanceof DeathScreen) {
            deathPos = mc.player.blockPosition();
            deathDim = mc.level.dimension();
            deathTick = ticks;
            deaths++;
            log("죽었다… 다시 시작 (" + deaths + "번째)");
            mc.player.respawn();
            mc.setScreen(null);
            nav.stop();
            if (restart != null) root = restart.get();
            return;
        }
        if (mc.screen instanceof WinScreen) {
            if (!victory) {
                victory = true;
                long sec = (System.currentTimeMillis() - startedAt) / 1000;
                log("엔딩 크레딧! 마인크래프트 클리어 (" + sec / 60 + "분 " + sec % 60 + "초)");
            }
            return;
        }

        ctl.reset();
        boolean busy = reflex.tick();
        if (!busy && root != null) {
            Task.S s;
            try {
                s = root.tick(this);
            } catch (Exception e) {
                LOG.error("봇 오류", e);
                log("오류: " + e);
                s = Task.S.RUNNING;
            }
            if (s != Task.S.RUNNING) {
                log(root.name() + (s == Task.S.DONE ? " 완료" : " 실패"));
                running = false;
                ctl.reset();
                ctl.apply(mc.player);
                ctl.releaseAll();
                return;
            }
        }
        // 길찾기가 아닌 직접 걷기(싸움·도망·줍기)일 때 앞이 낭떠러지·용암이면 멈춤
        if (!pathDriving && (ctl.fwd || ctl.sprint) && !safeAhead(mc.player)) {
            ctl.fwd = false;
            ctl.sprint = false;
        }
        pathDriving = false;
        ctl.apply(mc.player);
        traceHist.addFirst(trace);
        while (traceHist.size() > 6) traceHist.removeLast();
        trace = "";
    }

    /** PathExec가 이번 틱에 몸을 움직였는지 (길찾기 경로는 자체적으로 안전 확인) */
    public boolean pathDriving;

    /** 바라보는 쪽으로 한 걸음 앞이 안전한지 (3칸 안에 바닥이 있고 용암·불이 아님) */
    public static boolean safeAhead(LocalPlayer p) {
        if (!p.onGround()) return true;
        net.minecraft.world.level.Level l = p.level();
        double yaw = Math.toRadians(p.getYRot());
        double fx = p.getX() - Math.sin(yaw) * 0.9, fz = p.getZ() + Math.cos(yaw) * 0.9;
        int y = (int) Math.floor(p.getY() + 0.05);
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(fx, y, fz);
        for (int dy = 0; dy <= 1; dy++) {
            var s = l.getBlockState(at.above(dy));
            if (BlockUtil.hurts(s)) return false;
        }
        for (int k = 1; k <= 3; k++) {
            var s = l.getBlockState(at.below(k));
            if (BlockUtil.isLava(s) || BlockUtil.hurts(s)) return false;
            if (BlockUtil.floor(l, at.getX(), at.getY() - k, at.getZ(), s)) return true;
            if (!s.getCollisionShape(l, at.below(k)).isEmpty()) return true;
        }
        return false;
    }

    /** 통합 서버에서 관리자 권한으로 명령 실행 (테스트 준비용) */
    public void server(String... cmds) {
        MinecraftServer srv = mc().getSingleplayerServer();
        if (srv == null || p() == null) { log("싱글플레이 서버 없음"); return; }
        UUID id = p().getUUID();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayer(id);
            if (sp == null) return;
            CommandSourceStack src = sp.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            for (String c : cmds) srv.getCommands().performPrefixedCommand(src, c);
        });
    }

    public List<String> hud() {
        List<String> out = new ArrayList<>();
        LocalPlayer p = p();
        String st = running ? (root != null ? root.name() : "?") : (victory ? "클리어!" : "대기");
        out.add("§b[Damoon Bot]§r " + st);
        if (running && root != null) {
            String d = root.detail();
            if (!d.isEmpty()) out.add("§7작업:§r " + d);
        }
        if (!reflex.doing.isEmpty()) out.add("§e반응:§r " + reflex.doing);
        if (running && !nav.info.isEmpty()) out.add("§7이동:§r " + nav.info);
        if (p != null) {
            long sec = running ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
            out.add(String.format("§c체력 %.0f§r  §6배고픔 %d§r  시간 %d:%02d  죽음 %d", p.getHealth(), p.getFoodData().getFoodLevel(), sec / 60, sec % 60, deaths));
        }
        int n = 0;
        for (String e : events) { if (n++ >= 4) break; out.add("§8" + e); }
        return out;
    }

    // ---------------- 명령 ----------------
    public void command(String line) {
        String[] a = line.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return;
        log("명령: " + line.trim());
        switch (a[0]) {
            case "stop" -> stop();
            case "start" -> {
                String what = a.length > 1 ? a[1] : "end";
                switch (what) {
                    case "end" -> start(EndGameTask::new);
                    case "full" -> start(net.damoon.bot.task.BeatGameTask::new);
                    default -> log("모르는 작업: " + what);
                }
            }
            case "goto" -> {
                if (a.length >= 4) {
                    int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
                    start(() -> new GotoTask(new Goal.Block(x, y, z, false)));
                } else if (a.length == 3) {
                    int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                    start(() -> new GotoTask(new Goal.XZ(x, z)));
                }
            }
            case "setup" -> Setup.run(this, a.length > 1 ? a[1] : "end");
            case "cmd" -> server(line.trim().substring(4));
            case "gamma" -> { gamma = a.length < 2 || !a[1].equals("off"); log("감마 " + (gamma ? "켬 (1500%)" : "끔")); }
            case "probe" -> {
                if (p() == null) return;
                net.minecraft.core.BlockPos f = net.damoon.bot.path.PathExec.feet(p());
                StringBuilder sb = new StringBuilder("probe feet=" + f.toShortString() + " y=" + p().getY() + " ground=" + p().onGround() + " sel=" + p().getInventory().selected + "\n");
                for (int dy = 3; dy >= -2; dy--) {
                    sb.append("dy=").append(dy).append(":");
                    for (int[] o : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        var s = level().getBlockState(f.offset(o[0], dy, o[1]));
                        sb.append(' ').append(s.getBlock().builtInRegistryHolder().key().location().getPath());
                    }
                    sb.append('\n');
                }
                var ex = nav.exec();
                if (ex != null) sb.append("target=").append(ex.target()).append(" breaks=").append(ex.target().breaks.length);
                LOG.info(sb.toString());
                try { Files.writeString(dir().resolve("probe.txt"), sb.toString()); } catch (IOException ignored) {}
            }
            case "newworld" -> Setup.newWorld(this, a.length > 1 ? Long.parseLong(a[1]) : System.nanoTime(), a.length > 2 ? a[2] : "bot-" + (System.currentTimeMillis() / 1000));
            case "load" -> Setup.loadWorld(this, a[1]);
            case "title" -> { if (mc().level != null) { mc().level.disconnect(); mc().disconnect(); } mc().setScreen(new TitleScreen()); }
            case "skipcredits" -> { if (mc().screen instanceof WinScreen) mc().setScreen(null); if (p() != null) p().connection.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)); }
            default -> log("모르는 명령");
        }
    }
}
