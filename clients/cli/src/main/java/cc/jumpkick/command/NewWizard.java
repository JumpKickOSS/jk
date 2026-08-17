// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.tui.Answers;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.cli.tui.WizardStep;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.scaffold.NewInputs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/** Wizard construction and flag/name helpers for jk new. */
@NullMarked
public final class NewWizard {

    private NewWizard() {}

    static Optional<String> wizardPresetName(Path directoryArg, Path cwd) {
        if (directoryArg == null) return Optional.empty();
        if (isCurrentDirArg(directoryArg)) {
            var leaf = cwd.getFileName();
            if (leaf == null) return Optional.empty();
            var s = leaf.toString();
            return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
        }
        var leaf = directoryArg.getFileName();
        if (leaf == null) return Optional.empty();
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the resolved project name.
     * Package-private for unit testing.
     */
    static Path resolveTarget(Path directoryArg, Path cwd, String projectName) {
        if (directoryArg != null) {
            if (isCurrentDirArg(directoryArg)) return cwd;
            return cwd.resolve(directoryArg).normalize();
        }
        // No positional → create a subdir under cwd named after the project.
        return cwd.resolve(projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    static boolean isCurrentDirArg(Path arg) {
        var raw = arg.toString();
        return raw.equals(".") || raw.equals("./") || raw.equals(".\\");
    }

    static NewInputs.Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return NewInputs.Language.JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "java" -> NewInputs.Language.JAVA;
            case "kotlin", "kt" -> NewInputs.Language.KOTLIN;
            case "groovy" -> NewInputs.Language.GROOVY;
            default ->
                throw new IllegalArgumentException(
                        "jk new: --lang must be 'java', 'kotlin', or 'groovy', got: " + value);
        };
    }

    static List<String> parseDeps(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    static int parseJdkMajorOrDefault(String jdk) {
        return NewJdkOptions.parseMajor(jdk).orElse(25);
    }

    /** Current Java LTS feature release. Bumped on each new LTS. */
    static final int LATEST_LTS_MAJOR = 25;

    static String deriveMainFqcn(String group, NewInputs.Language lang, boolean compact) {
        return switch (lang) {
            case JAVA -> group + ".Main";
            case KOTLIN -> compact ? "MainKt" : group + ".MainKt";
            case GROOVY -> compact ? "Main" : group + ".Main";
        };
    }

    static Wizard buildWizard(
            List<NewJdkCandidate> candidates,
            cc.jumpkick.jdk.JdkCatalog catalog,
            String groupGuess,
            NewCommand.ParentInfo parent,
            boolean hasDefaultJdk,
            boolean isInit) {
        boolean module = parent != null;
        // Modules inherit the parent's group, JDK, and language as defaults; a
        // standalone project guesses the group and defaults to the latest LTS.
        String effectiveGroup = module ? parent.group() : groupGuess;
        String langDefault = module && parent.kotlin() ? "kotlin" : module && parent.groovy() ? "groovy" : "java";

        // The wizard opens with the "native" toggle off, so the initial radio
        // list is whatever filter produces for the non-native case — which
        // promotes Temurin LTS to the top. Take the default selection from
        // there so the preselected row matches what the user sees. For a
        // module, prefer the candidate matching the parent's JDK major.
        var initial = NewJdkCandidate.filter(candidates, false, LATEST_LTS_MAJOR);
        if (initial.isEmpty()) initial = candidates;
        var defaultJdkId = initial.getFirst().id();
        if (module) {
            defaultJdkId = candidates.stream()
                    .filter(c -> c.major() == parent.jdkMajor())
                    .map(NewJdkCandidate::id)
                    .findFirst()
                    .orElse(defaultJdkId);
        }

        var javaLayoutStep = WizardStep.RadioStep.vertical("layout", "Project layout:")
                .choice("simple", "Simple / Mill-like (./src, ./test/src, ./resources, ./test/resources)")
                .choice("traditional", "Traditional (sources in ./src/main/java, tests in ./src/test/java)")
                .defaultChoice("simple")
                .when(a -> "java".equals(a.get("lang")))
                .build();

        var kotlinLayoutStep = WizardStep.RadioStep.vertical("layout", "Project layout:")
                .choice("simple", "Simple / Mill-like (./src, ./test/src, ./resources, ./test/resources)")
                .choice("traditional", "Traditional (sources in ./src/main/kotlin, tests in ./src/test/kotlin)")
                .defaultChoice("simple")
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        // Type-ahead library picker: catalog short names + free-form GAV.
        var librariesStep = WizardStep.MultiSelectStep.vertical("libraries", "Libraries / dependencies:")
                .choicesFn(a -> libraryPickerChoices())
                .filterable(true)
                .customOption("group:artifact or short-name (e.g. com.google.guava:guava)")
                .defaults(Set.of("jspecify"))
                .when(a -> "java".equals(a.get("lang")))
                .build();

        var kotlinLibrariesStep = WizardStep.MultiSelectStep.vertical("libraries", "Libraries / dependencies:")
                .choicesFn(a -> libraryPickerChoices())
                .filterable(true)
                .customOption("group:artifact or short-name")
                .defaults(Set.of("kotest"))
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        var kotlinOptions = WizardStep.MultiSelectStep.vertical("kotlinOptions", "Kotlin options:")
                .choice("module", "Set module name")
                .defaults(Set.of("module"))
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        var buildTargets = WizardStep.MultiSelectStep.vertical("targets", "Build output:")
                .choice("jar", "Regular jar")
                .choice("assembly", "Assembly (fat) jar")
                .choice("native", "Native binary")
                .defaults(Set.of("jar"))
                .when(a -> "executable".equals(a.get("kind")))
                .build();

        // Java projects pick their language version (the `java = N` target)
        // before choosing a JDK — it shapes the JDK list (you can't target a
        // release newer than the toolchain). Modules inherit the parent's
        // release, and when a global default JDK is set we adopt its major, so
        // both skip this question. Kotlin projects skip it too.
        // Two tracks, both derived from the live jdks.json catalog (so a newly
        // published major appears automatically): a standard track over all
        // distributions, and a native track restricted to native-image-capable
        // (GraalVM) JDKs — you can't build a native binary against a Java release
        // no GraalVM ships yet (e.g. 26 today). `targets` (the native choice) is
        // asked before this step, so the choicesFn can read it per render. When
        // the catalog is unavailable (offline) we fall back to constants.
        String os = cc.jumpkick.jdk.HostPlatform.currentOs();
        String arch = cc.jumpkick.jdk.HostPlatform.currentArch();
        List<Integer> standardMajors = orElseList(
                cc.jumpkick.jdk.SupportedJdk.offerableMajors(catalog, false, os, arch), offlineMajors(false));
        List<Integer> nativeMajors =
                orElseList(cc.jumpkick.jdk.SupportedJdk.offerableMajors(catalog, true, os, arch), offlineMajors(true));
        var javaVersion = WizardStep.RadioStep.horizontal("javaVersion", "Java Language Version:")
                .choicesFn(a -> (a.getList("targets").contains("native") ? nativeMajors : standardMajors)
                        .stream()
                                .map(m -> new cc.jumpkick.cli.tui.Choice(String.valueOf(m), String.valueOf(m)))
                                .toList())
                .defaultChoice(String.valueOf(LATEST_LTS_MAJOR))
                .when(a -> "java".equals(a.get("lang")) && !module && !hasDefaultJdk)
                .build();

        // Dynamic choices: the JDKs that can compile the chosen Java release
        // (major >= the target), in the full preference order (installed plus
        // auto-installable latest-LTS rows). This is the *build* JDK only
        // native projects do NOT pick a GraalVM here. The native-image GraalVM
        // is resolved automatically (latest Oracle GraalVM) into jk-lock.toml when
        // project.native is set, so the toolchain choice stays decoupled from
        // the Java language version. Rebuilt per render so changing the language
        // version refreshes the list; empty results fall back so the user can
        // still progress.
        // Only shown when there's a real choice to make: a standalone project,
        // no global default JDK, and more than one eligible installed JDK for
        // the chosen Java level. Modules inherit the parent; a default JDK is
        // adopted silently; 0/1 eligible resolves without asking (see
        // pickCandidate / NewJdkPlan).
        var jdkStep = WizardStep.RadioStep.vertical("jdk", "Select a JDK:")
                .choicesFn(answers -> {
                    int floor = jdkFloor(answers, parent);
                    var filtered = NewJdkCandidate.filter(candidates, false, LATEST_LTS_MAJOR).stream()
                            .filter(c -> c.major() >= floor)
                            .toList();
                    if (filtered.isEmpty()) {
                        filtered = candidates.stream()
                                .filter(c -> c.major() >= floor)
                                .toList();
                    }
                    if (filtered.isEmpty()) filtered = candidates;
                    return filtered.stream()
                            .map(c -> new cc.jumpkick.cli.tui.Choice(c.id(), c.label(), c.hint()))
                            .toList();
                })
                .when(a -> NewJdkPlan.shouldPrompt(module, hasDefaultJdk, candidates, jdkFloor(a, parent)))
                .defaultChoice(defaultJdkId);

        String wizardSubtitle = module
                ? "Create a new module for " + parent.displayName()
                : isInit ? "Initialize this project" : "Create a new project";
        return Wizard.builder()
                .command(module ? "New Module" : isInit ? "Init" : "New Project")
                .subtitle(wizardSubtitle)
                .step(WizardStep.InputStep.of("name", module ? "Module name:" : "Project name:")
                        .placeholder("untitled")
                        .defaultValue("untitled")
                        .build())
                .step(WizardStep.InputStep.of("group", module ? "Module group:" : "Project group:")
                        .placeholder(effectiveGroup)
                        .defaultValue(effectiveGroup)
                        .build())
                .step(WizardStep.RadioStep.horizontal("kind", "Project type:")
                        .choice("executable", "Executable")
                        .choice("library", "Library")
                        .defaultChoice("executable")
                        .build())
                .step(buildTargets)
                // Language first, then (for Java) the language version, then the
                // JDK shaped by that version.
                .step(WizardStep.RadioStep.horizontal("lang", "Project language:")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .choice("groovy", "Groovy")
                        .defaultChoice(langDefault)
                        .build())
                .step(javaVersion)
                .step(jdkStep.build())
                .step(javaLayoutStep)
                .step(kotlinLayoutStep)
                .step(librariesStep)
                .step(kotlinLibrariesStep)
                .step(kotlinOptions)
                .build();
    }

    /** Catalog short names as multi-select choices (bundled offline; type-to-filter in the wizard). */
    static List<cc.jumpkick.cli.tui.Choice> libraryPickerChoices() {
        var out = new ArrayList<cc.jumpkick.cli.tui.Choice>();
        // Prefer curated scaffold ids first (stable defaults for new projects).
        for (String id : NewCommand.CURATED_IDS) {
            if (cc.jumpkick.scaffold.NewScaffolder.CURATED_DEPS.containsKey(id)
                    || LibraryCatalog.bundled().lookup(id).isPresent()) {
                out.add(new cc.jumpkick.cli.tui.Choice(id, id, "curated"));
            }
        }
        for (String name : LibraryCatalog.bundled().names()) {
            if (NewCommand.CURATED_IDS.contains(name)) continue;
            var mod = LibraryCatalog.bundled().lookup(name).orElse(null);
            String hint = mod == null ? "" : mod.group() + ":" + mod.artifact();
            out.add(new cc.jumpkick.cli.tui.Choice(name, name, hint));
        }
        return out;
    }

    /**
     * Lowest JDK feature-release the "Select a JDK" step may offer: a JDK can't compile a release
     * newer than itself. A module inherits the parent's {@code java} target; a standalone Java
     * project uses the chosen Java Language Version; Kotlin (no Java target) imposes no floor.
     */
    /** {@code primary} unless it's empty, in which case {@code fallback} (the offline default). */
    static List<Integer> orElseList(List<Integer> primary, List<Integer> fallback) {
        return primary.isEmpty() ? fallback : primary;
    }

    /**
     * Offline language-major list for one wizard track, from the compiled-in constants — used only
     * when the catalog can't be fetched. LTS majors down to {@link cc.jumpkick.jdk.SupportedJdk#MIN_MAJOR},
     * then (standard track only) the latest non-LTS stable. The native track caps at the latest LTS,
     * since GraalVM tracks LTS releases and we can't know a newer native-capable major offline.
     */
    static List<Integer> offlineMajors(boolean nativeTrack) {
        List<Integer> out = new ArrayList<>();
        for (int v = cc.jumpkick.jdk.JdkLts.OFFLINE_LATEST_LTS; v >= cc.jumpkick.jdk.SupportedJdk.MIN_MAJOR; v--) {
            if (cc.jumpkick.jdk.JdkLts.isLtsMajor(v)) out.add(v);
        }
        int latestStable = cc.jumpkick.jdk.JdkLts.OFFLINE_LATEST_STABLE;
        if (!nativeTrack && latestStable > cc.jumpkick.jdk.JdkLts.OFFLINE_LATEST_LTS) out.add(latestStable);
        return out;
    }

    /** The newest native-image-capable (GraalVM) Java major, from the catalog or the offline cap. */
    static int maxNativeMajor(cc.jumpkick.jdk.JdkCatalog catalog) {
        List<Integer> majors = orElseList(
                cc.jumpkick.jdk.SupportedJdk.offerableMajors(
                        catalog,
                        true,
                        cc.jumpkick.jdk.HostPlatform.currentOs(),
                        cc.jumpkick.jdk.HostPlatform.currentArch()),
                offlineMajors(true));
        return majors.stream().mapToInt(Integer::intValue).max().orElse(cc.jumpkick.jdk.JdkLts.OFFLINE_LATEST_LTS);
    }

    static int jdkFloor(Answers answers, NewCommand.ParentInfo parent) {
        if (parent != null) return parent.javaRelease();
        if ("kotlin".equalsIgnoreCase(answers.get("lang"))) return 0;
        String v = answers.get("javaVersion");
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return LATEST_LTS_MAJOR;
    }
}
