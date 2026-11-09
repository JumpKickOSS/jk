// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Finds where this person keeps code, so Defender exclusions are proposed rather than typed. The
 * signals, strongest first: projects already known (built by JumpKick, recent in IntelliJ or VS
 * Code), then the conventional container folders under the profile and on each fixed drive. A
 * folder counts when, within two levels, it holds a project marker. The container is proposed, not
 * each project; a lone project outside any container is proposed as itself.
 *
 * <p>The walk skips hidden folders, {@code node_modules}, {@code target}, {@code build} and every
 * reparse point (junctions, links, OneDrive placeholders), and stops at its time budget. A proposed
 * junction is replaced by its real target, since Defender does not follow one. {@code JK_HOME} is
 * always proposed; nothing {@link Forbidden} ever is.
 *
 * @param profile {@code %USERPROFILE%}
 * @param appData {@code %APPDATA%}, where the IDEs keep their recent lists; null when unset
 * @param drives the fixed drives' roots ({@code C:\}, {@code D:\})
 * @param known projects already known to be projects
 */
public record RootDiscovery(
        Path profile, @Nullable Path appData, Path home, List<Path> drives, List<Path> known, Forbidden forbidden) {

    /** One proposed root and why it was proposed. {@code note} is advice for it, or null. */
    public record Root(Path path, String why, @Nullable String note) {}

    /** Container folders under the profile. */
    static final List<String> PROFILE_CONTAINERS = List.of(
            "IdeaProjects",
            "source\\repos",
            "src",
            "source",
            "Projects",
            "projects",
            "code",
            "dev",
            "git",
            "repos",
            "workspace",
            "eclipse-workspace",
            "AndroidStudioProjects",
            "NetBeansProjects",
            "Documents\\GitHub");

    /** Container folders at a fixed drive's root. */
    static final List<String> DRIVE_CONTAINERS = List.of("src", "dev", "code", "projects");

    private static final Set<String> MARKERS = Set.of(
            ".git",
            ManifestNames.MANIFEST,
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "package.json",
            "pyproject.toml",
            "Cargo.toml",
            "go.mod");

    private static final Set<String> SKIPPED = Set.of("node_modules", OutputDirs.TARGET, "build");

    public static final Duration BUDGET = Duration.ofSeconds(1);

    public RootDiscovery {
        drives = List.copyOf(drives);
        known = List.copyOf(known);
    }

    /** The proposed roots, {@code JK_HOME} first, none inside another. */
    public List<Root> propose() {
        return propose(BUDGET);
    }

    List<Root> propose(Duration budget) {
        long deadline = Clock.SYSTEM.nanos() + budget.toNanos();
        Map<String, Root> out = new LinkedHashMap<>();
        add(out, new Root(home, "JumpKick's store, cache and toolchains", null));
        List<Path> projects = new ArrayList<>(known);
        projects.addAll(ideRecents());
        for (Path p : projects) {
            if (Clock.SYSTEM.nanos() > deadline) break;
            if (!isDirectory(p)) continue;
            Path container = p.getParent();
            if (container != null
                    && forbidden.reason(container.toString()) == null
                    && projectsIn(container, deadline) >= 2) {
                add(out, new Root(container, "holds " + p.getFileName() + " and other projects", null));
            } else {
                String note = container != null && placedBadly(container) ? "move it out of " + container : null;
                add(out, new Root(p, "a project you work on", note));
            }
        }
        for (String name : PROFILE_CONTAINERS) {
            if (Clock.SYSTEM.nanos() > deadline) break;
            Path c = profile.resolve(
                    name.replace('\\', profile.getFileSystem().getSeparator().charAt(0)));
            if (isDirectory(c) && projectsIn(c, deadline) >= 1) add(out, new Root(c, "holds projects", null));
        }
        for (Path drive : drives) {
            for (String name : DRIVE_CONTAINERS) {
                if (Clock.SYSTEM.nanos() > deadline) break;
                Path c = drive.resolve(name);
                if (isDirectory(c) && projectsIn(c, deadline) >= 1) add(out, new Root(c, "holds projects", null));
            }
        }
        return List.copyOf(out.values());
    }

    /** Adds {@code root} by its real path unless it is forbidden or already inside a proposed root. */
    private void add(Map<String, Root> out, Root root) {
        Path real = realPath(root.path());
        String text = real.toString();
        if (!real.equals(home) && forbidden.reason(text) != null) return;
        for (Root existing : out.values())
            if (WinPaths.under(text, existing.path().toString())) return;
        out.values().removeIf(existing -> !existing.path().equals(home) && WinPaths.under(existing.path(), text));
        out.putIfAbsent(WinPaths.key(text), new Root(real, root.why(), root.note()));
    }

    private boolean placedBadly(Path container) {
        String c = WinPaths.key(container);
        return c.equals(WinPaths.key(profile.resolve("Documents")))
                || c.equals(WinPaths.key(profile.resolve("Desktop")));
    }

    /** How many projects {@code dir} holds within two levels, counting {@code dir} itself as none. */
    int projectsIn(Path dir, long deadline) {
        int n = 0;
        for (Path child : children(dir)) {
            if (Clock.SYSTEM.nanos() > deadline) break;
            if (isProject(child)) {
                n++;
                continue;
            }
            for (Path grandchild : children(child)) {
                if (isProject(grandchild)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private static boolean isProject(Path dir) {
        boolean[] found = {false};
        try {
            PathUtil.forEachChild(dir, (e, attrs) -> {
                String name = String.valueOf(e.getFileName());
                found[0] =
                        MARKERS.contains(name) || name.toLowerCase(Locale.ROOT).endsWith(".sln");
                return !found[0];
            });
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        return found[0];
    }

    /** The plain subfolders the walk may enter: no hidden, skipped or reparse-point ones. */
    private static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (e, ignored) -> {
                String name = String.valueOf(e.getFileName());
                if (name.startsWith(".") || SKIPPED.contains(name)) return true;
                BasicFileAttributes a = Files.readAttributes(e, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory() && !a.isSymbolicLink() && !a.isOther() && !hidden(e)) out.add(e);
                return true;
            });
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        return out;
    }

    private static boolean hidden(Path p) {
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDirectory(Path p) {
        return Files.isDirectory(p);
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }

    // ---- IDE recents ----

    private static final Pattern IDEA_ENTRY = Pattern.compile("<entry key=\"([^\"]+)\"");
    private static final Pattern VSCODE_FOLDER = Pattern.compile("\"(file:///[^\"]+)\"");

    /** IntelliJ's and VS Code's recent project folders. */
    List<Path> ideRecents() {
        List<Path> out = new ArrayList<>();
        if (appData == null) return out;
        for (Path options : jetBrainsOptions(appData.resolve("JetBrains"))) {
            String xml = read(options.resolve("recentProjects.xml"));
            if (xml == null) continue;
            Matcher m = IDEA_ENTRY.matcher(xml);
            while (m.find()) {
                String key = m.group(1).replace("$USER_HOME$", profile.toString());
                if (key.contains("$")) continue;
                out.add(Path.of(
                        key.replace('/', profile.getFileSystem().getSeparator().charAt(0))));
            }
        }
        String storage = read(
                appData.resolve("Code").resolve("User").resolve("globalStorage").resolve("storage.json"));
        if (storage != null) {
            Matcher m = VSCODE_FOLDER.matcher(storage);
            while (m.find()) {
                Path p = fileUri(m.group(1));
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static List<Path> jetBrainsOptions(Path jetBrains) {
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(jetBrains, (product, attrs) -> out.add(product.resolve("options")));
        } catch (IOException | RuntimeException none) {
            return out;
        }
        return out;
    }

    /** A {@code file:///c%3A/Users/…} URI as a path; null when it is not one. */
    static @Nullable Path fileUri(String uri) {
        try {
            String raw =
                    URLDecoder.decode(uri.substring("file:///".length()).replace("+", "%2B"), StandardCharsets.UTF_8);
            boolean drive = raw.length() >= 2 && raw.charAt(1) == ':';
            return Path.of((drive ? raw : "/" + raw).replace('/', File.separatorChar));
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
