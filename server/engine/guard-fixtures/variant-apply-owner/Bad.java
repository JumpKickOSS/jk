package fx.variantapply;

import cc.jumpkick.plugin.manifest.VariantApply;

/** A forecast folding the variant overlay itself: a manifest of its own, keyed apart from the build's. */
class Bad {
    Object forecastManifest(Object parsed, Object dir) {
        return VariantApply.apply(parsed, dir);
    }
}
