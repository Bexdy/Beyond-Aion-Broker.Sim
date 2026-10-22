package com.aionemu.gameserver.services.item; import com.aionemu.gameserver.model.gameobjects.Item; public class ItemFactory { public static Item newItem(int id,long c){return new Item(id,c);} }
