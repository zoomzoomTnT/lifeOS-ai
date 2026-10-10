package com.lifeos.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The DBeaver libSQL driver talks to Turso over stateless HTTP: every statement is its own
 * auto-committed request, and setAutoCommit/commit/rollback/setReadOnly throw
 * SQLFeatureNotSupportedException. Spring/Hibernate call those on every @Transactional method,
 * so without this shim every JPA write fails with "Could not open JPA EntityManager".
 * It also only implements prepareStatement(String), so the generated-keys overloads Hibernate
 * uses are mapped to that (ids come from INSERT ... RETURNING, see TursoSQLiteDialect).
 *
 * This wraps the DataSource (only for libSQL URLs) so those calls become no-ops. Consequence:
 * a @Transactional method is NOT atomic on Turso — each statement commits on its own, and a
 * "rollback" does not undo earlier statements.
 */
@Configuration
@Slf4j
public class LibSqlCompatConfig {

    static boolean isLibSql(String url) {
        return url != null && (url.startsWith("jdbc:dbeaver:libsql:") || url.startsWith("libsql://"));
    }

    @Bean
    static BeanPostProcessor libSqlDataSourceWrapper(Environment env) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof LibSqlDataSource)
                        && isLibSql(env.getProperty("spring.datasource.url"))) {
                    log.info("libSQL datasource: transactions are per-statement (no atomic multi-statement tx)");
                    return new LibSqlDataSource(ds);
                }
                return bean;
            }
        };
    }

    static final class LibSqlDataSource extends DelegatingDataSource {
        LibSqlDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }
    }

    private static Object invoke(Connection target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Connection wrap(Connection target) {
        boolean[] autoCommit = {true};
        boolean[] readOnly = {false};
        return (Connection) Proxy.newProxyInstance(
                LibSqlCompatConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAutoCommit": autoCommit[0] = (Boolean) args[0]; return null;
                        case "getAutoCommit": return autoCommit[0];
                        case "commit", "rollback": return null;
                        case "setReadOnly": readOnly[0] = (Boolean) args[0]; return null;
                        case "isReadOnly": return readOnly[0];
                        case "setTransactionIsolation": return null;
                        case "prepareStatement":
                            // Driver only implements prepareStatement(String); drop key/column hints.
                            if (args.length == 2 && (args[1] instanceof Integer || args[1] instanceof int[]
                                    || args[1] instanceof String[])) {
                                return invoke(target, Connection.class.getMethod("prepareStatement", String.class),
                                        new Object[]{args[0]});
                            }
                            break;
                        case "unwrap":
                            if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                            break;
                        case "isWrapperFor":
                            if (((Class<?>) args[0]).isInstance(proxy)) return true;
                            break;
                        default:
                            break;
                    }
                    return invoke(target, method, args);
                });
    }
}
