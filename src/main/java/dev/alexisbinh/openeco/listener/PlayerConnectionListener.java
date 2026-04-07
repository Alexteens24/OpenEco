package dev.alexisbinh.openeco.listener;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Logger;

public class PlayerConnectionListener implements Listener {

    private final AccountService service;
    private final Messages messages;
    private final Logger log;
    private final JavaPlugin plugin;

    public PlayerConnectionListener(AccountService service, Messages messages, Logger log, JavaPlugin plugin) {
        this.service = service;
        this.messages = messages;
        this.log = log;
        this.plugin = plugin;
    }

    /** Cross-server: re-read account from DB before the player finishes connecting. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!service.isCrossServerEnabled()) return;
        service.refreshAccount(event.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();

        if (service.hasAccount(uuid)) {
            service.getAccount(uuid).ifPresent(record -> {
                if (!record.getLastKnownName().equals(name)) {
                    handleRenameFailure(event, uuid, record.getLastKnownName(), name,
                            service.renameAccountDetailed(uuid, name));
                }
            });
        } else {
            handleCreateFailure(event, uuid, name, service.createAccountDetailed(uuid, name));
        }
    }

    private void handleCreateFailure(PlayerJoinEvent event, UUID accountId, String name,
                                     AccountService.CreateAccountStatus status) {
        switch (status) {
            case CREATED, ALREADY_EXISTS -> {
                return;
            }
            case NAME_IN_USE -> {
                log.warning("Could not create economy account for " + name + " (" + accountId
                        + "): account name is already linked to another account.");
                messages.send(event.getPlayer(), "account-name-conflict",
                        Placeholder.unparsed("player", name));
            }
            case INVALID_NAME -> {
                log.warning("Could not create economy account for " + name + " (" + accountId
                        + "): invalid account name.");
                messages.send(event.getPlayer(), "account-sync-failed");
            }
        }
    }

    private void handleRenameFailure(PlayerJoinEvent event, UUID accountId, String oldName, String newName,
                                     AccountService.RenameAccountStatus status) {
        switch (status) {
            case RENAMED, UNCHANGED -> {
                return;
            }
            case NAME_IN_USE -> {
                log.warning("Could not rename economy account for " + oldName + " (" + accountId + ") to '"
                        + newName + "': account name is already linked to another account.");
                messages.send(event.getPlayer(), "account-name-conflict",
                        Placeholder.unparsed("player", newName));
            }
            case INVALID_NAME -> {
                log.warning("Could not rename economy account for " + oldName + " (" + accountId + ") to '"
                        + newName + "': invalid account name.");
                messages.send(event.getPlayer(), "account-sync-failed");
            }
            case CANCELLED -> log.warning("Economy account rename for " + oldName + " (" + accountId
                    + ") to '" + newName + "' was cancelled by another plugin.");
            case NOT_FOUND -> {
                log.warning("Could not rename economy account for " + oldName + " (" + accountId
                        + "): account no longer exists.");
                messages.send(event.getPlayer(), "account-sync-failed");
            }
        }
    }

    /** Cross-server: flush account to DB when the player disconnects (before joining another server). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!service.isCrossServerEnabled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> service.flushAccount(uuid));
    }
}
