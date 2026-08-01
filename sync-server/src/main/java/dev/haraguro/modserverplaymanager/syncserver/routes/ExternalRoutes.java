package dev.haraguro.modserverplaymanager.syncserver.routes;

import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;

import java.util.Map;

/**
 * Placeholder for external-service integrations (e.g. real-world exchange
 * rates feeding an in-game economy mod). Returns static example data;
 * replace with a real upstream client + caching layer when needed.
 */
public class ExternalRoutes {

    public void register(Javalin app) {
        app.get(Routes.EXCHANGE_RATES, ctx -> ctx.json(Map.of(
                "base", "USD",
                "rates", Map.of("JPY", 148.0, "EUR", 0.92),
                "note", "static placeholder — wire up a real exchange rate provider here"
        )));
    }
}
