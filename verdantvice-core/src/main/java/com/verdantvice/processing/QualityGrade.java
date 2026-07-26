package com.verdantvice.processing;

public enum QualityGrade {
    SPOILED(0.0),
    VERY_POOR(10.0),
    STREET(25.0),
    COMMON(40.0),
    GOOD(55.0),
    PREMIUM(70.0),
    TOP_SHELF(82.0),
    PERFECT_BATCH(92.0),
    VERDANT_GRADE(98.0);

    private final double minimumScore;

    QualityGrade(double minimumScore) {
        this.minimumScore = minimumScore;
    }

    public double minimumScore() {
        return minimumScore;
    }

    public static QualityGrade fromScore(double score) {
        QualityGrade result = SPOILED;
        for (QualityGrade grade : values()) {
            if (score >= grade.minimumScore) result = grade;
        }
        return result;
    }

    public boolean atLeast(QualityGrade other) {
        return ordinal() >= other.ordinal();
    }
}
