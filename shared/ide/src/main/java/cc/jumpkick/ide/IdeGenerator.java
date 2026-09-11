// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import java.io.IOException;

/**
 * A strategy that emits project configuration for one IDE from the shared {@link IdeModel}. The
 * model is computed once (engine-side) and handed to every selected generator, so a generator only
 * turns the resolved workspace/deps/JDK model into that IDE's files, through {@link IdeOutput}.
 * Generators never print; run one via {@link IdeGenerators#run}.
 */
public interface IdeGenerator {

    /** The IDE this generator targets. */
    IdeTarget target();

    /** Emit the IDE's project files for the workspace described by {@code model} through {@code out}. */
    void generate(IdeModel model, IdeOutput out) throws IOException;
}
