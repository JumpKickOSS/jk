// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.http.Http;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Installs the GraalVM a native build asked for: {@code spec} is a keyword ({@code native}) or a
 * JDK spec ({@code graalvm-community-21}), resolved against the catalog for this host and installed
 * through the JDK installer — so its download reports to the run's board like every other. The
 * engine runs it inside the build; the client only decides that an install is wanted, and asks
 * first when no pin does.
 */
public final class GraalInstall {

    private GraalInstall() {}

    /** The GraalVM's home, and whether this call downloaded it rather than finding it installed. */
    public record Installed(Path home, boolean downloaded) {}

    /**
     * The home of the GraalVM {@code spec} names, installed under {@code jdksDir} (the request's JDK
     * root when null, as {@link JdkEnsure} resolves it) when it is not already there. Fails naming the host when it has no installable
     * GraalVM, the spec when nothing in the catalog matches, and the home when the install lacks
     * {@code native-image}.
     */
    public static Installed install(String spec, @Nullable Path jdksDir) throws IOException, InterruptedException {
        Objects.requireNonNull(spec, "spec");
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        if (!HostPlatform.supported()) {
            throw new IOException(
                    "this host (" + os + "/" + arch + ") has no installable GraalVM; set $GRAALVM_HOME instead");
        }
        JdkCatalog catalog = new JdkCatalogClient().fetch();
        String effective = JdkKeywords.isKeyword(spec)
                ? JdkKeywords.resolveToMajorSpec(catalog, spec, os, arch).orElse(spec)
                : spec;
        JdkCatalog.Entry entry = JdkSelector.selectPreferred(catalog, effective, os, arch)
                .orElseThrow(() -> new IOException("no GraalVM matches " + spec + " on " + os + "/" + arch));
        // The request's JDK root, as the JDK side installs into: the JK_JDKS_DIR of the shell
        // running jk when it set one, never only the daemon's.
        JdkRegistry registry = JdkEnsure.sharedRegistry(jdksDir, null);
        boolean present = new JdkInstaller(new Http(), registry).alreadyInstalled(entry) != null;
        InstalledJdk installed = new JdkService().install(entry, registry, false, JdkInstallListener.NO_OP);
        requireNativeImage(installed.home());
        return new Installed(installed.home(), !present);
    }

    /**
     * Refuse a home with no {@code native-image} launcher, naming it. An install that reported
     * "already installed" for a directory that was not one once handed back such a home; the native
     * step then fell through to the project's JDK and failed naming that instead.
     */
    static void requireNativeImage(Path home) throws IOException {
        if (GraalLauncher.in(home).isPresent()) return;
        throw new IOException("the GraalVM at " + home + " has no " + GraalLauncher.NAME + "; run jk jdk install -F "
                + home.getFileName() + " or set $GRAALVM_HOME");
    }
}
