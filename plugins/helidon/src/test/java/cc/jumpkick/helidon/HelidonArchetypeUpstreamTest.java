// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.helidon.ArchetypeInputs.Input;
import cc.jumpkick.helidon.ArchetypeInputs.Snapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Re-derives {@code archetype-inputs.txt} from Helidon's repository on GitHub: at the snapshot's own
 * tag, which proves the snapshot is what upstream holds there, and at the newest release on the same
 * major line, so an input a Helidon patch release adds to {@code helidon init} is noticed before a
 * user asks for it. A difference fails with both line lists; {@code HELIDON_ARCHETYPE_CAPTURE=<file>}
 * also writes what was read at the newest release there, to replace the snapshot with (then map the
 * new inputs in {@link HelidonArchetypeOptionsTest}).
 *
 * <p>Network tier: the GitHub API (two unauthenticated calls) and raw.githubusercontent.com.
 */
@Tag("network")
class HelidonArchetypeUpstreamTest {

    private static final String REPO = "helidon-io/helidon";
    private static final String CAPTURE_ENV = "HELIDON_ARCHETYPE_CAPTURE";
    private static final Pattern RELEASE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern TREE_PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TAG_REF = Pattern.compile("\"ref\"\\s*:\\s*\"refs/tags/([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    void the_snapshot_is_what_the_archetypes_hold_at_its_tag() throws Exception {
        Snapshot snapshot = Snapshot.read();
        Snapshot upstream = new Snapshot(snapshot.tag(), inputsAt(snapshot.tag()));
        assertThat(upstream.text())
                .as("archetype-inputs.txt against helidon %s on GitHub", snapshot.tag())
                .isEqualTo(snapshot.text());
    }

    @Test
    void the_newest_release_on_the_line_adds_no_input() throws Exception {
        Snapshot snapshot = Snapshot.read();
        String newest = newestRelease(snapshot.tag());
        Snapshot upstream = new Snapshot(newest, inputsAt(newest));
        String capture = System.getenv(CAPTURE_ENV);
        if (capture != null && !capture.isBlank()) {
            Files.writeString(Path.of(capture), upstream.text(), StandardCharsets.UTF_8);
        }
        assertThat(upstream.inputs())
                .as(
                        "helidon %s's archetype inputs against archetype-inputs.txt (%s); %s=<file> writes the"
                                + " new snapshot, then map its inputs in HelidonArchetypeOptionsTest and move the"
                                + " templates' [helidon] version to %s",
                        newest, snapshot.tag(), CAPTURE_ENV, newest)
                .isEqualTo(snapshot.inputs());
    }

    /** The archetype inputs at {@code tag}: every script under the archetype directory, read raw. */
    private Map<String, Input> inputsAt(String tag) throws Exception {
        String tree = get("https://api.github.com/repos/" + REPO + "/git/trees/" + tag + "?recursive=1");
        assertThat(tree).as("the tree at %s is listed whole", tag).doesNotContain("\"truncated\":true");
        List<byte[]> scripts = new ArrayList<>();
        Matcher m = TREE_PATH.matcher(tree);
        while (m.find()) {
            String path = m.group(1);
            // Scripts only: an XML file under a files/ directory is a project file the archetype copies.
            if (!path.startsWith(ArchetypeInputs.ARCHETYPE_DIR) || !path.endsWith(".xml") || path.contains("/files/")) {
                continue;
            }
            scripts.add(get("https://raw.githubusercontent.com/" + REPO + "/" + tag + "/" + path)
                    .getBytes(StandardCharsets.UTF_8));
        }
        // Fake-green floor: 25 scripts at 27.0.1; a moved directory reads as none.
        assertThat(scripts).as("archetype scripts at %s", tag).hasSizeGreaterThanOrEqualTo(10);
        return ArchetypeInputs.of(scripts);
    }

    /** The highest {@code major.minor.patch} release tag on {@code tag}'s major line. */
    private String newestRelease(String tag) throws Exception {
        Matcher own = RELEASE.matcher(tag);
        assertThat(own.matches()).as("the snapshot's tag %s is a release", tag).isTrue();
        String refs = get("https://api.github.com/repos/" + REPO + "/git/matching-refs/tags/" + own.group(1) + ".");
        List<String> releases = new ArrayList<>();
        Matcher m = TAG_REF.matcher(refs);
        while (m.find()) {
            if (RELEASE.matcher(m.group(1)).matches()) releases.add(m.group(1));
        }
        assertThat(releases)
                .as("release tags on helidon's %s line", own.group(1))
                .contains(tag);
        return releases.stream()
                .max(Comparator.comparing(HelidonArchetypeUpstreamTest::versionKey))
                .orElseThrow();
    }

    private static String versionKey(String release) {
        Matcher m = RELEASE.matcher(release);
        if (!m.matches()) throw new IllegalArgumentException(release);
        return "%06d.%06d.%06d"
                .formatted(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/vnd.github+json")
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("GET %s: %s", url, response.body()).isEqualTo(200);
        return response.body();
    }
}
