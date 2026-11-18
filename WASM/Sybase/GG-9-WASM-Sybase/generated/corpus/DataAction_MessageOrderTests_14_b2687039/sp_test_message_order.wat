(module
  (import "ignite" "print_0" (func $print_0))
  (import "ignite" "print_1" (func $print_1))
  (import "ignite" "condition_2" (func $condition_2 (param i32) (result i32)))
  (import "ignite" "result_set_3" (func $result_set_3 (result i32)))
  (import "ignite" "print_4" (func $print_4))
  (import "ignite" "condition_5" (func $condition_5 (param i32) (result i32)))
  (import "ignite" "result_set_6" (func $result_set_6 (result i32)))
  (import "ignite" "print_7" (func $print_7))
  (import "ignite" "condition_8" (func $condition_8 (param i32) (result i32)))
  (import "ignite" "result_set_9" (func $result_set_9 (result i32)))
  (import "ignite" "print_10" (func $print_10))
  (import "ignite" "assign_11" (func $assign_11 (param i32) (result i32 i32)))
  (func (export "sp_test_message_order") (param $runselect i32) (param $status i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $print_0
    i32.const 0
    local.set $@@rowcount
    call $print_1
    i32.const 0
    local.set $@@rowcount
    local.get $runselect
    call $condition_2
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_3
    local.set $@@rowcount
    end
    call $print_4
    i32.const 0
    local.set $@@rowcount
    local.get $runselect
    call $condition_5
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_6
    local.set $@@rowcount
    end
    call $print_7
    i32.const 0
    local.set $@@rowcount
    local.get $runselect
    call $condition_8
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_9
    local.set $@@rowcount
    end
    call $print_10
    i32.const 0
    local.set $@@rowcount
    local.get $status
    call $assign_11
    local.set $@@rowcount
    local.set $status
    i32.const 0
    local.get $status
  )
)
