package com.aionemu.gameserver.model.broker.filter; import com.aionemu.gameserver.model.templates.item.ItemTemplate;
public class BrokerContainsExtraFilter implements BrokerFilter { int[] ids; public BrokerContainsExtraFilter(int... ids){this.ids=ids;} public boolean accept(ItemTemplate t){ for(int i:ids) if(i==t.extraId) return true; return false; } }
