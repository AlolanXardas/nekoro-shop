package dev.xardas.nekoroshop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The files in config/Nekoro/Shop: shop.json (money and market settings), categories.json (the tabs) and
 * server_shop.json (what the server sells and buys; operators change it in game, so it has no comments).
 */
public final class ShopConfig {
    private ShopConfig() { }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Category(String name, ResourceLocation icon, List<String> items) { }

    /**
     * One item of the server shop, priced per piece. dailyLimit is per player, serverLimit for all players together;
     * 0 means no limit.
     */
    public record Entry(ItemStack stack, long price, int dailyLimit, int serverLimit) { }

    public static String currency = "Nekoro Coins";
    public static String currencyShort = "NC";
    /** How long a market offer stays, in milliseconds; 0 = forever. */
    public static long offerDuration = 7L * 24 * 3600 * 1000;
    /** The item drawn next to the balance, or null for none (the default). */
    public static ResourceLocation currencyIcon;
    public static long startBalance;
    public static boolean playerCommands = true;
    public static boolean marketEnabled = true;
    public static int taxPercent;
    public static int maxOffers = 54;
    public static List<String> blocked = List.of();
    public static ItemFilter blockedFilter = ItemFilter.NONE;
    public static List<Category> categories = List.of();
    public static boolean creativeTabs = true;
    public static List<Entry> buy = new ArrayList<>();
    public static List<Entry> sell = new ArrayList<>();

    /** The highest price per piece, so prices times amounts stay far from overflowing. */
    public static final long MAX_PRICE = 1_000_000_000_000L;

    private static Path dir() {
        return Platform.INSTANCE.configDir().resolve("Nekoro").resolve("Shop");
    }

    private static boolean coins() {
        return Platform.INSTANCE.isModLoaded("nekorocoins");
    }

