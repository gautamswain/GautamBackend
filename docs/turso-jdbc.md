# Turso JDBC setup

The libSQL JDBC artifact is hosted on GitHub Packages. Gradle reads package credentials from either
the `gpr.user` and `gpr.key` Gradle properties or the `GITHUB_ACTOR` and `GITHUB_TOKEN`
environment variables. The token needs the `read:packages` scope. Keep credentials in your local
Gradle configuration or environment, not in this repository.

For example, add these entries to `%USERPROFILE%\.gradle\gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_TOKEN
```

At runtime, set `TURSO_DATABASE_URL` to the database URL and `TURSO_AUTH_TOKEN` to the database
token. The service converts `libsql://` URLs to HTTPS JDBC URLs.
