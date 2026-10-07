package com.wontlost.web3.nft;

/** NFT RPC 查询的资源上限。 */
public final class NftQueryLimits {
    public static final int MAX_CONCURRENCY = 64;
    public static final int MAX_BATCH_SIZE = 500;
    public static final int MAX_PAGE_SIZE = 500;
    public static final int MAX_TOKEN_IDS = 10_000;
    public static final int MAX_QUEUE_CAPACITY = 4_096;
    public static final int DEFAULT_QUEUE_MULTIPLIER = 16;

    private NftQueryLimits() {
    }
}
