// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The invariant every cache key owes the next build: <em>a step's key does not depend on what its
 * own build writes</em>. A key taken before a file the same build then writes existed (a node step's
 * tree walk before a sibling file appeared, a plugin step's classes token before build-info wrote
 * into the tree, the preflight memo's guard salt before a pack was unpacked) keys build 2
 * differently, so the second build of an unchanged tree runs the step again and stores it again
 * (JK-875KR). On content the action cache has seen before both keys hit, so the defect hides.
 *
 * <p>Under {@link KeyAuditMode} ({@code JK_KEY_AUDIT}), every step that keys its work registers the
 * key it looked up or stored under, the named tokens it derived it from, and a {@link Rekey}: a
 * closure over the step's own key owner, never a second derivation. Once the build has succeeded,
 * {@link #verify} takes every key again from the tree as the build left it; a key that differs is
 * a {@link Moved}, reported as a {@value #MOVED} warning naming the module, the step and each token
 * that moved with its old and new value, and under {@link KeyAuditMode#STRICT} it fails the build.
 *
 * <p>Held by the build's request under {@link #SLOT}, so concurrent builds in one engine never share
 * one; off a request, or with the audit off, registering costs a slot read.
 */
public final class KeyAudit {

    /** The warning code of a step whose key moved between its use and the end of its build. */
    public static final String MOVED = "key-moved";

    /** The system property a test JVM turns the audit on with, as {@code JK_KEY_AUDIT} spells it. */
    public static final String PROPERTY = "jk.keyAudit";

    /** The audit of the build a request runs. */
    public static final Request.Slot<KeyAudit> SLOT = new Request.Slot<>("key-audit");

    /** How many moved tokens one warning names before it counts the rest. */
    private static final int NAMED = 6;

    /** A key, and the named tokens it was derived from: file paths to digests, env and config names to values. */
    public record Keyed(String key, Map<String, String> tokens) {
        public Keyed {
            Objects.requireNonNull(key, "key");
            tokens = tokens == null ? Map.of() : tokens;
        }
    }

    /** A step's key taken again, through the step's own key owner, from the tree as it stands. */
    @FunctionalInterface
    public interface Rekey {
        Keyed key() throws Exception;
    }

    /** A step's key derivation, the very call the step makes: null when an input would not read. */
    @FunctionalInterface
    public interface KeySource {
        @Nullable
        String key() throws Exception;
    }

    /** The lines the key derivation running on this thread hashes, while {@link #tapped} listens. */
    private static final ScopedValue<List<String>> TAP = ScopedValue.newInstance();

    static {
        // A derivation that hashes on a pool lane taps there too.
        ContextPropagator.carry(TAP);
    }

    /**
     * A line a key owner hashes, named for the audit: a no-op unless {@link #tapped} is listening on
     * this thread. The digest helpers every owner feeds call it, so a re-key names its tokens without
     * a second list kept beside the digest.
     */
    public static void tap(String line) {
        // Carried onto a pool lane, an unbound tap arrives bound to nothing.
        List<String> lines = ContextPropagator.get(TAP);
        if (lines != null) lines.add(line);
    }

    /** Each line of {@code text}, as {@link #tap} takes one: for an owner that hashes a built-up string. */
    public static void tapLines(CharSequence text) {
        List<String> lines = ContextPropagator.get(TAP);
        if (lines == null) return;
        for (String line : text.toString().split("\\n")) {
            if (!line.isEmpty()) lines.add(line);
        }
    }

    /** {@code source}'s key with the lines it hashed on this thread as its tokens; null when it has no key. */
    public static @Nullable Keyed tapped(KeySource source) throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        String key = ScopedValue.where(TAP, lines).<String, Exception>call(source::key);
        return key == null ? null : new Keyed(key, lines("", lines));
    }

    /** One token that differs: absent before ({@code was} null), gone after ({@code now} null), or changed. */
    public record Token(
            String name, @Nullable String was, @Nullable String now) {
        String describe() {
            if (was == null) return name + " appeared" + (now == null || now.isEmpty() ? "" : " (" + brief(now) + ")");
            if (now == null) return name + " went away";
            return name + ": " + brief(was) + " -> " + brief(now);
        }
    }

    /** A step whose key, taken again once its build ended, is not the key it was used under. */
    public record Moved(Path module, String step, String was, String now, List<Token> tokens) {

        /** The warning's sentence: the step, the keys, and the tokens that moved. */
        public String describe() {
            StringBuilder sb = new StringBuilder("the key of ")
                    .append(step)
                    .append(" moved after its own build: ")
                    .append(head(was))
                    .append(" -> ")
                    .append(head(now));
            if (tokens.isEmpty()) {
                sb.append("; no token it names moved (a fact outside its token list keys it)");
            } else {
                sb.append("; ");
                for (int i = 0; i < Math.min(NAMED, tokens.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(tokens.get(i).describe());
                }
                if (tokens.size() > NAMED)
                    sb.append(" and ").append(tokens.size() - NAMED).append(" more");
            }
            return sb.append("; the next build runs it again").toString();
        }
    }

    private record Entry(Path module, String step, Keyed keyed, Rekey rekey) {}

    private final KeyAuditMode mode;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> remembered = new ConcurrentHashMap<>();

    public KeyAudit(KeyAuditMode mode) {
        this.mode = mode;
    }

    public KeyAuditMode mode() {
        return mode;
    }

    /**
     * The mode a build starting now runs under: its session's, else {@link #PROPERTY} (a test JVM
     * that audits every build it runs).
     */
    public static KeyAuditMode requested() {
        KeyAuditMode m = SessionContext.current().keyAudit();
        return m.on() ? m : KeyAuditMode.parse(System.getProperty(PROPERTY));
    }

    /** The audit of the build running now, or null when it is not audited. */
    public static @Nullable KeyAudit current() {
        Request request = Request.current();
        return request == null ? null : request.get(SLOT);
    }

    /** Whether the build running now is audited: a step derives its audit tokens only then. */
    public static boolean on() {
        return current() != null;
    }

    /**
     * The step {@code step} of the module at {@code module} used {@code keyed}; {@code rekey} takes
     * it again. A later registration of the same step replaces an earlier one: a step that looked
     * up one key and stored under another (a suite's discovered inputs folded in, a node record
     * stored again without a file the build wrote) is held to the last.
     */
    public static void keyed(Path module, String step, Keyed keyed, Rekey rekey) {
        KeyAudit audit = current();
        if (audit == null) return;
        Path dir = module.toAbsolutePath().normalize();
        audit.entries.put(dir + "\u0000" + step, new Entry(dir, step, keyed, rekey));
    }

    /** As {@link #keyed(Path, String, Keyed, Rekey)} for a key with {@code tokens}. */
    public static void keyed(Path module, String step, String key, Map<String, String> tokens, Rekey rekey) {
        keyed(module, step, new Keyed(key, tokens), rekey);
    }

    /**
     * The step used {@code key}, which {@code source} derives: the step's own key call, taken once
     * more now to name its tokens and again once the build has ended. A no-op when the build is not
     * audited or {@code key} is null; a derivation that throws now leaves the step unaudited.
     */
    public static void used(Path module, String step, @Nullable String key, KeySource source) {
        if (key == null || !on()) return;
        Keyed was;
        try {
            was = tapped(source);
        } catch (Exception e) {
            Log.debug("key audit: " + step + " of " + module + " would not re-key at registration", e);
            return;
        }
        Map<String, String> tokens = was == null ? Map.of() : was.tokens();
        keyed(module, step, new Keyed(key, tokens), () -> {
            Keyed now = tapped(source);
            return now == null ? new Keyed("(no key: an input would not read)", Map.of()) : now;
        });
    }

    /**
     * As {@link #used(Path, String, String, KeySource)} for a step whose owner names its inputs
     * itself ({@code rekey}'s tokens, as a record's inputs name them: a compile's sources and
     * classpath entries by path) rather than through the lines it hashes.
     */
    public static void usedNaming(Path module, String step, @Nullable String key, Rekey rekey) {
        if (key == null || !on()) return;
        Map<String, String> tokens;
        try {
            tokens = rekey.key().tokens();
        } catch (Exception e) {
            Log.debug("key audit: " + step + " of " + module + " would not re-key at registration", e);
            return;
        }
        keyed(module, step, new Keyed(key, tokens), rekey);
    }

    /**
     * The module a step's output under {@code output} belongs to, for the warning: the nearest
     * directory above it holding a {@code jk.toml}, else {@code output} itself.
     */
    public static Path moduleOf(Path output) {
        Path abs = output.toAbsolutePath().normalize();
        for (Path p = abs; p != null; p = p.getParent()) {
            if (Files.isRegularFile(ManifestPaths.manifestIn(p))) return p;
        }
        return abs;
    }

    /** A qualified task id's step: {@code compile-main@3f2a…} is {@code compile-main}. */
    public static String stepOf(String taskId) {
        int at = taskId.indexOf('@');
        return at < 0 ? taskId : taskId.substring(0, at);
    }

    /**
     * The tokens a key was derived from, kept for a step that hashes them before it knows it will
     * register: the preflight's module fingerprints, taken before the build and certified after.
     */
    public static void remember(String key, Map<String, String> tokens) {
        KeyAudit audit = current();
        if (audit != null) audit.remembered.put(key, Map.copyOf(tokens));
    }

    /** What {@link #remember} kept under {@code key}; empty when nothing was. */
    public static Map<String, String> remembered(String key) {
        KeyAudit audit = current();
        Map<String, String> t = audit == null ? null : audit.remembered.get(key);
        return t == null ? Map.of() : t;
    }

    /**
     * Every registered key taken again from the tree as it stands: the steps whose key moved, by
     * module then step. A step whose key cannot be taken again is reported as moved to the failure,
     * so a re-key that broke is seen rather than read as a pass.
     */
    public List<Moved> verify() {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparing((Entry e) -> e.module().toString()).thenComparing(Entry::step));
        // Each re-key reads only its own step's inputs: side by side, as the steps ran.
        List<CompletableFuture<@Nullable Moved>> pending = new ArrayList<>(all.size());
        for (Entry e : all) pending.add(CompletableFuture.supplyAsync(() -> check(e), JkThreads.cpu()));
        List<Moved> moved = new ArrayList<>();
        for (CompletableFuture<@Nullable Moved> f : pending) {
            Moved m = f.join();
            if (m != null) moved.add(m);
        }
        return moved;
    }

    /** {@code e}'s key taken again: null when it held, else how it moved. */
    private static @Nullable Moved check(Entry e) {
        Keyed now;
        try {
            now = e.rekey().key();
        } catch (Exception | LinkageError failed) {
            Log.debug("key audit: " + e.step() + " of " + e.module() + " would not re-key", failed);
            String why = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            return new Moved(
                    e.module(),
                    e.step(),
                    e.keyed().key(),
                    "(none)",
                    List.of(new Token("the re-key", "", "failed: " + why)));
        }
        if (now.key().equals(e.keyed().key())) return null;
        Moved m = new Moved(
                e.module(), e.step(), e.keyed().key(), now.key(), diff(e.keyed().tokens(), now.tokens()));
        Log.info("key audit: " + e.module() + " " + m.describe());
        return m;
    }

    /**
     * Drop every registration: each re-key closes over its step's context, and with it the plan's
     * state, which an audit that outlived its build would keep alive.
     */
    public void close() {
        entries.clear();
        remembered.clear();
    }

    /** How many steps this audit holds a key for. */
    public int size() {
        return entries.size();
    }

    /** The tokens that differ between {@code was} and {@code now}, in name order. */
    public static List<Token> diff(Map<String, String> was, Map<String, String> now) {
        TreeSet<String> names = new TreeSet<>(was.keySet());
        names.addAll(now.keySet());
        List<Token> out = new ArrayList<>();
        for (String name : names) {
            String a = was.get(name);
            String b = now.get(name);
            if (Objects.equals(a, b)) continue;
            out.add(new Token(name, a, b));
        }
        return out;
    }

    /**
     * Hashed lines as one name-to-value map for {@link Keyed}: a line {@code name=value} or {@code
     * name:value}, split at its last {@code :} or first {@code =} when what follows is a digest, is
     * that token; any other line is a token named by what it says, valued empty, so a line that
     * appears or goes is named whole.
     */
    public static Map<String, String> lines(String prefix, List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String t = raw.strip();
            int colon = t.lastIndexOf(':');
            int eq = t.indexOf('=');
            String name;
            String value;
            if (colon > 0 && DIGEST.matcher(t.substring(colon + 1)).matches()) {
                name = t.substring(0, colon);
                value = t.substring(colon + 1);
            } else if (eq > 0) {
                name = t.substring(0, eq);
                value = t.substring(eq + 1);
            } else {
                name = t;
                value = "";
            }
            out.merge(prefix + name, value, (x, y) -> x + "," + y);
        }
        return out;
    }

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{16,}");

    /** A key by its head, as the action cache's own short form reads it; a sentence in parentheses kept whole. */
    private static String head(String key) {
        return key.startsWith("(") || key.length() <= 12 ? key : key.substring(0, 12);
    }

    private static String brief(String value) {
        if (value.length() <= 16) return value;
        // A digest reads by its head; a path or a sentence is kept whole up to a point.
        if (value.matches("(?:sha256:|file:|abi:)?[0-9a-f]{20,}"))
            return value.substring(0, Math.min(value.length(), 12));
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
