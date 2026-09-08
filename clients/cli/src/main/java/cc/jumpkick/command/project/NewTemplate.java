// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineCatalogFreshen;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk new --template <local-path|short-name|git-uri|owner/repo>} — the third mode of
 * {@code jk new}, beside the wizard and the flag path.
 *
 * <p>It is a mode, not a variation: the giter8 parameter map <em>is</em> the project definition, so
 * the flags this mode reads ({@code --name}, {@code --group}, {@code --lang}, {@code --layout}) are
 * seeds for that map rather than fields of a {@code NewInputs}, and the whole scaffold happens
 * engine-side in one call instead of through the local plan. Nothing rolls back here — a partially
 * expanded template is the engine's problem to avoid, not the client's to undo.
 */
final class NewTemplate {

    private NewTemplate() {}

    /**
     * The flags this mode seeds its parameter map from.
     *
     * @param standalone true when no enclosing project was detected (writes a lockfile)
     */
    record Args(
            String ref,
            List<String> params,
            @Nullable String name,
            @Nullable String group,
            @Nullable String lang,
            @Nullable String layout,
            @Nullable Path directory,
            Path cwd,
            boolean plugin,
            boolean standalone,
            boolean offline) {}

    static int apply(Args args) {
        if (args.plugin()) {
            CommandWedge.printFail("New", "--template cannot be combined with --plugin");
            return Exit.USAGE;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String p : args.params()) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                CommandWedge.printFail("New", "--param expects key=value, got: " + p);
                return Exit.USAGE;
            }
            params.put(p.substring(0, eq), p.substring(eq + 1));
        }
        var presetName = NewWizard.wizardPresetName(args.directory(), args.cwd());
        String fileBase = Path.of(args.ref()).getFileName().toString();
        if (fileBase.endsWith(".g8")) fileBase = fileBase.substring(0, fileBase.length() - 3);
        String name = args.name();
        String resolvedName =
                (name != null && !name.isBlank()) ? name : params.getOrDefault("name", presetName.orElse(fileBase));
        params.putIfAbsent("name", resolvedName);
        String group = args.group();
        if (group != null && !group.isBlank()) {
            params.putIfAbsent("organization", group);
            params.putIfAbsent("group", group);
            params.putIfAbsent("package", group);
        }
        // Only an explicit --lang goes on the wire: template resolution searches every language
        // for a bare name, and hardcoding java here made kotlin-only templates unreachable.
        String resolvedLang = (args.lang() != null && !args.lang().isBlank()) ? args.lang() : null;
        Path target = NewWizard.resolveTarget(args.directory(), args.cwd(), resolvedName);
        Path parentDir = target.getParent() == null ? args.cwd() : target.getParent();
        if (officialShortName(args.ref())) {
            EngineCatalogFreshen.freshenCatalog(EnginePaths.current(), "templates", args.offline(), null, null);
        }
        Layout resolvedLayout;
        try {
            resolvedLayout =
                    args.layout() == null || args.layout().isBlank() ? Layout.TRADITIONAL : Layout.parse(args.layout());
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("New", e.getMessage());
            return Exit.USAGE;
        }
        try {
            var ack = EngineClient.newProject(
                    EnginePaths.current(),
                    new EngineRequests.NewProjectRequest(
                            resolvedName,
                            parentDir.toString(),
                            group,
                            resolvedLang,
                            resolvedLayout.token(),
                            args.ref(),
                            false,
                            null,
                            0,
                            false,
                            false,
                            false,
                            null,
                            List.of(),
                            true,
                            args.standalone(),
                            params,
                            true,
                            target.toString()));
            if (ack.error() != null && !ack.error().isBlank()) {
                CommandWedge.printFail("New", ack.error());
                return ack.error().contains("not found") ? Exit.USAGE : Exit.SOFTWARE;
            }
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(
                    Glyphs.CHECK,
                    "New Project",
                    GlobalConfig.nerdFont(),
                    "Applied template (" + ack.filesWritten() + " files) → " + target.getFileName()));
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("New", e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /** Short-name shaped refs: the engine owns the catalog and freshen. */
    private static boolean officialShortName(String id) {
        if (id == null || id.isBlank()) return false;
        return id.matches("[a-z][a-z0-9-]*")
                || id.matches("[a-z][a-z0-9-]*/[a-z][a-z0-9-]*")
                || id.matches("[a-z]+/[a-z][a-z0-9-]*/[a-z][a-z0-9-]*");
    }
}
