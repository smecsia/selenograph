package ru.qatools.selenograph.util;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * @author Ilya Sadykov
 */
public class StreamUtil {
    public static <T, U, A, R>
    Collector<T, ?, R> flatMapping(Function<? super T, ? extends Stream<? extends U>> mapper,
                                   Collector<? super U, A, R> downstream) {
        BiConsumer<A, ? super U> downstreamAccumulator = downstream.accumulator();
        return Collector.of(downstream.supplier(),
                (r, t) -> {
                    try (Stream<? extends U> result = mapper.apply(t)) {
                        if (result != null)
                            result.sequential().forEach(u -> downstreamAccumulator.accept(r, u));
                    }
                },
                downstream.combiner(), downstream.finisher(),
                downstream.characteristics().toArray(new Collector.Characteristics[0]));
    }
}
