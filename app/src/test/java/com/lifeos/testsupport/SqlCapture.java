package com.lifeos.testsupport;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Hibernate statement inspector that records every SQL string Hibernate prepares. */
public class SqlCapture implements StatementInspector {

    public static final List<String> SQL = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        SQL.add(sql);
        return sql;
    }
}
