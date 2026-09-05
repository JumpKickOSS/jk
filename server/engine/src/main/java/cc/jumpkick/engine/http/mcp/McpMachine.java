// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.config.EffectiveUserConfig;
import cc.jumpkick.config.NerdFontMode;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.verbs.CacheMaintenanceLocks;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInstallListener;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkService;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Config, disk, and doctor snapshots for MCP. */
public final class McpMachine {

    private static final Pattern HEAP_LINE = Pattern.compile("(?m)^([ \\t]*)max-heap-mb[ \\t]*=[ \\t]*\\d+[ \\t]*$");

    private McpMachine() {}

    /**
     * Installed jkx tools ({@code jk tool list} facts): env dirs under
     * {@code <state>/tools/envs}, coordinate + provenance from each {@code env.json}, launcher
     * presence under the platform bin dir. Same fields the CLI table renders — never the CAS.
     */
    public static Map<String, Object> tools() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Path envsRoot = JkDirs.state().resolve("tools").resolve("envs");
        Path binDir = JkDirs.binDir();
        if (Files.isDirectory(envsRoot)) {
            List<Path> envs = new ArrayList<>();
            try (var stream = Files.list(envsRoot)) {
                stream.filter(Files::isDirectory).forEach(envs::add);
            } catch (IOException e) {
                m.put("error", Errors.text(e));
                return m;
            }
            envs.sort(Comparator.comparing(pth -> pth.getFileName().toString()));
            for (Path envDir : envs) {
                String bin = envDir.getFileName().toString();
                Path envJson = envDir.resolve("env.json");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tool", bin);
                row.put("coord", envField(envJson, "primary"));
                String kind = envField(envJson, "kind");
                String spec = envField(envJson, "spec");
                if (kind != null && spec != null) row.put("source", kind + " " + spec);
                row.put("onPath", Files.exists(binDir.resolve(bin)));
                rows.add(row);
            }
        }
        m.put("tools", rows);
        return m;
    }

    private static @Nullable String envField(Path envJson, String field) {
        if (!Files.isRegularFile(envJson)) return null;
        try {
            // env.json is pretty-printed — parse properly, never compact-form key scans.
            Object parsed = MiniJson.parse(Files.readString(envJson, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> map && map.get(field) instanceof String s) return s;
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> configGet() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EffectiveUserConfig.Row r : EffectiveUserConfig.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.key());
            row.put("default", r.defaultValue());
            row.put("value", r.effectiveValue());
            row.put("overridden", r.overridden());
            rows.add(row);
        }
        m.put("rows", rows);
        return m;
    }

    public static Map<String, Object> configSet(String key, @Nullable String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            if ("nerd-font".equals(key)) {
                NerdFontMode mode = parseNerd(value);
                Path written = UserConfigEditor.setNerdFont(JkDirs.userConfigFile(), mode);
                m.put("written", written.toString());
                m.put("key", key);
                m.put("value", mode.toToml());
                return m;
            }
            if ("engine.max-heap-mb".equals(key)) {
                if (value == null) throw new McpError(-32602, "jk_config set " + key + " requires value");
                int mb = Integer.parseInt(value.trim());
                Path file = JkDirs.userConfigFile();
                String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
                AtomicWrites.replace(file, upsertHeap(text, mb));
                m.put("written", file.toString());
                m.put("key", key);
                m.put("value", mb);
                m.put("note", "engine restart required");
                return m;
            }
            m.put("error", "unknown key (nerd-font | engine.max-heap-mb)");
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    public static Map<String, Object> applyCiPreset() {
        Map<String, Object> heap = configSet("engine.max-heap-mb", "512");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heap", heap);
        m.put("env", List.of("JK_AOT_TRAIN=off"));
        m.put("note", "Set JK_AOT_TRAIN=off in the CI environment. Heap written to config.toml.");
        return m;
    }

    /**
     * Cache-tier vs artifact-store bytes for {@code jk_disk} / {@code jk_doctor} / {@code
     * jk://disk}. Reads the shared {@link CacheSnapshot} supplier (memoized single-flight walk,
     * same exclusive CAS-first accounting as {@code GET /api/cache}); a fresh capture only when
     * no supplier is wired.
     */
    public static Map<String, Object> diskUsage(@Nullable Supplier<CacheSnapshot> cache) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            CacheSnapshot snap = cache == null ? null : cache.get();
            if (snap == null) snap = CacheSnapshot.capture(JkDirs.cache());
            m.put("cacheDir", JkDirs.cache().toString());
            m.put("cacheBytes", snap.actionCacheBytes());
            m.put("storeDir", JkDirs.store().toString());
            m.put("storeBytes", snap.artifactStorageBytes());
            m.put("hint", "jk_disk action=clean then nuke if you still need space");
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    public static Map<String, Object> jdkList() {
        return jdkList(new JdkRegistry());
    }

    static Map<String, Object> jdkList(JdkRegistry registry) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (InstalledJdk jdk : registry.list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("home", jdk.home().toString());
                row.put("identifier", jdk.identifier());
                rows.add(row);
            }
            m.put("jdks", rows);
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    public static Map<String, Object> jdkAction(
            String action, @Nullable String spec, @Nullable Integer olderThan, boolean confirm) {
        return jdkAction(action, spec, olderThan, confirm, new JdkRegistry());
    }

    static Map<String, Object> jdkAction(
            String action, @Nullable String spec, @Nullable Integer olderThan, boolean confirm, JdkRegistry registry) {
        if (action == null || action.isBlank() || "list".equals(action)) return jdkList(registry);
        if ("install".equals(action) || "update".equals(action)) {
            return jdkInstall(spec, registry);
        }
        if ("uninstall".equals(action)) {
            return jdkUninstall(spec, olderThan, confirm, registry);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "unknown action (list | install | uninstall)");
        return m;
    }

    static Map<String, Object> jdkInstall(@Nullable String spec, JdkRegistry registry) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            m.put("error", "jk_jdk install requires spec (lts, latest, 26, temurin-26)");
            return m;
        }
        if (!HostPlatform.supported()) {
            m.put("error", "host is not covered by the JetBrains JDK feed — set JAVA_HOME");
            return m;
        }
        try {
            JdkInstaller.sweepStaleDownloads(registry.jdksRoot());
            InstalledJdk jdk = new JdkService()
                    .install(
                            spec,
                            registry,
                            false,
                            null,
                            null,
                            HostPlatform.currentOs(),
                            HostPlatform.currentArch(),
                            JdkInstallListener.NO_OP);
            m.put("identifier", jdk.identifier());
            m.put("home", jdk.home().toString());
            m.put("installed", true);
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    static Map<String, Object> jdkUninstall(
            @Nullable String spec, @Nullable Integer olderThan, boolean confirm, JdkRegistry registry) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            JdkInstaller.sweepStaleDownloads(registry.jdksRoot());
            List<Map<String, Object>> victims = new ArrayList<>();
            if (olderThan != null) {
                for (JdkHit hit : registry.listHits()) {
                    if (!"jk".equals(hit.source()) && !"jdks".equals(hit.source())) continue;
                    Integer major = majorOf(hit.version());
                    if (major == null || major >= olderThan) continue;
                    if (!JdkOwnership.isJkOwnedJavaHome(hit.home())) continue;
                    victims.add(jdkVictim(hit));
                }
            } else if (spec != null && !spec.isBlank()) {
                var hit = registry.findHitBySpec(spec, "jk");
                if (hit.isEmpty()) hit = registry.findHitBySpec(spec, null);
                if (hit.isEmpty()) {
                    m.put("error", "no installed JDK matches " + spec);
                    return m;
                }
                if (!JdkOwnership.isJkOwnedJavaHome(hit.get().home())) {
                    m.put("error", "refusing to uninstall a JDK jk does not own — use jk jdk uninstall");
                    return m;
                }
                victims.add(jdkVictim(hit.get()));
            } else {
                m.put("error", "jk_jdk uninstall requires spec or older_than");
                return m;
            }
            m.put("victims", victims);
            if (!confirm) {
                m.put("preview", true);
                m.put("note", "pass confirm=true to uninstall");
                return m;
            }
            List<String> removed = new ArrayList<>();
            for (Map<String, Object> v : victims) {
                String id = String.valueOf(v.get("identifier"));
                if (registry.remove(id)) removed.add(id);
            }
            m.put("removed", removed);
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    private static Map<String, Object> jdkVictim(JdkHit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("identifier", JdkRegistry.identifierFor(hit.home()));
        row.put("home", hit.home().toString());
        row.put("version", hit.version());
        row.put("source", hit.source());
        return row;
    }

    static @Nullable Integer majorOf(@Nullable String version) {
        if (version == null || version.isEmpty()) return null;
        String s = version;
        int dash = s.lastIndexOf('-');
        if (dash >= 0 && dash < s.length() - 1 && Character.isDigit(s.charAt(dash + 1))) {
            s = s.substring(dash + 1);
        }
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return null;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Map<String, Object> diskAction(
            String action, boolean confirm, @Nullable ReentrantReadWriteLock cacheGate) {
        return diskAction(action, confirm, JkDirs.cache(), cacheGate);
    }

    /**
     * {@code clean}/{@code nuke} take the same exclusive locks as the wire cache verb (engine
     * {@code cacheGate} write + cross-process {@code .prune.lock}); when either is busy the tool
     * refuses instead of deleting under an in-flight build or a concurrent prune.
     */
    static Map<String, Object> diskAction(
            String action, boolean confirm, Path cache, @Nullable ReentrantReadWriteLock cacheGate) {
        if (action == null || action.isBlank() || "usage".equals(action)) return diskUsage(null);
        Map<String, Object> preview = diskUsageOf(cache);
        if ("clean".equals(action) || "nuke".equals(action)) {
            if (!confirm) {
                preview.put("preview", true);
                preview.put("note", "pass confirm=true to " + action + " (cache tier only for nuke)");
                return preview;
            }
            try {
                boolean ran = CacheMaintenanceLocks.tryExclusively(cacheGate, cache, () -> {
                    if ("nuke".equals(action)) {
                        CachePlans.purgeActionCache(cache);
                        preview.put("nuked", true);
                        preview.put("note", "cache tier wiped; artifact store untouched");
                    } else {
                        var plan = CachePlans.pruneBuildPlan(cache, false, true);
                        var result = plan.run();
                        preview.put("cleaned", result.success());
                        preview.put("files", plan.get(CachePlans.FILES).orElse(-1L));
                        preview.put("bytes", plan.get(CachePlans.BYTES).orElse(-1L));
                        if (result.success()) {
                            CacheMaintenanceLocks.stampLastPruned(
                                    cache,
                                    System.currentTimeMillis(),
                                    plan.get(CachePlans.FINAL_ACTION_BYTES).orElse(-1L));
                        } else {
                            preview.put("error", "cache clean failed");
                        }
                    }
                });
                if (!ran) {
                    preview.put("error", "cache is busy (build in flight or another prune) — retry when idle");
                    return preview;
                }
                Map<String, Object> after = diskUsageOf(cache);
                preview.put("cacheBytesAfter", after.get("cacheBytes"));
                return preview;
            } catch (Exception e) {
                preview.put("error", Errors.text(e));
                return preview;
            }
        }
        preview.put("error", "unknown action (usage | clean | nuke)");
        return preview;
    }

    static Map<String, Object> diskUsageOf(Path cache) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path store = JkDirs.store();
            DiskUsage.Stats cs = DiskUsage.of(cache);
            DiskUsage.Stats ss = DiskUsage.of(store);
            m.put("cacheDir", cache.toString());
            m.put("cacheBytes", cs.bytes());
            m.put("storeDir", store.toString());
            m.put("storeBytes", ss.bytes());
        } catch (Exception e) {
            m.put("error", Errors.text(e));
        }
        return m;
    }

    public static Map<String, Object> doctor(@Nullable Supplier<CacheSnapshot> cache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", configGet());
        m.put("disk", diskUsage(cache));
        return m;
    }

    static String upsertHeap(String toml, int mb) {
        if (toml == null) toml = "";
        Matcher m = HEAP_LINE.matcher(toml);
        if (m.find()) return m.replaceFirst(m.group(1) + "max-heap-mb = " + mb);
        if (toml.contains("[engine]")) {
            return toml.replaceFirst("(?m)^\\[engine]\\s*$", "[engine]\nmax-heap-mb = " + mb);
        }
        String block = "[engine]\nmax-heap-mb = " + mb + "\n";
        return toml.isBlank() ? block : toml.stripTrailing() + "\n\n" + block;
    }

    private static NerdFontMode parseNerd(@Nullable String value) {
        return NerdFontMode.parse(value).orElse(NerdFontMode.ON);
    }
}
