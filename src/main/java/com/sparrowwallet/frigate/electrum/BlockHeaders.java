package com.sparrowwallet.frigate.electrum;

import java.util.List;

public record BlockHeaders(int count, String hex, int max, String root, List<String> branch) {}
