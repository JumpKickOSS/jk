// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code jk engine aot} — list JEP 514 AOT caches under {@code state/aot/} with human-readable
 * details from {@code aot.toml} (and filename/size fallback for pre-manifest caches).
 */
public final class EngineAotCommand implements cc.jumpkick.model.command.CliCommand {

    private static final int LABEL_FIELD = 12;

    @Override
    public String name() {
        return "aot";
    }

    @Override
    public String description() {
        return "List AOT cache files and their training details";
    }

    @Override
    public List<cc.jumpkick.model.command.Opt> options() {
        return List.of();
    }

    @Override
    public int run(cc.jumpkick.model.command.Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        Path aotDir = JkDirs.state().resolve("aot");
        List<AotManifest.Entry> entries = AotManifest.list(aotDir);

        if (global.outputIsJson()) {
            CliOutput.out(toJson(aotDir, entries));
            return 0;
        }

        CommandWedge.envelopeStart();
        if (!Files.isDirectory(aotDir)) {
            CliOutput.out(CommandWedge.menu("AOT Caches"));
            detail("Directory", cc.jumpkick.cli.PathDisplay.styledRaw(aotDir) + " (not yet created)");
            detail("Caches", "0");
            return 0;
        }

        // Summary table
        List<String> headers = List.of("File", "Tool", "Status", "Size");
        List<List<String>> rows = new ArrayList<>();
        long totalBytes = 0;
        for (AotManifest.Entry e : entries) {
            long sz = e.sizeBytes() != null ? e.sizeBytes() : 0L;
            totalBytes += sz;
            rows.add(List.of(
                    e.file(),
                    nullToDash(e.tool()),
                    nullToDash(e.status()),
                    e.sizeBytes() != null ? CacheCommand.fmtBytes(e.sizeBytes()) : "—"));
        }
        for (String line : cc.jumpkick.cli.tui.BoxTable.render("AOT Caches", headers, rows)) {
            CliOutput.out(line);
        }
        CliOutput.out("  Directory: " + cc.jumpkick.cli.PathDisplay.styledRaw(aotDir));
        CliOutput.out("  "
                + entries.size()
                + " cache"
                + (entries.size() == 1 ? "" : "s")
                + ", "
                + CacheCommand.fmtBytes(totalBytes)
                + " total"
                + (Files.isRegularFile(AotManifest.path(aotDir)) ? "" : "  (no aot.toml yet — details limited)"));

        if (entries.isEmpty()) return 0;

        // Full details per cache — section header is a green pulse + path-styled name, not a
        // second CommandWedge (only the summary table uses the wedge title bar).
        for (AotManifest.Entry e : entries) {
            CliOutput.out();
            CliOutput.out(cacheSectionHeader(e.file()));
            detail("Status", nullToDash(e.status()));
            if (e.sizeBytes() != null) detail("Size", CacheCommand.fmtBytes(e.sizeBytes()));
            if (notBlank(e.tool())) detail("Tool", e.tool());
            if (notBlank(e.key())) detail("Key", e.key());
            if (notBlank(e.jkVersion())) detail("JK version", e.jkVersion());
            if (notBlank(e.jdkVendor()) || notBlank(e.jdkVersion())) {
                String jdk = joinNonBlank(" ", e.jdkVendor(), e.jdkVersion());
                detail("JDK", jdk);
            }
            if (notBlank(e.jdkHome())) detail("JDK home", e.jdkHome());
            if (notBlank(e.gc())) detail("GC", e.gc());
            if (notBlank(e.engineJar())) {
                String jar = e.engineJar();
                if (e.engineJarSize() != null) jar += " (" + CacheCommand.fmtBytes(e.engineJarSize()) + ")";
                detail("Engine jar", jar);
            }
            if (!e.classpath().isEmpty()) {
                detail("Classpath", e.classpath().size() + " entr" + (e.classpath().size() == 1 ? "y" : "ies"));
                for (String cp : e.classpath()) {
                    CliOutput.out("   " + Theme.colorize(Glyphs.bullet(), Theme.active().dim()) + " " + cp);
                }
            }
            if (!e.jvmFlags().isEmpty()) {
                detail("JVM flags", String.join(" ", e.jvmFlags()));
            }
            if (notBlank(e.created())) detail("Created", e.created());
            if (notBlank(e.lastUsed())) detail("Last used", e.lastUsed());
        }
        return 0;
    }

    /** Green ● + bold path-colored filename — detail section title under the summary table. */
    private static String cacheSectionHeader(String fileName) {
        Theme t = Theme.active();
        return Theme.colorize(Glyphs.pulse(), t.success())
                + " "
                + Theme.colorize(fileName, t.path().bold());
    }

    private static void detail(String label, String value) {
        CliOutput.out(" " + Theme.colorize(Glyphs.bullet(), Theme.active().dim()) + " "
                + String.format("%-" + LABEL_FIELD + "s", label + ":") + " " + value);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToDash(String s) {
        return notBlank(s) ? s : "—";
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!notBlank(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String toJson(Path aotDir, List<AotManifest.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"directory\":").append(Jsonl.quote(aotDir.toString()));
        sb.append(",\"manifest\":")
                .append(Files.isRegularFile(AotManifest.path(aotDir)))
                .append(",\"caches\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            AotManifest.Entry e = entries.get(i);
            sb.append('{');
            sb.append("\"file\":").append(Jsonl.quote(e.file()));
            appendJson(sb, "tool", e.tool());
            appendJson(sb, "key", e.key());
            appendJson(sb, "status", e.status());
            if (e.sizeBytes() != null) sb.append(",\"sizeBytes\":").append(e.sizeBytes());
            appendJson(sb, "jdkHome", e.jdkHome());
            appendJson(sb, "jdkVendor", e.jdkVendor());
            appendJson(sb, "jdkVersion", e.jdkVersion());
            appendJson(sb, "gc", e.gc());
            appendJson(sb, "jkVersion", e.jkVersion());
            appendJson(sb, "engineJar", e.engineJar());
            if (e.engineJarSize() != null) sb.append(",\"engineJarSize\":").append(e.engineJarSize());
            if (e.engineJarMtimeMs() != null) sb.append(",\"engineJarMtimeMs\":").append(e.engineJarMtimeMs());
            appendJson(sb, "created", e.created());
            appendJson(sb, "lastUsed", e.lastUsed());
            if (!e.classpath().isEmpty()) {
                sb.append(",\"classpath\":[")
                        .append(e.classpath().stream().map(Jsonl::quote).collect(Collectors.joining(",")))
                        .append(']');
            }
            if (!e.jvmFlags().isEmpty()) {
                sb.append(",\"jvmFlags\":[")
                        .append(e.jvmFlags().stream().map(Jsonl::quote).collect(Collectors.joining(",")))
                        .append(']');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(",\"").append(key).append("\":").append(Jsonl.quote(value));
    }
}
