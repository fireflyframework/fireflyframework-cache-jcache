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
import org.fireflyframework.cache.spi.CacheProviderFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the JCache (JSR-107) cache provider is discovered via the {@link ServiceLoader}
 * SPI mechanism and reports {@link CacheType#JCACHE}, and that it can build a working
 * {@link CacheAdapter} backed by a host-supplied JSR-107 {@link CacheManager}.
 */
class JCacheProviderWiringTest {

    @Test
    void serviceLoaderDiscoversJCacheProvider() {
        ServiceLoader<CacheProviderFactory> loader = ServiceLoader.load(CacheProviderFactory.class);

        CacheProviderFactory jcache = loader.stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.getType() == CacheType.JCACHE)
                .findFirst()
                .orElse(null);

        assertThat(jcache)
                .as("ServiceLoader must discover a CacheProviderFactory with getType() == JCACHE")
                .isNotNull();
        assertThat(jcache).isInstanceOf(JCacheProvider.class);
        assertThat(jcache.getType()).isEqualTo(CacheType.JCACHE);
        assertThat(jcache.priority()).isEqualTo(30);
    }

    @Test
    void providerIsAvailableAndCreatesWorkingAdapterWithHostSuppliedCacheManager() {
        CachingProvider cachingProvider = Caching.getCachingProvider();
        try (CacheManager cacheManager = cachingProvider.getCacheManager()) {
            CacheProviderFactory.ProviderContext ctx = new CacheProviderFactory.ProviderContext(
                    null, null, null, null, cacheManager);

            JCacheProvider provider = new JCacheProvider();

            assertThat(provider.isAvailable(ctx))
                    .as("JCache provider must report available when a JSR-107 CacheManager bean is supplied")
                    .isTrue();

            CacheAdapter adapter = provider.create("wiring-test", "firefly", Duration.ofMinutes(5), ctx);
            assertThat(adapter.getCacheType()).isEqualTo(CacheType.JCACHE);
            assertThat(adapter.getCacheName()).isEqualTo("wiring-test");

            StepVerifier.create(adapter.put("k", "v").then(adapter.<String, String>get("k")))
                    .expectNext(Optional.of("v"))
                    .verifyComplete();

            StepVerifier.create(adapter.evict("k"))
                    .expectNext(true)
                    .verifyComplete();

            StepVerifier.create(adapter.<String>exists("k"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }
}
