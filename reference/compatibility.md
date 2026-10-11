# Upgrade prerequisites

What a target version pulls along. Numbers move with every release — treat the table as the list of things to check, and confirm the current numbers in the linked sources.

## The usual chain

| Target | Requires | Usually covered by |
| --- | --- | --- |
| Spring Boot 3.x | Java 17+, Jakarta EE 10 (`javax` → `jakarta`), Spring Framework 6, Spring Security 6, Hibernate 6 | `UpgradeSpringBoot_3_x` (includes `UpgradeToJava17` and the jakarta migration) |
| Spring Boot 4.0 | Java 17+ (supports up to 25), Spring Framework 7, Spring Security 7, Hibernate 7.1, Jackson 3, Gradle 8.14+ or 9, Kotlin 2.2 if Kotlin is used | `UpgradeSpringBoot_4_0` (chains all 3.x steps, bumps the Gradle wrapper to 8.14+) — **not** a newer Java than 17 |
| Java 21 / 25 | Gradle that runs on that JDK (8.5+ for 21, 9.1+ for 25 — table in `gradle.md`); Maven 3.9+; annotation processors and bytecode tools that know the class file version | `UpgradeToJava21` / `UpgradeToJava25` (Gradle wrapper and common plugins) |

Sources: Spring Boot "System Requirements" page in the reference docs and the migration guides in the `spring-projects/spring-boot` GitHub wiki; Gradle "Compatibility Matrix"; release notes of each tool.

## Tools with JDK limits

Each of these fails on a JDK newer than it knows, often with misleading errors (Lombok: "cannot find symbol" on getters that should be generated). Check every one the project uses; the Java upgrade recipes bump some of them, not all.

| Tool | How it breaks | What to check |
| --- | --- | --- |
| Lombok (pinned version) | annotation processing silently does nothing or crashes; getters/constructors "missing" | version that supports the target JDK; `UpgradeToJava25` bumped 1.18.24 → 1.18.48 in our run |
| MapStruct, other annotation processors | processor errors at compile time | release notes for the target JDK |
| Code generators (openapi-generator, protobuf, jaxb) | generated code uses `javax`, old APIs | a plugin version that supports the target natively (openapi-generator 7.x: `useSpringBoot4`, `useJackson3` for Spring Boot 4; 6.x only has `useSpringBoot3`); the generator's release notes on GitHub; recipes touch neither the version nor the configuration |
| Mockito / ByteBuddy | "Java 25 (69) is not supported" at test time | versions managed by the Spring Boot BOM are fine; pinned ones need a bump |
| Jacoco | instrumentation fails on new class files | plugin version |
| Kotlin | compiler does not know the JDK target | Kotlin version required by the framework |
| Gradle plugins of the project (Spring Boot, dependency-management, generators) | old plugin APIs removed in the new Gradle | plugin versions that support the new Gradle |

## When the target is not reachable

If the framework does not support the requested Java yet, or a dependency has no release for the new stack, say so before migrating and propose the nearest reachable target (for example "Spring Boot 4.0 on Java 21 now, Java 25 after library X releases").
