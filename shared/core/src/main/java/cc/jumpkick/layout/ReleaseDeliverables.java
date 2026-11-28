// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a module puts in a release: the files its build writes that a person downloads, each named
 * as the release names it. Platform-neutral files (a framework's runnable jar, the fat jar, the
 * minified jar, the lib-dir archive, a war) come from any host; platform-specific ones (the runtime-image archive, the native
 * executable) come from the host of their platform. Decided from the manifest alone, so a host can
 * name the files another platform's host uploads.
 */
public final class ReleaseDeliverables {

    private ReleaseDeliverables() {}

    /**
     * One file of a release. {@code name} is its name in the release, {@code module} the module that
     * builds it, {@code platform} its {@link ReleasePlatform} name ({@code null} for a
     * platform-neutral file), and {@code file} where this host's build writes it.
     */
    public record Deliverable(
            String name, String module, @Nullable String platform, Path file) {
        public boolean neutral() {
            return platform == null;
        }
    }

    /**
     * The files of {@code build}'s release this host writes: the neutral ones and this host's
     * platform's. {@code runnable} is the framework packager's self-contained jar (a Spring Boot or
     * Grails jar, a Quarkus uber-jar), shipped as named on disk; {@code null} when there is none.
     */
    public static List<Deliverable> local(BuildLayout layout, JkBuild build, @Nullable Path runnable) {
        String module = layout.artifact();
        List<Deliverable> out = new ArrayList<>();
        if (runnable != null) out.add(neutral(module, runnable));
        if (build.assembly()) out.add(neutral(module, layout.assemblyJar()));
        if (build.minified()) out.add(neutral(module, layout.minifiedJar()));
        if (build.libDir() != null) out.add(neutral(module, layout.libDirArchive()));
        BuildBlock.War war = build.build().war();
        if (war != null) out.add(neutral(module, layout.warFile(war)));
        String host = ReleasePlatform.current();
        if (build.build().jlink() != null) {
            out.add(new Deliverable(jlinkName(layout, host), module, host, layout.jlinkArchive()));
        }
        if (shipsNative(build)) {
            out.add(new Deliverable(nativeName(layout, build, host), module, host, layout.nativeBinary()));
        }
        return List.copyOf(out);
    }

    /** The names of {@code build}'s platform-specific files for {@code platform}, in the order {@link #local} lists them. */
    public static List<String> platformNames(BuildLayout layout, JkBuild build, String platform) {
        List<String> out = new ArrayList<>();
        if (build.build().jlink() != null) out.add(jlinkName(layout, platform));
        if (shipsNative(build)) out.add(nativeName(layout, build, platform));
        return List.copyOf(out);
    }

    /** True when {@code build} carries a platform-specific file on any platform. */
    public static boolean platformSpecific(JkBuild build) {
        return build.build().jlink() != null || shipsNative(build);
    }

    /**
     * True when the release carries the module's native executable: any {@code [native]} module
     * not {@code enabled = false}, which a release-lane publish builds whatever {@code enabled}
     * says; a {@code --shared} library is no download.
     */
    public static boolean shipsNative(JkBuild build) {
        return build.nativeMode().isEnabled()
                && build.nativeConfigOpt()
                        .map(c -> !c.args().contains("--shared"))
                        .orElse(true);
    }

    /** {@code <artifact>-<version>-<platform>.tar.gz}, {@code .zip} for Windows: the runtime-image archive. */
    static String jlinkName(BuildLayout layout, String platform) {
        return layout.archiveRoot() + "-" + platform + (ReleasePlatform.windows(platform) ? ".zip" : ".tar.gz");
    }

    /** {@code <executable>-<version>-<platform>}, {@code .exe} for Windows: the native executable. */
    public static String nativeName(BuildLayout layout, JkBuild build, String platform) {
        String base = build.nativeConfigOpt()
                .map(JkBuild.NativeConfig::name)
                .filter(n -> !n.isBlank())
                .orElse(layout.artifact());
        return base + "-" + layout.version() + "-" + platform + (ReleasePlatform.windows(platform) ? ".exe" : "");
    }

    private static Deliverable neutral(String module, Path file) {
        return new Deliverable(String.valueOf(file.getFileName()), module, null, file);
    }
}
