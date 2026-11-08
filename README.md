# MobInteract

**English** | [简体中文](README.zh-CN.md)

[![release](https://img.shields.io/github/v/release/LiuyeStudio/MobInteract?sort=semver&display_name=tag)](https://github.com/LiuyeStudio/MobInteract/releases)
[![downloads](https://img.shields.io/github/downloads/LiuyeStudio/MobInteract/total)](https://github.com/LiuyeStudio/MobInteract/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.8%20~%2026.x-green.svg)](#compatibility)
[![Folia](https://img.shields.io/badge/Folia-supported-brightgreen.svg)](#compatibility)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> Gives **humanoid mobs** the ability to mine blocks, build their own path (stairs / pillar up / bridge) toward targets they could never reach, and pick up blocks and pickaxes off the ground.

Once installed, the zombie, skeleton or vindicator that used to stand behind a fence, stare across a ravine, or pace under a player on a pillar will take matters into its own hands: break the blocking block, pull cobblestone out of its carried stock, stack a step up, bridge across, and grab pickaxes and blocks lying around.

| Ability | What it does | Requirement |
| --- | --- | --- |
| **Mining** | Breaks the block in its way, the block above its head, or the block under its feet / under its target | A pickaxe in the main hand, a mineable block, within reach |
| **Building** | Takes blocks from its virtual stock to build stairs / pillar up / bridge / step down | Was given a stock at spawn, charges left, has a target |
| **Pickup** | Walks to dropped items: blocks go into the stock, pickaxes into the main hand | On by default |

This is **not** a general-purpose mob AI and it does not replace vanilla pathfinding: it only adds two higher-priority behaviours on top, so vanilla targeting, jumping and detouring still work as usual. Villagers, wandering traders and other friendly mobs are not taken over by default.

## Compatibility

| Item | Details |
| --- | --- |
| Server | **Paper / Purpur / Canvas / Folia**, 1.21.8 – 26.x |
| One jar for all | Built against the oldest supported version; newer servers accept the older `api-version`, the reverse is not true — hence a single jar for 1.21.8 and 26.x alike |
| Minimum version | 1.21.8 (older servers lack the pathfinding / scheduling capabilities this plugin relies on) |
| Soft dependency | BlockLocker (if present, protected chests, doors and trapdoors are skipped automatically; not required) |
| Not required | Client mods, resource packs, extra dependencies |

## Download and install

1. Grab the latest `MobInteract-x.y.z.jar` from [Releases](https://github.com/LiuyeStudio/MobInteract/releases).
2. Drop it into your server's `plugins/` folder and restart.
3. On first start it creates six configuration files in `plugins/MobInteract/`: `mining.yml`, `placement.yml`, `equip.yml`, `targeting.yml`, `message.yml`, `update.yml` — all with comments.
4. In game, run `/mobinteract status` for a configuration summary, then aim at a zombie and run `/mobinteract inspect` to see what it is currently thinking.

To upgrade, just replace the jar and restart — your configuration files are never overwritten, and values you edited in an older version win. See [Installation, Upgrade and Removal](https://github.com/LiuyeStudio/MobInteract/wiki/Installation) for the details.

## Commands

The command is `/mobinteract`, alias `/mi`, permission node `mobinteract.admin` (OP only by default).

| Command | Purpose |
| --- | --- |
| `/mobinteract status` | Configuration summary: mob / world filters, mining parameters, build limits, equipment chance, known mobs, temporary blocks, protection-plugin status |
| `/mobinteract inspect` | Live state of the mob you are looking at: cooldown, placements used in this chase, pickaxe in hand, stock contents, last action (the first thing to check when a mob "just won't move") |
| `/mobinteract reload` | Hot-reloads every configuration file without interrupting an ongoing mining or building action |
| `/mobinteract update` | Checks GitHub Releases for a newer version right away (the plugin also does this on its own in the background) |

## Configuration at a glance

Six files, one job each; run `/mobinteract reload` and the change takes effect immediately:

| File | What it controls |
| --- | --- |
| `mining.yml` | Mining: which blocks are mineable, how slow, how far, which are excluded |
| `placement.yml` | Building and pickup: what gets placed, how often, how long blocks last, what the stock contains, what gets picked up |
| `equip.yml` | The spawn-equipment gate: what fraction of mobs are born with a pickaxe / with a stock |
| `targeting.yml` | Player targeting: switch to the nearest player when one is around |
| `message.yml` | Every message sent to players: colours, wording, multiple lines |
| `update.yml` | Update checks: on or off, which repository to ask, how often, who gets told |

Every key, default value and tuning advice lives in the wiki, one page per topic:

| Wiki page | Contents |
| --- | --- |
| [Installation, Upgrade and Removal](https://github.com/LiuyeStudio/MobInteract/wiki/Installation) | Where to put the jar, how to verify it works, upgrading, what the plugin cleans up on removal |
| [Mining](https://github.com/LiuyeStudio/MobInteract/wiki/Mining) | The five trigger rules, reach, break-time formula and conversion table, tools and pickaxes, every `mining.yml` key |
| [Building Paths](https://github.com/LiuyeStudio/MobInteract/wiki/Placement) | When it builds, how it chooses stairs / pillar / bridge, the anti-spam rules, `placement.*` and entry format |
| [Block Backpack and Item Pickup](https://github.com/LiuyeStudio/MobInteract/wiki/Backpack-and-Pickup) | How the stock is rolled, how counts are split, what is picked up and what is not |
| [Birth Equipment, Messages and Player Targeting](https://github.com/LiuyeStudio/MobInteract/wiki/Equip-and-Messages) | Nested `equip.yml` probabilities, `message.yml` placeholders, `targeting.yml` |
| [Commands, Permissions and Behaviour Overview](https://github.com/LiuyeStudio/MobInteract/wiki/Commands) | Command details, mob / world filters, Folia notes, BlockLocker coexistence |
| [FAQ and Known Limitations](https://github.com/LiuyeStudio/MobInteract/wiki/FAQ) | How to debug "it won't move / mine / build / pick up", plus the "looks broken but isn't" cases |
| [Changelog](https://github.com/LiuyeStudio/MobInteract/wiki/Changelog) | What changed in each version and which setting to touch when upgrading |

## Common misunderstandings

- **"Why isn't it mining?"** — No pickaxe in hand (one is required by default), the block is on the safety blacklist (bedrock, barriers, block entities…), or it is simply out of reach.
- **"Why is it just standing there?"** — No stock, placements used up, still cooling down, or filtered out by the world / mob allow- and deny-lists; `/mobinteract inspect` tells you which.
- **"Why is the bridge still there / gone already?"** — Placed blocks vanish after 15 seconds by default; a mob standing on top re-arms the timer. Set it to 0 to keep them forever.
- **"Why doesn't it chase me?"** — Vanilla pathfinding will not dig through a wall it cannot see you behind, unless you enable "mine the block in front" or it is actively chasing (`targeting.yml` can make it fixate on a nearby player).
- **"Why do villagers do nothing?"** — Friendly mobs are not taken over by default; that is a feature, not a bug.

More troubleshooting in [FAQ and Known Limitations](https://github.com/LiuyeStudio/MobInteract/wiki/FAQ).

## License

[Apache License 2.0](LICENSE) © 2026 [LiuyeStudio](https://github.com/LiuyeStudio) · Author [@nukebean](https://github.com/nukebean)

Free to use, modify and redistribute, including commercially, as long as the copyright and license notice are kept.
