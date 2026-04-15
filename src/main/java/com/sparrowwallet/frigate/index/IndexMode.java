package com.sparrowwallet.frigate.index;

public enum IndexMode {
    FULL,      // Current behavior - indexes all transactions
    UTXO_ONLY  // Only tracks unspent outputs
}
