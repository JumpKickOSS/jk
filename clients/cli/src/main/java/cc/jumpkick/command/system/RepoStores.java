// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The repository stores under {@code <store>/repos/} as the engine's {@code repos} inventory
 * reports them: each tree's id, the name it was first filled under, and the origin that filled
 * it. The store is keyed by origin, so the name is only a label — and a tree that carries no
 * origin was keyed by a name, is read by nothing, and is reclaimed by {@code jk storage clean}.
 * Shared by {@code jk storage usage} and {@code jk doctor}.
 */
public final class RepoStores {

    private RepoStores() {}

    /** One repository store. {@code origin} is null for the first-party shelf and for a legacy tree. */
    public record Store(String id, String name, @Nullable String origin, long files, long bytes, boolean legacy) {}

    /** The rows, or the reason there are none. */
    public record Stores(List<Store> rows, @Nullable String error) {}

    /** Decode {@code id|name|origin|files|bytes|state} rows. */
    public static Stores decode(CacheInventoryAck ack) {
        if (ack.error() != null) return new Stores(List.of(), ack.error());
        List<Store> rows = new ArrayList<>();
        for (String line : ack.lines()) {
            String[] f = line.split("\\|", 6);
            if (f.length < 6) continue;
            rows.add(new Store(
                    f[0],
                    f[1].isBlank() ? f[0] : f[1],
                    f[2].isBlank() ? null : f[2],
                    parseLong(f[3]),
                    parseLong(f[4]),
                    "legacy".equals(f[5])));
        }
        return new Stores(List.copyOf(rows), null);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * One line per store: {@code repo: <name> → <origin> (repos/<id>, N files, size)}; the
     * first-party shelf names itself; a legacy tree is a warning naming the command that removes it.
     */
    public static List<String> render(Stores stores, Theme t) {
        List<String> out = new ArrayList<>();
        if (stores.error() != null) {
            out.add(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("repos", t.cyan()) + " — "
                    + stores.error());
            return out;
        }
        if (stores.rows().isEmpty()) {
            out.add(Theme.colorize("ok:      ", t.completedStep()) + " " + Theme.colorize("repos", t.cyan())
                    + " — no repository store yet (the first resolve creates one per origin)");
            return out;
        }
        for (Store s : stores.rows()) {
            String size = CacheCommand.fmtCount(s.files())
                    + (s.files() == 1 ? " file, " : " files, ")
                    + CacheCommand.fmtBytes(s.bytes());
            String where = Theme.colorize("repos/" + s.id(), t.path());
            if (s.legacy()) {
                out.add(Theme.colorize("warn:    ", t.warning()) + " " + Theme.colorize(s.name(), t.cyan())
                        + " — keyed by repository name, origin unknown; nothing reads it (" + where + ", " + size
                        + ") — jk storage clean removes it");
                continue;
            }
            String origin = s.origin() == null ? "first-party shelf (jk install)" : s.origin();
            out.add(Theme.colorize("repo:    ", t.completedStep()) + " " + Theme.colorize(s.name(), t.cyan()) + " "
                    + Theme.colorize("→", t.darkGray()) + " " + origin + " (" + where + ", " + size + ")");
        }
        return out;
    }

    /** The {@code repos} member of a JSON report: an array of store objects, or an error string. */
    public static String json(Stores stores) {
        if (stores.error() != null) {
            return JsonFields.object().string("error", stores.error()).finish();
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < stores.rows().size(); i++) {
            Store s = stores.rows().get(i);
            if (i > 0) array.append(',');
            array.append(JsonFields.object()
                    .string("id", s.id())
                    .string("name", s.name())
                    .string("origin", s.origin())
                    .number("files", s.files())
                    .number("bytes", s.bytes())
                    .string("state", s.legacy() ? "legacy" : "ok")
                    .finish());
        }
        return array.append(']').toString();
    }
}
