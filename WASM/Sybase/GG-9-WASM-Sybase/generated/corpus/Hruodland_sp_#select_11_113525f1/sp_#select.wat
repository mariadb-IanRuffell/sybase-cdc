(module
  (import "ignite" "assign_0" (func $assign_0 (param i32 i32 i32) (result i32 i32)))
  (import "ignite" "condition_1" (func $condition_1 (param i32) (result i32)))
  (import "ignite" "print_2" (func $print_2))
  (import "ignite" "create_temp_3" (func $create_temp_3))
  (import "ignite" "insert_4" (func $insert_4 (result i32)))
  (import "ignite" "set_variable_5" (func $set_variable_5 (result i32)))
  (import "ignite" "open_cursor_6" (func $open_cursor_6 (param i32 i32) (result i32)))
  (import "ignite" "fetch_cursor_7" (func $fetch_cursor_7 (param i32) (result i32 i32 i32)))
  (import "ignite" "condition_8" (func $condition_8 (param i32) (result i32)))
  (import "ignite" "set_variable_9" (func $set_variable_9 (param i32) (result i32)))
  (import "ignite" "condition_10" (func $condition_10 (param i32) (result i32)))
  (import "ignite" "set_variable_11" (func $set_variable_11 (param i32) (result i32)))
  (import "ignite" "set_variable_12" (func $set_variable_12 (param i32) (result i32)))
  (import "ignite" "insert_13" (func $insert_13 (param i32 i32) (result i32)))
  (import "ignite" "set_variable_14" (func $set_variable_14 (param i32) (result i32)))
  (import "ignite" "fetch_cursor_15" (func $fetch_cursor_15 (param i32) (result i32 i32 i32)))
  (import "ignite" "close_cursor_16" (func $close_cursor_16))
  (import "ignite" "deallocate_cursor_17" (func $deallocate_cursor_17))
  (import "ignite" "set_variable_18" (func $set_variable_18 (param i32 i32) (result i32)))
  (import "ignite" "insert_19" (func $insert_19 (param i32 i32) (result i32)))
  (import "ignite" "result_set_20" (func $result_set_20 (result i32)))
  (import "ignite" "drop_temp_21" (func $drop_temp_21))
  (func (export "sp_#select") (param $table i32) (param $schema i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $found i32)
    (local $column i32)
    (local $line i32)
    (local $position i32)
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $table
    local.get $found
    call $assign_0
    local.set $@@rowcount
    local.set $found
    local.get $found
    call $condition_1
    i32.const 0
    local.set $@@rowcount
    if
    call $print_2
    i32.const 0
    local.set $@@rowcount
    i32.const 1
    return
    end
    call $create_temp_3
    i32.const 0
    local.set $@@rowcount
    call $insert_4
    local.set $@@rowcount
    call $set_variable_5
    local.set $position
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $table
    call $open_cursor_6
    local.set $@@rowcount
    local.get $column
    call $fetch_cursor_7
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $column
    block $done0
    loop $loop0
    local.get $@@sqlstatus
    call $condition_8
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    local.get $column
    call $set_variable_9
    local.set $line
    i32.const 0
    local.set $@@rowcount
    local.get $position
    call $condition_10
    i32.const 0
    local.set $@@rowcount
    if
    local.get $line
    call $set_variable_11
    local.set $line
    i32.const 0
    local.set $@@rowcount
    else
    local.get $line
    call $set_variable_12
    local.set $line
    i32.const 0
    local.set $@@rowcount
    end
    local.get $position
    local.get $line
    call $insert_13
    local.set $@@rowcount
    local.get $position
    call $set_variable_14
    local.set $position
    i32.const 0
    local.set $@@rowcount
    local.get $column
    call $fetch_cursor_15
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $column
    br $loop0
    end
    end
    call $close_cursor_16
    i32.const 0
    local.set $@@rowcount
    call $deallocate_cursor_17
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $table
    call $set_variable_18
    local.set $line
    i32.const 0
    local.set $@@rowcount
    local.get $position
    local.get $line
    call $insert_19
    local.set $@@rowcount
    call $result_set_20
    local.set $@@rowcount
    call $drop_temp_21
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
