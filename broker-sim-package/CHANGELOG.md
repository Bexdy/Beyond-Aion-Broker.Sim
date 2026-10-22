# Changelog

## 0.2.0
- **200 traders by default** (100 per faction), each with its **own random timer** (5 minutes to 3 hours); at most 8 act per 20 seconds.
- **Specialist traders** (general, furniture, armor and accessories, weapons; optional cosmetic role).
- **Broker categories from the broker's own rules** (`BrokerItemMask`): every tab and sub tab of the broker window is covered, a coverage pass keeps each
  category on sale in both factions, items without a broker tab (quest items, keys, coins) are never sold.
- **Variety:** about 1500 active items, same item at most twice per faction, catalog rotation every 6 hours, per-role repeat cap, trader price range settings.
- **No level 1 weapons, armor or accessories** (`equipmentMinLevel = 2`); other tabs unchanged.
- **Per-category minimum price** (100 000 for armor, weapons, housing), lowered only for categories that would otherwise be empty (`minItemsPerCategory`).
- **Buy orders and a moving market** (price band 20 % - 250 % of the reference price); selling at or near the current lowest price sells faster.
- Sell window shows a market estimate and the best open buy order.
- Name filter for NPC, test, admin and internal items; faction locking (new check when buying).
- Review files: catalog, blocked, categories, groups, race audit, coverage.
- Test harness in `dev-tests/`, plan for Mail and Private Store in `docs/`.

## 0.1.0
- First version: about 80 virtual traders, virtual buyers and the apply / restore script.
