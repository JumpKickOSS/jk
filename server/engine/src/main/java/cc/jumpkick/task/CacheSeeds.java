// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Where a remote cache's key seed comes from ({@link KeySeed}): the team's own, set on every client,
 * else the one the cache issues its authenticated clients ({@link CacheBackend#keySeed}).
 *
 * <p>The team's seed is {@value #ENV} of the shell that runs jk (a CI secret), else the file {@code
 * [cache] key-seed-file} of {@code ~/.jk/config.toml} names (a developer machine); never the
 * project's {@code jk.toml}, which every checkout and fork reads. It wins over the issued one, so the
 * service then holds no key it could test guessed values against. {@code off} (or {@code false})
 * takes no seed at all. A team seed shorter than {@value #MIN_CHARS} characters is refused, said once:
 * a seed is what keeps a short value from being guessed. With no remote cache, the team's seed (and
 * never an issued one) keys the shared forms the request's own action cache keeps ({@link
 * RemoteCacheBinding#seedForms}).
 *
 * <p>An issued seed is kept in this jk home's state, readable by its owner alone, one file per cache
 * and caller (named by an HMAC under the home's secret of the cache and the credential, never the
 * credential), and asked again once it is {@link #KEEP} old, so a cache that rotates its seed reaches every client
 * within that. The answer that the cache issues none is kept as long. Neither seed is ever printed,
 * logged or sent anywhere.
 *
 * <p>Under a seed a credential's value ({@code BuildEnv.looksSecret}) keys a result's shared form
 * like any other. {@value #CREDENTIAL_ENV}{@code =local}, or {@code [cache] credential-env = "local"}
 * in {@code ~/.jk/config.toml}, keeps such results on this host ({@link #credentialsLocal}): the
 * host's operator, who put the credential in its environment, decides what leaves it, as for the
 * cache's URL and the seed, never the project's {@code jk.toml}.
 */
public final class CacheSeeds {

    private CacheSeeds() {}

    /** The team's seed for one shell; {@code off} for none. */
    public static final String ENV = BuildEnv.CACHE_KEY_SEED;

    /** The user setting naming a file that holds the team's seed. */
    static final String FILE_KEY = JkCacheConfig.KEY_SEED_FILE.dotted();

    /** {@code local} or {@code shared}: whether a result keyed on a credential's value stays on this host. */
    public static final String CREDENTIAL_ENV = "JK_CACHE_CREDENTIAL_ENV";

    /** The user setting {@link #CREDENTIAL_ENV} spells in {@code ~/.jk/config.toml}. */
    static final String CREDENTIAL_KEY = JkCacheConfig.CREDENTIAL_ENV.dotted();

    /** The fewest characters a team seed is taken with. */
    static final int MIN_CHARS = 16;

    /** What a kept copy's name is an HMAC of, before the cache's scope and the caller's credential. */
    private static final String KEPT_DOMAIN = "jk kept key seed 1\u0000";

    /** How long an issued seed, or the answer that there is none, is kept before the cache is asked again. */
    static final Duration KEEP = Duration.ofHours(1);

    /** Where a tier's seed comes from. */
    public sealed interface Source {
        /** The seed the cache issues, if it issues one: what a client that sets none takes. */
        Source ISSUED = new Issued();

        /** No seed: what read the environment stays on each host. */
        Source OFF = new Off();
    }

    /** The cache's issued seed. */
    record Issued() implements Source {}

    /** No seed. */
    record Off() implements Source {}

    /** The team's seed, as its bytes. Never printed. */
    record Team(byte[] seed) implements Source {
        @Override
        public boolean equals(Object o) {
            return o instanceof Team t && Arrays.equals(seed, t.seed);
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "Team[seed withheld]";
        }
    }

    /** The source {@code env} and {@code userConfig} name: the team's seed, {@code off}, else the issued one. */
    public static Source resolve(Function<String, @Nullable String> env, Path userConfig) {
        String raw = EnvValues.string(env, ENV).orElse(null);
        String from = ENV;
        if (raw == null && Files.isRegularFile(userConfig)) {
            String file = TomlScan.scan(userConfig, FILE_KEY).get(FILE_KEY);
            if (file != null && !file.isBlank()) {
                from = "[cache] key-seed-file";
                raw = read(file.strip());
                if (raw == null) return refused(from, "the file it names cannot be read");
            }
        }
        if (raw == null) return Source.ISSUED;
        if (EnvValues.parseBool(raw).map(b -> !b).orElse(false)) return Source.OFF;
        if (raw.strip().length() < MIN_CHARS)
            return refused(from, "a seed needs at least " + MIN_CHARS + " characters");
        return new Team(raw.strip().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code env} and {@code userConfig} keep a result keyed on a credential's value on this
     * host even under a seed: {@code local}; {@code shared}, or nothing set, shares it. A word that is
     * neither is said once and read as {@code local}, the choice that sends nothing.
     */
    public static boolean credentialsLocal(Function<String, @Nullable String> env, Path userConfig) {
        String raw = EnvValues.string(env, CREDENTIAL_ENV).orElse(null);
        String from = CREDENTIAL_ENV;
        if (raw == null && Files.isRegularFile(userConfig)) {
            raw = TomlScan.scan(userConfig, CREDENTIAL_KEY).get(CREDENTIAL_KEY);
            from = "[cache] credential-env";
        }
        if (raw == null || raw.isBlank()) return false;
        String word = raw.strip().toLowerCase(Locale.ROOT);
        if (word.equals("shared")) return false;
        if (!word.equals("local")) {
            String setting = from;
            RunNotices.warnOnce(
                    "cache-credential-env",
                    () -> "jk: warning: " + setting + " is \"local\" or \"shared\"; results keyed on a"
                            + " credential's value stay on this machine");
        }
        return true;
    }

    /**
     * The key {@code source} gives for {@code backend}: the team's seed under the cache's scope, the
     * issued one under its scope and id, or null for none.
     *
     * @throws IOException when the cache was asked for its seed and did not hand it out
     */
    static @Nullable KeySeed keySeed(Source source, CacheBackend backend) throws IOException {
        return switch (source) {
            case Team t -> KeySeed.derive(t.seed(), "team\u0000" + backend.seedScope());
            case Off o -> null;
            case Issued i -> issued(backend, JkDirs.state().resolve("cache-seeds"), EnvReadKey.load()::hmac);
        };
    }

    /**
     * The cache's issued seed as a key, from what this home keeps when it is fresh, else asked. The
     * kept copy is the answer to one caller: its file is named by {@code mac}, an HMAC under this
     * home's secret, of the cache's scope and the credential this client sends ({@link
     * CacheBackend#caller}), so a home that changes to a credential the cache hands less is asked
     * again rather than keep the other's seed, and no file names or holds the credential.
     */
    static @Nullable KeySeed issued(CacheBackend backend, Path dir, UnaryOperator<String> mac) throws IOException {
        String scope = backend.seedScope();
        Path file = dir.resolve(backend.caller(credential -> mac.apply(KEPT_DOMAIN + scope + "\u0000" + credential))
                .substring(0, 32));
        CacheBackend.IssuedSeed seed;
        Kept kept = Kept.read(file);
        if (kept != null && Clock.SYSTEM.millis() - kept.fetchedMillis() < KEEP.toMillis()) {
            seed = kept.seed();
        } else {
            seed = backend.keySeed();
            try {
                OwnerOnlyFiles.write(dir, file, Kept.render(Clock.SYSTEM.millis(), seed));
                // The copy a jk before per-caller copies kept for every caller of this cache.
                Files.deleteIfExists(dir.resolve(Hashing.sha256Hex(scope).substring(0, 32)));
            } catch (IOException unwritable) {
                Log.debug("cache key seed not kept; asked again next build", unwritable);
            }
        }
        return seed == null ? null : KeySeed.derive(seed.seed(), "issued\u0000" + scope + "\u0000" + seed.id());
    }

    /** An issued seed as this home keeps it, or the answer that there is none, with when it was asked. */
    record Kept(long fetchedMillis, CacheBackend.@Nullable IssuedSeed seed) {

        static String render(long fetchedMillis, CacheBackend.@Nullable IssuedSeed seed) {
            return "fetched " + fetchedMillis + "\n"
                    + (seed == null
                            ? "none\n"
                            : "id " + seed.id() + "\nseed "
                                    + Base64.getEncoder().encodeToString(seed.seed()) + "\n");
        }

        /** What {@code file} keeps, or null when there is none or it does not read. */
        static @Nullable Kept read(Path file) {
            try {
                if (!Files.isRegularFile(file)) return null;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                long fetched = Long.parseLong(lines.getFirst().substring("fetched ".length()));
                if (lines.size() == 2 && lines.get(1).equals("none")) return new Kept(fetched, null);
                return new Kept(
                        fetched,
                        new CacheBackend.IssuedSeed(
                                lines.get(1).substring("id ".length()),
                                Base64.getDecoder().decode(lines.get(2).substring("seed ".length()))));
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
    }

    private static @Nullable String read(String spelled) {
        String home = System.getProperty("user.home");
        Path file = spelled.startsWith("~/") && home != null ? Path.of(home, spelled.substring(2)) : Path.of(spelled);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Source refused(String from, String why) {
        RunNotices.warnOnce(
                "cache-key-seed:" + from,
                () -> "jk: warning: " + from + " not used: " + why
                        + "; results that read your environment stay on this machine");
        return Source.OFF;
    }
}
