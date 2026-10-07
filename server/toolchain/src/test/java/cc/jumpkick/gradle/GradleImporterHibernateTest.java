// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code org.hibernate.orm} plugin's enhancement block imports as {@code [hibernate] enhance =
 * true} with the switches it changes from the plugin's defaults, in either script dialect.
 */
class GradleImporterHibernateTest {

    @Test
    void a_kotlin_enhancement_block_becomes_the_table() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    java
                    id("org.hibernate.orm") version "7.4.12.Final"
                }
                hibernate {
                    enhancement {
                        enableLazyInitialization = true
                        enableDirtyTracking.set(false)
                        enableAssociationManagement = true
                    }
                }
                """, "app");

        assertThat(result.jkBuild().pluginConfig("hibernate").orElseThrow().values())
                .isEqualTo(Map.of("enhance", true, "dirty-tracking", false, "association-management", true));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("org.hibernate.orm") && m.contains("not yet mapped"));
    }

    @Test
    void a_groovy_enhancement_block_with_defaults_is_enhance_alone() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    id 'java'
                    id 'org.hibernate.orm' version '6.6.13.Final'
                }
                hibernate {
                    enhancement {
                        enableLazyInitialization true
                    }
                }
                """, "app");

        assertThat(result.jkBuild().pluginConfig("hibernate").orElseThrow().values())
                .isEqualTo(Map.of("enhance", true));
    }

    @Test
    void the_plugin_without_an_enhancement_block_writes_no_table() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    id("org.hibernate.orm") version "7.4.12.Final"
                }
                """, "app");

        assertThat(result.jkBuild().pluginConfig("hibernate")).isEmpty();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.contains("without an `enhancement` block"));
    }
}
