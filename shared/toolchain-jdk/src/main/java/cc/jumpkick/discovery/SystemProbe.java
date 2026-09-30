// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The OS's own JDK locations, the set Gradle's auto-detection covers:
 *
 * <ul>
 *   <li>Linux: every JDK under {@code /usr/lib/jvm}, {@code /usr/java}, {@code /usr/lib64/jvm},
 *       {@code /usr/local/java} and {@code /opt/java}
 *   <li>macOS: every bundle under {@code /Library/Java/JavaVirtualMachines}, plus each home {@code
 *       /usr/libexec/java_home -V} lists ({@link MacJavaHomes})
 *   <li>Windows: the homes the registry names ({@link WindowsJavaRegistry})
 * </ul>
 *
 * <p>Every hit is source {@code system}, which {@code jk jdk uninstall} refuses.
 */
public final class SystemProbe extends HomeListProbe {

    static final List<Path> LINUX_ROOTS = List.of(
            Path.of("/usr/lib/jvm"),
            Path.of("/usr/java"),
            Path.of("/usr/lib64/jvm"),
            Path.of("/usr/local/java"),
            Path.of("/opt/java"));

    static final List<Path> MAC_ROOTS = List.of(Path.of("/Library/Java/JavaVirtualMachines"));

    /** Homes the OS reports rather than a directory listing; empty when it reports none. */
    @FunctionalInterface
    interface ListedHomes {
        List<Path> homes() throws IOException;
    }

    private final List<Path> roots;
    private final ListedHomes listed;

    public SystemProbe() {
        this(Os.name());
    }

    private SystemProbe(String osName) {
        this(
                Os.isDarwin(osName) ? MAC_ROOTS : Os.isWindows(osName) ? List.of() : LINUX_ROOTS,
                Os.isDarwin(osName)
                        ? MacJavaHomes::list
                        : Os.isWindows(osName)
                                ? () -> WindowsJavaRegistry.homes(WindowsJavaRegistry.REG_QUERY)
                                : List::of);
    }

    SystemProbe(List<Path> roots, ListedHomes listed) {
        this.roots = roots;
        this.listed = listed;
    }

    @Override
    public String name() {
        return "system";
    }

    @Override
    List<Path> candidateHomes() throws IOException {
        Set<Path> homes = new LinkedHashSet<>();
        for (Path root : roots) homes.addAll(childHomes(root));
        homes.addAll(listed.homes());
        return new ArrayList<>(homes);
    }
}
