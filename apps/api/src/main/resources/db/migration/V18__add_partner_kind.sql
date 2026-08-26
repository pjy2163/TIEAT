ALTER TABLE partner_organizations
    ADD COLUMN partner_kind VARCHAR(16) NOT NULL DEFAULT 'ORGANIZATION';

ALTER TABLE partner_organizations
    ADD CONSTRAINT ck_partner_organizations_partner_kind CHECK (
        partner_kind IN ('INDIVIDUAL', 'ORGANIZATION')
    );

ALTER TABLE store_partner_registrations
    ADD COLUMN partner_kind VARCHAR(16) NOT NULL DEFAULT 'ORGANIZATION';

ALTER TABLE store_partner_registrations
    ADD CONSTRAINT ck_store_partner_registrations_partner_kind CHECK (
        partner_kind IN ('INDIVIDUAL', 'ORGANIZATION')
    );
