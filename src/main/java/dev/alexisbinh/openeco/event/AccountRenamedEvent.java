package dev.alexisbinh.openeco.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired after an account name change has been successfully committed.
 * Not cancellable — the rename has already been applied.
 *
 * <p><strong>Threading note:</strong> This event is dispatched <em>outside</em> all account
 * locks. It is safe to call economy operations on the renamed account from a listener. Use
 * this event — rather than the pre-event {@link AccountRenameEvent} — when you need to react
 * to a confirmed rename without risking re-entrant mutations.
 */
public class AccountRenamedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String oldName;
    private final String newName;

    public AccountRenamedEvent(UUID playerId, String oldName, String newName) {
        this.playerId = playerId;
        this.oldName = oldName;
        this.newName = newName;
    }

    public UUID getPlayerId() { return playerId; }

    public String getOldName() { return oldName; }

    public String getNewName() { return newName; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
