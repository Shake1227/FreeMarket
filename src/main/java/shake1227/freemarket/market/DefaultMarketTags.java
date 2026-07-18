package shake1227.freemarket.market;

import java.util.ArrayList;
import java.util.List;

final class DefaultMarketTags {
    private DefaultMarketTags() {
    }

    static List<MarketTag> create() {
        ArrayList<MarketTag> tags = new ArrayList<>();
        addCategory(tags, "weapons", "Weapons");
        add(tags, "weapons.sword", "weapons", "Swords");
        add(tags, "weapons.axe", "weapons", "Battle Axes");
        add(tags, "weapons.bow", "weapons", "Bows");
        add(tags, "weapons.crossbow", "weapons", "Crossbows");
        add(tags, "weapons.trident", "weapons", "Tridents");
        add(tags, "weapons.arrow", "weapons", "Arrows");
        add(tags, "weapons.shield", "weapons", "Shields");
        addCategory(tags, "armor", "Armor");
        add(tags, "armor.helmet", "armor", "Helmets");
        add(tags, "armor.chestplate", "armor", "Chestplates");
        add(tags, "armor.leggings", "armor", "Leggings");
        add(tags, "armor.boots", "armor", "Boots");
        add(tags, "armor.elytra", "armor", "Elytra");
        add(tags, "armor.horse", "armor", "Horse Armor");
        addCategory(tags, "tools", "Tools");
        add(tags, "tools.pickaxe", "tools", "Pickaxes");
        add(tags, "tools.axe", "tools", "Axes");
        add(tags, "tools.shovel", "tools", "Shovels");
        add(tags, "tools.hoe", "tools", "Hoes");
        add(tags, "tools.shears", "tools", "Shears");
        add(tags, "tools.fishing_rod", "tools", "Fishing Rods");
        add(tags, "tools.brush", "tools", "Brushes");
        add(tags, "tools.navigation", "tools", "Navigation");
        add(tags, "tools.utility", "tools", "Utility Tools");
        addCategory(tags, "food", "Food");
        add(tags, "food.raw", "food", "Raw Food");
        add(tags, "food.cooked", "food", "Cooked Food");
        add(tags, "food.bread", "food", "Bread and Baked Food");
        add(tags, "food.fruit", "food", "Fruit");
        add(tags, "food.vegetable", "food", "Vegetables");
        add(tags, "food.stew", "food", "Stews and Soups");
        add(tags, "food.golden", "food", "Golden Food");
        add(tags, "food.sweets", "food", "Sweets");
        add(tags, "food.drink", "food", "Drinks");
        addCategory(tags, "building", "Building Blocks");
        add(tags, "building.stone", "building", "Stone");
        add(tags, "building.wood", "building", "Wood");
        add(tags, "building.glass", "building", "Glass");
        add(tags, "building.metal", "building", "Metal Blocks");
        add(tags, "building.earth", "building", "Earth and Sand");
        add(tags, "building.brick", "building", "Bricks");
        add(tags, "building.concrete", "building", "Concrete");
        add(tags, "building.terracotta", "building", "Terracotta");
        add(tags, "building.wool", "building", "Wool");
        add(tags, "building.nether", "building", "Nether Blocks");
        add(tags, "building.end", "building", "End Blocks");
        add(tags, "building.copper", "building", "Copper Blocks");
        addCategory(tags, "decoration", "Decoration");
        add(tags, "decoration.flowers", "decoration", "Flowers");
        add(tags, "decoration.plants", "decoration", "Plants");
        add(tags, "decoration.banner", "decoration", "Banners");
        add(tags, "decoration.painting", "decoration", "Paintings");
        add(tags, "decoration.carpet", "decoration", "Carpets");
        add(tags, "decoration.candle", "decoration", "Candles");
        add(tags, "decoration.head", "decoration", "Heads");
        add(tags, "decoration.pottery", "decoration", "Pottery");
        add(tags, "decoration.light", "decoration", "Lighting");
        addCategory(tags, "redstone", "Redstone");
        add(tags, "redstone.component", "redstone", "Components");
        add(tags, "redstone.piston", "redstone", "Pistons");
        add(tags, "redstone.sensor", "redstone", "Sensors");
        add(tags, "redstone.rail", "redstone", "Rails");
        add(tags, "redstone.machine", "redstone", "Machines");
        add(tags, "redstone.storage", "redstone", "Storage");
        addCategory(tags, "materials", "Materials");
        add(tags, "materials.ore", "materials", "Ores");
        add(tags, "materials.ingot", "materials", "Ingots");
        add(tags, "materials.gem", "materials", "Gems");
        add(tags, "materials.nugget", "materials", "Nuggets");
        add(tags, "materials.dust", "materials", "Dusts");
        add(tags, "materials.leather", "materials", "Leather");
        add(tags, "materials.string", "materials", "String and Fiber");
        add(tags, "materials.dye", "materials", "Dyes");
        add(tags, "materials.smithing", "materials", "Smithing Materials");
        addCategory(tags, "enchanting", "Enchanting");
        add(tags, "enchanting.book", "enchanting", "Enchanted Books");
        add(tags, "enchanting.lapis", "enchanting", "Lapis Lazuli");
        add(tags, "enchanting.table", "enchanting", "Enchanting Stations");
        add(tags, "enchanting.experience", "enchanting", "Experience Items");
        addCategory(tags, "alchemy", "Potions and Alchemy");
        add(tags, "alchemy.potion", "alchemy", "Potions");
        add(tags, "alchemy.splash", "alchemy", "Splash Potions");
        add(tags, "alchemy.lingering", "alchemy", "Lingering Potions");
        add(tags, "alchemy.ingredient", "alchemy", "Brewing Ingredients");
        add(tags, "alchemy.station", "alchemy", "Brewing Stations");
        addCategory(tags, "farming", "Farming");
        add(tags, "farming.seed", "farming", "Seeds");
        add(tags, "farming.crop", "farming", "Crops");
        add(tags, "farming.sapling", "farming", "Saplings");
        add(tags, "farming.bone_meal", "farming", "Bone Meal");
        add(tags, "farming.animal", "farming", "Animal Products");
        add(tags, "farming.bee", "farming", "Bee Products");
        addCategory(tags, "mob_drops", "Mob Drops");
        add(tags, "mob_drops.hostile", "mob_drops", "Hostile Mob Drops");
        add(tags, "mob_drops.passive", "mob_drops", "Passive Mob Drops");
        add(tags, "mob_drops.boss", "mob_drops", "Boss Drops");
        add(tags, "mob_drops.nether", "mob_drops", "Nether Mob Drops");
        add(tags, "mob_drops.end", "mob_drops", "End Mob Drops");
        addCategory(tags, "transport", "Transport");
        add(tags, "transport.boat", "transport", "Boats");
        add(tags, "transport.minecart", "transport", "Minecarts");
        add(tags, "transport.rail", "transport", "Rails");
        add(tags, "transport.saddle", "transport", "Saddles");
        add(tags, "transport.elytra", "transport", "Flight");
        addCategory(tags, "storage", "Storage");
        add(tags, "storage.chest", "storage", "Chests");
        add(tags, "storage.barrel", "storage", "Barrels");
        add(tags, "storage.shulker", "storage", "Shulker Boxes");
        add(tags, "storage.bundle", "storage", "Portable Storage");
        addCategory(tags, "rare", "Rare Items");
        add(tags, "rare.netherite", "rare", "Netherite");
        add(tags, "rare.beacon", "rare", "Beacons");
        add(tags, "rare.totem", "rare", "Totems");
        add(tags, "rare.template", "rare", "Smithing Templates");
        add(tags, "rare.collectible", "rare", "Collectibles");
        addCategory(tags, "music", "Music");
        add(tags, "music.disc", "music", "Music Discs");
        add(tags, "music.instrument", "music", "Instruments");
        addCategory(tags, "books", "Books and Maps");
        add(tags, "books.written", "books", "Written Books");
        add(tags, "books.map", "books", "Maps");
        add(tags, "books.pattern", "books", "Patterns");
        addCategory(tags, "misc", "Miscellaneous");
        add(tags, "misc.bulk", "misc", "Bulk Lots");
        add(tags, "misc.custom", "misc", "Custom Items");
        add(tags, "misc.other", "misc", "Other");
        return List.copyOf(tags);
    }

    private static void addCategory(List<MarketTag> tags, String id, String label) {
        tags.add(new MarketTag(id, translationKey(id), label, "", tags.size(), true));
    }

    private static void add(List<MarketTag> tags, String id, String parentId, String label) {
        tags.add(new MarketTag(id, translationKey(id), label, parentId, tags.size(), true));
    }

    private static String translationKey(String id) {
        return "tag.freemarket." + id.replace('.', '_');
    }
}
