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
 * Where the new-project form ends: one engine call scaffolds the template
 * ({@code <local-path|short-name|git-uri|owner/repo>}) from the settled answers.
 *
 * <p>The Giter8 parameter map <em>is</em> the project definition, so the step-1 answers (name, group,
 * language, layout) are seeds for that map, and the whole scaffold happens engine-side in one call.
 * Nothing rolls back here — a partially expanded template is the engine's problem to avoid, not the
 * client's to undo.
 */
final class NewTemplate {

    private NewTemplate() {}

    /**
     * The flags this mode seeds its parameter map from.
     *
     * @param parent the enclosing project when one was detected: the scaffold becomes one of its
     *     modules and is listed in its {@code [workspace].modules}, as a plain {@code jk new} module
     *     is. Null makes a standalone project, which gets jk's seeded {@code .gitignore} (unless the
     *     template ships one), {@code AGENTS.md}, the files of each agent in use and a first code
     *     graph; a module gets none of these, the workspace root owns them.
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
            NewCommand.@Nullable ParentInfo parent,
            boolean offline,
            @Nullable String frontend) {

        boolean standalone() {
            return parent == null;
        }
    }

    static int apply(Args args) {
        Map<String, String> params = params(args.params());
        if (params == null) return Exit.USAGE;
        var presetName = NewArgs.presetName(args.directory(), args.cwd());
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
        Path target = NewArgs.resolveTarget(args.directory(), args.cwd(), resolvedName);
        Path parentDir = target.getParent() == null ? args.cwd() : target.getParent();
        if (officialShortName(args.ref())) {
            EngineCatalogFreshen.freshenCatalog(EnginePaths.current(), "templates", args.offline(), null, null);
        }
        // No -L leaves the layout to the engine: a jk_layout parameter, else the template's default.
        Layout resolvedLayout;
        try {
            resolvedLayout =
                    args.layout() == null || args.layout().isBlank() ? Layout.AUTO : Layout.parse(args.layout());
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
                            args.standalone(),
                            params,
                            true,
                            target.toString()));
            if (ack.error() != null && !ack.error().isBlank()) {
                CommandWedge.printFail("New", ack.error());
                return ack.error().contains("not found") ? Exit.USAGE : Exit.SOFTWARE;
            }
            if (args.frontend() != null && !args.frontend().isBlank()) {
                int swapped =
                        NewNode.replaceFrontend(target.resolve("web"), args.frontend(), List.of(), args.offline());
                if (swapped != Exit.SUCCESS) return swapped;
            }
            NewCommand.ParentInfo parent = args.parent();
            if (parent == null) {
                AgentWiring.report(target);
            } else {
                NewCommand.registerModule(parent.root(), target);
            }
            CommandWedge.envelopeStart();
            String applied = "Applied template (" + ack.filesWritten() + " files) → " + target.getFileName();
            CliOutput.out(
                    parent == null
                            ? JkWedge.chipLine(Glyphs.CHECK, "New Project", GlobalConfig.nerdFont(), applied)
                            : JkWedge.chipLine(
                                    Glyphs.CHECK,
                                    "New Module",
                                    GlobalConfig.nerdFont(),
                                    applied + ", added to project " + parent.displayName()));
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("New", e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /** {@code -p key=value} pairs in order; {@code null}, the failure printed, for one without a key. */
    static @Nullable Map<String, String> params(List<String> pairs) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq <= 0) {
                CommandWedge.printFail("New", "--param expects key=value, got: " + p);
                return null;
            }
            params.put(p.substring(0, eq), p.substring(eq + 1));
        }
        return params;
    }

    /** Short-name shaped refs: the engine owns the catalog and freshen. */
    private static boolean officialShortName(String id) {
        if (id == null || id.isBlank()) return false;
        return id.matches("[a-z][a-z0-9-]*")
                || id.matches("[a-z][a-z0-9-]*/[a-z][a-z0-9-]*")
                || id.matches("[a-z]+/[a-z][a-z0-9-]*/[a-z][a-z0-9-]*");
    }
}
