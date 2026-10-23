-- Demo identities for the fictional company "Halcyon Robotics" (tenant: acme).
-- In production these rows come from SCIM / IdP group sync, not a migration.
INSERT INTO app_user (tenant_id, user_id, email, display_name, title) VALUES
  ('acme', 'alice', 'alice@halcyon.example', 'Alice Chen',     'Staff Engineer, Payments'),
  ('acme', 'bob',   'bob@halcyon.example',   'Bob Martinez',   'Account Executive'),
  ('acme', 'carol', 'carol@halcyon.example', 'Carol Okafor',   'HR Business Partner'),
  ('acme', 'dave',  'dave@halcyon.example',  'Dave Lindqvist', 'Chief Operating Officer'),
  ('acme', 'erin',  'erin@halcyon.example',  'Erin Walsh',     'Contractor, Marketing');

INSERT INTO group_member (tenant_id, group_id, user_id) VALUES
  ('acme', 'engineering', 'alice'),
  ('acme', 'oncall',      'alice'),
  ('acme', 'sales',       'bob'),
  ('acme', 'hr',          'carol'),
  ('acme', 'execs',       'dave'),
  ('acme', 'employees',   'alice'),
  ('acme', 'employees',   'bob'),
  ('acme', 'employees',   'carol'),
  ('acme', 'employees',   'dave');
-- erin is a contractor: no "employees" group, so she sees only org:public documents.
