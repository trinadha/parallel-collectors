package com.pivovarit.collectors.inParallelToSet;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.generator.InRange;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;
import com.pivovarit.collectors.ExecutorAwareTest;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static com.pivovarit.collectors.ParallelCollectors.inParallelToSet;
import static com.pivovarit.collectors.ParallelCollectors.supplier;
import static com.pivovarit.collectors.TimeUtils.returnWithDelay;
import static com.pivovarit.collectors.TimeUtils.timed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * @author Grzegorz Piwowarek
 */
@RunWith(JUnitQuickcheck.class)
public class ToSetParallelismThrottlingBDDTest extends ExecutorAwareTest {

    private static final long BLOCKING_MILLIS = 50;
    private static final long CONSTANT_DELAY = 100;

    @Property(trials = 10)
    public void shouldCollectToSetWithThrottledParallelism(@InRange(minInt = 2, maxInt = 20) int unitsOfWork, @InRange(minInt = 1, maxInt = 40) int parallelism) {
        // given
        executor = threadPoolExecutor(unitsOfWork);
        long expectedDuration = expectedDuration(parallelism, unitsOfWork);
        Map.Entry<Set<Long>, Long> result = timed(collectWith(inParallelToSet(executor, parallelism), unitsOfWork));

        assertThat(result)
          .satisfies(e -> {
              assertThat(e.getValue())
                .isGreaterThanOrEqualTo(expectedDuration)
                .isCloseTo(expectedDuration, offset(CONSTANT_DELAY));

              assertThat(e.getKey()).hasSize(1);
          });
    }

    private static long expectedDuration(long parallelism, long unitsOfWork) {
        if (unitsOfWork < parallelism) {
            return BLOCKING_MILLIS;
        } else if (unitsOfWork % parallelism == 0) {
            return (unitsOfWork / parallelism) * BLOCKING_MILLIS;
        } else {
            return (unitsOfWork / parallelism + 1) * BLOCKING_MILLIS;
        }
    }

    private static <T, R extends Collection<T>> Supplier<R> collectWith(Collector<Supplier<Long>, List<CompletableFuture<T>>, CompletableFuture<R>> collector, int unitsOfWork) {
        return () -> Stream.generate(() -> supplier(() -> returnWithDelay(42L, Duration.ofMillis(BLOCKING_MILLIS))))
          .limit(unitsOfWork)
          .collect(collector)
          .join();
    }
}