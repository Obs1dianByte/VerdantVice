package com.verdantvice.worker;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Minecraft-day schedule expressed in vanilla world ticks.
 */
public record WorkerSchedule(
        int wakeUp,
        int workStart,
        int breakStart,
        int breakEnd,
        int workEnd,
        int sleep
) {
    private static final int DAY_TICKS = 24_000;

    public WorkerSchedule {
        wakeUp = tick(wakeUp, "wakeUp");
        workStart = tick(workStart, "workStart");
        breakStart = tick(breakStart, "breakStart");
        breakEnd = tick(breakEnd, "breakEnd");
        workEnd = tick(workEnd, "workEnd");
        sleep = tick(sleep, "sleep");
        if (!(wakeUp <= workStart
                && workStart <= breakStart
                && breakStart <= breakEnd
                && breakEnd <= workEnd
                && workEnd <= sleep)) {
            throw new IllegalArgumentException("Worker schedule times must be ordered");
        }
    }

    public static WorkerSchedule from(YamlConfiguration configuration) {
        return new WorkerSchedule(
                configuration.getInt("schedule.wake-up", 0),
                configuration.getInt("schedule.work-start", 1_000),
                configuration.getInt("schedule.break-start", 6_000),
                configuration.getInt("schedule.break-end", 7_000),
                configuration.getInt("schedule.work-end", 12_000),
                configuration.getInt("schedule.sleep", 16_000)
        );
    }

    public Phase phase(long worldTime) {
        int current = Math.floorMod(worldTime, DAY_TICKS);
        if (current < wakeUp || current >= sleep) {
            return Phase.SLEEP;
        }
        if (current < workStart) {
            return Phase.OFF_DUTY;
        }
        if (current < breakStart) {
            return Phase.WORK;
        }
        if (current < breakEnd) {
            return Phase.BREAK;
        }
        if (current < workEnd) {
            return Phase.WORK;
        }
        return Phase.OFF_DUTY;
    }

    public boolean isNightWork(long worldTime) {
        return phase(worldTime) == Phase.SLEEP;
    }

    private static int tick(int value, String name) {
        if (value < 0 || value >= DAY_TICKS) {
            throw new IllegalArgumentException(name + " must be between 0 and 23999");
        }
        return value;
    }

    public enum Phase {
        WORK,
        BREAK,
        OFF_DUTY,
        SLEEP
    }
}
