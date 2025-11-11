package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record PackageResult(String package_msg, @JsonProperty("tx-results") Map<String, TxResult> txResults, @JsonProperty("replaced-transactions") List<String> replacedTransactions) {
    public record TxResult(String txid, @JsonProperty("other-wtxid") String otherWtxid, Integer vsize, Fees fees, String error) {}
    public record Fees(Double base, @JsonProperty("effective-feerate") Double effectiveFeerate, @JsonProperty("effective-includes") List<String> effectiveIncludes) {}
}
