FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /app/target/javalearn-1.0.0.jar app.jar
EXPOSE 10000
CMD ["sh", "-c", "java --add-exports=jdk.jdi/com.sun.jdi=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.jdi.connect=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.jdi.event=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.jdi.request=ALL-UNNAMED -Dserver.port=${PORT:-10000} -jar app.jar"]
