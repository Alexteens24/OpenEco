/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired after a new economy account is created for a player.
 * Not cancellable — the account has already been added to the in-memory store.
 *
 * <p><strong>Threading note:</strong> This event is dispatched after the account has been
 * committed to the in-memory store and outside openeco's internal locks. Listeners may
 * call other openeco operations synchronously, subject to the normal server-thread rules.
 */
public class AccountCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final BigDecimal startingBalance;

    public AccountCreateEvent(UUID playerId, String playerName, BigDecimal startingBalance) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.startingBalance = startingBalance;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public BigDecimal getStartingBalance() { return startingBalance; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
