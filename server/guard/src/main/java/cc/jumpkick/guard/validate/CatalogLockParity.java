// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository that builds with Gradle as well as jk compiles against two pins: the version
 * catalog's and the lock's. Where {@code gradle/libs.versions.toml} and {@code jk-lock.toml} name the
 * same module they must agree on its version, or the two builds ship different bytes. Runs only
 * when the catalog exists; reports under the reserved code {@code catalog-lock}.
 */
public final class CatalogLockParity {

    public static final String CODE = "catalog-lock";
    public static final String CATALOG = "gradle/libs.versions.toml";

    private static final Pattern VERSION_KEY = Pattern.compile("(?m)^([A-Za-z0-9-]+)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LIBRARY = Pattern.compile(
            "(?m)^[A-Za-z0-9-]+\\s*=\\s*\\{\\s*module\\s*=\\s*\"([^\"]+)\"\\s*,\\s*(?:version\\.ref\\s*=\\s*\"([A-Za-z0-9-]+)\"|version\\s*=\\s*\"([^\"]+)\")");

    private CatalogLockParity() {}

    public static List<Fault> validate(Path root) {
        Path catalogFile = root.resolve(CATALOG);
        Path lockFile = LockPaths.lockFile(root);
        if (!Files.isRegularFile(catalogFile) || !Files.isRegularFile(lockFile)) return List.of();
        String text;
        Lockfile lock;
        try {
            text = Files.readString(catalogFile);
            lock = LockfileReader.read(lockFile);
        } catch (IOException | RuntimeException e) {
            return List.of(new Fault(
                    CODE,
                    "could not read " + CATALOG + " or the lock: " + e.getMessage(),
                    "fix the file; the two builds still have to agree"));
        }
        Map<String, String> catalog = catalog(text);
        if (catalog.isEmpty() && text.contains("[libraries]")) {
            return List.of(new Fault(
                    CODE,
                    CATALOG
                            + " has a [libraries] table and this scan read no `module = ..., version(.ref) = ...` entry from it",
                    "the scan is blind to the catalog's spelling; report it"));
        }
        Map<String, Set<String>> locked = new TreeMap<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            String[] parts = a.name().split(":", -1);
            if (parts.length < 2) continue;
            locked.computeIfAbsent(parts[0] + ":" + parts[1], k -> new TreeSet<>())
                    .add(a.version());
        }
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : catalog.entrySet()) {
            Set<String> versions = locked.get(e.getKey());
            if (versions != null && !versions.contains(e.getValue())) {
                mismatches.add(e.getKey() + ": catalog=" + e.getValue() + " lock=" + versions);
            }
        }
        if (mismatches.isEmpty()) return List.of();
        return List.of(
                new Fault(
                        CODE,
                        CATALOG + " and jk-lock.toml disagree:\n    " + String.join("\n    ", mismatches),
                        "the two builds compile against different bytes; align the catalog with the lock (the lock is the pin), or re-lock"));
    }

    /** module → version for every catalog library with a literal version or a resolvable {@code version.ref}. */
    static Map<String, String> catalog(String text) {
        Map<String, String> versions = new LinkedHashMap<>();
        int libraries = text.indexOf("[libraries]");
        Matcher v = VERSION_KEY.matcher(libraries < 0 ? text : text.substring(0, libraries));
        while (v.find()) versions.put(v.group(1), v.group(2));
        Map<String, String> out = new TreeMap<>();
        Matcher m = LIBRARY.matcher(text);
        while (m.find()) {
            String version = m.group(2) != null ? versions.get(m.group(2)) : m.group(3);
            if (version != null) out.put(m.group(1), version);
        }
        return out;
    }
}
