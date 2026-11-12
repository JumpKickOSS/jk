package fx.modulelanguages;

import cc.jumpkick.layout.Languages;

/** Deciding javac from the raw source languages: misses the javac a Kotlin module's processors need. */
class Bad {
    boolean runsJavac(Object project, Object dir) {
        return Languages.resolve(project, dir).java();
    }
}
