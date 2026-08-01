package dev.haraguro.modserverplaymanager.mod;

import dev.haraguro.modserverplaymanager.mod.api.ApiClient;
import dev.haraguro.modserverplaymanager.mod.bank.BankCommands;
import dev.haraguro.modserverplaymanager.mod.bank.network.BankNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncMod implements ModInitializer {

    public static final String MOD_ID = "mcsync-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ApiClient apiClient;

    @Override
    public void onInitialize() {
        apiClient = new ApiClient(System.getProperty("mcsync.api.baseUrl", "http://localhost:7070"));

        apiClient.healthCheckAsync().whenComplete((ok, error) -> {
            if (error != null) {
                LOGGER.warn("mcsync API server not reachable at {}: {}", apiClient.getBaseUrl(), error.toString());
            } else if (ok) {
                LOGGER.info("mcsync API server reachable at {}", apiClient.getBaseUrl());
            }
        });

        BankNetworking.registerTypesAndServerReceivers();
        BankCommands.register();
    }

    public static ApiClient apiClient() {
        return apiClient;
    }
}
