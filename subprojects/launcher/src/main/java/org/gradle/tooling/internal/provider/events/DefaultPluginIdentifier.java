/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider.events;

import org.gradle.tooling.internal.protocol.events.InternalPluginIdentifier;

import javax.annotation.Nullable;

public class DefaultPluginIdentifier implements InternalPluginIdentifier {

    private final String className;
    private final String pluginId;

    public DefaultPluginIdentifier(Class<?> pluginClass, @Nullable String pluginId) {
        this.className = pluginClass.getName();
        this.pluginId = pluginId;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultPluginIdentifier that = (DefaultPluginIdentifier) o;
        return className.equals(that.className);
    }

    @Override
    public int hashCode() {
        return className.hashCode();
    }

}
