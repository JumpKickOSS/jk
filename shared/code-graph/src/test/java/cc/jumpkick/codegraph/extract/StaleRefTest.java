// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.GraphReport;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.StaleRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class StaleRefTest {

    private static CodeGraph graph(Map<String, String> files) {
        List<Fragment> fragments = new ArrayList<>();
        files.forEach((rel, text) ->
                fragments.add(Objects.requireNonNull(Extractors.forPath(rel)).extract(rel, text)));
        return Linker.link(fragments);
    }

    @Test
    void brokenLinksAndUndeclaredWorkspaceClassesAreReportedAndNothingElse() {
        CodeGraph g = graph(Map.of(
                "src/main/java/com/acme/Shop.java", "package com.acme;\npublic class Shop {}\n",
                "docs/guide.md", """
                        # Guide

                        See [the shop](../src/main/java/com/acme/Shop.java) and [old notes](old.md).
                        It replaced `com.acme.Basket` and works with `com.fasterxml.jackson.databind.ObjectMapper`.
                        Read [the site](https://example.com) and `com.acme.Shop`.
                        """));
        assertThat(g.stale())
                .containsExactlyInAnyOrder(
                        new StaleRef("docs/guide.md", 3, StaleRef.FILE, "docs/old.md"),
                        new StaleRef("docs/guide.md", 4, StaleRef.CLASS, "com.acme.Basket"));
        String report = GraphReport.write(g, null);
        assertThat(report)
                .contains("## Stale doc references")
                .contains("- docs/guide.md:3 links to missing `docs/old.md`")
                .contains("- docs/guide.md:4 names undeclared class `com.acme.Basket`");
        assertThat(GraphJson.read(GraphJson.write(g, "test", null)).stale()).isEqualTo(g.stale());
    }
}
