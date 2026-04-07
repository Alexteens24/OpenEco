package dev.alexisbinh.openeco.enhancements.exchange;

import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class ExchangeCommand implements CommandExecutor, TabCompleter {

    private final OpenEcoApi api;
    private final JavaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ExchangeCommand(OpenEcoApi api, JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm.deserialize("<red>Only players can use /exchange."));
            return true;
        }

        FileConfiguration config = plugin.getConfig();

        if (args.length != 3) {
            String usage = config.getString("exchange.messages.usage",
                    "<red>Usage: /exchange <amount> <from> <to>");
            player.sendMessage(mm.deserialize(usage));
            return true;
        }

        String amountStr = args[0];
        String fromId = args[1];
        String toId = args[2];

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            String msg = config.getString("exchange.messages.invalid-amount", "<red>Invalid amount.");
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            String msg = config.getString("exchange.messages.invalid-amount", "<red>Invalid amount.");
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        if (!api.hasCurrency(fromId)) {
            String msg = config.getString("exchange.messages.unknown-currency",
                    "<red>Unknown currency: <yellow><currency>");
            player.sendMessage(mm.deserialize(msg, Placeholder.unparsed("currency", fromId)));
            return true;
        }

        if (!api.hasCurrency(toId)) {
            String msg = config.getString("exchange.messages.unknown-currency",
                    "<red>Unknown currency: <yellow><currency>");
            player.sendMessage(mm.deserialize(msg, Placeholder.unparsed("currency", toId)));
            return true;
        }

        if (fromId.equals(toId)) {
            String msg = config.getString("exchange.messages.same-currency",
                    "<red>Cannot exchange a currency for itself.");
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        Double rate = findRate(config, fromId, toId);
        if (rate == null) {
            String msg = config.getString("exchange.messages.no-rate",
                    "<red>No exchange rate configured for that currency pair.");
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        CurrencyInfo fromCurrency = api.getCurrencyInfo(fromId);
        CurrencyInfo toCurrency = api.getCurrencyInfo(toId);

        BigDecimal scaledAmount = amount.setScale(fromCurrency.fractionalDigits(), RoundingMode.HALF_UP);

        double feePercent = config.getDouble("exchange.fee-percent", 0.0);
        BigDecimal multiplier = BigDecimal.valueOf(rate)
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(feePercent / 100.0)));
        BigDecimal toAmount = scaledAmount.multiply(multiplier)
                .setScale(toCurrency.fractionalDigits(), RoundingMode.HALF_DOWN);

        if (toAmount.compareTo(BigDecimal.ZERO) <= 0) {
            String msg = config.getString("exchange.messages.invalid-amount", "<red>Invalid amount.");
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        // Precheck both sides without mutating
        BalanceCheckResult withdrawCheck = api.canWithdraw(player.getUniqueId(), fromId, scaledAmount);
        if (!withdrawCheck.isAllowed()) {
            String msg = switch (withdrawCheck.status()) {
                case INSUFFICIENT_FUNDS -> config.getString("exchange.messages.insufficient-funds",
                        "<red>Insufficient funds.");
                case FROZEN -> config.getString("exchange.messages.frozen",
                        "<red>Your account is frozen.");
                default -> config.getString("exchange.messages.failed", "<red>Exchange failed.");
            };
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        BalanceCheckResult depositCheck = api.canDeposit(player.getUniqueId(), toId, toAmount);
        if (!depositCheck.isAllowed()) {
            String msg = switch (depositCheck.status()) {
                case BALANCE_LIMIT -> config.getString("exchange.messages.balance-limit",
                        "<red>Exchange would exceed your balance limit.");
                case FROZEN -> config.getString("exchange.messages.frozen",
                        "<red>Your account is frozen.");
                default -> config.getString("exchange.messages.failed", "<red>Exchange failed.");
            };
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        // Execute: withdraw first, then deposit. Rollback deposit-side amount if deposit fails.
        BalanceChangeResult withdrawResult = api.withdraw(player.getUniqueId(), fromId, scaledAmount);
        if (!withdrawResult.isSuccess()) {
            String msg = switch (withdrawResult.status()) {
                case INSUFFICIENT_FUNDS -> config.getString("exchange.messages.insufficient-funds",
                        "<red>Insufficient funds.");
                case FROZEN -> config.getString("exchange.messages.frozen",
                        "<red>Your account is frozen.");
                default -> config.getString("exchange.messages.failed", "<red>Exchange failed.");
            };
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        BalanceChangeResult depositResult = api.deposit(player.getUniqueId(), toId, toAmount);
        if (!depositResult.isSuccess()) {
            // Rollback the withdraw
            BalanceChangeResult rollbackResult = api.deposit(player.getUniqueId(), fromId, scaledAmount);
            if (!rollbackResult.isSuccess()) {
            plugin.getLogger().severe("Exchange rollback failed for player " + player.getUniqueId()
                + " (" + player.getName() + "): withdrew " + scaledAmount.toPlainString() + " " + fromId
                + ", target deposit failed with status " + depositResult.status()
                + ", rollback failed with status " + rollbackResult.status() + ".");
            String msg = config.getString("exchange.messages.rollback-failed",
                "<red>Exchange failed and automatic rollback could not be completed. Contact an administrator.");
            player.sendMessage(mm.deserialize(msg));
            return true;
            }
            String msg = switch (depositResult.status()) {
                case BALANCE_LIMIT -> config.getString("exchange.messages.balance-limit",
                        "<red>Exchange would exceed your balance limit.");
                case FROZEN -> config.getString("exchange.messages.frozen",
                        "<red>Your account is frozen.");
                default -> config.getString("exchange.messages.failed", "<red>Exchange failed.");
            };
            player.sendMessage(mm.deserialize(msg));
            return true;
        }

        String successMsg = config.getString("exchange.messages.success",
                "<green>Exchanged <yellow><from_amount> <from_currency></yellow> for <yellow><to_amount> <to_currency></yellow>.");
        player.sendMessage(mm.deserialize(successMsg,
                Placeholder.unparsed("from_amount", api.format(scaledAmount, fromId)),
                Placeholder.unparsed("from_currency", fromCurrency.pluralName()),
                Placeholder.unparsed("to_amount", api.format(toAmount, toId)),
                Placeholder.unparsed("to_currency", toCurrency.pluralName())));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player)) return List.of();
        if (!plugin.getConfig().getBoolean("exchange.enabled", false)) return List.of();

        if (args.length == 1) {
            return List.of();
        }

        List<String> currencies = new ArrayList<>(api.getCurrencies()
                .stream().map(dev.alexisbinh.openeco.api.CurrencyInfo::id).toList());

        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return currencies.stream().filter(c -> c.toLowerCase().startsWith(prefix)).toList();
        }

        if (args.length == 3) {
            String fromId = args[1];
            String prefix = args[2].toLowerCase();
            FileConfiguration config = plugin.getConfig();
            List<?> rates = config.getMapList("exchange.rates");
            return rates.stream()
                    .filter(r -> r instanceof java.util.Map<?, ?> m
                            && fromId.equals(m.get("from")))
                    .map(r -> (String) ((java.util.Map<?, ?>) r).get("to"))
                    .filter(to -> to != null && to.toLowerCase().startsWith(prefix))
                    .toList();
        }

        return List.of();
    }

    static Double findRate(FileConfiguration config, String fromId, String toId) {
        List<?> rates = config.getMapList("exchange.rates");
        for (Object entry : rates) {
            if (!(entry instanceof java.util.Map<?, ?> map)) continue;
            if (fromId.equals(map.get("from")) && toId.equals(map.get("to"))) {
                Object rateVal = map.get("rate");
                if (rateVal instanceof Number n) return n.doubleValue();
            }
        }
        return null;
    }
}
