-- =============================================================================================
-- Executed ONCE by the official mysql image, on the FIRST start with an empty data volume
-- (files in /docker-entrypoint-initdb.d run in alphabetical order). To re-run it you must wipe
-- the volume:  scripts/stop.sh --volumes   (or: docker compose down -v)
--
-- The app user itself (MYSQL_USER / MYSQL_PASSWORD from .env) is created by the image's
-- entrypoint before this script runs; MYSQL_DATABASE is intentionally not used because we need
-- one schema per service ("database per service" pattern: no service reads another's tables).
--
-- Tables are NOT created here: each service owns its schema through Flyway migrations
-- (src/main/resources/db/migration) and Hibernate only validates it.
-- =============================================================================================

CREATE DATABASE IF NOT EXISTS philips_product      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS philips_inventory    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS philips_cart         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS philips_order        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS philips_payment      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS philips_notification CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Grants for the app user are applied by the one-shot "mysql-init" compose service
-- (docker/mysql/grant-app-user.sh), because plain .sql files cannot read environment variables
-- (MYSQL_USER is configurable in .env) and because initdb scripts run only on an empty volume.
