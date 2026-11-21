// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Types named only as type arguments: erased from every descriptor, kept in the Signature attributes. */
public final class Generics<T extends Generics.Bound> implements Supplier<List<Generics.Supplied>> {

    public interface Bound {}

    public interface Supplied {}

    public interface FieldArg {}

    public interface ParamArg {}

    public interface ReturnArg {}

    public interface LocalArg {}

    public interface InnerArg {}

    public static final class Outer<X> {
        public final class Inner {}
    }

    private final List<FieldArg> field = new ArrayList<>();

    private Outer<InnerArg>.@Nullable Inner inner;

    Optional<ReturnArg> method(Map<String, ParamArg> in) {
        return Optional.empty();
    }

    int local() {
        List<LocalArg> list = new ArrayList<>();
        return list.size() + field.size();
    }

    @Override
    public List<Supplied> get() {
        return List.of();
    }

    /** An annotation only a record component can carry: no field or accessor keeps it. */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface OnComponent {}

    public interface ComponentArg {}

    public record Component(@OnComponent String name, List<ComponentArg> args) {}
}
