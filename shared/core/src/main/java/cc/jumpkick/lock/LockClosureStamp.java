// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Which plugin answered a closure resolved for the lock, as the row records it ({@code plugin}):
 * the plugin's id and version, jk's version for a plugin jk bundles, and the digest of the jar it
 * runs from. A row another plugin build answered — a jk upgrade, a plugin rebuilt under the same
 * version — is a stale lock, which the next build relocks.
 */
public final class LockClosureStamp {

    private LockClosureStamp() {}

    /** A jar's digest, kept per path, size and modification time: read once per jar this process sees. */
    private static final Map<String, String> JAR_DIGESTS = new ConcurrentHashMap<>();

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
        try {
            BasicFileAttributes attrs = Files.readAttributes(jar, BasicFileAttributes.class);
            String key = jar.toAbsolutePath() + "|" + attrs.size() + "|"
                    + attrs.lastModifiedTime().toMillis();
            @Nullable String known = JAR_DIGESTS.get(key);
            if (known != null) return known;
            String hex = Hashing.sha256Hex(jar).substring(0, 16);
            JAR_DIGESTS.put(key, hex);
            return hex;
        } catch (IOException e) {
            return "unreadable";
        }
    }
}
