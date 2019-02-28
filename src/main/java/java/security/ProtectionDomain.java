/*
 * Copyright (c) 1997, 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import sun.misc.JavaSecurityProtectionDomainAccess;
import static sun.misc.JavaSecurityProtectionDomainAccess.ProtectionDomainCache;
import sun.security.util.Debug;
import sun.security.util.SecurityConstants;
import sun.misc.JavaSecurityAccess;
import sun.misc.SharedSecrets;

/**
 *<p>
 *     ·ProtectionDomain类其实是 AccessControlContext类的应用
 *</p>
 *<p>
 * This ProtectionDomain class encapsulates the characteristics of a domain,
 * which encloses a set of classes whose instances are granted a set
 * of permissions when being executed on behalf of a given set of Principals.
 * <p>
 * A static set of permissions can be bound to a ProtectionDomain when it is
 * constructed; such permissions are granted to the domain regardless of the
 * Policy in force. However, to support dynamic security policies, a
 * ProtectionDomain can also be constructed such that it is dynamically
 * mapped to a set of permissions by the current Policy whenever a permission
 * is checked.
 * <p>
 *
 * @author Li Gong
 * @author Roland Schemers
 * @author Gary Ellison
 */

public class ProtectionDomain {
    /**
     * ·JavaSercurityAccess实现类
     */
    private static class JavaSecurityAccessImpl implements JavaSecurityAccess {

        // ·私有构造器，外部不能创建此对象
        private JavaSecurityAccessImpl() {
        }

        /**
         * ·交集特权
         * @param action
         * @param stack
         * @param context
         * @param <T>
         * @return
         */
        @Override
        public <T> T doIntersectionPrivilege(
                PrivilegedAction<T> action,
                final AccessControlContext stack,// ·AccessController.getContext()
                final AccessControlContext context) {
            if (action == null) {
                throw new NullPointerException();
            }

            // ·合并 context和 stack的上下文，并对 action赋予权限
            return AccessController.doPrivileged(action, getCombinedACC(context, stack));
        }

        /* ·上面方法的外部封装*/
        @Override
        public <T> T doIntersectionPrivilege(
                PrivilegedAction<T> action,
                AccessControlContext context) {
            return doIntersectionPrivilege(action, AccessController.getContext(), context);
        }

        /**
         * ·合并两个上下文，其实就是封装AccessControlContext.optimize()
         * @param context
         * @param stack
         * @return
         */
        private static AccessControlContext getCombinedACC(AccessControlContext context, AccessControlContext stack) {
            AccessControlContext acc = new AccessControlContext(context, stack.getCombiner(), true);

            return new AccessControlContext(stack.getContext(), acc).optimize();
        }
    }

    // ·静态块，类加载的时候被初始化
    static {
        // ·新建 JavaSecurityAccessImpl()对象，C·JavaSecurityAccessImpl为私有构造函数
        // Set up JavaSecurityAccess in SharedSecrets
        SharedSecrets.setJavaSecurityAccess(new JavaSecurityAccessImpl());
    }

    /* CodeSource */
    private CodeSource codesource ;

    /* ClassLoader the protection domain was consed from */
    private ClassLoader classloader;

    /* Principals running-as within this protection domain */
    private Principal[] principals;// ·在 protection domain里面运行的 "负责人"

    /* the rights this protection domain is granted */
    private PermissionCollection permissions;// ·在 protection domain里的 被授予的权力

    /* if the permissions object has AllPermission */
    private boolean hasAllPerm = false;// · permission是否是 全权限

    /* the PermissionCollection is static (pre 1.4 constructor)
       or dynamic (via a policy refresh) */
    private boolean staticPermissions;// ·PermissionCollection是否是 static OR dynamic

    /*
     * An object used as a key when the ProtectionDomain is stored in a Map.
     */
    final Key key = new Key();// ·在存储着 ProtectionDomain的 map中，obj作为key

    private static final Debug debug = Debug.getInstance("domain");

