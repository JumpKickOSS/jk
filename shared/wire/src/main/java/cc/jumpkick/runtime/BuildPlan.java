// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.List;

/**
 * Front-end-safe build forecast data for {@code jk explain} and dirty-set reporting — pure data, no
 * engine internals.
 */
public final class BuildPlan {

    private BuildPlan() {}

    /** Per-step verdict. CACHED = restored from cache; the rest do real work. */
    public enum Status {
        CACHED,
        FULL,
        PARTIAL,
        RUN
    }

    /**
     * One step of a module's build. {@code text} is the right-hand detail after the status glyph;
     * {@code key} is the 8-char action key when known (cached).
     */
    public record Step(String name, Status status, String text, String key) {
        public boolean cached() {
            return status == Status.CACHED;
        }
    }

    /** One module's forecast: identity plus ordered steps. */
    public static final class Module {
        private final Path dir;
        private final String coord;
        private final List<Step> steps;
        private final int sourceCount;
        private final int testCount;
        private final boolean producesJar;
        private final boolean producesImage;

        public Module(
                Path dir,
                String coord,
                List<Step> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage) {
            this.dir = dir;
            this.coord = coord;
            this.steps = steps;
            this.sourceCount = sourceCount;
            this.testCount = testCount;
            this.producesJar = producesJar;
            this.producesImage = producesImage;
        }

        /** Reconstruct a forecast module client-side from wire-level data (engine front-ends). */
        public static Module fromWire(
                Path dir,
                String coord,
                List<Step> steps,
                int sourceCount,
                int testCount,
                boolean producesJar,
                boolean producesImage) {
            return new Module(dir, coord, steps, sourceCount, testCount, producesJar, producesImage);
        }

        /** The module's {@code group:artifact} coordinate. */
        public String coord() {
            return coord;
        }

        /** The module's directory. */
        public Path dir() {
            return dir;
        }

        public List<Step> steps() {
            return steps;
        }

        public int sourceCount() {
            return sourceCount;
        }

        public int testCount() {
            return testCount;
        }

        public boolean producesJar() {
            return producesJar;
        }

        public boolean producesImage() {
            return producesImage;
        }

        public boolean dirty() {
            return steps.stream().anyMatch(p -> !p.cached());
        }
    }
}
