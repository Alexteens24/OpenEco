package dev.alexisbinh.openeco.service;

import org.bukkit.event.Event;

@FunctionalInterface
interface EventDispatcher {

    void dispatch(Event event);
}