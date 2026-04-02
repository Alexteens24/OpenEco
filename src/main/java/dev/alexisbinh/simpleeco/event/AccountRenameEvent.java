package dev.alexisbinh.simpleeco.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired before an account name change is committed to the in-memory store.
 * Cancelling this event aborts the rename.
 *
 * <p><strong>Threading note:</strong> This event is dispatched while both the
 * persistence lock and the account's own lock are held. Listeners must not call
 * any account lifecycle methods ({@code createAccount}, {@code renameAccount},
 * {@code deleteAccount}) or balance operations on the <em>same</em> account
 * synchronously from the handler thread — doing so will deadlock.
 */
public class AccountRenameEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String oldName;
    private final String newName;
    private boolean cancelled = false;

    public AccountRenameEvent(UUID playerId, String oldName, String newName) {
        this.playerId = playerId;
        this.oldName = oldName;
        this.newName = newName;
    }

    public UUID getPlayerId() { return playerId; }

    public String getOldName() { return oldName; }

    public String getNewName() { return newName; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}