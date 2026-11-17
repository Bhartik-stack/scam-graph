package com.scamgraph.data;

import com.scamgraph.analysis.Format;
import com.scamgraph.model.Transaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes transaction CSV files.
 *
 * Required columns: from_account, to_account, amount, timestamp
 * Optional columns: txn_id, from_name, to_name, channel
 * Column names are matched loosely (e.g. "sender", "receiver", "date", "time" also work).
 */
public final class CsvIO {

    public static final String HEADER = "txn_id,from_account,from_name,to_account,to_name,amount,timestamp,channel";

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

    private CsvIO() {
    }

    // ------------------------------------------------------------------ write
    public static String write(List<Transaction> txns) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Transaction t : txns) {
            sb.append(t.id()).append(',')
                    .append(t.from()).append(',').append(quote(t.fromName())).append(',')
                    .append(t.to()).append(',').append(quote(t.toName())).append(',')
                    .append(t.amount()).append(',')
                    .append(Format.time(t.time())).append(',')
                    .append(t.channel() == null ? "" : t.channel()).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ------------------------------------------------------------------ read
    public static List<Transaction> read(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) throw new IllegalArgumentException("The file is empty.");
        if (headerLine.startsWith("﻿")) headerLine = headerLine.substring(1);   // Excel adds this
        List<String> header = parseLine(headerLine);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.put(normalise(header.get(i)), i);

        int from = find(col, "fromaccount", "from", "sender", "senderaccount", "source", "payer");
        int to = find(col, "toaccount", "to", "receiver", "receiveraccount", "beneficiary", "target", "payee");
        int amount = find(col, "amount", "amt", "value", "amountinr", "rupees");
        int time = find(col, "timestamp", "datetime", "time", "date", "transactiontime");
        if (from < 0 || to < 0 || amount < 0 || time < 0) {
            throw new IllegalArgumentException("CSV must have columns: from_account, to_account, amount, timestamp. Found: " + header);
        }
        int id = find(col, "txnid", "id", "transactionid", "txn");
        int fromName = find(col, "fromname", "sendername", "payername");
        int toName = find(col, "toname", "receivername", "payeename", "beneficiaryname");
        int channel = find(col, "channel", "mode", "type", "paymentmode");

        List<Transaction> result = new ArrayList<>();
        String line;
        int lineNo = 1;
        int skipped = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) continue;
            List<String> cells = parseLine(line);
            try {
                String f = cell(cells, from);
                String t = cell(cells, to);
                long a = Math.round(Double.parseDouble(cell(cells, amount).replace(",", "").replace("₹", "").trim()));
                LocalDateTime when = parseTime(cell(cells, time));
                if (f.isEmpty() || t.isEmpty() || a <= 0) {
                    skipped++;
                    continue;
                }
                String txnId = id >= 0 && !cell(cells, id).isEmpty() ? cell(cells, id) : "ROW" + lineNo;
                result.add(new Transaction(txnId, f, cell(cells, fromName), t, cell(cells, toName), a, when, cell(cells, channel)));
            } catch (RuntimeException e) {
                skipped++;
                if (skipped > 50 && result.isEmpty()) {
                    throw new IllegalArgumentException("Could not read rows. First problem at line " + lineNo + ": " + e.getMessage());
                }
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No valid transactions found in the file.");
        return result;
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static int find(Map<String, Integer> col, String... names) {
        for (String n : names) {
            Integer i = col.get(n);
            if (i != null) return i;
        }
        return -1;
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) return "";
        return cells.get(index).trim();
    }

    static LocalDateTime parseTime(String s) {
        String v = s.trim();
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        try {
            return LocalDate.parse(v).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unknown date format: " + s);
        }
    }

    /** Splits one CSV line, handling "quoted, values" and "" escapes. */
    static List<String> parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }
}
