package dev.xardas.nekoroshop;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Everything the shop keeps with the world (data/nekoroshop.dat): every player's money, the offers on the market
 * (their items are kept here until someone buys them or the seller takes them back), money earned while offline and
 * today's purchases and sales with the server for the daily limits.
 */
public final class ShopData extends SavedData {
    private static final String FILE = NekoroShop.MOD_ID;
    // the type only matters for upgrading old worlds; item stacks upgrade themselves when parsed
    private static final Factory<ShopData> FACTORY = new Factory<>(ShopData::new, ShopData::load, DataFixTypes.LEVEL);

    /** An offer on the market: amount pieces of stack (always of count 1) for price each. */
    public static final class Offer {
        public final long id;
        public final UUID seller;
        public final ItemStack stack;
        public int amount;
        public long price;
        /** When it was listed and when it leaves the market (real time in milliseconds; expires 0 = never). */
        public long created, expires;

        Offer(long id, UUID seller, ItemStack stack, int amount, long price, long created, long expires) {
            this.id = id;
            this.seller = seller;
            this.stack = stack;
            this.amount = amount;
            this.price = price;
            this.created = created;
            this.expires = expires;
        }

        public boolean expired(long now) {
            return expires > 0 && now >= expires;
        }

        /** Listed again from now, for the configured time. */
        public void renew(long now) {
            created = now;
            expires = ShopConfig.offerDuration > 0 ? now + ShopConfig.offerDuration : 0;
        }
    }

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Long> earnedOffline = new HashMap<>();
    public final List<Offer> offers = new ArrayList<>();
    private long nextOfferId = 1;
    /** Pieces bought and sold today, by player and "buy|item" or "sell|item"; cleared when the date changes. */
    private final Map<UUID, Map<String, Integer>> usedToday = new HashMap<>();
    /** The same for all players together. */
    private final Map<String, Integer> serverToday = new HashMap<>();
    private String day = "";

    public static ShopData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    public boolean known(UUID player) {
        return balances.containsKey(player);
    }

    public long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public void setBalance(UUID player, long value) {
        balances.put(player, Math.max(0, value));
        setDirty();
    }

    /** Adds (or with a negative amount takes) money; the balance never goes below 0. */
    public void add(UUID player, long amount) {
        long now = balance(player);
        long next = amount > 0 && now > Long.MAX_VALUE - amount ? Long.MAX_VALUE : now + amount;
        setBalance(player, next);
    }

    public String name(UUID player) {
        return names.getOrDefault(player, "?");
    }

    public void setName(UUID player, String name) {
        if (!name.equals(names.get(player))) {
            names.put(player, name);
            setDirty();
        }
    }

    public void addEarnedOffline(UUID player, long amount) {
        earnedOffline.merge(player, amount, Long::sum);
        setDirty();
    }

    public long takeEarnedOffline(UUID player) {
        Long value = earnedOffline.remove(player);
        if (value != null) setDirty();
        return value == null ? 0 : value;
    }

    public Offer addOffer(UUID seller, ItemStack stack, int amount, long price, long created, long expires) {
        Offer offer = new Offer(nextOfferId++, seller, stack.copyWithCount(1), amount, price, created, expires);
        offers.add(offer);
        setDirty();
        return offer;
    }

    public Offer offer(long id) {
        for (Offer offer : offers) {
            if (offer.id == id) return offer;
        }
        return null;
    }

    public int offerCount(UUID seller) {
        int count = 0;
        for (Offer offer : offers) {
            if (offer.seller.equals(seller)) count++;
        }
        return count;
    }

    public int expiredCount(UUID seller, long now) {
        int count = 0;
        for (Offer offer : offers) {
            if (offer.seller.equals(seller) && offer.expired(now)) count++;
        }
        return count;
    }

    private void checkDay() {
        String today = LocalDate.now().toString();
        if (!today.equals(day)) {
            day = today;
            usedToday.clear();
            serverToday.clear();
            setDirty();
        }
    }

