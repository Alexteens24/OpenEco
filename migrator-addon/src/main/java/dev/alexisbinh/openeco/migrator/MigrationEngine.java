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
import dev.alexisbinh.openeco.service.AmountBounds;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class MigrationEngine {

    /** Mirrors AccountRecord.MAX_NAME_LENGTH, the column limit for account names. */
    private static final int MAX_NAME_LENGTH = 16;

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
                // A preview still has to run the checks, otherwise --dry-run reports zero for
                // every source and tells the operator nothing about whether the run would work.
                evaluateAccount(report, foreign, overwrite);
                continue;
            }
            applyAccount(report, foreign, overwrite);
        }
        return report;
    }

    private void applyAccount(MigrationReport report, ForeignAccount foreign, boolean overwrite) {
        // One account must never end the whole migration. Foreign sources are outside our
        // control: a name longer than the 16 character limit, or a balance too large for the
        // column, throws out of the API and previously aborted every remaining account.
        try {
            applyAccountUnguarded(report, foreign, overwrite);
        } catch (RuntimeException error) {
            report.incrementFailed();
            report.addError(foreign.name() + ": " + describe(error));
        }
    }

    /**
     * Dry run: perform every check and count exactly what a real run would do, but change
     * nothing.
     */
    private void evaluateAccount(MigrationReport report, ForeignAccount foreign, boolean overwrite) {
        if (foreign.balance().compareTo(BigDecimal.ZERO) < 0) {
            report.incrementFailed();
            report.addError(foreign.name() + ": negative balance " + foreign.balance());
            return;
        }

        String name = clampName(foreign.name());
        if (!name.equals(foreign.name())) {
            report.addError(foreign.name() + ": name is longer than " + MAX_NAME_LENGTH
                    + " characters and would be truncated to '" + name + "'");
        }

        try {
            if (!AmountBounds.fitsColumn(foreign.balance())) {
                report.incrementFailed();
                report.addError(foreign.name() + ": balance " + foreign.balance()
                        + " does not fit the balance column and could not be stored");
                return;
            }
        } catch (RuntimeException error) {
            report.incrementFailed();
            report.addError(foreign.name() + ": " + describe(error));
            return;
        }

        if (api.hasAccount(foreign.id()) && !overwrite) {
            report.incrementSkipped();
        } else {
            report.incrementCreated();
        }
    }

    /**
     * Trims a foreign name to what OpenEco can store.
     *
     * <p>Truncation happens on code points, not chars, so a name ending in an emoji cannot be
     * cut in half and produce an unpaired surrogate that renders as a replacement character.
     * Four readers in this addon did their own {@code substring(0, 16)}, which is what made a
     * long foreign name reach the API at all.
     */
    private static String clampName(String name) {
        if (name == null || name.isEmpty()) {
            return "Unknown";
        }
        int codePoints = name.codePointCount(0, name.length());
        if (codePoints <= MAX_NAME_LENGTH) {
            return name;
        }
        int endIndex = name.offsetByCodePoints(0, MAX_NAME_LENGTH);
        return name.substring(0, endIndex);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void applyAccountUnguarded(MigrationReport report, ForeignAccount foreign, boolean overwrite) {
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

        AccountOperationResult ensure = api.ensureAccount(foreign.id(), clampName(foreign.name()));
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
