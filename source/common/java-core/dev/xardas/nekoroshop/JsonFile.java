package dev.xardas.nekoroshop;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

/** Small helpers for config files: JSON with // comments, created from a default text when missing. */
final class JsonFile {
    private JsonFile() { }

    static JsonObject read(Path file, String defaults) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, defaults, StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        } catch (Exception e) {
            NekoroShop.LOGGER.error("Could not read {}, using the defaults", file, e);
            return parse(new StringReader(defaults));
        }
    }

    private static JsonObject parse(Reader reader) {
        JsonReader json = new JsonReader(reader);
        json.setLenient(true);
        return JsonParser.parseReader(json).getAsJsonObject();
    }

    static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    static long number(JsonObject o, String key, long fallback) {
        return o.has(key) ? o.get(key).getAsLong() : fallback;
    }

    static String string(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    /** A single string or a list of strings. */
    static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key)) return out;
        JsonElement e = o.get(key);
        if (e.isJsonArray()) e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        else out.add(e.getAsString());
        return out;
    }
}
