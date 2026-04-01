package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class LeaderboardCacheTest {

    @Test
    void cacheKeepsOrderingUntilInvalidated() {
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

        List<AccountRecord> second = cache.getSnapshot(List.of(alice, bob));
        assertSame(first, second);
        assertEquals(0, new BigDecimal("50.00").compareTo(second.getFirst().getBalance()));
        assertEquals(0, new BigDecimal("10.00").compareTo(second.getLast().getBalance()));

        cache.invalidate();

        List<AccountRecord> refreshed = cache.getSnapshot(List.of(alice, bob));
        assertEquals(List.of("Bob", "Alice"), refreshed.stream().map(AccountRecord::getLastKnownName).toList());
        assertEquals(0, new BigDecimal("100.00").compareTo(refreshed.getFirst().getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(refreshed.getLast().getBalance()));
    }
}