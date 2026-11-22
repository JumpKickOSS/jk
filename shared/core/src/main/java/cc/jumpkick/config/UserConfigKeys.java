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
import java.util.List;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Every key this jk reads from {@code ~/.jk/config.toml}, and the keys a file holds that it does not.
 * A key jk does not know is reported and ignored, never translated: {@code jk doctor} warns on its
 * config row, the dashboard's Configuration panel lists it, and a command says so once per key.
 *
 * <p>The keys are the readers' own: each reader declares the keys it reads in a {@link
 * UserConfigTable} and reads through them, and {@link #TABLES} lists those tables. {@code
 * [image]} is the manifest's own table, so its keys come from {@code ManifestImage.KEYS}. {@code
 * UserConfigKeysTest} fails when a table is declared and not listed here, when a key is declared
 * twice, or when a reader reads a key no table declares.
 */
public final class UserConfigKeys {

    private UserConfigKeys() {}

    /** Every table a reader of the file declares. */
    static final List<UserConfigTable> TABLES = List.of(
            GlobalConfig.ROOT,
            GlobalConfig.TOOLCHAIN,
            GlobalConfig.UPDATES,
            GlobalConfig.CREDENTIALS,
            GlobalConfig.RELEASE,
            GlobalConfig.NODE,
            GlobalConfig.NODE_SCOPES,
            GlobalConfig.MIRRORS,
            GlobalConfig.REPOSITORIES,
            GlobalConfig.WINDOWS,
            GlobalConfig.WSL,
            JkConfigLoader.CONFIG,
            EngineControls.ENGINE,
            JkHttpConfig.HTTP,
            JkHttpConfig.MCP,
            SessionExpiry.MCP,
            JkCacheConfig.CACHE,
            HeavyTtl.CACHE,
            JkHistoryConfig.HISTORY,
            JkM2Config.M2,
            JkInstallConfig.INSTALL,
            JkTemplatesConfig.TEMPLATES,
            JkTemplatesConfig.SOURCES,
            NetworkConfig.NETWORK,
            ReadUsage.AGENTS,
            UiAsk.UI,
            UserPlugins.PLUGINS,
            ForgeAuthConfig.FORGE);

    /** Keys known exactly, each as its path of bare keys. */
    static final Set<List<String>> EXACT = Set.copyOf(declared());

    /** Tables under which any key is known: their entries are names the user chooses. */
    static final Set<List<String>> OPEN = Set.copyOf(TABLES.stream()
            .filter(UserConfigTable::isOpen)
            .map(UserConfigTable::path)
            .toList());

    /** Every declared key, in declaration order, duplicates kept so a test can name them. */
    static List<List<String>> declared() {
        List<List<String>> out = new ArrayList<>();
        for (UserConfigTable table : TABLES) {
            for (UserConfigTable.Key key : table.keys()) out.add(key.path());
        }
        ManifestImage.KEYS.forEach(k -> out.add(List.of("image", k)));
        return out;
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
