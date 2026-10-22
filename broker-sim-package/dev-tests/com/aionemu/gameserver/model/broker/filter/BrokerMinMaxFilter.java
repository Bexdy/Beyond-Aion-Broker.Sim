package com.aionemu.gameserver.model.broker.filter; import com.aionemu.gameserver.model.templates.item.ItemTemplate;
public class BrokerMinMaxFilter implements BrokerFilter { int lo,hi; public BrokerMinMaxFilter(int lo,int hi){this.lo=lo;this.hi=hi;} public boolean accept(ItemTemplate t){return t.catId>=lo && t.catId<=hi;} }
