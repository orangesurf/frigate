package com.sparrowwallet.frigate.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public abstract class AbstractDbManager implements DbManager {
    private static final Logger log = LoggerFactory.getLogger(AbstractDbManager.class);

    public AbstractDbManager() {
    }

    protected Connection createWriteConnection(String connectionUrl) throws SQLException {
        log.debug("Creating write connection");
        return DriverManager.getConnection(connectionUrl);
    }
}
