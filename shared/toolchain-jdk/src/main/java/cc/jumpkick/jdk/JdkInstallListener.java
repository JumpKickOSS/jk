// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.List;

/**
 * Progress sink for {@link JdkService} installs. Defaults are no-ops; override only what you need.
 * Sequence: already-installed ({@link #onAlreadyInstalled}) or download → extract →
 * {@link #onInstalled}. {@link #onDownloadProgress} may run off the calling thread.
 */
public interface JdkInstallListener {

    /** A listener that ignores every event. */
    JdkInstallListener NO_OP = new JdkInstallListener() {};

    /**
     * One listener that forwards every event to each of {@code sinks} in order — a renderer and a
     * plan-label emitter watching the same install, without either knowing about the other.
     */
    static JdkInstallListener tee(JdkInstallListener... sinks) {
        List<JdkInstallListener> all = List.of(sinks);
        return new JdkInstallListener() {
            @Override
            public void onResolved(JdkCatalog.Entry entry) {
                for (JdkInstallListener s : all) s.onResolved(entry);
            }

            @Override
            public void onAlreadyInstalled(InstalledJdk jdk) {
                for (JdkInstallListener s : all) s.onAlreadyInstalled(jdk);
            }

            @Override
            public void onDownloadStart(String label, long totalBytes) {
                for (JdkInstallListener s : all) s.onDownloadStart(label, totalBytes);
            }

            @Override
            public void onDownloadProgress(long readBytes, long totalBytes) {
                for (JdkInstallListener s : all) s.onDownloadProgress(readBytes, totalBytes);
            }

            @Override
            public void onExtractStart(String label) {
                for (JdkInstallListener s : all) s.onExtractStart(label);
            }

            @Override
            public void onInstalled(InstalledJdk jdk) {
                for (JdkInstallListener s : all) s.onInstalled(jdk);
            }
        };
    }

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
