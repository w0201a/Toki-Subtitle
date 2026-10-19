package io.github.meiyongai.toki.subtitle.hook;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Parallel read-only method analysis; DEX buffers remain shared and publication stays serial. */
public final class ScanWorkers {
    private ScanWorkers() {}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    public static int recommended() {
        Runtime runtime=Runtime.getRuntime();
        long headroom=runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory();
        return Math.max(1,Math.min(Math.min(8,runtime.availableProcessors()),(int)Math.max(1,headroom/(16L*1024*1024))));
    }
    public static Scope open() { return open(recommended()); }
    static Scope open(int workers) {
        Scope previous=CURRENT.get();
        Scope scope=new Scope(Math.max(1,Math.min(8,workers)),previous);
        CURRENT.set(scope);return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final int workers;
        private final Scope previous;
        private final ExecutorService executor;
        private Scope(int workers,Scope previous) {
            this.workers=workers;this.previous=previous;
            java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
            executor=workers>1?Executors.newFixedThreadPool(workers,task->{
                Thread thread=new Thread(task,"TokiDexWorker-"+sequence.incrementAndGet());
                thread.setPriority(Thread.NORM_PRIORITY-1);thread.setDaemon(true);return thread;
            }):null;
        }
        public int workers() { return workers; }
        public void close() { CURRENT.set(previous);if(executor!=null) executor.shutdown(); }
    }
    static <T,R> List<R> map(List<T> values,Function<T,R> action) {
        Scope scope=CURRENT.get();
        if(scope==null || scope.workers==1 || values.size()<32) {
            List<R> result=new ArrayList<>(values.size());for(T value:values) result.add(action.apply(value));return result;
        }
        int count=Math.min(scope.workers,Math.max(2,values.size()/16));
        List<Callable<List<R>>> tasks=new ArrayList<>();
        for(int index=0;index<count;index++) {
            int from=index*values.size()/count,to=(index+1)*values.size()/count;
            tasks.add(()->{List<R> part=new ArrayList<>(to-from);for(int i=from;i<to;i++) part.add(action.apply(values.get(i)));return part;});
        }
        try {
            List<R> result=new ArrayList<>(values.size());
            for(Future<List<R>> part:scope.executor.invokeAll(tasks)) result.addAll(part.get());
            return result;
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("Analysis interrupted",error);}
        catch(ExecutionException error) {
            Throwable cause=error.getCause();if(cause instanceof Error fatal) throw fatal;if(cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }
}
