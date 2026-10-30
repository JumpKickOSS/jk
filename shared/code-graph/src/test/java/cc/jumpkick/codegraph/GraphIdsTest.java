// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphIdsTest {

    @Test
    void fileAndMemberIdsFollowThePath() {
        String file = "src/main/java/com/acme/shop/OrderService.java";
        assertThat(GraphIds.file(file)).isEqualTo("src_main_java_com_acme_shop_orderservice");
        String type = GraphIds.declared(file, "OrderService");
        assertThat(type).isEqualTo("src_main_java_com_acme_shop_orderservice_orderservice");
        assertThat(GraphIds.member(type, "total"))
                .isEqualTo("src_main_java_com_acme_shop_orderservice_orderservice_total");
        assertThat(GraphIds.file("README.md")).isEqualTo("readme");
        assertThat(GraphIds.file("docs/notes.md")).isEqualTo("docs_notes");
    }

    @Test
    void filesOtherThanSourceAndMarkdownKeepTheirExtension() {
        assertThat(GraphIds.file("src/app/app.ts")).isEqualTo("src_app_app");
        assertThat(GraphIds.file("src/app/app.css")).isEqualTo("src_app_app_css");
        assertThat(GraphIds.file("src/app/app.html")).isEqualTo("src_app_app_html");
        assertThat(GraphIds.declared("src/main/resources/application.yml", "server.port"))
                .isEqualTo("src_main_resources_application_yml_server_port");
    }

    @Test
    void normalizeCollapsesFoldsAndIsIdempotent() {
        assertThat(GraphIds.normalize("__Foo--Bar..baz__")).isEqualTo("foo_bar_baz");
        assertThat(GraphIds.normalize("Straße")).isEqualTo("strasse");
        assertThat(GraphIds.normalize("Ｆｕｌｌwidth")).isEqualTo("fullwidth");
        assertThat(GraphIds.normalize("Größe_Ünit")).isEqualTo(GraphIds.normalize(GraphIds.normalize("Größe_Ünit")));
        assertThat(GraphIds.make("_.a.", "", "B")).isEqualTo("a_b");
    }

    @Test
    void stemDropsOnlyTheLastExtension() {
        assertThat(GraphIds.stem("a/b.tar.gz")).isEqualTo("a/b.tar");
        assertThat(GraphIds.stem("a/.gitignore")).isEqualTo("a/.gitignore");
        assertThat(GraphIds.stem("Makefile")).isEqualTo("Makefile");
    }
}
