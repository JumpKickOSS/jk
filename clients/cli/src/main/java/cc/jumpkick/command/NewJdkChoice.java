// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JdkDownloadBar;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkService;
import cc.jumpkick.model.Project;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Which JDK a new project gets, and putting it on disk if it is not there yet.
 *
 * <p>The three ways to arrive at that answer — an explicit {@code --jdk} spec, the wizard's
 * "Select a JDK" step, and the silent resolve when that step is skipped — are one decision with one
 * precedence: a spec the user typed wins, then the parent module's major, then the machine's default
 * JDK, then the newest LTS. Kept together because the precedence is only checkable where all three
 * entries are visible; split across the flag path and the wizard path is how {@code jk new --jdk 21}
 * and the wizard end up pinning different majors for the same project.
 *
 * <p>Everything here is best-effort against the network: a catalog fetch that fails degrades to
 * whatever is installed locally rather than killing an interactive wizard mid-prompt.
 */
final class NewJdkChoice {

    private NewJdkChoice() {}

    /** The user's global default JDK identifier, or empty (best-effort — never throws). */
    static Optional<String> defaultJdk() {
        try {
            return JdkInventory.current().defaultId();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Best-effort catalog fetch for the wizard's "Select a JDK" step. Network failures (offline, DNS,
     * 5xx) degrade to an empty optional rather than killing the wizard: the user still sees whatever
     * installs are on disk.
     */
    static Optional<JdkCatalog> catalogQuiet() {
        try {
            return Optional.of(new JdkCatalogClient().fetch());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve an explicit {@code --jdk <spec>} into its major + {@code jk.toml} pin. Keywords
     * ({@code lts} / {@code stable} / {@code latest}) resolve to a major against the JetBrains feed
     * (falling back to the latest LTS offline) and always pin a bare major; a
     * {@code <vendor>-<major>} spec keeps its vendor; a bare major stays bare. Throws
     * {@link IllegalArgumentException} (caught by the flag path) on a point release or a spec with
     * no major.
     */
    static NewJdkPlan.Spec fromArg(String arg) {
        String a = arg.trim();
        if (JdkKeywords.isKeyword(a) && !"native".equalsIgnoreCase(a)) {
            String os = HostPlatform.currentOs();
            String arch = HostPlatform.currentArch();
            int major = catalogQuiet()
                    .flatMap(c -> JdkKeywords.resolveToMajorSpec(c, a, os, arch))
                    .map(Project::majorOf)
                    .filter(m -> m > 0)
                    .orElse(NewWizard.LATEST_LTS_MAJOR);
            return new NewJdkPlan.Spec(major, Integer.toString(major));
        }
        return NewJdkPlan.parseExplicit(a);
    }

    /**
     * Resolve the wizard's {@code jdk} answer back to a candidate. When the "Select a JDK" step ran,
     * the candidate's {@code id} matches one of the entries surfaced via
     * {@link NewJdkCandidate#filter}, so it is just a lookup. When the step was skipped, resolve
     * silently: inherit the parent's major (module), adopt the global default's major, else auto-pick
     * by the chosen Java level (sole eligible install, or the LTS to install).
     */
    static NewJdkCandidate pick(
            Answers answers,
            List<NewJdkCandidate> candidates,
            NewCommand.@Nullable ParentInfo parent,
            Optional<String> defaultJdk) {
        if (answers.has("jdk")) {
            var pickedId = answers.get("jdk");
            return candidates.stream()
                    .filter(c -> c.id().equals(pickedId))
                    .findFirst()
                    .orElseGet(() -> candidates.getFirst());
        }
        int preferred = parent != null
                ? (parent.jdkMajor() > 0 ? parent.jdkMajor() : parent.javaRelease())
                : defaultJdk.map(Project::majorOf).orElse(0);
        int floor = NewWizard.jdkFloor(answers, parent);
        return NewJdkPlan.autoCandidate(candidates, floor, preferred, NewWizard.LATEST_LTS_MAJOR)
                .orElseGet(() -> candidates.getFirst());
    }

    /**
     * Download + extract an installable candidate. Reuses the same progress UI as {@code jk jdk
     * install}. On success, returns the freshly-resolved installed candidate (so its {@code home}
     * points at the new JDK). On failure, prints the error and returns empty so the caller exits.
     */
    static Optional<NewJdkCandidate> install(NewJdkCandidate candidate) {
        if (!(candidate instanceof NewJdkCandidate.Installable installable)) {
            return Optional.of(candidate);
        }
        try {
            var entry = installable.entry();
            var installer = new JdkInstaller(new Http(), new JdkRegistry());
            // Download (progress bar) then extract (spinner).
            var label = JdkService.displayLabel(entry);
            long total = entry.archiveSize();
            try (var pb = JdkDownloadBar.show(CliOutput.stdout(), label)) {
                var dl = installer.download(entry, bytes -> pb.update(bytes, total));
                pb.finish();
                try (var sp = Spinner.show(CliOutput.stdout(), "Installing " + label + "...")) {
                    var installed = installer.extractInstalled(entry, dl);
                    CliOutput.out("✓ Installed " + label + " → " + installed.home());
                    var opt = new NewJdkOptions.Option(
                            installed.identifier(),
                            installed.identifier() + "  (JDK " + entry.majorVersion() + ")",
                            installed.home(),
                            entry.majorVersion(),
                            "jk");
                    return Optional.of(new NewJdkCandidate.Installed(opt, installable.vendor()));
                }
            }
        } catch (Exception e) {
            CommandWedge.printFail("New", "failed to install JDK: " + e.getMessage());
            return Optional.empty();
        }
    }
}
