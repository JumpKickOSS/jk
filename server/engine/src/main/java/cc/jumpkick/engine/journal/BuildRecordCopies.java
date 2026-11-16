// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.engine.journal.BuildRecord.CacheBenefit;
import cc.jumpkick.engine.journal.BuildRecord.Coverage;
import cc.jumpkick.engine.journal.BuildRecord.Diag;
import cc.jumpkick.engine.journal.BuildRecord.Io;
import cc.jumpkick.engine.journal.BuildRecord.Module;
import cc.jumpkick.engine.journal.BuildRecord.Publish;
import cc.jumpkick.engine.journal.BuildRecord.Suspension;
import cc.jumpkick.engine.journal.BuildRecord.Task;
import cc.jumpkick.engine.journal.BuildRecord.Tests;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** A {@link BuildRecord} with some of its fields changed: what its {@code with} methods return. */
final class BuildRecordCopies {

    private BuildRecordCopies() {}

    /** {@code r} with {@code change} applied to a copy of its fields. */
    static BuildRecord edit(BuildRecord r, Consumer<Fields> change) {
        Fields f = new Fields(r);
        change.accept(f);
        return f.build();
    }

    /** The fields of a record being copied, each one assignable. */
    static final class Fields {
        @Nullable
        String id;

        long buildNumber;
        int schema;
        String kind;
        String dir;

        @Nullable
        String coord;

        @Nullable
        String projectId;

        long startedAt;
        long finishedAt;
        long millis;
        boolean success;
        boolean cancelled;
        int exitCode;
        String jkVersion;

        @Nullable
        Tests tests;

        List<Module> modules;
        List<Task> steps;
        List<Diag> diagnostics;

        @Nullable
        String trigger;

        @Nullable
        String session;

        @Nullable
        String commit;

        @Nullable
        CacheBenefit benefit;

        boolean running;

        @Nullable
        Io io;

        long requestId;

        @Nullable
        Publish publish;

        List<Coverage> coverage;

        @Nullable
        JobDelta delta;

        @Nullable
        String episode;

        List<Suspension> suspensions;

        @Nullable
        RunSource source;

        private Fields(BuildRecord r) {
            id = r.id();
            buildNumber = r.buildNumber();
            schema = r.schema();
            kind = r.kind();
            dir = r.dir();
            coord = r.coord();
            projectId = r.projectId();
            startedAt = r.startedAt();
            finishedAt = r.finishedAt();
            millis = r.millis();
            success = r.success();
            cancelled = r.cancelled();
            exitCode = r.exitCode();
            jkVersion = r.jkVersion();
            tests = r.tests();
            modules = r.modules();
            steps = r.steps();
            diagnostics = r.diagnostics();
            trigger = r.trigger();
            session = r.session();
            commit = r.commit();
            benefit = r.benefit();
            running = r.running();
            io = r.io();
            requestId = r.requestId();
            publish = r.publish();
            coverage = r.coverage();
            delta = r.delta();
            episode = r.episode();
            suspensions = r.suspensions();
            source = r.source();
        }

        private BuildRecord build() {
            return new BuildRecord(
                    id,
                    buildNumber,
                    schema,
                    kind,
                    dir,
                    coord,
                    projectId,
                    startedAt,
                    finishedAt,
                    millis,
                    success,
                    cancelled,
                    exitCode,
                    jkVersion,
                    tests,
                    modules,
                    steps,
                    diagnostics,
                    trigger,
                    session,
                    commit,
                    benefit,
                    running,
                    io,
                    requestId,
                    publish,
                    coverage,
                    delta,
                    episode,
                    suspensions,
                    source);
        }
    }
}
