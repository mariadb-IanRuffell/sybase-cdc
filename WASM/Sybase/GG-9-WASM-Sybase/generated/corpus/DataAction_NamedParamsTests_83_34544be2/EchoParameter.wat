(module
  (import "ignite" "result_set_0" (func $result_set_0 (param i32 i32) (result i32)))
  (func (export "EchoParameter") (param $parameter1 i32) (param $parameter2 i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    local.get $parameter1
    local.get $parameter2
    call $result_set_0
    local.set $@@rowcount
    i32.const 0
  )
)
