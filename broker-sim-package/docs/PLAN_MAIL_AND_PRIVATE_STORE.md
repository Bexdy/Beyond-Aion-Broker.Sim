# Plan: Mail and Private Store for solo / offline play

**Status: design notes only. Nothing here is implemented yet.**
Class names come from the [DeepWiki page on the emulator's player systems](https://deepwiki.com/beyond-aion/aion-server/8-player-systems);
anything marked *(verify)* has to be confirmed by reading that class before coding.

## 1. Goals

A solo or offline player has no one to write to and no one to trade with. The broker simulation already gives them a market. This plan gives them
two more of the game's economy tools, with the same rules as the rest of the project:

- **No bots.** Nothing walks around, no fake sessions, no fake `Player` objects.
- **Everything virtual and cheap.** Work happens on the existing simulation thread, driven by timers, never per player per tick.
- **Database-safe.** Virtual characters never get a character row. Only real mails and real items are saved, like normal gameplay.
- **Bounded economy.** Every Kinah faucet shares the existing purchase cap, every service has a fee or a material cost (a sink), nothing is free.
- **Switchable.** One config switch per feature; GM kill switch.
- **Works offline.** Everything is resolved on the server; the result waits in the mailbox or broker until the player logs in.

## 2. What the server already offers (DeepWiki)

| System | Class(es) | Useful for us |
|---|---|---|
| Mail | `MailService`, `SystemMailService`, `Mailbox`, packet `SM_MAIL_SERVICE` | Regular and express mail, item + Kinah attachments, mail fees, race and block-list restrictions, **system-generated mail** for notifications and rewards |
| Private store | `PrivateStoreService`, `PrivateStore`, `CM_PRIVATE_STORE`, `SM_PRIVATE_STORE`, `SM_PRIVATE_STORE_NAME` | A store belongs to a `Player` object; it is shown as an icon/title over that player |
| Direct trade | `ExchangeService` | Not needed |

## 3. Mail

### 3.1 The idea: virtual correspondents

Every virtual trader already has a stable name (for example "Arion", shown in the broker). Let those names also work as **mail addresses**, and let
traders **write back**. The mail window becomes the way to talk to the market.

*How it plugs in (verify):* when a player sends a mail, `MailService` looks up the recipient by name and refuses unknown names. A small hook before that
lookup asks `BrokerSimulationService` "is this name one of my traders (of the sender's faction)?". If yes, the mail is handled by a **virtual mail desk**
instead of being delivered to a character. Replies are sent as system mail through `SystemMailService`, with the trader's name as sender.

### 3.2 Phases (each one is useful on its own)

**M0 - Notifications (smallest step, no parsing).** Trader mails that tell an offline player what happened:
- "Your 40x Aether Powder sold for 12 400 kinah" (broker sale receipts, also useful because the settle icon is easy to miss),
- "A buyer is asking for X at up to N kinah" (a visible buy order),
- "Your listing expired".
Needs only `SystemMailService` plus a hook where sales and expiries already happen (`BrokerService.putToSettled`, the order matching).

**M1 - Price quotes and watches.** Player mails any trader with a subject like `PRICE <item name>` (or attaches one sample item).
The trader replies with the current market range and best open order. `WATCH <item>` subscribes the player to a weekly mail with the price trend.
Pure information, no economy impact, and it replaces the missing tooltip price.

**M2 - Commissions ("get me that item").** The answer to "ask a seller to put a specific item on sale".
1. The player mails a **specialist** trader (the role matters: only the weapon trader takes weapon orders, only cosmetic traders take costumes, and so on).
   Subject: `ORDER <item name>`. Attachments: **tribute**, not Kinah.
2. The desk checks (a) the item is in that role's catalog, (b) the faction is right, (c) the player has fewer than N open commissions, (d) the tribute is worth
   enough.
3. If the tribute is too small or wrong, the trader mails everything back with a hint ("bring me 30 more iron ore"). Nothing is lost.
4. If accepted, the tribute is consumed and the trader answers "I will have it in a few days". After a **random delay** (role dependent, for example
   30 minutes to 6 hours for general goods, 6 to 48 hours for cosmetics) the item appears in the broker, listed by that trader at the normal market price, and
   a mail tells the player. The player still pays Kinah at the broker; the tribute only **unlocks** the order.
5. Optional: hold the listing for the player for 24 hours (verify how to restrict a listing to one buyer; otherwise list it with a short head start by
   not counting it against the repeat cap).

What counts as tribute (keeps it fair and uses the gathering game): a TSV file `commission-tributes.tsv` per role listing accepted items and a value
weight, for example ore and herbs for armor and weapons, wood and stone for furniture, thread and dye for costumes. Required value is a percentage of
the target item's market price (default 60 %), counted with the vendor-price value of the tribute items.

Persistence: pending commissions must survive a restart. Choices: a small flat file (JSON lines) next to the config (no schema change, simplest) or a new
table (cleaner, needs SQL). Recommendation: start with the file.

**M3 - Contracts on the notice board.** The traders mail the player (system mail, a few per day at most, only to players who opted in or were active
recently) with a request: "I need 40 Mithril Ore, I pay 90 kinah each, until Friday". The player answers by **mailing the items back** (reply mail with
attachment); the desk pays Kinah by mail. This is the buy-order engine again, but visible and personal, and it gives gatherers something to do offline.
Payments count against the same purchase cap.

**M4 - Direct purchase by mail.** `BUY <item> x5` with Kinah attached; the trader sends the goods after a delay, at market price plus a markup (a sink).
Handy for specialist cosmetics. If the mail system has cash-on-delivery letters *(verify)*, use them instead: the trader sends the item with a COD price.

**M5 - Quick sale by mail.** Attach one item to a mail to a trader (subject `SELL`). After a delay the trader mails back Kinah at a fixed share of the
market price (for example 70 %). One step, works offline, no listing to manage. Reject enchanted, soulbound and unknown-value items; daily cap per player.

**M6 - Loyalty.** Purchases and commissions per trader build a small reputation. Reputation unlocks commissions, discounts, rarer stock and thank-you mails.
Needs per-player storage (file or table).

**M7 - Flavor mail.** Short, occasional mails signed by traders: market rumours generated from the real price model ("ore is getting expensive"), event hints,
quest hooks. Cheap, makes the world feel alive; opt-in and a daily cap keep the mailbox clean.

### 3.3 Technical outline

- `VirtualMailDesk` (new class): `boolean tryHandle(Player sender, String recipient, String subject, String body, List<Item> attachments, long kinah)`.
  Returns true if the mail was consumed. Called from one hook in `MailService` *(verify exact place and signature)*.
- Command parsing: first word of the subject (`ORDER`, `PRICE`, `WATCH`, `BUY`) plus an item name matched against the active catalog
  (exact, then case-insensitive, then "starts with"). If several items match, reply with the first few candidates. Names with typos get one retry mail.
- Replies: templates in `config/broker-simulation/mail-templates.properties` (so servers can translate them), sent with a random delay through
  `SystemMailService`.
- Limits (all configurable): open commissions per player, mails per player per hour, tribute minimum, markup, daily Kinah paid out by contracts.
- Logging: every accepted commission and payout is logged.

### 3.4 Things to read first *(files I need)*

`MailService.java`, `SystemMailService.java`, the client packet that sends a mail (`CM_SEND_MAIL` or similar), `Mailbox.java`/`Letter.java`,
`MailDAO.java`. Questions: where is the recipient checked, can system mail use any sender name, are COD letters supported, how are attachments taken
from the sender (so a consumed tribute can be removed safely).

### 3.5 Comparison table: Mail ideas

Difficulty: 1 = trivial ... 5 = very hard. Effort is a rough guess for focused work **after** the needed source files have been read.

| # | Idea | Pros | Cons | Limitations / must be verified | Difficulty (effort) |
|---|---|---|---|---|---|
| M0 | Notifications (sale receipts, expiry, "a buyer wants X") | Tiny; works offline; fixes the easy-to-miss settle icon; proves the mail hook | Mailbox clutter unless batched into a digest | `SystemMailService` must allow a custom sender name and text; mailbox size limit | 1-2 (about 1 day) |
| M1 | Price quote and price watch (`PRICE <item>`, `WATCH <item>`) | Replaces the impossible tooltip price; no economy impact; very safe | Needs item-name matching; small feature on its own | `MailService` rejects unknown recipient names, so a hook before that check is needed; name matching depends on the server language | 3 (2-3 days, mostly the hook) |
| M2 | Commissions: mail a specialist, pay with gathered materials, item appears in the broker later | Gives mail and gathering a real purpose; a material sink; finds rare items; fully offline; fits the specialist traders | Most complex; attachment handling must be exploit-proof; pending orders must survive restarts; needs balancing | Safe removal of attachments; tribute list per role; whether a listing can be reserved for one buyer; persistence (file or table) | 4 (1-2 weeks) |
| M3 | Contracts: traders ask for items, player mails them back and gets paid | Income for gatherers while offline; lots of natural variety | Kinah faucet; can feel spammy; needs reply matching | System mail volume; opt-in needed; linking a reply mail to a contract | 4 (1-2 weeks) |
| M4 | Direct purchase by mail (`BUY <item> xN` plus Kinah) | Buy specialist items without the broker; markup works as a Kinah sink | Bypasses broker listings and the repeat cap; refunds and failed orders need handling | Whether cash-on-delivery letters exist; refund path | 3-4 (about 1 week) |
| M5 | Quick sale by mail (`SELL`, trader pays about 70 % of market) | One step; offline friendly; simple rules | Kinah faucet; easy to dump gear for Kinah; undervalues items | Only items with a known market price; reject enchanted and soulbound; daily cap | 3 (3-5 days) |
| M6 | Loyalty and reputation per trader | Ties everything together; retention for solo players | Needs per-player persistent data; design and balance work | A table or file; hooks on broker purchases | 4 (1-2 weeks) |
| M7 | Flavor mail (rumours, hints, newsletters) | Cheap; uses real market data; makes the world feel alive | Pure flavor; text to write and translate; spam risk | Opt-in and daily cap; `SystemMailService` | 1-2 (1-2 days) |

**Suggested order:** M0 -> M1 -> M2 -> M5 -> M3 -> M6 -> M4 -> M7 (M7 can slip in anywhere).

## 4. Private Store without bots

The emulator's private store is attached to a `Player` object and the client expects a player to display the store title. Making virtual players would
mean fake sessions, spawning, and packets for characters that do not exist: the "bots walking around" route, a lot of risk. These options avoid it.

| # | Idea | What the player sees | Pros | Cons | Limitations / must be verified | Difficulty (effort) |
|---|---|---|---|---|---|---|
| P1 | Market stall NPCs: merchant NPCs whose shop window shows a trader's **current listings** at market prices | Stalls in the cities; buying works like in any NPC shop | Visible market in the world; uses the normal shop window; reuses the broker listings and prices | Static NPC shop data may not support a live goods list; stock must stay in sync with the broker (no double sales) | Dynamic goods-list API of the NPC shop system; runtime or fixed spawns; dialog handling | 4 (1-2 weeks) |
| P2 | Static stall NPCs: same NPCs, **fixed stock** rotated on a schedule (data files plus a timer) | Stalls with changing stock | Simplest way to get visible stalls; mostly data work | Not connected to the market model or broker; stock chosen by hand or script | Reloading shop lists at runtime (or a restart); client-known items only | 2-3 (2-4 days) |
| P3 | Offline store: when a player with an open private store logs out, the store becomes broker-style listings at the store prices (fee-free, held a few days) that virtual buyers can buy; unsold items are returned | "I can sell while offline" without any bot | Solves the biggest solo/offline problem; reuses broker and buyers | Items must leave the store safely and come back exactly once; many logout and login edge cases | `PrivateStoreService`, `PrivateStore`, logout path, item lock state | 4 (1-2 weeks) |
| P4 | Traveling merchant: an NPC spawns in a city for a few hours with special stock, announced by a system message or mail | Events that come and go | Liveliness at almost no load; good for cosmetics and rare goods | Needs the spawn system; stock still needs P1 or P2 | Runtime spawn and despawn timers; announcement packet | 3 (3-5 days) |
| P5 | Buyer NPC / wanted board: an NPC that buys your items at a market-based price and shows what buyers currently want | Instant sale without the broker | Uses the order book; very offline friendly | Kinah faucet; needs a custom price in the NPC sell window | The NPC sell packets and price calculation | 4-5 (2+ weeks) |
| P6 | Named stalls ("Arion's Stall") | Immersion | Cheap once P1 or P2 exists | NPC names usually come from client files, so it may be impossible | Whether a custom name or title can be sent for an NPC | 2, possibly impossible |
| P7 | Virtual players standing in store mode | Looks exactly like real players | None that justify the cost | Fake sessions, packets, persistence; high risk of crashes | Not recommended | 5 |

**Suggested order:** M0-M2 (mail desk) -> P2 (cheap visible stalls) -> P1 -> P3 -> P4 -> P5.

## 5. Open questions for you

1. Should commissions be possible for **any** catalog item or only for items a trader does not currently stock (so the feature is a real "find rare
   things" tool)?
2. How slow should specialists be? (Suggestion: general 30 min - 6 h, armor and weapons 2 - 12 h, furniture 6 - 24 h, cosmetics 6 - 48 h.)
3. Tribute: raw gatherables only, or crafted intermediates too?
4. Do you want contracts (M3) pushed to the player, or only available when the player asks (`CONTRACT` mail)?
5. Language of the mail templates (English only, or a template file per language)?

## 6. Risks

- **Exploits through mail attachments.** Every consumed attachment must be removed and saved in one step; test with item stacks, enchanted and soulbound items
  (reject those).
- **Economy drift.** Contracts and orders create Kinah. Keep the shared purchase cap and a daily payout cap, and prefer item sinks (tribute) over Kinah sinks.
- **Upgrades.** Hooks into `MailService` and `PrivateStoreService` touch more upstream files than the broker patch does. Keep each hook to a few lines and
  list it in `NOTICE.md`.
