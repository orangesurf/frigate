package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.bitcoind.PackageResult;

public record PackageResultSummary(boolean success, PackageResultError[] errors) {
    public record PackageResultError(String txid, String error) {}

    public static PackageResultSummary fromPackageResult(PackageResult result) {
        return new PackageResultSummary("success".equals(result.package_msg()),
                result.txResults().values().stream()
                    .filter(r -> r.error() != null)
                    .map(r -> new PackageResultError(r.txid(), r.error()))
                    .toArray(PackageResultError[]::new));
    }
}
