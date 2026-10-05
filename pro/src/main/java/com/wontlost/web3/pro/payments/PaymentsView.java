package com.wontlost.web3.pro.payments;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.wontlost.web3.pay.PaymentStatus;

/** Displays recorded payments with status/date filters and CSV export. The supplied store is transient; register it through {@link PaymentRecordStore#register(com.vaadin.flow.server.VaadinContext, PaymentRecordStore)} so it can be resolved again after UI deserialization. */
public final class PaymentsView extends Composite<VerticalLayout> {
    private transient PaymentRecordStore store;
    private final Grid<PaymentRecord> grid = new Grid<>(PaymentRecord.class, false);
    private final Anchor csv = new Anchor("", "Download CSV");
    private final DatePicker from = new DatePicker("From");
    private final DatePicker to = new DatePicker("To");
    private final Select<String> status = new Select<>();
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final com.vaadin.flow.component.html.Span pageLabel = new com.vaadin.flow.component.html.Span();
    private int page;
    private static final int PAGE_SIZE = 50;

    /** Creates a view using the application-scoped payment store. */
    public PaymentsView(PaymentRecordStore store) {
        this.store = Objects.requireNonNull(store, "store");
        VaadinService service = VaadinService.getCurrent();
        if (service != null) PaymentRecordStore.register(service.getContext(), store);
        configureGrid();
        status.setLabel("Status");
        status.setItems(java.util.stream.Stream.concat(java.util.stream.Stream.of("All"),
                java.util.Arrays.stream(PaymentStatus.values()).map(Enum::name)).toList());
        status.setValue("All");
        from.setValue(LocalDate.now(ZoneOffset.UTC).minusDays(30));
        to.setValue(LocalDate.now(ZoneOffset.UTC));
        from.addValueChangeListener(event -> refreshFromFirstPage());
        to.addValueChangeListener(event -> refreshFromFirstPage());
        status.addValueChangeListener(event -> refreshFromFirstPage());
        previous.addClickListener(event -> { if (page > 0) { page--; refresh(); } });
        next.addClickListener(event -> { if ((page + 1L) * PAGE_SIZE < matchingCount()) { page++; refresh(); } });
        getContent().add(new HorizontalLayout(from, to, status, csv), grid,
                new HorizontalLayout(previous, pageLabel, next));
        getContent().setSizeFull();
        refresh();
    }

    private void configureGrid() {
        grid.addColumn(record -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(record.createdAt().atOffset(ZoneOffset.UTC)))
                .setHeader("Time").setKey("time");
        grid.addColumn(PaymentRecord::orderId).setHeader("Order").setKey("order");
        grid.addColumn(PaymentRecord::status).setHeader("Status").setKey("status");
        grid.addColumn(record -> record.tokenSymbol() + " (" + record.chainId() + ")").setHeader("Token").setKey("token");
        grid.addColumn(PaymentRecord::amount).setHeader("Amount").setKey("amount");
        grid.addColumn(record -> shorten(record.payer())).setHeader("Payer").setKey("payer");
        grid.addComponentColumn(record -> {
            Button copy = new Button(shorten(record.txHash()));
            copy.setEnabled(record.txHash() != null && !record.txHash().isBlank());
            copy.getElement().setAttribute("title", "Copy transaction hash");
            copy.addClickListener(event -> getUI().ifPresent(ui -> ui.getPage().executeJs(
                    "navigator.clipboard.writeText($0)", record.txHash())));
            return copy;
        }).setHeader("Transaction hash").setKey("txHash");
        // 每页最多 PAGE_SIZE 行：直接展开全部行，不依赖宿主布局是否给出确定高度
        // （百分比高度在未定高的父容器里会塌缩成约一行，记录会被藏在滚动条后面）
        grid.setWidthFull();
        grid.setAllRowsVisible(true);
    }

    @Override protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        if (store == null) store = PaymentRecordStore.find(event.getUI().getSession().getService().getContext()).orElse(null);
        if (store == null) throw new IllegalStateException("Register a PaymentRecordStore in VaadinContext before attaching PaymentsView");
        csv.setHref(createDownloadHandler());
        refresh();
    }

    private DownloadHandler createDownloadHandler() {
        return event -> {
            event.getResponse().setContentType("text/csv; charset=utf-8");
            event.getResponse().setHeader("Content-Disposition", "attachment; filename=payment-records.csv");
            writeCsv(new OutputStreamWriter(event.getOutputStream(), StandardCharsets.UTF_8));
        };
    }

    /** Reloads the current page using the active filters. */
    public void refresh() {
        if (store == null) return;
        grid.setItems(store.find(fromInstant(), toInstant(), selectedStatus(), page * PAGE_SIZE, PAGE_SIZE));
        long total = matchingCount();
        pageLabel.setText("Page " + (page + 1) + " of " + Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        previous.setEnabled(page > 0);
        next.setEnabled((page + 1L) * PAGE_SIZE < total);
    }

    List<PaymentRecord> visibleRecords() { return grid.getListDataView().getItems().toList(); }
    private long matchingCount() { return store == null ? 0 : store.count(fromInstant(), toInstant(), selectedStatus()); }
    private Instant fromInstant() { return (from.getValue() == null ? LocalDate.of(1970, 1, 1) : from.getValue()).atStartOfDay().toInstant(ZoneOffset.UTC); }
    private Instant toInstant() { return (to.getValue() == null ? LocalDate.now(ZoneOffset.UTC).plusDays(1) : to.getValue().plusDays(1)).atStartOfDay().toInstant(ZoneOffset.UTC); }
    private String selectedStatus() { return status.getValue() == null || "All".equals(status.getValue()) ? null : status.getValue(); }
    private void refreshFromFirstPage() { page = 0; refresh(); }
    void writeCsv(Writer writer) throws IOException {
        PaymentRecordsCsv.writeHeader(writer);
        // 导出开始时固定查询窗口：上界取筛选截止与当前时间的较早者，导出期间新插入的记录（created_at 更晚）
        // 不会落入窗口，也就不会移动后续页的 offset 边界
        Instant from = fromInstant();
        Instant until = java.util.Collections.min(List.of(toInstant(), Instant.now()));
        String state = selectedStatus();
        int offset = 0;
        while (true) {
            List<PaymentRecord> batch = store.find(from, until, state, offset, 500);
            for (PaymentRecord record : batch) PaymentRecordsCsv.writeRecord(writer, record);
            writer.flush();
            if (batch.size() < 500) return;
            offset += batch.size();
        }
    }
    private static String shorten(String value) {
        return value == null || value.length() < 14 ? value : value.substring(0, 8) + "…" + value.substring(value.length() - 6);
    }
}
