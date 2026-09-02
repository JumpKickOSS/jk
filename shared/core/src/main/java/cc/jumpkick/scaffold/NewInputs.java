// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.model.Layout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Answers for {@link NewScaffolder} from flags or the interactive wizard. */
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
        boolean plugin,
        Language lang,
        Layout layout,
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
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(kotlinModuleName, "kotlinModuleName");
        Objects.requireNonNull(directory, "directory");
        deps = List.copyOf(deps);
    }

    /** Blank project (not a plugin-authoring tree). */
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
            Layout layout,
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
                lang,
                layout,
                kotlinModuleName,
                deps,
                sample,
                directory);
    }

    /** Blank project (not a plugin-authoring tree). {@code javaRelease} is {@code jdkMajor}. */
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
            Layout layout,
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
        GROOVY,
        SCALA;

        public String hoconValue() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
                case GROOVY -> "groovy";
                case SCALA -> "scala";
            };
        }

        public String sourceDir() {
            return switch (this) {
                case JAVA -> "java";
                case KOTLIN -> "kotlin";
                case GROOVY -> "groovy";
                case SCALA -> "scala";
            };
        }
    }

    /** True when the chosen layout is simple (Mill-like {@code ./src} + {@code ./test/src}). */
    public boolean isSimpleLayout() {
        return layout == Layout.SIMPLE;
    }

    public boolean isRunnable() {
        return main.isPresent();
    }
}
