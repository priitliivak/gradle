package org.gradle.plugin.management.internal;

import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.plugin.management.ConfigurablePluginRequest;
import org.gradle.plugin.management.PluginRequest;
import org.gradle.plugin.management.PluginResolveDetails;
import org.gradle.plugin.use.internal.InternalPluginRequest;

import java.util.LinkedHashSet;
import java.util.Set;

class PluginResolutions {

    private final Set<Action<? super PluginResolveDetails>> resolutionDetails = new LinkedHashSet<Action<? super PluginResolveDetails>>();

    public void add(Action<? super PluginResolveDetails> rule) {
        resolutionDetails.add(rule);
    }

    void resolveRequest(InternalPluginRequest pluginRequest) {
        ConfigurablePluginRequest configurablePluginRequest = new DefaultConfigurablePluginRequest(pluginRequest);
        DefaultPluginResolveDetails details = new DefaultPluginResolveDetails(configurablePluginRequest);
        for (Action<? super PluginResolveDetails> resolutionDetail : resolutionDetails) {
            resolutionDetail.execute(details);
        }
    }

}
