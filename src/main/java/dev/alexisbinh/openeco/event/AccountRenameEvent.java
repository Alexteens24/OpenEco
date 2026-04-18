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

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired before an account name change is committed to the in-memory store.
 * Cancelling this event aborts the rename.
 *
 * <p><strong>Threading note:</strong> This event is dispatched outside openeco's
 * internal locks. The name fields describe the rename attempt that was evaluated at
 * dispatch time. If another operation races before the rename is committed, the final
 * stored name may differ; the post-mutation account snapshot is authoritative.
 */
public class AccountRenameEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String oldName;
    private final String newName;
    private boolean cancelled = false;

    public AccountRenameEvent(UUID playerId, String oldName, String newName) {
        this.playerId = playerId;
        this.oldName = oldName;
        this.newName = newName;
    }

    public UUID getPlayerId() { return playerId; }

    public String getOldName() { return oldName; }

    public String getNewName() { return newName; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}