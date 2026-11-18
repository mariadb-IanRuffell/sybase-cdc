(module
  (import "ignite" "set_variable_0" (func $set_variable_0 (result i32)))
  (import "ignite" "set_variable_1" (func $set_variable_1 (result i32)))
  (import "ignite" "create_temp_2" (func $create_temp_2))
  (import "ignite" "condition_3" (func $condition_3 (param i32) (result i32)))
  (import "ignite" "insert_4" (func $insert_4 (param i32 i32) (result i32)))
  (import "ignite" "set_variable_5" (func $set_variable_5 (param i32) (result i32)))
  (import "ignite" "condition_6" (func $condition_6 (param i32) (result i32)))
  (import "ignite" "result_set_7" (func $result_set_7 (result i32)))
  (import "ignite" "condition_8" (func $condition_8 (param i32) (result i32)))
  (import "ignite" "result_set_9" (func $result_set_9 (result i32)))
  (import "ignite" "condition_10" (func $condition_10 (param i32) (result i32)))
  (import "ignite" "result_set_11" (func $result_set_11 (result i32)))
  (import "ignite" "condition_12" (func $condition_12 (param i32) (result i32)))
  (import "ignite" "result_set_13" (func $result_set_13 (result i32)))
  (import "ignite" "drop_temp_14" (func $drop_temp_14))
  (func (export "large-results") (param $numberofrecords i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $index i32)
    (local $text i32)
    call $set_variable_0
    local.set $index
    i32.const 0
    local.set $@@rowcount
    call $set_variable_1
    local.set $text
    i32.const 0
    local.set $@@rowcount
    call $create_temp_2
    i32.const 0
    local.set $@@rowcount
    block $done0
    loop $loop0
    local.get $index
    call $condition_3
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    local.get $index
    local.get $text
    call $insert_4
    local.set $@@rowcount
    local.get $index
    call $set_variable_5
    local.set $index
    i32.const 0
    local.set $@@rowcount
    br $loop0
    end
    end
    local.get $numberofrecords
    call $condition_6
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_7
    local.set $@@rowcount
    end
    local.get $numberofrecords
    call $condition_8
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_9
    local.set $@@rowcount
    end
    local.get $numberofrecords
    call $condition_10
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_11
    local.set $@@rowcount
    end
    local.get $numberofrecords
    call $condition_12
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_13
    local.set $@@rowcount
    end
    call $drop_temp_14
    i32.const 0
    local.set $@@rowcount
    i32.const 0
  )
)
