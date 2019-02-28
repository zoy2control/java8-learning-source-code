/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * <p>
     *     <blockquote>·因为 键值对Entry（K-V）是存在数组里，而不是 map里，所以要用 下标获取 键值对而之所以用 哈希来获取下标，</blockquote>
     *     是因为map获取value需要 key就可以了，数组获取value只要 下标就可以了，而这个特殊的数组是存 键值对的，入参是 key而不是 下标<p>
     *     所以这里 哈希的作用是将 key转换成 下标。<p>即：map -> key -> 哈希 -> 下标 -> arr[下标]，即 Entry<K,V><p>
     *     通常经过 hash得到的 下标i，再由 下标i得到的 键值对Entry需要校验，即 key与 得到的Entry.getKey()做 ==判断（.equals()与 hashCode的区别）<p>
     * </p>
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    private static AtomicInteger nextHashCode =
        new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * <p>
     *     ·当前线程的初始值 赋给 thread-local var。
     *     <blockquote>
     *         ·通过 .get()方法会获取第一时间 访问当前var的线程
     *     </blockquote>
     *     <blockquote>
     *         ·除非线程被 .set()方法调用，否则 .initialValue()方法不会被线程调用。即 .set() -> 线程 -> .initialValue()
     *     </blockquote>
     *     <p>
     *     <blockquote>
     *         ·.initialValue()默认实现是返回null，即 thread-local var的值是null。
     *          如果要设定特定值，继承 ThreadLocal类，并重写 .initialValue()方法。
     *          通常用于匿名内部类
     *     </blockquote>
     * </p>

     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * ·当前线程 -> ThreadLocalMap -> 根据 map.key获取 CV键值表的Entry -> Entry.value（目的达到）
     *
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    public T get() {
        // ·获取当前线程
        Thread t = Thread.currentThread();
        // ·根据 当前线程获取 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            // ·根据 当前线程获取 Entry
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;// 获取 value
                return result;
            }
        }
        return setInitialValue();
    }

    /**
     * <p>
     *     ·设置初始值<p>
     *     ·当前线程 -> ThreadLocalMap -> map是否存在 -> 对 map更新或 新建map
     * </p>
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    private T setInitialValue() {
        T value = initialValue();// ·value = null
        // ·获取 当前线程
        Thread t = Thread.currentThread();
        // ·根据 当前线程获取 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            // map存在，设置 K-V。key为 当前线程，value为Null
            map.set(this, value);
        } else {
            // ·ThreadLocalMap为Null，创建新的 map
            // ·其实func()里面也是 ThreadLocalMap(this, firstValue)
            createMap(t, value);
        }
        return value;
    }

    /**
     * ·initialValue()替代了这个方法。其实两个方法逻辑是一样的
     * ·（给 当前线程的 ThreadLocalMap设置 value）
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            map.set(this, value);
        } else {
            createMap(t, value);
        }
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
     public void remove() {
         // ·根据 当前线程获取 ThreadLocalMap
         ThreadLocalMap m = getMap(Thread.currentThread());
         if (m != null) {
             // 在 CV键值表中删除 当前线程
             m.remove(this);
         }
     }

    /**
     * ·获取 当前线程的 CV键值表（ThreadLocalMap）
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param  t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * ·为 当前线程（作为key）创建 ThreadLocalMap（CV键值对）
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map ·map的第一个K-V
     */
    void createMap(Thread t, T firstValue) {
        // ·ThreadLocalMap[<this,firstValue>]
        // ·更新 Thread类的 CV·threadLocals
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * ·通过 Supplier获得 ThreadLocal的初始值
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /**
             * <p>
             *     ·ThreadLocal(key)的值
             * </p>
             * The value associated with this ThreadLocal.
             */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * <p>
         *     ·初始大小，必须是2的次幂
         * </p>
         * The initial capacity -- MUST be a power of two.
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * <p>
         *     ·存放K-V这样一个 Entry的键值表（数组形式，元素是K-V）
         * </p>
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         */
        private Entry[] table;

        /**
         * <p>
         *     ·键值表的实际大小（元素个数）
         * </p>
         * The number of entries in the table.
         */
        private int size = 0;

        /**
         * <p>
         *     ·保持线程的大小
         *     ？？？为什么要 threshold
         * </p>
         * The next size value at which to resize.
         */
        private int threshold; // Default to 0

        /**
         * <p>
         *      ·调整 threshold为入参的2/3
         * </p>
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * <p>
         *     ·获取数组下一个下标。此处有个有意思的三目运算，超过数组范围的下标都从0开始
         * </p>
         * Increment i modulo len.
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * <p>
         *     ·获取数组上一个下标。下标小于0的部分从 数组最后一位开始计算
         * </p>
         * Decrement i modulo len.
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * <p>
         *     ·初始化键值表，因为 ThreadLocalMaps是懒加载，所以 map内要有
         *     至少一个数据才能把表建起来
         * </p>
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            // ·初始化CV键值表（空的）
            table = new Entry[INITIAL_CAPACITY];
            // 向 CV键值表内添加一个数据
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            table[i] = new Entry(firstKey, firstValue);
            // 更新CV·size/CV·threshold
            size = 1;
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * <p>
         *     ·入参是父map，根据 父map构建 键值表
         *     <blockquote>
         *         ·值得学习的地方：旧数组 copy到 新数组，需要 下标。此处用 哈希获得下标，既然用 哈希就要考虑是否哈希冲突<p>
         *         如果新数组某个下标是否已存在元素（是：冲突）,冲突了此处用向下搜索直至找到空位
         *     </blockquote>
         * </p>
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.·父线程的map
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            // ·获取 父线程的map
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            // ·创建 空键值表（长度与 父map一致）
            table = new Entry[len];

            // ·向 空键值表添加 父map数据
            for (int j = 0; j < len; j++) {
                // ·父map的 Entry
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();// ·父map的 key
                    if (key != null) {
                        Object value = key.childValue(e.value);// ·父map的 value
                        // ·将 父map的 key/value（Entry）重新封装
                        Entry c = new Entry(key, value);
                        // ·通过 哈希获取 新下标
                        int h = key.threadLocalHashCode & (len - 1);
                        // ·哈希冲突（即该下标的数组已经存在元素）
                        while (table[h] != null) {
                            // ·向下搜索解决冲突，直到得到不冲突的下标
                            h = nextIndex(h, len);
                        }
                        // ·赋值、更新：父entry赋值到 CV键值表中
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntry(ThreadLocal<?> key) {
            // ·根据 入参key通过哈希取得下标
            int i = key.threadLocalHashCode & (table.length - 1);
            // ·获取数组元素
            Entry e = table[i];
            if (e != null && e.get() == key) {// ·找到并校验：Entry确实是想要的
                return e;
            } else {// ·不是想要的元素，继续向下查找
                return getEntryAfterMiss(key, i, e);
            }
        }

        /**
         * ·根据key找不到想要的 entry，继续查找
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         *
         * <p>
         *     入参之间的转换关系：key -> hash算法 -> i -> arr[i] -> e，然后 第一个key和 最后一个e做对比校验
         * </p>
         *
         * <p>
         *     PS：这里的封装思想值得学习：
         *     <blockquote>
         *         ·下标，移动：用 nextIndex(绝对index, 数组长度)。<p>
         *     </blockquote>
         * </p>
         *
         * <p>
         *     ·？？？这里 while()循环终止条件是 e == null，我想问如果 tab[]是满的，那么是否无限循环
         * </p>
         * @param  key the thread local object·-threadLocal对象的key
         * @param  i the table index for key's hash code·经过 hash得到的数组下标
         * @param  e the entry at table[i]·由 下标i得到的数组元素（Entry<K,V>）
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            // ·备份CV键值表
            Entry[] tab = table;
            int len = tab.length;

            while (e != null) {
                ThreadLocal<?> k = e.get();
                if (k == key) {// ·数组里找到了
                    return e;// ·返回找到的Entry
                }
                if (k == null) {
                    // ·擦去腐败的Entry
                    expungeStaleEntry(i);
                } else {// ·下一个下标
                    i = nextIndex(i, len);
                }
                // ·？？？为什么要赋值给 e，e并不返回。而且e的生命周期在上一个调用方法内
                // ·==》注意这是 while循环，所以会在数组中循环一一匹配
                e = tab[i];
            }
            // ·整个数组都找不到，返回null
            return null;
        }

        /**
         * ·根据 入参key，设置 值为入参value
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            // ·备份 CV键值表
            Entry[] tab = table;
            int len = tab.length;
            // ·根据 入参key，哈希得到 下标i
            int i = key.threadLocalHashCode & (len-1);

            // ·遍历数组
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                ThreadLocal<?> k = e.get();

                // ·成功匹配
                if (k == key) {
                    e.value = value;
                    return;
                }
                // ·替换陈腐的key
                if (k == null) {
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }

            // ·更新
            tab[i] = new Entry(key, value);
            int sz = ++size;
            if (!cleanSomeSlots(i, sz) && sz >= threshold) {
                rehash();
            }
        }

        /**
         * Remove the entry for key.
         */
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                    e.clear();
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * ·在 set操作时，擦除 陈腐的entry
         * ·！！！所谓 staleEntry，其实就是 e.get() == null的 Entry，即 ThreadLocal<?> k为Null
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * ·副作用是这个方法可能擦除两个 null的下标之间的所有陈腐的entry
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param  key the key
         * @param  value the value to be associated with key·根据 key得到的 value
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.·获得 key为Null的 下标i，纪委 staleSlot
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            // ·备份 CV键值表
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            /* ·向前查找的原因：
            ？？？在某个时刻清除所有 runs要避免 因为gc时冻结引用（因为 收集器在任何时候都会run）
            而导致持续的 rehashing
            * */
            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            int slotToExpunge = staleSlot;// ·待擦除的下标
            // ·向前遍历数组。终止条件：tab[i] == null，也就是 e.get() == null。
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len)) {
                // ·ThreadLocal<?> k为 Null。会退出 for循环
                if (e.get() == null) {
                    slotToExpunge = i;
                }
            }

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            // ·向前遍历，找 下标slotToExpunge
            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                if (k == key) {
                    e.value = value;
                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    if (slotToExpunge == staleSlot) {
                        slotToExpunge = i;
                    }
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                if (k == null && slotToExpunge == staleSlot) {
                    slotToExpunge = i;
                }
            }

            // If key not found, put new entry in stale slot
            tab[staleSlot].value = null;// ·擦除 value值
            tab[staleSlot] = new Entry(key, value);// ·设置 新entry在 下标staleSlot

            // If there are any other stale entries in run, expunge them
            // ·如果有其他陈腐的entry在运行，擦除它
            if (slotToExpunge != staleSlot) {
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
            }
        }

        /**
         * ·擦除下标为 staleSlot的
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         *
         * ·key -> i -> Entry -> key -> i -> 重新哈希 -> 新旧哈希下标对比 -> 可能设置 CV键值表
         *
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        private int expungeStaleEntry(int staleSlot) {
            // ·备份键值表
            Entry[] tab = table;
            int len = tab.length;

            // expunge entry at staleSlot
            // ·设Null，更新size
            tab[staleSlot].value = null;// ·Entry.value设为Null
            tab[staleSlot] = null;// ·Entry设为Null
            size--;

            // Rehash until we encounter null
            // ·Rehash
            Entry e;
            int i;
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;// 根据 下标i获取 Entry
                 i = nextIndex(i, len)) {
                // ·校验 key和 Entry.getKey()
                ThreadLocal<?> k = e.get();
                if (k == null) {
                    // key为null，擦除下标i的数组元素（Entry）
                    e.value = null;// ·Entry.value设为Null
                    tab[i] = null;// ·Entry设为Null
                    size--;
                } else {
                    // key不为null，重新hash
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {// ·新哈希下标h != 原哈希下标i
                        // ·擦除下标i上的数组数据
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        // ·遍历键值表，直到找到空位，然后将 e填充进空位
                        while (tab[h] != null) {
                            // ·h的下一个下标
                            h = nextIndex(h, len);
                        }
                        // tab[h]为null，然后用 e覆盖
                        tab[h] = e;
                    }
                }
            }
            return i;
        }

        /**
         * Heuristically scan some cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         *
         * @param i a position known NOT to hold a stale entry. The
         * scan starts at the element after i.
         *
         * @param n scan control: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         *
         * @return true if any stale entries have been removed.
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            // ·备份 CV键值表
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len); //·下标
                Entry e = tab[i];// ·数组值 Entry
                // ThreadLocal<?> k为 Null
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;// ·设置flag
                    i = expungeStaleEntry(i);// ·擦除 下标为i的数组值
                }
            } while ( (n >>>= 1) != 0); // ·移位op，除于2。二分
            return removed;
        }

        /**
         * ·rehash()使用场景：
         * <blockquote>
         *     ·数组从新设置长度等情况下
         *     ·删除 staleEntry时扫描整个 CV键值表
         *     ·如果不能充分的收缩 CV键值表大小，那么 扩大一倍的CV键值表大小
         * </blockquote>
         *
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        private void rehash() {
            expungeStaleEntries();

            // Use lower threshold for doubling to avoid hysteresis
            if (size >= threshold - threshold / 4) {
                resize();
            }
        }

        /**
         * ·扩大一倍容量
         * Double the capacity of the table.
         */
        private void resize() {
            // ·原 CV键值表
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            // ·原 CV键值表数据回填 新CV键值表
            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    if (k == null) {
                        e.value = null; // Help the GC。等待GC
                    } else {// ·其实就是 if(e != null && e.get() != null)
                        int h = k.threadLocalHashCode & (newLen - 1);// 重新hash
                        // 在 新CV键值表中找个 空位
                        while (newTab[h] != null) {
                            h = nextIndex(h, newLen);
                        }
                        // 插入新数据，更新CV·count
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * ·擦除 CV键值表的所有 staleEntry
         * Expunge all stale entries in the table.
         */
        private void expungeStaleEntries() {
            // ·备份 CV键值表
            Entry[] tab = table;
            int len = tab.length;
            // ·遍历整个 CV键值表
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                // ·若为 staleEntry
                if (e != null && e.get() == null) {
                    // ·e.get()即 ThreadLocal<?> k。 ThreadLocal<?> k为Null，这就是 staleEntry
                    expungeStaleEntry(j);// 擦除op
                }
            }
        }
    }
}
