package org.polyfrost.example.client;

import org.polyfrost.example.ExampleConstants;
import org.polyfrost.oneconfig.api.commands.v1.factories.annotated.Command;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

/**
 * An example command implementing the Command api of OneConfig.
 * Registered in ExampleMod.java with `CommandManager.INSTANCE.registerCommand(new ExampleCommand());`
 *
 * @see Command
 * @see ExampleClient
 */
@Command(ExampleConstants.ID)
public class ExampleCommand {

    @Command
    private void main() {
        ScreensKt.openUI(ExampleConfig.INSTANCE);
    }

}
