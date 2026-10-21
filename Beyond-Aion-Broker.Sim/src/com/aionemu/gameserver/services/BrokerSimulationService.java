// Part of aion-living-broker. Licensed under the GNU General Public License v3.0 (see LICENSE), like the emulator it extends.
package com.aionemu.gameserver.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.broker.BrokerRace;
import com.aionemu.gameserver.model.gameobjects.BrokerItem;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.ItemType;
import com.aionemu.gameserver.services.item.ItemFactory;

/**
 * Keeps the stock Broker (the one opened at broker NPCs) populated with a small, cheap-to-run population of virtual sellers.
 * <p>
 * Design rules:
 * <ul>
 * <li>Listings are in-memory only. Nothing about a simulated seller is ever written to the DB. Only the item a real player buys is persisted
 * (as part of that player's inventory), exactly like a normal broker purchase.</li>
 * <li>Simulated sellers use ids in [{@link #SELLER_ID_MIN}, Integer.MAX_VALUE], far above any real object id.</li>
 * <li>Work per tick is bounded ({@code tradersServicedPerTick} traders, at most 2 new listings each). Default population is 40 traders per race.</li>
 * <li>The item catalog is built from the server's own item templates (tradeable, not soulbound, not timed, vendor price > 0) or from a TSV file.</li>
 * <li>Kinah paid to a simulated seller is simply removed from the economy (a sink). Items are created from nothing (a faucet).</li>
 * </ul>
 */
public final class BrokerSimulationService {

	private static final Logger log = LoggerFactory.getLogger(BrokerSimulationService.class);

	/** Seller ids at or above this value belong to virtual traders. */
	public static final int SELLER_ID_MIN = Integer.MAX_VALUE - 10_000;

	private static final Path CONFIG_FILE = Paths.get("config", "broker-simulation", "broker-simulation.properties");
	private static final Path CATALOG_FILE = Paths.get("config", "broker-simulation", "broker-simulation-items.tsv");
	private static final Path DUMP_FILE = Paths.get("config", "broker-simulation", "broker-simulation-catalog.txt");
	private static final Path RACE_AUDIT_FILE = Paths.get("config", "broker-simulation", "broker-simulation-race-audit.txt");
	private static final Path GROUPS_FILE = Paths.get("config", "broker-simulation", "broker-simulation-groups.txt");
	private static final Path BLOCKED_FILE = Paths.get("config", "broker-simulation", "broker-simulation-blocked.txt");

	private static final long MAX_UNIT_PRICE = 999_999_999L; // retail broker limit used by BrokerService.registerItem
	private static final long MAX_TOTAL_PRICE = 99_999_999_999L;

	private static final BrokerSimulationService INSTANCE = new BrokerSimulationService();

	public static boolean isSimulatedSeller(int sellerId) {
		return sellerId >= SELLER_ID_MIN;
	}

	private static final String[] NAME_START = { "Ar", "Bel", "Cor", "Dar", "El", "Fen", "Gal", "Hel", "Ith", "Kor", "Lan", "Mar", "Nor", "Or", "Pyr", "Ren",
		"Sol", "Tar", "Ul", "Vel" };
	private static final String[] NAME_END = { "ion", "as", "eth", "iel", "or", "wyn", "an", "ar", "os", "ith" };

	/** Deterministic display name for a virtual seller (shown in the broker list). Unique for up to 200 traders. */
	public static String getTraderName(int sellerId) {
		int idx = sellerId - SELLER_ID_MIN;
		return NAME_START[idx % NAME_START.length] + NAME_END[(idx / NAME_START.length) % NAME_END.length];
	}

	/**
	 * Price range shown in the broker's sell window for an item. Real listings win; when nobody lists the item, the market estimate is shown
	 * (low = 0.85 x market price, high = market price + tolerance). If virtual buyers have an open order for the item, the high end is at
	 * least that order price (= "this is what you can sell it for right now").
	 *
	 * @param low  lowest current listing price (0 = none)
	 * @param high highest current listing price (0 = none)
	 * @return {low, high}; unchanged if the simulation is off
	 */
	public static long[] getSellWindowRange(Race race, int itemId, long low, long high) {
		return INSTANCE.sellWindowRange(race, itemId, low, high);
	}

	private long[] sellWindowRange(Race race, int itemId, long low, long high) {
		if (!active)
			return new long[] { low, high };
		double m = marketPrice(itemId);
		if (low == 0 && high == 0 && m > 0) {
			low = Math.max(1, Math.round(m * 0.85));
			high = Math.max(low, Math.round(m * (1.0 + buyerTolerancePercent / 100.0)));
		}
		Long order = bestOrder.get(orderKey(itemId, race));
		if (order != null) {
			high = Math.max(high, order);
			if (low == 0)
				low = Math.min(order, high);
		}
		return new long[] { low, high };
	}

	/** Called by the broker after a player registered a listing: virtual buyers with a matching open order take it within a few seconds. */
	public static void onPlayerListing() {
		INSTANCE.triggerMatch();
	}

	private void triggerMatch() {
		if (active && ordersEnabled)
			executor.schedule(this::matchNowSafely, 2, TimeUnit.SECONDS);
	}

	/** Call once from the BrokerService constructor. Starts after a delay so BrokerService.getInstance() is fully initialised. */
	public static void scheduleStart() {
		INSTANCE.executor.schedule(INSTANCE::startSafely, 15, TimeUnit.SECONDS);
	}

