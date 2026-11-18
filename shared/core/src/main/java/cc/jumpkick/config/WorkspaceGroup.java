// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * A group root: a {@code jk.toml} whose {@code [workspaces]} table lists self-contained workspaces,
 * and nothing else. The root contributes nothing a member inherits: a member builds the same with
 * or without it, each keeping its own lock, version and {@code target/}. From the root a build verb
 * runs over the members in the order the {@code path} dependencies between them give.
 *
 * <pre>{@code
 * [workspaces]
 * members = ["cache", "insights", "examples/*"]
 * exclude = [{ path = "examples/android-app", reason = "downloads the Android SDK" }]
 *
 * [profiles.all.workspaces]
 * include = ["examples/android-app"]
 * }</pre>
 *
 * <p>A member is a workspace or a single project; it is never a group root itself. An excluded
 * member stays a member (it is named, released and checked like any other) but the root's verbs
 * skip it, printing its reason, unless the profile {@code -p} names includes it again or a selected
 * member depends on it.
 *
 * @param root the group root's directory
 * @param members every member, in declared order, globs expanded
 * @param missing literal {@code members} entries with no project in their directory
 * @param profiles each root profile's {@code include} patterns, by profile name
 */
public record WorkspaceGroup(
        Path root, List<Member> members, List<String> missing, Map<String, List<String>> profiles) {

    /** The table that makes a {@code jk.toml} a group root. */
    public static final String TABLE = "workspaces";

    /** The verbs that run at a group root over its members, in the order groups.md lists them. */
    public static final List<String> ROOT_VERBS = List.of(
            "build", "compile", "test", "native", "image", "sync", "train", "install", "guard", "format", "explain",
            "clean", "lock");

    /** What the list is called in errors. */
    static final String MEMBERS = "[workspaces] members";

    static final List<String> TOP_LEVEL = List.of(TABLE, "profiles");
    static final List<String> KEYS = List.of("members", "exclude");
    static final List<String> EXCLUDE_KEYS = List.of("path", "reason");
    static final List<String> PROFILE_KEYS = List.of(TABLE);
    static final List<String> PROFILE_WORKSPACES_KEYS = List.of("include");

    /**
     * One member.
     *
     * @param path its directory relative to the root, {@code /}-separated
     * @param dir its directory
     * @param excludedBecause the reason its {@code exclude} entry gives, or {@code null} when the
     *     root's verbs run it by default
     */
    public record Member(String path, Path dir, @Nullable String excludedBecause) {

        public boolean excluded() {
            return excludedBecause != null;
        }

        /**
         * The member's artifact name, its {@code jk.toml}'s {@code name}: what its release tag
         * carries and what selects it besides its path. Its last directory name when it has none.
         */
        public String name() {
            String name = TomlScan.scan(ManifestPaths.manifestIn(dir), "name").get("name");
            if (name != null && !name.isBlank()) return name;
            Path leaf = dir.getFileName();
            return leaf == null ? path : leaf.toString();
        }
    }

    /**
     * What a root verb runs.
     *
     * @param run the members to run, dependencies first
     * @param skipped excluded members left out, each with its reason
     * @param edges member dir → the member dirs its {@code path} dependencies name
     */
    public record Selection(List<Member> run, List<Member> skipped, Map<Path, Set<Path>> edges) {

        /** The members {@code m} depends on directly through {@code path} dependencies. */
        public Set<Path> prerequisites(Member m) {
            return edges.getOrDefault(m.dir(), Set.of());
        }
    }

    /**
     * Why a command that needs a project cannot run at {@code dir}, or null when {@code dir} is no
     * group root: the refusal a parse of its {@code jk.toml} as a project gives, said before any job
     * starts, so the command leaves nothing behind at the root.
     */
    public static @Nullable String notAProject(Path dir) throws IOException {
        if (!declares(dir)) return null;
        Path manifest = dir.resolve(ManifestPaths.MANIFEST);
        JkBuildParseException refusal = asProject(JkBuildParser.document(manifest));
        return refusal == null ? null : refusal.locatedIn(manifest).getMessage();
    }

    /** True when {@code dir}'s {@code jk.toml} declares {@code [workspaces]}: a key scan, no parse. */
    public static boolean declares(Path dir) {
        Path manifest = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return false;
        TomlScan scan = TomlScan.scan(manifest, TABLE + ".members");
        return scan.hasSection(TABLE) || scan.hasKey(TABLE + ".members");
    }

    /**
     * The group root that lists {@code dir} as a member: the nearest strict ancestor that is a group
     * root and whose members name it, excluded or not. {@code null} when none does, so a workspace
     * that merely sits below a group root without being listed is standalone. Lexical but for the
     * one file check a glob entry needs, so a release can ask it cheaply.
     */
    public static @Nullable Path listing(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        SearchCeiling ceiling = SearchCeiling.current();
        for (Path parent = ceiling.above(normalized); parent != null; parent = ceiling.above(parent)) {
            if (!declares(parent)) continue;
            String rel = parent.relativize(normalized).toString().replace('\\', '/');
            List<String> entries = TomlScan.scan(parent.resolve(ManifestPaths.MANIFEST), TABLE + ".members")
                    .stringArray(TABLE + ".members");
            return WorkspaceModules.lists(parent, entries, rel) ? parent : null;
        }
        return null;
    }

    /**
     * The refusal for a group root read as a project: it has no identity, dependencies or build of
     * its own, so a command that needs one runs in a member. Null when {@code document} declares no
     * {@code [workspaces]}.
     */
    static @Nullable JkBuildParseException asProject(TomlParseResult document) {
        if (!document.contains(TABLE)) return null;
        List<String> named = new ArrayList<>();
        TomlArray members = document.get(List.of(TABLE)) instanceof TomlTable t ? t.getArray("members") : null;
        if (members != null) {
            for (int i = 0; i < members.size() && named.size() < 3; i++) {
                if (members.get(i) instanceof String s && !s.isBlank()) named.add(s);
            }
        }
        String example = named.isEmpty() ? "<member>" : named.getFirst();
        return JkBuildParseException.at(
                document,
                TABLE,
                "this jk.toml groups workspaces"
                        + (named.isEmpty() ? "" : " (" + String.join(", ", named) + ")")
                        + " and is not a project: run this command in a member (jk -C " + example
                        + " …); jk " + String.join(", ", ROOT_VERBS.subList(0, ROOT_VERBS.size() - 1)) + " and "
                        + ROOT_VERBS.getLast() + " run over every member from here");
    }

    /**
     * Read the group root at {@code root}: its members expanded, its exclusions and profiles checked.
     *
     * @throws JkBuildParseException when the file is not a valid group root
     */
    public static WorkspaceGroup load(Path root) throws IOException {
        Path dir = root.toAbsolutePath().normalize();
        Path manifest = dir.resolve(ManifestPaths.MANIFEST);
        try {
            return read(dir, JkBuildParser.document(manifest));
        } catch (JkBuildParseException e) {
            throw e.locatedIn(manifest);
        }
    }

    private static WorkspaceGroup read(Path root, TomlParseResult doc) {
        for (String key : doc.keySet()) {
            if (!TOP_LEVEL.contains(key)) {
                throw JkBuildParseException.at(
                        doc,
                        key,
                        "a group root lists workspaces and nothing else: `" + key
                                + "` belongs in a member's jk.toml, since the root contributes nothing a member"
                                + " inherits");
            }
        }
        if (!(doc.get(List.of(TABLE)) instanceof TomlTable table)) {
            throw JkBuildParseException.at(doc, TABLE, "`workspaces` must be a table");
        }
        rejectUnknown(table, KEYS, "[workspaces]");
        List<String> entries = strings(table, "members", MEMBERS);
        if (entries.isEmpty()) {
            throw JkBuildParseException.at(table, "members", MEMBERS + " must list at least one workspace");
        }
        List<String> paths = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String entry : entries) {
            String normalized = entry.replace('\\', '/');
            if (normalized.startsWith("/")
                    || normalized.equals("..")
                    || normalized.startsWith("../")
                    || normalized.contains("/../")
                    || normalized.equals(".")) {
                throw JkBuildParseException.at(
                        table, "members", MEMBERS + " entry `" + entry + "`: a member is a directory below the root");
            }
            if (WorkspaceModules.isGlob(normalized)) {
                paths.addAll(WorkspaceModules.expand(root, List.of(normalized), MEMBERS));
            } else if (ManifestPaths.describesProject(root.resolve(normalized))) {
                paths.add(normalized);
            } else {
                missing.add(normalized);
            }
        }
        Map<String, String> reasons = exclusions(table, paths);
        List<Member> members = new ArrayList<>();
        for (String path : new LinkedHashSet<>(paths)) {
            Path memberDir = root.resolve(path).normalize();
            if (declares(memberDir)) {
                throw JkBuildParseException.at(
                        table,
                        "members",
                        MEMBERS + " entry `" + path + "` is a group root itself; list its members here instead");
            }
            members.add(new Member(path, memberDir, reasons.get(path)));
        }
        Map<String, List<String>> profiles = profiles(doc, members);
        return new WorkspaceGroup(root, List.copyOf(members), List.copyOf(missing), profiles);
    }

    /** Each excluded member's reason, by path. */
    private static Map<String, String> exclusions(TomlTable table, List<String> paths) {
        Map<String, String> reasons = new LinkedHashMap<>();
        if (!table.contains("exclude")) return reasons;
        if (!(table.get(List.of("exclude")) instanceof TomlArray exclude)) {
            throw JkBuildParseException.at(
                    table, "exclude", "[workspaces] exclude must be an array of { path, reason } tables");
        }
        for (int i = 0; i < exclude.size(); i++) {
            if (!(exclude.get(i) instanceof TomlTable entry)) {
                throw JkBuildParseException.at(
                        table, "exclude", "[workspaces] exclude must be an array of { path, reason } tables");
            }
            rejectUnknown(entry, EXCLUDE_KEYS, "[workspaces] exclude");
            String path = entry.isString("path") ? entry.getString("path") : null;
            String reason = entry.isString("reason") ? entry.getString("reason") : null;
            if (path == null || path.isBlank()) {
                throw JkBuildParseException.at(table, "exclude", "[workspaces] exclude: every entry needs a path");
            }
            if (reason == null || reason.isBlank()) {
                throw JkBuildParseException.at(
                        table,
                        "exclude",
                        "[workspaces] exclude `" + path
                                + "` needs a reason: jk prints it whenever it skips the member");
            }
            List<String> matched = matching(path, paths);
            if (matched.isEmpty()) {
                throw JkBuildParseException.at(table, "exclude", "[workspaces] exclude `" + path + "` names no member");
            }
            for (String m : matched) reasons.putIfAbsent(m, reason.strip());
        }
        return reasons;
    }

    /** Each root profile's {@code include} patterns, checked against the excluded members. */
    private static Map<String, List<String>> profiles(TomlParseResult doc, List<Member> members) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!doc.contains("profiles")) return out;
        if (!(doc.get(List.of("profiles")) instanceof TomlTable profiles)) {
            throw JkBuildParseException.at(doc, "profiles", "`profiles` must be a table");
        }
        List<String> excluded =
                members.stream().filter(Member::excluded).map(Member::path).toList();
        for (String name : profiles.keySet()) {
            String where = "[profiles." + name + "]";
            if (!(profiles.get(List.of(name)) instanceof TomlTable profile)) {
                throw JkBuildParseException.at(profiles, name, where + " must be a table");
            }
            for (String key : profile.keySet()) {
                if (!PROFILE_KEYS.contains(key)) {
                    throw JkBuildParseException.at(
                            profile,
                            key,
                            where + " `" + key + "`: a group root's profile only adds members back, under "
                                    + where.substring(0, where.length() - 1) + ".workspaces] include; `" + key
                                    + "` belongs in a member's jk.toml");
                }
            }
            if (!(profile.get(List.of(TABLE)) instanceof TomlTable ws)) {
                throw JkBuildParseException.at(profile, TABLE, where + " workspaces must be a table");
            }
            String wsWhere = "[profiles." + name + ".workspaces]";
            rejectUnknown(ws, PROFILE_WORKSPACES_KEYS, wsWhere);
            List<String> include = strings(ws, "include", wsWhere + " include");
            for (String pattern : include) {
                if (matching(pattern, excluded).isEmpty()) {
                    throw JkBuildParseException.at(
                            ws, "include", wsWhere + " include `" + pattern + "` names no excluded member");
                }
            }
            out.put(name, List.copyOf(include));
        }
        return Map.copyOf(out);
    }

    /** The paths among {@code paths} that {@code pattern} names, literally or as a glob. */
    private static List<String> matching(String pattern, Collection<String> paths) {
        String p = pattern.replace('\\', '/');
        return paths.stream()
                .filter(path -> WorkspaceModules.lists(List.of(p), path))
                .toList();
    }

    private static List<String> strings(TomlTable table, String key, String where) {
        if (!table.contains(key)) {
            throw JkBuildParseException.at(table, key, where + " is required: a list of directories");
        }
        if (!(table.get(List.of(key)) instanceof TomlArray array)) {
            throw JkBuildParseException.at(table, key, where + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.get(i) instanceof String s) || s.isBlank()) {
                throw JkBuildParseException.at(table, key, where + " must be an array of strings");
            }
            out.add(s.strip());
        }
        return out;
    }

    private static void rejectUnknown(TomlTable table, List<String> known, String where) {
        for (String key : table.keySet()) {
            if (!known.contains(key)) {
                throw JkBuildParseException.at(
                        table, key, where + " unknown key `" + key + "`, expected one of: " + String.join(", ", known));
            }
        }
    }

    // ---- selection ---------------------------------------------------------------------------

    /** The first root profile whose {@code include} names {@code member}, or {@code null}. */
    public @Nullable String includedBy(Member member) {
        for (Map.Entry<String, List<String>> p : profiles.entrySet()) {
            for (String pattern : p.getValue()) {
                if (!matching(pattern, List.of(member.path())).isEmpty()) return p.getKey();
            }
        }
        return null;
    }

    /**
     * The member {@code token} names: its path relative to the root, or its artifact name.
     *
     * @throws IllegalArgumentException naming the members when none matches
     */
    public Member named(String token) {
        String t = token.strip().replace('\\', '/');
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        for (Member m : members) if (m.path().equals(t)) return m;
        for (Member m : members) if (m.name().equals(t)) return m;
        throw new IllegalArgumentException("no member named `" + token + "`; the group's members are "
                + String.join(", ", members.stream().map(Member::path).toList()));
    }

    /**
     * What a root verb runs: the members {@code names} selects (paths or artifact names; every
     * member the default run includes when empty), with the members they depend on through {@code
     * path} dependencies, dependencies first. {@code profile} is a root profile's name or {@code
     * null}; its {@code include} adds excluded members back to the default run.
     *
     * @throws IllegalArgumentException for an unknown name, an unknown profile or a dependency cycle
     */
    public Selection select(@Nullable String profile, List<String> names) {
        if (profile != null && !profiles.containsKey(profile)) {
            throw new IllegalArgumentException("the group root declares no profile `" + profile + "`");
        }
        Set<Member> wanted = new LinkedHashSet<>();
        if (names.isEmpty()) {
            List<String> include = profile == null ? List.of() : profiles.getOrDefault(profile, List.of());
            for (Member m : members) {
                if (!m.excluded()
                        || include.stream()
                                .anyMatch(p -> !matching(p, List.of(m.path())).isEmpty())) {
                    wanted.add(m);
                }
            }
        } else {
            for (String n : names) wanted.add(named(n));
        }
        Map<Path, Set<Path>> edges = PathEdges.between(members);
        Map<Path, Member> byDir = new LinkedHashMap<>();
        for (Member m : members) byDir.put(m.dir(), m);
        // The closure: a selected member's path dependencies on other members run first.
        List<Member> queue = new ArrayList<>(wanted);
        for (int i = 0; i < queue.size(); i++) {
            for (Path prereq : edges.getOrDefault(queue.get(i).dir(), Set.of())) {
                Member dep = byDir.get(prereq);
                if (dep != null && wanted.add(dep)) queue.add(dep);
            }
        }
        // Declared order, each member's dependencies placed just before it.
        List<Member> run = new ArrayList<>();
        Set<Member> placing = new LinkedHashSet<>();
        for (Member m : members) {
            if (wanted.contains(m)) place(m, byDir, edges, placing, run);
        }
        List<Member> skipped = names.isEmpty()
                ? members.stream()
                        .filter(m -> m.excluded() && !wanted.contains(m))
                        .toList()
                : List.of();
        return new Selection(run, skipped, edges);
    }

    /** Place {@code m} after its dependencies; {@code placing} holds the chain being walked. */
    private static void place(
            Member m, Map<Path, Member> byDir, Map<Path, Set<Path>> edges, Set<Member> placing, List<Member> run) {
        if (run.contains(m)) return;
        if (!placing.add(m)) {
            List<String> chain = new ArrayList<>();
            boolean in = false;
            for (Member p : placing) {
                if (p.equals(m)) in = true;
                if (in) chain.add(p.path());
            }
            throw new IllegalArgumentException(
                    "the path dependencies between members " + String.join(", ", chain) + " form a cycle");
        }
        for (Path prereq : edges.getOrDefault(m.dir(), Set.of())) {
            Member dep = byDir.get(prereq);
            if (dep != null) place(dep, byDir, edges, placing, run);
        }
        placing.remove(m);
        run.add(m);
    }

    // ---- registering a member ----------------------------------------------------------------

    private static final Pattern MEMBERS_KEY = Pattern.compile("(?m)^\\s*(?:workspaces\\.)?members\\s*=\\s*\\[");
    private static final Pattern INDENT = Pattern.compile("(?m)^([ \\t]+)\\S");

    /**
     * List {@code rel} in the {@code [workspaces] members} of the group root at {@code root}, after
     * the entries already there, keeping the file's layout: a one-line array gains {@code , "rel"},
     * a multi-line one a line of its own. Nothing changes when an entry already names it.
     *
     * @return whether the file changed
     */
    public static boolean addMember(Path root, String rel) throws IOException {
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        String text = Files.readString(manifest);
        String updated = withMember(text, rel);
        if (updated.equals(text)) return false;
        AtomicWrites.replace(manifest, updated);
        return true;
    }

    /** {@code text}, a group root's manifest, with {@code rel} listed; see {@link #addMember}. */
    static String withMember(String text, String rel) {
        String wanted = rel.replace('\\', '/');
        List<String> listed = new ArrayList<>();
        if (Toml.parse(text).get(List.of(TABLE, "members")) instanceof TomlArray array) {
            for (int i = 0; i < array.size(); i++) if (array.get(i) instanceof String entry) listed.add(entry);
        }
        if (WorkspaceModules.lists(listed, wanted)) return text;
        Matcher key = MEMBERS_KEY.matcher(text);
        if (!key.find())
            throw new JkBuildParseException(
                    "[workspaces] members is not an array jk can add to; add \"" + wanted + "\" yourself");
        int open = key.end() - 1;
        int close = closing(text, open);
        String quoted = "\"" + wanted.replace("\"", "\\\"") + "\"";
        String inside = text.substring(open + 1, close);
        if (!inside.contains("\n")) {
            String body = inside.strip();
            String joined = body.isEmpty() ? quoted : body.endsWith(",") ? body + " " + quoted : body + ", " + quoted;
            return text.substring(0, open + 1) + joined + text.substring(close);
        }
        // Multi-line: a line of its own, indented like the last entry, before the closing bracket's line.
        int lineStart = text.lastIndexOf('\n', close - 1) + 1;
        String before = text.substring(open + 1, lineStart).stripTrailing();
        String indent = "  ";
        Matcher last = INDENT.matcher(inside);
        while (last.find()) indent = last.group(1);
        // The last entry gains its comma before any comment that trails it.
        int lastLine = before.lastIndexOf('\n') + 1;
        int hash = before.indexOf('#', Math.max(lastLine, before.lastIndexOf('"') + 1));
        String code = hash < 0 ? before : before.substring(0, hash).stripTrailing();
        String comment = hash < 0 ? "" : before.substring(code.length());
        String comma = code.isEmpty() || code.endsWith(",") || code.endsWith("[") ? "" : ",";
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        return text.substring(0, open + 1) + code + comma + comment + eol + indent + quoted + "," + eol
                + text.substring(lineStart);
    }

    /** The index of the {@code ]} that closes the array opening at {@code open}, skipping strings and comments. */
    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) break;
                i = end;
            } else if (c == '#') {
                int end = text.indexOf('\n', i);
                if (end < 0) break;
                i = end;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        throw new JkBuildParseException("[workspaces] members has no closing bracket");
    }
}
