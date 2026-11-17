// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.java.compiler.IndexedJavac;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JSON a handler reads and writes, read from compiled classes: javac's for Java, and for
 * Kotlin, Scala and Groovy, Java classes compiled to the shapes kotlinc 2.4, scalac and groovyc 5
 * give a data class, a case class and a property class ({@code javap -v -p} of their output, on the
 * Mac): private fields behind public getters, a {@code @JsonProperty} on the constructor parameter
 * only (where kotlinc before 2.2 and scalac put one written on a {@code val}), {@code isActive},
 * {@code componentN}, {@code _1}, {@code copy}, Groovy's {@code metaClass}, and a {@code suspend}
 * handler's continuation.
 */
class JsonDtosTest {

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern TOP = Pattern.compile(
            "(?m)^(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*public\\s+(?:final\\s+|abstract\\s+)*(?:class|record|interface|@interface|enum)\\s+(\\w+)"
                    + "|public\\s+(?:final\\s+)?(?:class|record|interface|@interface|enum)\\s+(\\w+)");

    /** The libraries' annotations and types the classes name, as small stand-ins with their real names and retention. */
    private static final Map<String, String> STUBS = stubs(
            "package com.fasterxml.jackson.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface JsonProperty { String value() default \"\"; }",
            "package com.fasterxml.jackson.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface JsonIgnore { boolean value() default true; }",
            "package com.fasterxml.jackson.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface JsonIgnoreProperties { String[] value() default {}; }",
            "package com.fasterxml.jackson.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface JsonAutoDetect {"
                    + " enum Visibility { ANY, NON_PRIVATE, PUBLIC_ONLY, NONE, DEFAULT }"
                    + " Visibility fieldVisibility() default Visibility.DEFAULT; }",
            "package tools.jackson.databind; public class PropertyNamingStrategies { public static class SnakeCaseStrategy {} }",
            "package tools.jackson.databind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface JsonNaming { Class<?> value(); }",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface RestController {}",
            "package org.springframework.stereotype; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface Controller {}",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface GetMapping { String[] value() default {}; }",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface PutMapping { String[] value() default {}; }",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface PostMapping { String[] value() default {}; }",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface RequestBody {}",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface ResponseBody {}",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface RequestParam {}",
            "package org.springframework.web.bind.annotation; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface PathVariable {}",
            "package org.springframework.http; public class ResponseEntity<T> {}",
            "package jakarta.ws.rs; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface POST {}",
            "package jakarta.ws.rs; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface PathParam { String value(); }",
            "package jakarta.ws.rs.core; public class Response {}",
            "package jakarta.validation; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Valid {}",
            "package kotlin; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Metadata {}",
            "package kotlin.coroutines; public interface Continuation<T> {}",
            "package scala.reflect; import java.lang.annotation.*;"
                    + " @Retention(RetentionPolicy.RUNTIME) public @interface ScalaSignature { String bytes(); }",
            "package scala; public interface Product {}",
            "package groovy.lang; public interface MetaClass {}");

