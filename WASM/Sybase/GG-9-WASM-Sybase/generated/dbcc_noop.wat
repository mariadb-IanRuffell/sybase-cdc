(module
  (import "ignite" "mock_admin_0" (func $mock_admin_0))
  (import "ignite" "mock_admin_1" (func $mock_admin_1))
  (func (export "dbcc_noop") (param $database i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    call $mock_admin_0
    i32.const 0
    local.set $@@rowcount
    call $mock_admin_1
    i32.const 0
    local.set $@@rowcount
    i32.const 0
    return
    i32.const 0
  )
)
