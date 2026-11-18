(module
  (import "ignite" "set_variable_0" (func $set_variable_0 (result i32)))
  (func (export "sp_test_echo_text_locator") (param $input i32) (param $output i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $set_variable_0
    local.set $output
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    local.get $output
  )
)
