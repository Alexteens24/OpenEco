package dev.alexisbinh.openeco.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class CurrencyRegistry {

    private final String defaultCurrencyId;
    private final Map<String, CurrencyDefinition> definitionsByNormalizedId;
    private final List<CurrencyDefinition> definitionsInOrder;

    private CurrencyRegistry(String defaultCurrencyId,
                             Map<String, CurrencyDefinition> definitionsByNormalizedId,
                             List<CurrencyDefinition> definitionsInOrder) {
        this.defaultCurrencyId = defaultCurrencyId;
        this.definitionsByNormalizedId = definitionsByNormalizedId;
        this.definitionsInOrder = definitionsInOrder;
    }

    static CurrencyRegistry of(String defaultCurrencyId, Collection<CurrencyDefinition> definitions) {
        Objects.requireNonNull(defaultCurrencyId, "defaultCurrencyId");
        Objects.requireNonNull(definitions, "definitions");

        LinkedHashMap<String, CurrencyDefinition> byNormalizedId = new LinkedHashMap<>();
        for (CurrencyDefinition definition : definitions) {
            String normalizedId = normalize(definition.id());
            CurrencyDefinition existing = byNormalizedId.putIfAbsent(normalizedId, definition);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate currency id detected: '" + definition.id() + "' conflicts with '"
                                + existing.id() + "'");
            }
        }

        if (byNormalizedId.isEmpty()) {
            throw new IllegalArgumentException("At least one currency definition is required");
        }

        CurrencyDefinition defaultDefinition = byNormalizedId.get(normalize(defaultCurrencyId));
        if (defaultDefinition == null) {
            throw new IllegalArgumentException(
                    "Configured default currency '" + defaultCurrencyId + "' is missing from the currency definitions");
        }

        return new CurrencyRegistry(
                defaultDefinition.id(),
                Map.copyOf(byNormalizedId),
                List.copyOf(byNormalizedId.values()));
    }

    String defaultCurrencyId() {
        return defaultCurrencyId;
    }

    CurrencyDefinition defaultCurrency() {
        return get(defaultCurrencyId);
    }

    boolean has(String currencyId) {
        return find(currencyId).isPresent();
    }

    Optional<CurrencyDefinition> find(String currencyId) {
        if (currencyId == null) {
            return Optional.empty();
        }

        String trimmed = currencyId.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(definitionsByNormalizedId.get(normalize(trimmed)));
    }

    CurrencyDefinition get(String currencyId) {
        return find(currencyId).orElseThrow(() ->
                new IllegalArgumentException("Unknown currency id: " + currencyId));
    }

    Collection<CurrencyDefinition> all() {
        return definitionsInOrder;
    }

    Set<String> ids() {
        return definitionsInOrder.stream()
                .map(CurrencyDefinition::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    int size() {
        return definitionsInOrder.size();
    }

    private static String normalize(String currencyId) {
        return currencyId.trim().toLowerCase(Locale.ROOT);
    }
}