    /**
     * <p>
     *     ·构造器，初始化CV
     * </p>
     * Creates a new ProtectionDomain with the given CodeSource and
     * Permissions. If the permissions object is not null, then
     *  {@code setReadOnly())} will be called on the passed in
     * Permissions object. The only permissions granted to this domain
     * are the ones specified; the current Policy will not be consulted.
     *
     * @param codesource the codesource associated with this domain
     * @param permissions the permissions granted to this domain
     */
    public ProtectionDomain(CodeSource codesource,
                            PermissionCollection permissions) {
        this.codesource = codesource;
        if (permissions != null) {
            this.permissions = permissions;
            this.permissions.setReadOnly();// ·只读
            // ·该permission为 全权限
            if (permissions instanceof Permissions &&
                ((Permissions)permissions).allPermission != null) {
                hasAllPerm = true;
            }
        }
        this.classloader = null;
        this.principals = new Principal[0];
        staticPermissions = true;// ·TRUE（PermissionCollection是否是 static OR dynamic）
    }

    /**
     * <p>
     *     ·构造器
     * </p>
     * Creates a new ProtectionDomain qualified by the given CodeSource,
     * Permissions, ClassLoader and array of Principals. If the
     * permissions object is not null, then {@code setReadOnly()}
     * will be called on the passed in Permissions object.
     * The permissions granted to this domain are dynamic; they include
     * both the static permissions passed to this constructor, and any
     * permissions granted to this domain by the current Policy at the
     * time a permission is checked.
     * <p>
     * This constructor is typically used by
     * {@link SecureClassLoader ClassLoaders}
     * and {@link DomainCombiner DomainCombiners} which delegate to
     * {@code Policy} to actively associate the permissions granted to
     * this domain. This constructor affords the
     * Policy provider the opportunity to augment the supplied
     * PermissionCollection to reflect policy changes.
     * <p>
     *
     * @param codesource the CodeSource associated with this domain
     * @param permissions the permissions granted to this domain
     * @param classloader the ClassLoader associated with this domain
     * @param principals the array of Principals associated with this
     * domain. The contents of the array are copied to protect against
     * subsequent modification.
     * @see Policy#refresh
     * @see Policy#getPermissions(ProtectionDomain)
     * @since 1.4
     */
    public ProtectionDomain(CodeSource codesource,
                            PermissionCollection permissions,
                            ClassLoader classloader,
                            Principal[] principals) {
        this.codesource = codesource;
        if (permissions != null) {
            this.permissions = permissions;
            this.permissions.setReadOnly();// ·只读
            // ·该permission为 全权限
            if (permissions instanceof Permissions &&
                ((Permissions)permissions).allPermission != null) {
                hasAllPerm = true;
            }
        }
        this.classloader = classloader;
        // ·与上面构造器不同的是，这里 principals用 .clone()复制。而且 CV·staticPermissions为false
        this.principals = (principals != null ? principals.clone():
                           new Principal[0]);
        staticPermissions = false;// ·FALSE（PermissionCollection是否是 static OR dynamic）
    }

    /**
     * Returns the CodeSource of this domain.
     * @return the CodeSource of this domain which may be null.
     * @since 1.2
     */
    public final CodeSource getCodeSource() {
        return this.codesource;
    }


    /**
     * Returns the ClassLoader of this domain.
     * @return the ClassLoader of this domain which may be null.
     *
     * @since 1.4
     */
    public final ClassLoader getClassLoader() {
        return this.classloader;
    }


    /**
     * Returns an array of principals for this domain.
     * @return a non-null array of principals for this domain.
     * Returns a new array each time this method is called.
     *
     * @since 1.4
     */
    public final Principal[] getPrincipals() {
        return this.principals.clone();
    }

    /**
     * Returns the static permissions granted to this domain.
     *
     * @return the static set of permissions for this domain which may be null.
     * @see Policy#refresh
     * @see Policy#getPermissions(ProtectionDomain)
     */
    public final PermissionCollection getPermissions() {
        return permissions;
    }

