// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

/**
 * Progress sink for {@link JdkService} installs. Defaults are no-ops; override only what you need.
 * Sequence: already-installed ({@link #onAlreadyInstalled}) or download → extract →
 * {@link #onInstalled}. {@link #onDownloadProgress} may run off the calling thread.
 */
public interface JdkInstallListener {

    /** A listener that ignores every event. */
    JdkInstallListener NO_OP = new JdkInstallListener() {};

    /**
     * The catalog entry a spec resolved to. Emitted only by the aggregate {@link
     * JdkService#install(String, cc.jumpkick.jdk.JdkRegistry, boolean, java.net.URI,
     * java.nio.file.Path, String, String, JdkInstallListener)} path (where the facade did the
     * resolution); the entry-taking overload skips it since the caller already holds the entry.
     */
    default void onResolved(JdkCatalog.Entry entry) {}

    /** The target JDK was already present on disk; no download or extraction ran. */
    default void onAlreadyInstalled(InstalledJdk jdk) {}

    /**
     * A fresh download is about to begin.
     *
     * @param label human-readable JDK label (e.g. {@code "Eclipse Temurin 26"})
     * @param totalBytes archive size from the feed, or {@code 0} when the feed omits it
     */
    default void onDownloadStart(String label, long totalBytes) {}

    /** Cumulative bytes read so far; {@code totalBytes} mirrors {@link #onDownloadStart}. */
    default void onDownloadProgress(long readBytes, long totalBytes) {}

    /** The archive finished downloading and extraction is starting. */
    default void onExtractStart(String label) {}

    /** The JDK has been fully extracted and registered. Terminal event of the fresh-install path. */
    default void onInstalled(InstalledJdk jdk) {}
}
