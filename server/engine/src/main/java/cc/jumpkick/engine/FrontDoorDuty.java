// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.desktop.PowerShell;
import cc.jumpkick.desktop.WslWindowsDesktop;
import cc.jumpkick.engine.http.FrontDoor;
import cc.jumpkick.engine.http.FrontDoorRole;
import cc.jumpkick.engine.http.GuestPairing;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.WslInterop;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What an engine does for the front door while it runs. The front door (the host engine, or a WSL
 * guest holding 8910) tells every other paired engine it is there every two seconds and that it is
 * leaving when it stops. A WSL guest also runs its {@link FrontDoorRole}: it takes 8910 when no front
 * door is heard from, through a second listener that serves only the front door, with the shared
 * front-door token, and sends the root page to its own dashboard under {@code /engines/<id>/}.
 */
final class FrontDoorDuty implements AutoCloseable {

    /** Builds the 8910 listener a guest serves the front door on; not yet started. */
    interface Listener {
        HttpEngineServer create();
    }

    private final Supplier<@Nullable FrontDoor> door;
    private final @Nullable FrontDoorRole role;
    private final Consumer<String> log;
    private volatile boolean closed;

    private FrontDoorDuty(Supplier<@Nullable FrontDoor> door, @Nullable FrontDoorRole role, Consumer<String> log) {
        this.door = door;
        this.role = role;
        this.log = log;
    }

    /** The host engine's duty: heartbeats from {@code server}'s front door, and leaving when it stops. */
    static FrontDoorDuty host(Supplier<@Nullable HttpEngineServer> server, Consumer<String> log) {
        FrontDoorDuty duty = new FrontDoorDuty(
                () -> {
                    HttpEngineServer s = server.get();
                    return s == null ? null : s.frontDoor();
                },
                null,
                log);
        duty.every(FrontDoorRole.HEARTBEAT_MS, duty::beat);
        return duty;
    }

    /**
     * A WSL guest's duty: its role over 8910, taken through {@code listener}; {@code band} answers the
     * front door's control messages. {@code guests} is the Windows engine's pairing directory.
     */
    static FrontDoorDuty guest(
            HttpEngineServer band,
            String id,
            Path guests,
            Listener listener,
            @Nullable WslWindowsDesktop windows,
            Consumer<String> log) {
        Seat seat = new Seat(listener, windows, log);
        FrontDoorRole role = new FrontDoorRole(seat, Clock.SYSTEM, () -> outranking(guests, id), log);
        band.setFrontDoorRole(role);
        FrontDoorDuty duty = new FrontDoorDuty(seat::door, role, log);
        duty.every(1_000, duty::guestTick);
        return duty;
    }

    /** How many paired guests outrank {@code id}: those with a lower engine id. */
    static int outranking(Path guests, String id) {
        int n = 0;
        for (String other : GuestPairing.read(guests).keySet()) {
            if (other.compareTo(id) < 0) n++;
        }
        return n;
    }

    private long lastBeat;

