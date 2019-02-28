/*
 * Copyright (c) 1997, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.security;

import java.util.ArrayList;
import java.util.List;
import sun.security.util.Debug;
import sun.security.util.SecurityConstants;


/**
 * <p>
 *     ·访问控制权上下文
 * </p>
 * An AccessControlContext is used to make system resource access decisions
 * based on the context it encapsulates.
 *
 * <p>More specifically, it encapsulates a context and
 * has a single method, {@code checkPermission},
 * that is equivalent to the {@code checkPermission} method
 * in the AccessController class, with one difference: The AccessControlContext
 * {@code checkPermission} method makes access decisions based on the
 * context it encapsulates,
 * rather than that of the current execution thread.
 *
 * <p>Thus, the purpose of AccessControlContext is for those situations where
 * a security check that should be made within a given context
 * actually needs to be done from within a
 * <i>different</i> context (for example, from within a worker thread).
 *
 * <p> An AccessControlContext is created by calling the
 * {@code AccessController.getContext} method.
 * The {@code getContext} method takes a "snapshot"
 * of the current calling context, and places
 * it in an AccessControlContext object, which it returns. A sample call is
 * the following:
 *
 * <pre>
 *   AccessControlContext acc = AccessController.getContext()
 * </pre>
 *
 * <p>
 * Code within a different context can subsequently call the
 * {@code checkPermission} method on the
 * previously-saved AccessControlContext object. A sample call is the
 * following:
 *
 * <pre>
 *   acc.checkPermission(permission)
 * </pre>
 *
 * @see AccessController
 *
 * @author Roland Schemers
 */

public final class AccessControlContext {

    private ProtectionDomain context[];
    // ·isPrivileged、isAuthorized是被 VM引用的，不要改变他们的名字
    // isPrivileged and isAuthorized are referenced by the VM - do not remove
    // or change their names
    private boolean isPrivileged;
    private boolean isAuthorized = false;

    // ·特权上下文privilegedContext被 VM本地代码直接使用
    // Note: This field is directly used by the virtual machine
    // native codes. Don't touch it.
    private AccessControlContext privilegedContext;

    private DomainCombiner combiner = null;

    // ·限制特权作用域
    // limited privilege scope
    private Permission permissions[];
    private AccessControlContext parent;
    private boolean isWrapped; // ·判断是否存储了 permission和 parentField（即标记上面那 限制特权作用域）

    // is constrained by limited privilege scope?
    private boolean isLimited;
    private ProtectionDomain limitedContext[];

    private static boolean debugInit = false;
    private static Debug debug = null;

    /**
     * ·获取 Debug
     * @return
     */
    static Debug getDebug()
    {
        if (debugInit) {
            // ·Debug初始化了，直接返回
            return debug;
        } else {
            if (Policy.isSet()) {
                // ·否则，初始化 Debug为“access”并返回
                debug = Debug.getInstance("access");
                debugInit = true;
            }
            return debug;
        }
    }

    /**
     * Create an AccessControlContext with the given array of ProtectionDomains.
     * Context must not be null. Duplicate domains will be removed from the
     * context.
     *
     * @param context the ProtectionDomains associated with this context.
     * The non-duplicate domains are copied from the array. Subsequent
     * changes to the array will not affect this AccessControlContext.
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public AccessControlContext(ProtectionDomain context[])
    {
        if (context.length == 0) {
            this.context = null;
        } else if (context.length == 1) {
            if (context[0] != null) {
                this.context = context.clone();
            } else {
                this.context = null;
            }
        } else {
            List<ProtectionDomain> v = new ArrayList<>(context.length);
            for (int i =0; i< context.length; i++) {
                if ((context[i] != null) &&  (!v.contains(context[i]))) {
                    v.add(context[i]);
                }
            }
            if (!v.isEmpty()) {
                this.context = new ProtectionDomain[v.size()];
                this.context = v.toArray(this.context);
            }
        }
    }

    /**
     * Create a new {@code AccessControlContext} with the given
     * {@code AccessControlContext} and {@code DomainCombiner}.
     * This constructor associates the provided
     * {@code DomainCombiner} with the provided
     * {@code AccessControlContext}.
     *
     * <p>
     *
     * @param acc the {@code AccessControlContext} associated
     *          with the provided {@code DomainCombiner}.
     *
     * @param combiner the {@code DomainCombiner} to be associated
     *          with the provided {@code AccessControlContext}.
     *
     * @exception NullPointerException if the provided
     *          {@code context} is {@code null}.
     *
     * @exception SecurityException if a security manager is installed and the
     *          caller does not have the "createAccessControlContext"
     *          {@link SecurityPermission}
     * @since 1.3
     */
    public AccessControlContext(AccessControlContext acc,
                                DomainCombiner combiner) {

        this(acc, combiner, false);
    }

