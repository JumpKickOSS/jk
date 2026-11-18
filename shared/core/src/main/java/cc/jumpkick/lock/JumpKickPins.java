// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RulePackDeclarations;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * JumpKick's own pins in a project: the {@code cc.jumpkick.guards} rule packs the root rules file
 * extends, the {@code cc.jumpkick} coordinates a manifest's dependency tables pin exactly (the
 * plugin SDK), and a plugin's {@code [plugin] sdk} in its {@code jk-plugin.toml}. A plugin's {@code
 * jk-compat} is not one: it claims the oldest jk the plugin loads in, which may rightly be older
 * than the jk building it; it moves only with {@code sdk}, never below it ({@link
 * #movePluginManifest}). They are meant to track the jk that builds the project: JumpKick publishes them
 * with each release and removes older releases from its repository, so a pin behind the running jk
 * resolves today and stops resolving later. One reader for every surface that reports or moves
 * them — {@code jk doctor}, the build's warning, {@code jk outdated}, {@code jk update} — so they
 * agree on what is behind.
 */
public final class JumpKickPins {

    /** The Maven group JumpKick publishes under; its rule packs are under {@code cc.jumpkick.guards}. */
    public static final String GROUP = "cc.jumpkick";

    /** Where a plugin module keeps its manifest. */
    public static final String PLUGIN_MANIFEST = "src/main/resources/" + ManifestNames.PLUGIN_MANIFEST;

    /** The scope a rule pack's pin is reported and moved under. */
    public static final String PACKS = "guards.extends";

    /** The scope of a plugin manifest's SDK version. */
    public static final String PLUGIN_SDK = "jk-plugin.toml sdk";

    /** The code of the warning a build gives for pins behind the running jk. */
    public static final String BEHIND = "pins-behind-jk";

    /** The coordinate a plugin manifest's {@code sdk} names. */
    public static final String SDK = GROUP + ":jk-plugin-sdk";

    private JumpKickPins() {}

    /**
     * One pin: the file that writes it, the scope it is written under ({@link #PACKS}, a dependency
     * table, {@link #PLUGIN_SDK}), the coordinate it names and the version it pins.
     */
    public record Pin(Path file, String scope, String coordinate, String version) {

        /** {@code library 0.50.2 (jk-guards.toml)}: the artifact, the version and the file, relative to {@code root}. */
        public String describe(Path root) {
            String artifact = coordinate.substring(coordinate.lastIndexOf(':') + 1);
            String what = (scope.equals(PLUGIN_SDK) ? "jk-plugin.toml sdk" : artifact) + " " + version;
            Path rel = root.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize());
            return what + " (" + rel.toString().replace('\\', '/') + ")";
        }
    }

    /** True when {@code group} is JumpKick's: {@code cc.jumpkick} or one under it. */
    public static boolean isJumpKick(String group) {
        return group.equals(GROUP) || group.startsWith(GROUP + ".");
    }

    /** True for a {@code group:artifact} coordinate in a JumpKick group. */
    public static boolean ownsCoordinate(String coordinate) {
        int colon = coordinate.indexOf(':');
        return colon > 0 && isJumpKick(coordinate.substring(0, colon));
    }

    /**
     * True when {@code pinned} is a release older than {@code client}, a release. A pin that is not
     * a plain version compares by numeric core. A client that is not a release (a checkout's
     * {@code -SNAPSHOT} build) calls nothing behind: its version is published nowhere, the release
     * before it may be the newest there is, and the way out would be to pin a snapshot.
     */
    public static boolean behind(String pinned, String client) {
        if (!isRelease(client)) return false;
        String p = Versions.numericCore(pinned);
        String c = Versions.numericCore(client);
        if (p.isEmpty() || c.isEmpty()) return false;
        return Versions.compare(p, c) < 0;
    }

    /**
     * The version a JumpKick pin moves to: the newest stable release in {@code available} that is
     * not newer than {@code client} and is newer than {@code current}; {@code null} when there is
     * none. A newer release than the client may need a newer jk, so jk's own artifacts follow the
     * jk that builds them, not the repository's newest.
     */
    public static @Nullable String target(String current, List<String> available, String client) {
        String ceiling = Versions.numericCore(client);
        return available.stream()
                .filter(Versions::isStable)
                .filter(v -> ceiling.isEmpty() || Versions.compare(v, ceiling) <= 0)
                .filter(v -> Versions.compare(v, current) > 0)
                .max(Versions::compare)
                .orElse(null);
    }

    /**
     * Every JumpKick pin of the lock scope at {@code lockDir}: the root and each workspace member,
     * in that order, packs first. A manifest that does not parse contributes nothing here; the
     * build reports it.
     */
    public static List<Pin> declared(Path lockDir) {
        Path root = lockDir.toAbsolutePath().normalize();
        List<Pin> out = new ArrayList<>();
        try {
            for (String text : RulePackDeclarations.of(root)) {
                String[] parts = text.strip().split(":");
                if (parts.length == 3 && isJumpKick(parts[0])) {
                    out.add(new Pin(
                            root.resolve(RulePackDeclarations.RULES_FILE), PACKS, parts[0] + ":" + parts[1], parts[2]));
                }
            }
        } catch (IOException unreadable) {
            // the guard loader reports an unreadable rules file
        }
        for (Map.Entry<Path, JkBuild> module : modules(root).entrySet()) {
            Path dir = module.getKey();
            Path manifest = ManifestPaths.manifestIn(dir);
            Set<String> seen = new LinkedHashSet<>();
            module.getValue().dependencies().byScope().forEach((scope, deps) -> {
                for (Dependency dep : deps) {
                    if (dep.isPath() || dep.isWorkspace() || dep.isFile() || dep.isGit()) continue;
                    if (!ownsCoordinate(dep.module())) continue;
                    if (!(dep.version() instanceof VersionSelector.Exact exact)) continue;
                    if (!seen.add(dep.module())) continue;
                    out.add(new Pin(manifest, scope.canonical(), dep.module(), exact.version()));
                }
            });
            out.addAll(pluginManifest(dir));
        }
        return List.copyOf(out);
    }

    /** The pins of {@link #declared} behind {@code client}. */
    public static List<Pin> behind(Path lockDir, String client) {
        return declared(lockDir).stream()
                .filter(p -> behind(p.version(), client))
                .toList();
    }

    /** True when {@code client} is a released jk, the only one whose pins can be behind it ({@link #behind}). */
    public static boolean isRelease(String client) {
        return Versions.isStable(client);
    }

    /**
     * The one line a build and {@code jk doctor} say about {@code behind}; empty when there is
     * nothing, or when {@code client} is not a release, whose version no pin may move to.
     */
    public static String describe(Path lockDir, List<Pin> behind, String client) {
        if (behind.isEmpty() || !isRelease(client)) return "";
        String pins = behind.stream().map(p -> p.describe(lockDir)).collect(Collectors.joining(", "));
        return "JumpKick's own " + (behind.size() == 1 ? "pin is" : "pins are") + " behind this jk (" + client
                + "): " + pins + "; JumpKick removes older releases from its repository, so run jk update to"
                + " move " + (behind.size() == 1 ? "it" : "them") + " to " + client;
    }

    /** The {@code [plugin] sdk} of the plugin manifest under {@code dir}, when it has one. */
    public static List<Pin> pluginManifest(Path dir) {
        Path file = dir.resolve(PLUGIN_MANIFEST);
        if (!Files.isRegularFile(file)) return List.of();
        TomlParseResult toml;
        try {
            toml = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return List.of();
        }
        if (toml.hasErrors()) return List.of();
        List<Pin> out = new ArrayList<>();
        String sdk = toml.getString(List.of("plugin", "sdk"));
        if (sdk != null && !sdk.isBlank()) out.add(new Pin(file, PLUGIN_SDK, SDK, sdk.strip()));
        return out;
    }

    /**
     * {@code text}, a plugin manifest, with {@code sdk} written as {@code to} and a {@code jk-compat}
     * floor below {@code to} raised to it; the text as it was when neither applies.
     */
    public static String movePluginManifest(String text, String to) {
        String moved = text.replaceFirst("(?m)^(\\s*sdk\\s*=\\s*)([\"'])[^\"'\\n]*\\2", "$1$2" + to + "$2");
        Matcher compat = Pattern.compile("(?m)^(\\s*jk-compat\\s*=\\s*)([\"'])>=\\s*([^\"'\\n]*)\\2")
                .matcher(moved);
        if (compat.find() && Versions.compare(compat.group(3).strip(), to) < 0) {
            moved = moved.substring(0, compat.start()) + compat.group(1) + compat.group(2) + ">=" + to + compat.group(2)
                    + moved.substring(compat.end());
        }
        return moved;
    }

    /** The manifest under {@code root} and, for a workspace root, every member's, by directory. */
    private static Map<Path, JkBuild> modules(Path root) {
        Map<Path, JkBuild> out = new LinkedHashMap<>();
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return out;
        try {
            JkBuild build = JkBuildParser.parseLocal(manifest);
            out.put(root, build);
            if (build.isWorkspaceRoot()) out.putAll(WorkspaceLoader.loadModules(root, build));
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position
        } catch (IOException | RuntimeException unparsed) {
            // the build reports a manifest that does not parse
        }
        return out;
    }
}
