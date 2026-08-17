// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.tool.JarManifest;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk add}: Maven coord, catalog name, or local workspace module ({@code :name}/path) into
 * {@code jk.toml}. {@code --ping} checks availability only.
 */
public final class AddCommand implements CliCommand {

    private String coord;
    private String libraryFlag;
    private String groupFlag;
    private String nameFlag;
    private String versionFlag;
    private boolean test;
    private boolean runtime;
    private boolean provided;
    private boolean processor;
    private boolean ping;
    private GlobalOptions global;

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String description() {
        return "Add a dependency, or workspace module, to jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<handle>", "Manifest key; defaults to the dependency name.", "--library"),
                Opt.value("<group>", "Maven groupId. Required for a bare short name.", "--group"),
                Opt.value("<name>", "Maven artifactId; defaults to the library handle.", "--name"),
                // --version collides with the global --version, so jk uses --ver.
                Opt.value("<ver>", "Version selector, e.g. \"3.4.0\", \"~3.4\", \"=3.4.0\".", "--ver"),
                Opt.flag("Test scope", "--test"),
                Opt.flag("Runtime scope", "--runtime"),
                Opt.flag("Provided scope", "--provided"),
                Opt.flag("Annotation processor scope", "--processor"),
                Opt.flag("Check the dep is reachable without adding it.", "--ping"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "dep|path",
                Arity.ONE,
                "Library short name, name@ver, group:artifact[:ver], or path.\n"
                        + "A bare name is a path when that directory exists."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.coord = in.positionals().get(0);
        this.libraryFlag = in.value("library").orElse(null);
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.versionFlag = in.value("ver").orElse(null);
        this.test = in.isSet("test");
        this.runtime = in.isSet("runtime");
        this.provided = in.isSet("provided");
        this.processor = in.isSet("processor");
        this.ping = in.isSet("ping");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();

        // Explicit coordinate flags mean the library/coord form: a bare name that happens to stat
        // as a directory must not silently drop --group/--name/--ver/--ping. On
        // explicit path syntax the combination is contradictory — refuse rather than guess.
        boolean coordFlags =
                libraryFlag != null || groupFlag != null || nameFlag != null || versionFlag != null || ping;
        if (coordFlags && isExplicitPathSyntax(coord)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Add", "--library/--group/--name/--ver/--ping do not apply to a local path");
            return Exit.USAGE;
        }

        // Local path argument: file jar, workspace module dir, or bare name that stats as a dir.
        if (!coordFlags && isLocalPathArg(coord, dir)) {
            String normalized = coord.replace('\\', '/');
            String stripped = (normalized.charAt(0) == ':') ? normalized.substring(1) : normalized;
            Path candidate = dir.resolve(stripped).normalize();
            if (Files.isRegularFile(candidate)) {
                Scope scope = resolveScope();
                if (scope == null) return Exit.USAGE;
                return addFile(dir, candidate, scope);
            }
            Scope scope = resolveScope();
            if (scope == null) return Exit.USAGE;
            return addModule(dir, scope);
        }

        ParsedDep parsed;
        try {
            parsed = ParsedDep.parse(coord, libraryFlag, groupFlag, nameFlag, versionFlag);
        } catch (IllegalArgumentException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", e.getMessage());
            return Exit.USAGE;
        }

        if (ping) {
            return runPing(parsed.toCoord());
        }

        Path file = dir.resolve("jk.toml");
        if (!Files.exists(file)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", "no jk.toml in current directory");
            return Exit.CONFIG;
        }
        Scope scope = resolveScope();
        if (scope == null) return Exit.USAGE;
        try {
            EngineEdits.apply(
                    file,
                    "add-dependency",
                    java.util.List.of(
                            scope.canonical(),
                            parsed.library(),
                            parsed.group(),
                            parsed.name(),
                            parsed.versionLiteral()));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", e.getMessage());
            return 1;
        }
        String msg = "Added "
                + Coords.shortName(parsed.library())
                + " ("
                + Coords.gav(parsed.group(), parsed.name(), parsed.versionLiteral())
                + ") to "
                + Theme.colorize("dependency", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan());
        CommandWedge.printOk("Add", msg);
        CliOutput.out();
        CliOutput.out("The next "
                + Theme.colorize("jk build", Theme.active().warning())
                + " / "
                + Theme.colorize("jk lock", Theme.active().warning())
                + " will pin versions automatically");
        return 0;
    }

