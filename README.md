# Clearlagg

A lag-reduction plugin for Paper servers. It targets the usual suspects behind
poor tick times: runaway entity counts, spawner farms, mob-egg spam, uncontrolled
breeding, dropped items that never expire, and TNT chain reactions. On top of the
limiters it ships the monitoring tools you need to find out what is actually
costing you ticks: TPS/RAM tracking, chunk analysis, GC monitoring, a tick
sampler and a memory sampler.

The design goal is prevention rather than brute force. Clearing entities every
five minutes hides the symptom; the limiters keep the entities from piling up in
the first place.

## Requirements

- Paper (or a fork) on Minecraft 1.21+
- Java 21

PlaceholderAPI is optional. If it is installed, Clearlagg registers its
placeholders automatically; if not, everything else works unchanged.

## Commands

All commands live under `/lagg` (aliases: `/clearlag`, `/clearlagg`).

| Command | Description |
| --- | --- |
| `/lagg clear` | Remove the entity types configured under `entity-clear` |
| `/lagg check` | Show world and chunk information |
| `/lagg killmobs` | Kill the mob types configured under `kill-mobs` |
| `/lagg area <radius>` | Remove non-player entities within a radius |
| `/lagg tpchunk <x> <z> [world]` | Teleport to a chunk by its coordinates |
| `/lagg tps` | Show estimated TPS over 5s, 1m and 5m |
| `/lagg memory` | Watch heap usage in realtime |
| `/lagg performance` | Watch main-thread usage in realtime |
| `/lagg gc` | Request a garbage collection |
| `/lagg sampleTicks <ticks> [raw]` | Sample tick durations and report spikes |
| `/lagg sampleMemory <seconds>` | Sample memory usage and GC timings |
| `/lagg profile <seconds> <type>` | Profile server activity |
| `/lagg unloadchunks` | Attempt to unload chunks |
| `/lagg halt` | Temporarily halt basic server functions |
| `/lagg admin <list\|enable\|disable> [module]` | Enable or disable individual modules at runtime |
| `/lagg reload` | Reload the configuration |

Each subcommand has a matching permission (`lagg.clear`, `lagg.check`, and so
on), all defaulting to op. `lagg.*` grants everything. `lagg.notify` controls who
receives the automatic threshold and GC notifications.

## Configuration

`config.yml` is split into one section per feature, each documented inline:

- `settings` - prefix, language, update checks, startup banner
- `entity-clear` / `kill-mobs` - what gets removed, on which schedule, with warnings
- `limiters` - per-chunk and per-world entity caps
- `spawner-limiter`, `mob-egg-limiter`, `breeding-limiter` - caps on the common farm exploits
- `item-livetime` - per-item despawn times
- `tnt` - chain-reaction and block-damage limits
- `chunk-analysis`, `chunk-unloader` - finding and unloading expensive chunks
- `ai-limiter` - distance-based mob AI throttling
- `tps-meter` - run commands when TPS or RAM crosses a threshold
- `gc-monitor` - warn on long GC pauses
- `tick-sampler`, `memory-sampler`, `profiler` - the measurement tools
- `halt` - what `/lagg halt` actually freezes

Twelve languages ship in `lang/`; pick one with `settings.language`.

`tps-meter` triggers are opt-in. No trigger is enabled by default, so Clearlagg
will not broadcast anything to your players unless you configure it to.

## Placeholders

Available when PlaceholderAPI is installed:

```
%clearlagg_tps%                %clearlagg_entities_world%
%clearlagg_tps_colored%        %clearlagg_entities_total%
%clearlagg_ram_used%           %clearlagg_chunks_loaded%
%clearlagg_ram_max%            %clearlagg_last_clear_amount%
%clearlagg_ram_percent%        %clearlagg_halted%
```

## Building

```bash
mvn clean package
```

The shaded jar lands in `target/clearlagg-1.0-RELEASE.jar`. bStats is shaded and
relocated into `de.empireblocks.clearlagg.libs.bstats`; paper-api and
PlaceholderAPI are `provided` and stay out of the jar.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
