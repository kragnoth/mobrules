package com.kragnoth.mobrules;

import com.kragnoth.mobrules.rule.BossBarSpec;
import com.kragnoth.mobrules.rule.Combat;
import com.kragnoth.mobrules.rule.EffectSpec;
import com.kragnoth.mobrules.rule.ItemSpec;
import com.kragnoth.mobrules.rule.Loot;
import com.kragnoth.mobrules.rule.MobContext;
import com.kragnoth.mobrules.rule.Rule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/** Applies rules to mobs and runs their combat/behavior/loot/boss bar effects. Server side only. */
public final class RuleEngine {
    private static final ArrayDeque<Mob> PENDING = new ArrayDeque<>();
    private static final Map<Mob, Resolved> RESOLVED = new WeakHashMap<>();
    private static final Set<Mob> TICKING = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Mob, ServerBossEvent> BARS = new WeakHashMap<>();
    /** Rules whose runtime parts (goals) are already set up on this mob instance. */
    private static final Map<Mob, Set<String>> SETUP = new WeakHashMap<>();

    private record Resolved(int generation, List<Rule> rules) {}

    private RuleEngine() {}

    // ------------------------------------------------------------------ applying rules

    static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (!mob.hasData(MobRulesAttachments.APPLIED)) {
            if (event.loadedFromDisk() && !MobRulesConfig.PROCESS_EXISTING.get()) {
                mob.setData(MobRulesAttachments.APPLIED, List.of());
                return;
            }
            // wait a tick so the mob is fully in the world (safe to read biomes/structures around it)
            PENDING.add(mob);
        } else {
            setupRuntime(mob);
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        for (Rule rule : RuleManager.rules()) {
            if (rule.spawner == null) continue;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (ServerPlayer p : level.players()) {
                    try {
                        rule.spawner.tick(level, p, rule);
                    } catch (Exception ex) {
                        MobRules.LOGGER.error("[Mob Rules] spawner of {} failed", rule.id, ex);
                    }
                }
            }
        }
        WorldEvents.tick(event.getServer());
        tickTempBlocks(0);
        int budget = MobRulesConfig.MAX_PER_TICK.get();
        while (budget-- > 0 && !PENDING.isEmpty()) {
            Mob mob = PENDING.poll();
            if (mob.isRemoved() || !mob.isAlive() || mob.hasData(MobRulesAttachments.APPLIED)) continue;
            if (!(mob.level() instanceof ServerLevel)) continue;
            applyRules(mob, null);
        }
    }

    /**
     * Checks every rule against the mob and applies the matching ones.
     * @param only if non-null, apply just this rule and skip its conditions (for /mobrules apply)
     */
    public static List<String> applyRules(Mob mob, @Nullable Rule only) {
        return applyRules(mob, only, null);
    }

    /** @param forced if non-null, this rule always applies (skips its conditions) alongside the normal matching */
    public static List<String> applyRules(Mob mob, @Nullable Rule only, @Nullable Rule forced) {
        ServerLevel level = (ServerLevel) mob.level();
        MobContext c = new MobContext(mob, level);
        List<String> applied = new ArrayList<>(mob.getData(MobRulesAttachments.APPLIED));
        Set<String> groups = new HashSet<>();
        List<Rule> candidates = only != null ? List.of(only) : RuleManager.rules();
        for (Rule rule : candidates) {
            if (rule.group != null && groups.contains(rule.group)) continue;
            if (only == null && rule.affix != null && rule != forced) continue;
            if (only == null && rule != forced && !rule.conditions.test(c)) continue;
            try {
                rule.apply(c);
            } catch (Exception ex) {
                MobRules.LOGGER.error("[Mob Rules] Rule {} failed on {}", rule.id, mob, ex);
                continue;
            }
            String id = rule.id.toString();
            if (!applied.contains(id)) applied.add(id);
            if (rule.group != null) groups.add(rule.group);
            if (MobRulesConfig.DEBUG.get()) {
                MobRules.LOGGER.info("[Mob Rules] {} -> {} at {}", rule.id, mob.getType().getDescriptionId(), mob.blockPosition());
            }
            if (rule.affixes != null) rollAffixes(rule.affixes, c, applied);
            if (rule.stop) break;
        }
        mob.setData(MobRulesAttachments.APPLIED, List.copyOf(applied));
        RESOLVED.remove(mob);
        if (!applied.isEmpty()) setupRuntime(mob);
        return applied;
    }

    private static void rollAffixes(com.kragnoth.mobrules.rule.Affix.Roll roll, MobContext c, List<String> applied) {
        List<Rule> pool = new ArrayList<>();
        for (Rule r : RuleManager.rules()) {
            if (r.affix == null || applied.contains(r.id.toString()) || r.affix.weight() <= 0) continue;
            if (r.affix.pools().stream().noneMatch(roll.pools()::contains)) continue;
            if (!r.conditions.test(c)) continue;
            pool.add(r);
        }
        int n = Math.max(0, roll.count().evalInt(c));
        List<String> names = new ArrayList<>();
        while (n-- > 0 && !pool.isEmpty()) {
            double total = 0;
            for (Rule r : pool) total += r.affix.weight();
            double pick = c.random.nextDouble() * total;
            Rule chosen = pool.get(pool.size() - 1);
            for (Rule r : pool) {
                pick -= r.affix.weight();
                if (pick < 0) { chosen = r; break; }
            }
            pool.remove(chosen);
            try {
                chosen.apply(c);
            } catch (Exception ex) {
                MobRules.LOGGER.error("[Mob Rules] Affix {} failed on {}", chosen.id, c.mob, ex);
                continue;
            }
            applied.add(chosen.id.toString());
            names.add(chosen.affix.name());
        }
        if (roll.rename() && !names.isEmpty()) {
            net.minecraft.network.chat.Component base = c.mob.hasCustomName() ? c.mob.getCustomName() : c.mob.getType().getDescription();
            var style = net.minecraft.network.chat.Style.EMPTY.withColor(roll.color() != null ? roll.color()
                    : net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.YELLOW));
            c.mob.setCustomName(net.minecraft.network.chat.Component.empty().withStyle(style)
                    .append(String.join(" ", names) + " ").append(base));
        }
    }

    // ------------------------------------------------------------------ abilities

    private static final class AbilityState {
        final Map<com.kragnoth.mobrules.rule.Ability, Long> next = new java.util.IdentityHashMap<>();
        com.kragnoth.mobrules.rule.Ability pending;
        LivingEntity pendingTarget;
        long fireAt, liveUntil, shieldUntil;
        int windupTick;
        boolean liveHit;
        com.kragnoth.mobrules.rule.Ability live;
    }

    private static final Map<Mob, AbilityState> ABILITIES = new WeakHashMap<>();
    private record TempBlock(ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block, long at) {}
    private static final List<TempBlock> TEMP_BLOCKS = new ArrayList<>();

    private static final com.kragnoth.mobrules.rule.Ability.AbilityHooks HOOKS = new com.kragnoth.mobrules.rule.Ability.AbilityHooks() {
        @Override
        public void tempBlock(ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block, int ticks) {
            TEMP_BLOCKS.add(new TempBlock(level, pos.immutable(), block, level.getGameTime() + ticks));
        }

        @Override
        public void shield(Mob mob, int ticks) {
            ABILITIES.computeIfAbsent(mob, m -> new AbilityState()).shieldUntil = mob.level().getGameTime() + ticks;
        }
    };

    private static void tickAbilities(Mob mob, ServerLevel level) {
        AbilityState st = ABILITIES.computeIfAbsent(mob, m -> new AbilityState());
        long now = level.getGameTime();
        if (st.shieldUntil > now && now % 4 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT, mob.getX(), mob.getY() + mob.getBbHeight() / 2, mob.getZ(), 4,
                    mob.getBbWidth() * 0.7, mob.getBbHeight() * 0.5, mob.getBbWidth() * 0.7, 0);
        }
        if (st.live != null && now < st.liveUntil && !st.liveHit && st.pendingTarget != null
                && mob.getBoundingBox().inflate(0.4).intersects(st.pendingTarget.getBoundingBox())) {
            st.liveHit = true;
            LivingEntity t = st.pendingTarget;
            t.hurt(mob.damageSources().mobAttack(mob), st.live.contactDamage(mob));
            net.minecraft.world.phys.Vec3 dv = t.position().subtract(mob.position()).multiply(1, 0, 1);
            if (dv.lengthSqr() > 0.01) dv = dv.normalize();
            t.setDeltaMovement(dv.x * 0.8, 0.4, dv.z * 0.8);
            t.hurtMarked = true;
        }
        if (st.pending != null) {
            LivingEntity t = st.pendingTarget;
            if (t == null || !t.isAlive() || t.level() != level) {
                st.pending = null;
                return;
            }
            if (now < st.fireAt) {
                st.pending.telegraph(level, mob, t, st.windupTick++);
                return;
            }
            var a = st.pending;
            st.pending = null;
            int live = a.fire(level, mob, t, new MobContext(mob, level), HOOKS);
            if (live > 0) {
                st.live = a;
                st.liveUntil = now + live;
                st.liveHit = false;
            }
            return;
        }
        if (now % 5 != 0) return;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || (target instanceof Player p && (p.isCreative() || p.isSpectator()))) return;
        for (Rule rule : rulesOf(mob)) {
            for (var a : rule.abilities) {
                Long nx = st.next.get(a);
                if (nx == null) {
                    st.next.put(a, now + 10 + mob.getRandom().nextInt(Math.min(a.cooldown, 60)));
                    continue;
                }
                if (now < nx || !a.ready(mob, target)) continue;
                st.next.put(a, now + a.cooldown);
                if (a.chance < 1 && mob.getRandom().nextDouble() >= a.chance) continue;
                st.pending = a;
                st.pendingTarget = target;
                st.windupTick = 0;
                st.fireAt = now + a.windup;
                com.kragnoth.mobrules.rule.Ability.play(level, mob, a.sound, 1.2f);
                a.telegraph(level, mob, target, 0);
                return;
            }
        }
    }

    static void tickTempBlocks(long dummy) {
        if (TEMP_BLOCKS.isEmpty()) return;
        for (var it = TEMP_BLOCKS.iterator(); it.hasNext(); ) {
            TempBlock b = it.next();
            if (b.level.getGameTime() < b.at) continue;
            it.remove();
            if (b.level.isLoaded(b.pos) && b.level.getBlockState(b.pos).is(b.block)) b.level.removeBlock(b.pos, false);
        }
    }

    static void clearTempBlocks() {
        for (TempBlock b : TEMP_BLOCKS) {
            if (b.level.isLoaded(b.pos) && b.level.getBlockState(b.pos).is(b.block)) b.level.removeBlock(b.pos, false);
        }
        TEMP_BLOCKS.clear();
    }

    /** Rules applied to this mob that still exist after the last reload. */
    public static List<Rule> rulesOf(Entity e) {
        if (!(e instanceof Mob mob) || !mob.hasData(MobRulesAttachments.APPLIED)) return List.of();
        Resolved r = RESOLVED.get(mob);
        if (r == null || r.generation != RuleManager.generation()) {
            List<Rule> list = new ArrayList<>();
            for (String id : mob.getData(MobRulesAttachments.APPLIED)) {
                Rule rule = RuleManager.get(id);
                if (rule != null) list.add(rule);
            }
            r = new Resolved(RuleManager.generation(), List.copyOf(list));
            RESOLVED.put(mob, r);
        }
        return r.rules;
    }

    /** Goals, ticking and boss bars. Not saved, so this runs every time the mob is loaded. */
    private static void setupRuntime(Mob mob) {
        Set<String> done = SETUP.computeIfAbsent(mob, m -> new HashSet<>());
        for (Rule rule : rulesOf(mob)) {
            if (!done.add(rule.id.toString())) continue;
            if (rule.behavior != null && rule.behavior.hostileTo != null && mob instanceof PathfinderMob pm) {
                var matcher = rule.behavior.hostileTo;
                mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 10, true, false,
                        t -> matcher.matches(t.getType().builtInRegistryHolder())
                                && !(t instanceof Player p && (p.isCreative() || p.isSpectator()))));
                if (!(mob instanceof Enemy)) {
                    if (mob.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
                        mob.goalSelector.addGoal(1, new MeleeAttackGoal(pm, 1.25, false));
                    } else {
                        mob.goalSelector.addGoal(1, new FixedDamageMeleeGoal(pm, 1.25, (float) rule.behavior.attackDamage));
                    }
                }
            }
            if (rule.needsTick()) TICKING.add(mob);
            if (rule.bossBar != null && !BARS.containsKey(mob)) {
                BossBarSpec spec = rule.bossBar;
                ServerBossEvent bar = new ServerBossEvent(mob.getDisplayName(), spec.color, spec.style);
                bar.setDarkenScreen(spec.darkenSky);
                BARS.put(mob, bar);
            }
        }
    }

    static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        TICKING.remove(mob);
        ServerBossEvent bar = BARS.remove(mob);
        if (bar != null) bar.removeAllPlayers();
    }

    static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        TICKING.clear();
        RESOLVED.clear();
        SETUP.clear();
        ABILITIES.clear();
        WorldEvents.clear();
        BARS.values().forEach(ServerBossEvent::removeAllPlayers);
        BARS.clear();
    }

    // ------------------------------------------------------------------ spawn control

    static void onFinalizeSpawn(net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent event) {
        // world generation runs off-thread in a region; skip spawn control there to stay safe
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Mob mob = event.getEntity();
        MobContext c = null;
        for (Rule rule : RuleManager.rules()) {
            if (rule.spawn == null) continue;
            if (c == null) c = new MobContext(mob, level);
            if (!rule.conditions.test(c)) continue;
            if (rule.spawn.denies(c, event.getSpawnType())) {
                event.setSpawnCancelled(true);
                if (MobRulesConfig.DEBUG.get()) {
                    MobRules.LOGGER.info("[Mob Rules] {} blocked {} ({}) at {}", rule.id, mob.getType().getDescriptionId(),
                            event.getSpawnType(), mob.blockPosition());
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ per-tick effects

    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !TICKING.contains(mob) || mob.level().isClientSide()) return;
        if (!mob.isAlive()) return;
        for (Rule rule : rulesOf(mob)) {
            Combat c = rule.combat;
            if (c != null) {
                if (c.regenAmount > 0 && mob.tickCount % c.regenInterval == 0 && mob.getHealth() < mob.getMaxHealth()) {
                    mob.heal((float) c.regenAmount);
                }
                if (mob.isOnFire()) {
                    if (c.fireImmune) mob.clearFire();
                    else if (c.sunImmune && mob.level().isDay() && mob.level().canSeeSky(mob.blockPosition())
                            && !mob.isInLava() && !mob.isInWater()) mob.clearFire();
                }
            }
        }
        for (Rule rule : rulesOf(mob)) if (rule.particles != null) rule.particles.tick(mob);
        for (Rule rule : rulesOf(mob)) {
            if (!rule.abilities.isEmpty()) {
                tickAbilities(mob, (ServerLevel) mob.level());
                break;
            }
        }
        ServerBossEvent bar = BARS.get(mob);
        if (bar != null && mob.tickCount % 5 == 0) updateBar(mob, bar);
    }

    private static void updateBar(Mob mob, ServerBossEvent bar) {
        BossBarSpec spec = null;
        for (Rule r : rulesOf(mob)) if (r.bossBar != null) spec = r.bossBar;
        if (spec == null) return;
        bar.setName(mob.getDisplayName());
        bar.setProgress(Math.max(0f, Math.min(1f, mob.getHealth() / mob.getMaxHealth())));
        double rangeSq = spec.range * spec.range;
        for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) {
            if (p.isRemoved() || p.level() != mob.level() || p.distanceToSqr(mob) > rangeSq) bar.removePlayer(p);
        }
        for (ServerPlayer p : ((ServerLevel) mob.level()).players()) {
            if (p.distanceToSqr(mob) <= rangeSq && !bar.getPlayers().contains(p)) bar.addPlayer(p);
        }
    }

    // ------------------------------------------------------------------ combat

    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;
        DamageSource src = event.getSource();
        Entity attacker = src.getEntity();
        float amount = event.getAmount();
        boolean changed = false;
        if (target instanceof Mob sm) {
            AbilityState as = ABILITIES.get(sm);
            if (as != null && as.shieldUntil > sm.level().getGameTime() && !src.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                event.setCanceled(true);
                sm.level().playSound(null, sm.getX(), sm.getY(), sm.getZ(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, net.minecraft.sounds.SoundSource.HOSTILE, 1f, 1f);
                return;
            }
        }
        for (Rule rule : rulesOf(target)) {
            Combat c = rule.combat;
            if (c == null) continue;
            if (c.fireImmune && src.is(DamageTypeTags.IS_FIRE)) {
                event.setCanceled(true);
                return;
            }
            for (Combat.DamageMod m : c.taken) {
                if (m.matches(src, attacker)) {
                    amount = (float) (amount * m.multiply() + m.add());
                    changed = true;
                }
            }
        }
        if (attacker != null) {
            for (Rule rule : rulesOf(attacker)) {
                Combat c = rule.combat;
                if (c == null) continue;
                for (Combat.DamageMod m : c.dealt) {
                    if (m.matches(src, target)) {
                        amount = (float) (amount * m.multiply() + m.add());
                        changed = true;
                    }
                }
            }
        }
        if (!changed) return;
        if (amount <= 0) event.setCanceled(true);
        else event.setAmount(amount);
    }

    static void onDamageDealt(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || event.getNewDamage() <= 0) return;
        DamageSource src = event.getSource();
        if (src.is(DamageTypes.THORNS)) return; // never bounce thorns back and forth
        if (src.getEntity() instanceof Mob attacker) {
            for (Rule rule : rulesOf(attacker)) {
                Combat c = rule.combat;
                if (c == null) continue;
                MobContext ctx = new MobContext(attacker, (ServerLevel) attacker.level());
                for (EffectSpec e : c.hitEffects) e.apply(target, ctx);
                if (c.igniteSeconds > 0) target.igniteForSeconds((float) c.igniteSeconds);
                if (c.lifesteal > 0) attacker.heal((float) (event.getNewDamage() * c.lifesteal));
            }
        }
        if (target instanceof Mob mob && src.getEntity() instanceof LivingEntity attacker && attacker != mob) {
            for (Rule rule : rulesOf(mob)) {
                Combat c = rule.combat;
                if (c == null) continue;
                MobContext ctx = new MobContext(mob, (ServerLevel) mob.level());
                if (c.thorns > 0 && src.getDirectEntity() == attacker) {
                    attacker.hurt(mob.damageSources().thorns(mob), (float) c.thorns);
                }
                for (EffectSpec e : c.attackerEffects) e.apply(attacker, ctx);
            }
        }
        if (target instanceof Mob mob && mob.isAlive() && mob.level() instanceof ServerLevel level) hurtExtras(mob, level, src);
    }

    private static void hurtExtras(Mob mob, ServerLevel level, DamageSource src) {
        var data = mob.getPersistentData();
        for (Rule rule : rulesOf(mob)) {
            Combat c = rule.combat;
            if (c == null) continue;
            MobContext ctx = new MobContext(mob, level);
            if (!c.reinforcements.isEmpty() && level.getGameTime() >= data.getLong("mobrules_reinforce_at")) {
                data.putLong("mobrules_reinforce_at", level.getGameTime() + c.reinforceCooldown);
                for (var s : c.reinforcements) s.spawn(mob, level, ctx);
            }
            if (c.enrageBelow > 0 && !data.getBoolean("mobrules_enraged") && mob.getHealth() <= mob.getMaxHealth() * c.enrageBelow) {
                data.putBoolean("mobrules_enraged", true);
                for (EffectSpec e : c.enrageEffects) e.apply(mob, ctx);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 6, 0.4, 0.3, 0.4, 0);
            }
            if (c.teleportChance > 0 && ctx.random.nextDouble() < c.teleportChance) {
                double r = c.teleportRange;
                for (int i = 0; i < 12; i++) {
                    double x = mob.getX() + (ctx.random.nextDouble() - 0.5) * 2 * r, z = mob.getZ() + (ctx.random.nextDouble() - 0.5) * 2 * r;
                    double y = mob.getY() + ctx.random.nextInt(9) - 4;
                    if (mob.randomTeleport(x, y, z, true)) break;
                }
            }
        }
    }

    static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim && event.getSource().getEntity() instanceof Mob killer
                && killer.level() instanceof ServerLevel kl) {
            for (Rule rule : rulesOf(killer)) {
                if (rule.nemesis != null) {
                    rule.nemesis.onKill(killer, victim, kl);
                    break;
                }
            }
        }
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        for (Rule rule : rulesOf(mob)) if (rule.onDeath != null) rule.onDeath.run(mob, level);
    }

    static void onKnockback(LivingKnockBackEvent event) {
        for (Rule rule : rulesOf(event.getEntity())) {
            if (rule.combat != null && rule.combat.noKnockback) {
                event.setCanceled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ behavior

    static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity next = event.getNewAboutToBeSetTarget();
        if (next == null) return;
        if (next instanceof Player np && event.getEntity() instanceof Mob tm && tm.getTarget() != next) {
            for (Rule rule : rulesOf(tm)) if (rule.nemesis != null) { rule.nemesis.onTarget(tm, np); break; }
        }
        for (Rule rule : rulesOf(event.getEntity())) {
            var b = rule.behavior;
            if (b == null) continue;
            if (b.neverTarget != null && b.neverTarget.matches(next.getType().builtInRegistryHolder())) {
                event.setCanceled(true);
                return;
            }
            if (b.ignoreWearing != null && next instanceof Player p) {
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack worn = p.getItemBySlot(slot);
                    if (!worn.isEmpty() && b.ignoreWearing.matches(worn.getItemHolder())) {
                        event.setCanceled(true);
                        return;
                    }
                }
            }
        }
    }

    static void onGriefing(EntityMobGriefingEvent event) {
        for (Rule rule : rulesOf(event.getEntity())) {
            if (rule.behavior != null && rule.behavior.griefing != null) event.setCanGrief(rule.behavior.griefing);
        }
    }

    // ------------------------------------------------------------------ loot and xp

    static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        List<Rule> rules = rulesOf(mob);
        if (rules.isEmpty()) return;
        MobContext ctx = new MobContext(mob, level);
        boolean playerKill = event.getSource().getEntity() instanceof Player;
        int looting = 0;
        if (event.getSource().getEntity() instanceof LivingEntity killer) {
            var holder = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolder(net.minecraft.world.item.enchantment.Enchantments.LOOTING);
            if (holder.isPresent()) looting = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(holder.get(), killer);
        }
        for (Rule rule : rules) {
            Loot loot = rule.loot;
            if (loot == null) continue;
            if (loot.replace) event.getDrops().clear();
            for (ItemSpec d : loot.drops) {
                if (d.playerKillOnly && !playerKill) continue;
                if (d.chance < 1 && ctx.random.nextDouble() >= d.chance) continue;
                if (d.biomes != null && !d.biomes.matches(level.getBiome(mob.blockPosition()))) continue;
                ItemStack stack = d.create(ctx);
                if (d.perLooting > 0 && looting > 0 && !stack.isEmpty()) {
                    double extra = d.perLooting * looting;
                    int add = (int) extra + (ctx.random.nextDouble() < extra - (int) extra ? 1 : 0);
                    stack.setCount(Math.min(stack.getMaxStackSize(), stack.getCount() + add));
                }
                if (!stack.isEmpty()) event.getDrops().add(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack));
            }
            for (var key : loot.tables) {
                LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
                LootParams.Builder b = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, mob)
                        .withParameter(LootContextParams.ORIGIN, mob.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, event.getSource())
                        .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, event.getSource().getEntity())
                        .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, event.getSource().getDirectEntity());
                if (event.getSource().getEntity() instanceof Player p) {
                    b = b.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, p).withLuck(p.getLuck());
                }
                for (ItemStack stack : table.getRandomItems(b.create(LootContextParamSets.ENTITY))) {
                    event.getDrops().add(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack));
                }
            }
        }
        if (event.getSource().getEntity() instanceof Player killer) {
            for (Rule rule : rules) if (rule.nemesis != null) { rule.nemesis.onDeath(mob, level, killer, event.getDrops(), ctx); break; }
        }
    }

    static void onXp(LivingExperienceDropEvent event) {
        double xp = event.getDroppedExperience();
        boolean changed = false;
        for (Rule rule : rulesOf(event.getEntity())) {
            if (rule.loot != null && (rule.loot.xpMultiply != 1 || rule.loot.xpAdd != 0)) {
                xp = xp * rule.loot.xpMultiply + rule.loot.xpAdd;
                changed = true;
            }
        }
        if (event.getEntity() instanceof Mob xm) {
            int lvl = com.kragnoth.mobrules.rule.Nemesis.level(xm);
            if (lvl > 0) for (Rule rule : rulesOf(xm)) if (rule.nemesis != null) { xp *= 1 + rule.nemesis.xpPerLevel * lvl; changed = true; break; }
        }
        if (changed) event.setDroppedExperience((int) Math.max(0, Math.round(xp)));
    }
}
