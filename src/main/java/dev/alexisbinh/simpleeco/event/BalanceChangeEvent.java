package dev.alexisbinh.simpleeco.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired before a balance change is applied (give, take, set, reset).
 * Cancelling this event prevents the change from taking effect.
 * Not fired for /pay — use {@link PayEvent} for that.
 */
public class BalanceChangeEvent extends Event implements Cancellable {

    public enum Reason { GIVE, TAKE, SET, RESET }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final BigDecimal oldBalance;
    private final BigDecimal newBalance;
    private final Reason reason;
    private boolean cancelled = false;

    public BalanceChangeEvent(UUID playerId, BigDecimal oldBalance, BigDecimal newBalance, Reason reason) {
        this.playerId = playerId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason;
    }

    /** The player whose balance is changing. */
    public UUID getPlayerId() { return playerId; }

    /** Balance before the change. */
    public BigDecimal getOldBalance() { return oldBalance; }

    /** Balance after the change (if not cancelled). */
    public BigDecimal getNewBalance() { return newBalance; }

    /** What caused this change. */
    public Reason getReason() { return reason; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
