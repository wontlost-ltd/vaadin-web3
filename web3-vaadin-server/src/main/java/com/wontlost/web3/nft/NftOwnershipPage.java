package com.wontlost.web3.nft;

import java.util.List;

/**
 * 持有记录分页及集合级部分失败。
 * <p>
 * {@code snapshotBlock} 为本页所对应的区块：内置 RPC 实现在同一快照区块上完成整次分页；商业索引器可能逐页对应不同区块，
 * 不提供区块的供应商以 {@code 0} 表示“未提供”。
 */
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
