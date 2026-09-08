// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Server → client lines of the sync reads: the explain burst ({@code explain-module} / {@code
 * explain-task} / {@code explain-edge} / {@code explain-done}) and the acks whose payload is a few
 * scalars. The requests these answer are records ({@link ExplainRequest}, {@link TreeRequest}, …)
 * with their own decoders.
 */
public final class ProtoReads {

    private ProtoReads() {}

    public static String explainModule(
            String dir, String coord, int sourceCount, int testCount, boolean producesJar, boolean producesImage) {
        return new ExplainModuleEvent(dir, coord, sourceCount, testCount, producesJar, producesImage).encode();
    }

    public static String explainStep(String dir, String name, String status, String text, @Nullable String key) {
        return new ExplainTaskEvent(dir, name, status, text, key).encode();
    }

    public static String explainEdge(String dir, String dependsOnDir) {
        return new ExplainEdgeEvent(dir, dependsOnDir).encode();
    }

    public static String treeAck(@Nullable String error, @Nullable String rendered) {
        return new TreeAck(error, rendered == null ? "" : rendered).encode();
    }

    public static String editAck(boolean changed, @Nullable String error) {
        return editAck(changed, error, "");
    }

    public static String editAck(boolean changed, @Nullable String error, @Nullable String detail) {
        return new EditAck(changed, error, detail).encode();
    }

    public static String freshenCatalogAck(boolean ok, @Nullable String error) {
        return new FreshenCatalogAck(ok, error).encode();
    }

    public static String forecastAck(List<String> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
        return new ForecastAck(dirtyDirs, lockStale, empty, errors).encode();
    }

    public static String explainDone(int maxReadyWidth, int moduleCount) {
        return new ExplainDoneEvent(maxReadyWidth, moduleCount).encode();
    }
}
