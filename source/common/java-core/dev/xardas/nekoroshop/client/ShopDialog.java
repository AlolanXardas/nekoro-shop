package dev.xardas.nekoroshop.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import dev.xardas.nekoroshop.Inventories;
import dev.xardas.nekoroshop.Payloads;
import dev.xardas.nekoroshop.ShopService;

/**
 * The small window over the shop after clicking an item: what it costs, how many to buy, sell, offer or take back,
 * the total, and why the action cannot be done when it cannot. Expired offers can be taken back or listed again,
 * and operators use it to add and change server shop items.
 */
final class ShopDialog {
    enum Kind { BUY, SELL, BUY_OFFER, NEW_OFFER, EDIT_OFFER, EXPIRED, ADD, EDIT }

    static final int WIDTH = 176;
    private static final int TEXT = 0x404040, RED = 0xC02020, LABEL_W = 60;
    private static final long MAX_AMOUNT = 1_000_000_000L;

    private final ShopScreen screen;
    final Kind kind;
    final ItemStack stack;
    /** Index in the server shop (BUY, SELL, EDIT), an inventory slot (NEW_OFFER, ADD) or an offer id (the others). */
    final long target;
    /** ADD and EDIT: the Sell list of the server shop instead of the Buy list. */
    final boolean sellList;
    private final Payloads.Entry entry;

    private String amountText = "1", priceText = "", limitText = "0", serverLimitText = "0";
    private EditBox amountBox, priceBox, limitBox, serverLimitBox;
    private final List<EditBox> boxes = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private Button primary, takeButton, saveButton;
    int x, y, height;
    private int infoY, amountY, quickY, priceY, limitY, serverLimitY, totalY, reasonY, buttonsY;

    private ShopDialog(ShopScreen screen, Kind kind, ItemStack stack, long target, boolean sellList, Payloads.Entry entry) {
        this.screen = screen;
        this.kind = kind;
        this.stack = stack.copyWithCount(1);
        this.target = target;
        this.sellList = sellList;
        this.entry = entry;
    }

    static ShopDialog buy(ShopScreen screen, int index, Payloads.Entry entry) {
        return new ShopDialog(screen, Kind.BUY, entry.stack(), index, false, entry);
    }

    static ShopDialog sell(ShopScreen screen, int index, Payloads.Entry entry) {
        return new ShopDialog(screen, Kind.SELL, entry.stack(), index, true, entry);
    }

    static ShopDialog buyOffer(ShopScreen screen, Payloads.Offer offer) {
        return new ShopDialog(screen, Kind.BUY_OFFER, offer.stack(), offer.id(), false, null);
    }

    static ShopDialog newOffer(ShopScreen screen, int slot, ItemStack stack, int count, long suggestedPrice) {
        ShopDialog d = new ShopDialog(screen, Kind.NEW_OFFER, stack, slot, false, null);
        d.amountText = Integer.toString(count);
        d.priceText = suggestedPrice > 0 ? Long.toString(suggestedPrice) : "";
        return d;
    }

    /** An own offer: still on the market (change the price, take back) or expired (also list again). */
    static ShopDialog ownOffer(ShopScreen screen, Payloads.Offer offer) {
        ShopDialog d = new ShopDialog(screen, ClientShop.expired(offer) ? Kind.EXPIRED : Kind.EDIT_OFFER, offer.stack(), offer.id(), false, null);
        d.amountText = Integer.toString(offer.amount());
        d.priceText = Long.toString(offer.price());
        return d;
    }

    static ShopDialog add(ShopScreen screen, int slot, ItemStack stack, boolean sellList, Payloads.Entry existing) {
        ShopDialog d = new ShopDialog(screen, Kind.ADD, stack, slot, sellList, existing);
        if (existing != null) d.fillFrom(existing);
        return d;
    }

    static ShopDialog edit(ShopScreen screen, int index, Payloads.Entry entry, boolean sellList) {
        ShopDialog d = new ShopDialog(screen, Kind.EDIT, entry.stack(), index, sellList, entry);
        d.fillFrom(entry);
        return d;
    }

