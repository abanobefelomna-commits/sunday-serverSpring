CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DO $$ BEGIN
    CREATE TYPE users_role_enum AS ENUM ('admin', 'servant', 'priest');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE users_grades_enum AS ENUM ('first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'general');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE classes_grade_enum AS ENUM ('first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'general');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE servants_role_enum AS ENUM ('خادم', 'خادم رئيسي', 'مشرف', 'خادم فقرة عامة', 'قائد فصل', 'مسؤول', 'أب كاهن');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE children_gender_enum AS ENUM ('male', 'female');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE attendance_status_enum AS ENUM ('present', 'absent', 'late', 'excused');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE visitations_type_enum AS ENUM ('home_visit', 'phone_call', 'church_meeting', 'other');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    CREATE TYPE visitations_status_enum AS ENUM ('planned', 'completed', 'cancelled');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TYPE users_grades_enum ADD VALUE IF NOT EXISTS 'general';
ALTER TYPE classes_grade_enum ADD VALUE IF NOT EXISTS 'general';
ALTER TYPE servants_role_enum ADD VALUE IF NOT EXISTS 'خادم فقرة عامة';
ALTER TYPE servants_role_enum ADD VALUE IF NOT EXISTS 'قائد فصل';
ALTER TYPE servants_role_enum ADD VALUE IF NOT EXISTS 'مسؤول';
ALTER TYPE servants_role_enum ADD VALUE IF NOT EXISTS 'أب كاهن';

CREATE TABLE IF NOT EXISTS users (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    name varchar NOT NULL,
    email varchar NOT NULL UNIQUE,
    "emailVerified" boolean NOT NULL DEFAULT true,
    phone varchar NOT NULL UNIQUE,
    password varchar NOT NULL,
    role users_role_enum NOT NULL DEFAULT 'servant',
    grades users_grades_enum[] NOT NULL DEFAULT '{}',
    avatar varchar,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    "tokenHash" varchar NOT NULL UNIQUE,
    "userId" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    "expiresAt" timestamp NOT NULL,
    "revokedAt" timestamptz,
    "createdAt" timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS classes (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    name varchar NOT NULL,
    grade classes_grade_enum NOT NULL,
    "academicYear" varchar NOT NULL DEFAULT '',
    description varchar,
    image varchar,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);
ALTER TABLE classes ADD COLUMN IF NOT EXISTS "academicYear" varchar NOT NULL DEFAULT '';
ALTER TABLE classes ADD COLUMN IF NOT EXISTS description varchar;
ALTER TABLE classes ADD COLUMN IF NOT EXISTS image varchar;

CREATE TABLE IF NOT EXISTS servants (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    name varchar NOT NULL,
    phone varchar NOT NULL UNIQUE,
    email varchar UNIQUE,
    role servants_role_enum NOT NULL DEFAULT 'خادم',
    avatar varchar,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS servants_classes (
    "servantsId" uuid NOT NULL REFERENCES servants(id) ON DELETE CASCADE ON UPDATE CASCADE,
    "classesId" uuid NOT NULL REFERENCES classes(id) ON DELETE CASCADE ON UPDATE CASCADE,
    PRIMARY KEY ("servantsId", "classesId")
);
CREATE INDEX IF NOT EXISTS "IDX_servants_classes_servant" ON servants_classes ("servantsId");
CREATE INDEX IF NOT EXISTS "IDX_servants_classes_class" ON servants_classes ("classesId");

CREATE TABLE IF NOT EXISTS children (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    name varchar NOT NULL,
    "birthDate" date NOT NULL,
    gender children_gender_enum NOT NULL,
    address varchar,
    "parentName" varchar,
    "parentPhone" varchar,
    notes text,
    "classId" uuid REFERENCES classes(id) ON DELETE SET NULL,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);
ALTER TABLE children ADD COLUMN IF NOT EXISTS "parentPhone" varchar;
ALTER TABLE children ADD COLUMN IF NOT EXISTS "parentName" varchar;
ALTER TABLE children ADD COLUMN IF NOT EXISTS address varchar;
ALTER TABLE children ADD COLUMN IF NOT EXISTS notes text;
ALTER TABLE children ADD COLUMN IF NOT EXISTS "classId" uuid REFERENCES classes(id) ON DELETE SET NULL;
ALTER TABLE children ALTER COLUMN "parentPhone" DROP NOT NULL;

CREATE TABLE IF NOT EXISTS attendance (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    "childId" uuid NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    "classId" uuid NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    date date NOT NULL,
    status attendance_status_enum NOT NULL DEFAULT 'present',
    notes text,
    "recordedBy" uuid,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now(),
    UNIQUE ("childId", "classId", date)
);
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS "recordedBy" uuid;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS notes text;
ALTER TABLE attendance ADD COLUMN IF NOT EXISTS "updatedAt" timestamp NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS visitations (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    "childId" uuid NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    "servantId" uuid REFERENCES servants(id) ON DELETE SET NULL,
    date date NOT NULL,
    type visitations_type_enum NOT NULL DEFAULT 'home_visit',
    status visitations_status_enum NOT NULL DEFAULT 'completed',
    notes text,
    "followUpDate" date,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS news (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    title varchar,
    content text NOT NULL,
    image varchar,
    "isPinned" boolean NOT NULL DEFAULT false,
    "authorName" varchar,
    "authorRole" varchar,
    "authorId" uuid REFERENCES users(id) ON DELETE SET NULL,
    "createdAt" timestamp NOT NULL DEFAULT now(),
    "updatedAt" timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auth_otp_codes (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    "userId" uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose varchar(40) NOT NULL,
    "codeHash" varchar(64) NOT NULL,
    "expiresAt" timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    "consumedAt" timestamptz,
    "createdAt" timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS "IDX_auth_otp_user_purpose"
    ON auth_otp_codes ("userId", purpose);
