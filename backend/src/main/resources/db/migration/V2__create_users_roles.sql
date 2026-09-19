-- V2: authentication & RBAC foundation — users, roles, user_roles.
--
-- users holds local password credentials (BCrypt hashes; never plaintext).
-- roles is a closed set (USER, ADMIN) enforced by CHECK, seeded below.
-- user_roles links users to roles; the owning side is mapped as a
-- unidirectional @ManyToMany from the User entity (no join entity).
--
-- Dataset.owner_subject stays an opaque TEXT column in this step; the
-- convention owner_subject = users.id (JWT subject) is adopted without
-- adding a foreign key yet.
--
-- Conventions from V1 apply: UUID keys via gen_random_uuid(), plural
-- snake_case tables, TEXT + CHECK instead of enums, explicitly named
-- constraints (pk_ / uq_ / chk_ / fk_) and indexes (idx_).

CREATE TABLE users (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    display_name  TEXT        NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_not_blank CHECK (char_length(btrim(email)) > 0)
);

COMMENT ON TABLE users IS 'Local credentials; passwords stored as BCrypt hashes only.';
COMMENT ON COLUMN users.email IS 'Lowercase-normalized by the application; unique login identifier.';

CREATE TABLE roles (
    id   UUID NOT NULL DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name),
    CONSTRAINT chk_roles_name CHECK (name IN ('USER', 'ADMIN'))
);

COMMENT ON TABLE roles IS 'Closed role set for RBAC; mapped to ROLE_<name> authorities.';

INSERT INTO roles (name) VALUES ('USER'), ('ADMIN') ON CONFLICT DO NOTHING;

CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
);

COMMENT ON TABLE user_roles IS 'User-to-role assignments; removing a user removes its assignments, roles themselves are protected.';

-- Reverse lookup ("all users with role X").
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);
