package dev.alexisbinh.simpleeco.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired before a /pay transaction is processed.
 * Cancelling this event aborts the transfer entirely.
 *
 * <p><strong>Threading note:</strong> This event is dispatched outside SimpleEco's internal
 * locks. The transfer is revalidated when it is committed, so other concurrent operations can
 * still make the pay fail after this event if balances, cooldown, or limits change first.
 */
public class PayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID fromId;
    private final UUID toId;
    private final BigDecimal amount;
    private final BigDecimal tax;
    private final BigDecimal received;
    private boolean cancelled = false;

    public PayEvent(UUID fromId, UUID toId, BigDecimal amount, BigDecimal tax, BigDecimal received) {
        this.fromId = fromId;
        this.toId = toId;
        this.amount = amount;
        this.tax = tax;
        this.received = received;
    }

    /** The player sending money. */
    public UUID getFromId() { return fromId; }

    /** The player receiving money. */
    public UUID getToId() { return toId; }

    /** Total amount deducted from sender. */
    public BigDecimal getAmount() { return amount; }

    /** Tax component (0 if disabled). */
    public BigDecimal getTax() { return tax; }

    /** Amount credited to receiver (amount - tax). */
    public BigDecimal getReceived() { return received; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
