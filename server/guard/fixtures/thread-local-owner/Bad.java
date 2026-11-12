package fx.tl;

import java.util.HashSet;
import java.util.Set;

class Bad {
    private final ThreadLocal<Set<String>> building = ThreadLocal.withInitial(HashSet::new);

    boolean enter(String pom) {
        return building.get().add(pom);
    }
}
