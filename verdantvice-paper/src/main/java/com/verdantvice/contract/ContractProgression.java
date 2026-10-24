package com.verdantvice.contract;

import com.verdantvice.herbalist.HerbalistOffer;
import com.verdantvice.processing.ProductBatch;
import com.verdantvice.security.CustomItemType;
import com.verdantvice.strain.Strain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Contract objectives must be obtainable at the issuing merchant's current tier. */
final class ContractProgression {
    private ContractProgression() { }

    static List<Strain> availableStrains(Collection<Strain> strains, List<HerbalistOffer> offers) {
        var available = offers.stream().flatMap(offer -> offer.strainId().stream()).collect(Collectors.toSet());
        return strains.stream().filter(strain -> available.contains(strain.id())).toList();
    }

    static ContractType playableType(ContractType requested, List<HerbalistOffer> offers,
                                     boolean hasGeneticBatch) {
        if (requested == ContractType.GENETIC_DELIVERY && !hasGeneticBatch) return ContractType.STRAIN_DELIVERY;
        if (requested == ContractType.CURED_PRODUCT && offers.stream()
                .noneMatch(offer -> offer.itemType() == CustomItemType.CURING_CONTAINER)) {
            return ContractType.STRAIN_DELIVERY;
        }
        return requested;
    }

    static ContractRequirement geneticRequirement(ProductBatch batch, double requestedWeight) {
        var component = batch.strainComposition().entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
        double available = batch.dryWeight() * component.getValue();
        return new ContractRequirement(Optional.of(component.getKey()), Math.min(requestedWeight, available),
                batch.qualityGrade(), false, batch.geneticOrigin().stream().min(Comparator.naturalOrder()));
    }
}
