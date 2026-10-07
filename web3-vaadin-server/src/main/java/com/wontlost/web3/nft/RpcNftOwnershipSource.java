package com.wontlost.web3.nft;

import java.io.Closeable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.EthRpcException;

/** 通过已配置的 EVM RPC 节点直接读取 NFT 所有权。 */
public final class RpcNftOwnershipSource implements NftOwnershipSource, Closeable {
    private static final System.Logger LOGGER = System.getLogger(RpcNftOwnershipSource.class.getName());

    public static final String ERC721_INTERFACE = "0x80ac58cd";
    public static final String ERC721_ENUMERABLE_INTERFACE = "0x780e9d63";
    public static final String ERC1155_INTERFACE = "0xd9b67a26";

    private final ChainRegistry chains;
    private final int maxConcurrency;
    private final int maxPageSize;
    private final int batchSize;
    private final int maxTokenIds;
    private final ThreadPoolExecutor executor;
    private final Cache<InterfaceCacheKey, InterfaceCapabilities> interfaceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public RpcNftOwnershipSource(ChainRegistry chains) {
        this(chains, 8, 100, 100, 1000, 128);
    }

    public RpcNftOwnershipSource(ChainRegistry chains, int maxConcurrency, int maxPageSize, int batchSize,
            int maxTokenIds) {
        this(chains, maxConcurrency, maxPageSize, batchSize, maxTokenIds,
                Math.max(1, maxConcurrency) * NftQueryLimits.DEFAULT_QUEUE_MULTIPLIER);
    }

    public RpcNftOwnershipSource(ChainRegistry chains, int maxConcurrency, int maxPageSize, int batchSize,
            int maxTokenIds, int queueCapacity) {
        this.chains = java.util.Objects.requireNonNull(chains);
        requireLimit("max-concurrency", maxConcurrency, NftQueryLimits.MAX_CONCURRENCY);
        requireLimit("max-page-size", maxPageSize, NftQueryLimits.MAX_PAGE_SIZE);
        requireLimit("batch-size", batchSize, NftQueryLimits.MAX_BATCH_SIZE);
        requireLimit("max-token-ids", maxTokenIds, NftQueryLimits.MAX_TOKEN_IDS);
        requireLimit("queue-capacity", queueCapacity, NftQueryLimits.MAX_QUEUE_CAPACITY);
        this.maxConcurrency = maxConcurrency;
        this.maxPageSize = maxPageSize;
        this.batchSize = batchSize;
        this.maxTokenIds = maxTokenIds;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(maxConcurrency, maxConcurrency, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
            Thread thread = new Thread(task, "web3-nft-rpc-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private void requireLimit(String name, int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("web3.nft." + name + " must be between 1 and " + maximum);
        }
    }

    @Override
    public NftOwnershipPage find(long chainId, String owner, List<NftCollection> collections, String cursor,
            int pageSize) {
        String normalizedOwner = NftAddress.normalize(owner);
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new IllegalArgumentException("Page size must be between 1 and " + maxPageSize);
        }
        List<NftCollection> requested = List.copyOf(collections);
        Cursor start = decodeCursor(cursor, chainId, normalizedOwner, requested);
        EthRpcClient unpinned = chains.get(chainId).orElse(null);
        if (unpinned == null) {
            return failedPage(chainId, requested, NftFailureCode.UNAVAILABLE);
        }
        EthRpcClient rpc = unpinned.pinned();
        long currentBlock;
        try {
            currentBlock = rpc.blockNumber();
        } catch (RuntimeException exception) {
            logFailureType(exception);
            return failedPage(chainId, requested, NftFailureCode.UNAVAILABLE);
        }
        try {
            return findAtSnapshot(chainId, normalizedOwner, requested, start, pageSize, rpc, currentBlock);
        } catch (RejectedExecutionException exception) {
            logFailureType(exception);
            return failedPage(chainId, requested, NftFailureCode.UNAVAILABLE);
        }
    }

