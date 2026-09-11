// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of JDKs registered in JetBrains/Android Studio {@code jdk.table.xml}. Used to
 * avoid deleting IDE-managed installs; discovery is lazy and process-cached.
 */
public final class IntellijJdkTable {

    private static final IntellijJdkTable SHARED = new IntellijJdkTable(
            defaultVendorRoots(System::getenv, Os.name(), System.getProperty("user.home", "")),
            System.getProperty("user.home", ""));

    /** Process-wide instance backed by the host's real IDE config directories. */
    public static IntellijJdkTable shared() {
        return SHARED;
    }

    private final List<Path> vendorRoots;
    private final String userHome;
    private volatile @Nullable Set<Path> cache;

    IntellijJdkTable(List<Path> vendorRoots, String userHome) {
        this.vendorRoots = List.copyOf(vendorRoots);
        this.userHome = userHome;
    }

    /** Test seam: a table backed by an explicit managed set (no disk access). */
    static IntellijJdkTable ofManaged(Set<Path> managed) {
        IntellijJdkTable t = new IntellijJdkTable(List.of(), "");
        t.cache = Set.copyOf(managed);
        return t;
    }

    /** True when {@code canonicalHome} is registered in some IDE's table. */
    public boolean isManaged(Path canonicalHome) {
        return registeredHomes().contains(canonicalHome);
    }

    private Set<Path> registeredHomes() {
        Set<Path> c = cache;
        if (c != null) return c;
        synchronized (this) {
            Set<Path> scanned = cache;
            if (scanned == null) cache = scanned = scan();
            return scanned;
        }
    }

    /**
     * Vendor directories that may contain {@code <Product><Version>} config folders. Both JetBrains
     * products and Android Studio (under {@code Google}) live beside each other under the platform's
     * per-user config base.
     */
    public static List<Path> defaultVendorRoots(
            Function<String, @Nullable String> env, String osName, String userHome) {
        String lower = osName.toLowerCase(Locale.ROOT);
        Path home = Path.of(userHome);
        Path base;
        if (lower.contains("mac") || lower.contains("darwin")) {
            base = home.resolve("Library").resolve("Application Support");
        } else if (lower.contains("win")) {
            String appData = env.apply("APPDATA");
            base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : home.resolve("AppData").resolve("Roaming");
        } else {
            String xdg = env.apply("XDG_CONFIG_HOME");
            base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : home.resolve(".config");
        }
        return List.of(base.resolve("JetBrains"), base.resolve("Google"));
    }

    private Set<Path> scan() {
        Set<Path> homes = new HashSet<>();
        for (Path vendorRoot : vendorRoots) {
            if (!Files.isDirectory(vendorRoot)) continue;
            try (Stream<Path> products = Files.list(vendorRoot)) {
                products.map(p -> p.resolve("options").resolve("jdk.table.xml"))
                        .filter(Files::isRegularFile)
                        .forEach(table -> parseHomePaths(table, homes));
            } catch (IOException ignored) {
                // Unreadable vendor dir — treat as "nothing registered here".
            }
        }
        return homes;
    }

    private void parseHomePaths(Path table, Set<Path> out) {
        // MinimalXml rejects DOCTYPE outright, so the XXE hardening the old StAX scan needed
        // feature flags for is structural. Tables are a few KB; a full parse is fine.
        try {
            MinimalXml.Element doc = MinimalXml.parse(Files.readString(table));
            for (MinimalXml.Element homePath : doc.descendants("homePath")) {
                String value = homePath.attr("value");
                if (value != null && !value.isBlank()) {
                    out.add(canonicalize(value));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Malformed/partial table — skip it rather than fail discovery.
        }
    }

    /** Expand the {@code $USER_HOME$} macro and resolve to a canonical path. */
    private Path canonicalize(String homePath) {
        String expanded = homePath.replace("$USER_HOME$", userHome);
        Path p = Path.of(expanded);
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
