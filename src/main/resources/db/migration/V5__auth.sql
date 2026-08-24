-- V5 — authentication
--
-- Login accounts. This is the last schema migration; V6 carries seed data.

-- ---------------------------------------------------------------------------
-- app_user
--
-- employee_id is what lets an authenticated request name the person behind it. Without it
-- a login is an anonymous set of privileges: the system could tell an ADMIN from an
-- EMPLOYEE but not which employee, so it could neither fill customer_order.employee_id
-- from the session nor answer "does this user manage this department".
--
-- It is NULLABLE on purpose. A login is not the same thing as a person: a service account
-- or an integration belongs to nobody on the payroll. Making the column NOT NULL would
-- force every account to be a member of staff. The reverse is also allowed — an employee
-- with no account simply has no row here.
--
-- It is UNIQUE so that one person cannot be behind two logins. Without that, revoking
-- somebody's access means hunting for every account that claims to be them.
--
-- password_hash is VARCHAR(60) because a BCrypt digest is exactly 60 characters. Moving to
-- argon2 or scrypt later means widening this column.
--
-- ck_app_user_password_hashed refuses anything that is not shaped like a BCrypt digest.
-- Its job is to make one specific accident impossible: a code path that writes the plain
-- password into this column. A constraint is the right place for that because it holds no
-- matter which code does the writing.
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    user_id       BIGSERIAL   PRIMARY KEY,
    username      VARCHAR(50) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    role          user_role   NOT NULL DEFAULT 'EMPLOYEE',
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    employee_id   BIGINT,

    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_employee UNIQUE (employee_id),
    CONSTRAINT ck_app_user_username_not_blank
        CHECK (length(trim(username)) > 0),
    CONSTRAINT ck_app_user_password_hashed
        CHECK (password_hash ~ '^\$2[aby]\$\d{2}\$.{53}$'),
    CONSTRAINT fk_app_user_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id)
        ON DELETE SET NULL
);

-- No separate index on employee_id: uq_app_user_employee already builds a unique index on
-- that column, and it serves the foreign key lookup just as well.

COMMENT ON COLUMN app_user.password_hash IS
    'BCrypt digest, cost 12. Never a plaintext password — see ck_app_user_password_hashed.';

COMMENT ON COLUMN app_user.enabled IS
    'Disabling an account keeps its history intact; deleting the row would not.';


-- ---------------------------------------------------------------------------
-- v_user_identity
--
-- Everything the login endpoint needs to answer "who is this and what may they do", in
-- one row. This view lives here rather than with the others in V4 because it reads
-- app_user, and V4 runs before this file exists.
--
-- is_manager is DERIVED, not stored. There is no MANAGER value in user_role, because
-- department.manager_id already records who manages what; a second copy in the role column
-- would drift the moment somebody is promoted. Computing it here means promotion stays a
-- single UPDATE and permissions follow on their own.
--
-- One department, not many: an employee can only manage a department they work in
-- (ct_department_manager_membership in V3), and employee.department_id holds exactly one
-- department. So a manager manages precisely the department they belong to, and the single
-- join below is sufficient — there is no second department to find.
--
-- All joins are LEFT because employee_id is nullable: a service account resolves to a row
-- with a username and a role but no person, which is correct rather than missing.
-- ---------------------------------------------------------------------------

CREATE VIEW v_user_identity AS
SELECT u.user_id,
       u.username,
       u.role,
       u.enabled,
       u.employee_id,
       e.full_name,
       e.department_id,
       d.name AS department_name,
       d.type AS department_type,
       COALESCE(d.manager_id = u.employee_id, FALSE) AS is_manager
FROM app_user u
LEFT JOIN employee   e ON e.employee_id   = u.employee_id
LEFT JOIN department d ON d.department_id = e.department_id;
