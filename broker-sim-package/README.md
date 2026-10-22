# Beyond Aion Broker Sim

**Brings the native Broker of a [Beyond Aion](https://github.com/beyond-aion/aion-server) 4.8 server to life, for small and solo servers.**
Virtual sellers, buy orders and a price-stable market, all inside the stock Broker window you open at the broker NPCs.
No client patch, no new window, no database changes.

> **Status: experimental.** The simulation runs on a Beyond Aion 4.8 server: the Broker window fills with sellers, categories and prices (checked in-game).
> Buying from traders, selling to virtual buyers and relogging still need more testing on real servers, see the [Testing checklist](#testing-checklist).
> Back up your database and your server JAR first, and please report what you find.

---

## What it does

- **Virtual sellers, with variety.** 200 virtual traders (100 per faction, configurable) keep a few listings each in the normal Broker. Items come from
  your server's own item data (tradeable, not soulbound, not timed): about 1500 different items are active at a time, the set **rotates** every few hours,
  and the same item is never listed more than twice (per faction), so the Broker does not look the same twice.
- **Every category of the broker window is covered.** Items are sorted into the broker's own categories (the same rules the client tabs use: Weapon, Armor
  with Clothing/Cloth/Leather/Chain/Plate/Shield, Accessory, Skill related, Home Decor, Furniture, Craft, Consumables, Other and all their sub tabs). Each
  role picks its items category by category in turns, traders pick a category before an item, and a coverage pass makes sure every category has at least one
  listing in both factions (re-checked every 10 minutes). Items the broker has no tab for (quest items, keys, coins) are never sold.
- **Specialist traders.** Each trader has a role and only sells that kind of item: *general* (consumables, materials, stones), *furniture* (housing),
  *armor and accessories*, and *weapons*. The split is configurable (default 40 / 10 / 25 / 25 %). Level 1 weapons, armor and accessories (skins, costumes,
  hats) are not sold at all (`equipmentMinLevel`); a slow *cosmetic* trader role exists for servers that want them back.
- **Not everyone at once.** Every trader has its own random timer (5 minutes to 3 hours for most roles). At most 8 traders act
  per 20 seconds, so the load stays small however many traders you add.
- **A market that moves, inside limits.** Every item has a market price that wanders between 20 % and 250 % of its reference price
  (vendor price x `priceMultiplier`). An item worth 10-20 kinah can reach about 50, or fall to about 2, but never 10 000. Sales push prices up,
  unsold listings pull them down, trader prices follow.
- **Your listings sell.** Virtual buyers keep buy orders (item, quantity, max price). List an item at or below an order and it sells within
  seconds. The cheaper you are compared with the current lowest price, the faster you sell. Buyers take random partial amounts of a stack.
  A hard cap (default 10 purchases per 30 minutes) limits how much Kinah the system can create.
- **High-value categories.** Armor, weapons, accessories and housing are only listed at 100 000 kinah or more (configurable). 
- **No NPC, test or admin items.** Names like "NPC ... Polearm 02", "... Test Design 01", "... Login Stage ...", `Nohohon_05_A` are filtered out;
  you can add your own words or item ids.
- **Factions stay separate.** Elyos and Asmodian brokers are separate lists; virtual traders respect an item's faction, and a new check refuses
  buying an item that belongs to the other faction.
- **Price help in the sell window.** The sell window shows the real lowest/highest price, or a market estimate when nobody lists the item, and the
  best open buy order as the high end.
- **Safe for the database.** Virtual sellers exist only in memory. Only what a real player buys or sells is saved, exactly like a normal Broker sale.

Everything happens inside the existing Broker UI, so the broker NPCs remain the only way to access it.

## Repository layout

| Path | What it is |
|---|---|
| `Apply-BrokerSim.ps1` | One-command installer / restore for an already built server |
| `src/` | The four Java files (`BrokerSimulationService` is new, the other three are modified copies of upstream files) |
| `config/broker-simulation/` | The settings file (and an optional item list), copied to your server by the script |
| `patches/` | The same changes as `git apply` patches against the upstream files |
| `docs/` | [Plan for Mail and Private Store ideas](docs/PLAN_MAIL_AND_PRIVATE_STORE.md) (not implemented yet) |
| `dev-tests/` | Test harness that runs the real simulation against fake item data |
| `NOTICE.md`, `CHANGELOG.md`, `LICENSE` | Modification notice, version history, GPL-3.0 |

## Requirements

- A Beyond Aion 4.8 server (Java 25, as the emulator requires)
- **A JDK 25** (not just a JRE) on the machine where you apply it
- Windows + PowerShell for the one-command method (the source method works anywhere Maven does)

---

## Easy method: an already built server

1. **Shut the GameServer down** completely.
2. Download this repository (green *Code* button > *Download ZIP*) and unzip it. GitHub names the folder `Beyond-Aion-Broker.Sim-main`; `Apply-BrokerSim.ps1` is directly inside it.
3. Open PowerShell in the unzipped folder and run (use your own path; it is the folder that holds the server JAR and `config`):

```powershell
.\Apply-BrokerSim.ps1 -GameServerRoot 'C:\...\Aion\Server\game-server'
```

4. Start the server. After about 15 seconds the log should show:

```
Broker simulation started: 200 traders (80 general, 20 furniture, 50 armor, 50 weapon), ~750 listings, 1500 catalog items.
Broker simulation buyers active: max 10 purchases per 30 min, up to +20% over fair price.
```

**To undo it** (shut the server down first):

```powershell
.\Apply-BrokerSim.ps1 -GameServerRoot 'C:\...\Aion\Server\game-server' -Restore
```

If the script says it cannot find `javac`, add your JDK folder: `-JdkHome 'C:\Path\To\JDK25'`.

**What the script does**

1. Refuses to run while a Java process started from that folder is running.
2. Finds the JAR that contains `BrokerService` and saves a backup next to it as `<jar>.before-brokersim` (only the first time, so the backup is always the original).
3. Compiles the four changed files against **your server's own JARs** and updates that JAR. If compilation fails, nothing is touched.
4. Creates `config\broker-simulation\` with the settings file (an existing file is never overwritten).

> Do **not** put the config files in `config\main\`. The server loads every file there as a global setting and refuses to start on unknown keys.
> The script removes such misplaced copies for you.

## Fresh server: build from source

Use this when you build your own server from source. Two ways:

### A) Copy the files (same upstream version)

```powershell
git clone https://github.com/beyond-aion/aion-server.git
git clone https://github.com/Bexdy/Beyond-Aion-Broker.Sim.git

Copy-Item -Recurse -Force .\Beyond-Aion-Broker.Sim\src\*    .\aion-server\game-server\src\
Copy-Item -Recurse -Force .\Beyond-Aion-Broker.Sim\config\* .\aion-server\game-server\config\

cd aion-server
# build the server exactly as the emulator's own README describes (Java 25 + Maven), for example:
mvn -DskipTests package
```

This **replaces** three upstream files (`BrokerService`, `PlayerService`, `InventoryDAO`) with the versions in this repository, so it is only safe when
your upstream checkout is the same version they were taken from. If your sources differ, use B.

### B) Apply the patches (other upstream versions)

```powershell
cd aion-server
git apply --3way ..\Beyond-Aion-Broker.Sim\patches\BrokerService.patch
git apply --3way ..\Beyond-Aion-Broker.Sim\patches\PlayerService.patch
git apply --3way ..\Beyond-Aion-Broker.Sim\patches\InventoryDAO.patch
Copy-Item ..\Beyond-Aion-Broker.Sim\src\com\aionemu\gameserver\services\BrokerSimulationService.java .\game-server\src\com\aionemu\gameserver\services\
Copy-Item -Recurse -Force ..\Beyond-Aion-Broker.Sim\config\* .\game-server\config\
mvn -DskipTests package
```

Resolve any conflicts by hand (the changes are small, see [NOTICE.md](NOTICE.md)).

### Deploy

Copy the new JAR to your server as usual, and make sure the folder `config\broker-simulation\` exists **next to** `config\main\` in the deployed server
(never inside it). Start the server and look for the log lines above.

## Settings

Edit `config\broker-simulation\broker-simulation.properties` (read at server start; every key has a built-in default, so you may delete any line).

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `tradersPerRace` | `100` | Virtual sellers per faction (max 250) |
| `maxListingsPerItem` | `2` | Same item at most this often among virtual listings, per faction |
| `tickSeconds`, `maxTradersPerTick` | `20`, `8` | How often the scheduler looks for due traders, and the cap per look (load limit) |
| `priceSpreadPercent` | `10` | Random price noise per listing |
| `traderPriceMinFactor`, `traderPriceMaxFactor` | `0.85`, `1.20` | Each trader's price personality; narrow it (e.g. `0.95`, `1.05`) for less price difference between sellers of the same item |
| `role.<name>.percent` | `40/10/25/25/0` | Share of traders per role: `general`, `furniture`, `armor`, `weapon`, `cosmetic` |
| `role.<name>.min/maxListings` | `3-6`, `2-4`, `3-5`, `3-5`, `2-4` | Listings each trader of the role keeps (broker limit is 15) |
| `role.<name>.intervalMin/MaxMinutes` | `5-180` (cosmetic `180-720`) | Random time between two actions of one trader |
| `role.<name>.lifetimeMinutes` | `360` (cosmetic `1440`) | How long a listing stays before it is relisted |
| `role.<name>.maxListingsPerItem` | unset | Per-role override of `maxListingsPerItem` (`1` = no visible duplicates in that role) |
| `minItemsPerCategory` | `8` | Every broker category keeps at least this many items; if fewer reach the role's minimum price, the minimum is lowered for that category only (and logged) |
| `listStigmas` | `true` | Sell stigma stones ("Skill related" category) |
| `equipmentMinLevel` | `2` | Weapons, armor and accessories below this level are never sold (`2` = no level 1 items); other tabs are not affected |
| `cosmeticMaxLevel`, `cosmeticMinPrice` | `1`, `0` | Only used if cosmetic traders are enabled (`equipmentMinLevel = 0` and `role.cosmetic.percent` above 0) |
| `catalogSource` | `auto` | `auto` (server item data), `file` (`broker-simulation-items.tsv`), or `both` |
| `catalogSize`, `catalogSeed` | `1500`, `0` | Number of different active items; seed 0 = a new random catalog at every start |
| `catalogRotateHours`, `catalogRotatePercent` | `6`, `15` | Every N hours this share of each role's items is swapped for others (0 = never) |
| `minItemLevel`, `maxItemLevel` | `0`, `65` | Item level filter |
| `minItemPrice`, `maxItemPrice` | `1`, `5000000` | Filter on the item's vendor price |
| **`priceMultiplier`** | `4.0` | Market price = vendor price x this. **Tune this first.** |
| `maxStackListed` | `200` | Biggest stack a trader lists |
| `listRandomStatItems` | `true` | `false` leaves out items with random stats, which show up as "?" (unidentified) in the broker list |
| `blockUnderscoreNames` | `true` | Block internal names like `Nohohon_05_A` |
| `extraBlockedWords`, `blockedItemIds` | empty | Your own additions to the NPC/test/admin filter |
| `highValueMinPrice` | `100000` | Minimum price for armor, weapons and housing traders (accessories count as armor) |
| `highValueGroupPattern` | `(?i)HOUS\|DECO\|FURNIT` | Fallback only (used if the broker's category rules cannot be read): how housing item groups are recognised |
| `buyersEnabled` | `true` | Virtual buyers on/off |
| `buyerTolerancePercent` | `20` | A listing is bought only up to this much above the market price |
| `buyerMaxPurchases`, `buyerWindowMinutes` | `10`, `30` | **Cap on purchases** (limits how much Kinah is created) |
| `buyerMin/MaxDelayMinutes` | `5` / `90` | Random waiting time before a listing is looked at |
| `buyerMaxStackPercent` | `50` | A purchase takes 1..N % of a splittable stack |
| `ordersEnabled`, `maxOrders`, `ordersPerTick` | `true`, `120`, `3` | Buy orders |
| `orderMin/MaxHours` | `2` / `12` | Lifetime of an order |
| `marketFloorPercent`, `marketCeilingPercent` | `20`, `250` | Price band relative to the reference price |
| `marketVolatilityPercent`, `marketReversionPercent` | `1.5`, `1.0` | Random move and pull-back per minute |
| `buyBumpPercent`, `unsoldDropPercent` | `2.0`, `1.0` | Price reaction to a sale / to an unsold expiry |
| `dumpCatalog` | `true` | Write the review files below |

After the first start, review these files in `config\broker-simulation\`:

- `broker-simulation-catalog.txt`: what the traders sell at start (with role), vendor and broker price
- `broker-simulation-blocked.txt`: what the NPC/test/admin filter removed
- `broker-simulation-categories.txt`: **what the broker window shows right now**: every broker category with its usable items and its listings per faction (`NO ITEMS EXIST` = the data has nothing for that tab)
- `broker-simulation-groups.txt`: **every broker category with the reason items were dropped** (not tradeable, blocked name, level/price filter, faction, below the category minimum), how many are usable and how many are active, and the role it was assigned to. If a category is empty in the broker, look here first.
- `broker-simulation-coverage.txt`: per role, usable items, item groups, active items and the minimum price used
- `broker-simulation-race-audit.txt`: items whose faction was decided by their name

## How it works

- **Traders.** A single background thread (`BrokerSimulation`) services a few traders every `tickSeconds`. Listings live in memory only, inside the broker's
  own faction lists, so search, paging and buying work unchanged.
- **Buying from a trader** is a normal Broker purchase: the Kinah leaves your inventory, the item arrives, nothing is saved for the seller.
- **Selling to the simulation.** Virtual buyers either fill an open order (about 2 seconds after you list) or, by passive demand, visit your listing after
  a random delay that shrinks the closer your price is to the current lowest. The seller is settled like in a normal sale (sold entry, settle icon, Kinah
  collectable at the broker NPC).
- **Economy.** Kinah paid to virtual sellers disappears (a sink); Kinah paid to you by virtual buyers is created (a faucet), capped by `buyerMaxPurchases`.
  Lower the cap, or set `buyersEnabled = false`, if you want no faucet.
- **Limits you should know about.** The broker window is part of the game client, so there is **no new "Orders" tab**, and the **inventory hover tooltip
  cannot show prices** (both would need a client patch). Your own listings of cheap armor/weapons/accessories/housing are not blocked; virtual buyers just
  ignore them.

## Testing checklist

1. Server starts and the log shows the `Broker simulation started` line.
2. Search at a broker NPC: items and seller names (e.g. "Arion") appear, only for your own faction, and no item appears more than twice.
3. Buy a whole stack, then relog: the item is still in your inventory.
4. Buy part of a stack: the rest stays listed.
5. List a stack at a fair price: part of it sells after a while; collect the Kinah at the broker NPC.
6. List something at or below a visible order price: it sells within seconds.
7. Run it for 10+ minutes: listings churn and the log shows no errors.

## Troubleshooting

| Problem | Fix |
|---|---|
| Server crashes at startup with `Could not transform property: catalogSource` | The config file is in `config\main\`. Delete it there (the script does this for you) and use `config\broker-simulation\`. |
| `javac not found` | Install a JDK 25 or pass `-JdkHome`. |
| Compilation error mentioning `DataManager.ITEM_DATA` | Your fork names the item data field differently; rename that one field in `BrokerSimulationService.java`. |
| `No jar ... contains BrokerService` | Point `-GameServerRoot` at the real server folder. |
| A broker category is empty (for example Home Decor) | Open `broker-simulation-categories.txt` (does the category have usable items and listings?) and `broker-simulation-groups.txt` (why were its items dropped?). `blockedName` high: the name filter caught them (see `broker-simulation-blocked.txt`, try `blockUnderscoreNames = false`). `belowMinPrice` high: the category minimum removed them (`highValueMinPrice` or `minItemsPerCategory`). The startup log also warns when a whole role has no items or the broker's category rules could not be read. |
| Items look too cheap/expensive | Adjust `priceMultiplier`. |
| An internal item slipped through | Add a word to `extraBlockedWords` or its id to `blockedItemIds`. |

**Using the Central Market mod?** This project is meant as a lighter alternative that works inside the native broker. If your server was built with
[aion-central-market](https://github.com/AngelShade/aion-central-market), the patch still applies, but its market keeps running unless you set
`gameserver.centralmarket.enable = false` in `config/mygs.properties`. Rebuilding with that project's `Install-Module.ps1` replaces the JAR, so run
`Apply-BrokerSim.ps1` again afterwards.

## Roadmap: mail and private store

Ideas and a staged plan for using **Mail** (virtual traders you can write to and that write back, commissions paid with gatherable materials, price quotes,
contracts) and for a **bot-free private store** (stall NPCs, offline stores) are in
[docs/PLAN_MAIL_AND_PRIVATE_STORE.md](docs/PLAN_MAIL_AND_PRIVATE_STORE.md). Nothing in it is implemented yet.

## Development

`dev-tests/` contains a small harness that runs the real `BrokerSimulationService` against fake item data and a fake broker (no server needed): it checks the
repeat cap, role purity, faction locks, the minimum prices and the staggering. See [dev-tests/README.md](dev-tests/README.md).

## Credits

- **[Beyond Aion team](https://github.com/beyond-aion)**: the [aion-server](https://github.com/beyond-aion/aion-server) emulator this project extends. `BrokerService`,
  `PlayerService` and `InventoryDAO` here are modified copies of their files ([NOTICE.md](NOTICE.md)).
- **[AngelShade](https://github.com/AngelShade)**: [aion-central-market](https://github.com/AngelShade/aion-central-market), the inspiration for a
  simulated, lively market on a solo server. No code from it is included; this project takes the idea into the native Broker instead.
- **Documentation used:** the [Aion Wiki: Broker](https://aion.fandom.com/wiki/Broker) and DeepWiki's
  [Economy System](https://deepwiki.com/beyond-aion/aion-server/8.2-economy-system) page for the emulator.
- Developed with the assistance of **Claude** (Anthropic).
- *Aion* is a trademark of **NCSOFT**. This project is a fan-made, non-commercial tool for private servers and is not affiliated with or endorsed by NCSOFT.

## License

[GNU General Public License v3.0](LICENSE). The emulator this project extends is GPL-3.0 and this project contains modified copies of its files,
so it must stay under the same license. Keep the license file and the notice comments in the modified files when you fork or redistribute.
(This is a summary, not legal advice.)

## Contributing

Issues and pull requests are welcome at [github.com/Bexdy/Beyond-Aion-Broker.Sim](https://github.com/Bexdy/Beyond-Aion-Broker.Sim/issues), especially reports from real servers: your emulator version, the log lines, and what you saw in the broker.
