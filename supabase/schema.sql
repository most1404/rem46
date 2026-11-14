create extension if not exists pgcrypto;

create table if not exists public.spaces (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 80),
  created_by uuid not null default auth.uid(),
  created_at timestamptz not null default now()
);

create table if not exists public.space_members (
  space_id uuid not null references public.spaces(id) on delete cascade,
  user_id uuid not null,
  role text not null default 'member' check (role in ('owner','member')),
  display_name text not null default '' check (char_length(display_name) <= 60),
  joined_at timestamptz not null default now(),
  primary key (space_id, user_id)
);

create table if not exists public.invites (
  code text primary key,
  space_id uuid not null references public.spaces(id) on delete cascade,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  max_uses int not null default 1 check (max_uses between 1 and 100),
  uses int not null default 0,
  revoked boolean not null default false
);

alter table public.spaces add column if not exists deleted_at timestamptz;

create sequence if not exists public.shared_items_rev_seq;

create table if not exists public.shared_items (
  id uuid primary key,
  space_id uuid not null references public.spaces(id) on delete cascade,
  payload text not null,
  updated_at bigint not null,
  updated_by uuid not null,
  deleted boolean not null default false,
  rev bigint not null default nextval('public.shared_items_rev_seq')
);
alter table public.shared_items add column if not exists created_by uuid;
create index if not exists shared_items_space_rev on public.shared_items(space_id, rev);

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

alter table public.spaces enable row level security;
alter table public.space_members enable row level security;
alter table public.invites enable row level security;
alter table public.shared_items enable row level security;

drop policy if exists spaces_select on public.spaces;
create policy spaces_select on public.spaces for select using (public.is_member(id));

drop policy if exists members_select on public.space_members;
create policy members_select on public.space_members for select using (public.is_member(space_id));

drop policy if exists invites_select on public.invites;
create policy invites_select on public.invites for select using (public.is_owner(space_id));

drop policy if exists items_select on public.shared_items;
create policy items_select on public.shared_items for select using (public.is_member(space_id));

revoke all on public.spaces, public.space_members, public.invites, public.shared_items from anon, authenticated;
grant select on public.spaces, public.space_members, public.invites, public.shared_items to authenticated;

create or replace function public.create_space(p_name text, p_display text)
returns uuid language plpgsql security definer set search_path = public as $$
declare sid uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  insert into spaces(name, created_by) values (trim(p_name), auth.uid()) returning id into sid;
  insert into space_members(space_id, user_id, role, display_name) values (sid, auth.uid(), 'owner', coalesce(trim(p_display), ''));
  return sid;
end $$;

create or replace function public.create_invite(p_space uuid, p_hours int, p_max_uses int)
returns text language plpgsql security definer set search_path = public as $$
declare c text;
begin
  if not public.is_owner(p_space) then raise exception 'forbidden'; end if;
  c := substr(replace(gen_random_uuid()::text, '-', ''), 1, 12);
  insert into invites(code, space_id, created_by, expires_at, max_uses)
  values (c, p_space, auth.uid(), now() + make_interval(hours => greatest(1, least(p_hours, 24*30))), greatest(1, least(p_max_uses, 100)));
  return c;
end $$;

create or replace function public.revoke_invite(p_code text)
returns void language plpgsql security definer set search_path = public as $$
begin
  update invites set revoked = true where code = p_code and public.is_owner(space_id);
end $$;

create or replace function public.join_space(p_code text, p_display text)
returns table(id uuid, name text) language plpgsql security definer set search_path = public as $$
declare inv invites%rowtype;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  select * into inv from invites where code = p_code for update;
  if not found or inv.revoked or inv.expires_at < now() or inv.uses >= inv.max_uses then
    raise exception 'invite_invalid';
  end if;
  if not exists(select 1 from space_members m where m.space_id = inv.space_id and m.user_id = auth.uid()) then
    insert into space_members(space_id, user_id, role, display_name)
    values (inv.space_id, auth.uid(), 'member', coalesce(trim(p_display), ''));
    update invites set uses = uses + 1 where code = p_code;
  end if;
  return query select s.id, s.name from spaces s where s.id = inv.space_id;
end $$;

create or replace function public.leave_space(p_space uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner(p_space) and (select count(*) from space_members where space_id = p_space and role = 'owner') = 1
     and (select count(*) from space_members where space_id = p_space) > 1 then
    raise exception 'owner_must_transfer_or_delete';
  end if;
  delete from space_members where space_id = p_space and user_id = auth.uid();
  if not exists(select 1 from space_members where space_id = p_space) then
    delete from spaces where id = p_space;
  end if;
end $$;

create or replace function public.remove_member(p_space uuid, p_user uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(p_space) then raise exception 'forbidden'; end if;
  if p_user = auth.uid() then raise exception 'use_leave_space'; end if;
  delete from space_members where space_id = p_space and user_id = p_user;
end $$;

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

revoke all on function public.create_space, public.create_invite, public.revoke_invite, public.join_space,
  public.leave_space, public.remove_member, public.delete_space, public.upsert_item,
  public.restore_space, public.list_deleted_spaces from public, anon;
grant execute on function public.create_space, public.create_invite, public.revoke_invite, public.join_space,
  public.leave_space, public.remove_member, public.delete_space, public.upsert_item,
  public.restore_space, public.list_deleted_spaces to authenticated;

create or replace function public.rename_space(p_space uuid, p_name text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(p_space) then raise exception 'forbidden'; end if;
  if char_length(trim(coalesce(p_name, ''))) < 1 or char_length(p_name) > 80 then raise exception 'bad_name'; end if;
  update spaces set name = trim(p_name) where id = p_space;
end $$;

revoke all on function public.rename_space from public, anon;
grant execute on function public.rename_space to authenticated;
