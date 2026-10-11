package com.lifeos.db.libsql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes a small implementation class as a full JDBC interface. A call is routed to the public
 * method of the implementation with the same name and parameter types; a {@code (String label, ...)}
 * getter falls back to the {@code (int index, ...)} one via {@link Labeled#findColumn}. Anything
 * the implementation does not have throws {@link SQLFeatureNotSupportedException} — never a
 * silent no-op, so unsupported JDBC features fail loudly instead of misbehaving.
 */
final class JdbcProxy implements InvocationHandler {

    /** Implementations whose getters can be called by column label. */
    interface Labeled {
        int findColumn(String label) throws SQLException;
    }

    private static final Map<String, Optional<Method>> CACHE = new ConcurrentHashMap<>();

    private final Class<?> iface;
    private final Object impl;

    private JdbcProxy(Class<?> iface, Object impl) {
        this.iface = iface;
        this.impl = impl;
    }

    static <T> T create(Class<T> iface, Object impl) {
        return iface.cast(Proxy.newProxyInstance(JdbcProxy.class.getClassLoader(), new Class<?>[]{iface},
                new JdbcProxy(iface, impl)));
    }

    /** The implementation behind a proxy created here, or null. */
    static Object implOf(Object proxy) {
        if (proxy != null && Proxy.isProxyClass(proxy.getClass())
                && Proxy.getInvocationHandler(proxy) instanceof JdbcProxy h) {
            return h.impl;
        }
        return null;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        String name = m.getName();
        Class<?>[] types = m.getParameterTypes();
        if (m.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> iface.getSimpleName() + "@libsql[" + impl + "]";
            };
        }
        if (name.equals("unwrap") && types.length == 1) {
            Class<?> c = (Class<?>) args[0];
            if (c.isInstance(proxy)) return proxy;
            if (c.isInstance(impl)) return impl;
            throw new SQLException(iface.getSimpleName() + " does not wrap " + c.getName());
        }
        if (name.equals("isWrapperFor") && types.length == 1) {
            Class<?> c = (Class<?>) args[0];
            return c.isInstance(proxy) || c.isInstance(impl);
        }
        Method target = find(impl.getClass(), name, types);
        if (target != null) {
            return call(target, args);
        }
        if (types.length >= 1 && types[0] == String.class && impl instanceof Labeled labeled) {
            Class<?>[] byIndex = types.clone();
            byIndex[0] = int.class;
            target = find(impl.getClass(), name, byIndex);
            if (target != null) {
                Object[] a = args.clone();
                a[0] = labeled.findColumn((String) args[0]);
                return call(target, a);
            }
        }
        throw new SQLFeatureNotSupportedException(iface.getSimpleName() + "." + name
                + Arrays.toString(Arrays.stream(types).map(Class::getSimpleName).toArray())
                + " is not supported by the libSQL HTTP driver");
    }

    private Object call(Method target, Object[] args) throws Throwable {
        try {
            return target.invoke(impl, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method find(Class<?> c, String name, Class<?>[] types) {
        String key = c.getName() + "#" + name + Arrays.toString(types);
        return CACHE.computeIfAbsent(key, k -> {
            try {
                Method m = c.getMethod(name, types);
                return m.getDeclaringClass() == Object.class ? Optional.empty() : Optional.of(m);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
