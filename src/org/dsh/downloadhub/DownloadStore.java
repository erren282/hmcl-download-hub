/*
 * 下载中心插件 —— 记录存储
 *
 * 本插件是第三方独立作品，与 HMCL 官方无关。
 */
package org.dsh.downloadhub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Keeps the download list on disk.
///
/// <p>Records are saved by default — that is what makes the page useful after a restart — and the
/// user can clear them from the page. Writes are throttled by the caller rather than here: the list
/// changes on every progress tick, and rewriting the file that often would be wasteful.</p>
final class DownloadStore {

    /// File holding the history.
    private static final String FILE = "downloads.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final PluginContext context;

    DownloadStore(PluginContext context) {
        this.context = context;
    }

    /// Loads the saved records.
    ///
    /// <p>Anything that was still running when the launcher closed is marked as cancelled: it can
    /// no longer make progress, and leaving it "running" forever would be misleading.</p>
    ///
    /// @return the records, newest first
    List<DownloadEntry> load() {
        List<DownloadEntry> entries = new ArrayList<>();
        Path file = context.getDataFile(FILE);
        if (!Files.isRegularFile(file))
            return entries;

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                DownloadEntry entry = fromJson(element.getAsJsonObject());
                if (entry != null) {
                    if (entry.state == DownloadEntry.State.RUNNING)
                        entry.state = DownloadEntry.State.CANCELLED;
                    entries.add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            context.logWarning("无法读取下载记录：" + e.getMessage());
        }
        return entries;
    }

    /// Saves the records, keeping the list ordered newest first.
    ///
    /// @param entries the records
    void save(List<DownloadEntry> entries) {
        JsonArray array = new JsonArray();
        for (DownloadEntry entry : entries)
            array.add(toJson(entry));

        try {
            Path file = context.getDataFile(FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(array), StandardCharsets.UTF_8);
        } catch (IOException e) {
            context.logWarning("无法保存下载记录：" + e.getMessage());
        }
    }

    /// Deletes the history file.
    void clear() {
        try {
            Files.deleteIfExists(context.getDataFile(FILE));
        } catch (IOException e) {
            context.logWarning("无法清空下载记录：" + e.getMessage());
        }
    }

    /// Serialises one record.
    ///
    /// @param entry the record
    /// @return the JSON object
    private static JsonObject toJson(DownloadEntry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("name", entry.name);
        object.addProperty("url", entry.url);
        object.addProperty("target", entry.target);
        object.addProperty("state", entry.state.name());
        object.addProperty("received", entry.received);
        object.addProperty("total", entry.total);
        object.addProperty("startedAt", entry.startedAt);
        object.addProperty("message", entry.message);
        return object;
    }

    /// Reads one record.
    ///
    /// @param object the JSON object
    /// @return the record, or `null` when it is unusable
    private static DownloadEntry fromJson(JsonObject object) {
        try {
            DownloadEntry entry = new DownloadEntry();
            entry.name = string(object, "name", "");
            entry.url = string(object, "url", "");
            entry.target = string(object, "target", "");
            entry.received = number(object, "received");
            entry.total = number(object, "total");
            entry.startedAt = number(object, "startedAt");
            entry.message = string(object, "message", "");
            entry.state = DownloadEntry.State.valueOf(
                    string(object, "state", DownloadEntry.State.DONE.name()));
            return entry;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Reads a string field with a fallback.
    ///
    /// @param object  the JSON object
    /// @param field   the field name
    /// @param fallback the value to use when absent
    /// @return the value
    private static String string(JsonObject object, String field, String fallback) {
        if (!object.has(field) || object.get(field).isJsonNull())
            return fallback;
        return object.get(field).getAsString();
    }

    /// Reads a numeric field.
    ///
    /// @param object the JSON object
    /// @param field  the field name
    /// @return the value, or `0`
    private static long number(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull())
            return 0L;
        return object.get(field).getAsLong();
    }
}
