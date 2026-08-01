package dev.haraguro.modserverplaymanager.servermanager;

import com.google.gson.Gson;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

/**
 * Javalin defaults to Jackson and throws on ctx.json(...)/bodyAsClass(...)
 * without it. The rest of this project (shared, launcher, mod, sync-server)
 * already standardizes on Gson, so this wires Javalin to the same library
 * instead of pulling in a second JSON stack.
 */
public class GsonJsonMapper implements JsonMapper {

    private final Gson gson = new Gson();

    @Override
    public String toJsonString(Object obj, Type type) {
        return gson.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return gson.fromJson(json, targetType);
    }
}
