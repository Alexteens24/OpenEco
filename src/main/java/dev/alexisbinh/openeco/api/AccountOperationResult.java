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