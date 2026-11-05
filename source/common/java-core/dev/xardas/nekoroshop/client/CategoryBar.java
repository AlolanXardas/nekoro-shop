package dev.xardas.nekoroshop.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A row of category buttons above a grid of items, with arrows on both sides to move through them. The categories are
 * "All", then any the caller adds, then every creative inventory tab (vanilla and mods, in the creative order, with
 * their icons and names): an item belongs where it is in the creative inventory. Categories with nothing in the grid
 * are not shown; the arrows stay but do nothing when there is nowhere to move.
 */
final class CategoryBar {
    /** icon is an item, or empty when picture (a 16x16 texture) is drawn instead. */
    record Category(String key, Component name, ItemStack icon, ResourceLocation picture, Predicate<Item> test) {
        Category(String key, Component name, ItemStack icon, Predicate<Item> test) {
            this(key, name, icon, null, test);
        }
    }

    /** Positions inside the window: the row, the left arrow, the first button and the right arrow. */
    static final int Y = 34, LEFT_X = 59, FIRST_X = 77, RIGHT_X = 225, SIZE = 18, SLOTS = 8, ARROW_W = 14;
    private static final String ALL = "all";
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    /** The selected category looks pressed. */
    private static final ResourceLocation PRESSED = ResourceLocation.withDefaultNamespace("widget/button_disabled");

    // kept while the game runs, also when a category is empty for a moment (e.g. in another part of the shop)
    private static String selected = ALL;
    private static int offset;

    private final Category all;
    private final List<Category> categories = new ArrayList<>();
    private final List<Category> visible = new ArrayList<>();
    private int left, top;
    private Button prev, next;
    /** Called when the selected category changes (the screen scrolls its grid back to the top). */
    private Runnable onSelect = () -> { };

    /** allPicture: the 16x16 picture of "All" (a placeholder, to be drawn). */
    CategoryBar(Component allName, ResourceLocation allPicture) {
        all = new Category(ALL, allName, ItemStack.EMPTY, allPicture, item -> true);
        setCategories(List.of(), true);
    }

    /** Categories of the caller, shown after "All", and (when creative is on) the creative tabs after them. */
    void setCategories(List<Category> custom, boolean creative) {
        categories.clear();
        categories.add(all);
        // every key once: two categories with one key could not both be selected
        Set<String> keys = new HashSet<>();
        keys.add(all.key());
        for (Category c : custom) {
            if (keys.add(c.key())) categories.add(c);
        }
        if (creative) {
            for (Category c : creativeTabs()) {
                if (keys.add(c.key())) categories.add(c);
            }
        }
        visible.clear();
        visible.addAll(categories);
    }

    private static List<Category> creativeTabs() {
        Minecraft mc = Minecraft.getInstance();
        List<Category> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        // what the creative inventory does when it opens; without it the tabs are empty until it was opened once
        if (CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                mc.player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get(), mc.level.registryAccess())) {
            ClientPlatform.INSTANCE.updateCreativeSearch(mc.level.registryAccess());
        }
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            Set<Item> items = new HashSet<>();
            for (ItemStack stack : tab.getDisplayItems()) items.add(stack.getItem());
            if (items.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            // a copy taken once, so an icon that keeps changing stays the same; an icon that is none of the tab's items
            // (a special changing icon, e.g. Gems Realm) is replaced by the tab's first item (the Nekoro tab keeps its coin)
            ItemStack icon = tab.getIconItem().copy();
            if (!items.contains(icon.getItem()) && !"nekoro".equals(id.getNamespace())) {
                icon = tab.getDisplayItems().iterator().next().copy();
            }
            out.add(new Category("tab:" + id, tab.getDisplayName(), icon, items::contains));
        }
        return out;
    }

    /** Creates the arrows; add returns the button after adding it to the screen. */
    void init(int left, int top, java.util.function.UnaryOperator<Button> add) {
        this.left = left;
        this.top = top;
        prev = add.apply(Button.builder(Component.literal("<"), b -> step(-1)).bounds(left + LEFT_X, top + Y, ARROW_W, SIZE).build());
        next = add.apply(Button.builder(Component.literal(">"), b -> step(1)).bounds(left + RIGHT_X, top + Y, ARROW_W, SIZE).build());
        updateArrows();
    }

    /** Only categories with at least one of these items are shown ("All" always). */
    void update(Collection<Item> items) {
        visible.clear();
        for (Category c : categories) {
            if (c == all || items.stream().anyMatch(c.test())) visible.add(c);
        }
        offset = visible.size() > SLOTS ? Math.floorMod(offset, visible.size()) : 0;
        updateArrows();
    }

    void onSelect(Runnable action) {
        onSelect = action;
    }

    private void updateArrows() {
        if (prev == null) return;
        // the arrows select the previous or next category; after the last comes the first again
        prev.active = visible.size() > 1;
        next.active = visible.size() > 1;
    }

    /** Selects the category before or after the selected one and moves the row so it can be seen. */
    private void step(int by) {
        if (visible.size() < 2) return;
        int at = visible.indexOf(current());
        int index = Math.floorMod(at + by, visible.size());
        selected = visible.get(index).key();
        if (visible.size() > SLOTS) {
            int shown = Math.floorMod(index - offset, visible.size());
            if (shown >= SLOTS) offset = by > 0 ? Math.floorMod(index - SLOTS + 1, visible.size()) : index;
        }
        onSelect.run();
    }

    private void move(int by) {
        if (visible.size() > SLOTS) offset = Math.floorMod(offset + by, visible.size());
        updateArrows();
    }

    /** The selected category, or "All" when the selected one has nothing in the grid now. */
    private Category current() {
        for (Category c : visible) {
            if (c.key().equals(selected)) return c;
        }
        return all;
    }

    String selectedKey() {
        return current().key();
    }

    boolean test(Item item) {
        return current().test().test(item);
    }

    private int at(double mouseX, double mouseY) {
        if (mouseY < top + Y || mouseY >= top + Y + SIZE) return -1;
        int i = (int) Math.floor((mouseX - left - FIRST_X) / SIZE);
        if (mouseX < left + FIRST_X || i < 0 || i >= Math.min(SLOTS, visible.size())) return -1;
        return (offset + i) % visible.size();
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Category current = current();
        int hovered = at(mouseX, mouseY);
        for (int i = 0; i < Math.min(SLOTS, visible.size()); i++) {
            int index = (offset + i) % visible.size();
            Category c = visible.get(index);
            int x = left + FIRST_X + i * SIZE, y = top + Y;
            graphics.blitSprite(c == current ? PRESSED : index == hovered ? HIGHLIGHTED : BUTTON, x, y, SIZE, SIZE);
            if (c.picture() != null) graphics.blit(c.picture(), x + 1, y + 1, 0, 0, 16, 16, 16, 16);
            else graphics.renderItem(c.icon(), x + 1, y + 1);
        }
    }

    Component tooltip(double mouseX, double mouseY) {
        int i = at(mouseX, mouseY);
        return i < 0 ? null : visible.get(i).name();
    }

    boolean click(double mouseX, double mouseY) {
        int i = at(mouseX, mouseY);
        if (i < 0) return false;
        selected = visible.get(i).key();
        onSelect.run();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        return true;
    }

    /** The mouse wheel over the row moves through the categories. */
    boolean scroll(double mouseX, double mouseY, double amount) {
        if (mouseY < top + Y || mouseY >= top + Y + SIZE || mouseX < left + LEFT_X || mouseX >= left + RIGHT_X + ARROW_W) return false;
        move(amount > 0 ? -1 : 1);
        return true;
    }
}
