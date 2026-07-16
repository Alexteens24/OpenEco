/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

public interface EconomyPolicyRegistry {
    void register(String providerId, EconomyMutationPolicyProvider provider);
    void unregister(String providerId);
}
