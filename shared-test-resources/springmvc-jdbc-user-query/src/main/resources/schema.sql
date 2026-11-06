CREATE TABLE app_users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(200) NOT NULL,
    age INTEGER NOT NULL,
    active BOOLEAN NOT NULL
);
