package fx.modulelanguages;

import cc.jumpkick.layout.Languages;

/** A source probe that answers no compiler question. */
class Ok {
    boolean hasGroovy(Object dir) {
        return Languages.anySourceUnder(dir, ".groovy");
    }
}
