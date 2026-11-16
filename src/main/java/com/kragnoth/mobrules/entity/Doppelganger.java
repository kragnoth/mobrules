package com.kragnoth.mobrules.entity;

import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A copy of a player: drawn with that player's skin, wearing copies of their equipped gear (which it never drops).
 * Spawn one with {@code /mobrules doppelganger <player> [pos] [tag]}; rules can then match it by type or tag.
 */
public class Doppelganger extends Monster implements RangedAttackMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(Doppelganger.class, EntityDataSerializers.OPTIONAL_UUID);
    private final RangedBowAttackGoal<Doppelganger> bowGoal = new RangedBowAttackGoal<>(this, 1.0, 20, 15.0F);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.2, false);
    private boolean healed;

    public Doppelganger(EntityType<? extends Doppelganger> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.0F);
        if (!level.isClientSide) reassessWeaponGoal();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Nullable
    public UUID getOwnerUUID() {
        return entityData.get(OWNER).orElse(null);
    }

    /** Takes on the player's look and copies their equipped gear (drop chance 0). */
    public void copy(Player player) {
        entityData.set(OWNER, Optional.of(player.getUUID()));
        setCustomName(Component.translatable("entity.mobrules.doppelganger.of", player.getDisplayName()));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ANIMAL_ARMOR) continue;
            setItemSlot(slot, player.getItemBySlot(slot).copy());
            setDropChance(slot, 0.0F);
        }
        reassessWeaponGoal();
    }

    public static int summon(CommandSourceStack source, ServerPlayer player, Vec3 pos, @Nullable String tag) {
        ServerLevel level = source.getLevel();
        Doppelganger d = MobRulesEntities.DOPPELGANGER.get().create(level);
        if (d == null) return 0;
        d.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0F);
        d.copy(player);
        if (tag != null) d.addTag(tag);
        d.setTarget(player);
        level.addFreshEntity(d);
        source.sendSuccess(() -> Component.literal("Summoned " + player.getGameProfile().getName() + "'s doppelganger"), true);
        return 1;
    }

    private boolean holdingBow() {
        return getMainHandItem().getItem() instanceof BowItem;
    }

    public void reassessWeaponGoal() {
        if (meleeGoal == null || level() == null || level().isClientSide) return; // null while Mob's constructor runs
        goalSelector.removeGoal(meleeGoal);
        goalSelector.removeGoal(bowGoal);
        if (holdingBow()) {
            bowGoal.setMinAttackInterval(20);
            goalSelector.addGoal(4, bowGoal);
        } else {
            goalSelector.addGoal(4, meleeGoal);
        }
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (!level().isClientSide && slot == EquipmentSlot.MAINHAND) reassessWeaponGoal();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // gear and rules change max health in the first ticks; start the fight at full health once
        if (!level().isClientSide && !healed && tickCount > 5) {
            setHealth(getMaxHealth());
            healed = true;
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        ItemStack weapon = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, Items.BOW));
        ItemStack ammo = getProjectile(weapon);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo, velocity, weapon);
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * 0.2, dz, 1.6F, 14 - level().getDifficulty().getId() * 4);
        playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        level().addFreshEntity(arrow);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID owner = getOwnerUUID();
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putBoolean("Healed", healed);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) entityData.set(OWNER, Optional.of(tag.getUUID("Owner")));
        healed = tag.getBoolean("Healed");
        reassessWeaponGoal();
    }
}
