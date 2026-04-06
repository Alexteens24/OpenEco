package dev.alexisbinh.simpleeco.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired before a balance change is applied (give, take, set, reset).
 * Cancelling this event prevents the change from taking effect.
 * Not fired for /pay — use {@link PayEvent} for that.
 *
 * <p><strong>Threading note:</strong> This event is dispatched outside SimpleEco's
 * internal locks. The old and new balance fields describe the proposed change at dispatch
 * time. If another operation races before the mutation is committed, the actual applied
 * balances may differ; {@link BalanceChangedEvent} is the authoritative post-commit state.
 */
public class BalanceChangeEvent extends Event implements Cancellable {

    public enum Reason { GIVE, TAKE, SET, RESET, PAY_SENT, PAY_RECEIVED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final BigDecimal oldBalance;
    private final BigDecimal newBalance;
    private final String currencyId;
    private final Reason reason;
    private boolean cancelled = false;

    public BalanceChangeEvent(UUID playerId, BigDecimal oldBalance, BigDecimal newBalance, Reason reason) {
        this(playerId, oldBalance, newBalance, reason, null);
    }

    public BalanceChangeEvent(UUID playerId, BigDecimal oldBalance, BigDecimal newBalance, Reason reason,
                              @Nullable String currencyId) {
        this.playerId = playerId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason;
        this.currencyId = currencyId;
    }

    /** The player whose balance is changing. */
    public UUID getPlayerId() { return playerId; }

    /** Balance before the change. */
    public BigDecimal getOldBalance() { return oldBalance; }

    /** Balance after the change (if not cancelled). */
    public BigDecimal getNewBalance() { return newBalance; }

    /** What caused this change. */
    public Reason getReason() { return reason; }

    /** Currency affected by the change, or null for legacy callers that did not supply one. */
    public @Nullable String getCurrencyId() { return currencyId; }

    public boolean hasCurrencyId() { return currencyId != null; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
