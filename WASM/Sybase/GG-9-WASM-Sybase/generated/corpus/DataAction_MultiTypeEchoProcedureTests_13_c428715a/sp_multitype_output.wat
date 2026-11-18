(module
  (import "ignite" "set_variable_0" (func $set_variable_0 (result i32)))
  (import "ignite" "set_variable_1" (func $set_variable_1 (param i32) (result i32)))
  (func (export "sp_multitype_output") (param $echochar i32) (param $inbinary i32) (param $echobinary i32) (result i32 i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $set_variable_0
    local.set $echochar
    i32.const 0
    local.set $@@rowcount
    local.get $inbinary
    call $set_variable_1
    local.set $echobinary
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    local.get $echochar
    local.get $echobinary
  )
)
