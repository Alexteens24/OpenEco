package dev.alexisbinh.openeco.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(
    id = "openeco-proxy",
    name = "OpenEco Proxy",
    version = "@version@",
    description = "Cross-server balance sync helper for OpenEco — place on your Velocity proxy.",
    authors = {"alexisbinh"}
)
public class OpenEcoProxyPlugin {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public OpenEcoProxyPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(PlayerServerSwitchListener.CHANNEL);

        FlushAckTracker tracker = new FlushAckTracker();
        proxy.getEventManager().register(this, new PlayerServerSwitchListener(tracker, logger));

        CommandMeta meta = proxy.getCommandManager()
                .metaBuilder("ecosync")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, new EcoSyncCommand(proxy, tracker, logger));

        logger.info("OpenEco cross-server proxy plugin enabled.");
        logger.info("Channel registered: openeco:sync | Command registered: /ecosync");
    }
}
