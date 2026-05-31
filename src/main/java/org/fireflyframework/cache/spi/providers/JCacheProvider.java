/*
 * Copyright 2024-2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.cache.spi.providers;

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.cache.core.CacheType;
import org.fireflyframework.cache.factory.JCacheCacheHelper;
import org.fireflyframework.cache.spi.CacheProviderFactory;

import java.time.Duration;

public class JCacheProvider implements CacheProviderFactory {
    @Override public CacheType getType() { return CacheType.JCACHE; }
    @Override public int priority() { return 30; }

    @Override
    public boolean isAvailable(ProviderContext ctx) {
        try {
            try { Class.forName("javax.cache.CacheManager"); }
            catch (ClassNotFoundException ex) { Class.forName("jakarta.cache.CacheManager"); }
            return ctx.jcacheManager != null;
        } catch (ClassNotFoundException e) { return false; }
    }

    @Override
    public CacheAdapter create(String cacheName, String keyPrefix, Duration defaultTtl, ProviderContext ctx) {
        try {
            Class<?> helperClass = Class.forName("org.fireflyframework.cache.factory.JCacheCacheHelper");
            Class<?> cm;
            try { cm = Class.forName("javax.cache.CacheManager"); }
            catch (ClassNotFoundException ex) { cm = Class.forName("jakarta.cache.CacheManager"); }
            var m = helperClass.getMethod("createJCacheAdapter",
                    String.class, String.class, Duration.class, Object.class);
            return (CacheAdapter) m.invoke(null, cacheName, keyPrefix, defaultTtl, ctx.jcacheManager);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create JCache cache via SPI", e);
        }
    }
}
