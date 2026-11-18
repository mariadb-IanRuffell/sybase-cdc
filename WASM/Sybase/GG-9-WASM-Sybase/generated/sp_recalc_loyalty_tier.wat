(module
  (import "ignite" "assign_0" (func $assign_0 (param i32 i32) (result i32 i32)))
  (import "ignite" "condition_1" (func $condition_1 (param i32) (result i32)))
  (import "ignite" "raise_error_2" (func $raise_error_2))
  (import "ignite" "assign_3" (func $assign_3 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_4" (func $assign_4 (param i32 i32) (result i32 i32)))
  (import "ignite" "condition_5" (func $condition_5 (param i32 i32) (result i32)))
  (import "ignite" "dml_6" (func $dml_6 (param i32 i32) (result i32)))
  (func (export "sp_recalc_loyalty_tier") (param $customer_id i32) (param $new_tier i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $spend i32)
    (local $old_tier i32)
    local.get $customer_id
    local.get $old_tier
    call $assign_0
    local.set $@@rowcount
    local.set $old_tier
    local.get $old_tier
    call $condition_1
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_2
    i32.const 0
    local.set $@@rowcount
    i32.const -1
    local.get $new_tier
    return
    end
    local.get $customer_id
    local.get $spend
    call $assign_3
    local.set $@@rowcount
    local.set $spend
    local.get $spend
    local.get $new_tier
    call $assign_4
    local.set $@@rowcount
    local.set $new_tier
    local.get $new_tier
    local.get $old_tier
    call $condition_5
    i32.const 0
    local.set $@@rowcount
    if
    local.get $new_tier
    local.get $customer_id
    call $dml_6
    local.set $@@rowcount
    end
    i32.const 0
    local.get $new_tier
    return
    i32.const 0
    local.get $new_tier
  )
)
