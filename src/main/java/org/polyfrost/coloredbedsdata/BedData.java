package org.polyfrost.coloredbedsdata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class BedData {

    public static final String UPSTREAM_URL = "https://data-v2.polyfrost.org/hytils/bed_locations.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("coloredbedsdata");
    private static final Path PROGRESS = DIR.resolve("progress.json");
    public static final Path OUTPUT = DIR.resolve("bed_locations.json");

    public final Map<String, String[]> maps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public final Map<String, Double> scores = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public final Map<String, TreeSet<String>> done = new TreeMap<>();

    public static BedData load() {
        BedData data = new BedData();
        if (!Files.exists(PROGRESS)) return data;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(PROGRESS)).getAsJsonObject();
            json.getAsJsonObject("maps").entrySet().forEach(e -> data.maps.put(e.getKey(), GSON.fromJson(e.getValue(), String[].class)));
            if (json.has("scores")) json.getAsJsonObject("scores").entrySet().forEach(e -> data.scores.put(e.getKey(), e.getValue().getAsDouble()));
            json.getAsJsonObject("done").entrySet().forEach(e -> {
                TreeSet<String> names = new TreeSet<>();
                for (String name : GSON.fromJson(e.getValue(), String[].class)) names.add(cleanName(name));
                data.done.put(e.getKey(), names);
            });
        } catch (Exception e) {
            ColoredBedsData.LOGGER.error("Failed to read {}", PROGRESS, e);
        }
        return data;
    }

    public void save() {
        JsonObject json = new JsonObject();
        json.add("maps", GSON.toJsonTree(maps));
        json.add("scores", GSON.toJsonTree(scores));
        json.add("done", GSON.toJsonTree(done));
        write(PROGRESS, json);
    }

    public static String cleanName(String selectorName) {
        return selectorName.replaceFirst("^[^A-Za-z0-9]+", "").replaceFirst("\\s*NEW!?$", "").trim();
    }

    public boolean isDone(String mode, String selectorName) {
        return done.getOrDefault(mode, new TreeSet<>()).contains(selectorName);
    }

    public void markDone(String mode, String selectorName) {
        done.computeIfAbsent(mode, k -> new TreeSet<>()).add(selectorName);
    }

    public void unmarkDone(String map) {
        done.values().forEach(names -> names.removeIf(map::equalsIgnoreCase));
    }

    public String modeOf(String map) {
        for (Map.Entry<String, TreeSet<String>> e : done.entrySet()) {
            if (e.getValue().stream().anyMatch(map::equalsIgnoreCase)) return e.getKey();
        }
        return null;
    }

    public static Map<String, String[]> fetchUpstreamMaps() throws IOException, InterruptedException {
        Map<String, String[]> maps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonElement el : fetchUpstream().getAsJsonArray("overrides")) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("maps")) continue;
            String[] locations = GSON.fromJson(o.get("locations"), String[].class);
            for (JsonElement m : o.getAsJsonArray("maps")) maps.put(m.getAsString(), locations);
        }
        return maps;
    }

    private static JsonObject fetchUpstream() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String body = client.send(HttpRequest.newBuilder(URI.create(UPSTREAM_URL)).build(), HttpResponse.BodyHandlers.ofString()).body();
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    public List<String> export() throws IOException, InterruptedException {
        JsonObject upstream = fetchUpstream();
        String[] defaults = GSON.fromJson(upstream.get("default"), String[].class);

        Map<String, String[]> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<JsonObject> modeOverrides = new ArrayList<>();
        for (JsonElement el : upstream.getAsJsonArray("overrides")) {
            JsonObject o = el.getAsJsonObject();
            String[] locations = GSON.fromJson(o.get("locations"), String[].class);
            if (o.has("maps")) {
                for (JsonElement m : o.getAsJsonArray("maps")) merged.put(m.getAsString(), locations);
            } else {
                modeOverrides.add(o);
            }
        }

        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String[]> e : maps.entrySet()) {
            String[] old = merged.getOrDefault(e.getKey(), defaults);
            if (!Arrays.equals(old, e.getValue())) changes.add(e.getKey() + ": " + String.join(",", old) + " -> " + String.join(",", e.getValue()));
            merged.put(e.getKey(), e.getValue());
        }

        Map<List<String>, JsonArray> grouped = new LinkedHashMap<>();
        merged.forEach((map, locations) -> {
            if (Arrays.equals(locations, defaults)) return;
            grouped.computeIfAbsent(List.of(locations), k -> new JsonArray()).add(map);
        });

        JsonArray overrides = new JsonArray();
        grouped.forEach((locations, mapNames) -> {
            JsonObject o = new JsonObject();
            o.add("locations", GSON.toJsonTree(locations));
            o.add("maps", mapNames);
            overrides.add(o);
        });
        modeOverrides.forEach(overrides::add);

        JsonObject out = new JsonObject();
        out.add("default", upstream.get("default"));
        out.add("overrides", overrides);
        write(OUTPUT, out);
        return changes;
    }

    private static void write(Path path, JsonElement json) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(json));
        } catch (IOException e) {
            ColoredBedsData.LOGGER.error("Failed to write {}", path, e);
        }
    }
}
