package $package$;

import io.helidon.common.Weight;
import io.helidon.dbclient.DbMapper;
import io.helidon.dbclient.spi.DbMapperProvider;
import java.util.Optional;

/**
 * {@link java.util.ServiceLoader} provider implementation for DB mapper provider.
 */
@Weight(100)
public class PokemonMapperProvider implements DbMapperProvider {
    private static final PokemonMapper MAPPER = new PokemonMapper();

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<DbMapper<T>> mapper(Class<T> type) {
        if (type.equals(Pokemon.class)) {
            return Optional.of((DbMapper<T>) MAPPER);
        }
        return Optional.empty();
    }
}
