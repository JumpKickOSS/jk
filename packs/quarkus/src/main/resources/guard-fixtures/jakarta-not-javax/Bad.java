package fx.javax;

import javax.persistence.Persistence;

class Bad {
    Object factory() {
        return Persistence.createEntityManagerFactory("demo");
    }
}