    /** The selected dependency scope, or {@code null} if more than one flag was given. */
    private Scope resolveScope() {
        int selected = (test ? 1 : 0) + (runtime ? 1 : 0) + (provided ? 1 : 0) + (processor ? 1 : 0);
        if (selected > 1) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Add", "--test / --runtime / --provided / --processor are mutually exclusive");
            return null;
        }
        return test
                ? Scope.TEST
                : runtime ? Scope.RUNTIME : provided ? Scope.PROVIDED : processor ? Scope.PROCESSOR : Scope.MAIN;
    }

    /**
     * Whether the positional denotes a local path/module rather than a library or Maven coord.
     *
     * <ul>
     *   <li>{@code :name} — explicit local marker
     *   <li>{@code ./n}, {@code n/}, {@code ../n}, backslash forms — path separators. Separators
     *       win over {@code @}/{@code :}: coords and versions never contain them, but Windows
     *       absolute paths ({@code C:\x}) and nested paths ({@code ./libs/foo@v2/mod}) do
     *   <li>bare {@code n} — <em>path</em> only when {@code cwd/n} is an existing directory; else
     *       library short name
     *   <li>separator-free {@code n@…} or {@code g:a…} — never a path (version / Maven coord)
     * </ul>
     */
    static boolean isLocalPathArg(String arg, Path cwd) {
        if (arg == null || arg.isEmpty()) return false;
        if (arg.charAt(0) == ':') return true;
        if (arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0) return true;
        // Versioned library short name — never a path.
        if (arg.indexOf('@') >= 0) return false;
        // Maven GAV (group:artifact[:version]) — ':' after the group, not a leading local marker.
        if (arg.indexOf(':') >= 0) return false;
        // Bare token: path if a relative directory exists, otherwise a library short name.
        if (cwd == null) return false;
        return Files.isDirectory(cwd.resolve(arg).normalize());
    }

    /** Explicit path syntax ({@code :name} marker or a separator) — never a library form. */
    static boolean isExplicitPathSyntax(String arg) {
        return arg != null
                && !arg.isEmpty()
                && (arg.charAt(0) == ':' || arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0);
    }

    /**
     * Add a local sibling as a dependency of the current project (pinned to the sibling's declared
     * version) and register it in the enclosing workspace root's {@code [workspace].modules}.
     */
    private int addModule(Path cwd, Scope scope) throws IOException {
        Path currentToml = cwd.resolve("jk.toml");
        if (!Files.exists(currentToml)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", "no jk.toml in current directory");
            return Exit.CONFIG;
        }
        // Strip the optional leading ':' marker and normalise Windows-style
        // separators so `:jackson`, `jackson/`, and `..\..\jackson` all resolve.
        String raw = coord.charAt(0) == ':' ? coord.substring(1) : coord;
        raw = raw.replace('\\', '/');
        if (raw.isBlank()) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", "empty module path");
            return Exit.USAGE;
        }
        Path target = cwd.resolve(raw).normalize();
        Path targetToml = target.resolve("jk.toml");
        if (!Files.exists(targetToml)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Add", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(target));
            return Exit.CONFIG;
        }
        var module = BuildCommand.projectInfoOrNull(target);
        if (module == null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Add", "could not read " + cc.jumpkick.cli.PathDisplay.styledRaw(targetToml));
            return 1;
        }
        String group = module.group();
        String artifact = module.name();
        String version = module.version();
        String name = (libraryFlag != null && !libraryFlag.isBlank()) ? libraryFlag : artifact;

        // 1. Dependency edge into the current project, pinned to the module's
        //    version — matching how this repo's own modules reference siblings.
        try {
            EngineEdits.apply(
                    currentToml,
                    "add-dependency",
                    java.util.List.of(scope.canonical(), name, group, artifact, "=" + version));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", e.getMessage());
            return 1;
        }
        CommandWedge.printOk(
                "Add",
                "Added "
                        + Coords.shortName(name)
                        + " ("
                        + Coords.gav(group, artifact, version)
                        + ") to ["
                        + scope.tomlSection()
                        + "]");

        // 2. Register membership in the enclosing workspace root (cwd itself
        //    when cwd is the root).
        Path root = cc.jumpkick.config.WorkspaceScan.findEnclosingWorkspace(cwd).orElse(cwd);
        Path rootToml = root.resolve("jk.toml");
        try {
            if (!target.startsWith(root)) {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Add",
                        raw
                                + " is outside the workspace root "
                                + root
                                + "; added the dependency but not registering it as a module.");
            } else if (Files.exists(rootToml) && BuildCommand.projectInfoOrNull(root) != null) {
                // Adding the first local module promotes a plain project into a workspace root
                // (Cargo/uv semantics) — without the registration the dependency names a
                // coordinate that was never published and `jk lock` cannot resolve it.
                boolean alreadyWorkspace = BuildCommand.projectInfoOrNull(root).workspaceRoot();
                String rel = root.relativize(target).toString().replace('\\', '/');
                String op = alreadyWorkspace ? "add-workspace-module" : "register-workspace-module";
                if (EngineEdits.apply(rootToml, op, java.util.List.of(rel))) {
                    CliOutput.out("Registered module '"
                            + rel
                            + "' in "
                            + (alreadyWorkspace ? "workspace " : "new workspace ")
                            + cc.jumpkick.cli.PathDisplay.styledRaw(root));
                }
            }
        } catch (RuntimeException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", "could not register workspace module: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Add an arbitrary local file as a dependency. The file is immediately stored in the CAS and
     * mirrored into the m2 local repo so {@code jk lock} can resolve it without re-reading the
     * original path.
     *
     * <p>For {@code .jar} files, Maven coordinate metadata is auto-detected from {@code
     * META-INF/maven/.../pom.properties}; flag overrides win. Non-JAR files, or JARs without embedded
     * metadata, require all three of {@code --group}, {@code --name}/{@code --library}, and {@code
     * --ver}.
     */
    private int addFile(Path cwd, Path filePath, Scope scope) throws IOException {
        Path tomlFile = cwd.resolve("jk.toml");
        if (!Files.exists(tomlFile)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", "no jk.toml in current directory");
            return Exit.CONFIG;
        }

        // Auto-detect coordinates from JAR metadata (best-effort).
        Optional<Coordinate> detected = Optional.empty();
        boolean isJar = filePath.getFileName().toString().toLowerCase().endsWith(".jar");
        if (isJar) {
            try {
                detected = JarManifest.coordinateFrom(filePath);
            } catch (IOException e) {
                // Non-fatal: fall through to flag-based coords.
            }
        }

        // Flags override auto-detected values.
        String group = nonBlankOr(groupFlag, detected.map(Coordinate::group).orElse(null));
        String artifact =
                nonBlankOr(nameFlag, detected.map(Coordinate::artifact).orElse(null));
        String version =
                nonBlankOr(versionFlag, detected.map(Coordinate::version).orElse(null));
        String library = nonBlankOr(libraryFlag, artifact != null ? artifact : null);

        // Validate: all three coordinates are required.
        if (group == null || artifact == null || version == null) {
            if (!isJar) {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Add", "--group, --name, and --ver are required for non-JAR files");
            } else {
                StringBuilder msg = new StringBuilder("jk add: could not detect");
                if (group == null) msg.append(" group");
                if (artifact == null) msg.append(group == null ? "," : "").append(" name");
                if (version == null) msg.append(" version");
                msg.append(" from JAR metadata");
                if (group == null) msg.append("; supply --group");
                if (artifact == null) msg.append("; supply --name");
                if (version == null) msg.append("; supply --ver");
                CliOutput.err(msg.toString());
            }
            return Exit.USAGE;
        }
        if (library == null) library = artifact;

        // Store in the CAS. File deps are resolved straight from the CAS by their sha256 at both
        // lock and build time (see ClasspathResolver), so no Maven-layout mirroring is needed.
        Path cache = JkDirs.cache();
        Files.createDirectories(cache);
        // Streamed hash + hard-link — the jar never has to fit in the CLI's small heap.
        String sha256 = Hashing.sha256Hex(filePath);
        Cas cas = JkStores.cas(cache);
        cas.putFile(filePath, sha256);

        // Edit jk.toml (engine-side).
        try {
            EngineEdits.apply(
                    tomlFile,
                    "add-file-dependency",
                    java.util.List.of(scope.canonical(), library, group, artifact, version, sha256));
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Add", e.getMessage());
            return 1;
        }

        String shortSha = sha256.substring(0, Math.min(12, sha256.length()));
        String msg = "Added "
                + Coords.shortName(library)
                + " ("
                + Coords.gav(group, artifact, version)
                + ", sha256:"
                + shortSha
                + "...)"
                + " to "
                + Theme.colorize("dependency", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan());
        CommandWedge.printOk("Add", msg);
        CliOutput.out();
        CliOutput.out("The next "
                + Theme.colorize("jk build", Theme.active().warning())
                + " / "
                + Theme.colorize("jk lock", Theme.active().warning())
                + " will pin versions automatically");
        return 0;
    }

    private static String nonBlankOr(String first, String fallback) {
        return (first != null && !first.isBlank()) ? first : fallback;
    }

    /**
     * Parsed representation of a dep spec. Carries the four pieces the editor needs ({@code name},
     * {@code group}, {@code artifact}, {@code versionLiteral}) and the floating/pinned distinction
     * for round-trip display.
     */
    record ParsedDep(String library, String group, String name, String versionLiteral, boolean floating) {

        static ParsedDep parse(
                String coord, String libraryFlag, String groupFlag, String nameFlag, String versionFlag) {
            if (coord == null || coord.isBlank()) {
                throw new IllegalArgumentException("dependency argument must not be blank");
            }
            int firstColon = coord.indexOf(':');
            int atSign = coord.indexOf('@');

            if (firstColon < 0) {
                // Bare short name, optionally with an `@version` suffix
                // (e.g. `jackson3-core` or `jackson3-core@3.1.0`). The layered
                // library catalog (workspace jk-libs.toml + global + bundled) supplies
                // group + artifact for curated names. The version comes from
                // --ver, else the `@version` suffix (caret-floating, like the
                // group:artifact@version coord form), else defaults to floating
                // "latest". All are resolved at `jk lock`. Flags override the catalog.
                String libraryKey = atSign >= 0 ? coord.substring(0, atSign) : coord;
                String atVersion = atSign >= 0 ? coord.substring(atSign + 1) : null;
                if (libraryKey.isBlank()) {
                    throw new IllegalArgumentException("empty name before '@' in: " + coord);
                }
                if (atVersion != null && atVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
                String library = nonBlank(libraryFlag, libraryKey);
                var catalog = cc.jumpkick.library.LibraryCatalog.forProject(
                        Path.of(".").toAbsolutePath().normalize(), CliOutput.stderr()::println);
                var catalogHit = catalog.lookup(libraryKey);
                String group = nonBlank(
                        groupFlag,
                        catalogHit
                                .map(cc.jumpkick.library.LibraryCatalog.Module::group)
                                .orElse(null));
                String name = nonBlank(
                        nameFlag,
                        catalogHit
                                .map(cc.jumpkick.library.LibraryCatalog.Module::artifact)
                                .orElse(library));
                if (group == null || group.isBlank()) {
                    StringBuilder msg = new StringBuilder("bare name `")
                            .append(libraryKey)
                            .append("` is not in the library catalog. ");
                    List<String> suggestions = catalog.suggestionsFor(libraryKey, 5);
                    if (!suggestions.isEmpty()) {
                        msg.append("Did you mean: ")
                                .append(String.join(", ", suggestions))
                                .append("? ");
                    }
                    msg.append("Either pick an library name or supply --group ")
                            .append("(and optionally --name) explicitly.");
                    throw new IllegalArgumentException(msg.toString());
                }
                boolean hasFlagVersion = versionFlag != null && !versionFlag.isBlank();
                String versionLiteral = hasFlagVersion ? versionFlag : atVersion != null ? atVersion : "latest";
                return new ParsedDep(library, group, name, versionLiteral, !hasFlagVersion);
            }

            // Maven-coord shorthand (has a colon). Three forms:
            //   group:artifact            → version="latest", floating=true
            //   group:artifact@version    → caret-floating
            //   group:artifact:version    → pinned (versionLiteral prefixed with `=`)
            int nextColon = coord.indexOf(':', firstColon + 1);
            int versionMark = atSign >= 0 && (nextColon < 0 || atSign < nextColon) ? atSign : nextColon;

            String moduleStr;
            String rawVersion;
            boolean floating;
            if (versionMark < 0) {
                moduleStr = coord;
                rawVersion = "latest";
                floating = true;
            } else if (versionMark == atSign) {
                moduleStr = coord.substring(0, atSign);
                rawVersion = coord.substring(atSign + 1);
                floating = true;
                if (rawVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
            } else {
                moduleStr = coord.substring(0, nextColon);
                rawVersion = coord.substring(nextColon + 1);
                // group:artifact:  (empty version) → same as group:artifact → latest
                if (rawVersion.isBlank()) {
                    rawVersion = "latest";
                    floating = true;
                } else {
                    floating = false;
                }
            }
            int sep = moduleStr.indexOf(':');
            if (sep < 0 || sep == moduleStr.length() - 1 || sep == 0) {
                throw new IllegalArgumentException("expected group:artifact in: " + coord);
            }
            String groupFromCoord = moduleStr.substring(0, sep);
            String artifactFromCoord = moduleStr.substring(sep + 1);

            // Flags override the parsed coord. Defaults: library → artifactId,
            // name → artifactId (override with --name), group → groupFromCoord.
            String library = nonBlank(libraryFlag, artifactFromCoord);
            String group = nonBlank(groupFlag, groupFromCoord);
            String name = nonBlank(nameFlag, artifactFromCoord);
            String versionLiteral = versionFlag != null && !versionFlag.isBlank()
                    ? versionFlag
                    // Pinned colon-form gets an explicit `=` prefix so the parser
                    // reads it as Exact (caret-default in `parseFloating`).
                    : floating ? rawVersion : "=" + rawVersion;
            return new ParsedDep(library, group, name, versionLiteral, floating);
        }

        private static String nonBlank(String flagValue, String fallback) {
            return (flagValue == null || flagValue.isBlank()) ? fallback : flagValue;
        }

        /** Best-effort Coordinate for --ping. Strips any `=` selector prefix. */
        Coordinate toCoord() {
            String v =
                    versionLiteral.startsWith("=") || versionLiteral.startsWith("^") || versionLiteral.startsWith("~")
                            ? versionLiteral.substring(1)
                            : versionLiteral;
            return Coordinate.of(group, name, v);
        }
    }

    private int runPing(Coordinate coord) throws IOException, InterruptedException {
        URI repoBase = RepositorySpec.MAVEN_CENTRAL.url();
        URI pomUri = repoBase.resolve(MavenLayout.pomPath(coord));
        String coordStr = Coords.gav(coord);

        var http = new Http();
        var response = http.get(pomUri);

        if (response.statusCode() == 200) {
            URI artifactUri = repoBase.resolve(MavenLayout.artifactPath(coord));
            CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().success()) + " " + coordStr + " is available.");
            CliOutput.out(osc8Link(artifactUri.toString()));
            return 0;
        }

        CliOutput.out(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + coordStr + " is unavailable.");
        CliOutput.out("Failed to find " + coordStr + " in any configured repo.");
        return 1;
    }

    /** OSC 8 hyperlink: the URL is both the link target and the visible text. */
    private static String osc8Link(String url) {
        String coloredUrl = Theme.colorize(url, Theme.active().activeStep());
        return Ansi.hyperlink(url, coloredUrl);
    }
}
