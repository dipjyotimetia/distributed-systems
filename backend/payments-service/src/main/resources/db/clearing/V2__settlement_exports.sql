CREATE SEQUENCE clearing.settlement_sequence MAXVALUE 999999;
CREATE TABLE clearing.settlement_export (
    business_date date PRIMARY KEY,
    sequence integer UNIQUE NOT NULL,
    payload bytea NOT NULL,
    uploaded_at timestamptz
);
