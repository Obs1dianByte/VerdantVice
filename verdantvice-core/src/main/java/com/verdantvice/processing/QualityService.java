package com.verdantvice.processing;

import com.verdantvice.common.DomainChecks;

public final class QualityService {
    public double calculate(QualityInputs input, QualityWeights weights) {
        DomainChecks.required(input, "input");
        DomainChecks.required(weights, "weights");
        double weighted = input.genetics() * weights.genetics()
                + input.plantCare() * weights.plantCare()
                + input.health() * weights.health()
                + input.harvestTiming() * weights.harvestTiming()
                + input.cleanliness() * weights.cleanliness()
                + input.drying() * weights.drying()
                + input.curing() * weights.curing()
                + input.playerSkill() * weights.playerSkill()
                + input.workerSkill() * weights.workerSkill();
        double score = weighted / weights.positiveWeightTotal()
                - input.contamination() * weights.contaminationPenalty();
        return DomainChecks.clamp(score, 0.0, 100.0);
    }

    public QualityGrade grade(QualityInputs input, QualityWeights weights) {
        return QualityGrade.fromScore(calculate(input, weights));
    }
}
