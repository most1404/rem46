-- Diagnostic only (changes nothing). Run it and look at the result:
-- every row must show ok = true. A false row names what is missing.
select 'column spaces.deleted_at' as item,
       exists(select 1 from information_schema.columns where table_schema='public' and table_name='spaces' and column_name='deleted_at') as ok
union all select 'column shared_items.created_by',
       exists(select 1 from information_schema.columns where table_schema='public' and table_name='shared_items' and column_name='created_by')
union all select 'column shared_items.rev',
       exists(select 1 from information_schema.columns where table_schema='public' and table_name='shared_items' and column_name='rev')
union all select 'function ' || f,
       exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname=f)
from unnest(array['create_space','create_invite','revoke_invite','join_space','leave_space','remove_member','delete_space','restore_space','list_deleted_spaces','upsert_item','is_member','is_owner']) as f
union all select 'RLS enabled on ' || t,
       (select relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname=t)
from unnest(array['spaces','space_members','invites','shared_items']) as t;
