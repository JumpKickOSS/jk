// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One change JumpKick made outside a project.
 *
 * @param id sortable by time and unique: a time stamp, a dash, and a random suffix; the paging
 *     cursor
 * @param at when, in epoch milliseconds
 * @param what the file or resource: {@code ~/.jk/config.toml}, {@code JDK temurin-25}, {@code cache
 *     tier}
 * @param action what happened to it: {@link #EDIT}, {@link #INSTALL}, {@link #UNINSTALL}, {@link
 *     #CLEAN}, {@link #DELETE}, {@link #STORE}, {@link #CLEAR}, {@link #APPLY}
 * @param summary one line a person reads
 * @param diff a unified diff of the file, secrets masked, or a before/after summary; null when the
 *     change has no text to show
 * @param actor who asked for it
 */
public record Change(
        String id,
        long at,
        String what,
        String action,
        String summary,
        @Nullable String diff,
        ChangeActor actor) {

    /** The record kind every journal line carries. */
    public static final String KIND = "change";

    public static final String EDIT = "edit";
    public static final String INSTALL = "install";
    public static final String UNINSTALL = "uninstall";
    public static final String CLEAN = "clean";
    public static final String DELETE = "delete";
    public static final String STORE = "store";
    public static final String CLEAR = "clear";
    public static final String APPLY = "apply";

    public Change {
        Objects.requireNonNull(id);
        Objects.requireNonNull(what);
        Objects.requireNonNull(action);
        Objects.requireNonNull(summary);
        Objects.requireNonNull(actor);
    }

    /** The journal line's shape, which is also the API's. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", KIND);
        m.put("id", id);
        m.put("at", at);
        m.put("what", what);
        m.put("action", action);
        m.put("summary", summary);
        if (diff != null) m.put("diff", diff);
        m.put("actor", actor.toMap());
        return m;
    }

    /** The change a journal line holds, or null for a line of another kind or a broken one. */
    static @Nullable Change of(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m) || !KIND.equals(m.get("kind"))) return null;
        ChangeActor actor = ChangeActor.of(m.get("actor"));
        if (actor == null
                || !(m.get("id") instanceof String id)
                || !(m.get("at") instanceof Number at)
                || !(m.get("what") instanceof String what)
                || !(m.get("action") instanceof String action)
                || !(m.get("summary") instanceof String summary)) return null;
        return new Change(
                id, at.longValue(), what, action, summary, m.get("diff") instanceof String d ? d : null, actor);
    }
}
