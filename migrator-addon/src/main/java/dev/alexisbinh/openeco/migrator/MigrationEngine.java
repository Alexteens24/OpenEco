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

package dev.alexisbinh.openeco.migrator;

import dev.alexisbinh.openeco.api.AccountOperationResult;
import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.migrator.model.ForeignAccount;
import dev.alexisbinh.openeco.migrator.model.MigrationReport;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import dev.alexisbinh.openeco.migrator.source.EconomySourceReader;
import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import dev.alexisbinh.openeco.migrator.source.MigrationReaders;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class MigrationEngine {

    private final OpenEcoApi api;
    private final MigrationContext context;
    private final String currencyId;

    public MigrationEngine(OpenEcoApi api, MigrationContext context, String currencyId) {
        this.api = api;
        this.context = context;
        this.currencyId = currencyId;
    }

    public Optional<ScanResult> scan(MigrationSource source) throws IOException {
        EconomySourceReader reader = MigrationReaders.get(source).orElseThrow();
        if (!reader.isAvailable(context)) {
            return Optional.empty();
        }
        List<ForeignAccount> accounts = reader.read(context);
        BigDecimal total = accounts.stream()
                .map(ForeignAccount::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new ScanResult(source, reader.describeLocation(context), accounts.size(), total));
    }

    public MigrationReport migrate(MigrationSource source, boolean dryRun, boolean overwrite) throws IOException {
        EconomySourceReader reader = MigrationReaders.get(source).orElseThrow();
        if (!reader.isAvailable(context)) {
            throw new IOException("Source " + source.displayName() + " is not available on this server");
        }

        List<ForeignAccount> accounts = reader.read(context);
        MigrationReport report = new MigrationReport(source, dryRun);
        report.addScanned(accounts.size());

        for (ForeignAccount foreign : accounts) {
            report.addSourceTotal(foreign.balance());
            if (dryRun) {
                continue;
            }
            applyAccount(report, foreign, overwrite);
        }
        return report;
    }

    private void applyAccount(MigrationReport report, ForeignAccount foreign, boolean overwrite) {
        if (foreign.balance().compareTo(BigDecimal.ZERO) < 0) {
            report.incrementFailed();
            report.addError(foreign.name() + ": negative balance " + foreign.balance());
            return;
        }

        boolean existed = api.hasAccount(foreign.id());
        if (existed && !overwrite) {
            report.incrementSkipped();
            return;
        }

        AccountOperationResult ensure = api.ensureAccount(foreign.id(), foreign.name());
        if (ensure.status() == AccountOperationResult.Status.FAILED
                || ensure.status() == AccountOperationResult.Status.NAME_IN_USE) {
            report.incrementFailed();
            report.addError(foreign.name() + ": " + ensure.status() + " - " + ensure.message());
            return;
        }

        BalanceChangeResult set = api.setBalance(foreign.id(), currencyId, foreign.balance());
        if (!set.isSuccess()) {
            report.incrementFailed();
            report.addError(foreign.name() + ": setBalance failed - " + set.status());
            return;
        }

        if (existed) {
            report.incrementUpdated();
        } else {
            report.incrementCreated();
        }
    }

    public record ScanResult(MigrationSource source, String location, int accounts, BigDecimal totalBalance) {
    }
}
