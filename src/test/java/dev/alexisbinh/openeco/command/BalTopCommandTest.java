/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.service.LeaderboardView;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalTopCommandTest {

    @Mock private AccountService service;
    @Mock private Messages messages;
    @Mock private CommandSender sender;
    @Mock private Command command;

    private BalTopCommand balTop;

    @BeforeEach
    void setUp() {
        balTop = new BalTopCommand(service, messages);
        when(sender.hasPermission("openeco.command.baltop")).thenReturn(true);
        when(service.getBalTopPageSize()).thenReturn(10);
        when(service.getCurrencyId()).thenReturn("openeco");
        when(service.hasCurrency("openeco")).thenReturn(true);
    }

    @Test
    void validPageUsesOneLeaderboardQuery() {
        when(service.getLeaderboardPage("openeco", 0, 10))
                .thenReturn(new LeaderboardView(25, List.of()));

        balTop.onCommand(sender, command, "baltop", new String[0]);

        verify(service).getLeaderboardPage("openeco", 0, 10);
    }

    @Test
    void outOfRangePageQueriesAgainOnlyForClampedLastPage() {
        when(service.getLeaderboardPage("openeco", 980, 10))
                .thenReturn(new LeaderboardView(25, List.of()));
        when(service.getLeaderboardPage("openeco", 20, 10))
                .thenReturn(new LeaderboardView(25, List.of()));

        balTop.onCommand(sender, command, "baltop", new String[]{"99"});

        verify(service).getLeaderboardPage("openeco", 980, 10);
        verify(service).getLeaderboardPage("openeco", 20, 10);
    }
}
