// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * <strong>The</strong> precedence every machine-scoped setting follows — built-in default, then
 * {@code ~/.jk/config.toml}, then the environment, highest layer first — together with the
 * one thing that makes the layers agree: <em>a single validity predicate, applied identically to
 * every layer</em>. A value the predicate rejects falls through to the layer below; it never fails
 * the command, because a machine preference must not be able to stop a build.
 *
 * <p>Six config records wrote that out by hand, once per field, which meant writing the predicate
 * twice per field — once where the env value is read and once where the file value is read. Nothing
 * connected the two spellings, so a field could (and a field eventually will) validate one layer and
 * not the other, and the invalid value would win precisely because it came from the higher layer.
 * Declaring the field's default and predicate once, as a constant, is what removes that seam:
 *
 * <pre>{@code
 * private static final MachineConfig<Integer> PORT =
 *         MachineConfig.of(DEFAULT_PORT, p -> p >= 0 && p <= 65535);
 * ...
 * PORT.layer(EnvValues.intValue(env, "JK_HTTP_PORT").orElse(null), tomlInt(http, "port"))
 * }</pre>
 *
 * <p>A layer is a {@code @Nullable T}, not an {@code Optional<T>}: {@code Optional} is a return
 * type, and a reader's {@code Optional} ends at the {@code .orElse(null)} that hands the value here.
 * {@code null} means "this layer said nothing", and — on purpose — is indistinguishable from "this
 * layer said something the rule rejects".
 *
 * <p><strong>What this does not own: the read.</strong> Which substrate a layer comes from —
 * {@link TomlScan} for machine config, tomlj for manifest config, {@link EnvValues} for the
 * environment, {@link System#getProperty} for the JVM overrides {@link JkM2Config} honours — stays
 * at the call site, because that split is deliberate and a reader keeps its own policy. This owns
 * the order the layers are consulted in and the rule that judges them. A caller with an extra layer
 * (a legacy env spelling, a system property) passes it as one more argument in rank order rather
 * than reimplementing the fall-through.
 *
 * <p>The built-in is not passed through the predicate: it is the code's own constant, and a default
 * the code declares invalid is a bug in the code, not a value to fall through from.
 *
 * @param builtIn the value when no layer supplies a valid one
 * @param valid judges every layer alike; {@link #any()} for fields whose decode is their validation
 * @param <T> the setting's type
 */
public record MachineConfig<T extends @Nullable Object>(T builtIn, Predicate<T> valid) {

    /** A setting whose only validity rule is that the layer decoded at all. */
    public static <T extends @Nullable Object> MachineConfig<T> of(T builtIn) {
        return new MachineConfig<>(builtIn, any());
    }

    /** A setting with a range rule ({@code port <= 65535}, {@code size > 0}, …). */
    public static <T extends @Nullable Object> MachineConfig<T> of(T builtIn, Predicate<T> valid) {
        return new MachineConfig<>(builtIn, valid);
    }

    /** Accepts anything that decoded — for booleans and free strings, where parsing is the check. */
    public static <T extends @Nullable Object> Predicate<T> any() {
        return v -> true;
    }

    /**
     * The value: the first of {@code highestFirst} that is set and valid, else {@link #builtIn}.
     * Layers are listed in precedence order, highest first — env before file, and a preferred env
     * spelling before its legacy alias.
     */
    @SafeVarargs
    public final T layer(@Nullable T... highestFirst) {
        return layerOver(builtIn, highestFirst);
    }

    /**
     * As {@link #layer} against a floor other than {@link #builtIn}: a default this host computes at
     * resolve time ({@link JkCacheConfig}'s CI-aware, disk-clamped budget), or a lower layer some
     * other reader already resolved. The floor is trusted for the same reason {@link #builtIn} is.
     */
    @SafeVarargs
    public final T layerOver(T floor, @Nullable T... highestFirst) {
        for (T candidate : highestFirst) {
            if (candidate != null && valid.test(candidate)) return candidate;
        }
        return floor;
    }

    /**
     * One layer's raw read, judged by this setting's rule and nothing else — for a caller that must
     * keep the layer separate (a legacy spelling it has to convert, a "was it set at all?" question)
     * rather than collapse it here. A rejected value comes back {@code null}, so it ranks exactly
     * like an absent one.
     */
    public @Nullable T accept(@Nullable T read) {
        return read != null && valid.test(read) ? read : null;
    }
}
