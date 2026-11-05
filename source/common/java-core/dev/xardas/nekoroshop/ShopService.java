package dev.xardas.nekoroshop;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Everything the shop does on the server. Every action is checked again here, whatever the client showed. */
public final class ShopService {
    private ShopService() { }

    public static String key(String name) {
        return NekoroShop.MOD_ID + "." + name;
    }

    public static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** "1,250 NC" in gold. */
    public static MutableComponent money(long value) {
        return Component.literal(format(value) + " " + ShopConfig.currencyShort).withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent pieces(int amount, ItemStack stack) {
        return Component.translatable(key("pieces"), format(amount), stack.getHoverName());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** New players get the start balance; the name is kept for showing offers while the seller is offline. */
    private static ShopData ensure(ServerPlayer player) {
        ShopData data = ShopData.get(player.server);
        data.setName(player.getUUID(), player.getGameProfile().getName());
        if (!data.known(player.getUUID())) data.setBalance(player.getUUID(), ShopConfig.startBalance);
        return data;
    }

    public static void onLogin(ServerPlayer player) {
        ShopData data = ensure(player);
        long earned = data.takeEarnedOffline(player.getUUID());
        if (earned > 0) player.sendSystemMessage(Component.translatable(key("chat.offline"), money(earned)));
        int expired = data.expiredCount(player.getUUID(), now());
        if (expired > 0) player.sendSystemMessage(Component.translatable(key("chat.expired"), expired));
    }

    public static void onOpen(ServerPlayer player) {
        ensure(player);
        sendRules(player);
        sendBalance(player);
        sendMarket(player);
    }

    private static Payloads.Entry entryFor(ShopData data, ServerPlayer player, ShopConfig.Entry e, String list) {
        String k = list + "|" + itemKey(e.stack());
        int used = e.dailyLimit() > 0 ? data.usedToday(player.getUUID(), k) : 0;
        int serverUsed = e.serverLimit() > 0 ? data.serverToday(k) : 0;
        return new Payloads.Entry(e.stack(), e.price(), e.dailyLimit(), e.serverLimit(), used, serverUsed);
    }

    private static Payloads.Rules rules(ServerPlayer player) {
        ShopData data = ShopData.get(player.server);
        List<Payloads.Category> categories = new ArrayList<>();
        for (ShopConfig.Category c : ShopConfig.categories) {
            categories.add(new Payloads.Category(c.name(), icon(c.icon()), c.items()));
        }
        List<Payloads.Entry> buy = new ArrayList<>();
        for (ShopConfig.Entry e : ShopConfig.buy) buy.add(entryFor(data, player, e, "buy"));
        List<Payloads.Entry> sell = new ArrayList<>();
        for (ShopConfig.Entry e : ShopConfig.sell) sell.add(entryFor(data, player, e, "sell"));
        return new Payloads.Rules(ShopConfig.currency, ShopConfig.currencyShort, ShopConfig.currencyIcon == null ? ItemStack.EMPTY : icon(ShopConfig.currencyIcon), ShopConfig.marketEnabled,
                ShopConfig.taxPercent, ShopConfig.maxOffers, ShopConfig.blocked, operator(player), categories, ShopConfig.creativeTabs, buy, sell);
    }

    private static ItemStack icon(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static String itemKey(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean operator(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    private static void sendRules(ServerPlayer player) {
        Platform.INSTANCE.sendToPlayer(player, rules(player));
    }

    public static void sendBalance(ServerPlayer player) {
        Platform.INSTANCE.sendToPlayer(player, new Payloads.Balance(ShopData.get(player.server).balance(player.getUUID())));
    }

    private static void sendMarket(ServerPlayer player) {
        ShopData data = ShopData.get(player.server);
        List<Payloads.Offer> offers = new ArrayList<>();
        long now = now();
        if (ShopConfig.marketEnabled) {
            for (ShopData.Offer o : data.offers) {
                boolean own = o.seller.equals(player.getUUID());
                // an expired offer is only for its seller, to take back or list again
                if (o.expired(now) && !own) continue;
                long remaining = o.expires > 0 ? o.expires - now : -1;
                long duration = o.expires > 0 ? Math.max(1, o.expires - o.created) : 0;
                offers.add(new Payloads.Offer(o.id, o.stack, o.amount, o.price, data.name(o.seller), own, now - o.created, remaining, duration));
            }
        }
        Platform.INSTANCE.sendToPlayer(player, new Payloads.Market(offers));
    }

    private static boolean inShop(ServerPlayer player) {
        return player.containerMenu instanceof ShopMenu;
    }

    /** Players looking at the shop get the changed server shop or market. */
    private static void refreshOpen(MinecraftServer server, boolean shop, boolean market) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!inShop(p)) continue;
            if (shop) sendRules(p);
            if (market) sendMarket(p);
        }
    }

    /** After the config was reloaded. */
    public static void refreshAll(MinecraftServer server) {
        refreshOpen(server, true, true);
    }

    private static void notice(ServerPlayer player, Component text, boolean ok) {
        Platform.INSTANCE.sendToPlayer(player, new Payloads.Notice(text, ok));
    }

    private static void error(ServerPlayer player, String name, Object... args) {
        notice(player, Component.translatable(key("error." + name), args), false);
    }

    public static void handle(ServerPlayer player, Payloads.Trade t) {
        if (!inShop(player)) return;
        ensure(player);
        switch (t.action()) {
            case Payloads.Trade.BUY -> trade(player, t, false);
            case Payloads.Trade.SELL -> trade(player, t, true);
            case Payloads.Trade.BUY_OFFER -> buyOffer(player, t);
            case Payloads.Trade.OFFER -> offer(player, t);
            case Payloads.Trade.TAKE_BACK -> takeBack(player, t);
            case Payloads.Trade.REPRICE -> reprice(player, t, false);
            case Payloads.Trade.RELIST -> reprice(player, t, true);
            case Payloads.Trade.ADD_BUY, Payloads.Trade.ADD_SELL -> add(player, t, t.action() == Payloads.Trade.ADD_SELL);
            case Payloads.Trade.EDIT_BUY, Payloads.Trade.EDIT_SELL -> edit(player, t, t.action() == Payloads.Trade.EDIT_SELL, false);
            case Payloads.Trade.REMOVE_BUY, Payloads.Trade.REMOVE_SELL -> edit(player, t, t.action() == Payloads.Trade.REMOVE_SELL, true);
            default -> { }
        }
    }

    /** The server shop entry the client clicked, or null (and a message) when the shop changed meanwhile. */
    private static ShopConfig.Entry entry(ServerPlayer player, List<ShopConfig.Entry> list, long index, Item item) {
        if (index < 0 || index >= list.size() || list.get((int) index).stack().getItem() != item) {
            error(player, "changed");
            sendRules(player);
            return null;
        }
        return list.get((int) index);
    }

    /** price * amount, or -1 when it is too big to pay anyway. */
    private static long cost(long price, int amount) {
        try {
            return Math.multiplyExact(price, (long) amount);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    /** Buying from (sell = false) or selling to the server shop, within the daily limits. */
    private static void trade(ServerPlayer player, Payloads.Trade t, boolean sell) {
        ShopConfig.Entry entry = entry(player, sell ? ShopConfig.sell : ShopConfig.buy, t.target(), t.item());
        if (entry == null || t.amount() <= 0) return;
        ShopData data = ShopData.get(player.server);
        String limitKey = (sell ? "sell|" : "buy|") + itemKey(entry.stack());
        int amount = t.amount();
        boolean limited = entry.dailyLimit() > 0 || entry.serverLimit() > 0;
        if (entry.dailyLimit() > 0) amount = Math.min(amount, entry.dailyLimit() - data.usedToday(player.getUUID(), limitKey));
        if (entry.serverLimit() > 0) amount = Math.min(amount, entry.serverLimit() - data.serverToday(limitKey));
        if (limited && amount <= 0) {
            error(player, "limit");
            return;
        }
        long value;
        if (sell) {
            amount = Math.min(amount, Inventories.count(player, entry.stack()));
            if (amount <= 0) {
                error(player, "nothing");
                return;
            }
            if (cost(entry.price(), amount) < 0) return;
            amount = Inventories.remove(player, entry.stack(), amount);
            value = entry.price() * amount;
            data.add(player.getUUID(), value);
        } else {
            amount = Math.min(amount, Inventories.space(player, entry.stack()));
            if (amount <= 0) {
                error(player, "space");
                return;
            }
            value = cost(entry.price(), amount);
            if (value < 0 || data.balance(player.getUUID()) < value) {
                error(player, "money", ShopConfig.currencyShort);
                return;
            }
            data.add(player.getUUID(), -value);
            Inventories.give(player, entry.stack(), amount);
        }
        if (limited) {
            data.addToday(player.getUUID(), limitKey, amount);
            // a server-wide limit changes what everyone sees
            if (entry.serverLimit() > 0) refreshOpen(player.server, true, false);
            else sendRules(player);
        }
        sendBalance(player);
        notice(player, Component.translatable(key(sell ? "notice.sold" : "notice.bought"), pieces(amount, entry.stack()), money(value)), true);
    }

    private static ShopData.Offer offerOf(ServerPlayer player, Payloads.Trade t) {
        ShopData.Offer offer = ShopData.get(player.server).offer(t.target());
        if (offer == null || offer.stack.getItem() != t.item()) {
            error(player, "gone");
            sendMarket(player);
            return null;
        }
        return offer;
    }

    private static void buyOffer(ServerPlayer player, Payloads.Trade t) {
        if (!ShopConfig.marketEnabled || t.amount() <= 0) return;
        ShopData.Offer offer = offerOf(player, t);
        if (offer == null) return;
        if (offer.seller.equals(player.getUUID())) {
            error(player, "own");
            return;
        }
        if (offer.expired(now())) {
            error(player, "gone");
            sendMarket(player);
            return;
        }
        int amount = Math.min(Math.min(t.amount(), offer.amount), Inventories.space(player, offer.stack));
        if (amount <= 0) {
            error(player, "space");
            return;
        }
        ShopData data = ShopData.get(player.server);
        long cost = cost(offer.price, amount);
        if (cost < 0 || data.balance(player.getUUID()) < cost) {
            error(player, "money", ShopConfig.currencyShort);
            return;
        }
        long earned = cost - cost * ShopConfig.taxPercent / 100;
        data.add(player.getUUID(), -cost);
        data.add(offer.seller, earned);
        offer.amount -= amount;
        if (offer.amount <= 0) data.offers.remove(offer);
        data.setDirty();
        Inventories.give(player, offer.stack, amount);
        sendBalance(player);
        notice(player, Component.translatable(key("notice.bought"), pieces(amount, offer.stack), money(cost)), true);

        ServerPlayer seller = player.server.getPlayerList().getPlayer(offer.seller);
        if (seller != null) {
            seller.sendSystemMessage(Component.translatable(key("chat.sold"), player.getDisplayName(), pieces(amount, offer.stack), money(earned)));
            sendBalance(seller);
        } else {
            data.addEarnedOffline(offer.seller, earned);
        }
        refreshOpen(player.server, false, true);
    }

    private static void offer(ServerPlayer player, Payloads.Trade t) {
        if (!ShopConfig.marketEnabled) {
            error(player, "market");
            return;
        }
        if (t.target() < 0 || t.target() >= player.getInventory().items.size() || t.amount() <= 0) return;
        ItemStack clicked = player.getInventory().items.get((int) t.target());
        if (clicked.isEmpty() || clicked.getItem() != t.item()) {
            error(player, "changed");
            return;
        }
        if (ShopConfig.blockedFilter.matches(clicked.getItem())) {
            error(player, "blocked");
            return;
        }
        if (t.price() <= 0 || t.price() > ShopConfig.MAX_PRICE) {
            error(player, "price");
            return;
        }
        ItemStack template = clicked.copyWithCount(1);
        ShopData data = ShopData.get(player.server);
        long now = now();
        // the same item at the same price joins an own offer that is still on the market (and starts its time again)
        ShopData.Offer existing = null;
        for (ShopData.Offer o : data.offers) {
            if (o.seller.equals(player.getUUID()) && o.price == t.price() && !o.expired(now) && Inventories.same(o.stack, template)) existing = o;
        }
        if (existing == null && data.offerCount(player.getUUID()) >= ShopConfig.maxOffers) {
            error(player, "offers", ShopConfig.maxOffers);
            return;
        }
        int amount = Inventories.remove(player, template, t.amount());
        if (amount <= 0) return;
        if (existing != null) {
            existing.amount = (int) Math.min(Integer.MAX_VALUE, (long) existing.amount + amount);
            existing.renew(now);
            data.setDirty();
        } else {
            long expires = ShopConfig.offerDuration > 0 ? now + ShopConfig.offerDuration : 0;
            data.addOffer(player.getUUID(), template, amount, t.price(), now, expires);
        }
        notice(player, Component.translatable(key("notice.offered"), pieces(amount, template), money(t.price())), true);
        refreshOpen(player.server, false, true);
    }

    private static ShopData.Offer ownOffer(ServerPlayer player, Payloads.Trade t) {
        ShopData.Offer offer = offerOf(player, t);
        if (offer != null && !offer.seller.equals(player.getUUID())) return null;
        return offer;
    }

    private static void takeBack(ServerPlayer player, Payloads.Trade t) {
        ShopData.Offer offer = ownOffer(player, t);
        if (offer == null || t.amount() <= 0) return;
        int amount = Math.min(Math.min(t.amount(), offer.amount), Inventories.space(player, offer.stack));
        if (amount <= 0) {
            error(player, "space");
            return;
        }
        ShopData data = ShopData.get(player.server);
        offer.amount -= amount;
        if (offer.amount <= 0) data.offers.remove(offer);
        data.setDirty();
        Inventories.give(player, offer.stack, amount);
        notice(player, Component.translatable(key("notice.taken"), pieces(amount, offer.stack)), true);
        refreshOpen(player.server, false, true);
    }

    /** A new price for an own offer; relist also puts it back on the market for the whole time. */
    private static void reprice(ServerPlayer player, Payloads.Trade t, boolean relist) {
        ShopData.Offer offer = ownOffer(player, t);
        if (offer == null) return;
        if (t.price() <= 0 || t.price() > ShopConfig.MAX_PRICE) {
            error(player, "price");
            return;
        }
        offer.price = t.price();
        if (relist) offer.renew(now());
        ShopData.get(player.server).setDirty();
        notice(player, relist ? Component.translatable(key("notice.relisted"), pieces(offer.amount, offer.stack), money(t.price()))
                : Component.translatable(key("notice.repriced"), money(t.price())), true);
        refreshOpen(player.server, false, true);
    }

    /** Operators: an item from the inventory goes to the server shop (or gets the new price if it is there). */
    private static void add(ServerPlayer player, Payloads.Trade t, boolean sellList) {
        if (!operator(player)) return;
        if (t.target() < 0 || t.target() >= player.getInventory().items.size()) return;
        ItemStack clicked = player.getInventory().items.get((int) t.target());
        if (clicked.isEmpty() || clicked.getItem() != t.item()) {
            error(player, "changed");
            return;
        }
        if (t.price() <= 0 || t.price() > ShopConfig.MAX_PRICE) {
            error(player, "price");
            return;
        }
        List<ShopConfig.Entry> list = sellList ? ShopConfig.sell : ShopConfig.buy;
        ShopConfig.Entry entry = new ShopConfig.Entry(clicked.copyWithCount(1), t.price(), Math.max(0, t.limit()), Math.max(0, t.serverLimit()));
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (Inventories.same(list.get(i).stack(), entry.stack())) at = i;
        }
        if (at >= 0) list.set(at, entry);
        else list.add(entry);
        ShopConfig.saveShop(player.server.registryAccess());
        notice(player, Component.translatable(key(at >= 0 ? "notice.changed" : "notice.added"), entry.stack().getHoverName()), true);
        refreshOpen(player.server, true, false);
    }

    private static void edit(ServerPlayer player, Payloads.Trade t, boolean sellList, boolean remove) {
        if (!operator(player)) return;
        List<ShopConfig.Entry> list = sellList ? ShopConfig.sell : ShopConfig.buy;
        ShopConfig.Entry entry = entry(player, list, t.target(), t.item());
        if (entry == null) return;
        if (remove) {
            list.remove((int) t.target());
        } else {
            if (t.price() <= 0 || t.price() > ShopConfig.MAX_PRICE) {
                error(player, "price");
                return;
            }
            list.set((int) t.target(), new ShopConfig.Entry(entry.stack(), t.price(), Math.max(0, t.limit()), Math.max(0, t.serverLimit())));
        }
        ShopConfig.saveShop(player.server.registryAccess());
        notice(player, Component.translatable(key(remove ? "notice.removed" : "notice.changed"), entry.stack().getHoverName()), true);
        refreshOpen(player.server, true, false);
    }

    // ---- used by the commands

    public static long balance(MinecraftServer server, UUID player) {
        return ShopData.get(server).balance(player);
    }

    public static void change(ServerPlayer player, long amount, boolean set) {
        ShopData data = ensure(player);
        if (set) data.setBalance(player.getUUID(), amount);
        else data.add(player.getUUID(), amount);
        sendBalance(player);
    }

    /** Moves money between two players; false when the payer has too little. */
    public static boolean pay(ServerPlayer from, ServerPlayer to, long amount) {
        ShopData data = ensure(from);
        ensure(to);
        if (data.balance(from.getUUID()) < amount) return false;
        data.add(from.getUUID(), -amount);
        data.add(to.getUUID(), amount);
        sendBalance(from);
        sendBalance(to);
        return true;
    }

    private static final int TEST_SELLERS = 5;

    private static UUID testSeller(int i) {
        return UUID.nameUUIDFromBytes(("nekoroshop test seller " + i).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * For trying the market alone: offers from made-up sellers ("Test Seller 1" to 5) with random items, prices and
     * time left, plus three own offers of the player (expired, nearly expired, half way). Items come from nowhere.
     */
    public static int addTestOffers(ServerPlayer player, int count) {
        ShopData data = ShopData.get(player.server);
        RandomSource random = player.getRandom();
        long now = now();
        long duration = ShopConfig.offerDuration > 0 ? ShopConfig.offerDuration : 7L * 24 * 3600 * 1000;
        List<ItemStack> pool = new ArrayList<>();
        for (ShopConfig.Entry e : ShopConfig.buy) pool.add(e.stack());
        for (Item item : new Item[] { Items.OAK_LOG, Items.COBBLESTONE, Items.IRON_INGOT, Items.DIAMOND, Items.BREAD, Items.GLASS,
                Items.WHITE_WOOL, Items.REDSTONE, Items.EMERALD, Items.APPLE, Items.TORCH, Items.BRICKS }) {
            pool.add(new ItemStack(item));
        }
        for (int i = 1; i <= TEST_SELLERS; i++) data.setName(testSeller(i), "Test Seller " + i);
        for (int i = 0; i < count; i++) {
            ItemStack stack = pool.get(random.nextInt(pool.size()));
            double left = 0.02 + random.nextDouble() * 0.98;
            long created = now - (long) ((1 - left) * duration);
            long price = 1 + random.nextInt(500) * (random.nextInt(4) == 0 ? 100L : 1L);
            data.addOffer(testSeller(1 + random.nextInt(TEST_SELLERS)), stack, 1 + random.nextInt(64), price, created, created + duration);
        }
        double[] own = { -0.1, 0.05, 0.4 };
        for (double left : own) {
            ItemStack stack = pool.get(random.nextInt(pool.size()));
            long created = now - (long) ((1 - left) * duration);
            data.addOffer(player.getUUID(), stack, 1 + random.nextInt(32), 1 + random.nextInt(200), created, created + duration);
        }
        refreshOpen(player.server, false, true);
        return count + own.length;
    }

    public static int clearTestOffers(MinecraftServer server) {
        ShopData data = ShopData.get(server);
        List<UUID> fake = new ArrayList<>();
        for (int i = 1; i <= TEST_SELLERS; i++) fake.add(testSeller(i));
        int before = data.offers.size();
        data.offers.removeIf(o -> fake.contains(o.seller));
        data.setDirty();
        refreshOpen(server, false, true);
        return before - data.offers.size();
    }
}
