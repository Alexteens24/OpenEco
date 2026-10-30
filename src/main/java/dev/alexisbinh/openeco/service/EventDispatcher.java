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

package dev.alexisbinh.openeco.service;

import org.bukkit.event.Event;

import java.util.concurrent.Callable;

@FunctionalInterface
interface EventDispatcher {

    void dispatch(Event event);

    /**
     * Runs an economy operation on the dispatcher thread (the server primary thread in
     * production). Account monitors must only ever be acquired on that thread: an operation
     * that holds a monitor while blocking for the primary thread to run an event would
     * deadlock against the primary thread waiting for the same monitor.
     *
     * <p>The default runs inline, which keeps tests and non-Bukkit callers unchanged.
     */
    default <T> T runOnDispatchThread(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Economy operation failed", error);
        }
    }
}