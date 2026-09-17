// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * When Maven Central refuses this host, route Central traffic to Google's GCS mirror for a while.
 *
 * <p>Central refuses a host two ways. Sonatype enforces a <b>per-IP</b> quota and answers HTTP 429
 * above it; Cloudflare, which fronts Central's edge, blocks a host it judges abusive and answers
 * HTTP 403 with {@code Server: cloudflare} (or {@code cf-mitigated}) on every request — a plain
 * 403 without those headers is a permission answer and is handed back as one. Both are sticky:
 * once tripped, they do not clear for hours, and a rejected request still costs something. jk's own
 * request rate is not the problem — a single-dependency project is refused just as readily as a
 * large one — so throttling further would not help. The only recovery is to ask somewhere else.
 *
 * <h2>Why a wholesale, sticky switch</h2>
 *
 * Per-request failover would let one resolve read some coordinates from Central and others from the
 * mirror. If the mirror lags even slightly, that mixes two views of "which versions exist" inside a
 * single resolve, and the resulting {@code jk-lock.toml} would depend on the order requests happened to
 * fail. Switching <em>everything</em> Central-bound for a fixed window keeps one resolve on one
 * source, which is the property that matters.
 *
 * <p>The window is tracked by a stamp file's mtime, the same way {@link
 * cc.jumpkick.http.Http}'s callers already age other on-disk state — so it survives an engine
 * restart, which matters because {@code jk engine stop} between attempts would otherwise forget the
 * rate limit and go straight back to hammering Central.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * It does not rewrite the repository's identity. The lockfile records the repo's configured URL, so a
 * lock written while mirrored is byte-identical to one written normally: the mirror is a transport
 * detail, not a source of record. Nor does it touch any repository other than Central.
 *
 * <p>Note the {@code /maven2/} prefix. Google also serves {@code /repos/central/data/}, which answers
 * HTTP 200 with a <b>2019 snapshot</b> — using that would silently resolve floating selectors to
 * years-old versions instead of failing, which is worse than a rate limit.
 */
public final class CentralMirror {

    /** Maven Central's canonical host. Only traffic to this host is ever rewritten. */
    public static final String CENTRAL_HOST = "repo.maven.apache.org";

    /** Google's GCS-hosted Central mirror. The {@code /maven2/} prefix is the live one. */
    public static final String MIRROR_BASE = "https://maven-central.storage-download.googleapis.com/maven2";

    /** How long one refusal keeps us on the mirror. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(4);

    /** Set to {@code off}/{@code false}/{@code 0} to keep using Central even when rate-limited. */
    public static final String ENV_DISABLE = "JK_CENTRAL_MIRROR";

    private static final String STAMP_NAME = "central-rate-limited.stamp";

    /**
     * Why the window is open, recorded as the stamp's first line so the note can name it. A stamp
     * without a recognisable first line — one armed by hand with {@code touch} — reads as a rate
     * limit.
     */
    enum Cause {
        RATE_LIMITED("rate-limited", "Maven Central is rate-limiting this host (HTTP 429)"),
        BLOCKED("blocked", "Maven Central is blocking this host (Cloudflare)");

        final String line;
        final String text;

        Cause(String line, String text) {
            this.line = line;
            this.text = text;
        }

        static Cause parse(@Nullable String firstLine) {
            for (Cause c : values()) {
                if (c.line.equals(firstLine)) return c;
            }
            return RATE_LIMITED;
        }
    }

    /** The one instance production uses; see {@link #standard()}. */
    private static final CentralMirror STANDARD =
            new CentralMirror(JkDirs.cache(), DEFAULT_WINDOW, enabledByEnv(System::getenv));

    private final Path stamp;
    private final Duration window;
    private final boolean enabled;
    private final String centralHost;
    private final String mirrorBase;

    /**
     * The test seam. Package-private so {@link #standard()} is the only way production obtains a
     * mirror, which is what keeps the window to one directory.
     *
     * @param centralHost the host to treat as rate-limitable (normally {@link #CENTRAL_HOST})
     * @param mirrorBase the base URL to route to, no trailing slash (normally {@link #MIRROR_BASE})
     */
    CentralMirror(Path cacheDir, Duration window, boolean enabled, String centralHost, String mirrorBase) {
        this.stamp = cacheDir.resolve(STAMP_NAME);
        this.window = window;
        this.enabled = enabled;
        this.centralHost = centralHost;
        this.mirrorBase = mirrorBase.endsWith("/") ? mirrorBase.substring(0, mirrorBase.length() - 1) : mirrorBase;
    }

    CentralMirror(Path cacheDir, Duration window, boolean enabled) {
        this(cacheDir, window, enabled, CENTRAL_HOST, MIRROR_BASE);
    }

    /**
     * The shared instance: stamp under {@link JkDirs#cache()}, a four-hour window, honouring
     * {@link #ENV_DISABLE}.
     *
     * <p>It takes no directory, and there is only one of it. The window is a single fact about this
     * machine's IP, and both legs of a build act on it — the transport reroutes enumeration, the
     * artifact fetch reroutes bytes. A caller that named its own directory would record a 429 where the
     * other leg cannot read it, leaving a switch that is meant to be wholesale sticky for only half the
     * build. {@code JK_CACHE_DIR} and {@code JK_CENTRAL_MIRROR} are both forwarded to a spawned engine,
     * so client and engine read the same stamp.
     *
     * <p>Resolved once per process: the layout is fixed at JVM start and {@link System#getenv} cannot
     * change, so deriving it per caller can only produce disagreement.
     */
    public static CentralMirror standard() {
        return STANDARD;
    }

