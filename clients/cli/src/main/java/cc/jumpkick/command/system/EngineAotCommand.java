// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk engine aot} — list JEP 514 AOT caches under {@code state/aot/} with human-readable
 * details from {@code aot.toml} (and filename/size fallback for pre-manifest caches).
 */
public final class EngineAotCommand implements CliCommand {

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
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
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
            detail("Directory", PathDisplay.styledRaw(aotDir) + " (not yet created)");
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
        for (String line : Table.render("AOT Caches", headers, rows)) {
            CliOutput.out(line);
        }
        CliOutput.out("  Directory: " + PathDisplay.styledRaw(aotDir));
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
                detail(
                        "Classpath",
                        e.classpath().size() + " entr" + (e.classpath().size() == 1 ? "y" : "ies"));
                for (String cp : e.classpath()) {
                    CliOutput.out("   "
                            + Theme.colorize(Glyphs.bullet(), Theme.active().dim()) + " " + cp);
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
    private static String cacheSectionHeader(@Nullable String fileName) {
        Theme t = Theme.active();
        return Theme.colorize(Glyphs.pulse(), t.success())
                + " "
                + Theme.colorize(fileName, t.path().bold());
    }

    private static void detail(@Nullable String label, @Nullable String value) {
        CliOutput.out(" " + Theme.colorize(Glyphs.bullet(), Theme.active().dim()) + " "
                + String.format("%-" + LABEL_FIELD + "s", label + ":") + " " + value);
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static @Nullable String nullToDash(@Nullable String s) {
        return notBlank(s) ? s : "—";
    }

    private static String joinNonBlank(String sep, @Nullable String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!notBlank(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    public static String toJson(Path aotDir, List<AotManifest.Entry> entries) {
        List<String> caches = new ArrayList<>();
        for (AotManifest.Entry e : entries) {
            JsonFields cache = JsonFields.object()
                    .string("file", e.file())
                    .optionalNonBlankString("tool", e.tool())
                    .optionalNonBlankString("key", e.key())
                    .optionalNonBlankString("status", e.status());
            if (e.sizeBytes() != null) cache.number("sizeBytes", e.sizeBytes());
            cache.optionalNonBlankString("jdkHome", e.jdkHome())
                    .optionalNonBlankString("jdkVendor", e.jdkVendor())
                    .optionalNonBlankString("jdkVersion", e.jdkVersion())
                    .optionalNonBlankString("gc", e.gc())
                    .optionalNonBlankString("jkVersion", e.jkVersion())
                    .optionalNonBlankString("engineJar", e.engineJar());
            if (e.engineJarSize() != null) cache.number("engineJarSize", e.engineJarSize());
            if (e.engineJarMtimeMs() != null) cache.number("engineJarMtimeMs", e.engineJarMtimeMs());
            cache.optionalNonBlankString("created", e.created())
                    .optionalNonBlankString("lastUsed", e.lastUsed())
                    .optionalArray("classpath", e.classpath())
                    .optionalArray("jvmFlags", e.jvmFlags());
            caches.add(cache.finish());
        }
        return JsonFields.object()
                .string("directory", aotDir.toString())
                .bool("manifest", Files.isRegularFile(AotManifest.path(aotDir)))
                .token("caches", "[" + String.join(",", caches) + "]")
                .finish();
    }
}
