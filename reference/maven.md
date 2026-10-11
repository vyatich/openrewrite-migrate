# Maven projects

## Run recipes without editing the pom

```
./mvnw -B org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.activeRecipes=<recipe>,<recipe> \
  -Drewrite.recipeArtifactCoordinates=<group:artifact:version>,<group:artifact:version>
```

(`mvn` instead of `./mvnw` if the project has no wrapper.) Use `:dryRun` instead of `:run` to get a patch in `target/rewrite/rewrite.patch` without changing files. Check the output for `BUILD SUCCESS`. Your own `rewrite.yml` in the project root is picked up automatically; another location: `-Drewrite.configLocation=<path>`.

## Which JDK

Maven 3.9 runs on any JDK from 8 to 25, so run it on the target JDK. If the project must still be compiled and tested on its old JDK (an old Spring Boot does not run its tests on a new JVM), use Maven toolchains: `maven-toolchains-plugin` in the pom and the JDKs listed in `~/.m2/toolchains.xml`. After the Java recipe changes `java.version`, the toolchain switches to the new JDK by itself.

## Build and errors

```
./mvnw -B verify
```

- Compile errors: lines `[ERROR] <file>.java:[line,col] error: ...`.
- Test results: `target/surefire-reports/TEST-*.xml`.
- One test class only: `-Dtest=OrderServiceTest`.
- Versions to watch: `pom.xml` (dependencies, `dependencyManagement`, properties, parent).
