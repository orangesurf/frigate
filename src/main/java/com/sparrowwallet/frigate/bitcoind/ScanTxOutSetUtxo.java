package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowwallet.drongo.protocol.Transaction;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanTxOutSetUtxo(
    String txid,
    int vout,
    String scriptPubKey,
    String desc,
    double amount,  // in BTC from Bitcoin Core
    int height
) {
    /**
     * Returns the amount in satoshis.
     */
    public long getAmountSats() {
        return (long) (amount * Transaction.SATOSHIS_PER_BITCOIN);
    }
}
