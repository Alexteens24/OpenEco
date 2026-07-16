/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

@FunctionalInterface
public interface EconomyMutationPolicyProvider {
    MutationPolicyDecision evaluate(MutationPolicyContext context);
}
