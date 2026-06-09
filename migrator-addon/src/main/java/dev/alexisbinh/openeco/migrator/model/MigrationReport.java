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

package dev.alexisbinh.openeco.migrator.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class MigrationReport {

    private final MigrationSource source;
    private final boolean dryRun;
    private int scanned;
    private int created;
    private int updated;
    private int skipped;
    private int failed;
    private BigDecimal sourceTotal = BigDecimal.ZERO;
    private final List<String> errors = new ArrayList<>();

    public MigrationReport(MigrationSource source, boolean dryRun) {
        this.source = source;
        this.dryRun = dryRun;
    }

    public MigrationSource source() {
        return source;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public int scanned() {
        return scanned;
    }

    public void addScanned(int count) {
        scanned += count;
    }

    public int created() {
        return created;
    }

    public void incrementCreated() {
        created++;
    }

    public int updated() {
        return updated;
    }

    public void incrementUpdated() {
        updated++;
    }

    public int skipped() {
        return skipped;
    }

    public void incrementSkipped() {
        skipped++;
    }

    public int failed() {
        return failed;
    }

    public void incrementFailed() {
        failed++;
    }

    public BigDecimal sourceTotal() {
        return sourceTotal;
    }

    public void addSourceTotal(BigDecimal amount) {
        if (amount != null) {
            sourceTotal = sourceTotal.add(amount);
        }
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    public void addError(String message) {
        if (errors.size() < 25) {
            errors.add(message);
        }
    }

    public int migratedOrWouldMigrate() {
        return created + updated;
    }
}