    private void fillFrom(Payloads.Entry e) {
        priceText = Long.toString(e.price());
        limitText = Integer.toString(e.playerLimit());
        serverLimitText = Integer.toString(e.serverLimit());
    }

    private static MutableComponent t(String name, Object... args) {
        return Component.translatable(ShopScreen.key(name), args);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static Player player() {
        return Minecraft.getInstance().player;
    }

    private boolean hasAmount() {
        return kind != Kind.ADD && kind != Kind.EDIT;
    }

    private boolean hasPrice() {
        return kind == Kind.NEW_OFFER || kind == Kind.EDIT_OFFER || kind == Kind.EXPIRED || kind == Kind.ADD || kind == Kind.EDIT;
    }

    private boolean hasLimits() {
        return kind == Kind.ADD || kind == Kind.EDIT;
    }

    private boolean hasTotal() {
        return kind == Kind.BUY || kind == Kind.SELL || kind == Kind.BUY_OFFER || kind == Kind.NEW_OFFER;
    }

    /** The offer as it is now (another player may have bought some meanwhile), or null when it is gone. */
    private Payloads.Offer offer() {
        return ClientShop.offer(target);
    }

    private List<Component> info() {
        List<Component> lines = new ArrayList<>();
        Payloads.Rules rules = ClientShop.rules();
        switch (kind) {
            case BUY, SELL -> {
                lines.add(t("price", money(entry.price())));
                if (kind == Kind.SELL) lines.add(t("have", ShopService.format(Inventories.count(player(), stack))));
                if (entry.limited()) lines.add(t("left_today", ShopService.format(entry.left())));
            }
            case BUY_OFFER, EDIT_OFFER, EXPIRED -> {
                Payloads.Offer offer = offer();
                lines.add(t("price", money(offer == null ? 0 : offer.price())));
                lines.add(t("stock", ShopService.format(offer == null ? 0 : offer.amount())));
                if (kind == Kind.BUY_OFFER) lines.add(t("seller", offer == null ? "?" : offer.seller()));
                if (kind == Kind.EXPIRED) lines.add(t("expired").withStyle(ChatFormatting.DARK_RED));
                else if (offer != null && offer.duration() > 0) lines.add(t("expires", ShopScreen.timeText(ClientShop.remaining(offer))));
            }
            case NEW_OFFER -> {
                lines.add(t("have", ShopService.format(Inventories.count(player(), stack))));
                if (rules != null && rules.tax() > 0) lines.add(t("tax", rules.tax()));
            }
            case ADD, EDIT -> lines.add(t(sellList ? "list.sell" : "list.buy"));
        }
        return lines;
    }

    static String money(long value) {
        Payloads.Rules rules = ClientShop.rules();
        return ShopService.format(value) + (rules == null ? "" : " " + rules.currencyShort());
    }

    /** Builds the widgets for the current window position; called again when the game window is resized. */
    void layout(int left, int top, int imageWidth) {
        if (!boxes.isEmpty()) save();
        boxes.clear();
        buttons.clear();
        int cy = 28;
        infoY = cy;
        cy += info().size() * 10 + 2;
        if (hasAmount()) {
            amountY = cy;
            cy += 17;
            quickY = cy;
            cy += 18;
        }
        if (hasPrice()) {
            priceY = cy;
            cy += 17;
        }
        if (hasLimits()) {
            limitY = cy;
            cy += 17;
            serverLimitY = cy;
            cy += 17;
        }
        if (hasTotal()) {
            totalY = cy;
            cy += 10;
        }
        reasonY = cy;
        cy += 11;
        buttonsY = cy;
        height = cy + 16 + 7;
        x = left + (imageWidth - WIDTH) / 2;
        // centred on the shop grid, but never above the window
        y = Math.max(top + 4, top + 54 + (90 - height) / 2);

        amountBox = hasAmount() ? box(amountY, amountText, 9, "amount") : null;
        priceBox = hasPrice() ? box(priceY, priceText, 13, "price.hint") : null;
        limitBox = hasLimits() ? box(limitY, limitText, 9, "limit.hint") : null;
        serverLimitBox = hasLimits() ? box(serverLimitY, serverLimitText, 9, "limit.hint") : null;
        if (hasAmount()) {
            int[] quick = { 1, 16, 64 };
            int bw = (WIDTH - 16 - 9) / 4;
            for (int i = 0; i < 4; i++) {
                int value = i < 3 ? quick[i] : -1;
                Component label = i < 3 ? Component.literal(Integer.toString(value)) : t("max");
                buttons.add(Button.builder(label, b -> setAmount(value < 0 ? max() : value))
                        .bounds(x + 8 + i * (bw + 3), y + quickY, bw, 14).build());
            }
        }
        List<Button> row = new ArrayList<>();
        switch (kind) {
            case BUY, BUY_OFFER -> row.add(primary = button("buy", this::confirm));
            case SELL -> row.add(primary = button("sell", this::confirm));
            case NEW_OFFER -> row.add(primary = button("list", this::confirm));
            case ADD -> row.add(primary = button("add", this::confirm));
            case EDIT_OFFER, EXPIRED -> {
                row.add(takeButton = button("take", () -> send(Payloads.Trade.TAKE_BACK, amount(), 0)));
                row.add(primary = saveButton = button(kind == Kind.EXPIRED ? "relist" : "save", this::confirm));
            }
            case EDIT -> {
                row.add(button("remove", () -> send(sellList ? Payloads.Trade.REMOVE_SELL : Payloads.Trade.REMOVE_BUY, 0, 0)));
                row.add(primary = saveButton = button("save", this::confirm));
            }
        }
        row.add(0, button("cancel", screen::closeDialog));
        if (row.size() == 3) {
            // Cancel last when there are two actions, so the actions sit together
            row.add(row.remove(0));
        }
        int bw = (WIDTH - 16 - (row.size() - 1) * 4) / row.size();
        for (int i = 0; i < row.size(); i++) {
            Button b = row.get(i);
            b.setX(x + 8 + i * (bw + 4));
            b.setY(y + buttonsY);
            b.setWidth(bw);
            buttons.add(b);
        }
        boolean priceFirst = kind == Kind.ADD || kind == Kind.EDIT || kind == Kind.NEW_OFFER && priceText.isEmpty();
        focus(priceFirst ? priceBox : amountBox);
    }

    private Button button(String name, Runnable action) {
        return Button.builder(t("button." + name), b -> action.run()).bounds(0, 0, 40, 16).build();
    }

    private EditBox box(int rowY, String value, int maxLength, String hint) {
        EditBox box = new EditBox(font(), x + LABEL_W + 10, y + rowY + 3, WIDTH - LABEL_W - 20, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(0xFFFFFF);
        box.setFilter(text -> text.chars().allMatch(Character::isDigit));
        box.setValue(value);
        box.setHint(t(hint).withStyle(ChatFormatting.DARK_GRAY));
        boxes.add(box);
        return box;
    }

    private void save() {
        if (amountBox != null) amountText = amountBox.getValue();
        if (priceBox != null) priceText = priceBox.getValue();
        if (limitBox != null) limitText = limitBox.getValue();
        if (serverLimitBox != null) serverLimitText = serverLimitBox.getValue();
    }

    private void focus(EditBox box) {
        for (EditBox b : boxes) b.setFocused(b == box);
    }

    private EditBox focused() {
        for (EditBox b : boxes) {
            if (b.isFocused()) return b;
        }
        return null;
    }

    private static long parse(EditBox box) {
        if (box == null || box.getValue().isEmpty()) return 0;
        try {
            return Long.parseLong(box.getValue());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private int amount() {
        return (int) Math.min(MAX_AMOUNT, parse(amountBox));
    }

    private long price() {
        return switch (kind) {
            case BUY, SELL -> entry.price();
            case BUY_OFFER -> offer() == null ? 0 : offer().price();
            default -> parse(priceBox);
        };
    }

    private static int limit(EditBox box) {
        return (int) Math.min(MAX_AMOUNT, parse(box));
    }

    private void setAmount(int value) {
        if (amountBox != null && value > 0) amountBox.setValue(Integer.toString(value));
    }

    private int space() {
        return Inventories.space(player(), stack);
    }

    /** What the Max button puts in. */
    private int max() {
        Payloads.Offer offer = offer();
        long price = price();
        return switch (kind) {
            case BUY -> (int) Math.min(Math.min(space(), entry.left()), price > 0 ? ClientShop.balance() / price : 0);
            case SELL -> Math.min(Inventories.count(player(), stack), entry.left());
            case BUY_OFFER -> offer == null ? 0 : (int) Math.min(Math.min(offer.amount(), space()), price > 0 ? ClientShop.balance() / price : 0);
            case NEW_OFFER -> Inventories.count(player(), stack);
            case EDIT_OFFER, EXPIRED -> offer == null ? 0 : Math.min(offer.amount(), space());
            default -> 0;
        };
    }

    private static long total(long price, int amount) {
        try {
            return Math.multiplyExact(price, (long) amount);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Why the main action cannot be done now, or null. */
    private Component reason() {
        int amount = amount();
        long price = price();
        Payloads.Rules rules = ClientShop.rules();
        Payloads.Offer offer = offer();
        switch (kind) {
            case BUY, BUY_OFFER -> {
                if (kind == Kind.BUY_OFFER && (offer == null || ClientShop.expired(offer))) return t("error.gone");
                if (kind == Kind.BUY && entry.left() == 0) return t("error.limit");
                if (amount <= 0) return Component.empty();
                if (kind == Kind.BUY_OFFER && amount > offer.amount()) return t("reason.stock", ShopService.format(offer.amount()));
                if (kind == Kind.BUY && amount > entry.left()) return t("reason.limit");
                if (amount > space()) return t("error.space");
                if (total(price, amount) > ClientShop.balance()) return t("error.money", rules == null ? "" : rules.currencyShort());
            }
            case SELL -> {
                int have = Inventories.count(player(), stack);
                if (have == 0) return t("error.nothing");
                if (entry.left() == 0) return t("error.limit");
                if (amount <= 0) return Component.empty();
                if (amount > have) return t("reason.items");
                if (amount > entry.left()) return t("reason.limit");
            }
            case NEW_OFFER -> {
                if (amount <= 0) return Component.empty();
                if (amount > Inventories.count(player(), stack)) return t("reason.items");
                if (price <= 0) return t("error.price");
                if (rules != null && ClientShop.ownOffers() >= rules.maxOffers() && !joinsOffer(price)) {
                    return t("error.offers", rules.maxOffers());
                }
            }
            case EDIT_OFFER, EXPIRED -> {
                if (offer == null) return t("error.gone");
                if (price <= 0) return t("error.price");
            }
            case ADD, EDIT -> {
                if (price <= 0) return t("error.price");
            }
        }
        return null;
    }

    /** An own offer of the same item at the same price, which the new pieces join (so it is not a new offer). */
    private boolean joinsOffer(long price) {
        for (Payloads.Offer o : ClientShop.offers()) {
            if (o.own() && o.price() == price && !ClientShop.expired(o) && Inventories.same(o.stack(), stack)) return true;
        }
        return false;
    }

    private void update() {
        Component reason = reason();
        if (kind == Kind.EDIT_OFFER || kind == Kind.EXPIRED) {
            Payloads.Offer offer = offer();
            int amount = amount();
            takeButton.active = offer != null && amount > 0 && amount <= offer.amount() && amount <= space();
            // relisting at the same price is fine; saving the same price on a live offer changes nothing
            saveButton.active = offer != null && price() > 0 && (kind == Kind.EXPIRED || price() != offer.price());
        } else {
            primary.active = reason == null;
        }
    }

    private void confirm() {
        if (!primary.active) return;
        switch (kind) {
            case BUY -> send(Payloads.Trade.BUY, amount(), 0);
            case SELL -> send(Payloads.Trade.SELL, amount(), 0);
            case BUY_OFFER -> send(Payloads.Trade.BUY_OFFER, amount(), 0);
            case NEW_OFFER -> send(Payloads.Trade.OFFER, amount(), price());
            case EDIT_OFFER -> send(Payloads.Trade.REPRICE, 0, price());
            case EXPIRED -> send(Payloads.Trade.RELIST, 0, price());
            case ADD -> send(sellList ? Payloads.Trade.ADD_SELL : Payloads.Trade.ADD_BUY, 0, price());
            case EDIT -> send(sellList ? Payloads.Trade.EDIT_SELL : Payloads.Trade.EDIT_BUY, 0, price());
        }
    }

    private void send(int action, int amount, long price) {
        ClientPlatform.INSTANCE.sendToServer(new Payloads.Trade(action, target, stack.getItem(), amount, price,
                limit(limitBox), limit(serverLimitBox)));
        screen.closeDialog();
    }

    boolean isInside(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        update();
        Font font = font();
        ShopScreen.panel(graphics, x, y, WIDTH, height);
        graphics.renderItem(stack, x + 8, y + 7);
        String name = font.plainSubstrByWidth(stack.getHoverName().getString(), WIDTH - 36);
        graphics.drawString(font, name, x + 28, y + 11, TEXT, false);

        List<Component> lines = info();
        for (int i = 0; i < lines.size(); i++) graphics.drawString(font, lines.get(i), x + 8, y + infoY + i * 10, TEXT, false);
        if (amountBox != null) field(graphics, amountY, kind == Kind.EDIT_OFFER || kind == Kind.EXPIRED ? "take_amount" : "amount");
        if (priceBox != null) field(graphics, priceY, "price.label");
        if (limitBox != null) field(graphics, limitY, "limit.player");
        if (serverLimitBox != null) field(graphics, serverLimitY, "limit.server");
        if (hasTotal()) {
            long total = total(price(), amount());
            Component text = t("total", total == Long.MAX_VALUE ? "-" : money(total));
            graphics.drawString(font, text, x + 8, y + totalY, TEXT, false);
        }
        Component reason = reason();
        if (reason != null && !reason.getString().isEmpty()) {
            String r = font.plainSubstrByWidth(reason.getString(), WIDTH - 16);
            graphics.drawString(font, r, x + (WIDTH - font.width(r)) / 2, y + reasonY, RED, false);
        }
        for (EditBox box : boxes) box.render(graphics, mouseX, mouseY, partialTick);
        for (Button button : buttons) button.render(graphics, mouseX, mouseY, partialTick);
    }

    private void field(GuiGraphics graphics, int rowY, String label) {
        graphics.drawString(font(), t(label), x + 8, y + rowY + 3, TEXT, false);
        ShopScreen.inset(graphics, x + LABEL_W + 8, y + rowY, WIDTH - LABEL_W - 16, 14);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isInside(mouseX, mouseY)) {
            screen.closeDialog();
            return true;
        }
        for (EditBox box : boxes) {
            if (box.isMouseOver(mouseX, mouseY)) {
                focus(box);
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) box.setValue("");
                else box.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }
        for (Button b : buttons) {
            if (b.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            screen.closeDialog();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            update();
            if (primary != null && primary.active) primary.onPress();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !boxes.isEmpty()) {
            int i = boxes.indexOf(focused());
            focus(boxes.get((i + 1) % boxes.size()));
            return true;
        }
        EditBox box = focused();
        if (box != null) box.keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    boolean charTyped(char c, int modifiers) {
        EditBox box = focused();
        if (box != null) box.charTyped(c, modifiers);
        return true;
    }
}
