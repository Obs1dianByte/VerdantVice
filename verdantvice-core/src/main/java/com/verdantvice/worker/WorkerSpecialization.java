package com.verdantvice.worker;

public enum WorkerSpecialization {
    GROWER,
    HARVESTER,
    DRYING_MASTER,
    GENETICIST,
    PACKAGER,
    FOREMAN;

    public double bonusFor(WorkerTaskType type) {
        return switch (this) {
            case GROWER -> switch (type) {
                case WATER, PLANT, CLEAN, TREAT_DISEASE, SAVE_DYING_PLANTS -> 1.2;
                default -> 1.0;
            };
            case HARVESTER -> type == WorkerTaskType.HARVEST ? 1.25 : 1.0;
            case DRYING_MASTER -> switch (type) {
                case LOAD_DRYING_RACK, UNLOAD_DRYING_RACK -> 1.25;
                default -> 1.0;
            };
            case GENETICIST -> switch (type) {
                case PLANT, INSPECT -> 1.18;
                default -> 1.0;
            };
            case PACKAGER -> switch (type) {
                case PACKAGE, MOVE_BATCH -> 1.25;
                default -> 1.0;
            };
            case FOREMAN -> 1.08;
        };
    }

    public boolean mayAssignTasks() {
        return this == FOREMAN;
    }
}
