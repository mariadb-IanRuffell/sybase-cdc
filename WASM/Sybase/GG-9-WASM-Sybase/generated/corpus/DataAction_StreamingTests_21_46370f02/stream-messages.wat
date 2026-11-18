(module
  (import "ignite" "set_variable_0" (func $set_variable_0 (result i32)))
  (import "ignite" "condition_1" (func $condition_1 (param i32 i32) (result i32)))
  (import "ignite" "print_2" (func $print_2))
  (import "ignite" "set_variable_3" (func $set_variable_3 (param i32) (result i32)))
  (import "ignite" "wait_4" (func $wait_4 (param i32)))
  (func (export "stream-messages") (param $sleeptime i32) (param $numberofloops i32) (result i32)
    (local $@@rowcount i32)
    (local $@@sqlstatus i32)
    (local $@@identity i32)
    (local $index i32)
    call $set_variable_0
    local.set $index
    i32.const 0
    local.set $@@rowcount
    block $done0
    loop $loop0
    local.get $index
    local.get $numberofloops
    call $condition_1
    i32.const 0
    local.set $@@rowcount
    i32.eqz
    br_if $done0
    call $print_2
    i32.const 0
    local.set $@@rowcount
    local.get $index
    call $set_variable_3
    local.set $index
    i32.const 0
    local.set $@@rowcount
    local.get $sleeptime
    call $wait_4
    i32.const 0
    local.set $@@rowcount
    br $loop0
    end
    end
    i32.const 0
  )
)
