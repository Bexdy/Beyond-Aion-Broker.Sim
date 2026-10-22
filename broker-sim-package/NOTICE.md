# Notice of modifications

Aion Living Broker extends the [Beyond Aion server emulator](https://github.com/beyond-aion/aion-server) (GPL-3.0).
As required by the GPL, this file lists which upstream files are modified copies and what was changed (2026-10-20).

| File (under `game-server/src/com/aionemu/gameserver/`) | Status | Change |
|---|---|---|
| `services/BrokerService.java` | modified copy | Hooks for virtual sellers/buyers; virtual sellers are never written to the database; sell-window price range; cross-faction purchase check; cleanup of leftover item rows of sold lots; start of the simulation |
| `services/player/PlayerService.java` | modified copy | `getPlayerName()` returns a generated name for virtual sellers (no database lookup) |
| `dao/InventoryDAO.java` | modified copy | One new method `deleteBrokerLocationItems(...)` |
| `services/BrokerSimulationService.java` | **new file** | The whole simulation (traders, market model, buy orders, filters) |

`patches/` contains the same changes as `git apply`-able patches against the upstream files.
Every modified file carries a notice comment on its first line.

## Test harness

`dev-tests/` contains an **unmodified copy of `BrokerItemMask.java`** from beyond-aion/aion-server (GPL-3.0) so the harness can test the category logic against
the real category tree. All other files there are small stand-ins written for this project.
