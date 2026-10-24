package com.verdantvice.contract;

import com.verdantvice.config.ConfigManager;
import com.verdantvice.herbalist.HerbalistRecord;
import com.verdantvice.herbalist.HerbalistOfferCatalog;
import com.verdantvice.herbalist.HerbalistTier;
import com.verdantvice.market.PaperMarketService;
import com.verdantvice.processing.QualityGrade;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.security.ItemSigner;
import com.verdantvice.security.SignedItemData;
import com.verdantvice.strain.Strain;
import com.verdantvice.strain.StrainRegistry;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class PaperContractService {
    private final PaperContractRepository repository;
    private final PaperMarketService market;
    private final StrainRegistry strains;
    private final ConfigManager configs;
    private final ItemSigner signer;

    public PaperContractService(
            PaperContractRepository repository,
            PaperMarketService market,
            StrainRegistry strains,
            ConfigManager configs,
            ItemSigner signer
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.market = Objects.requireNonNull(market, "market");
        this.strains = Objects.requireNonNull(strains, "strains");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    public CompletableFuture<StoredContract> generate(
            UUID playerUuid,
            HerbalistRecord herbalist,
            Instant now
    ) {
        if (!configs.get("contracts.yml").getBoolean("enabled", true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("contracts-disabled"));
        }
        var offers = new HerbalistOfferCatalog(strains, configs).available(herbalist.tier());
        List<Strain> available = ContractProgression.availableStrains(strains.all(), offers);
        if (available.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("no-strains"));
        }
        ContractType selectedType = randomType();
        CompletableFuture<Optional<com.verdantvice.processing.ProductBatch>> geneticBatch =
                selectedType == ContractType.GENETIC_DELIVERY
                        ? repository.candidateGeneticBatch(playerUuid)
                        : CompletableFuture.completedFuture(Optional.empty());
        double baseWeight = switch (herbalist.tier()) {
            case NOVICE, APPRENTICE -> 4.0;
            case JOURNEYMAN -> 6.0;
            case EXPERT -> 8.0;
            case MASTER -> 12.0;
            case VERDANT_EXPERT -> 16.0;
        };
        long minimumHours = Math.max(1L,
                configs.get("contracts.yml").getLong("minimum-deadline-hours", 6));
        long maximumHours = Math.max(minimumHours,
                configs.get("contracts.yml").getLong("maximum-deadline-hours", 72));
        return geneticBatch.thenCompose(candidate -> {
            ContractType type = ContractProgression.playableType(selectedType, offers, candidate.isPresent());
            List<ContractRequirement> requirements = type == ContractType.GENETIC_DELIVERY
                    ? List.of(ContractProgression.geneticRequirement(candidate.orElseThrow(), baseWeight))
                    : requirements(type, available, baseWeight, herbalist.tier(), Optional.empty());
            long hours = type == ContractType.EXPRESS_DELIVERY
                    ? minimumHours
                    : ThreadLocalRandom.current().nextLong(
                            minimumHours, maximumHours + 1L);
            List<CompletableFuture<com.verdantvice.market.MarketQuote>> quotes =
                    requirements.stream()
                            .map(requirement -> market.quote(
                                    requirement.strainId().orElseThrow(),
                                    requirement.minimumQuality().minimumScore(),
                                    100.0, 0.0))
                            .toList();
            return CompletableFuture.allOf(
                    quotes.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
                    double quotedValue = 0.0;
                    for (int index = 0; index < requirements.size(); index++) {
                        quotedValue += quotes.get(index).join().unitPrice()
                                * requirements.get(index).requiredWeight();
                    }
                    double multiplier = configs.get("contracts.yml")
                            .getDouble("reward-multiplier", 1.25)
                            * typeRewardMultiplier(type);
                    int maximumReward = Math.max(1,
                            configs.get("economy.yml").getInt("maximum-transaction-price", 4096));
                    int emeralds = (int) Math.max(1, Math.min(maximumReward,
                            Math.floor(quotedValue * multiplier)));
                    double reputation = Math.max(0.0, configs.get("contracts.yml")
                            .getDouble("reputation-per-contract", 5.0));
                    Contract contract = Contract.active(
                            UUID.randomUUID(), playerUuid, herbalist.herbalistUuid(), type,
                            requirements,
                            now.plus(Duration.ofHours(hours)),
                            new ContractReward(emeralds, 0.0, reputation),
                            reputation,
                            now);
                    int maximumActive = Math.max(1, configs.get("contracts.yml")
                            .getInt("maximum-active-per-player", 8));
                    return repository.create(contract, maximumActive);
                });
        });
    }

    public CompletableFuture<List<StoredContract>> list(UUID playerUuid) {
        return repository.list(playerUuid);
    }

    public CompletableFuture<ContractDeliveryResult> deliver(
            UUID contractId,
            ItemStack item,
            UUID playerUuid,
            String operationId
    ) {
        ItemSigner.Verification verification = signer.verify(item);
        if (item.getAmount() != 1
                || !verification.valid()
                || !isDeliverable(verification.data().itemType())
                || verification.data().batchId() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("authentic-only"));
        }
        SignedItemData data = verification.data();
        return repository.deliver(
                contractId, data, signer.payloadHash(data), playerUuid, operationId, Instant.now());
    }

    public CompletableFuture<ContractClaimResult> claim(
            UUID contractId,
            UUID playerUuid,
            String operationId
    ) {
        return repository.claim(contractId, playerUuid, operationId, Instant.now());
    }

    private ContractType randomType() {
        List<String> values = configs.get("contracts.yml").getStringList("types");
        if (values.isEmpty()) return ContractType.STRAIN_DELIVERY;
        String configured = values.get(ThreadLocalRandom.current().nextInt(values.size()))
                .toUpperCase(Locale.ROOT);
        return switch (configured) {
            case "MULTI_STRAIN" -> ContractType.MULTI_STRAIN_DELIVERY;
            case "EARLY_DELIVERY" -> ContractType.EXPRESS_DELIVERY;
            case "BULK_ORDER" -> ContractType.WHOLESALE;
            default -> {
                try {
                    yield ContractType.valueOf(configured);
                } catch (IllegalArgumentException exception) {
                    yield ContractType.STRAIN_DELIVERY;
                }
            }
        };
    }

    private static QualityGrade minimumQuality(HerbalistTier tier, ContractType type) {
        if (type == ContractType.QUALITY_DELIVERY || type == ContractType.CURED_PRODUCT) {
            return tier.ordinal() >= HerbalistTier.MASTER.ordinal()
                    ? QualityGrade.PREMIUM : QualityGrade.GOOD;
        }
        return tier.ordinal() >= HerbalistTier.EXPERT.ordinal()
                ? QualityGrade.GOOD : QualityGrade.COMMON;
    }

    private static List<ContractRequirement> requirements(
            ContractType type,
            List<Strain> available,
            double baseWeight,
            HerbalistTier tier,
            Optional<UUID> geneticOrigin
    ) {
        List<Strain> shuffled = new ArrayList<>(available);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        int count = type == ContractType.MULTI_STRAIN_DELIVERY
                ? Math.min(3, shuffled.size()) : 1;
        double totalWeight = type == ContractType.WHOLESALE
                ? baseWeight * 2.5 : baseWeight;
        double perRequirement = totalWeight / count;
        QualityGrade quality = minimumQuality(tier, type);
        boolean cured = type == ContractType.CURED_PRODUCT;
        List<ContractRequirement> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new ContractRequirement(
                    Optional.of(shuffled.get(index).id()),
                    perRequirement,
                    quality,
                    cured,
                    type == ContractType.GENETIC_DELIVERY
                            ? geneticOrigin : Optional.empty()));
        }
        return List.copyOf(result);
    }

    private static double typeRewardMultiplier(ContractType type) {
        return switch (type) {
            case STRAIN_DELIVERY, QUALITY_DELIVERY -> 1.0;
            case MULTI_STRAIN_DELIVERY -> 1.10;
            case EXPRESS_DELIVERY -> 1.25;
            case CURED_PRODUCT -> 1.20;
            case GENETIC_DELIVERY -> 1.20;
            case WHOLESALE -> 1.08;
        };
    }

    private static boolean isDeliverable(CustomItemType type) {
        return type == CustomItemType.DRY_BATCH
                || type == CustomItemType.CURED_BATCH
                || type == CustomItemType.PACKAGE;
    }
}
