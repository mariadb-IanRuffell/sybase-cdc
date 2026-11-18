(module
  (import "ignite" "condition_0" (func $condition_0 (param i32) (result i32)))
  (import "ignite" "raise_error_1" (func $raise_error_1))
  (import "ignite" "condition_2" (func $condition_2 (param i32) (result i32)))
  (import "ignite" "raise_error_3" (func $raise_error_3))
  (import "ignite" "assign_4" (func $assign_4 (param i32 i32) (result i32 i32)))
  (import "ignite" "condition_5" (func $condition_5 (param i32) (result i32)))
  (import "ignite" "raise_error_6" (func $raise_error_6))
  (import "ignite" "begin_transaction_7" (func $begin_transaction_7))
  (import "ignite" "dml_8" (func $dml_8 (param i32 i32 i32) (result i32)))
  (import "ignite" "condition_9" (func $condition_9 (param i32) (result i32)))
  (import "ignite" "rollback_transaction_10" (func $rollback_transaction_10))
  (import "ignite" "raise_error_11" (func $raise_error_11))
  (import "ignite" "insert_12" (func $insert_12 (param i32 i32 i32 i32 i32 i32) (result i32 i32)))
  (import "ignite" "dml_13" (func $dml_13 (param i32 i32 i32) (result i32)))
  (import "ignite" "commit_transaction_14" (func $commit_transaction_14))
  (func (export "sp_add_order_item") (param $order_id i32) (param $product_id i32) (param $qty i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $price i32)
    local.get $qty
    call $condition_0
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_1
    i32.const 0
    local.set $@@rowcount
    i32.const -4
    return
    end
    local.get $order_id
    call $condition_2
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_3
    i32.const 0
    local.set $@@rowcount
    i32.const -5
    return
    end
    local.get $product_id
    local.get $price
    call $assign_4
    local.set $@@rowcount
    local.set $price
    local.get $price
    call $condition_5
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_6
    i32.const 0
    local.set $@@rowcount
    i32.const -6
    return
    end
    call $begin_transaction_7
    i32.const 0
    local.set $@@rowcount
    local.get $qty
    local.get $product_id
    local.get $qty
    call $dml_8
    local.set $@@rowcount
    local.get $@@rowcount
    call $condition_9
    i32.const 0
    local.set $@@rowcount
    if
    call $rollback_transaction_10
    i32.const 0
    local.set $@@rowcount
    call $raise_error_11
    i32.const 0
    local.set $@@rowcount
    i32.const -3
    return
    end
    local.get $order_id
    local.get $product_id
    local.get $qty
    local.get $price
    local.get $price
    local.get $qty
    call $insert_12
    local.set $@@identity
    local.set $@@rowcount
    local.get $price
    local.get $qty
    local.get $order_id
    call $dml_13
    local.set $@@rowcount
    call $commit_transaction_14
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
