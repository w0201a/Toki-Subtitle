package io.github.meiyongai.toki.subtitle.hook;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ScanWorkersTest {
    @Test public void methodAnalysisActuallyRunsOnSeveralWorkersAndPreservesOrder() {
        Set<String> threads=ConcurrentHashMap.newKeySet();
        List<Integer> input=new ArrayList<>();for(int i=0;i<64;i++) input.add(i);
        CyclicBarrier barrier=new CyclicBarrier(4);
        try(var scope=ScanWorkers.open(4)) {
            List<Integer> output=ScanWorkers.map(input,value->{
                threads.add(Thread.currentThread().getName());
                if(value%16==0) try {barrier.await(5,TimeUnit.SECONDS);} catch(Exception error) {throw new IllegalStateException(error);}
                return value*2;
            });
            assertEquals(4,threads.size());
            for(int i=0;i<64;i++) assertEquals(Integer.valueOf(i*2),output.get(i));
        }
    }
    @Test public void workerExceptionsAreNotConvertedIntoSuccessfulResults() {
        List<Integer> input=new ArrayList<>();for(int i=0;i<64;i++) input.add(i);
        try(var scope=ScanWorkers.open(2)) {
            assertThrows(IllegalStateException.class,()->ScanWorkers.map(input,value->{throw new IllegalStateException("worker failed");}));
        }
        assertEquals(List.of(2),ScanWorkers.map(List.of(1),value->value+1));
    }
}
