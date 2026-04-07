package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountRegistryTest {

    @Test
    void createRenameRemoveAndRestoreKeepLookupsConsistent() {
        AccountRegistry registry = new AccountRegistry();
        AccountRecord live = new AccountRecord(
                UUID.randomUUID(),
                "Alice",
                new BigDecimal("12.50"),
                100L,
                100L);

        assertTrue(registry.create(live));
        assertTrue(registry.hasAccount(live.getId()));
        assertEquals("Alice", registry.findSnapshotByName("alice").orElseThrow().getLastKnownName());

        assertTrue(registry.rename(live, "Bob"));
        assertTrue(registry.findSnapshotByName("alice").isEmpty());
        assertEquals("Bob", registry.findSnapshotByName("bob").orElseThrow().getLastKnownName());

        assertTrue(registry.remove(live.getId(), live));
        assertFalse(registry.hasAccount(live.getId()));
        assertTrue(registry.findSnapshotByName("bob").isEmpty());

        registry.restore(live);
        assertTrue(registry.hasAccount(live.getId()));
        assertEquals("Bob", registry.findSnapshotByName("bob").orElseThrow().getLastKnownName());
    }

    @Test
    void snapshotsAreDetachedFromLiveRecord() {
        AccountRegistry registry = new AccountRegistry();
        UUID accountId = UUID.randomUUID();
        AccountRecord live = new AccountRecord(accountId, "Alice", new BigDecimal("10.00"), 100L, 100L);

        assertTrue(registry.create(live));
        Optional<AccountRecord> snapshotOpt = registry.getSnapshot(accountId);
        assertTrue(snapshotOpt.isPresent());

        AccountRecord snapshot = snapshotOpt.orElseThrow();
        live.setBalance(new BigDecimal("25.00"));

        assertEquals(new BigDecimal("10.00"), snapshot.getBalance());
        assertEquals(new BigDecimal("25.00"), registry.getLiveRecord(accountId).getBalance());
    }

    @Test
    void duplicateNamesAreRejectedCaseInsensitively() {
        AccountRegistry registry = new AccountRegistry();
        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("10.00"), 1L, 1L);
        AccountRecord duplicate = new AccountRecord(UUID.randomUUID(), "alice", new BigDecimal("5.00"), 1L, 1L);

        assertTrue(registry.create(alice));
        assertFalse(registry.create(duplicate));
        assertTrue(registry.isNameClaimedByAnother(duplicate.getId(), "ALICE"));
    }

    @Test
    void renameRejectsNamesClaimedByAnotherAccount() {
        AccountRegistry registry = new AccountRegistry();
        AccountRecord alice = new AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("10.00"), 1L, 1L);
        AccountRecord bob = new AccountRecord(UUID.randomUUID(), "Bob", new BigDecimal("5.00"), 1L, 1L);

        assertTrue(registry.create(alice));
        assertTrue(registry.create(bob));
        assertFalse(registry.rename(bob, "ALICE"));
        assertEquals("Bob", registry.getLiveRecord(bob.getId()).getLastKnownName());
        assertEquals("Alice", registry.findSnapshotByName("alice").orElseThrow().getLastKnownName());
    }

    @Test
    void replaceUpdatesExistingRecordAndMovesNameLookup() {
        AccountRegistry registry = new AccountRegistry();
        UUID accountId = UUID.randomUUID();
        AccountRecord live = new AccountRecord(accountId, "Alice", new BigDecimal("10.00"), 1L, 1L);
        AccountRecord refreshed = new AccountRecord(accountId, "Alicia", new BigDecimal("15.00"), 1L, 2L);

        assertTrue(registry.create(live));
        assertTrue(registry.replace(refreshed));

        assertTrue(registry.findSnapshotByName("alice").isEmpty());
        assertEquals("Alicia", registry.findSnapshotByName("alicia").orElseThrow().getLastKnownName());
        assertEquals(new BigDecimal("15.00"), registry.getLiveRecord(accountId).getBalance());
    }

    @Test
    void replaceRejectsConflictingNameAndKeepsExistingRecord() {
        AccountRegistry registry = new AccountRegistry();
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        AccountRecord alice = new AccountRecord(aliceId, "Alice", new BigDecimal("10.00"), 1L, 1L);
        AccountRecord bob = new AccountRecord(bobId, "Bob", new BigDecimal("5.00"), 1L, 1L);
        AccountRecord conflictingAlice = new AccountRecord(aliceId, "Bob", new BigDecimal("12.00"), 1L, 2L);

        assertTrue(registry.create(alice));
        assertTrue(registry.create(bob));
        assertFalse(registry.replace(conflictingAlice));

        assertEquals("Alice", registry.getLiveRecord(aliceId).getLastKnownName());
        assertEquals(new BigDecimal("10.00"), registry.getLiveRecord(aliceId).getBalance());
        assertEquals("Bob", registry.findSnapshotByName("bob").orElseThrow().getLastKnownName());
    }
}