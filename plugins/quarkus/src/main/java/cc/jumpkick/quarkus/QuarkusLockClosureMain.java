// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.command.Exit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The {@code quarkus/deployment} lock closure, resolved the way the augment and the test model
 * resolve: Quarkus's own resolver builds the production application model, then the test one, each
 * from the locked entries over an empty local repository, and every file it read — the extensions'
 * {@code -deployment} artifacts and their closure, each POM, parent and imported BOM, and the
 * runtime POMs it walked — is written to the output file, one coordinate a line. {@code jk lock}
 * pins them; {@code jk sync} puts them in the store, where the augment's resolver reads them as a
 * local tail, so a clean build downloads nothing and an offline one has everything.
 *
 * <p>Quarkus decides what a deployment closure is (each runtime extension's descriptor, its
 * conditional dependencies and capabilities, the platform BOM); this fork asks it rather than
 * repeating that logic.
 */
public final class QuarkusLockClosureMain {

    /** The prefix of every line this fork writes about itself. */
    static final String LOG_PREFIX = "jk-quarkus-lock";

    public static void main(String[] args) throws Exception {
        if (args.length != 12) {
            System.err.println("usage: QuarkusLockClosureMain classesDir scratchDir group artifact version"
                    + " runtimeListFile testRuntimeListFile quarkusVersion repositoriesFile offline outFile"
                    + " closureRepository");
            System.exit(Exit.USAGE);
        }
        Path classesDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path scratch = Path.of(args[1]).toAbsolutePath().normalize();
        String group = args[2];
        String artifact = args[3];
        String version = args[4];
        LockedClosure runtime = LockedClosure.parse(Path.of(args[5]));
        LockedClosure test = LockedClosure.parse(Path.of(args[6]));
        String quarkusVersion = args[7];
        RepositoryRoutes routes =
                RepositoryRoutes.read(Path.of(args[8]).toAbsolutePath().normalize());
        boolean offline = EnvValues.parseBool(args[9]).orElse(false);
        Path out = Path.of(args[10]).toAbsolutePath().normalize();
        // What the store already holds of the closure: a relock downloads only what is new.
        @Nullable Path seed = QuarkusAugmentMain.closureRepository(args[11]);

        Set<String> files = new TreeSet<>();
        files.addAll(resolve(
                "runtime",
                runtime,
                seed,
                classesDir,
                scratch,
                group,
                artifact,
                version,
                quarkusVersion,
                routes,
                offline));
        if (!test.artifacts().isEmpty()) {
            files.addAll(resolve(
                    "test",
                    test,
                    seed,
                    classesDir,
                    scratch,
                    group,
                    artifact,
                    version,
                    quarkusVersion,
                    routes,
                    offline));
        }
        Files.write(out, files, StandardCharsets.UTF_8);
        System.err.println(LOG_PREFIX + ": " + files.size() + " files");
    }

    /**
     * Every file the resolver reads building {@code locked}'s application model over a fresh local
     * repository of its own, the application and the workspace siblings left out: neither comes
     * from a repository.
     */
    static List<String> resolve(
            String label,
            LockedClosure locked,
            @Nullable Path seed,
            Path classesDir,
            Path scratch,
            String group,
            String artifact,
            String version,
            String quarkusVersion,
            RepositoryRoutes routes,
            boolean offline)
            throws Exception {
        Path dir = Files.createDirectories(scratch.resolve(label));
        Path localRepo = Files.createDirectories(dir.resolve("m2"));
        Path appJar = dir.resolve("app.jar");
        AppJar.write(classesDir, appJar);
        Set<String> skipped = new LinkedHashSet<>();
        skipped.add(group + ":" + artifact + ":" + version);
        for (LockedClosure.Artifact a : locked.artifacts()) {
            if (a.workspace()) skipped.add(a.group() + ":" + a.artifact() + ":" + a.version());
        }
        ResolvedArtifacts record = new ResolvedArtifacts(skipped);
        QuarkusAugmentMain.resolveModel(
                locked,
                localRepo,
                seed,
                offline,
                routes,
                group,
                artifact,
                version,
                appJar,
                classesDir,
                quarkusVersion,
                record);
        System.err.println(
                LOG_PREFIX + ": " + label + " model read " + record.files().size() + " files");
        return record.files();
    }

    private QuarkusLockClosureMain() {}
}
