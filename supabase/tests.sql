-- Self-check of the server rules (ownership, creator rights, outsiders, rename, delete/restore).
-- It creates temporary data and ALWAYS ends with an error so that everything is rolled back:
--   * "ALL SERVER TESTS PASSED (changes rolled back)"  -> all rules work.
--   * "FAIL: ..."                                      -> the named rule is broken.
-- Run it in the SQL Editor after schema.sql (and migration-1.0.15.sql). It does not touch real data.
do $$
declare
  a uuid := '11111111-1111-1111-1111-111111111111';  -- owner
  b uuid := '22222222-2222-2222-2222-222222222222';  -- member
  c uuid := '33333333-3333-3333-3333-333333333333';  -- outsider
  sid uuid; code text; item1 uuid := gen_random_uuid(); item2 uuid := gen_random_uuid();
  r boolean; failed boolean;
begin
  -- owner creates a group and an invite
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'role', 'authenticated')::text, true);
  sid := public.create_space('test group', 'A');
  if not public.is_owner(sid) then raise exception 'FAIL: creator is not owner'; end if;
  code := public.create_invite(sid, 24, 2);

  -- member joins with the code
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'role', 'authenticated')::text, true);
  perform public.join_space(code, 'B');
  if not public.is_member(sid) then raise exception 'FAIL: joined person is not a member'; end if;
  if public.is_owner(sid) then raise exception 'FAIL: joined person became owner'; end if;

  -- outsider can neither write nor see
  perform set_config('request.jwt.claims', json_build_object('sub', c, 'role', 'authenticated')::text, true);
  if public.is_member(sid) then raise exception 'FAIL: outsider counts as member'; end if;
  failed := false;
  begin perform public.upsert_item(gen_random_uuid(), sid, 'x', 1, false); exception when others then failed := true; end;
  if not failed then raise exception 'FAIL: outsider could write an item'; end if;

  -- owner writes item1, member writes item2
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'role', 'authenticated')::text, true);
  r := public.upsert_item(item1, sid, 'owner item', 100, false);
  if not r then raise exception 'FAIL: owner could not create an item'; end if;
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'role', 'authenticated')::text, true);
  r := public.upsert_item(item2, sid, 'member item', 100, false);
  if not r then raise exception 'FAIL: member could not create an item'; end if;

  -- member cannot change the owner's item; can change own; older edit is ignored
  r := public.upsert_item(item1, sid, 'hijack', 200, false);
  if r then raise exception 'FAIL: member changed an item of the owner'; end if;
  r := public.upsert_item(item2, sid, 'member edit', 200, false);
  if not r then raise exception 'FAIL: member could not edit own item'; end if;
  r := public.upsert_item(item2, sid, 'older edit', 150, false);
  if r then raise exception 'FAIL: an older edit replaced a newer one'; end if;

  -- owner may change and delete everything
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'role', 'authenticated')::text, true);
  r := public.upsert_item(item2, sid, 'owner edit', 300, false);
  if not r then raise exception 'FAIL: owner could not edit a member item'; end if;
  r := public.upsert_item(item2, sid, 'gone', 400, true);
  if not r then raise exception 'FAIL: owner could not delete a member item'; end if;

  -- rename: only the owner
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'role', 'authenticated')::text, true);
  failed := false;
  begin perform public.rename_space(sid, 'hacked'); exception when others then failed := true; end;
  if not failed then raise exception 'FAIL: member renamed the group'; end if;
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'role', 'authenticated')::text, true);
  perform public.rename_space(sid, 'new name');
  if (select name from public.spaces where id = sid) <> 'new name' then raise exception 'FAIL: rename did not work'; end if;

  -- delete: only the owner; deleted group hides; owner can restore
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'role', 'authenticated')::text, true);
  failed := false;
  begin perform public.delete_space(sid); exception when others then failed := true; end;
  if not failed then raise exception 'FAIL: member deleted the group'; end if;
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'role', 'authenticated')::text, true);
  perform public.delete_space(sid);
  if public.is_member(sid) then raise exception 'FAIL: deleted group is still usable'; end if;
  perform public.restore_space(sid);
  if not public.is_member(sid) then raise exception 'FAIL: restore did not work'; end if;

  raise exception 'ALL SERVER TESTS PASSED (changes rolled back)';
end $$;
