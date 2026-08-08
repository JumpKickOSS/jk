// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Scaffold answers for {@link NewScaffolder} from flags or the interactive wizard. */
public record NewInputs(
        String group,
        String name,
        String jdk,
        int jdkMajor,
        int javaRelease,
        Optional<String> jdkIdentifier,
        Optional<String> main,
        boolean assembly,
        boolean nativeImage,
        boolean spring,
        boolean grails,
        boolean quarkus,
        boolean micronaut,
        boolean plugin,
        Language lang,
        String layout,
        Optional<String> kotlinModuleName,
        List<String> deps,
        boolean sample,
        Path directory) {

    public NewInputs {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jdk, "jdk");
        Objects.requireNonNull(jdkIdentifier, "jdkIdentifier");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(kotlinModuleName, "kotlinModuleName");
        Objects.requireNonNull(directory, "directory");
        deps = List.copyOf(deps);
    }

    /** Back-compat constructor: no framework scaffold or plugin project. */
    public NewInputs(
            String group,
            String name,
            String jdk,
            int jdkMajor,
            int javaRelease,
            Optional<String> jdkIdentifier,
            Optional<String> main,
            boolean assembly,
            boolean nativeImage,
            Language lang,
            String layout,
            Optional<String> kotlinModuleName,
            List<String> deps,
            boolean sample,
            Path directory) {
        this(
                group,
                name,
                jdk,
                jdkMajor,
                javaRelease,
                jdkIdentifier,
                main,
                assembly,
                nativeImage,
                false,
                false,
                false,
                false,
                false,
                lang,
                layout,
                kotlinModuleName,
                deps,
                sample,
                directory);
    }

    /** Back-compat constructor: spring/plugin flags, no Grails/Quarkus. */
    public NewInputs(
            String group,
            String name,
            String jdk,
            int jdkMajor,
            int javaRelease,
            Optional<String> jdkIdentifier,
            Optional<String> main,
            boolean assembly,
            boolean nativeImage,
            boolean spring,
            boolean plugin,
            Language lang,
            String layout,
            Optional<String> kotlinModuleName,
            List<String> deps,
            boolean sample,
            Path directory) {
        this(
                group,
                name,
                jdk,
                jdkMajor,
                javaRelease,
                jdkIdentifier,
                main,
                assembly,
                nativeImage,
                spring,
                false,
                false,
                false,
                plugin,
                lang,
                layout,
                kotlinModuleName,
                deps,
                sample,
                directory);
    }

    /** Back-compat constructor: spring/grails/plugin, no Quarkus. */
    public NewInputs(
            String group,
            String name,
            String jdk,
            int jdkMajor,
            int javaRelease,
            Optional<String> jdkIdentifier,
            Optional<String> main,
            boolean assembly,
            boolean nativeImage,
            boolean spring,
            boolean grails,
            boolean plugin,
            Language lang,
            String layout,
            Optional<String> kotlinModuleName,
            List<String> deps,
            boolean sample,
            Path directory) {
        this(
                group,
                name,
                jdk,
                jdkMajor,
                javaRelease,
                jdkIdentifier,
                main,
                assembly,
                nativeImage,
                spring,
                grails,
                false,
                false,
                plugin,
                lang,
                layout,
                kotlinModuleName,
                deps,
                sample,
                directory);
    }

    /** Back-compat constructor: {@code javaRelease} defaults to {@code jdkMajor}. */
    public NewInputs(
            String group,
            String name,
            String jdk,
            int jdkMajor,
            Optional<String> jdkIdentifier,
            Optional<String> main,
            boolean assembly,
            boolean nativeImage,
            Language lang,
            String layout,
            Optional<String> kotlinModuleName,
            List<String> deps,
            boolean sample,
            Path directory) {
        this(
                group,
                name,
                jdk,
                jdkMajor,
                jdkMajor,
                jdkIdentifier,
                main,
                assembly,
                nativeImage,
                false,
                false,
                false,
                false,
                false,
                lang,
                layout,
                kotlinModuleName,
                deps,
                sample,
                directory);
    }

    public enum Language {
        JAVA,
        KOTLIN,
        GROOVY;

        public String hoconValue() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
                case GROOVY -> "groovy";
            };
        }

        public String sourceDir() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
                case GROOVY -> "groovy";
            };
        }
    }

    /** True when the chosen layout is "simple" (Mill-like {@code ./src} + {@code ./test/src}). */
    public boolean isSimpleLayout() {
        return "simple".equalsIgnoreCase(layout);
    }

    public boolean isRunnable() {
        return main.isPresent();
    }

    /** True when any framework plugin scaffold flag is set. */
    public boolean frameworkScaffold() {
        return spring || grails || quarkus || micronaut;
    }

    /** Scaffold flag name for the engine ({@code spring} / {@code grails} / {@code quarkus}). */
    public String frameworkPluginFlag() {
        if (grails) return "grails";
        if (quarkus) return "quarkus";
        if (micronaut) return "micronaut";
        if (spring) return "spring";
        throw new IllegalStateException("no framework scaffold flag set");
    }
}
