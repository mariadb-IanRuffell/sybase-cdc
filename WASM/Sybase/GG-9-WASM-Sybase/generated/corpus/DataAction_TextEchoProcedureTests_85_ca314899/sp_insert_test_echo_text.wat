(module
  (import "ignite" "insert_0" (func $insert_0 (param i32) (result i32)))
  (import "ignite" "set_variable_1" (func $set_variable_1 (result i32)))
  (func (export "sp_insert_test_echo_text") (param $input i32) (param $output i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    local.get $input
    call $insert_0
    local.set $@@rowcount
    call $set_variable_1
    local.set $output
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    local.get $output
  )
)
