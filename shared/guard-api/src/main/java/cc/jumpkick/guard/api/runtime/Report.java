// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.MetricSite;
import cc.jumpkick.guard.api.ModelSite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Site;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.ToolSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.jsonl.JsonFields;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
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

        List<String> tools = List.of();

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

        /**
         * The exception line, its top frames, and the first frame outside the JDK when the top ones
         * are all JDK code: a throw inside a collection or a regex still names the guard line that
         * reached it.
         */
        public void threw(Throwable t) {
            outcome = "threw";
            StackTraceElement[] frames = t.getStackTrace();
            StringBuilder sb = new StringBuilder(t.toString());
            int shown = Math.min(frames.length, TOP_FRAMES);
            for (int i = 0; i < shown; i++) sb.append("\nat ").append(frames[i]);
            for (int i = 0; i < frames.length; i++) {
                if (jdk(frames[i])) continue;
                if (i >= shown) sb.append("\n...\nat ").append(frames[i]);
                break;
            }
            error = sb.toString();
        }

        private static final int TOP_FRAMES = 4;

        private static boolean jdk(StackTraceElement frame) {
            String c = frame.getClassName();
            return c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.");
        }

        public void ownerMissing(String message) {
            outcome = "owner-missing";
            error = message;
        }

        /** The guard did not run here; {@code reason} is the notice the engine prints. */
        public void skipped(String reason) {
            skipped(reason, List.of());
        }

        /** As {@link #skipped(String)}, for want of every one of {@code missing}. */
        public void skipped(String reason, List<String> missing) {
            outcome = "skipped";
            error = reason;
            tools = List.copyOf(missing);
        }

        /** The guard does not apply on this operating system; {@code reason} is the notice. */
        public void notHere(String reason) {
            outcome = "not-here";
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
            StringJoiner allowsJson = new StringJoiner(",", "[", "]");
            for (AllowEntry allow : allows) {
                allowsJson.add(JsonFields.object()
                        .string("in", allow.in())
                        .string("reason", allow.reason())
                        .finish());
            }
            JsonFields json = JsonFields.object()
                    .string("id", id)
                    .string("why", why)
                    .string("instead", instead)
                    .string("source", source)
                    .string("scope", scope.name().toLowerCase(Locale.ROOT))
                    .array("params", params)
                    .token("allows", allowsJson.toString())
                    .optionalString("fixture", fixture)
                    .string("outcome", collector.outcome)
                    .optionalString("error", collector.error)
                    .optionalArray("tools", collector.tools)
                    .optionalNumber("population", collector.population, -1);
            StringJoiner violations = new StringJoiner(",", "[", "]");
            for (String[] s : collector.sites) {
                JsonFields site = JsonFields.object()
                        .string("fingerprint", s[0])
                        .optionalString("file", s[1])
                        .token("line", s[2])
                        .string("detail", s[3]);
                if (s[4] != null) site.token("value", s[4]);
                violations.add(site.string("root", s[5]).finish());
            }
            return json.token("violations", violations.toString()).finish();
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
