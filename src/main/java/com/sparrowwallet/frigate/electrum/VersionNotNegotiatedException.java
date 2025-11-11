package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;

@JsonRpcError(code=-1, message="server.version must be the first message")
public class VersionNotNegotiatedException extends RuntimeException {}
