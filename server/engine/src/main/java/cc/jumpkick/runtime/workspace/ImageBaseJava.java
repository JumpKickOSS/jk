// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one place that maps a module's Java level to an image base, and the reading back of the
 * Java major a configured base names, where its reference makes that knowable.
 *
 * <p>The default base is a JRE of the module's own level, so a project at {@code java = 27} gets a
 * JRE 27 and one at 21 a JRE 21; templates write no base and inherit it. A base written by hand
 * is read for its Java major only when the image is a known JDK distribution
 * ({@code eclipse-temurin:21-jre}, {@code bellsoft/liberica-runtime-container:jre-25-slim-glibc},
 * {@code gcr.io/distroless/java21-debian12}, …) or its tag says {@code jre}, {@code jdk} or
 * {@code java} next to the number; a company base tagged {@code :10} names its own version, not a
 * Java, and is not read.
 */
public final class ImageBaseJava {

    private ImageBaseJava() {}

    /**
     * The base used when no {@code image.base} is set in the project or global config.
     * {@code {java-major-version}} is replaced with the module's Java level.
     */
    public static final String DEFAULT_BASE_TEMPLATE =
            "bellsoft/liberica-runtime-container:jre-{java-major-version}-slim-glibc";

    /** Repository names (last path segment) whose tags name a Java major. */
    private static final Set<String> JDK_REPOSITORIES = Set.of(
            "eclipse-temurin",
            "temurin",
            "openjdk",
            "jdk",
            "jre",
            "amazoncorretto",
            "corretto",
            "sapmachine",
            "ibm-semeru-runtimes",
            "semeru",
            "zulu-openjdk",
            "zulu-openjdk-alpine",
            "zulu-openjdk-debian",
            "liberica-runtime-container",
            "liberica-openjdk-debian",
            "liberica-openjdk-alpine",
            "liberica-openjre-debian",
            "liberica-openjre-alpine",
            "liberica-openjdk-alpine-musl",
            "liberica-openjre-alpine-musl",
            "graalvm-community",
            "graalvm-ce",
            "jdk-community",
            "java");

    /** A repository named for its Java major: {@code distroless/java21-debian12}, {@code ubi9/openjdk-21-runtime}. */
    private static final Pattern REPOSITORY_MAJOR =
            Pattern.compile("^(?:java|openjdk|jdk|jre)-?(\\d{1,2})(?:[-_.].*)?$");

    private static final Pattern TAG_TOKEN = Pattern.compile("^(\\d{1,2})(?:u\\d+)?$");

    /** {@code configured} with {@code {java-major-version}} filled, or the default base for {@code level}. */
    public static String resolve(@Nullable String configured, int level) {
        String base = configured == null || configured.isBlank() ? DEFAULT_BASE_TEMPLATE : configured;
        return base.replace("{java-major-version}", String.valueOf(level));
    }

    /**
     * The Java major {@code base} names, when its reference makes that knowable; empty for a
     * base whose tag is {@code latest}, a digest alone, or a version of something else.
     */
    public static OptionalInt javaMajor(String base) {
        String ref = base.strip();
        int at = ref.indexOf('@');
        if (at >= 0) ref = ref.substring(0, at);
        int slash = ref.lastIndexOf('/');
        int colon = ref.lastIndexOf(':');
        String repository = colon > slash ? ref.substring(0, colon) : ref;
        String tag = colon > slash ? ref.substring(colon + 1) : "";
        String name = repository.substring(repository.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);

        Matcher byName = REPOSITORY_MAJOR.matcher(name);
        if (byName.matches()) return plausible(Integer.parseInt(byName.group(1)));

        String lowerTag = tag.toLowerCase(Locale.ROOT);
        boolean jdkImage = JDK_REPOSITORIES.contains(name) || name.startsWith("liberica-");
        boolean javaWordInTag = lowerTag.matches(".*(?:^|[-_.])(?:jre|jdk|java|openjdk)(?:[-_.]|$).*");
        if (!jdkImage && !javaWordInTag) return OptionalInt.empty();
        // The first token of the tag that is a Java major: 25-jre, jre-25-slim, 21.0.4_7-jre, open-21-jre, 8u412-b08.
        for (String token : lowerTag.split("[-_.]")) {
            Matcher m = TAG_TOKEN.matcher(token);
            if (m.matches()) return plausible(Integer.parseInt(m.group(1)));
        }
        return OptionalInt.empty();
    }

    /**
     * Why {@code base} cannot run classes of {@code level}, or {@code null} when it can or its
     * Java is not knowable from the reference.
     */
    public static @Nullable String tooOld(String base, int level) {
        OptionalInt major = javaMajor(base);
        if (major.isEmpty() || major.getAsInt() >= level) return null;
        return "image base `" + base + "` is a Java " + major.getAsInt() + " image and this module is built for Java "
                + level + ": its classes would not start there. Remove [image] base to take the default ("
                + resolve(null, level) + "), or name a base of Java " + level + " or later";
    }

    private static OptionalInt plausible(int major) {
        return major >= 8 && major <= 99 ? OptionalInt.of(major) : OptionalInt.empty();
    }
}
