--liquibase formatted sql
--changeset jobhunter:015-outreach-contact-email

ALTER TABLE outreach_contact ADD COLUMN email VARCHAR(255);
ALTER TABLE outreach_contact ADD COLUMN email_confidence VARCHAR(20) DEFAULT 'NONE';

COMMENT ON COLUMN outreach_contact.email IS 'Inferred or verified email address';
COMMENT ON COLUMN outreach_contact.email_confidence IS 'Confidence level: NONE, LOW, MEDIUM, HIGH';
