// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static java.util.Objects.requireNonNull;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.command.EngineEdits;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.http.Http;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.tool.JarManifest;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk add}: Maven coord, catalog name, or local workspace module ({@code :name}/path) into
 * {@code jk.toml}. A coordinate without a version is pinned to its newest stable release, looked
 * up engine-side in the project's repositories; the file never carries {@code latest}. {@code
 * --ping} checks availability only.
 */
public final class AddCommand implements CliCommand {

    private String coord;
    private @Nullable String libraryFlag;
    private @Nullable String groupFlag;
    private @Nullable String nameFlag;
    private @Nullable String versionFlag;
    private @Nullable String classifierFlag;
    private boolean ping;
    private Invocation invocation;
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
        List<Opt> opts = new ArrayList<>(List.of(
                Opt.value("<handle>", "Manifest key; defaults to the dependency name.", "--library"),
                Opt.value("<group>", "Maven groupId. Required for a bare short name.", "--group"),
                Opt.value("<name>", "Maven artifactId; defaults to the library handle.", "--name"),
                // --version collides with the global --version, so jk uses --ver.
                Opt.value("<ver>", "Version selector, e.g. \"3.4.0\", \"^3.4\", \"~3.4.0\".", "--ver"),
                Opt.value("<c>", "Maven classifier; the handle defaults to name-<c>.", "--classifier")));
        opts.addAll(DepScopeFlags.options());
        opts.add(Opt.flag("Check the dep is reachable without adding it.", "--ping"));
        return List.copyOf(opts);
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "dep|path",
                Arity.ONE,
                "Library short name, name@ver, group:artifact[:ver], or path.\n"
                        + "Without a version the newest stable release is pinned.\n"
                        + "A bare name is a path when that directory exists."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.coord = in.positionals().get(0);
        this.libraryFlag = in.value("library").orElse(null);
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.versionFlag = in.value("ver").orElse(null);
        this.classifierFlag = in.value("classifier").filter(c -> !c.isBlank()).orElse(null);
        this.ping = in.isSet("ping");
        this.invocation = in;
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();

        // Explicit coordinate flags mean the library/coord form: a bare name that happens to stat
        // as a directory must not silently drop --group/--name/--ver/--ping. On
        // explicit path syntax the combination is contradictory — refuse rather than guess.
        boolean coordFlags = libraryFlag != null
                || groupFlag != null
                || nameFlag != null
                || versionFlag != null
                || classifierFlag != null
                || ping;
        if (coordFlags && isExplicitPathSyntax(coord)) {
            CommandWedge.printFail(
                    "Add", "--library/--group/--name/--ver/--classifier/--ping do not apply to a local path");
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
            parsed = ParsedDep.parse(coord, libraryFlag, groupFlag, nameFlag, versionFlag, classifierFlag);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Add", e.getMessage());
            return Exit.USAGE;
        }

        if (ping) {
            return runPing(parsed);
        }

