package dev.xardas.nekoroshop;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The packets of the mod. Handlers live in ShopService (server) and ClientShop (client). */
public final class Payloads {
    private Payloads() { }

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);

    /**
     * One item of the server shop. The limits are pieces per day for this player and for all players together
     * (0 = none); used and serverUsed are how many pieces were already bought or sold today.
     */
    public record Entry(ItemStack stack, long price, int playerLimit, int serverLimit, int used, int serverUsed) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Entry::stack,
                ByteBufCodecs.VAR_LONG, Entry::price,
                ByteBufCodecs.VAR_INT, Entry::playerLimit,
                ByteBufCodecs.VAR_INT, Entry::serverLimit,
                ByteBufCodecs.VAR_INT, Entry::used,
                ByteBufCodecs.VAR_INT, Entry::serverUsed,
                Entry::new);

        /** Pieces still allowed today, or Integer.MAX_VALUE without limits. */
        public int left() {
            int left = Integer.MAX_VALUE;
            if (playerLimit > 0) left = Math.min(left, Math.max(0, playerLimit - used));
            if (serverLimit > 0) left = Math.min(left, Math.max(0, serverLimit - serverUsed));
            return left;
        }

        public boolean limited() {
            return playerLimit > 0 || serverLimit > 0;
        }
    }

    public record Category(String name, ItemStack icon, List<String> items) {
        static final StreamCodec<RegistryFriendlyByteBuf, Category> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Category::name,
                ItemStack.OPTIONAL_STREAM_CODEC, Category::icon,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Category::items,
                Category::new);
    }

    /** Server -> client: the settings, the tabs and the server shop, sent when the window opens and after changes. */
    public record Rules(String currency, String currencyShort, ItemStack currencyIcon, boolean market, int tax, int maxOffers,
                        List<String> blocked, boolean operator, List<Category> categories, boolean creativeTabs, List<Entry> buy,
                        List<Entry> sell)
            implements CustomPacketPayload {
        public static final Type<Rules> TYPE = new Type<>(NekoroShop.id("rules"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rules> STREAM_CODEC = StreamCodec.of((buf, r) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, r.currency);
            ByteBufCodecs.STRING_UTF8.encode(buf, r.currencyShort);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, r.currencyIcon);
            buf.writeBoolean(r.market);
            buf.writeVarInt(r.tax);
            buf.writeVarInt(r.maxOffers);
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, r.blocked);
            buf.writeBoolean(r.operator);
            Category.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, r.categories);
            buf.writeBoolean(r.creativeTabs);
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, r.buy);
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, r.sell);
        }, buf -> new Rules(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
                buf.readBoolean(),
                Category.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                buf.readBoolean(),
                new ArrayList<>(Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf)),
                new ArrayList<>(Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the player's money. */
    public record Balance(long balance) implements CustomPacketPayload {
        public static final Type<Balance> TYPE = new Type<>(NekoroShop.id("balance"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Balance> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Balance::balance,
                Balance::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One offer on the market, as one player sees it (own = their own offer). Time is sent as what is left when the
     * packet was made (remaining) out of the whole time (duration, 0 = never expires), so the client's clock does
     * not matter; age is how long ago it was listed, for sorting.
     */
    public record Offer(long id, ItemStack stack, int amount, long price, String seller, boolean own, long age, long remaining,
                        long duration) {
        static final StreamCodec<RegistryFriendlyByteBuf, Offer> STREAM_CODEC = StreamCodec.of((buf, o) -> {
            buf.writeVarLong(o.id);
            ItemStack.STREAM_CODEC.encode(buf, o.stack);
            buf.writeVarInt(o.amount);
            buf.writeVarLong(o.price);
            ByteBufCodecs.STRING_UTF8.encode(buf, o.seller);
            buf.writeBoolean(o.own);
            buf.writeVarLong(Math.max(0, o.age));
            buf.writeLong(o.remaining);
            buf.writeVarLong(o.duration);
        }, buf -> new Offer(buf.readVarLong(), ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarLong(),
                ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean(), buf.readVarLong(), buf.readLong(), buf.readVarLong()));
    }

    /** Server -> client: every offer on the market (other players' expired offers left out). */
    public record Market(List<Offer> offers) implements CustomPacketPayload {
        public static final Type<Market> TYPE = new Type<>(NekoroShop.id("market"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Market> STREAM_CODEC = StreamCodec.composite(
                Offer.STREAM_CODEC.apply(ByteBufCodecs.list()), Market::offers,
                Market::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: a short message shown in the window (what was bought, or why not). */
    public record Notice(Component text, boolean ok) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(NekoroShop.id("notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notice::text,
                ByteBufCodecs.BOOL, Notice::ok,
                Notice::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server: one action in the shop. target is an index in the server shop, an offer id or an inventory
     * slot, depending on the action; item is what the client saw there, checked by the server. limit and serverLimit
     * are the daily limits an operator sets.
     */
    public record Trade(int action, long target, Item item, int amount, long price, int limit, int serverLimit) implements CustomPacketPayload {
        public static final int BUY = 0, SELL = 1, BUY_OFFER = 2, OFFER = 3, TAKE_BACK = 4, REPRICE = 5,
                ADD_BUY = 6, ADD_SELL = 7, EDIT_BUY = 8, EDIT_SELL = 9, REMOVE_BUY = 10, REMOVE_SELL = 11, RELIST = 12;
        public static final Type<Trade> TYPE = new Type<>(NekoroShop.id("trade"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Trade> STREAM_CODEC = StreamCodec.of((buf, t) -> {
            buf.writeVarInt(t.action);
            buf.writeVarLong(t.target);
            ITEM.encode(buf, t.item);
            buf.writeVarInt(t.amount);
            buf.writeVarLong(t.price);
            buf.writeVarInt(t.limit);
            buf.writeVarInt(t.serverLimit);
        }, buf -> new Trade(buf.readVarInt(), buf.readVarLong(), ITEM.decode(buf), buf.readVarInt(), buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
