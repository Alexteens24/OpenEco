package dev.alexisbinh.simpleeco.api;

import java.util.List;

public record HistoryPage(
        int page,
        int pageSize,
        int totalEntries,
        int totalPages,
        List<TransactionSnapshot> entries
) {

    public HistoryPage {
        entries = List.copyOf(entries);
    }
}