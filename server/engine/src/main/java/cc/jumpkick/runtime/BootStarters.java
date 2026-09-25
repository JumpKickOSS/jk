// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Spring Boot starter a package belongs to when the project already uses Boot. Boot 4 renamed
 * several starters; {@code bootMajor} selects that name. The longest matching prefix wins.
 */
final class BootStarters {

    private record Row(String prefix, String boot4, String earlier) {}

    private static final List<Row> ROWS = List.of(
            new Row(
                    "org.springframework.data.r2dbc",
                    "spring-boot-starter-data-r2dbc",
                    "spring-boot-starter-data-r2dbc"),
            new Row("io.r2dbc", "spring-boot-starter-data-r2dbc", "spring-boot-starter-data-r2dbc"),
            new Row("org.springframework.data.jpa", "spring-boot-starter-data-jpa", "spring-boot-starter-data-jpa"),
            new Row("jakarta.persistence", "spring-boot-starter-data-jpa", "spring-boot-starter-data-jpa"),
            new Row("org.springframework.batch", "spring-boot-starter-batch-jdbc", "spring-boot-starter-batch"),
            new Row("org.springframework.graphql", "spring-boot-starter-graphql", "spring-boot-starter-graphql"),
            new Row("jakarta.validation", "spring-boot-starter-validation", "spring-boot-starter-validation"),
            new Row("org.springframework.hateoas", "spring-boot-starter-hateoas", "spring-boot-starter-hateoas"),
            new Row("org.springframework.web.client", "spring-boot-starter-restclient", "spring-boot-starter-web"),
            new Row("org.springframework.http", "spring-boot-starter-webmvc", "spring-boot-starter-web"),
            new Row("org.springframework.web", "spring-boot-starter-webmvc", "spring-boot-starter-web"),
            new Row("org.springframework.security", "spring-boot-starter-security", "spring-boot-starter-security"),
            new Row("org.springframework.boot", "spring-boot-starter", "spring-boot-starter"),
            new Row("org.springframework.context", "spring-boot-starter", "spring-boot-starter"),
            new Row("org.springframework.stereotype", "spring-boot-starter", "spring-boot-starter"),
            new Row("org.springframework.scheduling", "spring-boot-starter", "spring-boot-starter"));

    private static final String GROUP = "org.springframework.boot";

    private BootStarters() {}

    /** {@code group:artifact} of the starter, or null when no row covers {@code pkg}. */
    static @Nullable String coordinate(String pkg, int bootMajor) {
        Row best = null;
        for (Row row : ROWS) {
            if (!(pkg.equals(row.prefix) || pkg.startsWith(row.prefix + "."))) continue;
            if (best == null || row.prefix.length() > best.prefix.length()) best = row;
        }
        if (best == null) return null;
        String artifact = bootMajor >= 4 ? best.boot4 : best.earlier;
        return GROUP + ":" + artifact;
    }

    /** The leading number of a Boot version ({@code 4.0.8} → 4), or 0 when it is not numeric. */
    static int major(String version) {
        int dot = version.indexOf('.');
        String head = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
