// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.RepoStoreDirs;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

/**
 * Rule packs: {@code [guards] extends = ["g:a:v", …]} in the root rules file names jars whose entries
 * are a {@code jk-guards.toml} fragment and {@code guard-fixtures/**}. A pack is pinned in {@code
 * jk-lock.toml} like a plugin ({@code [[plugin]]} row: coordinate, version, and a sha256 unless
 * {@link Coordinate#pinsByVersionOnly} holds), located offline through that pin in the store, and
 * unpacked once per sha under {@code
 * target/jk-guards/guard-packs/<artifact>/}, where the loader reads it as the {@code PACK} layer and
 * {@code jk guard test} finds its fixtures.
 */
public final class GuardPacks {

    public static final String FRAGMENT = GuardsPresence.RULES_FILE;
    static final String STAMP = ".pack-sha256";

    /** The Maven group every rule pack that ships inside jk publishes under. */
    public static final String FIRST_PARTY_GROUP = "cc.jumpkick.guards";

    /**
     * Parsed {@code [guards] extends} per rules file, stamped on its (size, mtime).
     *
     * <p>{@link GuardRules#stamp} calls this for every module of a workspace, and a stamp is meant
     * to cost a stat: without the memo each call re-read and re-parsed the whole rules file, which
     * on a large workspace is the single most expensive thing in the cache check and the engine's
     * biggest allocator during it.
     */
    private static final StampedMemo<Path, String, List<String>> DECLARED = StampedMemo.bounded(64);

    private GuardPacks() {}

    /** One declared pack: {@code group:artifact:version}. */
    public record Coordinate(String group, String artifact, String version) {
        public String gav() {
            return group + ":" + artifact + ":" + version;
        }

        public String ga() {
            return group + ":" + artifact;
        }

        /** True for a pack that ships inside jk ({@link #FIRST_PARTY_GROUP}). */
        public boolean isFirstParty() {
            return FIRST_PARTY_GROUP.equals(group);
        }

        /**
         * True when the lock pins this pack by version alone. A first-party pack at a pre-release
         * version is republished with every jk rebuild, so a digest would fix one moment of it and
         * fail every committed consumer lock on the next side-load; the version already names the
         * jk it ships with. A stable release, and every third-party pack, carries its digest.
         */
        public boolean pinsByVersionOnly() {
            return isFirstParty() && Versions.isPreRelease(version);
        }

        /** {@code g:a:v} exactly; anything else is {@code null} — a floating version has no pin to read. */
        public static @Nullable Coordinate parse(String text) {
            String[] parts = text.strip().split(":");
            if (parts.length != 3) return null;
            for (String p : parts) if (p.isBlank()) return null;
            return new Coordinate(parts[0], parts[1], parts[2]);
        }
    }

    /** The {@code [guards] extends} coordinates of the root rules file, as written (unparsed). */
    public static List<String> declared(Path root) throws IOException {
        Path file = GuardsPresence.rulesFile(root);
        if (!Files.isRegularFile(file)) return List.of();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw unreadable;
        }
        String hash = Hashing.sha256Hex(text);
        try {
            String body = text;
            return DECLARED.get(file, hash, () -> declared(body));
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
    }

    static List<String> declared(String rootText) {
        TomlParseResult toml = Toml.parse(rootText);
        List<String> out = new ArrayList<>();
        if (toml.hasErrors()) return out;
        Object v = toml.get(List.of("guards", "extends"));
        if (v instanceof TomlArray a) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        else if (v instanceof String s) out.add(s);
        return out;
    }

    /** Where a pack's fragment and fixtures live once unpacked. */
    public static Path unpackedDir(Path root, Coordinate c) {
        return root.resolve(BuildLayout.TARGET)
                .resolve("jk-guards")
                .resolve("guard-packs")
                .resolve(c.artifact());
    }

    public static Path fragment(Path unpackedDir) {
        return unpackedDir.resolve(FRAGMENT);
    }

    /** The pin the lock carries for a pack, or {@code null}. */
    public static Lockfile.@Nullable PluginEntry pin(Path root, Coordinate c) throws IOException {
        Path lock = LockPaths.lockFile(root);
        if (!Files.isRegularFile(lock)) return null;
        for (Lockfile.PluginEntry e : LockfileReader.read(lock).plugins()) {
            if (e.coordinate().equals(c.ga()) && e.version().equals(c.version())) return e;
        }
        return null;
    }

