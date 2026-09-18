// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import cc.jumpkick.model.Layout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Answers for {@link NewScaffolder} from flags or the interactive wizard. {@code javaRelease} is the
 * language level the manifest writes as {@code java = N}; {@code jdk} is a toolchain pin ({@code jdk =
 * "corretto-25"}, {@code "21"}) written only when the caller asked for one, and null when the level
 * alone says what to build with.
 */
public record NewInputs(
        String group,
        String name,
        @Nullable String jdk,
        int jdkMajor,
        int javaRelease,
        @Nullable String jdkIdentifier,
        @Nullable String main,
        boolean assembly,
        boolean nativeImage,
        boolean plugin,
        Language lang,
        Layout layout,
        @Nullable String kotlinModuleName,
        List<String> deps,
        boolean sample,
        Path directory) {

    public Optional<String> jdkIdentifierOpt() {
        return Optional.ofNullable(jdkIdentifier);
    }

    public Optional<String> mainOpt() {
        return Optional.ofNullable(main);
    }

    public Optional<String> kotlinModuleNameOpt() {
        return Optional.ofNullable(kotlinModuleName);
    }

    public NewInputs {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        if (jdk != null && jdk.isBlank()) jdk = null;
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(directory, "directory");
        deps = List.copyOf(deps);
    }

    /** Blank project (not a plugin-authoring tree). */
    public NewInputs(
            String group,
            String name,
            @Nullable String jdk,
            int jdkMajor,
            int javaRelease,
            @Nullable String jdkIdentifier,
            @Nullable String main,
            boolean assembly,
            boolean nativeImage,
            Language lang,
            Layout layout,
            @Nullable String kotlinModuleName,
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
            @Nullable String jdk,
            int jdkMajor,
            @Nullable String jdkIdentifier,
            @Nullable String main,
            boolean assembly,
            boolean nativeImage,
            Language lang,
            Layout layout,
            @Nullable String kotlinModuleName,
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
        return main != null;
    }
}
