package com.sparrowwallet.frigate.io;

public enum ComputeBackend {
    AUTO, GPU, CPU;

    public String toSqlValue() {
        return name().toLowerCase();
    }
}
