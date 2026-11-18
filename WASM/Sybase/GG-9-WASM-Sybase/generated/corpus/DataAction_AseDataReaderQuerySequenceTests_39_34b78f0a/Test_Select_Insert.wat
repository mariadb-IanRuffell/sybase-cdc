(module
  (import "ignite" "result_set_0" (func $result_set_0 (result i32)))
  (import "ignite" "insert_1" (func $insert_1 (result i32)))
  (func (export "Test_Select_Insert")  (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $result_set_0
    local.set $@@rowcount
    call $insert_1
    local.set $@@rowcount
    i32.const 0
  )
)
