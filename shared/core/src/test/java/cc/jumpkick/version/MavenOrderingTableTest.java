// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Versions#compare} against the orderings Maven's own {@code ComparableVersionTest} pins
 * (maven-artifact 3.9.x), which is the reference for every version comparison jk makes: each row
 * is strictly ascending, each group is equal. A mismatch is collected rather than thrown, so one
 * run names every pair that disagrees.
 */
class MavenOrderingTableTest {

    /** Maven's {@code VERSIONS_QUALIFIER}: qualifier order after a release. */
    private static final List<String> QUALIFIERS = List.of(
            "1-alpha2snapshot",
            "1-alpha2",
            "1-alpha-123",
            "1-beta-2",
            "1-beta123",
            "1-m2",
            "1-m11",
            "1-rc",
            "1-cr2",
            "1-rc123",
            "1-SNAPSHOT",
            "1",
            "1-sp",
            "1-sp2",
            "1-sp123",
            "1-abc",
            "1-def",
            "1-pom-1",
            "1-1-snapshot",
            "1-1",
            "1-2",
            "1-123");

    /** Maven's {@code VERSIONS_NUMBER}: numeric segments, mixed separators and short qualifiers. */
    private static final List<String> NUMBERS = List.of(
            "2.0", "2.0.a", "2-1", "2.0.2", "2.0.123", "2.1.0", "2.1-a", "2.1b", "2.1-c", "2.1-1", "2.1.0.1", "2.2",
            "2.123", "11.a2", "11.a11", "11.b2", "11.b11", "11.m2", "11.m11", "11", "11.a", "11b", "11c", "11m");

    /** Ascending rows: Maven's regression cases, and four-part releases as Central publishes them. */
    private static final List<List<String>> ASCENDING = List.of(
            // A four-part release is a patch of the three-part one, never older and never a qualifier.
            // "ea" is no qualifier Maven knows, so 27-ea+30 sorts above 27 and below 27.0.1.
            List.of("27", "27-ea+30", "27.0.1-rc1", "27.0.1", "27.0.1.1", "27.0.1.2", "27.0.2", "27.1", "28-ea+12"),
            List.of("3.46.1.3", "3.50.2.0", "3.50.3.0", "3.50.3.1"), // org.xerial:sqlite-jdbc
            List.of("11.5.9.0", "12.1.0.0", "12.1.2.0"), // com.ibm.db2:jcc
            List.of("1.0", "1.0.0.1", "1.0.1"),
            // MNG-5568: a qualifier glued to a digit is still a qualifier.
            List.of("6.1.0rc3", "6.1.0", "6.1H.5-beta"),
            // MNG-6572: long numeric segments compare as numbers.
            List.of(
                    "20190126.230843",
                    "1234567890.12345",
                    "123456789012345.1H.5-beta",
                    "12345678901234567890.1H.5-beta"),
            // MNG-6964: a list item after "-0" compares as a whole.
            List.of("1-0.alpha", "1-0.beta", "1"),
            // MNG-7644: ".X" reads as "-X" for a qualifier X, below the next number.
            List.of("1.0.RC2", "1.0-RC3", "1.0.1"),
            List.of("1.0.X2", "1.0-X3", "1.0.1"),
            // An unknown dotted qualifier sorts after the plain release.
            List.of("2.0.1", "2.0.1.MR"));

    /** Equal groups: Maven's {@code testVersionsEqual}. */
    private static final List<List<String>> EQUAL = List.of(
            List.of("1", "1.0", "1.0.0", "1-0", "1.0-0", "1.0.0-0", "1-0-0"),
            List.of("27.0.1", "27.0.1.0", "27.0.1-0"),
            List.of("1ga", "1-ga", "1.ga", "1.0-ga", "1-final", "1-release", "1"),
            List.of("1cr", "1rc", "1-cr", "1-rc"),
            List.of("1a1", "1-alpha-1", "1alpha1", "1-a1"),
            List.of("1b2", "1-beta-2", "1beta2"),
            List.of("1m3", "1-milestone-3", "1milestone3"),
            List.of("1X", "1x"),
            List.of("1A", "1a"),
            List.of("1-SNAPSHOT", "1-snapshot"));

    @Test
    void every_row_ascends_and_every_group_is_equal_as_maven_orders_them() {
        List<String> wrong = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>(ASCENDING);
        rows.add(QUALIFIERS);
        rows.add(NUMBERS);
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                for (int j = i + 1; j < row.size(); j++) {
                    String low = row.get(i);
                    String high = row.get(j);
                    if (Versions.compare(low, high) >= 0) wrong.add(low + " < " + high);
                    if (Versions.compare(high, low) <= 0) wrong.add(high + " > " + low);
                }
            }
        }
        for (List<String> group : EQUAL) {
            for (String a : group) {
                for (String b : group) {
                    if (Versions.compare(a, b) != 0) wrong.add(a + " == " + b);
                }
            }
        }
        assertThat(wrong).as("pairs that disagree with Maven").isEmpty();
    }

    @Test
    void the_newest_of_an_openjfx_catalog_is_its_four_part_release() {
        // maven-metadata.xml lists 27.0.1.1 before 27.0.1; order of listing says nothing.
        List<String> catalog = new ArrayList<>(List.of("27-ea+30", "27.0.1.1", "27.0.1", "26.0.2"));
        catalog.sort(Versions::compare);
        assertThat(catalog).containsExactly("26.0.2", "27-ea+30", "27.0.1", "27.0.1.1");
    }
}
