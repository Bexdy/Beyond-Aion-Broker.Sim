package com.aionemu.gameserver.dataholders; import java.util.*; import com.aionemu.gameserver.model.templates.item.ItemTemplate;
public class ItemData { public Map<Integer,ItemTemplate> m=new HashMap<>(); public Collection<ItemTemplate> getItemTemplates(){return m.values();} public ItemTemplate getItemTemplate(int i){return m.get(i);} }
