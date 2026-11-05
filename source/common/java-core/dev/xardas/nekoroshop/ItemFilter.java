package dev.xardas.nekoroshop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * A list of config entries matched against items: "minecraft:oak_planks" (one item), "#minecraft:planks" (a tag),
 * "mcwfurnitures:*" or "minecraft:*_planks" (* matches any text), "*" (everything) and "[blocks]" (every block).
 */
public final class ItemFilter {
    public static final ItemFilter NONE = new ItemFilter(List.of());

    private final List<Predicate<Holder<Item>>> entries;

    private ItemFilter(List<Predicate<Holder<Item>>> entries) {
        this.entries = entries;
    }

    public static ItemFilter of(List<String> entries) {
        List<Predicate<Holder<Item>>> out = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag == null) {
                    NekoroShop.LOGGER.warn("Bad tag \"{}\" in the config, skipped", entry);
                    continue;
                }
                TagKey<Item> key = TagKey.create(Registries.ITEM, tag);
                out.add(holder -> holder.is(key));
            } else if (entry.equals("*")) {
                out.add(holder -> true);
            } else if (entry.equals("[blocks]")) {
                out.add(holder -> holder.value() instanceof BlockItem);
            } else {
                String id = entry.contains(":") ? entry : "minecraft:" + entry;
                Pattern pattern = glob(id);
                out.add(holder -> holder.unwrapKey().map(k -> pattern.matcher(k.location().toString()).matches()).orElse(false));
            }
        }
        return new ItemFilter(out);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean matches(Item item) {
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        for (Predicate<Holder<Item>> entry : entries) {
            if (entry.test(holder)) return true;
        }
        return false;
    }

    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (!regex.isEmpty()) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
