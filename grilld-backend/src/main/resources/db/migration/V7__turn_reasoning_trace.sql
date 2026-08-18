alter table turns add column intent varchar(32);
alter table turns add column assistant_message text;
alter table turns add column reasoning_summary text;
alter table turns add column reasoning_decisions jsonb not null default '[]'::jsonb;
alter table turns add column reasoning_assumptions jsonb not null default '[]'::jsonb;
