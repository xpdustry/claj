package com.xpdustry.claj.server.util;

import java.util.concurrent.atomic.*;

//TODO: add a system like LinkedTranfertQueue, to directly run the posted task when possible
/**
 * Taken from arc-lite.
 * <p>
 * A lock-less thread-safe allocation-free unbounded queue. Allocation is only done when growing.
 * <p>
 * Why? ... Because it's funny having an overkill thing XD.
 */
public class TaskQueue{
    // Not final for testing
    protected static /*final*/ int CHUNK = 4096;

    protected static class Chunk{
        final AtomicInteger claim = new AtomicInteger();
        final AtomicReference<Chunk> next = new AtomicReference<>();
        final AtomicReferenceArray<Runnable> slots = new AtomicReferenceArray<>(CHUNK);
    }

    protected final AtomicReference<Chunk> producer;
    protected Chunk consumer;
    protected int index;
    protected final AtomicInteger size = new AtomicInteger();
    protected final AtomicBoolean draining = new AtomicBoolean();

    public TaskQueue(){
        Chunk first = new Chunk();
        producer = new AtomicReference<>(first);
        consumer = first;
    }

    /** @return whether the queue was empty. */
    public boolean post(Runnable task){
        for(;;){
            Chunk chunk = producer.get();
            int offset = chunk.claim.getAndIncrement();
            if(offset < CHUNK){
                // Counted before publish, so size stays >= 0
                boolean wasEmpty = size.getAndIncrement() == 0;
                chunk.slots.set(offset, task);
                return wasEmpty;
            }

            // Chunk full: link/advance, then retry
            Chunk next = chunk.next.get();
            if(next == null){
                Chunk created = new Chunk();
                next = chunk.next.compareAndSet(null, created) ? created : chunk.next.get();
            }
            producer.compareAndSet(chunk, next);
        }
    }

    private Runnable poll(){
        Chunk chunk = consumer;
        if(index == CHUNK){
            Chunk next = chunk.next.get();
            if(next == null) return null;
            consumer = chunk = next;
            index = 0;
        }

        Runnable task = chunk.slots.get(index);
        if(task == null) return null; // Reserved but not yet written, or producer died before adding the task
        chunk.slots.lazySet(index, null);
        index++;
        return task;
    }

    public void run(){
        if(!draining.compareAndSet(false, true)) return;
        try{
            for(int n = size.get(); n > 0; n--){
                Runnable task = poll();
                if(task == null) break;
                size.decrementAndGet();
                task.run();
            }
        }finally{
            draining.set(false);
        }
    }

    public int size(){
        return size.get();
    }

    public void clear(){
        if(!draining.compareAndSet(false, true)) return;
        try{
            while(poll() != null) size.decrementAndGet();
        }finally{
            draining.set(false);
        }
    }
}
