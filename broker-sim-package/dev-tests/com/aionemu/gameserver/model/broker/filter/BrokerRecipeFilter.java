package com.aionemu.gameserver.model.broker.filter; import com.aionemu.gameserver.model.templates.item.ItemTemplate;
public class BrokerRecipeFilter implements BrokerFilter { int skill,id; public BrokerRecipeFilter(int skill,int id){this.skill=skill;this.id=id;} public boolean accept(ItemTemplate t){ return t.catId==id && t.recipeSkill==skill; } }
