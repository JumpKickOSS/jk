// SPDX-License-Identifier: Apache-2.0

plugins { id("jk.java") }

// Guard G64: a compile task is never disabled.
//
// A compile task can be disabled by one wrong assignment in a convention plugin, and nothing
// downstream objects: the test task it feeds goes NO-SOURCE, the gate report says the tier did
// not run, and the build is green. That is how shared/core's 1,236 unit tests stopped executing
// without a red line anywhere. There is no legitimate disabled compile in this tree.
val checkNoDisabledCompile = registerGuard("checkNoDisabledCompile") {
    val compiles = tasks.withType<JavaCompile>()
    inputs.property("compileTasksEnabled", provider {
        compiles.map { "${it.name}=${it.enabled}" }.sorted().joinToString(",")
    })
    val stamp = layout.buildDirectory.file("guards/no-disabled-compile.ok")
    outputs.file(stamp)
    doLast {
        if (compiles.isEmpty()) {
            throw GradleException("checkNoDisabledCompile saw no JavaCompile tasks in ${project.path} — the"
                    + " scan broke and the guard is passing vacuously. Fix the collection.")
        }
        val disabled = compiles.filterNot { it.enabled }.map { it.path }.sorted()
        if (disabled.isNotEmpty()) {
            throw GradleException("A disabled compile task silently takes its source set out of every gate:"
                    + " the tests it compiles report NO-SOURCE and the tier prints \"did not run\" while the"
                    + " build stays green.\n"
                    + disabled.joinToString("\n") { "  $it" }
                    + "\n  Switch off the checker, not the compilation (options.errorprone.enabled), or"
                    + " delete the sources it would compile.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