    private static Map<String, String> stubs(String... sources) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : sources) out.put(path(s), s);
        return out;
    }

    private static String path(String source) {
        Matcher p = PACKAGE.matcher(source);
        Matcher t = TOP.matcher(source);
        if (!p.find() || !t.find()) throw new AssertionError("no package or public type in " + source);
        String name = t.group(1) != null ? t.group(1) : t.group(2);
        return p.group(1).replace('.', '/') + "/" + name + ".java";
    }

    /** {@code sources} (each a file of package {@code demo} or its own) compiled with the stubs; the class files by internal name. */
    private static Map<String, ClassModel> compile(Path dir, String... sources) throws IOException {
        Map<String, String> all = new LinkedHashMap<>(STUBS);
        for (String s : sources) all.put(path(s), s);
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            Path f = dir.resolve("src").resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f);
        }
        Path classes = dir.resolve("classes");
        String errors = IndexedJavac.compile(dir, classes, files, List.of());
        assertThat(errors).as("the classes compile").isNull();
        Map<String, ClassModel> out = new LinkedHashMap<>();
        try (var walk = Files.walk(classes)) {
            for (Path c : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = classes.relativize(c).toString().replace('\\', '/');
                out.put(
                        rel.substring(0, rel.length() - ".class".length()),
                        ClassFile.of().parse(Files.readAllBytes(c)));
            }
        }
        return out;
    }

    private static ClassModel cls(Map<String, ClassModel> classes, String internal) {
        ClassModel cm = classes.get(internal);
        if (cm == null) throw new AssertionError("no class " + internal + " in " + classes.keySet());
        return cm;
    }

    /** Each property as {@code json-name<member}, and after {@code ->} the workspace classes its type names. */
    private static List<String> props(Map<String, ClassModel> classes, String internal) {
        JsonDtos.Dto dto = JsonDtos.dto(cls(classes, internal));
        if (dto == null) throw new AssertionError(internal + " is not read as a class");
        return dto.properties().stream()
                .map(p -> p.name() + "<" + p.member() + (p.field() ? "" : "()")
                        + p.types().stream()
                                .filter(t -> t.startsWith("demo/"))
                                .map(t -> " -> " + t)
                                .reduce("", String::concat))
                .toList();
    }

    @Test
    void jacksonWritesGettersPublicFieldsAndRenamedOrNamedMembersOnly(@TempDir Path dir) throws IOException {
        Map<String, ClassModel> c = compile(dir, """
                package demo;

                import com.fasterxml.jackson.annotation.JsonIgnore;
                import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
                import com.fasterxml.jackson.annotation.JsonProperty;
                import java.time.LocalDate;

                @JsonIgnoreProperties({"internal"})
                public class Visit {
                    public static final String KIND = "visit";
                    private transient String cache;
                    private int id;
                    public String description;
                    private String internal;
                    @JsonProperty("visit_date")
                    private LocalDate date;
                    private String hidden;

                    public int getId() { return id; }
                    public LocalDate getDate() { return date; }
                    public String getInternal() { return internal; }
                    public boolean isUrgent() { return false; }
                    public Boolean isBoxed() { return null; }
                    @JsonIgnore
                    public String getAudit() { return ""; }
                    @JsonIgnore(false)
                    public String getKept() { return ""; }
                    String getPackagePrivate() { return ""; }
                    public static String getStatic() { return ""; }
                    public void setNotes(String notes) {}
                    public String getURL() { return ""; }
                }
                """);
        assertThat(props(c, "demo/Visit"))
                .containsExactly(
                        "id<id",
                        "description<description",
                        "visit_date<date",
                        "urgent<isUrgent()",
                        "kept<getKept()",
                        "notes<setNotes()",
                        "url<getURL()");
    }

    @Test
    void theNamingStrategyAndFieldVisibilityApplyToEveryNameNotGivenExplicitly(@TempDir Path dir) throws IOException {
        Map<String, ClassModel> c =
                compile(dir, """
                package demo;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import java.util.List;
                import tools.jackson.databind.PropertyNamingStrategies;
                import tools.jackson.databind.annotation.JsonNaming;

                @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
                public class OwnerForm {
                    private String firstName;
                    @JsonProperty("tel")
                    private String telephoneNumber;
                    private List<PetForm> pets;

                    public String getFirstName() { return firstName; }
                    public String getTelephoneNumber() { return telephoneNumber; }
                    public List<PetForm> getPets() { return pets; }
                }
                """, "package demo; public class PetForm { public String name; }", """
                package demo;

                import com.fasterxml.jackson.annotation.JsonAutoDetect;

                @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
                public class Raw {
                    private String secretName;
                    private static String shared;
                }
                """);
        assertThat(props(c, "demo/OwnerForm"))
                .containsExactly("first_name<firstName", "tel<telephoneNumber", "pets<pets -> demo/PetForm");
        assertThat(props(c, "demo/Raw")).containsExactly("secretName<secretName");
        assertThat(JsonDtos.translate("homeAddressLine", "KebabCaseStrategy")).isEqualTo("home-address-line");
        assertThat(JsonDtos.translate("id", "tools/jackson/databind/PropertyNamingStrategies$UpperCamelCaseStrategy"))
                .isEqualTo("Id");
    }

    @Test
    void aRecordsComponentsAreItsPropertiesAndAGenericBodyNamesItsTypeArguments(@TempDir Path dir) throws IOException {
        Map<String, ClassModel> c = compile(
                dir,
                """
                package demo;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import java.time.LocalDate;
                import java.util.Map;

                public record OwnerDto(int id, @JsonProperty("birth_date") LocalDate birthDate, Map<String, PetDto> pets) {
                    public String display() { return ""; }
                    public String getLabel() { return ""; }
                }
                """,
                "package demo; public record PetDto(String name) {}",
                "package demo; import java.util.List; public class Page<T> { public List<T> content; public long total; }",
                "package demo; public enum Kind { CAT, DOG }");
        assertThat(props(c, "demo/OwnerDto"))
                .containsExactly("id<id", "birth_date<birthDate", "pets<pets -> demo/PetDto", "label<getLabel()");
        assertThat(props(c, "demo/Page")).containsExactly("content<content", "total<total");
        assertThat(JsonDtos.dto(cls(c, "demo/Kind")))
                .as("an enum is written as a string")
                .isNull();
    }

    @Test
    void aHandlersBodyTypesAreItsReturnAndItsRequestBodyThroughLibraryWrappers(@TempDir Path dir) throws IOException {
        Map<String, ClassModel> c = compile(
                dir,
                """
                package demo;

                import jakarta.validation.Valid;
                import java.util.List;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PutMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OwnerApi {
                    @GetMapping("/owners")
                    public ResponseEntity<List<Page<OwnerDto>>> list(@RequestParam String q) { return null; }

                    @PutMapping("/owners/{id}")
                    public void update(@PathVariable int id, @Valid @RequestBody final OwnerForm form) {}

                    private OwnerDto list(int notAHandler) { return null; }
                }
                """,
                """
                package demo;

                import jakarta.validation.Valid;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.core.Response;

                public class Visits {
                    @POST
                    public Response add(@PathParam("id") int id, @Valid NewVisit visit) { return null; }
                }
                """,
                """
                package demo;

                import org.springframework.http.ResponseEntity;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.ResponseBody;

                @Controller
                public class Pages {
                    @GetMapping("/owners/{id}")
                    public OwnerDto view() { return null; }

                    @GetMapping("/api/owners/{id}")
                    @ResponseBody
                    public OwnerDto json() { return null; }

                    @GetMapping("/api/owners/{id}/card")
                    public ResponseEntity<OwnerForm> card() { return null; }
                }
                """,
                "package demo; public record OwnerDto(int id) {}",
                "package demo; public record OwnerForm(String name) {}",
                "package demo; public record NewVisit(String description) {}",
                "package demo; public class Page<T> { public java.util.List<T> content; }");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/OwnerApi"), "list"))
                .containsExactly(
                        "org/springframework/http/ResponseEntity", "java/util/List", "demo/Page", "demo/OwnerDto");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/OwnerApi"), "update")).containsExactly("demo/OwnerForm");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/Visits"), "add"))
                .containsExactly("jakarta/ws/rs/core/Response", "demo/NewVisit");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/Pages"), "view"))
                .as("a view's model is no JSON body")
                .isEmpty();
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/Pages"), "json")).containsExactly("demo/OwnerDto");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/Pages"), "card"))
                .containsExactly("org/springframework/http/ResponseEntity", "demo/OwnerForm");
    }

    /**
     * A Kotlin data class and controller in kotlinc's shape: {@code data class OwnerCard(val firstName:
     * String, @JsonProperty("tel") val telephone: String, val isActive: Boolean, val pets:
     * List<PetCard>)} and {@code suspend fun create(@RequestBody card: OwnerCard): OwnerCard}.
     */
    @Test
    void aKotlinDataClassReadsByItsGettersWithItsConstructorsNamesAndASuspendHandlersResult(@TempDir Path dir)
            throws IOException {
        Map<String, ClassModel> c = compile(
                dir,
                """
                package demo;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import java.util.List;

                @kotlin.Metadata
                public final class OwnerCard {
                    private final String firstName;
                    private final String telephone;
                    private final boolean isActive;
                    private final List<PetCard> pets;

                    public OwnerCard(String firstName, @JsonProperty("tel") String telephone, boolean isActive, List<PetCard> pets) {
                        this.firstName = firstName;
                        this.telephone = telephone;
                        this.isActive = isActive;
                        this.pets = pets;
                    }

                    public final String getFirstName() { return firstName; }
                    public final String getTelephone() { return telephone; }
                    public final boolean isActive() { return isActive; }
                    public final List<PetCard> getPets() { return pets; }
                    public final String component1() { return firstName; }
                    public final String component2() { return telephone; }
                    public final OwnerCard copy(String firstName, @JsonProperty("tel") String telephone, boolean isActive, List<PetCard> pets) {
                        return this;
                    }
                    public String toString() { return ""; }
                }
                """,
                "package demo; @kotlin.Metadata public final class PetCard { private final String name = \"\";"
                        + " public final String getName() { return name; } }",
                """
                package demo;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @kotlin.Metadata
                @RestController
                public final class CardController {
                    @PostMapping("/cards")
                    public final Object create(@RequestBody OwnerCard card, kotlin.coroutines.Continuation<? super Receipt> c) {
                        return null;
                    }
                }
                """,
                "package demo; public record Receipt(int id) {}");
        assertThat(props(c, "demo/OwnerCard"))
                .containsExactly(
                        "firstName<firstName", "tel<telephone", "isActive<isActive", "pets<pets -> demo/PetCard");
        assertThat(JsonDtos.bodyTypes(cls(c, "demo/CardController"), "create"))
                .containsExactlyInAnyOrder("demo/Receipt", "demo/OwnerCard");
    }

    /**
     * A Scala case class in scalac 2's shape ({@code case class VetCard(name: String, @JsonProperty("years")
     * experience: Int)}: private fields, same-named accessors, the annotation on the constructor
     * parameter, {@code _1} and {@code productArity}), and a Groovy class in groovyc's.
     */
    @Test
    void aScalaCaseClassReadsByItsAccessorsAndAGroovyClassWithoutItsMetaClass(@TempDir Path dir) throws IOException {
        Map<String, ClassModel> c = compile(
                dir,
                """
                package demo;

                import com.fasterxml.jackson.annotation.JsonProperty;

                @scala.reflect.ScalaSignature(bytes = "")
                public class VetCard implements scala.Product {
                    private final String name;
                    private final int experience;

                    public VetCard(String name, @JsonProperty("years") int experience) {
                        this.name = name;
                        this.experience = experience;
                    }

                    public String name() { return name; }
                    public int experience() { return experience; }
                    public String _1() { return name; }
                    public int productArity() { return 2; }
                    public String productPrefix() { return ""; }
                }
                """,
                """
                package demo;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import groovy.lang.MetaClass;

                public class VisitCard {
                    private String description;
                    @JsonProperty("when")
                    private String time;
                    private transient MetaClass metaClass;

                    public MetaClass getMetaClass() { return metaClass; }
                    public void setMetaClass(MetaClass m) {}
                    public String getDescription() { return description; }
                    public void setDescription(String d) {}
                    public String getTime() { return time; }
                    public void setTime(String t) {}
                }
                """,
                "package demo; public class Plain { private final String name = \"\"; public String name() { return name; } }");
        assertThat(props(c, "demo/VetCard")).containsExactly("name<name", "years<experience");
        assertThat(props(c, "demo/VisitCard")).containsExactly("description<description", "when<time");
        assertThat(props(c, "demo/Plain"))
                .as("outside Scala an accessor named for its field is no getter")
                .isEmpty();
    }

    /** The declaration line of a member, on the sources the classes above were modelled on, as kotlinc, scalac and groovyc read them. */
    @Test
    void aPropertysLineIsItsMembersDeclarationInEachLanguage() {
        String kotlin = """
                package demo.kt

                import com.fasterxml.jackson.annotation.JsonProperty

                // telephone: the card's phone
                data class OwnerCard(
                    val firstName: String,
                    @JsonProperty("tel") val telephone: String,
                    val isActive: Boolean,
                    val pets: List<PetCard>,
                )

                data class PetCard(val name: String)
                """;
        assertThat(JsonAnchors.declarationLine(kotlin, "telephone", false, 6)).isEqualTo(8);
        assertThat(JsonAnchors.declarationLine(kotlin, "pets", false, 6)).isEqualTo(10);
        assertThat(JsonAnchors.declarationLine(kotlin, "name", false, 13)).isEqualTo(13);
        assertThat(JsonAnchors.declarationLine(kotlin, "name", false, 6))
                .as("the next class's member is not this one's")
                .isEqualTo(6);
        String scala = """
                package demo.sc

                case class VetCard(name: String, @JsonProperty("years") experience: Int)
                """;
        assertThat(JsonAnchors.declarationLine(scala, "experience", false, 3)).isEqualTo(3);
        String groovy = """
                class VisitCard {
                    String description
                    @JsonProperty('when')
                    String time
                }
                """;
        assertThat(JsonAnchors.declarationLine(groovy, "time", false, 1)).isEqualTo(4);
        String java = """
                public class PetDto {

                    public PetDto(String type) {
                        this.type = type;
                    }

                    private String type;

                    public String getKind() {
                        return type;
                    }
                }
                """;
        assertThat(JsonAnchors.declarationLine(java, "type", false, 1))
                .as("a constructor's parameter of the name is not the field")
                .isEqualTo(7);
        assertThat(JsonAnchors.declarationLine(java, "getKind", true, 1)).isEqualTo(9);
        assertThat(JsonAnchors.declarationLine(java, "missing", false, 1)).isEqualTo(1);
    }
}
