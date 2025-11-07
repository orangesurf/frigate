package com.sparrowwallet.frigate.electrum;

import java.util.List;

public record TransactionMerkle(int block_height, List<String> merkle, int pos) {}
