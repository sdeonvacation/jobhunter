--liquibase formatted sql
--changeset jobhunter:017-outreach-contact-nullable-company

-- Allow outreach_contact to exist without a company (e.g. alumni search results)
ALTER TABLE outreach_contact ALTER COLUMN company_id DROP NOT NULL;

--rollback ALTER TABLE outreach_contact ALTER COLUMN company_id SET NOT NULL;
