package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.io.ComputeBackend;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBPreparedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Index {
    private static final Logger log = LoggerFactory.getLogger(Index.class);
    public static final String DEFAULT_DB_FILENAME = "frigate.duckdb";
    private static final String TWEAK_TABLE = "tweak";
    private static final String UTXO_TABLE = "utxo";
    public static final int HISTORY_PAGE_SIZE = 100;

    private static final String AUDIT_SCAN_KEY_ENV = "FRIGATE_AUDIT_SCAN_KEY";
    private static final String AUDIT_SPEND_KEY_ENV = "FRIGATE_AUDIT_SPEND_KEY";

    private final DbManager dbManager;
    private volatile int lastBlockIndexed = -1;
    private final int batchSize;
    private final IndexMode indexMode;
    private final ECKey auditScanKey;
    private final ECKey auditSpendKey;

    public Index(int startHeight, boolean inMemory, int batchSize, IndexMode indexMode) {
        lastBlockIndexed = Math.max(lastBlockIndexed, startHeight - 1);
        this.batchSize = batchSize;
        this.indexMode = indexMode;

        String scanKeyHex = System.getenv(AUDIT_SCAN_KEY_ENV);
        String spendKeyHex = System.getenv(AUDIT_SPEND_KEY_ENV);
        if(scanKeyHex != null && spendKeyHex != null) {
            this.auditScanKey = ECKey.fromPrivate(Utils.hexToBytes(scanKeyHex));
            this.auditSpendKey = ECKey.fromPublicOnly(Utils.hexToBytes(spendKeyHex));
            log.warn("Scan audit mode enabled — output prefixes will be computed for the provided wallet keys");
        } else {
            this.auditScanKey = null;
            this.auditSpendKey = null;
        }

        if(inMemory) {
            dbManager = new MemoryDbManager();
        } else {
            String dbUrl = Config.get().getDatabase().getUrl();
            List<String> readDbUrls = Config.get().getDatabase().getReadUrls();
            if(dbUrl != null && readDbUrls != null && !readDbUrls.isEmpty()) {
                dbManager = new ScalingDbManager(dbUrl, readDbUrls);
            } else if(dbUrl == null) {
                File dbFile = new File(Storage.getFrigateDbDir(), DEFAULT_DB_FILENAME);
                dbManager = new SingleDbManager(DbManager.DB_PREFIX + dbFile.getAbsolutePath());
            } else {
                dbManager = new SingleDbManager(dbUrl);
            }
        }

        try {
            dbManager.executeWrite(connection -> {
                try(Statement stmt = connection.createStatement()) {
                    if(indexMode == IndexMode.UTXO_ONLY) {
                        return stmt.execute("CREATE TABLE IF NOT EXISTS " + UTXO_TABLE + " (txid BLOB NOT NULL, output_index INTEGER NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, compressed_tweak_key BLOB NOT NULL, output_hash_prefix BIGINT NOT NULL, value BIGINT NOT NULL, PRIMARY KEY (txid, output_index))");
                    } else {
                        return stmt.execute("CREATE TABLE IF NOT EXISTS " + TWEAK_TABLE + " (txid BLOB NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, outputs BIGINT[])");
                    }
                }
            });
        } catch(Exception e) {
            throw new ConfigurationException("Error initialising index", e);
        }

        if(!inMemory) {
            checkGpuBackend();
        }
    }

    private void checkGpuBackend() {
        ComputeBackend computeBackend = Config.get().getScan().getComputeBackendEnum();
        if(computeBackend == ComputeBackend.CPU) {
            return;
        }

        try {
            String backend = dbManager.executeRead(connection -> {
                try(Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT ufsecp_backend()")) {
                    return rs.next() ? rs.getString(1) : "unknown";
                }
            });

            if(backend.startsWith("cpu")) {
                if(computeBackend == ComputeBackend.GPU) {
                    throw new ConfigurationException("No GPU detected, but \"computeBackend\" is set to \"GPU\". Set to \"AUTO\" or \"CPU\", or install a supported GPU.");
                }
                log.info("Using CPU backend for scanning (no GPU detected)");
            } else {
                log.info("Using {} backend for scanning", backend);
            }
        } catch(Exception e) {
            log.warn("Could not detect GPU backend", e);
        }
    }

    private double pollScanProgress(byte[] scanKeyBytes) {
        try {
            return dbManager.executeRead(progressConnection -> {
                try(PreparedStatement progressStmt = progressConnection.prepareStatement("SELECT ufsecp_progress(?)")) {
                    progressStmt.setBytes(1, scanKeyBytes);
                    ResultSet rs = progressStmt.executeQuery();
                    if(rs.next()) {
                        double pct = rs.getDouble(1);
                        if(pct < 0.0d) {
                            return 0.0d;
                        }
                        return Math.min(pct / 100.0d, 1.0d);
                    }
                    return 0.0d;
                }
            });
        } catch(Exception e) {
            return 0.0d;
        }
    }

    public IndexMode getIndexMode() {
        return indexMode;
    }

    public <T> T executeRead(DbManager.ReadOperation<T> operation) throws SQLException, InterruptedException {
        return dbManager.executeRead(operation);
    }

    public void close() {
        dbManager.close();
    }

    public int getLastBlockIndexed() {
        // Check persisted height from Config (set by bootstrap or previous indexing)
        Integer configHeight = Config.get().getIndex().getLastIndexedBlockHeight();
        if (configHeight != null && configHeight > lastBlockIndexed) {
            lastBlockIndexed = configHeight;
        }

        // For UTXO_ONLY mode, Config is authoritative
        // (MAX(height) in UTXO table is creation height, not the height we've indexed up to)
        if (indexMode == IndexMode.UTXO_ONLY) {
            return lastBlockIndexed;
        }

        // For FULL mode, also check database MAX(height)
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT MAX(height) from " + TWEAK_TABLE)) {
                    ResultSet resultSet = statement.executeQuery();
                    return resultSet.next() ? Math.max(lastBlockIndexed, resultSet.getInt(1)) : lastBlockIndexed;
                }
            });
        } catch(Exception e) {
            log.error("Error getting last block indexed", e);
            return lastBlockIndexed;
        }
    }

    public void addToIndex(Map<BlockTransaction, byte[]> transactions) {
        if(dbManager.isShutdown()) {
            return;
        }

        int fromBlockHeight = lastBlockIndexed;
        try {
            lastBlockIndexed = dbManager.executeWrite(connection -> {
                DuckDBConnection duckDBConnection = (DuckDBConnection)connection;
                try(DuckDBAppender appender = duckDBConnection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, TWEAK_TABLE)) {
                    int blockHeight = -1;

                    for(BlockTransaction blkTx : transactions.keySet()) {
                        appender.beginRow();
                        appender.append(blkTx.getTransaction().getTxId().getBytes());
                        appender.append(blkTx.getHeight());
                        appender.append(transactions.get(blkTx));

                        List<Long> hashPrefixes = new ArrayList<>();
                        if(auditScanKey != null) {
                            long hashPrefix = getAuditHashPrefix(transactions, blkTx);
                            hashPrefixes.add(hashPrefix);
                        } else {
                            List<TransactionOutput> outputs = blkTx.getTransaction().getOutputs();
                            for(TransactionOutput output : outputs) {
                                if(ScriptType.P2TR.isScriptType(output.getScript())) {
                                    long hashPrefix = getHashPrefix(ScriptType.P2TR.getPublicKeyFromScript(output.getScript()).getPubKey(), 1);
                                    hashPrefixes.add(hashPrefix);
                                }
                            }
                        }
                        appender.append(hashPrefixes.stream().mapToLong(Long::longValue).toArray());
                        appender.endRow();

                        blockHeight = Math.max(blockHeight, blkTx.getHeight());
                    }

                    if(blockHeight <= 0 && lastBlockIndexed < 0) {
                        log.info("Indexed " + transactions.size() + " mempool transactions");
                    } else if(blockHeight > 0) {
                        log.info("Indexed " + transactions.size() + " transactions to block height " + blockHeight);
                    }

                    return blockHeight;
                }
            });

            if(lastBlockIndexed <= 0) {
                Frigate.getEventBus().post(new SilentPaymentsMempoolIndexAdded(transactions.keySet().stream().map(blkTx -> blkTx.getTransaction().getTxId()).collect(Collectors.toSet())));
            } else {
                Frigate.getEventBus().post(new SilentPaymentsBlocksIndexUpdate(fromBlockHeight + 1, lastBlockIndexed, transactions.size()));
            }
        } catch(Exception e) {
            log.error("Error adding to index", e);
        }
    }

    private long getAuditHashPrefix(Map<BlockTransaction, byte[]> transactions, BlockTransaction blkTx) {
        byte[] tweakKeyBytes = transactions.get(blkTx);
        ECKey tweakKey = ECKey.fromPublicOnly(compressRawKey(tweakKeyBytes));
        ECKey sharedSecret = tweakKey.multiply(auditScanKey.getPrivKey(), true);
        byte[] ser37 = new byte[37];
        System.arraycopy(sharedSecret.getPubKey(true), 0, ser37, 0, 33);
        byte[] t_k = Utils.taggedHash("BIP0352/SharedSecret", ser37);
        ECKey tkG = ECKey.fromPublicOnly(ECKey.publicKeyFromPrivate(new BigInteger(1, t_k), true));
        ECKey P0 = auditSpendKey.add(tkG, true);
        return getHashPrefix(P0.getPubKeyXCoord(), 0);
    }

    public void addUtxosToIndex(Map<BlockTransaction, byte[]> transactions, long minValue) {
        if(dbManager.isShutdown()) {
            return;
        }

        int fromBlockHeight = lastBlockIndexed;
        try {
            lastBlockIndexed = dbManager.executeWrite(connection -> {
                DuckDBConnection duckDBConnection = (DuckDBConnection)connection;
                try(DuckDBAppender appender = duckDBConnection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, UTXO_TABLE)) {
                    int blockHeight = -1;
                    int utxoCount = 0;

                    for(BlockTransaction blkTx : transactions.keySet()) {
                        byte[] tweakKey = transactions.get(blkTx);
                        List<TransactionOutput> outputs = blkTx.getTransaction().getOutputs();

                        for(int i = 0; i < outputs.size(); i++) {
                            TransactionOutput output = outputs.get(i);
                            if(ScriptType.P2TR.isScriptType(output.getScript()) && output.getValue() >= minValue) {
                                appender.beginRow();
                                appender.append(blkTx.getTransaction().getTxId().getBytes());
                                appender.append(i);
                                appender.append(blkTx.getHeight());
                                appender.append(tweakKey);
                                appender.append(compressRawKey(tweakKey));
                                long hashPrefix = getHashPrefix(ScriptType.P2TR.getPublicKeyFromScript(output.getScript()).getPubKey(), 1);
                                appender.append(hashPrefix);
                                appender.append(output.getValue());
                                appender.endRow();
                                utxoCount++;
                            }
                        }

                        blockHeight = Math.max(blockHeight, blkTx.getHeight());
                    }

                    if(blockHeight <= 0 && lastBlockIndexed < 0) {
                        log.info("Indexed " + utxoCount + " UTXOs from " + transactions.size() + " mempool transactions");
                    } else if(blockHeight > 0) {
                        log.info("Indexed " + utxoCount + " UTXOs from " + transactions.size() + " transactions to block height " + blockHeight);
                    }

                    return blockHeight;
                }
            });

            if(lastBlockIndexed <= 0) {
                Frigate.getEventBus().post(new SilentPaymentsMempoolIndexAdded(transactions.keySet().stream().map(blkTx -> blkTx.getTransaction().getTxId()).collect(Collectors.toSet())));
            } else {
                Frigate.getEventBus().post(new SilentPaymentsBlocksIndexUpdate(fromBlockHeight + 1, lastBlockIndexed, transactions.size()));

                // Persist the indexed height to Config for UTXO_ONLY mode
                Integer currentConfig = Config.get().getIndex().getLastIndexedBlockHeight();
                if(currentConfig == null || lastBlockIndexed > currentConfig) {
                    Config.get().getIndex().setLastIndexedBlockHeight(lastBlockIndexed);
                }
            }
        } catch(Exception e) {
            log.error("Error adding UTXOs to index", e);
        }
    }

    public void removeSpentUtxos(Set<HashIndex> spentOutpoints) {
        if(dbManager.isShutdown() || spentOutpoints.isEmpty()) {
            return;
        }

        try {
            int removed = dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + UTXO_TABLE + " WHERE txid = ? AND output_index = ?")) {
                    for(HashIndex outpoint : spentOutpoints) {
                        statement.setBytes(1, outpoint.getHash().getBytes());
                        statement.setInt(2, (int) outpoint.getIndex());
                        statement.addBatch();
                    }

                    int[] results = statement.executeBatch();
                    int count = 0;
                    for(int r : results) {
                        if(r > 0) count += r;
                    }
                    return count;
                }
            });

            if(removed > 0) {
                log.debug("Removed " + removed + " spent UTXOs from index");
            }
        } catch(Exception e) {
            log.error("Error removing spent UTXOs from index", e);
        }
    }

    public void removeFromIndex(int startHeight) {
        if(dbManager.isShutdown()) {
            return;
        }

        String table = (indexMode == IndexMode.UTXO_ONLY) ? UTXO_TABLE : TWEAK_TABLE;
        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE height >= ?")) {
                    statement.setInt(1, startHeight);
                    return statement.execute();
                }
            });
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public void removeFromIndex(Set<Sha256Hash> txIds) {
        if(dbManager.isShutdown()) {
            return;
        }

        String table = (indexMode == IndexMode.UTXO_ONLY) ? UTXO_TABLE : TWEAK_TABLE;
        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE txid = ?")) {
                    for(Sha256Hash txId : txIds) {
                        statement.setBytes(1, txId.getBytes());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                    return txIds.size();
                }
            });

            Frigate.getEventBus().post(new SilentPaymentsMempoolIndexRemoved(txIds));
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public List<TxEntry> getHistoryAsync(SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        ConcurrentLinkedQueue<TxEntry> queue = new ConcurrentLinkedQueue<>();
        byte[] scanKeyBytes = Utils.reverseBytes(scanAddress.getScanKey().getPrivKeyBytes());

        try {
            dbManager.executeRead(connection -> {
                String sql = getSql(subscription, startHeight, endHeight);

                try(DuckDBPreparedStatement statement = connection.prepareStatement(sql).unwrap(DuckDBPreparedStatement.class)) {
                    if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                        return false;
                    }

                    bindParameters(statement, scanAddress, subscription, startHeight, endHeight);

                    try(ScheduledThreadPoolExecutor queryProgressExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQueryProgress-%d").build();
                        Thread t = namedThreadFactory.newThread(r);
                        t.setDaemon(true);
                        return t;
                    })) {
                        queryProgressExecutor.scheduleAtFixedRate(() -> {
                            try {
                                if(dbManager.isShutdown() || isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                                    statement.cancel();
                                    queryProgressExecutor.shutdownNow();
                                    return;
                                }

                                double progress = pollScanProgress(scanKeyBytes);

                                List<TxEntry> history = new ArrayList<>();
                                TxEntry entry;
                                while((entry = queue.poll()) != null) {
                                    history.add(entry);
                                    if(history.size() >= HISTORY_PAGE_SIZE) {
                                        Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                        history.clear();
                                    }
                                }
                                if(!history.isEmpty() || queryProgressExecutor.getTaskCount() % 5 == 0) {
                                    Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                    history.clear();
                                }
                            } catch(Exception e) {
                                log.error("Error getting query progress", e);
                            }
                        }, 1, 1, TimeUnit.SECONDS);

                        ResultSet resultSet = statement.executeQuery();
                        while(resultSet.next()) {
                            byte[] txid = resultSet.getBytes(1);
                            int height;
                            if(indexMode == IndexMode.UTXO_ONLY) {
                                byte[] compressed_tweak_key = resultSet.getBytes(2);
                                height = resultSet.getInt(3);
                                int outputIndex = resultSet.getInt(4);
                                queue.offer(new TxEntry(height, 0, Utils.bytesToHex(txid), Utils.bytesToHex(compressed_tweak_key), outputIndex));
                            } else {
                                byte[] tweak_key = compressRawKey(resultSet.getBytes(2));
                                height = resultSet.getInt(3);
                                queue.offer(new TxEntry(height, 0, Utils.bytesToHex(txid), Utils.bytesToHex(tweak_key)));
                            }
                        }
                    }
                }

                return true;
            });
        } catch(SQLTimeoutException e) {
            if(e.getMessage().startsWith("INTERRUPT Error")) {
                log.debug("Query cancelled", e);
            } else {
                log.error("Query timeout", e);
            }
            return Collections.emptyList();
        } catch(Exception e) {
            log.error("Error scanning index", e);
            return Collections.emptyList();
        }

        if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
            return Collections.emptyList();
        }

        List<TxEntry> history = new ArrayList<>();
        TxEntry entry;
        while((entry = queue.poll()) != null) {
            history.add(entry);
        }

        return history;
    }

    private String getSql(SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight) {
        String labelsStr = "[" + String.join(", ", Collections.nCopies(subscription.labels().length, "?")) + "]";

        if(indexMode == IndexMode.UTXO_ONLY) {
            String sql = "SELECT txid, compressed_tweak_key, height, output_index FROM " + UTXO_TABLE +
                    " WHERE scan_silent_payments([output_hash_prefix], [?, ?, tweak_key], " + labelsStr + ")";

            if(startHeight != null) {
                sql += " AND height >= ?";
            }
            if(endHeight != null) {
                sql += " AND height <= ?";
            }

            sql += " ORDER BY height";
            return sql;
        }

        String sql = "SELECT txid, tweak_key, height FROM ufsecp_scan((SELECT txid, height, tweak_key, outputs FROM " + TWEAK_TABLE;

        if(startHeight != null || endHeight != null) {
            sql += " WHERE ";
        }

        if(startHeight != null) {
            sql += "height >= ?";
            if(endHeight != null) {
                sql += " AND ";
            }
        }

        if(endHeight != null) {
            sql += "height <= ?";
        }

        sql += "), ?, ?, " + labelsStr + ", batch_size := ?";

        ComputeBackend computeBackend = Config.get().getScan().getComputeBackendEnum();
        if(computeBackend != ComputeBackend.AUTO) {
            sql += ", backend := ?";
        }

        sql += ") ORDER BY height";

        return sql;
    }

    private void bindParameters(DuckDBPreparedStatement statement, SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight) throws SQLException {
        int index = 1;
        if(indexMode == IndexMode.UTXO_ONLY) {
            statement.setBytes(index++, scanAddress.getScanKey().getPrivKeyBytes());
            statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getSpendKey()));
            for(Integer label : subscription.labels()) {
                statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getLabelledTweakKey(label)));
            }
            if(startHeight != null) {
                statement.setInt(index++, startHeight);
            }
            if(endHeight != null) {
                statement.setInt(index, endHeight);
            }
            return;
        }

        if(startHeight != null) {
            statement.setInt(index++, startHeight);
        }
        if(endHeight != null) {
            statement.setInt(index++, endHeight);
        }
        statement.setBytes(index++, Utils.reverseBytes(scanAddress.getScanKey().getPrivKeyBytes()));
        statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getSpendKey()));
        for(Integer label : subscription.labels()) {
            statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getLabelledTweakKey(label)));
        }
        statement.setInt(index++, batchSize);

        ComputeBackend computeBackend = Config.get().getScan().getComputeBackendEnum();
        if(computeBackend != ComputeBackend.AUTO) {
            statement.setString(index, computeBackend.toSqlValue());
        }
    }

    private static boolean isUnsubscribed(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        SubscriptionStatus status = subscriptionStatusRef.get();
        return status == null || !status.isConnected() || !status.isSilentPaymentsAddressSubscribed(scanAddress.toString());
    }

    public static long getHashPrefix(byte[] hash, int offset) {
        if(hash.length < 8 + offset) {
            throw new IllegalArgumentException("Hash must be at least 8 bytes long from the offset");
        }

        long result = 0;
        // Process 8 bytes from the offset in big-endian order
        for (int i = offset; i < 8 + offset; i++) {
            result = (result << 8) | (hash[i] & 0xFF);
        }
        return result;
    }

    public static byte[] compressRawKey(byte[] rawUncompressed) {
        byte[] uncompressed = new byte[64];
        System.arraycopy(rawUncompressed, 0, uncompressed, 32, 32);
        System.arraycopy(rawUncompressed, 32, uncompressed, 0, 32);
        uncompressed = Utils.reverseBytes(uncompressed);

        ECKey ecKey = ECKey.fromPublicOnly(Utils.concat(new byte[] {0x04}, uncompressed));
        return ecKey.getPubKey(true);
    }
}
