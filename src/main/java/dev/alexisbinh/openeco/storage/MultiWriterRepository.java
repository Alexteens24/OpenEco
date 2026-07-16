/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.storage;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Database-authoritative operations used by cross-server multi-writer mode. */
public interface MultiWriterRepository {

    enum MutationStatus {
        SUCCESS,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        FROZEN,
        COOLDOWN,
        NAME_IN_USE,
        ALREADY_EXISTS,
        ALREADY_APPLIED,
        POLICY_REJECTED
    }

    enum BalanceMutationKind { DEPOSIT, WITHDRAW, SET }

    enum ChangeKind { UPSERT, DELETE }

    record BalanceMutationRequest(
            UUID operationId,
            UUID accountId,
            String currencyId,
            BalanceMutationKind kind,
            BigDecimal amount,
            @Nullable BigDecimal maxBalance,
            TransactionType transactionType,
            long timestamp,
            @Nullable String source,
            @Nullable String note) {}

    record BalanceMutationResult(
            MutationStatus status,
            BigDecimal amount,
            BigDecimal before,
            BigDecimal after,
            @Nullable AccountRecord account) {}

    record TransferMutationRequest(
            UUID operationId,
            UUID fromId,
            UUID toId,
            String currencyId,
            BigDecimal sent,
            BigDecimal received,
            BigDecimal tax,
            @Nullable BigDecimal recipientMaxBalance,
            long cooldownMs,
            boolean applyCooldown,
            List<RollingPolicyConstraint> rollingPolicies,
            long timestamp) {
        public TransferMutationRequest {
            rollingPolicies = rollingPolicies == null ? List.of() : List.copyOf(rollingPolicies);
            long distinctProviders = rollingPolicies.stream()
                    .map(RollingPolicyConstraint::providerId).distinct().count();
            if (distinctProviders != rollingPolicies.size()) {
                throw new IllegalArgumentException("rolling policy provider ids must be unique");
            }
        }

        public TransferMutationRequest(
                UUID operationId, UUID fromId, UUID toId, String currencyId,
                BigDecimal sent, BigDecimal received, BigDecimal tax,
                @Nullable BigDecimal recipientMaxBalance, long cooldownMs, boolean applyCooldown,
                @Nullable String rollingPolicyId, @Nullable BigDecimal rollingMaximum,
                long rollingWindowMs, long timestamp) {
            this(operationId, fromId, toId, currencyId, sent, received, tax,
                    recipientMaxBalance, cooldownMs, applyCooldown,
                    rollingPolicyId == null || rollingMaximum == null || rollingWindowMs <= 0
                            ? List.of()
                            : List.of(new RollingPolicyConstraint(
                                    rollingPolicyId, rollingMaximum, rollingWindowMs)),
                    timestamp);
        }
    }

    record RollingPolicyConstraint(String providerId, BigDecimal maximumAmount, long windowMs) {
        public RollingPolicyConstraint {
            if (providerId == null || !providerId.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("providerId must match [a-z0-9_.-]{1,64}");
            }
            if (maximumAmount == null || maximumAmount.signum() <= 0) {
                throw new IllegalArgumentException("maximumAmount must be positive");
            }
            if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        }
    }

    record TransferMutationResult(
            MutationStatus status,
            BigDecimal sent,
            BigDecimal received,
            BigDecimal tax,
            long cooldownRemainingMs,
            @Nullable AccountRecord fromAccount,
            @Nullable AccountRecord toAccount) {}

    record ExchangeMutationRequest(
            UUID operationId,
            UUID accountId,
            String fromCurrencyId,
            String toCurrencyId,
            BigDecimal fromAmount,
            BigDecimal toAmount,
            @Nullable BigDecimal targetMaxBalance,
            long timestamp,
            @Nullable String source) {}

    record ExchangeMutationResult(
            MutationStatus status,
            BigDecimal fromBefore,
            BigDecimal fromAfter,
            BigDecimal toBefore,
            BigDecimal toAfter,
            @Nullable AccountRecord account) {}

    record AccountChange(long sequence, UUID accountId, long version, ChangeKind kind) {}

    record AccountWriteResult(MutationStatus status, @Nullable AccountRecord account) {}

    BalanceMutationResult mutateBalance(BalanceMutationRequest request) throws SQLException;

    TransferMutationResult transfer(TransferMutationRequest request) throws SQLException;

    ExchangeMutationResult exchange(ExchangeMutationRequest request) throws SQLException;

    AccountWriteResult createAccount(UUID operationId, UUID accountId, String name,
                                     Map<String, BigDecimal> balances, String primaryCurrencyId,
                                     long timestamp) throws SQLException;

    AccountWriteResult renameAccount(UUID operationId, UUID accountId, String newName,
                                     long timestamp) throws SQLException;

    AccountWriteResult setFrozen(UUID operationId, UUID accountId, boolean frozen,
                                 long timestamp) throws SQLException;

    AccountWriteResult deleteAccount(UUID operationId, UUID accountId, long timestamp) throws SQLException;

    long currentChangeSequence() throws SQLException;

    List<AccountChange> loadChangesAfter(long sequence, int limit) throws SQLException;

    Map<UUID, Optional<AccountRecord>> loadAccounts(Iterable<UUID> ids) throws SQLException;

    int pruneAccountChanges(long cutoffMs) throws SQLException;

    boolean tryAcquireJobLease(String jobId, String runId, String ownerId,
                               long now, long leaseUntil) throws SQLException;

    void completeJobLease(String jobId, String runId, String ownerId) throws SQLException;
}