    private NftOwnershipPage findAtSnapshot(long chainId, String normalizedOwner, List<NftCollection> requested,
            Cursor start, int pageSize, EthRpcClient rpc, long currentBlock) {
        long snapshotBlock = start.snapshotBlock();
        if (snapshotBlock < 0) {
            snapshotBlock = currentBlock;
        } else if (snapshotBlock > currentBlock) {
            throw new InvalidOwnershipCursorException();
        }

        List<NftHolding> holdings = new ArrayList<>();
        List<NftOwnershipFailure> failures = new ArrayList<>();
        int tokenIdsQueried = 0;
        for (int collectionIndex = start.collectionIndex(); collectionIndex < requested.size(); collectionIndex++) {
            NftCollection collection = requested.get(collectionIndex);
            if (collection.chainId() != chainId) {
                failures.add(failure(chainId, collection, NftFailureCode.INVALID_COLLECTION));
                continue;
            }
            BigInteger offset = collectionIndex == start.collectionIndex() ? start.position() : BigInteger.ZERO;
            if (tokenIdsQueried == maxTokenIds) {
                return new NftOwnershipPage(holdings, encodeCursor(chainId, normalizedOwner, requested,
                        collectionIndex, offset, snapshotBlock), snapshotBlock, failures);
            }
            try {
                if (!collection.enumerable() && collection.tokenIds().isEmpty() && collection.tokenIdRanges().isEmpty()) {
                    throw new UnsupportedException("Collection has no explicit token IDs and cannot enumerate ownership");
                }
                requireSupported(rpc, collection, snapshotBlock);
                CollectionPage page = collection.standard() == NftStandard.ERC721 && collection.enumerable()
                        ? enumerablePage(rpc, collection, normalizedOwner, snapshotBlock, offset,
                                pageSize - holdings.size(), maxTokenIds - tokenIdsQueried)
                        : explicitPage(rpc, collection, normalizedOwner, snapshotBlock, offset,
                                pageSize - holdings.size(), maxTokenIds - tokenIdsQueried);
                tokenIdsQueried += page.scannedTokenIds();
                holdings.addAll(page.holdings());
                if (page.nextPosition() != null) {
                    return new NftOwnershipPage(holdings, encodeCursor(chainId, normalizedOwner, requested,
                            collectionIndex, page.nextPosition(), snapshotBlock), snapshotBlock, failures);
                }
                if (holdings.size() == pageSize && collectionIndex + 1 < requested.size()) {
                    return new NftOwnershipPage(holdings, encodeCursor(chainId, normalizedOwner, requested,
                            collectionIndex + 1, BigInteger.ZERO, snapshotBlock), snapshotBlock, failures);
                }
            } catch (InvalidOwnershipCursorException exception) {
                throw exception;
            } catch (RejectedExecutionException exception) {
                throw exception;
            } catch (UnsupportedException exception) {
                failures.add(failure(chainId, collection, NftFailureCode.UNSUPPORTED));
            } catch (IllegalArgumentException exception) {
                logFailureType(exception);
                failures.add(failure(chainId, collection, NftFailureCode.INVALID_RESPONSE));
            } catch (RuntimeException exception) {
                logFailureType(exception);
                failures.add(failure(chainId, collection, NftFailureCode.UNAVAILABLE));
            }
            if (holdings.size() >= pageSize) {
                if (collectionIndex + 1 < requested.size()) {
                    return new NftOwnershipPage(holdings, encodeCursor(chainId, normalizedOwner, requested,
                            collectionIndex + 1, BigInteger.ZERO, snapshotBlock), snapshotBlock, failures);
                }
                return new NftOwnershipPage(holdings, null, snapshotBlock, failures);
            }
        }
        return new NftOwnershipPage(holdings, null, snapshotBlock, failures);
    }

    private void requireSupported(EthRpcClient rpc, NftCollection collection, long snapshotBlock) {
        InterfaceCacheKey key = new InterfaceCacheKey(collection.chainId(), collection.contract());
        InterfaceCapabilities capabilities = interfaceCache.get(key,
                ignored -> readCapabilities(rpc, collection.contract(), snapshotBlock));
        boolean supported = collection.standard() == NftStandard.ERC721
                ? capabilities.erc721() : capabilities.erc1155();
        if (!supported) {
            throw new UnsupportedException("Collection does not support " + collection.standard());
        }
        if (collection.enumerable() && !capabilities.enumerable()) {
            throw new UnsupportedException("Collection does not support ERC-721 Enumerable");
        }
    }

