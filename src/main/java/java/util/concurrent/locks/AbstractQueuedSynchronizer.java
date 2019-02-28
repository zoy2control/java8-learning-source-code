/*
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

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /** <p>node节点以 共享模式等待</p>Marker to indicate a node is waiting in shared mode */
        static final Node SHARED = new Node();
        /** <p>node节点以 独有的模式等待</p>Marker to indicate a node is waiting in exclusive mode */
        static final Node EXCLUSIVE = null;

        /** <p>线程被取消</p>waitStatus value to indicate thread has cancelled */
        static final int CANCELLED =  1;
        /** <p>该状态的线程要 唤醒</p>waitStatus value to indicate successor's thread needs unparking */
        static final int SIGNAL    = -1;
        /** <p>线程在 condition中等待</p>waitStatus value to indicate thread is waiting on condition */
        static final int CONDITION = -2;
        /**
         * <p>
         *     ·下一个 acquireShared要无条件的传播
         * </p>
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *               <p>
         *                  ·SIGNAL：如果 当前节点是 释放或者 取消的，那么这个节点的继承者就要阻塞。
         *                    为了避免竞争， acquire()方法必须先表明他们需要这样一个 signal，然后
         *                    他们重新尝试 原子acquire()，如果失败，则阻塞
         *               </p>
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *               <p>
         *                   ·CANCELLED：因 超时或 中断，这个节点是取消的。值得注意的是状态 一旦为cancelled，
         *                   这个线程就不能 阻塞
         *               </p>
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *               <p>
         *                   ·CONDITION：这个节点在 conditionQueue里，直到 同步状态值为0的时候，这个节点才会
         *                   被当做是 sync queue node
         *               </p>
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *               <p>
         *                   ·PROPAGATE：releaseShared应该被传播到 其他节点
         *               </p>
         *   0:          None of the above<p>
         *
         * <p>
         *     ·waitStatus的非负数值意味着 节点不需要 signal。
         * </p>
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * <p>
         *     等待状态值通过 CAS被改变
         * </p>
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus;

        /**
         * <p>
         *     ·根据检查 waitStatus来连接 上一个节点
         * </p>
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        volatile Node prev;

        /**
         * <p>
         *     ·指向下一个节点（待出队、释放）
         * </p>
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        volatile Node next;

        /**
         * <p>
         *     ·线程，即入队的节点
         *     ·初始化：通过构造器
         *     ·废弃：Null
         * </p>
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        volatile Thread thread;

        /**
         * <p>
         *     ·指向下一个 等待者（on condition）。<p>
         *     ·能够访问 conditionQueue的只能是 线程独有模式
         * </p>
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */
        Node nextWaiter;

        /**
         * <p>
         *     ·若以共享模式等待，则返回 TRUE
         * </p>
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * <p>
         *     ·返回上一个节点（previous node即 predecessor）
         *     ·如果上一个节点为Null，抛异常，现在由 VM来处理
         * </p>
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;// ·上一个节点
            if (p == null) {
                throw new NullPointerException();
            } else {
                return p;
            }
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * <p>
     *     ·同步等待队列的头部（区别 阻塞等待队列）
     * </p>
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */
    private transient volatile Node head;

    /**
     * <p>
     *     ·同步等待队列的尾部（区别 阻塞等待队列）
     * </p>
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */
    private transient volatile Node tail;

    /**
     * ·同步状态值。线程可见性，不保证原子性
     * The synchronization state.
     */
    private volatile int state;

    /**
     * <p>
     *     ·返回 当前同步状态值
     * </p>
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * <p>
     *     ·对比并设置（当前同步状态值等于 expectedValue，然后再设置）
     *     ·原子性地设置 同步状态值
     * </p>
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.·成功返回true，失败返回声明“actual值不等于expected值”
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * <p>
     *     ·入队尾op。等待队列 waitQueue（双向链表）
     * </p>
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        for (;;) {
            // ·游标指针t，指向队尾
            Node t = tail;
            if (t == null) { // Must initialize
                if (compareAndSetHead(new Node())) {// ·CAS操作，设置头节点
                    // ·尾部节点为Null，设置 尾部节点为 头部节点
                    tail = head;
                }
            } else {// ·当前节点入队尾（双向链表）
                node.prev = t;
                if (compareAndSetTail(t, node)) {// ·CAS操作，原本t为队尾，op之后 node为队尾。若成功入队尾
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * <p>
     *     ·为 当前线程创建 新节点
     * </p>
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */

    private Node addWaiter(Node mode) {// ·mode为 nextWaiter
        // ·为 当前线程创建节点
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        // ·尾部节点
        Node pred = tail;
        // ·新节点入 队尾
        if (pred != null) {
            // ·当前线程节点node 添加到 尾部节点后面（双向链表）
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {// ·CAS操作
                pred.next = node;
                return node;
            }
        }
        // ·？？？为什么这里还有个 入队操作，与上面冲突了吗
        enq(node);
        return node;
    }

    /**
     * <p>
     *     ·设置 入参节点为 头节点
     * </p>
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;// ·？？？node里面的 thread？==》对的
        node.prev = null;
    }

    /**
     * <p>
     *     ·唤醒 node节点，唤醒了node节点下一个 waitStatus <= 0的节点线程（离 node节点最近的 waitStatus <= 0的节点）<p>
     * </p>
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus;
        if (ws < 0) {
            // ·负数 waitStatus值，要CAS，改变 负值成为 0（通过等待线程）
            // ·CAS操作改 node.waitStatus为 0
            compareAndSetWaitStatus(node, ws, 0);
        }

        /*
         * ·从队尾到队头反向查找，直到找到一个 没有被取消的继承者
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */
        // ·s是从后往前遍历队列，指向离 node.next节点最近一个 waitStatus < 0的节点
        Node s = node.next;
        // ·waitStatus > 0，持锁
        if (s == null || s.waitStatus > 0) {// s.waitStatus == node.next.waitStatus
            s = null;
            // ·从队尾反向查找，直到找到 node或者 找到Null。下游标指针t
            for (Node t = tail; t != null && t != node; t = t.prev) {
                if (t.waitStatus <= 0) {
                    // ·？？？ waitStatus <= 0？
                    // ·==》 <= 0表示阻塞等待，这些节点还会在队列中
                    s = t;
                }
            }
        }
        if (s != null) {
            // ·唤醒。是唤醒了node节点下一个 waitStatus <= 0的节点（离 node节点最近的 waitStatus <= 0的节点）
            // ·如果 Node s = node.next;s.waitStatus <= 0，那么直接执行这个 if语句
            LockSupport.unpark(s.thread);
        }
    }

    /**
     * <p>
     *     ·尝试唤醒离 头节点head最近的 waitStatus的节点的线程
     * </p>
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        /** 无限循环，尝试 获得锁*/
        for (;;) {
            Node h = head;// ·头结点
            // ·若 头节点不为Null，且不为 最后一个节点
            if (h != null && h != tail) {
                // ·头结点的 waitStatus值
                int ws = h.waitStatus;
                // ·waitStatus 由 -1 -> 0
                // ·若waitStatus值为 SNGNAL，改为 0并 唤醒头节点
                if (ws == Node.SIGNAL) {
                    // ·CAS操作，改变 waitStatus值为0
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        continue;            // loop to recheck cases==》·循环重新检测
                    }
                    // ·将 头节点h的 waitStatus设置为0，并唤醒离头节点最近的 waitStatus <= 0的节点的线程
                    unparkSuccessor(h);
                }
                // ·waitStatus 由 0 -> -3
                // ·若 waitStatus值为0，且 CAS操作设置 waitStatus为 PROPAGATE（传播）失败
                else if (ws == 0 &&
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
                    continue;                // loop on failed CAS==》·CAS失败，循环
                }
            }
            if (h == head)                   // loop if head changed==》·？？？如果 h还是头节点，退出循环。如果 head被改变，则继续循环
                                             // ·？？？有 h != head的情况吗
            {
                break;// ·退出 无限循环
            }
        }
    }

    /**
     * <p>
     *     ·设置 头节点和 传播唤醒离 头节点最近 waitStauts <= 0的节点
     * </p>
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        // 设置 入参节点为 头结点
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        // ·注意，if里面是有判断顺序的
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {// 前后两个 h.waitStatus不是同一个h。后面那个 h是节点node
            // ·节点node的 下一个节点
            Node s = node.next;
            if (s == null || s.isShared()) {
                // ·尝试唤醒离 头节点head最近的 waitStatus的节点的线程
                doReleaseShared();
            }
        }
    }

    // Utilities for various versions of acquire

    /**
     * <p>
     *     ·将 node节点断链<p>
     *     ·取消并不间断的 尝试获取锁<p>
     *
     *     ·==>操作pred：pred.waitStatus > 0断链，留下 <= 0。 --> 操作node：将 节点node断链
     * </p>
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null) {
            return;
        }

        // ·线程置为Null
        node.thread = null;

        // Skip cancelled predecessors
        // ·当前节点的前一个节点
        Node pred = node.prev;
        // ·前节点，waitStatus > 0，获得锁，断链
        while (pred.waitStatus > 0) {
            /*
            // ·pred节点断链
            node.prev = pred.prev;
            pred = pred.prev;
            * */
            node.prev = pred = pred.prev;
        }

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        // ·此时 pred.waitStatus <= 0
        // ·注意：此时 pred.next还没有断链
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        // ·节点node的 waitStatus为 CANCELLED
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        // ·若 当前节点是尾节点 && CAS成功入队尾（node是原队尾，pred是新队尾） ==》 针对node节点操作，node节点断链
        if (node == tail && compareAndSetTail(node, pred)) {
            // ·CAS设置 pred节点的下一个节点为Null（因为此时 pred已经是新队尾了）
            compareAndSetNext(pred, predNext, null);

            // ==》针对 pred节点操作，其实也是 node节点断链操作
        } else {
            // ·pred.waitStatus设置为 SIGNAL。否则 唤醒节点。无论哪种，都会使 节点node断链
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            // ·pred不为头节点 && pred节点持有的线程不为Null && pred.waitStatus的值为SIGNAL
            if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL ||
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                pred.thread != null) {
                // ·当前节点的下一个节点
                Node next = node.next;
                // ·CAS将 node断链，使得 pred -> node.next
                if (next != null && next.waitStatus <= 0) {
                    compareAndSetNext(pred, predNext, next);
                }
            } else {
                // ·pred为头节点，唤醒node节点（node.waitStatus < 0设置成 0）
                unparkSuccessor(node);
            }
            // ·？？？node.next指向自己，会被gc？
            node.next = node; // help GC
        }
    }

    /**
     * <p>
     *     ·根据 pred.waitStatus来 op：尝试 获取锁失败之后 挂起等待<p>
     *     ·若pred.waitStatus > 0，则节点pred 断链。<= 0，则将 pred.waitStatus设置为 SIGNAL
     * </p>
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        // ·？？？为什么是 前节点pred
        int ws = pred.waitStatus;
        // ·挂起等待（pred.waitStatus为 SIGNAL）
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */ {
            return true;
        }
        // ·ws > 0，已获得锁，那么 该节点就要从 等待队列中删除
        if (ws > 0) {
            /*
             * ·如果 pred的 waitStatus > 0（待唤醒），那么把 pred从等待队列中删除
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                /*
                // ·将 pred节点从链表中断链
                node.prev = pred.prev;
                pred = pred.prev;
                * */
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            // ·接链（因双向）
            pred.next = node;
        } else {// ws <= 0
            /*
             * ·此时 waitStatus必为 0或者 PROPAGATE
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            // ·CAS，使得在 挂起之前不能被 获取锁
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * <p>
     *     ·挂起节点并 判断当前线程是否中断
     * </p>
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        // ·挂起 当前节点
        LockSupport.park(this);
        // ·当前线程是否中断
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * <p>
     *     ·在队列中以 独占非中断模式获取锁，若获取失败，挂起并线程中断而且将 节点node断链<p>
     *     ·PS：其实就是无限循环等待，直到 节点pred为 头节点，而且 获取锁成功，才返回（期间若 pred.waitStatus < 0，节点pred断链）
     * </p>
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean  acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;

            // ·！！！注意是 无限循环，退出循环的条件是 p为head并且 获取锁成功。循环过程中可能 获取锁失败，此时
            // ·会挂起并中断线程，这时候 interrupted为TRUE。一旦为TRUE，.acquireQueued()返回值为TRUE
            for (;;) {
                /*
                // ·当前节点node的上一个节点
                // ·因为 shouldParkAfterFaileAcquire()里面有 pred断链操作（条件：pred.waitStatus > 0），所以此处
                // ·node.predecessor()得到的 前一个节点不一定都是 同一个节点
                */
                final Node p = node.predecessor();
                // ·若是 头节点且 尝试获取锁成功
                if (p == head && tryAcquire(arg)) {
                    // ·设置当前节点为头节点（因为 当前节点node为原头节点的下一个节点）
                    // ·让 头节点p断链
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                /*
                // ·因为上面if 头节点获取锁失败，所以才会走下面这个if
                // ·CAS尝试获取锁失败之后，挂起当前节点并 中断当前线程
                // ·注意 shouldParkAfterFailedAcquire()里面是遍历队列，所以 node的前一个节点一定是挂起等待的
                // ·这里 &&有意思，前面 TRUE才判断后面是否 TRUE。所以 shouldParkAfterFailedAcquire()、parkAndCheckInterrupt()是有执行顺序的
                */
                if (shouldParkAfterFailedAcquire(p, node) &&// ·入参p 是pred，即 节点node的前一个节点。作用：设置 pred.waitStatus为 SIGNAL
                    parkAndCheckInterrupt()) {
                    // ·当前线程已中断
                    interrupted = true;
                }
            }
        } finally {
            // ·若 尝试获取锁失败
            if (failed) {
                // ·将 节点node断链（且所有 pred.waitStatus > 0断链）
                cancelAcquire(node);
            }
        }
    }

    /**
     * <p>
     *     ·创建 独占式模式节点，并尝试获取锁
     * </p>
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        // ·创建 独占式节点
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {

            /** ·无限循环，直到唤醒 头节点*/
            for (;;) {
                // ·获取该节点的上一个节点
                final Node p = node.predecessor();
                // ·上一个节点若是 同步等待队列的头节点 && 可以唤醒
                if (p == head && tryAcquire(arg)) {
                    // 原头节点被唤醒，那么 该节点node就是新的头节点
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                // ·尝试获取锁失败，继续等待
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            // ·若获取锁失败
            if (failed) {
                // ·节点node断链
                cancelAcquire(node);
            }
        }
    }

    /**
     * <p>
     *     ·创建 独占式
     * </p>
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // ·入参校验
        if (nanosTimeout <= 0L) {
            return false;
        }
        // ·准备var
        final long deadline = System.nanoTime() + nanosTimeout;
        // ·为 当前线程创建独占式的 新节点
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            /** ·无限循环，直到唤醒 头节点*/
            for (;;) {
                // ·该节点的上一个节点
                final Node p = node.predecessor();
                // ·若 上一个节点是 同步等待队列的头节点 && 可以唤醒
                if (p == head && tryAcquire(arg)) {
                    // ·上个节点（头节点）唤醒了，该节点node就是 新的头节点
                    setHead(node);// ·区别 共享式的 .setHeadAndPropagate();
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                // ·更新 nanosTimeout
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) {
                    return false;
                }
                // ·若 尝试获取锁失败之后，挂起等待
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold) {
                    // ·挂起时间 nanosTimeout
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                // 若 当前线程中断了，抛异常
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            // ·若 获取锁失败
            if (failed) {
                // 节点node断链
                cancelAcquire(node);
            }
        }
    }

    /**
     * <p>
     *     ·共享模式的节点，若尝试获取锁失败，则挂起并中断线程。<p>
     *     ·从头到尾唤醒节点，若 以传播性在队列内可以唤醒节点，？？？则 头节点断链？为什么是头节点
     * </p>
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        // ·为 当前线程创建共享式的 新节点node
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            // ·共享式才有 interrupted
            boolean interrupted = false;
            /** ·无限循环*/
            for (;;) {
                // ·当前节点node的 前节点
                final Node p = node.predecessor();
                // ·若是 头节点
                if (p == head) {
                    // ·r是传播值（propagate）。共享模式特有
                    int r = tryAcquireShared(arg);// ·区别 独占模式的 .tryAcquire()
                    if (r >= 0) {
                        // ·设置 头节点和 传播唤醒离 头节点最近 waitStauts <= 0的节点
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        // ·线程中断
                        if (interrupted) {
                            selfInterrupt();
                        }
                        failed = false;
                        return;
                    }
                }
                // ·尝试获取锁失败之后，挂起（含 waitStatus > 0断链操作）。并且 挂起后中断线程
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    interrupted = true;
                }
            }
        } finally {
            // ·若获取锁失败并挂起，而且线程已中断
            if (failed) {
                // 节点node断链
                cancelAcquire(node);
            }
        }
    }

    /**
     * <p>
     *     ·与上面那个方法 .doAcquireShared()，唯一不同是 上面代码多了 interrupted:boolean。此处获取锁失败挂起之后抛异常
     * </p>
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        // ·新建 共享模式节点
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            /** ·无限循环*/
            for (;;) {// ·-->
                // ·节点node的前一个节点
                final Node p = node.predecessor();
                // ·若是 头节点
                if (p == head) {
                    // ·r是传播值
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        // ·设置 头节点和 传播唤醒离 头节点最近 waitStauts <= 0的节点
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        // ·退出无限循环
                        return;
                    }
                }
                // ·尝试获取锁失败之后，挂起（含 waitStatus > 0断链操作）。&& 挂起后中断线程
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    throw new InterruptedException();
                }
            }// ·<--
        } finally {
            // ·若 获取锁失败且挂起，并且线程中断
            if (failed) {
                // ·节点node断链
                cancelAcquire(node);
            }
        }
    }

    /**
     * <p>
     *     ·共享式（区别独占式）
     * </p>
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // ·入参校验
        if (nanosTimeout <= 0L) {
            return false;
        }
        // ·创建var， 共享式的
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            /** ·无限循环*/
            for (;;) {// ·-->
                // ·节点node的 前一个节点
                final Node p = node.predecessor();
                // ·若是 头节点
                if (p == head) {
                    int r = tryAcquireShared(arg);// ·区别 独占模式的 .tryAcquire()
                    if (r >= 0) {
                        // ·设置 头节点和 传播唤醒离 头节点最近 waitStauts <= 0的节点
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                // ·nanosTimeout
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) {
                    return false;
                }
                // ·尝试获取锁失败之后，挂起（含 waitStatus > 0断链操作） && nanosTimeout。挂起
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                // ·若 线程中断
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }// ·<--
        } finally {
            // ·若 获取锁失败且挂起，并且线程中断
            if (failed) {
                // ·节点node断链
                cancelAcquire(node);
            }
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     *     设置状态值
     * </p>
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     *
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     *         ·如果当前对象是 fully release state，也就是说此对象会尝试接受任何等待线程。返回true
     *
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        // ·如果 独有模式不支持
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * <p>
     *     ·以 独占模式获取锁<p></p>
     *     ·忽略中断，以 独占式获取锁。通过调用最后一次 tryAcquire()返回成功来实现。否则，线程会被重复的 blocking/unblocking，直到 tryAcquire()成功为止。<p>
     *     ·这个方法在 Lock（）被调用
     * </p>
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    public final void acquire(int arg) {
        // ·不能获取锁 && 以独占模式获取锁失败
        if (!tryAcquire(arg) &&
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg)) {
            // ·线程中断
            selfInterrupt();
        }
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        // ·若 线程已中断
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        // ·？？？
        if (!tryAcquire(arg)) {
            // ·创建 独占式节点，并尝试获取锁
            doAcquireInterruptibly(arg);
        }
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // ·若 当前线程已中断，抛异常退出方法
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        // ·尝试获取锁 || 创建 独占式节点，并唤醒 头节点
        return tryAcquire(arg) ||
            doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * <p>
     *     ·在 独占模式下 释放
     * </p>
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        // ·尝试释放锁（唤醒）
        if (tryRelease(arg)) {
            // ·头
            Node h = head;
            if (h != null && h.waitStatus != 0) {
                // ·唤醒离节点h.next的最近的 waitStatus < 0的节点。而节点h是头节点
                // ·？？？为什么不唤醒 头节点呢
                unparkSuccessor(h);
            }
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        // ·尝试获取
        // ·？？？这里 < 0是什么意思
        if (tryAcquireShared(arg) < 0) {
            // ·真正获取锁（共享模式）
            doAcquireShared(arg);
        }
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        // ·若 当前线程中断，则 中断获取锁
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        // ·尝试获取
        if (tryAcquireShared(arg) < 0) {
            // ·真正获取
            doAcquireSharedInterruptibly(arg);
        }
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // ·中断
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        // ·尝试获取 || 真正获取（带nanosTimeout）
        return tryAcquireShared(arg) >= 0 ||
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        // ·尝试释放
        if (tryReleaseShared(arg)) {
            // ·真正释放
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * <p>
     *     ·判断是否有 线程在 等待队列中等待 释放
     * </p>
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        // ·头节点 != 尾节点
        return head != tail;
    }

    /**
     * <p>
     *     ·
     * </p>
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        // ·若 头节点存在
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * <p>
     *     ·若 h是头节点 && s是头节点下一个节点 && s上一个节点是头节点 && 节点s持有的线程不为Null。返回 节点s的线程<p>
     *     ·否则，从后往前遍历，找到 第一个线程不为Null并返回
     * </p>
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        // ·若 h是头节点 && s是头节点下一个节点 && s上一个节点是头节点 && 节点s持有的线程不为Null。返回 节点s的线程
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null)
                ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null)) {
            return st;
        }

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        // ·从后往前遍历，找到 第一个线程不为Null并返回
        while (t != null && t != head) {// ·-->
            Thread tt = t.thread;
            if (tt != null) {
                firstThread = tt;
            }
            // ·移动游标
            t = t.prev;
        }// ·<--
        return firstThread;
    }

    /**
     * <p>
     *     ·从后往前遍历，在队列中找到 入参线程
     * </p>
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        // ·若 入参线程为Null
        if (thread == null) {
            throw new NullPointerException();
        }
        // ·否则，从后往前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // ·在队列中找到 线程与 入参线程一样
            if (p.thread == thread) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>
     *     ·判断 节点head的 下一个节点s是否是 独占式的，并且 s.thread不为Null
     * </p>
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        // ·节点head的 下一个节点s是 独占式的，并且 s.thread不为Null
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * <p>
     *     ·判断头节点head的 下一个节点s是否是Null 或者 不为Null但线程不是 当前线程
     * </p>
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        // ·头节点head的 下一个节点s是Null 或者 不为Null但线程不是 当前线程
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * <p>
     *     ·统计线程不为Null的队列长度（从后往前）
     * </p>
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        // ·从后往前遍历
        for (Node p = tail; p != null; p = p.prev) {
            // ·线程不为Null才 自增++
            if (p.thread != null) {
                ++n;
            }
        }
        return n;
    }

    /**
     * <p>
     *     ·找出队列中所有的 线程
     * </p>
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        // ·从后往前
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            // ·线程不为Null，用 集合承载
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    /**
     * <p>
     *     ·统计队列中 独占式的线程的数目（多个限制条件，即多个if）
     * </p>
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        // ·从后往前
        for (Node p = tail; p != null; p = p.prev) {
            // ·独占式
            if (!p.isShared()) {
                Thread t = p.thread;
                // ·线程不为Null
                if (t != null) {
                    // ·添加
                    list.add(t);
                }
            }
        }
        return list;
    }

    /**
     * <p>
     *     ·统计队列中 共享式的线程的数目
     * </p>
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        // ·从后往前
        for (Node p = tail; p != null; p = p.prev) {
            // ·共享式
            if (p.isShared()) {
                Thread t = p.thread;
                // ·线程不为Null
                if (t != null) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    /**
     * <p>
     *     ·重写 toString（）方法
     * </p>
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    @Override
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * <p>
     *     ·判断 入参节点是否在 syncQueue中
     * </p>
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null) {
            return false;
        }
        // ·如果该节点存在 继承节点（下一个节点），那么该节点一定是在队列中的
        if (node.next != null) // If has successor, it must be on queue
        {
            return true;
        }
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        // ·从后往前遍历查找是否存在 节点node
        return findNodeFromTail(node);
    }

    /**
     * <p>
     *     ·入参节点是否在队列中（从尾到头遍历查询队列）
     * </p>
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        // ·下游标指针t
        Node t = tail;
        for (;;) {
            // ·在队列中找到 节点node
            if (t == node) {
                return true;
            }
            if (t == null) {// ·从后往前已经遍历到头了，还没找到 节点node
                return false;
            }
            // ·向上移动 下游标指针t
            t = t.prev;
        }
    }

    /**
     * <p>
     *     ·将一个节点从 conditionQueue转到 syncQueue中。如果成功，返回TRUE<p>
     *     ·waitStatus的值为0，即为 syncQueue，同步状态，加锁状态<p>
     *     ·waitStatus：CONDITION -> 0 -> SIGNAL（意思是从 等待队列(Condition对象把持)中加入到
     *     同步队列，然后 标上SIGNAL，表示 加锁状态）
     * </p>
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * ·CAS如果不能改变 waitStatus的值（线程竞争，只能有一个线程抢到锁），
         * 则这个 节点会被 取消(设置 等待状态值为0)，waitStatus被标记为0
         * If cannot change waitStatus, the node has been cancelled.
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            return false;
        }

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        // ·将 node入等待队列 waitQueue队尾
        // ·原队尾已返回，即此处的 节点p。节点node为 新队尾
        Node p = enq(node);
        int ws = p.waitStatus;
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL)) {
            // ·唤醒 节点持有的线程
            LockSupport.unpark(node.thread);
        }
        return true;
    }

    /**
     * <p>
     *     ·在 signalled之前，将节点node 推入syncQueue
     * </p>
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        // ·若CAS设置 waitStatus为0
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            // ·node节点入队尾
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        // ·若CAS设置失败，且节点node不在 syncQueue中，线程yield()
        while (!isOnSyncQueue(node)) {
            Thread.yield();
        }
        return false;
    }

    /**
     * <p>
     *     ·根据 同步状态值 唤醒等待队列的节点（此处是 离h.next节点最近的 waitStatus < 0的节点，节点h为头节点）
     * </p>
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait·等待的条件节点
     * @return previous sync state·返回 同步状态值
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            // ·同步状态值
            int savedState = getState();
            // ·根据同步状态值 唤醒头节点h
            if (release(savedState)) {
                failed = false;
                // ·返回 同步状态值
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed) {
                // ·唤醒失败，节点node的waitStatus为 CANCELLED
                node.waitStatus = Node.CANCELLED;
            }
        }
    }

    // Instrumentation methods for conditions

    /**
     * <p>
     *     ·判断是否使用这个 synchronizer作为 锁对象
     *     ·如果这个 condition是从 synchronization对象创建的，返回TRUE
     * </p>
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * <p>
     *     ·判断是否所有线程都与 synchronizer有关的 CONDITION作为条件来等待<p></p>
     *     ·因为 timeout/interrupt随时都有可能出现，所以不能保证 signal能唤醒全部线程<p></p>
     *     ·用于校验 系统状态<p></p>
     * </p>
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition)) {
            throw new IllegalArgumentException("Not owner");
        }
        // ·判断 任何线程是否以 CONDITION条件来等待（从后往前遍历，找到第一个 waitStatus为 CONDITION的节点）
        return condition.hasWaiters();
    }

    /**
     * <p>
     *     ·从头到尾遍历，统计队列中 waitStatus为 CONDITION的节点个数
     * </p>
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition)) {
            throw new IllegalArgumentException("Not owner");
        }
        // ·统计队列中 waitStatus为 CONDITION的节点个数
        return condition.getWaitQueueLength();
    }

    /**
     * <p>
     *     ·从头到尾，获取队列中 waitStatus为 CONDITION的线程
     * </p>
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition)) {
            throw new IllegalArgumentException("Not owner");
        }
        // ·获取队列中 waitStatus为 CONDITION的线程
        return condition.getWaitingThreads();
    }

    /**
     * <p>
     *     ConditionObject对象
     * </p>
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        // ·等待队列结构，首尾指针如下：
        /** First node of condition queue. */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * <p>
         *     ·添加 新的等待节点(持有当前线程)到 等待队列队尾中
         * </p>
         * Adds a new waiter to wait queue.
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            // ·下游标指针t
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            // ·如果 lastWaiter是 cancelled状态，则 清除
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            // ·为 当前线程创建一个 CONDITION的 waitStatus
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null) {// 若lastWaiter为Null，node为 firstWaiter
                firstWaiter = node;
            } else {// 否则， lastWaiter的下一个节点就是 node
                t.nextWaiter = node;
            }
            // ·保持语义：最后一个节点是 新node
            lastWaiter = node;
            return node;
        }

        /**
         * <p>
         *     ·删除 等待队列头节点（因为 头节点被唤醒）
         * </p>
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                // ·赋值 等待队列头firstWaiter，并判断
                if ( (firstWaiter = first.nextWaiter) == null) {
                    lastWaiter = null;
                }
                // ·删除 等待队列头节点
                first.nextWaiter = null;
                // ·不能将 节点first从 conditionQueue中转成 syncQueue，且first指向 等待队列头
            } while (!transferForSignal(first) &&
                     (first = firstWaiter) != null);
        }

        /**
         * <p>
         *     ·删除 等待队列所有节点（所有节点被唤醒）<p>
         *     ·从头到尾删除，所以每次删除的都是 头节点
         * </p>
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue·等待队列头节点
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                // ·下游标指针
                Node next = first.nextWaiter;
                // ·断链op
                first.nextWaiter = null;
                // ·？？？头节点入 等待队列队尾
                transferForSignal(first);
                // ·上游标指针
                first = next;
            } while (first != null);
        }

        /**
         * <p>
         *     ·从头到尾遍历队列，删除 waitStatus不为CONDITION的节点<p>
         *      <pre>
         *        单向链表：
         *        f -> node -> ... -> l
         *        ↑    ↑
         *        t    next
         *        其中 t、next是游标指针，f是头指针，l是尾指针
         *        删除节点的条件是，waitStatus不为 CONDITION
         *       </pre>
         *
         * </p>
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        private void unlinkCancelledWaiters() {
            // ·前游标指针：获取头结点
            Node t = firstWaiter;
            // ·waitStatus为CONDITION的最近节点，不删除（从头到尾遍历），用于连接下一个节点
            Node trail = null;

            // ·从头到尾遍历队列，删除 waitStatus不为 CONDITION的节点
            while (t != null) {
                // ·后游标指针：下一个节点
                Node next = t.nextWaiter;
                // ·节点waitStatus不为 CONDITION
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;// ·断开 前游标指针与 后游标指针节点之间的联系
                    if (trail == null) {// ·第一次循环都为Null
                        firstWaiter = next;// ·头节点下移
                    } else {// ·第二次循环
                        trail.nextWaiter = next;
                    }
                    // ·前游标指针是否是最后一个节点
                    if (next == null) {
                        lastWaiter = trail;
                    }
                }
                else {// ·节点waitStatus为 CONDITION
                    trail = t;
                }
                // ·移动 前游标指针
                t = next;
            }
        }

        // public methods

        /**
         * <p>
         *     ·唤醒 等待队列头节点持有的 线程
         * </p>
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        @Override
        public final void signal() {
            // ·如果 当前线程是 非独有线程
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            // ·获取 等待队列头节点
            Node first = firstWaiter;
            if (first != null) {
                // ·唤醒
                doSignal(first);
            }
        }

        /**
         * <p>
         *     ·唤醒 等待队列所有节点持有的线程
         * </p>
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        @Override
        public final void signalAll() {
            // ·如果 当前线程是 非独有线程
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            // ·获取 等待队列头节点
            Node first = firstWaiter;
            if (first != null) {
                // ·唤醒所有线程
                doSignalAll(first);
            }
        }

        /**
         * <p>
         *     ·非中断式条件等待实现
         * </p>
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        @Override
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();// ·新增（至队尾）并 获取等待队列最后一个节点
            int savedState = fullyRelease(node);// ·释放 尾节点（唤醒），获取该节点的 同步状态值
            boolean interrupted = false;
            // ·尾节点不在 syncQueue中（没有被唤醒）
            while (!isOnSyncQueue(node)) {
                // ·挂起当前线程
                LockSupport.park(this);
                // ·若 当前线程已中断
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            // ·尾节点 + 其同步状态值
            if (acquireQueued(node, savedState) || interrupted) {
                // ·中断 当前线程
                selfInterrupt();
            }
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /**
         * <pre>
         *      ·线程中断方式：
         *      ·1、reinterrupt
         *      ·2、抛异常throw
         * </pre>
         */
        /** Mode meaning to reinterrupt on exit from wait */
        // ·通过 reinterrupt，从 wait到 exit
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        // ·通过抛异常，从 wait到 exit
        private static final int THROW_IE    = -1;

        /**
         * <p>
         *     ·用于检查是否中断。<p>
         *     ·THROW_IF表示在 signalled之前中断。REINTERRUPT在 signalled之后。0表示未中断（这里用到2个三元表达式）
         * </p>
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        private int checkInterruptWhileWaiting(Node node) {
            // ·当前线程是否中断
            return Thread.interrupted() ?
                    // ·中断后，设置 node.waitStatus为0，并且将 节点node入队尾
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }

        /**
         * <p>
         *     ·根据 interruptMode来决定 抛异常/重新中断/不做任何事情
         * </p>
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE) {
                // ·线程中断异常
                throw new InterruptedException();
            } else if (interruptMode == REINTERRUPT) {
                // ·中断线程
                selfInterrupt();
            }
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        @Override
        public final void await() throws InterruptedException {
            // ·若 当前线程终止
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // ·新队尾节点node
            Node node = addConditionWaiter();
            // ·尝试唤醒头节点。注意此处 node是尾节点（入一个新节点到队尾，就尝试唤醒头节点）
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            // ·若 node节点不在 syncQueue中
            while (!isOnSyncQueue(node)) {
                // ·挂起
                LockSupport.park(this);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    // ·当前线程未中断，break
                    break;
                }
            }
            // ·设置中断方式为 reinterrupt
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            if (node.nextWaiter != null) // clean up if cancelled
            {
                unlinkCancelledWaiters();
            }
            // ·若线程未中断
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        @Override
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            // ·若 线程中断，抛异常
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // ·添加 新节点到队尾
            Node node = addConditionWaiter();
            // ·尝试唤醒 头节点
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            // ·若 节点node不在 syncQueue中
            while (!isOnSyncQueue(node)) {

                if (nanosTimeout <= 0L) {
                    // ·将 节点node推入 syncQueue中
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    // ·挂起时长nanosTimeout
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                // ·若 当前线程未中断
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
                // ·nanosTimeout越来越小
                nanosTimeout = deadline - System.nanoTime();
            }
            // ·若 尝试获取锁失败中断线程，且 interruptMode不为 THROW_IE
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            // ·存在 当前节点node的 nextWaiter
            if (node.nextWaiter != null) {
                // ·从头到尾遍历队列，删除 waitStatus不为 CONDITION的节点
                unlinkCancelledWaiters();
            }
            // ·根据 interruptMode做一些事情
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        @Override
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            // ·若 当前线程已中断
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // ·var准备：
            // ·添加 新节点到队尾
            Node node = addConditionWaiter();
            // ·尝试唤醒 头节点
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;

            // ·若 节点node不在 syncQueue中
            while (!isOnSyncQueue(node)) {
                // ·若 当前时间 > deadline.getTime()
                if (System.currentTimeMillis() > abstime) {
                    // ·CAS：node.waitStatus由 CONDITION设置为 0
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                // ·当前线程中断、挂起，时长abstime
                LockSupport.parkUntil(this, abstime);
                // ·若 当前线程没有中断，break
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
            }
            // ·尝试获取锁，失败则挂起、中断线程，并将 节点node断链
            // ·.acquireQueued()要为TRUE，一定是线程中断过，然后又重新获取锁成功了（进入看源码）
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            // ·若含 .nextWaiter，从头到尾遍历队列，删除 waitStatus不为CONDITION的节点
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            // ·若 interruptMode被更改过
            if (interruptMode != 0) {
                // ·根据 interruptMode来做一些事情：抛异常/重新中断/不做任何事情
                reportInterruptAfterWait(interruptMode);
            }
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        @Override
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            // ·纳秒
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // ·准备var
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;

            /** ·循环：若 节点node不在 syncQueue中*/
            while (!isOnSyncQueue(node)) {
                // ·检验 nanosTimeout，每次循环都递减
                if (nanosTimeout <= 0L) {
                    // ·CAS：node.waitStatus由 CONDITION设置为 0，此时 节点node入队
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                // ·若 当前线程没有中断，跳出 while循环
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
                // ·递减
                nanosTimeout = deadline - System.nanoTime();
            }

            // ·尝试获取锁，失败则挂起、中断线程，并将 节点node断链
            // ·.acquireQueued()要为TRUE，一定是线程中断过，然后又重新获取锁成功了（进入看源码）
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            // ·若含 .nextWaiter，从头到尾遍历队列，删除 waitStatus不为CONDITION的节点
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            // ·若 interruptMode被更改过
            if (interruptMode != 0) {
                // ·根据 interruptMode来做一些事情：抛异常/重新中断/不做任何事情
                reportInterruptAfterWait(interruptMode);
            }
            return !timedout;
        }

        //  support for instrumentation

        /**
         * <p>
         *     ·如果这个 condition是从 synchronization对象创建的，返回TRUE
         * </p>
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * <p>
         *     ·判断 任何线程是否以 CONDITION条件来等待
         * </p>
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            // ·异常情况：
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            /** ·从头到尾遍历等待队列，直到找到第一个 waitStatus为 CONDITION的节点，返回TRUE*/
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    return true;
                }
            }
            return false;
        }

        /**
         * <p>
         *     ·统计队列中 waitStatus为 CONDITION的节点个数
         * </p>
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            // ·校验异常情况
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            int n = 0;
            // ·从头到尾遍历队列
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    ++n;
                }
            }
            return n;
        }

        /**
         * <p>
         *     ·获取队列中 waitStatus为 CONDITION的线程
         * </p>
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            ArrayList<Thread> list = new ArrayList<Thread>();
            // ·从头到尾
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null) {
                        list.add(t);
                    }
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            // ·偏移量的获取。.objectFiledOffset(xxx.class.getDeclareField(String))
            // ·绝对+偏移
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * <p>
     *     CAS，入队头
     * </p>
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * <p>
     *     ·CAS，入队尾<p>
     *     ·原本 expect为队尾，更新后 update为队尾
     * </p>
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * <p>
     *     ·CAS，改变当前节点的 waitStatus值为 update
     * </p>
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * <p>
     *     ·CAS设置当前node的 下一个节点
     * </p>
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
