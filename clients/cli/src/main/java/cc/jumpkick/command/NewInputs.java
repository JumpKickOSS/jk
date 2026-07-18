// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

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
        boolean shadow,
        boolean nativeImage,
        boolean spring,
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

    /** Back-compat constructor: no Spring Boot. */
    public NewInputs(
            String group,
            String name,
            String jdk,
            int jdkMajor,
            int javaRelease,
            Optional<String> jdkIdentifier,
            Optional<String> main,
            boolean shadow,
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
                shadow,
                nativeImage,
                false,
                false,
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
            boolean shadow,
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
                shadow,
                nativeImage,
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
        KOTLIN;

        public String hoconValue() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
            };
        }

        public String sourceDir() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
            };
        }
    }

    /** True when the chosen layout is "simple" (flat ./src + ./test). */
    public boolean isSimpleLayout() {
        return "simple".equalsIgnoreCase(layout);
    }

    public boolean isRunnable() {
        return main.isPresent();
    }
}
