package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanTxOutSetResult(
    boolean success,
    int txouts,
    int height,
    String bestblock,
    List<ScanTxOutSetUtxo> unspents,
    double total_amount  // in BTC from Bitcoin Core
) {}
