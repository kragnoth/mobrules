# Changelog

## 0.6.1
- `affix_set`: many affixes in one rule file, each loaded as `<file id>/<name>`, with shared pool and conditions.
- `"affix": "Name"` shorthand.

## 0.6.0
- Affixes: affix rules plus `affixes` rollers, with names built from the rolled affixes.
- Abilities: leap, charge, pull, blink, web, summon, slam, shield and buff, each with a wind-up telegraph.
- World events (`data/<ns>/mobrules_events/`), the `events` condition and `/mobrules event start|stop|list`.
- Nemesis: mobs that kill a player level up, get a name, taunt their victims and pay out rewards.
- Editor: Powers tab and a "During events" condition.

## 0.5.2
- Simple tab protects hand-written rules it can't edit (mob lists and tags, height ranges with both ends, non-multiply stats) and lists other settings.

## 0.5.1
- Simple tab handles block rules (`spawn.block`).

## 0.5.0 and earlier
- Datapack JSON rules for conditions, attributes, equipment, effects, identity, riders, combat, behavior, loot, boss bars, spawn control and an optional spawner.
- Localhost browser editor with in-game previews, presets and in-place editing of datapacks and kubejs/data.
