package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DumpTxOutSetResult(
    long coins_written,
    String base_hash,
    int base_height,
    String path,
    String txoutset_hash,
    long nchaintx
) {}
