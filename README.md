# Firefly Framework - Cache JCache Adapter

[![CI](https://github.com/fireflyframework/fireflyframework-cache-jcache/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-cache-jcache/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> JSR-107 (JCache) provider adapter for the Firefly cache abstraction — plug any compliant `CacheManager` (Ehcache, Infinispan, Hazelcast JCache, ...) behind the unified reactive cache API.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [How It Works](#how-it-works)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

`fireflyframework-cache-jcache` is a **pluggable cache provider adapter** for the Firefly Framework unified cache abstraction. It lets a Firefly application back its caches with **any JSR-107 (JCache) compliant implementation** — such as Ehcache, Infinispan, or Hazelcast JCache — without changing a line of application code.

The Firefly cache core (`fireflyframework-cache`) defines a provider-agnostic, reactive cache API and a small **ServiceLoader SPI** (`org.fireflyframework.cache.spi.CacheProviderFactory`). Each backend ships as its own adapter module and is discovered at runtime. This module contributes a `JCacheProvider` that reports `CacheType.JCACHE` and builds a reactive `CacheAdapter` on top of a host-supplied `javax.cache.CacheManager` / `jakarta.cache.CacheManager`. Simply having this adapter on the classpath, plus a JCache `CacheManager` bean and the `firefly.cache.default-cache-type: JCACHE` property, is enough to route all Firefly caching through JSR-107.

This adapter is **purely SPI-based** — it contributes no Spring auto-configuration of its own. That keeps it lightweight and lets the host pick (and configure) the concrete JSR-107 provider it prefers. It sits alongside the other Firefly cache adapters; pick exactly the one that matches your infrastructure:

| Adapter module | `CacheType` | Backend |
|----------------|-------------|---------|
| `fireflyframework-cache` (core) | `CAFFEINE` | In-process Caffeine (built-in default) |
| `fireflyframework-cache-redis` | `REDIS` | Redis / Lettuce (distributed) |
| `fireflyframework-cache-hazelcast` | `HAZELCAST` | Hazelcast IMDG (distributed) |
| **`fireflyframework-cache-jcache`** | **`JCACHE`** | **Any JSR-107 provider** |
| `fireflyframework-cache-postgresql` | `POSTGRESQL` | PostgreSQL via R2DBC |

## Features

- **`JCacheProvider`** discovered via the Firefly cache `CacheProviderFactory` SPI (Java `ServiceLoader`) — no manual bean wiring.
- Reports **`CacheType.JCACHE`** with a provider **priority of `30`**, so the core selects it deterministically when JCache is requested.
- Backed by **any host-supplied JSR-107 `CacheManager`** — Ehcache, Infinispan, Hazelcast JCache, Apache Commons JCS, and more.
- **Dual-namespace support**: the bundled `JCacheCacheHelper` adapts the `CacheManager` reflectively, so it works against both the legacy `javax.cache` and the newer `jakarta.cache` API generations.
- **Portable per-entry TTL overlay** applied on top of the JCache store, giving consistent expiration semantics without relying on provider-specific `ExpiryPolicy` configuration.
- Full **reactive `CacheAdapter` surface**: `get`, `put`, `putIfAbsent`, `evict`, `clear`, `exists`, `keys`, `size`, statistics, and health reporting — all returning Reactor types.
- **Zero Spring auto-configuration** in this module — pure, lightweight SPI wiring that defers provider choice and tuning to the host application.

## Requirements

- Java 21+ (Java 25 recommended)
- Spring Boot 3.x
- Maven 3.9+
- A JSR-107 (JCache) provider on the classpath exposing a `CacheManager` bean — for example **Ehcache 3.x** (use the `jakarta` classifier), **Infinispan**, or **Hazelcast JCache**.

## Installation

Add the adapter alongside the Firefly cache core. The version is managed by the Firefly parent/BOM, so you normally omit `<version>`:

```xml
<dependencies>
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-cache</artifactId>
    </dependency>
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-cache-jcache</artifactId>
    </dependency>
</dependencies>
```

You must also bring your chosen JSR-107 implementation. For example, Ehcache 3 with the Jakarta classifier:

```xml
<dependency>
    <groupId>org.ehcache</groupId>
    <artifactId>ehcache</artifactId>
    <version>3.10.8</version>
    <classifier>jakarta</classifier>
</dependency>
```

If you manage versions yourself, the parent pom for this release is `org.fireflyframework:fireflyframework-parent:26.05.08`.

## Quick Start

**1. Provide a JSR-107 `CacheManager` bean.** The adapter does not create one for you — it consumes the one your application exposes:

```java
@Configuration
public class JCacheConfig {

    @Bean
    public javax.cache.CacheManager jCacheManager() {
        // Resolves the JSR-107 provider on the classpath (e.g. Ehcache).
        return javax.cache.Caching.getCachingProvider().getCacheManager();
    }
}
```

> Using the Jakarta variant of a provider? Expose a `jakarta.cache.CacheManager` bean instead — the adapter handles both namespaces reflectively.

**2. Select JCache as the cache backend:**

```yaml
firefly:
  cache:
    default-cache-type: JCACHE
```

**3. Use the Firefly cache API as usual** — it is now served by your JCache provider:

```java
@Service
public class ProductService {

    private final FireflyCacheManager cache; // from fireflyframework-cache

    public ProductService(FireflyCacheManager cache) {
        this.cache = cache;
    }

    public Mono<Product> findById(String id) {
        return cache.get("products", id, Product.class)
                .switchIfEmpty(loadFromDb(id)
                        .flatMap(p -> cache.put("products", id, p).thenReturn(p)));
    }
}
```

## Configuration

This adapter has **no `@ConfigurationProperties` of its own**. It is activated through the Firefly cache core's properties (`firefly.cache.*`). The only key strictly required to engage this adapter is the backend selector:

```yaml
firefly:
  cache:
    default-cache-type: JCACHE   # route Firefly caching through the JCache adapter
```

| Property | Description | Default |
|----------|-------------|---------|
| `firefly.cache.default-cache-type` | Selects the active cache provider. Set to `JCACHE` to use this adapter. | `CAFFEINE` |

All other cache tuning (default TTL, per-cache settings, statistics, health) is governed by `fireflyframework-cache` and applies uniformly across providers. Backend-specific tuning (heap/off-heap tiers, disk persistence, clustering) is configured on your JSR-107 provider itself (e.g. via Ehcache XML or programmatic `CacheManager` setup). See the `fireflyframework-cache` README for the full list of `firefly.cache.*` properties.

## How It Works

1. At startup, the Firefly cache core scans the classpath via `ServiceLoader` for `CacheProviderFactory` implementations registered under `META-INF/services/`.
2. This module registers `JCacheProvider`, which advertises `CacheType.JCACHE` and priority `30`.
3. When `firefly.cache.default-cache-type=JCACHE`, the core invokes the provider with a context carrying your `CacheManager` bean.
4. `JCacheProvider` wraps the manager in `JCacheCacheHelper` (handling `javax`/`jakarta` reflectively) and returns a reactive `CacheAdapter` with a portable TTL overlay layered on top of the JCache store.

## Documentation

- Firefly Framework module catalog and docs hub: [github.com/fireflyframework](https://github.com/fireflyframework)
- Cache core (SPI, properties, multi-tier strategy): [`fireflyframework-cache`](https://github.com/fireflyframework/fireflyframework-cache)
- JSR-107 specification: [JCache (JSR-107)](https://github.com/jsr107/jsr107spec)

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
