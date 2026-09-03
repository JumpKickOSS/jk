// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

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
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_MODULE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"sourceCount\":"
                + sourceCount
                + ",\"testCount\":"
                + testCount
                + ",\"producesJar\":"
                + producesJar
                + ",\"producesImage\":"
                + producesImage
                + "}";
    }

    public static String explainStep(String dir, String name, String status, String text, String key) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_TASK
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"status\":"
                + Jsonl.quote(status)
                + ",\"text\":"
                + Jsonl.quote(text)
                + ",\"key\":"
                + Jsonl.quote(key)
                + "}";
    }

    public static String explainEdge(String dir, String dependsOnDir) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_EDGE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"dependsOnDir\":"
                + Jsonl.quote(dependsOnDir)
                + "}";
    }

    public static String treeAck(String error, String rendered) {
        return "{\"type\":\"" + EngineProtocol.TREE_ACK + "\",\"error\":" + Jsonl.quote(error)
                + ",\"rendered\":" + Jsonl.quote(rendered == null ? "" : rendered)
                + "}";
    }

    public static String editAck(boolean changed, String error) {
        return editAck(changed, error, "");
    }

    public static String editAck(boolean changed, String error, String detail) {
        return "{\"type\":\"" + EngineProtocol.EDIT_ACK + "\",\"changed\":" + changed + ",\"error\":"
                + Jsonl.quote(error)
                + ((detail == null || detail.isBlank()) ? "" : ",\"detail\":" + Jsonl.quote(detail))
                + "}";
    }

    public static String freshenCatalogAck(boolean ok, String error) {
        return "{\"type\":\"" + EngineProtocol.FRESHEN_CATALOG_ACK + "\",\"ok\":" + ok + ",\"error\":"
                + Jsonl.quote(error) + "}";
    }

    public static String forecastAck(List<String> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
        return "{\"type\":\""
                + EngineProtocol.FORECAST_ACK
                + "\",\"dirtyDirs\":"
                + EngineProtocol.quoteArray(dirtyDirs)
                + ",\"lockStale\":"
                + lockStale
                + ",\"empty\":"
                + empty
                + ",\"errors\":"
                + EngineProtocol.quoteArray(errors)
                + "}";
    }

    public static String explainDone(int maxReadyWidth, int moduleCount) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_DONE
                + "\",\"maxReadyWidth\":"
                + maxReadyWidth
                + ",\"moduleCount\":"
                + moduleCount
                + "}";
    }
}
