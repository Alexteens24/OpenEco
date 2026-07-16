/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.crossserver;

import dev.alexisbinh.openeco.storage.MultiWriterRepository;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.UUID;
import java.util.logging.Logger;

/** Optional best-effort accelerator; durable JDBC polling remains authoritative. */
public final class RedisChangeBus implements MultiWriterChangeNotifier, AutoCloseable {
    private final UUID nodeId = UUID.randomUUID();
    private final String channel;
    private final Logger log;
    private final Runnable invalidationCallback;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriber;

    public RedisChangeBus(String uri, String channel, Logger log, Runnable invalidationCallback) {
        if (uri == null || (!uri.startsWith("redis://") && !uri.startsWith("rediss://"))) {
            throw new IllegalArgumentException("cross-server.redis.uri must start with redis:// or rediss://");
        }
        this.channel = channel == null || channel.isBlank() ? "openeco:changes" : channel.trim();
        this.log = log;
        this.invalidationCallback = invalidationCallback;
        client = RedisClient.create(uri.trim());
        publisher = client.connect();
        subscriber = client.connectPubSub();
        subscriber.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String incomingChannel, String message) {
                if (!RedisChangeBus.this.channel.equals(incomingChannel)) return;
                String[] parts = message.split("\\|", 5);
                if (parts.length != 5 || !"v1".equals(parts[0]) || nodeId.toString().equals(parts[1])) return;
                invalidationCallback.run();
            }
        });
        subscriber.sync().subscribe(this.channel);
    }

    @Override
    public void publish(UUID accountId, long version, MultiWriterRepository.ChangeKind kind) {
        String payload = "v1|" + nodeId + "|" + accountId + "|" + version + "|" + kind.name();
        publisher.async().publish(channel, payload).exceptionally(error -> {
            log.fine("Redis invalidation publish failed; JDBC polling will recover: " + error.getMessage());
            return null;
        });
    }

    @Override
    public void close() {
        try { subscriber.close(); } catch (RuntimeException ignored) { }
        try { publisher.close(); } catch (RuntimeException ignored) { }
        client.shutdown();
    }
}
