// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The tokens a record's consumers key on, by role ({@link ActionRecordText.Meta#derived}), worked
 * out while its blobs are on disk so a machine without them can still compute the keys downstream.
 */
final class DerivedTokens {

    private DerivedTokens() {}

    /**
     * For {@code outputs} of a step of {@code kind}: a classes tree's interface (the ABI token of
     * its {@code .class} outputs, as {@link ClasspathAbi#tokenFromOutputs} reads them), and for a
     * packaging step's one artifact its payload digest and, for a jar, its interface. Empty when
     * there is nothing to derive or a blob cannot be read.
     */
    static Map<String, String> of(
            @Nullable String kind, Map<String, String> outputs, Cas cas, Function<String, Path> blobPath) {
        Map<String, String> derived = new TreeMap<>();
        try {
            if (outputs.keySet().stream().anyMatch(path -> path.endsWith(".class"))) {
                derived.put(ActionRecordText.Meta.INTERFACE, ClasspathAbi.tokenFromOutputs(outputs, List.of(), cas));
            } else if (kind != null && kind.startsWith("package:") && outputs.size() == 1) {
                Map.Entry<String, String> artifact =
                        outputs.entrySet().iterator().next();
                if (!ActionRecordText.isSha256(artifact.getValue())) return derived;
                derived.put(ActionRecordText.Meta.PAYLOAD, artifact.getValue());
                if (artifact.getKey().endsWith(".jar"))
                    derived.put(
                            ActionRecordText.Meta.INTERFACE, ClasspathAbi.token(blobPath.apply(artifact.getValue())));
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("derived tokens: the record carries none", e);
            return Map.of();
        }
        return derived;
    }
}
