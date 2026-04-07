package dev.alexisbinh.openeco.enhancements.permcap;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.event.PayEvent;
import org.bukkit.Server;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermCapListenerTest {

    @Mock
    private OpenEcoApi api;

    @Mock
    private JavaPlugin plugin;

    @Mock
    private Server server;

    @Mock
    private Player recipient;

    private YamlConfiguration config;
    private PermCapListener listener;
    private UUID recipientId;

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        config.set("perm-cap.enabled", true);
        config.set("perm-cap.tiers", List.of(Map.of(
                "permission", "openeco.cap.vip",
                "cap", 100.0)));

        recipientId = UUID.randomUUID();

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(recipientId)).thenReturn(recipient);
        when(recipient.hasPermission("openeco.enhancements.bypass.permcap")).thenReturn(false);
        when(recipient.hasPermission("openeco.cap.vip")).thenReturn(true);
        when(api.getRules()).thenReturn(new EconomyRulesSnapshot(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, null),
                0,
                BigDecimal.ZERO,
                null,
                0,
                0));

        listener = new PermCapListener(api, plugin);
    }

    @Test
    void payCapCheckUsesRecipientReceivedAmount() {
        when(api.getBalance(recipientId)).thenReturn(new BigDecimal("90.00"));
        PayEvent event = new PayEvent(
                UUID.randomUUID(),
                recipientId,
                new BigDecimal("20.00"),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"));

        listener.onPay(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void payIsCancelledWhenRecipientWouldExceedCap() {
        when(api.getBalance(recipientId)).thenReturn(new BigDecimal("91.00"));
        PayEvent event = new PayEvent(
                UUID.randomUUID(),
                recipientId,
                new BigDecimal("20.00"),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"));

        listener.onPay(event);

        assertTrue(event.isCancelled());
    }
}