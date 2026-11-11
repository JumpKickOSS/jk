// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JDK homes the Windows registry names under {@code HKEY_LOCAL_MACHINE}, the keys Gradle reads:
 * {@code JavaHome} under the JavaSoft keys, and {@code Path} at {@code <version>\hotspot\MSI} under
 * the AdoptOpenJDK, Eclipse Adoptium, and Eclipse Foundation keys.
 */
final class WindowsJavaRegistry {

    /** Keys whose subkeys carry a {@code JavaHome} value. */
    static final List<String> JAVA_HOME_KEYS = List.of(
            "SOFTWARE\\JavaSoft\\JDK",
            "SOFTWARE\\JavaSoft\\Java Development Kit",
            "SOFTWARE\\JavaSoft\\Java Runtime Environment",
            "SOFTWARE\\Wow6432Node\\JavaSoft\\Java Development Kit",
            "SOFTWARE\\Wow6432Node\\JavaSoft\\Java Runtime Environment");

    /** Keys whose {@code <version>\hotspot\MSI} subkeys carry a {@code Path} value. */
    static final List<String> MSI_PATH_KEYS = List.of(
            "SOFTWARE\\AdoptOpenJDK\\JDK", "SOFTWARE\\Eclipse Adoptium\\JDK", "SOFTWARE\\Eclipse Foundation\\JDK");

    private static final String MSI_SUFFIX = "\\hotspot\\msi";

    private static final long TIMEOUT_SECONDS = 5;

    /** Reads a registry value by name from every subkey of {@code key}, recursively. */
    @FunctionalInterface
    interface Reader {
        /** Subkey path (relative to {@code HKEY_LOCAL_MACHINE}) to value data; empty when the key is missing. */
        Map<String, String> values(String key, String valueName) throws IOException;
    }

    /** {@code reg query HKLM\<key> /s /v <name>}. */
    static final Reader REG_QUERY = WindowsJavaRegistry::regQuery;

    private WindowsJavaRegistry() {}

    /**
     * The roots {@link #JAVA_HOME_KEYS} sit under: one recursive query per root answers every key
     * beneath it, where a query per key started a process for each.
     */
    static final List<String> JAVA_HOME_ROOTS = List.of("SOFTWARE\\JavaSoft", "SOFTWARE\\Wow6432Node\\JavaSoft");

    /** Every home the registry names, in key order. */
    static List<Path> homes(Reader reader) throws IOException {
        List<Path> homes = new ArrayList<>();
        Map<String, String> javaHomes = new LinkedHashMap<>();
        for (String root : JAVA_HOME_ROOTS) javaHomes.putAll(reader.values(root, "JavaHome"));
        for (String key : JAVA_HOME_KEYS) {
            String prefix = key.toLowerCase(Locale.ROOT) + "\\";
            javaHomes.forEach((subkey, v) -> {
                if (subkey.equalsIgnoreCase(key)
                        || subkey.toLowerCase(Locale.ROOT).startsWith(prefix)) add(homes, v);
            });
        }
        for (String key : MSI_PATH_KEYS) {
            reader.values(key, "Path").forEach((subkey, v) -> {
                if (subkey.toLowerCase(Locale.ROOT).endsWith(MSI_SUFFIX)) add(homes, v);
            });
        }
        return homes;
    }

    private static void add(List<Path> homes, String value) {
        if (value.isBlank()) return;
        try {
            Path home = Path.of(value.strip());
            if (!homes.contains(home)) homes.add(home);
        } catch (RuntimeException notAPathHere) {
            // A value that is not a path names no JDK.
        }
    }

    private static Map<String, String> regQuery(String key, String valueName) {
        String out = Processes.outputIfOk(
                List.of("reg", "query", "HKLM\\" + key, "/s", "/v", valueName), Duration.ofSeconds(TIMEOUT_SECONDS));
        // A missing key exits 1.
        return out == null ? Map.of() : parse(out, valueName);
    }

    /**
     * {@code reg query} output: a {@code HKEY_LOCAL_MACHINE\<subkey>} line, then indented {@code
     * <name>    REG_SZ    <value>} lines for that subkey. Keys come back without the hive prefix.
     */
    static Map<String, String> parse(String output, String valueName) {
        Map<String, String> values = new LinkedHashMap<>();
        @Nullable String subkey = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("HKEY_")) {
                int slash = line.indexOf('\\');
                subkey = slash < 0 ? "" : line.substring(slash + 1).strip();
                continue;
            }
            if (subkey == null) continue;
            String[] fields = line.strip().split("\\s{2,}|\\t", 3);
            if (fields.length == 3 && fields[0].equalsIgnoreCase(valueName) && fields[1].startsWith("REG_")) {
                values.put(subkey, fields[2]);
            }
        }
        return values;
    }
}
