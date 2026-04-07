package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class LeaderboardCacheTest {

    @Test
    void markDirtyRebuildsLeaderboardImmediately() {
        LeaderboardCache cache = new LeaderboardCache();
        cache.setCacheTtlMs(60_000L);

        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("50.00"), 1L, 1L);
        AccountRecord bob = new AccountRecord(UUID.randomUUID(), "Bob", new BigDecimal("10.00"), 1L, 1L);

        List<AccountRecord> first = cache.getSnapshot(List.of(alice, bob));
        assertNotSame(alice, first.getFirst());
        assertNotSame(bob, first.getLast());
        assertEquals(List.of("Alice", "Bob"), first.stream().map(AccountRecord::getLastKnownName).toList());
        assertEquals(0, new BigDecimal("50.00").compareTo(first.getFirst().getBalance()));
        assertEquals(0, new BigDecimal("10.00").compareTo(first.getLast().getBalance()));

        alice.setBalance(new BigDecimal("5.00"));
        bob.setBalance(new BigDecimal("100.00"));

        cache.markDirty();

        List<AccountRecord> refreshed = cache.getSnapshot(List.of(alice, bob));
        assertNotSame(first, refreshed);
        assertEquals(List.of("Bob", "Alice"), refreshed.stream().map(AccountRecord::getLastKnownName).toList());
        assertEquals(0, new BigDecimal("100.00").compareTo(refreshed.getFirst().getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(refreshed.getLast().getBalance()));
    }

    @Test
    void invalidateDropsCachedSnapshotSoRenamesAppearImmediately() {
        LeaderboardCache cache = new LeaderboardCache();
        cache.setCacheTtlMs(60_000L);

        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("50.00"), 1L, 1L);

        List<AccountRecord> first = cache.getSnapshot(List.of(alice));
        alice.setLastKnownName("Alicia");

        cache.invalidate();

        List<AccountRecord> refreshed = cache.getSnapshot(List.of(alice));
        assertNotSame(first, refreshed);
        assertEquals("Alicia", refreshed.getFirst().getLastKnownName());
    }
}