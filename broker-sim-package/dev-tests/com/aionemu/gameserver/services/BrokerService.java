package com.aionemu.gameserver.services; import java.util.*; import com.aionemu.gameserver.model.Race; import com.aionemu.gameserver.model.broker.BrokerRace; import com.aionemu.gameserver.model.gameobjects.BrokerItem;
public class BrokerService { static final BrokerService I=new BrokerService(); public static BrokerService getInstance(){return I;}
 public Map<Integer,BrokerItem> ely=new HashMap<>(), asmo=new HashMap<>();
 Map<Integer,BrokerItem> map(Race r){return r==Race.ELYOS?ely:asmo;}
 public void addSimulatedListing(BrokerItem b){ map(b.getItemBrokerRace()==BrokerRace.ELYOS?Race.ELYOS:Race.ASMODIANS).put(b.getItemUniqueId(),b);} 
 public boolean removeSimulatedListing(Race r,int i){return map(r).remove(i)!=null;} public boolean isListed(Race r,int i){return map(r).containsKey(i);} 
 public List<BrokerItem> getPlayerListings(Race r){return new ArrayList<>();} public boolean sellToSimulatedBuyer(Race r,int i,long c){return false;} public Map<Integer,Long> getLowestPrices(Race r){return new HashMap<>();} }
