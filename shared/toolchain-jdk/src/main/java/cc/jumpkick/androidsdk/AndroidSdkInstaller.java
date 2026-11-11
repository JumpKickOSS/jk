// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StaleScratch;
import cc.jumpkick.http.Http;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.PoolUse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Ensures Android SDK components under {@link AndroidSdk}: reuse installed, else download from
 * Google's {@code repository2} feed (sha1-verified, license-gated via {@code licenses/}) through
 * jk's {@link Http}, so the feed and the archives honour {@code --offline} and the shared
 * retry/cooldown policy.
 */
public final class AndroidSdkInstaller {

    /** Test seam: overrides the feed URL (a {@code file:} fixture in tests). */
    public static final String FEED_URL_PROPERTY = "jk.android.feedUrl";

    private final AndroidSdk sdk;
    private final Http http;
    private @Nullable AndroidRepoFeed feed; // fetched once per installer instance

    public AndroidSdkInstaller(AndroidSdk sdk) {
        this(sdk, new Http());
    }

    /**
     * Google's feed and archives travel through jk's own {@link Http} like every other remote:
     * {@code --offline} refuses them up front, and retries, host cooldowns and transfer metering
     * apply. A private client would answer to none of that.
     */
    public AndroidSdkInstaller(AndroidSdk sdk, Http http) {
        this.sdk = Objects.requireNonNull(sdk, "sdk");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * The installed directory of {@code componentPath} ({@code platforms;android-28},
     * {@code platform-tools}, …), downloading it if absent. Never re-downloads an existing
     * component (Studio's copies count — the root may be a symlink into a Studio SDK).
     */
    public Path ensure(String componentPath) throws IOException, InterruptedException {
        return ensure(componentPath, null);
    }

    /**
     * As {@link #ensure(String)}, verifying the installed/downloaded revision against a
     * {@code jk-lock.toml} pin when one exists. The feed only ever offers each channel's current
     * revision, so a drifted component cannot be re-materialized at the pinned revision — jk
     * reports the drift (stderr) instead of silently building against different tool bytes;
     * {@code jk lock} refreshes the pin.
     */
    public Path ensure(String componentPath, @Nullable String pinnedRevision) throws IOException, InterruptedException {
        Path dir = sdk.componentDir(componentPath);
        if (sdk.installed(componentPath)) {
            PoolUse.touch(sdk.root(), dir);
            warnOnDrift(componentPath, pinnedRevision);
            return dir;
        }

        AndroidRepoFeed.Component component = feed().find(componentPath);
        if (component == null) {
            throw new IOException(
                    "unknown Android SDK component: " + componentPath + " — not in Google's repository feed");
        }
        AndroidRepoFeed.Archive archive = component.archiveFor(AndroidRepoFeed.hostOs());
        if (archive == null) {
            throw new IOException("Android SDK component " + componentPath + " has no archive for this OS");
        }

        String licenseId = component.licenseId();
        String licenseText = licenseId.isEmpty() ? null : feed().licenseText(licenseId);
        if (licenseText != null && !sdk.licenseAccepted(licenseId, AndroidRepoFeed.licenseHash(licenseText))) {
            throw new IOException("Android SDK component " + componentPath + " needs the '" + licenseId
                    + "' license; run jk android licenses -y");
        }

        if (pinnedRevision != null && !pinnedRevision.equals(component.revision())) {
            Log.warn("jk: " + componentPath + ": jk-lock.toml pins revision " + pinnedRevision + ", installing "
                    + component.revision() + "; run jk lock");
        }

        Path archiveFile = download(archive, componentPath);
        try {
            extractZip(archiveFile, dir);
        } finally {
            Files.deleteIfExists(archiveFile);
        }
        return dir;
    }

    private void warnOnDrift(String componentPath, @Nullable String pinnedRevision) {
        if (pinnedRevision == null) return;
        String installed = sdk.installedRevision(componentPath);
        if (installed != null && !installed.equals(pinnedRevision)) {
            Log.warn("jk: " + componentPath + ": jk-lock.toml pins revision " + pinnedRevision + ", using installed "
                    + installed + "; run jk lock");
        }
    }

    /** The parsed feed (fetched once; {@code file:} fixture override for tests). */
    public AndroidRepoFeed feed() throws IOException, InterruptedException {
        if (feed == null) {
            String url = System.getProperty(FEED_URL_PROPERTY, AndroidRepoFeed.FEED_URL);
            if (url.startsWith("file:")) {
                feed = AndroidRepoFeed.parse(Files.readAllBytes(Path.of(URI.create(url))));
            } else {
                HttpResponse<byte[]> response = http.get(URI.create(url));
                if (response.statusCode() != 200) {
                    throw new IOException("Android SDK feed " + url + " returned " + response.statusCode());
                }
                feed = AndroidRepoFeed.parse(response.body());
            }
        }
        return feed;
    }

    public AndroidSdk sdk() {
        return sdk;
    }

    /**
     * Download the archive next to the SDK root, verifying its sha1 as it streams. SHA-1 is what
     * Google's repository feed advertises for each archive, so the feed's format names the
     * algorithm — jk's own content hashing never does.
     */
    private Path download(AndroidRepoFeed.Archive archive, String componentPath)
            throws IOException, InterruptedException {
        Path downloads = Files.createDirectories(sdk.root().resolve(".downloads"));
        Path target = Files.createTempFile(downloads, "jk-sdk-", ".zip");
        URI uri = URI.create(AndroidRepoFeed.REPOSITORY_BASE + archive.url());
        HttpResponse<InputStream> response = http.getStream(uri);
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            Files.deleteIfExists(target);
            throw new IOException("Android SDK download " + uri + " returned " + response.statusCode());
        }
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        try {
            receive(response.body(), Http.declaredLength(response), archive, componentPath, sha1, target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target); // a transfer that died leaves no partial zip in .downloads
            throw e;
        }
        String actual = Hashing.hex(sha1.digest());
        if (!actual.equalsIgnoreCase(archive.sha1())) {
            Files.deleteIfExists(target);
            throw new IOException("Android SDK download " + uri + " checksum mismatch: expected " + archive.sha1()
                    + ", got " + actual);
        }
        return target;
    }

    /**
     * Stream {@code body} into {@code target} through {@code sha1}, reporting it to the run's {@link
     * TransferBoard} so the archive shows beside every other download of the run. The feed states
     * each archive's size, which stands in when the server declared no length ({@code declared} is
     * {@code -1}).
     */
    static void receive(
            InputStream body,
            long declared,
            AndroidRepoFeed.Archive archive,
            String componentPath,
            MessageDigest sha1,
            Path target)
            throws IOException {
        try (DigestInputStream in = new DigestInputStream(body, sha1);
                OutputStream out = Files.newOutputStream(target);
                TransferBoard.Transfer transfer =
                        SessionContext.current().io().transfers().open("Android SDK " + componentPath)) {
            transfer.started(declared >= 0 ? declared : archive.size() > 0 ? archive.size() : -1L);
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            for (int n; (n = in.read(buf)) >= 0; ) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                read += n;
                transfer.advanced(read);
            }
            transfer.finished();
        }
    }

