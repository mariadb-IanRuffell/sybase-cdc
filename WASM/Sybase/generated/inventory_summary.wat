(module
  (import "ignite" "snapshot_0" (func $snapshot_0 (param i32) (result i32)))
  (import "ignite" "result_set_1" (func $result_set_1 (result i32)))
  (func (export "inventory_summary") (param $minimum i32) (param $matches i32) (result i32 i32)
    (local $@@rowcount i32)
    i32.const 0
    local.set $@@rowcount
    local.get $minimum
    call $snapshot_0
    local.set $@@rowcount
    local.get $@@rowcount
    local.set $matches
    i32.const 1
    local.set $@@rowcount
    local.get $matches
    i32.const 0
    i32.eq
    i32.const 0
    local.set $@@rowcount
    if
      i32.const 2
      local.get $matches
      return
    end
    call $result_set_1
    local.set $@@rowcount
    i32.const 0
    local.get $matches
    return
  )
)
