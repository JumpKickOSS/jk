// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import java.util.List;
import java.util.function.Consumer;

/**
 * The two ways a {@code jk dev} session shows what its processes say. On a terminal the app's lines
 * are its own — it is the module being developed — and every sidecar line carries {@code name │ }
 * in a colour picked from the name, so two sidecars never share one and the same name is the same
 * colour in every session. Under {@code --output json} every line and every lifecycle change is
 * one JSONL object with its source, so a consumer never has to guess who printed what.
 */
public final class SidecarOutput {

    /** Distinguishable foregrounds on light and dark backgrounds; a name hashes into one of them. */
    static final List<int[]> PALETTE = List.of(
            new int[] {0x56, 0xB6, 0xC2}, // cyan
            new int[] {0xC6, 0x78, 0xDD}, // violet
            new int[] {0xE5, 0xC0, 0x7B}, // gold
            new int[] {0x98, 0xC3, 0x79}, // green
            new int[] {0x61, 0xAF, 0xEF}, // blue
            new int[] {0xD1, 0x9A, 0x66}); // orange

    /** Wire {@code type} of each event; the field names follow {@code docs/user/machine-output.md}. */
    static final String SIDECAR_STARTED = "sidecar-started";

    static final String SIDECAR_OUTPUT = "sidecar-output";
    static final String SIDECAR_READY = "sidecar-ready";
    static final String SIDECAR_EXITED = "sidecar-exited";
    static final String APP_STARTED = "app-started";
    static final String APP_OUTPUT = "app-output";
    static final String APP_EXITED = "app-exited";
    static final String DEV_READY = "dev-ready";

    private SidecarOutput() {}

    /** The terminal listener under the session's colour gate ({@code --no-ansi}, {@code JK_NO_ANSI}, {@code NO_COLOR}). */
    public static Sidecars.Listener terminal(Consumer<String> sink) {
        return terminal(sink, GlobalConfig.colorEnabled());
    }

    /** The terminal listener with colour decided by the caller. */
    static Sidecars.Listener terminal(Consumer<String> sink, boolean colour) {
        return new Terminal(sink, colour);
    }

    /** One JSONL object per line and lifecycle change, handed to {@code sink} already encoded and stamped by {@code clock}. */
    public static Sidecars.Listener jsonl(Consumer<String> sink, Clock clock) {
        return new Jsonl(sink, clock);
    }

    /**
     * Every call to {@code first}, then to {@code second}: on a terminal the prefixed line for the
     * user and the event for the session transcript come from the same call.
     */
    public static Sidecars.Listener both(Sidecars.Listener first, Sidecars.Listener second) {
        return new Both(first, second);
    }

    /** {@code name │ } — the name in its colour when colour is on, the bar plain either way. */
    static String prefix(String name, boolean colour) {
        return (colour ? style(name).render(name) : name) + Sidecars.PREFIX_SEPARATOR;
    }

    /** Stable across sessions and machines: {@link String#hashCode} is specified, not incidental. */
    static Style style(String name) {
        int[] rgb = PALETTE.get(Math.floorMod(name.hashCode(), PALETTE.size()));
        return Style.EMPTY.foreground(rgb[0], rgb[1], rgb[2]);
    }

    /** The app's own {@code app-started} line. */
    public static String appStarted(Clock clock, long pid) {
        return JsonlEnvelope.open(clock.millis(), APP_STARTED)
                .number("pid", pid)
                .finish();
    }

    /** The app's own {@code app-output} line; {@code stream} is {@code stdout} or {@code stderr}. */
    public static String appOutput(Clock clock, String stream, String line) {
        return JsonlEnvelope.open(clock.millis(), APP_OUTPUT)
                .string("stream", stream)
                .string("line", line)
                .finish();
    }

    /**
     * The terminal's word that the stack is up: {@code ready · <url> (<app>)} when a sidecar is the
     * front door, {@code ready · <app>} when the app itself is — the address is what a reader wants
     * first, and the app's command is the whole story when there is no address.
     */
    static String readyLine(String url, String app) {
        return url.isEmpty() ? "ready \u00b7 " + app : "ready \u00b7 " + url + " (" + app + ")";
    }

