# Dev test harness

Runs the **real** `BrokerSimulationService` (from `../src`) against stand-in classes (fake items, a fake broker, a simple logger).
It needs only a JDK (21 or newer), not a server.

```bash
# from this folder
mkdir -p out config/broker-simulation
javac -d out $(find . -name "*.java") ../src/com/aionemu/gameserver/services/BrokerSimulationService.java
java -cp out com.aionemu.gameserver.services.SimTest
```

PowerShell:

```powershell
New-Item -ItemType Directory -Force out, config\broker-simulation | Out-Null
javac -d out (Get-ChildItem -Recurse -Filter *.java | % FullName) ..\src\com\aionemu\gameserver\services\BrokerSimulationService.java
java -cp out com.aionemu.gameserver.services.SimTest
```

It uses the **real** `BrokerItemMask` (the broker window's category tree) with generated items for every sub category, and checks that every category with items is on sale in both factions, before and after ticks and catalog rotations, that items without a broker category are never sold, and that cheap housing items still fill Home Decor and Furniture (their category minimum is lowered).

It prints the number of traders and listings, the maximum repeats of one item, listings per category, rule violations (should all be 0),
role mismatches (should be `{}`), the staggering of the first actions, and what happens when every trader is due at once (only 8 act).

The stand-ins mirror only the methods the simulation calls; they are not the emulator. A passing run does not replace testing on a real server.
