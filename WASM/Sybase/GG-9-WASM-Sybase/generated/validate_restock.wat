(module
  (func (export "validate_restock") (param $units i32) (result i32)
    (local $@@rowcount i32)
    local.get $units
    i32.const 0
    i32.gt_s
    i32.const 0
    local.set $@@rowcount
    if
      i32.const 0
      return
    end
    i32.const 1
    return
  )
)
