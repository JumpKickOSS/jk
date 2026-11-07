// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.command.Exit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One of the lock closures {@code [quarkus]} declares, resolved the way the step that reads it
 * resolves: Quarkus's own resolver builds the application model from the locked entries (the
 * production closure for the augment's {@code deployment}, the test closure for the test model's
 * {@code test-deployment}) over an empty local repository, and every file it read — the
 * extensions' {@code -deployment} artifacts and their closure, each POM, parent and imported BOM,
 * and the runtime POMs it walked — is written to the output file, one {@code coordinate<TAB>path}
 * a line, the path where the resolver holds the bytes. {@code jk lock} pins them, taking the bytes
 * from those paths rather than downloading them again; the step reads them from a repository the
 * engine builds from the store, so a clean build downloads nothing and an offline one has
 * everything.
 *
 * <p>The model is checked against the lock as the step's own is ({@link LockedAppModel#enforce}): a
 * runtime artifact it reaches outside the lock, which a build would leave out of the application,
 * is appended to the notices file the worker names, and {@code jk lock} says it.
 *
 * <p>Quarkus decides what a deployment closure is (each runtime extension's descriptor, its
 * conditional dependencies and capabilities, the platform BOM); this fork asks it rather than
 * repeating that logic.
 */
public final class QuarkusLockClosureMain {

    /** The prefix of every line this fork writes about itself. */
    static final String LOG_PREFIX = "jk-quarkus-lock";

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            System.err.println("usage: QuarkusLockClosureMain classesDir scratchDir group artifact version"
                    + " entriesListFile quarkusVersion repositoriesFile closureRepository offline outFile");
            System.exit(Exit.USAGE);
        }
        Path classesDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path scratch = Path.of(args[1]).toAbsolutePath().normalize();
        String group = args[2];
        String artifact = args[3];
        String version = args[4];
        LockedClosure locked = LockedClosure.parse(Path.of(args[5]));
        String quarkusVersion = args[6];
        RepositoryRoutes routes =
                RepositoryRoutes.read(Path.of(args[7]).toAbsolutePath().normalize());
        // What jk already holds of the closure: a relock downloads only what is new.
        @Nullable Path seed = QuarkusAugmentMain.closureRepository(args[8]);
        boolean offline = EnvValues.parseBool(args[9]).orElse(false);
        Path out = Path.of(args[10]).toAbsolutePath().normalize();

        Map<String, Path> files = resolve(
                locked,
                seed,
                classesDir,
                scratch,
                group,
                artifact,
                version,
                quarkusVersion,
                routes,
                offline,
                ForkNotices.fromProperty());
        List<String> lines = new ArrayList<>(files.size());
        files.forEach((coordinate, file) -> lines.add(coordinate + "\t" + file));
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.err.println(LOG_PREFIX + ": " + files.size() + " files");
    }

    /**
     * Every file the resolver reads building {@code locked}'s application model over a fresh local
     * repository under {@code scratch}, each to where it holds the bytes; the application and the
     * workspace siblings are left out, since neither comes from a repository. A runtime artifact
     * the model reaches and {@code locked} does not name is one a build of this lock would leave
     * out of the application; {@code notices} hears it now, when the lock is written.
     */
    static Map<String, Path> resolve(
            LockedClosure locked,
            @Nullable Path seed,
            Path classesDir,
            Path scratch,
            String group,
            String artifact,
            String version,
            String quarkusVersion,
            RepositoryRoutes routes,
            boolean offline,
            ForkNotices notices)
            throws Exception {
        Path localRepo = Files.createDirectories(scratch.resolve("m2"));
        Path appJar = scratch.resolve("app.jar");
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
                record,
                notices);
        return record.files();
    }

    private QuarkusLockClosureMain() {}
}
