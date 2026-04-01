package dev.alexisbinh.simpleeco.service;

import org.bukkit.event.Event;

@FunctionalInterface
interface EventDispatcher {

    void dispatch(Event event);
}