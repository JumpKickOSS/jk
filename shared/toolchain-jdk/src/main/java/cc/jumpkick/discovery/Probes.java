// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The default {@link LocalToolProbe} chain, in the order callers should consult them.
 * ServiceLoader-discovered probes are appended after the built-ins so a plugin can extend (but not
 * preempt) the default order.
 *
 * <p>Order rationale: explicit user intent first ({@code env}), then the version managers in rough
 * popularity order for JVM developers, then OS-level system installs as a last resort.
 *
 * <p>{@value #ALLOWLIST_ENV} narrows the chain to a comma-separated list of probe names ({@code
 * java-home,jk}, …), read through {@link JkDirs#env} so a test can set it for one JVM or one test.
 * The test tiers set it so a suite never sees the version managers' or the OS's installs on the
 * developer's machine, only the JDK the build itself runs on and jk's own root. Unset means the
 * full chain. A list that names no real probe leaves the chain empty rather than quietly widening
 * to everything.
 */
public final class Probes {

    /** Names the probes a process may consult; unset is every probe. */
    public static final String ALLOWLIST_ENV = "JK_JDK_PROBES";

    private Probes() {}

    public static List<LocalToolProbe> defaultChain() {
        return restrict(fullChain(new JkProbe()), JkDirs.env(ALLOWLIST_ENV));
    }

    /**
     * As {@link #defaultChain()} with jk's own probe rooted at {@code sharedRoot} — the caller's
     * managed JDK root rather than this process's — and every other probe as usual.
     */
    public static List<LocalToolProbe> defaultChain(Path sharedRoot) {
        return restrict(fullChain(JkProbe.sharedRoot(sharedRoot)), JkDirs.env(ALLOWLIST_ENV));
    }

    /** The built-in order plus ServiceLoader extensions, before any allowlist applies. */
    static List<LocalToolProbe> fullChain() {
        return fullChain(new JkProbe());
    }

    private static List<LocalToolProbe> fullChain(JkProbe jk) {
        List<LocalToolProbe> chain = new ArrayList<>();
        chain.add(new EnvVarProbe());
        chain.add(jk); // jk-owned installs under the shared JDK root
        chain.add(new IntellijProbe()); // shared IntelliJ root (~/.jdks / macOS Library JVMs)
        chain.add(new GradleProbe()); // ~/.gradle/jdks — Gradle toolchain auto-provisioned
        chain.add(new SdkmanProbe());
        chain.add(new JbangProbe());
        chain.add(new MiseProbe());
        chain.add(new AsdfProbe());
        chain.add(new JenvProbe());
        chain.add(new HomebrewProbe());
        chain.add(new SystemProbe());
        for (LocalToolProbe extension : ServiceLoader.load(LocalToolProbe.class)) {
            chain.add(extension);
        }
        return List.copyOf(chain);
    }

    /**
     * {@code chain} without every probe whose {@link LocalToolProbe#name()} the allowlist does not
     * carry, in the chain's own order. A blank or absent allowlist keeps the whole chain.
     */
    static List<LocalToolProbe> restrict(List<LocalToolProbe> chain, @Nullable String allowlist) {
        if (allowlist == null || allowlist.isBlank()) return chain;
        Set<String> allowed = Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
        return chain.stream().filter(probe -> allowed.contains(probe.name())).toList();
    }
}
