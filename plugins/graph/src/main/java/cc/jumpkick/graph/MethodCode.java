// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.Signature;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One method's instructions, with the local variables' types where the class file names them. */
final class MethodCode {
    final List<Instruction> instructions = new ArrayList<>();
    final List<Integer> bcis = new ArrayList<>();
    final List<Local> locals = new ArrayList<>();

    record Local(int slot, int from, int to, Signature type) {}

    static @Nullable MethodCode of(MethodModel m) {
        var attr = m.code().orElse(null);
        if (attr == null) return null;
        MethodCode c = new MethodCode();
        Map<Label, Integer> labels = new HashMap<>();
        List<CodeElement> debug = new ArrayList<>();
        int bci = 0;
        for (CodeElement e : attr) {
            if (e instanceof LabelTarget t) {
                labels.put(t.label(), bci);
            } else if (e instanceof Instruction ins) {
                c.instructions.add(ins);
                c.bcis.add(bci);
                bci += ins.sizeInBytes();
            } else if (e instanceof LocalVariable || e instanceof LocalVariableType) {
                debug.add(e);
            }
        }
        for (CodeElement e : debug) {
            if (e instanceof LocalVariableType t) {
                c.locals.add(new Local(
                        t.slot(),
                        labels.getOrDefault(t.startScope(), 0),
                        labels.getOrDefault(t.endScope(), Integer.MAX_VALUE),
                        Signature.parseFrom(t.signature().stringValue())));
            }
        }
        for (CodeElement e : debug) {
            if (e instanceof LocalVariable v) {
                int from = labels.getOrDefault(v.startScope(), 0);
                boolean typed = c.locals.stream().anyMatch(l -> l.slot() == v.slot() && l.from() == from);
                if (!typed) {
                    c.locals.add(new Local(
                            v.slot(),
                            from,
                            labels.getOrDefault(v.endScope(), Integer.MAX_VALUE),
                            Signature.of(v.typeSymbol())));
                }
            }
        }
        return c;
    }

    /** The class names of the value the instruction before {@code i} leaves on the stack; empty when that is not read. */
    List<String> typeBefore(int i) {
        Instruction v = instructions.get(i - 1);
        int bci = bcis.get(i - 1);
        if (v instanceof LoadInstruction load) {
            for (Local l : locals) {
                if (l.slot() == load.slot() && l.from() <= bci + load.sizeInBytes() && bci < l.to())
                    return JsonDtos.names(l.type());
            }
            return List.of();
        }
        if (v instanceof TypeCheckInstruction t && t.opcode() == Opcode.CHECKCAST)
            return List.of(t.type().asInternalName());
        if (v instanceof FieldInstruction f) return JsonDtos.names(Signature.of(f.typeSymbol()));
        if (v instanceof InvokeInstruction inv) {
            if (inv.name().equalsString("<init>")) return List.of(inv.owner().asInternalName());
            ClassDesc r = inv.typeSymbol().returnType();
            return r.isPrimitive() ? List.of() : JsonDtos.names(Signature.of(r));
        }
        return List.of();
    }
}
