// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.config.EffectiveUserConfig;
import cc.jumpkick.config.NerdFontMode;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.util.JkDirs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Config, disk, and doctor snapshots for MCP. */
public final class McpMachine {

    private static final Pattern HEAP_LINE = Pattern.compile("(?m)^([ \\t]*)max-heap-mb[ \\t]*=[ \\t]*\\d+[ \\t]*$");

    private McpMachine() {}

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

    public static Map<String, Object> configSet(String key, String value) {
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
                int mb = Integer.parseInt(value.trim());
                Path file = JkDirs.userConfigFile();
                String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
                Files.writeString(file, upsertHeap(text, mb), StandardCharsets.UTF_8);
                m.put("written", file.toString());
                m.put("key", key);
                m.put("value", mb);
                m.put("note", "engine restart required");
                return m;
            }
            m.put("error", "unknown key (nerd-font | engine.max-heap-mb)");
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
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

    public static Map<String, Object> diskUsage() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path cache = JkDirs.cache();
            Path store = JkDirs.store();
            DiskUsage.Stats cs = DiskUsage.of(cache);
            DiskUsage.Stats ss = DiskUsage.of(store);
            m.put("cacheDir", cache.toString());
            m.put("cacheBytes", cs.bytes());
            m.put("storeDir", store.toString());
            m.put("storeBytes", ss.bytes());
            m.put("hint", "jk_disk action=clean then nuke if you still need space");
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }

    public static Map<String, Object> jdkList() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (cc.jumpkick.jdk.InstalledJdk jdk : new cc.jumpkick.jdk.JdkRegistry().list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("home", jdk.home().toString());
                row.put("identifier", jdk.identifier());
                rows.add(row);
            }
            m.put("jdks", rows);
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }

    public static Map<String, Object> doctor() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", configGet());
        m.put("disk", diskUsage());
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

    private static NerdFontMode parseNerd(String value) {
        return NerdFontMode.parse(value).orElse(NerdFontMode.ON);
    }
}
