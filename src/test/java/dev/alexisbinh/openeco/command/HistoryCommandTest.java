package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryCommandTest {

    @Mock
    private AccountService service;

    @Mock
    private JavaPlugin plugin;

    @Mock
    private Server server;

    @Mock
    private AsyncScheduler asyncScheduler;

    @Mock
    private EntityScheduler entityScheduler;

    @Mock
    private Player player;

    @Mock
    private Command command;

    private HistoryCommand historyCommand;

    @BeforeEach
    void setUp() throws Exception {
        YamlConfiguration messagesConfig = new YamlConfiguration();
        messagesConfig.set("messages.history-header", "<player> <page>/<total>");
        messagesConfig.set("messages.history-empty", "empty");
        messagesConfig.set("messages.no-permission", "no-permission");
        messagesConfig.set("messages.console-player-only", "console-only");
        messagesConfig.set("messages.account-not-found", "missing <player>");
        messagesConfig.set("messages.unknown-currency", "unknown-currency");
        messagesConfig.set("messages.history-error", "history-error");

        historyCommand = new HistoryCommand(service, plugin, new Messages(messagesConfig));

        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("history-command-test"));
        lenient().when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        lenient().when(player.getScheduler()).thenReturn(entityScheduler);
        lenient().when(player.hasPermission("openeco.command.history")).thenReturn(true);
        lenient().when(player.hasPermission("openeco.command.history.others")).thenReturn(true);
        lenient().when(service.getCurrencyId()).thenReturn("coins");
        lenient().when(service.getUUIDNameMap()).thenReturn(Map.of());
        lenient().when(service.getTransactions(any(UUID.class), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(service.countTransactions(any(UUID.class), anyString())).thenReturn(0);

        lenient().doAnswer(invocation -> {
            invocation.<Consumer<ScheduledTask>>getArgument(1).accept(null);
            return null;
        }).when(asyncScheduler).runNow(eq(plugin), any());

        lenient().doAnswer(invocation -> {
            invocation.<Consumer<ScheduledTask>>getArgument(1).accept(null);
            return null;
        }).when(entityScheduler).run(eq(plugin), any(), any());
    }

    @Test
    void prefersExistingPlayerNameOverPageShortcut() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(service.hasCurrency("coins")).thenReturn(true);
        when(service.findByName("2")).thenReturn(Optional.of(new AccountRecord(targetId, "2", BigDecimal.ZERO, 1L, 1L)));

        historyCommand.onCommand(player, command, "history", new String[]{"2"});

        verify(service).countTransactions(targetId, "coins");
        verify(service).getTransactions(targetId, "coins", 1, 10);
        verify(service, never()).hasCurrency("2");
    }

    @Test
    void selfKeywordDisambiguatesOwnCurrencyHistory() throws Exception {
        UUID selfId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(selfId);
        when(player.getName()).thenReturn("Caller");
        when(service.hasCurrency("gems")).thenReturn(true);

        historyCommand.onCommand(player, command, "history", new String[]{"self", "gems"});

        verify(service).countTransactions(selfId, "gems");
        verify(service).getTransactions(selfId, "gems", 1, 10);
        verify(service, never()).findByName("gems");
    }
}