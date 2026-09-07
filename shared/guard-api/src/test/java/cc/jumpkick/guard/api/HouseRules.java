// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import cc.jumpkick.guard.api.archunit.JkArchUnit;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Disabled;

/** PRD §4.3's examples, verbatim: they must compile against the library as published. */
@Disabled("compile-only: a guard suite runs under jk, not under the library's own tests")
@GuardSuite(scope = Scope.WORKSPACE) // or Scope.MODULE — decides lane and cache key
final class HouseRules {

    @Guard(
            id = "one-json-codec",
            why = "two escapers that agree today disagree after one bug fix",
            instead = "Jsonl.quote / Jsonl.parse")
    void oneJsonCodec(Facts facts, Text text, Violations v) { // G21, exemption by spec
        for (CallSite s : facts.calls(Sig.of("java.lang.String#replace(**)"))) {
            if (s.origin().inPackage("cc.jumpkick.jsonl")) continue;
            String body = text.blanked(s.origin().sourceFile(), Blank.COMMENTS);
            if (body.contains("\":") && s.nearbyLiteral("\\\"")) // the JSON-object shape
            v.add(s, "an escaper beside a \"key\": literal is a JSON writer");
        }
    }

    @Guard(id = "tier-partition", why = "a tag no tier runs is a test that never fails")
    void tierPartition(Model model, Facts facts, Violations v) { // G23, exhaustiveness
        Set<String> vocab = model.tiers().tagVocabulary();
        for (Set<String> subset : Sets.powerSet(vocab))
            if (model.tiers().running(subset).size() != 1)
                v.add(
                        model.tiers(),
                        "subset " + subset + " runs in " + model.tiers().running(subset));
        for (TaggedClass t : facts.testClasses())
            if (!vocab.containsAll(t.tags())) v.add(t, "tag not in the vocabulary");
    }

    @Guard(id = "onion", why = "domain never sees adapters")
    void archUnit(Facts facts, Violations v) { // ArchUnit, unchanged
        JavaClasses classes = new ClassFileImporter().importPaths(facts.classDirs());
        JkArchUnit.check(onionArchitecture().domainModels("..domain.model..").adapter("web", "..web.."), classes, v);
    }
}
