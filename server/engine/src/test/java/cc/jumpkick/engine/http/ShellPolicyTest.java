// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link StaticContent#SHELL_POLICY} against the client it ships with: the unpkg sources in the
 * policy are exactly the versioned package prefixes the files under {@code /web} load, each in the
 * directive that governs how it loads, and nothing frames, embeds or rebases the dashboard.
 */
class ShellPolicyTest {

    /** {@code https://unpkg.com/<package>@<exact version>/}, scoped packages included. */
    private static final Pattern PINNED = Pattern.compile(
            "https://unpkg\\.com/((?:@[a-z0-9._-]+/)?[a-z0-9._-]+@\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?/)");

    private static final String UNPKG = "https://unpkg.com/";

    private static Map<String, String> client;
    private static Map<String, List<String>> policy;

    @BeforeAll
    static void read() throws Exception {
        client = clientFiles();
        policy = directives(StaticContent.SHELL_POLICY);
    }

    @Test
    void every_unpkg_url_in_the_client_names_an_exact_version() {
        Map<String, Integer> unpinned = new TreeMap<>();
        for (var file : client.entrySet()) {
            String text = file.getValue();
            for (int at = text.indexOf("unpkg.com"); at >= 0; at = text.indexOf("unpkg.com", at + 1)) {
                Matcher m = PINNED.matcher(text);
                boolean pinned = at >= 8 && m.region(at - 8, text.length()).lookingAt();
                if (!pinned) unpinned.merge(file.getKey(), 1, Integer::sum);
            }
        }
        // A bare version or a template is either a redirect (checked by CSP at each hop) or a URL
        // this test cannot read, and the policy could not name it exactly.
        assertThat(unpinned)
                .as("unpkg references without an exact package@version/ prefix")
                .isEmpty();
        assertThat(used()).as("the client's unpkg packages").isNotEmpty();
    }

    @Test
    void the_policy_names_exactly_the_unpkg_packages_the_client_loads_each_under_its_directive() {
        Set<String> fetched = fetchedByImportVerified();
        String monaco = prefix(constant(file("monaco.js"), "MONACO_BASE"));

        assertThat(fetched).as("modules importVerified fetches").isNotEmpty();
        Set<String> scripts = new TreeSet<>(used());
        scripts.removeAll(fetched);

        assertThat(unpkgSources("script-src")).isEqualTo(scripts);
        assertThat(unpkgSources("connect-src")).isEqualTo(fetched);
        assertThat(unpkgSources("style-src")).containsExactly(monaco);
        for (var d : policy.entrySet()) {
            if (Set.of("script-src", "connect-src", "style-src").contains(d.getKey())) continue;
            assertThat(d.getValue()).as(d.getKey()).noneMatch(s -> s.contains("unpkg"));
        }
    }

    @Test
    void no_directive_allows_all_of_unpkg() {
        for (var d : policy.entrySet()) {
            for (String source : d.getValue()) {
                if (!source.contains("unpkg")) continue;
                assertThat(PINNED.matcher(source).matches())
                        .as(d.getKey() + " " + source + " is a package@version/ prefix")
                        .isTrue();
            }
        }
    }

    @Test
    void nothing_frames_embeds_or_rebases_the_dashboard() {
        assertThat(policy.get("frame-ancestors")).containsExactly("'none'");
        assertThat(policy.get("frame-src")).containsExactly("'none'");
        assertThat(policy.get("object-src")).containsExactly("'none'");
        assertThat(policy.get("base-uri")).containsExactly("'none'");
        assertThat(policy.get("form-action")).containsExactly("'none'");
        assertThat(policy.get("connect-src")).contains("'self'");
        for (var file : client.entrySet()) {
            assertThat(file.getValue())
                    .as(file.getKey())
                    .doesNotContain("<iframe")
                    .doesNotContain("<base ");
        }
    }

    /** The unpkg prefixes of every URL the client's files name. */
    private static Set<String> used() {
        Set<String> out = new TreeSet<>();
        for (String text : client.values()) {
            Matcher m = PINNED.matcher(text);
            while (m.find()) out.add(UNPKG + m.group(1));
        }
        return out;
    }

    /** The prefixes of the {@code CDN} entries {@code monaco.js} hands to {@code importVerified}. */
    private static Set<String> fetchedByImportVerified() {
        String monaco = file("monaco.js");
        Set<String> out = new TreeSet<>();
        Matcher call = Pattern.compile("importVerified\\(CDN\\.(\\w+)\\)").matcher(monaco);
        while (call.find()) {
            Matcher entry = Pattern.compile("\\b" + call.group(1) + ":\\s*\\{\\s*src:\\s*'([^']+)'")
                    .matcher(monaco);
            assertThat(entry.find()).as("CDN." + call.group(1)).isTrue();
            out.add(prefix(entry.group(1)));
        }
        return out;
    }

    private static String file(String name) {
        return requireNonNull(client.get(name), name);
    }

    private static String constant(String js, String name) {
        Matcher m = Pattern.compile("const " + name + " = '([^']+)'").matcher(js);
        assertThat(m.find()).as(name + " is a string literal").isTrue();
        return m.group(1);
    }

    private static String prefix(String url) {
        Matcher m = PINNED.matcher(url);
        assertThat(m.lookingAt()).as(url).isTrue();
        return UNPKG + m.group(1);
    }

    private static Set<String> unpkgSources(String directive) {
        return policy.getOrDefault(directive, List.of()).stream()
                .filter(s -> s.startsWith(UNPKG))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, List<String>> directives(String csp) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String directive : csp.split(";")) {
            List<String> words = new ArrayList<>(Arrays.asList(directive.trim().split("\\s+")));
            if (words.getFirst().isEmpty()) continue;
            assertThat(out).as("directive named twice").doesNotContainKey(words.getFirst());
            out.put(words.removeFirst(), words);
        }
        return out;
    }

    /** The shipped client's text files by name, read from the jar (or directory) that carries them. */
    private static Map<String, String> clientFiles() throws Exception {
        URL index = ShellPolicyTest.class.getResource("/web/index.html");
        assertThat(index).as("the dashboard on the test classpath").isNotNull();
        URI uri = index.toURI();
        if (uri.getScheme().equals("jar")) {
            FileSystem fs;
            try {
                fs = FileSystems.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(uri);
            }
            return textFiles(requireNonNull(fs.provider().getPath(uri).getParent()));
        }
        return textFiles(requireNonNull(Path.of(uri).getParent()));
    }

    private static Map<String, String> textFiles(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.matches(".*\\.(html|js|mjs|css|webmanifest)")) out.put(name, Files.readString(f, UTF_8));
            }
        }
        assertThat(out).containsKeys("index.html", "monaco.js");
        return out;
    }
}
