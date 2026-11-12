package com.kragnoth.mobrules;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/** Melee attack for mobs that have no attack_damage attribute (cows, sheep...): hits for a fixed amount. */
final class FixedDamageMeleeGoal extends MeleeAttackGoal {
    private final float damage;

    FixedDamageMeleeGoal(PathfinderMob mob, double speed, float damage) {
        super(mob, speed, false);
        this.damage = damage;
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (canPerformAttack(target)) {
            resetAttackCooldown();
            mob.swing(InteractionHand.MAIN_HAND);
            target.hurt(mob.damageSources().mobAttack(mob), damage);
        }
    }
}
