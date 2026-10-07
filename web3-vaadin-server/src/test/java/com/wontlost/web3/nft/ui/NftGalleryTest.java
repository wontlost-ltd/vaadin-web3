package com.wontlost.web3.nft.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.ComponentUtil;
import com.wontlost.web3.nft.NftCollection;
import com.wontlost.web3.nft.NftFailureCode;
import com.wontlost.web3.nft.NftHolding;
import com.wontlost.web3.nft.NftMetadata;
import com.wontlost.web3.nft.NftMetadataAttribute;
import com.wontlost.web3.nft.NftMetadataFailureCode;
import com.wontlost.web3.nft.NftMetadataResolver;
import com.wontlost.web3.nft.NftMetadataResult;
import com.wontlost.web3.nft.NftOwnershipFailure;
import com.wontlost.web3.nft.NftOwnershipPage;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.nft.NftStandard;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.SiweLogin;
import com.wontlost.web3.siwe.VerifiedSignIn;

class NftGalleryTest {
    private static final String SIWE_ADDRESS = "0x1111111111111111111111111111111111111111";
    private static final String WALLET_ADDRESS = "0x2222222222222222222222222222222222222222";
    private static final NftCollection COLLECTION = new NftCollection(31337,
            "0x3333333333333333333333333333333333333333", NftStandard.ERC721, true, List.of());

    @Test
    void anonymousVisitorsNeverQueryAndSeeTheSignInPrompt() {
        FakeSource source = new FakeSource();
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> null, Runnable::run);
        attach(gallery);

