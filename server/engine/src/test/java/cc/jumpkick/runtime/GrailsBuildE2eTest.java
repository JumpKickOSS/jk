// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

/**
 * acceptance: the [grails] plugin drives a scaffold-shaped Grails 8 app through the real
 * plan — grails-app sources compile over the groovy lane (manifest source-roots), grails-app/
 * conf lands in resources, and the grails-jar packager produces a Boot-launcher jar.
 *
 * <p>Network test (Maven Central for the grails-bom closure — large on a cold cache); the CAS
 * persists under build/ so repeat runs are warm. A GORM runtime (dynamic finder) assertion is
 * deliberately NOT made here: booting even a minimal Grails app pulls the full Hibernate + web
 * stack up in-JVM — instead the compiled domain class is asserted to carry the GORM entity AST
 * (implements the GormEntity trait), which is the compile-time half of that behavior.
 */
@Tag("slow")
class GrailsBuildE2eTest {

    @Test
    void grails_app_compiles_packages_boot_jar_with_conf_resources(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("gnotes"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "gnotes"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                groovy  = "5.0.7" # grails scaffold pin (GRAILS_GROOVY_VERSION) — overrides the M4 bom

                [application]
                main = "com.example.Application"

                [grails]
                version = "8.0.0-M4"

                [dependencies]
                grails-core            = { group = "org.apache.grails", name = "grails-core" }
                grails-web-boot        = { group = "org.apache.grails", name = "grails-web-boot" }
                grails-url-mappings    = { group = "org.apache.grails", name = "grails-url-mappings" }
                grails-databinding     = { group = "org.apache.grails", name = "grails-databinding" }
                grails-rest-transforms = { group = "org.apache.grails", name = "grails-rest-transforms" }
                grails-data-hibernate7 = { group = "org.apache.grails", name = "grails-data-hibernate7" }
                grails-logging         = { group = "org.apache.grails", name = "grails-logging" }
                starter-tomcat         = { group = "org.springframework.boot", name = "spring-boot-starter-tomcat" }
                logback-classic        = { group = "ch.qos.logback", name = "logback-classic" }
                h2                     = { group = "com.h2database", name = "h2" }
                hikaricp               = { group = "com.zaxxer", name = "HikariCP" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph (see GroovyBuildE2eTest).
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }
                """);
        Path domain = Files.createDirectories(project.resolve("grails-app/domain/com/example"));
        Files.writeString(domain.resolve("Note.groovy"), """
                package com.example

                class Note {
                    String title
                    String body

                    static constraints = {
                        title blank: false
                        body nullable: true
                    }
                }
                """);
        Path controllers = Files.createDirectories(project.resolve("grails-app/controllers/com/example"));
        Files.writeString(controllers.resolve("NoteController.groovy"), """
                package com.example

                import grails.gorm.transactions.ReadOnly

                @ReadOnly
                class NoteController {

                    static responseFormats = ['json']

                    def index() {
                        respond Note.list()
                    }
                }
                """);
        Path init = Files.createDirectories(project.resolve("grails-app/init/com/example"));
        Files.writeString(init.resolve("Application.groovy"), """
                package com.example

                import grails.boot.GrailsApp
                import grails.boot.config.GrailsAutoConfiguration

                class Application extends GrailsAutoConfiguration {
                    static void main(String[] args) {
                        GrailsApp.run(Application, args)
                    }
                }
                """);
        Files.writeString(init.resolve("BootStrap.groovy"), """
                package com.example

                class BootStrap {
                    def init = { servletContext -> }
                    def destroy = {}
                }
                """);
        Path conf = Files.createDirectories(project.resolve("grails-app/conf"));
        Files.writeString(conf.resolve("application.yml"), """
                ---
                grails:
                    profile: rest-api
                ---
                dataSource:
                    pooled: true
                    driverClassName: org.h2.Driver
                    username: sa
                    password: ''
                    dbCreate: create-drop
                    url: jdbc:h2:mem:devDb;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // compile-groovy swept the grails-app roots (manifest source-roots, not src/).
        Path classes = project.resolve("target/classes/main");
        assertThat(classes.resolve("com/example/Note.class")).exists();
        assertThat(classes.resolve("com/example/NoteController.class")).exists();
        assertThat(classes.resolve("com/example/Application.class")).exists();
        // grails-app/conf is a contributed resource root.
        assertThat(classes.resolve("application.yml")).exists();

        // The GORM entity AST applied at compile time: Note implements the GormEntity trait.
        ClassReader reader = new ClassReader(Files.readAllBytes(classes.resolve("com/example/Note.class")));
        assertThat(reader.getInterfaces()).anyMatch(i -> i.equals("org/grails/datastore/gorm/GormEntity"));

        // The grails-jar packager replaced the main artifact with a Boot-launcher jar.
        Path jar = project.resolve("target/lib/gnotes-1.0.0.jar");
        if (!Files.isRegularFile(jar)) {
            try (var walk = Files.walk(project.resolve("target"))) {
                jar = walk.filter(f -> f.getFileName().toString().equals("gnotes-1.0.0.jar"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no gnotes-1.0.0.jar under target/"));
            }
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            var attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.Application");
            assertThat(attrs.getValue("Grails-Version")).isEqualTo("8.0.0-M4");
            assertThat(jf.getEntry("BOOT-INF/classes/com/example/Note.class")).isNotNull();
            assertThat(jf.getEntry("BOOT-INF/classes/application.yml")).isNotNull();
            assertThat(jf.getEntry("BOOT-INF/classpath.idx")).isNotNull();
            assertThat(jf.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/lib/grails-core-")))
                    .as("nested grails-core lib")
                    .isTrue();
        }
    }

    private static Path cache() {
        return Path.of(System.getProperty("user.dir"), "build", "grails-e2e-cache");
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
