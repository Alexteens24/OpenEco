package dev.alexisbinh.openeco.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired after a /pay transfer has been successfully applied.
 * Not cancellable — both balances have already been updated.
 *
 * <p><strong>Threading note:</strong> This event is dispatched <em>outside</em> all account
 * locks. It is safe to call economy operations on both the sender and recipient from a
 * listener. Use this event — rather than the pre-event {@link PayEvent} — when you need
 * to react to a confirmed transfer without risking re-entrant mutations.
 */
public class PayCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID fromId;
    private final UUID toId;
    private final String currencyId;
    private final BigDecimal sent;
    private final BigDecimal received;
    private final BigDecimal tax;
    private final BigDecimal fromBalanceBefore;
    private final BigDecimal fromBalanceAfter;
    private final BigDecimal toBalanceBefore;
    private final BigDecimal toBalanceAfter;

    public PayCompletedEvent(UUID fromId, UUID toId, BigDecimal sent, BigDecimal received, BigDecimal tax,
                             BigDecimal fromBalanceBefore, BigDecimal fromBalanceAfter,
                             BigDecimal toBalanceBefore, BigDecimal toBalanceAfter) {
        this(fromId, toId, sent, received, tax, fromBalanceBefore, fromBalanceAfter, toBalanceBefore, toBalanceAfter, null);
    }

    public PayCompletedEvent(UUID fromId, UUID toId, BigDecimal sent, BigDecimal received, BigDecimal tax,
                             BigDecimal fromBalanceBefore, BigDecimal fromBalanceAfter,
                             BigDecimal toBalanceBefore, BigDecimal toBalanceAfter,
                             @Nullable String currencyId) {
        this.fromId = fromId;
        this.toId = toId;
        this.currencyId = currencyId;
        this.sent = sent;
        this.received = received;
        this.tax = tax;
        this.fromBalanceBefore = fromBalanceBefore;
        this.fromBalanceAfter = fromBalanceAfter;
        this.toBalanceBefore = toBalanceBefore;
        this.toBalanceAfter = toBalanceAfter;
    }

    public UUID getFromId() { return fromId; }

    public UUID getToId() { return toId; }

    public @Nullable String getCurrencyId() { return currencyId; }

    public boolean hasCurrencyId() { return currencyId != null; }

    public BigDecimal getSent() { return sent; }

    public BigDecimal getReceived() { return received; }

    public BigDecimal getTax() { return tax; }

    public BigDecimal getFromBalanceBefore() { return fromBalanceBefore; }

    public BigDecimal getFromBalanceAfter() { return fromBalanceAfter; }

    public BigDecimal getToBalanceBefore() { return toBalanceBefore; }

    public BigDecimal getToBalanceAfter() { return toBalanceAfter; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}