    /** Visible for tests: the single file that carries the window. */
    Path stampFile() {
        return stamp;
    }

    static boolean enabledByEnv(Function<String, @Nullable String> env) {
        return EnvValues.parseBool(env.apply(ENV_DISABLE)).orElse(true);
    }

    /** True when {@code uri} targets the host this instance treats as Central. */
    public boolean matches(URI uri) {
        return uri != null && centralHost.equalsIgnoreCase(uri.getHost());
    }

    /** True when {@code uri} is a Maven Central request, using the canonical host. */
    public static boolean isCentral(URI uri) {
        return uri != null && CENTRAL_HOST.equalsIgnoreCase(uri.getHost());
    }

    /**
     * True when {@code status} and {@code headers} are Cloudflare blocking the requesting host: a 403
     * whose {@code Server} is {@code cloudflare} or that carries {@code cf-mitigated}. A 403 from
     * anything else, and every other status Cloudflare merely passes through, is not a block.
     */
    public static boolean isCloudflareBlock(int status, HttpHeaders headers) {
        if (status != 403) return false;
        boolean cloudflare = headers.firstValue("server")
                .map(v -> v.trim().equalsIgnoreCase("cloudflare"))
                .orElse(false);
        return cloudflare || headers.firstValue("cf-mitigated").isPresent();
    }

    /** True while a recent refusal still has us on the mirror. */
    public boolean active() {
        if (!enabled) return false;
        try {
            if (!Files.isRegularFile(stamp)) return false;
            Instant seen = Files.getLastModifiedTime(stamp).toInstant();
            return Duration.between(seen, Clock.SYSTEM.instant()).compareTo(window) < 0;
        } catch (IOException e) {
            return false; // unreadable stamp: prefer Central over guessing
        }
    }

    /**
     * Record that Central rate-limited us (HTTP 429), starting (or restarting) the window.
     *
     * <p>A later refusal legitimately extends the window, because it means the refusal is still in
     * force.
     */
    public void noteRateLimited() {
        note(Cause.RATE_LIMITED);
    }

    /** Record that Cloudflare is blocking this host on Central's behalf, starting (or restarting) the window. */
    public void noteBlocked() {
        note(Cause.BLOCKED);
    }

    /**
     * Start or extend the window under {@code cause}. The stamp is touched when it already names
     * {@code cause} and rewritten when the cause changed, so the note always names the latest refusal.
     */
    private void note(Cause cause) {
        if (!enabled) return;
        try {
            Files.createDirectories(stamp.getParent());
            if (Files.exists(stamp) && recordedCause() == cause) {
                Files.setLastModifiedTime(stamp, FileTime.from(Clock.SYSTEM.instant()));
                return;
            }
            Files.writeString(
                    stamp,
                    cause.line + "\n" + cause.text + ". Central-bound requests route to\n" + mirrorBase
                            + " until this file is older than " + window.toHours()
                            + "h. Delete it to retry Central immediately.\n");
        } catch (IOException e) {
            // Losing the stamp only costs us the optimisation; never fail a build over it.
        }
    }

    /** The cause the stamp records; a rate limit when the stamp is missing, empty or hand-made. */
    private Cause recordedCause() {
        try (var lines = Files.lines(stamp)) {
            return Cause.parse(lines.findFirst().orElse(null));
        } catch (IOException | UncheckedIOException e) {
            return Cause.RATE_LIMITED;
        }
    }

    /**
     * The one line the results carry while the window is open — which refusal Central gave and how
     * long Central-bound requests go to the mirror — or empty when Central is being asked directly.
     */
    public Optional<String> note() {
        if (!active()) return Optional.empty();
        return Optional.of(recordedCause().text + "; using the mirror for " + window.toHours() + " h");
    }

    /** When the window expires, or null when not currently mirrored. */
    public @Nullable Instant activeUntil() {
        try {
            if (!Files.isRegularFile(stamp)) return null;
            return Files.getLastModifiedTime(stamp).toInstant().plus(window);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@code uri} redirected to the mirror when it is Central-bound and the window is open;
     * unchanged otherwise.
     */
    public URI route(URI uri) {
        if (!matches(uri) || !active()) return uri;
        return toMirror(uri);
    }

    /**
     * The mirror URI for a Central-bound <em>artifact byte</em> fetch, regardless of the window.
     *
     * <p>{@link #route} is the refusal reaction: only reroute once Central has refused. This is the
     * standing preference for the download leg, and it is safe for a different reason — a locked artifact
     * is pinned by sha256, so the bytes are verified on arrival and where they came from does not matter.
     * Version <em>enumeration</em> is the opposite case: the mirror can lag, so metadata and POMs keep
     * asking Central and only fall back once refused.
     *
     * <p>Also spends the mirror's much larger concurrency budget instead of Sonatype's per-IP quota, which
     * is the point of {@link HostRateLimiter#MIRROR_PERMITS}.
     */
    public URI routeForDownload(URI uri) {
        if (!enabled || !matches(uri)) return uri;
        return toMirror(uri);
    }

    /**
     * The mirror URI for a Central URI, preserving the path below {@code /maven2}.
     *
     * <p>Central's path is already {@code /maven2/<coords>} and the mirror uses the same layout, so
     * this is a host swap — but the path is taken apart rather than string-replaced so a repository
     * configured with a different Central prefix still lands in the right place.
     */
    URI toMirror(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String suffix = path.startsWith("/maven2/") ? path.substring("/maven2/".length()) : trimLeadingSlash(path);
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return URI.create(mirrorBase + "/" + suffix + query);
    }

    private static String trimLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
