# Mob Rules (NeoForge 1.21.1)

Datapack JSON rules for modpack makers. Each rule picks mobs by what, where, when and how they
spawned, then changes them: any attribute (with scaling), gear, effects, flags, riders, combat,
targeting, griefing, drops, XP and boss bars.

Mob Rules is server side only; clients don't need it.

Inspired by [In Control](https://github.com/McJtyMods/InControl) by McJty.

## How it works

- Rules go in a datapack at `data/<namespace>/mobrules/<anything>.json`. The rule id is
  `<namespace>:<path>`. `/reload` reloads them.
- Each mob is checked once, the tick after it first enters the world. Every matching rule applies
  in priority order. The mob remembers which rules it got, and that is saved with it.
- Attribute changes are real named modifiers (`<rule id>/<attribute>/<op>`), so they save, stack
  predictably with other mods, and show in `/attribute`.
- Combat, behavior, loot and boss bar effects keep working for the mob's whole life, including
  after restarts, and follow later `/reload` edits to the rule.
- Mobs saved before Mob Rules was installed are checked the first time they load. Set
  `processExisting = false` in `config/mobrules-common.toml` to leave them alone.

## Rule file

```jsonc
{
  "priority": 10,          // higher runs first (default 0)
  "group": "tier",         // only the first matching rule of a group applies (elite vs boss)
  "stop": false,           // true: nothing lower-priority applies after this
  "enabled": true,
  "conditions": { ... },
  // actions, all optional:
  "attributes": { ... }, "heal": true, "equipment": { ... }, "effects": [ ... ],
  "name": ..., "glowing": true, "silent": true, "no_gravity": true, "invulnerable": true,
  "persistent": true, "left_handed": true, "no_ai": true, "baby": true,
  "add_tags": ["elite"], "nbt": "{CanBreakDoors:1b}",
  "rider": { ... }, "combat": { ... }, "behavior": { ... }, "loot": { ... }, "boss_bar": { ... }
}
```

Keys starting with `_` are ignored, so `"_comment"` works. Any other unknown key is an error,
which catches typos.

## Numbers

Anywhere a number goes, you can use:

| Form | Meaning |
|---|---|
| `2.5` | fixed |
| `[1, 3]` or `{"min": 1, "max": 3}` | random in range |
| `{"base": 1.5, "per_day": 0.05, "per_1000_blocks": 0.2, "per_local_difficulty": 0.3, "spread": [0, 0.5], "floor": 1, "cap": 4}` | scales with the world day, distance from world spawn and local difficulty, plus random spread, clamped |

## Conditions

All present conditions must pass.

| Key | Example |
|---|---|
| `mobs` | `["minecraft:zombie", "#minecraft:skeletons", "alexscaves:*", "!minecraft:husk"]` |
| `categories` | `["monster", "creature", "ambient", "water_creature", ...]` |
| `hostile`, `baby`, `sees_sky` | `true` / `false` |
| `dimensions` | `"minecraft:the_nether"` |
| `biomes` | `["#minecraft:is_forest", "!minecraft:dark_forest"]` |
| `structures` | `["minecraft:fortress", "#minecraft:village"]` (inside a structure piece) |
| `height`, `light`, `day`, `local_difficulty`, `distance_from_spawn` | `{"min": 0, "max": 40}` |
| `time` | `{"min": 13000, "max": 23000}`; wraps past midnight if min > max |
| `weather` | `["rain", "thunder", "clear"]` |
| `moon_phases` | `[0]` (0 = full moon) |
| `difficulty` | `["hard"]` |
| `spawn_types` | `natural, spawner, structure, chunk_generation, breeding, spawn_egg, command, jockey, reinforcement, patrol, event, trial_spawner, ...`, or `unknown` |
| `chance` | `0.15` |
| `has_tags`, `lacks_tags` | scoreboard tags |
| `any_of`, `none_of` | lists of nested condition blocks |

## Attributes

Any registered attribute, including other mods' attributes. Short names try
`minecraft:generic.<name>` first.

```json
"attributes": {
  "max_health":   { "multiply": 2 },
  "scale":        { "multiply": [0.8, 1.6] },
  "attack_damage":{ "add": { "base": 1, "per_day": 0.1, "cap": 8 } },
  "step_height":  { "set": 1.5 },
  "movement_speed": { "multiply_base": 1.2 }
}
```

The operations:
- `add` adds a flat amount.
- `multiply` multiplies the final value.
- `multiply_base` multiplies the base value.
- `set` sets the final value.

Health is refilled after `max_health` changes unless `"heal": false`.

## Equipment

```json
"equipment": {
  "mode": "fill_empty",                 // or "replace" (default)
  "clear": false,
  "sets": [ { "weight": 3, "head": "minecraft:iron_helmet", "chest": "...", "legs": "...", "feet": "..." } ],
  "mainhand": [ { "item": "minecraft:iron_sword", "weight": 3 }, { "empty": true, "weight": 1 } ],
  "offhand": "minecraft:shield",
  "body": "minecraft:iron_horse_armor",
  "drop_chance": { "all": 0.0, "mainhand": 0.25 },
  "can_pick_up_loot": true
}
```

- A set picks a whole matching loadout. Per-slot lists then fill the slots the set didn't cover.
- Drop chance follows vanilla rules: below 1 needs a recent player hit, and 2 or more always drops undamaged.

Item entries can be a plain id string, or an object:

```json
{ "item": "minecraft:diamond_helmet", "count": 1,
  "enchant": { "levels": [5, 20], "from": "#minecraft:on_mob_spawn_equipment" },
  "enchant": { "provider": "minecraft:mob_spawn_equipment" },
  "enchantments": { "minecraft:protection": [1, 4] },
  "durability": [0.3, 0.8], "dye": "#3C1F1F", "name": "Grave Hood", "unbreakable": true,
  "trim": { "material": "minecraft:gold", "pattern": "minecraft:coast" },
  "components": { "minecraft:rarity": "epic" } }
```

`enchant` takes one of two forms. Use `levels` plus `from` for random enchanting, or `provider` for a vanilla enchantment provider.

## Effects

`"effects": ["minecraft:fire_resistance", {"effect": "minecraft:speed", "amplifier": [0, 1], "duration": -1, "particles": false}]`

Duration is in ticks, and -1 (the default) means infinite.

## Name

`"name": "Elite {mob}"` or `{"text": "Elite {mob}", "color": "gold" | "#FFAA00", "bold": true, "visible": true}`.
`{mob}` is replaced with the mob's translated name.

## Rider

`"rider": {"mob": "minecraft:skeleton", "chance": 0.2}`

The rider spawns as a jockey, so it gets vanilla gear and can be targeted with
`"spawn_types": "jockey"`. Riders never get riders of their own.

## Combat

```json
"combat": {
  "damage_taken": [ { "types": ["#minecraft:is_projectile"], "attackers": ["minecraft:player"], "multiply": 0.5 } ],
  "damage_dealt": [ { "targets": ["minecraft:player"], "multiply": 1.5, "add": 1 } ],
  "on_hit":  { "effects": [ {"effect": "minecraft:poison", "duration": 100, "chance": 0.5} ], "ignite_seconds": 3, "lifesteal": 0.25 },
  "on_hurt": { "thorns": 2, "attacker_effects": [ "minecraft:glowing" ] },
  "regen": { "amount": 1, "interval": 40 },
  "fire_immune": true, "sun_immune": true, "no_knockback": true
}
```

`types` are damage types or tags, for example `#minecraft:is_fire`, `#minecraft:is_explosion`,
`#minecraft:is_fall` or `minecraft:arrow`. A multiplier of 0 makes the mob immune.

## Behavior

```json
"behavior": {
  "griefing": false,
  "hostile_to": ["minecraft:player"],
  "attack_damage": 3,
  "never_target": ["#minecraft:skeletons"],
  "ignore_players_wearing": ["minecraft:golden_helmet"],
  "angry_on_spawn": true
}
```

- `hostile_to` also works on passive mobs. Mobs that normally can't attack hit for `attack_damage`.
- `ignore_players_wearing` checks any armor slot.

## Loot and XP

```json
"loot": {
  "replace": false,
  "drops": [ { "item": "minecraft:emerald", "count": [1, 3], "chance": 0.5, "player_kill_only": true } ],
  "tables": ["minecraft:entities/wither_skeleton"],
  "xp": { "multiply": 3, "add": 10 }
}
```

Drop items accept everything an equipment item does. XP only drops on player kills, as in vanilla.

## Boss bar

`"boss_bar": {"color": "red", "style": "notched_10", "range": 40, "darken_sky": false}`, or simply `"boss_bar": true`.

## Spawning

```json
"spawn":   { "block": true }                                  // or { "max_nearby": { "count": 6, "radius": 48 } }, "types": [...]
"spawner": { "mob": "minecraft:zombie", "interval": 200, "attempts": 4, "chance": 1,
             "distance": { "min": 24, "max": 64 }, "vertical_range": 24, "group": [1, 3],
             "max_nearby": { "count": 6, "radius": 64 }, "placement": "ground", "vanilla_checks": true }
```

- `spawn` blocks or caps world spawns that match the rule's conditions.
- `spawner` adds new spawns near players wherever the conditions pass, searching every floor level. That makes it work inside structures (set `structures`) and around features: use the `near_blocks` condition, e.g. `{"blocks": ["#minecraft:logs"], "radius": 6}`.
- Spawner mobs always get the rest of their own rule.
- Biome spawn lists are edited with NeoForge biome modifiers, which the editor writes for you.

## Editor

Run `/mobrules_editor` in a singleplayer (or LAN host) world and click the link.
- It opens in your browser and only works from this computer, using a one-time link.
- Previews are rendered by the game with your current resource packs, and packs can be toggled from the editor.
- Rules save into `world/datapacks/mobrules_editor`.
- "Save and reload" applies a rule; biome spawn changes need a world rejoin.
- For a dedicated server, download the datapack zip and copy it over.

## Death, hurt, variants and particles

```json
"on_death": { "explode": { "power": 2, "fire": false, "break_blocks": false }, "lightning": true,
              "summon": [ { "mob": "minecraft:silverfish", "count": [2, 4], "chance": 1, "rule": "pack:brood" } ] },
"combat": { "on_hurt": { "reinforcements": [ { "mob": "minecraft:zombie", "count": [1, 2], "chance": 0.3 } ], "reinforce_cooldown": 200,
                         "enrage": { "below": 0.5, "effects": ["minecraft:strength"] }, "teleport": { "chance": 0.2, "range": 8 } } },
"variant": { "baby_chance": 0.25, "can_break_doors": true, "villager_profession": "minecraft:farmer",
             "villager_type": "minecraft:taiga", "sheep_color": "red", "slime_size": 4 },
"particles": { "type": "minecraft:soul_fire_flame", "count": 2, "interval": 4, "spread": 0.4 }
```

- Drop entries also take `"per_looting": 1`, which adds extra items per Looting level, and `"biomes": [...]`.
- Everything in this mod runs on the server; players don't need it installed.

## Abilities

```json
"abilities": [
  { "type": "leap", "cooldown": 100, "range": [3, 12], "windup": 10 },
  { "type": "web", "cooldown": 120, "duration": 80, "block": "minecraft:cobweb" },
  { "type": "summon", "cooldown": 400, "health_below": 0.5, "mobs": [ { "mob": "minecraft:vex", "count": 2 } ], "message": "Rise, {target} must fall!" }
]
```

Types are `leap`, `charge`, `pull`, `blink`, `web`, `summon`, `slam`, `shield` and `buff`. Before firing, the mob stops, faces its target and shows particles and a sound for `windup` ticks so players can react.

Every ability also takes these fields:
- `chance`, `needs_sight`, `health_below` (e.g. 0.5 means only at half health or below)
- `strength`, `damage`, `radius` and `duration`
- `self_effects` and `target_effects`
- `sound`, `particle` and `message`

## Affixes

```json
// data/pack/mobrules/affixes/vampiric.json
{ "affix": { "name": "Vampiric", "weight": 2, "pool": "elite" }, "combat": { "on_hit": { "lifesteal": 0.5 } } }
// data/pack/mobrules/elites.json
{ "conditions": { "hostile": true, "chance": 0.1 }, "affixes": { "pool": "elite", "count": [1, 3], "color": "gold" } }
```

- A rule with `affix` never applies on its own; it only applies when another rule rolls it.
- Its conditions decide which mobs can roll it.
- Rolled affixes are added to the name, e.g. "Swift Vampiric Zombie".

To keep many affixes in one file, use an `affix_set`:

```json
{ "affix_set": { "pool": "elite", "conditions": { "hostile": true },
    "affixes": [
      { "affix": "Vampiric", "combat": { "on_hit": { "lifesteal": 0.5 } } },
      { "affix": { "name": "Swift", "weight": 3 }, "attributes": { "movement_speed": { "multiply": 1.35 } } } ] } }
```

- Each entry becomes the rule `<file id>/<name>`, e.g. `pack:affixes/vampiric`.
- The set's `pool` and `conditions` are defaults; an entry's own values replace them.

## Nemesis

`"nemesis": true`, or `{ "names": [...], "title": "{name} the {victim}-Slayer", "stats": {"max_health": 0.3, "attack_damage": 0.2}, "max_level": 10, "announce": "all", "taunts": [...], "reward": [items], "xp_per_level": 1, "glow_at": 3 }`.

When a mob with this rule kills a player:
- It gets a name and gains the `stats` multipliers once per kill.
- It stops despawning.
- It taunts its victims when it targets them again.
- On death, its reward is rolled once per level.

## World events

Events go in `data/<ns>/mobrules_events/<name>.json`:

```json
{ "name": "Blood Moon", "color": "dark_red", "trigger": { "moon_phases": [0], "chance": 0.33, "min_day": 3, "every_days": 0 },
  "dimensions": ["minecraft:overworld"], "starts": "dusk", "lasts": "night",
  "start_message": "The Blood Moon rises...", "end_message": "The Blood Moon sets.", "title": true,
  "sound": "minecraft:entity.wither.spawn", "boss_bar": "red" }
```

- Rules check events with the condition `"events": ["pack:blood_moon"]`; prefix an id with `!` to mean "not during".
- `lasts` is `night`, `day` or a number of ticks.
- Sleeping ends a night event.
- An event with no `trigger` only starts from the command.
- Commands: `/mobrules event list`, `/mobrules event start "<id>"` and `/mobrules event stop "<id>"`.

## Commands (op level 2)

| Command | What it does |
|---|---|
| `/mobrules list` | loaded rules, in priority order |
| `/mobrules errors` | rules that failed to load, with the exact JSON path |
| `/mobrules inspect <targets>` | rules a mob got, its health, spawn type, and every attribute change |
| `/mobrules apply "<rule id>" <targets>` | applies one rule now, ignoring its conditions (for testing) |
| `/mobrules recheck <targets>` | runs all rules against the mobs again |

## Examples

`examples/mobrules_examples` is a ready datapack:
- elite undead that scale with the day
- creepers that don't break blocks
- spider jockeys
- a Fortress Warlord boss
- full-moon wolves
- piglins that ignore gold armor
- random elite affixes (Vampiric, Volatile, Frostbound, Swift, Stalwart, Brood)
- zombie nemeses
- a Blood Moon event
- a Spider Matriarch boss with abilities
- a deliberately broken rule that shows the error reporting

Copy the folder into `world/datapacks/`.

## Licence

MIT, by Kragnoth (designed by Kragnoth, coded with AI help). No In Control code is used; it was the reference for which features exist.
