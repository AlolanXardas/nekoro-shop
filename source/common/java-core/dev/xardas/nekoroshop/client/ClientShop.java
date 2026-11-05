package dev.xardas.nekoroshop.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import dev.xardas.nekoroshop.ItemFilter;
import dev.xardas.nekoroshop.Payloads;

/** What the client knows about the shop: settings and server shop, the player's money and the market. */
public final class ClientShop {
    private ClientShop() { }

    private static Payloads.Rules rules;
    private static long balance;
    private static List<Payloads.Offer> offers = List.of();
    /** When the market arrived, to count the offers' time from there. */
    private static long marketTime;
    private static int version, rulesVersion;
    private static List<ItemFilter> filters = List.of();
    /** Tabs with "*" show everything and do not count when finding what the empty "other" tab gets. */
    private static List<Boolean> everything = List.of();
    private static ItemFilter blocked = ItemFilter.NONE;

    private static Component notice;
    private static boolean noticeOk;
    private static long noticeUntil;

    public static void onRules(Payloads.Rules payload) {
        rules = payload;
        List<ItemFilter> f = new ArrayList<>();
        List<Boolean> e = new ArrayList<>();
        for (Payloads.Category c : payload.categories()) {
            f.add(ItemFilter.of(c.items()));
            e.add(c.items().contains("*"));
        }
        filters = f;
        everything = e;
        blocked = ItemFilter.of(payload.blocked());
        version++;
        rulesVersion++;
    }

    public static void onBalance(Payloads.Balance payload) {
        balance = payload.balance();
    }

    public static void onMarket(Payloads.Market payload) {
        offers = List.copyOf(payload.offers());
        marketTime = System.currentTimeMillis();
        version++;
    }

    public static void onNotice(Payloads.Notice payload) {
        notice(payload.text(), payload.ok());
    }

    /** Shows a message in the window for a few seconds. */
    public static void notice(Component text, boolean ok) {
        notice = text;
        noticeOk = ok;
        noticeUntil = System.currentTimeMillis() + 3500;
    }

    public static Component notice() {
        return System.currentTimeMillis() < noticeUntil ? notice : null;
    }

    public static boolean noticeOk() {
        return noticeOk;
    }

    public static Payloads.Rules rules() {
        return rules;
    }

    public static long balance() {
        return balance;
    }

    public static List<Payloads.Offer> offers() {
        return offers;
    }

    public static Payloads.Offer offer(long id) {
        for (Payloads.Offer o : offers) {
            if (o.id() == id) return o;
        }
        return null;
    }

    public static int ownOffers() {
        int count = 0;
        for (Payloads.Offer o : offers) {
            if (o.own()) count++;
        }
        return count;
    }

    /** Milliseconds left until the offer leaves the market, or -1 when it never does. */
    public static long remaining(Payloads.Offer offer) {
        if (offer.duration() <= 0) return -1;
        return Math.max(0, offer.remaining() - (System.currentTimeMillis() - marketTime));
    }

    public static boolean expired(Payloads.Offer offer) {
        return offer.duration() > 0 && remaining(offer) <= 0;
    }

    /** How long ago it was listed. */
    public static long age(Payloads.Offer offer) {
        return offer.age() + (System.currentTimeMillis() - marketTime);
    }

    /** The part of its time an offer has left, 0 to 1 (1 for offers that never expire). */
    public static double timeLeft(Payloads.Offer offer) {
        if (offer.duration() <= 0) return 1;
        return Math.min(1, remaining(offer) / (double) offer.duration());
    }

    /** Changes only when the settings and the server shop arrive (not with the market). */
    public static int rulesVersion() {
        return rulesVersion;
    }

    public static int version() {
        return version;
    }

    public static boolean blocked(Item item) {
        return blocked.matches(item);
    }

    /** Whether the item belongs to the tab: its list matches it, or an empty list and no other tab takes it. */
    public static boolean inCategory(int index, Item item) {
        if (index < 0 || index >= filters.size()) return true;
        ItemFilter filter = filters.get(index);
        if (!filter.isEmpty()) return filter.matches(item);
        for (int i = 0; i < filters.size(); i++) {
            if (i != index && !everything.get(i) && filters.get(i).matches(item)) return false;
        }
        return true;
    }
}
