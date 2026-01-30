package com.sparrowwallet.frigate.bitcoind;

import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bootstraps the UTXO index from Bitcoin Core's current chainstate using dumptxoutset.
 * This is much faster than processing all historical blocks from taproot activation.
 *
 * Requirements:
 * - Bitcoin Core with txindex=1 enabled (for fetching full transactions)
 * - Index must be in UTXO_ONLY mode
 * - Sufficient disk space for temporary snapshot file
 */
public class UtxoBootstrap {
    private static final Logger log = LoggerFactory.getLogger(UtxoBootstrap.class);
    private static final int BATCH_SIZE = 100;
    private static final String SNAPSHOT_FILENAME = "frigate-bootstrap-utxos.dat";

    private final BitcoindClientService bitcoindService;
    private final Index index;
    private final long minValue;

    public UtxoBootstrap(BitcoindClientService bitcoindService, Index index, long minValue) {
        this.bitcoindService = bitcoindService;
        this.index = index;
        this.minValue = minValue;
    }

    public void run() {
        log.info("Starting UTXO bootstrap using dumptxoutset...");
        log.info("Minimum UTXO value: {} sats", minValue);

        File snapshotFile = new File(Storage.getFrigateHome(), SNAPSHOT_FILENAME);

        try {
            // Step 1: Create UTXO snapshot
            log.info("[1/5] Creating UTXO snapshot (this may take several minutes)...");
            DumpTxOutSetResult dumpResult = createSnapshot(snapshotFile);
            if (dumpResult == null) {
                log.error("Failed to create UTXO snapshot");
                return;
            }
            log.info("Snapshot created: {} coins at height {}", dumpResult.coins_written(), dumpResult.base_height());

            // Step 2: Parse snapshot for P2TR UTXOs
            log.info("[2/5] Parsing snapshot for P2TR UTXOs...");
            List<UtxoSnapshotParser.ParsedUtxo> p2trUtxos = new ArrayList<>();
            UtxoSnapshotParser parser = new UtxoSnapshotParser(snapshotFile);
            parser.parseP2TRUtxos(minValue, p2trUtxos::add);

            if (p2trUtxos.isEmpty()) {
                log.warn("No P2TR UTXOs found above {} sats", minValue);
                log.info("Snapshot file kept at: {}", snapshotFile.getAbsolutePath());
                return;
            }
            log.info("Found {} P2TR UTXOs to process", p2trUtxos.size());

            // Step 3: Group by txid
            log.info("[3/5] Grouping by transaction...");
            Map<String, List<UtxoSnapshotParser.ParsedUtxo>> utxosByTxid = groupByTxid(p2trUtxos);
            log.info("Found {} unique transactions", utxosByTxid.size());

            // Step 4: Fetch transactions and compute tweaks
            log.info("[4/5] Fetching transactions and computing tweaks...");
            processTransactions(utxosByTxid);

            // Record the snapshot height for future indexing
            String baseBlockHash = parser.getBaseBlockHash();
            VerboseBlockHeader blockHeader = bitcoindService.getBlockHeader(baseBlockHash);
            int snapshotHeight = blockHeader.height();
            Config.get().setLastIndexedBlockHeight(snapshotHeight);
            log.info("Recorded snapshot height {} for future indexing", snapshotHeight);

            // Step 5: Cleanup
            log.info("[5/5] Bootstrap complete!");
            cleanup(snapshotFile);

        } catch (IOException e) {
            log.error("Bootstrap failed: {}", e.getMessage(), e);
            log.info("Snapshot file kept for debugging: {}", snapshotFile.getAbsolutePath());
        }
    }

