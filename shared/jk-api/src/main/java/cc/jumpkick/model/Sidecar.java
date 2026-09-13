// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One {@code [dev.sidecars]} entry as the manifest states it: {@code command} already split
 * into argv, {@code cwd} module-relative, {@code env} literal values laid over the inherited
 * environment. {@code ready} is the sidecar's readiness probe — {@code ready} /
 * {@code ready-pattern} / {@code ready-timeout}, the same {@link DevReady} the application's own
 * {@code [dev]} probe is — and {@code null} means "ready once it has stayed alive for a second".
 * {@code frontDoor} names the URL {@code jk dev} prints once everything is ready.
 */
public record Sidecar(
        String name,
        List<String> command,
        String cwd,
        Map<String, String> env,
        @Nullable DevReady ready,
        boolean frontDoor,
        Restart restart) {

    public Sidecar {
        Objects.requireNonNull(name, "name");
        command = List.copyOf(command);
        if (command.isEmpty()) throw new IllegalArgumentException("sidecar `" + name + "` has an empty command");
        cwd = cwd == null || cwd.isBlank() ? "." : cwd;
        env = env == null || env.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        restart = restart == null ? Restart.NEVER : restart;
    }

    /** What {@code jk dev} does when a sidecar exits on its own. */
    public enum Restart {
        /** Report the exit once and carry on without it. */
        NEVER,
        /** Start it again with backoff; give up after five failures in a row. */
        ON_EXIT;

        /** The manifest spelling — {@code never} or {@code on-exit} — which is also the wire spelling. */
        public String manifestValue() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public static Restart parse(String raw) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (Restart r : values()) {
                if (r.manifestValue().equals(value)) return r;
            }
            throw new IllegalArgumentException("restart is never or on-exit, not `" + raw + "`");
        }
    }
}
