/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Durable network-wide subject state used by mutation policy providers. */
public interface NetworkPolicyStateStore {
    Optional<String> load(String providerId, UUID subjectId);
    CompletionStage<Void> save(String providerId, UUID subjectId, String state);
}
