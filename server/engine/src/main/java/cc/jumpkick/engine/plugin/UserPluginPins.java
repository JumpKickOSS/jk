// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.UserPlugins;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code ~/.jk/config.toml [plugins]} path pins, registered over the shipped plugins and kept in
 * step with the file for the engine's whole life: a pin added, edited or removed while the engine
 * runs is what the next manifest parse validates against, with no restart. Maven pins wait for
 * {@code jk lock} (project lock is law) and are not registered here.
 *
 * <p>Each pinned jar is stamped alongside the file: a jar rebuilt in place is re-checked on the next
 * parse even though config.toml did not move, so a rebuild without its pin updated is refused rather
 * than served under the registration of the jar it replaced.
 *
 * <p>A pin that cannot be honored — missing file, unreadable jar, hash mismatch — is an error, never
 * the shipped plugin in its place: the pin is explicit user intent, and silently running other code
 * is wrong code with no diagnostic. This matches the project-pin posture ({@code jk lock} errors
 * naming both digests). Such a file changes nothing in the registry; the pins registered before it
 * stay until the file is fixed.
 *
 * <p>A config.toml that does not parse changes nothing either: engine control, doctor and help load
 * no user plugin, and every request that does fails on its own with the file's position ({@link
 * UserPlugins#fromConfig(Path)}).
 */
public final class UserPluginPins {

    private UserPluginPins() {}

    /** A path pin as the file declares it, the path resolved against the file's directory. */
    private record Pin(Path jar, String sha256) {}

    /** A shipped (or earlier) manifest a pin replaced, and the archive it had. */
    private record Displaced(
            PluginDescriptor descriptor, @Nullable Path archive) {}

    /**
     * A pin the registry holds: its descriptor (null for a jar without one), what it replaced, and
     * the jar's stamp taken before its digest was checked.
     */
    private record Registered(
            Pin pin,
            @Nullable PluginDescriptor descriptor,
            List<Displaced> displaced,
            StampedMemo.@Nullable FileStamp jarStamp) {}

    /**
     * The file version and pinned-jar versions the registry was last brought in step with, and the
     * pin error they gave.
     */
    private record Seen(
            Path config,
            StampedMemo.@Nullable FileStamp stamp,
            List<JarStamp> jars,
            @Nullable String failure) {}

    /** A pinned jar and its stamp when the registry was last brought in step with it. */
    private record JarStamp(Path jar, StampedMemo.@Nullable FileStamp stamp) {}

    private static final Object LOCK = new Object();

    /** Guarded by {@link #LOCK}, in registration order. */
    private static List<Registered> registered = List.of();

    private static volatile @Nullable Seen seen;

    /**
     * Engine start: register the file's pins and hook every later manifest parse to keep them in
     * step ({@link PluginTableRegistry#userPinSync}).
     *
     * @throws IllegalStateException when a pin cannot be honored — the engine refuses to start
     */
    public static void install() {
        PluginTableRegistry.userPinSync(UserPluginPins::syncForParse);
        Path config = JkDirs.userConfigFile();
        try {
            sync();
        } catch (UserConfigParseException unparsed) {
            Log.warn("jk engine: " + config + " [plugins] not registered: " + unparsed.getMessage());
        }
    }

    /**
     * The parse-time hook: one stat while the file is unchanged. A pin error is the parse's error, as
     * an unowned table or a bad jk.toml value is; a file that does not parse is left to the reads
     * that need it.
     */
    static void syncForParse() {
        Path config = JkDirs.userConfigFile();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(config);
        Seen last = seen;
        if (last == null || !current(last, config, stamp)) {
            try {
                sync();
            } catch (UserConfigParseException unparsed) {
                return;
            } catch (IllegalStateException unhonored) {
                throw new JkBuildParseException(unhonored.getMessage(), unhonored);
            }
            return;
        }
        if (last.failure() != null) throw new JkBuildParseException(last.failure());
    }

    private static boolean current(Seen last, Path config, StampedMemo.@Nullable FileStamp stamp) {
        if (!last.config().equals(config) || !unchanged(last.stamp(), stamp)) return false;
        for (JarStamp jar : last.jars()) {
            if (!unchanged(jar.stamp(), StampedMemo.FileStamp.of(jar.jar()))) return false;
        }
        return true;
    }

    private static boolean unchanged(StampedMemo.@Nullable FileStamp then, StampedMemo.@Nullable FileStamp now) {
        return Objects.equals(then, now) && settled(now);
    }

