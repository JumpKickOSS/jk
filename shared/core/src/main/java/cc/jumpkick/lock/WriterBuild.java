// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.BuildIdentity;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The build of the jk that wrote a lock, beside the version {@code generated-by} names: the code
 * archive's content identity ({@link BuildIdentity#buildId}) and its timestamp. Two builds of one
 * version have no order in their identities, so the timestamp is what says which is newer.
 */
public record WriterBuild(String id, @Nullable Instant time) {
    public WriterBuild {
        Objects.requireNonNull(id, "id");
    }
}