	// ------------------------------------------------------------------------------------------------------------------------------

	/** @param race PC_ALL = both factions may sell it, otherwise only traders of that race */
	private record CatalogEntry(int itemId, long unitPrice, int minStack, int maxStack, boolean stackable, Race race) {}

	private static final class Trader {

		final int sellerId;
		final Race race;
		final BrokerRace brokerRace;
		final int targetListings;
		double priceFactor; // this trader's personality: cheap (0.85) ... greedy (1.2)
		final Map<Integer, long[]> listings = new LinkedHashMap<>(); // item unique id -> {expiry millis, item template id}

		Trader(int sellerId, Race race, BrokerRace brokerRace, int targetListings, double priceFactor) {
			this.sellerId = sellerId;
			this.race = race;
			this.brokerRace = brokerRace;
			this.targetListings = targetListings;
			this.priceFactor = priceFactor;
		}
	}

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "BrokerSimulation");
		t.setDaemon(true);
		return t;
	});
	private final Random rnd = new Random();
	private final List<Trader> traders = new ArrayList<>();
	private final List<CatalogEntry> catalog = new ArrayList<>();

	// settings (defaults; overridden by broker-simulation.properties)
	private boolean enabled = true;
	private int tradersPerRace = 40;
	private int minListings = 3;
	private int maxListings = 6; // broker hard cap is 15 per seller; keep well below
	private int tickSeconds = 120;
	private int opsPerTick = 4;
	private int lifetimeMinutes = 360;
	private double priceSpread = 0.10; // +-10 % random noise around base * trader factor
	// catalog
	private String catalogSource = "auto"; // auto | file | both
	private int catalogSize = 300;
	private long catalogSeed = 48;
	private int minItemLevel = 0;
	private int maxItemLevel = 65;
	private long minItemPrice = 1; // template (vendor) price
	private long maxItemPrice = 5_000_000;
	private int equipmentPercent = 25; // share of the catalog that is weapons/armor
	private double priceMultiplier = 4.0; // broker price = template price * multiplier * trader factor
	private int maxStackListed = 200;
	// items that must never be sold (NPC-only / test / admin items), matched as whole words in the item name, case-insensitive
	// built in, always applied: whole words (case-insensitive) that mark NPC-only / test / admin / developer items
	private static final String DEFAULT_BLOCKED_WORDS = "npc,test,debug,dummy,gm,admin,dev,developer,placeholder,unused,sample,cheat,cbt,tbd,do not use,login stage";
	private String extraBlockedWords = ""; // from the config file, added to the built-in list
	private boolean blockUnderscoreNames = true; // internal names like "Nohohon_05_A" (real item names never contain '_')
	private final Set<Integer> blockedItemIds = new HashSet<>();
	private volatile boolean active; // true once the simulation has started, used by the price estimate
	private Pattern blockedNames;
	private boolean dumpCatalog = true;
	// armor / weapons / accessories / housing: virtual traders only deal in expensive items there
	private long highValueMinPrice = 100_000;
	private String highValueGroupPattern = "(?i)HOUS|DECO|FURNIT"; // matched against the item group (enum) name, to find housing items
	private Pattern highValueGroups;
	private final Map<Integer, Boolean> highValueCache = new ConcurrentHashMap<>();
	private final Map<String, long[]> groupStats = new java.util.TreeMap<>(); // group -> {count, min vendor price, max vendor price}
	private int excludedLowValue;
	// virtual buyers (buy real players' listings)
	private boolean buyersEnabled = true;
	private int buyerTolerancePercent = 20; // buys listings priced up to this much above the fair price
	private int buyerMaxPurchases = 10; // per window
	private int buyerWindowMinutes = 30;
	private int buyerCheckSeconds = 60;
	private int buyerMinDelayMinutes = 5; // a listing waits this long (random) before a buyer shows interest
	private int buyerMaxDelayMinutes = 90;
	private int buyerMaxStackPercent = 50; // one purchase takes 1..N % of a splittable stack
	private final Deque<Long> purchaseTimes = new ArrayDeque<>();
	private final Map<Integer, Long> firstSeen = new HashMap<>(); // listing id -> when buyers first noticed it (or last bought from it)
	private final Map<Integer, Long> baseDelay = new HashMap<>(); // listing id -> its random base waiting time
	// buy orders of virtual buyers
	private boolean ordersEnabled = true;
	private int maxOrders = 120;
	private int ordersPerTick = 3;
	private int orderMinHours = 2;
	private int orderMaxHours = 12;
	private final List<Order> orders = new ArrayList<>();
	private final Map<Long, Long> bestOrder = new ConcurrentHashMap<>(); // (item, faction) -> highest open order price per piece
	// market model: each item has a moving price inside [floor, ceiling] % of its reference price
	private int marketFloorPercent = 20;
	private int marketCeilingPercent = 250;
	private double marketVolatilityPercent = 1.5; // random move per buyer tick
	private double marketReversionPercent = 1.0; // pull back to the reference price per buyer tick
	private double buyBumpPercent = 2.0; // price rises by this much after a sale
	private double unsoldDropPercent = 1.0; // price falls by this much when a trader's listing expires unsold
	private final Map<Integer, Double> refPrice = new ConcurrentHashMap<>();
	private final Map<Integer, Double> market = new ConcurrentHashMap<>();

	private final List<String> blockedLines = new ArrayList<>();

	private BrokerService broker;

	private BrokerSimulationService() {}

	private void startSafely() {
		try {
			start();
		} catch (Throwable t) {
			log.error("Broker simulation failed to start", t);
		}
	}

	private void start() {
		loadSettings();
		if (!enabled) {
			log.info("Broker simulation disabled.");
			return;
		}
		broker = BrokerService.getInstance();
		rebuildBlockedPattern();
		try {
			highValueGroups = highValueGroupPattern.isBlank() ? null : Pattern.compile(highValueGroupPattern);
		} catch (RuntimeException e) {
			log.warn("Broker simulation: invalid highValueGroupPattern, housing items are not detected", e);
		}
		active = true;
		if (buyersEnabled) {
			executor.scheduleWithFixedDelay(this::buyerTickSafely, buyerCheckSeconds, buyerCheckSeconds, TimeUnit.SECONDS);
			log.info("Broker simulation buyers active: max {} purchases per {} min, up to +{}% over fair price.", buyerMaxPurchases, buyerWindowMinutes,
				buyerTolerancePercent);
		}
		catalog.clear();
		String src = catalogSource.toLowerCase(Locale.ROOT);
		if (src.equals("auto") || src.equals("both"))
			loadAutoCatalog();
		if (src.equals("file") || src.equals("both"))
			loadFileCatalog();
		if (catalog.isEmpty()) {
			log.warn("Broker simulation: catalog is empty (catalogSource={}), nothing to list.", catalogSource);
			return;
		}

		int nextId = SELLER_ID_MIN;
		for (Race race : new Race[] { Race.ELYOS, Race.ASMODIANS }) {
			BrokerRace brokerRace = race == Race.ELYOS ? BrokerRace.ELYOS : BrokerRace.ASMODIAN;
			for (int i = 0; i < tradersPerRace; i++) {
				int target = minListings + rnd.nextInt(Math.max(1, maxListings - minListings + 1));
				double factor = 0.85 + rnd.nextDouble() * 0.35;
				traders.add(new Trader(nextId++, race, brokerRace, target, factor));
			}
		}

		int total = 0;
		for (Trader t : traders) {
			int failures = 0;
			while (t.listings.size() < t.targetListings && failures < 10) {
				if (listOne(t))
					total++;
				else
					failures++;
			}
		}
		log.info("Broker simulation started: {} traders, {} listings, {} catalog items.", traders.size(), total, catalog.size());

		executor.scheduleWithFixedDelay(this::tickSafely, tickSeconds, tickSeconds, TimeUnit.SECONDS);
	}

	private void tickSafely() {
		try { // an uncaught exception would silently cancel a scheduled task
			for (int i = 0; i < opsPerTick; i++)
				serviceTrader(traders.get(rnd.nextInt(traders.size())));
		} catch (Throwable t) {
			log.error("Broker simulation tick failed", t);
		}
	}

	private void serviceTrader(Trader t) {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, long[]>> it = t.listings.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, long[]> e = it.next();
			int itemId = (int) e.getValue()[1];
			if (!broker.isListed(t.race, e.getKey())) {
				moveMarket(itemId, 1.0 + buyBumpPercent / 100.0); // bought by a player: demand
				it.remove();
			} else if (e.getValue()[0] <= now) {
				broker.removeSimulatedListing(t.race, e.getKey()); // expired -> relisted below at a fresh price
				moveMarket(itemId, 1.0 - unsoldDropPercent / 100.0); // nobody wanted it: price drifts down
				it.remove();
			}
		}
		t.priceFactor = Math.max(0.80, Math.min(1.25, t.priceFactor + (rnd.nextDouble() - 0.5) * 0.04)); // slow random walk
		for (int created = 0; created < 2 && t.listings.size() < t.targetListings; created++)
			if (!listOne(t))
				break;
	}

	private boolean listOne(Trader t) {
		CatalogEntry e = null;
		for (int attempt = 0; attempt < 20 && e == null; attempt++) { // pick an entry this faction may sell
			CatalogEntry c = catalog.get(rnd.nextInt(catalog.size()));
			if (c.race() == Race.PC_ALL || c.race() == t.race)
				e = c;
		}
		if (e == null)
			return false;

		long count = e.stackable() ? e.minStack() + rnd.nextInt(Math.max(1, e.maxStack() - e.minStack() + 1)) : 1;
		double noise = 1.0 + (rnd.nextDouble() * 2 - 1) * priceSpread;
		double base = marketPrice(e.itemId());
		if (base <= 0)
			base = e.unitPrice();
		long unit = Math.max(1, Math.round(base * t.priceFactor * noise));
		if (unit > MAX_UNIT_PRICE || unit * count > MAX_TOTAL_PRICE)
			return false;

		Item item = ItemFactory.newItem(e.itemId(), count);
		if (item == null)
			return false;
		item.setItemLocation(StorageType.BROKER.getId());
		BrokerItem listing = new BrokerItem(item, unit, t.sellerId, e.stackable(), t.brokerRace);
		broker.addSimulatedListing(listing);

		double jitter = 0.5 + rnd.nextDouble(); // 50 %..150 % of the configured lifetime
		t.listings.put(listing.getItemUniqueId(),
			new long[] { System.currentTimeMillis() + (long) (lifetimeMinutes * 60_000L * jitter), e.itemId() });
		return true;
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// market model: every item has a moving price that stays inside [floor, ceiling] % of its reference price (vendor price x multiplier)

	private double referencePrice(int itemId) {
		Double r = refPrice.get(itemId);
		if (r != null)
			return r;
		ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if (t == null || t.getPrice() <= 0 || isBlockedTemplate(t))
			return 0;
		double v = t.getPrice() * priceMultiplier;
		if (isHighValueCategory(t) && v < highValueMinPrice)
			return 0; // armor / weapons / accessories / housing below the minimum are not part of the simulated market
		refPrice.put(itemId, v);
		return v;
	}

	/** Current market price per piece; 0 if the item has no known value. */
	private double marketPrice(int itemId) {
		Double m = market.get(itemId);
		if (m != null)
			return m;
		double r = referencePrice(itemId);
		if (r > 0)
			market.put(itemId, r);
		return r;
	}

	private double clampToBand(int itemId, double price) {
		double r = referencePrice(itemId);
		double floor = r * marketFloorPercent / 100.0;
		if (isHighValueItem(itemId))
			floor = Math.max(floor, highValueMinPrice); // never cheaper than the minimum in these categories
		return Math.max(floor, Math.min(Math.max(floor, r * marketCeilingPercent / 100.0), price));
	}

	private void moveMarket(int itemId, double factor) {
		if (referencePrice(itemId) > 0)
			market.put(itemId, clampToBand(itemId, marketPrice(itemId) * factor));
	}

	private void updateMarket() {
		for (Integer itemId : new ArrayList<>(market.keySet())) {
			double r = referencePrice(itemId);
			if (r <= 0)
				continue;
			double m = market.get(itemId);
			m += (r - m) * marketReversionPercent / 100.0 + m * rnd.nextGaussian() * marketVolatilityPercent / 100.0;
			market.put(itemId, clampToBand(itemId, m));
		}
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// virtual buyers: buy orders (immediate) + passive demand (speed depends on how cheap the listing is)

	private static final class Order {

		final int itemId;
		final Race race;
		final long maxUnit; // price per piece the buyer pays at most
		long remaining;
		final long expiresAt;

		Order(int itemId, Race race, long maxUnit, long remaining, long expiresAt) {
			this.itemId = itemId;
			this.race = race;
			this.maxUnit = maxUnit;
			this.remaining = remaining;
			this.expiresAt = expiresAt;
		}
	}

	private static long orderKey(int itemId, Race race) {
		return ((long) itemId << 1) | (race == Race.ELYOS ? 0 : 1);
	}

	private static Race raceOf(BrokerItem b) {
		return b.getItemBrokerRace() == BrokerRace.ELYOS ? Race.ELYOS : Race.ASMODIANS;
	}

	private void buyerTickSafely() {
		try {
			buyerTick();
		} catch (Throwable t) {
			log.error("Broker simulation buyer tick failed", t);
		}
	}

	private void matchNowSafely() {
		try {
			long now = System.currentTimeMillis();
			pruneWindow(now);
			matchOrders(now, collectListings(now));
		} catch (Throwable t) {
			log.error("Broker simulation order matching failed", t);
		}
	}

	private long randomDelayMs() {
		long min = buyerMinDelayMinutes * 60_000L;
		long max = Math.max(min, buyerMaxDelayMinutes * 60_000L);
		return min + (long) (rnd.nextDouble() * (max - min));
	}

	private void pruneWindow(long now) {
		long windowMs = buyerWindowMinutes * 60_000L;
		while (!purchaseTimes.isEmpty() && purchaseTimes.peekFirst() <= now - windowMs)
			purchaseTimes.pollFirst();
	}

	/** All real players' listings of both factions, tracked for the waiting-time logic. */
	private List<BrokerItem> collectListings(long now) {
		List<BrokerItem> listings = new ArrayList<>();
		listings.addAll(broker.getPlayerListings(Race.ELYOS));
		listings.addAll(broker.getPlayerListings(Race.ASMODIANS));
		Set<Integer> present = new HashSet<>();
		for (BrokerItem b : listings) {
			int id = b.getItemUniqueId();
			present.add(id);
			firstSeen.putIfAbsent(id, now);
			baseDelay.computeIfAbsent(id, k -> randomDelayMs());
		}
		firstSeen.keySet().retainAll(present); // forget sold / cancelled / expired listings
		baseDelay.keySet().retainAll(present);
		return listings;
	}

	private void buyerTick() {
		long now = System.currentTimeMillis();
		pruneWindow(now);
		updateMarket();
		List<BrokerItem> listings = collectListings(now);
		if (ordersEnabled) {
			refreshOrders(now, listings);
			matchOrders(now, listings);
		}
		passiveBuying(now, listings);
	}

	// ---- buy orders ----

	private void refreshOrders(long now, List<BrokerItem> listings) {
		orders.removeIf(o -> o.remaining <= 0 || o.expiresAt <= now);
		for (int i = 0; i < ordersPerTick && orders.size() < maxOrders; i++) {
			int itemId;
			Race race;
			if (!listings.isEmpty() && (catalog.isEmpty() || rnd.nextInt(100) < 40)) { // 40 %: demand for something a player actually sells
				BrokerItem b = listings.get(rnd.nextInt(listings.size()));
				itemId = b.getItemId();
				race = raceOf(b);
			} else if (!catalog.isEmpty()) {
				CatalogEntry c = catalog.get(rnd.nextInt(catalog.size()));
				itemId = c.itemId();
				race = c.race() == Race.PC_ALL ? (rnd.nextBoolean() ? Race.ELYOS : Race.ASMODIANS) : c.race(); // faction items only get orders of their faction
			} else
				break;
			double m = marketPrice(itemId);
			ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(itemId);
			if (m <= 0 || t == null)
				continue;
			long maxUnit = Math.max(1, Math.round(clampToBand(itemId, m * (0.80 + rnd.nextDouble() * 0.35))));
			long qty = t.isStackable() ? 1 + rnd.nextInt((int) Math.max(1, Math.min(t.getMaxStackCount(), maxStackListed))) : 1;
			long hours = orderMinHours + (long) (rnd.nextDouble() * Math.max(0, orderMaxHours - orderMinHours));
			orders.add(new Order(itemId, race, maxUnit, qty, now + hours * 3_600_000L));
		}
		rebuildBestOrders();
	}

	private void rebuildBestOrders() {
		Map<Long, Long> best = new HashMap<>();
		for (Order o : orders)
			if (o.remaining > 0)
				best.merge(orderKey(o.itemId, o.race), o.maxUnit, Math::max);
		bestOrder.keySet().retainAll(best.keySet());
		bestOrder.putAll(best);
	}

	/** Cheapest listings first: a listing priced at or below an open order's price is sold to that order. */
	private void matchOrders(long now, List<BrokerItem> listings) {
		if (!ordersEnabled || orders.isEmpty())
			return;
		List<BrokerItem> byPrice = new ArrayList<>(listings);
		byPrice.sort(Comparator.comparingLong(BrokerItem::getPrice));
		for (BrokerItem b : byPrice) {
			if (purchaseTimes.size() >= buyerMaxPurchases)
				break;
			Race race = raceOf(b);
			Order best = null;
			for (Order o : orders)
				if (o.remaining > 0 && o.race == race && o.itemId == b.getItemId() && o.maxUnit >= b.getPrice() && (best == null || o.maxUnit > best.maxUnit))
					best = o;
			if (best == null)
				continue;
			long listed = b.getItemCount();
			long qty = Math.min(best.remaining, listed);
			if (qty <= 0 || qty < listed && !b.isSplittingAvailable())
				continue; // "don't split" listings can only be sold whole
			if (broker.sellToSimulatedBuyer(race, b.getItemUniqueId(), qty)) {
				best.remaining -= qty;
				purchaseTimes.addLast(now);
				moveMarket(b.getItemId(), 1.0 + buyBumpPercent / 100.0);
				firstSeen.put(b.getItemUniqueId(), now);
				baseDelay.put(b.getItemUniqueId(), randomDelayMs());
			}
		}
		orders.removeIf(o -> o.remaining <= 0);
		rebuildBestOrders();
	}

	// ---- passive demand ----

	/** How much the waiting time shrinks the cheaper a listing is compared with the current lowest price of that item. */
	private static double speedFactor(long price, long currentLow) {
		double ratio = currentLow <= 0 ? 1.0 : (double) price / currentLow;
		if (ratio <= 1.0)
			return 0.15; // you ARE the current low
		if (ratio <= 1.10)
			return 0.40;
		if (ratio <= 1.25)
			return 0.75;
		return 1.0;
	}

	private void passiveBuying(long now, List<BrokerItem> listings) {
		if (!buyersEnabled || listings.isEmpty())
			return;
		Map<Integer, Long> lowElyos = broker.getLowestPrices(Race.ELYOS);
		Map<Integer, Long> lowAsmo = broker.getLowestPrices(Race.ASMODIANS);
		Map<BrokerItem, Double> factor = new HashMap<>();
		for (BrokerItem b : listings)
			factor.put(b, speedFactor(b.getPrice(), (raceOf(b) == Race.ELYOS ? lowElyos : lowAsmo).getOrDefault(b.getItemId(), 0L)));
		List<BrokerItem> sorted = new ArrayList<>(listings);
		sorted.sort(Comparator.comparingDouble(factor::get)); // best-priced listings get served first when the purchase cap is tight
		for (BrokerItem b : sorted) {
			if (purchaseTimes.size() >= buyerMaxPurchases)
				break;
			int id = b.getItemUniqueId();
			Long seen = firstSeen.get(id);
			Long delay = baseDelay.get(id);
			if (seen == null || delay == null || now < seen + (long) (delay * factor.get(b)) || !isFairlyPriced(b))
				continue;
			long count = pickQuantity(b);
			if (count > 0 && broker.sellToSimulatedBuyer(raceOf(b), id, count)) {
				purchaseTimes.addLast(now);
				moveMarket(b.getItemId(), 1.0 + buyBumpPercent / 100.0);
				firstSeen.put(id, now); // what is left of the stack gets its next visit later
				baseDelay.put(id, randomDelayMs());
			}
		}
	}

	private boolean isFairlyPriced(BrokerItem b) {
		double m = marketPrice(b.getItemId());
		if (m <= 0)
			return false;
		return b.getPrice() <= m * (1.0 + buyerTolerancePercent / 100.0); // BrokerItem price is per piece (the broker packets send price * count)
	}

	private long pickQuantity(BrokerItem b) {
		long count = b.getItemCount();
		if (count <= 1 || !b.isSplittingAvailable())
			return count; // single items and "don't split" listings can only be bought whole
		long max = Math.max(1, count * buyerMaxStackPercent / 100);
		return 1 + (long) (rnd.nextDouble() * max); // 1..max
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// catalog

	/** Armor, weapons, accessories (all three are ItemTemplate armor/weapon) and housing items (by item group name). */
	private boolean isHighValueCategory(ItemTemplate t) {
		return t.isWeapon() || t.isArmor() || highValueGroups != null && highValueGroups.matcher(t.getItemGroup().name()).find();
	}

	private boolean isHighValueItem(int itemId) {
		return highValueCache.computeIfAbsent(itemId, id -> {
			ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(id);
			return t != null && isHighValueCategory(t);
		});
	}

	private static final Pattern NAME_ASMODIAN = Pattern.compile("(?<![\\p{L}\\p{N}])asmodian(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAME_ELYOS = Pattern.compile("(?<![\\p{L}\\p{N}])elyos(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
	private final List<String> raceAuditLines = new ArrayList<>();

	/**
	 * The faction an item belongs to: the template's race, and for items without a race restriction the faction named in the item name.
	 *
	 * @return ELYOS, ASMODIANS, PC_ALL (both factions) or null if template and name contradict each other (item is then not sold at all)
	 */
	private Race effectiveRace(ItemTemplate t) {
		Race templateRace = t.getRace();
		boolean asmo = NAME_ASMODIAN.matcher(t.getName()).find();
		boolean ely = NAME_ELYOS.matcher(t.getName()).find();
		Race named = asmo == ely ? null : asmo ? Race.ASMODIANS : Race.ELYOS;
		if (templateRace == Race.PC_ALL) {
			if (asmo && ely) {
				raceAuditLines.add(t.getTemplateId() + "\t" + t.getName() + "\ttemplate PC_ALL, name mentions both -> excluded");
				return null;
			}
			if (named != null)
				raceAuditLines.add(t.getTemplateId() + "\t" + t.getName() + "\ttemplate PC_ALL, name says " + named + " -> locked to " + named);
			return named != null ? named : Race.PC_ALL;
		}
		if (named != null && named != templateRace) {
			raceAuditLines.add(t.getTemplateId() + "\t" + t.getName() + "\ttemplate " + templateRace + ", name says " + named + " -> excluded");
			return null;
		}
		return templateRace;
	}

	private void rebuildBlockedPattern() {
		List<String> parts = new ArrayList<>();
		for (String w : (DEFAULT_BLOCKED_WORDS + "," + extraBlockedWords).split(",")) {
			w = w.strip();
			if (!w.isEmpty())
				parts.add(Pattern.quote(w));
		}
		blockedNames = parts.isEmpty() ? null : Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + String.join("|", parts) + ")(?![\\p{L}\\p{N}])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	private boolean isBlockedName(String name) {
		return blockedNames != null && blockedNames.matcher(name).find();
	}

	/** NPC-only / test / admin / developer items that must never be sold or bought by the simulation. */
	private boolean isBlockedTemplate(ItemTemplate t) {
		String name = t.getName();
		return blockedItemIds.contains(t.getTemplateId()) || isBlockedName(name) || blockUnderscoreNames && name.indexOf('_') >= 0;
	}

	private boolean isEligible(ItemTemplate t) {
		if (t.getItemType() != ItemType.NORMAL || !t.isTradeable() || t.isSoulBound() || t.isKinah() || t.isStigma())
			return false;
		if (t.getExpireTime() != 0 || t.getTempExchangeTime() != 0 || t.getExtraInventoryId() != -1)
			return false; // timed items, temporarily-tradable items and special-inventory items
		String name = t.getName();
		if (name.isEmpty() || name.startsWith("[")) // [Event], [Stamp], [Legion] ... (same rule ItemData uses for manastones)
			return false;
		if (isBlockedTemplate(t)) { // "NPC Asmodian Abyss Polearm 02", "Delay Test Design 01", "Asmodian Login Stage ... 01", "Nohohon_05_A", ...
			blockedLines.add(t.getTemplateId() + "\t" + name + "\t" + t.getPrice());
			return false;
		}
		long price = t.getPrice();
		if (price < minItemPrice || price > maxItemPrice || t.getLevel() < minItemLevel || t.getLevel() > maxItemLevel)
			return false;
		groupStats.merge(t.getItemGroup().name(), new long[] { 1, price, price },
			(a, b) -> new long[] { a[0] + 1, Math.min(a[1], b[1]), Math.max(a[2], b[2]) });
		if (isHighValueCategory(t) && price * priceMultiplier < highValueMinPrice) {
			excludedLowValue++;
			return false; // only high value armor / weapons / accessories / housing
		}
		return effectiveRace(t) != null;
	}

	private void loadAutoCatalog() {
		List<ItemTemplate> general = new ArrayList<>();
		List<ItemTemplate> equipment = new ArrayList<>();
		blockedLines.clear();
		raceAuditLines.clear();
		groupStats.clear();
		excludedLowValue = 0;
		for (ItemTemplate t : DataManager.ITEM_DATA.getItemTemplates()) {
			if (!isEligible(t))
				continue;
			(t.isWeapon() || t.isArmor() ? equipment : general).add(t);
		}
		// deterministic order + seeded shuffle: same catalog after every restart unless catalogSeed changes
		Comparator<ItemTemplate> byId = Comparator.comparingInt(ItemTemplate::getTemplateId);
		general.sort(byId);
		equipment.sort(byId);
		Collections.shuffle(general, new Random(catalogSeed));
		Collections.shuffle(equipment, new Random(catalogSeed + 1));

		int equipCount = Math.min(equipment.size(), catalogSize * equipmentPercent / 100);
		int generalCount = Math.min(general.size(), catalogSize - equipCount);
		List<String> dump = new ArrayList<>();
		for (ItemTemplate t : general.subList(0, generalCount))
			addAuto(t, dump);
		for (ItemTemplate t : equipment.subList(0, equipCount))
			addAuto(t, dump);
		if (dumpCatalog) {
			writeLines(DUMP_FILE, "itemId\tname\tvendorPrice\tbrokerPrice\trace", dump);
			Collections.sort(blockedLines);
			writeLines(BLOCKED_FILE, "itemId\tname\tvendorPrice  (excluded as NPC / test / admin item)", blockedLines);
			Collections.sort(raceAuditLines);
			writeLines(RACE_AUDIT_FILE, "itemId\tname\tdecision  (items where the faction in the name and the template's race need a ruling)", raceAuditLines);
			List<String> groups = new ArrayList<>();
			for (Map.Entry<String, long[]> g : groupStats.entrySet())
				groups.add(g.getKey() + "\t" + g.getValue()[0] + "\t" + g.getValue()[1] + "\t" + g.getValue()[2]
					+ (highValueGroups != null && highValueGroups.matcher(g.getKey()).find() ? "\tHIGH-VALUE (housing pattern)" : ""));
			writeLines(GROUPS_FILE, "itemGroup\titems\tminVendorPrice\tmaxVendorPrice  (tradeable items after the name filter; weapons and armor are always high-value)", groups);
		}
		log.info("Broker simulation: {} items excluded by name (NPC/test/admin), {} armor/weapon/accessory/housing items below {} kinah, {} faction conflicts or hints (see race audit).",
			blockedLines.size(), excludedLowValue, highValueMinPrice, raceAuditLines.size());
		log.info("Broker simulation: {} eligible general and {} eligible equipment templates, using {} + {}.", general.size(), equipment.size(),
			generalCount, equipCount);
	}

	private void writeLines(Path file, String header, List<String> lines) {
		try {
			List<String> all = new ArrayList<>();
			all.add("# " + header);
			all.addAll(lines);
			Files.write(file, all);
		} catch (IOException e) {
			log.warn("Could not write {}", file, e);
		}
	}

	private void addAuto(ItemTemplate t, List<String> dump) {
		boolean stackable = t.isStackable();
		int max = stackable ? (int) Math.max(1, Math.min(t.getMaxStackCount(), maxStackListed)) : 1;
		long unit = Math.max(1, Math.round(t.getPrice() * priceMultiplier));
		Race race = effectiveRace(t);
		catalog.add(new CatalogEntry(t.getTemplateId(), unit, 1, max, stackable, race));
		refPrice.put(t.getTemplateId(), (double) unit);
		dump.add(t.getTemplateId() + "\t" + t.getName() + "\t" + t.getPrice() + "\t" + unit + "\t" + race);
	}

	private void loadFileCatalog() {
		if (!Files.exists(CATALOG_FILE))
			return;
		try {
			for (String line : Files.readAllLines(CATALOG_FILE)) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				String[] c = line.split("\\s+");
				if (c.length < 2)
					continue;
				try {
					int itemId = Integer.parseInt(c[0]);
					long price = Long.parseLong(c[1]);
					int min = c.length > 2 ? Integer.parseInt(c[2]) : 1;
					int max = c.length > 3 ? Integer.parseInt(c[3]) : min;
					if (price <= 0 || min < 1 || max < min) {
						log.warn("Broker simulation: skipping invalid line '{}'", line);
						continue;
					}
					ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(itemId);
					if (t == null) {
						log.warn("Broker simulation: unknown item id {}", itemId);
						continue;
					}
					Race fileRace = effectiveRace(t);
					if (fileRace == null) {
						log.warn("Broker simulation: item id {} has contradicting faction data, skipped", itemId);
						continue;
					}
					if (isHighValueCategory(t) && price < highValueMinPrice) {
						log.warn("Broker simulation: item id {} is armor/weapon/accessory/housing below {} kinah, skipped", itemId, highValueMinPrice);
						continue;
					}
					boolean stackable = t.isStackable();
					catalog.add(new CatalogEntry(itemId, price, stackable ? min : 1, stackable ? max : 1, stackable, fileRace));
					refPrice.put(itemId, (double) price);
				} catch (NumberFormatException ex) {
					log.warn("Broker simulation: skipping malformed line '{}'", line);
				}
			}
		} catch (IOException e) {
			log.warn("Could not read {}", CATALOG_FILE, e);
		}
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// settings

	private void loadSettings() {
		if (!Files.exists(CONFIG_FILE)) {
			log.warn("{} not found, using defaults.", CONFIG_FILE);
			return;
		}
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
			p.load(in);
		} catch (IOException e) {
			log.warn("Could not read {}, using defaults.", CONFIG_FILE, e);
			return;
		}
		enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
		tradersPerRace = clamp(intOf(p, "tradersPerRace", 40), 1, 100); // names are unique up to 100 per race
		minListings = clamp(intOf(p, "minListingsPerTrader", 3), 1, 14);
		maxListings = clamp(intOf(p, "maxListingsPerTrader", 6), minListings, 14);
		tickSeconds = clamp(intOf(p, "tickSeconds", 120), 10, 86_400);
		opsPerTick = clamp(intOf(p, "tradersServicedPerTick", 4), 1, 100);
		lifetimeMinutes = clamp(intOf(p, "listingLifetimeMinutes", 360), 5, 10_080);
		priceSpread = clamp(intOf(p, "priceSpreadPercent", 10), 0, 50) / 100.0;

		catalogSource = p.getProperty("catalogSource", "auto").trim();
		catalogSize = clamp(intOf(p, "catalogSize", 300), 10, 5000);
		catalogSeed = intOf(p, "catalogSeed", 48);
		minItemLevel = clamp(intOf(p, "minItemLevel", 0), 0, 100);
		maxItemLevel = clamp(intOf(p, "maxItemLevel", 65), minItemLevel, 100);
		minItemPrice = Math.max(1, intOf(p, "minItemPrice", 1));
		maxItemPrice = Math.max(minItemPrice, intOf(p, "maxItemPrice", 5_000_000));
		equipmentPercent = clamp(intOf(p, "equipmentPercent", 25), 0, 100);
		maxStackListed = clamp(intOf(p, "maxStackListed", 200), 1, 100_000);
		extraBlockedWords = p.getProperty("extraBlockedWords", "");
		blockUnderscoreNames = Boolean.parseBoolean(p.getProperty("blockUnderscoreNames", "true").trim());
		blockedItemIds.clear();
		for (String id : p.getProperty("blockedItemIds", "").split(",")) {
			try {
				if (!id.isBlank())
					blockedItemIds.add(Integer.parseInt(id.strip()));
			} catch (NumberFormatException e) {
				log.warn("Broker simulation: ignoring invalid blockedItemIds entry '{}'", id);
			}
		}
		dumpCatalog = Boolean.parseBoolean(p.getProperty("dumpCatalog", "true").trim());
		highValueMinPrice = Math.max(0, intOf(p, "highValueMinPrice", 100_000));
		highValueGroupPattern = p.getProperty("highValueGroupPattern", highValueGroupPattern).trim();

		buyersEnabled = Boolean.parseBoolean(p.getProperty("buyersEnabled", "true").trim());
		buyerTolerancePercent = clamp(intOf(p, "buyerTolerancePercent", 20), 0, 500);
		buyerMaxPurchases = clamp(intOf(p, "buyerMaxPurchases", 10), 1, 1000);
		buyerWindowMinutes = clamp(intOf(p, "buyerWindowMinutes", 30), 1, 1440);
		buyerCheckSeconds = clamp(intOf(p, "buyerCheckSeconds", 60), 10, 3600);
		buyerMinDelayMinutes = clamp(intOf(p, "buyerMinDelayMinutes", 5), 0, 1440);
		buyerMaxDelayMinutes = clamp(intOf(p, "buyerMaxDelayMinutes", 90), buyerMinDelayMinutes, 10_080);
		buyerMaxStackPercent = clamp(intOf(p, "buyerMaxStackPercent", 50), 1, 100);

		ordersEnabled = Boolean.parseBoolean(p.getProperty("ordersEnabled", "true").trim());
		maxOrders = clamp(intOf(p, "maxOrders", 120), 1, 5000);
		ordersPerTick = clamp(intOf(p, "ordersPerTick", 3), 1, 100);
		orderMinHours = clamp(intOf(p, "orderMinHours", 2), 1, 720);
		orderMaxHours = clamp(intOf(p, "orderMaxHours", 12), orderMinHours, 720);
		marketFloorPercent = clamp(intOf(p, "marketFloorPercent", 20), 1, 100);
		marketCeilingPercent = clamp(intOf(p, "marketCeilingPercent", 250), 100, 10_000);
		marketVolatilityPercent = doubleOf(p, "marketVolatilityPercent", 1.5, 0, 20);
		marketReversionPercent = doubleOf(p, "marketReversionPercent", 1.0, 0, 50);
		buyBumpPercent = doubleOf(p, "buyBumpPercent", 2.0, 0, 50);
		unsoldDropPercent = doubleOf(p, "unsoldDropPercent", 1.0, 0, 50);
		try {
			priceMultiplier = Math.max(0.01, Double.parseDouble(p.getProperty("priceMultiplier", "4.0").trim()));
		} catch (NumberFormatException e) {
			priceMultiplier = 4.0;
		}
	}

	private static int intOf(Properties p, String key, int def) {
		try {
			return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static double doubleOf(Properties p, String key, double def, double lo, double hi) {
		try {
			return Math.max(lo, Math.min(hi, Double.parseDouble(p.getProperty(key, String.valueOf(def)).trim())));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