    /**
     * Extract a component zip into {@code dir}. SDK zips carry one top-level directory
     * ({@code android-9/}, {@code platform-tools/}); its contents become the component dir —
     * matching sdkmanager's layout exactly.
     */
    /** Prefix of the tree a component zip unpacks into beside its target before the rename. */
    private static final String EXTRACT_PREFIX = ".extract-";

    static void extractZip(Path zip, Path dir) throws IOException {
        Path parent = dir.getParent() == null ? zip.getParent() : mkdirs(dir.getParent());
        StaleScratch.sweep(parent, EXTRACT_PREFIX);
        Path staging = Files.createTempDirectory(parent, EXTRACT_PREFIX);
        try {
            extractInto(zip, staging, dir);
        } finally {
            // Published, the staging holds at most an emptied shell; failed, a partial tree.
            deleteRecursively(staging);
        }
    }

    private static void extractInto(Path zip, Path staging, Path dir) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path out = staging.resolve(entry.getName()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("zip entry escapes the extraction dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    // Unix mode rides the "extra" high bits in java.util.zip only via ZipFile;
                    // mark the obvious executables by location instead (bin-less SDK zips are
                    // data-only; platform-tools' adb/fastboot live at the top level).
                    String name = out.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.contains(".") || name.endsWith(".sh")) {
                        out.toFile().setExecutable(true);
                    }
                }
            }
        }
        Path root = singleTopLevel(staging);
        Files.createDirectories(dir.getParent());
        publishOrAccept(root, dir);
    }

    /**
     * Install the extracted component at {@code dir}. Two modules of one build can reach the same
     * missing component together; the second extraction then finds the directory already there
     * and keeps it — the archive is the same bytes — instead of failing the build on the rename.
     */
    static void publishOrAccept(Path extracted, Path dir) throws IOException {
        try {
            AtomicWrites.publishDir(extracted, dir);
        } catch (IOException raced) {
            if (!Files.isDirectory(dir)) throw raced;
        }
    }

    private static Path mkdirs(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    private static Path singleTopLevel(Path staging) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging)) {
            Path only = null;
            for (Path child : children) {
                if (only != null) return staging; // multiple roots — the staging dir IS the component
                only = child;
            }
            return only != null && Files.isDirectory(only) ? only : staging;
        }
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
