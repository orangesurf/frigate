package com.sparrowwallet.frigate.electrum;

public record SilentPaymentsSubscription(String address, Integer[] labels, int start_height) {

}
