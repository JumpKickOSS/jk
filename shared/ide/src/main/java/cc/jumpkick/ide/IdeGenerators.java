// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The generators {@code jk ide} and {@code ide} share, in their one stable emit order. */
public final class IdeGenerators {

    private static final List<IdeGenerator> ALL = List.of(new IntellijIdeGenerator(), new VscodeIdeGenerator());

    private IdeGenerators() {}

    /** Every generator, IntelliJ first. */
    public static List<IdeGenerator> all() {
        return ALL;
    }

    /** Run one generator; {@code preview} lists the files without writing them. */
    public static IdeGeneration run(IdeGenerator generator, IdeModel model, boolean preview) throws IOException {
        IdeOutput out = preview ? IdeOutput.preview() : IdeOutput.writing();
        generator.generate(model, out);
        return new IdeGeneration(generator.target(), out.files(), out.sdkTables());
    }

    /** Run every generator whose target is in {@code targets}, in emit order. */
    public static List<IdeGeneration> run(IdeModel model, Set<IdeTarget> targets, boolean preview) throws IOException {
        List<IdeGeneration> out = new ArrayList<>();
        for (IdeGenerator generator : ALL) {
            if (targets.contains(generator.target())) out.add(run(generator, model, preview));
        }
        return List.copyOf(out);
    }
}
