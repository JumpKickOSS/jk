// SPDX-License-Identifier: Apache-2.0

import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    id("jk.java-conventions")
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

nullaway {
    onlyNullMarked = true
    jspecifyMode = true
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    errorprone("com.uber.nullaway:nullaway:0.14.1")
}

tasks.withType<JavaCompile>().configureEach {
    val excuse = NullMarking.unmarkedCompileTasks["${project.name}:$name"]
    // Error Prone's own switch, set outside its lambda: inside it, `isEnabled` resolves to the
    // compile task and silently disables the whole compilation instead of the checker.
    options.errorprone.enabled.set(excuse == null)
    // javac stops reporting at 100 errors, which turns every large sweep into a count of 101; the
    // sweeps size their work from the whole list.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
    options.errorprone {
        disableAllChecks = true
        error("RequireExplicitNullMarking")
        nullaway {
            error()
            // A CLI command's fields are its parsed invocation: `run(Invocation)` is where they are
            // assigned and nothing reads them before it. Without this, NullAway reads every one of
            // them as uninitialized, and the only way to satisfy it would be to declare fields
            // nullable that the parse fills in on every path — putting a requireNonNull in front of
            // each use and saying the opposite of what the lifecycle guarantees.
            knownInitializers.add("cc.jumpkick.model.command.CliCommand.run")
        }
    }
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("checkNullMarkedApiPackages"))
}
