package org.polyfrost.example.client;

import net.fabricmc.api.ClientModInitializer;
import org.polyfrost.oneconfig.api.commands.v1.CommandManager;

public class ExampleClient implements ClientModInitializer {

    public static ExampleClient INSTANCE;

    public ExampleClient() {
        INSTANCE = this;
    }

    @Override
    public void onInitializeClient() {
        ExampleConfig.INSTANCE.preload();
        CommandManager.register(new ExampleCommand());
    }

}
