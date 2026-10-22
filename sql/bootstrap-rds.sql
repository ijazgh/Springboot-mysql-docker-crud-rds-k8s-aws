-- Run ONCE using the RDS administrator through a private network connection.
-- Replace the password locally. Never commit the edited version.
CREATE DATABASE IF NOT EXISTS javatechie;
CREATE USER 'springapp'@'%' IDENTIFIED BY 'REPLACE_WITH_UNIQUE_STRONG_PASSWORD' REQUIRE SSL;
GRANT ALL PRIVILEGES ON javatechie.* TO 'springapp'@'%';
-- Schema-only permissions allow Hibernate to create/update learning tables.
-- For production, use reviewed migrations and a narrower runtime user instead.
