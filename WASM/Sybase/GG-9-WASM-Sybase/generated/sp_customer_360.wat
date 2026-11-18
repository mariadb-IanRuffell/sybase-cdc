(module
  (import "ignite" "condition_0" (func $condition_0 (param i32) (result i32)))
  (import "ignite" "raise_error_1" (func $raise_error_1))
  (import "ignite" "result_set_2" (func $result_set_2 (param i32) (result i32)))
  (import "ignite" "result_set_3" (func $result_set_3 (param i32) (result i32)))
  (import "ignite" "result_set_4" (func $result_set_4 (param i32) (result i32)))
  (import "ignite" "result_set_5" (func $result_set_5 (param i32) (result i32)))
  (import "ignite" "result_set_6" (func $result_set_6 (param i32) (result i32)))
  (import "ignite" "assign_7" (func $assign_7 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_8" (func $assign_8 (param i32 i32) (result i32 i32)))
  (import "ignite" "result_set_9" (func $result_set_9 (param i32 i32 i32 i32 i32) (result i32)))
  (func (export "sp_customer_360") (param $customer_id i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $spend_12m i32)
    (local $next_threshold i32)
    local.get $customer_id
    call $condition_0
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_1
    i32.const 0
    local.set $@@rowcount
    i32.const -1
    return
    end
    local.get $customer_id
    call $result_set_2
    local.set $@@rowcount
    local.get $customer_id
    call $result_set_3
    local.set $@@rowcount
    local.get $customer_id
    call $result_set_4
    local.set $@@rowcount
    local.get $customer_id
    call $result_set_5
    local.set $@@rowcount
    local.get $customer_id
    call $result_set_6
    local.set $@@rowcount
    local.get $customer_id
    local.get $spend_12m
    call $assign_7
    local.set $@@rowcount
    local.set $spend_12m
    local.get $spend_12m
    local.get $next_threshold
    call $assign_8
    local.set $@@rowcount
    local.set $next_threshold
    local.get $spend_12m
    local.get $next_threshold
    local.get $next_threshold
    local.get $spend_12m
    local.get $customer_id
    call $result_set_9
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