    private InterfaceCapabilities readCapabilities(EthRpcClient rpc, String contract, long snapshotBlock) {
        try {
            boolean erc721 = readSupport(rpc, contract, ERC721_INTERFACE, snapshotBlock);
            boolean enumerable = readSupport(rpc, contract, ERC721_ENUMERABLE_INTERFACE, snapshotBlock);
            boolean erc1155 = readSupport(rpc, contract, ERC1155_INTERFACE, snapshotBlock);
            return new InterfaceCapabilities(erc721, enumerable, erc1155);
        } catch (EthRpcException exception) {
            if (isExecutionRevert(exception)) {
                throw new UnsupportedException("Collection does not implement supportsInterface");
            }
            throw exception;
        }
    }

    private boolean readSupport(EthRpcClient rpc, String contract, String interfaceId, long snapshotBlock) {
        return NftAbi.decodeBool(call(rpc, contract, NftAbi.supportsInterfaceData(interfaceId), snapshotBlock));
    }

    private CollectionPage explicitPage(EthRpcClient rpc, NftCollection collection, String owner, long snapshotBlock,
            BigInteger offset, int remainingPageSize, int remainingTokenLimit) {
        BigInteger totalCount = explicitTokenCount(collection);
        BigInteger start = offset.min(totalCount);
        BigInteger count = BigInteger.valueOf(Math.min(remainingPageSize, remainingTokenLimit));
        BigInteger end = start.add(count).min(totalCount);
        List<BigInteger> pageIds = explicitTokenIds(collection, start, end);
        List<NftHolding> holdings = collection.standard() == NftStandard.ERC1155
                ? queryErc1155(rpc, collection, owner, snapshotBlock, pageIds)
                : queryErc721(rpc, collection, owner, snapshotBlock, pageIds);
        BigInteger next = end.compareTo(totalCount) < 0 ? end : null;
        return new CollectionPage(holdings, next, pageIds.size());
    }

    private CollectionPage enumerablePage(EthRpcClient rpc, NftCollection collection, String owner,
            long snapshotBlock, BigInteger offset, int remainingPageSize, int remainingTokenLimit) {
        BigInteger count = NftAbi.decodeUint(call(rpc, collection.contract(), NftAbi.balanceOfData(owner), snapshotBlock));
        if (offset.compareTo(count) > 0) {
            throw new InvalidOwnershipCursorException();
        }
        BigInteger start = offset;
        BigInteger pageLimit = BigInteger.valueOf(Math.min(remainingPageSize, remainingTokenLimit));
        BigInteger end = start.add(pageLimit).min(count);
        List<BigInteger> indices = new ArrayList<>();
        for (BigInteger index = start; index.compareTo(end) < 0; index = index.add(BigInteger.ONE)) {
            indices.add(index);
        }
        List<BigInteger> ids = parallelMap(indices, index -> NftAbi.decodeUint(call(rpc, collection.contract(),
                NftAbi.tokenOfOwnerByIndexData(owner, index), snapshotBlock)));
        List<NftHolding> holdings = ids.stream().map(id -> new NftHolding(collection.chainId(), collection.contract(),
                id, BigInteger.ONE, NftStandard.ERC721)).toList();
        return new CollectionPage(holdings, end.compareTo(count) < 0 ? end : null, indices.size());
    }

