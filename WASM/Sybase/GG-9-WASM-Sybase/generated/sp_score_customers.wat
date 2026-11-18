(module
  (import "ignite" "condition_0" (func $condition_0 (param i32) (result i32)))
  (import "ignite" "assign_1" (func $assign_1 (param i32) (result i32 i32)))
  (import "ignite" "open_cursor_2" (func $open_cursor_2 (param i32 i32) (result i32)))
  (import "ignite" "fetch_cursor_3" (func $fetch_cursor_3 (param i32) (result i32 i32 i32)))
  (import "ignite" "condition_4" (func $condition_4 (param i32) (result i32)))
  (import "ignite" "assign_5" (func $assign_5 (param i32 i32 i32 i32 i32) (result i32 i32 i32)))
  (import "ignite" "assign_6" (func $assign_6 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_7" (func $assign_7 (param i32 i32) (result i32 i32)))
  (import "ignite" "assign_8" (func $assign_8 (param i32 i32 i32) (result i32 i32)))
  (import "ignite" "assign_9" (func $assign_9 (param i32 i32 i32 i32) (result i32 i32)))
  (import "ignite" "assign_10" (func $assign_10 (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32) (result i32 i32)))
  (import "ignite" "condition_11" (func $condition_11 (param i32) (result i32)))
  (import "ignite" "assign_12" (func $assign_12 (param i32) (result i32 i32)))
  (import "ignite" "assign_13" (func $assign_13 (param i32 i32 i32) (result i32 i32)))
  (import "ignite" "dml_14" (func $dml_14 (param i32 i32 i32 i32) (result i32)))
  (import "ignite" "condition_15" (func $condition_15 (param i32) (result i32)))
  (import "ignite" "insert_16" (func $insert_16 (param i32 i32 i32 i32) (result i32)))
  (import "ignite" "fetch_cursor_17" (func $fetch_cursor_17 (param i32) (result i32 i32 i32)))
  (import "ignite" "close_cursor_18" (func $close_cursor_18))
  (import "ignite" "deallocate_cursor_19" (func $deallocate_cursor_19))
  (import "ignite" "result_set_20" (func $result_set_20 (param i32) (result i32)))
  (func (export "sp_score_customers") (param $as_of i32) (param $customer_id i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $cid i32)
    (local $last_order i32)
    (local $orders_12m i32)
    (local $open_cases i32)
    (local $avg_sent i32)
    (local $recency i32)
    (local $score i32)
    (local $band i32)
    local.get $as_of
    call $condition_0
    i32.const 0
    local.set $@@rowcount
    if
    local.get $as_of
    call $assign_1
    local.set $@@rowcount
    local.set $as_of
    end
    local.get $customer_id
    local.get $customer_id
    call $open_cursor_2
    local.set $@@rowcount
    local.get $cid
    call $fetch_cursor_3
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $cid
    block $done0
    loop $loop0
    local.get $@@sqlstatus
    call $condition_4
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    local.get $as_of
    local.get $cid
    local.get $as_of
    local.get $last_order
    local.get $orders_12m
    call $assign_5
    local.set $@@rowcount
    local.set $orders_12m
    local.set $last_order
    local.get $orders_12m
    local.get $orders_12m
    call $assign_6
    local.set $@@rowcount
    local.set $orders_12m
    local.get $cid
    local.get $open_cases
    call $assign_7
    local.set $@@rowcount
    local.set $open_cases
    local.get $cid
    local.get $as_of
    local.get $avg_sent
    call $assign_8
    local.set $@@rowcount
    local.set $avg_sent
    local.get $last_order
    local.get $last_order
    local.get $as_of
    local.get $recency
    call $assign_9
    local.set $@@rowcount
    local.set $recency
    local.get $recency
    local.get $recency
    local.get $recency
    local.get $orders_12m
    local.get $orders_12m
    local.get $orders_12m
    local.get $open_cases
    local.get $open_cases
    local.get $avg_sent
    local.get $score
    call $assign_10
    local.set $@@rowcount
    local.set $score
    local.get $score
    call $condition_11
    i32.const 0
    local.set $@@rowcount
    if
    local.get $score
    call $assign_12
    local.set $@@rowcount
    local.set $score
    end
    local.get $score
    local.get $score
    local.get $band
    call $assign_13
    local.set $@@rowcount
    local.set $band
    local.get $score
    local.get $band
    local.get $as_of
    local.get $cid
    call $dml_14
    local.set $@@rowcount
    local.get $@@rowcount
    call $condition_15
    i32.const 0
    local.set $@@rowcount
    if
    local.get $cid
    local.get $score
    local.get $band
    local.get $as_of
    call $insert_16
    local.set $@@rowcount
    end
    local.get $cid
    call $fetch_cursor_17
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $cid
    br $loop0
    end
    end
    call $close_cursor_18
    i32.const 0
    local.set $@@rowcount
    call $deallocate_cursor_19
    i32.const 0
    local.set $@@rowcount
    local.get $as_of
    call $result_set_20
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
