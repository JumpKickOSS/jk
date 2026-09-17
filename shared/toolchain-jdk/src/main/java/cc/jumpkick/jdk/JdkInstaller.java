// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.run.JkThreads;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Downloads a {@link JdkPackage} and extracts it under the IntelliJ JDK directory ({@link
 * IntellijJdkDir}). Supports tar/tar.gz/zip with SHA-256 when present. {@link #download} and
 * {@link #extractInstalled} are separable for UI progress; {@link #install} stitches them.
 */
public final class JdkInstaller {

    /** Local archive file produced by {@link #download}. */
    public record DownloadedArchive(Path path, long bytes) {}

    private static final int DOWNLOAD_BUFFER = 64 * 1024;

    /** Scratch dir for in-flight downloads, under the jdks root (dot-prefixed → JkProbe skips it). */
    private static final String DOWNLOAD_DIR = ".downloads";

    /** Prefix for the streamed archive temp file; recognised by the stale-download sweep. */
    private static final String DOWNLOAD_PREFIX = "jk-jdk-";

    /** Age after which a partial download is treated as orphaned by {@link #sweepStaleDownloads}. */
    private static final long STALE_DOWNLOAD_AGE_MILLIS = Duration.ofHours(6).toMillis();

    /**
     * Scratch this process created and has not finished with: the archive being streamed, and the
     * staging tree being unpacked into. Drained by {@link #reapInFlight} when the user cancels.
     *
     * <p>Every entry was made by {@code createTempFile} / {@code createTempDirectory} in this
     * process, so the path in hand <em>is</em> the ownership evidence — there is no shared-root
     * question of the kind {@code JkOwnership} exists for. Weakly typed as paths rather
     * than a richer handle because the only thing the cancel path does with them is unlink.
     */
    private static final Set<Path> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Unlink the scratch this process was still using, and report the bytes reclaimed.
     *
     * <p>Ctrl-C ends in {@code Runtime.halt}, which neither runs shutdown hooks nor unwinds the
     * stack, so the {@code finally} blocks that normally delete this scratch never execute. The
     * download's backstop — {@link #sweepStaleDownloads} — deliberately spares anything younger than
     * six hours so it cannot yank a concurrent install's archive, which made "leave it for the sweep"
     * the <em>normal</em> route for a cancel rather than a fallback for a crash: 563&nbsp;MB of
     * partials accumulated in one session on the reporting machine. The staging tree had no backstop
     * at all — nothing sweeps {@code .stage-*} — so a cancel mid-extract leaked a whole unpacked JDK.
     *
     * <p>Deleting a path we hold from its own creation cannot make that mistake, so this runs
     * immediately on cancel and the age-based sweep goes back to being a fallback for the case it
     * was written for: a process that died without getting here.
     *
     * <p>Never throws: it runs on the SIGINT thread, moments before the halt. On POSIX an unlink of a
     * file another thread still has open succeeds (the writer keeps its descriptor); on Windows it
     * can fail, and a file left for the sweep is the right answer there.
     */
    public static long reapInFlight() {
        long bytes = 0;
        for (Path p : IN_FLIGHT) {
            IN_FLIGHT.remove(p);
            try {
                if (Files.isDirectory(p)) {
                    bytes += sizeOfTree(p);
                    PathUtil.deleteRecursively(p);
                } else {
                    bytes += Files.exists(p) ? Files.size(p) : 0L;
                    Files.deleteIfExists(p);
                }
            } catch (IOException | RuntimeException stillOpenOrGone) {
                // Best-effort by contract; sweepStaleDownloads is the fallback.
                Log.debug("reapInFlight: Best-effort by contract", stillOpenOrGone);
            }
        }
        return bytes;
    }

    private static long sizeOfTree(Path root) {
        long[] total = {0L};
        try {
            PathUtil.forEachRegularFile(root, (f, attrs) -> total[0] += attrs.size());
        } catch (IOException unreadable) {
            return total[0];
        }
        return total[0];
    }

    private final Http http;
    private final JdkRegistry registry;

    public JdkInstaller(Http http, JdkRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledJdk install(JdkPackage pkg) throws IOException, InterruptedException {
        String identifier = pkg.installIdentifier();
        Path target = registry.jdksRoot().resolve(identifier);
        if (Files.exists(target)) {
            return new InstalledJdk(identifier, target);
        }
        downloadAndExtractBuffered(pkg.downloadUri(), pkg.sha256(), pkg.filename(), pkg.archiveType(), target);
        JdkOwnership.mark(target);
        InstalledJdk installed = new InstalledJdk(identifier, target);
        recordInventory(installed);
        return installed;
    }

    /**
     * Install a {@link JdkCatalog.Entry} from the JetBrains feed. Uses the feed's {@code
     * install_folder_name} for the directory and resolves JAVA_HOME via {@code
     * package_to_java_home_prefix} (e.g. {@code Contents/Home} on macOS) so callers always get a
     * working {@link InstalledJdk#home()}.
     */
    public InstalledJdk install(JdkCatalog.Entry entry) throws IOException, InterruptedException {
        return install(entry, b -> {});
    }

    /**
     * Same as {@link #install(JdkCatalog.Entry)} but emits cumulative bytes-read to {@code
     * onBytesRead} as the download streams. Total size is available on the entry itself ({@link
     * JdkCatalog.Entry#archiveSize()}).
     */
    public InstalledJdk install(JdkCatalog.Entry entry, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        // Deliberately does NOT drain JdkGarbage. Installing is not collecting, and this method is
        // on the automatic provisioning path: the queue is a file that outlives the process, so a
        // row left by an earlier update fired here during an ordinary build and deleted the JDK the
        // build was running on. Draining belongs to the explicit `jk jdk` verb that
        // queued the row, where the user has been asked.
        InstalledJdk already = alreadyInstalled(entry);
        if (already != null) return already;
        DownloadedArchive dl = download(entry, onBytesRead);
        return extractInstalled(entry, dl);
    }

    /**
     * Fast path: if the target directory already holds a finished install, return its descriptor
     * without touching the network or disk. Returns {@code null} when nothing's installed yet.
     *
     * <p>{@link #completeInstall} decides, not {@link Files#exists}: a directory at the install name
     * is not an install. An empty one is what a canceled install leaves behind, and what a recursive
     * delete that could not unlink the top directory leaves behind — and answering either as "already
     * installed" is worse than answering nothing, because the install short-circuits forever and the
     * failure surfaces at whatever step needed the JDK, naming some other home entirely.
     * {@code ~/.jdks/graalvm-25}, empty, made {@code jk build} report "GraalVM 25 is already
     * installed" and then fail its native-image step against the pinned Temurin.
     *
     * <p>A GraalVM is held to one thing more: a {@code native-image} launcher. It is the reason a
     * caller asks for a GraalVM by name at all — {@code GraalResolver} reaches this method only from
     * a tier that wants one to link with — so a GraalVM tree without it is not the install that was
     * asked for, and answering it as one hands the native step a home it cannot build in. Where the
     * launcher may sit is {@link GraalLauncher}'s answer, not this class's: {@code bin} or {@code
     * lib/svm/bin}, and any of the three spellings a host uses.
     */
    public @Nullable InstalledJdk alreadyInstalled(JdkCatalog.Entry entry) {
        String installName = installName(entry);
        Path target = registry.jdksRoot().resolve(installName);
        Path javaHome = javaHomeFor(entry, target);
        if (!completeInstall(target, javaHome)) return null;
        if (isGraal(entry) && GraalLauncher.in(javaHome).isEmpty()) return null;
        return new InstalledJdk(installName, javaHome);
    }

    /** Whether {@code entry} is one of the GraalVM flavours, by the vendor the feed reported. */
    private static boolean isGraal(JdkCatalog.Entry entry) {
        return DefaultGraalPolicy.isGraal(JdkVendor.fromFeed(entry.vendor(), entry.product()));
    }

    /**
     * Stream the JDK archive to a temp file, verifying SHA-256 incrementally. {@code onBytesRead}
     * receives the cumulative byte count after every chunk; the total is on {@link
     * JdkCatalog.Entry#archiveSize()}.
     */
    public DownloadedArchive download(JdkCatalog.Entry entry, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        Path downloads = prepareDownloadDir();
        Path archive = Files.createTempFile(downloads, DOWNLOAD_PREFIX, "-" + extensionFor(entry.packageType()));
        IN_FLIGHT.add(archive);
        boolean keep = false;
        try {
            long bytes =
                    streamingDownload(entry.url(), entry.sha256(), entry.installFolderName(), archive, onBytesRead);
            keep = true;
            // Metered once off the finished archive, not per chunk (onBytesRead is a progress hook).
            SessionContext.current().io().remoteDown(bytes);
            return new DownloadedArchive(archive, bytes);
        } finally {
            // Handed to extractInstalled on success, which owns it from here; either way this
            // method is no longer the one that would have to clean it up on a cancel.
            IN_FLIGHT.remove(archive);
            if (!keep) Files.deleteIfExists(archive);
        }
    }

    /**
     * Extract a {@link DownloadedArchive} into the JDK root and return the resulting {@link
     * InstalledJdk}. The temp archive is deleted on success or failure — the caller does not need to
     * clean it up.
     */
    public InstalledJdk extractInstalled(JdkCatalog.Entry entry, DownloadedArchive dl) throws IOException {
        String installName = installName(entry);
        Path target = registry.jdksRoot().resolve(installName);
        Path javaHome = javaHomeFor(entry, target);
        try {
            Path stagingDir = Files.createTempDirectory(registry.jdksRoot(), ".stage-");
            IN_FLIGHT.add(stagingDir);
            try {
                extract(dl.path(), stagingDir, entry.packageType());
            } catch (IOException | RuntimeException e) {
                discardStaging(stagingDir);
                throw e;
            }
            moveIntoPlace(stagingDir, target, javaHome);
        } finally {
            Files.deleteIfExists(dl.path());
        }
        // Refresh the stable <vendor>-<major> pointer so IntelliJ (and anything
        // pinning a path) survives point-release upgrades. Best-effort — a
        // failure here must not fail the install itself.
        try {
            new StableJdkPointer(registry.jdksRoot()).ensure(pointerName(entry), target);
        } catch (IOException pointerFailed) {
            // Still not fatal — the install is on disk and usable by its own name. But silence was
            // the wrong answer: a stable name left aiming at an older install is a wrong answer
            // that looks like a right one, and whatever is configured with that path keeps building
            // against the JDK the user just replaced.
            Log.warn(
                    "installed " + target.getFileName() + " but could not aim " + pointerName(entry) + " at it",
                    pointerFailed);
        }
        InstalledJdk installed = new InstalledJdk(installName, javaHome);
        recordInventory(installed);
        return installed;
    }

    private void recordInventory(InstalledJdk installed) {
        // The new install invalidates the registry's memoized probe scan.
        registry.refresh();
        try {
            JdkInventory.of(registry.jdksRoot()).record(installed, true);
        } catch (IOException ignored) {
            // Inventory is bookkeeping; the install is already on disk.
        }
    }

    private static Path javaHomeFor(JdkCatalog.Entry entry, Path target) {
        return entry.javaHomeSubpath().isEmpty() ? target : target.resolve(entry.javaHomeSubpath());
    }

    // jk owns the on-disk names: the durable install dir is
    // <vendor>-<version> and the stable pointer is <vendor>-<major>, both keyed
    // off the vendor's jbPrefix rather than the feed's (inconsistent)
    // install_folder_name / suggested_sdk_name. This guarantees the pointer and
    // the install never collide (unless a feed reports version == major, where
    // the install dir harmlessly doubles as its own pointer).

    /** Durable install dir name, e.g. {@code temurin-25.0.3} / {@code graalvm-25.0.4}. */
    private static String installName(JdkCatalog.Entry e) {
        return vendorToken(e) + "-" + e.version();
    }

    /** Stable pointer name, e.g. {@code temurin-25} / {@code graalvm-25}. */
    private static String pointerName(JdkCatalog.Entry e) {
        return vendorToken(e) + "-" + e.majorVersion();
    }

    private static String vendorToken(JdkCatalog.Entry e) {
        return JdkVendor.fromFeed(e.vendor(), e.product())
                .jbPrefix()
                .orElseGet(() -> stripTrailingMajor(e.suggestedSdkName()));
    }

    /**
     * {@code "graalvm-jdk-25"} → {@code "graalvm-jdk"}; leaves names without a trailing major intact.
     */
    private static String stripTrailingMajor(String suggested) {
        int dash = suggested.lastIndexOf('-');
        if (dash > 0) {
            String tail = suggested.substring(dash + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                return suggested.substring(0, dash);
            }
        }
        return suggested;
    }

    /** Buffered download for the {@link JdkPackage} flow: no progress, no streaming. */
    private void downloadAndExtractBuffered(
            URI uri, @Nullable String sha256, String displayName, String archiveType, Path target)
            throws IOException, InterruptedException {
        String expected = requireDigest(sha256, displayName, uri);
        Path downloads = prepareDownloadDir();
        Path archive = Files.createTempFile(downloads, DOWNLOAD_PREFIX, "-" + extensionFor(archiveType));
        try {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException("JDK download " + uri + " returned " + response.statusCode());
            }
            byte[] body = response.body();
            String actual = Hashing.sha256Hex(body);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(
                        "sha256 mismatch for " + displayName + " — expected " + expected + ", got " + actual);
            }
            Files.write(archive, body);
            SessionContext.current().io().remoteDown(archive);

            // Stage under the jdks root so the final rename is on the same
            // filesystem as the target. Otherwise (/tmp on tmpfs vs. $HOME on
            // ext4/btrfs) Files.move falls into the cross-device branch and
            // fails with DirectoryNotEmptyException on the first non-empty
            // subdir of the JDK.
            Path stagingDir = Files.createTempDirectory(registry.jdksRoot(), ".stage-");
            IN_FLIGHT.add(stagingDir);
            try {
                extract(archive, stagingDir, archiveType);
            } catch (IOException | RuntimeException e) {
                discardStaging(stagingDir);
                throw e;
            }
            moveIntoPlace(stagingDir, target, target);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /**
     * One rename from the stage into the durable name, then jk's ownership marker. The stage is
     * gone afterwards whatever happened: consumed by the rename, or discarded.
     *
     * <p>Two clients pre-flighting the same pin against one root both extract to their own stage
     * and rename to the same target. A rename is one {@code rename(2)}, so whatever refused ours
     * is a complete tree: {@link java.nio.file.FileAlreadyExistsException} when it was there before
     * our move began, and a bare "directory not empty" (or, on Windows, an access-denied)
     * {@link FileSystemException} when it landed between the existence check and the syscall — once
     * {@link #clearEmptyTarget} has taken an empty directory or a stale pointer out of the way, so
     * that what refuses the rename is a tree and not a leftover. The loser adopts the winner's tree
     * and marks it — marking is idempotent, so the loser also closes the winner's gap between its
     * rename and its mark. A target that is not a JDK (nothing jk marked, no {@code release} beside
     * {@code bin/java}) is whatever refused the move, and the failure says so.
     */
    private void moveIntoPlace(Path stagingDir, Path target, Path javaHome) throws IOException {
        clearEmptyTarget(target);
        try {
            Files.move(flattenedRoot(stagingDir), target);
        } catch (FileSystemException raced) {
            discardStaging(stagingDir);
            if (!completeInstall(target, javaHome)) throw raced;
        } catch (IOException | RuntimeException e) {
            discardStaging(stagingDir);
            throw e;
        }
        // Drop the (now-empty) staging wrapper when flattenedRoot hoisted a child out; when the
        // rename consumed the stage itself this is a no-op.
        discardStaging(stagingDir);
        JdkOwnership.mark(target);
    }

    /**
     * Unlink an empty directory, or a stale pointer, sitting at the install name — so the rename that
     * follows is what decides the outcome.
     *
     * <p>Nothing is lost: an empty directory holds nothing, and a {@code StableJdkPointer} alias is a
     * link this install is about to make redundant by taking the name itself (Oracle GraalVM reports
     * version 25 for major 25, so the install dir and the pointer are one path). A POSIX {@code
     * rename(2)} would have replaced an empty directory for free; Windows refuses, which is how one
     * such directory came to wedge every later install of that version on the reporting host. A
     * <em>populated</em> tree is left exactly where it is — it may be somebody's JDK — and the rename
     * below reports it.
     */
    private static void clearEmptyTarget(Path target) {
        // A directory or a link, and nothing else: the two shapes described above. Whether a Windows
        // junction answers isSymbolicLink or isDirectory(NOFOLLOW_LINKS) has moved between JDKs, so
        // both are asked and either is enough.
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException populated) {
            // Not ours to remove: Files.move reports whatever is in the way.
        }
    }

    /**
     * Whether {@code target} holds a finished install: a launcher, plus jk's marker or a JDK the
     * probe accepts.
     *
     * <p>The launcher is checked first and on its own, because both of the other two answers survive
     * a tree that no longer has one. {@code JdkOwnership.MARKER} is one small file at the top of an
     * install jk extracted, so it outlives a delete or an unclean shutdown that took the JDK out from
     * under it; the probe reads {@code release}, beside {@code bin} rather than in it. A tree missing
     * {@code bin/java} is not something to adopt or to report as installed whatever else it carries.
     *
     * <p>This asks about a JDK and stops there — {@code native-image} is {@link
     * #alreadyInstalled}'s extra question, not this one's. The two callers differ: deciding whether
     * a download can be skipped is a judgement about what the caller asked for, while adopting the
     * tree that just won a rename is a judgement about what is on disk. Holding the rename path to
     * the launcher too would turn a GraalVM that ships without one into an unbounded re-download,
     * and then a hard failure, for a JDK that is in fact installed.
     */
    static boolean completeInstall(Path target, Path javaHome) {
        if (!Files.isRegularFile(JdkFingerprint.java(javaHome))) return false;
        return JdkOwnership.isJkOwned(target)
                || ProbeSupport.discoverJdk(javaHome, "jk").isPresent();
    }

    /**
     * Ensure the download scratch dir exists and sweep any orphaned partials from previously-canceled
     * downloads, returning the dir to stream into.
     *
     * <p>The dir is {@code <jdksRoot>/.downloads}: under the JDK root so the archive shares a
     * filesystem with the install target, and dot-prefixed so {@link cc.jumpkick.discovery.JkProbe}
     * (which skips dot dirs) never mistakes it for an installed JDK.
     */
    private Path prepareDownloadDir() throws IOException {
        Path dir = registry.jdksRoot().resolve(DOWNLOAD_DIR);
        Files.createDirectories(dir);
        sweepDir(dir);
        return dir;
    }

    /**
     * Delete partial archives orphaned by a canceled download under {@code <jdksRoot>/.downloads}.
     * The download path runs this automatically, but it's also the public entry point for {@code jk
     * jdk} commands that don't download (uninstall) or may early-return before downloading (install
     * /update when the target is already present) — call it once per command so a leftover partial
     * never outlives the user's next {@code jk jdk} action. No-op when the scratch dir is absent.
     */
    public static void sweepStaleDownloads(Path jdksRoot) {
        Path dir = jdksRoot.resolve(DOWNLOAD_DIR);
        if (Files.isDirectory(dir)) sweepDir(dir);
    }

    /**
     * Delete partial archives orphaned by a canceled download. Ctrl-C triggers {@code Runtime.halt},
     * which terminates the JVM without running the finally-block cleanup in {@link #download}, so the
     * partial archive is left behind; the next {@code jk jdk} command sweeps it. Only files older
     * than {@link #STALE_DOWNLOAD_AGE_MILLIS} are removed, so a download in flight from a concurrent
     * {@code jk jdk install} is never yanked out from under it. Best-effort: unreadable or vanished
     * entries are left for the next sweep.
     */
    private static void sweepDir(Path downloadDir) {
        long cutoff = System.currentTimeMillis() - STALE_DOWNLOAD_AGE_MILLIS;
        try (Stream<Path> entries = Files.list(downloadDir)) {
            entries.filter(p -> p.getFileName().toString().startsWith(DOWNLOAD_PREFIX))
                    .forEach(p -> {
                        try {
                            if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignored) {
                            // vanished or unreadable — the next sweep catches it
                        }
                    });
        } catch (IOException ignored) {
            // can't list the dir — nothing here is load-bearing
        }
    }

    /**
     * The digest a catalog entry must carry before its archive is fetched. The feed is the trust
     * anchor for every JDK jk installs and runs; an entry that names no sha256 cannot be verified,
     * so it is refused rather than installed on TLS alone.
     */
    private static String requireDigest(@Nullable String sha256, String displayName, URI uri) throws IOException {
        if (sha256 == null || sha256.isBlank()) {
            throw new IOException("JDK " + displayName + " (" + uri
                    + ") carries no sha256 in its catalog entry; refusing to install an archive that cannot be"
                    + " verified");
        }
        return sha256.trim();
    }

    /**
     * Stream {@code uri} into {@code archive} while updating a SHA-256 digest and forwarding
     * cumulative byte counts to {@code onBytesRead}. Verifies the digest against {@code
     * expectedSha256} on completion; an entry without one is refused before the request goes out.
     */
    private long streamingDownload(
            URI uri, @Nullable String expectedSha256, String displayName, Path archive, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        String expected = requireDigest(expectedSha256, displayName, uri);
        HttpResponse<InputStream> response = http.getStream(uri);
        if (response.statusCode() != 200) {
            try (var body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("JDK download " + uri + " returned " + response.statusCode());
        }
        MessageDigest sha = Hashing.newSha256();
        long total = 0;
        try (InputStream body = response.body();
                OutputStream sink = Files.newOutputStream(archive)) {
            byte[] buf = new byte[DOWNLOAD_BUFFER];
            int n;
            while ((n = body.read(buf)) > 0) {
                sha.update(buf, 0, n);
                sink.write(buf, 0, n);
                total += n;
                onBytesRead.accept(total);
            }
        }
        String actual = Hashing.hex(sha.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("sha256 mismatch for " + displayName + " — expected " + expected + ", got " + actual);
        }
        return total;
    }

    private static String extensionFor(String archiveType) {
        if (archiveType == null) return "tar.gz";
        return switch (archiveType) {
            case "targz" -> "tar.gz";
            default -> archiveType;
        };
    }

    static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir);
            case "tar.gz", "tgz", "targz" -> extractTar(archive, destDir, true);
            case "tar" -> extractTar(archive, destDir, false);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    private static void extractTar(Path archive, Path destDir, boolean gzipped) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archive));
                InputStream inflated = gzipped ? new GZIPInputStream(fis) : fis) {
            MinimalTar.stream(inflated, (name, linkName, mode, isDir, isLink, data, size) -> {
                // Drop macOS AppleDouble sidecars (`._foo`) anywhere in the tree.
                if (isAppleDoubleSidecar(name)) return;
                Path out = destDir.resolve(name).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("tar entry escapes destination: " + name);
                }
                if (isDir) {
                    MinimalTar.createDirectoryInside(destDir, out);
                } else if (isLink) {
                    MinimalTar.createSymlinkInside(destDir, out, linkName);
                } else {
                    MinimalTar.requireParentInside(destDir, out);
                    Files.copy(data, out);
                    MinimalTar.applyMode(out, mode);
                }
            });
        }
    }

    private static boolean isAppleDoubleSidecar(String entryName) {
        int end = entryName.endsWith("/") ? entryName.length() - 1 : entryName.length();
        int slash = entryName.lastIndexOf('/', end - 1);
        String basename = entryName.substring(slash + 1, end);
        return basename.startsWith("._");
    }

    /**
     * Parallel ZIP extraction. ZIP's Central Directory lets us seek to any entry's deflate stream
     * independently, so we fan out across {@link JkThreads#cpu()} workers — meaningful on JDK zips
     * with several thousand entries (Windows JDK builds typically ship as zip).
     *
     * <p>Every entry is judged by where it really lands, the same way the tar path judges its
     * entries: directories through {@link MinimalTar#createDirectoryInside}, file parents through
     * {@link MinimalTar#requireParentInside}. That happens in the calling thread, entry by entry,
     * before any worker writes a byte — so a refused archive has created nothing beneath the
     * offending path, and the workers never race on creating the same parent.
     *
     * <p>Tar.gz can't be parallelized this way: gunzip is a single sequential stream and the tar
     * metadata is interleaved with the file data.
     */
    private static void unzip(Path archive, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            for (ZipEntry e : entries) {
                Path out = destDir.resolve(e.getName());
                if (e.isDirectory()) MinimalTar.createDirectoryInside(destDir, out);
                else MinimalTar.requireParentInside(destDir, out);
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>(entries.size());
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                Path out = destDir.resolve(entry.getName());
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try (InputStream in = zip.getInputStream(entry)) {
                                Files.copy(in, out);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        JkThreads.cpu()));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof UncheckedIOException uio) throw uio.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("zip extraction failed: " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * JDK archives usually unpack to a single top-level directory ({@code jdk-21.0.5+11}) — if so,
     * return that. Otherwise return the staging dir.
     */
    private static Path flattenedRoot(Path stagingDir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(stagingDir)) {
            stream.forEach(children::add);
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst())) {
            Path lifted = children.getFirst();
            // Extraction judged every link against the staging directory; lifted one level, a link
            // that reached up out of this directory would point outside the installed tree.
            MinimalTar.requireSymlinksInside(lifted);
            return lifted;
        }
        return stagingDir;
    }

    /** Delete a staging tree and stop tracking it — the two always happen together. */
    private static void discardStaging(Path stagingDir) {
        IN_FLIGHT.remove(stagingDir);
        deleteRecursively(stagingDir);
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
