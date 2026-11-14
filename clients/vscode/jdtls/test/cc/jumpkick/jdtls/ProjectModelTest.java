// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The bundle's reading of a model the engine served for a two-module workspace
 * ({@code recorded-model.json}, its root written as {@code ${ROOT}}): every project, its folders
 * relative to it and its entries in order. Plain {@code main} so the bundle's build runs it with
 * nothing but Gson on the classpath; exits 1 on the first failed check.
 */
public final class ProjectModelTest {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path rootPath = Path.of("/work/ws").toAbsolutePath();
        String root = rootPath.toString();
        // Forward slashes keep a Windows root a valid JSON string.
        String json = Files.readString(dir.resolve("recorded-model.json")).replace("${ROOT}", root.replace('\\', '/'));
        ProjectModel model = ProjectModel.parse(json);

        check(model.root().equals(root), "root " + model.root());
        check(model.projects().size() == 2, "two projects, got " + model.projects().size());
        ProjectModel.Project app = model.projects().get(1);
        check(app.name().equals("app"), "second project is app: " + app.name());
        check(app.dir().equals(rootPath.resolve("app")), "app dir " + app.dir());
        check(app.output().equals("target/jdt/classes/main"), "app output " + app.output());
        check(app.compliance().equals("25"), "app compliance " + app.compliance());

        List<ProjectModel.Entry> e = app.entries();
        check(e.get(0).equals(new ProjectModel.Source("src/main/java", "target/jdt/classes/main", false, false)), "main source " + e.get(0));
        check(e.get(1).equals(new ProjectModel.Source("src/test/java", "target/jdt/classes/test", true, false)), "test source " + e.get(1));
        check(e.contains(new ProjectModel.ProjectRef("lib", false)), "lib project ref in " + e);
        check(e.stream().anyMatch(x -> x instanceof ProjectModel.Container c && c.path().endsWith("/JavaSE-25")), "JRE container in " + e);
        ProjectModel.Library guava = (ProjectModel.Library) e.stream()
                .filter(x -> x instanceof ProjectModel.Library l && l.path().contains("guava"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no guava library in " + e));
        check(guava.sources() != null && guava.sources().endsWith("-sources.jar"), "guava sources " + guava.sources());
        check(!guava.test(), "guava on the main classpath");

        String outside = json.replace("\"" + root.replace('\\', '/') + "/app/src/main/java\"", "\"/elsewhere/src\"");
        check(!outside.equals(json), "the fixture names app's main source folder");
        try {
            ProjectModel.parse(outside);
            check(false, "a source folder outside its project is refused");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("outside project app"), expected.getMessage());
        }
        System.out.println("ProjectModelTest: OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("ProjectModelTest FAILED: " + what);
            System.exit(1);
        }
    }
}
