// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Everything a rule may ask about one class file.
 *
 * @param name internal name, {@code cc/jumpkick/host/Hashing}
 * @param typeRefs every other internal name the class refers to (supers, members, instructions,
 *     annotations) — the package-edge source
 * @param sourceFile the {@code SourceFile} attribute, when compiled with debug info
 */
public record ClassFacts(
        String name,
        int access,
        @Nullable String superName,
        List<String> interfaces,
        @Nullable String sourceFile,
        List<AnnotationFacts> annotations,
        List<FieldFacts> fields,
        List<MethodFacts> methods,
        Set<String> typeRefs) {

    public ClassFacts {
        interfaces = List.copyOf(interfaces);
        annotations = List.copyOf(annotations);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        typeRefs = Set.copyOf(new TreeSet<>(typeRefs));
    }

    public String binaryName() {
        return Descriptors.binaryName(name);
    }

    public String packageName() {
        return Descriptors.packageOf(name);
    }

    public boolean isPackageInfo() {
        return name.endsWith("/package-info") || name.equals("package-info");
    }

    public boolean isNested() {
        return name.indexOf('$') >= 0;
    }

    public boolean hasAnnotation(String binaryTypeName) {
        for (AnnotationFacts a : annotations) if (a.typeName().equals(binaryTypeName)) return true;
        return false;
    }

    public boolean hasFlag(int accessFlag) {
        return (access & accessFlag) != 0;
    }
}