    /**
     * <p>
     *     ·如果 ProtectionDomain中的 permission是 隐含的（implicit），返回TRUE
     * </p>
     * Check and see if this ProtectionDomain implies the permissions
     * expressed in the Permission object.
     * <p>
     * The set of permissions evaluated is a function of whether the
     * ProtectionDomain was constructed with a static set of permissions
     * or it was bound to a dynamically mapped set of permissions.
     * <p>
     * If the ProtectionDomain was constructed to a
     * {@link #ProtectionDomain(CodeSource, PermissionCollection)
     * statically bound} PermissionCollection then the permission will
     * only be checked against the PermissionCollection supplied at
     * construction.
     * <p>
     * However, if the ProtectionDomain was constructed with
     * the constructor variant which supports
     * {@link #ProtectionDomain(CodeSource, PermissionCollection,
     * ClassLoader, java.security.Principal[]) dynamically binding}
     * permissions, then the permission will be checked against the
     * combination of the PermissionCollection supplied at construction and
     * the current Policy binding.
     * <p>
     *
     * @param permission the Permission object to check.
     *
     * @return true if "permission" is implicit to this ProtectionDomain.
     */
    public boolean implies(Permission permission) {

        // ·permission是 全权限，不需要 go to policy
        if (hasAllPerm) {
            // internal permission collection already has AllPermission -
            // no need to go to policy
            return true;
        }

        if (!staticPermissions && Policy.getPolicyNoCheck().implies(this, permission)) {
            return true;
        }
        // ·C·PermissionCollection·CF·implies()
        if (permissions != null) {
            return permissions.implies(permission);
        }

        return false;
    }

    // ·通知VM，不删除
    // called by the VM -- do not remove
    boolean impliesCreateAccessControlContext() {
        // ·createAccessControlContext：C·SecurityPermission
        return implies(SecurityConstants.CREATE_ACC_PERMISSION);
    }

    /**
     * <p>
     *     ·将 C·ProtectionDomain转化为 String
     * </p>
     * Convert a ProtectionDomain to a String.
     */
    @Override
    public String toString() {
        String pals = "<no principals>";
        if (principals != null && principals.length > 0) {
            StringBuilder palBuf = new StringBuilder("(principals ");

            for (int i = 0; i < principals.length; i++) {
                palBuf.append(principals[i].getClass().getName() +
                            " \"" + principals[i].getName() +
                            "\"");
                if (i < principals.length-1) {
                    palBuf.append(",\n");
                } else {
                    palBuf.append(")\n");
                }
            }
            pals = palBuf.toString();
        }

        // ·校验如果 policy已经设置，我们在这里不用过早的 加载policy
        // Check if policy is set; we don't want to load
        // the policy prematurely here
        PermissionCollection pc = Policy.isSet() && seeAllp() ?
                                      mergePermissions():
                                      getPermissions();

        return "ProtectionDomain "+
            " "+codesource+"\n"+
            " "+classloader+"\n"+
            " "+pals+"\n"+
            " "+pc+"\n";
    }

    /**
     * Return true (merge policy permissions) in the following cases:
     *
     * . SecurityManager is null
     *
     * . SecurityManager is not null,
     *          debug is not null,
     *          SecurityManager impelmentation is in bootclasspath,
     *          Policy implementation is in bootclasspath
     *          (the bootclasspath restrictions avoid recursion)
     *
     * . SecurityManager is not null,
     *          debug is null,
     *          caller has Policy.getPolicy permission
     */
    private static boolean seeAllp() {
        SecurityManager sm = System.getSecurityManager();

        if (sm == null) {// ·SecurityManager为Null -->·1
            return true;
        } else {// ·SecurityManager不为Null -->·1
            if (debug != null) {// ·debug -->·2
                // ·SecurityManager和 Policy的 classLoader都为Null
                if (sm.getClass().getClassLoader() == null &&
                    Policy.getPolicyNoCheck().getClass().getClassLoader() == null) {
                    return true;
                }// ·debug <--·2
            } else {// ·!debug -->·2
                try {
                    // ·getPolicy：C·SecurityPermission
                    sm.checkPermission(SecurityConstants.GET_POLICY_PERMISSION);
                    return true;
                } catch (SecurityException se) {
                    // fall thru and return false
                }
            }// ·!debug <--·2
        }// ·SecurityManager不为Null <--·1

        return false;
    }

