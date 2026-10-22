package com.aionemu.gameserver.model.broker.filter; import com.aionemu.gameserver.model.templates.item.ItemTemplate;
public class BrokerContainsFilter implements BrokerFilter { int[] ids; public BrokerContainsFilter(int... ids){this.ids=ids;} public boolean accept(ItemTemplate t){ for(int i:ids) if(i==t.catId) return true; return false; } }
