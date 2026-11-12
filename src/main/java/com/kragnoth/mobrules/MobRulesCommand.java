package com.kragnoth.mobrules;

import com.kragnoth.mobrules.rule.Rule;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * /mobrules list | errors | inspect [targets] | apply rule targets | recheck targets
 */
public final class MobRulesCommand {
    private MobRulesCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mobrules").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(MobRulesCommand::list))
                .then(Commands.literal("errors").executes(MobRulesCommand::errors))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(c -> inspect(c, EntityArgument.getEntities(c, "targets")))))
                .then(Commands.literal("apply")
                        .then(Commands.argument("rule", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(RuleManager.rules().stream().map(r -> "\"" + r.id + "\""), b))
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(c -> apply(c, StringArgumentType.getString(c, "rule"), EntityArgument.getEntities(c, "targets"))))))
                .then(Commands.literal("event")
                        .then(Commands.literal("list").executes(MobRulesCommand::events))
                        .then(Commands.literal("start").then(Commands.argument("event", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(WorldEvents.defs().keySet().stream().map(k -> "\"" + k + "\""), b))
                                .executes(c -> event(c, StringArgumentType.getString(c, "event"), true))))
                        .then(Commands.literal("stop").then(Commands.argument("event", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(WorldEvents.active(c.getSource().getLevel()).stream().map(k -> "\"" + k + "\""), b))
                                .executes(c -> event(c, StringArgumentType.getString(c, "event"), false)))))
                .then(Commands.literal("recheck")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(c -> recheck(c, EntityArgument.getEntities(c, "targets"))))));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<Rule> rules = RuleManager.rules();
        c.getSource().sendSuccess(() -> Component.literal(rules.size() + " rule(s) loaded, " + RuleManager.errors().size() + " with errors"), false);
        for (Rule r : rules) {
            c.getSource().sendSuccess(() -> Component.literal("  " + r.id + "  priority " + r.priority + (r.group != null ? "  group " + r.group : "")), false);
        }
        return rules.size();
    }

    private static int errors(CommandContext<CommandSourceStack> c) {
        List<String> errs = new java.util.ArrayList<>(RuleManager.errors());
        errs.addAll(WorldEvents.errors());
        if (errs.isEmpty()) c.getSource().sendSuccess(() -> Component.literal("No rule errors."), false);
        for (String e : errs) c.getSource().sendFailure(Component.literal(e));
        return errs.size();
    }

    private static int inspect(CommandContext<CommandSourceStack> c, Collection<? extends Entity> targets) {
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof Mob mob)) continue;
            n++;
            List<String> ids = mob.hasData(MobRulesAttachments.APPLIED) ? mob.getData(MobRulesAttachments.APPLIED) : null;
            String header = mob.getDisplayName().getString() + " (" + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()) + ")  "
                    + String.format("%.1f/%.1f hp", mob.getHealth(), mob.getMaxHealth())
                    + "  spawn: " + (mob.getSpawnType() == null ? "unknown" : mob.getSpawnType().name().toLowerCase());
            c.getSource().sendSuccess(() -> Component.literal(header), false);
            String rules = ids == null ? "not checked yet" : ids.isEmpty() ? "no rules matched" : String.join(", ", ids);
            c.getSource().sendSuccess(() -> Component.literal("  rules: " + rules), false);
            if (ids != null && !ids.isEmpty()) {
                BuiltInRegistries.ATTRIBUTE.holders().forEach(h -> {
                    AttributeInstance inst = mob.getAttribute(h);
                    if (inst != null) describe(c, inst, ids);
                });
            }
        }
        return n;
    }

    private static void describe(CommandContext<CommandSourceStack> c, AttributeInstance inst, List<String> ids) {
        for (AttributeModifier m : inst.getModifiers()) {
            String mid = m.id().toString();
            for (String rule : ids) {
                if (mid.startsWith(rule + "/")) {
                    String line = "  " + BuiltInRegistries.ATTRIBUTE.getKey(inst.getAttribute().value()) + " = "
                            + String.format("%.3f", inst.getValue()) + "  (" + m.operation().getSerializedName() + " " + String.format("%.3f", m.amount()) + " from " + rule + ")";
                    c.getSource().sendSuccess(() -> Component.literal(line), false);
                }
            }
        }
    }

    private static int apply(CommandContext<CommandSourceStack> c, String id, Collection<? extends Entity> targets) {
        Rule rule = RuleManager.get(id);
        if (rule == null) {
            c.getSource().sendFailure(Component.literal("No rule '" + id + "'. Try /mobrules list."));
            return 0;
        }
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof Mob mob) {
                RuleEngine.applyRules(mob, rule);
                n++;
            }
        }
        int count = n;
        c.getSource().sendSuccess(() -> Component.literal("Applied " + id + " to " + count + " mob(s), ignoring its conditions."), true);
        return n;
    }

    private static int recheck(CommandContext<CommandSourceStack> c, Collection<? extends Entity> targets) {
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof Mob mob) {
                List<String> ids = RuleEngine.applyRules(mob, null);
                n++;
                c.getSource().sendSuccess(() -> Component.literal(mob.getDisplayName().getString() + ": " + (ids.isEmpty() ? "no rules matched" : String.join(", ", ids))), false);
            }
        }
        return n;
    }

    private static int events(CommandContext<CommandSourceStack> c) {
        var lvl = c.getSource().getLevel();
        c.getSource().sendSuccess(() -> Component.literal(WorldEvents.defs().size() + " event(s) loaded. Active here: "
                + (WorldEvents.active(lvl).isEmpty() ? "none" : String.join(", ", WorldEvents.active(lvl)))), false);
        for (String id : WorldEvents.defs().keySet()) c.getSource().sendSuccess(() -> Component.literal("  " + id), false);
        return WorldEvents.defs().size();
    }

    private static int event(CommandContext<CommandSourceStack> c, String id, boolean start) {
        var lvl = c.getSource().getLevel();
        if (start) {
            var d = WorldEvents.defs().get(id);
            if (d == null) {
                c.getSource().sendFailure(Component.literal("No event '" + id + "'. Try /mobrules event list."));
                return 0;
            }
            boolean ok = WorldEvents.start(lvl, d);
            c.getSource().sendSuccess(() -> Component.literal(ok ? "Started " + id : id + " is already running"), true);
            return ok ? 1 : 0;
        }
        boolean ok = WorldEvents.stop(lvl, id);
        c.getSource().sendSuccess(() -> Component.literal(ok ? "Stopped " + id : id + " isn't running here"), true);
        return ok ? 1 : 0;
    }
}
