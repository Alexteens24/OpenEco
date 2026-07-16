/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.crossserver;

import dev.alexisbinh.openeco.storage.MultiWriterRepository;

import java.util.UUID;

@FunctionalInterface
public interface MultiWriterChangeNotifier {
    void publish(UUID accountId, long version, MultiWriterRepository.ChangeKind kind);
}
