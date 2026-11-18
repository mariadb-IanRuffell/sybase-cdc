(module
  (import "ignite" "assign_0" (func $assign_0 (param i32 i32 i32) (result i32 i32 i32)))
  (import "ignite" "condition_1" (func $condition_1 (param i32) (result i32)))
  (import "ignite" "raise_error_2" (func $raise_error_2))
  (import "ignite" "condition_3" (func $condition_3 (param i32) (result i32)))
  (import "ignite" "raise_error_4" (func $raise_error_4))
  (import "ignite" "assign_5" (func $assign_5 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_6" (func $assign_6 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_7" (func $assign_7 (param i32 i32 i32) (result i32 i32)))
  (import "ignite" "begin_transaction_8" (func $begin_transaction_8))
  (import "ignite" "dml_9" (func $dml_9 (param i32) (result i32)))
  (import "ignite" "insert_10" (func $insert_10 (param i32 i32 i32 i32) (result i32 i32)))
  (import "ignite" "insert_11" (func $insert_11 (param i32 i32 i32) (result i32 i32)))
  (import "ignite" "dml_12" (func $dml_12 (param i32 i32) (result i32)))
  (import "ignite" "commit_transaction_13" (func $commit_transaction_13))
  (import "ignite" "execute_procedure_14" (func $execute_procedure_14 (param i32)))
  (func (export "sp_complete_order") (param $order_id i32) (param $payment_method i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $customer_id i32)
    (local $total i32)
    (local $tier i32)
    (local $mult i32)
    (local $points i32)
    local.get $order_id
    local.get $customer_id
    local.get $total
    call $assign_0
    local.set $@@rowcount
    local.set $total
    local.set $customer_id
    local.get $customer_id
    call $condition_1
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_2
    i32.const 0
    local.set $@@rowcount
    i32.const -5
    return
    end
    local.get $total
    call $condition_3
    i32.const 0
    local.set $@@rowcount
    if
    call $raise_error_4
    i32.const 0
    local.set $@@rowcount
    i32.const -7
    return
    end
    local.get $customer_id
    local.get $tier
    call $assign_5
    local.set $@@rowcount
    local.set $tier
    local.get $tier
    local.get $mult
    call $assign_6
    local.set $@@rowcount
    local.set $mult
    local.get $total
    local.get $mult
    local.get $points
    call $assign_7
    local.set $@@rowcount
    local.set $points
    call $begin_transaction_8
    i32.const 0
    local.set $@@rowcount
    local.get $order_id
    call $dml_9
    local.set $@@rowcount
    local.get $order_id
    local.get $payment_method
    local.get $total
    local.get $order_id
    call $insert_10
    local.set $@@identity
    local.set $@@rowcount
    local.get $customer_id
    local.get $order_id
    local.get $points
    call $insert_11
    local.set $@@identity
    local.set $@@rowcount
    local.get $points
    local.get $customer_id
    call $dml_12
    local.set $@@rowcount
    call $commit_transaction_13
    i32.const 0
    local.set $@@rowcount
    local.get $customer_id
    call $execute_procedure_14
    i32.const 0
    return
    i32.const 0
  )
)
