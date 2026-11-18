(module
  (import "ignite" "insert_0" (func $insert_0 (result i32)))
  (import "ignite" "dml_1" (func $dml_1 (result i32)))
  (func (export "Test_Insert_Delete")  (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $insert_0
    local.set $@@rowcount
    call $dml_1
    local.set $@@rowcount
    i32.const 0
  )
)
