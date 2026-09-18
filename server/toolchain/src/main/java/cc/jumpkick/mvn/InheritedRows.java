// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.io.File;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;

/**
 * Report rows a parent's {@code <build>} puts on every module that inherits it — an extension, a
 * plugin the import has no mapping for — said once, at the declaring POM and with the count of
 * modules inheriting it, instead of once per module. A module's own declaration keeps its row at
 * the module. The declaring POM is named by its pom.xml relative to the workspace root when it is
 * in the reactor, else as the published parent.
 */
final class InheritedRows {

    private record Key(String declaredBy, ImportReport.Severity severity, String message) {}

    private final Path rootDir;
    private final Map<Key, Integer> inheriting = new LinkedHashMap<>();

    /** {@code rootDir} is the workspace root the declaring POMs are named relative to. */
    InheritedRows(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }

    /** The workspace root: what Maven's launcher names {@code ${maven.multiModuleProjectDirectory}}. */
    Path rootDir() {
        return rootDir;
    }

    /** Count one module inheriting {@code message} from {@code declaredBy}. */
    void inherited(String declaredBy, ImportReport.Severity severity, String message) {
        inheriting.merge(new Key(declaredBy, severity, message), 1, Integer::sum);
    }

    /** Say that the root declares {@code message} itself; the modules inheriting it are counted onto the same row. */
    void declaredByRoot(ImportReport.Severity severity, String message) {
        inheriting.merge(new Key(ROOT, severity, message), 0, Integer::sum);
    }

    /** Every counted row, in first-seen order: the message, who declares it and how many modules inherit it. */
    void flush(ImportReport.Builder report) {
        NumberFormat count = NumberFormat.getIntegerInstance(Locale.ROOT);
        inheriting.forEach((key, modules) -> {
            String text = modules == 0
                    ? key.message()
                    : key.message() + " Declared by " + key.declaredBy() + ", inherited by " + count.format(modules)
                            + (modules == 1 ? " module." : " modules.");
            if (key.severity() == ImportReport.Severity.ERROR) {
                report.error(text);
            } else {
                report.warning(text);
            }
        });
    }

    /** How the report names the root pom.xml as a declaring POM. */
    static final String ROOT = "the root pom.xml";

    /**
     * The nearest ancestor of {@code em} whose own {@code <build>} (or one of its profiles')
     * satisfies {@code declares}, named for the report; {@code "a parent"} when none does.
     */
    String declaredBy(EffectiveModel em, Predicate<Model> declares) {
        Optional<EffectiveModel.Ancestor> ancestor =
                em.ancestors().stream().filter(a -> declares.test(a.raw())).findFirst();
        return ancestor.map(this::label).orElse("a parent");
    }

    private String label(EffectiveModel.Ancestor ancestor) {
        File pomFile = ancestor.raw().getPomFile();
        Path pom = pomFile == null ? null : pomFile.toPath().toAbsolutePath().normalize();
        if (pom == null || !pom.startsWith(rootDir)) return ancestor.label();
        Path dir = Objects.requireNonNull(pom.getParent());
        if (dir.equals(rootDir)) return ROOT;
        return "`" + rootDir.relativize(pom).toString().replace(File.separatorChar, '/') + "`";
    }

    /** Whether a raw model declares {@code artifactId} under {@code <build><plugins>}, its profiles included. */
    static boolean declaresPlugin(Model raw, String artifactId) {
        return declaresPlugin(raw, artifactId, raw.getProfiles());
    }

    /** {@link #declaresPlugin(Model, String)} over the given profiles only: a module's active ones. */
    static boolean declaresPlugin(Model raw, String artifactId, List<Profile> profiles) {
        if (raw.getBuild() != null && hasPlugin(raw.getBuild().getPlugins(), artifactId)) return true;
        for (Profile profile : profiles) {
            if (profile.getBuild() != null && hasPlugin(profile.getBuild().getPlugins(), artifactId)) return true;
        }
        return false;
    }

    private static boolean hasPlugin(List<Plugin> plugins, String artifactId) {
        return plugins.stream().anyMatch(p -> artifactId.equals(p.getArtifactId()));
    }
}
