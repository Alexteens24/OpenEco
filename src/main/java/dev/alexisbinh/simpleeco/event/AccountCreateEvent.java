package dev.alexisbinh.simpleeco.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired after a new economy account is created for a player.
 * Not cancellable — the account has already been added to the in-memory store.
 */
public class AccountCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final BigDecimal startingBalance;

    public AccountCreateEvent(UUID playerId, String playerName, BigDecimal startingBalance) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.startingBalance = startingBalance;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public BigDecimal getStartingBalance() { return startingBalance; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
