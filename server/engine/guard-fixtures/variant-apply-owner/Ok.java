package fx.variantapply;

import cc.jumpkick.plugin.manifest.VariantApply;

/** A non-build consumer's defaults read, which never keys a step. */
class Ok {
    Object defaults(Object parsed, Object dir) {
        return VariantApply.applyDefaults(parsed, dir);
    }
}
