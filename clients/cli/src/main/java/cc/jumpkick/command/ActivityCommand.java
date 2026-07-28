// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.plugin.protocol.MiniJson;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedStyle;

/**
 * {@code jk activity} — Activity-page style feed of recent builds. Reads the same on-disk journal
 * the web UI uses ({@code ~/.jk/state/builds/journal/}), not the engine's flat history-entry
 * projection, so fields like {@code buildNumber} always match the dashboard (even when a resident
 * engine is mid-dogfood-skew).
 *
 * <pre>
 * ✓ #31 Success  group:name · build · 8 modules · 2.3s · 3h ago
 * </pre>
 */
public final class ActivityCommand implements CliCommand {

    /** Default rows — matches the web Activity feed window. */
    static final int DEFAULT_LIMIT = 30;

    @Override
    public String name() {
        return "activity";
    }

    @Override
    public List<String> aliases() {
        return List.of("act");
    }

    @Override
    public String description() {
        return "Show recent builds (Activity feed)";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
                "<N>", "Maximum number of runs to show (default: " + DEFAULT_LIMIT + ").", "--limit", "-n"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        int limit = in.value("limit").map(ActivityCommand::parseLimit).orElse(DEFAULT_LIMIT);
        List<String> records = loadJournalRecords(JkDirs.builds().resolve("journal"), limit);
        if (records.isEmpty()) {
            CliOutput.out("No activity yet — run a build and it will appear here.");
            return 0;
        }
        long now = System.currentTimeMillis();
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();
        for (String json : records) {
            CliOutput.out(formatRecord(json, now, nerdfont, t));
        }
        return 0;
    }

    /**
     * Newest-first {@code record.json} bodies under the journal dir (entry ids are
     * time-sortable). Best-effort; skips unreadable entries.
     */
    static List<String> loadJournalRecords(Path journalDir, int limit) {
        if (journalDir == null || !Files.isDirectory(journalDir)) return List.of();
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".")) {
                    dirs.add(p);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        // Id = directory name = yyyyMMdd'T'HHmmssSSS-xxxx — reverse lexicographic = newest first.
        dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        List<String> out = new ArrayList<>();
        for (Path dir : dirs) {
            if (out.size() >= limit) break;
            Path rec = dir.resolve("record.json");
            if (!Files.isRegularFile(rec)) continue;
            try {
                out.add(Files.readString(rec, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // skip
            }
        }
        return out;
    }

    /** One Activity row from a journal {@code record.json} (or flat history-entry JSONL). */
    static String formatRecord(String json, long now, boolean nerdfont, Theme t) {
        boolean running = bool(json, "running");
        boolean success = bool(json, "success");
        boolean cancelled = bool(json, "cancelled");
        long buildNumber = longField(json, "buildNumber");
        String kind = str(json, "kind");
        if (kind == null || kind.isBlank()) kind = "build";
        int moduleCount = moduleCount(json);
        if (moduleCount <= 0 && !running) moduleCount = 1;
        long millis = longField(json, "millis");
        long finishedAt = longField(json, "finishedAt");
        long startedAt = longField(json, "startedAt");

        String outcomeWord;
        String glyph;
        AttributedStyle chipBody;
        AttributedStyle chipCaps;
        if (running) {
            outcomeWord = "Running";
            glyph = Glyphs.PLAY;
            chipBody = t.pipelineChip();
            chipCaps = t.bright(t.planBadgeColor());
        } else if (cancelled) {
            outcomeWord = "Cancelled";
            glyph = "⊛";
            chipBody = t.withBackground(t.brightWhite(), t.planBadgeColor());
            chipCaps = t.bright(t.planBadgeColor());
        } else if (success) {
            outcomeWord = "Success";
            glyph = Glyphs.CHECK;
            chipBody = t.pipelineSuccessChip();
            chipCaps = t.bright(t.pipelineChipColor());
        } else {
            outcomeWord = "Failure";
            glyph = Glyphs.CROSS;
            chipBody = t.pipelineFailureChip();
            chipCaps = t.bright(t.pipelineFailColor());
        }

        String num = buildNumber > 0 ? "#" + buildNumber : "#—";
        String pillLabel = glyph + " " + num + " " + outcomeWord;
        String pill;
        if (!t.isAnsi()) {
            String prefix = success && !cancelled && !running ? "+" : (!success && !cancelled && !running ? "!" : "*");
            pill = prefix + " " + num + " " + outcomeWord;
        } else {
            pill = Badge.pill(pillLabel, nerdfont, chipBody, chipCaps);
        }

        String coordPart = formatCoord(str(json, "coord"), str(json, "dir"), t);
        String modulesPart = moduleCount == 1 ? "1 module" : moduleCount + " modules";
        String durationPart = running
                ? (startedAt > 0 ? HistoryCommand.duration(Math.max(0, now - startedAt)) : "…")
                : HistoryCommand.duration(millis);
        String agoPart = running
                ? "now"
                : HistoryCommand.ago(finishedAt > 0 ? finishedAt : startedAt, now);
        if (agoPart == null) agoPart = "";

        String sep = t.isAnsi() ? Theme.colorize(" · ", t.darkGray()) : " · ";
        StringBuilder line = new StringBuilder();
        line.append(pill).append(" ");
        line.append(coordPart);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(kind, t.normalGray()) : kind);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(modulesPart, t.normalGray()) : modulesPart);
        line.append(sep).append(t.isAnsi() ? Theme.colorize(durationPart, t.normalGray()) : durationPart);
        if (!agoPart.isEmpty()) {
            line.append(sep).append(t.isAnsi() ? Theme.colorize(agoPart, t.dim()) : agoPart);
        }
        return line.toString();
    }

    /** Module count from nested {@code modules:[]} or flat {@code moduleCount}. */
    @SuppressWarnings("unchecked")
    static int moduleCount(String json) {
        int flat = (int) longField(json, "moduleCount");
        if (flat > 0) return flat;
        try {
            Object root = MiniJson.parse(json);
            if (root instanceof Map<?, ?> m && m.get("modules") instanceof List<?> list) {
                return list.size();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return 0;
    }

    static String formatCoord(String coord, String dir, Theme t) {
        String raw = HistoryCommand.label(coord, dir);
        if (!t.isAnsi()) return raw;
        int i = raw.indexOf(':');
        if (i <= 0 || i >= raw.length() - 1) {
            return Theme.colorize(raw, t.coordName().bold());
        }
        return Theme.colorize(raw.substring(0, i), t.coordGroup())
                + Theme.colorize(":", t.darkGray())
                + Theme.colorize(raw.substring(i + 1), t.coordName().bold());
    }

    private static String str(String json, String key) {
        // Pretty journal JSON may have spaces after ':' — Jsonl.str is strict; MiniJson is reliable.
        try {
            Object root = MiniJson.parse(json);
            if (root instanceof Map<?, ?> m && m.get(key) instanceof String s) return s;
        } catch (RuntimeException ignored) {
            // fall through
        }
        return Jsonl.str(json, key);
    }

    private static long longField(String json, String key) {
        try {
            Object root = MiniJson.parse(json);
            if (root instanceof Map<?, ?> m && m.get(key) instanceof Number n) return n.longValue();
        } catch (RuntimeException ignored) {
            // fall through
        }
        return Jsonl.longValue(json, key, 0);
    }

    private static boolean bool(String json, String key) {
        try {
            Object root = MiniJson.parse(json);
            if (root instanceof Map<?, ?> m && m.get(key) instanceof Boolean b) return b;
        } catch (RuntimeException ignored) {
            // fall through
        }
        return Jsonl.bool(json, key, false);
    }

    private static int parseLimit(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
