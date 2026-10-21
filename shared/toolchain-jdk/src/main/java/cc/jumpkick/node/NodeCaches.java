// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.host.CacheTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where each package manager keeps its downloads when jk runs it, and the environment that points
 * it there: npm's cache, bun's install cache and Yarn's global folder under jk's cache root, pnpm's
 * store under jk's store. Every Node process jk starts (scaffold, build, the {@code jk node}
 * commands) takes these variables from here.
 */
public final class NodeCaches {

    private static final Pattern YARN_GLOBAL_FOLDER = Pattern.compile("^globalFolder\\s*:.*\\S.*");
    private static final Pattern PNPM_STORE_DIR = Pattern.compile("^storeDir\\s*:.*\\S.*");

    private NodeCaches() {}

    /** npm's cache under jk's cache root, shared by every npm jk runs. */
    public static Path npmCache(Path cacheRoot) {
        return CacheTree.NPM.under(cacheRoot);
    }

    /** pnpm's content-addressed store under jk's store root. */
    public static Path pnpmStore(Path storeRoot) {
        return storeRoot.resolve("pnpm-store");
    }

    /**
     * The variables a process jk owns runs with: npm's cache under every manager ({@code npx} and
     * {@code npm exec} read it), then the manager's own.
     */
    public static Map<String, String> env(PackageManager manager, Path cacheRoot, Path storeRoot) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (Setting s : settings(manager, cacheRoot, storeRoot)) vars.putAll(s.vars());
        return vars;
    }

    /**
     * {@link #env} for a command the user runs through jk, less each cache the user configured
     * themselves: a variable of that name in {@code inherited} (any case), or the manager's own
     * config file in {@code dir}, one of its parents or {@code userHome} ({@code .npmrc} {@code
     * cache} / {@code store-dir}, {@code pnpm-workspace.yaml} {@code storeDir}, {@code .yarnrc.yml}
     * {@code globalFolder}, {@code bunfig.toml} / {@code ~/.bunfig.toml} {@code [install.cache]
     * dir}).
     */
    public static Map<String, String> passthroughEnv(
            PackageManager manager,
            Path cacheRoot,
            Path storeRoot,
            Path dir,
            Map<String, String> inherited,
            Path userHome) {
        UserConfig user = new UserConfig(dir, inherited, userHome);
        Map<String, String> vars = new LinkedHashMap<>();
        for (Setting s : settings(manager, cacheRoot, storeRoot)) {
            if (!s.userSet(user)) vars.putAll(s.vars());
        }
        return vars;
    }

    /** One cache: the variables that point the manager at jk's, and whether the user set their own. */
    private record Setting(Map<String, String> vars, Kind kind) {

        boolean userSet(UserConfig user) {
            for (String key : vars.keySet()) {
                if (user.env(key)) return true;
            }
            return switch (kind) {
                case NPM -> user.npmrc("cache");
                case PNPM -> user.npmrc("store-dir") || user.matches("pnpm-workspace.yaml", PNPM_STORE_DIR);
                case YARN -> user.matches(".yarnrc.yml", YARN_GLOBAL_FOLDER);
                case BUN -> user.bunfig();
            };
        }
    }

    private enum Kind {
        NPM,
        PNPM,
        YARN,
        BUN
    }

    // npm warns about every config it does not know, so each manager's key goes only to that manager.
    private static List<Setting> settings(PackageManager manager, Path cacheRoot, Path storeRoot) {
        List<Setting> out = new ArrayList<>(2);
        out.add(new Setting(Map.of("npm_config_cache", npmCache(cacheRoot).toString()), Kind.NPM));
        switch (manager) {
            case PNPM -> {
                // pnpm 11 and later read only their own pnpm_config_* keys; older ones the npm_config_* one.
                String store = pnpmStore(storeRoot).toString();
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("pnpm_config_store_dir", store);
                vars.put("npm_config_store_dir", store);
                out.add(new Setting(vars, Kind.PNPM));
            }
            case BUN ->
                out.add(new Setting(
                        Map.of(
                                "BUN_INSTALL_CACHE_DIR",
                                CacheTree.BUN.under(cacheRoot).toString()),
                        Kind.BUN));
            case YARN ->
                out.add(new Setting(
                        Map.of(
                                "YARN_GLOBAL_FOLDER",
                                CacheTree.YARN.under(cacheRoot).toString()),
                        Kind.YARN));
            case NPM -> {}
        }
        return out;
    }

    /** What the user configured: the inherited environment and the config files a manager reads. */
    private record UserConfig(Path dir, Map<String, String> inherited, Path userHome) {

        /** {@code key} set non-blank in the environment, in any case: npm reads its keys so. */
        boolean env(String key) {
            return envValue(key) != null;
        }

        /** {@code key} in a project {@code .npmrc} at or above {@code dir}, or the user's. */
        boolean npmrc(String key) {
            List<Path> files = new ArrayList<>(projectFiles(".npmrc"));
            String userConfig = envValue("npm_config_userconfig");
            files.add(userConfig != null ? Path.of(userConfig) : userHome.resolve(".npmrc"));
            for (Path f : files) {
                for (String line : lines(f)) {
                    String l = line.strip();
                    if (l.startsWith(";") || l.startsWith("#")) continue;
                    int eq = l.indexOf('=');
                    if (eq > 0
                            && l.substring(0, eq).strip().equals(key)
                            && !l.substring(eq + 1).isBlank()) return true;
                }
            }
            return false;
        }

        /** A top-level line matching {@code key} in {@code name} at or above {@code dir}, or in the home. */
        boolean matches(String name, Pattern key) {
            List<Path> files = new ArrayList<>(projectFiles(name));
            files.add(userHome.resolve(name));
            for (Path f : files) {
                for (String line : lines(f)) {
                    if (key.matcher(line).matches()) return true;
                }
            }
            return false;
        }

        /** bun's cache directory in a {@code bunfig.toml} at or above {@code dir}, or {@code ~/.bunfig.toml}. */
        boolean bunfig() {
            List<Path> files = new ArrayList<>(projectFiles("bunfig.toml"));
            files.add(userHome.resolve(".bunfig.toml"));
            for (Path f : files) {
                String section = "";
                for (String line : lines(f)) {
                    String l = line.strip();
                    if (l.startsWith("[")) {
                        section = l.replaceAll("[\\[\\]\\s]", "");
                        continue;
                    }
                    int eq = l.indexOf('=');
                    if (eq <= 0) continue;
                    String key = l.substring(0, eq).strip();
                    String value = l.substring(eq + 1).strip();
                    String full = section.isEmpty() ? key : section + "." + key;
                    if (full.equals("install.cache.dir")) return true;
                    if (full.equals("install.cache") && (value.startsWith("\"") || value.startsWith("'"))) return true;
                }
            }
            return false;
        }

        private @Nullable String envValue(String key) {
            for (Map.Entry<String, String> e : inherited.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key) && !e.getValue().isBlank()) return e.getValue();
            }
            return null;
        }

        private List<Path> projectFiles(String name) {
            List<Path> out = new ArrayList<>();
            for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) out.add(d.resolve(name));
            return out;
        }

        private static List<String> lines(Path file) {
            if (!Files.isRegularFile(file)) return List.of();
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return List.of();
            }
        }
    }
}
