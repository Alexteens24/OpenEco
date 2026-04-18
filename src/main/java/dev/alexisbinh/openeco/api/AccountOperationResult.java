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

package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

public record AccountOperationResult(Status status, @Nullable AccountSnapshot account, String message) {

    public enum Status {
        CREATED,
        RENAMED,
        DELETED,
        ALREADY_EXISTS,
        NAME_IN_USE,
        NOT_FOUND,
        UNCHANGED,
        FAILED
    }

    public boolean isSuccess() {
        return status == Status.CREATED || status == Status.RENAMED || status == Status.DELETED;
    }

    public static AccountOperationResult created(AccountSnapshot account) {
        return new AccountOperationResult(Status.CREATED, account, "");
    }

    public static AccountOperationResult renamed(AccountSnapshot account) {
        return new AccountOperationResult(Status.RENAMED, account, "");
    }

    public static AccountOperationResult deleted(AccountSnapshot account) {
        return new AccountOperationResult(Status.DELETED, account, "");
    }

    public static AccountOperationResult alreadyExists(AccountSnapshot account) {
        return new AccountOperationResult(Status.ALREADY_EXISTS, account, "Account already exists");
    }

    public static AccountOperationResult nameInUse(@Nullable AccountSnapshot account) {
        return new AccountOperationResult(Status.NAME_IN_USE, account, "Account name is already in use");
    }

    public static AccountOperationResult notFound() {
        return new AccountOperationResult(Status.NOT_FOUND, null, "Account not found");
    }

    public static AccountOperationResult unchanged(AccountSnapshot account) {
        return new AccountOperationResult(Status.UNCHANGED, account, "Account name is unchanged");
    }

    public static AccountOperationResult failed(@Nullable AccountSnapshot account, String message) {
        return new AccountOperationResult(Status.FAILED, account, message);
    }
}