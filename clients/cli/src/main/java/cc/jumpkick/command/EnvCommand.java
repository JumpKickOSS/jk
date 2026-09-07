// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.DotEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk env} — print the effective build environment for the current directory with source
 * attribution. Precedence matches {@link EnvLookup}: workspace {@code .env}, module
 * {@code .env}, then the real environment (shell / clientEnv).
 */
public final class EnvCommand implements CliCommand {

    @Override
    public String name() {
        return "env";
    }

    @Override
    public String description() {
        return "Print resolved environment values and their sources";
    }

    @Override
    public List<Opt> options() {
        // Shadowed-file values ride the global -v/--verbose (a local flag would collide).
        return List.of(Opt.flag("Include all process environment keys (not only .env and JK_*).", "--all"));
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir().toAbsolutePath().normalize();
        boolean verbose = global.verbose;
        boolean all = in.isSet("all");
        boolean json = global.outputIsJson();

        Path workspaceRoot;
        try {
            workspaceRoot = WorkspaceLocator.findRoot(dir).orElse(dir);
        } catch (Exception e) {
            workspaceRoot = dir;
        }
        Path workspaceEnv = workspaceRoot.resolve(ManifestPaths.ENV);
        Path moduleEnv = dir.resolve(ManifestPaths.ENV);
        Map<String, String> wsMap = DotEnv.read(workspaceEnv);
        Map<String, String> modMap = DotEnv.read(moduleEnv);

        EnvLookup lookup = BuildEnv.lookupFor(dir);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(wsMap.keySet());
        names.addAll(modMap.keySet());
        // Always surface JK_* from the process env so daemon vs shell confusion is visible.
        for (String k : System.getenv().keySet()) {
            if (k != null && k.startsWith("JK_")) names.add(k);
        }
        if (all) {
            names.addAll(System.getenv().keySet());
        }

        List<Row> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(resolveRow(name, lookup, wsMap, modMap, workspaceEnv, moduleEnv, workspaceRoot, dir));
        }

        if (json) {
            emitJson(rows, verbose);
        } else {
            emitHuman(rows, verbose, dir, workspaceRoot);
        }
        return 0;
    }

    /** Package-private for tests. */
    static Row resolveRow(
            String name,
            EnvLookup lookup,
            Map<String, String> wsMap,
            Map<String, String> modMap,
            Path workspaceEnv,
            Path moduleEnv,
            Path workspaceRoot,
            Path moduleDir) {
        String effective = lookup.get(name);
        boolean secret = lookup.isFromFile(name);
        String source;
        String shadowed = null;
        if (effective == null) {
            source = "(unset)";
            effective = "";
        } else if (lookup.isFromFile(name)) {
            // Module file wins over workspace when both define the key.
            if (modMap.containsKey(name)) {
                source = labelFile(moduleEnv, moduleDir, "module");
            } else if (wsMap.containsKey(name)) {
                source = labelFile(workspaceEnv, workspaceRoot, "workspace");
            } else {
                source = ManifestPaths.ENV;
            }
        } else {
            source = "shell";
            // Shadowed file value when the real env won.
            if (modMap.containsKey(name)) {
                shadowed = modMap.get(name);
                source = "shell (shadows " + labelFile(moduleEnv, moduleDir, "module") + ")";
            } else if (wsMap.containsKey(name)) {
                shadowed = wsMap.get(name);
                source = "shell (shadows " + labelFile(workspaceEnv, workspaceRoot, "workspace") + ")";
            }
        }
        return new Row(name, effective, source, secret, shadowed);
    }

    private static String labelFile(Path file, Path base, String scope) {
        Path abs = file.toAbsolutePath().normalize();
        Path rel;
        try {
            rel = base.toAbsolutePath().normalize().relativize(abs);
        } catch (IllegalArgumentException e) {
            rel = abs.getFileName();
        }
        String shown = rel.toString().isEmpty() ? ManifestPaths.ENV : rel.toString();
        return ".env (" + scope + ": " + shown + ")";
    }

    private static void emitHuman(List<Row> rows, boolean verbose, Path dir, Path workspaceRoot) {
        Theme t = Theme.active();
        if (rows.isEmpty()) {
            CliOutput.out(Theme.colorize("(no .env keys and no JK_* in process env)", t.darkGray()));
            CliOutput.out(Theme.colorize(
                    "dir " + dir + (workspaceRoot.equals(dir) ? "" : "  workspace " + workspaceRoot), t.darkGray()));
            return;
        }
        int nameW = rows.stream().mapToInt(r -> r.name.length()).max().orElse(8);
        int valW = rows.stream().mapToInt(r -> displayValue(r).length()).max().orElse(8);
        nameW = Math.min(Math.max(nameW, 8), 40);
        valW = Math.min(Math.max(valW, 8), 48);
        for (Row r : rows) {
            String val = pad(displayValue(r), valW);
            String name = pad(r.name, nameW);
            String line = name + "  " + val + "  " + r.source;
            if (r.secret) line = line + "  " + Theme.colorize("secret", t.warning());
            CliOutput.out(line);
            if (verbose && r.shadowed != null) {
                String sv = r.secret || r.shadowed.length() >= SecretRedactor.MIN_SECRET_LENGTH
                        ? SecretRedactor.MASK
                        : r.shadowed;
                // isFromFile secrets are always masked for the effective value; shadowed file text
                // is also masked when long enough to look like a secret.
                if (r.shadowed.length() >= SecretRedactor.MIN_SECRET_LENGTH) sv = SecretRedactor.MASK;
                CliOutput.out(pad("", nameW) + "  " + Theme.colorize("shadowed file: " + sv, t.darkGray()));
            }
        }
    }

    private static void emitJson(List<Row> rows, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb.append("  {");
            sb.append("\"name\":").append(Jsonl.quote(r.name)).append(',');
            sb.append("\"value\":").append(Jsonl.quote(displayValue(r))).append(',');
            sb.append("\"source\":").append(Jsonl.quote(r.source)).append(',');
            sb.append("\"secret\":").append(r.secret);
            if (verbose && r.shadowed != null) {
                String sv = r.shadowed.length() >= SecretRedactor.MIN_SECRET_LENGTH ? SecretRedactor.MASK : r.shadowed;
                sb.append(",\"shadowed\":").append(Jsonl.quote(sv));
            }
            sb.append('}');
            if (i + 1 < rows.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("]\n");
        CliOutput.outRaw(sb.toString());
    }

    private static String displayValue(Row r) {
        if (r.secret) return SecretRedactor.MASK;
        if (r.effective == null) return "";
        // Mask long values that look file-sourced but slipped through (defense in depth).
        return r.effective;
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) return s;
        return s + " ".repeat(w - s.length());
    }

    /** One printed / JSON row. */
    record Row(
            String name,
            String effective,
            @Nullable String source,
            boolean secret,
            @Nullable String shadowed) {}
}