    private List<NftHolding> queryErc721(EthRpcClient rpc, NftCollection collection, String owner,
            long snapshotBlock, List<BigInteger> ids) {
        List<NftHolding> holdings = new ArrayList<>();
        for (int offset = 0; offset < ids.size(); offset += maxConcurrency) {
            List<BigInteger> work = ids.subList(offset, Math.min(offset + maxConcurrency, ids.size()));
            List<Optional<BigInteger>> owned = parallelMap(work, id -> {
                try {
                    String tokenOwner = NftAbi.decodeAddress(call(rpc, collection.contract(), NftAbi.ownerOfData(id), snapshotBlock));
                    return tokenOwner.equalsIgnoreCase(owner) ? Optional.of(id) : Optional.empty();
                } catch (EthRpcException exception) {
                    if (isExecutionRevert(exception)) {
                        return Optional.empty();
                    }
                    throw exception;
                }
            });
            for (Optional<BigInteger> id : owned) {
                if (id.isPresent()) {
                    holdings.add(new NftHolding(collection.chainId(), collection.contract(), id.get(), BigInteger.ONE,
                            NftStandard.ERC721));
                }
            }
        }
        return List.copyOf(holdings);
    }

    private List<NftHolding> queryErc1155(EthRpcClient rpc, NftCollection collection, String owner,
            long snapshotBlock, List<BigInteger> ids) {
        List<NftHolding> holdings = new ArrayList<>();
        List<List<BigInteger>> batches = partition(ids, batchSize);
        for (List<BigInteger> batch : batches) {
            List<String> owners = batch.stream().map(ignored -> owner).toList();
            String data = NftAbi.balanceOfBatchData(owners, batch);
            List<BigInteger> amounts = NftAbi.decodeUintArray(call(rpc, collection.contract(), data, snapshotBlock));
            if (amounts.size() != batch.size()) {
                throw new IllegalArgumentException("balanceOfBatch response length does not match request");
            }
            for (int index = 0; index < batch.size(); index++) {
                if (amounts.get(index).signum() > 0) {
                    holdings.add(new NftHolding(collection.chainId(), collection.contract(), batch.get(index),
                            amounts.get(index), NftStandard.ERC1155));
                }
            }
        }
        return List.copyOf(holdings);
    }

    private BigInteger explicitTokenCount(NftCollection collection) {
        BigInteger count = BigInteger.valueOf(collection.tokenIds().size());
        for (NftTokenIdRange range : collection.tokenIdRanges()) {
            count = count.add(range.size());
        }
        return count;
    }

    private List<BigInteger> explicitTokenIds(NftCollection collection, BigInteger start, BigInteger end) {
        List<BigInteger> ids = new ArrayList<>();
        int explicitCount = collection.tokenIds().size();
        BigInteger position = start;
        while (position.compareTo(end) < 0 && position.compareTo(BigInteger.valueOf(explicitCount)) < 0) {
            ids.add(collection.tokenIds().get(position.intValueExact()));
            position = position.add(BigInteger.ONE);
        }
        if (position.compareTo(BigInteger.valueOf(explicitCount)) < 0) {
            position = BigInteger.valueOf(explicitCount);
        }
        BigInteger rangeOffset = position.subtract(BigInteger.valueOf(explicitCount));
        for (NftTokenIdRange range : collection.tokenIdRanges()) {
            if (position.compareTo(end) >= 0) {
                break;
            }
            if (rangeOffset.compareTo(range.size()) >= 0) {
                rangeOffset = rangeOffset.subtract(range.size());
                continue;
            }
            BigInteger amount = range.size().subtract(rangeOffset).min(end.subtract(position));
            for (BigInteger index = BigInteger.ZERO; index.compareTo(amount) < 0; index = index.add(BigInteger.ONE)) {
                ids.add(range.at(rangeOffset.add(index)));
            }
            position = position.add(amount);
            rangeOffset = BigInteger.ZERO;
        }
        return List.copyOf(ids);
    }

