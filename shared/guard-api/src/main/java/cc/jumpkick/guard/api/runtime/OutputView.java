// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Output;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@link Output} from the paths the engine listed. */
public final class OutputView implements Output {

    private final List<Path> poms;
    private final List<Path> jars;
    private final @Nullable Path coverage;

    public OutputView(List<Path> poms, List<Path> jars, @Nullable Path coverage) {
        this.poms = List.copyOf(poms);
        this.jars = List.copyOf(jars);
        this.coverage = coverage;
    }

    @Override
    public List<Path> poms() {
        return poms;
    }

    @Override
    public List<Path> jars() {
        return jars;
    }

    @Override
    public Optional<Path> coverage() {
        return Optional.ofNullable(coverage);
    }
}
