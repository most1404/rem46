-- Yadavar Shamsi 1.0.11 migration: run on top of the 1.0.6 schema. Safe to run more than once.
-- Supabase: SQL Editor -> New query -> paste all -> Run.

alter table public.spaces add column if not exists deleted_at timestamptz;
alter table public.shared_items add column if not exists created_by uuid;

create or replace function public.is_member(p_space uuid)
returns boolean language sql security definer stable set search_path = public as $$
  select exists(
    select 1 from public.space_members m join public.spaces s on s.id = m.space_id
    where m.space_id = p_space and m.user_id = auth.uid() and s.deleted_at is null);
$$;

create or replace function public.is_owner(p_space uuid)
returns boolean language sql security definer stable set search_path = public as $$
  select exists(
    select 1 from public.space_members m join public.spaces s on s.id = m.space_id
    where m.space_id = p_space and m.user_id = auth.uid() and m.role = 'owner' and s.deleted_at is null);
$$;

create or replace function public.delete_space(p_space uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(p_space) then raise exception 'forbidden'; end if;
  update spaces set deleted_at = now() where id = p_space;
end $$;

create or replace function public.restore_space(p_space uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from space_members where space_id = p_space and user_id = auth.uid() and role = 'owner') then
    raise exception 'forbidden';
  end if;
  update spaces set deleted_at = null where id = p_space and deleted_at is not null;
end $$;

create or replace function public.list_deleted_spaces()
returns table(id uuid, name text, deleted_at timestamptz) language plpgsql security definer set search_path = public as $$
begin
  delete from spaces s where s.deleted_at is not null and s.deleted_at < now() - interval '30 days';
  return query
    select s.id, s.name, s.deleted_at from spaces s
    join space_members m on m.space_id = s.id and m.user_id = auth.uid() and m.role = 'owner'
    where s.deleted_at is not null order by s.deleted_at desc;
end $$;

create or replace function public.upsert_item(p_id uuid, p_space uuid, p_payload text, p_updated_at bigint, p_deleted boolean)
returns boolean language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if not public.is_member(p_space) then raise exception 'forbidden'; end if;
  if char_length(p_payload) > 20000 then raise exception 'too_large'; end if;
  insert into shared_items(id, space_id, payload, updated_at, updated_by, deleted, created_by)
  values (p_id, p_space, p_payload, p_updated_at, auth.uid(), p_deleted, auth.uid())
  on conflict (id) do update
    set payload = excluded.payload, updated_at = excluded.updated_at, updated_by = excluded.updated_by,
        deleted = excluded.deleted, rev = nextval('public.shared_items_rev_seq')
    where shared_items.space_id = excluded.space_id
      and shared_items.updated_at < excluded.updated_at
      and (shared_items.created_by is null or shared_items.created_by = auth.uid() or public.is_owner(p_space));
  get diagnostics n = row_count;
  return n > 0;
end $$;

revoke all on function public.delete_space, public.upsert_item, public.restore_space, public.list_deleted_spaces from public, anon;
grant execute on function public.delete_space, public.upsert_item, public.restore_space, public.list_deleted_spaces to authenticated;
