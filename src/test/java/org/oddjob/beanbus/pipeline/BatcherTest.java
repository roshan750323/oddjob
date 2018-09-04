package org.oddjob.beanbus.pipeline;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class BatcherTest {

    @Test
    public void testStandalone() {

        WireTap<Collection<? extends Integer>> results = new WireTap<>();

        Batcher<Integer> test = new Batcher<>(results, 2);

        test.accept(1);
        test.accept(2);
        test.accept(3);
        test.accept(4);
        test.accept(5);

        test.flush();

        List<List<? extends Integer>> resultLists = results.toCollection()
                .stream()
                .map( c -> c.stream()
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());

        assertThat( resultLists.size(), is(3));
        assertThat( resultLists.get(0), is(Arrays.asList(1,2)));
        assertThat( resultLists.get(1), is(Arrays.asList(3,4)));
        assertThat( resultLists.get(2), is(Arrays.asList(5)));
    }

    public void testBatchingToSuperType() {

        WireTap<Collection<? extends Number>> numbers = new WireTap<>();

        WireTap<Collection<Integer>> integers = new WireTap<>();

        Batcher<Integer> batcher = new Batcher<Integer>(integers, 2);
    }


    @Test
    public void testBatchingInPipeline() {

        int sampleSize = 10_001;
        int batchSize = 10;
        int expectedBatches = 1_001;

        ExecutorService executor = Executors.newFixedThreadPool(3);

        AsyncPipeline<Integer> pipeline = new AsyncPipeline<Integer>(executor);

        WireTap<Collection<Integer>> results = new WireTap<>();

        FlushableConsumer<Collection<Integer>> resultSection = pipeline.createSection(results);

        Batcher<Integer> test = new Batcher<>(resultSection, batchSize);

        FlushableConsumer<Integer> start = pipeline.openWith(test);

        for (int i = 0; i < sampleSize; ++i) {
            start.accept(i);
        }

        start.flush();

        List<List<? extends Integer>> resultLists = results.toCollection()
                .stream()
                .map( c -> c.stream()
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());

        assertThat( resultLists.size(), is(expectedBatches));

        int fullBatches = 0;
        int partialBatches = 0;

        for ( List<? extends Integer> batch : resultLists) {

        }
        assertThat( resultLists.get(0).size(), is(batchSize));
        assertThat( resultLists.get(expectedBatches-1).size(), is(1));

        executor.shutdown();
    }

}
