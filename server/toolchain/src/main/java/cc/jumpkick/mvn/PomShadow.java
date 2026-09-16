// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shadow manifest of a Maven module: the effective POM imported the way {@code jk import}
 * imports it, rendered as {@code jk.toml} text under a header that names the POM bytes it came
 * from. A shadow whose header names the current POM's digest (and the running jk) is current.
 *
 * <p>Single module only. A POM that lists {@code <modules>} — at the top level or in a profile —
 * is refused with the {@code jk import} remedy: a reactor becomes a workspace by import, not by
 * shadowing.
 */
public final class PomShadow {

    private PomShadow() {}

    /** Prefix of the shadow's first line; the rest of the line is {@link #stamp}'s value. */
    static final String HEADER = "# shadow of " + ManifestPaths.POM + " ";

    /** The rendered shadow and the rows of the import report that {@code jk import} would print. */
    public record Rendered(String toml, ImportReport report) {

        /** Tier-3 rows: what the effective POM declares that the shadow cannot carry. */
        public List<String> tier3() {
            List<String> rows = new ArrayList<>();
            for (ImportReport.Issue issue : report.issues()) {
                if (issue.severity() == ImportReport.Severity.ERROR) rows.add(issue.message());
            }
            return rows;
        }
    }

    /** The value the shadow's header carries for these POM bytes under the running jk. */
    public static String stamp(byte[] pomBytes) {
        return Hashing.sha256Hex(pomBytes) + " jk " + JkVersion.VERSION;
    }

    /** True when the shadow at {@code shadow} was rendered from {@code pomBytes} by this jk. */
    public static boolean isCurrent(Path shadow, byte[] pomBytes) {
        if (!Files.isRegularFile(shadow)) return false;
        try {
            String first = Files.readAllLines(shadow).stream().findFirst().orElse("");
            return first.equals(HEADER + stamp(pomBytes));
        } catch (IOException e) {
            return false;
        }
    }

    /** True when {@code pomBytes} lists modules anywhere, so the module is a reactor root. */
    public static boolean declaresModules(byte[] pomBytes) {
        return ReactorModules.declaresModules(EffectiveModel.rawModel(pomBytes));
    }

    /** One line: why a reactor is not shadowed, and the command that imports it instead. */
    public static String reactorRefusal(Path pom) {
        return pom + " declares <modules>: a reactor is built after `jk import " + ManifestPaths.POM
                + "` writes its jk.toml workspace; in-place builds cover a single module";
    }

    /**
     * Import {@code pom} through {@code importer} and render the shadow. The text starts with the
     * header line for {@code pomBytes}, which must be the bytes {@code pom} holds.
     */
    public static Rendered render(PomImporter importer, Path pom, byte[] pomBytes) throws IOException {
        if (declaresModules(pomBytes)) throw new IllegalStateException(reactorRefusal(pom));
        PomImporter.Result imported = importer.importFrom(pom);
        String body = JkBuildRenderer.render(imported.jkBuild());
        return new Rendered(HEADER + stamp(pomBytes) + "\n" + body, imported.report());
    }
}
