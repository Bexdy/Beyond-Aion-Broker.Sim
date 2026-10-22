package com.aionemu.gameserver.services;
import java.lang.reflect.*; import java.util.*; import java.util.stream.*;
import com.aionemu.gameserver.dataholders.DataManager; import com.aionemu.gameserver.model.PlayerClass; import com.aionemu.gameserver.model.Race; import com.aionemu.gameserver.model.broker.BrokerItemMask;
import com.aionemu.gameserver.model.gameobjects.BrokerItem; import com.aionemu.gameserver.model.templates.item.ItemTemplate; import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
/** Runs the real BrokerSimulationService against the REAL BrokerItemMask (category tree of the broker window) with fake item data. */
public class SimTest {
 static int nid=1000000; static Random r=new Random(7);
 /** n items of one broker category: catId is what the broker filters look at. */
 static void gen(String top,String leaf,int catId,int extraId,int recipeSkill,PlayerClass pc,int n,long lo,long hi,int lvLo,int lvHi,boolean w,boolean a,boolean stack){
  for(int i=0;i<n;i++){ ItemTemplate x=new ItemTemplate(); x.id=nid++; x.name=leaf.toLowerCase().replace('_',' ')+" "+i; x.price=lo+(long)(r.nextDouble()*(hi-lo)); x.level=lvLo+r.nextInt(Math.max(1,lvHi-lvLo+1));
   x.weapon=w; x.armor=a; x.stack=stack; x.group=ItemGroup.NONE; x.race=i%12==0?Race.ELYOS:i%12==1?Race.ASMODIANS:Race.PC_ALL; x.catId=catId; x.extraId=extraId; x.recipeSkill=recipeSkill; x.playerClass=pc; x.expTop=top; x.expLeaf=leaf; DataManager.ITEM_DATA.m.put(x.id,x); } }
 @SuppressWarnings("unchecked") static <T> T get(Object o,String f) throws Exception { Field fl=o.getClass().getDeclaredField(f); fl.setAccessible(true); return (T)fl.get(o); }
 static String role(ItemTemplate x,int cosmeticMaxLevel){ boolean eq=x.expTop.equals("WEAPON")||x.expTop.equals("ARMOR")||x.expTop.equals("ACCESSORY"); if(eq&&x.level<=cosmeticMaxLevel) return "COSMETIC"; if(x.expTop.equals("WEAPON")) return "WEAPON"; if(eq) return "ARMOR"; if(x.expTop.equals("HOME_DECOR")||x.expTop.equals("FURNITURE")) return "FURNITURE"; return "GENERAL"; }
 public static void main(String[] a) throws Exception {
  // weapons (every sub category of the tree), armor (5 kinds x 5 slots + shield), accessories
  int[] wt={1000,1001,1002,1005,1006,1009,1013,1015,1017,1018,1019,1020,1021}; String[] wn={"SWORD","MACE","DAGGER","ORB","SPELLBOOK","GREATSWORD","POLEARM","STAFF","BOW","PISTOL","AETHERCANNON","GARP","KEYBLADE"};
  for(int i=0;i<wt.length;i++){ gen("WEAPON","WEAPON_"+wn[i],wt[i],0,0,null,i==11?3:120,3000,500000,10,65,true,false,false); gen("WEAPON","WEAPON_"+wn[i],wt[i],0,0,null,15,100,5000,1,1,true,false,false); }
  String[] kinds={"CLOTHING","CLOTH","LEATHER","CHAIN","PLATE"}; int[] base={1100,1101,1103,1105,1106}; String[] slots={"JACKET","GLOVES","PAULDRONS","PANTS","SHOES"};
  for(int k=0;k<5;k++) for(int s=0;s<5;s++){ int id=base[k]+s*10; gen("ARMOR","ARMOR_"+kinds[k]+"_"+slots[s],id,0,0,null,60,3000,500000,10,65,false,true,false); gen("ARMOR","ARMOR_"+kinds[k]+"_"+slots[s],id,0,0,null,12,100,50000,1,1,false,true,false); }
  gen("ARMOR","ARMOR_SHIELD",1150,0,0,null,80,3000,500000,10,65,false,true,false);
  int[] ac={1200,1210,1220,1230,1250,1871}; String[] an={"EARRINGS","NECKLACE","RING","BELT","HEADGEAR","PLUME"};
  for(int i=0;i<ac.length;i++){ gen("ACCESSORY","ACCESSORY_"+an[i],ac[i],0,0,null,i==5?4:70,3000,500000,10,65,false,true,false); gen("ACCESSORY","ACCESSORY_"+an[i],ac[i],0,0,null,8,100,5000,1,1,false,true,false); }
  // skill related: stigmas and manuals per class
  PlayerClass[] pcs=PlayerClass.values(); String[] pn={"GLADIATOR","TEMPLAR","ASSASSIN","RANGER","SORCERER","SPIRITMASTER","CLERIC","CHANTER","GUNSLINGER","SONGWEAVER","RIDER"}; int[] stg={6010,6011,6012,6013,6014,6015,6016,6017,6018,6019,6048}; int[] man={6020,6021,6022,6023,6024,6025,6026,6027,6028,6029,6049};
  for(int i=0;i<pcs.length;i++){ gen("SKILL_RELATED","SKILL_RELATED_STIGMA_"+pn[i],1400,0,0,pcs[i],i==10?2:25,50000,400000,20,65,false,false,false); gen("SKILL_RELATED","SKILL_RELATED_SKILL_MANUAL_"+pn[i],1695,0,0,pcs[i],30,2000,60000,10,60,false,false,false); }
  // home decor + furniture: CHEAP vendor prices on purpose (the 100000 minimum cannot be met)
  gen("HOME_DECOR","HOME_DECOR_OUT_DOOR",1710,0,0,null,35,500,15000,1,1,false,false,false); gen("HOME_DECOR","HOME_DECOR_IN_DOOR",1711,0,0,null,60,500,20000,1,1,false,false,false);
  gen("FURNITURE","FURNITURE_OUT_DOOR",1703,0,0,null,40,800,30000,1,1,false,false,false); gen("FURNITURE","FURNITURE_IN_DOOR_WALL_MOUNTED",1700,0,0,null,50,800,30000,1,1,false,false,false);
  gen("FURNITURE","FURNITURE_IN_DOOR_FREE_STANDING",1701,0,0,null,80,800,30000,1,1,false,false,false); gen("FURNITURE","FURNITURE_IN_DOOR_RUGS",1702,0,0,null,5,800,9000,1,1,false,false,false); gen("FURNITURE","FURNITURE_IN_DOOR_OUT_DOOR",1704,0,0,null,20,800,30000,1,1,false,false,false);
  // craft
  gen("CRAFT","CRAFT_MATERIALS_GATHERED",1520,15200,0,null,300,10,2000,1,60,false,false,true); gen("CRAFT","CRAFT_MATERIALS_LOOTED",1520,15201,0,null,200,10,2000,1,60,false,false,true); gen("CRAFT","CRAFT_MATERIALS_COMPONENTS",1520,15202,0,null,150,10,5000,1,60,false,false,true);
  int[] rs={40002,40003,40004,40008,40007,40001,40010}; String[] rn={"WEAPONSMITHING","ARMORSMITHING","TAILORING","HANDICRAFTING","ALCHEMY","COOKING","CONSTRUCTION"}; for(int i=0;i<rs.length;i++) gen("CRAFT","CRAFT_DESIGN_"+rn[i],1522,0,rs[i],null,i==6?3:150,5000,900000,10,60,false,false,false);
  // consumables
  gen("CONSUMABLES","CONSUMABLES_FOOD",1600,0,0,null,200,20,800,1,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_POTION",1620,0,0,null,300,50,5000,10,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_SCROLL",1640,0,0,null,150,100,9000,10,60,false,false,true);
  gen("CONSUMABLES","CONSUMABLES_MODIFY_ENCHANTMENT_STONE",1660,16600,0,null,60,1000,95000,10,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_MODIFY_MANASTONE",1670,0,0,null,360,10,1000,10,60,false,false,true);
  gen("CONSUMABLES","CONSUMABLES_MODIFY_TEMPERING_SOLUTION",1660,16603,0,null,10,500,9000,10,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_MODIFY_GODSTONE",1680,0,0,null,60,90000,1200000,10,60,false,false,true);
  gen("CONSUMABLES","CONSUMABLES_MODIFY_DYE",1692,0,0,null,40,200,5000,1,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_MODIFY_PAIN",1691,0,0,null,6,200,5000,1,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_MODIFY_AMPLIFICATION_STONE",1665,0,0,null,9,2000,50000,1,60,false,false,true);
  gen("CONSUMABLES","CONSUMABLES_MODIFY_OTHER",1661,0,0,null,12,100,5000,1,60,false,false,true); gen("CONSUMABLES","CONSUMABLES_OTHER",1690,0,0,null,80,10,9000,1,60,false,false,true);
  // other, and items the broker has no tab for (quest items, keys): must never be sold
  for(int id:new int[]{1850,1860,1870,1880,1881,1887}) gen("OTHER","OTHER",id,0,0,null,40,5,3000,1,60,false,false,true);
  gen("","(none)",9999,0,0,null,300,5,3000,1,60,false,false,true);
  gen("GENERAL","JUNK",1620,0,0,null,0,1,1,1,1,false,false,false);
  for(int i=0;i<30;i++){ ItemTemplate x=DataManager.ITEM_DATA.m.values().iterator().next(); }

  Field inst=BrokerSimulationService.class.getDeclaredField("INSTANCE"); inst.setAccessible(true); BrokerSimulationService s=(BrokerSimulationService)inst.get(null);
  Method start=BrokerSimulationService.class.getDeclaredMethod("start"); start.setAccessible(true);
  long t0=System.nanoTime(); start.invoke(s); System.out.printf("start() took %d ms%n",(System.nanoTime()-t0)/1_000_000);
  List<Object> traders=get(s,"traders"); BrokerService b=BrokerService.getInstance();
  System.out.println("traders: "+traders.size()+"   listings ely/asmo: "+b.ely.size()+" / "+b.asmo.size());
  for(Map<Integer,BrokerItem> m:List.of(b.ely,b.asmo)){ Map<Integer,Long> c=m.values().stream().collect(Collectors.groupingBy(BrokerItem::getItemId,Collectors.counting()));
    System.out.println("   distinct items "+c.size()+" of "+m.size()+" listings, max repeats of one item: "+c.values().stream().mapToLong(x->x).max().getAsLong()); }

  // ---- the question of this test: does EVERY category of the broker window have listings?
  Set<String> leafOnSale=new TreeSet<>(); Map<String,long[]> perLeaf=new TreeMap<>(); int noCat=0, wrongFaction=0;
  for(var e:List.of(Map.entry(Race.ELYOS,b.ely),Map.entry(Race.ASMODIANS,b.asmo))) for(BrokerItem bi:e.getValue().values()){ ItemTemplate x=DataManager.ITEM_DATA.getItemTemplate(bi.getItemId());
    if(x.expTop.isEmpty()) noCat++; leafOnSale.add(x.expLeaf); perLeaf.computeIfAbsent(x.expLeaf,k->new long[2])[e.getKey()==Race.ELYOS?0:1]++;
    if(x.race!=Race.PC_ALL && x.race!=e.getKey()) wrongFaction++; }
  List<String> missing=new ArrayList<>(); List<String> oneFaction=new ArrayList<>();
  for(BrokerItemMask m:BrokerItemMask.values()){ if(m==BrokerItemMask.UNKNOWN||m.hasChildren()) continue; if(!DataManager.ITEM_DATA.m.values().stream().anyMatch(x->x.expLeaf.equals(m.name()))) continue;
    long[] c=perLeaf.getOrDefault(m.name(),new long[2]); if(c[0]+c[1]==0) missing.add(m.name()); else if(c[0]==0||c[1]==0) oneFaction.add(m.name()); }
  System.out.println("broker categories (leaves) tested: "+BrokerItemMask.values().length+" masks; leaves with items but NO listing: "+missing+"; listed in only one faction: "+oneFaction);
  System.out.println("top tabs on sale -> "+Stream.of("WEAPON","ARMOR","ACCESSORY","SKILL_RELATED","HOME_DECOR","FURNITURE","CRAFT","CONSUMABLES","OTHER").map(t->t+"="+b.ely.values().stream().filter(bi->DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()).expTop.equals(t)).count()+"/"+b.asmo.values().stream().filter(bi->DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()).expTop.equals(t)).count()).collect(Collectors.joining(" ")));
  System.out.println("listings of items WITHOUT a broker category: "+noCat+", wrong faction: "+wrongFaction);
  int lowEquip=0, lowOther=0; for(var m:List.of(b.ely,b.asmo)) for(BrokerItem bi:m.values()){ ItemTemplate x=DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()); if(x.level<=1){ if(x.expTop.equals("WEAPON")||x.expTop.equals("ARMOR")||x.expTop.equals("ACCESSORY")) lowEquip++; else lowOther++; } }
  System.out.println("level 1 listings -> in Weapon/Armor/Accessory: "+lowEquip+" (must be 0), in all other tabs: "+lowOther+" (must stay > 0)");