    /**
     * Bring the registry in step with {@link JkDirs#userConfigFile()}: unregister the pins the file
     * no longer declares (restoring what they replaced), register the ones it declares now, and drop
     * the parse memos when anything moved.
     *
     * @throws IllegalStateException when a pin cannot be honored; the registry is left as it was
     * @throws UserConfigParseException when the file does not parse; the registry is left as it was
     */
    public static void sync() {
        synchronized (LOCK) {
            Path config = JkDirs.userConfigFile();
            StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(config);
            List<Pin> wanted = pathPins(config, UserPlugins.fromConfig(config));
            // Stamped before any digest is read: a jar rewritten mid-check carries a newer stamp
            // than the one remembered here, so the next parse checks it again.
            List<JarStamp> jars = wanted.stream()
                    .map(pin -> new JarStamp(pin.jar(), StampedMemo.FileStamp.of(pin.jar())))
                    .toList();
            try {
                if (!wanted.equals(registered.stream().map(Registered::pin).toList())
                        || !jars.equals(registered.stream()
                                .map(r -> new JarStamp(r.pin().jar(), r.jarStamp()))
                                .toList())) {
                    replace(config, wanted, jars);
                }
            } catch (IllegalStateException unhonored) {
                remember(config, stamp, jars, unhonored.getMessage());
                throw unhonored;
            }
            remember(config, stamp, jars, null);
        }
    }

    /** An unsettled stamp may not tell this version from the next, so it is not remembered. */
    private static void remember(
            Path config, StampedMemo.@Nullable FileStamp stamp, List<JarStamp> jars, @Nullable String failure) {
        boolean settled = settled(stamp) && jars.stream().allMatch(j -> settled(j.stamp()));
        seen = settled ? new Seen(config, stamp, jars, failure) : null;
    }

    /** Absent files are settled: a file appearing later gives a stamp that differs from none. */
    private static boolean settled(StampedMemo.@Nullable FileStamp stamp) {
        return stamp == null || stamp.settled();
    }

    private static List<Pin> pathPins(Path config, List<PluginDeclaration> decls) {
        Path base = config.getParent() != null ? config.getParent() : Path.of(".");
        List<Pin> out = new ArrayList<>();
        for (PluginDeclaration decl : decls) {
            if (!decl.isPathPin()) continue;
            Path jar = Path.of(Objects.requireNonNull(decl.path(), "path"));
            if (!jar.isAbsolute()) jar = base.resolve(jar).normalize();
            out.add(new Pin(jar, decl.sha256()));
        }
        return List.copyOf(out);
    }

    /** Check every wanted pin first, so a bad one leaves the registry as it was; then swap. */
    private static void replace(Path config, List<Pin> wanted, List<JarStamp> jars) {
        List<@Nullable PluginDescriptor> descriptors = new ArrayList<>();
        for (Pin pin : wanted) descriptors.add(descriptorOf(config, pin));
        for (Registered r : registered.reversed()) {
            PluginDescriptor descriptor = r.descriptor();
            if (descriptor != null) PluginTableRegistry.unregister(descriptor);
            for (Displaced d : r.displaced()) PluginTableRegistry.restoreBuiltIn(d.descriptor(), d.archive());
        }
        List<Registered> now = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            Pin pin = wanted.get(i);
            PluginDescriptor descriptor = descriptors.get(i);
            List<Displaced> displaced = new ArrayList<>();
            if (descriptor != null) {
                for (PluginDescriptor existing : PluginTableRegistry.manifests()) {
                    if (existing.id().equals(descriptor.id())
                            || existing.table().equals(descriptor.table())) {
                        displaced.add(new Displaced(existing, PluginTableRegistry.archive(existing.id())));
                    }
                }
                PluginTableRegistry.putBuiltIn(descriptor, pin.jar());
            }
            now.add(new Registered(
                    pin, descriptor, List.copyOf(displaced), jars.get(i).stamp()));
        }
        registered = List.copyOf(now);
        // A memoized parse validated its tables against the registry as it was.
        JkBuildParser.dropMemos();
        Log.info("jk engine: " + config + " [plugins] path pins registered: "
                + wanted.stream().map(p -> p.jar().toString()).toList());
    }

    /** The pin's root {@code jk-plugin.toml}, checked against the pinned digest; null when the jar has none. */
    private static @Nullable PluginDescriptor descriptorOf(Path config, Pin pin) {
        Path jar = pin.jar();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    config + " [plugins] pins " + jar + ", which does not exist; fix or remove the pin");
        }
        String toml;
        try {
            String actual = Hashing.sha256Hex(jar);
            if (!actual.equals(pin.sha256())) {
                throw new IllegalStateException(config + " [plugins] pin " + jar + " hash mismatch: declared sha256 "
                        + pin.sha256() + ", file is " + actual + "; update the pin after rebuilding the jar");
            }
            toml = BuiltInPluginJars.manifestToml(jar);
        } catch (IOException e) {
            throw new IllegalStateException(config + " [plugins] pin " + jar + " is unreadable: " + e.getMessage(), e);
        }
        if (toml == null || toml.isBlank()) return null;
        try {
            return PluginDescriptors.parse(toml, jar + "!" + ManifestPaths.PLUGIN_MANIFEST);
        } catch (RuntimeException e) {
            throw new IllegalStateException(config + " [plugins] pin " + jar + ": " + e.getMessage(), e);
        }
    }
}