    /**
     * ·合并Permission。private class，私有类。
     * @return
     */
    private PermissionCollection mergePermissions() {
        // ·PermissionCollection是 static OR dynamic
        if (staticPermissions) {
            return permissions;
        }

        // ·AccessController授权
        PermissionCollection perms = java.security.AccessController.doPrivileged(new java.security.PrivilegedAction<PermissionCollection>() {
                    @Override
                    public PermissionCollection run() {
                        Policy p = Policy.getPolicyNoCheck();// ·获取 Policy
                        return p.getPermissions(ProtectionDomain.this);// ·根据 Policy获取 Permission
                    }
                });

        Permissions mergedPerms = new Permissions();
        int swag = 32;
        int vcap = 8;
        Enumeration<Permission> e;
        List<Permission> pdVector = new ArrayList<>(vcap);// ·Domain permission
        List<Permission> plVector = new ArrayList<>(swag);// ·Policy permission

        //·Domain permission
        // Build a vector of domain permissions for subsequent merge
        if (permissions != null) {
            // ·C·PermissionCollection加锁
            synchronized (permissions) {
                e = permissions.elements();// ·Permission由 PermissionCollection获取（语义上也好理解）
                while (e.hasMoreElements()) {
                    pdVector.add(e.nextElement());// ·将 Permission添加到 List
                }
            }
        }

        //·Policy permission
        // Build a vector of Policy permissions for subsequent merge
        if (perms != null) {
            // ·C·PermissionCollection加锁
            synchronized (perms) {
                e = perms.elements();// ·Permission由 PermissionCollection获取（语义上也好理解）
                while (e.hasMoreElements()) {
                    plVector.add(e.nextElement());// ·将 Permission添加到 List
                    vcap++;// ·？？？为什么增加的是 Domain permission的List size
                }
            }
        }

        /* ·删除 Policy Permission List中重复的 permission
        *  ·去重之后，合并才没有重复的*/
        if (perms != null && permissions != null) {// ·PermissionCollection对象不为Null
            //
            // Weed out the duplicates from the policy. Unless a refresh
            // has occurred since the pd was consed this should result in
            // an empty vector.
            synchronized (permissions) {
                // ·domain和 policy对比。plVector是 Policy的， permissions是 Domain的
                e = permissions.elements();   // domain vs policy
                while (e.hasMoreElements()) {
                    Permission pdp = e.nextElement();

                    Class<?> pdpClass = pdp.getClass();
                    String pdpActions = pdp.getActions();
                    String pdpName = pdp.getName();

                    for (int i = 0; i < plVector.size(); i++) {
                        Permission pp = plVector.get(i);
                        // domain vs policy
                        if (pdpClass.isInstance(pp)) {
                            // The equals() method on some permissions
                            // have some side effects so this manual
                            // comparison is sufficient.
                            if (pdpName.equals(pp.getName()) &&
                                pdpActions.equals(pp.getActions())) {
                                plVector.remove(i);// domain和 policy有重复，删除 Policy Permission List
                                break;
                            }
                        }
                    }
                }
            }
        }

        /* ·合并 Domain permission List和 Domain permission List*/
        if (perms !=null) {
            // the order of adding to merged perms and permissions
            // needs to preserve the bugfix 4301064

            for (int i = plVector.size()-1; i >= 0; i--) {// ·从后往前
                mergedPerms.add(plVector.get(i));// ·Policy permission List
            }
        }
        if (permissions != null) {
            for (int i = pdVector.size()-1; i >= 0; i--) {// ·从后往前
                mergedPerms.add(pdVector.get(i));// ·Domain permission List
            }
        }

        return mergedPerms;
    }

    /**
     * Used for storing ProtectionDomains as keys in a Map.
     */
    static final class Key {}

    static {
        SharedSecrets.setJavaSecurityProtectionDomainAccess(
            new JavaSecurityProtectionDomainAccess() {
                /* ·两个 getter，获取*/
                @Override
                public ProtectionDomainCache getProtectionDomainCache() {
                    return new PDCache();
                }

                @Override
                public boolean getStaticPermissionsField(ProtectionDomain pd) {
                    return pd.staticPermissions;
                }
            });
    }

    /**
     * ·ProtectionDomains和 它的Permissions的缓存
     * <p>
     * A cache of ProtectionDomains and their Permissions.
     *
     * This class stores ProtectionDomains as weak keys in a ConcurrentHashMap
     * with additional support for checking and removing weak keys that are no
     * longer in use. There can be cases where the permission collection may
     * have a chain of strong references back to the ProtectionDomain, which
     * ordinarily would prevent the entry from being removed from the map. To
     * address that, we wrap the permission collection in a SoftReference so
     * that it can be reclaimed by the garbage collector due to memory demand.
     */
    private static class PDCache implements ProtectionDomainCache {
        private final ConcurrentHashMap<WeakProtectionDomainKey,SoftReference<PermissionCollection>> pdMap = new ConcurrentHashMap<>();
        private final ReferenceQueue<Key> queue = new ReferenceQueue<>();

