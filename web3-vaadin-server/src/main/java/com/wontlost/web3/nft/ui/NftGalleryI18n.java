package com.wontlost.web3.nft.ui;

import java.text.MessageFormat;

/** NFT 画廊的可替换文案。 */
public class NftGalleryI18n {
    private String title = "NFT gallery";
    private String signInRequired = "Sign in with Ethereum to view this wallet's NFTs.";
    private String signInLink = "Sign in";
    private String loading = "Loading NFTs…";
    private String loadingMore = "Loading more NFTs…";
    private String empty = "No NFTs were found for this wallet.";
    private String partial = "Some collections could not be loaded.";
    private String unavailable = "NFT ownership data is temporarily unavailable.";
    private String error = "NFT ownership data could not be loaded.";
    private String loadMore = "Load more";
    private String retry = "Retry";
    private String maxItemsReached = "Maximum of {0} NFTs loaded.";
    private String details = "NFT details";
    private String close = "Close";
    private String noImage = "Image unavailable";
    private String svgImage = "SVG images are not displayed.";
    private String rejectedImage = "This image source cannot be displayed.";
    private String unsafeImage = "The image host could not be safely validated.";
    private String longImageUri = "The image URI is too long.";
    private String unavailableImage = "The image could not be loaded.";
    private String metadataUnavailable = "Metadata unavailable";
    private String amount = "Quantity";
    private String chain = "Chain";
    private String contract = "Contract";
    private String tokenId = "Token ID";
    private String sourceUri = "Source URI";
    private String externalUrl = "External link";
    private String name = "Name";
    private String description = "Description";
    private String attributes = "Attributes";
    private String attributeName = "Trait";
    private String value = "Value";
    private String inlineJsonSummary = "Inline JSON (data URI, {0} characters)";
    private String ready = "NFTs loaded.";
    private String loadedMore = "More NFTs loaded.";
    private String continueChains = "No NFTs on this chain. Continue to the next chain.";
    private String continuePage = "No NFTs on this page. Load more to continue.";
    private String ownershipFailure = "Chain {0}, {1} ({2}): {3}";
    private String failureUnsupported = "Unsupported collection";
    private String failureUnavailable = "Temporarily unavailable";
    private String failureInvalidCollection = "Invalid collection configuration";
    private String failureInvalidResponse = "Invalid collection response";
    private String failureLimit = "Query limit exceeded";
    private String failureUnsupportedLabel = "Unsupported";
    private String failureUnavailableLabel = "Temporarily unavailable";
    private String failureInvalidCollectionLabel = "Invalid collection";
    private String failureInvalidResponseLabel = "Invalid response";
    private String failureLimitLabel = "Query limit exceeded";

    public String getTitle() {
        return title;
    }
    public NftGalleryI18n setTitle(String value) {
        title = value;
        return this;
    }
    public String getSignInRequired() {
        return signInRequired;
    }
    public NftGalleryI18n setSignInRequired(String value) {
        signInRequired = value;
        return this;
    }
    public String getSignInLink() {
        return signInLink;
    }
    public NftGalleryI18n setSignInLink(String value) {
        signInLink = value;
        return this;
    }
    public String getLoading() {
        return loading;
    }
    public NftGalleryI18n setLoading(String value) {
        loading = value;
        return this;
    }
    public String getLoadingMore() {
        return loadingMore;
    }
    public NftGalleryI18n setLoadingMore(String value) {
        loadingMore = value;
        return this;
    }
    public String getEmpty() {
        return empty;
    }
    public NftGalleryI18n setEmpty(String value) {
        empty = value;
        return this;
    }
    public String getPartial() {
        return partial;
    }
    public NftGalleryI18n setPartial(String value) {
        partial = value;
        return this;
    }
    public String getUnavailable() {
        return unavailable;
    }
    public NftGalleryI18n setUnavailable(String value) {
        unavailable = value;
        return this;
    }
    public String getError() {
        return error;
    }
    public NftGalleryI18n setError(String value) {
        error = value;
        return this;
    }
    public String getLoadMore() {
        return loadMore;
    }
    public NftGalleryI18n setLoadMore(String value) {
        loadMore = value;
        return this;
    }
    public String getRetry() {
        return retry;
    }
    public NftGalleryI18n setRetry(String value) {
        retry = value;
        return this;
    }
    public String getMaxItemsReached(int maximum) {
        return MessageFormat.format(maxItemsReached, maximum);
    }
    public NftGalleryI18n setMaxItemsReached(String value) {
        maxItemsReached = value;
        return this;
    }
    public String getDetails() {
        return details;
    }
    public NftGalleryI18n setDetails(String value) {
        details = value;
        return this;
    }
    public String getClose() {
        return close;
    }
    public NftGalleryI18n setClose(String value) {
        close = value;
        return this;
    }
    public String getNoImage() {
        return noImage;
    }
    public NftGalleryI18n setNoImage(String value) {
        noImage = value;
        return this;
    }
    public String getSvgImage() {
        return svgImage;
    }
    public NftGalleryI18n setSvgImage(String value) {
        svgImage = value;
        return this;
    }
    public String getRejectedImage() {
        return rejectedImage;
    }
    public NftGalleryI18n setRejectedImage(String value) {
        rejectedImage = value;
        return this;
    }
    public String getUnsafeImage() {
        return unsafeImage;
    }
    public NftGalleryI18n setUnsafeImage(String value) {
        unsafeImage = value;
        return this;
    }
    public String getLongImageUri() {
        return longImageUri;
    }
    public NftGalleryI18n setLongImageUri(String value) {
        longImageUri = value;
        return this;
    }
    public String getUnavailableImage() {
        return unavailableImage;
    }
    public NftGalleryI18n setUnavailableImage(String value) {
        unavailableImage = value;
        return this;
    }
    public String getMetadataUnavailable() {
        return metadataUnavailable;
    }
    public NftGalleryI18n setMetadataUnavailable(String value) {
        metadataUnavailable = value;
        return this;
    }
    public String getAmount() {
        return amount;
    }
    public NftGalleryI18n setAmount(String value) {
        amount = value;
        return this;
    }
    public String getChain() {
        return chain;
    }
    public NftGalleryI18n setChain(String value) {
        chain = value;
        return this;
    }
    public String getContract() {
        return contract;
    }
    public NftGalleryI18n setContract(String value) {
        contract = value;
        return this;
    }
    public String getTokenId() {
        return tokenId;
    }
    public NftGalleryI18n setTokenId(String value) {
        tokenId = value;
        return this;
    }
    public String getSourceUri() {
        return sourceUri;
    }
    public NftGalleryI18n setSourceUri(String value) {
        sourceUri = value;
        return this;
    }
    public String getExternalUrl() {
        return externalUrl;
    }
    public NftGalleryI18n setExternalUrl(String value) {
        externalUrl = value;
        return this;
    }
    public String getName() {
        return name;
    }
    public NftGalleryI18n setName(String value) {
        name = value;
        return this;
    }
    public String getDescription() {
        return description;
    }
    public NftGalleryI18n setDescription(String value) {
        description = value;
        return this;
    }
    public String getAttributes() {
        return attributes;
    }
    public NftGalleryI18n setAttributes(String value) {
        attributes = value;
        return this;
    }
    public String getValue() {
        return value;
    }
    public NftGalleryI18n setValue(String value) {
        this.value = value;
        return this;
    }
    public String getAttributeName() {
        return attributeName;
    }
    public NftGalleryI18n setAttributeName(String value) {
        attributeName = value;
        return this;
    }
    public NftGalleryI18n setInlineJsonSummary(String value) {
        inlineJsonSummary = value;
        return this;
    }
    public String inlineJsonSummary(int characters) {
        return MessageFormat.format(inlineJsonSummary, characters);
    }
    public String getReady() {
        return ready;
    }
    public NftGalleryI18n setReady(String value) {
        ready = value;
        return this;
    }
    public String getLoadedMore() {
        return loadedMore;
    }
    public NftGalleryI18n setLoadedMore(String value) {
        loadedMore = value;
        return this;
    }
    public String getContinueChains() {
        return continueChains;
    }

