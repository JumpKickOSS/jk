// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootDiscoveryTest {

    @TempDir
    Path tmp;

    private Path profile() {
        return tmp.resolve("Users").resolve("ada");
    }

    private Path home() {
        return profile().resolve(".jk");
    }

    private static Path project(Path dir, String marker) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(marker), "");
        return dir;
    }

    private RootDiscovery discovery(List<Path> known, List<Path> drives) throws Exception {
        Files.createDirectories(home());
        Forbidden rules = new Forbidden(
                profile().toString(),
                home().toString(),
                tmp.resolve("Windows").toString(),
                List.of(tmp.resolve("Program Files").toString()),
                Os.isWindows());
        return new RootDiscovery(profile(), profile().resolve("AppData/Roaming"), home(), drives, known, rules);
    }

    private static List<Path> paths(List<RootDiscovery.Root> roots) {
        return roots.stream().map(RootDiscovery.Root::path).toList();
    }

    @Test
    void proposesConventionalContainersAndHomeNotEachProject() throws Exception {
        // source\repos is proposed through source, which holds it within two levels.
        project(profile().resolve("IdeaProjects/shop"), "pom.xml");
        project(profile().resolve("IdeaProjects/blog"), "build.gradle.kts");
        project(profile().resolve("source/repos/Tool"), "Tool.sln");
        Files.createDirectories(profile().resolve("src")); // empty: no projects, not proposed
        List<Path> roots = paths(discovery(List.of(), List.of()).propose());
        assertThat(roots)
                .containsExactly(
                        home().toRealPath(),
                        profile().resolve("IdeaProjects").toRealPath(),
                        profile().resolve("source").toRealPath());
    }

    @Test
    void aProjectTwoLevelsDownStillCounts() throws Exception {
        project(profile().resolve("code/acme/api"), "go.mod");
        assertThat(paths(discovery(List.of(), List.of()).propose()))
                .contains(profile().resolve("code").toRealPath());
    }

    @Test
    void aLoneKnownProjectIsProposedAsItselfAndOneUnderDocumentsGetsANote() throws Exception {
        Path lone = project(tmp.resolve("work/thing"), "jk.toml");
        Path docs = project(profile().resolve("Documents/app"), "package.json");
        List<RootDiscovery.Root> roots =
                discovery(List.of(lone, docs), List.of()).propose();
        assertThat(paths(roots)).contains(lone.toRealPath(), docs.toRealPath());
        Path docsReal = docs.toRealPath();
        RootDiscovery.Root underDocs = roots.stream()
                .filter(r -> r.path().equals(docsReal))
                .findFirst()
                .orElseThrow();
        assertThat(underDocs.note()).startsWith("move it out of");
    }

    @Test
    void aKnownProjectBesideOthersProposesTheirContainer() throws Exception {
        Path a = project(tmp.resolve("work/a"), ".git");
        project(tmp.resolve("work/b"), "Cargo.toml");
        assertThat(paths(discovery(List.of(a), List.of()).propose()))
                .contains(tmp.resolve("work").toRealPath());
    }

    @Test
    void neverProposesTheProfileDownloadsOrADriveRoot() throws Exception {
        Path inDownloads = project(profile().resolve("Downloads/x"), "pom.xml");
        project(profile().resolve("Downloads/y"), "pom.xml");
        Path atProfile = project(profile().resolve("lonely"), "pom.xml");
        project(profile().resolve("other"), "pom.xml");
        List<Path> roots =
                paths(discovery(List.of(inDownloads, atProfile), List.of(tmp)).propose());
        assertThat(roots)
                .doesNotContain(
                        profile().toRealPath(), profile().resolve("Downloads").toRealPath());
        Path downloads = profile().resolve("Downloads").toRealPath();
        assertThat(roots).noneMatch(r -> r.startsWith(downloads));
        assertThat(roots).contains(atProfile.toRealPath());
    }

    @Test
    void neverDescendsIntoALinkAndProposesALinkedProjectByItsTarget() throws Exception {
        Path real = project(tmp.resolve("elsewhere/real"), "pom.xml");
        Path containers = Files.createDirectories(profile().resolve("projects"));
        Symlinks.create(containers.resolve("linked"), real);
        // The only project under projects/ is behind a link, which the walk does not enter.
        assertThat(paths(discovery(List.of(), List.of()).propose())).doesNotContain(containers.toRealPath());
        // A known project reached through a link is proposed by its real path.
        assertThat(paths(discovery(List.of(containers.resolve("linked")), List.of())
                        .propose()))
                .contains(real.toRealPath());
    }

    @Test
    void skipsHiddenBuildAndNodeModulesFolders() throws Exception {
        project(profile().resolve("dev/node_modules/pkg"), "package.json");
        project(profile().resolve("dev/.cache/x"), "pom.xml");
        project(profile().resolve("dev/target/y"), "pom.xml");
        assertThat(paths(discovery(List.of(), List.of()).propose()))
                .doesNotContain(profile().resolve("dev").toRealPath());
    }

    @Test
    void driveContainersCountToo() throws Exception {
        project(tmp.resolve("D/src/svc"), "pom.xml");
        assertThat(paths(discovery(List.of(), List.of(tmp.resolve("D"))).propose()))
                .contains(tmp.resolve("D/src").toRealPath());
    }

    @Test
    void readsIntellijAndVsCodeRecents() throws Exception {
        Path ij = project(tmp.resolve("ij/proj"), "pom.xml");
        Path vs = project(tmp.resolve("vs/proj"), "package.json");
        Path options =
                Files.createDirectories(profile().resolve("AppData/Roaming/JetBrains/IntelliJIdea2026.2/options"));
        Files.writeString(
                options.resolve("recentProjects.xml"),
                "<map><entry key=\"" + ij.toString().replace('\\', '/') + "\"/></map>");
        Path storage = Files.createDirectories(profile().resolve("AppData/Roaming/Code/User/globalStorage"));
        Files.writeString(storage.resolve("storage.json"), "{\"folderUri\":\"" + vsCodeUri(vs) + "\"}");
        List<Path> recents = discovery(List.of(), List.of()).ideRecents();
        assertThat(recents).contains(ij, vs);
    }

    /** How VS Code writes a folder: {@code file:///c%3A/Users/…} on Windows. */
    private static String vsCodeUri(Path dir) {
        String s = dir.toString();
        if (!Os.isWindows()) return "file://" + s;
        return "file:///" + Character.toLowerCase(s.charAt(0)) + "%3A"
                + s.substring(2).replace('\\', '/');
    }

    @Test
    void stopsAtItsTimeBudget() throws Exception {
        project(profile().resolve("IdeaProjects/shop"), "pom.xml");
        List<Path> roots = paths(discovery(List.of(), List.of()).propose(Duration.ZERO));
        assertThat(roots).containsExactly(home().toRealPath());
    }
}
