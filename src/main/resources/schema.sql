CREATE TABLE IF NOT EXISTS usuarios (
    username VARCHAR(100) PRIMARY KEY,
    senha VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS authorities (
    username VARCHAR(100) NOT NULL,
    authority VARCHAR(50) NOT NULL,
    PRIMARY KEY (username, authority),
    CONSTRAINT fk_authorities_usuarios FOREIGN KEY (username) REFERENCES usuarios(username)
);

CREATE TABLE IF NOT EXISTS login_attempts (
    username VARCHAR(100) PRIMARY KEY,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    last_failed_at TIMESTAMP NULL,
    locked_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
