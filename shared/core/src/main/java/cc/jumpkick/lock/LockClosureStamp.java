// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Which plugin answered a closure resolved for the lock, as the row records it ({@code plugin}):
 * the plugin's id and version, jk's version for a plugin jk bundles, and the digest of the jar it
 * runs from. A row another plugin build answered — a jk upgrade, a plugin rebuilt under the same
 * version — is a stale lock, which the next build relocks.
 */
public final class LockClosureStamp {

    private LockClosureStamp() {}

    /** A jar's digest, kept while its settled stat holds: read once per jar version this process sees. */
    private static final StampedMemo<Path, FileStamp, String> JAR_DIGESTS = StampedMemo.bounded(256);

    /** The stamp of {@code manifest}'s plugin as this process loads it. */
    public static String of(PluginDescriptor manifest) {
        StringBuilder b = new StringBuilder(manifest.id())
                .append(' ')
                .append(Objects.requireNonNullElse(manifest.version(), "-"));
        if (PluginTableRegistry.isBuiltIn(manifest.id())) b.append(" jk ").append(JkVersion.VERSION);
        Path jar = PluginTableRegistry.archive(manifest.id());
        if (jar != null) b.append(" jar ").append(jarDigest(jar));
        return b.toString();
    }

    private static String jarDigest(Path jar) {
        FileStamp stamp = FileStamp.of(jar);
        if (stamp == null) return "unreadable";
        return JAR_DIGESTS.get(jar.toAbsolutePath().normalize(), stamp, () -> {
            try {
                return Hashing.sha256Hex(jar).substring(0, 16);
            } catch (IOException e) {
                return "unreadable";
            }
        });
    }
}
