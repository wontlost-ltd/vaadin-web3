package com.wontlost.web3.nft;

import java.util.List;

/** 单一区块上的持有记录分页及集合级部分失败。 */
public record NftOwnershipPage(List<NftHolding> holdings, String nextCursor, long snapshotBlock,
        List<NftOwnershipFailure> failures) {
    public NftOwnershipPage {
        holdings = List.copyOf(holdings);
        failures = List.copyOf(failures);
        if (snapshotBlock < 0) {
            throw new IllegalArgumentException("Snapshot block must be non-negative");
        }
    }
}
