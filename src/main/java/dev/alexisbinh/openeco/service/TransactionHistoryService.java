package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.storage.TransactionRepository;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class TransactionHistoryService {

    private final TransactionRepository repository;
    private final Logger log;
    private final ExecutorService transactionExecutor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    TransactionHistoryService(TransactionRepository repository, String threadNamePrefix, Logger log) {
        this.repository = repository;
        this.log = log;
        this.transactionExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadNamePrefix + "-transactions");
            thread.setDaemon(true);
            return thread;
        });
    }

    void log(TransactionEntry entry) {
        if (shuttingDown.get()) {
            insert(entry);
            return;
        }

        try {
            transactionExecutor.execute(() -> insert(entry));
        } catch (RejectedExecutionException e) {
            insert(entry);
        }
    }

    List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize) throws SQLException {
        return repository.getTransactions(playerId, pageSize, (page - 1) * pageSize);
    }

    List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize,
            String currencyId) throws SQLException {
        return repository.getTransactions(playerId, pageSize, (page - 1) * pageSize, currencyId);
    }

    List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return repository.getTransactions(playerId, pageSize, (page - 1) * pageSize, type, fromMs, toMs);
    }

    List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize,
            @Nullable TransactionType type, long fromMs, long toMs,
            String currencyId) throws SQLException {
        return repository.getTransactions(playerId, pageSize, (page - 1) * pageSize, type, fromMs, toMs, currencyId);
    }

    int countTransactions(UUID playerId) throws SQLException {
        return repository.countTransactions(playerId);
    }

    int countTransactions(UUID playerId, String currencyId) throws SQLException {
        return repository.countTransactions(playerId, currencyId);
    }

    int countTransactions(UUID playerId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return repository.countTransactions(playerId, type, fromMs, toMs);
    }

    int countTransactions(UUID playerId, @Nullable TransactionType type,
            long fromMs, long toMs, String currencyId) throws SQLException {
        return repository.countTransactions(playerId, type, fromMs, toMs, currencyId);
    }

    /**
     * Submits a prune job to the transaction executor. Rows with timestamp older
     * than {@code retentionDays} days are deleted.
     *
     * @param retentionDays must be > 0
     */
    void pruneOldTransactions(int retentionDays) {
        if (shuttingDown.get()) return;
        long cutoffMs = System.currentTimeMillis() - (long) retentionDays * 86_400_000L;
        try {
            transactionExecutor.execute(() -> {
                try {
                    int deleted = repository.pruneTransactions(cutoffMs);
                    if (deleted > 0) {
                        log.info("Pruned " + deleted + " transaction(s) older than " + retentionDays + " day(s).");
                    }
                } catch (SQLException e) {
                    log.warning("Failed to prune transaction history: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Executor already shut down; skip pruning.
        }
    }

    boolean waitForDrain() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            transactionExecutor.execute(() -> barrier.complete(null));
            barrier.get(10, TimeUnit.SECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            return shuttingDown.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Interrupted while waiting for pending transaction writes.");
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warning("Failed to wait for pending transaction writes: " + e.getMessage());
            return false;
        }
    }

    void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        transactionExecutor.shutdown();
        try {
            if (!transactionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Timed out waiting for pending transaction writes. Forcing shutdown.");
                transactionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Interrupted while waiting for pending transaction writes.");
            transactionExecutor.shutdownNow();
        }
    }

    private void insert(TransactionEntry entry) {
        try {
            repository.insertTransaction(entry);
        } catch (SQLException e) {
            log.warning("Failed to log transaction: " + e.getMessage());
        }
    }
}