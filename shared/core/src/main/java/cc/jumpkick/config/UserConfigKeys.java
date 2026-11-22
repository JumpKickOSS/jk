// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Every key this jk reads from {@code ~/.jk/config.toml}, and the keys a file holds that it does not.
 * A key jk does not know is reported and ignored, never translated: {@code jk doctor} warns on its
 * config row, the dashboard's Configuration panel lists it, and a command says so once per key.
 *
 * <p>The tables whose readers keep their own key lists ({@link EngineControls}, {@link
 * JkConfigLoader#KEYS}, {@code ManifestImage.KEYS}) are taken from them. {@code UserConfigKeysTest}
 * fails when a reader reads, or a page of {@code docs/user} shows, a key this list does not hold.
 */
public final class UserConfigKeys {

    private UserConfigKeys() {}

    /** Keys known exactly, each as its path of bare keys. */
    static final Set<List<String>> EXACT = Set.copyOf(exact());

    /** Tables under which any key is known: their entries are names the user chooses. */
    static final Set<List<String>> OPEN = Set.of(
            List.of("repositories"),
            List.of("mirrors"),
            List.of("plugins"),
            List.of("forge"),
            List.of("node", "scopes"),
            List.of("templates", "sources"));

    private static Set<List<String>> exact() {
        Set<List<String>> out = new LinkedHashSet<>();
        out.add(List.of("nerd-font"));
        out.add(List.of(SelfUpdatePolicy.KEY));
        JkConfigLoader.KEYS.forEach(k -> out.add(List.of("config", k)));
        EngineControls.TABLE.stream()
                .filter(c -> !c.toml().isEmpty())
                .forEach(c -> out.add(List.of("engine", c.toml())));
        table(
                out,
                "http",
                "enabled",
                "host",
                "port",
                "max-concurrent-requests",
                "max-event-streams",
                "web-root",
                "web-overlay");
        table(out, "mcp", "enabled", "max-event-streams", "max-sessions", "session-expiry", "tools");
        table(
                out,
                "cache",
                "auto-prune",
                "prune-interval-days",
                "max-cache-size-gb",
                "incremental-max-size-gb",
                "heavy-ttl",
                "jdk-match",
                "remote",
                "remote-write",
                "key-seed-file",
                "credential-env");
        table(out, "history", "enabled", "max-age-days", "max-disk-mb");
        table(out, "m2", "integration", "auto-clean", "clean-after-days");
        table(out, "install", "destination");
        table(out, "credentials", "store");
        table(out, "templates", "official");
        table(out, "toolchain", "jdk");
        table(out, "updates", "check");
        table(out, "release", "trusted-keys");
        table(out, "network", "proxy", "https-proxy", "no-proxy");
        table(out, "node", "registry");
        table(out, "agents", "read-usage");
        table(out, "ui", "ask");
        table(out, "windows", "fix");
        table(out, "wsl", "host-engine");
        ManifestImage.KEYS.forEach(k -> out.add(List.of("image", k)));
        return out;
    }

    private static void table(Set<List<String>> out, String table, String... keys) {
        for (String key : keys) out.add(List.of(table, key));
    }

    /** Whether {@code path} (bare keys, outermost first) is a key this jk reads. */
    public static boolean known(List<String> path) {
        if (EXACT.contains(path)) return true;
        for (int i = 1; i <= path.size(); i++) {
            if (OPEN.contains(path.subList(0, i))) return true;
        }
        // The values of [image] env, labels and training-env are maps of the user's own names.
        return path.size() > 2 && path.getFirst().equals("image") && EXACT.contains(path.subList(0, 2));
    }

    /**
     * The keys of {@code file} this jk does not read, dotted as TOML writes them, in file order.
     * Empty when the file is absent or does not parse: the parse error is reported on its own.
     */
    public static List<String> unknown(Path file) {
        return TomlValues.parse(file).map(UserConfigKeys::unknown).orElse(List.of());
    }

    static List<String> unknown(TomlParseResult toml) {
        List<String> out = new ArrayList<>();
        for (List<String> path : toml.keyPathSet(false)) {
            if (!known(path)) out.add(Toml.joinKeyPath(path));
        }
        return List.copyOf(out);
    }

    /**
     * The unknown keys of {@code file} no earlier call has returned on this machine, each recorded
     * under {@code stateDir} as it is returned, so a key is announced once. A key whose record
     * cannot be written is left out, so a read-only home stays quiet rather than repeating itself.
     */
    public static List<String> firstSeen(Path file, Path stateDir) {
        List<String> unknown = unknown(file);
        if (unknown.isEmpty()) return List.of();
        Path dir = stateDir.resolve("unknown-config-keys");
        List<String> fresh = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return List.of();
        }
        for (String key : unknown) {
            try {
                Files.writeString(
                        dir.resolve(marker(key)), key + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                fresh.add(key);
            } catch (FileAlreadyExistsException seen) {
                // Announced before.
            } catch (IOException e) {
                // Unrecordable: stay quiet.
            }
        }
        return List.copyOf(fresh);
    }

    /** One line naming {@code key} as unknown in {@code file}. */
    public static String notice(String key, Path file) {
        return key + ": unknown key in " + file + ", ignored";
    }

    private static String marker(String key) {
        return Hashing.sha256Hex(key).substring(0, 24);
    }
}