    /** Runs {@code step} every {@code ms} until closed, on one engine-lifetime virtual thread. */
    private void every(long ms, Runnable step) {
        // Engine-lifetime front-door loop; reads no session.
        SessionContext.startVirtual("jk-front-door", () -> {
            while (!closed) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (!closed) step.run();
                } catch (RuntimeException e) {
                    log.accept("jk engine: front door: " + e);
                }
            }
        });
    }

    private void guestTick() {
        FrontDoorRole r = role;
        if (r == null || closed) return;
        try {
            r.tick();
            long now = Clock.SYSTEM.millis();
            if (r.holding() && now - lastBeat >= FrontDoorRole.HEARTBEAT_MS) {
                lastBeat = now;
                beat();
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: front door: " + e);
        }
    }

    private void beat() {
        if (closed) return;
        FrontDoor d = door.get();
        if (d != null) d.tell("present");
    }

    @Override
    public void close() {
        closed = true;
        FrontDoorRole r = role;
        boolean front = r == null || r.holding();
        FrontDoor d = door.get();
        if (front && d != null) d.tell("leaving");
        if (r != null && r.holding()) r.stepAside();
    }

    /** The guest's 8910: a front-door-only listener, checked from Windows with a fresh nonce. */
    private static final class Seat implements FrontDoorRole.Seat {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final Duration VERIFY_WAIT = Duration.ofSeconds(20);

        private final Listener listener;
        private final @Nullable WslWindowsDesktop windows;
        private volatile @Nullable HttpEngineServer front;

        private final Consumer<String> log;

        Seat(Listener listener, @Nullable WslWindowsDesktop windows, Consumer<String> log) {
            this.listener = listener;
            this.windows = windows;
            this.log = log;
        }

        @Nullable
        FrontDoor door() {
            HttpEngineServer f = front;
            return f == null ? null : f.frontDoor();
        }

        @Override
        public boolean take() {
            HttpEngineServer f = listener.create();
            try {
                f.start(1);
            } catch (BindException taken) {
                f.close();
                return false;
            } catch (IOException | RuntimeException e) {
                f.close();
                return false;
            }
            front = f;
            return true;
        }

        @Override
        public void release() {
            HttpEngineServer f = front;
            front = null;
            if (f != null) f.stopNow();
        }

        @Override
        public FrontDoorRole.Reach verify() {
            HttpEngineServer f = front;
            WslWindowsDesktop w = windows;
            if (f == null) return FrontDoorRole.Reach.UNREACHABLE;
            if (w == null) return FrontDoorRole.Reach.UNKNOWN;
            byte[] bytes = new byte[12];
            RANDOM.nextBytes(bytes);
            String nonce = Hashing.hex(bytes);
            f.setIdentityNonce(nonce);
            String out = probeWindows(w, f.port());
            FrontDoorRole.Reach r = reach(out, nonce);
            if (r == FrontDoorRole.Reach.UNREACHABLE) {
                String answer = out == null ? "" : out.strip().replaceAll("\\s+", " ");
                log.accept("jk engine: Windows' 127.0.0.1:" + f.port() + " answered: "
                        + answer.substring(0, Math.min(160, answer.length())));
            }
            return r;
        }
    }

    /**
     * What Windows' {@code 127.0.0.1:<port>} answers {@code GET /api/identity}, asked through
     * PowerShell from this WSL guest ({@link #probeScript}); null when PowerShell could not run.
     */
    static @Nullable String probeWindows(WslWindowsDesktop windows, int port) {
        try {
            // Standard output alone: PowerShell writes progress and errors as CLIXML on stderr.
            Processes.Captured c = Processes.capture(
                    WslInterop.builder(windows.command(PowerShell.command(probeScript(port)))), Seat.VERIFY_WAIT);
            return c.timedOut() || c.exit() != 0 ? null : c.output();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * What {@link #probeScript} printed, read against {@code nonce}: the identity echoing it is this
     * guest; a refusal or any other answer is not; no output, a timeout or an error says nothing.
     */
    static FrontDoorRole.Reach reach(@Nullable String out, String nonce) {
        if (out == null) return FrontDoorRole.Reach.UNKNOWN;
        String s = out.strip();
        // PowerShell ran: a refusal, or a loopback connection that never completes, is an answer.
        if (s.startsWith("REFUSED") || s.startsWith("TIMEOUT")) return FrontDoorRole.Reach.UNREACHABLE;
        if (s.isEmpty() || s.startsWith("ERROR")) return FrontDoorRole.Reach.UNKNOWN;
        int body = s.indexOf("\r\n\r\n");
        String rest = body < 0 ? s : s.substring(body + 4);
        int open = rest.indexOf('{');
        int close = rest.lastIndexOf('}');
        String json = open < 0 || close < open ? rest.strip() : rest.substring(open, close + 1);
        try {
            if (MiniJson.parse(json) instanceof Map<?, ?> m && nonce.equals(m.get("nonce"))) {
                return FrontDoorRole.Reach.REACHABLE;
            }
        } catch (RuntimeException notJson) {
            // another listener's answer
        }
        return FrontDoorRole.Reach.UNREACHABLE;
    }

    /**
     * PowerShell that every Windows has: a TCP connection to Windows' {@code 127.0.0.1:<port>} and an
     * HTTP/1.0 {@code GET /api/identity}, printing the answer, or REFUSED, TIMEOUT or ERROR.
     */
    static String probeScript(int port) {
        // wslrelay starts forwarding a guest's new listener about a second after the bind: a refusal
        // is tried again for a few seconds before it counts.
        return "$ErrorActionPreference = 'Stop'\n"
                + "$ProgressPreference = 'SilentlyContinue'\n"
                + "for ($i = 0; $i -lt 8; $i++) {\n"
                + "  try {\n"
                + "    $c = New-Object Net.Sockets.TcpClient\n"
                + "    $t = $c.ConnectAsync('127.0.0.1', " + port + ")\n"
                + "    if (-not $t.Wait(5000)) { 'TIMEOUT'; exit 0 }\n"
                + "    $s = $c.GetStream(); $s.ReadTimeout = 5000\n"
                + "    $q = [Text.Encoding]::ASCII.GetBytes(\"GET /api/identity HTTP/1.0`r`nHost: 127.0.0.1:" + port
                + "`r`n`r`n\")\n"
                + "    $s.Write($q, 0, $q.Length)\n"
                + "    $r = New-Object IO.StreamReader($s)\n"
                + "    $r.ReadToEnd()\n"
                + "    $c.Close()\n"
                + "    exit 0\n"
                + "  } catch {\n"
                + "    $e = $_.Exception; while ($e.InnerException) { $e = $e.InnerException }\n"
                + "    if (-not ($e -is [Net.Sockets.SocketException])) { 'ERROR'; exit 0 }\n"
                + "    Start-Sleep -Milliseconds 500\n"
                + "  }\n"
                + "}\n"
                + "'REFUSED'\n";
    }
}
