(module
  (import "ignite" "raise_error_0" (func $raise_error_0))
  (import "ignite" "status_1" (func $status_1 (result i32)))
  (func (export "sp_test_raiseerror")  (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $raise_error_0
    i32.const 0
    local.set $@@rowcount
    call $status_1
    return
    i32.const 0
  )
)
