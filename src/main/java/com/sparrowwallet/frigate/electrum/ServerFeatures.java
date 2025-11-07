package com.sparrowwallet.frigate.electrum;

import java.util.Map;

public record ServerFeatures(Map<String, HostInfo> hosts, String genesis_hash, String hash_function, String server_version, String protocol_max, String protocol_min, Integer pruning) {
    public record HostInfo(Integer tcp_port, Integer ssl_port) {}
}
