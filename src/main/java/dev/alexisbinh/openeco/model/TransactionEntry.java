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

package dev.alexisbinh.openeco.model;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable record of a single economy event, always stored from the perspective
 * of {@code targetId} (the player whose balance changed).
 *
 * <ul>
 *   <li>For PAY_SENT: {@code counterpartId} = recipient UUID.</li>
 *   <li>For PAY_RECEIVED: {@code counterpartId} = sender UUID.</li>
 *   <li>For GIVE/TAKE/SET/RESET: {@code counterpartId} is null (admin/API action).</li>
 *   <li>{@code source} and {@code note} are optional addon metadata.</li>
 * </ul>
 */
public final class TransactionEntry {

    private final TransactionType type;
    private final UUID counterpartId; // nullable
    private final UUID targetId;
    private final String currencyId;
    private final BigDecimal amount;
    private final BigDecimal balanceBefore;
    private final BigDecimal balanceAfter;
    private final long timestamp;
    private final String source;
    private final String note;

    public TransactionEntry(TransactionType type, UUID counterpartId, UUID targetId,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, long timestamp) {
        this(type, counterpartId, targetId, amount, balanceBefore, balanceAfter, timestamp, null, null, null);
    }

    public TransactionEntry(TransactionType type, UUID counterpartId, UUID targetId,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, long timestamp,
                            @Nullable String currencyId) {
        this(type, counterpartId, targetId, amount, balanceBefore, balanceAfter, timestamp, null, null, currencyId);
    }

    public TransactionEntry(TransactionType type, UUID counterpartId, UUID targetId,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, long timestamp,
                            @Nullable String source, @Nullable String note) {
        this(type, counterpartId, targetId, amount, balanceBefore, balanceAfter, timestamp, source, note, null);
    }

    public TransactionEntry(TransactionType type, UUID counterpartId, UUID targetId,
                            BigDecimal amount, BigDecimal balanceBefore,
                            BigDecimal balanceAfter, long timestamp,
                            @Nullable String source, @Nullable String note,
                            @Nullable String currencyId) {
        this.type = type;
        this.counterpartId = counterpartId;
        this.targetId = targetId;
        this.currencyId = currencyId;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.timestamp = timestamp;
        this.source = source;
        this.note = note;
    }

    public TransactionType getType()          { return type; }
    public UUID getCounterpartId()            { return counterpartId; }
    public UUID getTargetId()                 { return targetId; }
    public String getCurrencyId()             { return currencyId; }
    public BigDecimal getAmount()             { return amount; }
    public BigDecimal getBalanceBefore()      { return balanceBefore; }
    public BigDecimal getBalanceAfter()       { return balanceAfter; }
    public long getTimestamp()                { return timestamp; }
    public String getSource()                 { return source; }
    public String getNote()                   { return note; }
    public boolean hasCurrencyId()            { return currencyId != null; }
    public boolean hasMetadata()              { return source != null || note != null; }
}
