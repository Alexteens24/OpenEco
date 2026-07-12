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

package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeaderboardCacheTest {

    @Test
    void dirtyLeaderboardKeepsOldSnapshotUntilBackgroundRefresh() {
        LeaderboardCache cache = new LeaderboardCache();
        cache.configureCurrencies(List.of("openeco"));

        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("50.00"), 1L, 1L);
        AccountRecord bob = new AccountRecord(UUID.randomUUID(), "Bob", new BigDecimal("10.00"), 1L, 1L);

        cache.rebuildAll(List.of(alice, bob));
        LeaderboardView first = cache.page("openeco", 0, 10);
        assertEquals(List.of("Alice", "Bob"), first.entries().stream().map(LeaderboardEntry::name).toList());
        assertEquals(1, cache.rankOf("openeco", alice.getId()));

        alice.setBalance(new BigDecimal("5.00"));
        bob.setBalance(new BigDecimal("100.00"));

        cache.markDirty("openeco");
        assertEquals("Alice", cache.entryAtRank("openeco", 1).name());

        cache.refreshDirty(List.of(alice, bob));
        LeaderboardView refreshed = cache.page("openeco", 0, 10);
        assertEquals(List.of("Bob", "Alice"), refreshed.entries().stream().map(LeaderboardEntry::name).toList());
        assertEquals(1, cache.rankOf("openeco", bob.getId()));
        assertEquals(2, cache.rankOf("openeco", alice.getId()));
    }

    @Test
    void configurationDropsRemovedCurrenciesAndSlicesWithoutCopyingAllEntries() {
        LeaderboardCache cache = new LeaderboardCache();
        cache.configureCurrencies(List.of("openeco", "gems"));

        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("50.00"), 1L, 1L);

        cache.rebuildAll(List.of(alice));
        assertEquals(1, cache.page("openeco", 0, 1).totalEntries());
        assertEquals(0, cache.page("openeco", 1, 1).entries().size());

        cache.configureCurrencies(List.of("gems"));
        assertNull(cache.entryAtRank("openeco", 1));
    }
}