    /**
     * The pinned pack's jar in the store, by content address then by repository layout; {@code
     * null} when absent. A content-address hit is the pinned bytes by construction; a layout hit is
     * whatever sits at that version and is verified against the pin before it is unpacked.
     */
    public static @Nullable Path locateJar(Coordinate c, Lockfile.PluginEntry pin, Path store) throws IOException {
        String hex = pin.sha256Hex();
        if (hex != null && hex.length() > 4) {
            Path cas = store.resolve("sha256")
                    .resolve(hex.substring(0, 2))
                    .resolve(hex.substring(2, 4))
                    .resolve(hex.substring(4));
            if (Files.isRegularFile(cas)) return cas;
        }
        String tail = c.group().replace('.', '/') + "/" + c.artifact() + "/" + c.version() + "/" + c.artifact() + "-"
                + c.version() + ".jar";
        // Known stores only: a legacy name-keyed tree has no origin anyone can vouch for.
        for (Path dir : RepoStoreDirs.known(store)) {
            Path jar = dir.resolve(tail);
            if (Files.isRegularFile(jar)) return jar;
        }
        return null;
    }

    /**
     * Unpack a pack jar under {@code dir}, stamped with {@code sha256} so an unchanged pin is a stat,
     * not a re-read. A pack is text — the fragment and its fixture sources — so {@code META-INF} and
     * class files are left in the jar; a path that escapes the directory is refused.
     */
    /** Whether {@code dir} holds an unpack of exactly these bytes. */
    public static boolean unpackedAs(Path dir, String sha256) throws IOException {
        Path stamp = dir.resolve(STAMP);
        return Files.isRegularFile(stamp)
                && Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(sha256)
                && Files.isRegularFile(fragment(dir));
    }

    public static void unpack(Path jar, Path dir, String sha256) throws IOException {
        if (unpackedAs(dir, sha256)) return;
        if (Files.isDirectory(dir)) PathUtil.deleteRecursivelyOrThrow(dir);
        Files.createDirectories(dir);
        Path base = dir.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean fragmentSeen = false;
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/") || name.endsWith(".class")) continue;
                Path target = base.resolve(name).normalize();
                if (!target.startsWith(base)) throw new IOException(jar + ": entry escapes the pack: " + name);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (name.equals(FRAGMENT)) fragmentSeen = true;
            }
            if (!fragmentSeen) throw new IOException(jar + " carries no " + FRAGMENT + "; it is not a rule pack");
        }
        Files.writeString(dir.resolve(STAMP), sha256 + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Make every declared pack readable: for each coordinate, the lock's pin locates the jar in the
     * store and it is unpacked once per sha. Returns one problem per pack that could not be made
     * readable; the loader turns each into a load error, so a missing pack is red, never a silent
     * subset of the rules.
     */
    public static List<String> ensure(Path root, Path store) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String text : declared(root)) {
            Coordinate c = Coordinate.parse(text);
            if (c == null) {
                problems.add("[guards] extends: `" + text + "` is not group:artifact:version");
                continue;
            }
            Lockfile.PluginEntry pin = pin(root, c);
            Path dir = unpackedDir(root, c);
            if (pin == null) {
                if (Files.isRegularFile(fragment(dir))) continue; // an earlier unpack still stands
                problems.add("pack " + c.gav() + " is not pinned in jk-lock.toml — run `jk lock`");
                continue;
            }
            String hex = pin.sha256Hex();
            if (hex == null) {
                // Pinned by version alone: the jar the store holds at that version is the pack, and
                // its digest is read from the bytes rather than from the lock.
                Path jar = locateJar(c, pin, store);
                if (jar == null) {
                    if (Files.isRegularFile(fragment(dir))) continue; // an earlier unpack still stands
                    problems.add("pack " + c.gav()
                            + " is pinned by version but its jar is neither unpacked nor in the store — run `jk lock`");
                    continue;
                }
                hex = Hashing.sha256Hex(jar);
                if (!unpackedAs(dir, hex)) unpack(jar, dir, hex);
                continue;
            }
            if (unpackedAs(dir, hex)) continue; // `jk lock` already unpacked these bytes
            Path jar = locateJar(c, pin, store);
            if (jar == null) {
                problems.add("pack " + c.gav() + " is pinned but neither unpacked nor in the store — run `jk lock`");
                continue;
            }
            String actual = Hashing.sha256Hex(jar);
            if (!actual.equals(hex)) {
                // The digest is the pin: a jar of another build at the same version is not the pack
                // the lock names, and unpacking it would make the lock's row mean nothing.
                problems.add(digestMismatch(c, hex, actual));
                continue;
            }
            unpack(jar, dir, hex);
        }
        return problems;
    }

    /** The refusal for a store jar whose bytes are not the ones the lock pins. */
    static String digestMismatch(Coordinate c, String pinned, String actual) {
        return "pack " + c.gav() + ": the jar in the store is sha256 " + actual.substring(0, 12)
                + "… but jk-lock.toml pins sha256 " + pinned.substring(0, 12)
                + "… — run `jk lock` to pin the jar you have, or put the pinned jar back in the store";
    }
}
