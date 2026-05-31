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

package org.fireflyframework.cache.factory;

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.cache.core.CacheHealth;
import org.fireflyframework.cache.core.CacheStats;
import org.fireflyframework.cache.core.CacheType;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reflection-based helper to create a JCache-backed CacheAdapter (JSR-107) without a compile-time dependency.
 * Supports both javax.cache and jakarta.cache namespaces.
 */
@Slf4j
public class JCacheCacheHelper {

    public static CacheAdapter createJCacheAdapter(String cacheName,
                                                   String keyPrefix,
                                                   Duration defaultTtl,
                                                   Object cacheManager) {
        return new JCacheAdapterReflective(cacheName, keyPrefix, defaultTtl, cacheManager);
    }

    /**
     * Reflective adapter for JCache providers. Because per-entry TTL is not universally supported across
     * providers at put-time, we implement a lightweight TTL overlay by wrapping values with an expiration.
     */
    private static class JCacheAdapterReflective implements CacheAdapter {
        private final String cacheName;
        private final String keyPrefix;
        private final Duration defaultTtl;

        private final Object cacheManager; // javax.cache.CacheManager or jakarta.cache.CacheManager
        private final Object cache;        // javax/jakarta.cache.Cache<String,Object>

        // methods on CacheManager and Cache
        private final Method getCacheMethod;    // CacheManager.getCache(String)
        private final Method createCacheMethod; // CacheManager.createCache(String, MutableConfiguration)
        private final Method getValueMethod;    // Cache.get(Object)
        private final Method putValueMethod;    // Cache.put(Object,Object)
        private final Method putIfAbsentMethod; // Cache.putIfAbsent(Object,Object)
        private final Method removeMethod;      // Cache.remove(Object)
        private final Method removeAllMethod;   // Cache.removeAll()
        private final Method containsKeyMethod; // Cache.containsKey(Object)
        private final Method iteratorMethod;    // Cache.iterator()
        private final boolean javaxNamespace;

        // TTL overlay when provider TTL is not configured per entry
        private final ConcurrentHashMap<String, Long> ttlOverlay = new ConcurrentHashMap<>();

        JCacheAdapterReflective(String cacheName, String keyPrefix, Duration defaultTtl, Object cacheManager) {
            try {
                this.cacheName = cacheName;
                this.keyPrefix = (keyPrefix != null && !keyPrefix.isBlank())
                        ? keyPrefix + ":" + cacheName + ":"
                        : "cache:" + cacheName + ":";
                this.defaultTtl = defaultTtl;
                this.cacheManager = Objects.requireNonNull(cacheManager, "CacheManager required");

                ClassLoader cl = cacheManager.getClass().getClassLoader();
                Class<?> cmClass;
                Class<?> cacheClass;
                Class<?> mutableCfgClass;
                Class<?> configClass;
                boolean isJavax;
                try {
                    cmClass = Class.forName("javax.cache.CacheManager", false, cl);
                    cacheClass = Class.forName("javax.cache.Cache", false, cl);
                    mutableCfgClass = Class.forName("javax.cache.configuration.MutableConfiguration", false, cl);
                    configClass = Class.forName("javax.cache.configuration.Configuration", false, cl);
                    isJavax = true;
                } catch (ClassNotFoundException ex) {
                    cmClass = Class.forName("jakarta.cache.CacheManager", false, cl);
                    cacheClass = Class.forName("jakarta.cache.Cache", false, cl);
                    mutableCfgClass = Class.forName("jakarta.cache.configuration.MutableConfiguration", false, cl);
                    configClass = Class.forName("jakarta.cache.configuration.Configuration", false, cl);
                    isJavax = false;
                }
                this.javaxNamespace = isJavax;

                this.getCacheMethod = cmClass.getMethod("getCache", String.class);
                // JSR-107 declares createCache(String, C extends Configuration); the erased
                // parameter type is the Configuration interface, not MutableConfiguration.
                this.createCacheMethod = cmClass.getMethod("createCache", String.class, configClass);

                this.getValueMethod = cacheClass.getMethod("get", Object.class);
                this.putValueMethod = cacheClass.getMethod("put", Object.class, Object.class);
                this.putIfAbsentMethod = cacheClass.getMethod("putIfAbsent", Object.class, Object.class);
                this.removeMethod = cacheClass.getMethod("remove", Object.class);
                this.removeAllMethod = cacheClass.getMethod("removeAll");
                this.containsKeyMethod = cacheClass.getMethod("containsKey", Object.class);
                this.iteratorMethod = cacheClass.getMethod("iterator"); // returns Iterator<Cache.Entry>

                // Obtain or create cache
                Object existing = getCacheMethod.invoke(cacheManager, this.cacheName);
                if (existing == null) {
                    Object cfg = mutableCfgClass.getConstructor().newInstance();
                    existing = createCacheMethod.invoke(cacheManager, this.cacheName, cfg);
                }
                this.cache = existing;

                log.info("JCache adapter created for cache '{}' with prefix '{}' (namespace: {})",
                        cacheName, this.keyPrefix, javaxNamespace ? "javax" : "jakarta");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize JCache reflective adapter: " + e.getMessage(), e);
            }
        }

        private String buildKey(Object key) {
            return keyPrefix + String.valueOf(key);
        }

        private static final class ValueWrapper implements java.io.Serializable {
            private static final long serialVersionUID = 1L;
            final Object value;
            final Long expiresAtMillis; // null means no overlay TTL
            ValueWrapper(Object value, Long expiresAtMillis) { this.value = value; this.expiresAtMillis = expiresAtMillis; }
        }

