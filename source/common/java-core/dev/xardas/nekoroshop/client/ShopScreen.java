package dev.xardas.nekoroshop.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.xardas.nekoroshop.Inventories;
import dev.xardas.nekoroshop.NekoroShop;
import dev.xardas.nekoroshop.NekoroTab;
import dev.xardas.nekoroshop.Payloads;
import dev.xardas.nekoroshop.ShopMenu;
import dev.xardas.nekoroshop.ShopService;

/**
 * Left: the balance and the four parts of the shop (Buy and Sell with the server, Market and My Shop with players),
 * plus Edit for operators. Top: price range, search and sort; the category tabs sit above the window like the creative
 * inventory's. A click on an item, in the shop or in the inventory, opens a ShopDialog with the amount and the price.
 */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final ResourceLocation TEXTURE = NekoroShop.id("textures/gui/shop.png");
    private static final ResourceLocation SCROLLER = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller");
    private static final ResourceLocation SCROLLER_DISABLED = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller_disabled");
    private static final int GRID_X = 60, GRID_Y = 54, COLUMNS = 9, ROWS = 5;
    private static final int SCROLL_X = 226, SCROLL_Y = 54, SCROLL_HEIGHT = 88;
    private static final int BALANCE_X = 7, BALANCE_W = 44;
    private static final int TOP_ROW_Y = 17, TOP_ROW_HEIGHT = 14;
    private static final int DIM_COLOR = 0xB0101010;
    /** Own offers on the Market; offers whose time ran out (seen only by their seller). */
    private static final int OWN_COLOR = 0xFFB04CFF, EXPIRED_COLOR = 0xFF000000;
    private static final ResourceLocation PANEL = NekoroShop.id("panel"), FIELD = NekoroShop.id("field");

    private enum Mode { BUY, SELL, MARKET, MY_SHOP }

    private enum Sort { SHOP, NEWEST, OLDEST, NAME, PRICE_LOW, PRICE_HIGH, MOD }

    /** The server shop has its own order; the market sorts by when offers were listed instead. */
    private static final Sort[] SERVER_SORTS = { Sort.SHOP, Sort.NAME, Sort.PRICE_LOW, Sort.PRICE_HIGH, Sort.MOD };
    private static final Sort[] MARKET_SORTS = { Sort.NEWEST, Sort.OLDEST, Sort.NAME, Sort.PRICE_LOW, Sort.PRICE_HIGH, Sort.MOD };

    // kept while the game runs
    private static Mode mode = Mode.BUY;
    private static Sort serverSort = Sort.SHOP, marketSort = Sort.NEWEST;
    private static String query = "", fromText = "", toText = "";

    private boolean editing;
    private ShopDialog dialog;
    private EditBox search, fromBox, toBox;
    private CycleButton sortButton;
    private final List<Button> modeButtons = new ArrayList<>();
    private Button editButton;
    private Sort tooltipSort;
    private CategoryBar bar;
    private int barRules = -1;
    private int tooltipOffers = -1;

    /** One item in the grid: a server shop entry (index in its list) or a market offer. */
    private record Row(ItemStack stack, int index, Payloads.Entry entry, Payloads.Offer offer, long price, long order,
                       String name, String mod) { }

    private final List<Row> shown = new ArrayList<>();
    private String shownKey = "";
    private int scrollRow;
    private boolean draggingScroll;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 246;
        imageHeight = 238;
        inventoryLabelX = ShopMenu.INVENTORY_X;
        inventoryLabelY = ShopMenu.INVENTORY_Y - 11;
    }

    static String key(String name) {
        return "gui." + NekoroShop.MOD_ID + "." + name;
    }

    private static net.minecraft.network.chat.MutableComponent t(String name, Object... args) {
        return Component.translatable(key(name), args);
    }

    @Override
    protected void init() {
        super.init();

        fromBox = textBox(61, 28, 12, fromText, "from", text -> fromText = text);
        toBox = textBox(99, 28, 12, toText, "to", text -> toText = text);
        fromBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        toBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        search = textBox(133, 88, 50, query, "search", text -> query = text);

        sortButton = addRenderableWidget(new CycleButton(leftPos + 225, topPos + TOP_ROW_Y, TOP_ROW_HEIGHT, TOP_ROW_HEIGHT, back -> {
            Sort[] all = marketMode() ? MARKET_SORTS : SERVER_SORTS;
            int at = java.util.Arrays.asList(all).indexOf(sort());
            Sort next = all[Math.floorMod(at + (back ? -1 : 1), all.length)];
            if (marketMode()) marketSort = next;
            else serverSort = next;
        }));
        tooltipSort = null;

        modeButtons.clear();
        int[] ys = { 47, 65, 97, 115 };
        for (Mode m : Mode.values()) {
            modeButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> switchMode(m))
                    .bounds(leftPos + 7, topPos + ys[m.ordinal()], 44, 16)
                    .tooltip(Tooltip.create(t("mode." + name(m) + ".tooltip")))
                    .build()));
        }
        tooltipOffers = -1;
        editButton = addRenderableWidget(Button.builder(t("edit"), b -> {
                    editing = !editing;
                    updateLabels();
                })
                .bounds(leftPos + 7, topPos + ShopMenu.INVENTORY_Y - 1, 44, 16)
                .tooltip(Tooltip.create(t("edit.tooltip")))
                .build());
        bar = new CategoryBar(t("category.all"), NekoroShop.id("textures/gui/category_all.png"));
        bar.init(leftPos, topPos, this::addRenderableWidget);
        bar.onSelect(() -> scrollRow = 0);
        barRules = -1;
        shownKey = "";
        updateLabels();
        if (dialog != null) dialog.layout(leftPos, topPos, imageWidth);
    }

    private EditBox textBox(int x, int width, int maxLength, String value, String hint, Consumer<String> responder) {
        EditBox box = new EditBox(font, leftPos + x, topPos + TOP_ROW_Y + 3, width, 10, t(hint));
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(0xFFFFFF);
        box.setHint(t(hint).withStyle(ChatFormatting.GRAY));
        box.setValue(value);
        box.setResponder(responder);
        return addRenderableWidget(box);
    }

    private static boolean marketMode() {
        return mode == Mode.MARKET || mode == Mode.MY_SHOP;
    }

    private static Sort sort() {
        return marketMode() ? marketSort : serverSort;
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private void switchMode(Mode next) {
        mode = next;
        scrollRow = 0;
        updateLabels();
    }

    private boolean marketOn() {
        Payloads.Rules rules = ClientShop.rules();
        return rules == null || rules.market();
    }

    private boolean operator() {
        Payloads.Rules rules = ClientShop.rules();
        return rules != null && rules.operator();
    }

    /** Edit applies to the server shop only. */
    private boolean editingShop() {
        return editing && operator() && (mode == Mode.BUY || mode == Mode.SELL);
    }

    /** The selected part is shown in yellow; the market parts are hidden when the market is off. */
    private void updateLabels() {
        if (!marketOn() && (mode == Mode.MARKET || mode == Mode.MY_SHOP)) mode = Mode.BUY;
        for (Mode m : Mode.values()) {
            Button b = modeButtons.get(m.ordinal());
            Component label = t("mode." + name(m));
            b.setMessage(m == mode ? label.copy().withStyle(ChatFormatting.YELLOW) : label);
            b.visible = m == Mode.BUY || m == Mode.SELL || marketOn();
        }
        editButton.visible = operator() && (mode == Mode.BUY || mode == Mode.SELL);
        editButton.setMessage(editing ? t("edit").copy().withStyle(ChatFormatting.YELLOW) : t("edit"));
        if (tooltipSort != sort()) {
            tooltipSort = sort();
            sortButton.setTooltip(Tooltip.create(t("sort", t("sort." + name(sort())))));
        }
        Payloads.Rules rules = ClientShop.rules();
        int offers = ClientShop.ownOffers();
        if (rules != null && offers != tooltipOffers) {
            tooltipOffers = offers;
            modeButtons.get(Mode.MY_SHOP.ordinal()).setTooltip(Tooltip.create(t("mode.my_shop.tooltip", offers, rules.maxOffers())));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // the shop may arrive after the window opened, and the market changes while it is open
        updateLabels();
    }

    void closeDialog() {
        dialog = null;
    }

    private void openDialog(ShopDialog next) {
        dialog = next;
        dialog.layout(leftPos, topPos, imageWidth);
        for (EditBox box : List.of(search, fromBox, toBox)) box.setFocused(false);
    }

    // ---- the grid

    private static long parse(String text) {
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private void refresh() {
        Payloads.Rules rules = ClientShop.rules();
        String text = query.trim().toLowerCase(Locale.ROOT);
        // on the market, offers run out while the window is open, so it is checked again every second
        long second = marketMode() ? System.currentTimeMillis() / 1000 : 0;
        if (rules != null && barRules != ClientShop.rulesVersion()) {
            barRules = ClientShop.rulesVersion();
            List<CategoryBar.Category> custom = new ArrayList<>();
            for (int i = 0; i < rules.categories().size(); i++) {
                int index = i;
                Payloads.Category c = rules.categories().get(i);
                custom.add(new CategoryBar.Category("custom:" + i, Component.literal(c.name()), c.icon(), item -> ClientShop.inCategory(index, item)));
            }
            bar.setCategories(custom, rules.creativeTabs());
            shownKey = "";
        }
        String key = ClientShop.version() + "|" + mode + "|" + sort() + "|" + bar.selectedKey() + "|" + text + "|" + fromText + "|" + toText + "|" + second;
        if (key.equals(shownKey)) return;
        shownKey = key;
        shown.clear();
        if (rules == null) return;
        List<Row> rows = new ArrayList<>();
        switch (mode) {
            case BUY, SELL -> {
                List<Payloads.Entry> list = mode == Mode.BUY ? rules.buy() : rules.sell();
                for (int i = 0; i < list.size(); i++) rows.add(row(list.get(i).stack(), i, list.get(i), null, list.get(i).price(), i));
            }
            case MARKET, MY_SHOP -> {
                for (Payloads.Offer o : ClientShop.offers()) {
                    // other players' offers leave when their time runs out; own ones stay to be taken back
                    if (!o.own() && ClientShop.expired(o)) continue;
                    if (mode == Mode.MARKET || o.own()) rows.add(row(o.stack(), -1, null, o, o.price(), ClientShop.age(o)));
                }
            }
        }
        long from = parse(fromText), to = parse(toText);
        // categories with nothing in this part of the shop (after the search and the price range) are hidden
        java.util.Set<net.minecraft.world.item.Item> items = new java.util.HashSet<>();
        for (Row r : rows) {
            if ((from > 0 && r.price() < from) || (to > 0 && r.price() > to) || !matches(r, text)) continue;
            items.add(r.stack().getItem());
        }
        bar.update(items);
        for (Row r : rows) {
            if (!bar.test(r.stack().getItem())) continue;
            if (from > 0 && r.price() < from) continue;
            if (to > 0 && r.price() > to) continue;
            if (!matches(r, text)) continue;
            shown.add(r);
        }
        // order is the index in the server shop, or how long ago an offer was listed
        Comparator<Row> order = switch (sort()) {
            case SHOP, NEWEST -> Comparator.comparingLong(Row::order);
            case OLDEST -> Comparator.comparingLong(Row::order).reversed();
            case NAME -> Comparator.comparing(Row::name).thenComparingLong(Row::order);
            case PRICE_LOW -> Comparator.comparingLong(Row::price).thenComparing(Row::name).thenComparingLong(Row::order);
            case PRICE_HIGH -> Comparator.comparingLong(Row::price).reversed().thenComparing(Row::name).thenComparingLong(Row::order);
            case MOD -> Comparator.comparing(Row::mod).thenComparing(Row::name).thenComparingLong(Row::order);
        };
        shown.sort(order);
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private static Row row(ItemStack stack, int index, Payloads.Entry entry, Payloads.Offer offer, long price, long order) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new Row(stack, index, entry, offer, price, order, stack.getHoverName().getString().toLowerCase(Locale.ROOT), id.getNamespace());
    }

    /** By name, or "@mod" by the mod's id (as in JEI). */
    private static boolean matches(Row row, String text) {
        if (text.isEmpty()) return true;
        if (text.startsWith("@")) return row.mod().contains(text.substring(1));
        return row.name().contains(text);
    }

    private int maxScroll() {
        return Math.max(0, (shown.size() + COLUMNS - 1) / COLUMNS - ROWS);
    }

    private int rowAt(double mouseX, double mouseY) {
        int x = (int) Math.floor(mouseX) - leftPos - GRID_X + 1;
        int y = (int) Math.floor(mouseY) - topPos - GRID_Y + 1;
        if (x < 0 || y < 0 || x >= COLUMNS * 18 || y >= ROWS * 18) return -1;
        int index = (scrollRow + y / 18) * COLUMNS + x / 18;
        return index < shown.size() ? index : -1;
    }

    private boolean over(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + w && mouseY >= topPos + y && mouseY < topPos + y + h;
    }

    private boolean overScrollBar(double mouseX, double mouseY) {
        return over(mouseX, mouseY, SCROLL_X, SCROLL_Y, 12, SCROLL_HEIGHT);
    }

    /**
     * The frame of an offer. Own offers on the Market are purple; otherwise the colour shows the part of the time left,
     * from light blue (just listed) to dark red (nearly gone), and black once it ran out.
     */
    private static int frameColor(Payloads.Offer offer) {
        if (ClientShop.expired(offer)) return EXPIRED_COLOR;
        if (mode == Mode.MARKET && offer.own()) return OWN_COLOR;
        if (offer.duration() <= 0) return 0;
        double left = ClientShop.timeLeft(offer);
        if (left <= 0.10) return 0xFF9B0000;
        if (left <= 0.25) return 0xFFFF4D4D;
        if (left <= 0.50) return 0xFFFF9A1F;
        if (left <= 0.75) return 0xFFFFE135;
        if (left <= 0.90) return 0xFF7CF05C;
        return 0xFF5CC8FF;
    }

    /** "6d 23h", "5h 12m" or "40m". */
    static String timeText(long ms) {
        long minutes = Math.max(1, ms / 60000);
        long days = minutes / 1440, hours = minutes / 60 % 24, mins = minutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    /** "12,345" up to 99,999, then "123K", "1.2M", "12M" and so on. */
    static String shortNumber(long value) {
        if (value < 100_000) return ShopService.format(value);
        String[] units = { "K", "M", "B", "T", "Q" };
        double v = value;
        int unit = -1;
        while (v >= 1000 && unit < units.length - 1) {
            v /= 1000;
            unit++;
        }
        String number = v < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10) : Long.toString((long) v);
        return number + units[unit];
    }

    // ---- drawing

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        refresh();
        Payloads.Rules rules = ClientShop.rules();
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        bar.render(graphics, mouseX, mouseY);

        if (rules != null && !rules.currencyIcon().isEmpty()) {
            // the money icon (currency_icon) at half size in the balance box
            graphics.pose().pushPose();
            graphics.pose().translate(leftPos + BALANCE_X + 3, topPos + TOP_ROW_Y + 3, 0);
            graphics.pose().scale(0.5F, 0.5F, 1);
            graphics.renderItem(rules.currencyIcon(), 0, 0);
            graphics.pose().popPose();
        }


        int hovered = dialog == null ? rowAt(mouseX, mouseY) : -1;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int index = (scrollRow + row) * COLUMNS + col;
                if (index >= shown.size()) break;
                int x = leftPos + GRID_X + col * 18, y = topPos + GRID_Y + row * 18;
                Row r = shown.get(index);
                int frame = r.offer() == null ? 0 : frameColor(r.offer());
                if (frame != 0) graphics.renderOutline(x - 1, y - 1, 18, 18, frame);
                graphics.renderItem(r.stack(), x, y);
                if (index == hovered) renderSlotHighlight(graphics, x, y, 0);
            }
        }

        int max = maxScroll();
        int scrollerY = topPos + SCROLL_Y + (max > 0 ? (SCROLL_HEIGHT - 15) * scrollRow / max : 0);
        graphics.blitSprite(max > 0 ? SCROLLER : SCROLLER_DISABLED, leftPos + SCROLL_X, scrollerY, 12, 15);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
        String balance = shortNumber(ClientShop.balance());
        Payloads.Rules shown = ClientShop.rules();
        // with the money icon the number is centred in the rest of the box
        int left = shown != null && !shown.currencyIcon().isEmpty() ? 12 : 0;
        graphics.drawString(font, balance, BALANCE_X + left + (BALANCE_W - left - font.width(balance)) / 2, TOP_ROW_Y + 3, 0x404040, false);
        graphics.drawString(font, "-", 94 - font.width("-") / 2, TOP_ROW_Y + 3, 0x404040, false);
        graphics.drawString(font, t("header.server"), 8, 37, 0x404040, false);
        if (marketOn()) graphics.drawString(font, t("header.players"), 8, 87, 0x404040, false);

        Component notice = ClientShop.notice();
        if (notice != null) {
            int room = imageWidth - 16 - font.width(title) - 8;
            String text = font.plainSubstrByWidth(notice.getString(), room);
            graphics.drawString(font, text, imageWidth - 8 - font.width(text), titleLabelY, ClientShop.noticeOk() ? 0x2E7D32 : 0xC02020, false);
        }
    }

    /** Sell: what the server does not buy is dimmed. My Shop: what cannot be offered. */
    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        super.renderSlot(graphics, slot);
        if (dialog != null || !(slot.container instanceof Inventory) || !slot.hasItem()) return;
        boolean dim = false;
        if (mode == Mode.SELL && !editingShop()) dim = sellIndex(slot.getItem()) < 0;
        else if (mode == Mode.MY_SHOP) dim = ClientShop.blocked(slot.getItem().getItem());
        if (dim) graphics.fillGradient(RenderType.guiOverlay(), slot.x, slot.y, slot.x + 16, slot.y + 16, DIM_COLOR, DIM_COLOR, 0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // with the small window open nothing under it reacts to the mouse
        int mx = dialog == null ? mouseX : -10000, my = dialog == null ? mouseY : -10000;
        super.render(graphics, mx, my, partialTick);
        if (dialog != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.fill(0, 0, width, height, 0x60000000);
            dialog.render(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
            return;
        }
        int hovered = rowAt(mouseX, mouseY);
        Component category = bar.tooltip(mouseX, mouseY);
        if (hovered >= 0 && menu.getCarried().isEmpty()) {
            Row r = shown.get(hovered);
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, r.stack()));
            lines.add(t("tooltip.price", ShopDialog.money(r.price())).withStyle(ChatFormatting.GOLD));
            if (r.offer() != null) {
                lines.add(t("tooltip.stock", ShopService.format(r.offer().amount())).withStyle(ChatFormatting.GRAY));
                lines.add(r.offer().own() ? t("tooltip.own").withStyle(ChatFormatting.LIGHT_PURPLE)
                        : t("tooltip.seller", r.offer().seller()).withStyle(ChatFormatting.GRAY));
                if (ClientShop.expired(r.offer())) lines.add(t("tooltip.expired").withStyle(ChatFormatting.DARK_RED));
                else if (r.offer().duration() > 0) {
                    lines.add(t("tooltip.expires", timeText(ClientShop.remaining(r.offer()))).withStyle(ChatFormatting.GRAY));
                }
            }
            if (r.entry() != null && r.entry().limited()) {
                lines.add(t("tooltip.left_today", ShopService.format(r.entry().left())).withStyle(ChatFormatting.GRAY));
            }
            graphics.renderTooltip(font, lines, r.stack().getTooltipImage(), mouseX, mouseY);
        } else if (category != null) {
            graphics.renderTooltip(font, category, mouseX, mouseY);
        } else if (over(mouseX, mouseY, BALANCE_X, TOP_ROW_Y, BALANCE_W, TOP_ROW_HEIGHT)) {
            graphics.renderTooltip(font, t("balance", ShopDialog.money(ClientShop.balance())), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    // ---- clicks

    private int sellIndex(ItemStack stack) {
        Payloads.Rules rules = ClientShop.rules();
        if (rules == null) return -1;
        for (int i = 0; i < rules.sell().size(); i++) {
            if (Inventories.same(rules.sell().get(i).stack(), stack)) return i;
        }
        return -1;
    }

    private static Payloads.Entry find(List<Payloads.Entry> list, ItemStack stack) {
        for (Payloads.Entry e : list) {
            if (Inventories.same(e.stack(), stack)) return e;
        }
        return null;
    }

    private void clickRow(Row r) {
        Payloads.Rules rules = ClientShop.rules();
        if (rules == null) return;
        switch (mode) {
            case BUY -> openDialog(editingShop() ? ShopDialog.edit(this, r.index(), r.entry(), false) : ShopDialog.buy(this, r.index(), r.entry()));
            case SELL -> openDialog(editingShop() ? ShopDialog.edit(this, r.index(), r.entry(), true) : ShopDialog.sell(this, r.index(), r.entry()));
            case MARKET -> openDialog(r.offer().own() ? ShopDialog.ownOffer(this, r.offer()) : ShopDialog.buyOffer(this, r.offer()));
            case MY_SHOP -> openDialog(ShopDialog.ownOffer(this, r.offer()));
        }
    }

    /** A click on the inventory in Sell, My Shop and Edit opens the small window instead of picking the item up. */
    private boolean clickInventory(Slot slot) {
        Payloads.Rules rules = ClientShop.rules();
        if (rules == null) return false;
        ItemStack stack = slot.getItem();
        int index = slot.getContainerSlot();
        if (editingShop()) {
            boolean sellList = mode == Mode.SELL;
            openDialog(ShopDialog.add(this, index, stack, sellList, find(sellList ? rules.sell() : rules.buy(), stack)));
            return true;
        }
        if (mode == Mode.SELL) {
            int i = sellIndex(stack);
            if (i >= 0) openDialog(ShopDialog.sell(this, i, rules.sell().get(i)));
            else ClientShop.notice(t("error.not_bought"), false);
            return true;
        }
        if (mode == Mode.MY_SHOP) {
            if (ClientShop.blocked(stack.getItem())) {
                ClientShop.notice(t("error.blocked"), false);
                return true;
            }
            openDialog(ShopDialog.newOffer(this, index, stack, Inventories.count(minecraft.player, stack), suggestedPrice(stack)));
            return true;
        }
        return false;
    }

    /** The cheapest price of the same item on the market (own offers first), so a new offer starts near it. */
    private static long suggestedPrice(ItemStack stack) {
        long own = 0, other = 0;
        for (Payloads.Offer o : ClientShop.offers()) {
            if (!Inventories.same(o.stack(), stack)) continue;
            if (o.own()) own = own == 0 ? o.price() : Math.min(own, o.price());
            else other = other == 0 ? o.price() : Math.min(other, o.price());
        }
        return own > 0 ? own : other;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null && slot.container instanceof Inventory && slot.hasItem() && menu.getCarried().isEmpty()
                && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && clickInventory(slot)) {
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dialog != null) return dialog.mouseClicked(mouseX, mouseY, button);
        for (EditBox box : List.of(search, fromBox, toBox)) {
            if (!box.isMouseOver(mouseX, mouseY)) box.setFocused(false);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && bar.click(mouseX, mouseY)) {
            scrollRow = 0;
            return true;
        }
        int index = rowAt(mouseX, mouseY);
        if (index >= 0 && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) && menu.getCarried().isEmpty()) {
            clickRow(shown.get(index));
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overScrollBar(mouseX, mouseY)) {
            draggingScroll = true;
            scrollTo(mouseY);
            return true;
        }
        for (EditBox box : List.of(search, fromBox, toBox)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && box.isMouseOver(mouseX, mouseY)) {
                box.setValue("");
                box.setFocused(true);
                setFocused(box);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dialog != null) return true;
        if (draggingScroll) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroll = false;
        if (dialog != null) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollTo(double mouseY) {
        int max = maxScroll();
        if (max == 0) return;
        double part = (mouseY - topPos - SCROLL_Y - 7.5) / (SCROLL_HEIGHT - 15);
        scrollRow = Mth.clamp((int) Math.round(part * max), 0, max);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dialog != null) return true;
        if (scrollY != 0 && bar.scroll(mouseX, mouseY, scrollY)) return true;
        // over the inventory, scrolling is left to other mods (e.g. moving items)
        if (hoveredSlot == null && maxScroll() > 0 && scrollY != 0) {
            scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dialog != null) return dialog.keyPressed(keyCode, scanCode, modifiers);
        // while typing, letters must not close the window or press hotbar keys
        for (EditBox box : List.of(search, fromBox, toBox)) {
            if (box.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
                box.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (dialog != null) return dialog.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }

    // ---- drawing helpers, also used by the small window

    /** A raised grey panel like the vanilla windows. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        // a nine-slice sprite, so resource packs can restyle it
        g.blitSprite(PANEL, x, y, w, h);
    }

    /** A sunken black box for typing in. */
    static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.blitSprite(FIELD, x, y, w, h);
    }

    /** Switches between values: left click goes to the next one, right click back. Drawn as three lines. */
    private final class CycleButton extends Button {
        private final Consumer<Boolean> cycle;
        private int pressedWith;

        CycleButton(int x, int y, int width, int height, Consumer<Boolean> cycle) {
            super(x, y, width, height, Component.empty(), b -> { }, DEFAULT_NARRATION);
            this.cycle = cycle;
        }

        @Override
        protected boolean isValidClickButton(int button) {
            return button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            pressedWith = button;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            int x = getX() + 3, y = getY() + 3, color = 0xFFE0E0E0;
            graphics.fill(x, y, x + 8, y + 2, color);
            graphics.fill(x, y + 3, x + 6, y + 5, color);
            graphics.fill(x, y + 6, x + 4, y + 8, color);
        }

        @Override
        public void onPress() {
            cycle.accept(pressedWith == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            updateLabels();
        }
    }
}
