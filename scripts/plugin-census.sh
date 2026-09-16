#!/usr/bin/env bash
# Count the GitHub build files that declare each Maven and Gradle build plugin, so the batteries
# register (docs/user/plugins.md) and the plugin census (docs/contributors/plugin-census.md) rank
# plugins by measured use rather than by recollection. One GitHub code-search query per
# (plugin, file kind): pom.xml for a Maven artifactId; build.gradle (language:Gradle) and
# build.gradle.kts for a Gradle plugin id. The count is files, not repositories, and a bare id
# such as `jacoco` also matches non-plugin mentions; the numbers rank, they do not measure.
#
# Usage:
#   scripts/plugin-census.sh [out.tsv]     # default: target/plugin-census.tsv
# Needs `gh` logged in. Code search allows ten queries a minute, so a full run takes about half an
# hour; the output is appended one row at a time and a re-run skips rows already present, so an
# interrupted run resumes. Columns: tool, plugin, file kind, files. The final line is DONE.
# Rank: sort the Gradle rows by plugin, summing the two file kinds; the Maven rows as they are.
set -euo pipefail

out="${1:-target/plugin-census.tsv}"
mkdir -p "$(dirname "$out")"
touch "$out"

query() { # tool plugin kind search-expression
  local tool=$1 plugin=$2 kind=$3 expr=$4 n tries=0
  if grep -q -F -- "$(printf '%s\t%s\t%s\t' "$tool" "$plugin" "$kind")" "$out"; then return; fi
  while :; do
    n=$(gh api -X GET search/code -f q="$expr" --jq '.total_count' 2>/dev/null || true)
    if [[ "$n" =~ ^[0-9]+$ ]]; then break; fi
    tries=$((tries + 1))
    if (( tries > 5 )); then echo "giving up on $plugin ($kind)" >&2; return; fi
    sleep 65 # the rate limit answered; wait out the minute
  done
  printf '%s\t%s\t%s\t%s\n' "$tool" "$plugin" "$kind" "$n" >> "$out"
  echo "$tool $plugin $kind $n" >&2
  sleep 6.5
}

GRADLE=(
  org.jetbrains.kotlin.jvm org.jetbrains.kotlin.android org.jetbrains.kotlin.multiplatform
  org.jetbrains.kotlin.plugin.spring org.jetbrains.kotlin.plugin.jpa org.jetbrains.kotlin.plugin.serialization
  org.jetbrains.kotlin.kapt com.google.devtools.ksp org.jetbrains.kotlin.plugin.compose org.jetbrains.compose
  org.jetbrains.dokka org.jetbrains.kotlinx.kover org.jetbrains.kotlin.plugin.allopen
  org.springframework.boot io.spring.dependency-management org.graalvm.buildtools.native io.quarkus
  io.micronaut.application io.ktor.plugin org.hibernate.orm
  com.github.johnrengelman.shadow com.gradleup.shadow com.google.cloud.tools.jib com.bmuschko.docker-remote-api com.palantir.docker
  com.google.protobuf com.squareup.wire org.openapi.generator com.github.davidmc24.gradle.plugin.avro nu.studer.jooq
  org.flywaydb.flyway org.liquibase.gradle app.cash.sqldelight
  com.netflix.dgs.codegen com.apollographql.apollo io.github.kobylynskyi.graphql.codegen com.expediagroup.graphql
  com.diffplug.spotless io.gitlab.arturbosch.detekt org.jlleitschuh.gradle.ktlint com.github.spotbugs net.ltgt.errorprone io.freefair.lombok
  io.spring.javaformat org.sonarqube org.owasp.dependencycheck org.cyclonedx.bom com.github.jk1.dependency-license-report
  com.github.ben-manes.versions io.github.gradle-nexus.publish-plugin com.vanniktech.maven.publish org.jreleaser net.researchgate.release pl.allegro.tech.build.axion-release
  com.gorylenko.gradle-git-properties com.github.node-gradle.node org.asciidoctor.jvm.convert me.champeau.jmh de.undercouch.download com.github.gmazzo.buildconfig
  com.android.application com.android.library com.google.dagger.hilt.android com.google.gms.google-services com.google.firebase.crashlytics androidx.navigation.safeargs
  org.jetbrains.intellij org.beryx.jlink org.openjfx.javafxplugin com.gradle.develocity org.gradle.toolchains.foojay-resolver-convention com.adarshr.test-logger
  maven-publish java-test-fixtures java-platform jacoco checkstyle pmd antlr
)
MAVEN=(
  maven-compiler-plugin maven-surefire-plugin maven-failsafe-plugin maven-jar-plugin maven-war-plugin maven-ear-plugin maven-resources-plugin
  maven-source-plugin maven-javadoc-plugin maven-shade-plugin maven-assembly-plugin maven-dependency-plugin maven-enforcer-plugin
  maven-release-plugin maven-deploy-plugin maven-install-plugin maven-gpg-plugin maven-clean-plugin maven-site-plugin
  maven-checkstyle-plugin maven-pmd-plugin maven-antrun-plugin maven-toolchains-plugin maven-plugin-plugin maven-bundle-plugin
  spring-boot-maven-plugin quarkus-maven-plugin micronaut-maven-plugin kotlin-maven-plugin scala-maven-plugin gmavenplus-plugin aspectj-maven-plugin
  jacoco-maven-plugin spotbugs-maven-plugin spotless-maven-plugin fmt-maven-plugin formatter-maven-plugin git-commit-id-maven-plugin git-commit-id-plugin
  build-helper-maven-plugin exec-maven-plugin versions-maven-plugin flatten-maven-plugin license-maven-plugin sonar-maven-plugin
  dependency-check-maven cyclonedx-maven-plugin nexus-staging-maven-plugin central-publishing-maven-plugin
  jib-maven-plugin docker-maven-plugin dockerfile-maven-plugin native-maven-plugin
  protobuf-maven-plugin os-maven-plugin openapi-generator-maven-plugin swagger-codegen-maven-plugin jooq-codegen-maven
  flyway-maven-plugin liquibase-maven-plugin avro-maven-plugin antlr4-maven-plugin jaxb2-maven-plugin jaxb-maven-plugin cxf-codegen-plugin
  graphql-maven-plugin graphql-codegen-maven-plugin dgs-codegen-maven-plugin
  hibernate-enhance-maven-plugin frontend-maven-plugin asciidoctor-maven-plugin jsonschema2pojo-maven-plugin mybatis-generator-maven-plugin
  apt-maven-plugin moditect-maven-plugin maven-jlink-plugin javafx-maven-plugin wildfly-maven-plugin jetty-maven-plugin tomcat7-maven-plugin
)

for p in "${GRADLE[@]}"; do
  query gradle "$p" groovy "\"$p\" language:Gradle"
  query gradle "$p" kts    "\"$p\" filename:build.gradle.kts"
done
for p in "${MAVEN[@]}"; do
  query maven "$p" pom "\"$p\" filename:pom.xml"
done
echo DONE >> "$out"
