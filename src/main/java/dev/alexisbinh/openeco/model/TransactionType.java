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