    private <T> List<T> parallelMap(List<BigInteger> input, java.util.function.Function<BigInteger, T> mapper) {
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (BigInteger value : input) {
                futures.add(executor.submit(() -> mapper.apply(value)));
            }
        } catch (RejectedExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            throw exception;
        }
        List<T> output = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                output.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.forEach(pending -> pending.cancel(true));
                throw new RejectedExecutionException("NFT ownership query was interrupted", exception);
            } catch (CancellationException exception) {
                throw new RejectedExecutionException("NFT ownership query was cancelled", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("NFT RPC query failed", cause);
            }
        }
        return java.util.Collections.unmodifiableList(output);
    }

    private String call(EthRpcClient rpc, String contract, String data, long snapshotBlock) {
        return rpc.call(contract, data, "0x" + Long.toHexString(snapshotBlock));
    }

    private List<List<BigInteger>> partition(List<BigInteger> ids, int size) {
        List<List<BigInteger>> batches = new ArrayList<>();
        for (int offset = 0; offset < ids.size(); offset += size) {
            batches.add(List.copyOf(ids.subList(offset, Math.min(offset + size, ids.size()))));
        }
        return batches;
    }

    private NftOwnershipPage failedPage(long chainId, List<NftCollection> collections, NftFailureCode code) {
        List<NftOwnershipFailure> failures = collections.stream()
                .map(collection -> failure(chainId, collection, code)).toList();
        return new NftOwnershipPage(List.of(), null, 0, failures);
    }

    private NftOwnershipFailure failure(long chainId, NftCollection collection, NftFailureCode code) {
        return new NftOwnershipFailure(chainId, collection.contract(), code);
    }

    private void logFailureType(Throwable exception) {
        LOGGER.log(System.Logger.Level.DEBUG, "NFT ownership RPC failed: {0}",
                exception.getClass().getSimpleName());
    }

    private Cursor decodeCursor(String value, long chainId, String owner, List<NftCollection> collections) {
        if (value == null || value.isBlank()) {
            return new Cursor(0, BigInteger.ZERO, -1);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] fields = decoded.split(":", -1);
            if (fields.length != 6 || Long.parseLong(fields[0]) != chainId || !fields[1].equals(owner)
                    || !fields[4].equals(collectionFingerprint(collections))) {
                throw new IllegalArgumentException("Cursor does not belong to this owner, chain and collections");
            }
            int collectionIndex = Integer.parseInt(fields[2]);
            BigInteger position = new BigInteger(fields[3]);
            long snapshotBlock = Long.parseLong(fields[5]);
            NftAbi.requireUint256(position);
            if (collectionIndex < 0 || collectionIndex >= collections.size() || snapshotBlock < 0) {
                throw new IllegalArgumentException("Cursor position is outside the collection list");
            }
            NftCollection collection = collections.get(collectionIndex);
            if (!isEnumerableCollection(collection)
                    && position.compareTo(explicitTokenCount(collection)) > 0) {
                throw new IllegalArgumentException("Cursor position exceeds the explicit token count");
            }
            return new Cursor(collectionIndex, position, snapshotBlock);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid NFT ownership cursor", exception);
        }
    }

    private String encodeCursor(long chainId, String owner, List<NftCollection> collections, int collectionIndex,
            BigInteger position, long snapshotBlock) {
        String value = chainId + ":" + owner + ":" + collectionIndex + ":" + position + ":"
                + collectionFingerprint(collections) + ":" + snapshotBlock;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String collectionFingerprint(List<NftCollection> collections) {
        String value = String.join("\n", collections.stream().map(Object::toString).toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isEnumerableCollection(NftCollection collection) {
        return collection.standard() == NftStandard.ERC721 && collection.enumerable();
    }

    private boolean isExecutionRevert(EthRpcException exception) {
        if (exception.getCode() == 3) {
            return true;
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        return message.startsWith("execution reverted");
    }

    @Override
    public void close() {
        executor.shutdownNow().forEach(task -> {
            if (task instanceof Future<?> future) {
                future.cancel(false);
            }
        });
    }

    private record Cursor(int collectionIndex, BigInteger position, long snapshotBlock) {
    }

    private record CollectionPage(List<NftHolding> holdings, BigInteger nextPosition, int scannedTokenIds) {
    }

    private record InterfaceCacheKey(long chainId, String contract) {
    }

    private record InterfaceCapabilities(boolean erc721, boolean enumerable, boolean erc1155) {
    }

    private static final class UnsupportedException extends RuntimeException {
        UnsupportedException(String message) {
            super(message);
        }
    }

    private static final class InvalidOwnershipCursorException extends IllegalArgumentException {
        InvalidOwnershipCursorException() {
            super("Invalid NFT ownership cursor");
        }
    }

}
