// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Typed key for a value in a BuildPlan's shared state.
 */
public final class BuildPlanKey<T> {

    private enum Shape {
        SCALAR,
        LIST,
        MAP
    }

    private final String name;
    private final Shape shape;
    private final Class<?> firstType;
    private final @Nullable Class<?> secondType;

    private BuildPlanKey(String name, Shape shape, Class<?> firstType, @Nullable Class<?> secondType) {
        this.name = Objects.requireNonNull(name, "name");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.firstType = Objects.requireNonNull(firstType, "firstType");
        this.secondType = secondType;
    }

    public static <T> BuildPlanKey<T> scalar(String name, Class<T> type) {
        return new BuildPlanKey<>(name, Shape.SCALAR, type, null);
    }

    public static <E> BuildPlanKey<List<E>> list(String name, Class<E> elementType) {
        return new BuildPlanKey<>(name, Shape.LIST, elementType, null);
    }

    public static <K, V> BuildPlanKey<Map<K, V>> map(String name, Class<K> keyType, Class<V> valueType) {
        return new BuildPlanKey<>(name, Shape.MAP, keyType, Objects.requireNonNull(valueType, "valueType"));
    }

    public String name() {
        return name;
    }

    String description() {
        return switch (shape) {
            case SCALAR -> firstType.getTypeName();
            case LIST -> "list<" + firstType.getTypeName() + ">";
            case MAP ->
                "map<" + firstType.getTypeName() + ", "
                        + Objects.requireNonNull(secondType).getTypeName() + ">";
        };
    }

    boolean sameType(BuildPlanKey<?> other) {
        return shape == other.shape
                && firstType.equals(other.firstType)
                && Objects.equals(secondType, other.secondType);
    }

    /**
     * Validate and cast a state value using this key's complete runtime descriptor.
     */
    @SuppressWarnings("unchecked")
    public T cast(Object value) {
        validate(name, value);
        return (T) value;
    }

    private void validate(String slotName, Object value) {
        switch (shape) {
            case SCALAR -> requireInstance(slotName, "value", firstType, value);
            case LIST -> {
                if (!(value instanceof List<?> list)) {
                    throw mismatch(slotName, description(), actualType(value));
                }
                for (int i = 0; i < list.size(); i++) {
                    requireInstance(slotName, "element [" + i + "]", firstType, list.get(i));
                }
            }
            case MAP -> {
                if (!(value instanceof Map<?, ?> map)) {
                    throw mismatch(slotName, description(), actualType(value));
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireInstance(slotName, "map key", firstType, entry.getKey());
                    requireInstance(
                            slotName,
                            "map value for key '" + entry.getKey() + "'",
                            Objects.requireNonNull(secondType),
                            entry.getValue());
                }
            }
        }
    }

    private static void requireInstance(String slotName, String location, Class<?> expected, Object value) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException("plan state '"
                    + slotName
                    + "' "
                    + location
                    + " expected "
                    + expected.getTypeName()
                    + " but was "
                    + actualType(value));
        }
    }

    private static IllegalArgumentException mismatch(String slotName, String expected, String actual) {
        return new IllegalArgumentException(
                "plan state '" + slotName + "' expected " + expected + " but was " + actual);
    }

    private static String actualType(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getTypeName();
    }

    @Override
    public String toString() {
        return name + ":" + description();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof BuildPlanKey<?> key && name.equals(key.name) && sameType(key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, shape, firstType, secondType);
    }
}
