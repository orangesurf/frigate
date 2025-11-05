package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface ElectrumClientService {
    @JsonRpcMethod("blockchain.silentpayments.subscribe")
    String subscribeSilentPayments(@JsonRpcParam("scan_private_key") String scanPrivateKey, @JsonRpcParam("spend_public_key") String spendPublicKey, @JsonRpcParam("start") @JsonRpcOptional Long start, @JsonRpcParam("labels") @JsonRpcOptional Integer[] labels);
}