    /**
     * The session's {@code dev-ready} line: the stack is up. {@code url} is the front-door
     * sidecar's address, or empty when the app itself is the front door; {@code app} is the app's
     * command as displayed.
     */
    public static String devReady(Clock clock, String url, String app) {
        return JsonlEnvelope.open(clock.millis(), DEV_READY)
                .optionalNonEmptyString("url", url)
                .string("app", app)
                .finish();
    }

    /** The app's own {@code app-exited} line. */
    public static String appExited(Clock clock, long pid, int exit) {
        return JsonlEnvelope.open(clock.millis(), APP_EXITED)
                .number("pid", pid)
                .number("exit", exit)
                .finish();
    }

    private record Both(Sidecars.Listener first, Sidecars.Listener second) implements Sidecars.Listener {
        @Override
        public void started(String name, long pid) {
            first.started(name, pid);
            second.started(name, pid);
        }

        @Override
        public void output(String name, String stream, String line) {
            first.output(name, stream, line);
            second.output(name, stream, line);
        }

        @Override
        public void ready(String name, String url, boolean frontDoor) {
            first.ready(name, url, frontDoor);
            second.ready(name, url, frontDoor);
        }

        @Override
        public void exited(String name, long pid, int exit, long restartInMs, boolean gaveUp) {
            first.exited(name, pid, exit, restartInMs, gaveUp);
            second.exited(name, pid, exit, restartInMs, gaveUp);
        }

        @Override
        public void failed(String message) {
            first.failed(message);
            second.failed(message);
        }
    }

    private static final class Terminal implements Sidecars.Listener {
        private final Consumer<String> sink;
        private final boolean colour;

        Terminal(Consumer<String> sink, boolean colour) {
            this.sink = sink;
            this.colour = colour;
        }

        @Override
        public void started(String name, long pid) {
            // the watching line already said the session is up; a pid is noise on a terminal
        }

        @Override
        public void output(String name, String stream, String line) {
            sink.accept(prefix(name, colour) + line);
        }

        @Override
        public void ready(String name, String url, boolean frontDoor) {
            // the session prints one `ready ·` line once every probe has passed
        }

        @Override
        public void exited(String name, long pid, int exit, long restartInMs, boolean gaveUp) {
            String next = gaveUp
                    ? " — gave up after " + Sidecars.MAX_RESTARTS + " restarts"
                    : restartInMs >= 0 ? " — restarting in " + restartInMs + " ms" : "";
            sink.accept(name + " exited with " + exit + next);
        }

        @Override
        public void failed(String message) {
            sink.accept(message);
        }
    }

    private static final class Jsonl implements Sidecars.Listener {
        private final Consumer<String> sink;
        private final Clock clock;

        Jsonl(Consumer<String> sink, Clock clock) {
            this.sink = sink;
            this.clock = clock;
        }

        private JsonFields open(String type, String name) {
            return JsonlEnvelope.open(clock.millis(), type).string("name", name);
        }

        @Override
        public void started(String name, long pid) {
            sink.accept(open(SIDECAR_STARTED, name).number("pid", pid).finish());
        }

        @Override
        public void output(String name, String stream, String line) {
            sink.accept(open(SIDECAR_OUTPUT, name)
                    .string("stream", stream)
                    .string("line", line)
                    .finish());
        }

        @Override
        public void ready(String name, String url, boolean frontDoor) {
            sink.accept(open(SIDECAR_READY, name)
                    .optionalNonEmptyString("url", url)
                    .optionalTrue("frontDoor", frontDoor)
                    .finish());
        }

        @Override
        public void exited(String name, long pid, int exit, long restartInMs, boolean gaveUp) {
            sink.accept(open(SIDECAR_EXITED, name)
                    .number("pid", pid)
                    .number("exit", exit)
                    .optionalNumber("restartInMs", restartInMs, -1)
                    .optionalTrue("gaveUp", gaveUp)
                    .finish());
        }

        @Override
        public void failed(String message) {
            sink.accept(JsonlEnvelope.open(clock.millis(), "error")
                    .string("message", message)
                    .finish());
        }
    }
}
