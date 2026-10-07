package com.wontlost.web3.nft.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.concurrent.Executor;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.Table;
import com.vaadin.flow.component.html.TableBody;
import com.vaadin.flow.component.html.TableDataCell;
import com.vaadin.flow.component.html.TableHead;
import com.vaadin.flow.component.html.TableHeaderCell;
import com.vaadin.flow.component.html.TableRow;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.nft.NftCollection;
import com.wontlost.web3.nft.NftFailureCode;
import com.wontlost.web3.nft.NftHolding;
import com.wontlost.web3.nft.NftMetadata;
import com.wontlost.web3.nft.NftMetadataAttribute;
import com.wontlost.web3.nft.NftMetadataResolver;
import com.wontlost.web3.nft.NftMetadataResult;
import com.wontlost.web3.nft.NftOwnershipFailure;
import com.wontlost.web3.nft.NftOwnershipPage;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.siwe.SiweLogin;

/** 为已验证的 SIWE 地址展示 NFT，并按需加载后续分页。并发限制由 N1 所有权源与 N2 元数据解析器负责。 */
public final class NftGallery extends VerticalLayout {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final NftOwnershipSource ownership;
    private final NftMetadataResolver metadataResolver;
    private final List<NftCollection> collections;
    private final int pageSize;
    private final String loginPath;
    private final Supplier<String> identityAddress;
    private final Executor executor;
    private final AtomicLong generation = new AtomicLong();
    private final Span liveStatus = new Span();
    private final H2 titleText;
    private final Div content = new Div();
    private final Div failureList = new Div();
    private final Button loadMore = new Button();
    private final Button retry = new Button();
    private final Dialog details = new Dialog();
    private final VerticalLayout detailsContent = new VerticalLayout();
    private final List<GalleryItem> items = new ArrayList<>();
    private final List<NftOwnershipFailure> failures = new ArrayList<>();
    private final List<Registration> siweRegistrations = new ArrayList<>();
    private final List<Long> chainIds;
    private Div itemGrid;
    private Paragraph stateNotice;
    private volatile int maxLoadedItems = 500;
    // 上限截断过结果后游标已越过被丢弃的藏品，此后不得继续分页，否则会静默跳过这些藏品
    private boolean truncated;
    private NftGalleryI18n i18n = new NftGalleryI18n();
    private GalleryState state = GalleryState.ANONYMOUS;
    private String owner;
    private String cursor;
    private int chainIndex;
    private boolean loading;
    private boolean cardFocusRequested;
    private volatile boolean detached = true;
    private Div focusedCard;

    public NftGallery(NftOwnershipSource ownership, NftMetadataResolver metadataResolver,
            List<NftCollection> collections, int pageSize, String loginPath) {
        this(ownership, metadataResolver, collections, pageSize, loginPath, NftGallery::currentIdentity);
    }

    NftGallery(NftOwnershipSource ownership, NftMetadataResolver metadataResolver,
            List<NftCollection> collections, int pageSize, String loginPath, Supplier<String> identityAddress) {
        this(ownership, metadataResolver, collections, pageSize, loginPath, identityAddress, EXECUTOR);
    }

