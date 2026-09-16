// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;

/**
 * What a POM's {@code <build><extensions>} mean to the import. os-maven-plugin exists to export
 * the host's {@code os.detected.*} properties, which the effective model values from the running
 * host already ({@link cc.jumpkick.repo.HostClassifiers}), so it is satisfied with nothing written.
 * Every other extension is a Tier-3 row naming its coordinate and, for the ones Maven builds
 * commonly carry, what it does under Maven.
 */
final class BuildExtensions {

    /** Extensions whose whole effect is a set of properties the effective model values itself. */
    static final Set<String> PROPERTY_ONLY = Set.of("kr.motd.maven:os-maven-plugin");

    /** What the common extensions do under Maven, by {@code groupId:artifactId}. */
    private static final Map<String, String> ROLES = Map.of(
            "io.quarkus.bot:build-reporter-maven-extension",
            "reports the Maven build to CI; the build's outputs do not depend on it",
            "io.github.gitflow-incremental-builder:gitflow-incremental-builder",
            "builds only the modules git shows as changed, a selection `jk build` makes from its own action cache",
            "org.apache.maven.wagon:wagon-ssh",
            "is the ssh transport of `mvn deploy`; `jk publish` speaks HTTP(S), `s3://` and `gs://`",
            "org.apache.maven.archetype:archetype-packaging",
            "is the `maven-archetype` packaging, a project template jk does not build");

    private BuildExtensions() {}

    /** One row per extension the import writes nothing for; a property-only extension needs no row. */
    static void report(Model model, ImportReport.Builder report) {
        Build build = model.getBuild();
        if (build == null) return;
        for (Extension extension : build.getExtensions()) {
            String ga = extension.getGroupId() + ":" + extension.getArtifactId();
            if (PROPERTY_ONLY.contains(ga)) continue;
            String role = ROLES.get(ga);
            report.error("`<build><extensions>` " + coordinate(extension, ga) + " "
                    + (role != null ? role : "is a Maven core extension jk does not load")
                    + "; nothing is written for it. Drop it from the POM when the jk build does not need it,"
                    + " or keep running that step with `jk mvn`.");
        }
    }

    /** {@code g:a:v}, or {@code g:a} when the version is a property no POM in the chain defines. */
    private static String coordinate(Extension extension, String ga) {
        @Nullable String version = PluginFacts.usable(extension.getVersion());
        return version == null ? ga : ga + ":" + version;
    }
}
