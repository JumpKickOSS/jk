// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The readers under JSON property anchors on real shapes of text: the properties Jackson writes for
 * a class (records, getters, Lombok, naming strategies, ignored members), the handler's body types,
 * jq programs, the keys of JSON and YAML documents, and the files a build's generators write.
 */
class JsonReadersTest {

    private static List<String> props(String source, String simple) {
        JsonDtos.Dto dto = JsonDtos.dto(JsonDtos.Source.of(source), simple);
        if (dto == null) throw new AssertionError(simple + " is not read as a class");
        return dto.properties().stream().map(p -> p.name() + ":" + p.line()).toList();
    }

    @Test
    void jacksonWritesGettersPublicFieldsAndRenamedOrNamedMembersOnly() {
        assertThat(props("""
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
                    String getPackagePrivate() { return ""; }
                    public void setNotes(String notes) {}
                    public String getURL() { return ""; }
                }
                """, "Visit"))
                .containsExactly("id:5", "description:6", "visit_date:9", "urgent:15", "notes:20", "url:21");
    }

    @Test
    void lombokAndTheNamingStrategyApplyToEveryNameNotGivenExplicitly() {
        assertThat(props("""
                @Data
                @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
                public class OwnerForm {
                    private String firstName;
                    @JsonProperty("tel")
                    private String telephoneNumber;
                    private List<PetForm> pets = new ArrayList<>() {{ add(new PetForm()); }};
                }
                """, "OwnerForm")).containsExactly("first_name:4", "tel:6", "pets:7");
        assertThat(JsonDtos.translate("homeAddressLine", "KebabCaseStrategy")).isEqualTo("home-address-line");
        assertThat(JsonDtos.translate("id", "UpperCamelCaseStrategy")).isEqualTo("Id");
    }

    @Test
    void aHandlersBodyTypesAreItsReturnAndItsRequestBodyThroughLibraryWrappers() {
        JsonDtos.Source src = JsonDtos.Source.of("""
                @RestController
                class OwnerApi {
                    @GetMapping("/owners")
                    public ResponseEntity<List<OwnerDto>> list(@RequestParam String q) { return null; }

                    @PutMapping("/owners/{id}")
                    public void update(@PathVariable int id, @Valid @RequestBody final OwnerForm form) {}

                    private OwnerDto list(int notAHandler) { return null; }
                }
                """);
        assertThat(JsonDtos.bodyTypes(src, "list")).containsExactly("ResponseEntity", "List", "OwnerDto");
        assertThat(JsonDtos.bodyTypes(src, "update")).containsExactly("OwnerForm");
        JsonDtos.Source jaxRs = JsonDtos.Source.of("""
                import jakarta.ws.rs.POST;
                class Visits {
                    @POST
                    public Response add(@PathParam("id") int id, @Valid NewVisit visit) { return null; }
                }
                """);
        assertThat(JsonDtos.bodyTypes(jaxRs, "add")).containsExactly("Response", "NewVisit");
    }

    @Test
    void jqProgramsYieldTheFieldsTheyReadOnTheirLogicalLine() {
        List<JsonReaders.Use> uses = JsonReaders.jqUses("""
                id=$(curl -s "$BASE/api/owners" \\
                  | jq -r --arg n "George" '.[] | select(.firstName == $n) | .id')
                curl -s $BASE/api/vets | jq '.[].specialties[0].name, .["last-name"]' > vets.txt
                echo '.notJq'
                  curl -fsS "http://localhost:8080/api/owners/$owner/pets" | jq -r '.[] | "\\(.name)\\t\\(.birthDate)"'
                """);
        assertThat(uses.stream().map(u -> u.name() + "@" + u.block() + "-" + u.line()))
                .containsExactly(
                        "firstName@1-2",
                        "id@1-2",
                        "specialties@3-3",
                        "name@3-3",
                        "last-name@3-3",
                        "name@5-5",
                        "birthDate@5-5");
    }

    @Test
    void documentKeysCarryTheirPathAndLine() {
        assertThat(JsonReaders.jsonKeys("""
                {
                  "owner": {
                    "first_name": "Jean",
                    "pets": [ { "name": "Leo" } ]
                  }
                }
                """).stream()
                        .map(k -> String.join(".", k.path()) + ":" + k.name() + "@" + k.line()))
                .containsExactly(":owner@2", "owner:first_name@3", "owner:pets@4", "owner.pets.:name@4");
        assertThat(JsonReaders.yamlKeys("""
                paths:
                  /owners/{id}:
                    get:
                      description: |
                        not: a key
                      parameters:
                        - name: id
                          in: path
                """).stream()
                        .map(k -> String.join(".", k.path()) + ":" + k.name() + "@" + k.line()))
                .containsExactly(
                        ":paths@1",
                        "paths:/owners/{id}@2",
                        "paths./owners/{id}:get@3",
                        "paths./owners/{id}.get:description@4",
                        "paths./owners/{id}.get:parameters@6",
                        "paths./owners/{id}.get.parameters:name@7",
                        "paths./owners/{id}.get.parameters:in@8");
    }

    @Test
    void generatorsNamedByBuildFilesMarkTheirOutputAsGenerated() {
        List<String> gradle = JsonReaders.generatorOutputs("build.gradle.kts", "server", """
                plugins { id("org.springdoc.openapi-gradle-plugin") version "1.9.0" }
                openApi {
                    outputDir.set(file("$buildDir/docs"))
                    outputFileName.set("swagger.json")
                }
                """);
        assertThat(gradle).contains("*/swagger.json");
        List<String> npm = JsonReaders.generatorOutputs("package.json", "web", """
                { "scripts": { "api": "openapi-typescript ../openapi.yaml -o src/api/schema.ts" } }
                """);
        assertThat(npm).containsExactly("web/src/api/schema.ts");
        assertThat(JsonReaders.generatedBy("web/src/api/schema.ts", npm)).isTrue();
        assertThat(JsonReaders.generatedBy("web/src/api/client.ts", npm)).isFalse();
        assertThat(JsonReaders.generatedHeader(
                        "/* eslint-disable */\n// This file was auto-generated by openapi-typescript.\n"))
                .isTrue();
        assertThat(JsonAnchors.stem("IOwner")).isEqualTo("Owner");
        assertThat(JsonAnchors.stem("OwnerDto")).isEqualTo("Owner");
        assertThat(JsonAnchors.stem("Identity")).isEqualTo("Identity");
    }
}