  // ---- roles and minimum prices
  Field roleF=traders.get(0).getClass().getDeclaredField("role"); roleF.setAccessible(true); Field sid=traders.get(0).getClass().getDeclaredField("sellerId"); sid.setAccessible(true);
  Map<Integer,String> roleOf=new HashMap<>(); for(Object tr:traders) roleOf.put((Integer)sid.get(tr), roleF.get(tr).toString());
  Map<String,Long> leafMin=get(s,"leafMin"); Map<String,Integer> mismatch=new TreeMap<>(); int belowMin=0; long cheapestFurn=Long.MAX_VALUE, cheapestWeapon=Long.MAX_VALUE;
  for(var m:List.of(b.ely,b.asmo)) for(BrokerItem bi:m.values()){ ItemTemplate x=DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()); String ro=role(x,1);
    if(!ro.equals(roleOf.get(bi.getSellerId()))) mismatch.merge(ro+" sold by "+roleOf.get(bi.getSellerId()),1,Integer::sum);
    Long mn=leafMin.get(ro+"/"+x.expLeaf); if(mn!=null && bi.getPrice()<mn) belowMin++;
    if(ro.equals("FURNITURE")) cheapestFurn=Math.min(cheapestFurn,bi.getPrice()); if(ro.equals("WEAPON")) cheapestWeapon=Math.min(cheapestWeapon,bi.getPrice()); }
  System.out.println("role mismatches: "+mismatch+"   listings below their category minimum: "+belowMin);
  System.out.println("cheapest furniture/decor listing: "+cheapestFurn+" (its categories' minimum was lowered because cheap items cannot reach 100000), cheapest weapon listing: "+cheapestWeapon);
  System.out.println("minimum prices lowered in "+leafMin.entrySet().stream().filter(e->e.getValue()<100000).count()+" of "+leafMin.size()+" categories");

  // ---- ticks + rotations keep everything
  Field na=traders.get(0).getClass().getDeclaredField("nextActionAt"); na.setAccessible(true); long now=System.currentTimeMillis();
  Method tick=BrokerSimulationService.class.getDeclaredMethod("tick"); tick.setAccessible(true); Method rot=BrokerSimulationService.class.getDeclaredMethod("rotateSafely"); rot.setAccessible(true);
  for(Object tr:traders) na.set(tr,now-1000); tick.invoke(s); long acted=traders.stream().filter(o->{try{return (Long)na.get(o)>now+1000;}catch(Exception ex){return false;}}).count();
  System.out.println("one tick with ALL traders due: traders that acted = "+acted+" (cap 8)");
  for(int i=0;i<300;i++){ for(Object tr:traders) if(r.nextInt(4)==0) na.set(tr,now-1); tick.invoke(s); if(i%50==0) rot.invoke(s); }
  Set<String> leaf2=new TreeSet<>(); int maxRep=0; for(var m:List.of(b.ely,b.asmo)){ Map<Integer,Long> c=m.values().stream().collect(Collectors.groupingBy(BrokerItem::getItemId,Collectors.counting())); maxRep=Math.max(maxRep,(int)c.values().stream().mapToLong(x->x).max().getAsLong());
    for(BrokerItem bi:m.values()) leaf2.add(DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()).expLeaf); }
  Method cov=BrokerSimulationService.class.getDeclaredMethod("ensureCoverage"); cov.setAccessible(true); int added=(Integer)cov.invoke(s); System.out.println("coverage pass after the ticks added "+added+" listings");
  leaf2.clear(); for(var m:List.of(b.ely,b.asmo)) for(BrokerItem bi:m.values()) leaf2.add(DataManager.ITEM_DATA.getItemTemplate(bi.getItemId()).expLeaf);
  List<String> missing2=new ArrayList<>(); for(String l:leafOnSale) if(!leaf2.contains(l)) missing2.add(l);
  System.out.println("after 300 ticks + 6 rotations: max repeats "+maxRep+", categories that were on sale at start but are gone now: "+missing2+" (small categories may be between two relistings)");
  System.exit(0);
 }}
