# Build stage: Gradle and JDK versions match gradle-wrapper.properties and the Java toolchain.
FROM gradle:9.7.0-jdk25 AS build
WORKDIR /src
COPY . .
RUN gradle build --no-daemon \
 && cp build/libs/stargate-plugin-*.jar /stargate-plugin.jar

# Runtime stage: a tiny image that only holds the plugin jar.
# On start, it copies the jar into /plugins (mount the server's plugins/ folder there), then exits.
FROM busybox:stable
COPY --from=build /stargate-plugin.jar /plugin/stargate-plugin.jar
VOLUME /plugins
CMD ["sh", "-c", "cp -f /plugin/stargate-plugin.jar /plugins/stargate-plugin.jar && echo 'stargate-plugin.jar copied to /plugins'"]
