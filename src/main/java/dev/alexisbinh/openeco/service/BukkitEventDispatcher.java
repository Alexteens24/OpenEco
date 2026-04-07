package dev.alexisbinh.openeco.service;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

final class BukkitEventDispatcher implements EventDispatcher {

    @Override
    public void dispatch(Event event) {
        Bukkit.getPluginManager().callEvent(event);
    }
}