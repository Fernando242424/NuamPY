FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
COPY lib ./lib
RUN mkdir out && javac -encoding UTF-8 -cp lib/postgresql.jar -d out $(find src -name '*.java')

FROM eclipse-temurin:17-jre
# pg_dump/pg_restore 17 (compatibles con Neon) para los respaldos
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo $VERSION_CODENAME)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update && apt-get install -y --no-install-recommends postgresql-client-17 \
 && apt-get purge -y curl gnupg && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/out ./out
COPY lib ./lib
COPY web ./web
COPY sql ./sql
RUN mkdir -p backups uploads
ENV PGSSLMODE=require NUAM_SECURE_COOKIES=1
EXPOSE 8080
CMD ["java", "-cp", "out:lib/postgresql.jar", "cl.nuam.Main"]
