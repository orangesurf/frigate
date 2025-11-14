package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowwallet.drongo.protocol.Transaction;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MempoolInfo(double mempoolminfee, double minrelaytxfee, double incrementalrelayfee) {
    public static final double DEFAULT_FEE_RATE = (Transaction.DEFAULT_MIN_RELAY_FEE / Transaction.SATOSHIS_PER_BITCOIN) * 1000;
    public static final MempoolInfo DEFAULT = new MempoolInfo(DEFAULT_FEE_RATE, DEFAULT_FEE_RATE, DEFAULT_FEE_RATE);
}
