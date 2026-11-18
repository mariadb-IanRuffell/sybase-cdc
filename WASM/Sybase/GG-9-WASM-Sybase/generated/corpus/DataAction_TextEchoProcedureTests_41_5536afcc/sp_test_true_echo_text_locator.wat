(module
  (import "ignite" "assign_0" (func $assign_0 (param i32 i32) (result i32 i32)))
  (import "ignite" "set_variable_1" (func $set_variable_1 (param i32) (result i32)))
  (func (export "sp_test_true_echo_text_locator") (param $input i32) (param $output i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $scommenttl i32)
    local.get $input
    local.get $scommenttl
    call $assign_0
    local.set $@@rowcount
    local.set $scommenttl
    local.get $input
    call $set_variable_1
    local.set $output
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    local.get $output
  )
)
