package dev.alexisbinh.openeco.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired before an economy account is removed from the in-memory store.
 * Cancelling this event aborts the deletion.
 *
 * <p><strong>Threading note:</strong> This event is dispatched outside OpenEco's
 * internal locks. The name and balance fields are a snapshot of the delete attempt at
 * dispatch time. If another operation changes the account before deletion is committed,
 * the final stored state may differ.
 */
public class AccountDeleteEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final BigDecimal balance;
    private boolean cancelled = false;

    public AccountDeleteEvent(UUID playerId, String playerName, BigDecimal balance) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.balance = balance;
    }

    public UUID getPlayerId() { return playerId; }

    public String getPlayerName() { return playerName; }

    public BigDecimal getBalance() { return balance; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}