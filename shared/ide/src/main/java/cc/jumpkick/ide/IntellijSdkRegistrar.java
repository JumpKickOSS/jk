// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.IntellijJdkTable;
import cc.jumpkick.util.MinimalXml;
import cc.jumpkick.util.MinimalXml.Element;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Best-effort upsert of {@code jk-<vendor>-<level>} SDKs into JetBrains global
 * {@code jdk.table.xml} (write counterpart to {@link IntellijJdkTable}). Points at the stable
 * {@code StableJdkPointer} path. A running IDE may clobber the file on exit — prefer a closed IDE.
 */
public final class IntellijSdkRegistrar {

    /** One SDK to register. {@code javaHome} is absolute and stable (not the patch dir). */
    public record SdkEntry(String name, Path javaHome, String version) {
        public SdkEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(javaHome, "javaHome");
        }
    }

    private final List<Path> vendorRoots;

    IntellijSdkRegistrar(List<Path> vendorRoots) {
        this.vendorRoots = List.copyOf(vendorRoots);
    }

    /**
     * Registrar over explicit vendor roots (each a directory whose children are {@code
     * <Product><Version>} config dirs). Used by {@code jk idea}'s {@code --ide-config-dir} override
     * and by tests.
     */
    public static IntellijSdkRegistrar of(List<Path> vendorRoots) {
        return new IntellijSdkRegistrar(vendorRoots);
    }

    /** Registrar backed by the host's real IDE config directories. */
    public static IntellijSdkRegistrar shared() {
        return new IntellijSdkRegistrar(
                IntellijJdkTable.defaultVendorRoots(System::getenv, Os.name(), System.getProperty("user.home", "")));
    }

    /**
     * Upsert {@code sdks} into every Java-capable IDE table found. Returns the table files actually
     * written (for caller logging). Never throws.
     */
    public List<Path> register(Collection<SdkEntry> sdks) {
        List<Path> touched = new ArrayList<>();
        if (sdks == null || sdks.isEmpty()) return touched;
        for (Path table : targetTables()) {
            try {
                upsert(table, sdks);
                touched.add(table);
            } catch (Exception e) {
                // Malformed table / permission issue — skip this IDE.
                Log.debug("register: Malformed table / permission issue", e);
            }
        }
        return touched;
    }

    /**
     * {@code options/jdk.table.xml} under every installed IntelliJ IDEA / Android Studio config dir.
     */
    private List<Path> targetTables() {
        List<Path> out = new ArrayList<>();
        for (Path root : vendorRoots) {
            try {
                PathUtil.forEachChild(root, (product, attrs) -> {
                    if (attrs.isDirectory() && isJavaIde(product.getFileName().toString())) {
                        out.add(product.resolve("options").resolve("jdk.table.xml"));
                    }
                    return true;
                });
            } catch (IOException ignored) {
                // Unreadable vendor dir — nothing to register here.
            }
        }
        return out;
    }

    /**
     * Only IDEs that actually use a JavaSDK table: IntelliJ IDEA (both editions) and Android Studio.
     */
    private static boolean isJavaIde(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        return p.startsWith("intellijidea") || p.startsWith("ideaic") || p.startsWith("androidstudio");
    }

    private void upsert(Path table, Collection<SdkEntry> sdks) throws Exception {
        Element application;
        if (Files.isRegularFile(table)) {
            application = MinimalXml.parse(Files.readString(table));
        } else {
            application = Element.of("application");
        }

        Element component = findComponent(application, "ProjectJdkTable");
        if (component == null) {
            component = Element.of("component").setAttr("name", "ProjectJdkTable");
            application.append(component);
        }

        for (SdkEntry sdk : sdks) {
            Element existing = findJdkByName(component, sdk.name());
            if (existing != null) component.remove(existing);
            component.append(buildJdk(sdk));
        }

        Files.createDirectories(table.getParent());
        Files.writeString(table, MinimalXml.write(application));
    }

    private static Element buildJdk(SdkEntry sdk) {
        String home = sdk.javaHome().toAbsolutePath().normalize().toString().replace('\\', '/');

        Element jdk = Element.of("jdk").setAttr("version", "2");
        jdk.append(valued("name", sdk.name()));
        jdk.append(valued("type", "JavaSDK"));
        if (sdk.version() != null && !sdk.version().isBlank()) {
            jdk.append(valued("version", "java version \"" + sdk.version() + "\""));
        }
        jdk.append(valued("homePath", home));

        Element roots = Element.of("roots");
        Element annotations = Element.of("annotationsPath");
        annotations.append(rootEl("composite", null));
        roots.append(annotations);

        Element classPath = Element.of("classPath");
        Element composite = rootEl("composite", null);
        // Modular JDK (9+): IntelliJ represents the platform classpath as one
        // jrt root PER MODULE (jrt://<home>!/<module>), exactly as it writes
        // them itself. A single jrt://<home>!/ root over the whole image does
        // not expose any packages — the SDK shows empty and even java.lang.*
        // fails to resolve. Enumerate the modules and emit one root each; only
        // fall back to the bare root when enumeration finds nothing (e.g. a
        // pre-9 JDK or an unreadable home).
        List<String> modules = moduleNames(sdk.javaHome());
        if (modules.isEmpty()) {
            composite.append(rootEl("simple", "jrt://" + home + "!/"));
        } else {
            for (String module : modules) {
                composite.append(rootEl("simple", "jrt://" + home + "!/" + module));
            }
        }
        classPath.append(composite);
        roots.append(classPath);
        jdk.append(roots);

        jdk.append(Element.of("additional"));
        return jdk;
    }

    /**
     * Sorted platform module names for IntelliJ jrt roots: {@code release}'s {@code MODULES=} line,
     * else {@code jmods/*.jmod}. Empty when non-modular or unreadable.
     */
    private static List<String> moduleNames(Path javaHome) {
        Path release = javaHome.resolve("release");
        try {
            for (String line : Files.readAllLines(release)) {
                if (!line.startsWith("MODULES=")) continue;
                String v = line.substring("MODULES=".length()).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                if (v.isBlank()) break;
                return Stream.of(v.split("\\s+"))
                        .filter(s -> !s.isBlank())
                        .sorted()
                        .toList();
            }
        } catch (IOException ignored) {
            // No release file / unreadable — try jmods next.
        }
        List<String> names = new ArrayList<>();
        try {
            PathUtil.forEachChild(javaHome.resolve("jmods"), (entry, attrs) -> {
                String n = entry.getFileName().toString();
                if (n.endsWith(".jmod")) names.add(n.substring(0, n.length() - ".jmod".length()));
                return true;
            });
        } catch (IOException ignored) {
            // Unreadable jmods dir — give up, fall back to the bare root.
            return List.of();
        }
        names.sort(null);
        return List.copyOf(names);
    }

    private static Element valued(String tag, String value) {
        return Element.of(tag).setAttr("value", value);
    }

    private static Element rootEl(String type, @Nullable String url) {
        Element e = Element.of("root");
        if (url != null) e.setAttr("url", url);
        e.setAttr("type", type);
        return e;
    }

    private static @Nullable Element findComponent(Element application, String name) {
        for (Element c : application.elements("component")) {
            if (name.equals(c.attr("name"))) return c;
        }
        return null;
    }

    private static @Nullable Element findJdkByName(Element component, String name) {
        for (Element jdk : component.elements("jdk")) {
            for (Element n : jdk.elements("name")) {
                if (name.equals(n.attr("value"))) return jdk;
            }
        }
        return null;
    }
}