    /**
     * package private to allow calls from ProtectionDomain without performing
     * the security check for {@linkplain SecurityConstants.CREATE_ACC_PERMISSION}
     * permission
     */
    AccessControlContext(AccessControlContext acc,
                        DomainCombiner combiner,
                        boolean preauthorized) {
        if (!preauthorized) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                sm.checkPermission(SecurityConstants.CREATE_ACC_PERMISSION);
                this.isAuthorized = true;
            }
        } else {
            this.isAuthorized = true;
        }

        this.context = acc.context;

        // we do not need to run the combine method on the
        // provided ACC.  it was already "combined" when the
        // context was originally retrieved.
        //
        // at this point in time, we simply throw away the old
        // combiner and use the newly provided one.
        this.combiner = combiner;
    }

    /**
     * package private for AccessController
     *
     * This "argument wrapper" context will be passed as the actual context
     * parameter on an internal doPrivileged() call used in the implementation.
     */
    AccessControlContext(ProtectionDomain caller, DomainCombiner combiner,
        AccessControlContext parent, AccessControlContext context,
        Permission[] perms)
    {
        /*
         * Combine the domains from the doPrivileged() context into our
         * wrapper context, if necessary.
         */
        ProtectionDomain[] callerPDs = null;
        if (caller != null) {
             callerPDs = new ProtectionDomain[] { caller };
        }
        if (context != null) {
            if (combiner != null) {
                this.context = combiner.combine(callerPDs, context.context);
            } else {
                this.context = combine(callerPDs, context.context);
            }
        } else {
            /*
             * Call combiner even if there is seemingly nothing to combine.
             */
            if (combiner != null) {
                this.context = combiner.combine(callerPDs, null);
            } else {
                this.context = combine(callerPDs, null);
            }
        }
        this.combiner = combiner;

        Permission[] tmp = null;
        if (perms != null) {
            tmp = new Permission[perms.length];
            for (int i=0; i < perms.length; i++) {
                if (perms[i] == null) {
                    throw new NullPointerException("permission can't be null");
                }

                /*
                 * An AllPermission argument is equivalent to calling
                 * doPrivileged() without any limit permissions.
                 */
                if (perms[i].getClass() == AllPermission.class) {
                    parent = null;
                }
                tmp[i] = perms[i];
            }
        }

        /*
         * For a doPrivileged() with limited privilege scope, initialize
         * the relevant fields.
         *
         * The limitedContext field contains the union of all domains which
         * are enclosed by this limited privilege scope. In other words,
         * it contains all of the domains which could potentially be checked
         * if none of the limiting permissions implied a requested permission.
         */
        if (parent != null) {
            this.limitedContext = combine(parent.context, parent.limitedContext);
            this.isLimited = true;
            this.isWrapped = true;
            this.permissions = tmp;
            this.parent = parent;
            this.privilegedContext = context; // used in checkPermission2()
        }
        this.isAuthorized = true;
    }


    /**
     * package private constructor for AccessController.getContext()
     */

    AccessControlContext(ProtectionDomain context[],
                         boolean isPrivileged)
    {
        this.context = context;
        this.isPrivileged = isPrivileged;
        this.isAuthorized = true;
    }

    /**
     * Constructor for JavaSecurityAccess.doIntersectionPrivilege()
     */
    AccessControlContext(ProtectionDomain[] context,
                         AccessControlContext privilegedContext)
    {
        this.context = context;
        this.privilegedContext = privilegedContext;
        this.isPrivileged = true;
    }

    /**
     * Returns this context's context.
     */
    ProtectionDomain[] getContext() {
        return context;
    }

    /**
     * Returns true if this context is privileged.
     */
    boolean isPrivileged()
    {
        return isPrivileged;
    }

    /**
     * <p>
     *     ·根据 特权或 继承的上下文获取 AssignedCombiner
     * </p>
     * get the assigned combiner from the privileged or inherited context
     */
    DomainCombiner getAssignedCombiner() {
        AccessControlContext acc;
        if (isPrivileged) {
            // ·有特权，直接返回 CV·特权上下文
            acc = privilegedContext;
        } else {
            // ·无特权。获取 继承AccessControlContext
            acc = AccessController.getInheritedAccessControlContext();
        }
        if (acc != null) {
            // ·若有上下文，获取 DomainCombiner
            return acc.combiner;
        }
        return null;
    }

    /**
     * Get the {@code DomainCombiner} associated with this
     * {@code AccessControlContext}.
     *
     * <p>
     *
     * @return the {@code DomainCombiner} associated with this
     *          {@code AccessControlContext}, or {@code null}
     *          if there is none.
     *
     * @exception SecurityException if a security manager is installed and
     *          the caller does not have the "getDomainCombiner"
     *          {@link SecurityPermission}
     * @since 1.3
     */
    public DomainCombiner getDomainCombiner() {

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SecurityConstants.GET_COMBINER_PERMISSION);// ·getDomainCombiner
        }
        return getCombiner();
    }

    /**
     * package private for AccessController
     */
    DomainCombiner getCombiner() {
        return combiner;
    }

    boolean isAuthorized() {
        return isAuthorized;
    }

    /**
     * <p>
     *     ·
     * </p>
     * Determines whether the access request indicated by the
     * specified permission should be allowed or denied, based on
     * the security policy currently in effect, and the context in
     * this object. The request is allowed only if every ProtectionDomain
     * in the context implies the permission. Otherwise the request is
     * denied.
     *
     * <p>
     * This method quietly returns if the access request
     * is permitted, or throws a suitable AccessControlException otherwise.
     *
     * @param perm the requested permission.
     *
     * @exception AccessControlException if the specified permission
     * is not permitted, based on the current security policy and the
     * context encapsulated by this object.
     * @exception NullPointerException if the permission to check for is null.
     */
    public void checkPermission(Permission perm)
        throws AccessControlException
    {
        boolean dumpDebug = false;

        if (perm == null) {
            throw new NullPointerException("permission can't be null");
        }
        // ·获取 Debug对象
        if (getDebug() != null) {
            // ·静态方法调用
            // ·如果
            // If "codebase" is not specified, we dump the info by default.
            dumpDebug = !Debug.isOn("codebase=");
            if (!dumpDebug) {
                // If "codebase" is specified, only dump if the specified code
                // value is in the stack.
                for (int i = 0; context != null && i < context.length; i++) {
                    if (context[i].getCodeSource() != null &&
                        context[i].getCodeSource().getLocation() != null &&
                        Debug.isOn("codebase=" + context[i].getCodeSource().getLocation().toString())) {
                        dumpDebug = true;
                        break;
                    }
                }
            }

            // ·dumpDebug设置：如果 Debug模式不是 permission，而是 "permission=" + perm.getClass().getCanonicalName()
            dumpDebug &= !Debug.isOn("permission=") ||
                Debug.isOn("permission=" + perm.getClass().getCanonicalName());

            // ·需要 dumpDebug（丢弃debug模式），而且 Debug模式在 stack ==》 丢弃stack
            if (dumpDebug && Debug.isOn("stack")) {
                Thread.dumpStack();
            }

            // ·需要 dumpDebug（丢弃debug模式） && Debug模式在 domain
            if (dumpDebug && Debug.isOn("domain")) {
                if (context == null) {
                    debug.println("domain (context is null)");
                    // ·context不为Null（ProtectionDomain[]）
                } else {
                    // ·遍历 ProtectionDomain[]，并打印元素内容
                    for (int i=0; i< context.length; i++) {
                        debug.println("domain "+i+" "+context[i]);
                    }
                }
            }
        }

        /*
         * iterate through the ProtectionDomains in the context.
         * Stop at the first one that doesn't allow the
         * requested permission (throwing an exception).
         *
         */

        /* if ctxt is null, all we had on the stack were system domains,
           or the first domain was a Privileged system domain. This
           is to make the common case for system code very fast */

        if (context == null) {
            checkPermission2(perm);
            return;
        }

        for (int i=0; i< context.length; i++) {
            if (context[i] != null &&  !context[i].implies(perm)) {
                if (dumpDebug) {
                    debug.println("access denied " + perm);
                }

                if (Debug.isOn("failure") && debug != null) {
                    // Want to make sure this is always displayed for failure,
                    // but do not want to display again if already displayed
                    // above.
                    if (!dumpDebug) {
                        debug.println("access denied " + perm);
                    }
                    Thread.dumpStack();
                    final ProtectionDomain pd = context[i];
                    final Debug db = debug;
                    AccessController.doPrivileged (new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            db.println("domain that failed "+pd);
                            return null;
                        }
                    });
                }
                throw new AccessControlException("access denied "+perm, perm);
            }
        }

        // allow if all of them allowed access
        if (dumpDebug) {
            debug.println("access allowed "+perm);
        }

        checkPermission2(perm);
    }

    /*
     * ·根据 限制特权scope来校验 domains
     * Check the domains associated with the limited privilege scope.
     */
    private void checkPermission2(Permission perm) {
        // ·非限制
        if (!isLimited) {
            return;
        }

        /*
         * Check the doPrivileged() context parameter, if present.
         */
        if (privilegedContext != null) {
            privilegedContext.checkPermission2(perm);
        }

        /*
         * ·wrapper context忽略
         *
         * Ignore the limited permissions and parent fields of a wrapper
         * context since they were already carried down into the unwrapped
         * context.
         */
        if (isWrapped) {
            return;
        }

        /*
         * Try to match any limited privilege scope.
         */
        if (permissions != null) {
            Class<?> permClass = perm.getClass();// ·反射
            // ·遍历 Permission[]
            for (int i=0; i < permissions.length; i++) {
                Permission limit = permissions[i];
                // ·.equals() && .implies() --> 蕴含， limit蕴含着 入参perm
                if (limit.getClass().equals(permClass) && limit.implies(perm)) {
                    return;
                }
            }
        }

        /*
         * ·校验 parent的 permission
         * Check the limited privilege scope up the call stack or the inherited
         * parent thread call stack of this ACC.
         */
        if (parent != null) {
            /*
             * As an optimization, if the parent context is the inherited call
             * stack context from a parent thread then checking the protection
             * domains of the parent context is redundant since they have
             * already been merged into the child thread's context by
             * optimize(). When parent is set to an inherited context this
             * context was not directly created by a limited scope
             * doPrivileged() and it does not have its own limited permissions.
             */
            // ·CV·Permission[]为Null
            if (permissions == null) {
                parent.checkPermission2(perm);
                // ·CV·Permission[]不为Null
            } else {
                parent.checkPermission(perm);
            }
        }
    }

    /**
     * <p>
     *     ·如果需要，将 当前context(this）与 特权或继承上下文合并<p>
     *     ·任何 限制特权scope都会 被标记，无论 assignedContext是否从 an immediately enclosing limited doPrivileged()中来<p>
     *     ·限制特权scope会通过 getContext()，从 inheritedParent线程或 assignedContext间接的获取
     * </p>
     * Take the stack-based context (this) and combine it with the
     * privileged or inherited context, if need be. Any limited
     * privilege scope is flagged regardless of whether the assigned
     * context comes from an immediately enclosing limited doPrivileged().
     * The limited privilege scope can indirectly flow from the inherited
     * parent thread or an assigned context previously captured by getContext().
     */
    AccessControlContext optimize() {
        // the assigned (privileged or inherited) context
        AccessControlContext acc;
        DomainCombiner combiner = null;
        AccessControlContext parent = null;
        Permission[] permissions = null;

        // ·有特权
        if (isPrivileged) {
            // ·获取 特权上下文
            acc = privilegedContext;
            if (acc != null) {
                /*
                 * ·wrapperContext是存储 permission和 parentField的，
                 * If the context is from a limited scope doPrivileged() then
                 * copy the permissions and parent fields out of the wrapper
                 * context that was created to hold them.
                 */
                // ·上下文中有 permission和 parentField
                if (acc.isWrapped) {
                    permissions = acc.permissions;
                    parent = acc.parent;
                }
            }
        } else {// ·无特权
            // ·获取 继承上下文
            acc = AccessController.getInheritedAccessControlContext();
            if (acc != null) {
                /*
                 * ·若 继承上下文（inherited context）是被 束缚在 limitedScope里面的，
                 * ·就把 继承上下文设置为 parent，因此可以 执行non-domain-related state
                 * If the inherited context is constrained by a limited scope
                 * doPrivileged() then set it as our parent so we will process
                 * the non-domain-related state.
                 */
                if (acc.isLimited) {
                    // ·parent为 继承上下文（InheritedAccessControlContext）
                    parent = acc;
                }
            }
        }

        // ·跳过栈：
        // ·若 栈中只有 系统代码，上下文将会是 null，这种情况，忽略 stackContext
        // this.context could be null if only system code is on the stack;
        // in that case, ignore the stack context
        boolean skipStack = (context == null);

        // ·跳过分配：
        // ·若 包含系统代码，acc.context将会为null，所以 assignedContext被忽略
        // ·acc/acc.context --> ProtectionDomain[]
        // acc.context could be null if only system code was involved;
        // in that case, ignore the assigned context
        boolean skipAssigned = (acc == null || acc.context == null);
        ProtectionDomain[] assigned = (skipAssigned) ? null : acc.context;
        ProtectionDomain[] pd;

        // ·跳过限制：
        // ·若不是在 栈中为封闭的限制特权，或是从 父线程中继承的特权
        // if there is no enclosing limited privilege scope on the stack or
        // inherited from a parent thread
        boolean skipLimited = ((acc == null || !acc.isWrapped) && parent == null);

        // ·分配的上下文（AccessControlContext）不为null，且有 DomainCombiner。则 组合
        if (acc != null && acc.combiner != null) {// -->1
            // let the assigned acc's combiner do its thing
            if (getDebug() != null) {
                debug.println("AccessControlContext invoking the Combiner");
            }

            // ·上下文组合器会组合 context和 ProtectionDomain
            // No need to clone current and assigned.context
            // combine() will not update them
            combiner = acc.combiner;
            pd = combiner.combine(context, assigned);
        } else {// ·分配的上下文（AccessControlContext）为null 或者无 DomainCombiner。则 不组合。1<--
            if (skipStack) {// ·跳过栈。-->2
                if (skipAssigned) {// ·跳过分配。-->3
                    // ·尝试合并 上下文acc和 parent的 ProtectionDomain[]为 新的ProtectionDomain[]，并 赋值给CV·limitedContext[]
                    calculateFields(acc, parent, permissions);
                    return this;// ·返回当前引用
                } else if (skipLimited) {// ·跳过限制。3<--
                    return acc;
                }
            } else if (assigned != null) {//·已分配。 2<--
                if (skipLimited) {// ·跳过限制
                    // ·优化：不需要 .combine()的情况，若 单个栈作用域早已存在在 assignedContext中
                    // optimization: if there is a single stack domain and
                    // that domain is already in the assigned context; no
                    // need to combine
                    if (context.length == 1 && context[0] == assigned[0]) {
                        return acc;
                    }
                }
            }
            // ·合并 context：ProtectionDomain[]和 assigned: ProtectionDomain[]两个数组为一个数组
            pd = combine(context, assigned);
            // ·跳过限制但 不跳过分配，并且 新合并的ProtectionDomain[]为 assigned
            if (skipLimited && !skipAssigned && pd == assigned) {
                // ·返回 acc
                return acc;
                // ·跳过分配并且 新合并的ProtectionDomain[]为 context
            } else if (skipAssigned && pd == context) {
                // ·尝试合并 acc和 parent
                calculateFields(acc, parent, permissions);
                return this;
            }
        }

        // Reuse existing ACC
        this.context = pd;
        this.combiner = combiner;
        this.isPrivileged = false;

        // ·尝试合并 acc和 parent这两个 ProtectionDomain[]，并 赋予权限permissions
        calculateFields(acc, parent, permissions);
        return this;
    }


    /*
     * ·组合 当前栈current和 分配的空间assigned
     * Combine the current (stack) and assigned domains.
     */
    private static ProtectionDomain[] combine(ProtectionDomain[]current,
        ProtectionDomain[] assigned) {

        // ·若 当前栈为null，跳过栈
        // current could be null if only system code is on the stack;
        // in that case, ignore the stack context
        boolean skipStack = (current == null);

        // ·若 分配Domain为null，跳过分配
        // assigned could be null if only system code was involved;
        // in that case, ignore the assigned context
        boolean skipAssigned = (assigned == null);

        // ·获取 当前栈数组的长度
        int slen = (skipStack) ? 0 : current.length;

        // ·优化：若没有 assignedContext和 栈长度小于2，没有理由压缩 栈上下文
        // optimization: if there is no assigned context and the stack length
        // is less then or equal to two; there is no reason to compress the
        // stack context, it already is
        if (skipAssigned && slen <= 2) {
            // ·返回 当前栈
            return current;
        }

        // ·获取 分配空间的长度
        int n = (skipAssigned) ? 0 : assigned.length;

        // ·将两个上下文结合起来，形成新的 上下文pd
        // now we combine both of them, and create a new context
        ProtectionDomain pd[] = new ProtectionDomain[slen + n];

        // ·不跳过分配，那么先拷贝 assignedContext到 新空间
        // first copy in the assigned context domains, no need to compress
        if (!skipAssigned) {
            System.arraycopy(assigned, 0, pd, 0, n);
        }

        // ·再拷贝 当前栈内容到 新空间
        // now add the stack context domains, discarding nulls and duplicates
    outer:
        for (int i = 0; i < slen; i++) {
            ProtectionDomain sd = current[i];
            if (sd != null) {
                for (int j = 0; j < n; j++) {
                    if (sd == pd[j]) {
                        // ·忽略重复的
                        continue outer;
                    }
                }
                pd[n++] = sd;
            }
        }

        // ·若有 空闲的新空间，缩短 新空间大小
        // if length isn't equal, we need to shorten the array
        if (n != pd.length) {
            // optimization: if we didn't really combine anything
            if (!skipAssigned && n == assigned.length) {
                // ·没跳过分配，并且 新上下文空间大小等于 原assigned空间大小，说明 当前栈current是没有的
                // ·直接返回 原分配空间assigned
                return assigned;
            } else if (skipAssigned && n == slen) {
                // ·若 跳过分配，且 新上下文空间大小等于 当前栈current大小，说明 原分配空间assigned是不存在的
                // ·直接返回 当前栈current
                return current;
            }
            // ·新空间大小为 n
            ProtectionDomain tmp[] = new ProtectionDomain[n];
            System.arraycopy(pd, 0, tmp, 0, n);
            pd = tmp;
        }

        return pd;
    }


    /*
     * ·设置CV·limitedContext、permissions、parent、isLimited
     * ·尝试合并 入参上下文 assigned和 parent的 ProtectionDomain[]为 新newLimit：ProtectionDomain[]
     *
     * Calculate the additional domains that could potentially be reached via
     * limited privilege scope. Mark the context as being subject to limited
     * privilege scope unless the reachable domains (if any) are already
     * contained in this domain context (in which case any limited
     * privilege scope checking would be redundant).
     */
    private void calculateFields(AccessControlContext assigned,
        AccessControlContext parent, Permission[] permissions)
    {
        ProtectionDomain[] parentLimit = null;
        ProtectionDomain[] assignedLimit = null;
        ProtectionDomain[] newLimit;

        // ·由 AccessControlContext --> ProtectionDomain[]，并通过 combine()
        parentLimit = (parent != null)? parent.limitedContext: null;
        assignedLimit = (assigned != null)? assigned.limitedContext: null;
        // ·组合两个作用域 parentLimit和 assignedLimit
        newLimit = combine(parentLimit, assignedLimit);
        if (newLimit != null) {
            // ·context[]为 Null或者 context[]中的元素在 newLimit[]中是没有的
            if (context == null || !containsAllPDs(newLimit, context)) {
                // ·limitedContext属性设置
                this.limitedContext = newLimit;
                this.permissions = permissions;
                this.parent = parent;
                this.isLimited = true;
            }
        }
    }


    /**
     * <p>
     *     ·重写 equals()</p>
     *     ·对比维度：类型AccessControlContext && Context（context、combiner） && LimitContext<p></p>
     * </p>
     * Checks two AccessControlContext objects for equality.
     * Checks that <i>obj</i> is
     * an AccessControlContext and has the same set of ProtectionDomains
     * as this context.
     * <P>
     * @param obj the object we are testing for equality with this object.
     * @return true if <i>obj</i> is an AccessControlContext, and has the
     * same set of ProtectionDomains as this context, false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        // ·对比维度：类型AccessControlContext && Context（context、combiner） && LimitContext
        if (! (obj instanceof AccessControlContext)) {
            return false;
        }

        AccessControlContext that = (AccessControlContext) obj;

        if (!equalContext(that)) {
            return false;
        }

        if (!equalLimitedContext(that)) {
            return false;
        }

        return true;
    }

    /*
     * ·CV·context && CV·combiner
     * Compare for equality based on state that is free of limited
     * privilege complications.
     */
    private boolean equalContext(AccessControlContext that) {
        if (!equalPDs(this.context, that.context)) {
            return false;
        }

        if (this.combiner == null && that.combiner != null) {
            return false;
        }

        if (this.combiner != null && !this.combiner.equals(that.combiner)) {
            return false;
        }

        return true;
    }

    /*·ProtectionDomain[]完全匹配*/
    private boolean equalPDs(ProtectionDomain[] a, ProtectionDomain[] b) {
        if (a == null) {
            return (b == null);
        }

        if (b == null) {
            return false;
        }

        if (!(containsAllPDs(a, b) && containsAllPDs(b, a))) {
            return false;
        }

        return true;
    }

    /*
     *·
     * Compare for equality based on state that is captured during a
     * call to AccessController.getContext() when a limited privilege
     * scope is in effect.
     */
    private boolean equalLimitedContext(AccessControlContext that) {
        if (that == null) {
            return false;
        }

        /*
         * ·没有 限制特权范围，TRUE
         * If neither instance has limited privilege scope then we're done.
         */
        if (!this.isLimited && !that.isLimited) {
            return true;
        }

        /*
         * ·两个都有 limited privilege scope，继续。只要有一个不是 isLimited，FALSE
         * If only one instance has limited privilege scope then we're done.
         */
         if (!(this.isLimited && that.isLimited)) {
             return false;
         }

        /*
         * ·要么都是 Wrapperd，要么都不是 Wrapped，继续
         * Wrapped instances should never escape outside the implementation
         * this class and AccessController so this will probably never happen
         * but it only makes any sense to compare if they both have the same
         * isWrapped state.
         */
        if ((this.isWrapped && !that.isWrapped) ||
            (!this.isWrapped && that.isWrapped)) {
            return false;
        }

        /* ·要么都有 permissions，要么都没有 permisstions*/
        if (this.permissions == null && that.permissions != null) {
            return false;
        }
        if (this.permissions != null && that.permissions == null) {
            return false;
        }

        /* ·校验 permission*/
        if (!(this.containsAllLimits(that) && that.containsAllLimits(this))) {
            return false;
        }

        /*
         * Skip through any wrapped contexts.
         */
        AccessControlContext thisNextPC = getNextPC(this);
        AccessControlContext thatNextPC = getNextPC(that);

        /*
         * The protection domains and combiner of a privilegedContext are
         * not relevant because they have already been included in the context
         * of this instance by optimize() so we only care about any limited
         * privilege state they may have.
         */
        if (thisNextPC == null && thatNextPC != null && thatNextPC.isLimited) {
            return false;
        }

        if (thisNextPC != null && !thisNextPC.equalLimitedContext(thatNextPC)) {
            return false;
        }

        if (this.parent == null && that.parent != null) {
            return false;
        }

        if (this.parent != null && !this.parent.equals(that.parent)) {
            return false;
        }

        return true;
    }

    /*
     * Follow the privilegedContext link making our best effort to skip
     * through any wrapper contexts.
     */
    private static AccessControlContext getNextPC(AccessControlContext acc) {
        while (acc != null && acc.privilegedContext != null) {
            acc = acc.privilegedContext;
            if (!acc.isWrapped) {
                return acc;
            }
        }
        return null;
    }

    /**
     * ·校验 thisContext[]是否包含 thatContext[]所有元素（当然 thisContext[] => thatContext[]）
     *
     * @param thisContext
     * @param thatContext
     * @return
     */
    private static boolean containsAllPDs(ProtectionDomain[] thisContext,
        ProtectionDomain[] thatContext) {
        boolean match = false;

        //·
        // ProtectionDomains within an ACC currently cannot be null
        // and this is enforced by the constructor and the various
        // optimize methods. However, historically this logic made attempts
        // to support the notion of a null PD and therefore this logic continues
        // to support that notion.
        ProtectionDomain thisPd;
        // ·遍历 thisContext[]
        for (int i = 0; i < thisContext.length; i++) {// ·-->
            match = false;
            // ·若 thisContext[]元素为 Null
            if ((thisPd = thisContext[i]) == null) {// ·-->1
                // ·遍历 thatContext，是否也有 Null
                for (int j = 0; (j < thatContext.length) && !match; j++) {
                    match = (thatContext[j] == null);
                }
            } else {// ·1<--thisContext[]元素不为Null
                // ·获取 元素的class
                Class<?> thisPdClass = thisPd.getClass();
                ProtectionDomain thatPd;
                // ·遍历 thatContext
                for (int j = 0; (j < thatContext.length) && !match; j++) {
                    thatPd = thatContext[j];

                    // ·class校验需要避免 PD暴露
                    // Class check required to avoid PD exposure (4285406)
                    // ·thisPd与 thatPd匹配
                    match = (thatPd != null && thisPdClass == thatPd.getClass() && thisPd.equals(thatPd));
                }
            }
            // ·只要 thatPd有一个元素不匹配 thisPd，那么就返回 false
            if (!match) {
                return false;
            }
        }// ·<--
        return match;
    }

    /**
     * ·是否permission为Null。否则，是否 this.permissions等于 that.permissions（.getClass()一致 && .equals()为true）
     * @param that
     * @return
     */
    private boolean containsAllLimits(AccessControlContext that) {
        boolean match = false;
        Permission thisPerm;

        // ·Permission[]均为Null
        if (this.permissions == null && that.permissions == null) {
            return true;
        }

        // ·两个数组元素一一匹配
        // ·Permission[]中双重循环一一匹配，直到找到 两个permission有一样的（.getClass()一致 && .equals()）。否则不匹配
        for (int i = 0; i < this.permissions.length; i++) {
            Permission limit = this.permissions[i];
            Class <?> limitClass = limit.getClass(); //·反射
            match = false;
            for (int j = 0; (j < that.permissions.length) && !match; j++) {
                Permission perm = that.permissions[j];
                match = (limitClass.equals(perm.getClass()) &&
                    limit.equals(perm));
            }
            if (!match) {
                return false;
            }
        }
        return match;
    }


    /**
     * <p>
     *     ·重写 hashCode()
     * </p>
     * Returns the hash code value for this context. The hash code
     * is computed by exclusive or-ing the hash code of all the protection
     * domains in the context together.
     *
     * @return a hash code value for this context.
     */
    @Override
    public int hashCode() {
        int hashCode = 0;

        if (context == null) {
            return hashCode;
        }

        // ·遍历 PermissionDomain[]
        for (int i =0; i < context.length; i++) {
            if (context[i] != null) {
                // ·异或表达式
                hashCode ^= context[i].hashCode();
            }
        }

        return hashCode;
    }
}
