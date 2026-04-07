package dev.alexisbinh.openeco.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired after a balance mutation has been successfully applied (give, take, set, reset).
 * Not cancellable — the balance has already been updated.
 *
 * <p><strong>Threading note:</strong> This event is dispatched <em>outside</em> all account
 * locks. It is safe to call economy operations (including on the same account) from a
 * listener. Use this event — rather than the pre-event {@link BalanceChangeEvent} — when
 * you need to react to a confirmed change without risking re-entrant mutations.
 */
public class BalanceChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final BigDecimal oldBalance;
    private final BigDecimal newBalance;
    private final String currencyId;
    private final BalanceChangeEvent.Reason reason;

    public BalanceChangedEvent(UUID playerId, BigDecimal oldBalance, BigDecimal newBalance,
                               BalanceChangeEvent.Reason reason) {
        this(playerId, oldBalance, newBalance, reason, null);
    }

    public BalanceChangedEvent(UUID playerId, BigDecimal oldBalance, BigDecimal newBalance,
                               BalanceChangeEvent.Reason reason, @Nullable String currencyId) {
        this.playerId = playerId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason;
        this.currencyId = currencyId;
    }

    public UUID getPlayerId() { return playerId; }

    public BigDecimal getOldBalance() { return oldBalance; }

    public BigDecimal getNewBalance() { return newBalance; }

    public @Nullable String getCurrencyId() { return currencyId; }

    public boolean hasCurrencyId() { return currencyId != null; }

    public BalanceChangeEvent.Reason getReason() { return reason; }

    /** Signed change applied to the balance. */
    public BigDecimal getDelta() { return newBalance.subtract(oldBalance); }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}