    NftGallery(NftOwnershipSource ownership, NftMetadataResolver metadataResolver,
            List<NftCollection> collections, int pageSize, String loginPath, Supplier<String> identityAddress,
            Executor executor) {
        this.ownership = Objects.requireNonNull(ownership);
        this.metadataResolver = Objects.requireNonNull(metadataResolver);
        this.collections = List.copyOf(collections);
        this.identityAddress = Objects.requireNonNull(identityAddress);
        this.executor = Objects.requireNonNull(executor);
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("pageSize must be between 1 and 500");
        }
        this.pageSize = pageSize;
        this.loginPath = safeLoginPath(loginPath);
        this.chainIds = this.collections.stream().map(NftCollection::chainId).distinct().toList();
        setId("nft-gallery");
        setWidthFull();
        titleText = new H2(i18n.getTitle());
        liveStatus.getElement().setAttribute("role", "status");
        liveStatus.getElement().setAttribute("aria-live", "polite");
        failureList.getElement().setAttribute("aria-live", "polite");
        loadMore.setText(i18n.getLoadMore());
        loadMore.addClickListener(event -> loadNextPage());
        loadMore.setVisible(false);
        retry.addClickListener(event -> refresh());
        retry.setVisible(false);
        details.setCloseOnEsc(true);
        details.setCloseOnOutsideClick(true);
        details.setMaxWidth("min(640px, 95vw)");
        details.addDialogCloseActionListener(event -> details.close());
        details.addOpenedChangeListener(event -> {
            if (!event.isOpened()) {
                focusCard();
            }
        });
        add(titleText, liveStatus, failureList, content, loadMore, retry, details);
        renderState();
    }

    public NftGallery setI18n(NftGalleryI18n value) {
        i18n = Objects.requireNonNull(value);
        titleText.setText(i18n.getTitle());
        loadMore.setText(i18n.getLoadMore());
        retry.setText(i18n.getRetry());
        if (state == GalleryState.ANONYMOUS) {
            renderState();
        }
        if (state != GalleryState.LOADING && state != GalleryState.ANONYMOUS) {
            renderState();
        }
        renderItems();
        renderFailures();
        return this;
    }

    public GalleryState getState() {
        return state;
    }

    public List<GalleryItem> getItems() {
        return List.copyOf(items);
    }

    public List<NftOwnershipFailure> getFailures() {
        return List.copyOf(failures);
    }

    /** Sets the maximum number of NFT cards retained by this component. */
    public NftGallery setMaxLoadedItems(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxLoadedItems must be at least 1");
        }
        maxLoadedItems = value;
        if (items.size() > maxLoadedItems) {
            items.subList(maxLoadedItems, items.size()).clear();
            truncated = true;
            renderItems();
        }
        renderState();
        renderFailures();
        updateContinuationControls();
        return this;
    }

    /** Rechecks the SIWE identity and clears results before querying a changed account. */
    public void refresh() {
        refreshIdentity(true);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        detached = false;
        subscribeToSiweEvents();
        refreshIdentity(true);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        detached = true;
        generation.incrementAndGet();
        loading = false;
        siweRegistrations.forEach(Registration::remove);
        siweRegistrations.clear();
        super.onDetach(detachEvent);
    }

    private static String currentIdentity() {
        VaadinSession session = VaadinSession.getCurrent();
        return Web3Session.current(session).map(signIn -> signIn.address()).orElse(null);
    }

    private void subscribeToSiweEvents() {
        getUI().ifPresent(this::registerSiweEvents);
    }

    private void registerSiweEvents(Component component) {
        if (component instanceof SiweLogin login) {
            siweRegistrations.add(login.addSignedInListener(event -> refresh()));
            siweRegistrations.add(login.addSignedOutListener(event -> refresh()));
        }
        component.getChildren().forEach(this::registerSiweEvents);
    }

    private void refreshIdentity(boolean reloadSameIdentity) {
        String current = identityAddress.get();
        if (current == null || current.isBlank()) {
            generation.incrementAndGet();
            clearData();
            owner = null;
            state = GalleryState.ANONYMOUS;
            loading = false;
            renderState();
            return;
        }
        if (!current.equalsIgnoreCase(owner) || reloadSameIdentity) {
            generation.incrementAndGet();
            clearData();
            owner = current;
            chainIndex = 0;
            cursor = null;
            state = GalleryState.LOADING;
            renderState();
            if (!detached && !chainIds.isEmpty()) {
                loadNextPage();
            } else if (chainIds.isEmpty()) {
                state = GalleryState.EMPTY;
                renderState();
            }
        }
    }

    private void loadNextPage() {
        if (loading) {
            return;
        }
        String current = identityAddress.get();
        if (current == null || !current.equalsIgnoreCase(owner)) {
            refreshIdentity(true);
            return;
        }
        if (chainIndex >= chainIds.size() || truncated || items.size() >= maxLoadedItems || detached) {
            return;
        }
        loading = true;
        long operation = generation.get();
        UI ui = getUI().orElse(null);
        if (ui == null) {
            loading = false;
            return;
        }
        if (items.isEmpty()) {
            state = GalleryState.LOADING;
            renderState();
        } else {
            liveStatus.setText(i18n.getLoadingMore());
            loadMore.setEnabled(false);
        }
        long chainId = chainIds.get(chainIndex);
        List<NftCollection> chainCollections = collections.stream()
                .filter(collection -> collection.chainId() == chainId).toList();
        String pageCursor = cursor;
        String pageOwner = owner;
        int remainingCapacity = maxLoadedItems - items.size();
        executor.execute(() -> loadPage(ui, operation, chainId, chainCollections, pageCursor, pageOwner,
                remainingCapacity));
    }

    private void loadPage(UI ui, long operation, long chainId, List<NftCollection> chainCollections,
            String pageCursor, String pageOwner, int remainingCapacity) {
        try {
            NftOwnershipPage page = ownership.find(chainId, pageOwner, chainCollections, pageCursor, pageSize);
            List<NftHolding> holdingsToResolve = page.holdings().stream().limit(remainingCapacity).toList();
            List<NftMetadataResult> resolved = metadataResolver.resolve(holdingsToResolve);
            update(ui, operation, () -> applyPage(operation, page, resolved, pageCursor));
        } catch (RuntimeException exception) {
            update(ui, operation, () -> applyPageFailure(operation));
        }
    }

    private void update(UI ui, long operation, Runnable action) {
        if (detached || ui == null) {
            return;
        }
        if (ui.getSession() == null) {
            // 无会话 UI 只在单测或嵌入场景出现，此时回调仍在当前 UI 线程执行。
            if (isAttached() && operation == generation.get()) {
                action.run();
            }
            return;
        }
        try {
            ui.access(() -> {
                if (!detached && isAttached() && operation == generation.get()) {
                    action.run();
                }
            });
        } catch (IllegalStateException | UIDetachedException exception) {
            // 组件卸载或会话关闭后 Vaadin 会拒绝排队的 UI 访问，旧结果无需再更新界面。
        }
    }

    private void applyPage(long operation, NftOwnershipPage page, List<NftMetadataResult> resolved,
            String requestedCursor) {
        if (!isCurrent(operation)) {
            return;
        }
        loading = false;
        loadMore.setEnabled(true);
        failures.addAll(page.failures());
        Map<NftHolding, NftMetadataResult> results = new LinkedHashMap<>();
        for (NftMetadataResult result : resolved) {
            results.put(result.holding(), result);
        }
        int firstNewItem = items.size();
        for (NftHolding holding : page.holdings()) {
            if (items.size() >= maxLoadedItems) {
                truncated = true;
                break;
            }
            NftMetadataResult result = results.get(holding);
            items.add(new GalleryItem(holding, result));
        }
        cursor = page.nextCursor();
        if (cursor == null) {
            chainIndex++;
        }
        if (chainIndex < chainIds.size() && items.isEmpty() && cursor == null) {
            state = failures.isEmpty() ? GalleryState.READY : GalleryState.PARTIAL;
            renderState();
            content.add(new Paragraph(i18n.getContinueChains()));
            renderFailures();
            liveStatus.setText(requestedCursor == null ? i18n.getReady() : i18n.getLoadedMore());
            return;
        }
        if (!failures.isEmpty()) {
            state = items.isEmpty() ? allUnavailable(failures) ? GalleryState.UNAVAILABLE : GalleryState.ERROR
                    : GalleryState.PARTIAL;
        } else {
            state = items.isEmpty() && chainIndex >= chainIds.size() ? GalleryState.EMPTY : GalleryState.READY;
        }
        renderState();
        appendItems(firstNewItem);
        renderFailures();
        updateContinuationControls();
        liveStatus.setText(requestedCursor == null ? i18n.getReady() : i18n.getLoadedMore());
    }

    private void applyPageFailure(long operation) {
        if (!isCurrent(operation)) {
            return;
        }
        loading = false;
        state = items.isEmpty() ? GalleryState.UNAVAILABLE : GalleryState.PARTIAL;
        renderState();
        renderFailures();
        updateContinuationControls();
        liveStatus.setText(i18n.getUnavailable());
    }

    private boolean isCurrent(long operation) {
        return !detached && operation == generation.get();
    }

    private void clearData() {
        focusedCard = null;
        cardFocusRequested = false;
        details.close();
        detailsContent.removeAll();
        details.removeAll();
        items.clear();
        failures.clear();
        truncated = false;
        loading = false;
        cursor = null;
        chainIndex = 0;
        content.removeAll();
        itemGrid = null;
        stateNotice = null;
        failureList.removeAll();
        loadMore.setVisible(false);
        retry.setVisible(false);
        liveStatus.setText("");
    }

    private void renderState() {
        failureList.removeAll();
        retry.setVisible(state == GalleryState.ERROR || state == GalleryState.UNAVAILABLE
                || state == GalleryState.PARTIAL);
        if (items.isEmpty()) {
            content.removeAll();
            itemGrid = null;
            stateNotice = null;
        } else {
            ensureItemGrid();
            ensureStateNotice();
            stateNotice.setText(stateNoticeText());
            stateNotice.setVisible(!stateNotice.getText().isBlank());
        }
        if (state == GalleryState.ANONYMOUS) {
            Paragraph prompt = new Paragraph(i18n.getSignInRequired());
            content.add(prompt);
            if (loginPath != null) {
                content.add(new Anchor(loginPath, i18n.getSignInLink()));
            }
            liveStatus.setText("");
            updateContinuationControls();
            return;
        }
        if (items.isEmpty() && state == GalleryState.LOADING) {
            content.add(skeleton());
            liveStatus.setText(loading && !items.isEmpty() ? i18n.getLoadingMore() : i18n.getLoading());
        } else if (items.isEmpty() && state == GalleryState.EMPTY) {
            content.add(new Paragraph(i18n.getEmpty()));
            liveStatus.setText(i18n.getEmpty());
        } else if (items.isEmpty() && state == GalleryState.UNAVAILABLE) {
            content.add(new Paragraph(i18n.getUnavailable()));
            liveStatus.setText(i18n.getUnavailable());
        } else if (items.isEmpty() && state == GalleryState.ERROR) {
            content.add(new Paragraph(i18n.getError()));
            liveStatus.setText(i18n.getError());
        } else if (items.isEmpty() && state == GalleryState.PARTIAL) {
            content.add(new Paragraph(i18n.getPartial()));
            liveStatus.setText(i18n.getPartial());
        } else if (items.isEmpty() && state == GalleryState.READY && cursor != null) {
            content.add(new Paragraph(i18n.getContinuePage()));
        }
        updateContinuationControls();
    }

    private Div skeleton() {
        Div grid = gridContainer();
        for (int index = 0; index < 6; index++) {
            Div card = new Div();
            card.getElement().setAttribute("aria-hidden", "true");
            card.getStyle().set("height", "300px").set("background", "var(--lumo-contrast-5pct)")
                    .set("border-radius", "var(--lumo-border-radius-l)");
            grid.add(card);
        }
        return grid;
    }

    private void renderItems() {
        if (items.isEmpty()) {
            updateContinuationControls();
            return;
        }
        ensureItemGrid();
        itemGrid.removeAll();
        for (GalleryItem item : items) {
            itemGrid.add(card(item));
        }
        ensureStateNotice();
        stateNotice.setText(stateNoticeText());
        stateNotice.setVisible(!stateNotice.getText().isBlank());
        updateContinuationControls();
    }

    private void appendItems(int startIndex) {
        if (items.isEmpty()) {
            updateContinuationControls();
            return;
        }
        ensureItemGrid();
        for (int index = startIndex; index < items.size(); index++) {
            itemGrid.add(card(items.get(index)));
        }
        ensureStateNotice();
        stateNotice.setText(stateNoticeText());
        stateNotice.setVisible(!stateNotice.getText().isBlank());
        updateContinuationControls();
    }

    private void ensureItemGrid() {
        if (itemGrid == null) {
            content.removeAll();
            itemGrid = gridContainer();
            content.addComponentAtIndex(0, itemGrid);
        } else if (!content.getChildren().anyMatch(child -> child == itemGrid)) {
            content.addComponentAtIndex(0, itemGrid);
        }
    }

    private void ensureStateNotice() {
        if (stateNotice == null) {
            stateNotice = new Paragraph();
        }
        if (!content.getChildren().anyMatch(child -> child == stateNotice)) {
            content.add(stateNotice);
        }
    }

    private String stateNoticeText() {
        List<String> messages = new ArrayList<>();
        if (state == GalleryState.PARTIAL) {
            messages.add(i18n.getPartial());
        }
        if (items.size() >= maxLoadedItems) {
            messages.add(i18n.getMaxItemsReached(maxLoadedItems));
        }
        return String.join(" ", messages);
    }

    private void updateContinuationControls() {
        boolean hasMore = !truncated && items.size() < maxLoadedItems
                && (cursor != null || chainIndex < chainIds.size());
        boolean pagingState = state == GalleryState.READY || state == GalleryState.PARTIAL
                || state == GalleryState.LOADING;
        loadMore.setVisible(!loading && hasMore && pagingState);
        loadMore.setEnabled(!loading);
        retry.setVisible(!loading && (state == GalleryState.ERROR || state == GalleryState.UNAVAILABLE
                || state == GalleryState.PARTIAL));
    }

    private Div gridContainer() {
        Div grid = new Div();
        grid.addClassName("nft-gallery-grid");
        grid.getStyle().set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fill, minmax(min(100%, 220px), 1fr))")
                .set("gap", "var(--lumo-space-m)");
        return grid;
    }

    private Div card(GalleryItem item) {
        NftHolding holding = item.holding();
        NftMetadataResult result = item.metadata();
        NftMetadata metadata = result == null ? null : result.metadata();
        String name = metadata == null || metadata.name() == null || metadata.name().isBlank()
                ? "#" + holding.tokenId() : metadata.name();
        Div card = new Div();
        card.getElement().setAttribute("tabindex", "0");
        card.getElement().setAttribute("role", "button");
        card.getElement().setAttribute("aria-label", name + ", " + i18n.getAmount() + " " + holding.amount());
        card.getStyle().set("display", "flex").set("flex-direction", "column")
                .set("gap", "var(--lumo-space-xs)").set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-m)").set("cursor", "pointer");
        String imageUrl = result == null ? null : result.displayableImageUrl();
        if (imageUrl != null && !isHttps(imageUrl)) {
            imageUrl = null;
        }
        Div imageFrame = new Div();
        imageFrame.addClassName("nft-gallery-image-frame");
        imageFrame.getStyle().set("width", "100%").set("aspect-ratio", "1 / 1").set("display", "flex")
                .set("align-items", "center").set("justify-content", "center")
                .set("overflow", "hidden").set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");
        if (imageUrl == null) {
            Span placeholder = new Span(imageReason(result));
            placeholder.getStyle().set("text-align", "center").set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "var(--lumo-font-size-s)").set("padding", "var(--lumo-space-m)");
            imageFrame.add(placeholder);
        } else {
            Image image = new Image(imageUrl, name);
            image.getElement().setAttribute("loading", "lazy");
            image.getElement().setAttribute("referrerpolicy", "no-referrer");
            image.setWidthFull();
            image.setHeight("100%");
            image.getStyle().set("object-fit", "cover");
            imageFrame.add(image);
        }
        card.add(imageFrame);
        H2 nameHeading = new H2(name);
        nameHeading.addClassName("nft-gallery-name");
        nameHeading.getStyle().set("overflow-wrap", "anywhere");
        card.add(nameHeading);
        Div contract = secondaryText("nft-gallery-contract", shortAddress(holding.contract()));
        card.add(contract);
        Div amount = secondaryText("nft-gallery-amount", i18n.getAmount() + ": " + holding.amount());
        card.add(amount);
        if (result == null || !result.successful()) {
            Div metadataStatus = secondaryText("nft-gallery-metadata-status", i18n.getMetadataUnavailable());
            card.add(metadataStatus);
        }
        card.addClickListener(event -> showDetails(item, card));
        card.getElement().addEventListener("keydown", event -> {
            String key = event.getEventData().path("event.key").asString();
            if ("Enter".equals(key) || " ".equals(key)) {
                showDetails(item, card);
            }
        }).addEventData("event.key")
                .setFilter("event.key === 'Enter' || event.key === ' '")
                .preventDefault();
        return card;
    }

    private Div secondaryText(String className, String value) {
        Div text = new Div(new Span(value));
        text.addClassName(className);
        text.getStyle().set("display", "block").set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        return text;
    }

    private String imageReason(NftMetadataResult result) {
        if (result == null || result.imageReasonCode() == null) {
            return i18n.getNoImage();
        }
        return switch (result.imageReasonCode()) {
            case "SVG_IMAGE" -> i18n.getSvgImage();
            case "UNSAFE_TARGET" -> i18n.getUnsafeImage();
            case "URI_TOO_LONG" -> i18n.getLongImageUri();
            case "UNAVAILABLE", "TIMEOUT" -> i18n.getUnavailableImage();
            default -> i18n.getRejectedImage();
        };
    }

    private void showDetails(GalleryItem item, Div card) {
        focusedCard = card;
        cardFocusRequested = false;
        detailsContent.removeAll();
        NftHolding holding = item.holding();
        NftMetadataResult result = item.metadata();
        NftMetadata metadata = result == null ? null : result.metadata();
        String title = metadata == null || metadata.name() == null || metadata.name().isBlank()
                ? "#" + holding.tokenId() : metadata.name();
        detailsContent.add(new H2(i18n.getDetails()));
        detailsContent.add(labelValue(i18n.getName(), title));
        detailsContent.add(labelValue(i18n.getDescription(), metadata == null ? "" : safe(metadata.description())));
        detailsContent.add(labelValue(i18n.getContract(), holding.contract()));
        detailsContent.add(labelValue(i18n.getChain(), Long.toString(holding.chainId())));
        detailsContent.add(labelValue(i18n.getTokenId(), holding.tokenId().toString()));
        detailsContent.add(labelValue(i18n.getAmount(), holding.amount().toString()));
        detailsContent.add(labelValue(i18n.getSourceUri(), sourceUriDisplay(result == null ? null : result.sourceUri())));
        if (metadata != null) {
            addAttributes(metadata.attributes());
            addExternalLink(metadata.externalUrl());
        }
        Button close = new Button(i18n.getClose(), event -> details.close());
        detailsContent.add(close);
        details.removeAll();
        details.add(detailsContent);
        details.open();
        close.focus();
    }

    private Div labelValue(String label, String value) {
        Div row = new Div();
        row.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "var(--lumo-space-s)")
                .set("overflow-wrap", "anywhere");
        row.add(new Span(label + ": "));
        Span text = new Span(value);
        text.addClassName("nft-gallery-detail-value");
        text.getStyle().set("min-width", "0").set("overflow-wrap", "anywhere");
        row.add(text);
        return row;
    }

    private void addAttributes(List<NftMetadataAttribute> attributes) {
        if (attributes.isEmpty()) {
            return;
        }
        detailsContent.add(new H2(i18n.getAttributes()));
        Table table = new Table();
        table.getStyle().set("width", "100%").set("table-layout", "fixed").set("border-collapse", "collapse");
        TableHead head = new TableHead();
        TableRow header = new TableRow();
        TableHeaderCell traitHeader = new TableHeaderCell(i18n.getAttributeName());
        traitHeader.setScope(TableHeaderCell.Scope.COL);
        TableHeaderCell valueHeader = new TableHeaderCell(i18n.getValue());
        valueHeader.setScope(TableHeaderCell.Scope.COL);
        header.add(traitHeader, valueHeader);
        head.add(header);
        TableBody body = new TableBody();
        for (NftMetadataAttribute attribute : attributes) {
            TableRow row = new TableRow();
            row.add(new TableDataCell(safe(attribute.traitType())));
            row.add(new TableDataCell(safe(attribute.value())));
            body.add(row);
        }
        table.setHead(head);
        table.addBody(body);
        detailsContent.add(table);
    }

    private void addExternalLink(String value) {
        if (value == null || !isHttps(value)) {
            return;
        }
        Anchor link = new Anchor(value, i18n.getExternalUrl());
        link.setTarget("_blank");
        link.getElement().setAttribute("rel", "noopener noreferrer");
        detailsContent.add(link);
    }

    private boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void focusCard() {
        if (focusedCard != null) {
            cardFocusRequested = true;
            focusedCard.getElement().callJsFunction("focus");
        }
    }

    private void renderFailures() {
        failureList.removeAll();
        for (NftOwnershipFailure failure : failures) {
            String code = failure.code().name();
            String message = i18n.ownershipFailure(failure.chainId(), failure.contract(), i18n.failureLabel(code),
                    i18n.failureMessage(code));
            failureList.add(new Paragraph(message));
        }
    }

    private boolean allUnavailable(List<NftOwnershipFailure> pageFailures) {
        return pageFailures.stream().allMatch(failure -> failure.code() == NftFailureCode.UNAVAILABLE);
    }

    private String shortAddress(String address) {
        return address.length() <= 12 ? address : address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String displayText(String value) {
        String text = safe(value);
        return text.length() <= 2048 ? text : text.substring(0, 2048) + "…";
    }

    private String sourceUriDisplay(String value) {
        String text = safe(value);
        if (text.regionMatches(true, 0, "data:", 0, 5)) {
            return i18n.inlineJsonSummary(text.length());
        }
        return displayText(text);
    }

    private String safeLoginPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("/") && !value.startsWith("//") && !value.contains("\\") && !value.contains("\n")) {
            return value;
        }
        throw new IllegalArgumentException("loginPath must be an internal route");
    }

    public enum GalleryState {
        ANONYMOUS,
        LOADING,
        READY,
        EMPTY,
        PARTIAL,
        UNAVAILABLE,
        ERROR
    }

    public record GalleryItem(NftHolding holding, NftMetadataResult metadata) { }

    void loadMoreForTest() {
        loadNextPage();
    }

    void detachForTest() {
        onDetach(new DetachEvent(this));
    }

    void showDetailsForTest(int index) {
        GalleryItem item = items.get(index);
        showDetails(item, card(item));
    }

    String detailsTextForTest() {
        return componentText(details);
    }

    boolean detailsHasExternalAnchorForTest() {
        return detailsContent.getChildren().anyMatch(child -> "a".equals(child.getElement().getTag()));
    }

    String detailsExternalAnchorHrefForTest() {
        return detailsContent.getChildren().filter(child -> "a".equals(child.getElement().getTag()))
                .map(child -> child.getElement().getAttribute("href")).findFirst().orElse(null);
    }

    String detailsExternalAnchorTargetForTest() {
        return detailsContent.getChildren().filter(child -> "a".equals(child.getElement().getTag()))
                .map(child -> child.getElement().getAttribute("target")).findFirst().orElse(null);
    }

    String detailsExternalAnchorRelForTest() {
        return detailsContent.getChildren().filter(child -> "a".equals(child.getElement().getTag()))
                .map(child -> child.getElement().getAttribute("rel")).findFirst().orElse(null);
    }

    boolean cardImageRenderedForTest() {
        return containsTag(content, "img");
    }

    private boolean containsTag(Component component, String tag) {
        return tag.equals(component.getElement().getTag())
                || component.getChildren().anyMatch(child -> containsTag(child, tag));
    }

    String detailsRawHtmlForTest() {
        return details.getElement().getProperty("innerHTML");
    }

    void fireDialogCloseActionForTest() {
        ComponentUtil.fireEvent(details, new Dialog.DialogCloseActionEvent(details, true));
    }

    boolean dialogOpenedForTest() {
        return details.isOpened();
    }

    boolean detailsHasContentForTest() {
        return detailsContent.getChildren().findAny().isPresent();
    }

    Component cardForTest(int index) {
        return itemGrid.getChildren().skip(index).findFirst().orElseThrow();
    }

    boolean loadMoreVisibleForTest() {
        return loadMore.isVisible();
    }

    boolean retryVisibleForTest() {
        return retry.isVisible();
    }

    void clickRetryForTest() {
        retry.click();
    }

    String failureTextForTest() {
        return componentText(failureList);
    }

    boolean cardFocusRequestedForTest() {
        return cardFocusRequested;
    }

    String dialogMaxWidthForTest() {
        return details.getMaxWidth();
    }

    boolean detailsHasAttributeTableForTest() {
        return detailsContent.getChildren().anyMatch(child -> "table".equals(child.getElement().getTag()));
    }

    long attributeTableColumnCountForTest() {
        return detailsContent.getChildren().filter(child -> "table".equals(child.getElement().getTag()))
                .map(Table.class::cast).flatMap(table -> table.getHeaderRows().stream())
                .findFirst().map(row -> row.getChildren().count()).orElse(0L);
    }

    Span sourceUriValueForTest() {
        return detailsContent.getChildren().filter(Div.class::isInstance).map(Div.class::cast)
                .filter(row -> row.getChildren().findFirst().map(child -> (i18n.getSourceUri() + ": ")
                        .equals(child.getElement().getText())).orElse(false))
                .flatMap(row -> row.getChildren().skip(1)).map(Span.class::cast).findFirst().orElseThrow();
    }

    String liveStatusForTest() {
        return liveStatus.getText();
    }

    String allTextForTest() {
        return componentText(this);
    }

    private String componentText(Component component) {
        StringBuilder value = new StringBuilder(component.getElement().getText());
        component.getChildren().forEach(child -> value.append(componentText(child)));
        return value.toString();
    }

}
