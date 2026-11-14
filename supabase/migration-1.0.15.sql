-- Yadavar Shamsi 1.0.15: lets a group owner rename the group. Safe to run more than once.

create or replace function public.rename_space(p_space uuid, p_name text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(p_space) then raise exception 'forbidden'; end if;
  if char_length(trim(coalesce(p_name, ''))) < 1 or char_length(p_name) > 80 then raise exception 'bad_name'; end if;
  update spaces set name = trim(p_name) where id = p_space;
end $$;

revoke all on function public.rename_space from public, anon;
grant execute on function public.rename_space to authenticated;
