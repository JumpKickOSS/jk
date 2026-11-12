package fx.parallel;

import java.util.List;

class Bad {
    List<Integer> lengths(List<String> names) {
        return names.parallelStream().map(String::length).toList();
    }
}
