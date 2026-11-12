package com.kragnoth.mobrules.rule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;

/** Everything conditions and number formulas need about a mob, computed once per evaluation. */
public final class MobContext {
    public final Mob mob;
    public final ServerLevel level;
    public final BlockPos pos;
    public final RandomSource random;
    public final DifficultyInstance difficulty;

    private long day = -1;
    private double distance = -1;

    public MobContext(Mob mob, ServerLevel level) {
        this.mob = mob;
        this.level = level;
        this.pos = mob.blockPosition();
        this.random = mob.getRandom();
        this.difficulty = level.getCurrentDifficultyAt(pos);
    }

    /** In-game day number (0 on the first day). */
    public long day() {
        if (day < 0) day = level.getDayTime() / 24000L;
        return day;
    }

    /** Horizontal distance from the world spawn point, in blocks. */
    public double distanceFromSpawn() {
        if (distance < 0) {
            BlockPos spawn = level.getSharedSpawnPos();
            double dx = pos.getX() - spawn.getX(), dz = pos.getZ() - spawn.getZ();
            distance = Math.sqrt(dx * dx + dz * dz);
        }
        return distance;
    }

    /** Vanilla local difficulty (roughly 0.75 to 6.75). */
    public float localDifficulty() {
        return difficulty.getEffectiveDifficulty();
    }
}
