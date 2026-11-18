(module
  (import "ignite" "condition_0" (func $condition_0 (param i32) (result i32)))
  (import "ignite" "raise_error_1" (func $raise_error_1))
  (import "ignite" "insert_2" (func $insert_2 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_3" (func $assign_3 (param i32 i32) (result i32 i32)))
  (func (export "sp_create_order") (param $customer_id i32) (param $channel i32) (param $order_id i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    local.get $customer_id
    call $condition_0
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_1
    i32.const 0
    local.set $@@rowcount
    i32.const -1
    local.get $order_id
    return
    end
    local.get $customer_id
    local.get $channel
    call $insert_2
    local.set $@@identity
    local.set $@@rowcount
    local.get $@@identity
    local.get $order_id
    call $assign_3
    local.set $@@rowcount
    local.set $order_id
    i32.const 0
    local.get $order_id
    return
    i32.const 0
    local.get $order_id
  )
)
