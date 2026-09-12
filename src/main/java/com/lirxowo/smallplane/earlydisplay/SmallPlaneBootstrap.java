package com.lirxowo.smallplane.earlydisplay;

import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmallPlaneBootstrap implements GraphicsBootstrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmallPlaneBootstrap.class);
    private static final String ENABLED_PROPERTY = "smallplane.earlyWindow";

    @Override
    public String name() {
        return SmallPlaneWindowProvider.PROVIDER_NAME;
    }

    @Override
    public void bootstrap(String[] arguments) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            LOGGER.info("The small plane loading screen is disabled by {}", ENABLED_PROPERTY);
            return;
        }

        String configured = FMLConfig.getConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER);
        if (SmallPlaneWindowProvider.FML_PROVIDER_NAME.equals(configured)) {
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_PROVIDER, SmallPlaneWindowProvider.PROVIDER_NAME);
        } else if (!SmallPlaneWindowProvider.PROVIDER_NAME.equals(configured)) {
            LOGGER.info("Early window provider {} is configured, leaving it alone", configured);
        }
    }
}
