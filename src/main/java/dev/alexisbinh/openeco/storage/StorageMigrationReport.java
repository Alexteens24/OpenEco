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

package dev.alexisbinh.openeco.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StorageMigrationReport {

    private final DatabaseDialect targetDialect;
    private final boolean dryRun;
    private int accountsCopied;
    private int transactionsCopied;
    private final List<String> errors = new ArrayList<>();

    public StorageMigrationReport(DatabaseDialect targetDialect, boolean dryRun) {
        this.targetDialect = targetDialect;
        this.dryRun = dryRun;
    }

    public DatabaseDialect targetDialect() {
        return targetDialect;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public int accountsCopied() {
        return accountsCopied;
    }

    public int transactionsCopied() {
        return transactionsCopied;
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    public void addAccountsCopied(int count) {
        accountsCopied += count;
    }

    public void addTransactionsCopied(int count) {
        transactionsCopied += count;
    }

    public void addError(String message) {
        errors.add(message);
    }
}
