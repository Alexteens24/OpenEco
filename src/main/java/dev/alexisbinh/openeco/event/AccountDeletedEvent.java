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
 * Fired after an economy account has been successfully removed.
 * Not cancellable — the account has already been deleted.
 *
 * <p><strong>Threading note:</strong> This event is dispatched <em>outside</em> all account
 * locks. The balance field reflects the final balance at deletion time. Use this event —
 * rather than the pre-event {@link AccountDeleteEvent} — when you need to react to a
 * confirmed deletion without risking re-entrant mutations.
 */
public class AccountDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final BigDecimal finalBalance;

    public AccountDeletedEvent(UUID playerId, String playerName, BigDecimal finalBalance) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.finalBalance = finalBalance;
    }

    public UUID getPlayerId() { return playerId; }

    public String getPlayerName() { return playerName; }

    /** The balance the account held at the time it was deleted. */
    public BigDecimal getFinalBalance() { return finalBalance; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
