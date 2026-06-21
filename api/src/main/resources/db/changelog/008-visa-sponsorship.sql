--liquibase formatted sql

--changeset jobhunter:008-visa-sponsorship
ALTER TABLE job_posting ADD COLUMN visa_sponsorship VARCHAR(20);
