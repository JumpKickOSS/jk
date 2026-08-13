// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.model.Coordinate;
import java.util.List;

/**
 * A Maven/Gradle coordinate as a widget. Models the three segments (group, artifact, version) and
 * paints them with the theme's coord colors.
 */
public final class Coord implements Widget {

    private final RichText text;

    private Coord(RichText text) {
        this.text = text == null ? RichText.empty() : text;
    }

    public static Coord gav(String group, String artifact, String version) {
        return new Coord(Coords.richGav(group, artifact, version));
    }

    public static Coord gav(Coordinate coordinate) {
        return gav(coordinate.group(), coordinate.artifact(), coordinate.version());
    }

    public static Coord ga(String group, String artifact) {
        return new Coord(Coords.richGa(group, artifact));
    }

    /** {@code group:artifact} or a short-name with no colon. */
    public static Coord module(String moduleKey) {
        return new Coord(Coords.richModule(moduleKey));
    }

    public static Coord module(String moduleKey, String version) {
        return new Coord(Coords.richModule(moduleKey, version));
    }

    public RichText text() {
        return text;
    }

    public String renderLine(RenderContext ctx) {
        return text.render(ctx);
    }

    public String renderLine() {
        return text.render();
    }

    @Override
    public String toString() {
        return renderLine();
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(renderLine(ctx));
    }
}