        /**
         * ·入cache
         * @param pd
         * @param pc
         */
        @Override
        public void put(ProtectionDomain pd, PermissionCollection pc) {
            processQueue(queue, pdMap);
            // ·根据 ProtectionDomain、ReferenceQueue<Key>生成 Key
            WeakProtectionDomainKey weakPd = new WeakProtectionDomainKey(pd, queue);
            // ·K-V put到 map中
            pdMap.put(weakPd, new SoftReference<>(pc));
        }

        /**
         * ·出cache
         * @param pd
         * @return
         */
        @Override
        public PermissionCollection get(ProtectionDomain pd) {
            processQueue(queue, pdMap);
            // ·根据 ProtectionDomain生成Key
            WeakProtectionDomainKey weakPd = new WeakProtectionDomainKey(pd);
            // ·.get(key)
            SoftReference<PermissionCollection> sr = pdMap.get(weakPd);
            return (sr == null) ? null : sr.get();
        }

        /**
         * ·删除队列中的 weak keys。因为 weak keys将不再被使用
         * <p>
         * Removes weak keys from the map that have been enqueued
         * on the reference queue and are no longer in use.
         */
        private static void processQueue(ReferenceQueue<Key> queue,
                                         ConcurrentHashMap<? extends
                                         WeakReference<Key>, ?> pdMap) {
            Reference<? extends Key> ref;
            // ·出队列的 ref不为 Null，就从 map中删除这个 ref
            // ·因为这个 map存放的是 WeakReference，不再会被使用
            while ((ref = queue.poll()) != null) {
                pdMap.remove(ref);
            }
        }
    }

    /**
     * <p>
     *     ·ProtectionDomain的 弱key
     * </p>
     * A weak key for a ProtectionDomain.
     */
    private static class WeakProtectionDomainKey extends WeakReference<Key> {
        /**
         * ·key的 .hashCode()值
         * <p>
         * Saved value of the referent's identity hash code, to maintain
         * a consistent hash code after the referent has been cleared
         */
        private final int hash;

        /**
         * <p>
         *     ·ProtectionDomain为Null的 key
         * </p>
         * A key representing a null ProtectionDomain.
         */
        private static final Key NULL_KEY = new Key();

        /**
         * ·构造器
         * <p>
         * Create a new WeakProtectionDomain with the specified domain and
         * registered with a queue.
         */
        WeakProtectionDomainKey(ProtectionDomain pd, ReferenceQueue<Key> rq) {
            // ·根据 ProtectionDomain获取 key
            // ·构造器调用 构造器
            this((pd == null ? NULL_KEY : pd.key), rq);
        }

        WeakProtectionDomainKey(ProtectionDomain pd) {
            this(pd == null ? NULL_KEY : pd.key);
        }

        /**
         * ·私有构造器，只能 类内部调用
         * @param key
         * @param rq
         */
        private WeakProtectionDomainKey(Key key, ReferenceQueue<Key> rq) {
            super(key, rq);// ·WeakProtectionDomainKey的目的，Key/ReferenceQueue<Key> 传到父类构造器中
            hash = key.hashCode();// ·子类自己保存 hash值
        }

        private WeakProtectionDomainKey(Key key) {
            super(key);// ·WeakProtectionDomainKey的目的，Key传到父类构造器中
            hash = key.hashCode();// ·子类自己保存 hash值
        }

        /* ·以下重写 .hashCode()和 .equals()*/
        /**
         * Returns the identity hash code of the original referent.
         */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * Returns true if the given object is an identical
         * WeakProtectionDomainKey instance, or, if this object's referent
         * has not been cleared and the given object is another
         * WeakProtectionDomainKey instance with an identical non-null
         * referent as this one.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }

            if (obj instanceof WeakProtectionDomainKey) {
                Object referent = get();// ·返回这个对象的 ref
                return (referent != null) &&
                       (referent == ((WeakProtectionDomainKey)obj).get());// ·ref是否 ==
            } else {
                return false;
            }
        }
    }
}
