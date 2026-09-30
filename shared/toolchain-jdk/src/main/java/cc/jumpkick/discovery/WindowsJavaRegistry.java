// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    /** Every home the registry names, in key order. */
    static List<Path> homes(Reader reader) throws IOException {
        List<Path> homes = new ArrayList<>();
        for (String key : JAVA_HOME_KEYS) {
            reader.values(key, "JavaHome").values().forEach(v -> add(homes, v));
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
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\" + key, "/s", "/v", valueName)
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Map.of();
            }
            // Exit 1 is a missing key.
            return p.exitValue() == 0 ? parse(out, valueName) : Map.of();
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
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
