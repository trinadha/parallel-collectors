package com.pivovarit.collectors;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static com.pivovarit.collectors.BatchingStream.batching;
import static com.pivovarit.collectors.BatchingStream.partitioned;
import static com.pivovarit.collectors.Dispatcher.getDefaultParallelism;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * @author Grzegorz Piwowarek
 */
final class AsyncParallelCollector<T, R, C>
  implements Collector<T, Stream.Builder<CompletableFuture<R>>, CompletableFuture<C>> {

    private final Dispatcher<R> dispatcher;
    private final Function<T, R> mapper;
    private final Function<Stream<R>, C> processor;

    private final CompletableFuture<C> result = new CompletableFuture<>();

    private AsyncParallelCollector(
      Function<T, R> mapper,
      Dispatcher<R> dispatcher,
      Function<Stream<R>, C> processor) {
        this.dispatcher = dispatcher;
        this.processor = processor;
        this.mapper = mapper;
    }

    @Override
    public Supplier<Stream.Builder<CompletableFuture<R>>> supplier() {
        return Stream::builder;
    }

    @Override
    public BinaryOperator<Stream.Builder<CompletableFuture<R>>> combiner() {
        return (left, right) -> {
            throw new UnsupportedOperationException();
        };
    }

    @Override
    public BiConsumer<Stream.Builder<CompletableFuture<R>>, T> accumulator() {
        return (acc, e) -> {
            if (!dispatcher.isRunning()) {
                dispatcher.start();
            }
            acc.add(dispatcher.enqueue(() -> mapper.apply(e)));
        };
    }

    @Override
    public Function<Stream.Builder<CompletableFuture<R>>, CompletableFuture<C>> finisher() {
        return futures -> {
            dispatcher.stop();

            return toCombined(futures.build())
              .thenApply(processor)
              .handle((c, ex) -> ex == null ? result.complete(c) : result.completeExceptionally(ex))
              .thenCompose(__ -> result);
        };
    }

    @Override
    public Set<Characteristics> characteristics() {
        return Collections.emptySet();
    }

    private static <T> CompletableFuture<Stream<T>> toCombined(Stream<CompletableFuture<T>> futures) {
        CompletableFuture<T>[] futuresArray = futures.toArray(CompletableFuture[]::new);
        CompletableFuture<Stream<T>> combined = allOf(futuresArray)
          .thenApply(__ -> Arrays.stream(futuresArray)
            .map(CompletableFuture::join));

        for (CompletableFuture<?> f : futuresArray) {
            f.exceptionally(ex -> {
                combined.completeExceptionally(ex);
                return null;
            });
        }

        return combined;
    }

    static <T, R> Collector<T, ?, CompletableFuture<Stream<R>>> collectingToStream(Function<T, R> mapper, Executor executor) {
        return collectingToStream(mapper, executor, getDefaultParallelism());
    }

    static <T, R> Collector<T, ?, CompletableFuture<Stream<R>>> collectingToStream(Function<T, R> mapper, Executor executor, int parallelism) {
        requireNonNull(executor, "executor can't be null");
        requireNonNull(mapper, "mapper can't be null");
        requireValidParallelism(parallelism);

        return parallelism == 1
          ? asyncCollector(mapper, executor, i -> i)
          : new AsyncParallelCollector<>(mapper, Dispatcher.limiting(executor, parallelism), t -> t);
    }

    static <T, R, RR> Collector<T, ?, CompletableFuture<RR>> collectingWithCollector(Collector<R, ?, RR> collector, Function<T, R> mapper, Executor executor) {
        return collectingWithCollector(collector, mapper, executor, getDefaultParallelism());
    }

    static <T, R, RR> Collector<T, ?, CompletableFuture<RR>> collectingWithCollector(Collector<R, ?, RR> collector, Function<T, R> mapper, Executor executor, int parallelism) {
        requireNonNull(collector, "collector can't be null");
        requireNonNull(executor, "executor can't be null");
        requireNonNull(mapper, "mapper can't be null");
        requireValidParallelism(parallelism);

        return parallelism == 1
          ? asyncCollector(mapper, executor, s -> s.collect(collector))
          : new AsyncParallelCollector<>(mapper, Dispatcher.limiting(executor, parallelism), s -> s.collect(collector));
    }

    static void requireValidParallelism(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("Parallelism can't be lower than 1");
        }
    }

    static <T, R, RR> Collector<T, ?, CompletableFuture<RR>> asyncCollector(Function<T, R> mapper, Executor executor, Function<Stream<R>, RR> finisher) {
        return collectingAndThen(toList(), list -> supplyAsync(() -> {
            Stream.Builder<R> acc = Stream.builder();
            for (T t : list) {
                acc.add(mapper.apply(t));
            }
            return finisher.apply(acc.build());
        }, executor));
    }

    static final class BatchingCollectors {

        private BatchingCollectors() {
        }

        static <T, R, RR> Collector<T, ?, CompletableFuture<RR>> collectingWithCollector(Collector<R, ?, RR> collector, Function<T, R> mapper, Executor executor, int parallelism) {
            requireNonNull(collector, "collector can't be null");
            requireNonNull(executor, "executor can't be null");
            requireNonNull(mapper, "mapper can't be null");
            requireValidParallelism(parallelism);

            return parallelism == 1
              ? asyncCollector(mapper, executor, s -> s.collect(collector))
              : batchingCollector(mapper, executor, parallelism, s -> s.collect(collector));
        }

        static <T, R> Collector<T, ?, CompletableFuture<Stream<R>>> collectingToStream(
          Function<T, R> mapper,
          Executor executor, int parallelism) {
            requireNonNull(executor, "executor can't be null");
            requireNonNull(mapper, "mapper can't be null");
            requireValidParallelism(parallelism);

            return parallelism == 1
              ? asyncCollector(mapper, executor, i -> i)
              : batchingCollector(mapper, executor, parallelism, s -> s);
        }

        private static <T, R, RR> Collector<T, ?, CompletableFuture<RR>> batchingCollector(Function<T, R> mapper, Executor executor, int parallelism, Function<Stream<R>, RR> finisher) {
            return collectingAndThen(
              toList(),
              list -> partitioned(list, parallelism).collect(
                new AsyncParallelCollector<>(batching(mapper), Dispatcher.of(executor),
                  listStream -> finisher.apply(listStream.flatMap(Collection::stream)))));
        }
    }
}