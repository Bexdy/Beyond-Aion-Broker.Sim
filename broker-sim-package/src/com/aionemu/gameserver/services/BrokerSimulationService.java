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
import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.broker.BrokerRace;
import com.aionemu.gameserver.model.broker.filter.BrokerFilter;
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
	private static final Path CATEGORIES_FILE = Paths.get("config", "broker-simulation", "broker-simulation-categories.txt");
	private static final Path COVERAGE_FILE = Paths.get("config", "broker-simulation", "broker-simulation-coverage.txt");
	private static final Path GROUPS_FILE = Paths.get("config", "broker-simulation", "broker-simulation-groups.txt");
	private static final Path BLOCKED_FILE = Paths.get("config", "broker-simulation", "broker-simulation-blocked.txt");

	private static final long MAX_UNIT_PRICE = 999_999_999L; // retail broker limit used by BrokerService.registerItem
	private static final long MAX_TOTAL_PRICE = 99_999_999_999L;

	private static final BrokerSimulationService INSTANCE = new BrokerSimulationService();

	public static boolean isSimulatedSeller(int sellerId) {
		return sellerId >= SELLER_ID_MIN;
	}

	private static final String[] NAME_START = { "Ar", "Bel", "Cor", "Dar", "El", "Fen", "Gal", "Hel", "Ith", "Kor", "Lan", "Mar", "Nor", "Or", "Pyr", "Ren",
		"Sol", "Tar", "Ul", "Vel", "Aer", "Bra", "Cal", "Dor", "Eri", "Fyn", "Gor", "Hal", "Jor", "Kai" };
	private static final String[] NAME_END = { "ion", "as", "eth", "iel", "or", "wyn", "an", "ar", "os", "ith", "ia", "us", "en", "ak", "is", "um", "ra", "on", "el",
		"ys" };

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
	private record CatalogEntry(int itemId, long unitPrice, int minStack, int maxStack, boolean stackable, Race race, String group) {}

	/** Specialisation of a trader: it only sells items of its own kind. */
	private enum Role {
		GENERAL, // consumables, materials, stones, ...
		FURNITURE, // housing
		ARMOR, // armor and accessories with real stats
		WEAPON,
		COSMETIC // level <= cosmeticMaxLevel armor / accessories / weapons (costumes and the like)
	}

	private static final class RoleCfg {

		int percent, minListings, maxListings, intervalMinMinutes, intervalMaxMinutes, lifetimeMinutes;
		int maxPerItem; // 0 = use the global maxListingsPerItem

		RoleCfg(int percent, int minListings, int maxListings, int intervalMinMinutes, int intervalMaxMinutes, int lifetimeMinutes) {
			this.percent = percent;
			this.minListings = minListings;
			this.maxListings = maxListings;
			this.intervalMinMinutes = intervalMinMinutes;
			this.intervalMaxMinutes = intervalMaxMinutes;
			this.lifetimeMinutes = lifetimeMinutes;
		}
	}

	private static final class Trader {

		final int sellerId;
		final Race race;
		final BrokerRace brokerRace;
		final Role role;
		final int targetListings;
		long nextActionAt; // every trader has its own random timetable
		double priceFactor; // this trader's personality: cheap (0.85) ... greedy (1.2)
		final Map<Integer, long[]> listings = new LinkedHashMap<>(); // item unique id -> {expiry millis, item template id}
		final Deque<Integer> recent = new ArrayDeque<>(); // last item ids this trader listed (not relisted right away)

		Trader(int sellerId, Race race, BrokerRace brokerRace, Role role, int targetListings, double priceFactor, long nextActionAt) {
			this.sellerId = sellerId;
			this.race = race;
			this.brokerRace = brokerRace;
			this.role = role;
			this.targetListings = targetListings;
			this.priceFactor = priceFactor;
			this.nextActionAt = nextActionAt;
		}
	}

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "BrokerSimulation");
		t.setDaemon(true);
		return t;
	});
	private final Random rnd = new Random();
	private final List<Trader> traders = new ArrayList<>();
	private final List<CatalogEntry> catalog = new ArrayList<>(); // all active entries (flat view, used by buy orders)
	private final Map<Role, List<CatalogEntry>> roleCatalog = new java.util.EnumMap<>(Role.class);
	private final Map<Role, List<ItemTemplate>> reserve = new java.util.EnumMap<>(Role.class); // eligible items not in the active catalog right now
	private final Map<Role, RoleCfg> roles = new java.util.EnumMap<>(Role.class);
	private final Set<Integer> pinned = new HashSet<>(); // items from the TSV file: never rotated out
	private final Map<Long, Integer> virtualCount = new HashMap<>(); // (item, faction) -> virtual listings right now
	private final Map<Integer, Role> roleCache = new ConcurrentHashMap<>();
	private final Map<Role, List<List<CatalogEntry>>> roleGroupLists = new java.util.EnumMap<>(Role.class); // role -> entries split by item group
	private final Map<Role, double[]> roleGroupWeights = new java.util.EnumMap<>(Role.class); // cumulative sqrt(size) weights for picking a group
	private final Map<String, Long> leafMin = new ConcurrentHashMap<>(); // "ROLE/category" -> minimum reference price actually used there
	private final Map<String, java.util.Set<Role>> groupRoles = new java.util.TreeMap<>();
	private int minItemsPerCategory = 8; // every category keeps at least this many items (its minimum price is lowered if needed)
	// the broker's own category rules (BrokerItemMask): they decide which tab of the broker window an item shows up in
	private static final String NO_CATEGORY = "(no broker category)";
	private record Category(String top, String leaf) {}
	private final Map<BrokerItemMask, BrokerFilter> maskFilters = new java.util.EnumMap<>(BrokerItemMask.class);
	private final List<BrokerItemMask> topMasks = new ArrayList<>();
	private final Map<BrokerItemMask, List<BrokerItemMask>> leavesByTop = new java.util.EnumMap<>(BrokerItemMask.class);
	private final Map<Integer, Category> categoryCache = new ConcurrentHashMap<>();
	private boolean listStigmas = true; // stigma stones belong to the "Skill related" category

	// settings (defaults; overridden by broker-simulation.properties)
	private boolean enabled = true;
	private int tradersPerRace = 100;
	private int tickSeconds = 20; // how often the scheduler looks for traders whose own timer ran out
	private int maxTradersPerTick = 8; // load cap: at most this many traders act per look
	private int maxListingsPerItem = 2; // the same item is never listed more often than this by virtual traders (per faction)
	private int catalogRotateHours = 6; // 0 = never
	private int catalogRotatePercent = 15;
	private int cosmeticMaxLevel = 1;
	private int equipmentMinLevel = 2; // weapons, armor and accessories below this level are never sold (level 1 = skins and costumes)
	private int excludedLowLevel;
	private long cosmeticMinPrice = 0;
	private double traderPriceMinFactor = 0.85; // each trader's personality: cheap ... greedy (same item, different sellers)
	private double traderPriceMaxFactor = 1.20;
	private boolean listRandomStatItems = true; // items that drop "unidentified" (question mark icon) because their stats are random
	private double priceSpread = 0.10; // +-10 % random noise around base * trader factor
	// catalog
	private String catalogSource = "auto"; // auto | file | both
	private int catalogSize = 1500;
	private long catalogSeed = 0; // 0 = a different random catalog at every start
	private int minItemLevel = 0;
	private int maxItemLevel = 65;
	private long minItemPrice = 1; // template (vendor) price
	private long maxItemPrice = 5_000_000;
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
	private final Map<String, long[]> groupStats = new java.util.TreeMap<>(); // item group -> diagnostic counters, see loadAutoCatalog
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

	private RoleCfg cfg(Role r) {
		return roles.get(r);
	}

	private void initRoles() {
		for (Role r : Role.values()) {
			roleCatalog.computeIfAbsent(r, k -> new ArrayList<>()).clear();
			reserve.computeIfAbsent(r, k -> new ArrayList<>()).clear();
		}
		if (roles.isEmpty()) { // defaults; the settings file may override every value
			roles.put(Role.GENERAL, new RoleCfg(40, 3, 6, 5, 180, 360));
			roles.put(Role.FURNITURE, new RoleCfg(10, 2, 4, 15, 240, 720));
			roles.put(Role.ARMOR, new RoleCfg(25, 3, 5, 10, 180, 480));
			roles.put(Role.WEAPON, new RoleCfg(25, 3, 5, 10, 180, 480));
			roles.put(Role.COSMETIC, new RoleCfg(0, 2, 4, 180, 720, 1440));
		}
	}

	private void start() {
		initRoles();
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
		initMasks();
		active = true;
		if (buyersEnabled) {
			executor.scheduleWithFixedDelay(this::buyerTickSafely, buyerCheckSeconds, buyerCheckSeconds, TimeUnit.SECONDS);
			log.info("Broker simulation buyers active: max {} purchases per {} min, up to +{}% over fair price.", buyerMaxPurchases, buyerWindowMinutes,
				buyerTolerancePercent);
		}
		String src = catalogSource.toLowerCase(Locale.ROOT);
		if (src.equals("auto") || src.equals("both"))
			loadAutoCatalog();
		if (src.equals("file") || src.equals("both"))
			loadFileCatalog();
		rebuildFlatCatalog();
		if (catalog.isEmpty()) {
			log.warn("Broker simulation: catalog is empty (catalogSource={}), nothing to list.", catalogSource);
			return;
		}

		createTraders();
		int total = 0;
		for (Trader t : traders)
			total += fillTrader(t);
		log.info("Broker simulation started: {} traders ({}), {} listings, {} catalog items.", traders.size(), roleSummary(), total, catalog.size());
		int covered = ensureCoverage();
		if (covered > 0)
			log.info("Broker simulation: coverage pass added {} listings so that every category is on sale.", covered);
		if (dumpCatalog)
			writeCategoryReport();

		executor.scheduleWithFixedDelay(this::tickSafely, tickSeconds, tickSeconds, TimeUnit.SECONDS);
		executor.scheduleWithFixedDelay(this::coverageSafely, 10, 10, TimeUnit.MINUTES);
		if (catalogRotateHours > 0)
			executor.scheduleWithFixedDelay(this::rotateSafely, catalogRotateHours, catalogRotateHours, TimeUnit.HOURS);
	}

	private String roleSummary() {
		StringBuilder sb = new StringBuilder();
		for (Role r : Role.values()) {
			long n = traders.stream().filter(t -> t.role == r).count();
			if (n > 0)
				sb.append(sb.length() > 0 ? ", " : "").append(n).append(' ').append(r.name().toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	private void rebuildFlatCatalog() {
		catalog.clear();
		for (Role r : Role.values()) {
			List<CatalogEntry> entries = roleCatalog.get(r);
			catalog.addAll(entries);
			Map<String, List<CatalogEntry>> byGroup = new java.util.TreeMap<>();
			for (CatalogEntry e : entries)
				byGroup.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
			List<List<CatalogEntry>> lists = new ArrayList<>(byGroup.values());
			double[] cum = new double[lists.size()];
			double sum = 0;
			for (int i = 0; i < cum.length; i++) {
				sum += Math.sqrt(lists.get(i).size()); // big groups (materials) get more listings, but small groups are never starved
				cum[i] = sum;
			}
			roleGroupLists.put(r, lists);
			roleGroupWeights.put(r, cum);
		}
	}

	/** tradersPerRace traders per faction, split over the roles by their percent (roles without any item are skipped). */
	private void createTraders() {
		int percentSum = 0;
		for (Role r : Role.values())
			if (!roleCatalog.get(r).isEmpty())
				percentSum += cfg(r).percent;
		if (percentSum == 0)
			return;
		long now = System.currentTimeMillis();
		int nextId = SELLER_ID_MIN;
		for (Race race : new Race[] { Race.ELYOS, Race.ASMODIANS }) {
			BrokerRace brokerRace = race == Race.ELYOS ? BrokerRace.ELYOS : BrokerRace.ASMODIAN;
			for (Role r : Role.values()) {
				if (roleCatalog.get(r).isEmpty() || cfg(r).percent <= 0)
					continue;
				RoleCfg c = cfg(r);
				int n = (int) Math.round(tradersPerRace * (double) c.percent / percentSum);
				for (int i = 0; i < n; i++) {
					int target = c.minListings + rnd.nextInt(Math.max(1, c.maxListings - c.minListings + 1));
					double factor = traderPriceMinFactor + rnd.nextDouble() * (traderPriceMaxFactor - traderPriceMinFactor);
					long first = now + (long) (rnd.nextDouble() * c.intervalMaxMinutes * 60_000L); // staggered first action
					traders.add(new Trader(nextId++, race, brokerRace, r, target, factor, first));
				}
			}
		}
	}

	private int fillTrader(Trader t) {
		int created = 0;
		int failures = 0;
		while (t.listings.size() < t.targetListings && failures < 15) {
			if (listOne(t))
				created++;
			else
				failures++;
		}
		return created;
	}

	private void tickSafely() {
		try { // an uncaught exception would silently cancel a scheduled task
			tick();
		} catch (Throwable t) {
			log.error("Broker simulation tick failed", t);
		}
	}

	/** Only traders whose own random timer has run out act, and never more than maxTradersPerTick at once. */
	private void tick() {
		long now = System.currentTimeMillis();
		List<Trader> due = new ArrayList<>();
		for (Trader t : traders)
			if (t.nextActionAt <= now)
				due.add(t);
		due.sort(Comparator.comparingLong(t -> t.nextActionAt));
		for (int i = 0; i < Math.min(due.size(), maxTradersPerTick); i++) {
			Trader t = due.get(i);
			serviceTrader(t, now);
			RoleCfg c = cfg(t.role);
			long min = c.intervalMinMinutes * 60_000L;
			long max = Math.max(min, c.intervalMaxMinutes * 60_000L);
			t.nextActionAt = now + min + (long) (rnd.nextDouble() * (max - min));
		}
	}

	private void uncount(Trader t, int itemId) {
		virtualCount.merge(orderKey(itemId, t.race), -1, (a, b) -> a + b <= 0 ? null : a + b);
	}

	private void serviceTrader(Trader t, long now) {
		Iterator<Map.Entry<Integer, long[]>> it = t.listings.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, long[]> e = it.next();
			int itemId = (int) e.getValue()[1];
			if (!broker.isListed(t.race, e.getKey())) {
				moveMarket(itemId, 1.0 + buyBumpPercent / 100.0); // bought by a player: demand
				uncount(t, itemId);
				it.remove();
			} else if (e.getValue()[0] <= now) {
				broker.removeSimulatedListing(t.race, e.getKey()); // expired -> relisted below at a fresh price
				moveMarket(itemId, 1.0 - unsoldDropPercent / 100.0); // nobody wanted it: price drifts down
				uncount(t, itemId);
				it.remove();
			}
		}
		if (t.listings.size() >= t.targetListings && rnd.nextInt(100) < 50) { // the trader changes its mind and swaps one listing
			Integer[] ids = t.listings.keySet().toArray(new Integer[0]);
			Integer id = ids[rnd.nextInt(ids.length)];
			if (broker.removeSimulatedListing(t.race, id))
				uncount(t, (int) t.listings.get(id)[1]);
			t.listings.remove(id);
		}
		t.priceFactor = Math.max(traderPriceMinFactor * 0.95, Math.min(traderPriceMaxFactor * 1.05, t.priceFactor + (rnd.nextDouble() - 0.5) * 0.04)); // slow random walk
		for (int created = 0; created < 2 && t.listings.size() < t.targetListings; created++)
			if (!listOne(t))
				break;
	}

	private boolean listOne(Trader t) {
		return listOne(t, null);
	}

	/** @param onlyFrom if not null, the item is taken from exactly this category (used by the coverage pass) */
	private boolean listOne(Trader t, List<CatalogEntry> onlyFrom) {
		List<List<CatalogEntry>> groups = roleGroupLists.get(t.role);
		double[] cum = roleGroupWeights.get(t.role);
		if (groups == null || groups.isEmpty())
			return false;
		CatalogEntry e = null;
		for (int attempt = 0; attempt < 60 && e == null; attempt++) { // find an entry this faction may sell, that is not over-represented
			List<CatalogEntry> entries = onlyFrom;
			if (entries == null) {
				double pick = rnd.nextDouble() * cum[cum.length - 1];
				int gi = 0;
				while (gi < cum.length - 1 && cum[gi] < pick)
					gi++;
				entries = groups.get(gi); // group first, so every category shows up, then an item of it
			}
			CatalogEntry c = entries.get(rnd.nextInt(entries.size()));
			if (c.race() != Race.PC_ALL && c.race() != t.race)
				continue;
			int cap = cfg(t.role).maxPerItem > 0 ? cfg(t.role).maxPerItem : maxListingsPerItem;
			if (virtualCount.getOrDefault(orderKey(c.itemId(), t.race), 0) >= cap || t.recent.contains(c.itemId()))
				continue;
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
		unit = Math.max(unit, minPriceOfItem(e.itemId())); // trader personality and noise must not undercut the category minimum
		if (unit > MAX_UNIT_PRICE || unit * count > MAX_TOTAL_PRICE)
			return false;

		Item item = ItemFactory.newItem(e.itemId(), count);
		if (item == null)
			return false;
		item.setItemLocation(StorageType.BROKER.getId());
		BrokerItem listing = new BrokerItem(item, unit, t.sellerId, e.stackable(), t.brokerRace);
		broker.addSimulatedListing(listing);

		virtualCount.merge(orderKey(e.itemId(), t.race), 1, Integer::sum);
		t.recent.addLast(e.itemId());
		if (t.recent.size() > 10)
			t.recent.pollFirst();
		double jitter = 0.5 + rnd.nextDouble(); // 50 %..150 % of the role's listing lifetime
		t.listings.put(listing.getItemUniqueId(),
			new long[] { System.currentTimeMillis() + (long) (cfg(t.role).lifetimeMinutes * 60_000L * jitter), e.itemId() });
		return true;
	}

	// ---- coverage: every broker category must have at least one listing in each faction ----

	private void coverageSafely() {
		try {
			int added = ensureCoverage();
			if (added > 0)
				log.info("Broker simulation: coverage pass added {} listings for categories that had none.", added);
		} catch (Throwable t) {
			log.error("Broker simulation coverage pass failed", t);
		}
	}

	/**
	 * Random picking can leave a small category (a handful of items) without any listing for a while. For every category of every role that has no virtual
	 * listing in a faction, one trader of that role and faction lists one item of it, even if that is slightly above the trader's usual number of listings.
	 */
	private int ensureCoverage() {
		int added = 0;
		for (Race race : new Race[] { Race.ELYOS, Race.ASMODIANS }) {
			Map<String, Integer> present = new HashMap<>();
			for (Trader t : traders)
				if (t.race == race)
					for (long[] l : t.listings.values()) {
						ItemTemplate it = DataManager.ITEM_DATA.getItemTemplate((int) l[1]);
						if (it != null)
							present.merge(t.role.name() + "/" + groupKey(it), 1, Integer::sum);
					}
			for (Role role : Role.values()) {
				List<Trader> candidates = new ArrayList<>();
				for (Trader t : traders)
					if (t.race == race && t.role == role && t.listings.size() < 14)
						candidates.add(t);
				if (candidates.isEmpty())
					continue;
				for (List<CatalogEntry> entries : roleGroupLists.getOrDefault(role, List.of())) {
					String key = role.name() + "/" + entries.get(0).group();
					if (present.getOrDefault(key, 0) > 0 || entries.stream().noneMatch(e -> e.race() == Race.PC_ALL || e.race() == race))
						continue;
					Trader t = candidates.get(rnd.nextInt(candidates.size()));
					if (t.listings.size() < 14 && listOne(t, entries)) {
						present.merge(key, 1, Integer::sum);
						added++;
					}
				}
			}
		}
		return added;
	}

	// ---- catalog rotation: the active item set slowly changes so the broker does not look the same all day ----

	private void rotateSafely() {
		try {
			int swapped = 0;
			for (Role r : Role.values()) {
				List<CatalogEntry> act = roleCatalog.get(r);
				List<ItemTemplate> res = reserve.get(r);
				if (act.isEmpty() || res.isEmpty())
					continue;
				int k = Math.max(1, act.size() * catalogRotatePercent / 100);
				for (int i = 0; i < k; i++) {
					int ai = rnd.nextInt(act.size());
					CatalogEntry old = act.get(ai);
					if (pinned.contains(old.itemId()))
						continue;
					List<Integer> sameGroup = new ArrayList<>(); // prefer a replacement of the same category
					for (int j = 0; j < res.size(); j++)
						if (groupKey(res.get(j)).equals(old.group()))
							sameGroup.add(j);
					int ri = sameGroup.isEmpty() ? rnd.nextInt(res.size()) : sameGroup.get(rnd.nextInt(sameGroup.size()));
					ItemTemplate fresh = res.get(ri);
					ItemTemplate oldT = DataManager.ITEM_DATA.getItemTemplate(old.itemId());
					act.set(ai, toEntry(fresh));
					if (oldT != null)
						res.set(ri, oldT);
					else
						res.remove(ri);
					swapped++;
				}
			}
			rebuildFlatCatalog();
			log.info("Broker simulation: catalog rotated, {} items swapped.", swapped);
		} catch (Throwable t) {
			log.error("Broker simulation catalog rotation failed", t);
		}
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// market model: every item has a moving price that stays inside [floor, ceiling] % of its reference price (vendor price x multiplier)

	private double referencePrice(int itemId) {
		Double r = refPrice.get(itemId);
		if (r != null)
			return r;
		ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(itemId);
		if (t == null || t.getPrice() <= 0 || isBlockedTemplate(t) || isLowLevelEquipment(t))
			return 0;
		double v = t.getPrice() * priceMultiplier;
		if (v < minPriceOf(t))
			return 0; // too cheap for its category (armor / weapons / housing: highValueMinPrice, cosmetics: cosmeticMinPrice)
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
		floor = Math.max(floor, minPriceOfItem(itemId)); // never cheaper than the minimum of its category
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

	/** Which kind of trader sells an item. Level <= cosmeticMaxLevel armor / accessories / weapons are cosmetics and only sold by cosmetic traders. */
	private Role classify(ItemTemplate t) {
		Category c = categoryOf(t);
		if (c != null) { // the broker's own categories
			return switch (c.top()) {
				case "WEAPON", "ARMOR", "ACCESSORY" -> t.getLevel() <= cosmeticMaxLevel ? Role.COSMETIC : c.top().equals("WEAPON") ? Role.WEAPON : Role.ARMOR;
				case "HOME_DECOR", "FURNITURE" -> Role.FURNITURE;
				default -> Role.GENERAL;
			};
		}
		if (t.isWeapon() || t.isArmor()) // fallback when the broker rules could not be read: guess from the template
			return t.getLevel() <= cosmeticMaxLevel ? Role.COSMETIC : t.isWeapon() ? Role.WEAPON : Role.ARMOR;
		if (highValueGroups != null && highValueGroups.matcher(t.getItemGroup().name()).find())
			return Role.FURNITURE;
		return Role.GENERAL;
	}

	/** Level 1 (below equipmentMinLevel) items of the Weapon, Armor and Accessory tabs: skins, costumes and similar. They are not sold at all. */
	private boolean isLowLevelEquipment(ItemTemplate t) {
		if (t.getLevel() >= equipmentMinLevel)
			return false;
		Category c = categoryOf(t);
		if (c != null)
			return c.top().equals("WEAPON") || c.top().equals("ARMOR") || c.top().equals("ACCESSORY");
		return t.isWeapon() || t.isArmor();
	}

	/** Reads the broker's category filters (private field of BrokerItemMask) so items can be sorted into the same tabs the client shows. */
	private void initMasks() {
		maskFilters.clear();
		topMasks.clear();
		leavesByTop.clear();
		categoryCache.clear();
		try {
			java.lang.reflect.Field filterField = BrokerItemMask.class.getDeclaredField("filter");
			filterField.setAccessible(true);
			for (BrokerItemMask m : BrokerItemMask.values())
				if (m != BrokerItemMask.UNKNOWN)
					maskFilters.put(m, (BrokerFilter) filterField.get(m));
			BrokerItemMask[] tops = { BrokerItemMask.WEAPON, BrokerItemMask.ARMOR, BrokerItemMask.ACCESSORY, BrokerItemMask.SKILL_RELATED, BrokerItemMask.CRAFT,
				BrokerItemMask.CONSUMABLES, BrokerItemMask.HOME_DECOR, BrokerItemMask.FURNITURE, BrokerItemMask.OTHER };
			for (BrokerItemMask top : tops) {
				topMasks.add(top);
				List<BrokerItemMask> leaves = new ArrayList<>();
				for (BrokerItemMask m : BrokerItemMask.values())
					if (m != top && m != BrokerItemMask.UNKNOWN && !m.hasChildren() && m.isChildrenMask(top.getId()))
						leaves.add(m);
				leavesByTop.put(top, leaves);
			}
			log.info("Broker simulation: using the broker's own category rules ({} categories).", maskFilters.size());
		} catch (Throwable e) {
			maskFilters.clear();
			log.warn("Broker simulation: could not read the broker's category rules, falling back to item group names (housing may be missed).", e);
		}
	}

	/** The broker category (top tab and the most specific sub category) of an item; null if the broker rules are not available. */
	private Category categoryOf(ItemTemplate t) {
		if (maskFilters.isEmpty())
			return null;
		return categoryCache.computeIfAbsent(t.getTemplateId(), id -> {
			for (BrokerItemMask top : topMasks) {
				if (maskFilters.get(top).accept(t)) {
					for (BrokerItemMask leaf : leavesByTop.get(top))
						if (maskFilters.get(leaf).accept(t))
							return new Category(top.name(), leaf.name());
					return new Category(top.name(), top.name());
				}
			}
			// the broker's own rules are not fully consistent: a sub category can accept items that its top tab does not list (for example "Pain"
			// under Consumables). The client still shows them through the sub category, so they count.
			for (BrokerItemMask top : topMasks)
				for (BrokerItemMask leaf : leavesByTop.get(top))
					if (maskFilters.get(leaf).accept(t))
						return new Category(top.name(), leaf.name());
			return new Category("", NO_CATEGORY);
		});
	}

	/** Key used to spread listings over all categories: the broker sub category, or the item group name as a fallback. */
	private String groupKey(ItemTemplate t) {
		Category c = categoryOf(t);
		return c == null ? t.getItemGroup().name() : c.leaf();
	}

	private Role roleOf(int itemId) {
		return roleCache.computeIfAbsent(itemId, id -> {
			ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(id);
			return t == null ? Role.GENERAL : classify(t);
		});
	}

	/** Cheapest reference price an item of this kind may have to take part in the simulated market. */
	private long configuredMin(Role r) {
		return switch (r) {
			case FURNITURE, ARMOR, WEAPON -> highValueMinPrice;
			case COSMETIC -> cosmeticMinPrice;
			default -> 0;
		};
	}

	/** Cheapest reference price an item may have to take part in the market: its role's minimum, lowered for categories that would otherwise be (nearly) empty. */
	private long minPriceOf(ItemTemplate t) {
		Role r = classify(t);
		return leafMin.getOrDefault(r.name() + "/" + groupKey(t), configuredMin(r));
	}

	private long minPriceOfItem(int itemId) {
		ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(itemId);
		return t == null ? 0 : minPriceOf(t);
	}

	private static final Pattern NAME_ASMODIAN = Pattern.compile("(?<![\\p{L}\\p{N}])asmodian(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAME_ELYOS = Pattern.compile("(?<![\\p{L}\\p{N}])elyos(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
	private final List<String> raceAuditLines = new ArrayList<>();

	private void audit(String line) {
		if (!raceAuditLines.contains(line))
			raceAuditLines.add(line);
	}

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
				audit(t.getTemplateId() + "\t" + t.getName() + "\ttemplate PC_ALL, name mentions both -> excluded");
				return null;
			}
			if (named != null)
				audit(t.getTemplateId() + "\t" + t.getName() + "\ttemplate PC_ALL, name says " + named + " -> locked to " + named);
			return named != null ? named : Race.PC_ALL;
		}
		if (named != null && named != templateRace) {
			audit(t.getTemplateId() + "\t" + t.getName() + "\ttemplate " + templateRace + ", name says " + named + " -> excluded");
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

	private static final int OK = 0, REJ_BASIC = 1, REJ_NAME = 2, REJ_FILTER = 3, REJ_RACE = 4;

	/** Why an item may not be sold (0 = fine). The category minimum price is applied later, per role. */
	private int eligibility(ItemTemplate t) {
		if (t.getItemType() != ItemType.NORMAL || !t.isTradeable() || t.isSoulBound() || t.isKinah() || t.isStigma() && !listStigmas)
			return REJ_BASIC;
		if (!listRandomStatItems && t.canTune())
			return REJ_BASIC; // items with random stats are created unidentified ("?" icon)
		if (t.getExpireTime() != 0 || t.getTempExchangeTime() != 0 || t.getExtraInventoryId() != -1)
			return REJ_BASIC; // timed items, temporarily-tradable items and special-inventory items
		String name = t.getName();
		if (name.isEmpty() || name.startsWith("[")) // [Event], [Stamp], [Legion] ... (same rule ItemData uses for manastones)
			return REJ_BASIC;
		if (isBlockedTemplate(t)) { // "NPC Asmodian Abyss Polearm 02", "Delay Test Design 01", "Asmodian Login Stage ... 01", "Nohohon_05_A", ...
			blockedLines.add(t.getTemplateId() + "\t" + name + "\t" + t.getPrice());
			return REJ_NAME;
		}
		long price = t.getPrice();
		if (price < minItemPrice || price > maxItemPrice || t.getLevel() < minItemLevel || t.getLevel() > maxItemLevel)
			return REJ_FILTER;
		return effectiveRace(t) != null ? OK : REJ_RACE;
	}

	private void loadAutoCatalog() {
		Map<Role, List<ItemTemplate>> pools = new java.util.EnumMap<>(Role.class);
		for (Role r : Role.values())
			pools.put(r, new ArrayList<>());
		blockedLines.clear();
		raceAuditLines.clear();
		groupStats.clear();
		groupRoles.clear();
		leafMin.clear();
		excludedLowValue = 0;
		excludedLowLevel = 0;
		// groupStats (one row per broker category) columns: 0 templates, 1 rejected (not tradeable / soulbound / timed / event), 2 blocked name,
		// 3 level or price filter, 4 faction conflict, 5 no broker category, 6 passed, 7 below the category minimum price, 8 usable,
		// 9 active in the catalog, 10 min vendor price, 11 max vendor price
		for (ItemTemplate t : DataManager.ITEM_DATA.getItemTemplates()) {
			String key = groupKey(t);
			long[] st = groupStats.computeIfAbsent(key, k -> new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Long.MAX_VALUE, 0 });
			st[0]++;
			int code = eligibility(t);
			if (code != OK) {
				st[code]++;
				continue;
			}
			if (NO_CATEGORY.equals(key)) {
				st[5]++; // the broker has no tab for it (quest items, keys, coins ...)
				continue;
			}
			if (isLowLevelEquipment(t)) {
				st[3]++; // counted as a level filter
				excludedLowLevel++;
				continue;
			}
			st[6]++;
			st[10] = Math.min(st[10], t.getPrice());
			st[11] = Math.max(st[11], t.getPrice());
			Role r = classify(t);
			pools.get(r).add(t);
			groupRoles.computeIfAbsent(key, k -> new java.util.TreeSet<>()).add(r);
		}

		// minimum price per category: lowered for a category that would otherwise keep fewer than minItemsPerCategory items
		StringBuilder relaxed = new StringBuilder();
		int relaxedCount = 0;
		for (Role r : Role.values()) {
			final long configured = configuredMin(r);
			List<ItemTemplate> pool = pools.get(r);
			if (configured > 0) {
				Map<String, List<Double>> refsByCategory = new java.util.TreeMap<>();
				for (ItemTemplate t : pool)
					refsByCategory.computeIfAbsent(groupKey(t), k -> new ArrayList<>()).add(t.getPrice() * priceMultiplier);
				for (Map.Entry<String, List<Double>> e : refsByCategory.entrySet()) {
					List<Double> refs = e.getValue();
					refs.sort(Comparator.reverseOrder());
					long enough = refs.stream().filter(v -> v >= configured).count();
					long threshold = configured;
					if (enough < minItemsPerCategory) {
						threshold = (long) Math.floor(refs.get(Math.min(minItemsPerCategory, refs.size()) - 1));
						relaxedCount++;
						if (relaxed.length() < 600)
							relaxed.append(relaxed.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(configured).append("->").append(threshold);
					}
					leafMin.put(r.name() + "/" + e.getKey(), threshold);
				}
			}
			pool.removeIf(t -> {
				long limit = leafMin.getOrDefault(r.name() + "/" + groupKey(t), configured);
				if (t.getPrice() * priceMultiplier >= limit)
					return false;
				groupStats.get(groupKey(t))[7]++;
				excludedLowValue++;
				return true;
			});
		}

		int percentSum = 0;
		for (Role r : Role.values())
			percentSum += cfg(r).percent;
		percentSum = Math.max(1, percentSum);
		Comparator<ItemTemplate> byId = Comparator.comparingInt(ItemTemplate::getTemplateId);
		List<String> dump = new ArrayList<>();
		List<String> coverage = new ArrayList<>();
		for (Role r : Role.values()) {
			List<ItemTemplate> pool = pools.get(r);
			Random rr = catalogSeed == 0 ? new Random() : new Random(catalogSeed + r.ordinal());
			Map<String, List<ItemTemplate>> byGroup = new java.util.TreeMap<>();
			for (ItemTemplate t : pool)
				byGroup.computeIfAbsent(groupKey(t), k -> new ArrayList<>()).add(t);
			for (Map.Entry<String, List<ItemTemplate>> g : byGroup.entrySet()) {
				g.getValue().sort(byId); // deterministic order, then a seeded (or, with seed 0, random) shuffle
				Collections.shuffle(g.getValue(), rr);
				groupStats.get(g.getKey())[8] += g.getValue().size();
			}
			// stratified pick: take items category by category in turns, so every broker category is represented
			int want = Math.min(pool.size(), Math.max(10, catalogSize * cfg(r).percent / percentSum));
			Map<String, Integer> taken = new HashMap<>();
			boolean progress = true;
			int picked = 0;
			while (picked < want && progress) {
				progress = false;
				for (Map.Entry<String, List<ItemTemplate>> g : byGroup.entrySet()) {
					int i = taken.getOrDefault(g.getKey(), 0);
					if (i < g.getValue().size() && picked < want) {
						ItemTemplate t = g.getValue().get(i);
						CatalogEntry e = toEntry(t);
						roleCatalog.get(r).add(e);
						dump.add(t.getTemplateId() + "\t" + t.getName() + "\t" + g.getKey() + "\t" + t.getPrice() + "\t" + e.unitPrice() + "\t" + e.race() + "\t" + r);
						taken.put(g.getKey(), i + 1);
						groupStats.get(g.getKey())[9]++;
						picked++;
						progress = true;
					}
				}
			}
			for (Map.Entry<String, List<ItemTemplate>> g : byGroup.entrySet())
				reserve.get(r).addAll(g.getValue().subList(taken.getOrDefault(g.getKey(), 0), g.getValue().size()));
			coverage.add(r.name().toLowerCase(Locale.ROOT) + ": " + pool.size() + " usable items in " + byGroup.size() + " broker categories, " + picked + " active");
			if (pool.isEmpty() && cfg(r).percent > 0 && !(r == Role.COSMETIC && equipmentMinLevel > cosmeticMaxLevel))
				log.warn("Broker simulation: role {} has NO items, so there are no {} traders. Look at broker-simulation-groups.txt.", r, r.name().toLowerCase(Locale.ROOT));
		}
		if (dumpCatalog) {
			writeLines(DUMP_FILE, "itemId\tname\tbrokerCategory\tvendorPrice\tbrokerPrice\trace\trole  (active catalog at start; it rotates while the server runs)", dump);
			Collections.sort(blockedLines);
			writeLines(BLOCKED_FILE, "itemId\tname\tvendorPrice  (excluded as NPC / test / admin item)", blockedLines);
			Collections.sort(raceAuditLines);
			writeLines(RACE_AUDIT_FILE, "itemId\tname\tdecision  (items where the faction in the name and the template's race need a ruling)", raceAuditLines);
			List<String> groups = new ArrayList<>();
			for (Map.Entry<String, long[]> g : groupStats.entrySet()) {
				long[] st = g.getValue();
				groups.add(g.getKey() + "\t" + st[0] + "\t" + st[1] + "\t" + st[2] + "\t" + st[3] + "\t" + st[4] + "\t" + st[5] + "\t" + st[6] + "\t" + st[7] + "\t" + st[8] + "\t"
					+ st[9] + "\t" + (st[6] == 0 ? "-" : st[10]) + "\t" + st[11] + "\t"
					+ groupRoles.getOrDefault(g.getKey(), java.util.Set.of()).stream().map(Enum::name).collect(java.util.stream.Collectors.joining("+")));
			}
			writeLines(GROUPS_FILE, "brokerCategory\ttemplates\trejected(untradeable/soulbound/timed/event)\tblockedName\tlevelPriceFilter\tfactionConflict\tnoBrokerCategory\tpassed\tbelowMinPrice\tusable\tactive\tminVendor\tmaxVendor\troles  "
				+ "(a category whose templates are all rejected or below the minimum has NO listings in the broker)", groups);
			writeLines(COVERAGE_FILE, "role: usable items, broker categories, active items", coverage);
		}
		for (String c : coverage)
			log.info("Broker simulation coverage - {}", c);
		if (relaxedCount > 0)
			log.info("Broker simulation: minimum price lowered in {} categories so each keeps at least {} items: {}", relaxedCount, minItemsPerCategory, relaxed);
		log.info("Broker simulation: {} items excluded by name (NPC/test/admin), {} level-1 weapons/armor/accessories left out, {} items below their category's minimum price, {} faction conflicts or hints (see race audit).",
			blockedLines.size(), excludedLowLevel, excludedLowValue, raceAuditLines.size());
	}

	/** What the broker window will really show: virtual listings per broker category and faction (the tabs of the client). */
	private void writeCategoryReport() {
		if (maskFilters.isEmpty())
			return;
		Map<String, long[]> counts = new java.util.TreeMap<>();
		for (Trader t : traders)
			for (long[] l : t.listings.values()) {
				ItemTemplate it = DataManager.ITEM_DATA.getItemTemplate((int) l[1]);
				if (it != null)
					counts.computeIfAbsent(groupKey(it), k -> new long[2])[t.race == Race.ELYOS ? 0 : 1]++;
			}
		List<String> lines = new ArrayList<>();
		List<String> empty = new ArrayList<>();
		for (BrokerItemMask m : BrokerItemMask.values()) {
			if (m == BrokerItemMask.UNKNOWN || m.hasChildren() && !topMasks.contains(m))
				continue; // only leaf categories (and top tabs that have no sub categories)
			if (topMasks.contains(m) && !leavesByTop.get(m).isEmpty())
				continue;
			String top = m.name();
			for (BrokerItemMask t : topMasks)
				if (m.isChildrenMask(t.getId()))
					top = t.name();
			long usable = groupStats.getOrDefault(m.name(), new long[12])[8];
			long[] c = counts.getOrDefault(m.name(), new long[2]);
			lines.add(m.name() + "\t" + top + "\t" + usable + "\t" + c[0] + "\t" + c[1] + (usable == 0 ? "\tNO ITEMS EXIST" : c[0] == 0 || c[1] == 0 ? "\tmissing in one faction" : ""));
			if (usable > 0 && c[0] + c[1] == 0)
				empty.add(m.name());
		}
		writeLines(CATEGORIES_FILE, "brokerCategory\ttab\tusableItems\tlistingsElyos\tlistingsAsmodian  (what the broker window shows right now, from the virtual traders)", lines);
		if (!empty.isEmpty())
			log.warn("Broker simulation: broker categories with usable items but no listing at the moment: {}.", empty);
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

	private CatalogEntry toEntry(ItemTemplate t) {
		boolean stackable = t.isStackable();
		int max = stackable ? (int) Math.max(1, Math.min(t.getMaxStackCount(), maxStackListed)) : 1;
		long unit = Math.max(1, Math.round(t.getPrice() * priceMultiplier));
		refPrice.put(t.getTemplateId(), (double) unit);
		return new CatalogEntry(t.getTemplateId(), unit, 1, max, stackable, effectiveRace(t), groupKey(t));
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
					if (isLowLevelEquipment(t)) {
						log.warn("Broker simulation: item id {} is a level-{} weapon/armor/accessory (equipmentMinLevel = {}), skipped", itemId, t.getLevel(), equipmentMinLevel);
						continue;
					}
					Role fileRole = classify(t);
					if (price < configuredMin(fileRole)) {
						log.warn("Broker simulation: item id {} ({}) is below the minimum price of its category ({} kinah), skipped", itemId, fileRole, configuredMin(fileRole));
						continue;
					}
					boolean stackable = t.isStackable();
					roleCatalog.get(fileRole).add(new CatalogEntry(itemId, price, stackable ? min : 1, stackable ? max : 1, stackable, fileRace, groupKey(t)));
					refPrice.put(itemId, (double) price);
					pinned.add(itemId);
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
		tradersPerRace = clamp(intOf(p, "tradersPerRace", 100), 1, 250); // names are unique up to 600 traders in total
		tickSeconds = clamp(intOf(p, "tickSeconds", 20), 5, 600);
		maxTradersPerTick = clamp(intOf(p, "maxTradersPerTick", 8), 1, 200);
		maxListingsPerItem = clamp(intOf(p, "maxListingsPerItem", 2), 1, 50);
		catalogRotateHours = clamp(intOf(p, "catalogRotateHours", 6), 0, 720);
		catalogRotatePercent = clamp(intOf(p, "catalogRotatePercent", 15), 1, 100);
		cosmeticMaxLevel = clamp(intOf(p, "cosmeticMaxLevel", 1), 0, 100);
		cosmeticMinPrice = Math.max(0, intOf(p, "cosmeticMinPrice", 0));
		equipmentMinLevel = clamp(intOf(p, "equipmentMinLevel", 2), 0, 100);
		minItemsPerCategory = clamp(intOf(p, "minItemsPerCategory", 8), 1, 500);
		listStigmas = Boolean.parseBoolean(p.getProperty("listStigmas", "true").trim());
		traderPriceMinFactor = doubleOf(p, "traderPriceMinFactor", 0.85, 0.1, 3.0);
		traderPriceMaxFactor = Math.max(traderPriceMinFactor, doubleOf(p, "traderPriceMaxFactor", 1.20, 0.1, 3.0));
		listRandomStatItems = Boolean.parseBoolean(p.getProperty("listRandomStatItems", "true").trim());
		for (Role r : Role.values()) {
			RoleCfg c = roles.get(r);
			String k = "role." + r.name().toLowerCase(Locale.ROOT) + ".";
			c.percent = clamp(intOf(p, k + "percent", c.percent), 0, 100);
			c.minListings = clamp(intOf(p, k + "minListings", c.minListings), 1, 14);
			c.maxListings = clamp(intOf(p, k + "maxListings", c.maxListings), c.minListings, 14);
			c.intervalMinMinutes = clamp(intOf(p, k + "intervalMinMinutes", c.intervalMinMinutes), 1, 10_080);
			c.intervalMaxMinutes = clamp(intOf(p, k + "intervalMaxMinutes", c.intervalMaxMinutes), c.intervalMinMinutes, 10_080);
			c.lifetimeMinutes = clamp(intOf(p, k + "lifetimeMinutes", c.lifetimeMinutes), 5, 10_080);
			c.maxPerItem = clamp(intOf(p, k + "maxListingsPerItem", c.maxPerItem), 0, 50);
		}
		priceSpread = clamp(intOf(p, "priceSpreadPercent", 10), 0, 50) / 100.0;

		catalogSource = p.getProperty("catalogSource", "auto").trim();
		catalogSize = clamp(intOf(p, "catalogSize", 1500), 10, 5000);
		catalogSeed = intOf(p, "catalogSeed", 0);
		minItemLevel = clamp(intOf(p, "minItemLevel", 0), 0, 100);
		maxItemLevel = clamp(intOf(p, "maxItemLevel", 65), minItemLevel, 100);
		minItemPrice = Math.max(1, intOf(p, "minItemPrice", 1));
		maxItemPrice = Math.max(minItemPrice, intOf(p, "maxItemPrice", 5_000_000));
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
