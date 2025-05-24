package org.polyfrost.example.client;

import org.polyfrost.example.ExampleConstants;
import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;

/**
 * The main Config entrypoint that extends the Config type and initializes your config options.
 * See <a href="https://docsv1.polyfrost.org/configuration/available-options">this link</a> for more config Options
 */
public class ExampleConfig extends Config {

    @Switch(title = "Example Switch")
    public static boolean exampleSwitch = false; // The default value for the boolean Switch.

    @Slider(
            title = "Example Slider",
            min = 0f, max = 100f, // Minimum and maximum values for the slider.
            step = 10 // The amount of steps that the slider should have.
    )
    public static float exampleSlider = 50f; // The default value for the float Slider.

    @Dropdown(
            title = "Example Dropdown", // Name of the Dropdown
            options = {"Option 1", "Option 2", "Option 3", "Option 4"} // Options available.
    )
    public static int exampleDropdown = 1; // Default option (in this case "Option 2")

    public static final ExampleConfig INSTANCE = new ExampleConfig(); // The instance of the config.

    public ExampleConfig() {
        super(ExampleConstants.ID + ".json", ExampleConstants.NAME, Category.OTHER); // TODO: Change your category here.
    }

}
