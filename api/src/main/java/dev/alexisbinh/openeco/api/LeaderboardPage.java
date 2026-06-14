/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
