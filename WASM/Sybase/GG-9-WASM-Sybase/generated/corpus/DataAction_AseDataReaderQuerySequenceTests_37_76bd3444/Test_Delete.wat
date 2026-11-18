(module
  (import "ignite" "dml_0" (func $dml_0 (result i32)))
  (func (export "Test_Delete")  (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $dml_0
    local.set $@@rowcount
    i32.const 0
  )
)
