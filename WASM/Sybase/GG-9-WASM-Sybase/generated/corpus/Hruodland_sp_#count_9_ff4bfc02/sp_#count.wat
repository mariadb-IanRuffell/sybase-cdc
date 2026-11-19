(module
  (import "ignite" "open_cursor_0" (func $open_cursor_0 (param i32 i32) (result i32)))
  (import "ignite" "create_temp_1" (func $create_temp_1))
  (import "ignite" "fetch_cursor_2" (func $fetch_cursor_2 (param i32) (result i32 i32 i32)))
  (import "ignite" "condition_3" (func $condition_3 (param i32) (result i32)))
  (import "ignite" "metadata_count_4" (func $metadata_count_4 (param i32 i32) (result i32)))
  (import "ignite" "insert_5" (func $insert_5 (param i32 i32 i32) (result i32)))
  (import "ignite" "fetch_cursor_6" (func $fetch_cursor_6 (param i32) (result i32 i32 i32)))
  (import "ignite" "close_cursor_7" (func $close_cursor_7))
  (import "ignite" "deallocate_cursor_8" (func $deallocate_cursor_8))
  (import "ignite" "print_9" (func $print_9))
  (import "ignite" "result_set_10" (func $result_set_10 (result i32)))
  (import "ignite" "drop_temp_11" (func $drop_temp_11))
  (func (export "sp_#count") (param $schema i32) (param $objname i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $table i32)
    (local $rows i32)
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $objname
    call $open_cursor_0
    local.set $@@rowcount
    call $create_temp_1
    i32.const 0
    local.set $@@rowcount
    local.get $table
    call $fetch_cursor_2
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $table
    block $done0
    loop $loop0
    local.get $@@sqlstatus
    call $condition_3
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    local.get $schema
    local.get $table
    call $metadata_count_4
    local.set $rows
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $table
    local.get $rows
    call $insert_5
    local.set $@@rowcount
    local.get $table
    call $fetch_cursor_6
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $table
    br $loop0
    end
    end
    call $close_cursor_7
    i32.const 0
    local.set $@@rowcount
    call $deallocate_cursor_8
    i32.const 0
    local.set $@@rowcount
    call $print_9
    i32.const 0
    local.set $@@rowcount
    call $result_set_10
    local.set $@@rowcount
    call $drop_temp_11
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
