package fx.parallel;

import java.util.List;

class Ok {
    List<Integer> lengths(List<String> names) {
        return names.stream().map(String::length).toList();
    }
}
