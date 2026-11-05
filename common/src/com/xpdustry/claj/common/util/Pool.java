package com.xpdustry.claj.common.util;

import java.util.concurrent.atomic.*;

import arc.func.Prov;
import arc.math.Mathf;
import arc.struct.Seq;

/**
 * Taken from arc-lite.
 * <p>
 * A pool of objects that can be reused to avoid allocation.
 * This is a bounded MPMC queue from the Vyukov algorithm.
 * The implementation is thread-safe, lock-less, and pool array is preallocated.
 *
 * @see Pools
 */
public class Pool<T> extends arc.util.pooling.Pool<T>{
    private final AtomicReferenceArray<T> pool;
    private final AtomicLongArray sequences;
    private final AtomicLong head, tail;
    private final Prov<T> newObject;
    private final int mask;
    /** The maximum number of objects that can be pooled. Always a power of two. */
    public final int capacity;
    /** The highest number of free objects. Can be reset any time. */
    public volatile int peak;

    /** Creates a pool with a maximum capacity of {@code 2048} objects. */
    public Pool(Prov<T> newObject){
        this(2048, newObject);
    }

    /** @param capacity The maximum pool capacity. Rounded up to a power of two. */
    public Pool(int capacity, Prov<T> newObject){
      this.capacity = capacity <= 1 ? 1 : Integer.highestOneBit(capacity - 1) << 1;
      this.mask = this.capacity - 1;
      this.newObject = newObject;
      this.head = new AtomicLong();
      this.tail = new AtomicLong();
      this.pool = new AtomicReferenceArray<>(this.capacity);
      this.sequences = new AtomicLongArray(this.capacity);
      for(int i = 0; i < this.capacity; i++) this.sequences.set(i, i);
    }

    @Override
    protected T newObject() {
      return newObject.get();
    }

    /**
     * Returns an object from this pool.
     * The object may be new (from {@link #newObject()}) or
     * reused (previously {@link #free(Object) freed}).
     */
    public T obtain(){
        T o = poll();
        return o == null ? newObject() : o;
    }

    /** Same as {@link #obtain()}, but returns {@code null} instead of allocating a new object if the pool is empty. */
    public T poll(){
        for(;;){
            long pos = head.get();
            int i = (int)pos & mask;
            long dif = sequences.get(i) - (pos + 1);
            if(dif < 0) return null; // Pool empty or slot in mid-writing, cut
            else if(dif == 0 && head.compareAndSet(pos, pos + 1)){
                T e = pool.get(i);
                pool.lazySet(i, null); // Help GC
                sequences.lazySet(i, pos + capacity); // Free for the next round
                return e;
            }
            // Lost CAS race, re-read
        }
    }

    /**
     * Puts the specified object in the pool, making it eligible to be returned by {@link #obtain()}.
     * If the pool already contains {@link #max} free objects, the specified object is reset but not added to the pool.
     * <p>
     * The pool does not check if an object is already freed, so the same object must not be freed multiple times.
     */
    public void free(T object){
        offer(object);
    }

    /**
     * Same as {@link #free()}, but returns {@code true} if the object has been placed to the pool array.
     * Else it means that the pool is full.
     */
    public boolean offer(T object){
        if(object == null) throw new IllegalArgumentException("object cannot be null.");
        for(;;){
            long pos = tail.get();
            int i = (int)pos & mask;
            long dif = sequences.get(i) - pos;
            if(dif < 0) return false; // Pool full or slot in mid-reading, cut
            else if(dif == 0 && tail.compareAndSet(pos, pos + 1)){
                reset(object);
                pool.lazySet(i, object); // Published by the sequence
                sequences.lazySet(i, pos + 1);
                peak = Math.max(peak, (int)(pos + 1 - head.get()));
                return true;
            }
            // Lost CAS race, re-read
        }
    }

    /**
     * Called when an object is freed to clear the state of the object for possible later reuse.
     * The default implementation calls {@link Poolable#reset()} if the object is {@link Poolable}.
     */
    protected void reset(T object){
        if(object instanceof Poolable) ((Poolable)object).reset();
    }

    /**
     * Puts the specified objects in the pool. Null objects within the array are silently ignored.
     * <p>
     * The pool does not check if an object is already freed, so the same object must not be freed multiple times.
     * @see #free(Object)
     */
    public void freeAll(Seq<T> objects){
        if(objects == null) throw new IllegalArgumentException("objects cannot be null.");
        for(int i = 0; i < objects.size; i++){
            T object = objects.get(i);
            if(object == null) continue;
            if(!offer(object)) reset(object);
        }
    }

    /** Fill the pool with new objects. */
    public void fill(){
        fill(capacity);
    }

    /** Fill the pool with {@code size} new objects. */
    public void fill(int size){
        for(int i = 0; i < size; i++){
            if(!offer(newObject())) return;
        }
    }

    /** Removes all free objects from this pool. */
    public void clear(){
        while(poll() != null);
    }

    /**
     * The number of objects available to be obtained.
     * This can be an estimation if pool is highly used.
     */
    public int getFree(){
      return (int)Mathf.clamp(tail.get() - head.get(), 0, capacity);
    }
}
