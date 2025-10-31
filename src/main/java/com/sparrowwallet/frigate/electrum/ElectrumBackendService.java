package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.frigate.index.TxEntry;

import java.util.Collection;
import java.util.List;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumBackendService {
    @JsonRpcMethod("mempool.get_fee_histogram")
    List<List<Number>> getFeeHistogram();

    @JsonRpcMethod("blockchain.scripthash.subscribe")
    String subscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.unsubscribe")
    String unsubscribeScriptHash(@JsonRpcParam("scripthash") String scriptHash);

    @JsonRpcMethod("blockchain.scripthash.get_history")
    Collection<TxEntry> getHistory(@JsonRpcParam("scripthash") String scriptHash);
}
