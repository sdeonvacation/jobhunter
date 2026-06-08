--liquibase formatted sql

--changeset jobhunter:006-add-hidden-column
ALTER TABLE job_posting ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;

--rollback ALTER TABLE job_posting DROP COLUMN hidden;
