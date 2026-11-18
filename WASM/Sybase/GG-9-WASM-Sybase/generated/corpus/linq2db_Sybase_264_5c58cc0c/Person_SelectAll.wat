(module
  (import "ignite" "result_set_0" (func $result_set_0 (result i32)))
  (func (export "Person_SelectAll")  (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $result_set_0
    local.set $@@rowcount
    i32.const 0
  )
)
