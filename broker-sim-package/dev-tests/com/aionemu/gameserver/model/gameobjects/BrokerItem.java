package com.aionemu.gameserver.model.gameobjects; import com.aionemu.gameserver.model.broker.BrokerRace;
public class BrokerItem { static int next=1000; int uid=next++; Item item; long price; int seller; boolean split; BrokerRace race;
 public BrokerItem(Item i,long p,int s,boolean b,BrokerRace r){item=i;price=p;seller=s;split=b;race=r;}
 public int getItemUniqueId(){return uid;} public int getItemId(){return item.id;} public long getPrice(){return price;} public long getItemCount(){return item.count;} public int getSellerId(){return seller;}
 public boolean isSplittingAvailable(){return split;} public BrokerRace getItemBrokerRace(){return race;} }
