package com.wontlost.web3.pro.payments;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/** Writes RFC 4180 CSV with spreadsheet formula-injection mitigation. */
public final class PaymentRecordsCsv {
    private PaymentRecordsCsv() { }
    /** Writes a header and all rows to the supplied writer. */
    public static void write(Writer writer, Iterable<PaymentRecord> records) throws IOException {
        Objects.requireNonNull(writer, "writer");
        writeHeader(writer);
        for (PaymentRecord record : records) writeRecord(writer, record);
    }
    static void writeHeader(Writer writer) throws IOException {
        row(writer, "orderId", "chainId", "tokenSymbol", "tokenAddress", "amount", "payer", "txHash", "status", "createdAt", "updatedAt");
    }
    static void writeRecord(Writer writer, PaymentRecord record) throws IOException {
        row(writer, record.orderId(), Long.toString(record.chainId()), record.tokenSymbol(),
                record.tokenAddress(), record.amount(), record.payer(), record.txHash(), record.status(),
                record.createdAt().toString(), record.updatedAt().toString());
    }
    private static boolean needsFormulaProtection(String value) {
        if (value.isEmpty()) return false;
        if (value.charAt(0) == '\t' || value.charAt(0) == '\r') return true;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
            index += Character.charCount(codePoint);
        }
        return index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0;
    }
    private static void row(Writer writer, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) writer.write(',');
            String cell = cells[i] == null ? "" : cells[i];
            if (needsFormulaProtection(cell)) cell = "'" + cell;
            boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\r') >= 0 || cell.indexOf('\n') >= 0;
            if (quote) writer.write('"');
            writer.write(quote ? cell.replace("\"", "\"\"") : cell);
            if (quote) writer.write('"');
        }
        writer.write("\r\n");
    }
}
