package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public record ServerPeer(String ip, String hostname, List<String> features) {}
