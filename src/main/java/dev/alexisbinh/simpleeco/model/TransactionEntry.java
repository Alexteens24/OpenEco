package dev.alexisbinh.simpleeco.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable record of a single economy event, always stored from the perspective
 * of {@code targetId} (the player whose balance changed).
 *
 * <ul>
 *   <li>For PAY_SENT: {@code counterpartId} = recipient UUID.</li>
 *   <li>For PAY_RECEIVED: {@code counterpartId} = sender UUID.</li>
 *   <li>For GIVE/TAKE/SET/RESET: {@code counterpartId} is null (admin/API action).</li>
 * </ul>
 */
public final class TransactionEntry {

    private final TransactionType type;
    private final UUID counterpartId; // nullable
    private final UUID targetId;
    private final BigDecimal amount;
    private final BigDecimal balanceBefore;
    private final BigDecimal balanceAfter;
    private final long timestamp;

    public TransactionEntry(TransactionType type, UUID counterpartId, UUID targetId,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, long timestamp) {
        this.type = type;
        this.counterpartId = counterpartId;
        this.targetId = targetId;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.timestamp = timestamp;
    }

    public TransactionType getType()          { return type; }
    public UUID getCounterpartId()            { return counterpartId; }
    public UUID getTargetId()                 { return targetId; }
    public BigDecimal getAmount()             { return amount; }
    public BigDecimal getBalanceBefore()      { return balanceBefore; }
    public BigDecimal getBalanceAfter()       { return balanceAfter; }
    public long getTimestamp()                { return timestamp; }
}
