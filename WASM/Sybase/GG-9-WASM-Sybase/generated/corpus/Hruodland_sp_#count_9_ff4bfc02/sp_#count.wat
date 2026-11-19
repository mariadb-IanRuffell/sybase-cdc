(module
  (import "ignite" "open_cursor_0" (func $open_cursor_0 (param i32 i32) (result i32)))
  (import "ignite" "create_temp_1" (func $create_temp_1))
  (import "ignite" "fetch_cursor_2" (func $fetch_cursor_2 (param i32) (result i32 i32 i32)))
  (import "ignite" "metadata_count_3" (func $metadata_count_3 (param i32 i32) (result i32)))
  (import "ignite" "insert_4" (func $insert_4 (param i32 i32 i32) (result i32)))
  (import "ignite" "fetch_cursor_5" (func $fetch_cursor_5 (param i32) (result i32 i32 i32)))
  (import "ignite" "close_cursor_6" (func $close_cursor_6))
  (import "ignite" "deallocate_cursor_7" (func $deallocate_cursor_7))
  (import "ignite" "print_8" (func $print_8))
  (import "ignite" "result_set_9" (func $result_set_9 (result i32)))
  (import "ignite" "drop_temp_10" (func $drop_temp_10))
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
    i32.const 0
    i32.eq
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    local.get $schema
    local.get $table
    call $metadata_count_3
    local.set $rows
    i32.const 0
    local.set $@@rowcount
    local.get $schema
    local.get $table
    local.get $rows
    call $insert_4
    local.set $@@rowcount
    local.get $table
    call $fetch_cursor_5
    local.set $@@rowcount
    local.set $@@sqlstatus
    local.set $table
    br $loop0
    end
    end
    call $close_cursor_6
    i32.const 0
    local.set $@@rowcount
    call $deallocate_cursor_7
    i32.const 0
    local.set $@@rowcount
    call $print_8
    i32.const 0
    local.set $@@rowcount
    call $result_set_9
    local.set $@@rowcount
    call $drop_temp_10
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
