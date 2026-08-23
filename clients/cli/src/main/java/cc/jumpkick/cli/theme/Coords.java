// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.terminal.Style;

/**
 * Themed rendering of Maven coordinates ({@code group:artifact:version}) and library short-names.
 */
public final class Coords {

    private Coords() {}

    /** group segment — the theme's coordinate-group color. */
    public static Style groupStyle() {
        return Theme.active().coordGroup();
    }

    /** artifact/name segment — bold bright-cyan ({@link Theme#coordName()}). */
    public static Style artifactStyle() {
        return Theme.active().coordName();
    }

    /** version segment — the theme's coordinate-version color. */
    public static Style versionStyle() {
        return Theme.active().coordVersion();
    }

    /** artifact short-name (dependency library) — bright-cyan. */
    public static Style shortNameStyle() {
        return Theme.active().brightCyan();
    }

    /** {@code [coord-group]group[/]:[coord-name]artifact[/]:[coord-version]version[/]}. */
    public static RichText richGav(String group, String artifact, String version) {
        return richGa(group, artifact).plus(RichText.plain(":")).plus(RichText.styled(version, "coord-version"));
    }

    /** Cyan group + bold bright-cyan artifact ({@code coord-group} / {@code coord-name}). */
    public static RichText richGa(String group, String artifact) {
        return RichText.styled(group, "coord-group")
                .plus(RichText.plain(":"))
                .plus(RichText.styled(artifact, "coord-name"));
    }

    public static RichText richModule(String moduleKey, String version) {
        if (moduleKey == null || moduleKey.isEmpty()) return RichText.empty();
        int colon = moduleKey.indexOf(':');
        RichText base = colon < 0
                ? RichText.styled(moduleKey, "bright-cyan")
                : richGa(moduleKey.substring(0, colon), moduleKey.substring(colon + 1));
        if (version == null || version.isEmpty()) return base;
        return base.plus(RichText.plain(":")).plus(RichText.styled(version, "coord-version"));
    }

    public static RichText richModule(String moduleKey) {
        return richModule(moduleKey, null);
    }

    /** {@code [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]}. */
    public static String gav(String group, String artifact, String version) {
        return richGav(group, artifact, version).render();
    }

    /** {@code [blue]group[/]:[cyan]artifact[/]} (no version). */
    public static String ga(String group, String artifact) {
        return richGa(group, artifact).render();
    }

    /** Color a {@link Coordinate} as {@code group:artifact:version}. */
    public static String gav(Coordinate coord) {
        return gav(coord.group(), coord.artifact(), coord.version());
    }

    /** Color the version on its own — cyan. */
    public static String version(String version) {
        return RichText.styled(version, "coord-version").render();
    }

    /** An artifact short-name / library on its own — bright-cyan. */
    public static String shortName(String name) {
        return RichText.styled(name, "bright-cyan").render();
    }

    /**
     * Color a module key with an optional version. The key is the usual {@code group:artifact} form;
     * a key with no {@code ':'} is treated as an artifact short-name and rendered in bright-cyan. A
     * {@code null}/blank version omits the version segment.
     */
    public static String module(String moduleKey, String version) {
        return richModule(moduleKey, version).render();
    }

    /** Color a {@code group:artifact} module key with no version. */
    public static String module(String moduleKey) {
        return module(moduleKey, null);
    }
}
