package dev.alexisbinh.openeco.api;

import java.util.List;

/**
 * A paginated slice of the leaderboard, ordered by balance descending.
 *
 * <p>Backed by the same cached snapshot as {@link OpenEcoApi#getTopAccounts(int)}, so all
 * entries on a given page reflect the same cache generation.</p>
 */
public record LeaderboardPage(
        int page,
        int pageSize,
        int totalEntries,
        int totalPages,
        List<AccountSnapshot> entries
) {

    public LeaderboardPage {
        entries = List.copyOf(entries);
    }

    /** Whether there is a next page after this one. */
    public boolean hasNextPage() {
        return page < totalPages;
    }
}
