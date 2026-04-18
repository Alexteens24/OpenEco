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

public enum TransactionType {
    /** Admin/API gave money to a player. */
    GIVE,
    /** Admin/API took money from a player. */
    TAKE,
    /** Admin/API set a player's balance. */
    SET,
    /** Admin reset a player's balance to starting balance. */
    RESET,
    /** Money was sent by a player (their perspective). */
    PAY_SENT,
    /** Money was received by a player (their perspective). */
    PAY_RECEIVED
}
