package com.sparrowwallet.frigate.electrum;

public record UnspentOutput(int height, int tx_pos, String tx_hash, long value) {}
