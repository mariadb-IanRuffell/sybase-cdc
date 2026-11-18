(module
  (import "ignite" "set_variable_0" (func $set_variable_0 (param i32) (result i32)))
  (import "ignite" "result_set_1" (func $result_set_1 (param i32) (result i32)))
  (import "ignite" "result_set_2" (func $result_set_2 (param i32) (result i32)))
  (import "ignite" "status_3" (func $status_3 (param i32) (result i32)))
  (func (export "sp_test_echo") (param $nechovalue i32) (param $nechovaluereturn i32) (result i32 i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    local.get $nechovalue
    call $set_variable_0
    local.set $nechovaluereturn
    i32.const 0
    local.set $@@rowcount
    local.get $nechovalue
    call $result_set_1
    local.set $@@rowcount
    local.get $nechovalue
    call $result_set_2
    local.set $@@rowcount
    local.get $nechovalue
    call $status_3
    local.get $nechovaluereturn
    return
    i32.const 0
    local.get $nechovaluereturn
  )
)
