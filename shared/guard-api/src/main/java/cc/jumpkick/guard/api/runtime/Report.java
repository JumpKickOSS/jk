// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.MetricSite;
import cc.jumpkick.guard.api.ModelSite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Site;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.ToolSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One JSON line per guard: what it declared and what it found, for the engine to reconcile with the
 * baseline. The file is appended, never rewritten: the engine truncates it before a run.
 */
public final class Report {

    private Report() {}

    public record AllowEntry(String in, String reason) {}

    /** What one guard reported. */
    public static final class Collector implements Violations {
        final List<String[]> sites = new ArrayList<>(); // fingerprint, file, line, detail, value-or-null
        long population = -1;

        @Nullable
        String error;

        String outcome = "ok";

        @Override
        public void add(Site site, String detail) {
            sites.add(new String[] {
                site.fingerprint(), site.file(), Integer.toString(site.line()), detail, null, rootOf(site)
            });
        }

        @Override
        public void metric(MetricSite site, String detail) {
            sites.add(new String[] {
                site.fingerprint(), site.file(), "0", detail, Double.toString(site.value()), "workspace"
            });
        }

        /** Bytecode sites name a file under a source root; text, tool and metric sites name it from the workspace root. */
        private static String rootOf(Site site) {
            return site instanceof TextSite
                            || site instanceof ToolSite
                            || site instanceof MetricSite
                            || site instanceof ModelSite
                    ? "workspace"
                    : "source";
        }

        @Override
        public void population(long examined) {
            population = examined;
        }

        public void threw(Throwable t) {
            outcome = "threw";
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, 6); i++)
                sb.append(lines[i].strip()).append(i == 0 ? "" : "").append('\n');
            error = sb.toString().strip();
        }

        public void ownerMissing(String message) {
            outcome = "owner-missing";
            error = message;
        }

        /** The guard did not run here; {@code reason} is the notice the engine prints. */
        public void skipped(String reason) {
            outcome = "skipped";
            error = reason;
        }
    }

    /** One guard's line. */
    public record Line(
            String id,
            String why,
            String instead,
            String source,
            Scope scope,
            List<AllowEntry> allows,
            @Nullable String fixture,
            List<String> params,
            Collector collector) {

        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"id\":").append(Jsonl.quote(id));
            sb.append(",\"why\":").append(Jsonl.quote(why));
            sb.append(",\"instead\":").append(Jsonl.quote(instead));
            sb.append(",\"source\":").append(Jsonl.quote(source));
            sb.append(",\"scope\":").append(Jsonl.quote(scope.name().toLowerCase(Locale.ROOT)));
            sb.append(",\"params\":[");
            for (int i = 0; i < params.size(); i++) sb.append(i > 0 ? "," : "").append(Jsonl.quote(params.get(i)));
            sb.append("],\"allows\":[");
            for (int i = 0; i < allows.size(); i++) {
                sb.append(i > 0 ? "," : "")
                        .append("{\"in\":")
                        .append(Jsonl.quote(allows.get(i).in()))
                        .append(",\"reason\":")
                        .append(Jsonl.quote(allows.get(i).reason()))
                        .append('}');
            }
            sb.append(']');
            if (fixture != null) sb.append(",\"fixture\":").append(Jsonl.quote(fixture));
            sb.append(",\"outcome\":").append(Jsonl.quote(collector.outcome));
            if (collector.error != null) sb.append(",\"error\":").append(Jsonl.quote(collector.error));
            if (collector.population >= 0) sb.append(",\"population\":").append(collector.population);
            sb.append(",\"violations\":[");
            for (int i = 0; i < collector.sites.size(); i++) {
                String[] s = collector.sites.get(i);
                sb.append(i > 0 ? "," : "").append("{\"fingerprint\":").append(Jsonl.quote(s[0]));
                if (s[1] != null) sb.append(",\"file\":").append(Jsonl.quote(s[1]));
                sb.append(",\"line\":").append(s[2]);
                sb.append(",\"detail\":").append(Jsonl.quote(s[3]));
                if (s[4] != null) sb.append(",\"value\":").append(s[4]);
                sb.append(",\"root\":").append(Jsonl.quote(s[5]));
                sb.append('}');
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    public static synchronized void append(Path file, Line line) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(
                file,
                line.toJson() + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