        private Object wrap(Object value, Duration ttl) {
            Long exp = null;
            Duration effective = ttl != null ? ttl : defaultTtl;
            if (effective != null && !effective.isZero() && !effective.isNegative()) {
                exp = System.currentTimeMillis() + effective.toMillis();
            }
            return new ValueWrapper(value, exp);
        }

        @SuppressWarnings("unchecked")
        private <V> Optional<V> unwrap(String fullKey, Object stored, Class<V> valueType) {
            if (stored == null) return Optional.empty();
            if (stored instanceof ValueWrapper vw) {
                if (vw.expiresAtMillis != null && System.currentTimeMillis() > vw.expiresAtMillis) {
                    try { removeMethod.invoke(cache, fullKey); } catch (Exception ignored) {}
                    ttlOverlay.remove(fullKey);
                    return Optional.empty();
                }
                if (vw.value == null) return Optional.empty();
                if (valueType == Object.class || valueType.isInstance(vw.value)) {
                    return Optional.of((V) vw.value);
                }
                return Optional.empty();
            }
            if (valueType == Object.class || valueType.isInstance(stored)) {
                return Optional.of((V) stored);
            }
            return Optional.empty();
        }

        @Override
        public <K, V> Mono<Optional<V>> get(K key) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    Object v = getValueMethod.invoke(cache, k);
                    return unwrap(k, v, (Class<V>) Object.class);
                } catch (Exception e) {
                    log.warn("JCache get error: {}", e.getMessage());
                    return Optional.empty();
                }
            });
        }

        @Override
        public <K, V> Mono<Optional<V>> get(K key, Class<V> valueType) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    Object v = getValueMethod.invoke(cache, k);
                    return unwrap(k, v, valueType);
                } catch (Exception e) {
                    log.warn("JCache get(T) error: {}", e.getMessage());
                    return Optional.empty();
                }
            });
        }

        @Override
        public <K, V> Mono<Void> put(K key, V value) {
            return Mono.fromRunnable(() -> {
                try {
                    String k = buildKey(key);
                    Object wrapped = wrap(value, null);
                    putValueMethod.invoke(cache, k, wrapped);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <K, V> Mono<Void> put(K key, V value, Duration ttl) {
            return Mono.fromRunnable(() -> {
                try {
                    String k = buildKey(key);
                    Object wrapped = wrap(value, ttl);
                    putValueMethod.invoke(cache, k, wrapped);
                    if (ttl != null) ttlOverlay.put(k, System.currentTimeMillis() + ttl.toMillis());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <K, V> Mono<Boolean> putIfAbsent(K key, V value) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    Object wrapped = wrap(value, null);
                    Object prev = putIfAbsentMethod.invoke(cache, k, wrapped);
                    return prev == null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <K, V> Mono<Boolean> putIfAbsent(K key, V value, Duration ttl) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    Object wrapped = wrap(value, ttl);
                    Object prev = putIfAbsentMethod.invoke(cache, k, wrapped);
                    boolean stored = prev == null;
                    if (stored && ttl != null) ttlOverlay.put(k, System.currentTimeMillis() + ttl.toMillis());
                    return stored;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <K> Mono<Boolean> evict(K key) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    boolean existed = (Boolean) containsKeyMethod.invoke(cache, k);
                    removeMethod.invoke(cache, k);
                    ttlOverlay.remove(k);
                    return existed;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public Mono<Void> clear() {
            return Mono.fromRunnable(() -> {
                try {
                    removeAllMethod.invoke(cache);
                    ttlOverlay.clear();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <K> Mono<Boolean> exists(K key) {
            return Mono.fromCallable(() -> {
                try {
                    String k = buildKey(key);
                    if (ttlOverlay.containsKey(k)) {
                        Long exp = ttlOverlay.get(k);
                        if (exp != null && System.currentTimeMillis() > exp) {
                            removeMethod.invoke(cache, k);
                            ttlOverlay.remove(k);
                            return false;
                        }
                    }
                    return (Boolean) containsKeyMethod.invoke(cache, k);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K> Mono<Set<K>> keys() {
            return Mono.fromCallable(() -> {
                try {
                    java.util.Iterator<?> it = (java.util.Iterator<?>) iteratorMethod.invoke(cache);
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    while (it.hasNext()) {
                        Object entry = it.next();
                        Method getKey = entry.getClass().getMethod("getKey");
                        Object k = getKey.invoke(entry);
                        String ks = String.valueOf(k);
                        if (ks.startsWith(keyPrefix)) {
                            keys.add(ks.substring(keyPrefix.length()));
                        }
                    }
                    return (Set<K>) keys.stream().map(k -> (K) k).collect(Collectors.toSet());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public Mono<Long> size() {
            // Iterate to count (portable); some providers have getManagement API, but keep simple
            return keys().map(set -> (long) set.size());
        }

        @Override
        public Mono<CacheStats> getStats() {
return size().map(count -> CacheStats.empty(CacheType.JCACHE, cacheName));
        }

        @Override
        public CacheType getCacheType() {
            return CacheType.JCACHE;
        }

        @Override
        public String getCacheName() {
            return cacheName;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Mono<CacheHealth> getHealth() {
            return Mono.just(CacheHealth.healthy(CacheType.JCACHE, cacheName, null));
        }

        @Override
        public void close() {
            // No-op
        }
    }
}