    public int usedToday(UUID player, String key) {
        checkDay();
        Map<String, Integer> used = usedToday.get(player);
        return used == null ? 0 : used.getOrDefault(key, 0);
    }

    public int serverToday(String key) {
        checkDay();
        return serverToday.getOrDefault(key, 0);
    }

    public void addToday(UUID player, String key, int amount) {
        checkDay();
        usedToday.computeIfAbsent(player, p -> new HashMap<>()).merge(key, amount, Integer::sum);
        serverToday.merge(key, amount, Integer::sum);
        setDirty();
    }

    private static ShopData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopData data = new ShopData();
        for (Tag t : tag.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            if (!p.hasUUID("uuid")) continue;
            UUID id = p.getUUID("uuid");
            data.balances.put(id, Math.max(0, p.getLong("balance")));
            if (p.contains("name")) data.names.put(id, p.getString("name"));
            if (p.getLong("earned_offline") > 0) data.earnedOffline.put(id, p.getLong("earned_offline"));
            CompoundTag used = p.getCompound("used_today");
            for (String key : used.getAllKeys()) {
                data.usedToday.computeIfAbsent(id, x -> new HashMap<>()).put(key, used.getInt(key));
            }
        }
        CompoundTag server = tag.getCompound("server_today");
        for (String key : server.getAllKeys()) data.serverToday.put(key, server.getInt(key));
        data.day = tag.getString("day");
        data.nextOfferId = Math.max(1, tag.getLong("next_offer"));
        long now = System.currentTimeMillis();
        for (Tag t : tag.getList("offers", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) t;
            ItemStack stack = ItemStack.parseOptional(registries, o.getCompound("item"));
            if (stack.isEmpty() || !o.hasUUID("seller")) {
                // the item's mod was removed; the offer cannot be shown or bought any more
                NekoroShop.LOGGER.warn("Dropped a market offer whose item no longer exists: {}", o.getCompound("item"));
                continue;
            }
            long id = o.getLong("id");
            // offers from before offers could expire start their time now
            long created = o.contains("created") ? o.getLong("created") : now;
            long expires = o.contains("expires") ? o.getLong("expires")
                    : ShopConfig.offerDuration > 0 ? now + ShopConfig.offerDuration : 0;
            data.offers.add(new Offer(id, o.getUUID("seller"), stack.copyWithCount(1), Math.max(1, o.getInt("amount")),
                    Math.max(1, o.getLong("price")), created, expires));
            data.nextOfferId = Math.max(data.nextOfferId, id + 1);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        Set<UUID> all = new HashSet<>(balances.keySet());
        all.addAll(earnedOffline.keySet());
        all.addAll(usedToday.keySet());
        for (UUID id : all) {
            CompoundTag p = new CompoundTag();
            p.putUUID("uuid", id);
            p.putLong("balance", balance(id));
            if (names.containsKey(id)) p.putString("name", names.get(id));
            if (earnedOffline.containsKey(id)) p.putLong("earned_offline", earnedOffline.get(id));
            Map<String, Integer> used = usedToday.get(id);
            if (used != null && !used.isEmpty()) {
                CompoundTag u = new CompoundTag();
                used.forEach(u::putInt);
                p.put("used_today", u);
            }
            players.add(p);
        }
        tag.put("players", players);
        CompoundTag server = new CompoundTag();
        serverToday.forEach(server::putInt);
        tag.put("server_today", server);
        tag.putString("day", day);
        tag.putLong("next_offer", nextOfferId);
        ListTag list = new ListTag();
        for (Offer offer : offers) {
            CompoundTag o = new CompoundTag();
            o.putLong("id", offer.id);
            o.putUUID("seller", offer.seller);
            o.put("item", offer.stack.save(registries));
            o.putInt("amount", offer.amount);
            o.putLong("price", offer.price);
            o.putLong("created", offer.created);
            o.putLong("expires", offer.expires);
            list.add(o);
        }
        tag.put("offers", list);
        return tag;
    }
}