    /** The coins of Nekoro Coins that work as money by default: the item and its value. */
    private static Map<String, Long> defaultCoins() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("nekorocoins:coin006", 1L);          // Copper Coin
        out.put("nekorocoins:coin036", 10L);         // Iron Coin
        out.put("nekorocoins:coin010", 100L);        // Gold Coin
        out.put("nekorocoins:coin016", 1_000L);      // Emerald Coin
        out.put("nekorocoins:coin031", 10_000L);     // Amethyst Coin
        out.put("nekorocoins:coin021", 100_000L);    // Diamond Coin
        out.put("nekorocoins:coin040", 1_000_000L);  // Netherite Coin
        return out;
    }

    /** shop.json and categories.json; they need no registries, so they are read early (also for the commands). */
    public static void loadSettings() {
        JsonObject o = JsonFile.read(dir().resolve("shop.json"), defaultSettings());
        currency = JsonFile.string(o, "currency_name", "Nekoro Coins");
        currencyShort = JsonFile.string(o, "currency_short", "NC");
        String icon = JsonFile.string(o, "currency_icon", "").trim();
        currencyIcon = icon.isEmpty() ? null : ResourceLocation.tryParse(icon);
        startBalance = Math.max(0, JsonFile.number(o, "start_balance", 0));
        playerCommands = JsonFile.bool(o, "player_commands", true);
        JsonObject market = JsonFile.obj(o, "market");
        marketEnabled = JsonFile.bool(market, "enabled", true);
        taxPercent = Math.max(0, Math.min(100, JsonFile.integer(market, "tax_percent", 0)));
        maxOffers = Math.max(1, Math.min(1000, JsonFile.integer(market, "max_offers", 54)));
        double days = market.has("offer_days") ? market.get("offer_days").getAsDouble() : 7;
        offerDuration = days <= 0 ? 0 : (long) (Math.min(days, 3650) * 24 * 3600 * 1000);
        blocked = JsonFile.strings(market, "blocked");
        blockedFilter = ItemFilter.of(blocked);

        JsonObject c = JsonFile.read(dir().resolve("categories.json"), defaultCategories());
        creativeTabs = JsonFile.bool(c, "creative_tabs", true);
        List<Category> list = new ArrayList<>();
        if (c.has("categories") && c.get("categories").isJsonArray()) {
            for (JsonElement e : c.getAsJsonArray("categories")) {
                if (!e.isJsonObject()) continue;
                JsonObject t = e.getAsJsonObject();
                ResourceLocation tabIcon = ResourceLocation.tryParse(JsonFile.string(t, "icon", "minecraft:chest"));
                list.add(new Category(JsonFile.string(t, "name", "?"),
                        tabIcon != null ? tabIcon : ResourceLocation.withDefaultNamespace("chest"), JsonFile.strings(t, "items")));
            }
        }
        categories = List.copyOf(list);
    }

    /** server_shop.json, which needs the registries for item components (e.g. potions). */
    public static void loadShop(HolderLookup.Provider registries) {
        Path file = dir().resolve("server_shop.json");
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, defaultShop(), StandardCharsets.UTF_8);
            }
            JsonObject o = JsonFile.read(file, defaultShop());
            buy = readEntries(o, "buy", registries);
            sell = readEntries(o, "sell", registries);
        } catch (Exception e) {
            NekoroShop.LOGGER.error("Could not read {}", file, e);
        }
    }

    private static List<Entry> readEntries(JsonObject o, String key, HolderLookup.Provider registries) {
        List<Entry> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            ResourceLocation id = ResourceLocation.tryParse(JsonFile.string(t, "item", ""));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) {
                // a mod that is not installed: skipped, and dropped from the file at the next change in game
                NekoroShop.LOGGER.warn("Unknown item \"{}\" in server_shop.json, skipped", JsonFile.string(t, "item", ""));
                continue;
            }
            DataComponentPatch patch = DataComponentPatch.EMPTY;
            if (t.has("components")) {
                patch = DataComponentPatch.CODEC.parse(ops, t.get("components"))
                        .resultOrPartial(error -> NekoroShop.LOGGER.warn("Bad components of {} in server_shop.json: {}", id, error))
                        .orElse(DataComponentPatch.EMPTY);
            }
            long price = Math.max(1, Math.min(MAX_PRICE, JsonFile.number(t, "price", 1)));
            int limit = Math.max(0, JsonFile.integer(t, "daily_limit", 0));
            int serverLimit = Math.max(0, JsonFile.integer(t, "server_daily_limit", 0));
            out.add(new Entry(new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), 1, patch), price, limit, serverLimit));
        }
        return out;
    }

    /** After a change made in game. */
    public static void saveShop(HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        JsonObject root = new JsonObject();
        root.add("buy", writeEntries(buy, ops));
        root.add("sell", writeEntries(sell, ops));
        Path file = dir().resolve("server_shop.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            NekoroShop.LOGGER.error("Could not save {}", file, e);
        }
    }

    private static JsonArray writeEntries(List<Entry> entries, RegistryOps<JsonElement> ops) {
        JsonArray out = new JsonArray();
        for (Entry entry : entries) {
            JsonObject t = new JsonObject();
            t.addProperty("item", BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString());
            DataComponentPatch patch = entry.stack().getComponentsPatch();
            if (!patch.isEmpty()) {
                DataComponentPatch.CODEC.encodeStart(ops, patch).result().ifPresent(json -> t.add("components", json));
            }
            t.addProperty("price", entry.price());
            if (entry.dailyLimit() > 0) t.addProperty("daily_limit", entry.dailyLimit());
            if (entry.serverLimit() > 0) t.addProperty("server_daily_limit", entry.serverLimit());
            out.add(t);
        }
        return out;
    }

    private static String defaultShop() {
        JsonObject root = new JsonObject();
        JsonArray buyList = new JsonArray(), sellList = new JsonArray();
        if (coins()) {
            // the coins are money you can hold: the server sells and buys them back for the same price
            defaultCoins().forEach((item, value) -> {
                JsonObject t = new JsonObject();
                t.addProperty("item", item);
                t.addProperty("price", value);
                buyList.add(t);
                sellList.add(t.deepCopy());
            });
        }
        root.add("buy", buyList);
        root.add("sell", sellList);
        return GSON.toJson(root) + "\n";
    }

    private static String defaultSettings() {
        return """
            {
              // The name of the money, and its short form used in the shop and in messages.
              "currency_name": "Nekoro Coins",
              "currency_short": "NC",
              // an item drawn small next to the balance, e.g. "nekorocoins:coin010"; empty = none
              "currency_icon": "",
              // money every new player starts with
              "start_balance": 0,
              // /balance and /pay for every player (/nekoroshop for operators is always there)
              "player_commands": true,
              // What the server sells (Buy) and buys (Sell) is in server_shop.json. Operators can change it in game with
              // the Edit button, or in the file: "item", optional "components", "price" per piece, and optional limits of
              // pieces per day: "daily_limit" for each player and "server_daily_limit" for all players together (0 = none).
              // The day follows the clock of the computer the server runs on.
              "market": {
                // players can sell items to each other at their own prices
                "enabled": true,
                // percent of every sale between players that disappears instead of going to the seller (0-100)
                "tax_percent": 0,
                // how many offers one player can have at the same time (expired ones count until taken back)
                "max_offers": 54,
                // days an offer stays on the market (decimals work, 0 = forever); then only its seller sees it,
                // and can take the items back or list them again
                "offer_days": 7,
                // items that cannot be offered: "minecraft:dirt" (one item), "#c:ores" (a tag), "somemod:*" (* matches any text)
                "blocked": []
              }
            }
            """;
    }

    private static String defaultCategories() {
        return """
            {
              // The category buttons above the shop. "All" is always first. With creative_tabs, every creative inventory
              // tab (vanilla and mods) is a category too, with its icon and name, holding what it holds in creative.
              "creative_tabs": true,
              // Own categories, shown after "All" and before the creative tabs, e.g.
              //   { "name": "Coins", "icon": "nekorocoins:coin010", "items": [ "#nekorocoins:coins" ] }
              // "items": "minecraft:bread" (one item), "#c:foods" (a tag), "cobblemon:*" or "minecraft:*_log"
              // (* matches any text), "*" (everything) and "[blocks]" (every block). An empty list shows what no other
              // own category shows. Categories with nothing in them at the moment are not shown.
              "categories": []
            }
            """;
    }
}
