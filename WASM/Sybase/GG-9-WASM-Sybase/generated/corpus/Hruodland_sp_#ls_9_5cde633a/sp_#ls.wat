(module
  (import "ignite" "condition_0" (func $condition_0 (param i32 i32 i32 i32 i32 i32) (result i32)))
  (import "ignite" "print_1" (func $print_1))
  (import "ignite" "condition_2" (func $condition_2 (param i32) (result i32)))
  (import "ignite" "result_set_3" (func $result_set_3 (result i32)))
  (import "ignite" "condition_4" (func $condition_4 (param i32) (result i32)))
  (import "ignite" "set_variable_5" (func $set_variable_5 (result i32)))
  (import "ignite" "result_set_6" (func $result_set_6 (param i32 i32) (result i32)))
  (func (export "sp_#ls") (param $objname i32) (param $schema i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    i32.const 0
    local.set $@@rowcount
    local.get $objname
    local.get $objname
    local.get $objname
    local.get $objname
    local.get $objname
    local.get $objname
    call $condition_0
    i32.const 0
    local.set $@@rowcount
    if
    call $print_1
    i32.const 0
    local.set $@@rowcount
    i32.const 1
    return
    end
    local.get $objname
    call $condition_2
    i32.const 0
    local.set $@@rowcount
    if
    call $result_set_3
    local.set $@@rowcount
    i32.const 0
    return
    end
    local.get $objname
    call $condition_4
    i32.const 0
    local.set $@@rowcount
    if
    call $set_variable_5
    local.set $objname
    i32.const 0
    local.set $@@rowcount
    end
    local.get $schema
    local.get $objname
    call $result_set_6
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