    public String getContinuePage() {
        return continuePage;
    }

    public NftGalleryI18n setContinuePage(String value) {
        continuePage = value;
        return this;
    }
    public NftGalleryI18n setContinueChains(String value) {
        continueChains = value;
        return this;
    }
    public NftGalleryI18n setOwnershipFailure(String value) {
        ownershipFailure = value;
        return this;
    }

    public String ownershipFailure(long chainId, String contractAddress, String code, String explanation) {
        return MessageFormat.format(ownershipFailure, chainId, shortAddress(contractAddress), code, explanation);
    }

    public String failureMessage(String code) {
        return switch (code) {
            case "UNSUPPORTED" -> failureUnsupported;
            case "UNAVAILABLE" -> failureUnavailable;
            case "INVALID_COLLECTION" -> failureInvalidCollection;
            case "INVALID_RESPONSE" -> failureInvalidResponse;
            case "LIMIT_EXCEEDED" -> failureLimit;
            default -> failureInvalidResponse;
        };
    }

    public String failureLabel(String code) {
        return switch (code) {
            case "UNSUPPORTED" -> failureUnsupportedLabel;
            case "UNAVAILABLE" -> failureUnavailableLabel;
            case "INVALID_COLLECTION" -> failureInvalidCollectionLabel;
            case "INVALID_RESPONSE" -> failureInvalidResponseLabel;
            case "LIMIT_EXCEEDED" -> failureLimitLabel;
            default -> failureInvalidResponseLabel;
        };
    }

    public NftGalleryI18n setFailureUnsupported(String value) {
        failureUnsupported = value;
        return this;
    }

    public NftGalleryI18n setFailureUnavailable(String value) {
        failureUnavailable = value;
        return this;
    }

    public NftGalleryI18n setFailureInvalidCollection(String value) {
        failureInvalidCollection = value;
        return this;
    }

    public NftGalleryI18n setFailureInvalidResponse(String value) {
        failureInvalidResponse = value;
        return this;
    }

    public NftGalleryI18n setFailureLimit(String value) {
        failureLimit = value;
        return this;
    }

    public NftGalleryI18n setFailureUnsupportedLabel(String value) {
        failureUnsupportedLabel = value;
        return this;
    }

    public NftGalleryI18n setFailureUnavailableLabel(String value) {
        failureUnavailableLabel = value;
        return this;
    }

    public NftGalleryI18n setFailureInvalidCollectionLabel(String value) {
        failureInvalidCollectionLabel = value;
        return this;
    }

    public NftGalleryI18n setFailureInvalidResponseLabel(String value) {
        failureInvalidResponseLabel = value;
        return this;
    }

    public NftGalleryI18n setFailureLimitLabel(String value) {
        failureLimitLabel = value;
        return this;
    }

    private String shortAddress(String value) {
        return value.length() <= 12 ? value : value.substring(0, 6) + "…" + value.substring(value.length() - 4);
    }
}