    private DumpTxOutSetResult createSnapshot(File snapshotFile) {
        try {
            // Reuse existing snapshot file if present (for development/debugging)
            if (snapshotFile.exists() && snapshotFile.length() > 0) {
                log.info("Reusing existing snapshot file: {} ({} bytes)",
                        snapshotFile.getAbsolutePath(), snapshotFile.length());
                // Return a placeholder result - we don't know the exact stats but that's OK
                return new DumpTxOutSetResult(0, null, 0, snapshotFile.getAbsolutePath(), null, 0);
            }

            log.info("Calling dumptxoutset to: {}", snapshotFile.getAbsolutePath());
            log.info("This operation can take 5-15 minutes depending on UTXO set size...");

            // Use "latest" type to dump current UTXO set
            DumpTxOutSetResult result = bitcoindService.dumpTxOutSet(snapshotFile.getAbsolutePath(), "latest");

            if (result == null || result.coins_written() == 0) {
                log.error("dumptxoutset returned no coins");
                return null;
            }

            return result;

        } catch (Exception e) {
            log.error("Error creating UTXO snapshot: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, List<UtxoSnapshotParser.ParsedUtxo>> groupByTxid(List<UtxoSnapshotParser.ParsedUtxo> utxos) {
        Map<String, List<UtxoSnapshotParser.ParsedUtxo>> grouped = new LinkedHashMap<>();
        for (UtxoSnapshotParser.ParsedUtxo utxo : utxos) {
            grouped.computeIfAbsent(utxo.txid(), k -> new ArrayList<>()).add(utxo);
        }
        return grouped;
    }

    private void processTransactions(Map<String, List<UtxoSnapshotParser.ParsedUtxo>> utxosByTxid) {
        List<String> txids = new ArrayList<>(utxosByTxid.keySet());
        int total = txids.size();
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger indexed = new AtomicInteger(0);
        HexFormat hexFormat = HexFormat.of();

        // Process in batches
        for (int i = 0; i < txids.size(); i += BATCH_SIZE) {
            List<String> batch = txids.subList(i, Math.min(i + BATCH_SIZE, txids.size()));

            // Batch fetch transactions using parallel streams
            Map<String, Transaction> txMap = batchFetchTransactions(batch, hexFormat);

            // Collect all input outpoints we need to look up
            Set<String> prevTxids = new HashSet<>();
            for (Transaction tx : txMap.values()) {
                if (!tx.isCoinBase()) {
                    for (TransactionInput input : tx.getInputs()) {
                        prevTxids.add(input.getOutpoint().getHash().toString());
                    }
                }
            }

            // Batch fetch previous transactions for scriptPubKeys
            Map<String, Transaction> prevTxMap = batchFetchTransactions(new ArrayList<>(prevTxids), hexFormat);

            // Process each transaction and compute tweaks
            Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();

            for (String txid : batch) {
                Transaction tx = txMap.get(txid);
                if (tx == null) {
                    log.debug("Could not fetch transaction {}, skipping", txid);
                    continue;
                }

                // Skip coinbase transactions (they can't have tweaks computed)
                if (tx.isCoinBase()) {
                    continue;
                }

                List<UtxoSnapshotParser.ParsedUtxo> outputs = utxosByTxid.get(txid);
                int height = outputs.get(0).height();

                // Build spent scriptPubKeys map from previous transactions
                Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
                boolean allInputsResolved = true;

                for (TransactionInput input : tx.getInputs()) {
                    HashIndex hashIndex = new HashIndex(input.getOutpoint().getHash(), input.getOutpoint().getIndex());
                    Transaction prevTx = prevTxMap.get(input.getOutpoint().getHash().toString());

                    if (prevTx != null && input.getOutpoint().getIndex() < prevTx.getOutputs().size()) {
                        TransactionOutput prevOutput = prevTx.getOutputs().get((int) input.getOutpoint().getIndex());
                        spentScriptPubKeys.put(hashIndex, prevOutput.getScript());
                    } else {
                        log.debug("Could not resolve input {}:{} for tx {}",
                                input.getOutpoint().getHash(), input.getOutpoint().getIndex(), txid);
                        allInputsResolved = false;
                        break;
                    }
                }

                if (!allInputsResolved) {
                    continue;
                }

                // Compute tweak
                byte[] tweak = SilentPaymentUtils.getTweak(tx, spentScriptPubKeys, false);
                if (tweak != null) {
                    BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), height, null, 0L, tx, null);
                    eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
                }
            }

            // Insert batch into index
            if (!eligibleTransactions.isEmpty()) {
                index.addUtxosToIndex(eligibleTransactions, minValue);
                indexed.addAndGet(eligibleTransactions.size());
            }

            processed.addAndGet(batch.size());
            int pct = (processed.get() * 100) / total;
            if (processed.get() % 1000 == 0 || processed.get() == total) {
                log.info("Progress: {}/{} transactions ({}%), {} eligible transactions indexed",
                        processed.get(), total, pct, indexed.get());
            }
        }

        log.info("Processed {} transactions, indexed {} eligible transactions", total, indexed.get());
    }

    private Map<String, Transaction> batchFetchTransactions(List<String> txids, HexFormat hexFormat) {
        Map<String, Transaction> result = new ConcurrentHashMap<>();

        // Use parallel stream for concurrent fetches
        txids.parallelStream().forEach(txid -> {
            try {
                Object response = bitcoindService.getRawTransaction(txid, false);
                if (response instanceof String hex) {
                    Transaction tx = new Transaction(hexFormat.parseHex(hex));
                    result.put(txid, tx);
                }
            } catch (Exception e) {
                // Transaction might not be found (shouldn't happen with txindex=1)
                log.trace("Failed to fetch tx {}: {}", txid, e.getMessage());
            }
        });

        return result;
    }

    private void cleanup(File snapshotFile) {
        if (snapshotFile.exists()) {
            if (snapshotFile.delete()) {
                log.info("Cleaned up snapshot file: {}", snapshotFile.getAbsolutePath());
            } else {
                log.warn("Could not delete snapshot file: {}", snapshotFile.getAbsolutePath());
            }
        }
    }
}
