# LoreCleaner

Paper 26.2 plugin that automatically moves **lore items** from players who have been offline for 6+ months into barrels at their last logout location.

Implements the policy announced by wilder0p:

> Going forward if you don't log into the server for 6 months, all lore items in your e-chest and inventory will be placed in a barrel at your last log off spot.

## Features

- **Grace period**: 30 days after first enable (configurable)
- **Load-aware**: Only processes while TPS has been at ~20.0 for a configurable number of continuous minutes
- **Oldest first**: Prioritizes players who have been offline the longest
- **Rate limited**: Configurable players-per-minute to avoid disk/CPU spikes
- **Cooldown**: After a full successful pass, waits 72 hours (configurable) before scanning again
- **Multiple barrels**: Automatically creates extra barrels if a player has >27 lore items
- **Wall sign** on every barrel with player name + date
- **World-border safe** placement
- **Failed-load logging**: Old/corrupted 1.14-era playerdata files are skipped and logged
- **Discord webhook** (no coordinates ever sent)
- **Login message** to the player the next time they join
- **OP commands**: `/lorecleaner force|status|reload`

## Requirements

- Paper 26.2
- Java 25 (required by Paper 26.2)

## Building

```bash
git clone https://github.com/wilderop/LoreCleaner.git
cd LoreCleaner
mvn clean package
```

The jar will be in `target/LoreCleaner-1.0.0.jar`.

## Installation

1. Place the jar in your `plugins/` folder
2. Start the server once to generate `config.yml` and `data.yml`
3. Edit `config.yml` (especially the Discord webhook if desired)
4. Restart or `/lorecleaner reload`

## Configuration highlights

| Key | Default | Meaning |
|-----|---------|---------|
| `inactive-days` | 180 | Days offline before eligible |
| `grace-period-days` | 30 | Days after first enable before any action |
| `tps-stable-minutes` | 5 | Continuous minutes at ~20 TPS required |
| `players-per-minute` | 4 | Max players processed per minute |
| `cooldown-after-full-run-hours` | 72 | Wait after a complete cycle |
| `recheck-days` | 180 | Days before a previously cleaned player can be cleaned again |
| `discord-webhook-url` | "" | Optional webhook |

## Commands

- `/lorecleaner force` – Queue an immediate run (still respects TPS lock unless already stable)
- `/lorecleaner status` – Show grace period, TPS lock, queue size, last run, etc.
- `/lorecleaner reload` – Reload config.yml

Permission: `lorecleaner.admin` (default: op)

## Notes on old playerdata

The server has existed since 1.14. Some `.dat` files may be corrupted or use very old formats. Those are automatically skipped and written to `plugins/LoreCleaner/logs/failed-loads.log` so you can inspect them later.

## Important note on item conversion

The offline playerdata reader uses reflection against Paper's NBT classes. On first test you may need a small adjustment to `OfflinePlayerData.nbtToItemStack` depending on the exact 26.2 build mappings. The rest of the plugin is pure Paper API.
