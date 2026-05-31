# Firefly Framework - Cache - JCache

[![CI](https://github.com/fireflyframework/fireflyframework-cache-jcache/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-cache-jcache/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> JCache (JSR-107) cache provider adapter for the Firefly cache abstraction, discovered via the ServiceLoader SPI.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

Firefly Framework Cache JCache plugs a JSR-107 (JCache) backend into the unified Firefly cache abstraction provided by `fireflyframework-cache`. It contributes a `JCacheProvider` that is discovered at runtime via the Java `ServiceLoader` SPI (`META-INF/services/org.fireflyframework.cache.spi.CacheProviderFactory`) and reports `CacheType.JCACHE`.

The adapter is purely SPI-based: there is no Spring auto-configuration in this module. The host application supplies a JSR-107 `CacheManager` bean (from any compliant provider such as Ehcache, Infinispan, or Hazelcast JCache), which the Firefly cache core passes to the provider context. The provider then builds a `CacheAdapter` backed by that `CacheManager`.

The bundled `JCacheCacheHelper` adapts the `CacheManager` reflectively and supports both the `javax.cache` and `jakarta.cache` namespaces, so it works regardless of which JSR-107 API generation the host uses. A lightweight TTL overlay is applied on top of the JCache store for portable per-entry expiration.

## Features

- `JCacheProvider` discovered via the Firefly cache `CacheProviderFactory` SPI (ServiceLoader)
- Reports `CacheType.JCACHE` with provider priority `30`
- Backed by any host-supplied JSR-107 `CacheManager` bean (Ehcache, Infinispan, Hazelcast JCache, ...)
- Supports both `javax.cache` and `jakarta.cache` namespaces via reflection
- Portable per-entry TTL overlay (no reliance on provider-specific expiry policies)
- Full reactive `CacheAdapter` surface: get, put, putIfAbsent, evict, clear, exists, keys, size, stats, health
- No Spring auto-configuration required — pure SPI wiring

## Requirements

- Java 21+
- Spring Boot 3.x
- Maven 3.9+
- A JSR-107 (JCache) provider on the classpath that exposes a `CacheManager` bean (e.g. Ehcache, Infinispan, Hazelcast JCache)

## Installation

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-cache-jcache</artifactId>
    <version>26.05.07</version>
</dependency>
```

## Quick Start

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

The host application must provide a JSR-107 `CacheManager` bean. Add a compliant JSR-107 provider (for example Ehcache with the `jakarta` classifier) and expose its `CacheManager`:

```java
@Configuration
public class JCacheConfig {

    @Bean
    public javax.cache.CacheManager jCacheManager() {
        return javax.cache.Caching.getCachingProvider().getCacheManager();
    }
}
```

## Configuration

Select JCache as the cache backend via the Firefly cache properties. The host supplies the JSR-107 `CacheManager` bean; this adapter then services the cache through it:

```yaml
firefly:
  cache:
    default-cache-type: JCACHE
```

## Documentation

No additional documentation available for this project.

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