        Path file = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(file)) return ManifestEditRefusal.print("Add", dir);
        Scope scope = resolveScope();
        if (scope == null) return Exit.USAGE;
        if (parsed.versionLiteral() == null && global.offline) {
            CommandWedge.printFail(
                    "Add",
                    "offline: cannot look up the current version of " + parsed.group() + ":" + parsed.name()
                            + "; pass an explicit version (" + parsed.group() + ":" + parsed.name()
                            + ":1.2.3 or --ver 1.2.3)");
            return Exit.USAGE;
        }
        String version;
        try {
            version = EngineEdits.applyDetail(
                    file,
                    "add-dependency",
                    List.of(
                            scope.canonical(),
                            requireNonNull(parsed.library()),
                            requireNonNull(parsed.group()),
                            requireNonNull(parsed.name()),
                            Objects.requireNonNullElse(parsed.versionLiteral(), "latest"),
                            Objects.requireNonNullElse(parsed.classifier(), "")));
        } catch (IOException e) {
            CommandWedge.printFail("Add", e.getMessage());
            return 1;
        }
        boolean managed = Dependency.MANAGED_KEYWORD.equals(version);
        String msg = "Added "
                + Coords.shortName(parsed.library())
                + " ("
                + (managed
                        ? parsed.group() + ":" + parsed.name() + ", version managed by the platform"
                        : Coords.gav(parsed.group(), parsed.name(), version))
                + (parsed.classifier() == null ? "" : ":" + parsed.classifier())
                + ") to "
                + Theme.colorize("dependency", Theme.active().cyan())
                + "."
                + Theme.colorize(scope.canonical(), Theme.active().cyan());
        CommandWedge.printOk("Add", msg);
        CliOutput.out();
        CliOutput.out(settleLine(version));
        return 0;
    }

    /**
     * What happens to the written selector from here: an exact pin stays where it is until {@code
     * jk update} moves it; a float is picked at the next lock; a managed version follows the
     * platform BOM.
     */
    static String settleLine(String version) {
        String lock = Theme.colorize("jk lock", Theme.active().warning());
        if (Dependency.MANAGED_KEYWORD.equals(version)) {
            return "Version managed by the platform BOM; " + lock + " reads it there and "
                    + Theme.colorize("jk update", Theme.active().warning()) + " moves the BOM";
        }
        if (VersionSelector.parse(version) instanceof VersionSelector.Exact) {
            return "Pinned to " + version + "; " + lock + " keeps it and "
                    + Theme.colorize("jk update", Theme.active().warning()) + " moves it";
        }
        return "`" + version + "` floats: the next "
                + Theme.colorize("jk build", Theme.active().warning()) + " / " + lock + " picks the newest match";
    }

    /** The selected dependency scope, or {@code null} when the flags name more than one table. */
    private @Nullable Scope resolveScope() {
        return DepScopeFlags.resolve("Add", invocation);
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
    public static boolean isLocalPathArg(String arg, Path cwd) {
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
    public static boolean isExplicitPathSyntax(String arg) {
        return arg != null
                && !arg.isEmpty()
                && (arg.charAt(0) == ':' || arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0);
    }

    /**
     * Add a local sibling as a dependency of the current project (pinned to the sibling's declared
     * version) and register it in the enclosing workspace root's {@code [workspace].modules}.
     */
    private int addModule(Path cwd, Scope scope) throws IOException {
        Path currentToml = cwd.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(currentToml)) return ManifestEditRefusal.print("Add", cwd);
        // Strip the optional leading ':' marker and normalise Windows-style
        // separators so `:jackson`, `jackson/`, and `..\..\jackson` all resolve.
        String raw = coord.charAt(0) == ':' ? coord.substring(1) : coord;
        raw = raw.replace('\\', '/');
        if (raw.isBlank()) {
            CommandWedge.printFail("Add", "empty module path");
            return Exit.USAGE;
        }
        Path target = cwd.resolve(raw).normalize();
        Path targetToml = target.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(targetToml)) {
            CommandWedge.printFail("Add", "no jk.toml in " + PathDisplay.styledRaw(target));
            return Exit.CONFIG;
        }
        var module = ProjectInfos.orNull(target);
        if (module == null) {
            CommandWedge.printFail("Add", "could not read " + PathDisplay.styledRaw(targetToml));
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
                    currentToml, "add-dependency", List.of(scope.canonical(), name, group, artifact, version));
        } catch (IOException e) {
            CommandWedge.printFail("Add", e.getMessage());
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
        Path root = WorkspaceScan.findEnclosingWorkspace(cwd).orElse(cwd);
        Path rootToml = root.resolve(ManifestPaths.MANIFEST);
        ProjectInfo rootInfo;
        try {
            if (!target.startsWith(root)) {
                CommandWedge.printFail(
                        "Add",
                        raw
                                + " is outside the workspace root "
                                + root
                                + "; added the dependency but not registering it as a module.");
            } else if (Files.exists(rootToml) && (rootInfo = ProjectInfos.orNull(root)) != null) {
                // Adding the first local module promotes a plain project into a workspace root
                // (Cargo/uv semantics) — without the registration the dependency names a
                // coordinate that was never published and `jk lock` cannot resolve it.
                boolean alreadyWorkspace = rootInfo.workspaceRoot();
                String rel = root.relativize(target).toString().replace('\\', '/');
                String op = alreadyWorkspace ? "add-workspace-module" : "register-workspace-module";
                if (EngineEdits.apply(rootToml, op, List.of(rel))) {
                    CliOutput.out("Registered module '"
                            + rel
                            + "' in "
                            + (alreadyWorkspace ? "workspace " : "new workspace ")
                            + PathDisplay.styledRaw(root));
                }
            }
        } catch (RuntimeException e) {
            CommandWedge.printFail("Add", "could not register workspace module: " + e.getMessage());
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
        Path tomlFile = cwd.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(tomlFile)) return ManifestEditRefusal.print("Add", cwd);

        // Auto-detect coordinates from JAR metadata (best-effort).
        Optional<Coordinate> detected = Optional.empty();
        boolean isJar =
                filePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
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
                CommandWedge.printFail("Add", "--group, --name, and --ver are required for non-JAR files");
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

        // Hash + CAS-put are engine-side; the CLI only names the file.
        String sha256;
        try {
            sha256 = EngineEdits.applyDetail(
                    tomlFile,
                    "add-file-dependency",
                    List.of(scope.canonical(), library, group, artifact, version, filePath.toString()));
        } catch (IOException e) {
            CommandWedge.printFail("Add", e.getMessage());
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
        return 0;
    }

    private static @Nullable String nonBlankOr(@Nullable String first, @Nullable String fallback) {
        return (first != null && !first.isBlank()) ? first : fallback;
    }

    /**
     * Parsed representation of a dep spec: the pieces the editor needs. {@code versionLiteral} is
     * the selector as the user spelled it, or {@code null} when none was given (or {@code latest}
     * was) — the engine then pins the newest stable release. {@code classifier} is {@code
     * --classifier} or null for the plain jar; with one and no {@code --library}, the handle is
     * {@code <name>-<classifier>} so the plain jar and its classified twin sit under two keys.
     */
    public record ParsedDep(
            @Nullable String library,
            @Nullable String group,
            @Nullable String name,
            @Nullable String versionLiteral,
            @Nullable String classifier) {

        static ParsedDep parse(
                String coord,
                @Nullable String libraryFlag,
                @Nullable String groupFlag,
                @Nullable String nameFlag,
                @Nullable String versionFlag,
                @Nullable String classifier) {
            ParsedDep plain = parsePlain(coord, libraryFlag, groupFlag, nameFlag, versionFlag);
            if (classifier == null) return plain;
            if (classifier.indexOf(':') >= 0) {
                throw new IllegalArgumentException("--classifier must be a word without `:`: " + classifier);
            }
            String library =
                    (libraryFlag == null || libraryFlag.isBlank()) ? plain.name() + "-" + classifier : plain.library();
            return new ParsedDep(library, plain.group(), plain.name(), plain.versionLiteral(), classifier);
        }

        private static ParsedDep parsePlain(
                String coord,
                @Nullable String libraryFlag,
                @Nullable String groupFlag,
                @Nullable String nameFlag,
                @Nullable String versionFlag) {
            if (coord == null || coord.isBlank()) {
                throw new IllegalArgumentException("dependency argument must not be blank");
            }
            int firstColon = coord.indexOf(':');
            int atSign = coord.indexOf('@');

            if (firstColon < 0) {
                // Bare short name, optionally with an `@version` suffix (`jackson3-core` or
                // `jackson3-core@3.1.0`). The layered library catalog (workspace jk-libs.toml +
                // global + bundled) supplies group + artifact for curated names. The version is
                // --ver, else the `@version` suffix, else the newest stable at write time. Flags
                // override the catalog.
                String libraryKey = atSign >= 0 ? coord.substring(0, atSign) : coord;
                String atVersion = atSign >= 0 ? coord.substring(atSign + 1) : null;
                if (libraryKey.isBlank()) {
                    throw new IllegalArgumentException("empty name before '@' in: " + coord);
                }
                if (atVersion != null && atVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
                String library = nonBlank(libraryFlag, libraryKey);
                var catalog = LibraryCatalog.forProject(
                        Path.of(".").toAbsolutePath().normalize(), CliOutput.stderr()::println);
                var catalogHit = catalog.lookup(libraryKey);
                String group = nonBlank(
                        groupFlag, catalogHit.map(LibraryCatalog.Module::group).orElse(null));
                String name = nonBlank(
                        nameFlag,
                        catalogHit.map(LibraryCatalog.Module::artifact).orElse(library));
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
                    msg.append("Pick a catalog name, write the Maven coordinate as group:artifact[:version], ")
                            .append("or supply --group (and optionally --name).");
                    throw new IllegalArgumentException(msg.toString());
                }
                String versionLiteral = nonBlank(versionFlag, atVersion);
                return new ParsedDep(library, group, name, explicitOrNull(versionLiteral), null);
            }

            // Maven-coord shorthand (has a colon). Three forms:
            //   group:artifact            → newest stable, pinned at write time
            //   group:artifact@selector   → the selector as written
            //   group:artifact:version    → an exact pin
            int nextColon = coord.indexOf(':', firstColon + 1);
            int versionMark = atSign >= 0 && (nextColon < 0 || atSign < nextColon) ? atSign : nextColon;

            String moduleStr;
            String rawVersion;
            if (versionMark < 0) {
                moduleStr = coord;
                rawVersion = null;
            } else if (versionMark == atSign) {
                moduleStr = coord.substring(0, atSign);
                rawVersion = coord.substring(atSign + 1);
                if (rawVersion.isBlank()) {
                    throw new IllegalArgumentException("empty version after '@' in: " + coord);
                }
            } else {
                moduleStr = coord.substring(0, nextColon);
                rawVersion = coord.substring(nextColon + 1);
                // group:artifact:  (empty version) → same as group:artifact
                if (rawVersion.isBlank()) rawVersion = null;
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
            return new ParsedDep(library, group, name, explicitOrNull(nonBlank(versionFlag, rawVersion)), null);
        }

        private static @Nullable String nonBlank(@Nullable String flagValue, @Nullable String fallback) {
            return (flagValue == null || flagValue.isBlank()) ? fallback : flagValue;
        }

        /** {@code latest} (any spelling) is the same request as no version: pin the newest stable. */
        private static @Nullable String explicitOrNull(@Nullable String selector) {
            if (selector == null) return null;
            return VersionSelector.parse(selector) instanceof VersionSelector.Latest ? null : selector;
        }

        /**
         * Best-effort Coordinate for --ping. A selector prefix is dropped; a version-less request
         * carries a placeholder, and the probe reads the artifact's metadata instead of a POM.
         */
        Coordinate toCoord() {
            String literal = versionLiteral == null ? "0" : versionLiteral;
            String v = literal.startsWith("=") || literal.startsWith("^") || literal.startsWith("~")
                    ? literal.substring(1)
                    : literal;
            return Coordinate.of(Objects.requireNonNull(group, "group"), Objects.requireNonNull(name, "name"), v);
        }
    }

    private int runPing(ParsedDep parsed) throws IOException, InterruptedException {
        Coordinate coord = parsed.toCoord();
        URI repoBase = RepositorySpec.MAVEN_CENTRAL.url();
        boolean versionless = parsed.versionLiteral() == null;
        URI probe = repoBase.resolve(versionless ? MavenLayout.metadataPath(coord) : MavenLayout.pomPath(coord));
        String coordStr = versionless ? coord.group() + ":" + coord.artifact() : Coords.gav(coord);

        var http = Http.forRepositories();
        var response = http.get(probe);

        if (response.statusCode() == 200) {
            URI artifactUri = versionless ? probe : repoBase.resolve(MavenLayout.artifactPath(coord));
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
        String coloredUrl = Theme.paint(url, Theme.active().activeStep());
        return Ansi.hyperlink(url, coloredUrl);
    }
}