        assertEquals(NftGallery.GalleryState.ANONYMOUS, gallery.getState());
        assertEquals(0, source.addresses.size());
        assertTrue(gallery.allTextForTest().contains("Sign in with Ethereum"));
        assertEquals(1, occurrences(gallery.allTextForTest(), "Sign in with Ethereum"));
        assertEquals("", gallery.liveStatusForTest());
    }

    @Test
    void queriesUseTheVerifiedSiweAddressAndNeverASeparateWalletAddress() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        attach(gallery);

        assertEquals(List.of(SIWE_ADDRESS), source.addresses);
        assertFalse(source.addresses.contains(WALLET_ADDRESS));
        assertEquals(NftGallery.GalleryState.READY, gallery.getState());
    }

    @Test
    void signOutClearsPreviouslyLoadedItemsAndDoesNotQueryAgain() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        attach(gallery);
        verifiedAddress.set(null);
        gallery.refresh();

        assertEquals(NftGallery.GalleryState.ANONYMOUS, gallery.getState());
        assertTrue(gallery.getItems().isEmpty());
        assertEquals(1, source.addresses.size());
    }

    @Test
    void siweSignOutEventClosesAndClearsOpenDetailsAndCards() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        UI ui = new UI();
        UI.setCurrent(ui);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        ui.add(login, gallery);
        gallery.showDetailsForTest(0);
        assertTrue(gallery.dialogOpenedForTest());
        assertTrue(gallery.detailsHasContentForTest());

        verifiedAddress.set(null);
        ComponentUtil.fireEvent(login, new SiweLogin.SignedOutEvent(login));

        assertEquals(NftGallery.GalleryState.ANONYMOUS, gallery.getState());
        assertTrue(gallery.getItems().isEmpty());
        assertFalse(gallery.dialogOpenedForTest());
        assertFalse(gallery.detailsHasContentForTest());
    }

    @Test
    void siweEventListenersAreRemovedWhenGalleryDetaches() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        UI ui = new UI();
        UI.setCurrent(ui);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        ui.add(login, gallery);
        assertEquals(1, source.addresses.size());

        ui.remove(gallery);
        verifiedAddress.set(null);
        ComponentUtil.fireEvent(login, new SiweLogin.SignedOutEvent(login));

        assertEquals(1, source.addresses.size());
    }

    @Test
    void siweSignInEventRefreshesToTheNewVerifiedAddress() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        UI ui = new UI();
        UI.setCurrent(ui);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        ui.add(login, gallery);
        verifiedAddress.set(WALLET_ADDRESS);

        ComponentUtil.fireEvent(login, new SiweLogin.SignedInEvent(login,
                new VerifiedSignIn(WALLET_ADDRESS, 31337, null, Instant.now())));

        assertEquals(List.of(SIWE_ADDRESS, WALLET_ADDRESS), source.addresses);
        assertEquals(List.of(holding(2)), gallery.getItems().stream().map(NftGallery.GalleryItem::holding).toList());
    }

    @Test
    void identityChangeClearsOldItemsAndQueriesTheNewVerifiedAddress() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, Runnable::run);
        attach(gallery);
        verifiedAddress.set(WALLET_ADDRESS);
        gallery.refresh();

        assertEquals(List.of(SIWE_ADDRESS, WALLET_ADDRESS), source.addresses);
        assertEquals(List.of(holding(2)), gallery.getItems().stream().map(NftGallery.GalleryItem::holding).toList());
    }

    @Test
    void identityChangeWhileOldRequestIsQueuedDoesNotLeaveGalleryLoading() {
        FakeSource source = new FakeSource();
        AtomicReference<String> verifiedAddress = new AtomicReference<>(SIWE_ADDRESS);
        QueuedExecutor executor = new QueuedExecutor();
        NftGallery gallery = gallery(source, new FakeMetadata(), verifiedAddress::get, executor);
        attach(gallery);
        assertEquals(NftGallery.GalleryState.LOADING, gallery.getState());

        verifiedAddress.set(WALLET_ADDRESS);
        gallery.refresh();
        assertEquals(NftGallery.GalleryState.LOADING, gallery.getState());
        executor.runNext();
        executor.runNext();

        assertEquals(NftGallery.GalleryState.READY, gallery.getState());
        assertEquals(List.of(holding(2)), gallery.getItems().stream().map(NftGallery.GalleryItem::holding).toList());
    }

    @Test
    void reportsPartialEmptyAndUnavailableStates() {
        FakeSource partial = new FakeSource();
        partial.page = new NftOwnershipPage(List.of(holding(1)), null, 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.UNSUPPORTED)));
        NftGallery partialGallery = gallery(partial, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(partialGallery);
        assertEquals(NftGallery.GalleryState.PARTIAL, partialGallery.getState());
        assertTrue(partialGallery.allTextForTest().contains("Unsupported collection"));
        assertFalse(partialGallery.allTextForTest().contains("UNSUPPORTED"));

        FakeSource empty = new FakeSource();
        empty.page = new NftOwnershipPage(List.of(), null, 1, List.of());
        NftGallery emptyGallery = gallery(empty, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(emptyGallery);
        assertEquals(NftGallery.GalleryState.EMPTY, emptyGallery.getState());

        FakeSource invalid = new FakeSource();
        invalid.page = new NftOwnershipPage(List.of(), null, 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.INVALID_COLLECTION)));
        NftGallery errorGallery = gallery(invalid, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(errorGallery);
        assertEquals(NftGallery.GalleryState.ERROR, errorGallery.getState());

        FakeSource unavailable = new FakeSource();
        unavailable.failure = new IllegalStateException("secret RPC detail");
        NftGallery unavailableGallery = gallery(unavailable, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(unavailableGallery);
        assertEquals(NftGallery.GalleryState.UNAVAILABLE, unavailableGallery.getState());
        assertFalse(unavailableGallery.allTextForTest().contains("secret RPC detail"));
    }

    @Test
    void loadMoreUsesTheOpaqueCursorAndAppendsTheNextPage() {
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(holding(1)), "cursor-2", 1, List.of()));
        source.pages.add(new NftOwnershipPage(List.of(holding(2)), null, 1, List.of()));
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);
        gallery.loadMoreForTest();

        assertEquals(java.util.Arrays.asList(null, "cursor-2"), source.cursors);
        assertEquals(2, gallery.getItems().size());
    }

    @Test
    void loadingAnotherPageAppendsCardsWithoutReplacingExistingCardComponents() {
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(holding(1)), "cursor-2", 1, List.of()));
        source.pages.add(new NftOwnershipPage(List.of(holding(2)), null, 1, List.of()));
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);
        Component firstCard = gallery.cardForTest(0);
        assertNull(firstCard.getElement().getAttribute("aria-hidden"));

        gallery.loadMoreForTest();

        assertSame(firstCard, gallery.cardForTest(0));
        assertEquals(2, gallery.getItems().size());
    }

    @Test
    void maxLoadedItemsStopsPagingAndShowsTheConfiguredLimitNotice() {
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(holding(1), holding(2)), "cursor-2", 1, List.of()));
        List<Integer> resolvedBatchSizes = new ArrayList<>();
        NftMetadataResolver resolver = holdings -> {
            resolvedBatchSizes.add(holdings.size());
            return holdings.stream().map(holding -> new NftMetadataResult(holding,
                    new NftMetadata("Token " + holding.tokenId(), null, null, null, null, List.of()), null,
                    null, null, null)).toList();
        };
        NftGallery gallery = new NftGallery(source, resolver, List.of(COLLECTION), 2, null,
                () -> SIWE_ADDRESS, Runnable::run).setMaxLoadedItems(1);
        attach(gallery);

        assertEquals(1, gallery.getItems().size());
        assertEquals(List.of(1), resolvedBatchSizes);
        assertFalse(gallery.loadMoreVisibleForTest());
        assertTrue(gallery.allTextForTest().contains("Maximum of 1 NFTs loaded."));
        gallery.loadMoreForTest();
        assertEquals(1, source.addresses.size());
    }

    @Test
    void raisingTheCapAfterTruncationDoesNotResumePagingPastDroppedHoldings() {
        // 截断页的游标已越过被丢弃的藏品，提高上限后继续分页会静默跳过它们
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(holding(1), holding(2)), "cursor-2", 1, List.of()));
        NftGallery gallery = new NftGallery(source, new FakeMetadata(), List.of(COLLECTION), 2, null,
                () -> SIWE_ADDRESS, Runnable::run).setMaxLoadedItems(1);
        attach(gallery);

        gallery.setMaxLoadedItems(10);

        assertEquals(1, gallery.getItems().size());
        assertFalse(gallery.loadMoreVisibleForTest());
        gallery.loadMoreForTest();
        assertEquals(1, source.addresses.size());
    }

    @Test
    void failureListSurvivesALoadMoreFailure() {
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(holding(1)), "cursor-2", 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.UNSUPPORTED))));
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        gallery.setI18n(new NftGalleryI18n().setFailureUnsupportedLabel("Not supported here"));
        attach(gallery);
        assertTrue(gallery.failureTextForTest().contains("Not supported here"));

        source.failure = new IllegalStateException("offline");
        gallery.loadMoreForTest();

        assertEquals(NftGallery.GalleryState.PARTIAL, gallery.getState());
        assertTrue(gallery.failureTextForTest().contains("Not supported here"));
    }

    @Test
    void retryButtonRefreshesUnavailableErrorAndPartialStates() {
        FakeSource unavailable = new FakeSource();
        unavailable.failure = new IllegalStateException("offline");
        NftGallery unavailableGallery = gallery(unavailable, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(unavailableGallery);
        unavailable.failure = null;
        assertTrue(unavailableGallery.retryVisibleForTest());
        unavailableGallery.clickRetryForTest();
        assertEquals(NftGallery.GalleryState.READY, unavailableGallery.getState());
        assertEquals(2, unavailable.addresses.size());

        FakeSource error = new FakeSource();
        error.page = new NftOwnershipPage(List.of(), null, 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.INVALID_COLLECTION)));
        NftGallery errorGallery = gallery(error, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(errorGallery);
        error.pages.add(new NftOwnershipPage(List.of(holding(1)), null, 2, List.of()));
        assertTrue(errorGallery.retryVisibleForTest());
        errorGallery.clickRetryForTest();
        assertEquals(NftGallery.GalleryState.READY, errorGallery.getState());

        FakeSource partial = new FakeSource();
        partial.page = new NftOwnershipPage(List.of(holding(1)), null, 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.UNSUPPORTED)));
        NftGallery partialGallery = gallery(partial, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(partialGallery);
        partial.pages.add(new NftOwnershipPage(List.of(holding(1)), null, 2, List.of()));
        assertTrue(partialGallery.retryVisibleForTest());
        partialGallery.clickRetryForTest();
        assertEquals(NftGallery.GalleryState.READY, partialGallery.getState());
    }

    @Test
    void ownershipFailureCodesRenderLocalizedLabelsInsteadOfEnumNames() {
        FakeSource source = new FakeSource();
        source.page = new NftOwnershipPage(List.of(holding(1)), null, 1,
                List.of(new NftOwnershipFailure(31337, COLLECTION.contract(), NftFailureCode.UNSUPPORTED)));
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        gallery.setI18n(new NftGalleryI18n().setFailureUnsupportedLabel("Not supported here"));
        attach(gallery);

        assertTrue(gallery.failureTextForTest().contains("Not supported here"));
        assertFalse(gallery.failureTextForTest().contains("UNSUPPORTED"));
    }

    @Test
    void emptyPageWithCursorOffersContinuationAndAnnouncesCompletion() {
        FakeSource source = new FakeSource();
        source.pages.add(new NftOwnershipPage(List.of(), "cursor-2", 1, List.of()));
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);

        assertTrue(gallery.allTextForTest().contains("No NFTs on this page. Load more to continue."));
        assertEquals("NFTs loaded.", gallery.liveStatusForTest());
    }

    @Test
    void aSingleMetadataFailureKeepsTheHoldingAndRendersAPlaceholder() {
        FakeSource source = new FakeSource();
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(), null,
                NftMetadataFailureCode.INVALID_JSON, null, null));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);

        assertEquals(1, gallery.getItems().size());
        assertTrue(gallery.allTextForTest().contains("Metadata unavailable"));
        Div imageFrame = findClass(findRole(gallery, "button"), "nft-gallery-image-frame");
        assertEquals("1 / 1", imageFrame.getStyle().get("aspect-ratio"));
        assertEquals("center", imageFrame.getStyle().get("justify-content"));
        assertTrue(treeText(imageFrame).contains("Image unavailable"));
        Div metadataStatus = findClass(findRole(gallery, "button"), "nft-gallery-metadata-status");
        assertEquals("block", metadataStatus.getStyle().get("display"));
        assertEquals("var(--lumo-secondary-text-color)", metadataStatus.getStyle().get("color"));
    }

    @Test
    void externalTextIsInsertedAsTextAndNonHttpsExternalUrlsAreNotRendered() {
        FakeSource source = new FakeSource();
        NftMetadata metadata = new NftMetadata("<b>name</b>", "<img src=x>", null, null,
                "javascript:alert(1)", List.of(new NftMetadataAttribute("<i>trait</i>", "<script>")));
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(), metadata,
                null, null, "UNSAFE_TARGET", "data:application/json,%7B%7D"));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);
        gallery.showDetailsForTest(0);

        String text = gallery.detailsTextForTest();
        assertTrue(text.contains("<b>name</b>"));
        assertTrue(text.contains("<img src=x>"));
        assertTrue(text.contains("<script>"));
        assertFalse(gallery.detailsHasExternalAnchorForTest());
        assertNull(gallery.detailsRawHtmlForTest());
    }

    @Test
    void customResolversCannotRenderUnsafeExternalUrls() {
        assertExternalUrl("javascript:alert(1)", false);
        assertExternalUrl("http://example.com", false);
        assertExternalUrl("https://user@example.com", false);
        assertExternalUrl("https://example.com", true);
    }

    @Test
    void customResolversCannotRenderUnsafeImageUrls() {
        assertImageUrl("javascript:alert(1)");
        assertImageUrl("http://example.com/image.png");
    }

    @Test
    void dialogCloseActionClosesDialogAndReturnsFocusToTheCard() {
        FakeSource source = new FakeSource();
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);
        gallery.showDetailsForTest(0);

        gallery.fireDialogCloseActionForTest();

        assertFalse(gallery.dialogOpenedForTest());
        assertTrue(gallery.cardFocusRequestedForTest());
    }

    @Test
    void cardsUseResponsiveGridSeparateSecondaryTextAndSquareImageFrames() {
        FakeSource source = new FakeSource();
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(),
                new NftMetadata("Token", null, null, null, null, List.of()), null,
                "https://example.com/image.png", null, null));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);

        Div grid = findClass(gallery, "nft-gallery-grid");
        assertTrue(grid.getStyle().get("grid-template-columns").contains("220px"));
        Div card = (Div) findRole(grid, "button");
        Component name = findComponentClass(card, "nft-gallery-name");
        assertEquals("anywhere", name.getElement().getStyle().get("overflow-wrap"));
        Div frame = findClass(card, "nft-gallery-image-frame");
        assertEquals("1 / 1", frame.getStyle().get("aspect-ratio"));
        assertEquals("center", frame.getStyle().get("align-items"));
        assertTrue(containsTag(frame, "img"));
        Div contract = findClass(card, "nft-gallery-contract");
        assertEquals("block", contract.getStyle().get("display"));
        assertEquals("var(--lumo-secondary-text-color)", contract.getStyle().get("color"));
        Div amount = findClass(card, "nft-gallery-amount");
        assertEquals("block", amount.getStyle().get("display"));
        assertEquals("var(--lumo-font-size-s)", amount.getStyle().get("font-size"));
    }

    @Test
    void dataUriDetailsAreSummarizedAndAttributesUseTwoColumnTable() {
        FakeSource source = new FakeSource();
        String dataUri = "data:application/json," + "x".repeat(1000);
        NftMetadata metadata = new NftMetadata("Token", "description", null, null, null,
                List.of(new NftMetadataAttribute("Color", "Blue")));
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(), metadata,
                null, null, null, dataUri));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);
        gallery.showDetailsForTest(0);

        String detailsText = gallery.detailsTextForTest();
        assertTrue(detailsText.matches("(?s).*Inline JSON \\(data URI, [\\d,]+ characters\\).*"));
        assertFalse(detailsText.contains(dataUri));
        assertEquals("min(640px, 95vw)", gallery.dialogMaxWidthForTest());
        assertTrue(gallery.detailsHasAttributeTableForTest());
        assertEquals(2, gallery.attributeTableColumnCountForTest());
        assertEquals("anywhere", gallery.sourceUriValueForTest().getStyle().get("overflow-wrap"));
    }

    @Test
    void detachedCallbacksDoNotUpdateTheGallery() {
        FakeSource source = new FakeSource();
        QueuedExecutor executor = new QueuedExecutor();
        NftGallery gallery = gallery(source, new FakeMetadata(), () -> SIWE_ADDRESS, executor);
        attach(gallery);
        gallery.detachForTest();
        executor.runNext();

        assertTrue(gallery.getItems().isEmpty());
        assertEquals(NftGallery.GalleryState.LOADING, gallery.getState());
    }

    private static NftGallery gallery(FakeSource source, NftMetadataResolver resolver,
            java.util.function.Supplier<String> identity, Executor executor) {
        return new NftGallery(source, resolver, List.of(COLLECTION), 1, null, identity, executor);
    }

    private static void assertExternalUrl(String externalUrl, boolean expectedLink) {
        FakeSource source = new FakeSource();
        NftMetadata metadata = new NftMetadata("Token", null, null, null, externalUrl, List.of());
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(), metadata,
                null, null, null, null));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        UI ui = attach(gallery);
        ui.remove(gallery);
        gallery.showDetailsForTest(0);

        assertEquals(expectedLink, gallery.detailsHasExternalAnchorForTest(), externalUrl);
        if (expectedLink) {
            assertEquals(externalUrl, gallery.detailsExternalAnchorHrefForTest());
            assertEquals("_blank", gallery.detailsExternalAnchorTargetForTest());
            assertEquals("noopener noreferrer", gallery.detailsExternalAnchorRelForTest());
        }
    }

    private static void assertImageUrl(String imageUrl) {
        FakeSource source = new FakeSource();
        NftMetadata metadata = new NftMetadata("Token", null, imageUrl, null, null, List.of());
        NftMetadataResolver resolver = holdings -> List.of(new NftMetadataResult(holdings.getFirst(), metadata,
                null, imageUrl, null, null));
        NftGallery gallery = gallery(source, resolver, () -> SIWE_ADDRESS, Runnable::run);
        attach(gallery);

        assertFalse(gallery.cardImageRenderedForTest(), imageUrl);
    }

    private static UI attach(NftGallery gallery) {
        UI ui = new UI();
        UI.setCurrent(ui);
        ui.add(gallery);
        return ui;
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private static Div findClass(Component component, String className) {
        return findClassOrNull(component, className).orElseThrow();
    }

    private static Component findComponentClass(Component component, String className) {
        if (component.getElement().getClassList().contains(className)) {
            return component;
        }
        return component.getChildren().map(child -> findComponentClassOrNull(child, className))
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get).findFirst().orElseThrow();
    }

    private static java.util.Optional<Component> findComponentClassOrNull(Component component, String className) {
        if (component.getElement().getClassList().contains(className)) {
            return java.util.Optional.of(component);
        }
        return component.getChildren().map(child -> findComponentClassOrNull(child, className))
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get).findFirst();
    }

    private static java.util.Optional<Div> findClassOrNull(Component component, String className) {
        if (component instanceof Div div && div.getElement().getClassList().contains(className)) {
            return java.util.Optional.of(div);
        }
        return component.getChildren().map(child -> findClassOrNull(child, className))
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get).findFirst();
    }

    private static Component findRole(Component component, String role) {
        return findRoleOrNull(component, role).orElseThrow();
    }

    private static java.util.Optional<Component> findRoleOrNull(Component component, String role) {
        if (role.equals(component.getElement().getAttribute("role"))) {
            return java.util.Optional.of(component);
        }
        return component.getChildren().map(child -> findRoleOrNull(child, role))
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get).findFirst();
    }

    private static boolean containsTag(Component component, String tag) {
        return tag.equals(component.getElement().getTag())
                || component.getChildren().anyMatch(child -> containsTag(child, tag));
    }

    private static String treeText(Component component) {
        StringBuilder text = new StringBuilder(component.getElement().getText());
        component.getChildren().forEach(child -> text.append(treeText(child)));
        return text.toString();
    }

    private static NftHolding holding(long id) {
        return new NftHolding(31337, COLLECTION.contract(), BigInteger.valueOf(id), BigInteger.ONE,
                NftStandard.ERC721);
    }

    private static final class FakeSource implements NftOwnershipSource {
        private final List<String> addresses = new ArrayList<>();
        private final List<String> cursors = new ArrayList<>();
        private final ArrayDeque<NftOwnershipPage> pages = new ArrayDeque<>();
        private NftOwnershipPage page = new NftOwnershipPage(List.of(holding(1)), null, 1, List.of());
        private RuntimeException failure;

        @Override
        public NftOwnershipPage find(long chainId, String owner, List<NftCollection> collections,
                String cursor, int pageSize) {
            addresses.add(owner);
            cursors.add(cursor);
            if (failure != null) {
                throw failure;
            }
            if (!pages.isEmpty()) {
                return pages.removeFirst();
            }
            if (WALLET_ADDRESS.equalsIgnoreCase(owner)) {
                return new NftOwnershipPage(List.of(holding(2)), null, 1, List.of());
            }
            return page;
        }
    }

    private static final class FakeMetadata implements NftMetadataResolver {
        @Override
        public List<NftMetadataResult> resolve(List<NftHolding> holdings) {
            return holdings.stream().map(holding -> new NftMetadataResult(holding,
                    new NftMetadata("Token " + holding.tokenId(), null, null, null, null, List.of()), null,
                    null, null, "data:application/json,%7B%7D")).toList();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        private void runNext() { tasks.removeFirst().run(); }
    }
}
