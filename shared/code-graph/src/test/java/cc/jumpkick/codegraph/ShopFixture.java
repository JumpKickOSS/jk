// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The small shop workspace under {@code shop/}, extracted and linked; {@code .txt} keeps the formatter off the sources. */
public final class ShopFixture {

    private ShopFixture() {}

    static final String AUDIT = "src_main_java_com_acme_shop_orderservice_orderservice_audit";
    static final String LOG = "src_main_java_com_acme_shop_baseservice_baseservice_log";

    public static CodeGraph graph() throws IOException {
        return graph(List.of());
    }

    /** The shop with the call edges its compiled classes add: {@code audit} calls {@code log} at line 21. */
    static CodeGraph withCalls() throws IOException {
        String file = "src/main/java/com/acme/shop/OrderService.java";
        GraphEdge call = GraphEdge.of(AUDIT, LOG, Relations.CALLS, Confidence.EXTRACTED, file, 21)
                .withContext("call");
        return graph(List.of(new Fragment(List.of(), List.of(call))));
    }

    private static CodeGraph graph(List<Fragment> more) throws IOException {
        List<Fragment> fragments = new ArrayList<>(more);
        for (String file : resource("shop-files.txt").lines().toList()) {
            String rel = file.substring(2);
            String path = rel.endsWith(".txt") ? rel.substring(0, rel.length() - 4) : rel;
            Extractor e = Objects.requireNonNull(Extractors.forPath(path), path);
            fragments.add(e.extract(path, resource("shop/" + rel)));
        }
        return Linker.link(fragments);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ShopFixture.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
